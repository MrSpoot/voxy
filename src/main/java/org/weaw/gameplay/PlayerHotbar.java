package org.weaw.gameplay;

import org.weaw.game.utils.BlockCatalog;
import org.weaw.game.utils.BlockDefinition;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Session-local creative hotbar shared by gameplay and rendering. */
public final class PlayerHotbar {
    public static final int SLOT_COUNT = 9;

    private static final List<String> DEFAULT_BLOCKS = List.of(
            "voxy:grass_block",
            "voxy:dirt",
            "voxy:stone",
            "voxy:sand",
            "voxy:wood_log",
            "voxy:red_lamp",
            "voxy:green_lamp",
            "voxy:blue_lamp",
            "voxy:white_lamp"
    );

    private final BlockDefinition[] slots = new BlockDefinition[SLOT_COUNT];
    private int selectedIndex;

    public PlayerHotbar(BlockCatalog catalog) {
        Objects.requireNonNull(catalog, "catalog");
        Set<BlockDefinition> assigned = new LinkedHashSet<>();
        int targetSlot = 0;
        for (String stableId : DEFAULT_BLOCKS) {
            BlockDefinition block = catalog.getBlock(stableId);
            if (isSelectable(block) && assigned.add(block)) {
                slots[targetSlot++] = block;
            }
        }
        for (BlockDefinition block : catalog.getRegisteredBlocks().values()) {
            if (targetSlot >= SLOT_COUNT) {
                break;
            }
            if (isSelectable(block) && assigned.add(block)) {
                slots[targetSlot++] = block;
            }
        }
    }

    public BlockDefinition getSlot(int index) {
        validateIndex(index);
        return slots[index];
    }

    public void setSlot(int index, BlockDefinition block) {
        validateIndex(index);
        if (block != null && block.isAir()) {
            throw new IllegalArgumentException("Air cannot be assigned to the hotbar");
        }
        slots[index] = block;
    }

    public void swap(int firstIndex, int secondIndex) {
        validateIndex(firstIndex);
        validateIndex(secondIndex);
        BlockDefinition first = slots[firstIndex];
        slots[firstIndex] = slots[secondIndex];
        slots[secondIndex] = first;
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    public void select(int index) {
        validateIndex(index);
        selectedIndex = index;
    }

    public void cycle(int delta) {
        if (delta != 0) {
            selectedIndex = Math.floorMod(selectedIndex + delta, SLOT_COUNT);
        }
    }

    public BlockDefinition getSelectedBlock() {
        return slots[selectedIndex];
    }

    public BlockDefinition[] snapshot() {
        return slots.clone();
    }

    private static boolean isSelectable(BlockDefinition block) {
        return block != null && !block.isAir() && block.getTexturePath() != null;
    }

    private static void validateIndex(int index) {
        if (index < 0 || index >= SLOT_COUNT) {
            throw new IndexOutOfBoundsException("Hotbar index must be in range [0, 8], got " + index);
        }
    }
}
