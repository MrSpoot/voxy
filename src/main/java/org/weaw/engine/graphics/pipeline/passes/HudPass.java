package org.weaw.engine.graphics.pipeline.passes;

import org.lwjgl.system.MemoryUtil;
import org.weaw.engine.graphics.pipeline.RenderContext;
import org.weaw.engine.graphics.pipeline.RenderPass;
import org.weaw.engine.graphics.pipeline.resources.GLStateManager;
import org.weaw.engine.graphics.utils.Shader;
import org.weaw.engine.ui.CreativeInventoryLayout;
import org.weaw.engine.ui.CreativeInventoryLayout.Rect;
import org.weaw.game.utils.BlockDefinition;
import org.weaw.gameplay.CreativeInventoryState;
import org.weaw.gameplay.PlayerHotbar;

import java.nio.FloatBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_FILL;
import static org.lwjgl.opengl.GL11.GL_LINES;
import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_TRIANGLE_STRIP;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glBufferSubData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_TEXTURE_2D_ARRAY;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;
import static org.lwjgl.opengl.GL31.glDrawArraysInstanced;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43.glBindBufferBase;

/** Draws the crosshair, nine-slot hotbar, and creative inventory without ImGui. */
public final class HudPass implements RenderPass {
    private static final int RECT_BUFFER_BINDING = 4;
    private static final int ICON_BUFFER_BINDING = 5;
    private static final int RECT_FLOATS = 8;
    private static final int ICON_FLOATS = 4;
    private static final int MAX_RECTS = 256;
    private static final int MAX_ICONS = 64;

    private Shader crosshairShader;
    private Shader rectangleShader;
    private Shader blockIconShader;
    private int vao;
    private int rectangleBuffer;
    private int iconBuffer;
    private FloatBuffer rectangles;
    private FloatBuffer icons;

    @Override
    public String getName() {
        return "HudPass";
    }

    @Override
    public void create() {
        crosshairShader = new Shader("/shaders/hud-crosshair.glsl");
        rectangleShader = new Shader("/shaders/hud-ui-rect.glsl");
        blockIconShader = new Shader("/shaders/hud-block-icon.glsl");
        vao = glGenVertexArrays();

        rectangles = MemoryUtil.memAllocFloat(MAX_RECTS * RECT_FLOATS);
        rectangleBuffer = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, rectangleBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, (long) MAX_RECTS * RECT_FLOATS * Float.BYTES, GL_DYNAMIC_DRAW);

