package org.weaw.engine.graphics.utils;

import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.util.List;

import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glBufferSubData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL40.GL_DRAW_INDIRECT_BUFFER;
import static org.lwjgl.opengl.GL42C.glDrawArraysInstancedBaseInstance;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43.glBindBufferBase;
import static org.lwjgl.opengl.GL43C.glMultiDrawArraysIndirect;

/**
 * Reusable per-layer buffers for chunk draw metadata and indirect commands.
 *
 * One entry contains:
 * - 4 ints for DrawArraysIndirectCommand
 * - 4 ints for chunk metadata (face offset + chunk origin)
 */
public final class ChunkMultiDrawBatch {
    private static final int COMMAND_INTS = 4;
    private static final int DRAW_DATA_INTS = 4;
    private static final int MIN_DRAW_CAPACITY = 256;
    private static final int DRAW_METADATA_BINDING = 1;

    private final int metadataSsbo;
    private final int indirectBuffer;

    private IntBuffer commandBuffer;
    private IntBuffer drawDataBuffer;
    private int drawCapacity;
    private int activeDrawCount;

    public ChunkMultiDrawBatch(int initialDrawCapacity) {
        this.drawCapacity = Math.max(initialDrawCapacity, MIN_DRAW_CAPACITY);
        this.metadataSsbo = glGenBuffers();
        this.indirectBuffer = glGenBuffers();
        this.commandBuffer = MemoryUtil.memAllocInt(drawCapacity * COMMAND_INTS);
        this.drawDataBuffer = MemoryUtil.memAllocInt(drawCapacity * DRAW_DATA_INTS);

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, metadataSsbo);
        glBufferData(GL_SHADER_STORAGE_BUFFER, (long) drawCapacity * DRAW_DATA_INTS * Integer.BYTES, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectBuffer);
        glBufferData(GL_DRAW_INDIRECT_BUFFER, (long) drawCapacity * COMMAND_INTS * Integer.BYTES, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
    }

    public void upload(List<? extends ChunkDrawSource> draws) {
        activeDrawCount = draws.size();
        if (activeDrawCount == 0) {
            return;
        }

        ensureCapacity(activeDrawCount);

        commandBuffer.clear();
        drawDataBuffer.clear();
        for (int drawIndex = 0; drawIndex < draws.size(); drawIndex++) {
            ChunkDrawSource draw = draws.get(drawIndex);
            commandBuffer.put(4);
            commandBuffer.put(draw.faceCount());
            commandBuffer.put(0);
            commandBuffer.put(drawIndex);

            drawDataBuffer.put(draw.faceOffsetInts());
            drawDataBuffer.put(draw.originX());
            drawDataBuffer.put(draw.originY());
            drawDataBuffer.put(draw.originZ());
        }
        commandBuffer.flip();
        drawDataBuffer.flip();

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, metadataSsbo);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0L, drawDataBuffer);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectBuffer);
        glBufferSubData(GL_DRAW_INDIRECT_BUFFER, 0L, commandBuffer);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
    }

    public void bind() {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, DRAW_METADATA_BINDING, metadataSsbo);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectBuffer);
    }

    public void unbind() {
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
    }

    public void drawIndirect() {
        if (activeDrawCount == 0) {
            return;
        }
        glMultiDrawArraysIndirect(org.lwjgl.opengl.GL11C.GL_TRIANGLE_STRIP, 0L, activeDrawCount, 0);
    }

    public void drawLegacy(List<? extends ChunkDrawSource> draws) {
        for (int drawIndex = 0; drawIndex < draws.size(); drawIndex++) {
            ChunkDrawSource draw = draws.get(drawIndex);
            glDrawArraysInstancedBaseInstance(
                    org.lwjgl.opengl.GL11C.GL_TRIANGLE_STRIP,
                    0,
                    4,
                    draw.faceCount(),
                    drawIndex
            );
        }
    }

    public long getEstimatedGpuBytes() {
        long metadataBytes = (long) drawCapacity * DRAW_DATA_INTS * Integer.BYTES;
        long indirectBytes = (long) drawCapacity * COMMAND_INTS * Integer.BYTES;
        return metadataBytes + indirectBytes;
    }

    public long getMetadataGpuBytes() {
        return (long) drawCapacity * DRAW_DATA_INTS * Integer.BYTES;
    }

    public long getIndirectCommandGpuBytes() {
        return (long) drawCapacity * COMMAND_INTS * Integer.BYTES;
    }

    public int getActiveDrawCount() {
        return activeDrawCount;
    }

    public int getDrawCapacity() {
        return drawCapacity;
    }

    public void cleanup() {
        glDeleteBuffers(metadataSsbo);
        glDeleteBuffers(indirectBuffer);
        if (commandBuffer != null) {
            MemoryUtil.memFree(commandBuffer);
            commandBuffer = null;
        }
        if (drawDataBuffer != null) {
            MemoryUtil.memFree(drawDataBuffer);
            drawDataBuffer = null;
        }
        activeDrawCount = 0;
        drawCapacity = 0;
    }

    private void ensureCapacity(int requiredDrawCount) {
        if (requiredDrawCount <= drawCapacity) {
            return;
        }

        int newCapacity = drawCapacity;
        while (newCapacity < requiredDrawCount) {
            newCapacity *= 2;
        }

        commandBuffer = MemoryUtil.memRealloc(commandBuffer, newCapacity * COMMAND_INTS);
        drawDataBuffer = MemoryUtil.memRealloc(drawDataBuffer, newCapacity * DRAW_DATA_INTS);

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, metadataSsbo);
        glBufferData(GL_SHADER_STORAGE_BUFFER, (long) newCapacity * DRAW_DATA_INTS * Integer.BYTES, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectBuffer);
        glBufferData(GL_DRAW_INDIRECT_BUFFER, (long) newCapacity * COMMAND_INTS * Integer.BYTES, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);

        drawCapacity = newCapacity;
    }

    public interface ChunkDrawSource {
        int faceOffsetInts();

        int faceCount();

        int originX();

        int originY();

        int originZ();
    }
}
