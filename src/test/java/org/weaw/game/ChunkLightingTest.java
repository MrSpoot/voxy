package org.weaw.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkLightingTest {
    @Test
    void startsDarkWithoutAllocatingFullStorage() {
        ChunkLighting lighting = new ChunkLighting();

        assertTrue(lighting.isCompact());
        assertTrue(lighting.isAllDark());
        assertEquals(0, lighting.getPackedLight(0, 0, 0));
        assertTrue(lighting.estimateRetainedBytes() < Chunk.TOTAL_BLOCKS * Short.BYTES);
    }

    @Test
    void differentWriteExpandsAndClearCompactsAgain() {
        ChunkLighting lighting = new ChunkLighting();
        short light = ChunkLighting.pack(12, 3, 1, 0);

        lighting.setPackedLight(4, 5, 6, light);

        assertFalse(lighting.isCompact());
        assertEquals(light, lighting.getPackedLight(4, 5, 6));
        assertEquals(0, lighting.getPackedLight(4, 5, 7));

        lighting.clear();

        assertTrue(lighting.isCompact());
        assertTrue(lighting.isAllDark());
    }

    @Test
    void fillAndCopyPreserveCompactRepresentation() {
        ChunkLighting source = new ChunkLighting();
        short light = ChunkLighting.pack(1, 2, 3, 15);
        source.fill(light);

        ChunkLighting copy = source.copy();

        assertTrue(copy.isCompact());
        assertEquals(light, copy.getUniformLight());
        assertEquals(light, copy.getPackedLight(Chunk.SIZE - 1, Chunk.SIZE - 1, Chunk.SIZE - 1));
    }

    @Test
    void copyOfExpandedStorageIsIndependent() {
        ChunkLighting source = new ChunkLighting();
        short first = ChunkLighting.pack(10, 0, 0, 0);
        short second = ChunkLighting.pack(0, 10, 0, 0);
        source.setPackedLight(1, 2, 3, first);

        ChunkLighting copy = source.copy();
        source.setPackedLight(1, 2, 3, second);

        assertEquals(first, copy.getPackedLight(1, 2, 3));
        assertEquals(second, source.getPackedLight(1, 2, 3));
    }

    @Test
    void bulkReplacementCompactsUniformResultsAndDetectsUnchangedData() {
        ChunkLighting lighting = new ChunkLighting();
        short fullSky = ChunkLighting.pack(0, 0, 0, 15);
        short[] replacement = new short[Chunk.TOTAL_BLOCKS];
        java.util.Arrays.fill(replacement, fullSky);

        assertTrue(lighting.replaceWithOwnedData(replacement));
        assertTrue(lighting.isCompact());
        assertEquals(fullSky, lighting.getUniformLight());

        short[] sameReplacement = new short[Chunk.TOTAL_BLOCKS];
        java.util.Arrays.fill(sameReplacement, fullSky);
        assertFalse(lighting.replaceWithOwnedData(sameReplacement));
    }

    @Test
    void combinedLevelIncludesSkyAndEveryBlockLightChannel() {
        assertEquals(0, ChunkLighting.getCombinedLevel(ChunkLighting.pack(0, 0, 0, 0)));
        assertEquals(15, ChunkLighting.getCombinedLevel(ChunkLighting.pack(0, 0, 0, 15)));
        assertEquals(12, ChunkLighting.getCombinedLevel(ChunkLighting.pack(12, 3, 4, 2)));
        assertEquals(13, ChunkLighting.getCombinedLevel(ChunkLighting.pack(3, 13, 4, 2)));
        assertEquals(14, ChunkLighting.getCombinedLevel(ChunkLighting.pack(3, 4, 14, 2)));
        assertEquals(11, ChunkLighting.getCombinedLevel(ChunkLighting.pack(3, 4, 5, 11)));
    }
}
