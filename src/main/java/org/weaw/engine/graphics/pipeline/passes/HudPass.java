package org.weaw.engine.graphics.pipeline.passes;

import org.weaw.engine.graphics.pipeline.RenderContext;
import org.weaw.engine.graphics.pipeline.RenderPass;
import org.weaw.engine.graphics.pipeline.resources.GLStateManager;
import org.weaw.engine.graphics.utils.Shader;

import static org.lwjgl.opengl.GL11.GL_LINES;
import static org.lwjgl.opengl.GL11.glDrawArrays;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

public class HudPass implements RenderPass {
    private Shader shader;
    private int vao;

    @Override
    public String getName() {
        return "HudPass";
    }

    @Override
    public void create() {
        shader = new Shader("/shaders/hud-crosshair.glsl");
        vao = glGenVertexArrays();
    }

    @Override
    public void execute(RenderContext context) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        GLStateManager.setViewport(context.getViewportWidth(), context.getViewportHeight());
        GLStateManager.setDepthTest(false, false);
        GLStateManager.setBlending(true);
        GLStateManager.setCulling(false);

        shader.useProgram();
        shader.setUniform("uViewport", (float) context.getViewportWidth(), (float) context.getViewportHeight(), 0.0f);
        shader.setUniform("uColor", 1.0f, 1.0f, 1.0f);
        shader.setUniform("uAlpha", 0.55f);
        shader.setUniform("uHalfLengthPx", 5.0f);
        shader.setUniform("uGapPx", 3.0f);

        glBindVertexArray(vao);
        glDrawArrays(GL_LINES, 0, 8);
        glBindVertexArray(0);

        shader.unbind();
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
        if (shader != null) {
            shader.cleanup();
            shader = null;
        }
    }
}
