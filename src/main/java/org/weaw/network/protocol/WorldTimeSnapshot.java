package org.weaw.network.protocol;

import org.weaw.game.WorldTimeState;

/** Clock state periodically replicated by the authoritative server. */
public record WorldTimeSnapshot(double phase, int dayLengthSeconds, double timeScale, boolean frozen) {
    public WorldTimeSnapshot {
        phase = new WorldTimeState(phase, dayLengthSeconds).phase();
        if (!Double.isFinite(timeScale) || timeScale < 0.1 || timeScale > 20.0) {
            throw new IllegalArgumentException("Invalid replicated time scale");
        }
    }
}
