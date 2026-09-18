package org.weaw.game;

import org.joml.Vector3f;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.generation.GenerationConfig;
import org.weaw.game.generation.NoiseWorldGenerator;
import org.weaw.game.generation.WorldGenerator;
import org.weaw.game.utils.BlockDefinition;
import org.weaw.game.utils.BlockCatalog;
import org.weaw.game.utils.BlockRegistry;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

public class World implements AutoCloseable, WorldBlockProvider {
    private final ChunkManager chunkManager;
    private final BlockCatalog blockCatalog;
    private final WorldStreamer worldStreamer;
    private final WorldGenerator worldGenerator;
    private final WorldGenerator baseWorldGenerator;
    private final Map<ChunkPosition, Map<Integer, Short>> sessionEdits = new ConcurrentHashMap<>();
    private final WorldSettings settings;
    private final WorldLightingSystem lightingSystem;
    private volatile boolean dynamicLightingEnabled = !Boolean.getBoolean("voxy.disableDynamicLighting");
    private long synchronizedLightingUploadsVersion;
    private volatile WorldProfilingSnapshot lastProfilingSnapshot = WorldProfilingSnapshot.empty();
    private final ConcurrentLinkedQueue<WorldBlockChange> blockChanges = new ConcurrentLinkedQueue<>();

    public World() {
        this(new NoiseWorldGenerator(GenerationConfig.defaults()));
    }

    public World(WorldGenerator worldGenerator) {
        this(worldGenerator, new WorldSettings());
    }

    public World(WorldGenerator worldGenerator, WorldSettings settings) {
        this(worldGenerator, settings, BlockRegistry.getDefaultCatalog());
    }

    public World(WorldGenerator worldGenerator, WorldSettings settings, BlockCatalog blockCatalog) {
        this.blockCatalog = Objects.requireNonNull(blockCatalog, "blockCatalog");
        this.chunkManager = new ChunkManager(blockCatalog);
        this.baseWorldGenerator = Objects.requireNonNull(worldGenerator, "worldGenerator");
        this.worldGenerator = new SessionWorldGenerator(baseWorldGenerator);
        this.settings = Objects.requireNonNull(settings, "settings");
        this.worldStreamer = new WorldStreamer(chunkManager, this, this.worldGenerator, settings);
        this.lightingSystem = new WorldLightingSystem(
                blockCatalog,
                this,
                settings.getHeightRange(),
                worldStreamer::executeAuxiliaryTask
        );
    }

    public ChunkManager getChunkManager() {
        return chunkManager;
    }

    public WorldSettings getSettings() {
        return settings;
    }

    public BlockCatalog getBlockCatalog() {
        return blockCatalog;
    }

    public void update(Vector3f playerPosition) {
        update(List.of(playerPosition));
    }

