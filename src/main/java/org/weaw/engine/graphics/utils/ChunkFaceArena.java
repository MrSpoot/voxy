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
import static org.lwjgl.opengl.GL30C.glBindVertexArray;
import static org.lwjgl.opengl.GL31C.GL_COPY_READ_BUFFER;
import static org.lwjgl.opengl.GL31C.GL_COPY_WRITE_BUFFER;
import static org.lwjgl.opengl.GL31C.glCopyBufferSubData;
import static org.lwjgl.opengl.GL43.GL_SHADER_STORAGE_BUFFER;
import static org.lwjgl.opengl.GL43.glBindBufferBase;

/**
 * Shared SSBO arena for chunk face data.
 *
 * Multiple chunk allocations live inside one growable SSBO. Chunks keep only
 * offset/capacity/count metadata instead of owning one dedicated buffer each.
 */
public final class ChunkFaceArena {
    private static final int INTS_PER_FACE = 2;
    private static final int MIN_CAPACITY_INTS = 1024;

    private final int vao;
    private final int maxCapacityInts;
    private final ChunkGpuMemoryBudget gpuMemoryBudget;
    private final List<FreeSpan> freeSpans = new ArrayList<>();

    private int ssbo;
    private int capacityInts;
    private int activeAllocationCount;
    private long reservedInts;
    private long payloadInts;
    private IntBuffer stagingBuffer;

    public ChunkFaceArena(int vao, int initialCapacityInts) {
        this(vao, initialCapacityInts, standaloneBudget(Long.MAX_VALUE));
    }

    public ChunkFaceArena(int vao, int initialCapacityInts, long maxGpuBytes) {
        this(vao, initialCapacityInts, standaloneBudget(maxGpuBytes));
    }

    public ChunkFaceArena(int vao, int initialCapacityInts, ChunkGpuMemoryBudget gpuMemoryBudget) {
        this.vao = vao;
        this.gpuMemoryBudget = gpuMemoryBudget;
        this.maxCapacityInts = (int) Math.min(
                Integer.MAX_VALUE,
                Math.max(MIN_CAPACITY_INTS, gpuMemoryBudget.getMaxResidentBytes() / Integer.BYTES)
        );
        this.capacityInts = Math.min(maxCapacityInts, Math.max(initialCapacityInts, MIN_CAPACITY_INTS));
        if (!gpuMemoryBudget.register((long) capacityInts * Integer.BYTES)) {
            throw new IllegalStateException("GPU chunk budget is too small for the initial face arena");
        }
        this.ssbo = glGenBuffers();

        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
        glBufferData(GL_SHADER_STORAGE_BUFFER, (long) capacityInts * Integer.BYTES, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        freeSpans.add(new FreeSpan(0, capacityInts));
    }

    public Allocation upload(int[] faceData, int faceCount, Allocation existing) {
        int requiredInts = faceCount * INTS_PER_FACE;
        if (requiredInts == 0) {
            free(existing);
            return null;
        }

        Allocation target = existing;
        boolean reused = target != null && requiredInts <= target.capacityInts();
        if (!reused) {
            free(existing);
            target = allocate(requiredInts);
            if (target == null) {
                return null;
            }
            payloadInts += requiredInts;
        } else if (target.faceCount() != faceCount) {
            payloadInts += (long) (faceCount - target.faceCount()) * INTS_PER_FACE;
        }

        ensureStagingCapacity(requiredInts);
        stagingBuffer.clear();
        stagingBuffer.put(faceData, 0, requiredInts).flip();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, ssbo);
        glBufferSubData(GL_SHADER_STORAGE_BUFFER, (long) target.offsetInts() * Integer.BYTES, stagingBuffer);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        return new Allocation(target.offsetInts(), target.capacityInts(), faceCount);
    }

