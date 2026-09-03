package org.weaw.runtime;

import org.joml.Vector3f;

public record BenchmarkOptions(
        boolean enabled,
        long seed,
        int durationSeconds,
        int warmupSeconds,
        int loadingTimeoutSeconds,
        int settleSeconds,
        int renderDistanceChunks,
        int windowWidth,
        int windowHeight,
        Vector3f spawn
) {
    private static final int DEFAULT_DURATION_SECONDS = 30;
    private static final int DEFAULT_WARMUP_SECONDS = 5;
    private static final int DEFAULT_LOADING_TIMEOUT_SECONDS = 60;
    private static final int DEFAULT_SETTLE_SECONDS = 10;
    private static final int DEFAULT_RENDER_DISTANCE_CHUNKS = 16;
    private static final int DEFAULT_WINDOW_WIDTH = 1280;
    private static final int DEFAULT_WINDOW_HEIGHT = 720;
    private static final Vector3f DEFAULT_SPAWN = new Vector3f(16.0f, 48.0f, 48.0f);

    public BenchmarkOptions {
        durationSeconds = Math.max(1, durationSeconds);
        warmupSeconds = Math.max(0, warmupSeconds);
        loadingTimeoutSeconds = Math.max(1, loadingTimeoutSeconds);
        settleSeconds = Math.max(0, settleSeconds);
        renderDistanceChunks = Math.max(2, renderDistanceChunks);
        windowWidth = Math.max(320, windowWidth);
        windowHeight = Math.max(240, windowHeight);
        spawn = new Vector3f(spawn);
    }

    public static BenchmarkOptions disabled() {
        return new BenchmarkOptions(
                false,
                1052002L,
                DEFAULT_DURATION_SECONDS,
                DEFAULT_WARMUP_SECONDS,
                DEFAULT_LOADING_TIMEOUT_SECONDS,
                DEFAULT_SETTLE_SECONDS,
                DEFAULT_RENDER_DISTANCE_CHUNKS,
                DEFAULT_WINDOW_WIDTH,
                DEFAULT_WINDOW_HEIGHT,
                DEFAULT_SPAWN
        );
    }

    @Override
    public Vector3f spawn() {
        return new Vector3f(spawn);
    }
}