        icons = MemoryUtil.memAllocFloat(MAX_ICONS * ICON_FLOATS);
        iconBuffer = glGenBuffers();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, iconBuffer);
        glBufferData(GL_SHADER_STORAGE_BUFFER, (long) MAX_ICONS * ICON_FLOATS * Float.BYTES, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }

    @Override
    public void execute(RenderContext context) {
        CreativeInventoryState inventory = context.getCreativeInventoryState();
        if (inventory == null) {
            return;
        }

        int width = context.getViewportWidth();
        int height = context.getViewportHeight();
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        GLStateManager.setViewport(width, height);
        GLStateManager.setDepthTest(false, false);
        GLStateManager.setBlending(true);
        GLStateManager.setCulling(false);
        int previousPolygonMode = GLStateManager.getPolygonMode();
        GLStateManager.setPolygonMode(GL_FILL);
        glBindVertexArray(vao);

        if (!inventory.isOpen()) {
            renderCrosshair(width, height);
        }

        CreativeInventoryLayout layout = CreativeInventoryLayout.forViewport(width, height, inventory.isOpen());
        int rectangleCount = buildRectangles(inventory, layout);
        renderRectangles(width, height, rectangleCount);
        int iconCount = buildIcons(inventory, layout);
        renderIcons(context, width, height, iconCount);

        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, RECT_BUFFER_BINDING, 0);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, ICON_BUFFER_BINDING, 0);
        glBindVertexArray(0);
        GLStateManager.setPolygonMode(previousPolygonMode);
    }

    private void renderCrosshair(int width, int height) {
        crosshairShader.useProgram();
        crosshairShader.setUniform("uViewport", (float) width, (float) height, 0.0f);
        crosshairShader.setUniform("uColor", 1.0f, 1.0f, 1.0f);
        crosshairShader.setUniform("uAlpha", 0.55f);
        crosshairShader.setUniform("uHalfLengthPx", 5.0f);
        crosshairShader.setUniform("uGapPx", 3.0f);
        glDrawArrays(GL_LINES, 0, 8);
        crosshairShader.unbind();
    }

    private int buildRectangles(CreativeInventoryState inventory, CreativeInventoryLayout layout) {
        rectangles.clear();
        float scale = layout.scale();
        if (inventory.isOpen()) {
            addRectangle(new Rect(0.0f, 0.0f, layout.viewportWidth(), layout.viewportHeight()), 0.0f, 0.0f, 0.0f, 0.58f);
            addRectangle(layout.panel(), 0.06f, 0.07f, 0.09f, 0.98f);
            addRectangle(layout.panel().inset(2.0f * scale), 0.16f, 0.17f, 0.19f, 0.98f);

            for (int row = 0; row < CreativeInventoryLayout.VISIBLE_ROWS; row++) {
                for (int column = 0; column < CreativeInventoryLayout.GRID_COLUMNS; column++) {
                    int itemIndex = (inventory.getScrollRow() + row) * CreativeInventoryLayout.GRID_COLUMNS + column;
                    if (itemIndex >= inventory.getEntries().size()) {
                        break;
                    }
                    addSlot(layout.creativeSlot(column, row), itemIndex == inventory.getHoveredCreativeIndex(), false, scale);
                }
            }

            if (inventory.getTotalRows() > CreativeInventoryLayout.VISIBLE_ROWS) {
                addRectangle(layout.scrollbarTrack(), 0.07f, 0.08f, 0.10f, 0.9f);
                addRectangle(layout.scrollbarThumb(inventory.getTotalRows(), inventory.getScrollRow()), 0.52f, 0.55f, 0.61f, 0.95f);
            }
        }

        for (int index = 0; index < PlayerHotbar.SLOT_COUNT; index++) {
            addSlot(
                    layout.hotbarSlot(index),
                    inventory.isOpen() && index == inventory.getHoveredHotbarIndex(),
                    index == inventory.getHotbar().getSelectedIndex(),
                    scale
            );
        }
        return rectangles.position() / RECT_FLOATS;
    }

    private void addSlot(Rect bounds, boolean hovered, boolean selected, float scale) {
        if (selected) {
            addRectangle(bounds, 0.96f, 0.89f, 0.63f, 0.98f);
        } else if (hovered) {
            addRectangle(bounds, 0.48f, 0.70f, 0.94f, 0.98f);
        } else {
            addRectangle(bounds, 0.045f, 0.05f, 0.06f, 0.94f);
        }
        addRectangle(bounds.inset((selected ? 3.0f : 2.0f) * scale), 0.18f, 0.19f, 0.21f, 0.94f);
    }

    private void addRectangle(Rect bounds, float red, float green, float blue, float alpha) {
        if (rectangles.remaining() < RECT_FLOATS) {
            throw new IllegalStateException("Creative inventory rectangle batch exceeded capacity");
        }
        rectangles.put(bounds.x()).put(bounds.y()).put(bounds.width()).put(bounds.height());
        rectangles.put(red).put(green).put(blue).put(alpha);
    }

    private void renderRectangles(int width, int height, int rectangleCount) {
        if (rectangleCount == 0) {
            return;
        }
        rectangles.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, rectangleBuffer);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, rectangles);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, RECT_BUFFER_BINDING, rectangleBuffer);
        rectangleShader.useProgram();
        rectangleShader.setUniform("uViewport", (float) width, (float) height, 0.0f);
        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, rectangleCount);
        rectangleShader.unbind();
    }

    private int buildIcons(CreativeInventoryState inventory, CreativeInventoryLayout layout) {
        icons.clear();
        if (inventory.isOpen()) {
            List<BlockDefinition> entries = inventory.getEntries();
            for (int row = 0; row < CreativeInventoryLayout.VISIBLE_ROWS; row++) {
                for (int column = 0; column < CreativeInventoryLayout.GRID_COLUMNS; column++) {
                    int itemIndex = (inventory.getScrollRow() + row) * CreativeInventoryLayout.GRID_COLUMNS + column;
                    if (itemIndex >= entries.size()) {
                        break;
                    }
                    addIcon(layout.creativeSlot(column, row).inset(layout.slotSize() * 0.12f), entries.get(itemIndex));
                }
            }
        }

        for (int index = 0; index < PlayerHotbar.SLOT_COUNT; index++) {
            BlockDefinition block = inventory.getHotbar().getSlot(index);
            if (block != null && !(inventory.isOpen() && index == inventory.getDraggedHotbarIndex())) {
                addIcon(layout.hotbarSlot(index).inset(layout.slotSize() * 0.12f), block);
            }
        }

        if (inventory.isOpen() && inventory.getDraggedBlock() != null) {
            float size = layout.slotSize() * 0.9f;
            addIcon(new Rect(inventory.getCursorX() - size * 0.5f, inventory.getCursorY() - size * 0.5f, size, size), inventory.getDraggedBlock());
        }
        return icons.position() / ICON_FLOATS;
    }

    private void addIcon(Rect bounds, BlockDefinition block) {
        if (icons.remaining() < ICON_FLOATS) {
            throw new IllegalStateException("Creative inventory icon batch exceeded capacity");
        }
        float size = Math.min(bounds.width(), bounds.height());
        icons.put(bounds.x()).put(bounds.y()).put(size).put(block.getTextureIndex());
    }

    private void renderIcons(RenderContext context, int width, int height, int iconCount) {
        if (iconCount == 0 || context.getBlockTextureManager() == null) {
            return;
        }
        icons.flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, iconBuffer);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, icons);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, ICON_BUFFER_BINDING, iconBuffer);
        context.getBlockTextureManager().bind(0);
        blockIconShader.useProgram();
        blockIconShader.setUniform("uViewport", (float) width, (float) height, 0.0f);
        blockIconShader.setUniform("uBlockTextures", 0);
        glDrawArraysInstanced(GL_TRIANGLES, 0, 18, iconCount);
        blockIconShader.unbind();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
    }

    @Override
    public void resize(int width, int height) {
        // Layout is recomputed from RenderContext every frame.
    }

    @Override
    public void cleanup() {
        if (rectangles != null) {
            MemoryUtil.memFree(rectangles);
            rectangles = null;
        }
        if (icons != null) {
            MemoryUtil.memFree(icons);
            icons = null;
        }
        if (rectangleBuffer != 0) {
            glDeleteBuffers(rectangleBuffer);
            rectangleBuffer = 0;
        }
        if (iconBuffer != 0) {
            glDeleteBuffers(iconBuffer);
            iconBuffer = 0;
        }
        if (vao != 0) {
            glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (crosshairShader != null) {
            crosshairShader.cleanup();
            crosshairShader = null;
        }
        if (rectangleShader != null) {
            rectangleShader.cleanup();
            rectangleShader = null;
        }
        if (blockIconShader != null) {
            blockIconShader.cleanup();
            blockIconShader = null;
        }
    }
}
