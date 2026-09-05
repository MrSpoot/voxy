package org.weaw.engine.graphics.pipeline.passes;

final class WaterAnimation {
    static final double LOOP_SECONDS = 4096.0;
    private static final float MAX_FRAME_DELTA_SECONDS = 0.25f;

    private WaterAnimation() {
    }

    static double advanceWrapped(double currentTime, float frameDeltaSeconds) {
        double safeCurrent = Double.isFinite(currentTime) ? currentTime : 0.0;
        float safeDelta = Float.isFinite(frameDeltaSeconds)
                ? Math.max(0.0f, Math.min(MAX_FRAME_DELTA_SECONDS, frameDeltaSeconds))
                : 0.0f;
        double wrapped = (safeCurrent + safeDelta) % LOOP_SECONDS;
        return wrapped < 0.0 ? wrapped + LOOP_SECONDS : wrapped;
    }
}
