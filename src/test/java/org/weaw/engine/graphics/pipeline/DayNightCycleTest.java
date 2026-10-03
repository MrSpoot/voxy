package org.weaw.engine.graphics.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DayNightCycleTest {
    @Test
    void noonIsBrighterThanMidnight() {
        DayNightVisualState noon = DayNightCycle.sample(0.5);
        DayNightVisualState midnight = DayNightCycle.sample(0.0);

        assertTrue(noon.daylight() > midnight.daylight());
        assertTrue(noon.skyMultiplier() > midnight.skyMultiplier());
        assertTrue(noon.ambientMultiplier() > midnight.ambientMultiplier());
        assertTrue(midnight.ambientMultiplier() >= 0.5f);
        assertTrue(midnight.skyMultiplier() >= 0.25f);
        assertTrue(midnight.skyRed() <= 0.02f);
        assertTrue(midnight.skyGreen() <= 0.03f);
        assertTrue(midnight.skyBlue() <= 0.05f);
        assertTrue(midnight.skyBlue() > midnight.skyGreen());
        assertTrue(noon.skyBlue() > noon.skyRed() * 3.0f);
        assertTrue(noon.cloudRed() > midnight.cloudRed());
        assertTrue(midnight.cloudBlue() <= 0.10f);
    }

    @Test
    void cycleIsContinuousAtPhaseWrap() {
        DayNightVisualState before = DayNightCycle.sample(1.0 - 1.0e-7);
        DayNightVisualState after = DayNightCycle.sample(1.0e-7);

        assertEquals(before.sunX(), after.sunX(), 1.0e-5f);
        assertEquals(before.sunY(), after.sunY(), 1.0e-5f);
        assertEquals(before.daylight(), after.daylight(), 1.0e-5f);
    }
}
