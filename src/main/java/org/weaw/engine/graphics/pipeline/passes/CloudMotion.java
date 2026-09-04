package org.weaw.engine.graphics.pipeline.passes;

final class CloudMotion {
    static final int PATTERN_PERIOD_CELLS = 4096;
    private static final float MAX_FRAME_DELTA_SECONDS = 0.25f;

    private CloudMotion() {
    }

    static double advanceWrapped(
            double currentOffset,
            float frameDeltaSeconds,
            float speedBlocksPerSecond,
            float cellSize
    ) {
        double safeOffset = Double.isFinite(currentOffset) ? currentOffset : 0.0;
        float safeDelta = Float.isFinite(frameDeltaSeconds)
                ? Math.max(0.0f, Math.min(MAX_FRAME_DELTA_SECONDS, frameDeltaSeconds))
                : 0.0f;
        float safeSpeed = Float.isFinite(speedBlocksPerSecond) ? Math.max(0.0f, speedBlocksPerSecond) : 0.0f;
        float safeCellSize = Float.isFinite(cellSize) ? Math.max(1.0f, cellSize) : 1.0f;
        double period = safeCellSize * PATTERN_PERIOD_CELLS;
        double nextOffset = (safeOffset + safeDelta * safeSpeed) % period;
        return nextOffset < 0.0 ? nextOffset + period : nextOffset;
    }
}
