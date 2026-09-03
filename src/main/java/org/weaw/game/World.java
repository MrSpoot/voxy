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
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.Set;

public class World implements AutoCloseable, WorldBlockProvider {
    private static final int MAX_LIGHTING_UPDATES_PER_FRAME = Integer.getInteger("voxy.maxLightingUpdatesPerFrame", 4);

    private final ChunkManager chunkManager;
    private final BlockCatalog blockCatalog;
    private final WorldStreamer worldStreamer;
    private final WorldGenerator worldGenerator;
    private final WorldSettings settings;
    private final WorldLightingSystem lightingSystem;
    private final Set<ChunkPosition> pendingLightingUpdates = new LinkedHashSet<>();
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
        this.lightingSystem = new WorldLightingSystem(blockCatalog);
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
        LightingSynchronizationProfilingSnapshot lightingSynchronizationSnapshot =
                LightingSynchronizationProfilingSnapshot.empty();
        long lightingCollectionCpuTimeNs = 0L;
        long lightingCpuTimeNs = 0L;
        if (dynamicLightingEnabled) {
            long lightingCollectionStartNs = System.nanoTime();
            lightingCollectionSnapshot = collectChunkLightingUpdates();
            lightingCollectionCpuTimeNs = System.nanoTime() - lightingCollectionStartNs;

            long lightingStartNs = System.nanoTime();
            lightingSynchronizationSnapshot = synchronizeLighting();
            lightingCpuTimeNs = System.nanoTime() - lightingStartNs;
        }

        WorldStreamerProfilingSnapshot streamerSnapshot = worldStreamer.getLastProfilingSnapshot();
        WorldLightingProfilingSnapshot lightingSnapshot = lightingSynchronizationSnapshot.worldLightingProfilingSnapshot();
        lastProfilingSnapshot = new WorldProfilingSnapshot(
                System.nanoTime() - worldUpdateStartNs,
                worldStreamerCpuTimeNs,
                lightingCollectionCpuTimeNs,
                lightingCpuTimeNs,
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
                lightingSynchronizationSnapshot.batchSize(),
                lightingSnapshot.affectedChunkCount(),
                lightingSnapshot.expandedChunkCount(),
                lightingSnapshot.loadedChunkCount(),
                lightingSnapshot.loadedTargetChunkCount(),
                lightingSynchronizationSnapshot.markedChunkCount(),
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
                lightingSynchronizationSnapshot.lightUploadRefreshedChunkCount(),
                lightingSynchronizationSnapshot.lightUploadFreedChunkCount(),
                lightingSynchronizationSnapshot.lightUploadUploadedChunkCount(),
                lightingSynchronizationSnapshot.lightUploadResidentChunkCount(),
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
    }

    public WorldProfilingSnapshot getLastProfilingSnapshot() {
        return lastProfilingSnapshot;
    }

    public WorldMemorySnapshot getMemorySnapshot() {
        return worldStreamer.getLastMemorySnapshot();
    }

    public void setDynamicLightingEnabled(boolean dynamicLightingEnabled) {
        this.dynamicLightingEnabled = dynamicLightingEnabled;
        pendingLightingUpdates.clear();
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
        return worldStreamer.isConverged();
    }

    public boolean containsChunk(int x, int y, int z) {
        return chunkManager.hasChunk(x, y, z);
    }

    @Override
    public short getBlockAtWorld(int worldX, int worldY, int worldZ) {
        ChunkPosition position = toChunkPosition(worldX, worldY, worldZ);
        if (chunkManager.hasChunk(position.x(), position.y(), position.z())) {
            return chunkManager.getBlockAtWorld(worldX, worldY, worldZ);
        }
        return worldGenerator.getBlockAtWorld(worldX, worldY, worldZ);
    }

