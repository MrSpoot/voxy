package org.weaw.game.generation;

import org.weaw.game.Chunk;
import org.weaw.game.utils.Blocks;
import org.weaw.game.utils.FastNoiseLite;

import java.util.Objects;

public final class NoiseWorldGenerator implements WorldGenerator {
    private final GenerationConfig config;
    private final ThreadLocal<FastNoiseLite> noise;
    private final ThreadLocal<FastNoiseLite> treeNoise;

    public NoiseWorldGenerator(GenerationConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.noise = ThreadLocal.withInitial(() -> createNoise((int) config.seed()));
        this.treeNoise = ThreadLocal.withInitial(() -> createNoise((int) config.seed() + config.treeSeedOffset()));
    }

    @Override
    public void generateChunkData(Chunk chunk) {
        int chunkGlobalX = chunk.getPosition().x * Chunk.SIZE;
        int chunkGlobalZ = chunk.getPosition().z * Chunk.SIZE;
        int chunkGlobalY = chunk.getPosition().y * Chunk.SIZE;
        short[] blocks = new short[Chunk.TOTAL_BLOCKS];
        int[] surfaceHeights = new int[Chunk.SIZE * Chunk.SIZE];
        FastNoiseLite terrainNoise = noise.get();

        for (int x = 0; x < Chunk.SIZE; x++) {
            for (int z = 0; z < Chunk.SIZE; z++) {
                int globalX = chunkGlobalX + x;
                int globalZ = chunkGlobalZ + z;
                surfaceHeights[x + (z * Chunk.SIZE)] = getSurfaceHeight(terrainNoise, globalX, globalZ);
            }
        }

        for (int y = 0; y < Chunk.SIZE; y++) {
            int globalY = chunkGlobalY + y;
            int yOffset = y * Chunk.SIZE * Chunk.SIZE;
            for (int z = 0; z < Chunk.SIZE; z++) {
                int zOffset = yOffset + (z * Chunk.SIZE);
                for (int x = 0; x < Chunk.SIZE; x++) {
                    int height = surfaceHeights[x + (z * Chunk.SIZE)];
                    blocks[zOffset + x] = getBaseTerrainBlock(globalY, height);
                }
            }
        }

        populateTrees(chunk, blocks, chunkGlobalX, chunkGlobalY, chunkGlobalZ);
        chunk.setAllBlocks(blocks);
    }

    @Override
    public short getBlockAtWorld(int worldX, int worldY, int worldZ) {
        short baseBlock = getBaseTerrainBlock(worldX, worldY, worldZ);
        if (baseBlock != Blocks.AIR.getId()) {
            return baseBlock;
        }

        for (int treeX = worldX - 2; treeX <= worldX + 2; treeX++) {
            for (int treeZ = worldZ - 2; treeZ <= worldZ + 2; treeZ++) {
                short treeBlock = getTreeBlockAt(treeX, treeZ, worldX, worldY, worldZ);
                if (treeBlock != Blocks.AIR.getId()) {
                    return treeBlock;
                }
            }
        }

        return baseBlock;
    }

    @Override
    public int getSurfaceHeight(int worldX, int worldZ) {
        return getSurfaceHeight(noise.get(), worldX, worldZ);
    }

    private int getSurfaceHeight(FastNoiseLite terrainNoise, int worldX, int worldZ) {
        float height = getFractalNoise(
                terrainNoise,
                worldX * config.terrainFrequency(),
                worldZ * config.terrainFrequency(),
                config.terrainOctaves(),
                config.terrainLacunarity(),
                config.terrainGain()
        ) * config.amplitude() + config.baseHeight();
        return (int) height;
    }

    private short getBaseTerrainBlock(int worldX, int worldY, int worldZ) {
        int height = getSurfaceHeight(worldX, worldZ);
        return getBaseTerrainBlock(worldY, height);
    }

    private short getBaseTerrainBlock(int worldY, int height) {
        if (worldY > height) {
            return worldY <= config.waterLevel() ? Blocks.WATER.getId() : Blocks.AIR.getId();
        }

        if (worldY < config.waterLevel() + 1) {
            return worldY >= height - 3 ? Blocks.SAND.getId() : Blocks.STONE.getId();
        }

        if (worldY == height) {
            return Blocks.GRASS_BLOCK.getId();
        }

        return worldY >= height - 3 ? Blocks.DIRT.getId() : Blocks.STONE.getId();
    }

    private short getTreeBlockAt(int treeX, int treeZ, int worldX, int worldY, int worldZ) {
        int surfaceY = getSurfaceHeight(treeX, treeZ);
        if (surfaceY <= config.waterLevel()) {
            return Blocks.AIR.getId();
        }

        if (!shouldPlace(treeNoise.get().GetNoise(treeX, treeZ), treeX, treeZ)) {
            return Blocks.AIR.getId();
        }

        int trunkBaseY = surfaceY + 1;
        if (worldX == treeX && worldZ == treeZ && worldY >= trunkBaseY && worldY < trunkBaseY + 4) {
            return Blocks.WOOD_LOG.getId();
        }

        int dx = worldX - treeX;
        int dy = worldY - (trunkBaseY + 3);
        int dz = worldZ - treeZ;
        int distance = dx * dx + dy * dy + dz * dz;

        if (dy >= 0 && dy <= 2 && distance <= 5) {
            return Blocks.LEAVES.getId();
        }

        return Blocks.AIR.getId();
    }

