package org.weaw.game;

import java.util.Arrays;

/** Compact storage for the direct (vertical) skylight sources of a chunk. */
final class ChunkSkyLight {
    private static final int LEVEL_MASK = 0xF;

    private byte uniformLevel;
    private byte[] packedLevels;

    int get(int x, int y, int z) {
        return getAtIndex(index(x, y, z));
    }

    int getAtIndex(int index) {
        if (packedLevels == null) {
            return uniformLevel & LEVEL_MASK;
        }
        int packed = packedLevels[index >>> 1] & 0xFF;
        return (index & 1) == 0 ? packed & LEVEL_MASK : (packed >>> 4) & LEVEL_MASK;
    }

    void set(int x, int y, int z, int level) {
        validate(level);
        if (packedLevels == null) {
            if (level == (uniformLevel & LEVEL_MASK)) {
                return;
            }
            packedLevels = new byte[Chunk.TOTAL_BLOCKS / 2];
            int pair = (uniformLevel & LEVEL_MASK) | ((uniformLevel & LEVEL_MASK) << 4);
            if (pair != 0) {
                Arrays.fill(packedLevels, (byte) pair);
            }
        }
        int index = index(x, y, z);
        int packedIndex = index >>> 1;
        int previous = packedLevels[packedIndex] & 0xFF;
        packedLevels[packedIndex] = (byte) ((index & 1) == 0
                ? (previous & 0xF0) | level
                : (previous & 0x0F) | (level << 4));
    }

    void replaceWithLevels(byte[] levels) {
        if (levels.length != Chunk.TOTAL_BLOCKS) {
            throw new IllegalArgumentException("Skylight source array must contain exactly " + Chunk.TOTAL_BLOCKS + " values");
        }
        int first = levels[0] & LEVEL_MASK;
        boolean uniform = true;
        for (int index = 1; index < levels.length; index++) {
            if ((levels[index] & LEVEL_MASK) != first) {
                uniform = false;
                break;
            }
        }
        uniformLevel = (byte) first;
        if (uniform) {
            packedLevels = null;
            return;
        }
        if (packedLevels == null) {
            packedLevels = new byte[Chunk.TOTAL_BLOCKS / 2];
        }
        for (int index = 0; index < levels.length; index += 2) {
            packedLevels[index >>> 1] = (byte) ((levels[index] & LEVEL_MASK)
                    | ((levels[index + 1] & LEVEL_MASK) << 4));
        }
    }

    void clear() {
        uniformLevel = 0;
        packedLevels = null;
    }

    void copyFrom(ChunkSkyLight other) {
        uniformLevel = other.uniformLevel;
        packedLevels = other.packedLevels == null ? null : Arrays.copyOf(other.packedLevels, other.packedLevels.length);
    }

    long estimateRetainedBytes() {
        return 24L + (packedLevels == null ? 0L : 16L + packedLevels.length);
    }

    private static int index(int x, int y, int z) {
        return x + z * Chunk.SIZE + y * Chunk.SIZE * Chunk.SIZE;
    }

    private static void validate(int level) {
        if (level < 0 || level > ChunkLighting.MAX_SKY_LIGHT) {
            throw new IllegalArgumentException("Direct skylight level out of range: " + level);
        }
    }
}