    public void update(Collection<Vector3f> playerPositions) {
        long worldUpdateStartNs = System.nanoTime();

        long worldStreamerStartNs = System.nanoTime();
        worldStreamer.update(playerPositions);
        long worldStreamerCpuTimeNs = System.nanoTime() - worldStreamerStartNs;

        LightingCollectionProfilingSnapshot lightingCollectionSnapshot = LightingCollectionProfilingSnapshot.empty();
        long lightingCollectionCpuTimeNs = 0L;
        if (dynamicLightingEnabled) {
            long lightingCollectionStartNs = System.nanoTime();
            lightingCollectionSnapshot = collectChunkLightingUpdates();
            lightingCollectionCpuTimeNs = System.nanoTime() - lightingCollectionStartNs;

        }

        WorldStreamerProfilingSnapshot streamerSnapshot = worldStreamer.getLastProfilingSnapshot();
        WorldLightingProfilingSnapshot lightingSnapshot = WorldLightingProfilingSnapshot.empty();
        lastProfilingSnapshot = new WorldProfilingSnapshot(
                System.nanoTime() - worldUpdateStartNs,
                worldStreamerCpuTimeNs,
                lightingCollectionCpuTimeNs,
                0L,
                lightingSnapshot.snapshotLoadedChunksCpuTimeNs(),
                lightingSnapshot.clearLightingCpuTimeNs(),
                lightingSnapshot.seedEmittersCpuTimeNs(),
                lightingSnapshot.propagateCpuTimeNs(),
                streamerSnapshot.chunkGenerationCpuTimeNs(),
                streamerSnapshot.chunkMeshCpuTimeNs(),
                streamerSnapshot.chunkMeshingSnapshotCpuTimeNs(),
                streamerSnapshot.chunkMeshingFaceClassificationCpuTimeNs(),
                streamerSnapshot.chunkMeshingGreedyMergeCpuTimeNs(),
                streamerSnapshot.chunkMeshingOutputBuildCpuTimeNs(),
                streamerSnapshot.chunkPublishCpuTimeNs(),
                streamerSnapshot.chunkUnloadCpuTimeNs(),
                lightingCollectionSnapshot.pendingBeforeCollection(),
                lightingCollectionSnapshot.pendingAfterCollection(),
                0,
                lightingSnapshot.affectedChunkCount(),
                lightingSnapshot.expandedChunkCount(),
                lightingSnapshot.loadedChunkCount(),
                lightingSnapshot.loadedTargetChunkCount(),
                0,
                lightingSnapshot.clearedChunkCount(),
                lightingSnapshot.emitterCount(),
                lightingSnapshot.seededNodeCount(),
                lightingSnapshot.propagationNodeCount(),
                lightingSnapshot.lightWriteCount(),
                lightingSnapshot.blockedByOpaqueCount(),
                lightingSnapshot.missingChunkNeighborCount(),
                lightingSnapshot.noGainCount(),
                lightingCollectionSnapshot.fullSnapshotCount(),
                lightingCollectionSnapshot.deltaCount(),
                0,
                0,
                0,
                0,
                streamerSnapshot.loadedChunks(),
                streamerSnapshot.queuedTasks(),
                streamerSnapshot.pendingRemesh(),
                streamerSnapshot.pendingUploads(),
                streamerSnapshot.pendingUnloads(),
                streamerSnapshot.chunksPublished(),
                streamerSnapshot.chunksUnloaded(),
                streamerSnapshot.chunksGenerated(),
                streamerSnapshot.chunksMeshed(),
                streamerSnapshot.chunksRemeshed(),
                streamerSnapshot.chunkMeshingAmbientOcclusionFaces(),
                streamerSnapshot.chunkMeshingSampledBlocks(),
                streamerSnapshot.cancelledChunkBuilds()
        );
        if (dynamicLightingEnabled && lightingSystem.getPendingPriorityChangeCount() > 0) {
            processLightingFrame(false);
        }
    }

    public WorldProfilingSnapshot getLastProfilingSnapshot() {
        return lastProfilingSnapshot;
    }

    public WorldMemorySnapshot getMemorySnapshot() {
        return worldStreamer.getLastMemorySnapshot();
    }

    public void setDynamicLightingEnabled(boolean dynamicLightingEnabled) {
        this.dynamicLightingEnabled = dynamicLightingEnabled;
        lightingSystem.clearPendingWork();
        synchronizedLightingUploadsVersion = dynamicLightingEnabled
                ? Long.MIN_VALUE
                : chunkManager.getChunkUploadsVersion();
    }

    public void setRemeshEnabled(boolean remeshEnabled) {
        worldStreamer.setRemeshEnabled(remeshEnabled);
    }

    public void setMeshGenerationEnabled(boolean meshGenerationEnabled) {
        worldStreamer.setMeshGenerationEnabled(meshGenerationEnabled);
    }

    public void setUnloadsEnabled(boolean unloadsEnabled) {
        worldStreamer.setUnloadsEnabled(unloadsEnabled);
    }

    public int getLoadedChunkCount() {
        return chunkManager.getChunkCount();
    }

    public int getQueuedChunkCount() {
        return worldStreamer.getPendingTaskCount();
    }

    public boolean isStreamingConverged() {
        return worldStreamer.isConverged() && (!dynamicLightingEnabled || !lightingSystem.hasPendingWork());
    }

    public boolean containsChunk(int x, int y, int z) {
        return chunkManager.hasChunk(x, y, z);
    }

    @Override
    public short getBlockAtWorld(int worldX, int worldY, int worldZ) {
        int loadedBlock = chunkManager.getLoadedBlockAtWorld(worldX, worldY, worldZ);
        return loadedBlock >= 0 ? (short) loadedBlock : worldGenerator.getBlockAtWorld(worldX, worldY, worldZ);
    }

    @Override
    public void fillBlockRegion(
            int originX,
            int originY,
            int originZ,
            int sizeX,
            int sizeY,
            int sizeZ,
            short[] destination
    ) {
        worldGenerator.fillBlockRegion(originX, originY, originZ, sizeX, sizeY, sizeZ, destination);
        chunkManager.overlayLoadedBlocks(originX, originY, originZ, sizeX, sizeY, sizeZ, destination);
    }

