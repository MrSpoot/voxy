package org.weaw.engine.graphics.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WaterSettingsTest {

    @Test
    void usesSubtleWaveDefaults() {
        WaterSettings settings = new WaterSettings();

        assertTrue(settings.areWavesEnabled());
        assertEquals(WaterSettings.DEFAULT_SURFACE_INSET, settings.getSurfaceInset(), 0.0001f);
        assertEquals(WaterSettings.DEFAULT_WAVE_AMPLITUDE, settings.getWaveAmplitude(), 0.0001f);
        assertEquals(WaterSettings.DEFAULT_WAVE_SPEED, settings.getWaveSpeed(), 0.0001f);
        assertEquals(WaterSettings.DEFAULT_WAVE_LENGTH, settings.getWaveLength(), 0.0001f);
    }

    @Test
    void resetRestoresEveryWaterParameter() {
        WaterSettings settings = new WaterSettings();
        settings.setWavesEnabled(false);
        settings.surfaceInsetRef()[0] = 0.4f;
        settings.waveAmplitudeRef()[0] = 0.1f;
        settings.waveSpeedRef()[0] = 3.0f;
        settings.waveLengthRef()[0] = 24.0f;

        settings.reset();

        assertTrue(settings.areWavesEnabled());
        assertEquals(WaterSettings.DEFAULT_SURFACE_INSET, settings.getSurfaceInset(), 0.0001f);
        assertEquals(WaterSettings.DEFAULT_WAVE_AMPLITUDE, settings.getWaveAmplitude(), 0.0001f);
        assertEquals(WaterSettings.DEFAULT_WAVE_SPEED, settings.getWaveSpeed(), 0.0001f);
        assertEquals(WaterSettings.DEFAULT_WAVE_LENGTH, settings.getWaveLength(), 0.0001f);
    }
}
