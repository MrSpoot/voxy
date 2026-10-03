package org.weaw.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldClockTest {
    @Test
    void advancesFromSimulationTicksAndWraps() {
        WorldClock clock = new WorldClock(new WorldTimeState(0.999, 60));

        for (int tick = 0; tick < 60; tick++) {
            clock.tick();
        }

        assertEquals(0.999 + 1.0 / 30.0, clock.phase() + 1.0, 1.0e-9);
    }

    @Test
    void supportsSpeedAndFreezeControls() {
        WorldClock clock = new WorldClock(new WorldTimeState(0.5, 60));
        clock.setTimeScale(2.0);
        clock.tick();
        assertEquals(0.5 + 2.0 / (60.0 * 30.0), clock.phase(), 1.0e-9);

        clock.setFrozen(true);
        double frozenPhase = clock.phase();
        clock.tick();
        assertEquals(frozenPhase, clock.phase());
    }
}
