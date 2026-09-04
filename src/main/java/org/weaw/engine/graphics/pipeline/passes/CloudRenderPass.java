package org.weaw.engine.graphics.pipeline.passes;

import org.joml.Matrix4f;
import org.weaw.engine.graphics.pipeline.CloudSettings;
import org.weaw.engine.graphics.pipeline.RenderContext;
import org.weaw.engine.graphics.pipeline.RenderPass;
import org.weaw.engine.graphics.pipeline.resources.GLStateManager;
import org.weaw.engine.graphics.pipeline.resources.RenderTarget;
import org.weaw.engine.graphics.utils.Camera;
import org.weaw.engine.graphics.utils.Shader;

import static org.lwjgl.opengl.GL11.GL_CCW;
import static org.lwjgl.opengl.GL11.GL_POINTS;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;
import static org.lwjgl.opengl.GL31.glDrawArraysInstanced;

/** Renders a camera-centered field of procedural opaque voxel clouds. */
public final class CloudRenderPass implements RenderPass {
    private static final int GRID_SIDE = 160;
    private static final int INSTANCE_COUNT = GRID_SIDE * GRID_SIDE;

    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Matrix4f viewMatrix = new Matrix4f();

    private Shader shader;
    private int vao;
    private double windOffsetBlocks;

    @Override
    public String getName() {
        return "CloudRenderPass";
    }

    @Override
    public void create() {
        shader = new Shader("/shaders/clouds.glsl");
        vao = glGenVertexArrays();
    }

    @Override
    public void execute(RenderContext context) {
        CloudSettings settings = context.getCloudSettings();
        float cellSize = finiteOr(settings.getCellSize(), CloudSettings.DEFAULT_CELL_SIZE, 1.0f, 64.0f);
        float speed = finiteOr(settings.getSpeed(), CloudSettings.DEFAULT_SPEED, 0.0f, 100.0f);
        windOffsetBlocks = CloudMotion.advanceWrapped(
                windOffsetBlocks,
                context.getFrameDeltaSeconds(),
                speed,
                cellSize
        );

        RenderTarget sceneTarget = context.getRenderTarget("sceneColor");
        Camera camera = context.getCamera();
        if (!settings.isEnabled() || sceneTarget == null || camera == null) {
            return;
        }

        float altitude = finiteOr(settings.getAltitude(), CloudSettings.DEFAULT_ALTITUDE, -2048.0f, 4096.0f);
        float density = finiteOr(settings.getDensity(), CloudSettings.DEFAULT_DENSITY, 0.0f, 1.0f);
        float cloudSize = finiteOr(settings.getCloudSize(), CloudSettings.DEFAULT_CLOUD_SIZE, 0.25f, 4.0f);
        if (density <= 0.001f) {
            return;
        }

        sceneTarget.bind();
        GLStateManager.setViewport(context.getViewportWidth(), context.getViewportHeight());
        GLStateManager.setDepthTest(true, true);
        GLStateManager.setBlending(false);
        GLStateManager.setCulling(true);
        GLStateManager.setFrontFace(GL_CCW);

        camera.getProjectionMatrix(projectionMatrix);
        camera.getViewMatrix(viewMatrix);

        shader.useProgram();
        shader.setUniform("uProjection", projectionMatrix);
        shader.setUniform("uView", viewMatrix);
        shader.setUniform("uCameraPosition", camera.getPosition());
        shader.setUniform("uCloudBaseAltitude", CloudGeometry.centeredBaseY(altitude, cellSize));
        shader.setUniform("uCloudCellSize", cellSize);
        shader.setUniform("uCloudDensity", density);
        shader.setUniform("uCloudSize", cloudSize);
        shader.setUniform("uWindOffset", (float) windOffsetBlocks);

        glBindVertexArray(vao);
        glDrawArraysInstanced(GL_POINTS, 0, 1, INSTANCE_COUNT);
        glBindVertexArray(0);
        shader.unbind();
    }

    @Override
    public void resize(int width, int height) {
        // Uses the shared scene target and camera projection.
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

    private static float finiteOr(float value, float fallback, float minimum, float maximum) {
        if (!Float.isFinite(value)) {
            return fallback;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}
