package org.weaw.engine.graphics.pipeline.passes;

import org.weaw.engine.graphics.pipeline.ColorGradingSettings;
import org.weaw.engine.graphics.pipeline.RenderContext;
import org.weaw.engine.graphics.pipeline.RenderPass;
import org.weaw.engine.graphics.pipeline.resources.FullscreenQuad;
import org.weaw.engine.graphics.pipeline.resources.GLStateManager;
import org.weaw.engine.graphics.pipeline.resources.RenderTarget;
import org.weaw.engine.graphics.utils.Shader;

import static org.lwjgl.opengl.GL11.GL_FILL;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;

public class ToneMappingPass implements RenderPass {
    private Shader shader;
    private FullscreenQuad fullscreenQuad;
    private AutoExposureSystem autoExposureSystem;
    private int lastExposureTexture;
    private int autoExposureFrame;

    @Override
    public String getName() {
        return "ToneMappingPass";
    }

    @Override
    public void create() {
        shader = new Shader("/shaders/tone-mapping.glsl");
        fullscreenQuad = new FullscreenQuad();
        fullscreenQuad.create();
        autoExposureSystem = new AutoExposureSystem();
        autoExposureSystem.create();
    }

    @Override
    public void execute(RenderContext context) {
        RenderTarget sourceTarget = context.getCurrentColorTarget();
        if (sourceTarget == null) {
            sourceTarget = context.getRenderTarget("sceneColor");
        }
        if (sourceTarget == null) {
            return;
        }

        ColorGradingSettings settings = context.getColorGradingSettings();

        GLStateManager.setDepthTest(false, false);
        GLStateManager.setBlending(false);
        GLStateManager.setCulling(false);
        int polygonMode = GLStateManager.getPolygonMode();
        GLStateManager.setPolygonMode(GL_FILL);

        int exposureInterval = context.getAdaptiveGraphicsQuality().getLevel().autoExposureIntervalFrames();
        if (lastExposureTexture == 0 || autoExposureFrame++ % exposureInterval == 0) {
            lastExposureTexture = autoExposureSystem.update(
                    sourceTarget.getColorTexture(),
                    sourceTarget.getWidth(),
                    sourceTarget.getHeight(),
                    context.getFrameDeltaSeconds() * exposureInterval,
                    settings,
                    fullscreenQuad
            );
        }
        int exposureTexture = lastExposureTexture;

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        GLStateManager.setViewport(context.getViewportWidth(), context.getViewportHeight());

        shader.useProgram();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sourceTarget.getColorTexture());
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, exposureTexture);

        shader.setUniform("uHdrTexture", 0);
        shader.setUniform("uAutoExposureTexture", 1);
        shader.setUniform(
                "uAutoExposureEnabled",
                settings.isAutoExposureEnabled() && settings.isToneMappingEnabled() && exposureTexture != 0 ? 1 : 0
        );
        shader.setUniform("uColorGradingEnabled", settings.isEnabled() ? 1 : 0);
        shader.setUniform("uToneMappingEnabled", settings.isToneMappingEnabled() ? 1 : 0);
        shader.setUniform("uExposure", settings.getExposure());
        shader.setUniform("uContrast", settings.getContrast());
        shader.setUniform("uSaturation", settings.getSaturation());
        shader.setUniform("uVibrance", settings.getVibrance());
        shader.setUniform("uGamma", settings.getGamma());
        shader.setUniform("uTemperature", settings.getTemperature());

        fullscreenQuad.render();

        glBindTexture(GL_TEXTURE_2D, 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);
        shader.unbind();
        GLStateManager.setPolygonMode(polygonMode);
    }

    @Override
    public void resize(int width, int height) {
        if (autoExposureSystem != null) {
            autoExposureSystem.invalidateLuminancePyramid();
            lastExposureTexture = 0;
            autoExposureFrame = 0;
        }
    }

    @Override
    public void cleanup() {
        if (autoExposureSystem != null) {
            autoExposureSystem.cleanup();
            autoExposureSystem = null;
        }
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
