package org.weaw.persistence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GraphicsPreferencesTest {
    @Test
    void presetsHaveStablePlayerFacingMappings() {
        GraphicsPreferences low = GraphicsPreferences.forPreset(GraphicsPreset.LOW, GraphicsPreferences.defaults());
        assertEquals(AntiAliasingMode.OFF, low.antiAliasing());
        assertFalse(low.cloudsEnabled());
        assertFalse(low.waterWavesEnabled());
        assertTrue(low.lightingEnabled());

        GraphicsPreferences high = GraphicsPreferences.forPreset(GraphicsPreset.HIGH, low);
        assertEquals(AntiAliasingMode.MSAA_4X, high.antiAliasing());
        assertTrue(high.cloudsEnabled());
        assertTrue(high.waterWavesEnabled());
        assertEquals(low.gamma(), high.gamma());
    }

    @Test
    void detailedEditSwitchesToCustomAndClampsValues() {
        GraphicsPreferences custom = GraphicsPreferences.defaults().customized(
                AntiAliasingMode.MSAA_2X, true, false, true, true, true, false,
                99.0f, -1.0f, 5.0f, Float.NaN
        );
        assertEquals(GraphicsPreset.CUSTOM, custom.preset());
        assertEquals(4.0f, custom.exposure());
        assertEquals(0.5f, custom.contrast());
        assertEquals(2.0f, custom.saturation());
        assertEquals(2.2f, custom.gamma());
    }
}
