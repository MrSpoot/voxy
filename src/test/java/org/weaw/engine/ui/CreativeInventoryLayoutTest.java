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

        assertEquals(0.75f, small.scale());
        assertEquals(1.0f, reference.scale());
        assertEquals(1.25f, large.scale());
        assertFalse(small.panel().x() < 0.0f);
        assertFalse(small.panel().y() < 0.0f);
        assertTrue(small.panel().right() <= small.viewportWidth());
        assertTrue(small.panel().bottom() <= small.viewportHeight());
    }
}
