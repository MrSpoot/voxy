package org.weaw.network.client;

import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.weaw.game.Chunk;
import org.weaw.game.ChunkLighting;
import org.weaw.game.ChunkPosition;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.game.utils.Blocks;
import org.weaw.gameplay.BlockAction;
import org.weaw.gameplay.GameplaySession;
import org.weaw.gameplay.GameplaySettings;
import org.weaw.gameplay.PlayerInput;
import org.weaw.network.protocol.ServerMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientWorldPredictionTest {
    private static final ChunkPosition ORIGIN = new ChunkPosition(0, 0, 0);

    @BeforeAll
    static void initializeBlocks() {
        BlockRegistry.initialize();
    }

    @Test
    void appliesBreakImmediatelyAndRollsBackWhenRejected() {
        try (ClientWorld clientWorld = worldWithBlock(BlockRegistry.getRuntimeId(Blocks.STONE))) {
            BlockAction action = breakAction(BlockRegistry.getRuntimeId(Blocks.STONE));

            assertTrue(clientWorld.predictBlock(10L, action));
            assertEquals(BlockRegistry.getRuntimeId(Blocks.AIR), clientWorld.world().getBlockAtWorld(1, 1, 1));

            clientWorld.apply(new ServerMessage.BlockActionResult(
                    10L, false, 1, 1, 1, BlockRegistry.getRuntimeId(Blocks.STONE), 1L
            ));

            assertEquals(BlockRegistry.getRuntimeId(Blocks.STONE), clientWorld.world().getBlockAtWorld(1, 1, 1));
            assertEquals(0, clientWorld.pendingBlockPredictionCount());
        }
    }

    @Test
    void acceptedPredictionDoesNotChangeTheVisibleResult() {
        try (ClientWorld clientWorld = worldWithBlock(BlockRegistry.getRuntimeId(Blocks.STONE))) {
            assertTrue(clientWorld.predictBlock(11L, breakAction(BlockRegistry.getRuntimeId(Blocks.STONE))));

            clientWorld.apply(new ServerMessage.BlockActionResult(
                    11L, true, 1, 1, 1, BlockRegistry.getRuntimeId(Blocks.AIR), 2L
            ));

            assertEquals(BlockRegistry.getRuntimeId(Blocks.AIR), clientWorld.world().getBlockAtWorld(1, 1, 1));
            assertEquals(0, clientWorld.pendingBlockPredictionCount());
        }
    }

    @Test
    void keepsANewerPredictionVisibleWhileResolvingAnOlderOne() {
        try (ClientWorld clientWorld = worldWithBlock(BlockRegistry.getRuntimeId(Blocks.STONE))) {
            assertTrue(clientWorld.predictBlock(20L, breakAction(BlockRegistry.getRuntimeId(Blocks.STONE))));
            assertTrue(clientWorld.predictBlock(21L, new BlockAction(
                    BlockAction.Type.PLACE, 1, 1, 1,
                    BlockRegistry.getRuntimeId(Blocks.AIR), BlockRegistry.getRuntimeId(Blocks.DIRT)
            )));

            clientWorld.apply(new ServerMessage.BlockActionResult(
                    20L, true, 1, 1, 1, BlockRegistry.getRuntimeId(Blocks.AIR), 2L
            ));
            assertEquals(BlockRegistry.getRuntimeId(Blocks.DIRT), clientWorld.world().getBlockAtWorld(1, 1, 1));

            clientWorld.apply(new ServerMessage.BlockActionResult(
                    21L, false, 1, 1, 1, BlockRegistry.getRuntimeId(Blocks.AIR), 2L
            ));
            assertEquals(BlockRegistry.getRuntimeId(Blocks.AIR), clientWorld.world().getBlockAtWorld(1, 1, 1));
        }
    }

    @Test
    void reappliesPredictionOverANewerChunkSnapshot() {
        try (ClientWorld clientWorld = worldWithBlock(BlockRegistry.getRuntimeId(Blocks.STONE))) {
            assertTrue(clientWorld.predictBlock(30L, breakAction(BlockRegistry.getRuntimeId(Blocks.STONE))));

            clientWorld.apply(snapshot(BlockRegistry.getRuntimeId(Blocks.DIRT), 2L));
            assertEquals(BlockRegistry.getRuntimeId(Blocks.AIR), clientWorld.world().getBlockAtWorld(1, 1, 1));

            clientWorld.apply(new ServerMessage.BlockActionResult(
                    30L, false, 1, 1, 1, BlockRegistry.getRuntimeId(Blocks.DIRT), 2L
            ));
            assertEquals(BlockRegistry.getRuntimeId(Blocks.DIRT), clientWorld.world().getBlockAtWorld(1, 1, 1));
        }
    }

    @Test
    void actionResultDoesNotHideAnOlderUpdateForAnotherBlock() {
        try (ClientWorld clientWorld = worldWithBlock(BlockRegistry.getRuntimeId(Blocks.STONE))) {
            assertTrue(clientWorld.predictBlock(40L, breakAction(BlockRegistry.getRuntimeId(Blocks.STONE))));
            clientWorld.apply(new ServerMessage.BlockActionResult(
                    40L, true, 1, 1, 1, BlockRegistry.getRuntimeId(Blocks.AIR), 3L
            ));

            clientWorld.apply(new ServerMessage.BlockUpdate(2, 1, 1, BlockRegistry.getRuntimeId(Blocks.DIRT), 2L));
            clientWorld.apply(new ServerMessage.BlockUpdate(1, 1, 1, BlockRegistry.getRuntimeId(Blocks.STONE), 2L));

            assertEquals(BlockRegistry.getRuntimeId(Blocks.DIRT), clientWorld.world().getBlockAtWorld(2, 1, 1));
            assertEquals(BlockRegistry.getRuntimeId(Blocks.AIR), clientWorld.world().getBlockAtWorld(1, 1, 1));
        }
    }

    @Test
    void unloadingAChunkDropsItsPendingPredictions() {
        try (ClientWorld clientWorld = worldWithBlock(BlockRegistry.getRuntimeId(Blocks.STONE))) {
            assertTrue(clientWorld.predictBlock(50L, breakAction(BlockRegistry.getRuntimeId(Blocks.STONE))));

            clientWorld.apply(new ServerMessage.ChunkUnload(ORIGIN));

            assertEquals(0, clientWorld.pendingBlockPredictionCount());
            org.junit.jupiter.api.Assertions.assertFalse(clientWorld.world().containsChunk(0, 0, 0));
        }
    }

    @Test
    void predictsBlockLightingAndReappliesItOverAnAuthoritativeLightUpdate() {
        try (ClientWorld clientWorld = worldWithBlock(BlockRegistry.getRuntimeId(Blocks.AIR))) {
            BlockAction action = new BlockAction(
                    BlockAction.Type.PLACE, 1, 1, 1,
                    BlockRegistry.getRuntimeId(Blocks.AIR), BlockRegistry.getRuntimeId(Blocks.RED_LAMP)
            );

            assertTrue(clientWorld.predictBlock(60L, action));
            assertEquals(15, redLightAtPrediction(clientWorld));

            clientWorld.apply(new ServerMessage.ChunkLightUpdate(
                    ORIGIN,
                    1L,
                    new int[ChunkLighting.packedIntCount()],
                    new byte[Chunk.packedDirectSkyByteCount()]
            ));

            assertEquals(15, redLightAtPrediction(clientWorld));

            clientWorld.apply(new ServerMessage.BlockActionResult(
                    60L, false, 1, 1, 1, BlockRegistry.getRuntimeId(Blocks.AIR), 1L
            ));
            assertEquals(0, redLightAtPrediction(clientWorld));
        }
    }

    @Test
    void predictsSkyLightWhenAnOpaqueBlockIsBroken() {
        try (ClientWorld clientWorld = new ClientWorld(BlockRegistry.getDefaultCatalog(), 99L, 0, 0, 2)) {
            short[] blocks = new short[Chunk.TOTAL_BLOCKS];
            blocks[1 + Chunk.SIZE + 30 * Chunk.SIZE * Chunk.SIZE] = BlockRegistry.getRuntimeId(Blocks.STONE);
            clientWorld.apply(new ServerMessage.ChunkSnapshot(
                    ORIGIN,
                    1L,
                    blocks,
                    new int[ChunkLighting.packedIntCount()],
                    new byte[Chunk.packedDirectSkyByteCount()]
            ));

            assertTrue(clientWorld.predictBlock(65L, new BlockAction(
                    BlockAction.Type.BREAK, 1, 30, 1,
                    BlockRegistry.getRuntimeId(Blocks.STONE), BlockRegistry.getRuntimeId(Blocks.AIR)
            )));
            assertEquals(15, ChunkLighting.getSky(clientWorld.world().getPackedLightAtWorld(1, 30, 1)));

            clientWorld.apply(new ServerMessage.BlockActionResult(
                    65L, false, 1, 30, 1, BlockRegistry.getRuntimeId(Blocks.STONE), 1L
            ));
            assertEquals(BlockRegistry.getRuntimeId(Blocks.STONE), clientWorld.world().getBlockAtWorld(1, 30, 1));
            assertEquals(0, clientWorld.world().getChunkManager().getChunk(0, 0, 0)
                    .getDirectSkyLight(1, 29, 1));
            assertTrue(ChunkLighting.getSky(clientWorld.world().getPackedLightAtWorld(1, 29, 1)) < 15);
        }
    }

    @Test
    void predictionReplayMovesBeforeApplyingTheBlockFromTheSameTick() {
        try (ClientWorld clientWorld = worldWithBlock(BlockRegistry.getRuntimeId(Blocks.AIR))) {
            BlockAction action = new BlockAction(
                    BlockAction.Type.PLACE, 1, 1, 0,
                    BlockRegistry.getRuntimeId(Blocks.AIR), BlockRegistry.getRuntimeId(Blocks.STONE)
            );
            assertTrue(clientWorld.predictBlock(70L, action));
            GameplaySession gameplay = new GameplaySession(clientWorld.world(), new GameplaySettings());
            PlayerInput moveForward = new PlayerInput(
                    true, true, false, false, false, false, false,
                    false, false, false, false, false, 0.0f, 0.0f, 0
            );
            gameplay.getPlayer().setPosition(new Vector3f(1.5f, 2.62f, 1.31f));

            try (ClientWorld.PredictionReplay replay = clientWorld.beginPredictionReplay(69L)) {
                gameplay.beginSimulationTick();
                gameplay.updateMovement(1.0f / 30.0f, moveForward);
                assertTrue(gameplay.getPlayer().getPosition().z < 1.31f);
                replay.apply(70L);
                assertEquals(BlockRegistry.getRuntimeId(Blocks.STONE), clientWorld.world().getBlockAtWorld(1, 1, 0));
            }

            gameplay.getPlayer().setPosition(new Vector3f(1.5f, 2.62f, 1.31f));
            gameplay.beginSimulationTick();
            gameplay.updateMovement(1.0f / 30.0f, moveForward);
            assertEquals(1.31f, gameplay.getPlayer().getPosition().z, 0.001f);
        }
    }

    private static ClientWorld worldWithBlock(short blockId) {
        ClientWorld clientWorld = new ClientWorld(BlockRegistry.getDefaultCatalog(), 99L, 0, 0, 2);
        clientWorld.apply(snapshot(blockId, 1L));
        return clientWorld;
    }

    private static ServerMessage.ChunkSnapshot snapshot(short blockId, long revision) {
        short[] blocks = new short[Chunk.TOTAL_BLOCKS];
        blocks[1 + Chunk.SIZE + Chunk.SIZE * Chunk.SIZE] = blockId;
        return new ServerMessage.ChunkSnapshot(
                ORIGIN,
                revision,
                blocks,
                new int[ChunkLighting.packedIntCount()],
                new byte[Chunk.packedDirectSkyByteCount()]
        );
    }

    private static BlockAction breakAction(short expectedBlockId) {
        return new BlockAction(
                BlockAction.Type.BREAK, 1, 1, 1,
                expectedBlockId, BlockRegistry.getRuntimeId(Blocks.AIR)
        );
    }

    private static int redLightAtPrediction(ClientWorld clientWorld) {
        return ChunkLighting.getRed(clientWorld.world().getPackedLightAtWorld(1, 1, 1));
    }
}
