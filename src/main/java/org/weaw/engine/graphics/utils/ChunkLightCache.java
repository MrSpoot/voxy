package org.weaw.engine.graphics.utils;

import org.weaw.game.Chunk;
import org.weaw.game.ChunkManager;
import org.weaw.game.ChunkMeshData;
import org.weaw.game.ChunkManager.ChunkLightDelta;
import org.weaw.game.ChunkManager.ChunkLightSync;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.ChunkManager.ChunkUpload;
import org.weaw.game.ChunkManager.ChunkUploadChangeType;
import org.lwjgl.system.MemoryUtil;

import java.nio.IntBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;

public final class ChunkLightCache {
    private static final int INITIAL_LIGHT_ARENA_CAPACITY_INTS =
            Integer.getInteger("voxy.chunkLightArenaInitialCapacityInts", 32768);
    private static final int PADDED_DIMENSION = Chunk.SIZE + 2;
    private static final int PADDED_VOXELS = PADDED_DIMENSION * PADDED_DIMENSION * PADDED_DIMENSION;

    private final ChunkManager chunkManager;
    private final ChunkGpuMemoryBudget gpuMemoryBudget;
    private final Map<ChunkPosition, ChunkLightArena.Allocation> allocations = new LinkedHashMap<>();
    private final Set<ChunkPosition> deferredUploads = new LinkedHashSet<>();
    private ChunkLightArena lightArena;
    private IntBuffer stagingBuffer;
    private ChunkLightArena.Allocation darkAllocation;
    private long synchronizedChunkLightVersion = Long.MIN_VALUE;
    private boolean lightDataResident;
    private int profilingSynchronizeCalls;
    private int profilingFullSnapshotCount;
    private int profilingDeltaCount;
    private int profilingRefreshedAllocationCount;
    private int profilingFreedAllocationCount;
    private int profilingUploadedChunkCount;

    public ChunkLightCache(ChunkManager chunkManager) {
        this(chunkManager, new ChunkGpuMemoryBudget(Long.MAX_VALUE, Long.MAX_VALUE));
    }

    public ChunkLightCache(ChunkManager chunkManager, long maxGpuBytes) {
        this(chunkManager, new ChunkGpuMemoryBudget(maxGpuBytes, Long.MAX_VALUE));
    }

    public ChunkLightCache(ChunkManager chunkManager, ChunkGpuMemoryBudget gpuMemoryBudget) {
        this.chunkManager = chunkManager;
        this.gpuMemoryBudget = gpuMemoryBudget;
    }

    public void create() {
        if (lightArena != null) {
            return;
        }
        lightArena = new ChunkLightArena(INITIAL_LIGHT_ARENA_CAPACITY_INTS, gpuMemoryBudget);
        stagingBuffer = MemoryUtil.memAllocInt((PADDED_VOXELS + 1) / 2);
        stagingBuffer.clear();
        while (stagingBuffer.hasRemaining()) {
            stagingBuffer.put(0);
        }
        stagingBuffer.flip();
        darkAllocation = lightArena.upload(stagingBuffer, null);
    }

    public void synchronize(boolean requiresLightData) {
        profilingSynchronizeCalls++;
        if (!requiresLightData) {
            lightDataResident = false;
            return;
        }

        if (lightArena == null) {
            create();
        }

        if (!lightDataResident) {
            synchronizedChunkLightVersion = Long.MIN_VALUE;
        }

        long currentLightVersion = chunkManager.getChunkLightVersion();
        if (currentLightVersion == synchronizedChunkLightVersion) {
            lightDataResident = true;
            return;
        }

        ChunkLightSync lightSync = chunkManager.snapshotChunkLightSync(synchronizedChunkLightVersion);
        if (lightSync.requiresFullSnapshot()) {
            profilingFullSnapshotCount++;
            applyFullSnapshot(lightSync.fullSnapshot());
        } else {
            profilingDeltaCount += lightSync.deltas().size();
            for (ChunkLightDelta delta : lightSync.deltas()) {
                if (delta.changeType() == ChunkUploadChangeType.REMOVED) {
                    freeAllocation(delta.position());
                    continue;
                }
                refreshAllocation(delta.position());
            }
        }

        synchronizedChunkLightVersion = lightSync.version();
        retryDeferredUploads();
        lightDataResident = true;
    }

    public void bind() {
        if (lightArena != null) {
            lightArena.bind();
        }
    }

    public int getLightOffsetInts(ChunkPosition position) {
        ChunkLightArena.Allocation allocation = allocations.get(position);
        return allocation != null
                ? allocation.offsetInts()
                : (darkAllocation != null ? darkAllocation.offsetInts() : 0);
    }

    public long getEstimatedGpuBytes() {
        return lightArena != null ? lightArena.getEstimatedGpuBytes() : 0L;
    }

    public ChunkLightCacheProfilingSnapshot consumeProfilingSnapshot() {
        ChunkLightCacheProfilingSnapshot snapshot = new ChunkLightCacheProfilingSnapshot(
                profilingSynchronizeCalls,
                profilingFullSnapshotCount,
                profilingDeltaCount,
                profilingRefreshedAllocationCount,
                profilingFreedAllocationCount,
                profilingUploadedChunkCount,
                allocations.size()
        );
        profilingSynchronizeCalls = 0;
        profilingFullSnapshotCount = 0;
        profilingDeltaCount = 0;
        profilingRefreshedAllocationCount = 0;
        profilingFreedAllocationCount = 0;
        profilingUploadedChunkCount = 0;
        return snapshot;
    }

