package org.weaw.game.generation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.game.utils.Blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoiseWorldGeneratorTest {
    @BeforeAll
    static void initializeBlocks() {
        BlockRegistry.initialize();
    }

    @Test
    void classifiesDeepSurfaceAndHighChunksConservatively() {
        NoiseWorldGenerator generator = new NoiseWorldGenerator(GenerationConfig.defaults());

        ChunkGenerationHint deep = generator.classifyChunk(new ChunkPosition(0, -2, 0));
        ChunkGenerationHint topsoilBoundary = generator.classifyChunk(new ChunkPosition(0, -1, 0));
        ChunkGenerationHint surface = generator.classifyChunk(new ChunkPosition(0, 0, 0));
        ChunkGenerationHint high = generator.classifyChunk(new ChunkPosition(0, 1, 0));

        assertEquals(ChunkGenerationHint.Kind.UNIFORM, deep.kind());
        assertEquals(Blocks.STONE.getId(), deep.uniformBlockId());
        assertEquals(ChunkGenerationHint.Kind.MATERIALIZED, topsoilBoundary.kind());
        assertEquals(ChunkGenerationHint.Kind.MATERIALIZED, surface.kind());
        assertEquals(ChunkGenerationHint.Kind.EMPTY, high.kind());
    }

    @Test
    void reusesAndEvictsColumnClassificationBounds() {
        NoiseWorldGenerator generator = new NoiseWorldGenerator(GenerationConfig.defaults());

        generator.classifyChunk(new ChunkPosition(0, 0, 0));
        generator.classifyChunk(new ChunkPosition(0, 0, 0));
        generator.classifyChunk(new ChunkPosition(0, 0, 0));

        ChunkClassificationCacheStats populated = generator.getChunkClassificationCacheStats();
        assertEquals(1, populated.size());
        assertEquals(1L, populated.misses());
        assertEquals(2L, populated.hits());

        generator.retainChunkClassificationsAround(100, 100, 1);
        assertEquals(0, generator.getChunkClassificationCacheStats().size());
    }

    @Test
    void defaultWorldMaterializesLessThanThirtyPercentOfTheLegacyCylinder() {
        NoiseWorldGenerator generator = new NoiseWorldGenerator(GenerationConfig.defaults());
        int candidates = 0;
        int materialized = 0;
        int empty = 0;
        int uniform = 0;

        for (int z = -16; z <= 16; z++) {
            for (int x = -16; x <= 16; x++) {
                if (x * x + z * z > 16 * 16) {
                    continue;
                }
                for (int y = -4; y <= 3; y++) {
                    candidates++;
                    ChunkGenerationHint hint = generator.classifyChunk(new ChunkPosition(x, y, z));
                    switch (hint.kind()) {
                        case EMPTY -> empty++;
                        case UNIFORM -> uniform++;
                        case MATERIALIZED -> materialized++;
                    }
                }
            }
        }

        assertEquals(6_376, candidates);
        assertTrue(empty > 0);
        assertTrue(uniform > 0);
        assertTrue(materialized <= candidates * 0.30, "sparse streaming should remove at least 70% of candidates");
    }
}
