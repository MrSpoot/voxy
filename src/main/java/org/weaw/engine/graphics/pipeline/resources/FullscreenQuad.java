package org.weaw.engine.graphics.pipeline.resources;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.glEnableVertexAttribArray;
import static org.lwjgl.opengl.GL20.glVertexAttribPointer;
import static org.lwjgl.opengl.GL30.*;

/**
 * Helper for rendering a fullscreen quad for post-processing effects.
 *
 * Creates a simple quad covering the screen in NDC coordinates (-1 to 1).
 * Used by post-processing passes to apply screen-space effects.
 *
 * Usage:
 * <pre>
 * FullscreenQuad quad = new FullscreenQuad();
 * quad.create();
 *
 * // In render loop:
 * shader.useProgram();
 * shader.setUniform("uTexture", 0);
 * glBindTexture(GL_TEXTURE_2D, colorTexture);
 * quad.render();
 * shader.unbind();
 *
 * // Cleanup:
 * quad.cleanup();
 * </pre>
 */
public class FullscreenQuad {

    private int vao;
    private int vbo;

    /**
     * Create VAO/VBO for fullscreen quad.
     * Call this once during initialization.
     */
    public void create() {
        // Quad vertices in NDC coordinates with UVs
        // Format: vec2 position, vec2 texCoord
        float[] vertices = {
            // Triangle 1
            -1.0f, -1.0f,  0.0f, 0.0f,  // Bottom-left
             1.0f, -1.0f,  1.0f, 0.0f,  // Bottom-right
             1.0f,  1.0f,  1.0f, 1.0f,  // Top-right

            // Triangle 2
            -1.0f, -1.0f,  0.0f, 0.0f,  // Bottom-left
             1.0f,  1.0f,  1.0f, 1.0f,  // Top-right
            -1.0f,  1.0f,  0.0f, 1.0f   // Top-left
        };

        // Create VAO
        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        // Create VBO
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);

        // Configure vertex attributes
        // Location 0: position (vec2)
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);

        // Location 1: texCoord (vec2)
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
        glEnableVertexAttribArray(1);

        // Unbind
        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    /**
     * Render the fullscreen quad.
     * Assumes shader is already bound and uniforms are set.
     */
    public void render() {
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindVertexArray(0);
    }

    /**
     * Cleanup GPU resources.
     */
    public void cleanup() {
        if (vbo != 0) {
            glDeleteBuffers(vbo);
            vbo = 0;
        }
        if (vao != 0) {
            glDeleteVertexArrays(vao);
            vao = 0;
        }
    }
}
