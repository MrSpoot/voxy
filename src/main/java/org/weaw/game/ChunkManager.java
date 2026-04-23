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
    private final List<ChunkLightDelta> chunkLightDeltas = new ArrayList<>();
    private final Set<ChunkPosition> queuedChunks = new HashSet<>();
    private volatile Map<ChunkPosition, ChunkUpload> chunkUploadsSnapshot = Map.of();

    private long chunkUploadsVersion;
    private long chunkLightVersion;
    private long firstRetainedChunkUploadVersion = 1L;
    private long firstRetainedChunkLightVersion = 1L;

    public synchronized void addChunk(Chunk chunk) {
        chunks.put(ChunkPosition.fromChunk(chunk), chunk);
    }

    public synchronized Chunk getChunk(int chunkX, int chunkY, int chunkZ) {
        return chunks.get(new ChunkPosition(chunkX, chunkY, chunkZ));
    }

    public synchronized boolean hasChunk(int chunkX, int chunkY, int chunkZ) {
        return chunks.containsKey(new ChunkPosition(chunkX, chunkY, chunkZ));
    }

    public synchronized boolean hasChunk(ChunkPosition position) {
        return chunks.containsKey(position);
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

    public synchronized short getPackedLightAtWorld(int worldX, int worldY, int worldZ) {
        int chunkX = Math.floorDiv(worldX, Chunk.SIZE);
        int chunkY = Math.floorDiv(worldY, Chunk.SIZE);
        int chunkZ = Math.floorDiv(worldZ, Chunk.SIZE);

        Chunk chunk = chunks.get(new ChunkPosition(chunkX, chunkY, chunkZ));
        if (chunk == null) {
            return 0;
        }

        int localX = Math.floorMod(worldX, Chunk.SIZE);
        int localY = Math.floorMod(worldY, Chunk.SIZE);
        int localZ = Math.floorMod(worldZ, Chunk.SIZE);
        return chunk.getPackedLight(localX, localY, localZ);
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

    public synchronized void setPackedLightAtWorld(int worldX, int worldY, int worldZ, short packedLight) {
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
        chunk.setPackedLight(localX, localY, localZ, packedLight);
    }

    public synchronized void setLightAtWorld(int worldX, int worldY, int worldZ, int red, int green, int blue, int sky) {
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
        chunk.setLight(localX, localY, localZ, red, green, blue, sky);
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
        recordChunkLightDelta(ChunkUploadChangeType.UPDATED, position);
        refreshChunkUploadsSnapshot();
    }

    public synchronized boolean publishRemeshedChunk(ChunkPosition position, ChunkMeshData meshData) {
        Chunk existingChunk = chunks.get(position);
        if (existingChunk == null) {
            return false;
        }

        chunkMeshes.put(position, meshData);
        ChunkUpload upload = new ChunkUpload(position, existingChunk, meshData);
        ChunkUpload previousUpload = chunkUploads.put(position, upload);
        recordChunkUploadDelta(
                previousUpload == null ? ChunkUploadChangeType.ADDED : ChunkUploadChangeType.UPDATED,
                position,
                upload
        );
        recordChunkLightDelta(ChunkUploadChangeType.UPDATED, position);
        refreshChunkUploadsSnapshot();
        return true;
    }

    public synchronized void unloadChunk(ChunkPosition position) {
        chunks.remove(position);
        chunkMeshes.remove(position);
        queuedChunks.remove(position);

        if (chunkUploads.remove(position) != null) {
            recordChunkUploadDelta(ChunkUploadChangeType.REMOVED, position, null);
            recordChunkLightDelta(ChunkUploadChangeType.REMOVED, position);
            refreshChunkUploadsSnapshot();
        }
    }

    public synchronized long getChunkUploadsVersion() {
        return chunkUploadsVersion;
    }

    public synchronized long getChunkLightVersion() {
        return chunkLightVersion;
    }

    public synchronized ChunkUpload getChunkUpload(ChunkPosition position) {
        return chunkUploads.get(position);
    }

    public Map<ChunkPosition, ChunkUpload> snapshotChunkUploads() {
        return chunkUploadsSnapshot;
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

    public synchronized ChunkLightSync snapshotChunkLightSync(long lastSeenVersion) {
        if (lastSeenVersion == chunkLightVersion) {
            return new ChunkLightSync(chunkLightVersion, false, Set.of(), List.of());
        }

        boolean requiresFullSnapshot = lastSeenVersion < (firstRetainedChunkLightVersion - 1);
        if (requiresFullSnapshot) {
            return new ChunkLightSync(
                    chunkLightVersion,
                    true,
                    Set.copyOf(chunks.keySet()),
                    List.of()
            );
        }

        if (chunkLightDeltas.isEmpty()) {
            return new ChunkLightSync(chunkLightVersion, false, Set.of(), List.of());
        }

        int startIndex = 0;
        while (startIndex < chunkLightDeltas.size()
                && chunkLightDeltas.get(startIndex).version() <= lastSeenVersion) {
            startIndex++;
        }

        if (startIndex >= chunkLightDeltas.size()) {
            return new ChunkLightSync(chunkLightVersion, false, Set.of(), List.of());
        }

        return new ChunkLightSync(
                chunkLightVersion,
                false,
                Set.of(),
                List.copyOf(chunkLightDeltas.subList(startIndex, chunkLightDeltas.size()))
        );
    }

    public synchronized int markChunksLightUpdated(Set<ChunkPosition> positions) {
        int updatedChunkCount = 0;
        for (ChunkPosition position : positions) {
            if (!chunks.containsKey(position)) {
                continue;
            }
            recordChunkLightDelta(ChunkUploadChangeType.UPDATED, position);
            updatedChunkCount++;
        }
        return updatedChunkCount;
    }

    public synchronized Chunk copyChunk(ChunkPosition position) {
        Chunk chunk = chunks.get(position);
        return chunk == null ? null : chunk.copy();
    }

    private void recordChunkUploadDelta(ChunkUploadChangeType changeType, ChunkPosition position, ChunkUpload upload) {
        chunkUploadsVersion++;
        chunkUploadDeltas.add(new ChunkUploadDelta(chunkUploadsVersion, changeType, position, upload));
        trimRetainedChunkUploadDeltas();
    }

    private void recordChunkLightDelta(ChunkUploadChangeType changeType, ChunkPosition position) {
        chunkLightVersion++;
        chunkLightDeltas.add(new ChunkLightDelta(chunkLightVersion, changeType, position));
        trimRetainedChunkLightDeltas();
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

    private void trimRetainedChunkLightDeltas() {
        int excess = chunkLightDeltas.size() - MAX_RETAINED_UPLOAD_DELTAS;
        if (excess <= 0) {
            if (!chunkLightDeltas.isEmpty()) {
                firstRetainedChunkLightVersion = chunkLightDeltas.get(0).version();
            }
            return;
        }

        chunkLightDeltas.subList(0, excess).clear();
        firstRetainedChunkLightVersion = chunkLightDeltas.isEmpty()
                ? (chunkLightVersion + 1)
                : chunkLightDeltas.get(0).version();
    }

    private void refreshChunkUploadsSnapshot() {
        chunkUploadsSnapshot = Collections.unmodifiableMap(new LinkedHashMap<>(chunkUploads));
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

    public record ChunkLightDelta(
            long version,
            ChunkUploadChangeType changeType,
            ChunkPosition position
    ) {
    }

    public record ChunkLightSync(
            long version,
            boolean requiresFullSnapshot,
            Set<ChunkPosition> fullSnapshot,
            List<ChunkLightDelta> deltas
    ) {
    }

    public enum ChunkUploadChangeType {
        ADDED,
        UPDATED,
        REMOVED
    }
}
