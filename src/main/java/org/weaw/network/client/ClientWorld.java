package org.weaw.network.client;

import org.joml.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weaw.game.Chunk;
import org.weaw.game.ChunkPosition;
import org.weaw.game.ChunkMeshData;
import org.weaw.game.ChunkMesher;
import org.weaw.game.World;
import org.weaw.game.WorldHeightRange;
import org.weaw.game.WorldLightingSystem;
import org.weaw.game.WorldSettings;
import org.weaw.game.WorldMemoryBudget;
import org.weaw.game.generation.GenerationConfig;
import org.weaw.game.generation.NoiseWorldGenerator;
import org.weaw.game.utils.BlockCatalog;
import org.weaw.game.utils.BlockDefinition;
import org.weaw.gameplay.BlockAction;
import org.weaw.network.protocol.ServerMessage;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Client replica with authoritative snapshots plus predicted blocks, lighting and meshes. */
public final class ClientWorld implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientWorld.class);
    private static final int MAX_COMPLETED_MESHES_PER_FRAME = 4;

    private final World world;
    private final WorldLightingSystem localLighting;
    private final ExecutorService meshingExecutor;
    private final Map<ChunkPosition, Long> revisions = new ConcurrentHashMap<>();
    private final Map<ChunkPosition, Long> snapshotRevisions = new ConcurrentHashMap<>();
    private final Map<BlockPosition, Long> blockRevisions = new HashMap<>();
    private final Map<ChunkPosition, Long> meshVersions = new ConcurrentHashMap<>();
    private final Map<Long, PendingBlockPrediction> predictionsBySequence = new LinkedHashMap<>();
    private final Map<BlockPosition, PredictedBlockState> predictedBlocks = new HashMap<>();
    private final Set<ChunkPosition> scheduled = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPosition> dirtyMeshes = new LinkedHashSet<>();
    private final ConcurrentLinkedQueue<MeshResult> completed = new ConcurrentLinkedQueue<>();
    private final int maxConcurrentMeshes;

    public ClientWorld(BlockCatalog catalog, long worldSeed, int minChunkY, int maxChunkY, int renderDistance) {
        WorldHeightRange heightRange = new WorldHeightRange(minChunkY, maxChunkY);
        WorldSettings settings = new WorldSettings(
                renderDistance,
                heightRange,
                WorldMemoryBudget.balanced(),
                false
        );
        world = new World(
                new NoiseWorldGenerator(GenerationConfig.defaults().withSeed(worldSeed), catalog),
                settings,
                catalog
        );
        localLighting = new WorldLightingSystem(catalog, world, heightRange);
        world.setDynamicLightingEnabled(false);
        world.setRemeshEnabled(false);
        world.setUnloadsEnabled(false);
        maxConcurrentMeshes = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 2));
        meshingExecutor = Executors.newFixedThreadPool(maxConcurrentMeshes, runnable -> {
            Thread thread = new Thread(runnable, "voxy-client-mesher");
            thread.setDaemon(true);
            return thread;
        });
    }

    public World world() {
        return world;
    }

    public void apply(ServerMessage message) {
        switch (message) {
            case ServerMessage.ChunkSnapshot snapshot -> applyChunk(snapshot);
            case ServerMessage.ChunkUnload unload -> unload(unload.position());
            case ServerMessage.BlockUpdate block -> applyBlock(block);
            case ServerMessage.BlockActionResult result -> applyBlockActionResult(result);
            case ServerMessage.ChunkLightUpdate light -> applyLight(light);
            default -> {
                // Non-world messages are handled by NetworkClientSession.
            }
        }
    }

    public void update() {
        MeshResult result;
        int published = 0;
        while (published < MAX_COMPLETED_MESHES_PER_FRAME && (result = completed.poll()) != null) {
            scheduled.remove(result.position);
            Long currentVersion = meshVersions.get(result.position);
            if (currentVersion != null && currentVersion == result.version) {
                world.getChunkManager().publishRemeshedChunk(result.position, result.meshData);
            } else if (currentVersion != null) {
                dirtyMeshes.add(result.position);
            }
            published++;
        }

        Iterator<ChunkPosition> iterator = dirtyMeshes.iterator();
        while (scheduled.size() < maxConcurrentMeshes && iterator.hasNext()) {
            ChunkPosition position = iterator.next();
            if (scheduled.contains(position)) {
                continue;
            }
            iterator.remove();
            Long version = meshVersions.get(position);
            if (version != null) {
                scheduleMesh(position, version);
            }
        }
    }

    public int pendingMeshCount() {
        return dirtyMeshes.size() + scheduled.size() + completed.size();
    }

    public int pendingBlockPredictionCount() {
        return predictionsBySequence.size();
    }

    public boolean predictBlock(long sequence, BlockAction action) {
        if (action == null || predictionsBySequence.containsKey(sequence)) {
            return false;
        }
        BlockPosition blockPosition = new BlockPosition(action.x(), action.y(), action.z());
        ChunkPosition chunkPosition = blockPosition.chunkPosition();
        if (!world.getChunkManager().hasChunk(chunkPosition)) {
            return false;
        }
        BlockDefinition replacement = world.getBlockCatalog().getBlock(action.replacementBlockId());
        if (replacement == null) {
            return false;
        }

        PredictedBlockState state = predictedBlocks.computeIfAbsent(blockPosition, ignored ->
                new PredictedBlockState(
                        world.getBlockAtWorld(action.x(), action.y(), action.z()),
                        revisions.getOrDefault(chunkPosition, 0L)
                )
        );
        PendingBlockPrediction prediction = new PendingBlockPrediction(sequence, blockPosition, action.replacementBlockId());
        state.pending.addLast(prediction);
        predictionsBySequence.put(sequence, prediction);
        setVisibleBlock(blockPosition, action.replacementBlockId());
        return true;
    }

    PredictionReplay beginPredictionReplay(long acknowledgedSequence) {
        return new PredictionReplay(acknowledgedSequence);
    }

    private void applyChunk(ServerMessage.ChunkSnapshot snapshot) {
        Long current = revisions.get(snapshot.position());
        if (current != null && snapshot.revision() < current) {
            return;
        }
        Chunk chunk = new Chunk(
                new Vector3i(snapshot.position().x(), snapshot.position().y(), snapshot.position().z()),
                world.getBlockCatalog()
        );
        chunk.setAllBlocks(snapshot.blocks());
        chunk.getLighting().replacePackedIntArray(snapshot.packedLight());
        chunk.replacePackedDirectSkyLight(snapshot.packedDirectSkyLight());
        world.getChunkManager().publishBuiltChunk(chunk, ChunkMeshData.empty());
        revisions.put(snapshot.position(), snapshot.revision());
        snapshotRevisions.put(snapshot.position(), snapshot.revision());
        blockRevisions.keySet().removeIf(position -> position.chunkPosition().equals(snapshot.position()));
        for (Map.Entry<BlockPosition, PredictedBlockState> entry : predictedBlocks.entrySet()) {
            if (!entry.getKey().chunkPosition().equals(snapshot.position())) {
                continue;
            }
            PredictedBlockState state = entry.getValue();
            if (snapshot.revision() >= state.authoritativeRevision) {
                state.authoritativeBlockId = world.getBlockAtWorld(
                        entry.getKey().x, entry.getKey().y, entry.getKey().z
                );
                state.authoritativeRevision = snapshot.revision();
            }
            PendingBlockPrediction latest = state.pending.peekLast();
            if (latest != null) {
                writeVisibleBlock(entry.getKey(), latest.replacementBlockId);
            }
        }
        invalidateMeshAndNeighbors(snapshot.position());
        recalculatePredictedLighting(snapshot.position());
    }

    private void applyBlock(ServerMessage.BlockUpdate update) {
        ChunkPosition position = toChunkPosition(update.x(), update.y(), update.z());
        Long current = revisions.get(position);
        BlockPosition blockPosition = new BlockPosition(update.x(), update.y(), update.z());
        long minimumRevision = blockRevisions.getOrDefault(
                blockPosition,
                snapshotRevisions.getOrDefault(position, 0L)
        );
        if (current == null || update.revision() < minimumRevision || !world.getChunkManager().hasChunk(position)) {
            return;
        }
        if (world.getBlockCatalog().getBlock(update.blockId()) == null) {
            return;
        }
        revisions.merge(position, update.revision(), Math::max);
        blockRevisions.put(blockPosition, update.revision());
        PredictedBlockState state = predictedBlocks.get(blockPosition);
        if (state != null) {
            if (update.revision() >= state.authoritativeRevision) {
                state.authoritativeBlockId = update.blockId();
                state.authoritativeRevision = update.revision();
            }
            PendingBlockPrediction latest = state.pending.peekLast();
            if (latest != null) {
                setVisibleBlock(blockPosition, latest.replacementBlockId);
            }
        } else {
            setVisibleBlock(blockPosition, update.blockId());
        }
    }

    private void applyBlockActionResult(ServerMessage.BlockActionResult result) {
        PendingBlockPrediction prediction = predictionsBySequence.remove(result.sequence());
        if (prediction == null) {
            return;
        }
        PredictedBlockState state = predictedBlocks.get(prediction.position);
        if (state == null) {
            return;
        }
        state.pending.remove(prediction);
        if (result.revision() >= state.authoritativeRevision) {
            state.authoritativeBlockId = result.authoritativeBlockId();
            state.authoritativeRevision = result.revision();
            blockRevisions.merge(prediction.position, result.revision(), Math::max);
            revisions.merge(prediction.position.chunkPosition(), result.revision(), Math::max);
        }
        PendingBlockPrediction latest = state.pending.peekLast();
        if (latest == null) {
            predictedBlocks.remove(prediction.position);
            setVisibleBlock(prediction.position, state.authoritativeBlockId);
        } else {
            setVisibleBlock(prediction.position, latest.replacementBlockId);
        }
    }

    private void applyLight(ServerMessage.ChunkLightUpdate update) {
        Long current = revisions.get(update.position());
        if (current == null || update.revision() < current) {
            return;
        }
        world.getChunkManager().replaceChunkLighting(
                update.position(),
                update.packedLight(),
                update.packedDirectSkyLight()
        );
        invalidateMeshAndNeighbors(update.position());
        recalculatePredictedLighting(update.position());
    }

    private void unload(ChunkPosition position) {
        revisions.remove(position);
        snapshotRevisions.remove(position);
        blockRevisions.keySet().removeIf(blockPosition -> blockPosition.chunkPosition().equals(position));
        meshVersions.remove(position);
        scheduled.remove(position);
        dirtyMeshes.remove(position);
        predictedBlocks.entrySet().removeIf(entry -> {
            if (!entry.getKey().chunkPosition().equals(position)) {
                return false;
            }
            for (PendingBlockPrediction prediction : entry.getValue().pending) {
                predictionsBySequence.remove(prediction.sequence);
            }
            return true;
        });
        world.getChunkManager().unloadChunk(position);
        forEachNeighbor(position, neighbor -> {
            if (revisions.containsKey(neighbor)) {
                meshVersions.merge(neighbor, 1L, Long::sum);
                scheduleCurrentRevision(neighbor);
            }
        });
    }

    private void scheduleMeshAndNeighbors(ChunkPosition position) {
        scheduleCurrentRevision(position);
        forEachNeighbor(position, this::scheduleCurrentRevision);
    }

    private void scheduleCurrentRevision(ChunkPosition position) {
        if (meshVersions.containsKey(position)) {
            dirtyMeshes.add(position);
        }
    }

    private void scheduleMesh(ChunkPosition position, long version) {
        if (!scheduled.add(position)) {
            return;
        }
        Chunk chunk = world.getChunkManager().copyChunkForMeshing(position);
        if (chunk == null) {
            scheduled.remove(position);
            return;
        }
        meshingExecutor.execute(() -> {
            try {
                ChunkMeshData meshData = ChunkMesher.buildMeshData(chunk, world);
                completed.offer(new MeshResult(position, version, meshData));
            } catch (RuntimeException exception) {
                scheduled.remove(position);
                LOGGER.error("Client mesh failed for {}", position, exception);
            }
        });
    }

    private static void forEachNeighbor(ChunkPosition center, java.util.function.Consumer<ChunkPosition> consumer) {
        consumer.accept(new ChunkPosition(center.x() - 1, center.y(), center.z()));
        consumer.accept(new ChunkPosition(center.x() + 1, center.y(), center.z()));
        consumer.accept(new ChunkPosition(center.x(), center.y() - 1, center.z()));
        consumer.accept(new ChunkPosition(center.x(), center.y() + 1, center.z()));
        consumer.accept(new ChunkPosition(center.x(), center.y(), center.z() - 1));
        consumer.accept(new ChunkPosition(center.x(), center.y(), center.z() + 1));
    }

    private static ChunkPosition toChunkPosition(int x, int y, int z) {
        return new ChunkPosition(
                Math.floorDiv(x, Chunk.SIZE),
                Math.floorDiv(y, Chunk.SIZE),
                Math.floorDiv(z, Chunk.SIZE)
        );
    }

    private void setVisibleBlock(BlockPosition position, short blockId) {
        if (writeVisibleBlock(position, blockId)) {
            invalidateMeshAndNeighbors(position.chunkPosition());
            recalculateLighting(Set.of(position));
        }
    }

    private void recalculatePredictedLighting(ChunkPosition replacedLightChunk) {
        if (!hasPredictionInfluenceNear(replacedLightChunk)) {
            return;
        }
        recalculateLighting(predictionsBySequence.values().stream()
                .map(PendingBlockPrediction::position)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)));
    }

    private boolean hasPredictionInfluenceNear(ChunkPosition position) {
        for (PendingBlockPrediction prediction : predictionsBySequence.values()) {
            ChunkPosition predictedChunk = prediction.position.chunkPosition();
            int dx = Math.abs(predictedChunk.x() - position.x());
            int dy = Math.abs(predictedChunk.y() - position.y());
            int dz = Math.abs(predictedChunk.z() - position.z());
            if (dx <= WorldLightingSystem.LIGHT_CHUNK_RADIUS
                    && dz <= WorldLightingSystem.LIGHT_CHUNK_RADIUS
                    && (dy <= WorldLightingSystem.LIGHT_CHUNK_RADIUS || (dx == 0 && dz == 0))) {
                return true;
            }
        }
        return false;
    }

    private void recalculateLighting(Set<BlockPosition> changedBlocks) {
        if (changedBlocks.isEmpty()) {
            return;
        }
        boolean queued = false;
        for (BlockPosition position : changedBlocks) {
            if (!world.getChunkManager().hasChunk(position.chunkPosition())) {
                continue;
            }
            localLighting.enqueueBlockChange(position.x, position.y, position.z);
            queued = true;
        }
        if (!queued) {
            return;
        }
        WorldLightingSystem.WorldLightingUpdateResult result = localLighting.processPriority(world.getChunkManager());
        for (ChunkPosition position : result.changedPositions()) {
            invalidateMeshAndNeighbors(position);
        }
    }

    private void invalidateMeshAndNeighbors(ChunkPosition position) {
        meshVersions.merge(position, 1L, Long::sum);
        forEachNeighbor(position, neighbor -> {
            if (revisions.containsKey(neighbor)) {
                meshVersions.merge(neighbor, 1L, Long::sum);
            }
        });
        scheduleMeshAndNeighbors(position);
    }

    private boolean writeVisibleBlock(BlockPosition position, short blockId) {
        BlockDefinition block = world.getBlockCatalog().getBlock(blockId);
        if (block == null || !world.getChunkManager().hasChunk(position.chunkPosition())) {
            return false;
        }
        if (world.getBlockAtWorld(position.x, position.y, position.z) == blockId) {
            return false;
        }
        world.getChunkManager().setBlockAtWorld(position.x, position.y, position.z, block);
        return true;
    }

    final class PredictionReplay implements AutoCloseable {
        private final long acknowledgedSequence;
        private final Set<BlockPosition> rewoundPositions = new LinkedHashSet<>();
        private boolean closed;

        private PredictionReplay(long acknowledgedSequence) {
            this.acknowledgedSequence = acknowledgedSequence;
            for (Map.Entry<BlockPosition, PredictedBlockState> entry : predictedBlocks.entrySet()) {
                short replayBase = entry.getValue().authoritativeBlockId;
                boolean hasUnacknowledgedPrediction = false;
                for (PendingBlockPrediction prediction : entry.getValue().pending) {
                    if (prediction.sequence <= acknowledgedSequence) {
                        replayBase = prediction.replacementBlockId;
                    } else {
                        hasUnacknowledgedPrediction = true;
                    }
                }
                if (hasUnacknowledgedPrediction) {
                    writeVisibleBlock(entry.getKey(), replayBase);
                    rewoundPositions.add(entry.getKey());
                }
            }
        }

        void apply(long sequence) {
            if (closed || sequence <= acknowledgedSequence) {
                return;
            }
            PendingBlockPrediction prediction = predictionsBySequence.get(sequence);
            if (prediction != null) {
                writeVisibleBlock(prediction.position, prediction.replacementBlockId);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            for (BlockPosition position : rewoundPositions) {
                PredictedBlockState state = predictedBlocks.get(position);
                if (state == null) {
                    continue;
                }
                PendingBlockPrediction latest = state.pending.peekLast();
                writeVisibleBlock(position, latest == null ? state.authoritativeBlockId : latest.replacementBlockId);
            }
        }
    }

    @Override
    public void close() {
        meshingExecutor.shutdownNow();
        world.close();
    }

    private record MeshResult(ChunkPosition position, long version, ChunkMeshData meshData) {
    }

    private record BlockPosition(int x, int y, int z) {
        private ChunkPosition chunkPosition() {
            return toChunkPosition(x, y, z);
        }
    }

    private record PendingBlockPrediction(long sequence, BlockPosition position, short replacementBlockId) {
    }

    private static final class PredictedBlockState {
        private short authoritativeBlockId;
        private long authoritativeRevision;
        private final ArrayDeque<PendingBlockPrediction> pending = new ArrayDeque<>();

        private PredictedBlockState(short authoritativeBlockId, long authoritativeRevision) {
            this.authoritativeBlockId = authoritativeBlockId;
            this.authoritativeRevision = authoritativeRevision;
        }
    }

}
