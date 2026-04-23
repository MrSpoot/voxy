package org.weaw.engine.graphics.utils;

import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.lwjgl.opengl.GL15.GL_DYNAMIC_DRAW;
import static org.lwjgl.opengl.GL15.glBindBuffer;
import static org.lwjgl.opengl.GL15.glBufferData;
import static org.lwjgl.opengl.GL15.glBufferSubData;
import static org.lwjgl.opengl.GL15.glDeleteBuffers;
import static org.lwjgl.opengl.GL15.glGenBuffers;
import static org.lwjgl.opengl.GL31C.GL_COPY_READ_BUFFER;
import static org.lwjgl.opengl.GL31C.GL_COPY_WRITE_BUFFER;
import static org.lwjgl.opengl.GL31C.glCopyBufferSubData;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43.glBindBufferBase;

public final class ChunkLightArena {
    private static final int LIGHT_DATA_BINDING = 2;
    private static final int MIN_CAPACITY_INTS = 1024;

    private final List<FreeSpan> freeSpans = new ArrayList<>();

    private int ssbo;
    private int capacityInts;
    private int activeAllocationCount;
    private long reservedInts;

    public ChunkLightArena(int initialCapacityInts) {
        this.capacityInts = Math.max(initialCapacityInts, MIN_CAPACITY_INTS);
        this.ssbo = glGenBuffers();

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
        glBufferData(GL_SHADER_STORAGE_BUFFER, (long) capacityInts * Integer.BYTES, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        freeSpans.add(new FreeSpan(0, capacityInts));
    }

    public Allocation upload(int[] packedLightData, Allocation existing) {
        int requiredInts = packedLightData.length;
        if (requiredInts == 0) {
            free(existing);
            return null;
        }

        Allocation target = existing;
        boolean reused = target != null && requiredInts <= target.capacityInts();
        if (!reused) {
            free(existing);
            target = allocate(requiredInts);
        }

        IntBuffer lightBuffer = MemoryUtil.memAllocInt(requiredInts);
        try {
            lightBuffer.put(packedLightData, 0, requiredInts).flip();
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, (long) target.offsetInts() * Integer.BYTES, lightBuffer);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        } finally {
            MemoryUtil.memFree(lightBuffer);
        }

        return new Allocation(target.offsetInts(), target.capacityInts());
    }

    public void free(Allocation allocation) {
        if (allocation == null) {
            return;
        }

        reservedInts -= allocation.capacityInts();
        freeSpans.add(new FreeSpan(allocation.offsetInts(), allocation.capacityInts()));
        activeAllocationCount--;
        mergeFreeSpans();
    }

    public void bind() {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, LIGHT_DATA_BINDING, ssbo);
    }

    public long getEstimatedGpuBytes() {
        return (long) capacityInts * Integer.BYTES;
    }

    public void cleanup() {
        if (ssbo != 0) {
            glDeleteBuffers(ssbo);
            ssbo = 0;
        }
        freeSpans.clear();
        capacityInts = 0;
        activeAllocationCount = 0;
        reservedInts = 0L;
    }

    private Allocation allocate(int requiredInts) {
        int bestIndex = -1;
        int bestCapacity = Integer.MAX_VALUE;

        for (int index = 0; index < freeSpans.size(); index++) {
            FreeSpan span = freeSpans.get(index);
            if (span.lengthInts() >= requiredInts && span.lengthInts() < bestCapacity) {
                bestIndex = index;
                bestCapacity = span.lengthInts();
            }
        }

        if (bestIndex < 0) {
            grow(requiredInts);
            return allocate(requiredInts);
        }

        FreeSpan span = freeSpans.get(bestIndex);
        Allocation allocation = new Allocation(span.offsetInts(), requiredInts);
        if (span.lengthInts() == requiredInts) {
            freeSpans.remove(bestIndex);
        } else {
            span.offsetInts += requiredInts;
            span.lengthInts -= requiredInts;
        }
        reservedInts += requiredInts;
        activeAllocationCount++;
        return allocation;
    }

    private void grow(int requiredInts) {
        int oldCapacityInts = capacityInts;
        int newCapacityInts = Math.max(capacityInts * 2, capacityInts + requiredInts);
        int newSsbo = glGenBuffers();

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, newSsbo);
        glBufferData(GL_SHADER_STORAGE_BUFFER, (long) newCapacityInts * Integer.BYTES, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        glBindBuffer(GL_COPY_READ_BUFFER, ssbo);
        glBindBuffer(GL_COPY_WRITE_BUFFER, newSsbo);
        glCopyBufferSubData(
                GL_COPY_READ_BUFFER,
                GL_COPY_WRITE_BUFFER,
                0L,
                0L,
                (long) oldCapacityInts * Integer.BYTES
        );
        glBindBuffer(GL_COPY_READ_BUFFER, 0);
        glBindBuffer(GL_COPY_WRITE_BUFFER, 0);

        glDeleteBuffers(ssbo);
        ssbo = newSsbo;
        capacityInts = newCapacityInts;

        freeSpans.add(new FreeSpan(oldCapacityInts, newCapacityInts - oldCapacityInts));
        mergeFreeSpans();
    }

    private void mergeFreeSpans() {
        freeSpans.sort(Comparator.comparingInt(FreeSpan::offsetInts));
        for (int index = 0; index < freeSpans.size() - 1; ) {
            FreeSpan current = freeSpans.get(index);
            FreeSpan next = freeSpans.get(index + 1);
            if (current.offsetInts() + current.lengthInts() == next.offsetInts()) {
                current.lengthInts += next.lengthInts();
                freeSpans.remove(index + 1);
                continue;
            }
            index++;
        }
    }

    public record Allocation(int offsetInts, int capacityInts) {
    }

    private static final class FreeSpan {
        private int offsetInts;
        private int lengthInts;

        private FreeSpan(int offsetInts, int lengthInts) {
            this.offsetInts = offsetInts;
            this.lengthInts = lengthInts;
        }

        private int offsetInts() {
            return offsetInts;
        }

        private int lengthInts() {
            return lengthInts;
        }
    }
}
