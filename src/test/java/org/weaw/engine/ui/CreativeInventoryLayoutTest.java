package org.weaw.engine.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreativeInventoryLayoutTest {
    @Test
    void exposesNineNonOverlappingColumnsAndAccurateHitTesting() {
        CreativeInventoryLayout layout = CreativeInventoryLayout.forViewport(1920, 1080, true);
        CreativeInventoryLayout.Rect first = layout.creativeSlot(0, 0);
        CreativeInventoryLayout.Rect last = layout.creativeSlot(8, 0);

        assertTrue(first.right() < last.x());
        assertEquals(0, layout.hitCreativeSlot(first.x() + 1.0f, first.y() + 1.0f, 45, 0));
        assertEquals(8, layout.hitCreativeSlot(last.x() + 1.0f, last.y() + 1.0f, 45, 0));
        assertEquals(-1, layout.hitCreativeSlot(first.right() + 1.0f, first.y() + 1.0f, 45, 0));
    }

    @Test
    void scalesAndKeepsThePanelInsideCommonFramebufferSizes() {
        CreativeInventoryLayout small = CreativeInventoryLayout.forViewport(800, 600, true);
        CreativeInventoryLayout reference = CreativeInventoryLayout.forViewport(1920, 1080, true);
        CreativeInventoryLayout large = CreativeInventoryLayout.forViewport(3840, 2160, true);

        assertEquals(800.0f / 1920.0f, small.scale());
        assertEquals(1.0f, reference.scale());
        assertEquals(2.0f, large.scale());
        assertFalse(small.panel().x() < 0.0f);
        assertFalse(small.panel().y() < 0.0f);
        assertTrue(small.panel().right() <= small.viewportWidth());
        assertTrue(small.panel().bottom() <= small.viewportHeight());
    }

    @Test
    void keepsInventoryAndHotbarInsideVerySmallViewports() {
        for (int[] size : new int[][]{{480, 270}, {320, 180}}) {
            CreativeInventoryLayout layout = CreativeInventoryLayout.forViewport(size[0], size[1], 2.0f, true);
            assertTrue(layout.scale() > 0.0f);
            assertTrue(layout.panel().x() >= 0.0f);
            assertTrue(layout.panel().y() >= 0.0f);
            assertTrue(layout.panel().right() <= size[0]);
            assertTrue(layout.panel().bottom() <= size[1]);
            assertTrue(layout.hotbar().right() <= size[0]);
            assertTrue(layout.hotbar().bottom() <= size[1]);
        }
    }
}