    private void populateTrees(Chunk chunk, short[] blocks, int chunkGlobalX, int chunkGlobalY, int chunkGlobalZ) {
        int minTreeX = chunkGlobalX - 2;
        int maxTreeX = chunkGlobalX + Chunk.SIZE + 1;
        int minTreeZ = chunkGlobalZ - 2;
        int maxTreeZ = chunkGlobalZ + Chunk.SIZE + 1;
        int chunkMaxWorldY = chunkGlobalY + Chunk.SIZE - 1;

        for (int treeX = minTreeX; treeX <= maxTreeX; treeX++) {
            for (int treeZ = minTreeZ; treeZ <= maxTreeZ; treeZ++) {
                int surfaceY = getSurfaceHeight(treeX, treeZ);
                if (surfaceY <= config.waterLevel()) {
                    continue;
                }
                if (!shouldPlace(treeNoise.get().GetNoise(treeX, treeZ), treeX, treeZ)) {
                    continue;
                }

                int trunkBaseY = surfaceY + 1;
                int canopyTopY = trunkBaseY + 5;
                if (trunkBaseY > chunkMaxWorldY || canopyTopY < chunkGlobalY) {
                    continue;
                }

                placeTreeIntoChunk(blocks, chunkGlobalX, chunkGlobalY, chunkGlobalZ, treeX, treeZ, trunkBaseY);
            }
        }
    }

    private void placeTreeIntoChunk(
            short[] blocks,
            int chunkGlobalX,
            int chunkGlobalY,
            int chunkGlobalZ,
            int treeX,
            int treeZ,
            int trunkBaseY
    ) {
        for (int offsetY = 0; offsetY < 4; offsetY++) {
            writeTreeBlockIfInChunk(
                    blocks,
                    chunkGlobalX,
                    chunkGlobalY,
                    chunkGlobalZ,
                    treeX,
                    trunkBaseY + offsetY,
                    treeZ,
                    Blocks.WOOD_LOG.getId()
            );
        }

        for (int offsetY = 0; offsetY <= 2; offsetY++) {
            int worldY = trunkBaseY + 3 + offsetY;
            for (int offsetZ = -2; offsetZ <= 2; offsetZ++) {
                for (int offsetX = -2; offsetX <= 2; offsetX++) {
                    int distance = (offsetX * offsetX) + (offsetY * offsetY) + (offsetZ * offsetZ);
                    if (distance > 5) {
                        continue;
                    }

                    writeTreeBlockIfInChunk(
                            blocks,
                            chunkGlobalX,
                            chunkGlobalY,
                            chunkGlobalZ,
                            treeX + offsetX,
                            worldY,
                            treeZ + offsetZ,
                            Blocks.LEAVES.getId()
                    );
                }
            }
        }
    }

    private void writeTreeBlockIfInChunk(
            short[] blocks,
            int chunkGlobalX,
            int chunkGlobalY,
            int chunkGlobalZ,
            int worldX,
            int worldY,
            int worldZ,
            short blockId
    ) {
        int localX = worldX - chunkGlobalX;
        int localY = worldY - chunkGlobalY;
        int localZ = worldZ - chunkGlobalZ;
        if (localX < 0 || localY < 0 || localZ < 0
                || localX >= Chunk.SIZE || localY >= Chunk.SIZE || localZ >= Chunk.SIZE) {
            return;
        }

        int blockIndex = localX + (localZ * Chunk.SIZE) + (localY * Chunk.SIZE * Chunk.SIZE);
        if (blocks[blockIndex] == Blocks.AIR.getId()) {
            blocks[blockIndex] = blockId;
        }
    }

    private boolean shouldPlace(float noiseValue, int globalX, int globalZ) {
        float adjusted = noiseValue * 0.2f + 0.2f;
        adjusted = (float) Math.pow(adjusted, config.treeSteepness());
        float random = randomValueBasedOnBlock(globalX, globalZ) * config.treeRarity();
        return adjusted > random;
    }

    private float randomValueBasedOnBlock(int x, int z) {
        return hash2D(x, z);
    }

    private float hash2D(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >> 13)) * 1274126177;
        return ((h ^ (h >> 16)) & 0x7fffffff) / (float) Integer.MAX_VALUE;
    }

    private FastNoiseLite createNoise(int seed) {
        FastNoiseLite fastNoise = new FastNoiseLite();
        fastNoise.SetSeed(seed);
        return fastNoise;
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
