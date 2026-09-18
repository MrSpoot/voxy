package org.weaw.server;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.weaw.game.Chunk;
import org.weaw.game.ChunkLighting;
import org.weaw.game.World;
import org.weaw.game.WorldHeightRange;
import org.weaw.game.WorldMemoryBudget;
import org.weaw.game.WorldSettings;
import org.weaw.game.generation.WorldGenerator;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.game.utils.Blocks;
import org.weaw.gameplay.PlayerInput;
import org.weaw.network.protocol.CatalogFingerprint;
import org.weaw.network.protocol.ClientMessage;
import org.weaw.network.protocol.Protocol;
import org.weaw.network.protocol.ServerMessage;
import org.weaw.network.client.NetworkClientSession;
import org.weaw.network.transport.LocalTransportPair;
import org.weaw.network.transport.TcpClientTransport;
import org.weaw.network.transport.TcpServerTransport;

import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiplayerGameServerTest {
    @BeforeAll
    static void initializeBlocks() {
        BlockRegistry.initialize();
    }

    @Test
    void acceptsHandshakeAndAcknowledgesAuthoritativeInput() {
        LocalTransportPair pair = new LocalTransportPair();
        World world = createSmallWorld();
        try (MultiplayerGameServer server = new MultiplayerGameServer(world, 1234L, pair.server(), 2)) {
            assertTrue(pair.client().send(new ClientMessage.Hello(
                    Protocol.VERSION,
                    CatalogFingerprint.compute(BlockRegistry.getDefaultCatalog()),
                    "Alice",
                    2
            )));

            server.tickOnce();

            ServerMessage.Welcome welcome = pollUntil(pair, ServerMessage.Welcome.class);
            assertNotNull(welcome);
            assertEquals(1234L, welcome.worldSeed());
            assertEquals(1, server.getPlayerCount());

            PlayerInput input = new PlayerInput(
                    true, true, false, false, false, false, false,
                    false, false, true, false, false, 0.0f, 0.0f, 0
            );
            assertTrue(pair.client().send(new ClientMessage.PlayerCommand(7L, 1L, input, 0)));
            server.tickOnce();
            server.tickOnce();

            ServerMessage.StateSnapshot snapshot = pollUntil(pair, ServerMessage.StateSnapshot.class);
            while (snapshot != null && snapshot.acknowledgedSequence() != 7L) {
                snapshot = pollUntil(pair, ServerMessage.StateSnapshot.class);
            }
            assertNotNull(snapshot);
            assertEquals(7L, snapshot.acknowledgedSequence());
            assertEquals(welcome.playerId(), snapshot.players().getFirst().playerId());
            assertTrue(snapshot.players().getFirst().noclip());
        }
    }

    @Test
    void rejectsAnIncompatibleProtocolBeforeCreatingAPlayer() {
        LocalTransportPair pair = new LocalTransportPair();
        World world = createSmallWorld();
        try (MultiplayerGameServer server = new MultiplayerGameServer(world, 1L, pair.server(), 1)) {
            pair.client().send(new ClientMessage.Hello(
                    Protocol.VERSION + 1,
                    CatalogFingerprint.compute(BlockRegistry.getDefaultCatalog()),
                    "Alice",
                    2
            ));

            server.tickOnce();

            assertEquals(0, server.getPlayerCount());
            assertInstanceOf(ServerMessage.Rejected.class, pair.client().poll());
        }
    }

    @Test
    void acknowledgesOnlyTheCommandActuallySimulatedDuringTheTick() {
        LocalTransportPair pair = new LocalTransportPair();
        World world = createSmallWorld();
        try (MultiplayerGameServer server = new MultiplayerGameServer(world, 1L, pair.server(), 1)) {
            pair.client().send(new ClientMessage.Hello(
                    Protocol.VERSION,
                    CatalogFingerprint.compute(BlockRegistry.getDefaultCatalog()),
                    "Alice",
                    2
            ));
            pair.client().send(new ClientMessage.PlayerCommand(0L, 0L, PlayerInput.disabled(), 4));
            pair.client().send(new ClientMessage.PlayerCommand(1L, 1L, PlayerInput.disabled(), 6));

            server.tickOnce();

            ServerMessage.StateSnapshot snapshot = pollUntil(pair, ServerMessage.StateSnapshot.class);
            assertNotNull(snapshot);
            assertEquals(0L, snapshot.acknowledgedSequence());
            assertEquals(4, snapshot.selectedHotbarSlot());
        }
    }

    @Test
    void doesNotKeepMovingWhenNoNewCommandArrives() {
        LocalTransportPair pair = new LocalTransportPair();
        World world = createSmallWorld();
        try (MultiplayerGameServer server = new MultiplayerGameServer(world, 1L, pair.server(), 1)) {
            pair.client().send(new ClientMessage.Hello(
                    Protocol.VERSION,
                    CatalogFingerprint.compute(BlockRegistry.getDefaultCatalog()),
                    "Alice",
                    2
            ));
            server.tickOnce();
            pollUntil(pair, ServerMessage.Welcome.class);
            pollUntil(pair, ServerMessage.StateSnapshot.class);

            PlayerInput moveOnce = new PlayerInput(
                    true, true, false, false, false, false, false,
                    false, false, true, false, false, 0.0f, 0.0f, 0
            );
            pair.client().send(new ClientMessage.PlayerCommand(0L, 0L, moveOnce, 0));
            server.tickOnce();
            server.tickOnce();
            ServerMessage.StateSnapshot afterCommand = pollUntil(pair, ServerMessage.StateSnapshot.class);
            assertNotNull(afterCommand);

            server.tickOnce();
            server.tickOnce();
            ServerMessage.StateSnapshot afterTwoNeutralTicks = pollUntil(pair, ServerMessage.StateSnapshot.class);
            assertNotNull(afterTwoNeutralTicks);

            assertEquals(
                    afterCommand.players().getFirst().position(),
                    afterTwoNeutralTicks.players().getFirst().position()
            );
        }
    }

    @Test
    void throttlesAnUnreadLocalClientWithoutOverflowingOrDisconnectingIt() {
        LocalTransportPair pair = new LocalTransportPair();
        World world = createSmallWorld();
        try (MultiplayerGameServer server = new MultiplayerGameServer(world, 1L, pair.server(), 1)) {
            pair.client().send(new ClientMessage.Hello(
                    Protocol.VERSION,
                    CatalogFingerprint.compute(BlockRegistry.getDefaultCatalog()),
                    "SlowClient",
                    2
            ));

            for (int tick = 0; tick < 300; tick++) {
                server.tickOnce();
            }

            assertTrue(pair.client().isOpen());
            assertTrue(pair.server().outboundBacklog(1L) <= 8);
            assertTrue(server.getNetworkStats().skippedSnapshots() > 0L);
        }
    }

    @Test
    void streamsAnAuthoritativeWorldThroughTheCompleteTcpClientStack() throws Exception {
        World world = createSmallWorld();
        TcpServerTransport transport = new TcpServerTransport(0);
        MultiplayerGameServer server = new MultiplayerGameServer(world, 4321L, transport, 2);
        server.start();
        try (NetworkClientSession client = new NetworkClientSession(
                new TcpClientTransport("127.0.0.1", transport.localPort()),
                BlockRegistry.getDefaultCatalog(),
                "TcpPlayer",
                2
        )) {
            client.connect();
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (client.getClientWorld().world().getLoadedChunkCount() == 0
                    && System.nanoTime() < deadline) {
                client.update(1.0f / GameServer.DEFAULT_TICKS_PER_SECOND, PlayerInput.disabled());
                LockSupport.parkNanos(5_000_000L);
            }

            assertTrue(client.getLocalPlayerId() > 0L);
            assertTrue(client.getClientWorld().world().getLoadedChunkCount() > 0);
            Chunk receivedChunk = client.getClientWorld().world().getChunkManager()
                    .snapshotChunkUploads()
                    .values()
                    .iterator()
                    .next()
                    .chunk();
            assertEquals(
                    ChunkLighting.MAX_SKY_LIGHT,
                    receivedChunk.getDirectSkyLight(Chunk.SIZE / 2, Chunk.SIZE - 1, Chunk.SIZE / 2)
            );
        } finally {
            server.close();
        }
    }

    private static World createSmallWorld() {
        World world = new World(
                new FlatGenerator(Blocks.AIR.getId()),
                new WorldSettings(2, new WorldHeightRange(0, 0), WorldMemoryBudget.balanced(), false)
        );
        world.setDynamicLightingEnabled(false);
        return world;
    }

    private static <T extends ServerMessage> T pollUntil(LocalTransportPair pair, Class<T> type) {
        ServerMessage message;
        while ((message = pair.client().poll()) != null) {
            if (type.isInstance(message)) {
                return type.cast(message);
            }
        }
        return null;
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
