package org.weaw.game;

import org.joml.Vector3i;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.weaw.game.generation.WorldGenerator;
import org.weaw.game.generation.GenerationConfig;
import org.weaw.game.generation.NoiseWorldGenerator;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.game.utils.Blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldTest {
    @BeforeAll
    static void initializeBlocks() {
        BlockRegistry.initialize();
    }

    @Test
    void trySetBlockAtWorldUpdatesLoadedChunkAndMarksCenterChunkDirty() {
        try (World world = new World(new FlatGenerator(BlockRegistry.getRuntimeId(Blocks.AIR)), new WorldSettings(2))) {
            publishChunk(world, new ChunkPosition(0, 0, 0));

            assertTrue(world.trySetBlockAtWorld(3, 4, 5, Blocks.STONE));

            assertEquals(BlockRegistry.getRuntimeId(Blocks.STONE), world.getBlockAtWorld(3, 4, 5));
            assertEquals(1, world.getPendingRemeshCount());
        }
    }

    @Test
    void trySetBlockAtWorldMaterializesAnUnloadedChunkBeforeEditingIt() {
        try (World world = new World(new FlatGenerator(BlockRegistry.getRuntimeId(Blocks.AIR)), new WorldSettings(2))) {
            assertTrue(world.trySetBlockAtWorld(3, 4, 5, Blocks.STONE));
            assertTrue(world.containsChunk(0, 0, 0));
            assertEquals(BlockRegistry.getRuntimeId(Blocks.STONE), world.getBlockAtWorld(3, 4, 5));
            assertEquals(1, world.getPendingRemeshCount());
        }
    }

    @Test
    void trySetBlockAtWorldRejectsChunksOutsideTheConfiguredWorldHeight() {
        try (World world = new World(new FlatGenerator(BlockRegistry.getRuntimeId(Blocks.AIR)), new WorldSettings(2))) {
            int firstWorldYAboveRange = (world.getSettings().getHeightRange().maxChunkY() + 1) * Chunk.SIZE;

            assertFalse(world.trySetBlockAtWorld(0, firstWorldYAboveRange, 0, Blocks.STONE));
            assertEquals(0, world.getPendingRemeshCount());
        }
    }

    @Test
    void changingBlockOnChunkCornerMarksAllAdjacentBoundaryChunksDirty() {
        try (World world = new World(new FlatGenerator(BlockRegistry.getRuntimeId(Blocks.AIR)), new WorldSettings(2))) {
            for (int chunkX = -1; chunkX <= 0; chunkX++) {
                for (int chunkY = -1; chunkY <= 0; chunkY++) {
                    for (int chunkZ = -1; chunkZ <= 0; chunkZ++) {
                        publishChunk(world, new ChunkPosition(chunkX, chunkY, chunkZ));
                    }
                }
            }

            assertTrue(world.trySetBlockAtWorld(0, 0, 0, Blocks.STONE));

            assertEquals(8, world.getPendingRemeshCount());
        }
    }

    @Test
    void getBlockAtWorldFallsBackToGeneratorWhenChunkIsNotLoaded() {
        try (World world = new World(new FlatGenerator(BlockRegistry.getRuntimeId(Blocks.SAND)), new WorldSettings(2))) {
            assertEquals(BlockRegistry.getRuntimeId(Blocks.SAND), world.getBlockAtWorld(100, 5, 100));
        }
    }

    @Test
    void bulkSamplingOverlaysLoadedEditsOnProceduralBlocks() {
        try (World world = new World(new FlatGenerator(BlockRegistry.getRuntimeId(Blocks.SAND)), new WorldSettings(2))) {
            publishChunk(world, new ChunkPosition(0, 0, 0));
            world.getChunkManager().setBlockAtWorld(3, 4, 5, Blocks.STONE);
            short[] region = new short[6 * 6 * 6];

            world.fillBlockRegion(-1, 0, 0, 6, 6, 6, region);

            assertEquals(BlockRegistry.getRuntimeId(Blocks.SAND), region[0]);
            assertEquals(BlockRegistry.getRuntimeId(Blocks.STONE), region[4 + 5 * 6 + 4 * 36]);
        }
    }

    @Test
    void blockEditQueuesAPriorityLightingUpdate() {
        try (World world = new World(
                new FlatGenerator(BlockRegistry.getRuntimeId(Blocks.AIR)),
                new WorldSettings(2, new WorldHeightRange(0, 0), WorldMemoryBudget.balanced(), false)
        )) {
            publishChunk(world, new ChunkPosition(0, 0, 0));

            world.setBlockAtWorld(4, 5, 6, Blocks.STONE);

            assertEquals(1, world.getPendingPriorityLightingUpdateCount());
        }
    }

    @Test
    void sessionEditSurvivesChunkUnloadAndRegeneration() {
        ChunkPosition position = new ChunkPosition(0, 0, 0);
        try (World world = new World(new FlatGenerator(BlockRegistry.getRuntimeId(Blocks.AIR)), new WorldSettings(2))) {
            assertTrue(world.trySetBlockAtWorld(3, 4, 5, Blocks.STONE));
            world.getChunkManager().unloadChunk(position);

            assertTrue(world.trySetBlockAtWorld(6, 4, 5, Blocks.SAND));

            assertEquals(BlockRegistry.getRuntimeId(Blocks.STONE), world.getBlockAtWorld(3, 4, 5));
            assertEquals(BlockRegistry.getRuntimeId(Blocks.SAND), world.getBlockAtWorld(6, 4, 5));
        }
    }

    @Test
    void interactionLightingRunsBeforeOlderGenerationLighting() {
        try (World world = new World(
                new FlatGenerator(BlockRegistry.getRuntimeId(Blocks.AIR)),
                new WorldSettings(2, new WorldHeightRange(0, 0), WorldMemoryBudget.balanced(), false)
        )) {
            world.setUnloadsEnabled(false);
            world.setRemeshEnabled(false);
            for (int chunkX = 10; chunkX < 15; chunkX++) {
                publishChunk(world, new ChunkPosition(chunkX, 0, 0));
            }
            publishChunk(world, new ChunkPosition(0, 0, 0));
            world.setBlockAtWorld(1, 1, 1, Blocks.STONE);

            world.update(new org.joml.Vector3f(0.0f, 0.0f, 0.0f));

            assertEquals(15, ChunkLighting.getSky(world.getPackedLightAtWorld(2, 1, 1)));
            assertEquals(0, world.getPendingPriorityLightingUpdateCount());
        }
    }

    @Test
    void proceduralSpawnIsDeterministicDryAndWalkable() {
        try (World first = new World(new NoiseWorldGenerator(GenerationConfig.defaults()), new WorldSettings(2));
             World second = new World(new NoiseWorldGenerator(GenerationConfig.defaults()), new WorldSettings(2))) {
            Vector3f spawn = first.findSpawnPosition();

            assertEquals(spawn, second.findSpawnPosition());
            int worldX = (int) Math.floor(spawn.x);
            int worldY = (int) Math.floor(spawn.y);
            int worldZ = (int) Math.floor(spawn.z);
            short ground = first.getBlockAtWorld(worldX, worldY - 1, worldZ);
            assertNotEquals(BlockRegistry.getRuntimeId(Blocks.AIR), ground);
            assertNotEquals(BlockRegistry.getRuntimeId(Blocks.WATER), ground);
            assertEquals(BlockRegistry.getRuntimeId(Blocks.AIR), first.getBlockAtWorld(worldX, worldY, worldZ));
            assertEquals(BlockRegistry.getRuntimeId(Blocks.AIR), first.getBlockAtWorld(worldX, worldY + 1, worldZ));
        }
    }

    private static void publishChunk(World world, ChunkPosition position) {
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
