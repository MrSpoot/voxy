package org.weaw.gameplay;

import org.junit.jupiter.api.Test;
import org.weaw.game.utils.BlockCatalog;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerHotbarTest {
    @Test
    void initializesTheNinePreferredCreativeBlocksInOrder() {
        PlayerHotbar hotbar = new PlayerHotbar(BlockCatalog.createDefault());

        assertEquals(List.of(
                "voxy:grass_block",
                "voxy:dirt",
                "voxy:stone",
                "voxy:sand",
                "voxy:wood_log",
                "voxy:red_lamp",
                "voxy:green_lamp",
                "voxy:blue_lamp",
                "voxy:white_lamp"
        ), Arrays.stream(hotbar.snapshot()).map(block -> block.getStableId()).toList());
    }

    @Test
    void selectionWrapsAndSlotsCanBeSwapped() {
        PlayerHotbar hotbar = new PlayerHotbar(BlockCatalog.createDefault());
        String first = hotbar.getSlot(0).getStableId();
        String second = hotbar.getSlot(1).getStableId();

        hotbar.cycle(-1);
        assertEquals(8, hotbar.getSelectedIndex());
        hotbar.cycle(2);
        assertEquals(1, hotbar.getSelectedIndex());

        hotbar.swap(0, 1);
        assertEquals(second, hotbar.getSlot(0).getStableId());
        assertEquals(first, hotbar.getSlot(1).getStableId());
    }

    @Test
    void refusesAirAssignments() {
        BlockCatalog catalog = BlockCatalog.createDefault();
        PlayerHotbar hotbar = new PlayerHotbar(catalog);

        assertThrows(IllegalArgumentException.class, () -> hotbar.setSlot(0, catalog.air()));
    }
}
