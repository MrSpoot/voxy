package org.weaw.engine.graphics.pipeline.passes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WaterAnimationTest {

    @Test
    void advancesWithFrameDelta() {
        assertEquals(10.1, WaterAnimation.advanceWrapped(10.0, 0.1f), 0.0001);
    }

    @Test
    void wrapsAtAnimationPeriod() {
        assertEquals(0.15, WaterAnimation.advanceWrapped(WaterAnimation.LOOP_SECONDS - 0.1, 0.25f), 0.0001);
    }

    @Test
    void clampsLargeFramePauses() {
        assertEquals(1.25, WaterAnimation.advanceWrapped(1.0, 3.0f), 0.0001);
    }
}
