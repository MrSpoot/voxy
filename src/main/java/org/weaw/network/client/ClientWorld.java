package org.weaw.network.client;

import org.joml.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weaw.game.Chunk;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.ChunkMeshData;
import org.weaw.game.ChunkMesher;
import org.weaw.game.World;
import org.weaw.game.WorldHeightRange;
import org.weaw.game.WorldSettings;
import org.weaw.game.WorldMemoryBudget;
import org.weaw.game.generation.GenerationConfig;
import org.weaw.game.generation.NoiseWorldGenerator;
import org.weaw.game.utils.BlockCatalog;
import org.weaw.game.utils.BlockDefinition;
import org.weaw.network.protocol.ServerMessage;

import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Client-only world replica. The server supplies voxels; this class only meshes them. */
public final class ClientWorld implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClientWorld.class);
    private static final int MAX_COMPLETED_MESHES_PER_FRAME = 4;

    private final World world;
    private final ExecutorService meshingExecutor;
    private final Map<ChunkPosition, Long> revisions = new ConcurrentHashMap<>();
    private final Set<ChunkPosition> scheduled = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPosition> dirtyMeshes = new LinkedHashSet<>();
    private final ConcurrentLinkedQueue<MeshResult> completed = new ConcurrentLinkedQueue<>();
    private final int maxConcurrentMeshes;

    public ClientWorld(BlockCatalog catalog, long worldSeed, int minChunkY, int maxChunkY, int renderDistance) {
        WorldSettings settings = new WorldSettings(
                renderDistance,
                new WorldHeightRange(minChunkY, maxChunkY),
                WorldMemoryBudget.balanced(),
                false
        );
        world = new World(
                new NoiseWorldGenerator(GenerationConfig.defaults().withSeed(worldSeed)),
                settings,
                catalog
        );
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
            Long currentRevision = revisions.get(result.position);
            if (currentRevision != null && currentRevision == result.revision) {
                world.getChunkManager().publishRemeshedChunk(result.position, result.meshData);
            } else if (currentRevision != null) {
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
            Long revision = revisions.get(position);
            if (revision != null) {
                scheduleMesh(position, revision);
            }
        }
    }

    public int pendingMeshCount() {
        return dirtyMeshes.size() + scheduled.size() + completed.size();
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
        scheduleMeshAndNeighbors(snapshot.position());
    }

    private void applyBlock(ServerMessage.BlockUpdate update) {
        ChunkPosition position = toChunkPosition(update.x(), update.y(), update.z());
        Long current = revisions.get(position);
        if (current == null || update.revision() < current || !world.getChunkManager().hasChunk(position)) {
            return;
        }
        BlockDefinition block = world.getBlockCatalog().getBlock(update.blockId());
        if (block == null) {
            return;
        }
        world.getChunkManager().setBlockAtWorld(update.x(), update.y(), update.z(), block);
        revisions.put(position, update.revision());
        scheduleMeshAndNeighbors(position);
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
    }

    private void unload(ChunkPosition position) {
        revisions.remove(position);
        scheduled.remove(position);
        dirtyMeshes.remove(position);
        world.getChunkManager().unloadChunk(position);
        forEachNeighbor(position, this::scheduleCurrentRevision);
    }

    private void scheduleMeshAndNeighbors(ChunkPosition position) {
        scheduleCurrentRevision(position);
        forEachNeighbor(position, this::scheduleCurrentRevision);
    }

    private void scheduleCurrentRevision(ChunkPosition position) {
        if (revisions.containsKey(position)) {
            dirtyMeshes.add(position);
        }
    }

    private void scheduleMesh(ChunkPosition position, long revision) {
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
                completed.offer(new MeshResult(position, revision, meshData));
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

    @Override
    public void close() {
        meshingExecutor.shutdownNow();
        world.close();
    }

    private record MeshResult(ChunkPosition position, long revision, ChunkMeshData meshData) {
    }

}
