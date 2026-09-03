package org.weaw.engine.graphics.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ColorGradingSettingsTest {

    @Test
    void sanitizesAutoExposureParameters() {
        ColorGradingSettings settings = new ColorGradingSettings();
        settings.minimumExposureEvRef()[0] = Float.NaN;
        settings.maximumExposureEvRef()[0] = 50.0f;
        settings.targetLuminanceRef()[0] = -1.0f;
        settings.darkenAdaptationSpeedRef()[0] = Float.POSITIVE_INFINITY;
        settings.brightenAdaptationSpeedRef()[0] = 0.0f;

        settings.sanitizeAutoExposure();

        assertEquals(-12.0f, settings.getMinimumExposureEv(), 0.0001f);
        assertEquals(12.0f, settings.getMaximumExposureEv(), 0.0001f);
        assertEquals(0.01f, settings.getTargetLuminance(), 0.0001f);
        assertEquals(0.01f, settings.getDarkenAdaptationSpeed(), 0.0001f);
        assertEquals(0.01f, settings.getBrightenAdaptationSpeed(), 0.0001f);
    }

    @Test
    void resetRestoresAutoExposureDefaults() {
        ColorGradingSettings settings = new ColorGradingSettings();
        settings.setAutoExposureEnabled(true);
        settings.minimumExposureEvRef()[0] = -8.0f;
        settings.maximumExposureEvRef()[0] = 8.0f;
        settings.targetLuminanceRef()[0] = 0.5f;
        settings.darkenAdaptationSpeedRef()[0] = 9.0f;
        settings.brightenAdaptationSpeedRef()[0] = 9.0f;

        settings.reset();

        assertFalse(settings.isAutoExposureEnabled());
        assertEquals(0.045f, settings.getExposure(), 0.0001f);
        assertEquals(0.045f, settings.getMinimumExposureEv(), 0.0001f);
        assertEquals(4.0f, settings.getMaximumExposureEv(), 0.0001f);
        assertEquals(0.18f, settings.getTargetLuminance(), 0.0001f);
        assertEquals(3.0f, settings.getDarkenAdaptationSpeed(), 0.0001f);
        assertEquals(1.0f, settings.getBrightenAdaptationSpeed(), 0.0001f);
        assertEquals(1.18f, settings.getContrast(), 0.0001f);
        assertEquals(1.05f, settings.getSaturation(), 0.0001f);
        assertEquals(0.14f, settings.getVibrance(), 0.0001f);
        assertEquals(1.0f, settings.getGamma(), 0.0001f);
        assertEquals(0.05f, settings.getTemperature(), 0.0001f);
    }

    @Test
    void keepsExposureBoundsOrdered() {
        ColorGradingSettings settings = new ColorGradingSettings();
        settings.minimumExposureEvRef()[0] = 3.0f;
        settings.maximumExposureEvRef()[0] = -2.0f;

        settings.sanitizeAutoExposure();

        assertEquals(3.0f, settings.getMinimumExposureEv(), 0.0001f);
        assertEquals(3.0f, settings.getMaximumExposureEv(), 0.0001f);
    }
}
