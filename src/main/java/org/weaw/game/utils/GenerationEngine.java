package org.weaw.game.utils;

import org.weaw.game.Chunk;

import static org.weaw.game.utils.Blocks.AIR;
import static org.weaw.game.utils.Blocks.DIRT;
import static org.weaw.game.utils.Blocks.GRASS_BLOCK;
import static org.weaw.game.utils.Blocks.LEAVES;
import static org.weaw.game.utils.Blocks.SAND;
import static org.weaw.game.utils.Blocks.STONE;
import static org.weaw.game.utils.Blocks.WATER;
import static org.weaw.game.utils.Blocks.WOOD_LOG;

public class GenerationEngine {
    private static final long SEED = 1;
    private static final float AMPLITUDE = 25.0f;
    private static final int BASE_HEIGHT = 0;
    private static final int WATER_LEVEL = -10;

    private static final ThreadLocal<FastNoiseLite> noise = ThreadLocal.withInitial(() -> {
        FastNoiseLite n = new FastNoiseLite();
        n.SetSeed((int) SEED);
        return n;
    });

    private static final ThreadLocal<FastNoiseLite> treeNoise = ThreadLocal.withInitial(() -> {
        FastNoiseLite n = new FastNoiseLite();
        n.SetSeed((int) SEED + 999);
        return n;
    });

    private GenerationEngine() {
    }

    public static void generateChunkData(Chunk chunk) {
        int chunkGlobalX = chunk.getPosition().x * Chunk.SIZE;
        int chunkGlobalZ = chunk.getPosition().z * Chunk.SIZE;
        int chunkGlobalY = chunk.getPosition().y * Chunk.SIZE;

        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int z = 0; z < Chunk.SIZE; z++) {
                int globalX = chunkGlobalX + x;
                int globalZ = chunkGlobalZ + z;

                for (int y = 0; y < Chunk.SIZE; y++) {
                    int globalY = chunkGlobalY + y;
                    chunk.setBlock(x, y, z, BlockRegistry.getBlock(getBlockAtWorld(globalX, globalY, globalZ)));
                }
            }
        }
    }

    public static short getBlockAtWorld(int worldX, int worldY, int worldZ) {
        short baseBlock = getBaseTerrainBlock(worldX, worldY, worldZ);
        if (baseBlock != AIR.getId()) {
            return baseBlock;
        }

//        for (int treeX = worldX - 2; treeX <= worldX + 2; treeX++) {
//            for (int treeZ = worldZ - 2; treeZ <= worldZ + 2; treeZ++) {
//                short treeBlock = getTreeBlockAt(treeX, treeZ, worldX, worldY, worldZ);
//                if (treeBlock != AIR.getId()) {
//                    return treeBlock;
//                }
//            }
//        }

        return baseBlock;
    }

    public static int getSurfaceHeight(int worldX, int worldZ) {
        float height = getFractalNoise(noise.get(), worldX * 0.3f, worldZ * 0.3f, 4, 2.0f, 0.5f) * AMPLITUDE + BASE_HEIGHT;
        return (int) height;
    }

    private static short getBaseTerrainBlock(int worldX, int worldY, int worldZ) {
        int height = getSurfaceHeight(worldX, worldZ);

        if (worldY > height) {
            return worldY <= WATER_LEVEL ? WATER.getId() : AIR.getId();
        }

        if (worldY < WATER_LEVEL + 1) {
            return worldY >= height - 3 ? SAND.getId() : STONE.getId();
        }

        if (worldY == height) {
            return GRASS_BLOCK.getId();
        }

        return worldY >= height - 3 ? DIRT.getId() : STONE.getId();
    }

    private static short getTreeBlockAt(int treeX, int treeZ, int worldX, int worldY, int worldZ) {
        int surfaceY = getSurfaceHeight(treeX, treeZ);
        if (surfaceY <= WATER_LEVEL) {
            return AIR.getId();
        }

        if (!shouldPlace(treeNoise.get().GetNoise(treeX, treeZ), treeX, treeZ, 0.5f, 2.0f)) {
            return AIR.getId();
        }

        int trunkBaseY = surfaceY + 1;
        if (worldX == treeX && worldZ == treeZ && worldY >= trunkBaseY && worldY < trunkBaseY + 4) {
            return WOOD_LOG.getId();
        }

        int dx = worldX - treeX;
        int dy = worldY - (trunkBaseY + 3);
        int dz = worldZ - treeZ;
        int distance = dx * dx + dy * dy + dz * dz;

        if (dy >= 0 && dy <= 2 && distance <= 5) {
            return LEAVES.getId();
        }

        return AIR.getId();
    }

    private static boolean shouldPlace(float noiseValue, int globalX, int globalZ, float rarity, float steepness) {
        float adjusted = noiseValue * 0.2f + 0.2f;
        adjusted = (float) Math.pow(adjusted, steepness);
        float random = randomValueBasedOnBlock(globalX, globalZ) * rarity;
        return adjusted > random;
    }

    private static float randomValueBasedOnBlock(int x, int z) {
        return hash2D(x, z);
    }

    private static float hash2D(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >> 13)) * 1274126177;
        return ((h ^ (h >> 16)) & 0x7fffffff) / (float) Integer.MAX_VALUE;
    }

    public static float getFractalNoise(FastNoiseLite noise, float x, float y, int octaves, float lacunarity, float gain) {
        float total = 0f;
        float frequency = 1f;
        float amplitude = 1f;
        float maxAmplitude = 0f;

        for (int i = 0; i < octaves; i++) {
            total += noise.GetNoise(x * frequency, y * frequency) * amplitude;
            maxAmplitude += amplitude;
            frequency *= lacunarity;
            amplitude *= gain;
        }

        return total / maxAmplitude;
    }
}
