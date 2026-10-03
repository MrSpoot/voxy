package org.weaw.game;

public record ChunkPosition(int x, int y, int z) {
    public static ChunkPosition fromChunk(Chunk chunk) {
        return new ChunkPosition(chunk.getPosition().x, chunk.getPosition().y, chunk.getPosition().z);
    }
}
