package org.weaw.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixedRateUpdateSchedulerTest {
    @Test
    void updateAccumulatesTimeAtConfiguredRate() {
        FixedRateUpdateScheduler scheduler = new FixedRateUpdateScheduler(60, 2);
        AtomicInteger updates = new AtomicInteger();

        assertEquals(0, scheduler.update(0.008f, updates::incrementAndGet));
        assertEquals(0, updates.get());

        assertEquals(1, scheduler.update(0.009f, updates::incrementAndGet));
        assertEquals(1, updates.get());
    }

    @Test
    void updateCapsCatchUpWorkPerFrame() {
        FixedRateUpdateScheduler scheduler = new FixedRateUpdateScheduler(60, 2);
        AtomicInteger updates = new AtomicInteger();

        assertEquals(2, scheduler.update(1.0f, updates::incrementAndGet));
        assertEquals(2, updates.get());
        assertEquals(0, scheduler.update(0.0f, updates::incrementAndGet));
        assertEquals(2, updates.get());
    }
}
