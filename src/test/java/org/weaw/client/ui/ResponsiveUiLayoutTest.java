package org.weaw.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsiveUiLayoutTest {
    @Test
    void fittedWindowNeverLeavesViewport() {
        for (int[] size : new int[][]{
                {3840, 2160}, {1920, 1080}, {1280, 720}, {800, 450}, {480, 270}, {320, 180}
        }) {
            ResponsiveUiLayout layout = ResponsiveUiLayout.fit(
                    size[0], size[1], 780.0f, 690.0f, 2.0f, 16.0f
            );
            assertTrue(layout.scale() > 0.0f);
            assertTrue(layout.x() >= 0.0f);
            assertTrue(layout.y() >= 0.0f);
            assertTrue(layout.x() + layout.width() <= size[0]);
            assertTrue(layout.y() + layout.height() <= size[1]);
        }
    }
}