    public void free(Allocation allocation) {
        if (allocation == null) {
            return;
        }

        reservedInts -= allocation.capacityInts();
        payloadInts = Math.max(0L, payloadInts - ((long) allocation.faceCount() * INTS_PER_FACE));
        freeSpans.add(new FreeSpan(allocation.offsetInts(), allocation.capacityInts()));
        activeAllocationCount--;
        mergeFreeSpans();
    }

    public void bind() {
        glBindVertexArray(vao);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, ssbo);
    }

    public void unbind() {
        glBindVertexArray(0);
    }

    public long getEstimatedGpuBytes() {
        return (long) capacityInts * Integer.BYTES;
    }

    public int getCapacityInts() {
        return capacityInts;
    }

    public long getReservedInts() {
        return reservedInts;
    }

    public long getPayloadInts() {
        return payloadInts;
    }

    public long getFreeInts() {
        return Math.max(0L, capacityInts - reservedInts);
    }

    public int getActiveAllocationCount() {
        return activeAllocationCount;
    }

    public int getFreeSpanCount() {
        return freeSpans.size();
    }

    public int getLargestFreeSpanInts() {
        int largest = 0;
        for (FreeSpan span : freeSpans) {
            largest = Math.max(largest, span.lengthInts());
        }
        return largest;
    }

    public float getReservationRatio() {
        if (capacityInts == 0) {
            return 0.0f;
        }
        return (float) reservedInts / capacityInts;
    }

    public float getPayloadRatio() {
        if (capacityInts == 0) {
            return 0.0f;
        }
        return (float) payloadInts / capacityInts;
    }

    public float getFragmentationRatio() {
        long freeInts = getFreeInts();
        if (freeInts <= 0) {
            return 0.0f;
        }
        return 1.0f - ((float) getLargestFreeSpanInts() / freeInts);
    }

    public void cleanup() {
        if (ssbo != 0) {
            glDeleteBuffers(ssbo);
            ssbo = 0;
        }
        gpuMemoryBudget.release((long) capacityInts * Integer.BYTES);
        freeSpans.clear();
        capacityInts = 0;
        activeAllocationCount = 0;
        reservedInts = 0L;
        payloadInts = 0L;
        if (stagingBuffer != null) {
            MemoryUtil.memFree(stagingBuffer);
            stagingBuffer = null;
        }
    }

    private void ensureStagingCapacity(int requiredInts) {
        if (stagingBuffer == null) {
            stagingBuffer = MemoryUtil.memAllocInt(requiredInts);
        } else if (stagingBuffer.capacity() < requiredInts) {
            stagingBuffer = MemoryUtil.memRealloc(stagingBuffer, requiredInts);
        }
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
            if (!grow(requiredInts)) {
                return null;
            }
            return allocate(requiredInts);
        }

        FreeSpan span = freeSpans.get(bestIndex);
        Allocation allocation = new Allocation(span.offsetInts(), requiredInts, 0);
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

    private boolean grow(int requiredInts) {
        int oldCapacityInts = capacityInts;
        long desiredCapacity = Math.max((long) capacityInts * 2L, (long) capacityInts + requiredInts);
        int newCapacityInts = (int) Math.min(maxCapacityInts, desiredCapacity);
        if (newCapacityInts <= oldCapacityInts || (long) oldCapacityInts + requiredInts > maxCapacityInts) {
            return false;
        }
        if (!gpuMemoryBudget.tryResize(
                (long) oldCapacityInts * Integer.BYTES,
                (long) newCapacityInts * Integer.BYTES
        )) {
            return false;
        }
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
        return true;
    }

    private static ChunkGpuMemoryBudget standaloneBudget(long maxGpuBytes) {
        long residentLimit = Math.max((long) MIN_CAPACITY_INTS * Integer.BYTES, maxGpuBytes);
        return new ChunkGpuMemoryBudget(residentLimit, Long.MAX_VALUE);
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

    public record Allocation(int offsetInts, int capacityInts, int faceCount) {
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
