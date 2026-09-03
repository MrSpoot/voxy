package org.weaw.game.generation;

import org.weaw.game.Chunk;
import org.weaw.game.ChunkManager.ChunkPosition;

public interface WorldGenerator {
    void generateChunkData(Chunk chunk);

    short getBlockAtWorld(int worldX, int worldY, int worldZ);

    int getSurfaceHeight(int worldX, int worldZ);

    /**
     * Returns a conservative chunk description used by sparse streaming.
     * Generators that cannot prove a chunk is empty or uniform must keep the
     * default and materialize it.
     */
    default ChunkGenerationHint classifyChunk(ChunkPosition position) {
        return ChunkGenerationHint.materialized();
    }

    default void retainChunkClassificationsAround(int centerChunkX, int centerChunkZ, int radius) {
    }

    default ChunkClassificationCacheStats getChunkClassificationCacheStats() {
        return ChunkClassificationCacheStats.EMPTY;
    }
}
