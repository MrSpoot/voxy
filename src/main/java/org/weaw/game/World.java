package org.weaw.game;

public class World {
    private final ChunkManager chunkManager;

    public World() {
        this.chunkManager = new ChunkManager();
    }

    public ChunkManager getChunkManager() {
        return chunkManager;
    }

    public boolean containsChunk(int x, int y, int z) {
        return true;
    }
}
