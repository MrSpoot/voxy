package org.weaw.engine.graphics.pipeline.passes;

import org.joml.Matrix4f;
import org.weaw.engine.graphics.pipeline.RenderContext;
import org.weaw.engine.graphics.pipeline.RenderPass;
import org.weaw.engine.graphics.pipeline.resources.GLStateManager;
import org.weaw.engine.graphics.pipeline.resources.RenderTarget;
import org.weaw.engine.graphics.utils.Shader;

import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LINES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.glDrawElements;
import static org.lwjgl.opengl.GL15.GL_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_ELEMENT_ARRAY_BUFFER;
import static org.lwjgl.opengl.GL15.GL_STATIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL20.glDisableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.glBindVertexArray;
import static org.lwjgl.opengl.GL30.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30.glGenVertexArrays;

public class BlockOutlinePass implements RenderPass {
    private static final float[] CUBE_VERTICES = {
            0.0f, 0.0f, 0.0f,
            1.0f, 0.0f, 0.0f,
            1.0f, 1.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 1.0f,
            1.0f, 0.0f, 1.0f,
            1.0f, 1.0f, 1.0f,
            0.0f, 1.0f, 1.0f
    };

    private static final int[] CUBE_INDICES = {
            0, 1, 1, 2, 2, 3, 3, 0,
            4, 5, 5, 6, 6, 7, 7, 4,
            0, 4, 1, 5, 2, 6, 3, 7
    };

    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Matrix4f viewMatrix = new Matrix4f();
    private Shader shader;
    private int vao;
    private int vertexBuffer;
    private int indexBuffer;

    @Override
    public String getName() {
        return "BlockOutlinePass";
    }

    @Override
    public void create() {
        shader = new Shader("/shaders/block-outline.glsl");

        vao = glGenVertexArrays();
        vertexBuffer = glGenBuffers();
        indexBuffer = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer);
        glBufferData(GL_ARRAY_BUFFER, CUBE_VERTICES, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0L);

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, CUBE_INDICES, GL_STATIC_DRAW);

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    @Override
    public void execute(RenderContext context) {
        if (!context.isHasBlockOutlineTarget()) {
            return;
        }

        RenderTarget sceneTarget = context.getRenderTarget("sceneColor");
        if (sceneTarget != null) {
            sceneTarget.bind();
        }

        GLStateManager.setViewport(context.getViewportWidth(), context.getViewportHeight());
        GLStateManager.setDepthTest(true, false);
        GLStateManager.setBlending(true);
        GLStateManager.setCulling(false);

        context.getCamera().getProjectionMatrix(projectionMatrix);
        context.getCamera().getViewMatrix(viewMatrix);

        shader.useProgram();
        shader.setUniform("uProjection", projectionMatrix);
        shader.setUniform("uView", viewMatrix);
        shader.setUniform("uBlockOrigin",
                (float) context.getBlockOutlineTargetX(),
                (float) context.getBlockOutlineTargetY(),
                (float) context.getBlockOutlineTargetZ());
        shader.setUniform("uColor", 1.0f, 1.0f, 1.0f);
        shader.setUniform("uAlpha", 0.65f);

        glBindVertexArray(vao);
        glDrawElements(GL_LINES, CUBE_INDICES.length, GL_UNSIGNED_INT, 0L);
        glBindVertexArray(0);

        shader.unbind();
    }

    @Override
    public void resize(int width, int height) {
        // Uses shared viewport size from RenderContext.
    }

    @Override
    public void cleanup() {
        if (vao != 0) {
            glBindVertexArray(vao);
            glDisableVertexAttribArray(0);
            glBindVertexArray(0);
            glDeleteVertexArrays(vao);
            vao = 0;
        }
        if (vertexBuffer != 0) {
            glDeleteBuffers(vertexBuffer);
            vertexBuffer = 0;
        }
        if (indexBuffer != 0) {
            glDeleteBuffers(indexBuffer);
            indexBuffer = 0;
        }
        if (shader != null) {
            shader.cleanup();
            shader = null;
        }
    }
}
