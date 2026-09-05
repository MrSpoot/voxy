package org.weaw.engine.graphics.pipeline;

/** Applies slow hysteresis around the 120 Hz GPU budget. */
final class AdaptiveQualityController {
    static final long DOWNGRADE_THRESHOLD_NS = 8_300_000L;
    static final long UPGRADE_THRESHOLD_NS = 7_000_000L;
    static final double DOWNGRADE_DELAY_SECONDS = 2.0d;
    static final double UPGRADE_DELAY_SECONDS = 5.0d;

    private double overBudgetSeconds;
    private double underBudgetSeconds;

    void update(AdaptiveGraphicsQuality quality, long gpuTimeNs, float deltaSeconds) {
        if (!quality.isEnabled() || gpuTimeNs <= 0L || !Float.isFinite(deltaSeconds) || deltaSeconds <= 0.0f) {
            return;
        }
        if (gpuTimeNs > DOWNGRADE_THRESHOLD_NS) {
            overBudgetSeconds += deltaSeconds;
            underBudgetSeconds = 0.0d;
            if (overBudgetSeconds >= DOWNGRADE_DELAY_SECONDS) {
                downgrade(quality);
                overBudgetSeconds = 0.0d;
            }
            return;
        }
        if (gpuTimeNs < UPGRADE_THRESHOLD_NS) {
            underBudgetSeconds += deltaSeconds;
            overBudgetSeconds = 0.0d;
            if (underBudgetSeconds >= UPGRADE_DELAY_SECONDS) {
                upgrade(quality);
                underBudgetSeconds = 0.0d;
            }
            return;
        }
        overBudgetSeconds = 0.0d;
        underBudgetSeconds = 0.0d;
    }

    private static void downgrade(AdaptiveGraphicsQuality quality) {
        quality.setLevel(switch (quality.getLevel()) {
            case HIGH -> AdaptiveGraphicsQuality.Level.MEDIUM;
            case MEDIUM, LOW -> AdaptiveGraphicsQuality.Level.LOW;
        });
    }

    private static void upgrade(AdaptiveGraphicsQuality quality) {
        quality.setLevel(switch (quality.getLevel()) {
            case LOW -> AdaptiveGraphicsQuality.Level.MEDIUM;
            case MEDIUM, HIGH -> AdaptiveGraphicsQuality.Level.HIGH;
        });
    }
}
