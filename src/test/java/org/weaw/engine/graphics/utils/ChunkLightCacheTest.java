package org.weaw.engine.graphics.utils;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.weaw.game.ChunkManager;
import org.weaw.game.ChunkManager.ChunkPosition;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkLightCacheTest {
    @Test
    void interiorLightChangeRefreshesOnlyItsOwnChunk() {
        ChunkPosition center = new ChunkPosition(3, 4, 5);

        assertEquals(Set.of(center), ChunkLightCache.affectedConsumers(center, 0));
    }

    @Test
    void boundaryLightChangeRefreshesOnlySamplingNeighbor() {
        ChunkPosition center = new ChunkPosition(3, 4, 5);
        Set<ChunkPosition> affected = ChunkLightCache.affectedConsumers(
                center,
                ChunkManager.LIGHT_BOUNDARY_LOW_X | ChunkManager.LIGHT_BOUNDARY_HIGH_Y
        );

        assertEquals(4, affected.size());
        assertTrue(affected.contains(center));
        assertTrue(affected.contains(new ChunkPosition(2, 4, 5)));
        assertTrue(affected.contains(new ChunkPosition(3, 5, 5)));
        assertTrue(affected.contains(new ChunkPosition(2, 5, 5)));
    }

    @Test
    void fullBoundaryInvalidationStillCoversPaddedNeighborhood() {
        assertEquals(27, ChunkLightCache.affectedConsumers(
                new ChunkPosition(0, 0, 0), ChunkManager.LIGHT_BOUNDARY_ALL).size());
    }

    @Test
    void newlyVisibleMissingLightingIsUrgentWhilePrefetchStaysInBackground() {
        assertEquals(ChunkLightCache.UploadPriority.URGENT,
                ChunkLightCache.classifyUpload(true, true, false, true, false));
        assertEquals(ChunkLightCache.UploadPriority.BACKGROUND,
                ChunkLightCache.classifyUpload(false, true, false, true, false));
        assertEquals(ChunkLightCache.UploadPriority.BACKGROUND,
                ChunkLightCache.classifyUpload(true, true, true, true, false));
        assertEquals(ChunkLightCache.UploadPriority.URGENT,
                ChunkLightCache.classifyUpload(true, true, true, true, true));
        assertEquals(ChunkLightCache.UploadPriority.NONE,
                ChunkLightCache.classifyUpload(false, false, false, true, false));
    }

    @Test
    void urgentUploadsAreOrderedNearestToCamera() {
        ChunkPosition near = new ChunkPosition(0, 0, 0);
        ChunkPosition middle = new ChunkPosition(3, 0, 0);
        ChunkPosition far = new ChunkPosition(8, 0, 0);

        List<ChunkPosition> sorted = ChunkLightCache.nearestFirst(
                new LinkedHashSet<>(List.of(far, near, middle)),
                new Vector3f(0.0f, 0.0f, 0.0f)
        );

        assertEquals(List.of(near, middle, far), sorted);
    }

    @Test
    void visibleChunksRemainProtectedWhenPrefetchProtectionIsRelaxed() {
        ChunkPosition visible = new ChunkPosition(0, 0, 0);
        ChunkPosition prefetched = new ChunkPosition(1, 0, 0);
        Set<ChunkPosition> visiblePositions = Set.of(visible);
        Set<ChunkPosition> prefetchPositions = Set.of(visible, prefetched);

        assertTrue(ChunkLightCache.isEvictionProtected(
                visible, visiblePositions, prefetchPositions, false));
        assertTrue(ChunkLightCache.isEvictionProtected(
                prefetched, visiblePositions, prefetchPositions, true));
        assertEquals(false, ChunkLightCache.isEvictionProtected(
                prefetched, visiblePositions, prefetchPositions, false));
    }
}
