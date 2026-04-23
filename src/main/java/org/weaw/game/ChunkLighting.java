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

    private final short[] data = new short[Chunk.TOTAL_BLOCKS];

    public short getPackedLight(int x, int y, int z) {
        return data[getBlockIndex(x, y, z)];
    }

    public void setPackedLight(int x, int y, int z, short packedLight) {
        data[getBlockIndex(x, y, z)] = packedLight;
    }

    public void setLight(int x, int y, int z, int red, int green, int blue, int sky) {
        data[getBlockIndex(x, y, z)] = pack(red, green, blue, sky);
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
        Arrays.fill(data, (short) 0);
    }

    public void fill(short packedLight) {
        Arrays.fill(data, packedLight);
    }

    public ChunkLighting copy() {
        ChunkLighting copy = new ChunkLighting();
        System.arraycopy(data, 0, copy.data, 0, data.length);
        return copy;
    }

    public void copyFrom(ChunkLighting other) {
        System.arraycopy(other.data, 0, data, 0, data.length);
    }

    public int[] packToIntArray() {
        int[] packed = new int[packedIntCount()];
        for (int index = 0; index < data.length; index += LIGHTS_PER_PACKED_INT) {
            int low = data[index] & 0xFFFF;
            int high = index + 1 < data.length ? (data[index + 1] & 0xFFFF) << 16 : 0;
            packed[index / LIGHTS_PER_PACKED_INT] = low | high;
        }
        return packed;
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