    @Override
    public int getSkyLightScanStartY(int worldX, int worldZ, int maxWorldY) {
        return worldGenerator.getSkyLightScanStartY(worldX, worldZ, maxWorldY);
    }

    public void setBlockAtWorld(int worldX, int worldY, int worldZ, BlockDefinition block) {
        Objects.requireNonNull(block, "block");
        ChunkPosition position = toChunkPosition(worldX, worldY, worldZ);
        if (!worldStreamer.materializeChunkForEdit(position)) {
            throw new IllegalArgumentException(
                    "Unable to materialize chunk at world position: " + worldX + ", " + worldY + ", " + worldZ
            );
        }
        Chunk editedChunk = chunkManager.getChunk(position.x(), position.y(), position.z());
        if (dynamicLightingEnabled && editedChunk != null) {
            lightingSystem.ensureInitialized(editedChunk);
        }
        chunkManager.setBlockAtWorld(worldX, worldY, worldZ, block);
        rememberSessionEdit(worldX, worldY, worldZ, block.getId());
        blockChanges.offer(new WorldBlockChange(worldX, worldY, worldZ, block.getId()));
        if (dynamicLightingEnabled) {
            lightingSystem.enqueueBlockChange(worldX, worldY, worldZ);
        }
        markChunksDirtyForBlockChange(worldX, worldY, worldZ);
    }

    public short getPackedLightAtWorld(int worldX, int worldY, int worldZ) {
        return chunkManager.getPackedLightAtWorld(worldX, worldY, worldZ);
    }

    public void setPackedLightAtWorld(int worldX, int worldY, int worldZ, short packedLight) {
        chunkManager.setPackedLightAtWorld(worldX, worldY, worldZ, packedLight);
    }

    public void setLightAtWorld(int worldX, int worldY, int worldZ, int red, int green, int blue, int sky) {
        chunkManager.setLightAtWorld(worldX, worldY, worldZ, red, green, blue, sky);
    }

    public boolean trySetBlockAtWorld(int worldX, int worldY, int worldZ, BlockDefinition block) {
        if (!worldStreamer.materializeChunkForEdit(toChunkPosition(worldX, worldY, worldZ))) {
            return false;
        }

        setBlockAtWorld(worldX, worldY, worldZ, block);
        return true;
    }

    public List<WorldBlockChange> drainBlockChanges() {
        List<WorldBlockChange> changes = new ArrayList<>();
        WorldBlockChange change;
        while ((change = blockChanges.poll()) != null) {
            changes.add(change);
        }
        return List.copyOf(changes);
    }

    public boolean isSolidBlockAtWorld(int worldX, int worldY, int worldZ) {
        BlockDefinition block = blockCatalog.getBlock(getBlockAtWorld(worldX, worldY, worldZ));
        return block != null && block.isSolid();
    }

    public boolean containsChunkAtWorld(int worldX, int worldY, int worldZ) {
        ChunkPosition position = toChunkPosition(worldX, worldY, worldZ);
        return chunkManager.hasChunk(position.x(), position.y(), position.z());
    }

    int getPendingRemeshCount() {
        return worldStreamer.getPendingRemeshCount();
    }

    int getPendingPriorityLightingUpdateCount() {
        return lightingSystem.getPendingPriorityChangeCount();
    }

    /** Runs lighting exactly once for the rendered frame, independently of the fixed-rate streamer. */
    public void processLightingFrame() {
        processLightingFrame(true);
    }

    private void processLightingFrame(boolean includeBackground) {
        if (!dynamicLightingEnabled) {
            return;
        }
        long start = System.nanoTime();
        WorldLightingSystem.WorldLightingUpdateResult result = includeBackground
                ? lightingSystem.processFrame(chunkManager)
                : lightingSystem.processPriority(chunkManager);
        long elapsed = System.nanoTime() - start;
        lastProfilingSnapshot = lastProfilingSnapshot.withLighting(
                elapsed,
                result.profilingSnapshot(),
                result.markedChunkCount(),
                lightingSystem.getPendingPriorityChangeCount() + lightingSystem.getPendingBackgroundWorkCount()
        );
    }

    @Override
    public void close() {
        worldStreamer.close();
    }

    public record WorldBlockChange(int x, int y, int z, short blockId) {
        public ChunkPosition chunkPosition() {
            return new ChunkPosition(
                    Math.floorDiv(x, Chunk.SIZE),
                    Math.floorDiv(y, Chunk.SIZE),
                    Math.floorDiv(z, Chunk.SIZE)
            );
        }
    }

