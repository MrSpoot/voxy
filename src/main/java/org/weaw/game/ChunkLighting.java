package org.weaw.game;

import java.util.Arrays;

public final class ChunkLighting {
    public static final int MAX_RGB_COMPONENT = 0xF;
    public static final int MAX_SKY_LIGHT = 0xF;

    private static final int RED_BITS = 4;
    private static final int GREEN_SHIFT = RED_BITS;
    private static final int BLUE_SHIFT = GREEN_SHIFT + RED_BITS;
    private static final int SKY_SHIFT = BLUE_SHIFT + RED_BITS;
    private static final int LIGHTS_PER_PACKED_INT = 2;

    private short uniformLight;
    private short[] data;

    public short getPackedLight(int x, int y, int z) {
        int index = getBlockIndex(x, y, z);
        return getPackedLightAtIndex(index);
    }

    short getPackedLightAtIndex(int index) {
        return data == null ? uniformLight : data[index];
    }

    public void setPackedLight(int x, int y, int z, short packedLight) {
        int index = getBlockIndex(x, y, z);
        if (data == null) {
            if (packedLight == uniformLight) {
                return;
            }
            data = new short[Chunk.TOTAL_BLOCKS];
            if (uniformLight != 0) {
                Arrays.fill(data, uniformLight);
            }
        }
        data[index] = packedLight;
    }

    public void setLight(int x, int y, int z, int red, int green, int blue, int sky) {
        setPackedLight(x, y, z, pack(red, green, blue, sky));
    }

    public int getRed(int x, int y, int z) {
        return getRed(getPackedLight(x, y, z));
    }

    public int getGreen(int x, int y, int z) {
        return getGreen(getPackedLight(x, y, z));
    }

    public int getBlue(int x, int y, int z) {
        return getBlue(getPackedLight(x, y, z));
    }

    public int getSky(int x, int y, int z) {
        return getSky(getPackedLight(x, y, z));
    }

    public boolean isDark(int x, int y, int z) {
        return getPackedLight(x, y, z) == 0;
    }

    public void clear() {
        data = null;
        uniformLight = 0;
    }

    public void fill(short packedLight) {
        data = null;
        uniformLight = packedLight;
    }

    boolean replaceWithOwnedData(short[] replacement) {
        if (replacement.length != Chunk.TOTAL_BLOCKS) {
            throw new IllegalArgumentException("Lighting array must contain exactly " + Chunk.TOTAL_BLOCKS + " values");
        }

        short first = replacement[0];
        boolean uniform = true;
        for (int index = 1; index < replacement.length; index++) {
            if (replacement[index] != first) {
                uniform = false;
                break;
            }
        }

        if (uniform) {
            boolean changed = data != null || uniformLight != first;
            data = null;
            uniformLight = first;
            return changed;
        }

        if (data != null && Arrays.equals(data, replacement)) {
            return false;
        }
        data = replacement;
        uniformLight = 0;
        return true;
    }

    public ChunkLighting copy() {
        ChunkLighting copy = new ChunkLighting();
        copy.uniformLight = uniformLight;
        copy.data = data == null ? null : Arrays.copyOf(data, data.length);
        return copy;
    }

    public void copyFrom(ChunkLighting other) {
        uniformLight = other.uniformLight;
        data = other.data == null ? null : Arrays.copyOf(other.data, other.data.length);
    }

    public int[] packToIntArray() {
        int[] packed = new int[packedIntCount()];
        if (data == null) {
            int pair = (uniformLight & 0xFFFF) | ((uniformLight & 0xFFFF) << 16);
            Arrays.fill(packed, pair);
            return packed;
        }
        for (int index = 0; index < data.length; index += LIGHTS_PER_PACKED_INT) {
            int low = data[index] & 0xFFFF;
            int high = index + 1 < data.length ? (data[index + 1] & 0xFFFF) << 16 : 0;
            packed[index / LIGHTS_PER_PACKED_INT] = low | high;
        }
        return packed;
    }

    public boolean isCompact() {
        return data == null;
    }

    public boolean isAllDark() {
        return data == null && uniformLight == 0;
    }

    public short getUniformLight() {
        if (data != null) {
            throw new IllegalStateException("Lighting is not uniform");
        }
        return uniformLight;
    }

    public long estimateRetainedBytes() {
        return 32L + (data == null ? 0L : 16L + (long) data.length * Short.BYTES);
    }

    public static int packedIntCount() {
        return (Chunk.TOTAL_BLOCKS + LIGHTS_PER_PACKED_INT - 1) / LIGHTS_PER_PACKED_INT;
    }

    public static short pack(int red, int green, int blue, int sky) {
        validateRgb(red, "red");
        validateRgb(green, "green");
        validateRgb(blue, "blue");
        validateSky(sky);

        return (short) (
                (red & MAX_RGB_COMPONENT)
                        | ((green & MAX_RGB_COMPONENT) << GREEN_SHIFT)
                        | ((blue & MAX_RGB_COMPONENT) << BLUE_SHIFT)
                        | ((sky & MAX_SKY_LIGHT) << SKY_SHIFT)
        );
    }

    public static int getRed(short packedLight) {
        return packedLight & MAX_RGB_COMPONENT;
    }

    public static int getGreen(short packedLight) {
        return (packedLight >>> GREEN_SHIFT) & MAX_RGB_COMPONENT;
    }

    public static int getBlue(short packedLight) {
        return (packedLight >>> BLUE_SHIFT) & MAX_RGB_COMPONENT;
    }

    public static int getSky(short packedLight) {
        return (packedLight >>> SKY_SHIFT) & MAX_SKY_LIGHT;
    }

    /** Returns the strongest voxel-light component, including both skylight and RGB block light. */
    public static int getCombinedLevel(short packedLight) {
        return Math.max(
                getSky(packedLight),
                Math.max(getRed(packedLight), Math.max(getGreen(packedLight), getBlue(packedLight)))
        );
    }

    private static int getBlockIndex(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= Chunk.SIZE || y >= Chunk.SIZE || z >= Chunk.SIZE) {
            throw new IndexOutOfBoundsException("Chunk coordinates out of bounds: " + x + ", " + y + ", " + z);
        }
        return x + (z * Chunk.SIZE) + (y * Chunk.SIZE * Chunk.SIZE);
    }

    private static void validateRgb(int component, String componentName) {
        if (component < 0 || component > MAX_RGB_COMPONENT) {
            throw new IllegalArgumentException(componentName + " light component out of range: " + component);
        }
    }

    private static void validateSky(int sky) {
        if (sky < 0 || sky > MAX_SKY_LIGHT) {
            throw new IllegalArgumentException("sky light component out of range: " + sky);
        }
    }
}
