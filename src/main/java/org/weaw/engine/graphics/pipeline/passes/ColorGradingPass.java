package org.weaw.engine.graphics.pipeline.passes;

import org.weaw.engine.graphics.pipeline.ColorGradingSettings;
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
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;

public class ColorGradingPass implements RenderPass {
    private Shader shader;
    private FullscreenQuad fullscreenQuad;
    private final int[] polygonMode = new int[2];

    @Override
    public String getName() {
        return "ColorGradingPass";
    }

    @Override
    public void create() {
        shader = new Shader("/shaders/color-grading.glsl");
        fullscreenQuad = new FullscreenQuad();
        fullscreenQuad.create();
    }

    @Override
    public void execute(RenderContext context) {
        RenderTarget sceneTarget = context.getRenderTarget("sceneColor");
        RenderTarget outputTarget = context.getRenderTarget("postProcessColor");
        if (sceneTarget == null) {
            return;
        }

        ColorGradingSettings settings = context.getColorGradingSettings();

        if (outputTarget != null) {
            outputTarget.bind();
        } else {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            GLStateManager.setViewport(context.getViewportWidth(), context.getViewportHeight());
        }
        GLStateManager.setDepthTest(false, false);
        GLStateManager.setBlending(false);
        glDisable(GL_CULL_FACE);
        glGetIntegerv(GL_POLYGON_MODE, polygonMode);
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);

        shader.useProgram();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sceneTarget.getColorTexture());

        shader.setUniform("uSceneTexture", 0);
        shader.setUniform("uEnabled", settings.isEnabled() ? 1 : 0);
        shader.setUniform("uExposure", settings.getExposure());
        shader.setUniform("uContrast", settings.getContrast());
        shader.setUniform("uSaturation", settings.getSaturation());
        shader.setUniform("uVibrance", settings.getVibrance());
        shader.setUniform("uGamma", settings.getGamma());
        shader.setUniform("uTemperature", settings.getTemperature());

        fullscreenQuad.render();

        glBindTexture(GL_TEXTURE_2D, 0);
        shader.unbind();
        glPolygonMode(GL_FRONT_AND_BACK, polygonMode[0]);
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
