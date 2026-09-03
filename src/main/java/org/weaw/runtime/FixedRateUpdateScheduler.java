package org.weaw.runtime;

import java.util.Objects;

public final class FixedRateUpdateScheduler {
    private final float fixedDeltaTime;
    private final int maxUpdatesPerFrame;
    private double accumulatedTimeSeconds;

    public FixedRateUpdateScheduler(int updatesPerSecond, int maxUpdatesPerFrame) {
        if (updatesPerSecond <= 0) {
            throw new IllegalArgumentException("updatesPerSecond must be positive");
        }
        if (maxUpdatesPerFrame <= 0) {
            throw new IllegalArgumentException("maxUpdatesPerFrame must be positive");
        }
        this.fixedDeltaTime = 1.0f / updatesPerSecond;
        this.maxUpdatesPerFrame = maxUpdatesPerFrame;
    }

    public int update(float frameDeltaTime, Runnable updateAction) {
        if (frameDeltaTime < 0.0f) {
            throw new IllegalArgumentException("frameDeltaTime must be non-negative");
        }
        Objects.requireNonNull(updateAction, "updateAction");

        accumulatedTimeSeconds += frameDeltaTime;
        int updates = 0;
        while (accumulatedTimeSeconds >= fixedDeltaTime && updates < maxUpdatesPerFrame) {
            updateAction.run();
            accumulatedTimeSeconds -= fixedDeltaTime;
            updates++;
        }

        if (updates == maxUpdatesPerFrame && accumulatedTimeSeconds >= fixedDeltaTime) {
            accumulatedTimeSeconds = 0.0d;
        }

        return updates;
    }

    public float getFixedDeltaTime() {
        return fixedDeltaTime;
    }
}
