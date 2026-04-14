package org.weaw.game;

import org.joml.Vector3f;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.utils.BlockDefinition;
import org.weaw.game.utils.GenerationEngine;

public class World implements AutoCloseable, WorldBlockProvider {
    private final ChunkManager chunkManager;
    private final WorldStreamer worldStreamer;

    public World() {
        this.chunkManager = new ChunkManager();
        this.worldStreamer = new WorldStreamer(chunkManager, this);
    }

    public ChunkManager getChunkManager() {
        return chunkManager;
    }

    public void update(Vector3f playerPosition) {
        worldStreamer.update(playerPosition);
    }

    public int getLoadedChunkCount() {
        return chunkManager.getChunkCount();
    }

    public int getQueuedChunkCount() {
        return chunkManager.getQueuedChunkCount();
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
        return GenerationEngine.getBlockAtWorld(worldX, worldY, worldZ);
    }

    public void setBlockAtWorld(int worldX, int worldY, int worldZ, BlockDefinition block) {
        chunkManager.setBlockAtWorld(worldX, worldY, worldZ, block);
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
}
