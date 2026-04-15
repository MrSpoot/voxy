package org.weaw.game.generation;

import org.weaw.game.Chunk;

public interface WorldGenerator {
    void generateChunkData(Chunk chunk);

    short getBlockAtWorld(int worldX, int worldY, int worldZ);

    int getSurfaceHeight(int worldX, int worldZ);
}