    private void rememberSessionEdit(int worldX, int worldY, int worldZ, short blockId) {
        ChunkPosition position = toChunkPosition(worldX, worldY, worldZ);
        int localX = Math.floorMod(worldX, Chunk.SIZE);
        int localY = Math.floorMod(worldY, Chunk.SIZE);
        int localZ = Math.floorMod(worldZ, Chunk.SIZE);
        int index = localX + localZ * Chunk.SIZE + localY * Chunk.SIZE * Chunk.SIZE;
        short generated = baseWorldGenerator.getBlockAtWorld(worldX, worldY, worldZ);
        if (generated == blockId) {
            Map<Integer, Short> edits = sessionEdits.get(position);
            if (edits != null) {
                edits.remove(index);
                if (edits.isEmpty()) {
                    sessionEdits.remove(position, edits);
                }
            }
            return;
        }
        sessionEdits.computeIfAbsent(position, ignored -> new ConcurrentHashMap<>()).put(index, blockId);
    }

    private final class SessionWorldGenerator implements WorldGenerator {
        private final WorldGenerator delegate;

        private SessionWorldGenerator(WorldGenerator delegate) {
            this.delegate = delegate;
        }

        @Override
        public void generateChunkData(Chunk chunk) {
            delegate.generateChunkData(chunk);
            Map<Integer, Short> edits = sessionEdits.get(ChunkPosition.fromChunk(chunk));
            if (edits == null || edits.isEmpty()) {
                return;
            }
            short[] blocks = chunk.snapshotBlocks();
            edits.forEach((index, blockId) -> blocks[index] = blockId);
            chunk.setAllBlocks(blocks);
        }

        @Override
        public short getBlockAtWorld(int worldX, int worldY, int worldZ) {
            ChunkPosition position = toChunkPosition(worldX, worldY, worldZ);
            Map<Integer, Short> edits = sessionEdits.get(position);
            if (edits != null) {
                int index = Math.floorMod(worldX, Chunk.SIZE)
                        + Math.floorMod(worldZ, Chunk.SIZE) * Chunk.SIZE
                        + Math.floorMod(worldY, Chunk.SIZE) * Chunk.SIZE * Chunk.SIZE;
                Short edited = edits.get(index);
                if (edited != null) {
                    return edited;
                }
            }
            return delegate.getBlockAtWorld(worldX, worldY, worldZ);
        }

        @Override
        public void fillBlockRegion(
                int originX,
                int originY,
                int originZ,
                int sizeX,
                int sizeY,
                int sizeZ,
                short[] destination
        ) {
            delegate.fillBlockRegion(originX, originY, originZ, sizeX, sizeY, sizeZ, destination);
            int maxX = originX + sizeX;
            int maxY = originY + sizeY;
            int maxZ = originZ + sizeZ;
            for (Map.Entry<ChunkPosition, Map<Integer, Short>> chunkEntry : sessionEdits.entrySet()) {
                ChunkPosition position = chunkEntry.getKey();
                int chunkOriginX = position.x() * Chunk.SIZE;
                int chunkOriginY = position.y() * Chunk.SIZE;
                int chunkOriginZ = position.z() * Chunk.SIZE;
                if (chunkOriginX >= maxX || chunkOriginX + Chunk.SIZE <= originX
                        || chunkOriginY >= maxY || chunkOriginY + Chunk.SIZE <= originY
                        || chunkOriginZ >= maxZ || chunkOriginZ + Chunk.SIZE <= originZ) {
                    continue;
                }
                chunkEntry.getValue().forEach((index, blockId) -> {
                    int localY = index / (Chunk.SIZE * Chunk.SIZE);
                    int remainder = index - localY * Chunk.SIZE * Chunk.SIZE;
                    int localZ = remainder / Chunk.SIZE;
                    int localX = remainder - localZ * Chunk.SIZE;
                    int worldX = chunkOriginX + localX;
                    int worldY = chunkOriginY + localY;
                    int worldZ = chunkOriginZ + localZ;
                    if (worldX >= originX && worldX < maxX
                            && worldY >= originY && worldY < maxY
                            && worldZ >= originZ && worldZ < maxZ) {
                        int destinationIndex = worldX - originX
                                + (worldZ - originZ) * sizeX
                                + (worldY - originY) * sizeX * sizeZ;
                        destination[destinationIndex] = blockId;
                    }
                });
            }
        }

        @Override
        public int getSurfaceHeight(int worldX, int worldZ) {
            return delegate.getSurfaceHeight(worldX, worldZ);
        }

        @Override
        public int getSkyLightScanStartY(int worldX, int worldZ, int maxWorldY) {
            return delegate.getSkyLightScanStartY(worldX, worldZ, maxWorldY);
        }

