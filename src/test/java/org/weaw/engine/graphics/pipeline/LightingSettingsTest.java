package org.weaw.engine.graphics.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LightingSettingsTest {

    @Test
    void usesReadableZeroLightShadowByDefault() {
        LightingSettings settings = new LightingSettings();

        assertEquals(0.8f, settings.getShadowStrength(), 0.0001f);
        assertEquals(0.35f, settings.getSkyIntensity(), 0.0001f);
        assertEquals(0.85f, settings.getVoxelLightGamma(), 0.0001f);
        assertEquals(0.04f, settings.getVoxelDarknessFloor(), 0.0001f);
    }

    @Test
    void resetRestoresShadowStrength() {
        LightingSettings settings = new LightingSettings();
        settings.shadowStrengthRef()[0] = 0.1f;
        settings.skyIntensityRef()[0] = 2.0f;
        settings.voxelLightGammaRef()[0] = 2.5f;
        settings.voxelDarknessFloorRef()[0] = 0.2f;

        settings.reset();

        assertEquals(0.8f, settings.getShadowStrength(), 0.0001f);
        assertEquals(0.35f, settings.getSkyIntensity(), 0.0001f);
        assertEquals(0.85f, settings.getVoxelLightGamma(), 0.0001f);
        assertEquals(0.04f, settings.getVoxelDarknessFloor(), 0.0001f);
    }
}
