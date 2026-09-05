package org.weaw.engine.graphics.pipeline.passes;

import org.joml.Vector3f;
import org.weaw.engine.graphics.pipeline.RenderContext;
import org.weaw.engine.graphics.pipeline.RenderPass;
import org.weaw.engine.graphics.pipeline.resources.FullscreenQuad;
import org.weaw.engine.graphics.pipeline.resources.GLStateManager;
import org.weaw.engine.graphics.pipeline.resources.RenderTarget;
import org.weaw.engine.graphics.utils.Camera;
import org.weaw.engine.graphics.utils.Shader;

import static org.lwjgl.opengl.GL11.GL_FILL;

/**
 * Renders the procedural HDR sky directly into the scene target before world geometry.
 */
public final class SkyBoxPass implements RenderPass {
    private static final float FIXED_TIME_OF_DAY = 0.75f;

    private final Vector3f cameraRight = new Vector3f();
    private final Vector3f cameraUp = new Vector3f();
    private final Vector3f cameraForward = new Vector3f();

    private Shader shader;
    private FullscreenQuad fullscreenQuad;

    @Override
    public String getName() {
        return "SkyBoxPass";
    }

    @Override
    public void create() {
        shader = new Shader("/shaders/skybox.glsl");
        fullscreenQuad = new FullscreenQuad();
        fullscreenQuad.create();
    }

    @Override
    public void execute(RenderContext context) {
        RenderTarget sceneTarget = context.getRenderTarget("sceneColor");
        Camera camera = context.getCamera();
        if (sceneTarget == null || camera == null) {
            return;
        }

        sceneTarget.bind();
        GLStateManager.setViewport(context.getViewportWidth(), context.getViewportHeight());
        GLStateManager.setDepthTest(false, true);
        GLStateManager.setBlending(false);
        GLStateManager.setCulling(false);
        GLStateManager.clear(0.0f, 0.0f, 0.0f, 1.0f);
        GLStateManager.setDepthTest(false, false);

        int polygonMode = GLStateManager.getPolygonMode();
        GLStateManager.setPolygonMode(GL_FILL);

        camera.getRight(cameraRight);
        camera.getUp(cameraUp);
        camera.getForward(cameraForward);

        shader.useProgram();
        shader.setUniform("uCameraRight", cameraRight);
        shader.setUniform("uCameraUp", cameraUp);
        shader.setUniform("uCameraForward", cameraForward);
        shader.setUniform("uVerticalFov", (float) Math.toRadians(camera.getFov()));
        shader.setUniform("uAspectRatio", camera.getAspectRatio());
        shader.setUniform("uTimeOfDay", FIXED_TIME_OF_DAY);
        fullscreenQuad.render();
        shader.unbind();

        GLStateManager.setPolygonMode(polygonMode);
    }

    @Override
    public void resize(int width, int height) {
        // Uses the shared scene target and viewport dimensions.
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
