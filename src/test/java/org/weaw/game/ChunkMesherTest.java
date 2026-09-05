package org.weaw.game;

import org.joml.Vector3i;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.game.utils.BlockDefinition;
import org.weaw.game.utils.Blocks;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void proceduralSolidNeighborHidesTheBoundaryFaceWithoutAResidentChunk() {
        ChunkMesher.MeshingMode previousMode = ChunkMesher.getMeshingMode();
        boolean previousAmbientOcclusion = ChunkMesher.isAmbientOcclusionEnabled();
        int previousStoneTextureIndex = Blocks.STONE.getTextureIndex();

        try {
            ChunkMesher.setMeshingMode(ChunkMesher.MeshingMode.GREEDY);
            ChunkMesher.setAmbientOcclusionEnabled(false);
            Blocks.STONE.setTextureIndex(0);
            Chunk chunk = new Chunk(new Vector3i(0, 0, 0));
            chunk.setBlock(0, 1, 1, Blocks.STONE);
            WorldBlockProvider provider = (worldX, worldY, worldZ) -> {
                if (worldX >= 0 && worldX < Chunk.SIZE
                        && worldY >= 0 && worldY < Chunk.SIZE
                        && worldZ >= 0 && worldZ < Chunk.SIZE) {
                    return chunk.getBlock(worldX, worldY, worldZ);
                }
                return worldX < 0 ? Blocks.STONE.getId() : Blocks.AIR.getId();
            };

            ChunkMeshData meshData = ChunkMesher.buildMeshData(chunk, provider);

            assertEquals(5, meshData.opaque().faceCount());
        } finally {
            ChunkMesher.setMeshingMode(previousMode);
            ChunkMesher.setAmbientOcclusionEnabled(previousAmbientOcclusion);
            Blocks.STONE.setTextureIndex(previousStoneTextureIndex);
        }
    }

    @Test
    void legacyAndGreedyProduceTheSameVisibleOpaqueSurface() {
        ChunkMesher.MeshingMode previousMode = ChunkMesher.getMeshingMode();
        boolean previousAmbientOcclusion = ChunkMesher.isAmbientOcclusionEnabled();
        int previousStoneTextureIndex = Blocks.STONE.getTextureIndex();
        int previousLeavesTextureIndex = Blocks.LEAVES.getTextureIndex();
        int previousGlassTextureIndex = Blocks.GLASS.getTextureIndex();
        try {
            ChunkMesher.setAmbientOcclusionEnabled(false);
            Blocks.STONE.setTextureIndex(0);
            Blocks.LEAVES.setTextureIndex(1);
            Blocks.GLASS.setTextureIndex(2);
            Chunk chunk = new Chunk(new Vector3i(0, 0, 0));
            chunk.setBlock(0, 0, 0, Blocks.STONE);
            chunk.setBlock(1, 0, 0, Blocks.STONE);
            chunk.setBlock(1, 1, 0, Blocks.STONE);
            chunk.setBlock(1, 1, 1, Blocks.STONE);
            chunk.setBlock(5, 5, 5, Blocks.LEAVES);
            chunk.setBlock(5, 6, 5, Blocks.LEAVES);
            chunk.setBlock(8, 8, 8, Blocks.GLASS);
            chunk.setBlock(9, 8, 8, Blocks.GLASS);
            WorldBlockProvider provider = (worldX, worldY, worldZ) -> worldX < 0
                    ? Blocks.STONE.getId()
                    : Blocks.AIR.getId();

            ChunkMesher.setMeshingMode(ChunkMesher.MeshingMode.LEGACY);
            ChunkMeshData legacy = ChunkMesher.buildMeshData(chunk, provider);
            ChunkMesher.setMeshingMode(ChunkMesher.MeshingMode.GREEDY);
            ChunkMeshData greedy = ChunkMesher.buildMeshData(chunk, provider);

            assertEquals(expandSurface(legacy.opaque()), expandSurface(greedy.opaque()));
            assertEquals(expandSurface(legacy.cutout()), expandSurface(greedy.cutout()));
            assertEquals(expandSurface(legacy.transparent()), expandSurface(greedy.transparent()));
        } finally {
            ChunkMesher.setMeshingMode(previousMode);
            ChunkMesher.setAmbientOcclusionEnabled(previousAmbientOcclusion);
            Blocks.STONE.setTextureIndex(previousStoneTextureIndex);
            Blocks.LEAVES.setTextureIndex(previousLeavesTextureIndex);
            Blocks.GLASS.setTextureIndex(previousGlassTextureIndex);
        }
    }

    @Test
    void greedyMesherKeepsWaterFacesPerBlockButStillMergesGlass() {
        ChunkMesher.MeshingMode previousMode = ChunkMesher.getMeshingMode();
        boolean previousTransparentChunks = ChunkMesher.isTransparentChunksEnabled();
        int previousWaterTextureIndex = Blocks.WATER.getTextureIndex();
        int previousGlassTextureIndex = Blocks.GLASS.getTextureIndex();

        try {
            ChunkMesher.setMeshingMode(ChunkMesher.MeshingMode.GREEDY);
            ChunkMesher.setTransparentChunksEnabled(true);
            Blocks.WATER.setTextureIndex(7);
            Blocks.GLASS.setTextureIndex(8);

            ChunkMeshData.LayerMeshData waterMesh = meshAdjacentBlocks(Blocks.WATER).transparent();
            assertEquals(10, waterMesh.faceCount());
            assertEquals(6, countWaterSurfaceEdges(waterMesh));
            assertEquals(6, meshAdjacentBlocks(Blocks.GLASS).transparent().faceCount());
        } finally {
            ChunkMesher.setMeshingMode(previousMode);
            ChunkMesher.setTransparentChunksEnabled(previousTransparentChunks);
            Blocks.WATER.setTextureIndex(previousWaterTextureIndex);
            Blocks.GLASS.setTextureIndex(previousGlassTextureIndex);
        }
    }

    @Test
    void profiledMeshingHonorsCancellation() {
        Chunk chunk = new Chunk(new Vector3i());
        assertThrows(CancellationException.class, () -> ChunkMesher.buildMeshDataProfiled(
                chunk,
                (x, y, z) -> Blocks.AIR.getId(),
                () -> true
        ));
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

    private static ChunkMeshData meshAdjacentBlocks(BlockDefinition block) {
        Chunk chunk = new Chunk(new Vector3i(0, 0, 0));
        chunk.setBlock(4, 4, 4, block);
        chunk.setBlock(5, 4, 4, block);
        WorldBlockProvider provider = (worldX, worldY, worldZ) -> {
            if (worldX >= 0 && worldX < Chunk.SIZE
                    && worldY >= 0 && worldY < Chunk.SIZE
                    && worldZ >= 0 && worldZ < Chunk.SIZE) {
                return chunk.getBlock(worldX, worldY, worldZ);
            }
            return Blocks.AIR.getId();
        };
        return ChunkMesher.buildMeshData(chunk, provider);
    }

    private static int countWaterSurfaceEdges(ChunkMeshData.LayerMeshData layer) {
        int count = 0;
        for (int face = 0; face < layer.faceCount(); face++) {
            if ((layer.faceData()[face * 2 + 1] & (1 << 16)) != 0) {
                count++;
            }
        }
        return count;
    }

    private static Set<String> expandSurface(ChunkMeshData.LayerMeshData layer) {
        Set<String> surface = new HashSet<>();
        for (int faceIndex = 0; faceIndex < layer.faceCount(); faceIndex++) {
            int encoded = layer.faceData()[faceIndex * 2];
            int x = encoded & 0x1F;
            int y = (encoded >>> 5) & 0x1F;
            int z = (encoded >>> 10) & 0x1F;
            int direction = (encoded >>> 15) & 0x07;
            int width = ((encoded >>> 18) & 0x1F) + 1;
            int height = ((encoded >>> 23) & 0x1F) + 1;
            for (int v = 0; v < height; v++) {
                for (int u = 0; u < width; u++) {
                    int faceX = x;
                    int faceY = y;
                    int faceZ = z;
                    if (direction <= 1) {
                        faceY += v;
                        faceZ += u;
                    } else if (direction <= 3) {
                        faceX += u;
                        faceZ += v;
                    } else {
                        faceX += u;
                        faceY += v;
                    }
                    surface.add(direction + ":" + faceX + ":" + faceY + ":" + faceZ);
                }
            }
        }
        return surface;
    }
}
