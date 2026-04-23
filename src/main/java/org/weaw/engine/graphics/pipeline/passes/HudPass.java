package org.weaw.engine.graphics.pipeline.passes;

import org.weaw.engine.graphics.pipeline.RenderContext;
import org.weaw.engine.graphics.pipeline.RenderPass;
import org.weaw.engine.graphics.pipeline.resources.GLStateManager;
import org.weaw.engine.graphics.utils.Shader;

import static org.lwjgl.opengl.GL11.GL_LINES;
import static org.lwjgl.opengl.GL11.GL_TRIANGLE_STRIP;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL31.glDrawArraysInstanced;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

public class HudPass implements RenderPass {
    private Shader crosshairShader;
    private Shader selectionShader;
    private int vao;

    @Override
    public String getName() {
        return "HudPass";
    }

    @Override
    public void create() {
        crosshairShader = new Shader("/shaders/hud-crosshair.glsl");
        selectionShader = new Shader("/shaders/hud-lamp-selection.glsl");
        vao = glGenVertexArrays();
    }

    @Override
    public void execute(RenderContext context) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        GLStateManager.setViewport(context.getViewportWidth(), context.getViewportHeight());
        GLStateManager.setDepthTest(false, false);
        GLStateManager.setBlending(true);
        GLStateManager.setCulling(false);

        glBindVertexArray(vao);

        crosshairShader.useProgram();
        crosshairShader.setUniform("uViewport", (float) context.getViewportWidth(), (float) context.getViewportHeight(), 0.0f);
        crosshairShader.setUniform("uColor", 1.0f, 1.0f, 1.0f);
        crosshairShader.setUniform("uAlpha", 0.55f);
        crosshairShader.setUniform("uHalfLengthPx", 5.0f);
        crosshairShader.setUniform("uGapPx", 3.0f);
        glDrawArrays(GL_LINES, 0, 8);
        crosshairShader.unbind();

        selectionShader.useProgram();
        selectionShader.setUniform("uViewport", (float) context.getViewportWidth(), (float) context.getViewportHeight(), 0.0f);
        selectionShader.setUniform("uSelectedIndex", context.getSelectedLampHotbarIndex());
        selectionShader.setUniform("uSlotColors[0]", 1.0f, 0.22f, 0.22f);
        selectionShader.setUniform("uSlotColors[1]", 0.22f, 1.0f, 0.32f);
        selectionShader.setUniform("uSlotColors[2]", 0.28f, 0.52f, 1.0f);
        selectionShader.setUniform("uSlotColors[3]", 1.0f, 1.0f, 1.0f);
        selectionShader.setUniform("uSlotSizePx", 24.0f);
        selectionShader.setUniform("uSlotGapPx", 10.0f);
        selectionShader.setUniform("uBottomOffsetPx", 34.0f);
        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, 4);
        selectionShader.unbind();

        glBindVertexArray(0);
    }

    @Override
    public void resize(int width, int height) {
        // Uses viewport size from RenderContext.
    }

    @Override
    public void cleanup() {
        if (vao != 0) {
            glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (crosshairShader != null) {
            crosshairShader.cleanup();
            crosshairShader = null;
        }
        if (selectionShader != null) {
            selectionShader.cleanup();
            selectionShader = null;
        }
    }
}
