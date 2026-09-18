package org.weaw.network.client;

import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.weaw.game.Chunk;
import org.weaw.game.ChunkLighting;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.game.utils.Blocks;
import org.weaw.game.generation.GenerationConfig;
import org.weaw.game.generation.NoiseWorldGenerator;
import org.weaw.gameplay.PlayerInput;
import org.weaw.gameplay.PlayerRenderPose;
import org.weaw.network.protocol.ClientMessage;
import org.weaw.network.protocol.NetworkPlayerState;
import org.weaw.network.protocol.ServerMessage;
import org.weaw.network.transport.ClientTransport;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkClientSessionTest {
    @BeforeAll
    static void initializeBlocks() {
        BlockRegistry.initialize();
    }

    @Test
    void preservesSnapshotsThatArriveInTheSameBatchAsWelcome() throws IOException {
        QueueClientTransport transport = new QueueClientTransport();
        short[] blocks = new short[Chunk.TOTAL_BLOCKS];
        blocks[0] = Blocks.STONE.getId();
        int[] light = new int[ChunkLighting.packedIntCount()];
        byte[] directSky = new byte[Chunk.packedDirectSkyByteCount()];
        java.util.Arrays.fill(directSky, (byte) 0xFF);
        transport.inbound.addLast(new ServerMessage.Welcome(4L, 10L, 99L, 0, 0, 2));
        transport.inbound.addLast(new ServerMessage.ChunkSnapshot(
                new ChunkPosition(0, 0, 0), 1L, blocks, light, directSky
        ));
        transport.inbound.addLast(new ServerMessage.StateSnapshot(
                10L,
                -1L,
                List.of(new NetworkPlayerState(
                        4L, "Alice", new Vector3f(7.0f, 8.0f, 9.0f),
                        12.0f, 3.0f, 0.0f, false, true
                )),
                new String[9],
                0
        ));

        try (NetworkClientSession client = new NetworkClientSession(
                transport, BlockRegistry.getDefaultCatalog(), "Alice", 2
        )) {
            client.connect();

            assertTrue(client.getClientWorld().world().containsChunk(0, 0, 0));
            assertEquals(Blocks.STONE.getId(), client.getClientWorld().world().getBlockAtWorld(0, 0, 0));
            assertEquals(
                    ChunkLighting.MAX_SKY_LIGHT,
                    client.getClientWorld().world().getChunkManager().getChunk(0, 0, 0)
                            .getDirectSkyLight(0, 0, 0)
            );
            assertEquals(new Vector3f(7.0f, 8.0f, 9.0f), client.getGameplay().getPlayer().getPosition());
            assertTrue(client.getGameplay().getPlayer().isNoclip());
        }
    }

    @Test
    void usesTheServerSeedForCollisionBeforeChunksArrive() {
        long seed = 78234L;
        NoiseWorldGenerator expected = new NoiseWorldGenerator(GenerationConfig.defaults().withSeed(seed));
        try (ClientWorld clientWorld = new ClientWorld(
                BlockRegistry.getDefaultCatalog(), seed, -4, 4, 12
        )) {
            for (int[] point : List.of(
                    new int[]{16, 0, 48},
                    new int[]{37, -5, -91},
                    new int[]{-102, 8, 63}
            )) {
                assertEquals(
                        expected.getBlockAtWorld(point[0], point[1], point[2]),
                        clientWorld.world().getBlockAtWorld(point[0], point[1], point[2])
                );
            }
        }
    }

    @Test
    void smoothsASmallAuthoritativeCorrectionOverOneHundredMilliseconds() throws IOException {
        QueueClientTransport transport = connectedTransport(new Vector3f(16.0f, 12.0f, 48.0f));
        try (NetworkClientSession client = new NetworkClientSession(
                transport, BlockRegistry.getDefaultCatalog(), "Alice", 2
        )) {
            client.connect();
            client.getGameplay().getPlayer().setPosition(new Vector3f(16.5f, 12.0f, 48.0f));
            transport.inbound.addLast(stateAt(new Vector3f(16.0f, 12.0f, 48.0f), 30.0f));

            client.update(0.0f, PlayerInput.disabled());
            PlayerRenderPose start = client.sampleRenderPose(1.0f);
            client.update(0.05f, PlayerInput.disabled());
            PlayerRenderPose middle = client.sampleRenderPose(1.0f);
            client.update(0.05f, PlayerInput.disabled());
            PlayerRenderPose end = client.sampleRenderPose(1.0f);

            assertEquals(16.0f, client.getGameplay().getPlayer().getPosition().x, 0.001f);
            assertEquals(16.5f, start.position().x, 0.001f);
            assertEquals(16.25f, middle.position().x, 0.02f);
            assertEquals(16.0f, end.position().x, 0.02f);
        }
    }

    @Test
    void snapsImmediatelyWhenTheAuthoritativeCorrectionIsAtLeastTwoBlocks() throws IOException {
        QueueClientTransport transport = connectedTransport(new Vector3f(16.0f, 12.0f, 48.0f));
        try (NetworkClientSession client = new NetworkClientSession(
                transport, BlockRegistry.getDefaultCatalog(), "Alice", 2
        )) {
            client.connect();
            client.getGameplay().getPlayer().setPosition(new Vector3f(18.0f, 12.0f, 48.0f));
            transport.inbound.addLast(stateAt(new Vector3f(16.0f, 12.0f, 48.0f), 30.0f));

            client.update(0.0f, PlayerInput.disabled());
            PlayerRenderPose renderPose = client.sampleRenderPose(1.0f);

            assertEquals(16.0f, client.getGameplay().getPlayer().getPosition().x, 0.001f);
            assertEquals(16.0f, renderPose.position().x, 0.001f);
        }
    }

    @Test
    void limitsWorldPacketApplicationPerFrame() throws IOException {
        QueueClientTransport transport = connectedTransport(new Vector3f(16.0f, 12.0f, 48.0f));
        try (NetworkClientSession client = new NetworkClientSession(
                transport, BlockRegistry.getDefaultCatalog(), "Alice", 2
        )) {
            client.connect();
            for (int chunkX = 0; chunkX < 6; chunkX++) {
                transport.inbound.addLast(emptyChunk(chunkX));
            }

            client.update(0.0f, PlayerInput.disabled());
            assertEquals(4, client.getClientWorld().world().getLoadedChunkCount());
            client.update(0.0f, PlayerInput.disabled());
            assertEquals(6, client.getClientWorld().world().getLoadedChunkCount());
        }
    }

    @Test
    void appliesDirectSunlightFromIncrementalLightUpdates() throws IOException {
        QueueClientTransport transport = connectedTransport(new Vector3f(16.0f, 12.0f, 48.0f));
        try (NetworkClientSession client = new NetworkClientSession(
                transport, BlockRegistry.getDefaultCatalog(), "Alice", 2
        )) {
            client.connect();
            transport.inbound.addLast(emptyChunk(0));
            client.update(0.0f, PlayerInput.disabled());

            byte[] directSky = new byte[Chunk.packedDirectSkyByteCount()];
            java.util.Arrays.fill(directSky, (byte) 0xFF);
            transport.inbound.addLast(new ServerMessage.ChunkLightUpdate(
                    new ChunkPosition(0, 0, 0),
                    2L,
                    new int[ChunkLighting.packedIntCount()],
                    directSky
            ));
            client.update(0.0f, PlayerInput.disabled());

            assertEquals(
                    ChunkLighting.MAX_SKY_LIGHT,
                    client.getClientWorld().world().getChunkManager().getChunk(0, 0, 0)
                            .getDirectSkyLight(Chunk.SIZE - 1, Chunk.SIZE - 1, Chunk.SIZE - 1)
            );
        }
    }

    @Test
    void appliesOnlyTheStateMatchingTheWelcomedPlayerId() throws IOException {
        QueueClientTransport transport = connectedTransport(new Vector3f(16.0f, 12.0f, 48.0f));
        try (NetworkClientSession client = new NetworkClientSession(
                transport, BlockRegistry.getDefaultCatalog(), "Alice", 2
        )) {
            client.connect();
            transport.inbound.addLast(new ServerMessage.StateSnapshot(
                    4L,
                    -1L,
                    List.of(
                            new NetworkPlayerState(
                                    3L, "Host", new Vector3f(90.0f, 50.0f, 20.0f),
                                    120.0f, 10.0f, 0.0f, false, true
                            ),
                            new NetworkPlayerState(
                                    4L, "Alice", new Vector3f(17.0f, 12.0f, 48.0f),
                                    -80.0f, 0.0f, 0.0f, false, true
                            )
                    ),
                    new String[9],
                    0
            ));

            client.update(0.0f, PlayerInput.disabled());

            assertEquals(17.0f, client.getGameplay().getPlayer().getPosition().x, 0.001f);
            assertEquals(-80.0f, client.getGameplay().getPlayer().getYaw(), 0.001f);
            assertEquals(1, client.getRemotePlayers().size());
        }
    }

    @Test
    void keepsANumberKeySelectionUntilTheServerAcknowledgesIt() throws IOException {
        Vector3f position = new Vector3f(16.0f, 12.0f, 48.0f);
        QueueClientTransport transport = connectedTransport(position);
        try (NetworkClientSession client = new NetworkClientSession(
                transport, BlockRegistry.getDefaultCatalog(), "Alice", 2
        )) {
            client.connect();
            client.selectHotbarSlot(4);
            transport.inbound.addLast(stateAt(position, -90.0f, -1L, 0));

            client.update(1.0f / 30.0f, PlayerInput.disabled());

            ClientMessage.PlayerCommand command = lastPlayerCommand(transport);
            assertEquals(4, command.selectedHotbarSlot());
            assertEquals(4, client.getHotbar().getSelectedIndex());

            transport.inbound.addLast(stateAt(position, -90.0f, command.sequence(), 4));
            client.update(0.0f, PlayerInput.disabled());
            assertEquals(4, client.getHotbar().getSelectedIndex());

            transport.inbound.addLast(stateAt(position, -90.0f, command.sequence(), 2));
            client.update(0.0f, PlayerInput.disabled());
            assertEquals(2, client.getHotbar().getSelectedIndex());
        }
    }

    @Test
    void convertsRepeatedFrameScrollIntoOneAbsoluteSelection() throws IOException {
        QueueClientTransport transport = connectedTransport(new Vector3f(16.0f, 12.0f, 48.0f));
        PlayerInput scrollOnce = new PlayerInput(
                true, false, false, false, false, false, false,
                false, false, false, false, false, 0.0f, 0.0f, 1
        );
        try (NetworkClientSession client = new NetworkClientSession(
                transport, BlockRegistry.getDefaultCatalog(), "Alice", 2
        )) {
            client.connect();

            assertEquals(0, client.update(0.01f, scrollOnce));
            assertEquals(0, client.update(0.01f, scrollOnce));
            assertEquals(0, client.getHotbar().getSelectedIndex());
            assertEquals(1, client.update(0.02f, scrollOnce));

            ClientMessage.PlayerCommand command = lastPlayerCommand(transport);
            assertEquals(1, command.selectedHotbarSlot());
            assertEquals(0, command.input().scrollDelta());
            assertEquals(1, client.getHotbar().getSelectedIndex());
        }
    }

    private static QueueClientTransport connectedTransport(Vector3f position) {
        QueueClientTransport transport = new QueueClientTransport();
        transport.inbound.addLast(new ServerMessage.Welcome(4L, 0L, 99L, -4, 4, 2));
        transport.inbound.addLast(stateAt(position, -90.0f));
        return transport;
    }

    private static ServerMessage.StateSnapshot stateAt(Vector3f position, float yaw) {
        return stateAt(position, yaw, -1L, 0);
    }

    private static ServerMessage.StateSnapshot stateAt(
            Vector3f position,
            float yaw,
            long acknowledgedSequence,
            int selectedHotbarSlot
    ) {
        return new ServerMessage.StateSnapshot(
                2L,
                acknowledgedSequence,
                List.of(new NetworkPlayerState(4L, "Alice", position, yaw, 0.0f, 0.0f, false, true)),
                new String[9],
                selectedHotbarSlot
        );
    }

    private static ClientMessage.PlayerCommand lastPlayerCommand(QueueClientTransport transport) {
        return transport.outbound.stream()
                .filter(ClientMessage.PlayerCommand.class::isInstance)
                .map(ClientMessage.PlayerCommand.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private static ServerMessage.ChunkSnapshot emptyChunk(int chunkX) {
        return new ServerMessage.ChunkSnapshot(
                new ChunkPosition(chunkX, 0, 0),
                1L,
                new short[Chunk.TOTAL_BLOCKS],
                new int[ChunkLighting.packedIntCount()],
                new byte[Chunk.packedDirectSkyByteCount()]
        );
    }

    private static final class QueueClientTransport implements ClientTransport {
        private final ArrayDeque<ServerMessage> inbound = new ArrayDeque<>();
        private final ArrayDeque<ClientMessage> outbound = new ArrayDeque<>();
        private boolean open = true;

        @Override
        public boolean send(ClientMessage message) {
            if (!open) {
                return false;
            }
            outbound.addLast(message);
            return true;
        }

        @Override
        public ServerMessage poll() {
            return inbound.pollFirst();
        }

        @Override
        public int inboundBacklog() {
            return inbound.size();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public String closeReason() {
            return "test closed";
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
