package org.weaw.network.client;

import org.weaw.game.WorldTimeState;
import org.weaw.network.protocol.WorldTimeSnapshot;

/** Predicts replicated world time and smoothly absorbs wrap-aware corrections. */
public final class ClientWorldClock {
    private static final double CORRECTION_RATE = 2.0;

    private double phase;
    private double targetPhase;
    private int dayLengthSeconds;
    private double timeScale;
    private boolean frozen;
    private boolean initialized;

    public void synchronize(WorldTimeSnapshot snapshot) {
        dayLengthSeconds = snapshot.dayLengthSeconds();
        timeScale = snapshot.timeScale();
        frozen = snapshot.frozen();
        targetPhase = snapshot.phase();
        if (!initialized) {
            phase = targetPhase;
            initialized = true;
        }
    }

    public void update(double deltaSeconds) {
        if (!initialized) {
            return;
        }
        double delta = Math.max(0.0, deltaSeconds);
        if (!frozen) {
            double advance = delta * timeScale / dayLengthSeconds;
            phase = WorldTimeState.normalize(phase + advance);
            targetPhase = WorldTimeState.normalize(targetPhase + advance);
        }
        double error = shortestDelta(phase, targetPhase);
        phase = WorldTimeState.normalize(phase + error * Math.min(1.0, delta * CORRECTION_RATE));
    }

    public double phase() {
        return initialized ? phase : WorldTimeState.NOON;
    }

    static double shortestDelta(double from, double to) {
        double delta = (to - from) % 1.0;
        if (delta >= 0.5) delta -= 1.0;
        if (delta < -0.5) delta += 1.0;
        return delta;
    }
}
