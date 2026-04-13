package org.weaw.engine.graphics.utils;

import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11C.GL_TRIANGLE_STRIP;
import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glBufferSubData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL31C.glDrawArraysInstanced;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;

/**
 * Mesh implementation using vertex pulling technique.
 *
 * Instead of traditional VAO/VBO setup with vertex attributes:
 * - Face data is stored in a Shader Storage Buffer Object (SSBO)
 * - Vertex shader generates quad vertices procedurally using gl_VertexID
 * - Vertex shader fetches face data using gl_InstanceID as index into SSBO
 *
 * Benefits:
 * - Eliminates quad VBO (48 bytes saved per mesh)
 * - Reduces OpenGL setup calls (15+ -> 3)
 * - Minimal VAO overhead (no vertex attributes configured)
 * - Enables future GPU-driven rendering (compute shader culling)
 */
public class Mesh {
    private static final int INTS_PER_FACE = 2;

    private final int vao;            // Dummy VAO (required by OpenGL even with vertex pulling)
    private final int ssbo;           // Shader Storage Buffer for face data
    private int instanceCount;        // Number of faces to render
    private int capacityInts;

    /**
     * Creates a mesh using vertex pulling with SSBO.
     *
     * @param faceBuffer Buffer containing encoded face data (2 uints per face)
     * @param instanceCount Number of faces
     */
    public Mesh(IntBuffer faceBuffer, int instanceCount) {
        // Create dummy VAO (OpenGL requires VAO to be bound during draw calls)
        vao = glGenVertexArrays();
        // No vertex attributes configured - vertex shader uses gl_VertexID

        // Create SSBO and upload face data
        ssbo = glGenBuffers();
        update(faceBuffer, instanceCount);
    }

    public Mesh(int[] faceData, int instanceCount) {
        vao = glGenVertexArrays();
        ssbo = glGenBuffers();
        update(faceData, instanceCount);
    }

    public void update(int[] faceData, int instanceCount) {
        IntBuffer faceBuffer = MemoryUtil.memAllocInt(instanceCount * INTS_PER_FACE);
        try {
            if (instanceCount > 0) {
                faceBuffer.put(faceData, 0, instanceCount * INTS_PER_FACE).flip();
            }
            update(faceBuffer, instanceCount);
        } finally {
            MemoryUtil.memFree(faceBuffer);
        }
    }

    public void update(IntBuffer faceBuffer, int instanceCount) {
        this.instanceCount = instanceCount;
        int requiredInts = instanceCount * INTS_PER_FACE;

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
        if (requiredInts > capacityInts) {
            glBufferData(GL_SHADER_STORAGE_BUFFER, faceBuffer, GL_DYNAMIC_DRAW);
            capacityInts = requiredInts;
        } else if (requiredInts > 0) {
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, faceBuffer);
        }
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }

    /**
     * Renders the mesh using instanced drawing with vertex pulling.
     * SSBO is bound to binding point 0, shader reads from it.
     */
    public void render() {
        if (instanceCount <= 0) return;

        // Bind VAO (required by OpenGL spec, even though we don't use vertex attributes)
        glBindVertexArray(vao);

        // Bind SSBO to shader storage binding point 0
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssbo);

        // Draw 4 vertices (quad) × instanceCount instances
        // Vertex shader generates quad vertices from gl_VertexID (0,1,2,3)
        // Vertex shader fetches face data from SSBO using gl_InstanceID
        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, instanceCount);

        // Unbind VAO
        glBindVertexArray(0);
    }

    public int getInstanceCount() {
        return instanceCount;
    }

    public long getEstimatedGpuBytes() {
        return (long) capacityInts * Integer.BYTES;
    }

    /**
     * Cleanup GPU resources.
     */
    public void cleanup() {
        glDeleteBuffers(ssbo);
        glDeleteVertexArrays(vao);
    }
}
