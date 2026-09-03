package org.weaw.engine.graphics.utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Best-fit allocator for integer ranges. It has no OpenGL dependency. */
public final class IntRangeAllocator {
    private final List<FreeRange> freeRanges = new ArrayList<>();
    private final Map<Integer, Integer> allocations = new HashMap<>();
    private int capacity;
    private long reserved;
    private int allocationCount;

    public IntRangeAllocator(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity must be non-negative");
        }
        this.capacity = capacity;
        if (capacity > 0) {
            freeRanges.add(new FreeRange(0, capacity));
        }
    }

    public Range allocate(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive");
        }
        int bestIndex = -1;
        int bestLength = Integer.MAX_VALUE;
        for (int index = 0; index < freeRanges.size(); index++) {
            FreeRange range = freeRanges.get(index);
            if (range.length >= length && range.length < bestLength) {
                bestIndex = index;
                bestLength = range.length;
            }
        }
        if (bestIndex < 0) {
            return null;
        }

        FreeRange freeRange = freeRanges.get(bestIndex);
        Range allocation = new Range(freeRange.offset, length);
        if (freeRange.length == length) {
            freeRanges.remove(bestIndex);
        } else {
            freeRange.offset += length;
            freeRange.length -= length;
        }
        reserved += length;
        allocationCount++;
        allocations.put(allocation.offset(), allocation.length());
        return allocation;
    }

    public void free(Range range) {
        if (range == null) {
            return;
        }
        if (range.offset < 0 || range.length <= 0 || (long) range.offset + range.length > capacity) {
            throw new IllegalArgumentException("range is outside allocator capacity");
        }
        Integer allocatedLength = allocations.get(range.offset());
        if (allocatedLength == null || allocatedLength != range.length()) {
            throw new IllegalStateException("range is unknown or was already freed");
        }
        allocations.remove(range.offset());
        freeRanges.add(new FreeRange(range.offset, range.length));
        reserved -= range.length;
        allocationCount--;
        mergeFreeRanges();
    }

    public void grow(int newCapacity) {
        if (newCapacity < capacity) {
            throw new IllegalArgumentException("allocator capacity cannot shrink");
        }
        if (newCapacity == capacity) {
            return;
        }
        freeRanges.add(new FreeRange(capacity, newCapacity - capacity));
        capacity = newCapacity;
        mergeFreeRanges();
    }

    public void clear() {
        capacity = 0;
        reserved = 0L;
        allocationCount = 0;
        freeRanges.clear();
        allocations.clear();
    }

    public int capacity() {
        return capacity;
    }

    public long reserved() {
        return reserved;
    }

    public long free() {
        return Math.max(0L, capacity - reserved);
    }

    public int allocationCount() {
        return allocationCount;
    }

    public int freeRangeCount() {
        return freeRanges.size();
    }

    public int largestFreeRange() {
        int largest = 0;
        for (FreeRange range : freeRanges) {
            largest = Math.max(largest, range.length);
        }
        return largest;
    }

    public float fragmentationRatio() {
        long free = free();
        return free == 0L ? 0.0f : 1.0f - ((float) largestFreeRange() / free);
    }

    private void mergeFreeRanges() {
        freeRanges.sort(Comparator.comparingInt(range -> range.offset));
        for (int index = 0; index < freeRanges.size() - 1; ) {
            FreeRange current = freeRanges.get(index);
            FreeRange next = freeRanges.get(index + 1);
            if (current.offset + current.length > next.offset) {
                throw new IllegalStateException("overlapping free ranges");
            }
            if (current.offset + current.length == next.offset) {
                current.length += next.length;
                freeRanges.remove(index + 1);
            } else {
                index++;
            }
        }
    }

    public record Range(int offset, int length) {
    }

    private static final class FreeRange {
        private int offset;
        private int length;

        private FreeRange(int offset, int length) {
            this.offset = offset;
            this.length = length;
        }
    }
}
