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

public class World implements AutoCloseable, WorldBlockProvider {
    private final ChunkManager chunkManager;
    private final BlockCatalog blockCatalog;
    private final WorldStreamer worldStreamer;
    private final WorldGenerator worldGenerator;
    private final WorldSettings settings;
    private final WorldLightingSystem lightingSystem;
    private volatile boolean dynamicLightingEnabled = !Boolean.getBoolean("voxy.disableDynamicLighting");
    private long synchronizedLightingUploadsVersion;
    private volatile WorldProfilingSnapshot lastProfilingSnapshot = WorldProfilingSnapshot.empty();

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
        this.worldGenerator = Objects.requireNonNull(worldGenerator, "worldGenerator");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.worldStreamer = new WorldStreamer(chunkManager, this, worldGenerator, settings);
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
        long worldUpdateStartNs = System.nanoTime();

        long worldStreamerStartNs = System.nanoTime();
        worldStreamer.update(playerPosition);
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
