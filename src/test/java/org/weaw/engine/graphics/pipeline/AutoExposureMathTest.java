package org.weaw.engine.graphics.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoExposureMathTest {

    @Test
    void computesExposureFromGeometricAverageLuminance() {
        assertEquals(0.0f, AutoExposureMath.targetExposureEv(0.18f, 0.18f, 0.0f, -4.0f, 4.0f), 0.0001f);
        assertEquals(-2.0f, AutoExposureMath.targetExposureEv(0.72f, 0.18f, 0.0f, -4.0f, 4.0f), 0.0001f);
        assertEquals(2.0f, AutoExposureMath.targetExposureEv(0.045f, 0.18f, 0.0f, -4.0f, 4.0f), 0.0001f);
        assertEquals(1.0f, AutoExposureMath.targetExposureEv(0.18f, 0.18f, 1.0f, -4.0f, 4.0f), 0.0001f);
    }

    @Test
    void clampsExposureAndHandlesInvalidInputs() {
        assertEquals(-4.0f, AutoExposureMath.targetExposureEv(1000.0f, 0.18f, 0.0f, -4.0f, 4.0f), 0.0001f);
        assertEquals(4.0f, AutoExposureMath.targetExposureEv(0.0f, 0.18f, 0.0f, -4.0f, 4.0f), 0.0001f);
        assertTrue(Float.isFinite(AutoExposureMath.targetExposureEv(
                Float.NaN,
                Float.NaN,
                Float.NaN,
                Float.NaN,
                Float.NaN
        )));
    }

    @Test
    void daylightFloorPreservesThePreviousManualExposure() {
        float daylightExposure = AutoExposureMath.targetExposureEv(
                1000.0f,
                0.18f,
                0.045f,
                0.045f,
                4.0f
        );
        float darkExposure = AutoExposureMath.targetExposureEv(
                0.045f,
                0.18f,
                0.045f,
                0.045f,
                4.0f
        );

        assertEquals(0.045f, daylightExposure, 0.0001f);
        assertTrue(darkExposure > daylightExposure);
        assertTrue(darkExposure <= 4.0f);
    }

    @Test
    void temporalAdaptationIsStableAcrossFrameSubdivision() {
        float oneFrame = AutoExposureMath.adaptExposure(0.0f, 3.0f, 0.1f, 3.0f, 1.0f);
        float firstHalf = AutoExposureMath.adaptExposure(0.0f, 3.0f, 0.05f, 3.0f, 1.0f);
        float twoFrames = AutoExposureMath.adaptExposure(firstHalf, 3.0f, 0.05f, 3.0f, 1.0f);

        assertEquals(oneFrame, twoFrames, 0.0001f);
    }

    @Test
    void reactsFasterWhenDarkeningAndClampsLongFrameTimes() {
        float darkened = AutoExposureMath.adaptExposure(0.0f, -2.0f, 0.1f, 3.0f, 1.0f);
        float brightened = AutoExposureMath.adaptExposure(0.0f, 2.0f, 0.1f, 3.0f, 1.0f);

        assertTrue(Math.abs(darkened) > Math.abs(brightened));
        assertEquals(
                AutoExposureMath.adaptExposure(0.0f, 2.0f, 0.1f, 3.0f, 1.0f),
                AutoExposureMath.adaptExposure(0.0f, 2.0f, 10.0f, 3.0f, 1.0f),
                0.0001f
        );
    }
}
