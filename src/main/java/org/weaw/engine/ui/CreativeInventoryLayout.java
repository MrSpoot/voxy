package org.weaw.engine.ui;

import org.weaw.gameplay.PlayerHotbar;

/** Responsive framebuffer-space layout shared by inventory input and rendering. */
public final class CreativeInventoryLayout {
    public static final int GRID_COLUMNS = 9;
    public static final int VISIBLE_ROWS = 5;

    private static final float BASE_SLOT_SIZE = 48.0f;
    private static final float BASE_GAP = 4.0f;
    private static final float BASE_PADDING = 18.0f;
    private static final float BASE_GRID_HOTBAR_GAP = 20.0f;
    private static final float BASE_BOTTOM_OFFSET = 28.0f;
    private static final float BASE_SCROLLBAR_WIDTH = 8.0f;
    private static final float BASE_SCROLLBAR_GAP = 10.0f;

    private final float viewportWidth;
    private final float viewportHeight;
    private final float scale;
    private final float slotSize;
    private final float gap;
    private final Rect panel;
    private final Rect grid;
    private final Rect hotbar;
    private final Rect scrollbarTrack;

    private CreativeInventoryLayout(int viewportWidth, int viewportHeight, boolean inventoryOpen) {
        this.viewportWidth = Math.max(1, viewportWidth);
        this.viewportHeight = Math.max(1, viewportHeight);
        this.scale = Math.clamp(
                Math.min(this.viewportWidth / 1920.0f, this.viewportHeight / 1080.0f),
                0.75f,
                1.25f
        );
        this.slotSize = BASE_SLOT_SIZE * scale;
        this.gap = BASE_GAP * scale;
        float padding = BASE_PADDING * scale;
        float gridHotbarGap = BASE_GRID_HOTBAR_GAP * scale;
        float gridWidth = GRID_COLUMNS * slotSize + (GRID_COLUMNS - 1) * gap;
        float gridHeight = VISIBLE_ROWS * slotSize + (VISIBLE_ROWS - 1) * gap;
        float scrollbarGap = BASE_SCROLLBAR_GAP * scale;
        float scrollbarWidth = BASE_SCROLLBAR_WIDTH * scale;
        float panelWidth = padding * 2.0f + gridWidth + scrollbarGap + scrollbarWidth;
        float panelHeight = padding * 2.0f + gridHeight + gridHotbarGap + slotSize;
        float panelX = (this.viewportWidth - panelWidth) * 0.5f;
        float panelY = (this.viewportHeight - panelHeight) * 0.5f;
        this.panel = new Rect(panelX, panelY, panelWidth, panelHeight);
        this.grid = new Rect(panelX + padding, panelY + padding, gridWidth, gridHeight);
        this.scrollbarTrack = new Rect(grid.right() + scrollbarGap, grid.y(), scrollbarWidth, gridHeight);

        float hotbarX = (this.viewportWidth - gridWidth) * 0.5f;
        float hotbarY = inventoryOpen
                ? grid.bottom() + gridHotbarGap
                : this.viewportHeight - BASE_BOTTOM_OFFSET * scale - slotSize;
        this.hotbar = new Rect(hotbarX, hotbarY, gridWidth, slotSize);
    }

    public static CreativeInventoryLayout forViewport(int width, int height, boolean inventoryOpen) {
        return new CreativeInventoryLayout(width, height, inventoryOpen);
    }

    public Rect creativeSlot(int column, int visibleRow) {
        if (column < 0 || column >= GRID_COLUMNS || visibleRow < 0 || visibleRow >= VISIBLE_ROWS) {
            throw new IndexOutOfBoundsException("Creative inventory slot outside visible grid");
        }
        return new Rect(
                grid.x() + column * (slotSize + gap),
                grid.y() + visibleRow * (slotSize + gap),
                slotSize,
                slotSize
        );
    }

    public Rect hotbarSlot(int index) {
        if (index < 0 || index >= PlayerHotbar.SLOT_COUNT) {
            throw new IndexOutOfBoundsException("Hotbar slot outside range [0, 8]");
        }
        return new Rect(hotbar.x() + index * (slotSize + gap), hotbar.y(), slotSize, slotSize);
    }

    public int hitCreativeSlot(float x, float y, int itemCount, int scrollRow) {
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            for (int column = 0; column < GRID_COLUMNS; column++) {
                int itemIndex = (Math.max(0, scrollRow) + row) * GRID_COLUMNS + column;
                if (itemIndex >= itemCount) {
                    return -1;
                }
                if (creativeSlot(column, row).contains(x, y)) {
                    return itemIndex;
                }
            }
        }
        return -1;
    }

    public int hitHotbarSlot(float x, float y) {
        for (int index = 0; index < PlayerHotbar.SLOT_COUNT; index++) {
            if (hotbarSlot(index).contains(x, y)) {
                return index;
            }
        }
        return -1;
    }

    public Rect scrollbarThumb(int totalRows, int scrollRow) {
        if (totalRows <= VISIBLE_ROWS) {
            return scrollbarTrack;
        }
        float visibleRatio = VISIBLE_ROWS / (float) totalRows;
        float thumbHeight = Math.max(slotSize * 0.5f, scrollbarTrack.height() * visibleRatio);
        float travel = scrollbarTrack.height() - thumbHeight;
        float progress = Math.clamp(scrollRow / (float) (totalRows - VISIBLE_ROWS), 0.0f, 1.0f);
        return new Rect(scrollbarTrack.x(), scrollbarTrack.y() + travel * progress, scrollbarTrack.width(), thumbHeight);
    }

    public float viewportWidth() {
        return viewportWidth;
    }

    public float viewportHeight() {
        return viewportHeight;
    }

    public float scale() {
        return scale;
    }

    public float slotSize() {
        return slotSize;
    }

    public Rect panel() {
        return panel;
    }

    public Rect grid() {
        return grid;
    }

    public Rect hotbar() {
        return hotbar;
    }

    public Rect scrollbarTrack() {
        return scrollbarTrack;
    }

    public record Rect(float x, float y, float width, float height) {
        public boolean contains(float pointX, float pointY) {
            return pointX >= x && pointX < right() && pointY >= y && pointY < bottom();
        }

        public float right() {
            return x + width;
        }

        public float bottom() {
            return y + height;
        }

        public Rect inset(float amount) {
            float clamped = Math.max(0.0f, Math.min(amount, Math.min(width, height) * 0.5f));
            return new Rect(x + clamped, y + clamped, width - clamped * 2.0f, height - clamped * 2.0f);
        }
    }
}
