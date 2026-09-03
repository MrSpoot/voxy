package org.weaw.game;

import org.joml.Vector3i;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.weaw.game.generation.WorldGenerator;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.game.utils.Blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldTest {
    @BeforeAll
    static void initializeBlocks() {
        BlockRegistry.initialize();
    }

    @Test
    void trySetBlockAtWorldUpdatesLoadedChunkAndMarksCenterChunkDirty() {
        try (World world = new World(new FlatGenerator(Blocks.AIR.getId()), new WorldSettings(2))) {
            publishChunk(world, new ChunkManager.ChunkPosition(0, 0, 0));

            assertTrue(world.trySetBlockAtWorld(3, 4, 5, Blocks.STONE));

            assertEquals(Blocks.STONE.getId(), world.getBlockAtWorld(3, 4, 5));
            assertEquals(1, world.getPendingRemeshCount());
        }
    }

    @Test
    void trySetBlockAtWorldMaterializesAnUnloadedChunkBeforeEditingIt() {
        try (World world = new World(new FlatGenerator(Blocks.AIR.getId()), new WorldSettings(2))) {
            assertTrue(world.trySetBlockAtWorld(3, 4, 5, Blocks.STONE));
            assertTrue(world.containsChunk(0, 0, 0));
            assertEquals(Blocks.STONE.getId(), world.getBlockAtWorld(3, 4, 5));
            assertEquals(1, world.getPendingRemeshCount());
        }
    }

    @Test
    void trySetBlockAtWorldRejectsChunksOutsideTheConfiguredWorldHeight() {
        try (World world = new World(new FlatGenerator(Blocks.AIR.getId()), new WorldSettings(2))) {
            int firstWorldYAboveRange = (world.getSettings().getHeightRange().maxChunkY() + 1) * Chunk.SIZE;

            assertFalse(world.trySetBlockAtWorld(0, firstWorldYAboveRange, 0, Blocks.STONE));
            assertEquals(0, world.getPendingRemeshCount());
        }
    }

    @Test
    void changingBlockOnChunkCornerMarksAllAdjacentBoundaryChunksDirty() {
        try (World world = new World(new FlatGenerator(Blocks.AIR.getId()), new WorldSettings(2))) {
            publishChunk(world, new ChunkManager.ChunkPosition(0, 0, 0));

            assertTrue(world.trySetBlockAtWorld(0, 0, 0, Blocks.STONE));

            assertEquals(8, world.getPendingRemeshCount());
        }
    }

    @Test
    void getBlockAtWorldFallsBackToGeneratorWhenChunkIsNotLoaded() {
        try (World world = new World(new FlatGenerator(Blocks.SAND.getId()), new WorldSettings(2))) {
            assertEquals(Blocks.SAND.getId(), world.getBlockAtWorld(100, 5, 100));
        }
    }

    private static void publishChunk(World world, ChunkManager.ChunkPosition position) {
        Chunk chunk = new Chunk(new Vector3i(position.x(), position.y(), position.z()));
        world.getChunkManager().publishBuiltChunk(chunk, emptyMeshData());
    }

    private static ChunkMeshData emptyMeshData() {
        ChunkMeshData.LayerMeshData emptyLayer = new ChunkMeshData.LayerMeshData(new int[0], 0);
        return new ChunkMeshData(emptyLayer, emptyLayer, emptyLayer);
    }

    private record FlatGenerator(short blockId) implements WorldGenerator {
        @Override
        public void generateChunkData(Chunk chunk) {
            short[] blocks = new short[Chunk.TOTAL_BLOCKS];
            java.util.Arrays.fill(blocks, blockId);
            chunk.setAllBlocks(blocks);
        }

        @Override
        public short getBlockAtWorld(int worldX, int worldY, int worldZ) {
            return blockId;
        }

        @Override
        public int getSurfaceHeight(int worldX, int worldZ) {
            return 0;
        }
    }
}
