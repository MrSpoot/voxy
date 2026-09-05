package org.weaw.engine.graphics.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LightingSettingsTest {

    @Test
    void usesReadableZeroLightShadowByDefault() {
        LightingSettings settings = new LightingSettings();

        assertEquals(0.8f, settings.getShadowStrength(), 0.0001f);
        assertEquals(0.35f, settings.getSkyIntensity(), 0.0001f);
        assertEquals(0.85f, settings.getVoxelLightGamma(), 0.0001f);
        assertEquals(0.04f, settings.getVoxelDarknessFloor(), 0.0001f);
        assertTrue(settings.isDistanceSofteningEnabled());
        assertEquals(0.35f, settings.getDistanceSofteningStartRatio(), 0.0001f);
        assertEquals(0.80f, settings.getDistanceSofteningEndRatio(), 0.0001f);
        assertEquals(0.20f, settings.getDistantDirectionalStrength(), 0.0001f);
        assertEquals(0.25f, settings.getDistantAoStrength(), 0.0001f);
    }

    @Test
    void resetRestoresShadowStrength() {
        LightingSettings settings = new LightingSettings();
        settings.shadowStrengthRef()[0] = 0.1f;
        settings.skyIntensityRef()[0] = 2.0f;
        settings.voxelLightGammaRef()[0] = 2.5f;
        settings.voxelDarknessFloorRef()[0] = 0.2f;
        settings.setDistanceSofteningEnabled(false);
        settings.distanceSofteningStartRatioRef()[0] = 0.8f;
        settings.distanceSofteningEndRatioRef()[0] = 0.9f;
        settings.distantDirectionalStrengthRef()[0] = 0.9f;
        settings.distantAoStrengthRef()[0] = 0.9f;

        settings.reset();

        assertEquals(0.8f, settings.getShadowStrength(), 0.0001f);
        assertEquals(0.35f, settings.getSkyIntensity(), 0.0001f);
        assertEquals(0.85f, settings.getVoxelLightGamma(), 0.0001f);
        assertEquals(0.04f, settings.getVoxelDarknessFloor(), 0.0001f);
        assertTrue(settings.isDistanceSofteningEnabled());
        assertEquals(0.35f, settings.getDistanceSofteningStartRatio(), 0.0001f);
        assertEquals(0.80f, settings.getDistanceSofteningEndRatio(), 0.0001f);
        assertEquals(0.20f, settings.getDistantDirectionalStrength(), 0.0001f);
        assertEquals(0.25f, settings.getDistantAoStrength(), 0.0001f);
    }

    @Test
    void clampsDistanceSofteningInputs() {
        LightingSettings settings = new LightingSettings();
        settings.distanceSofteningStartRatioRef()[0] = 2.0f;
        settings.distanceSofteningEndRatioRef()[0] = -1.0f;
        settings.distantDirectionalStrengthRef()[0] = -1.0f;
        settings.distantAoStrengthRef()[0] = 2.0f;

        assertEquals(0.95f, settings.getDistanceSofteningStartRatio(), 0.0001f);
        assertEquals(1.0f, settings.getDistanceSofteningEndRatio(), 0.0001f);
        assertEquals(0.0f, settings.getDistantDirectionalStrength(), 0.0001f);
        assertEquals(1.0f, settings.getDistantAoStrength(), 0.0001f);
    }
}
