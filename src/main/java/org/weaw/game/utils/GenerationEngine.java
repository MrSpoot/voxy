package org.weaw.game.utils;

import org.weaw.game.Chunk;
import org.weaw.game.generation.GenerationConfig;
import org.weaw.game.generation.NoiseWorldGenerator;

@Deprecated(forRemoval = true)
public final class GenerationEngine {
    private static final NoiseWorldGenerator DEFAULT_GENERATOR = new NoiseWorldGenerator(GenerationConfig.defaults());

    private GenerationEngine() {
    }

    public static void generateChunkData(Chunk chunk) {
        DEFAULT_GENERATOR.generateChunkData(chunk);
    }

    public static short getBlockAtWorld(int worldX, int worldY, int worldZ) {
        return DEFAULT_GENERATOR.getBlockAtWorld(worldX, worldY, worldZ);
    }

    public static int getSurfaceHeight(int worldX, int worldZ) {
        return DEFAULT_GENERATOR.getSurfaceHeight(worldX, worldZ);
    }

    public static float getFractalNoise(FastNoiseLite noise, float x, float y, int octaves, float lacunarity, float gain) {
        return NoiseWorldGenerator.getFractalNoise(noise, x, y, octaves, lacunarity, gain);
    }
}
