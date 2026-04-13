package org.weaw.game;

import org.weaw.game.utils.BlockDefinition;
import org.weaw.game.utils.Blocks;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChunkManager {
    private final Map<ChunkPosition, Chunk> chunks = new LinkedHashMap<>();
    private final Map<ChunkPosition, ChunkMeshData> chunkMeshes = new LinkedHashMap<>();
    private final Set<ChunkPosition> queuedChunks = new HashSet<>();
    private Map<ChunkPosition, ChunkUpload> chunkUploadsSnapshot = Map.of();
    private long chunkUploadsVersion;
    private boolean chunkUploadsDirty = true;

    public synchronized void addChunk(Chunk chunk) {
        chunks.put(ChunkPosition.fromChunk(chunk), chunk);
        markChunkUploadsDirty();
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
        markChunkUploadsDirty();
    }

    public synchronized void unloadChunk(ChunkPosition position) {
        chunks.remove(position);
        chunkMeshes.remove(position);
        queuedChunks.remove(position);
        markChunkUploadsDirty();
    }

    public synchronized long getChunkUploadsVersion() {
        return chunkUploadsVersion;
    }

    public synchronized Map<ChunkPosition, ChunkUpload> snapshotChunkUploads() {
        if (!chunkUploadsDirty) {
            return chunkUploadsSnapshot;
        }

        Map<ChunkPosition, ChunkUpload> snapshot = new LinkedHashMap<>();
        for (Map.Entry<ChunkPosition, Chunk> entry : chunks.entrySet()) {
            ChunkMeshData meshData = chunkMeshes.get(entry.getKey());
            if (meshData != null) {
                snapshot.put(entry.getKey(), new ChunkUpload(entry.getKey(), entry.getValue(), meshData));
            }
        }
        chunkUploadsSnapshot = snapshot;
        chunkUploadsDirty = false;
        return chunkUploadsSnapshot;
    }

    private void markChunkUploadsDirty() {
        chunkUploadsVersion++;
        chunkUploadsDirty = true;
    }

    public record ChunkPosition(int x, int y, int z) {
        public static ChunkPosition fromChunk(Chunk chunk) {
            return new ChunkPosition(chunk.getPosition().x, chunk.getPosition().y, chunk.getPosition().z);
        }
    }

    public record ChunkUpload(ChunkPosition position, Chunk chunk, ChunkMeshData meshData) {
    }
}
