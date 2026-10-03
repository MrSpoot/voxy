package org.weaw.network.client;

import org.junit.jupiter.api.Test;
import org.weaw.network.protocol.WorldTimeSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientWorldClockTest {
    @Test
    void interpolatesCorrectionsWithoutSnapping() {
        ClientWorldClock clock = new ClientWorldClock();
        clock.synchronize(new WorldTimeSnapshot(0.25, 1_200, 1.0, false));
        clock.synchronize(new WorldTimeSnapshot(0.35, 1_200, 1.0, false));

        assertEquals(0.25, clock.phase(), 1.0e-9);
        clock.update(0.1);

        assertTrue(clock.phase() > 0.25 && clock.phase() < 0.35);
    }

    @Test
    void correctionTakesShortestPathAcrossMidnight() {
        ClientWorldClock clock = new ClientWorldClock();
        clock.synchronize(new WorldTimeSnapshot(0.99, 1_200, 1.0, true));
        clock.synchronize(new WorldTimeSnapshot(0.01, 1_200, 1.0, true));
        clock.update(0.1);

        assertTrue(clock.phase() > 0.99 || clock.phase() < 0.01);
        assertEquals(0.02, ClientWorldClock.shortestDelta(0.99, 0.01), 1.0e-9);
    }
}
