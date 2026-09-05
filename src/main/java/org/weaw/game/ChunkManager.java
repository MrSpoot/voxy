package org.weaw.game;

import org.weaw.game.utils.BlockDefinition;
import org.weaw.game.utils.BlockCatalog;
import org.weaw.game.utils.BlockRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ChunkManager {
    private static final int MAX_RETAINED_UPLOAD_DELTAS = 4096;
    public static final int LIGHT_BOUNDARY_LOW_X = 1;
    public static final int LIGHT_BOUNDARY_HIGH_X = 1 << 1;
    public static final int LIGHT_BOUNDARY_LOW_Y = 1 << 2;
    public static final int LIGHT_BOUNDARY_HIGH_Y = 1 << 3;
    public static final int LIGHT_BOUNDARY_LOW_Z = 1 << 4;
    public static final int LIGHT_BOUNDARY_HIGH_Z = 1 << 5;
    public static final int LIGHT_BOUNDARY_ALL = (1 << 6) - 1;
    private final BlockCatalog blockCatalog;

    private final Map<ChunkPosition, Chunk> chunks = new LinkedHashMap<>();
    private final Map<ChunkPosition, ChunkMeshData> chunkMeshes = new LinkedHashMap<>();
    private final Map<ChunkPosition, ChunkUpload> chunkUploads = new LinkedHashMap<>();
    private final List<ChunkUploadDelta> chunkUploadDeltas = new ArrayList<>();
    private final List<ChunkLightDelta> chunkLightDeltas = new ArrayList<>();
    private final Set<ChunkPosition> queuedChunks = new HashSet<>();
    private final Map<ChunkPosition, Long> residentBytesByChunk = new LinkedHashMap<>();
    private final Map<ChunkPosition, Boolean> compactLightingByChunk = new LinkedHashMap<>();
    private volatile Map<ChunkPosition, ChunkUpload> chunkUploadsSnapshot = Map.of();
    private long chunkUploadsSnapshotVersion = -1L;

    private long chunkUploadsVersion;
    private long chunkLightVersion;
    private long firstRetainedChunkUploadVersion = 1L;
    private long firstRetainedChunkLightVersion = 1L;
    private long estimatedResidentBytes;
    private int compactLightingChunkCount;

    public ChunkManager() {
        this(BlockRegistry.getDefaultCatalog());
    }

    public ChunkManager(BlockCatalog blockCatalog) {
        this.blockCatalog = java.util.Objects.requireNonNull(blockCatalog, "blockCatalog");
    }

    public BlockCatalog getBlockCatalog() {
        return blockCatalog;
    }

    public synchronized void addChunk(Chunk chunk) {
        requireCompatibleCatalog(chunk);
        ChunkPosition position = ChunkPosition.fromChunk(chunk);
        chunks.put(position, chunk);
        refreshResidentEstimate(position);
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

    public synchronized long getEstimatedResidentBytes() {
        return estimatedResidentBytes;
    }

    public synchronized long getEstimatedResidentBytes(ChunkPosition position) {
        return residentBytesByChunk.getOrDefault(position, 0L);
    }

    public synchronized int getCompactLightingChunkCount() {
        return compactLightingChunkCount;
    }

    public synchronized int getExpandedLightingChunkCount() {
        return chunks.size() - compactLightingChunkCount;
    }

    public synchronized short getBlockAtWorld(int worldX, int worldY, int worldZ) {
        int loadedBlock = getLoadedBlockAtWorld(worldX, worldY, worldZ);
        return loadedBlock < 0 ? blockCatalog.air().getId() : (short) loadedBlock;
    }

    /** Returns an unsigned runtime id, or {@code -1} when the chunk is absent. */
    public synchronized int getLoadedBlockAtWorld(int worldX, int worldY, int worldZ) {
        int chunkX = Math.floorDiv(worldX, Chunk.SIZE);
        int chunkY = Math.floorDiv(worldY, Chunk.SIZE);
        int chunkZ = Math.floorDiv(worldZ, Chunk.SIZE);

        Chunk chunk = chunks.get(new ChunkPosition(chunkX, chunkY, chunkZ));
        if (chunk == null) {
            return -1;
        }

        int localX = Math.floorMod(worldX, Chunk.SIZE);
        int localY = Math.floorMod(worldY, Chunk.SIZE);
        int localZ = Math.floorMod(worldZ, Chunk.SIZE);
        return Short.toUnsignedInt(chunk.getBlock(localX, localY, localZ));
    }

    /** Overlays only loaded chunks onto an already initialized block region. */
    public synchronized void overlayLoadedBlocks(
            int originX,
            int originY,
            int originZ,
            int sizeX,
            int sizeY,
            int sizeZ,
            short[] destination
    ) {
        if (sizeX < 0 || sizeY < 0 || sizeZ < 0
                || destination.length < sizeX * sizeY * sizeZ) {
            throw new IllegalArgumentException("Invalid block region dimensions or destination size");
        }
        int maxX = originX + sizeX;
        int maxY = originY + sizeY;
        int maxZ = originZ + sizeZ;
        int minChunkX = Math.floorDiv(originX, Chunk.SIZE);
        int minChunkY = Math.floorDiv(originY, Chunk.SIZE);
        int minChunkZ = Math.floorDiv(originZ, Chunk.SIZE);
        int maxChunkX = Math.floorDiv(maxX - 1, Chunk.SIZE);
        int maxChunkY = Math.floorDiv(maxY - 1, Chunk.SIZE);
        int maxChunkZ = Math.floorDiv(maxZ - 1, Chunk.SIZE);

        for (int chunkY = minChunkY; chunkY <= maxChunkY; chunkY++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    Chunk chunk = chunks.get(new ChunkPosition(chunkX, chunkY, chunkZ));
                    if (chunk == null) {
                        continue;
                    }
                    int chunkOriginX = chunkX * Chunk.SIZE;
                    int chunkOriginY = chunkY * Chunk.SIZE;
                    int chunkOriginZ = chunkZ * Chunk.SIZE;
                    int fromX = Math.max(originX, chunkOriginX);
                    int fromY = Math.max(originY, chunkOriginY);
                    int fromZ = Math.max(originZ, chunkOriginZ);
                    int toX = Math.min(maxX, chunkOriginX + Chunk.SIZE);
                    int toY = Math.min(maxY, chunkOriginY + Chunk.SIZE);
                    int toZ = Math.min(maxZ, chunkOriginZ + Chunk.SIZE);
                    for (int worldY = fromY; worldY < toY; worldY++) {
                        int destinationY = (worldY - originY) * sizeX * sizeZ;
                        int localY = worldY - chunkOriginY;
                        for (int worldZ = fromZ; worldZ < toZ; worldZ++) {
                            int destinationZ = destinationY + (worldZ - originZ) * sizeX;
                            int localZ = worldZ - chunkOriginZ;
                            for (int worldX = fromX; worldX < toX; worldX++) {
                                destination[destinationZ + worldX - originX] = chunk.getBlock(
                                        worldX - chunkOriginX,
                                        localY,
                                        localZ
                                );
                            }
                        }
                    }
                }
            }
        }
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
        refreshResidentEstimate(new ChunkPosition(chunkX, chunkY, chunkZ));
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
        refreshResidentEstimate(new ChunkPosition(chunkX, chunkY, chunkZ));
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
        refreshResidentEstimate(new ChunkPosition(chunkX, chunkY, chunkZ));
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
        requireCompatibleCatalog(chunk);
        ChunkPosition position = ChunkPosition.fromChunk(chunk);
        chunks.put(position, chunk);
        chunkMeshes.put(position, meshData);
        queuedChunks.remove(position);

        ChunkUpload upload = new ChunkUpload(position, chunk, meshData);
        ChunkUpload previousUpload = chunkUploads.put(position, upload);
        refreshResidentEstimate(position);
        recordChunkUploadDelta(
                previousUpload == null ? ChunkUploadChangeType.ADDED : ChunkUploadChangeType.UPDATED,
                position,
                upload
        );
        recordChunkLightDelta(ChunkUploadChangeType.UPDATED, position, LIGHT_BOUNDARY_ALL, false);
    }

    public synchronized boolean publishRemeshedChunk(ChunkPosition position, ChunkMeshData meshData) {
        Chunk existingChunk = chunks.get(position);
        if (existingChunk == null) {
            return false;
        }

        chunkMeshes.put(position, meshData);
        ChunkUpload upload = new ChunkUpload(position, existingChunk, meshData);
        ChunkUpload previousUpload = chunkUploads.put(position, upload);
        refreshResidentEstimate(position);
        recordChunkUploadDelta(
                previousUpload == null ? ChunkUploadChangeType.ADDED : ChunkUploadChangeType.UPDATED,
                position,
                upload
        );
        return true;
    }

    public synchronized void unloadChunk(ChunkPosition position) {
        chunks.remove(position);
        chunkMeshes.remove(position);
        queuedChunks.remove(position);
        removeResidentEstimate(position);

        if (chunkUploads.remove(position) != null) {
            recordChunkUploadDelta(ChunkUploadChangeType.REMOVED, position, null);
            recordChunkLightDelta(ChunkUploadChangeType.REMOVED, position, LIGHT_BOUNDARY_ALL, false);
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

    public synchronized Map<ChunkPosition, ChunkUpload> snapshotChunkUploads() {
        if (chunkUploadsSnapshotVersion != chunkUploadsVersion) {
            chunkUploadsSnapshot = Collections.unmodifiableMap(new LinkedHashMap<>(chunkUploads));
            chunkUploadsSnapshotVersion = chunkUploadsVersion;
        }
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
        Map<ChunkPosition, Integer> updates = new LinkedHashMap<>(positions.size());
        for (ChunkPosition position : positions) {
            updates.put(position, LIGHT_BOUNDARY_ALL);
        }
        return markChunksLightUpdated(updates, false);
    }

    public synchronized int markChunksLightUpdated(Map<ChunkPosition, Integer> updates, boolean priority) {
        int updatedChunkCount = 0;
        for (Map.Entry<ChunkPosition, Integer> entry : updates.entrySet()) {
            ChunkPosition position = entry.getKey();
            if (!chunks.containsKey(position)) {
                continue;
            }
            refreshResidentEstimate(position);
            recordChunkLightDelta(
                    ChunkUploadChangeType.UPDATED,
                    position,
                    entry.getValue() & LIGHT_BOUNDARY_ALL,
                    priority
            );
            updatedChunkCount++;
        }
        return updatedChunkCount;
    }

    public synchronized Chunk copyChunk(ChunkPosition position) {
        Chunk chunk = chunks.get(position);
        return chunk == null ? null : chunk.copy();
    }

    public synchronized Chunk copyChunkForMeshing(ChunkPosition position) {
        Chunk chunk = chunks.get(position);
        return chunk == null ? null : chunk.copyForMeshing();
    }

    public static long estimateResidentBytes(Chunk chunk, ChunkMeshData meshData) {
        long meshBytes = meshData == null ? 0L : meshData.estimateRetainedBytes();
        return 256L + chunk.estimateRetainedBytes() + meshBytes;
    }

    private void refreshResidentEstimate(ChunkPosition position) {
        Chunk chunk = chunks.get(position);
        if (chunk == null) {
            removeResidentEstimate(position);
            return;
        }

        long nextBytes = estimateResidentBytes(chunk, chunkMeshes.get(position));
        Long previousBytes = residentBytesByChunk.put(position, nextBytes);
        estimatedResidentBytes += nextBytes - (previousBytes == null ? 0L : previousBytes);

        boolean compact = chunk.getLighting().isCompact();
        Boolean previousCompact = compactLightingByChunk.put(position, compact);
        if (previousCompact == null) {
            if (compact) {
                compactLightingChunkCount++;
            }
        } else if (previousCompact != compact) {
            compactLightingChunkCount += compact ? 1 : -1;
        }
    }

    private void removeResidentEstimate(ChunkPosition position) {
        Long removedBytes = residentBytesByChunk.remove(position);
        if (removedBytes != null) {
            estimatedResidentBytes = Math.max(0L, estimatedResidentBytes - removedBytes);
        }
        Boolean wasCompact = compactLightingByChunk.remove(position);
        if (Boolean.TRUE.equals(wasCompact)) {
            compactLightingChunkCount = Math.max(0, compactLightingChunkCount - 1);
        }
    }

    private void recordChunkUploadDelta(ChunkUploadChangeType changeType, ChunkPosition position, ChunkUpload upload) {
        chunkUploadsVersion++;
        chunkUploadDeltas.add(new ChunkUploadDelta(chunkUploadsVersion, changeType, position, upload));
        trimRetainedChunkUploadDeltas();
    }

    private void recordChunkLightDelta(
            ChunkUploadChangeType changeType,
            ChunkPosition position,
            int boundaryMask,
            boolean priority
    ) {
        chunkLightVersion++;
        chunkLightDeltas.add(new ChunkLightDelta(chunkLightVersion, changeType, position, boundaryMask, priority));
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
            ChunkPosition position,
            int boundaryMask,
            boolean priority
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

    private void requireCompatibleCatalog(Chunk chunk) {
        if (chunk.getBlockCatalog() != blockCatalog) {
            throw new IllegalArgumentException("Chunk belongs to a different block catalogue");
        }
    }
}