    public void cleanup() {
        allocations.clear();
        deferredUploads.clear();
        synchronizedChunkLightVersion = Long.MIN_VALUE;
        lightDataResident = false;
        profilingSynchronizeCalls = 0;
        profilingFullSnapshotCount = 0;
        profilingDeltaCount = 0;
        profilingRefreshedAllocationCount = 0;
        profilingFreedAllocationCount = 0;
        profilingUploadedChunkCount = 0;
        if (lightArena != null) {
            lightArena.cleanup();
            lightArena = null;
        }
        darkAllocation = null;
        if (stagingBuffer != null) {
            MemoryUtil.memFree(stagingBuffer);
            stagingBuffer = null;
        }
    }

    private void applyFullSnapshot(Set<ChunkPosition> fullSnapshot) {
        Iterator<Map.Entry<ChunkPosition, ChunkLightArena.Allocation>> iterator = allocations.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ChunkPosition, ChunkLightArena.Allocation> entry = iterator.next();
            if (fullSnapshot.contains(entry.getKey())) {
                continue;
            }
            lightArena.free(entry.getValue());
            iterator.remove();
        }

        for (ChunkPosition position : fullSnapshot) {
            refreshAllocation(position);
        }
    }

    private void refreshAllocation(ChunkPosition position) {
        ChunkUpload upload = chunkManager.getChunkUpload(position);
        if (upload == null || !hasRenderableFaces(upload.meshData())) {
            freeAllocation(position);
            return;
        }

        Chunk[] neighborhood = resolveNeighborhood(position);
        if (isEntireNeighborhoodDark(neighborhood)) {
            freeAllocation(position);
            profilingRefreshedAllocationCount++;
            return;
        }

        ChunkLightArena.Allocation allocation = lightArena.upload(
                packLightDataWithChunkBorder(neighborhood),
                allocations.get(position)
        );
        if (allocation != null) {
            allocations.put(position, allocation);
            deferredUploads.remove(position);
        } else {
            allocations.remove(position);
            deferredUploads.add(position);
        }
        profilingRefreshedAllocationCount++;
        profilingUploadedChunkCount++;
    }

    private void freeAllocation(ChunkPosition position) {
        deferredUploads.remove(position);
        ChunkLightArena.Allocation allocation = allocations.remove(position);
        if (allocation != null && lightArena != null) {
            lightArena.free(allocation);
            profilingFreedAllocationCount++;
        }
    }

    private void retryDeferredUploads() {
        if (deferredUploads.isEmpty()) {
            return;
        }
        for (ChunkPosition position : Set.copyOf(deferredUploads)) {
            refreshAllocation(position);
        }
    }

    private IntBuffer packLightDataWithChunkBorder(Chunk[] neighborhood) {
        stagingBuffer.clear();

        for (int y = -1; y <= Chunk.SIZE; y++) {
            for (int z = -1; z <= Chunk.SIZE; z++) {
                for (int x = -1; x <= Chunk.SIZE; x += 2) {
                    int low = samplePackedLight(neighborhood, x, y, z) & 0xFFFF;
                    int high = 0;
                    if (x + 1 <= Chunk.SIZE) {
                        high = (samplePackedLight(neighborhood, x + 1, y, z) & 0xFFFF) << 16;
                    }
                    stagingBuffer.put(low | high);
                }
            }
        }

        stagingBuffer.flip();
        return stagingBuffer;
    }

    private boolean isEntireNeighborhoodDark(Chunk[] neighborhood) {
        for (Chunk chunk : neighborhood) {
            if (chunk != null && !chunk.getLighting().isAllDark()) {
                return false;
            }
        }
        return true;
    }

    private Chunk[] resolveNeighborhood(ChunkPosition centerPosition) {
        Chunk[] neighborhood = new Chunk[27];
        int index = 0;
        for (int offsetY = -1; offsetY <= 1; offsetY++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                for (int offsetX = -1; offsetX <= 1; offsetX++) {
                    neighborhood[index++] = chunkManager.getChunk(
                            centerPosition.x() + offsetX,
                            centerPosition.y() + offsetY,
                            centerPosition.z() + offsetZ
                    );
                }
            }
        }
        return neighborhood;
    }

    private short samplePackedLight(Chunk[] neighborhood, int localX, int localY, int localZ) {
        int chunkOffsetX = chunkOffset(localX);
        int chunkOffsetY = chunkOffset(localY);
        int chunkOffsetZ = chunkOffset(localZ);
        Chunk chunk = neighborhood[neighborhoodIndex(chunkOffsetX, chunkOffsetY, chunkOffsetZ)];
        if (chunk == null) {
            return 0;
        }

        return chunk.getPackedLight(remapLocalCoordinate(localX), remapLocalCoordinate(localY), remapLocalCoordinate(localZ));
    }

    private static boolean hasRenderableFaces(ChunkMeshData meshData) {
        return meshData.opaque().faceCount() > 0
                || meshData.cutout().faceCount() > 0
                || meshData.transparent().faceCount() > 0;
    }

    private static int chunkOffset(int localCoordinate) {
        if (localCoordinate < 0) {
            return -1;
        }
        if (localCoordinate >= Chunk.SIZE) {
            return 1;
        }
        return 0;
    }

    private static int remapLocalCoordinate(int localCoordinate) {
        if (localCoordinate < 0) {
            return Chunk.SIZE - 1;
        }
        if (localCoordinate >= Chunk.SIZE) {
            return 0;
        }
        return localCoordinate;
    }

    private static int neighborhoodIndex(int offsetX, int offsetY, int offsetZ) {
        return (offsetX + 1) + ((offsetZ + 1) * 3) + ((offsetY + 1) * 9);
    }
}
