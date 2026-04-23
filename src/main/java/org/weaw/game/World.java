package org.weaw.game;

import org.joml.Vector3f;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.generation.GenerationConfig;
import org.weaw.game.generation.NoiseWorldGenerator;
import org.weaw.game.generation.WorldGenerator;
import org.weaw.game.utils.BlockDefinition;
import org.weaw.game.utils.BlockRegistry;

import java.util.Objects;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.Set;

public class World implements AutoCloseable, WorldBlockProvider {
    private static final int MAX_LIGHTING_UPDATES_PER_FRAME = Integer.getInteger("voxy.maxLightingUpdatesPerFrame", 4);

    private final ChunkManager chunkManager;
    private final WorldStreamer worldStreamer;
    private final WorldGenerator worldGenerator;
    private final WorldSettings settings;
    private final WorldLightingSystem lightingSystem;
    private final Set<ChunkPosition> pendingLightingUpdates = new LinkedHashSet<>();
    private long synchronizedLightingUploadsVersion;

    public World() {
        this(new NoiseWorldGenerator(GenerationConfig.defaults()));
    }

    public World(WorldGenerator worldGenerator) {
        this(worldGenerator, new WorldSettings());
    }

    public World(WorldGenerator worldGenerator, WorldSettings settings) {
        this.chunkManager = new ChunkManager();
        this.worldGenerator = Objects.requireNonNull(worldGenerator, "worldGenerator");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.worldStreamer = new WorldStreamer(chunkManager, this, worldGenerator, settings);
        this.lightingSystem = new WorldLightingSystem();
    }

    public ChunkManager getChunkManager() {
        return chunkManager;
    }

    public WorldSettings getSettings() {
        return settings;
    }

    public void update(Vector3f playerPosition) {
        worldStreamer.update(playerPosition);
        collectChunkLightingUpdates();
        synchronizeLighting();
    }

    public int getLoadedChunkCount() {
        return chunkManager.getChunkCount();
    }

    public int getQueuedChunkCount() {
        return worldStreamer.getPendingTaskCount();
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
        chunkManager.setBlockAtWorld(worldX, worldY, worldZ, block);
        pendingLightingUpdates.add(toChunkPosition(worldX, worldY, worldZ));
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
        if (!containsChunkAtWorld(worldX, worldY, worldZ)) {
            return false;
        }

        setBlockAtWorld(worldX, worldY, worldZ, block);
        return true;
    }

    public boolean isSolidBlockAtWorld(int worldX, int worldY, int worldZ) {
        BlockDefinition block = BlockRegistry.getBlock(getBlockAtWorld(worldX, worldY, worldZ));
        return block != null && block.isSolid();
    }

    public boolean containsChunkAtWorld(int worldX, int worldY, int worldZ) {
        ChunkPosition position = toChunkPosition(worldX, worldY, worldZ);
        return chunkManager.hasChunk(position.x(), position.y(), position.z());
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

    private void synchronizeLighting() {
        if (pendingLightingUpdates.isEmpty()) {
            return;
        }

        Set<ChunkPosition> batch = drainLightingUpdateBatch();
        if (batch.isEmpty()) {
            return;
        }

        Set<ChunkPosition> updatedPositions = expandLightingUpdatePositions(batch);
        lightingSystem.rebuildLightingAround(chunkManager, batch);
        chunkManager.markChunksLightUpdated(updatedPositions);
    }

    private void collectChunkLightingUpdates() {
        ChunkManager.ChunkUploadSync uploadSync = chunkManager.snapshotChunkUploadSync(synchronizedLightingUploadsVersion);
        if (uploadSync.requiresFullSnapshot()) {
            pendingLightingUpdates.addAll(uploadSync.fullSnapshot().keySet());
            synchronizedLightingUploadsVersion = uploadSync.version();
            return;
        }

        for (ChunkManager.ChunkUploadDelta delta : uploadSync.deltas()) {
            pendingLightingUpdates.add(delta.position());
        }
        synchronizedLightingUploadsVersion = uploadSync.version();
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
}
