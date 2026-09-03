package org.weaw.engine.graphics.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkGpuMemoryBudgetTest {
    @Test
    void enforcesResidentAndTransientLimitsAcrossAllocators() {
        ChunkGpuMemoryBudget budget = new ChunkGpuMemoryBudget(100, 110);

        assertTrue(budget.register(40));
        assertTrue(budget.register(20));
        assertFalse(budget.tryResize(40, 90));
        assertFalse(budget.tryResize(40, 60));

        budget.release(20);
        assertTrue(budget.tryResize(40, 70));

        assertEquals(70, budget.getResidentBytes());
    }
}
