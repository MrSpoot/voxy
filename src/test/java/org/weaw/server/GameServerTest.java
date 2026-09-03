package org.weaw.server;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.weaw.game.Chunk;
import org.weaw.game.World;
import org.weaw.game.WorldSettings;
import org.weaw.game.generation.WorldGenerator;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.game.utils.Blocks;
import org.weaw.gameplay.GameplaySession;
import org.weaw.gameplay.GameplaySettings;
import org.weaw.gameplay.PlayerInput;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameServerTest {
    @BeforeAll
    static void initializeBlocks() {
        BlockRegistry.initialize();
    }

    @Test
    void updateAccumulatesFrameTimeIntoFixedTicks() {
        try (GameServer server = createServer(10)) {
            assertEquals(0, server.update(0.05f, PlayerInput.disabled()));
            assertEquals(0, server.getTickIndex());

            assertEquals(1, server.update(0.05f, PlayerInput.disabled()));
            assertEquals(1, server.getTickIndex());
        }
    }

    @Test
    void updateDoesNotDriveWorldStreaming() {
        try (CountingWorld world = new CountingWorld(new FlatGenerator(Blocks.AIR.getId()), new WorldSettings(1))) {
            GameplaySession session = new GameplaySession(world, new GameplaySettings());
            try (GameServer server = new GameServer(world, session, 10)) {
                assertEquals(1, server.update(0.1f, PlayerInput.disabled()));
                assertEquals(0, world.updateCalls);
            }
        }
    }

    @Test
    void transientInputIsConsumedOnlyOnFirstTickOfFrame() {
        try (GameServer server = createServer(10)) {
            PlayerInput toggleNoclip = new PlayerInput(
                    true,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    true,
                    false,
                    false,
                    0.0f,
                    0.0f,
                    0
            );

            assertEquals(2, server.update(0.2f, toggleNoclip));
            assertTrue(server.getGameplaySession().getPlayer().isNoclip());
        }
    }

    @Test
    void updateCapsCatchUpTicksPerFrame() {
        try (GameServer server = createServer(60)) {
            assertEquals(5, server.update(1.0f, PlayerInput.disabled()));
            assertEquals(5, server.getTickIndex());
        }
    }

    @Test
    void playerInputClearsOnlyFrameTransitionsBetweenCatchUpTicks() {
        PlayerInput input = new PlayerInput(
                true,
                true,
                false,
                false,
                false,
                true,
                false,
                true,
                true,
                true,
                true,
                true,
                12.0f,
                -4.0f,
                2
        );

        PlayerInput nextTickInput = input.withoutFrameTransitions();

        assertTrue(nextTickInput.moveForward());
        assertTrue(nextTickInput.moveUp());
        assertTrue(nextTickInput.sprint());
        assertEquals(false, nextTickInput.jump());
        assertEquals(false, nextTickInput.toggleNoclip());
        assertEquals(false, nextTickInput.breakBlock());
        assertEquals(false, nextTickInput.placeBlock());
        assertEquals(0.0f, nextTickInput.mouseDeltaX());
        assertEquals(0.0f, nextTickInput.mouseDeltaY());
        assertEquals(0, nextTickInput.scrollDelta());
    }

    private static GameServer createServer(int ticksPerSecond) {
        World world = new World(new FlatGenerator(Blocks.AIR.getId()), new WorldSettings(1));
        GameplaySession session = new GameplaySession(world, new GameplaySettings());
        return new GameServer(world, session, ticksPerSecond);
    }

    private static final class CountingWorld extends World {
        private int updateCalls;

        private CountingWorld(WorldGenerator worldGenerator, WorldSettings settings) {
            super(worldGenerator, settings);
        }

        @Override
        public void update(org.joml.Vector3f playerPosition) {
            updateCalls++;
        }
    }

    private record FlatGenerator(short blockId) implements WorldGenerator {
        @Override
        public void generateChunkData(Chunk chunk) {
            short[] blocks = new short[Chunk.TOTAL_BLOCKS];
            Arrays.fill(blocks, blockId);
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
