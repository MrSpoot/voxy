package org.weaw.game;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkSkyLightTest {
    @Test
    void roundTripsUniformPackedLevels() {
        ChunkSkyLight source = new ChunkSkyLight();
        byte[] fullSun = new byte[ChunkSkyLight.packedByteCount()];
        Arrays.fill(fullSun, (byte) 0xFF);
        source.replacePackedByteArray(fullSun);

        byte[] packed = source.packToByteArray();
        ChunkSkyLight restored = new ChunkSkyLight();
        restored.replacePackedByteArray(packed);

        assertArrayEquals(fullSun, packed);
        assertEquals(15, restored.get(0, 0, 0));
        assertEquals(15, restored.get(Chunk.SIZE - 1, Chunk.SIZE - 1, Chunk.SIZE - 1));
    }

    @Test
    void roundTripsNonUniformPackedLevelsWithoutSharingStorage() {
        byte[] packed = new byte[ChunkSkyLight.packedByteCount()];
        packed[0] = (byte) 0xE3;
        packed[packed.length - 1] = (byte) 0x7A;
        byte[] expected = packed.clone();
        ChunkSkyLight restored = new ChunkSkyLight();

        restored.replacePackedByteArray(packed);
        packed[0] = 0;

        assertEquals(3, restored.getAtIndex(0));
        assertEquals(14, restored.getAtIndex(1));
        assertEquals(10, restored.getAtIndex(Chunk.TOTAL_BLOCKS - 2));
        assertEquals(7, restored.getAtIndex(Chunk.TOTAL_BLOCKS - 1));
        assertArrayEquals(expected, restored.packToByteArray());
    }

    @Test
    void rejectsAnInvalidPackedLength() {
        ChunkSkyLight directSky = new ChunkSkyLight();

        assertThrows(
                IllegalArgumentException.class,
                () -> directSky.replacePackedByteArray(new byte[ChunkSkyLight.packedByteCount() - 1])
        );
    }
}
