package org.weaw.engine.graphics.pipeline.passes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloudMotionTest {

    @Test
    void advancesTowardPositiveWorldX() {
        double offset = CloudMotion.advanceWrapped(10.0, 0.1f, 2.0f, 16.0f);

        assertEquals(10.2, offset, 0.0001);
    }

    @Test
    void wrapsOnTheProceduralPatternPeriod() {
        double period = 16.0 * CloudMotion.PATTERN_PERIOD_CELLS;
        double offset = CloudMotion.advanceWrapped(period - 0.25, 0.25f, 2.0f, 16.0f);

        assertEquals(0.25, offset, 0.0001);
    }

    @Test
    void clampsLargeFramePauses() {
        double offset = CloudMotion.advanceWrapped(0.0, 2.0f, 4.0f, 16.0f);

        assertEquals(1.0, offset, 0.0001);
    }
}
