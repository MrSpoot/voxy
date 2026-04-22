package org.weaw.game;

import org.joml.Vector3f;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.generation.GenerationConfig;
import org.weaw.game.generation.NoiseWorldGenerator;
import org.weaw.game.generation.WorldGenerator;
import org.weaw.game.utils.BlockDefinition;
import org.weaw.game.utils.BlockRegistry;

import java.util.Objects;

public class World implements AutoCloseable, WorldBlockProvider {
    private final ChunkManager chunkManager;
    private final WorldStreamer worldStreamer;
    private final WorldGenerator worldGenerator;
    private final WorldSettings settings;

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
    }

    public ChunkManager getChunkManager() {
        return chunkManager;
    }

    public WorldSettings getSettings() {
        return settings;
    }

    public void update(Vector3f playerPosition) {
        worldStreamer.update(playerPosition);
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
        markChunksDirtyForBlockChange(worldX, worldY, worldZ);
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