    public void setBlockAtWorld(int worldX, int worldY, int worldZ, BlockDefinition block) {
        Objects.requireNonNull(block, "block");
        ChunkPosition position = toChunkPosition(worldX, worldY, worldZ);
        if (!worldStreamer.materializeChunkForEdit(position)) {
            throw new IllegalArgumentException(
                    "Unable to materialize chunk at world position: " + worldX + ", " + worldY + ", " + worldZ
            );
        }
        chunkManager.setBlockAtWorld(worldX, worldY, worldZ, block);
        if (dynamicLightingEnabled) {
            pendingLightingUpdates.add(position);
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

    private LightingSynchronizationProfilingSnapshot synchronizeLighting() {
        if (pendingLightingUpdates.isEmpty()) {
            return LightingSynchronizationProfilingSnapshot.empty();
        }

        Set<ChunkPosition> batch = drainLightingUpdateBatch();
        if (batch.isEmpty()) {
            return LightingSynchronizationProfilingSnapshot.empty();
        }

        Set<ChunkPosition> updatedPositions = expandLightingUpdatePositions(batch);
        WorldLightingProfilingSnapshot lightingSnapshot = lightingSystem.rebuildLightingAround(chunkManager, batch);
        int markedChunkCount = chunkManager.markChunksLightUpdated(updatedPositions);
        return new LightingSynchronizationProfilingSnapshot(
                batch.size(),
                markedChunkCount,
                lightingSnapshot,
                0,
                0,
                0,
                0
        );
    }

    private LightingCollectionProfilingSnapshot collectChunkLightingUpdates() {
        int pendingBeforeCollection = pendingLightingUpdates.size();
        ChunkManager.ChunkUploadSync uploadSync = chunkManager.snapshotChunkUploadSync(synchronizedLightingUploadsVersion);
        if (uploadSync.requiresFullSnapshot()) {
            pendingLightingUpdates.addAll(uploadSync.fullSnapshot().keySet());
            synchronizedLightingUploadsVersion = uploadSync.version();
            return new LightingCollectionProfilingSnapshot(
                    pendingBeforeCollection,
                    pendingLightingUpdates.size(),
                    uploadSync.fullSnapshot().size(),
                    0
            );
        }

        for (ChunkManager.ChunkUploadDelta delta : uploadSync.deltas()) {
            pendingLightingUpdates.add(delta.position());
        }
        synchronizedLightingUploadsVersion = uploadSync.version();
        return new LightingCollectionProfilingSnapshot(
                pendingBeforeCollection,
                pendingLightingUpdates.size(),
                0,
                uploadSync.deltas().size()
        );
    }

    private Set<ChunkPosition> drainLightingUpdateBatch() {
        int maxUpdates = Math.max(1, MAX_LIGHTING_UPDATES_PER_FRAME);
        Set<ChunkPosition> batch = new LinkedHashSet<>(Math.min(maxUpdates, pendingLightingUpdates.size()));
        Iterator<ChunkPosition> iterator = pendingLightingUpdates.iterator();
        while (iterator.hasNext() && batch.size() < maxUpdates) {
            ChunkPosition position = iterator.next();
            batch.add(position);
            iterator.remove();
        }
        return batch;
    }

    private Set<ChunkPosition> expandLightingUpdatePositions(Set<ChunkPosition> positions) {
        Set<ChunkPosition> expanded = new HashSet<>();
        int radius = WorldLightingSystem.BLOCK_LIGHT_CHUNK_RADIUS;
        for (ChunkPosition position : positions) {
            for (int offsetX = -radius; offsetX <= radius; offsetX++) {
                for (int offsetY = -radius; offsetY <= radius; offsetY++) {
                    for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                        expanded.add(new ChunkPosition(
                                position.x() + offsetX,
                                position.y() + offsetY,
                                position.z() + offsetZ
                        ));
                    }
                }
            }
        }
        return expanded;
    }

    private void markChunksDirtyForBlockChange(int worldX, int worldY, int worldZ) {
        ChunkPosition center = toChunkPosition(worldX, worldY, worldZ);
        int localX = Math.floorMod(worldX, Chunk.SIZE);
        int localY = Math.floorMod(worldY, Chunk.SIZE);
        int localZ = Math.floorMod(worldZ, Chunk.SIZE);

        int[] offsetXs = resolveBoundaryOffsets(localX);
        int[] offsetYs = resolveBoundaryOffsets(localY);
        int[] offsetZs = resolveBoundaryOffsets(localZ);

        for (int offsetX : offsetXs) {
            for (int offsetY : offsetYs) {
                for (int offsetZ : offsetZs) {
                    worldStreamer.markChunkDirtyPriority(new ChunkPosition(
                            center.x() + offsetX,
                            center.y() + offsetY,
                            center.z() + offsetZ
                    ));
                }
            }
        }
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

    private record LightingSynchronizationProfilingSnapshot(
            int batchSize,
            int markedChunkCount,
            WorldLightingProfilingSnapshot worldLightingProfilingSnapshot,
            int lightUploadRefreshedChunkCount,
            int lightUploadFreedChunkCount,
            int lightUploadUploadedChunkCount,
            int lightUploadResidentChunkCount
    ) {
        private static LightingSynchronizationProfilingSnapshot empty() {
            return new LightingSynchronizationProfilingSnapshot(
                    0,
                    0,
                    WorldLightingProfilingSnapshot.empty(),
                    0,
                    0,
                    0,
                    0
            );
        }
    }
}