        @Override
        public org.weaw.game.generation.ChunkGenerationHint classifyChunk(ChunkPosition position) {
            Map<Integer, Short> edits = sessionEdits.get(position);
            return edits == null || edits.isEmpty()
                    ? delegate.classifyChunk(position)
                    : org.weaw.game.generation.ChunkGenerationHint.materialized();
        }

        @Override
        public void retainChunkClassificationsAround(int centerChunkX, int centerChunkZ, int radius) {
            delegate.retainChunkClassificationsAround(centerChunkX, centerChunkZ, radius);
        }

        @Override
        public void retainChunkClassificationsAround(Collection<ChunkPosition> centers, int radius) {
            delegate.retainChunkClassificationsAround(centers, radius);
        }

        @Override
        public org.weaw.game.generation.ChunkClassificationCacheStats getChunkClassificationCacheStats() {
            return delegate.getChunkClassificationCacheStats();
        }
    }

    private static ChunkPosition toChunkPosition(int worldX, int worldY, int worldZ) {
        return new ChunkPosition(
                Math.floorDiv(worldX, Chunk.SIZE),
                Math.floorDiv(worldY, Chunk.SIZE),
                Math.floorDiv(worldZ, Chunk.SIZE)
        );
    }

    private LightingCollectionProfilingSnapshot collectChunkLightingUpdates() {
        int pendingBeforeCollection = lightingSystem.getPendingBackgroundWorkCount();
        ChunkManager.ChunkUploadSync uploadSync = chunkManager.snapshotChunkUploadSync(synchronizedLightingUploadsVersion);
        if (uploadSync.requiresFullSnapshot()) {
            for (ChunkPosition position : uploadSync.fullSnapshot().keySet()) {
                lightingSystem.enqueueChunkBoundary(position, chunkManager);
            }
            synchronizedLightingUploadsVersion = uploadSync.version();
            return new LightingCollectionProfilingSnapshot(
                    pendingBeforeCollection,
                    lightingSystem.getPendingBackgroundWorkCount(),
                    uploadSync.fullSnapshot().size(),
                    0
            );
        }

        for (ChunkManager.ChunkUploadDelta delta : uploadSync.deltas()) {
            if (delta.changeType() != ChunkManager.ChunkUploadChangeType.UPDATED) {
                lightingSystem.enqueueChunkBoundary(delta.position(), chunkManager);
            }
        }
        synchronizedLightingUploadsVersion = uploadSync.version();
        return new LightingCollectionProfilingSnapshot(
                pendingBeforeCollection,
                lightingSystem.getPendingBackgroundWorkCount(),
                0,
                uploadSync.deltas().size()
        );
    }

    private void markChunksDirtyForBlockChange(int worldX, int worldY, int worldZ) {
        ChunkPosition center = toChunkPosition(worldX, worldY, worldZ);
        worldStreamer.markChunkDirtyPriority(center);

        int localX = Math.floorMod(worldX, Chunk.SIZE);
        int localY = Math.floorMod(worldY, Chunk.SIZE);
        int localZ = Math.floorMod(worldZ, Chunk.SIZE);

        int[] offsetXs = resolveBoundaryOffsets(localX);
        int[] offsetYs = resolveBoundaryOffsets(localY);
        int[] offsetZs = resolveBoundaryOffsets(localZ);

        for (int offsetX : offsetXs) {
            for (int offsetY : offsetYs) {
                for (int offsetZ : offsetZs) {
                    ChunkPosition affectedPosition = new ChunkPosition(
                            center.x() + offsetX,
                            center.y() + offsetY,
                            center.z() + offsetZ
                    );
                    if (!affectedPosition.equals(center)) {
                        worldStreamer.markChunkDirtyPriority(affectedPosition);
                    }
                }
            }
        }

        worldStreamer.submitInteractionRemeshes();
    }

    private static int[] resolveBoundaryOffsets(int localCoordinate) {
        if (localCoordinate == 0) {
            return new int[]{-1, 0};
        }
        if (localCoordinate == Chunk.SIZE - 1) {
            return new int[]{0, 1};
        }
        return new int[]{0};
    }

    private record LightingCollectionProfilingSnapshot(
            int pendingBeforeCollection,
            int pendingAfterCollection,
            int fullSnapshotCount,
            int deltaCount
    ) {
        private static LightingCollectionProfilingSnapshot empty() {
            return new LightingCollectionProfilingSnapshot(0, 0, 0, 0);
        }
    }

}
