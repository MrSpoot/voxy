package org.weaw.game;

public class WorldSettings {
    public static final int MIN_RENDER_DISTANCE_CHUNKS = 2;
    public static final int MAX_RENDER_DISTANCE_CHUNKS = 64;
    public static final int DEFAULT_RENDER_DISTANCE_CHUNKS = 32;

    private final float[] renderDistanceChunks;

    public WorldSettings() {
        this(DEFAULT_RENDER_DISTANCE_CHUNKS);
    }

    public WorldSettings(int renderDistanceChunks) {
        this.renderDistanceChunks = new float[]{clamp(renderDistanceChunks)};
    }

    public int getRenderDistanceChunks() {
        int roundedDistance = Math.round(renderDistanceChunks[0]);
        int clampedDistance = clamp(roundedDistance);
        renderDistanceChunks[0] = clampedDistance;
        return clampedDistance;
    }

    public float[] renderDistanceChunksRef() {
        return renderDistanceChunks;
    }

    public void reset() {
        renderDistanceChunks[0] = DEFAULT_RENDER_DISTANCE_CHUNKS;
    }

    private static int clamp(int value) {
        return Math.max(MIN_RENDER_DISTANCE_CHUNKS, Math.min(MAX_RENDER_DISTANCE_CHUNKS, value));
    }
}
