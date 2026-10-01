package org.weaw.persistence;

import org.weaw.game.Chunk;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.utils.BlockCatalog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

final class ChunkEditCodec {
    private static final int MAGIC = 0x56584348; // VXCH
    private static final int VERSION = 1;

    byte[] encode(ChunkPosition position, Map<Integer, Short> edits, BlockCatalog catalog) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(buffer)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeInt(position.x());
            output.writeInt(position.y());
            output.writeInt(position.z());
            output.writeInt(edits.size());
            for (Map.Entry<Integer, Short> entry : new TreeMap<>(edits).entrySet()) {
                if (entry.getKey() < 0 || entry.getKey() >= Chunk.TOTAL_BLOCKS) {
                    throw new IOException("Invalid local block index: " + entry.getKey());
                }
                output.writeInt(entry.getKey());
                output.writeUTF(catalog.getStableId(entry.getValue()));
            }
        }
        return buffer.toByteArray();
    }

    DecodedChunk decode(byte[] bytes, BlockCatalog catalog) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != MAGIC) {
                throw new IOException("Invalid chunk edit magic");
            }
            int version = input.readInt();
            if (version != VERSION) {
                throw new IOException("Unsupported chunk edit version: " + version);
            }
            ChunkPosition position = new ChunkPosition(input.readInt(), input.readInt(), input.readInt());
            int count = input.readInt();
            if (count < 0 || count > Chunk.TOTAL_BLOCKS) {
                throw new IOException("Invalid chunk edit count: " + count);
            }
            Map<Integer, Short> edits = new LinkedHashMap<>();
            for (int index = 0; index < count; index++) {
                int localIndex = input.readInt();
                if (localIndex < 0 || localIndex >= Chunk.TOTAL_BLOCKS || edits.containsKey(localIndex)) {
                    throw new IOException("Invalid or duplicate local block index: " + localIndex);
                }
                String stableId = input.readUTF();
                if (catalog.getBlock(stableId) == null) {
                    throw new IOException("Unknown block stable id: " + stableId);
                }
                edits.put(localIndex, catalog.getRuntimeId(stableId));
            }
            if (input.read() != -1) {
                throw new IOException("Trailing bytes in chunk edit object");
            }
            return new DecodedChunk(position, Map.copyOf(edits));
        }
    }

    record DecodedChunk(ChunkPosition position, Map<Integer, Short> edits) {
    }
}
