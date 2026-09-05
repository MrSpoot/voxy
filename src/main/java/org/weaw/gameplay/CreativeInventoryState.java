package org.weaw.gameplay;

import org.weaw.engine.ui.CreativeInventoryLayout;
import org.weaw.game.utils.BlockCatalog;
import org.weaw.game.utils.BlockDefinition;

import java.util.List;
import java.util.Objects;

/** Mutable UI state for the session-local creative inventory. */
public final class CreativeInventoryState {
    private final List<BlockDefinition> entries;
    private final PlayerHotbar hotbar;

    private boolean open;
    private int scrollRow;
    private int hoveredCreativeIndex = -1;
    private int hoveredHotbarIndex = -1;
    private BlockDefinition draggedBlock;
    private int draggedHotbarIndex = -1;
    private float cursorX;
    private float cursorY;

    public CreativeInventoryState(BlockCatalog catalog, PlayerHotbar hotbar) {
        Objects.requireNonNull(catalog, "catalog");
        this.hotbar = Objects.requireNonNull(hotbar, "hotbar");
        this.entries = catalog.getRegisteredBlocks().values().stream()
                .filter(block -> !block.isAir() && block.getTexturePath() != null)
                .toList();
    }

    public void open() {
        open = true;
    }

    public void close() {
        open = false;
        hoveredCreativeIndex = -1;
        hoveredHotbarIndex = -1;
        cancelDrag();
    }

    public boolean isOpen() {
        return open;
    }

    public void updatePointer(
            CreativeInventoryLayout layout,
            float mouseX,
            float mouseY,
            int scrollDelta,
            boolean primaryPressed,
            boolean primaryDown,
            boolean primaryReleased
    ) {
        if (!open) {
            return;
        }
        cursorX = mouseX;
        cursorY = mouseY;
        if (scrollDelta != 0) {
            scrollRow = Math.clamp(scrollRow - scrollDelta, 0, getMaxScrollRow());
        }
        hoveredCreativeIndex = layout.hitCreativeSlot(mouseX, mouseY, entries.size(), scrollRow);
        hoveredHotbarIndex = layout.hitHotbarSlot(mouseX, mouseY);

        if (primaryPressed && draggedBlock == null) {
            if (hoveredCreativeIndex >= 0) {
                draggedBlock = entries.get(hoveredCreativeIndex);
                draggedHotbarIndex = -1;
            } else if (hoveredHotbarIndex >= 0) {
                draggedBlock = hotbar.getSlot(hoveredHotbarIndex);
                draggedHotbarIndex = draggedBlock == null ? -1 : hoveredHotbarIndex;
            }
        }

        if (primaryReleased && draggedBlock != null) {
            if (hoveredHotbarIndex >= 0) {
                if (draggedHotbarIndex >= 0) {
                    hotbar.swap(draggedHotbarIndex, hoveredHotbarIndex);
                } else {
                    hotbar.setSlot(hoveredHotbarIndex, draggedBlock);
                }
            }
            cancelDrag();
        } else if (!primaryDown && !primaryPressed && draggedBlock != null) {
            cancelDrag();
        }
    }

    public int getTotalRows() {
        return (entries.size() + CreativeInventoryLayout.GRID_COLUMNS - 1)
                / CreativeInventoryLayout.GRID_COLUMNS;
    }

    public int getMaxScrollRow() {
        return Math.max(0, getTotalRows() - CreativeInventoryLayout.VISIBLE_ROWS);
    }

    public List<BlockDefinition> getEntries() {
        return entries;
    }

    public PlayerHotbar getHotbar() {
        return hotbar;
    }

    public int getScrollRow() {
        return scrollRow;
    }

    public int getHoveredCreativeIndex() {
        return hoveredCreativeIndex;
    }

    public int getHoveredHotbarIndex() {
        return hoveredHotbarIndex;
    }

    public BlockDefinition getDraggedBlock() {
        return draggedBlock;
    }

    public int getDraggedHotbarIndex() {
        return draggedHotbarIndex;
    }

    public float getCursorX() {
        return cursorX;
    }

    public float getCursorY() {
        return cursorY;
    }

    private void cancelDrag() {
        draggedBlock = null;
        draggedHotbarIndex = -1;
    }
}
