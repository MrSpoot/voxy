package org.weaw.gameplay;

import org.junit.jupiter.api.Test;
import org.weaw.engine.ui.CreativeInventoryLayout;
import org.weaw.engine.ui.CreativeInventoryLayout.Rect;
import org.weaw.game.utils.BlockCatalog;
import org.weaw.game.utils.BlockDefinition;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CreativeInventoryStateTest {
    @Test
    void creativeDragCopiesABlockIntoTheHotbar() {
        BlockCatalog catalog = BlockCatalog.createDefault();
        PlayerHotbar hotbar = new PlayerHotbar(catalog);
        CreativeInventoryState inventory = new CreativeInventoryState(catalog, hotbar);
        CreativeInventoryLayout layout = CreativeInventoryLayout.forViewport(1920, 1080, true);
        Rect source = layout.creativeSlot(0, 0);
        Rect target = layout.hotbarSlot(8);
        inventory.open();

        point(inventory, layout, source, true, true, false);
        point(inventory, layout, target, false, false, true);

        assertEquals(inventory.getEntries().getFirst(), hotbar.getSlot(8));
        assertNull(inventory.getDraggedBlock());
    }

    @Test
    void hotbarDragSwapsSlotsAndDroppingOutsideCancels() {
        BlockCatalog catalog = BlockCatalog.createDefault();
        PlayerHotbar hotbar = new PlayerHotbar(catalog);
        CreativeInventoryState inventory = new CreativeInventoryState(catalog, hotbar);
        CreativeInventoryLayout layout = CreativeInventoryLayout.forViewport(1920, 1080, true);
        BlockDefinition first = hotbar.getSlot(0);
        BlockDefinition second = hotbar.getSlot(1);
        inventory.open();

        point(inventory, layout, layout.hotbarSlot(0), true, true, false);
        point(inventory, layout, layout.hotbarSlot(1), false, false, true);
        assertEquals(second, hotbar.getSlot(0));
        assertEquals(first, hotbar.getSlot(1));

        point(inventory, layout, layout.hotbarSlot(0), true, true, false);
        inventory.updatePointer(layout, 1.0f, 1.0f, 0, false, false, true);
        assertEquals(second, hotbar.getSlot(0));
        assertNull(inventory.getDraggedBlock());
    }

    @Test
    void scrollIsClampedToTheAvailableRows() {
        List<BlockDefinition> definitions = new ArrayList<>();
        definitions.add(new BlockDefinition("voxy:air", null, BlockDefinition.TransparencyType.TRANSPARENT, false));
        for (int index = 0; index < 60; index++) {
            definitions.add(new BlockDefinition(
                    "test:block_" + index,
                    "/textures/test.png",
                    BlockDefinition.TransparencyType.OPAQUE,
                    true
            ));
        }
        BlockCatalog catalog = BlockCatalog.create(definitions);
        CreativeInventoryState inventory = new CreativeInventoryState(catalog, new PlayerHotbar(catalog));
        CreativeInventoryLayout layout = CreativeInventoryLayout.forViewport(1920, 1080, true);
        inventory.open();

        inventory.updatePointer(layout, 0.0f, 0.0f, -100, false, false, false);
        assertNotEquals(0, inventory.getMaxScrollRow());
        assertEquals(inventory.getMaxScrollRow(), inventory.getScrollRow());

        inventory.updatePointer(layout, 0.0f, 0.0f, 100, false, false, false);
        assertEquals(0, inventory.getScrollRow());
    }

    private static void point(
            CreativeInventoryState inventory,
            CreativeInventoryLayout layout,
            Rect rect,
            boolean pressed,
            boolean down,
            boolean released
    ) {
        inventory.updatePointer(
                layout,
                rect.x() + rect.width() * 0.5f,
                rect.y() + rect.height() * 0.5f,
                0,
                pressed,
                down,
                released
        );
    }
}
