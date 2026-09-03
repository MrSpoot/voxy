package org.weaw.game;

import org.joml.Vector3i;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.game.utils.Blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChunkMesherTest {
    @BeforeAll
    static void initializeBlocks() {
        BlockRegistry.initialize();
    }

    @Test
    void singleOpaqueBlockProducesSixFacesInLegacyAndGreedyModes() {
        ChunkMesher.MeshingMode previousMode = ChunkMesher.getMeshingMode();
        boolean previousAmbientOcclusion = ChunkMesher.isAmbientOcclusionEnabled();
        boolean previousTransparentChunks = ChunkMesher.isTransparentChunksEnabled();
        int previousStoneTextureIndex = Blocks.STONE.getTextureIndex();

        try {
            ChunkMesher.setAmbientOcclusionEnabled(false);
            ChunkMesher.setTransparentChunksEnabled(true);
            Blocks.STONE.setTextureIndex(0);

            assertSingleBlockFaceCount(ChunkMesher.MeshingMode.LEGACY);
            assertSingleBlockFaceCount(ChunkMesher.MeshingMode.GREEDY);
        } finally {
            ChunkMesher.setMeshingMode(previousMode);
            ChunkMesher.setAmbientOcclusionEnabled(previousAmbientOcclusion);
            ChunkMesher.setTransparentChunksEnabled(previousTransparentChunks);
            Blocks.STONE.setTextureIndex(previousStoneTextureIndex);
        }
    }

    private static void assertSingleBlockFaceCount(ChunkMesher.MeshingMode mode) {
        ChunkMesher.setMeshingMode(mode);
        Chunk chunk = new Chunk(new Vector3i(0, 0, 0));
        chunk.setBlock(1, 1, 1, Blocks.STONE);
        WorldBlockProvider provider = (worldX, worldY, worldZ) -> {
            if (worldX >= 0 && worldX < Chunk.SIZE
                    && worldY >= 0 && worldY < Chunk.SIZE
                    && worldZ >= 0 && worldZ < Chunk.SIZE) {
                return chunk.getBlock(worldX, worldY, worldZ);
            }
            return Blocks.AIR.getId();
        };

        ChunkMeshData meshData = ChunkMesher.buildMeshData(chunk, provider);

        assertEquals(6, meshData.opaque().faceCount(), mode + " should expose each side of one block");
        assertEquals(0, meshData.cutout().faceCount());
        assertEquals(0, meshData.transparent().faceCount());
    }
}
