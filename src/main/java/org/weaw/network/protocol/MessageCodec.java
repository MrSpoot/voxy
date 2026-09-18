package org.weaw.network.protocol;

import org.joml.Vector3f;
import org.weaw.game.Chunk;
import org.weaw.game.ChunkLighting;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.gameplay.PlayerInput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class MessageCodec {
    private static final int CLIENT_HELLO = 1;
    private static final int CLIENT_COMMAND = 2;
    private static final int CLIENT_SET_HOTBAR = 3;
    private static final int CLIENT_SWAP_HOTBAR = 4;
    private static final int CLIENT_DISCONNECT = 5;

    private static final int SERVER_WELCOME = 64;
    private static final int SERVER_STATE = 65;
    private static final int SERVER_CHUNK = 66;
    private static final int SERVER_CHUNK_UNLOAD = 67;
    private static final int SERVER_BLOCK = 68;
    private static final int SERVER_LIGHT = 69;
    private static final int SERVER_PLAYER_LEFT = 70;
    private static final int SERVER_REJECTED = 71;

    private MessageCodec() {
    }

    public static byte[] encodeClient(ClientMessage message) throws IOException {
        return encode(output -> writeClient(output, message));
    }

    public static ClientMessage decodeClient(byte[] payload) throws IOException {
        try (DataInputStream input = input(payload)) {
            ClientMessage message = readClient(input);
            requireFullyConsumed(input);
            return message;
        }
    }

    public static byte[] encodeServer(ServerMessage message) throws IOException {
        return encode(output -> writeServer(output, message));
    }

    public static ServerMessage decodeServer(byte[] payload) throws IOException {
        try (DataInputStream input = input(payload)) {
            ServerMessage message = readServer(input);
            requireFullyConsumed(input);
            return message;
        }
    }

    private static void writeClient(DataOutputStream output, ClientMessage message) throws IOException {
        switch (message) {
            case ClientMessage.Hello hello -> {
                output.writeByte(CLIENT_HELLO);
                output.writeInt(hello.protocolVersion());
                output.writeLong(hello.catalogFingerprint());
                writeString(output, hello.playerName(), Protocol.MAX_PLAYER_NAME_BYTES);
                output.writeInt(hello.viewDistance());
            }
            case ClientMessage.PlayerCommand command -> {
                output.writeByte(CLIENT_COMMAND);
                output.writeLong(command.sequence());
                output.writeLong(command.clientTick());
                writePlayerInput(output, command.input());
                output.writeByte(command.selectedHotbarSlot());
            }
            case ClientMessage.SetHotbarSlot set -> {
                output.writeByte(CLIENT_SET_HOTBAR);
                output.writeLong(set.sequence());
                output.writeByte(set.slot());
                writeString(output, set.stableBlockId(), 128);
            }
            case ClientMessage.SwapHotbarSlots swap -> {
                output.writeByte(CLIENT_SWAP_HOTBAR);
                output.writeLong(swap.sequence());
                output.writeByte(swap.firstSlot());
                output.writeByte(swap.secondSlot());
            }
            case ClientMessage.Disconnect ignored -> output.writeByte(CLIENT_DISCONNECT);
        }
    }

    private static ClientMessage readClient(DataInputStream input) throws IOException {
        return switch (input.readUnsignedByte()) {
            case CLIENT_HELLO -> new ClientMessage.Hello(
                    input.readInt(),
                    input.readLong(),
                    readString(input, Protocol.MAX_PLAYER_NAME_BYTES),
                    input.readInt()
            );
            case CLIENT_COMMAND -> new ClientMessage.PlayerCommand(
                    input.readLong(),
                    input.readLong(),
                    readPlayerInput(input),
                    input.readUnsignedByte()
            );
            case CLIENT_SET_HOTBAR -> new ClientMessage.SetHotbarSlot(
                    input.readLong(),
                    input.readUnsignedByte(),
                    readString(input, 128)
            );
            case CLIENT_SWAP_HOTBAR -> new ClientMessage.SwapHotbarSlots(
                    input.readLong(),
                    input.readUnsignedByte(),
                    input.readUnsignedByte()
            );
            case CLIENT_DISCONNECT -> new ClientMessage.Disconnect();
            default -> throw new IOException("Unknown client message type");
        };
    }

    private static void writeServer(DataOutputStream output, ServerMessage message) throws IOException {
        switch (message) {
            case ServerMessage.Welcome welcome -> {
                output.writeByte(SERVER_WELCOME);
                output.writeLong(welcome.playerId());
                output.writeLong(welcome.serverTick());
                output.writeLong(welcome.worldSeed());
                output.writeInt(welcome.minChunkY());
                output.writeInt(welcome.maxChunkY());
                output.writeInt(welcome.renderDistance());
            }
            case ServerMessage.StateSnapshot snapshot -> {
                output.writeByte(SERVER_STATE);
                output.writeLong(snapshot.serverTick());
                output.writeLong(snapshot.acknowledgedSequence());
                output.writeShort(snapshot.players().size());
                for (NetworkPlayerState player : snapshot.players()) {
                    writePlayerState(output, player);
                }
                String[] hotbar = snapshot.hotbarStableIds();
                output.writeByte(hotbar.length);
                for (String stableId : hotbar) {
                    writeString(output, stableId == null ? "" : stableId, 128);
                }
                output.writeByte(snapshot.selectedHotbarSlot());
            }
            case ServerMessage.ChunkSnapshot chunk -> {
                output.writeByte(SERVER_CHUNK);
                writeChunkPosition(output, chunk.position());
                output.writeLong(chunk.revision());
                byte[] compressed = compressChunk(
                        chunk.blocks(),
                        chunk.packedLight(),
                        chunk.packedDirectSkyLight()
                );
                output.writeInt(compressed.length);
                output.write(compressed);
            }
            case ServerMessage.ChunkUnload unload -> {
                output.writeByte(SERVER_CHUNK_UNLOAD);
                writeChunkPosition(output, unload.position());
            }
            case ServerMessage.BlockUpdate block -> {
                output.writeByte(SERVER_BLOCK);
                output.writeInt(block.x());
                output.writeInt(block.y());
                output.writeInt(block.z());
                output.writeShort(block.blockId());
                output.writeLong(block.revision());
            }
            case ServerMessage.ChunkLightUpdate light -> {
                output.writeByte(SERVER_LIGHT);
                writeChunkPosition(output, light.position());
                output.writeLong(light.revision());
                byte[] compressed = compressLight(light.packedLight(), light.packedDirectSkyLight());
                output.writeInt(compressed.length);
                output.write(compressed);
            }
            case ServerMessage.PlayerLeft left -> {
                output.writeByte(SERVER_PLAYER_LEFT);
                output.writeLong(left.playerId());
            }
            case ServerMessage.Rejected rejected -> {
                output.writeByte(SERVER_REJECTED);
                writeString(output, rejected.reason(), 1024);
            }
        }
    }

    private static ServerMessage readServer(DataInputStream input) throws IOException {
        return switch (input.readUnsignedByte()) {
            case SERVER_WELCOME -> new ServerMessage.Welcome(
                    input.readLong(), input.readLong(), input.readLong(),
                    input.readInt(), input.readInt(), input.readInt()
            );
            case SERVER_STATE -> readStateSnapshot(input);
            case SERVER_CHUNK -> {
                ChunkPosition position = readChunkPosition(input);
                long revision = input.readLong();
                int compressedLength = readBoundedLength(input, Protocol.MAX_FRAME_BYTES);
                byte[] compressed = input.readNBytes(compressedLength);
                if (compressed.length != compressedLength) {
                    throw new IOException("Truncated compressed chunk");
                }
                ChunkArrays arrays = decompressChunk(compressed);
                yield new ServerMessage.ChunkSnapshot(
                        position,
                        revision,
                        arrays.blocks,
                        arrays.packedLight,
                        arrays.packedDirectSkyLight
                );
            }
            case SERVER_CHUNK_UNLOAD -> new ServerMessage.ChunkUnload(readChunkPosition(input));
            case SERVER_BLOCK -> new ServerMessage.BlockUpdate(
                    input.readInt(), input.readInt(), input.readInt(), input.readShort(), input.readLong()
            );
            case SERVER_LIGHT -> {
                ChunkPosition position = readChunkPosition(input);
                long revision = input.readLong();
                int compressedLength = readBoundedLength(input, Protocol.MAX_FRAME_BYTES);
                byte[] compressed = input.readNBytes(compressedLength);
                if (compressed.length != compressedLength) {
                    throw new IOException("Truncated compressed lighting");
                }
                LightArrays arrays = decompressLight(compressed);
                yield new ServerMessage.ChunkLightUpdate(
                        position,
                        revision,
                        arrays.packedLight,
                        arrays.packedDirectSkyLight
                );
            }
            case SERVER_PLAYER_LEFT -> new ServerMessage.PlayerLeft(input.readLong());
            case SERVER_REJECTED -> new ServerMessage.Rejected(readString(input, 1024));
            default -> throw new IOException("Unknown server message type");
        };
    }

    private static ServerMessage.StateSnapshot readStateSnapshot(DataInputStream input) throws IOException {
        long serverTick = input.readLong();
        long acknowledgedSequence = input.readLong();
        int playerCount = input.readUnsignedShort();
        if (playerCount > 256) {
            throw new IOException("Invalid player count: " + playerCount);
        }
        List<NetworkPlayerState> players = new ArrayList<>(playerCount);
        for (int index = 0; index < playerCount; index++) {
            players.add(readPlayerState(input));
        }
        int hotbarLength = input.readUnsignedByte();
        if (hotbarLength > 9) {
            throw new IOException("Invalid hotbar length: " + hotbarLength);
        }
        String[] hotbar = new String[hotbarLength];
        for (int index = 0; index < hotbarLength; index++) {
            String stableId = readString(input, 128);
            hotbar[index] = stableId.isEmpty() ? null : stableId;
        }
        return new ServerMessage.StateSnapshot(
                serverTick,
                acknowledgedSequence,
                players,
                hotbar,
                input.readUnsignedByte()
        );
    }

    private static void writePlayerState(DataOutputStream output, NetworkPlayerState state) throws IOException {
        output.writeLong(state.playerId());
        writeString(output, state.name(), Protocol.MAX_PLAYER_NAME_BYTES);
        Vector3f position = state.position();
        output.writeFloat(position.x);
        output.writeFloat(position.y);
        output.writeFloat(position.z);
        output.writeFloat(state.yaw());
        output.writeFloat(state.pitch());
        output.writeFloat(state.verticalVelocity());
        output.writeBoolean(state.grounded());
        output.writeBoolean(state.noclip());
    }

    private static NetworkPlayerState readPlayerState(DataInputStream input) throws IOException {
        return new NetworkPlayerState(
                input.readLong(),
                readString(input, Protocol.MAX_PLAYER_NAME_BYTES),
                new Vector3f(input.readFloat(), input.readFloat(), input.readFloat()),
                input.readFloat(),
                input.readFloat(),
                input.readFloat(),
                input.readBoolean(),
                input.readBoolean()
        );
    }

    private static void writePlayerInput(DataOutputStream output, PlayerInput input) throws IOException {
        int flags = 0;
        flags |= input.controlsEnabled() ? 1 : 0;
        flags |= input.moveForward() ? 1 << 1 : 0;
        flags |= input.moveBackward() ? 1 << 2 : 0;
        flags |= input.moveLeft() ? 1 << 3 : 0;
        flags |= input.moveRight() ? 1 << 4 : 0;
        flags |= input.moveUp() ? 1 << 5 : 0;
        flags |= input.moveDown() ? 1 << 6 : 0;
        flags |= input.jump() ? 1 << 7 : 0;
        flags |= input.sprint() ? 1 << 8 : 0;
        flags |= input.toggleNoclip() ? 1 << 9 : 0;
        flags |= input.breakBlock() ? 1 << 10 : 0;
        flags |= input.placeBlock() ? 1 << 11 : 0;
        output.writeShort(flags);
        output.writeFloat(input.mouseDeltaX());
        output.writeFloat(input.mouseDeltaY());
        output.writeInt(input.scrollDelta());
    }

    private static PlayerInput readPlayerInput(DataInputStream input) throws IOException {
        int flags = input.readUnsignedShort();
        float mouseDeltaX = input.readFloat();
        float mouseDeltaY = input.readFloat();
        int scrollDelta = input.readInt();
        return new PlayerInput(
                flag(flags, 0), flag(flags, 1), flag(flags, 2), flag(flags, 3),
                flag(flags, 4), flag(flags, 5), flag(flags, 6), flag(flags, 7),
                flag(flags, 8), flag(flags, 9), flag(flags, 10), flag(flags, 11),
                mouseDeltaX, mouseDeltaY, scrollDelta
        );
    }

    private static byte[] compressChunk(
            short[] blocks,
            int[] packedLight,
            byte[] packedDirectSkyLight
    ) throws IOException {
        if (packedLight.length != ChunkLighting.packedIntCount()) {
            throw new IOException("Invalid packed light length: " + packedLight.length);
        }
        validatePackedDirectSkyLength(packedDirectSkyLight);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try (DeflaterOutputStream compressed = new DeflaterOutputStream(bytes, deflater);
             DataOutputStream output = new DataOutputStream(compressed)) {
            for (short block : blocks) {
                output.writeShort(block);
            }
            for (int value : packedLight) {
                output.writeInt(value);
            }
            output.write(packedDirectSkyLight);
        }
        return bytes.toByteArray();
    }

    private static ChunkArrays decompressChunk(byte[] compressed) throws IOException {
        try (DataInputStream input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressed)))) {
            short[] blocks = new short[Chunk.TOTAL_BLOCKS];
            for (int index = 0; index < blocks.length; index++) {
                blocks[index] = input.readShort();
            }
            int[] packedLight = new int[ChunkLighting.packedIntCount()];
            for (int index = 0; index < packedLight.length; index++) {
                packedLight[index] = input.readInt();
            }
            byte[] packedDirectSkyLight = readPackedDirectSky(input);
            if (input.read() != -1) {
                throw new IOException("Compressed chunk contains trailing data");
            }
            return new ChunkArrays(blocks, packedLight, packedDirectSkyLight);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid compressed chunk", exception);
        }
    }

    private static byte[] compressLight(int[] packedLight, byte[] packedDirectSkyLight) throws IOException {
        if (packedLight.length != ChunkLighting.packedIntCount()) {
            throw new IOException("Invalid packed light length: " + packedLight.length);
        }
        validatePackedDirectSkyLength(packedDirectSkyLight);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.BEST_SPEED);
        try (DeflaterOutputStream compressed = new DeflaterOutputStream(bytes, deflater);
             DataOutputStream output = new DataOutputStream(compressed)) {
            for (int value : packedLight) {
                output.writeInt(value);
            }
            output.write(packedDirectSkyLight);
        }
        return bytes.toByteArray();
    }

    private static LightArrays decompressLight(byte[] compressed) throws IOException {
        try (DataInputStream input = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(compressed)))) {
            int[] packedLight = new int[ChunkLighting.packedIntCount()];
            for (int index = 0; index < packedLight.length; index++) {
                packedLight[index] = input.readInt();
            }
            byte[] packedDirectSkyLight = readPackedDirectSky(input);
            if (input.read() != -1) {
                throw new IOException("Compressed lighting contains trailing data");
            }
            return new LightArrays(packedLight, packedDirectSkyLight);
        } catch (RuntimeException exception) {
            throw new IOException("Invalid compressed lighting", exception);
        }
    }

    private static byte[] readPackedDirectSky(DataInputStream input) throws IOException {
        byte[] packedDirectSkyLight = input.readNBytes(Chunk.packedDirectSkyByteCount());
        if (packedDirectSkyLight.length != Chunk.packedDirectSkyByteCount()) {
            throw new IOException("Truncated packed direct skylight");
        }
        return packedDirectSkyLight;
    }

    private static void validatePackedDirectSkyLength(byte[] packedDirectSkyLight) throws IOException {
        if (packedDirectSkyLight.length != Chunk.packedDirectSkyByteCount()) {
            throw new IOException("Invalid packed direct skylight length: " + packedDirectSkyLight.length);
        }
    }

    private static void writeChunkPosition(DataOutputStream output, ChunkPosition position) throws IOException {
        output.writeInt(position.x());
        output.writeInt(position.y());
        output.writeInt(position.z());
    }

    private static ChunkPosition readChunkPosition(DataInputStream input) throws IOException {
        return new ChunkPosition(input.readInt(), input.readInt(), input.readInt());
    }

    private static byte[] encode(Encoder encoder) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            encoder.write(output);
        }
        byte[] result = bytes.toByteArray();
        if (result.length > Protocol.MAX_FRAME_BYTES) {
            throw new IOException("Encoded message exceeds maximum frame size");
        }
        return result;
    }

    private static DataInputStream input(byte[] payload) throws IOException {
        if (payload.length == 0 || payload.length > Protocol.MAX_FRAME_BYTES) {
            throw new IOException("Invalid message size: " + payload.length);
        }
        return new DataInputStream(new ByteArrayInputStream(payload));
    }

    private static void writeString(DataOutputStream output, String value, int maxBytes) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new IOException("String exceeds maximum encoded length");
        }
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input, int maxBytes) throws IOException {
        int length = input.readUnsignedShort();
        if (length > maxBytes) {
            throw new IOException("String exceeds maximum encoded length");
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("Truncated string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int readBoundedLength(DataInputStream input, int max) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > max) {
            throw new IOException("Invalid encoded length: " + length);
        }
        return length;
    }

    private static void requireFullyConsumed(DataInputStream input) throws IOException {
        if (input.available() != 0) {
            throw new IOException("Message contains trailing data");
        }
    }

    private static boolean flag(int flags, int bit) {
        return (flags & (1 << bit)) != 0;
    }

    @FunctionalInterface
    private interface Encoder {
        void write(DataOutputStream output) throws IOException;
    }

    private record ChunkArrays(short[] blocks, int[] packedLight, byte[] packedDirectSkyLight) {
    }

    private record LightArrays(int[] packedLight, byte[] packedDirectSkyLight) {
    }
}
