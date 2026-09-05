package org.weaw.game;

import org.joml.Vector3i;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.game.utils.Blocks;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldLightingSystemTest {
    private static final WorldHeightRange SINGLE_CHUNK_HEIGHT = new WorldHeightRange(0, 0);

    @BeforeAll
    static void initializeBlocks() {
        BlockRegistry.initialize();
    }

    @Test
    void openColumnReceivesFullSkyLightAndStaysCompact() {
        ChunkManager manager = managerWithAirChunk(0, 0, 0);
        WorldLightingSystem.WorldLightingUpdateResult result = rebuild(manager, SINGLE_CHUNK_HEIGHT);

        Chunk chunk = manager.getChunk(0, 0, 0);
        assertEquals(15, chunk.getLighting().getSky(4, 0, 7));
        assertEquals(15, chunk.getLighting().getSky(4, Chunk.SIZE - 1, 7));
        assertTrue(chunk.getLighting().isCompact());
        assertEquals(Set.of(new ChunkPosition(0, 0, 0)), result.changedPositions());
    }

    @Test
    void opaqueRoofRemovesSkyLightBelowIt() {
        ChunkManager manager = managerWithAirChunk(0, 0, 0);
        fillLayer(manager.getChunk(0, 0, 0), 20, Blocks.STONE);

        rebuild(manager, SINGLE_CHUNK_HEIGHT);

        assertEquals(15, sky(manager, 12, 21, 12));
        assertEquals(0, sky(manager, 12, 19, 12));
    }

    @Test
    void roofOpeningSpreadsSkyLightAndClosingItRemovesStaleLight() {
        ChunkManager manager = managerWithAirChunk(0, 0, 0);
        Chunk chunk = manager.getChunk(0, 0, 0);
        fillLayer(chunk, 20, Blocks.STONE);
        chunk.setBlock(16, 20, 16, Blocks.AIR);

        rebuild(manager, SINGLE_CHUNK_HEIGHT);

        assertEquals(15, sky(manager, 16, 19, 16));
        assertEquals(14, sky(manager, 17, 19, 16));
        assertEquals(13, sky(manager, 18, 19, 16));

        chunk.setBlock(16, 20, 16, Blocks.STONE);
        rebuild(manager, SINGLE_CHUNK_HEIGHT);

        assertEquals(0, sky(manager, 16, 19, 16));
        assertEquals(0, sky(manager, 17, 19, 16));
    }

    @Test
    void daylightFallsByOneLevelPerBlockInsideAHorizontalTunnel() {
        ChunkManager manager = managerWithAirChunk(0, 0, 0);
        Chunk chunk = manager.getChunk(0, 0, 0);
        encloseTunnel(chunk, 5, 25, 10, 16);

        rebuild(manager, SINGLE_CHUNK_HEIGHT);

        assertEquals(15, sky(manager, 4, 10, 16));
        for (int distance = 1; distance <= 15; distance++) {
            assertEquals(Math.max(0, 15 - distance), sky(manager, 4 + distance, 10, 16));
        }
        assertEquals(0, sky(manager, 20, 10, 16));
    }

    @Test
    void closingAndReopeningATunnelEntranceRemovesAndRestoresDaylight() {
        ChunkManager manager = managerWithAirChunk(0, 0, 0);
        Chunk chunk = manager.getChunk(0, 0, 0);
        encloseTunnel(chunk, 5, 25, 10, 16);
        WorldLightingSystem system = new WorldLightingSystem(
                manager.getBlockCatalog(), manager::getBlockAtWorld, SINGLE_CHUNK_HEIGHT);
        system.initializeChunk(chunk);

        assertEquals(13, sky(manager, 6, 10, 16));

        chunk.setBlock(5, 10, 16, Blocks.STONE);
        system.enqueueBlockChange(5, 10, 16);
        system.processFrame(manager);
        assertEquals(0, sky(manager, 6, 10, 16));

        chunk.setBlock(5, 10, 16, Blocks.AIR);
        system.enqueueBlockChange(5, 10, 16);
        system.processFrame(manager);
        assertEquals(14, sky(manager, 5, 10, 16));
        assertEquals(13, sky(manager, 6, 10, 16));
    }

    @Test
    void progressiveDaylightCrossesChunkBoundariesWithoutASeam() {
        ChunkManager manager = managerWithAirChunk(0, 0, 0);
        addAirChunk(manager, 1, 0, 0);
        encloseTunnelAcrossChunks(manager, 29, 45, 10, 16);

        rebuild(manager, SINGLE_CHUNK_HEIGHT);

        assertEquals(15, sky(manager, 28, 10, 16));
        assertEquals(14, sky(manager, 29, 10, 16));
        assertEquals(12, sky(manager, 31, 10, 16));
        assertEquals(11, sky(manager, 32, 10, 16));
        assertEquals(0, sky(manager, 43, 10, 16));
    }

    @Test
    void skyLightCrossesAChunkBoundary() {
        ChunkManager manager = managerWithAirChunk(0, 0, 0);
        addAirChunk(manager, 1, 0, 0);
        fillLayer(manager.getChunk(0, 0, 0), 20, Blocks.STONE);
        fillLayer(manager.getChunk(1, 0, 0), 20, Blocks.STONE);
        manager.getChunk(0, 0, 0).setBlock(31, 20, 16, Blocks.AIR);

        rebuild(manager, SINGLE_CHUNK_HEIGHT);

        assertEquals(15, sky(manager, 31, 19, 16));
        assertEquals(14, sky(manager, 32, 19, 16));
        assertEquals(13, sky(manager, 33, 19, 16));
    }

    @Test
    void incrementalBoundarySeedingSkipsEqualLightAndPropagatesOnlyNeededCells() {
        ChunkManager manager = managerWithAirChunk(0, 0, 0);
        addAirChunk(manager, 1, 0, 0);
        fillLayer(manager.getChunk(0, 0, 0), 20, Blocks.STONE);
        fillLayer(manager.getChunk(1, 0, 0), 20, Blocks.STONE);
        manager.getChunk(0, 0, 0).setBlock(31, 20, 16, Blocks.AIR);
        WorldLightingSystem system = new WorldLightingSystem(
                manager.getBlockCatalog(), manager::getBlockAtWorld, SINGLE_CHUNK_HEIGHT);
        system.initializeChunk(manager.getChunk(0, 0, 0));
        system.initializeChunk(manager.getChunk(1, 0, 0));

        system.enqueueChunkBoundary(new ChunkPosition(1, 0, 0), manager);
        while (system.hasPendingWork()) {
            system.processFrame(manager);
        }

        assertEquals(14, sky(manager, 32, 19, 16));
        assertEquals(13, sky(manager, 33, 19, 16));
        system.enqueueChunkBoundary(new ChunkPosition(0, 0, 0), manager);
        system.processFrame(manager);
        assertFalse(system.hasPendingWork());
    }

    @Test
    void skyLightCrossesTheFullConfiguredWorldHeight() {
        ChunkManager manager = managerWithAirChunk(0, 0, 0);
        addAirChunk(manager, 0, 1, 0);
        WorldHeightRange heightRange = new WorldHeightRange(0, 1);

        rebuild(manager, heightRange);

        assertEquals(15, sky(manager, 10, 63, 10));
        assertEquals(15, sky(manager, 10, 0, 10));
    }

    @Test
    void unloadedGeneratedRoofStillOccludesALoadedChunkBelow() {
        ChunkManager manager = managerWithAirChunk(0, 0, 0);
        WorldHeightRange heightRange = new WorldHeightRange(0, 1);
        WorldBlockProvider provider = new WorldBlockProvider() {
            @Override
            public short getBlockAtWorld(int worldX, int worldY, int worldZ) {
                if (worldY == 40) {
                    return Blocks.STONE.getId();
                }
                return manager.getBlockAtWorld(worldX, worldY, worldZ);
            }
        };
        WorldLightingSystem system = new WorldLightingSystem(manager.getBlockCatalog(), provider, heightRange);

        system.rebuildLightingAround(manager, Set.of(new ChunkPosition(0, 0, 0)));

        assertEquals(0, sky(manager, 10, 31, 10));
        assertEquals(0, sky(manager, 10, 0, 10));
    }

    @Test
    void transparentBlocksApplyTheirConfiguredVerticalAttenuation() {
        ChunkManager manager = managerWithAirChunk(0, 0, 0);
        Chunk chunk = manager.getChunk(0, 0, 0);
        fillLayer(chunk, 28, Blocks.GLASS);
        fillLayer(chunk, 27, Blocks.LEAVES);
        fillLayer(chunk, 26, Blocks.WATER);

        rebuild(manager, SINGLE_CHUNK_HEIGHT);

        assertEquals(15, sky(manager, 16, 28, 16));
        assertEquals(12, sky(manager, 16, 27, 16));
        assertEquals(10, sky(manager, 16, 26, 16));
        assertEquals(10, sky(manager, 16, 25, 16));
    }

    @Test
    void leavesAttenuateDirectSkyProgressivelyWhileOpaqueBlocksStopIt() {
        ChunkManager manager = managerWithAirChunk(0, 0, 0);
        Chunk chunk = manager.getChunk(0, 0, 0);
        chunk.setBlock(16, 22, 16, Blocks.LEAVES);
        chunk.setBlock(16, 21, 16, Blocks.LEAVES);
        chunk.setBlock(16, 20, 16, Blocks.LEAVES);

        rebuild(manager, SINGLE_CHUNK_HEIGHT);

        assertEquals(12, chunk.getDirectSkyLight(16, 22, 16));
        assertEquals(9, chunk.getDirectSkyLight(16, 21, 16));
        assertEquals(6, chunk.getDirectSkyLight(16, 19, 16));

        chunk.setBlock(16, 22, 16, Blocks.STONE);
        rebuild(manager, SINGLE_CHUNK_HEIGHT);

        assertEquals(0, chunk.getDirectSkyLight(16, 21, 16));
        assertEquals(0, chunk.getDirectSkyLight(16, 19, 16));
    }

    @Test
    void directSkyOnlyChangesStillInvalidateGpuLighting() {
        ChunkManager manager = managerWithAirChunk(0, 0, 0);
        Chunk chunk = manager.getChunk(0, 0, 0);
        AtomicBoolean addCanopy = new AtomicBoolean();
        WorldBlockProvider provider = new WorldBlockProvider() {
            @Override
            public short getBlockAtWorld(int worldX, int worldY, int worldZ) {
                if (addCanopy.get() && worldX == 16 && worldY == Chunk.SIZE - 1 && worldZ == 16) {
                    return Blocks.LEAVES.getId();
                }
                return manager.getBlockAtWorld(worldX, worldY, worldZ);
            }
        };
        WorldLightingSystem system = new WorldLightingSystem(manager.getBlockCatalog(), provider,
                SINGLE_CHUNK_HEIGHT);
        system.initializeChunk(chunk);
        for (int y = 0; y < Chunk.SIZE; y++) {
            chunk.setLight(16, y, 16, 0, 0, 0, 14);
        }

        addCanopy.set(true);
        system.enqueueBlockChange(16, Chunk.SIZE - 1, 16);
        WorldLightingSystem.WorldLightingUpdateResult result = system.processFrame(manager);

        assertEquals(12, chunk.getDirectSkyLight(16, 0, 16));
        assertTrue(result.changedPositions().contains(new ChunkPosition(0, 0, 0)));
        assertTrue(result.markedChunkCount() > 0);
    }

    @Test
    void blockLightStillIlluminatesAClosedCave() {
        ChunkManager manager = managerWithAirChunk(0, 0, 0);
        Chunk chunk = manager.getChunk(0, 0, 0);
        fillLayer(chunk, 20, Blocks.STONE);
        chunk.setBlock(16, 10, 16, Blocks.RED_LAMP);

        rebuild(manager, SINGLE_CHUNK_HEIGHT);

        short lamp = manager.getPackedLightAtWorld(16, 10, 16);
        short neighbor = manager.getPackedLightAtWorld(17, 10, 16);
        assertEquals(15, ChunkLighting.getRed(lamp));
        assertEquals(14, ChunkLighting.getRed(neighbor));
        assertEquals(0, ChunkLighting.getSky(neighbor));
    }

    @Test
    void lampLightFallsByOneLevelPerBlock() {
        ChunkManager manager = managerWithAirChunk(0, 0, 0);
        Chunk chunk = manager.getChunk(0, 0, 0);
        fillLayer(chunk, 20, Blocks.STONE);
        chunk.setBlock(8, 10, 16, Blocks.WHITE_LAMP);

        rebuild(manager, SINGLE_CHUNK_HEIGHT);

        for (int distance = 0; distance <= 15; distance++) {
            short light = manager.getPackedLightAtWorld(8 + distance, 10, 16);
            int expected = 15 - distance;
            assertEquals(expected, ChunkLighting.getRed(light));
            assertEquals(expected, ChunkLighting.getGreen(light));
            assertEquals(expected, ChunkLighting.getBlue(light));
        }
    }

    @Test
    void unchangedSecondRebuildDoesNotReportGpuChanges() {
        ChunkManager manager = managerWithAirChunk(0, 0, 0);

        WorldLightingSystem.WorldLightingUpdateResult first = rebuild(manager, SINGLE_CHUNK_HEIGHT);
        WorldLightingSystem.WorldLightingUpdateResult second = rebuild(manager, SINGLE_CHUNK_HEIGHT);

        assertFalse(first.changedPositions().isEmpty());
        assertTrue(second.changedPositions().isEmpty());
    }

    @Test
    void incrementalEditClosesAndReopensASkylightShaft() {
        ChunkManager manager = managerWithAirChunk(0, 0, 0);
        Chunk chunk = manager.getChunk(0, 0, 0);
        fillLayer(chunk, 20, Blocks.STONE);
        chunk.setBlock(16, 20, 16, Blocks.AIR);
        WorldLightingSystem system = new WorldLightingSystem(manager.getBlockCatalog(), manager::getBlockAtWorld,
                SINGLE_CHUNK_HEIGHT);
        system.initializeChunk(chunk);
        assertEquals(15, sky(manager, 16, 19, 16));

        chunk.setBlock(16, 20, 16, Blocks.STONE);
        system.enqueueBlockChange(16, 20, 16);
        system.processFrame(manager);
        assertEquals(0, sky(manager, 16, 19, 16));

        chunk.setBlock(16, 20, 16, Blocks.AIR);
        system.enqueueBlockChange(16, 20, 16);
        system.processFrame(manager);
        assertEquals(15, sky(manager, 16, 19, 16));
        assertEquals(14, sky(manager, 17, 19, 16));
    }

    @Test
    void incrementalEditRemovesColoredBlockLight() {
        ChunkManager manager = managerWithAirChunk(0, 0, 0);
        Chunk chunk = manager.getChunk(0, 0, 0);
        fillLayer(chunk, 20, Blocks.STONE);
        chunk.setBlock(16, 10, 16, Blocks.RED_LAMP);
        WorldLightingSystem system = new WorldLightingSystem(manager.getBlockCatalog(), manager::getBlockAtWorld,
                SINGLE_CHUNK_HEIGHT);
        system.initializeChunk(chunk);
        assertEquals(14, ChunkLighting.getRed(manager.getPackedLightAtWorld(17, 10, 16)));

        chunk.setBlock(16, 10, 16, Blocks.AIR);
        system.enqueueBlockChange(16, 10, 16);
        system.processFrame(manager);
        assertEquals(0, ChunkLighting.getRed(manager.getPackedLightAtWorld(17, 10, 16)));
    }

    private static WorldLightingSystem.WorldLightingUpdateResult rebuild(
            ChunkManager manager,
            WorldHeightRange heightRange
    ) {
        WorldLightingSystem system = new WorldLightingSystem(
                manager.getBlockCatalog(),
                manager::getBlockAtWorld,
                heightRange
        );
        return system.rebuildLightingAround(manager, Set.of(new ChunkPosition(0, 0, 0)));
    }

    private static int sky(ChunkManager manager, int worldX, int worldY, int worldZ) {
        return ChunkLighting.getSky(manager.getPackedLightAtWorld(worldX, worldY, worldZ));
    }

    private static ChunkManager managerWithAirChunk(int x, int y, int z) {
        ChunkManager manager = new ChunkManager();
        addAirChunk(manager, x, y, z);
        return manager;
    }

    private static void addAirChunk(ChunkManager manager, int x, int y, int z) {
        Chunk chunk = new Chunk(new Vector3i(x, y, z));
        chunk.fillChunk(Blocks.AIR);
        manager.addChunk(chunk);
    }

    private static void fillLayer(Chunk chunk, int y, org.weaw.game.utils.BlockDefinition block) {
        for (int z = 0; z < Chunk.SIZE; z++) {
            for (int x = 0; x < Chunk.SIZE; x++) {
                chunk.setBlock(x, y, z, block);
            }
        }
    }

    private static void encloseTunnel(Chunk chunk, int startX, int endX, int y, int z) {
        for (int x = startX; x <= endX; x++) {
            chunk.setBlock(x, y - 1, z, Blocks.STONE);
            chunk.setBlock(x, y + 1, z, Blocks.STONE);
            chunk.setBlock(x, y, z - 1, Blocks.STONE);
            chunk.setBlock(x, y, z + 1, Blocks.STONE);
        }
        chunk.setBlock(endX + 1, y, z, Blocks.STONE);
    }

    private static void encloseTunnelAcrossChunks(
            ChunkManager manager,
            int startX,
            int endX,
            int y,
            int z
    ) {
        for (int worldX = startX; worldX <= endX; worldX++) {
            manager.setBlockAtWorld(worldX, y - 1, z, Blocks.STONE);
            manager.setBlockAtWorld(worldX, y + 1, z, Blocks.STONE);
            manager.setBlockAtWorld(worldX, y, z - 1, Blocks.STONE);
            manager.setBlockAtWorld(worldX, y, z + 1, Blocks.STONE);
        }
        manager.setBlockAtWorld(endX + 1, y, z, Blocks.STONE);
    }
}
