package org.weaw.engine.graphics.pipeline.passes;

import org.joml.Matrix4f;
import org.weaw.engine.graphics.pipeline.FogSettings;
import org.weaw.engine.graphics.pipeline.LightingSettings;
import org.weaw.engine.graphics.pipeline.RenderContext;
import org.weaw.engine.graphics.pipeline.RenderPass;
import org.weaw.engine.graphics.pipeline.resources.FullscreenQuad;
import org.weaw.engine.graphics.pipeline.resources.GLStateManager;
import org.weaw.engine.graphics.pipeline.resources.RenderTarget;
import org.weaw.engine.graphics.utils.Shader;
import org.weaw.game.Chunk;
import org.weaw.game.WorldSettings;

import static org.lwjgl.opengl.GL11.GL_FILL;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.GL_TEXTURE1;
import static org.lwjgl.opengl.GL13.glActiveTexture;

public class FogPass implements RenderPass {
    private Shader shader;
    private FullscreenQuad fullscreenQuad;
    private final Matrix4f inverseProjection = new Matrix4f();

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
        RenderTarget sceneTarget = context.getCurrentColorTarget();
        if (sceneTarget == null) {
            sceneTarget = context.getRenderTarget("sceneColor");
        }
        RenderTarget depthTarget = context.getRenderTarget("sceneColor");
        RenderTarget outputTarget = context.getRenderTarget("postProcessColor");
        if (sceneTarget == null || depthTarget == null || outputTarget == null) {
            return;
        }

        FogSettings settings = context.getFogSettings();
        LightingSettings lighting = context.getLightingSettings();
        WorldSettings worldSettings = context.getWorldSettings();
        int effectiveRenderDistance = context.getWorld() != null
                ? context.getWorld().getMemorySnapshot().effectiveRenderDistanceChunks()
                : worldSettings.getRenderDistanceChunks();
        float renderDistance = effectiveRenderDistance * Chunk.SIZE;
        float fogStart = renderDistance * settings.getStartRatio();
        float fogEnd = renderDistance * settings.getEndRatio();

        context.getCamera().getProjectionMatrix(inverseProjection).invert();

        outputTarget.bind();
        GLStateManager.setDepthTest(false, false);
        GLStateManager.setBlending(false);
        GLStateManager.setCulling(false);
        int polygonMode = GLStateManager.getPolygonMode();
        GLStateManager.setPolygonMode(GL_FILL);

        shader.useProgram();

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, sceneTarget.getColorTexture());
        shader.setUniform("uColorTexture", 0);

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, depthTarget.getDepthTexture());
        shader.setUniform("uDepthTexture", 1);

        shader.setUniform("uEnabled", settings.isEnabled() ? 1 : 0);
        shader.setUniform("uInverseProjection", inverseProjection);
        shader.setUniform(
                "uFogColor",
                settings.getRed() * lighting.getSkyIntensity(),
                settings.getGreen() * lighting.getSkyIntensity(),
                settings.getBlue() * lighting.getSkyIntensity()
        );
        shader.setUniform("uFogStart", fogStart);
        shader.setUniform("uFogEnd", fogEnd);
        shader.setUniform("uFogDensity", settings.getDensity());
        shader.setUniform("uFogIntensity", settings.getIntensity());

        fullscreenQuad.render();

        glBindTexture(GL_TEXTURE_2D, 0);
        glActiveTexture(GL_TEXTURE0);
        shader.unbind();
        GLStateManager.setPolygonMode(polygonMode);
        context.setCurrentColorTarget("postProcessColor");
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
