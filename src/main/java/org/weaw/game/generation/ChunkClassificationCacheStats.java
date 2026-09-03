package org.weaw.game.generation;

public record ChunkClassificationCacheStats(int size, long hits, long misses) {
    public static final ChunkClassificationCacheStats EMPTY = new ChunkClassificationCacheStats(0, 0L, 0L);
}
