package org.weaw.engine.graphics.pipeline;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdaptiveQualityControllerTest {
    @Test
    void usesHysteresisToDowngradeAndRecover() {
        AdaptiveGraphicsQuality quality = new AdaptiveGraphicsQuality();
        AdaptiveQualityController controller = new AdaptiveQualityController();

        for (int index = 0; index < 21; index++) {
            controller.update(quality, 9_000_000L, 0.1f);
        }
        assertEquals(AdaptiveGraphicsQuality.Level.MEDIUM, quality.getLevel());

        for (int index = 0; index < 51; index++) {
            controller.update(quality, 6_000_000L, 0.1f);
        }
        assertEquals(AdaptiveGraphicsQuality.Level.HIGH, quality.getLevel());
    }

    @Test
    void disabledQualityRemainsHigh() {
        AdaptiveGraphicsQuality quality = new AdaptiveGraphicsQuality();
        quality.setEnabled(false);
        AdaptiveQualityController controller = new AdaptiveQualityController();

        for (int index = 0; index < 100; index++) {
            controller.update(quality, 20_000_000L, 0.1f);
        }

        assertEquals(AdaptiveGraphicsQuality.Level.HIGH, quality.getLevel());
    }
}
