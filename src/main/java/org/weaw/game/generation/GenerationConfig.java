package org.weaw.game.generation;

public record GenerationConfig(
        long seed,
        float amplitude,
        int baseHeight,
        int waterLevel,
        float terrainFrequency,
        int terrainOctaves,
        float terrainLacunarity,
        float terrainGain,
        int treeSeedOffset,
        float treeRarity,
        float treeSteepness
) {
    public static GenerationConfig defaults() {
        return new GenerationConfig(
                1052002L,
                25.0f,
                0,
                -10,
                0.3f,
                4,
                2.0f,
                0.5f,
                999,
                0.5f,
                2.0f
        );
    }

    public GenerationConfig withSeed(long seed) {
        return new GenerationConfig(
                seed,
                amplitude,
                baseHeight,
                waterLevel,
                terrainFrequency,
                terrainOctaves,
                terrainLacunarity,
                terrainGain,
                treeSeedOffset,
                treeRarity,
                treeSteepness
        );
    }
}
