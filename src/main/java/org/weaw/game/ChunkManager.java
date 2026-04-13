package org.weaw.game;

import org.weaw.game.utils.BlockDefinition;
import org.weaw.game.utils.Blocks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChunkManager {
    private static final int MAX_RETAINED_UPLOAD_DELTAS = 4096;

    private final Map<ChunkPosition, Chunk> chunks = new LinkedHashMap<>();
    private final Map<ChunkPosition, ChunkMeshData> chunkMeshes = new LinkedHashMap<>();
    private final Map<ChunkPosition, ChunkUpload> chunkUploads = new LinkedHashMap<>();
    private final List<ChunkUploadDelta> chunkUploadDeltas = new ArrayList<>();
    private final Set<ChunkPosition> queuedChunks = new HashSet<>();

    private long chunkUploadsVersion;
    private long firstRetainedChunkUploadVersion = 1L;

    public synchronized void addChunk(Chunk chunk) {
        chunks.put(ChunkPosition.fromChunk(chunk), chunk);
    }

    public synchronized Chunk getChunk(int chunkX, int chunkY, int chunkZ) {
        return chunks.get(new ChunkPosition(chunkX, chunkY, chunkZ));
    }

    public synchronized boolean hasChunk(int chunkX, int chunkY, int chunkZ) {
        return chunks.containsKey(new ChunkPosition(chunkX, chunkY, chunkZ));
    }

    public synchronized List<ChunkPosition> snapshotLoadedChunkPositions() {
        return List.copyOf(chunks.keySet());
    }

    public synchronized int getChunkCount() {
        return chunks.size();
    }

    public synchronized int getQueuedChunkCount() {
        return queuedChunks.size();
    }

    public synchronized short getBlockAtWorld(int worldX, int worldY, int worldZ) {
        int chunkX = Math.floorDiv(worldX, Chunk.SIZE);
        int chunkY = Math.floorDiv(worldY, Chunk.SIZE);
        int chunkZ = Math.floorDiv(worldZ, Chunk.SIZE);

        Chunk chunk = chunks.get(new ChunkPosition(chunkX, chunkY, chunkZ));
        if (chunk == null) {
            return Blocks.AIR.getId();
        }

        int localX = Math.floorMod(worldX, Chunk.SIZE);
        int localY = Math.floorMod(worldY, Chunk.SIZE);
        int localZ = Math.floorMod(worldZ, Chunk.SIZE);
        return chunk.getBlock(localX, localY, localZ);
    }

    public synchronized void setBlockAtWorld(int worldX, int worldY, int worldZ, BlockDefinition block) {
        int chunkX = Math.floorDiv(worldX, Chunk.SIZE);
        int chunkY = Math.floorDiv(worldY, Chunk.SIZE);
        int chunkZ = Math.floorDiv(worldZ, Chunk.SIZE);

        Chunk chunk = chunks.get(new ChunkPosition(chunkX, chunkY, chunkZ));
        if (chunk == null) {
            throw new IllegalArgumentException("No chunk loaded at world position: " + worldX + ", " + worldY + ", " + worldZ);
        }

        int localX = Math.floorMod(worldX, Chunk.SIZE);
        int localY = Math.floorMod(worldY, Chunk.SIZE);
        int localZ = Math.floorMod(worldZ, Chunk.SIZE);
        chunk.setBlock(localX, localY, localZ, block);
    }

    public synchronized boolean tryMarkChunkQueued(ChunkPosition position) {
        if (chunks.containsKey(position) || queuedChunks.contains(position)) {
            return false;
        }

        queuedChunks.add(position);
        return true;
    }

    public synchronized void clearQueuedChunk(ChunkPosition position) {
        queuedChunks.remove(position);
    }

    public synchronized void publishBuiltChunk(Chunk chunk, ChunkMeshData meshData) {
        ChunkPosition position = ChunkPosition.fromChunk(chunk);
        chunks.put(position, chunk);
        chunkMeshes.put(position, meshData);
        queuedChunks.remove(position);

        ChunkUpload upload = new ChunkUpload(position, chunk, meshData);
        ChunkUpload previousUpload = chunkUploads.put(position, upload);
        recordChunkUploadDelta(
                previousUpload == null ? ChunkUploadChangeType.ADDED : ChunkUploadChangeType.UPDATED,
                position,
                upload
        );
    }

    public synchronized void unloadChunk(ChunkPosition position) {
        chunks.remove(position);
        chunkMeshes.remove(position);
        queuedChunks.remove(position);

        if (chunkUploads.remove(position) != null) {
            recordChunkUploadDelta(ChunkUploadChangeType.REMOVED, position, null);
        }
    }

    public synchronized long getChunkUploadsVersion() {
        return chunkUploadsVersion;
    }

    public synchronized Map<ChunkPosition, ChunkUpload> snapshotChunkUploads() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(chunkUploads));
    }

    public synchronized ChunkUploadSync snapshotChunkUploadSync(long lastSeenVersion) {
        if (lastSeenVersion == chunkUploadsVersion) {
            return new ChunkUploadSync(chunkUploadsVersion, false, Map.of(), List.of());
        }

        boolean requiresFullSnapshot = lastSeenVersion < (firstRetainedChunkUploadVersion - 1);
        if (requiresFullSnapshot) {
            return new ChunkUploadSync(
                    chunkUploadsVersion,
                    true,
                    Collections.unmodifiableMap(new LinkedHashMap<>(chunkUploads)),
                    List.of()
            );
        }

        if (chunkUploadDeltas.isEmpty()) {
            return new ChunkUploadSync(chunkUploadsVersion, false, Map.of(), List.of());
        }

        int startIndex = 0;
        while (startIndex < chunkUploadDeltas.size()
                && chunkUploadDeltas.get(startIndex).version() <= lastSeenVersion) {
            startIndex++;
        }

        if (startIndex >= chunkUploadDeltas.size()) {
            return new ChunkUploadSync(chunkUploadsVersion, false, Map.of(), List.of());
        }

        return new ChunkUploadSync(
                chunkUploadsVersion,
                false,
                Map.of(),
                List.copyOf(chunkUploadDeltas.subList(startIndex, chunkUploadDeltas.size()))
        );
    }

    private void recordChunkUploadDelta(ChunkUploadChangeType changeType, ChunkPosition position, ChunkUpload upload) {
        chunkUploadsVersion++;
        chunkUploadDeltas.add(new ChunkUploadDelta(chunkUploadsVersion, changeType, position, upload));
        trimRetainedChunkUploadDeltas();
    }

    private void trimRetainedChunkUploadDeltas() {
        int excess = chunkUploadDeltas.size() - MAX_RETAINED_UPLOAD_DELTAS;
        if (excess <= 0) {
            if (!chunkUploadDeltas.isEmpty()) {
                firstRetainedChunkUploadVersion = chunkUploadDeltas.get(0).version();
            }
            return;
        }

        chunkUploadDeltas.subList(0, excess).clear();
        firstRetainedChunkUploadVersion = chunkUploadDeltas.isEmpty()
                ? (chunkUploadsVersion + 1)
                : chunkUploadDeltas.get(0).version();
    }

    public record ChunkPosition(int x, int y, int z) {
        public static ChunkPosition fromChunk(Chunk chunk) {
            return new ChunkPosition(chunk.getPosition().x, chunk.getPosition().y, chunk.getPosition().z);
        }
    }

    public record ChunkUpload(ChunkPosition position, Chunk chunk, ChunkMeshData meshData) {
    }

    public record ChunkUploadDelta(
            long version,
            ChunkUploadChangeType changeType,
            ChunkPosition position,
            ChunkUpload upload
    ) {
    }

    public record ChunkUploadSync(
            long version,
            boolean requiresFullSnapshot,
            Map<ChunkPosition, ChunkUpload> fullSnapshot,
            List<ChunkUploadDelta> deltas
    ) {
    }

    public enum ChunkUploadChangeType {
        ADDED,
        UPDATED,
        REMOVED
    }
}
