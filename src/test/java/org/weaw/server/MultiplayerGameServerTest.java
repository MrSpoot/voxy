package org.weaw.server;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.weaw.game.Chunk;
import org.weaw.game.ChunkLighting;
import org.weaw.game.World;
import org.weaw.game.WorldHeightRange;
import org.weaw.game.WorldMemoryBudget;
import org.weaw.game.WorldSettings;
import org.weaw.game.generation.WorldGenerator;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.game.utils.Blocks;
import org.weaw.gameplay.BlockAction;
import org.weaw.gameplay.PlayerInput;
import org.weaw.network.protocol.CatalogFingerprint;
import org.weaw.network.protocol.ClientMessage;
import org.weaw.network.protocol.Protocol;
import org.weaw.network.protocol.ServerMessage;
import org.weaw.network.client.NetworkClientSession;
import org.weaw.network.transport.LocalTransportPair;
import org.weaw.network.transport.TcpClientTransport;
import org.weaw.network.transport.TcpServerTransport;
import org.weaw.persistence.PlayerSaveState;
import org.weaw.persistence.StorageOptions;
import org.weaw.persistence.WorldManifest;
import org.weaw.persistence.WorldRepository;
import org.weaw.persistence.WorldSaveSession;
import org.weaw.game.generation.GenerationConfig;
import org.weaw.game.generation.NoiseWorldGenerator;
import org.weaw.gameplay.GameplaySession;
import org.weaw.gameplay.GameplaySettings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiplayerGameServerTest {
    @TempDir
    Path temporaryDirectory;

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
            assertTrue(pair.client().send(new ClientMessage.PlayerCommand(7L, 1L, input, 0, null)));
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
            pair.client().send(new ClientMessage.PlayerCommand(0L, 0L, PlayerInput.disabled(), 4, null));
            pair.client().send(new ClientMessage.PlayerCommand(1L, 1L, PlayerInput.disabled(), 6, null));

            server.tickOnce();

            ServerMessage.StateSnapshot snapshot = pollUntil(pair, ServerMessage.StateSnapshot.class);
            assertNotNull(snapshot);
            assertEquals(0L, snapshot.acknowledgedSequence());
            assertEquals(4, snapshot.selectedHotbarSlot());
        }
    }

    @Test
    void acceptsAValidatedBlockPredictionAndReturnsItsRevision() {
        LocalTransportPair pair = new LocalTransportPair();
        World world = createSmallWorld();
        try (MultiplayerGameServer server = new MultiplayerGameServer(world, 1L, pair.server(), 1)) {
            connect(pair, server);
            assertTrue(world.trySetBlockAtWorld(16, 11, 46, Blocks.STONE));
            PlayerInput breakInput = breakInput();
            BlockAction action = new BlockAction(
                    BlockAction.Type.BREAK, 16, 11, 46,
                    Blocks.STONE.getId(), Blocks.AIR.getId()
            );
            pair.client().send(new ClientMessage.PlayerCommand(0L, 0L, breakInput, 0, action));

            server.tickOnce();

            ServerMessage.BlockActionResult result = pollUntil(pair, ServerMessage.BlockActionResult.class);
            assertNotNull(result);
            assertTrue(result.accepted());
            assertEquals(Blocks.AIR.getId(), result.authoritativeBlockId());
            assertTrue(result.revision() >= 2L);
            assertEquals(Blocks.AIR.getId(), world.getBlockAtWorld(16, 11, 46));
        }
    }

    @Test
    void rejectsAStaleBlockPredictionWithoutChangingTheWorld() {
        LocalTransportPair pair = new LocalTransportPair();
        World world = createSmallWorld();
        try (MultiplayerGameServer server = new MultiplayerGameServer(world, 1L, pair.server(), 1)) {
            connect(pair, server);
            assertTrue(world.trySetBlockAtWorld(16, 11, 46, Blocks.STONE));
            BlockAction stale = new BlockAction(
                    BlockAction.Type.BREAK, 16, 11, 46,
                    Blocks.DIRT.getId(), Blocks.AIR.getId()
            );
            pair.client().send(new ClientMessage.PlayerCommand(
                    0L, 0L, breakInput(), 0, stale
            ));

            server.tickOnce();

            ServerMessage.BlockActionResult result = pollUntil(pair, ServerMessage.BlockActionResult.class);
            assertNotNull(result);
            org.junit.jupiter.api.Assertions.assertFalse(result.accepted());
            assertEquals(Blocks.STONE.getId(), result.authoritativeBlockId());
            assertEquals(Blocks.STONE.getId(), world.getBlockAtWorld(16, 11, 46));
        }
    }

    @Test
    void acceptsAValidatedPlacementPrediction() {
        LocalTransportPair pair = new LocalTransportPair();
        World world = createSmallWorld();
        try (MultiplayerGameServer server = new MultiplayerGameServer(world, 1L, pair.server(), 1)) {
            connect(pair, server);
            assertTrue(world.trySetBlockAtWorld(16, 11, 45, Blocks.STONE));
            BlockAction placement = new BlockAction(
                    BlockAction.Type.PLACE, 16, 11, 46,
                    Blocks.AIR.getId(), Blocks.GRASS_BLOCK.getId()
            );
            pair.client().send(new ClientMessage.PlayerCommand(
                    0L, 0L, placeInput(), 0, placement
            ));

            server.tickOnce();

            ServerMessage.BlockActionResult result = pollUntil(pair, ServerMessage.BlockActionResult.class);
            assertNotNull(result);
            assertTrue(result.accepted());
            assertEquals(Blocks.GRASS_BLOCK.getId(), world.getBlockAtWorld(16, 11, 46));
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
            pair.client().send(new ClientMessage.PlayerCommand(0L, 0L, moveOnce, 0, null));
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

    @Test
    void acceptsDistinctProfileUuidsAndRejectsAProfileAlreadyConnected() throws Exception {
        World world = createSmallWorld();
        TcpServerTransport transport = new TcpServerTransport(0);
        MultiplayerGameServer server = new MultiplayerGameServer(world, 4321L, transport, 3);
        UUID aliceId = UUID.randomUUID();
        UUID bobId = UUID.randomUUID();
        server.start();
        try (NetworkClientSession alice = new NetworkClientSession(
                new TcpClientTransport("127.0.0.1", transport.localPort()),
                BlockRegistry.getDefaultCatalog(), aliceId, "Alice", 2
        ); NetworkClientSession bob = new NetworkClientSession(
                new TcpClientTransport("127.0.0.1", transport.localPort()),
                BlockRegistry.getDefaultCatalog(), bobId, "Bob", 2
        )) {
            alice.connect();
            bob.connect();
            assertEquals(2, server.getPlayerCount());

            try (NetworkClientSession duplicateAlice = new NetworkClientSession(
                    new TcpClientTransport("127.0.0.1", transport.localPort()),
                    BlockRegistry.getDefaultCatalog(), aliceId, "Alice_Clone", 2
            )) {
                IOException rejection = assertThrows(IOException.class, duplicateAlice::connect);
                assertEquals("Player profile is already connected or invalid", rejection.getMessage());
            }
        } finally {
            server.close();
        }
    }

    @Test
    void restoresPlayerStateByStableProfileUuid() {
        UUID profileId = UUID.randomUUID();
        StorageOptions storage = new StorageOptions(
                temporaryDirectory, "persistent", "Persistent", "default", "Alice",
                60, 2, 2, false, false
        );
        GenerationConfig generation = GenerationConfig.defaults().withSeed(4567L);
        WorldSaveSession firstSave = new WorldRepository(temporaryDirectory).openOrCreate(
                storage, generation, new WorldHeightRange(0, 0), BlockRegistry.getDefaultCatalog()
        );
        World firstWorld = persistentWorld(firstSave);
        GameplaySession savedGameplay = new GameplaySession(firstWorld, new GameplaySettings());
        savedGameplay.setPlayerPose(new org.joml.Vector3f(7.0f, 8.0f, 9.0f), 33.0f, -4.0f);
        savedGameplay.getPlayer().setNoclip(true);
        savedGameplay.getHotbar().select(5);
        firstSave.saveNow(firstWorld, List.of(PlayerSaveState.capture(profileId, "Alice", savedGameplay)));
        firstSave.close();
        firstWorld.close();

        WorldSaveSession reopened = new WorldRepository(temporaryDirectory).openOrCreate(
                storage, generation, new WorldHeightRange(0, 0), BlockRegistry.getDefaultCatalog()
        );
        World restoredWorld = persistentWorld(reopened);
        LocalTransportPair pair = new LocalTransportPair();
        try (MultiplayerGameServer server = new MultiplayerGameServer(
                restoredWorld, 4567L, pair.server(), 1, reopened
        )) {
            pair.client().send(new ClientMessage.Hello(
                    Protocol.VERSION,
                    CatalogFingerprint.compute(BlockRegistry.getDefaultCatalog()),
                    profileId,
                    "Alice",
                    2
            ));
            server.tickOnce();

            ServerMessage.StateSnapshot snapshot = pollUntil(pair, ServerMessage.StateSnapshot.class);
            assertNotNull(snapshot);
            assertEquals(new org.joml.Vector3f(7.0f, 8.0f, 9.0f), snapshot.players().getFirst().position());
            assertTrue(snapshot.players().getFirst().noclip());
            assertEquals(5, snapshot.selectedHotbarSlot());
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

    private static World persistentWorld(WorldSaveSession save) {
        WorldManifest manifest = save.manifest();
        World world = new World(
                new NoiseWorldGenerator(manifest.generationConfig()),
                new WorldSettings(
                        manifest.simulationDistanceChunks(), manifest.defaultRenderDistanceChunks(),
                        manifest.heightRange(), WorldMemoryBudget.balanced(), false
                ),
                BlockRegistry.getDefaultCatalog(),
                save.consumeInitialEdits()
        );
        world.setDynamicLightingEnabled(false);
        return world;
    }

    private static void connect(LocalTransportPair pair, MultiplayerGameServer server) {
        pair.client().send(new ClientMessage.Hello(
                Protocol.VERSION,
                CatalogFingerprint.compute(BlockRegistry.getDefaultCatalog()),
                "Alice",
                2
        ));
        server.tickOnce();
        assertNotNull(pollUntil(pair, ServerMessage.Welcome.class));
    }

    private static PlayerInput breakInput() {
        return new PlayerInput(
                true, false, false, false, false, false, false,
                false, false, true, true, false, 0.0f, 0.0f, 0
        );
    }

    private static PlayerInput placeInput() {
        return new PlayerInput(
                true, false, false, false, false, false, false,
                false, false, true, false, true, 0.0f, 0.0f, 0
        );
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
