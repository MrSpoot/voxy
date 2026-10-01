package org.weaw.game;

public class WorldSettings {
    public static final int MIN_RENDER_DISTANCE_CHUNKS = 2;
    public static final int MAX_RENDER_DISTANCE_CHUNKS = 64;
    public static final int DEFAULT_RENDER_DISTANCE_CHUNKS = 32;

    private final float[] renderDistanceChunks;
    private final int simulationDistanceChunks;
    private final WorldHeightRange heightRange;
    private final WorldMemoryBudget memoryBudget;
    private final boolean sparseChunkStreamingEnabled;

    public WorldSettings() {
        this(DEFAULT_RENDER_DISTANCE_CHUNKS);
    }

    public WorldSettings(int renderDistanceChunks) {
        this(renderDistanceChunks, WorldHeightRange.configuredDefault(), WorldMemoryBudget.balanced());
    }

    public WorldSettings(
            int renderDistanceChunks,
            WorldHeightRange heightRange,
            WorldMemoryBudget memoryBudget
    ) {
        this(
                renderDistanceChunks,
                heightRange,
                memoryBudget,
                Boolean.parseBoolean(System.getProperty("voxy.sparseChunkStreaming", "true"))
        );
    }

    public WorldSettings(
            int renderDistanceChunks,
            WorldHeightRange heightRange,
            WorldMemoryBudget memoryBudget,
            boolean sparseChunkStreamingEnabled
    ) {
        this(renderDistanceChunks, renderDistanceChunks, heightRange, memoryBudget, sparseChunkStreamingEnabled);
    }

    public WorldSettings(
            int simulationDistanceChunks,
            int defaultRenderDistanceChunks,
            WorldHeightRange heightRange,
            WorldMemoryBudget memoryBudget,
            boolean sparseChunkStreamingEnabled
    ) {
        this.simulationDistanceChunks = clamp(simulationDistanceChunks);
        this.renderDistanceChunks = new float[]{clamp(defaultRenderDistanceChunks)};
        this.heightRange = java.util.Objects.requireNonNull(heightRange, "heightRange");
        this.memoryBudget = java.util.Objects.requireNonNull(memoryBudget, "memoryBudget");
        this.sparseChunkStreamingEnabled = sparseChunkStreamingEnabled;
    }

    public int getRenderDistanceChunks() {
        int roundedDistance = Math.round(renderDistanceChunks[0]);
        int clampedDistance = clamp(roundedDistance);
        renderDistanceChunks[0] = clampedDistance;
        return clampedDistance;
    }

    public int getSimulationDistanceChunks() {
        return simulationDistanceChunks;
    }

    public float[] renderDistanceChunksRef() {
        return renderDistanceChunks;
    }

    public WorldHeightRange getHeightRange() {
        return heightRange;
    }

    public WorldMemoryBudget getMemoryBudget() {
        return memoryBudget;
    }

    public boolean isSparseChunkStreamingEnabled() {
        return sparseChunkStreamingEnabled;
    }

    public void reset() {
        renderDistanceChunks[0] = DEFAULT_RENDER_DISTANCE_CHUNKS;
    }

    private static int clamp(int value) {
        return Math.max(MIN_RENDER_DISTANCE_CHUNKS, Math.min(MAX_RENDER_DISTANCE_CHUNKS, value));
    }
}
