package org.weaw.game;

/** Persistent world-time settings. Phase uses 0 = midnight and 0.5 = noon. */
public record WorldTimeState(double phase, int dayLengthSeconds) {
    public static final double NOON = 0.5;
    public static final int DEFAULT_DAY_LENGTH_SECONDS = 20 * 60;
    public static final int MIN_DAY_LENGTH_SECONDS = 60;
    public static final int MAX_DAY_LENGTH_SECONDS = 2 * 60 * 60;

    public WorldTimeState {
        if (!Double.isFinite(phase)) {
            throw new IllegalArgumentException("World time phase must be finite");
        }
        if (dayLengthSeconds < MIN_DAY_LENGTH_SECONDS || dayLengthSeconds > MAX_DAY_LENGTH_SECONDS) {
            throw new IllegalArgumentException("Day length must be between 60 and 7200 seconds");
        }
        phase = normalize(phase);
    }

    public static WorldTimeState defaults() {
        return new WorldTimeState(NOON, DEFAULT_DAY_LENGTH_SECONDS);
    }

    public static double normalize(double phase) {
        return phase - Math.floor(phase);
    }
}
