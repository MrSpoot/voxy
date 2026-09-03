package org.weaw.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FrameEventAccumulatorTest {
    @Test
    void sumsEveryFixedUpdateOnceAndResetsFramesWithoutUpdates() {
        FrameEventAccumulator<Integer> accumulator = new FrameEventAccumulator<>();

        accumulator.add(3);
        accumulator.add(4);

        assertEquals(2, accumulator.size());
        assertEquals(7, accumulator.sumInt(Integer::intValue));
        assertEquals(7L, accumulator.sumLong(Integer::longValue));
        assertEquals(3, accumulator.firstOr(-1));
        assertEquals(4, accumulator.latestOr(-1));

        accumulator.reset();

        assertEquals(0, accumulator.size());
        assertEquals(0, accumulator.sumInt(Integer::intValue));
        assertEquals(-1, accumulator.firstOr(-1));
        assertEquals(-1, accumulator.latestOr(-1));
    }
}
