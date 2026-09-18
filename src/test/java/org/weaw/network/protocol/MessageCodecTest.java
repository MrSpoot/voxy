package org.weaw.network.protocol;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.weaw.game.Chunk;
import org.weaw.game.ChunkLighting;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.gameplay.PlayerInput;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessageCodecTest {
    @Test
    void roundTripsPlayerCommand() throws IOException {
        PlayerInput input = new PlayerInput(
                true, true, false, true, false, false, false, true,
                true, true, true, false, 12.5f, -7.25f, -2
        );
        ClientMessage.PlayerCommand original = new ClientMessage.PlayerCommand(42L, 17L, input, 8);

        ClientMessage decoded = MessageCodec.decodeClient(MessageCodec.encodeClient(original));

        assertEquals(original, decoded);
    }

    @Test
    void roundTripsCompressedChunkSnapshot() throws IOException {
        short[] blocks = new short[Chunk.TOTAL_BLOCKS];
        int[] light = new int[ChunkLighting.packedIntCount()];
        byte[] directSky = new byte[Chunk.packedDirectSkyByteCount()];
        for (int index = 0; index < blocks.length; index++) {
            blocks[index] = (short) (index % 14);
        }
        for (int index = 0; index < light.length; index++) {
            light[index] = index * 31;
        }
        for (int index = 0; index < directSky.length; index++) {
            directSky[index] = (byte) (index * 17);
        }
        ServerMessage.ChunkSnapshot original = new ServerMessage.ChunkSnapshot(
                new ChunkPosition(-3, 2, 9), 73L, blocks, light, directSky
        );

        ServerMessage decodedMessage = MessageCodec.decodeServer(MessageCodec.encodeServer(original));
        ServerMessage.ChunkSnapshot decoded = assertInstanceOf(ServerMessage.ChunkSnapshot.class, decodedMessage);

        assertEquals(original.position(), decoded.position());
        assertEquals(original.revision(), decoded.revision());
        assertArrayEquals(blocks, decoded.blocks());
        assertArrayEquals(light, decoded.packedLight());
        assertArrayEquals(directSky, decoded.packedDirectSkyLight());
    }

    @Test
    void roundTripsStateSnapshot() throws IOException {
        NetworkPlayerState state = new NetworkPlayerState(
                5L, "Player_1", new Vector3f(1.5f, 2.5f, -3.5f),
                90.0f, -12.0f, 0.25f, true, false
        );
        ServerMessage.StateSnapshot original = new ServerMessage.StateSnapshot(
                100L, 88L, List.of(state), new String[]{"voxy:stone", null}, 1
        );

        ServerMessage decodedMessage = MessageCodec.decodeServer(MessageCodec.encodeServer(original));
        ServerMessage.StateSnapshot decoded = assertInstanceOf(ServerMessage.StateSnapshot.class, decodedMessage);

        assertEquals(original.serverTick(), decoded.serverTick());
        assertEquals(original.acknowledgedSequence(), decoded.acknowledgedSequence());
        assertEquals(original.players(), decoded.players());
        assertArrayEquals(original.hotbarStableIds(), decoded.hotbarStableIds());
        assertEquals(original.selectedHotbarSlot(), decoded.selectedHotbarSlot());
    }

    @Test
    void roundTripsCompressedLightUpdate() throws IOException {
        int[] light = new int[ChunkLighting.packedIntCount()];
        byte[] directSky = new byte[Chunk.packedDirectSkyByteCount()];
        for (int index = 0; index < light.length; index++) {
            light[index] = index % 17 == 0 ? 0xFFFF_0000 : 0x00FF_00FF;
        }
        java.util.Arrays.fill(directSky, (byte) 0xFF);
        ServerMessage.ChunkLightUpdate original = new ServerMessage.ChunkLightUpdate(
                new ChunkPosition(4, -2, 7), 12L, light, directSky
        );

        byte[] encoded = MessageCodec.encodeServer(original);
        ServerMessage.ChunkLightUpdate decoded = assertInstanceOf(
                ServerMessage.ChunkLightUpdate.class,
                MessageCodec.decodeServer(encoded)
        );

        assertArrayEquals(light, decoded.packedLight());
        assertArrayEquals(directSky, decoded.packedDirectSkyLight());
        org.junit.jupiter.api.Assertions.assertTrue(
                encoded.length < (light.length * Integer.BYTES + directSky.length) / 4
        );
    }

    @Test
    void rejectsTrailingBytes() throws IOException {
        byte[] encoded = MessageCodec.encodeClient(new ClientMessage.Disconnect());
        byte[] invalid = java.util.Arrays.copyOf(encoded, encoded.length + 1);

        assertThrows(IOException.class, () -> MessageCodec.decodeClient(invalid));
    }
}
