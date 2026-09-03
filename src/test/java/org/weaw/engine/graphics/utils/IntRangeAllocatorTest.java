package org.weaw.engine.graphics.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IntRangeAllocatorTest {
    @Test
    void usesBestFitAndMergesAdjacentFreeRanges() {
        IntRangeAllocator allocator = new IntRangeAllocator(100);
        IntRangeAllocator.Range first = allocator.allocate(20);
        IntRangeAllocator.Range middle = allocator.allocate(30);
        IntRangeAllocator.Range last = allocator.allocate(10);
        IntRangeAllocator.Range tail = allocator.allocate(40);

        allocator.free(first);
        allocator.free(last);
        IntRangeAllocator.Range bestFit = allocator.allocate(8);

        assertEquals(50, bestFit.offset());
        allocator.free(bestFit);
        allocator.free(middle);
        allocator.free(tail);
        assertEquals(100, allocator.largestFreeRange());
        assertEquals(1, allocator.freeRangeCount());
        assertEquals(0L, allocator.reserved());
    }

    @Test
    void reportsFragmentationExhaustionAndGrowth() {
        IntRangeAllocator allocator = new IntRangeAllocator(20);
        IntRangeAllocator.Range first = allocator.allocate(5);
        IntRangeAllocator.Range second = allocator.allocate(10);
        allocator.allocate(5);

        assertNull(allocator.allocate(1));
        allocator.free(first);
        allocator.free(second);
        assertEquals(15, allocator.largestFreeRange());
        assertEquals(0.0f, allocator.fragmentationRatio(), 0.0001f);

        allocator.grow(40);
        assertEquals(20, allocator.largestFreeRange());
        assertEquals(40, allocator.capacity());
    }

    @Test
    void rejectsDoubleFree() {
        IntRangeAllocator allocator = new IntRangeAllocator(10);
        IntRangeAllocator.Range range = allocator.allocate(4);
        allocator.free(range);
        assertThrows(IllegalStateException.class, () -> allocator.free(range));
    }
}
