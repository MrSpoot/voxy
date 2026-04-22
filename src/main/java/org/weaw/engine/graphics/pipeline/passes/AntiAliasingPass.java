package org.weaw.engine.graphics.pipeline.passes;

import org.joml.Vector2f;
import org.weaw.engine.graphics.pipeline.RenderContext;
import org.weaw.engine.graphics.pipeline.RenderPass;
import org.weaw.engine.graphics.pipeline.resources.FullscreenQuad;
import org.weaw.engine.graphics.pipeline.resources.GLStateManager;
import org.weaw.engine.graphics.pipeline.resources.RenderTarget;
import org.weaw.engine.graphics.utils.Shader;

import static org.lwjgl.opengl.GL11.GL_CULL_FACE;
import static org.lwjgl.opengl.GL11.GL_FILL;
import static org.lwjgl.opengl.GL11.GL_FRONT_AND_BACK;
import static org.lwjgl.opengl.GL11.GL_POLYGON_MODE;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glGetIntegerv;
import static org.lwjgl.opengl.GL11.glPolygonMode;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;

public class AntiAliasingPass implements RenderPass {
    private Shader shader;
    private FullscreenQuad fullscreenQuad;
    private final Vector2f texelSize = new Vector2f();
    private final int[] polygonMode = new int[2];

    @Override
    public String getName() {
        return "AntiAliasingPass";
    }

    @Override
    public void create() {
        shader = new Shader("/shaders/fxaa.glsl");
        fullscreenQuad = new FullscreenQuad();
        fullscreenQuad.create();
    }

    @Override
    public void execute(RenderContext context) {
        RenderTarget sourceTarget = context.getCurrentColorTarget();
        if (sourceTarget == null) {
            sourceTarget = context.getRenderTarget("sceneColor");
        }
        RenderTarget outputTarget = context.getRenderTarget("antiAliasColor");
        if (sourceTarget == null || outputTarget == null) {
            return;
        }

        outputTarget.bind();
        GLStateManager.setDepthTest(false, false);
        GLStateManager.setBlending(false);
        glDisable(GL_CULL_FACE);
        glGetIntegerv(GL_POLYGON_MODE, polygonMode);
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);

        shader.useProgram();

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sourceTarget.getColorTexture());

        texelSize.set(1.0f / Math.max(1, context.getViewportWidth()), 1.0f / Math.max(1, context.getViewportHeight()));
        shader.setUniform("uSourceTexture", 0);
        shader.setUniform("uTexelSize", texelSize);

        fullscreenQuad.render();

        glBindTexture(GL_TEXTURE_2D, 0);
        shader.unbind();
        glPolygonMode(GL_FRONT_AND_BACK, polygonMode[0]);
        context.setCurrentColorTarget("antiAliasColor");
    }

    @Override
    public void resize(int width, int height) {
        // Uses shared render targets resized by RenderPipeline.
    }

    @Override
    public void cleanup() {
        if (fullscreenQuad != null) {
            fullscreenQuad.cleanup();
            fullscreenQuad = null;
        }
        if (shader != null) {
            shader.cleanup();
            shader = null;
        }
    }
}
