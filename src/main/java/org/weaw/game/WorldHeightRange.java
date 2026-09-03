package org.weaw.game;

public record WorldHeightRange(int minChunkY, int maxChunkY) {
    public static final WorldHeightRange DEFAULT = new WorldHeightRange(-4, 3);

    public static WorldHeightRange configuredDefault() {
        return new WorldHeightRange(
                Integer.getInteger("voxy.world.minChunkY", DEFAULT.minChunkY()),
                Integer.getInteger("voxy.world.maxChunkY", DEFAULT.maxChunkY())
        );
    }

    public WorldHeightRange {
        if (minChunkY > maxChunkY) {
            throw new IllegalArgumentException("minChunkY must be <= maxChunkY");
        }
    }

    public int chunkCount() {
        return maxChunkY - minChunkY + 1;
    }

    public boolean contains(int chunkY) {
        return chunkY >= minChunkY && chunkY <= maxChunkY;
    }
}
