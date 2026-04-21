package org.weaw.engine.graphics.pipeline.passes;

import org.joml.Matrix4f;
import org.weaw.engine.graphics.pipeline.FogSettings;
import org.weaw.engine.graphics.pipeline.RenderContext;
import org.weaw.engine.graphics.pipeline.RenderPass;
import org.weaw.engine.graphics.pipeline.resources.FullscreenQuad;
import org.weaw.engine.graphics.pipeline.resources.GLStateManager;
import org.weaw.engine.graphics.pipeline.resources.RenderTarget;
import org.weaw.engine.graphics.utils.Shader;
import org.weaw.game.Chunk;
import org.weaw.game.WorldSettings;

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
import static org.lwjgl.opengl.GL13.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;

public class FogPass implements RenderPass {
    private Shader shader;
    private FullscreenQuad fullscreenQuad;
    private final Matrix4f inverseProjection = new Matrix4f();
    private final int[] polygonMode = new int[2];

    @Override
    public String getName() {
        return "FogPass";
    }

    @Override
    public void create() {
        shader = new Shader("/shaders/fog.glsl");
        fullscreenQuad = new FullscreenQuad();
        fullscreenQuad.create();
    }

    @Override
    public void execute(RenderContext context) {
        RenderTarget sceneTarget = context.getRenderTarget("sceneColor");
        RenderTarget colorTarget = context.getRenderTarget("postProcessColor");
        if (sceneTarget == null || colorTarget == null) {
            return;
        }

        FogSettings settings = context.getFogSettings();
        WorldSettings worldSettings = context.getWorldSettings();
        float renderDistance = worldSettings.getRenderDistanceChunks() * Chunk.SIZE;
        float fogStart = renderDistance * settings.getStartRatio();
        float fogEnd = renderDistance * settings.getEndRatio();

        context.getCamera().getProjectionMatrix(inverseProjection).invert();

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        GLStateManager.setViewport(context.getViewportWidth(), context.getViewportHeight());
        GLStateManager.setDepthTest(false, false);
        GLStateManager.setBlending(false);
        glDisable(GL_CULL_FACE);
        glGetIntegerv(GL_POLYGON_MODE, polygonMode);
        glPolygonMode(GL_FRONT_AND_BACK, GL_FILL);

        shader.useProgram();

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, colorTarget.getColorTexture());
        shader.setUniform("uColorTexture", 0);

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, sceneTarget.getDepthTexture());
        shader.setUniform("uDepthTexture", 1);

        shader.setUniform("uEnabled", settings.isEnabled() ? 1 : 0);
        shader.setUniform("uInverseProjection", inverseProjection);
        shader.setUniform("uFogColor", settings.getRed(), settings.getGreen(), settings.getBlue());
        shader.setUniform("uFogStart", fogStart);
        shader.setUniform("uFogEnd", fogEnd);
        shader.setUniform("uFogDensity", settings.getDensity());
        shader.setUniform("uFogIntensity", settings.getIntensity());

        fullscreenQuad.render();

        glBindTexture(GL_TEXTURE_2D, 0);
        glActiveTexture(GL_TEXTURE0);
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
