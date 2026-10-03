package org.weaw.game;

/** Server-authoritative, tick-driven world clock. */
public final class WorldClock {
    public static final int TICKS_PER_SECOND = 30;
    public static final double MIN_TIME_SCALE = 0.1;
    public static final double MAX_TIME_SCALE = 20.0;

    private double phase;
    private final int dayLengthSeconds;
    private double timeScale = 1.0;
    private boolean frozen;

    public WorldClock(WorldTimeState state) {
        this.phase = state.phase();
        this.dayLengthSeconds = state.dayLengthSeconds();
    }

    public synchronized void tick() {
        if (!frozen) {
            phase = WorldTimeState.normalize(
                    phase + timeScale / (dayLengthSeconds * (double) TICKS_PER_SECOND));
        }
    }

    public synchronized double phase() {
        return phase;
    }

    public synchronized int dayLengthSeconds() {
        return dayLengthSeconds;
    }

    public synchronized double timeScale() {
        return timeScale;
    }

    public synchronized boolean frozen() {
        return frozen;
    }

    public synchronized void setPhase(double phase) {
        if (!Double.isFinite(phase)) {
            throw new IllegalArgumentException("World time phase must be finite");
        }
        this.phase = WorldTimeState.normalize(phase);
    }

    public synchronized void setTimeScale(double timeScale) {
        if (!Double.isFinite(timeScale) || timeScale < MIN_TIME_SCALE || timeScale > MAX_TIME_SCALE) {
            throw new IllegalArgumentException("Time scale must be between 0.1 and 20");
        }
        this.timeScale = timeScale;
    }

    public synchronized void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    public synchronized WorldTimeState persistentState() {
        return new WorldTimeState(phase, dayLengthSeconds);
    }
}
