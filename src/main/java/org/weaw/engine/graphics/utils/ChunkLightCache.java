package org.weaw.engine.graphics.utils;

import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryUtil;
import org.weaw.game.Chunk;
import org.weaw.game.ChunkLighting;
import org.weaw.game.ChunkManager;
import org.weaw.game.ChunkManager.ChunkLightDelta;
import org.weaw.game.ChunkManager.ChunkLightSync;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.ChunkManager.ChunkUpload;
import org.weaw.game.ChunkManager.ChunkUploadChangeType;
import org.weaw.game.ChunkMeshData;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Visibility-driven GPU cache for padded chunk lighting data. */
public final class ChunkLightCache {
    private static final int INITIAL_LIGHT_ARENA_CAPACITY_INTS =
            Integer.getInteger("voxy.chunkLightArenaInitialCapacityInts", 32768);
    private static final int MAX_URGENT_UPLOADS_PER_FRAME = Math.max(1,
            Integer.getInteger("voxy.chunkLightUrgentUploadsPerFrame", 64));
    private static final long URGENT_UPLOAD_BUDGET_NS = Math.max(100_000L,
            Long.getLong("voxy.chunkLightUrgentUploadBudgetNs", 4_000_000L));
    private static final int MAX_BACKGROUND_UPLOADS_PER_FRAME = Math.max(1,
            Integer.getInteger("voxy.chunkLightBackgroundUploadsPerFrame", 16));
    private static final long BACKGROUND_UPLOAD_BUDGET_NS = Math.max(100_000L,
            Long.getLong("voxy.chunkLightBackgroundUploadBudgetNs", 1_500_000L));
    private static final int PADDED_DIMENSION = Chunk.SIZE + 2;
    private static final int PADDED_VOXELS = PADDED_DIMENSION * PADDED_DIMENSION * PADDED_DIMENSION;
    private static final int PACKED_LIGHT_INTS = (PADDED_VOXELS + 1) / 2;
    private static final int PACKED_DIRECT_SKY_INTS = (PADDED_VOXELS + 7) / 8;
    private static final int PACKED_CHUNK_LIGHT_INTS = PACKED_LIGHT_INTS + PACKED_DIRECT_SKY_INTS;

    private final ChunkManager chunkManager;
    private final ChunkGpuMemoryBudget gpuMemoryBudget;
    private final Map<ChunkPosition, ChunkLightArena.Allocation> allocations =
            new LinkedHashMap<>(128, 0.75f, true);
    private final Set<ChunkPosition> dirtyNotVisible = new LinkedHashSet<>();
    private final Set<ChunkPosition> urgentUploads = new LinkedHashSet<>();
    private final Set<ChunkPosition> backgroundUploads = new LinkedHashSet<>();
    private final Set<ChunkPosition> deferredUploads = new LinkedHashSet<>();
    private final Set<ChunkPosition> intentionalDarkFallbacks = new LinkedHashSet<>();
    private final Set<ChunkPosition> previousVisiblePositions = new LinkedHashSet<>();
    private final Set<ChunkPosition> prefetchedAllocations = new LinkedHashSet<>();
    private ChunkLightArena lightArena;
    private IntBuffer stagingBuffer;
    private final ChunkLightArena.Allocation[] fallbackAllocations =
            new ChunkLightArena.Allocation[ChunkLighting.MAX_SKY_LIGHT + 1];
    private long synchronizedChunkLightVersion = Long.MIN_VALUE;
    private long lastPreparedFrameIndex = Long.MIN_VALUE;
    private long compatibilityFrameIndex;
    private boolean lightDataResident;
    private int profilingSynchronizeCalls;
    private int profilingFullSnapshotCount;
    private int profilingDeltaCount;
    private int profilingRefreshedAllocationCount;
    private int profilingFreedAllocationCount;
    private int profilingUploadedChunkCount;
    private int profilingAllocationFailureCount;
    private int profilingEvictionCount;
    private int profilingSkippedRetryCount;
    private int profilingUrgentUploadedChunkCount;
    private int profilingBackgroundUploadedChunkCount;
    private int profilingNewVisibleMissingCount;
    private int profilingPrefetchedUploadedChunkCount;
    private int profilingPrefetchHitCount;
    private int profilingFallbackChunkCount;

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
        stagingBuffer = MemoryUtil.memAllocInt(PACKED_CHUNK_LIGHT_INTS);
        for (int skyLevel = 0; skyLevel <= ChunkLighting.MAX_SKY_LIGHT; skyLevel++) {
            fillUniformSkyFallback(skyLevel);
            fallbackAllocations[skyLevel] = lightArena.upload(stagingBuffer, null);
            if (fallbackAllocations[skyLevel] == null) {
                throw new IllegalStateException("Unable to allocate chunk light fallback level " + skyLevel);
            }
        }
    }

    /** Compatibility entry point for callers without a culled visibility set. */
    public void synchronize(boolean requiresLightData) {
        Set<ChunkPosition> positions = chunkManager.snapshotChunkUploads().keySet();
        prepareFrame(requiresLightData, positions, positions, new Vector3f(), ++compatibilityFrameIndex);
    }

    /** Compatibility overload without proactive prefetch information. */
    public void prepareFrame(boolean requiresLightData, Set<ChunkPosition> visiblePositions, long frameIndex) {
        prepareFrame(requiresLightData, visiblePositions, visiblePositions, new Vector3f(), frameIndex);
    }

    /** Applies light deltas once per frame and preloads chunks likely to enter the camera frustum. */
    public void prepareFrame(
            boolean requiresLightData,
            Set<ChunkPosition> visiblePositions,
            Set<ChunkPosition> prefetchPositions,
            Vector3fc cameraPosition,
            long frameIndex
    ) {
        profilingSynchronizeCalls++;
        if (lastPreparedFrameIndex == frameIndex) {
            return;
        }
        lastPreparedFrameIndex = frameIndex;

        if (!requiresLightData) {
            lightDataResident = false;
            previousVisiblePositions.clear();
            return;
        }
        if (lightArena == null) {
            create();
        }
        if (!lightDataResident) {
            synchronizedChunkLightVersion = Long.MIN_VALUE;
        }

        synchronizeDeltas(visiblePositions, prefetchPositions);
        reconcileInterest(visiblePositions, prefetchPositions);
        reorderNearestFirst(urgentUploads, cameraPosition);
        processUrgentUploads(visiblePositions, prefetchPositions);
        processBackgroundUploads(visiblePositions, prefetchPositions);
        profilingFallbackChunkCount += countVisibleFallbacks(visiblePositions);
        previousVisiblePositions.clear();
        previousVisiblePositions.addAll(visiblePositions);
        lightDataResident = true;
    }

    public void bind() {
        if (lightArena != null) {
            lightArena.bind();
        }
    }

    public int getLightOffsetInts(ChunkPosition position) {
        ChunkLightArena.Allocation allocation = allocations.get(position);
        if (allocation != null) {
            return allocation.offsetInts();
        }
        int fallbackLevel = representativeFallbackSkyLevel(position);
        ChunkLightArena.Allocation fallback = fallbackAllocations[fallbackLevel];
        return fallback != null ? fallback.offsetInts() : 0;
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
                allocations.size(),
                deferredUploads.size() + backgroundUploads.size() + urgentUploads.size(),
                profilingAllocationFailureCount,
                profilingEvictionCount,
                profilingSkippedRetryCount,
                profilingUrgentUploadedChunkCount,
                profilingBackgroundUploadedChunkCount,
                profilingNewVisibleMissingCount,
                profilingPrefetchedUploadedChunkCount,
                profilingPrefetchHitCount,
                profilingFallbackChunkCount
        );
        profilingSynchronizeCalls = 0;
        profilingFullSnapshotCount = 0;
        profilingDeltaCount = 0;
        profilingRefreshedAllocationCount = 0;
        profilingFreedAllocationCount = 0;
        profilingUploadedChunkCount = 0;
        profilingAllocationFailureCount = 0;
        profilingEvictionCount = 0;
        profilingSkippedRetryCount = 0;
        profilingUrgentUploadedChunkCount = 0;
        profilingBackgroundUploadedChunkCount = 0;
        profilingNewVisibleMissingCount = 0;
        profilingPrefetchedUploadedChunkCount = 0;
        profilingPrefetchHitCount = 0;
        profilingFallbackChunkCount = 0;
        return snapshot;
    }

    public void cleanup() {
        allocations.clear();
        dirtyNotVisible.clear();
        urgentUploads.clear();
        backgroundUploads.clear();
        deferredUploads.clear();
        intentionalDarkFallbacks.clear();
        previousVisiblePositions.clear();
        prefetchedAllocations.clear();
        synchronizedChunkLightVersion = Long.MIN_VALUE;
        lastPreparedFrameIndex = Long.MIN_VALUE;
        compatibilityFrameIndex = 0L;
        lightDataResident = false;
        profilingSynchronizeCalls = 0;
        profilingFullSnapshotCount = 0;
        profilingDeltaCount = 0;
        profilingRefreshedAllocationCount = 0;
        profilingFreedAllocationCount = 0;
        profilingUploadedChunkCount = 0;
        profilingAllocationFailureCount = 0;
        profilingEvictionCount = 0;
        profilingSkippedRetryCount = 0;
        profilingUrgentUploadedChunkCount = 0;
        profilingBackgroundUploadedChunkCount = 0;
        profilingNewVisibleMissingCount = 0;
        profilingPrefetchedUploadedChunkCount = 0;
        profilingPrefetchHitCount = 0;
        profilingFallbackChunkCount = 0;
        if (lightArena != null) {
            lightArena.cleanup();
            lightArena = null;
        }
        java.util.Arrays.fill(fallbackAllocations, null);
        if (stagingBuffer != null) {
            MemoryUtil.memFree(stagingBuffer);
            stagingBuffer = null;
        }
    }

    private void synchronizeDeltas(
            Set<ChunkPosition> visiblePositions,
            Set<ChunkPosition> prefetchPositions
    ) {
        long currentLightVersion = chunkManager.getChunkLightVersion();
        if (currentLightVersion == synchronizedChunkLightVersion) {
            return;
        }

        ChunkLightSync lightSync = chunkManager.snapshotChunkLightSync(synchronizedChunkLightVersion);
        if (lightSync.requiresFullSnapshot()) {
            profilingFullSnapshotCount++;
            applyFullSnapshot(lightSync.fullSnapshot());
            for (ChunkPosition position : prefetchPositions) {
                if (lightSync.fullSnapshot().contains(position)) {
                    enqueueRefresh(position, false, visiblePositions, prefetchPositions);
                }
            }
        } else {
            profilingDeltaCount += lightSync.deltas().size();
            for (ChunkLightDelta delta : lightSync.deltas()) {
                if (delta.changeType() == ChunkUploadChangeType.REMOVED) {
                    freeAllocation(delta.position());
                    removeFromPending(delta.position());
                }
                for (ChunkPosition consumer : affectedConsumers(delta.position(), delta.boundaryMask())) {
                    enqueueRefresh(consumer, delta.priority(), visiblePositions, prefetchPositions);
                }
            }
        }
        synchronizedChunkLightVersion = lightSync.version();
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
            profilingFreedAllocationCount++;
        }
        dirtyNotVisible.retainAll(fullSnapshot);
        urgentUploads.retainAll(fullSnapshot);
        backgroundUploads.retainAll(fullSnapshot);
        deferredUploads.retainAll(fullSnapshot);
        intentionalDarkFallbacks.retainAll(fullSnapshot);
        previousVisiblePositions.retainAll(fullSnapshot);
        prefetchedAllocations.retainAll(fullSnapshot);
    }

    private void enqueueRefresh(
            ChunkPosition position,
            boolean priority,
            Set<ChunkPosition> visiblePositions,
            Set<ChunkPosition> prefetchPositions
    ) {
        intentionalDarkFallbacks.remove(position);
        if (chunkManager.getChunkUpload(position) == null) {
            freeAllocation(position);
            removeFromPending(position);
            return;
        }
        UploadPriority uploadPriority = classifyUpload(
                visiblePositions.contains(position),
                prefetchPositions.contains(position),
                allocations.containsKey(position),
                true,
                priority
        );
        if (uploadPriority == UploadPriority.NONE) {
            dirtyNotVisible.add(position);
            urgentUploads.remove(position);
            backgroundUploads.remove(position);
            deferredUploads.remove(position);
            return;
        }
        dirtyNotVisible.remove(position);
        if (uploadPriority == UploadPriority.URGENT) {
            backgroundUploads.remove(position);
            deferredUploads.remove(position);
            urgentUploads.add(position);
        } else {
            enqueueBackground(position);
        }
    }

    private void reconcileInterest(Set<ChunkPosition> visiblePositions, Set<ChunkPosition> prefetchPositions) {
        demoteInvisibleUrgentUploads(visiblePositions, prefetchPositions);
        moveOutsideInterestToDirty(backgroundUploads, visiblePositions, prefetchPositions);
        moveOutsideInterestToDirty(deferredUploads, visiblePositions, prefetchPositions);

        for (ChunkPosition position : visiblePositions) {
            if (chunkManager.getChunkUpload(position) == null) {
                continue;
            }
            boolean newlyVisible = !previousVisiblePositions.contains(position);
            boolean hasAllocation = allocations.containsKey(position);
            if (newlyVisible && !hasAllocation && !intentionalDarkFallbacks.contains(position)) {
                profilingNewVisibleMissingCount++;
            } else if (newlyVisible && hasAllocation && prefetchedAllocations.remove(position)) {
                profilingPrefetchHitCount++;
            }
            if (intentionalDarkFallbacks.contains(position)) {
                continue;
            }
            boolean dirty = dirtyNotVisible.remove(position);
            UploadPriority priority = classifyUpload(
                    true,
                    true,
                    hasAllocation,
                    dirty || !hasAllocation,
                    newlyVisible && dirty
            );
            if (priority == UploadPriority.URGENT) {
                backgroundUploads.remove(position);
                deferredUploads.remove(position);
                urgentUploads.add(position);
            } else if (priority == UploadPriority.BACKGROUND) {
                enqueueBackground(position);
            }
        }

        for (ChunkPosition position : prefetchPositions) {
            if (visiblePositions.contains(position)
                    || chunkManager.getChunkUpload(position) == null
                    || intentionalDarkFallbacks.contains(position)) {
                continue;
            }
            boolean hasAllocation = allocations.containsKey(position);
            boolean dirty = dirtyNotVisible.remove(position);
            if (classifyUpload(false, true, hasAllocation, dirty || !hasAllocation, false)
                    == UploadPriority.BACKGROUND
                    && !urgentUploads.contains(position)) {
                enqueueBackground(position);
            }
        }
    }

    private void demoteInvisibleUrgentUploads(
            Set<ChunkPosition> visiblePositions,
            Set<ChunkPosition> prefetchPositions
    ) {
        Iterator<ChunkPosition> iterator = urgentUploads.iterator();
        while (iterator.hasNext()) {
            ChunkPosition position = iterator.next();
            if (!visiblePositions.contains(position)) {
                iterator.remove();
                if (prefetchPositions.contains(position)) {
                    enqueueBackground(position);
                } else {
                    dirtyNotVisible.add(position);
                }
            }
        }
    }

    private void moveOutsideInterestToDirty(
            Set<ChunkPosition> pending,
            Set<ChunkPosition> visiblePositions,
            Set<ChunkPosition> prefetchPositions
    ) {
        Iterator<ChunkPosition> iterator = pending.iterator();
        while (iterator.hasNext()) {
            ChunkPosition position = iterator.next();
            if (!visiblePositions.contains(position) && !prefetchPositions.contains(position)) {
                dirtyNotVisible.add(position);
                iterator.remove();
            }
        }
    }

    private void processUrgentUploads(
            Set<ChunkPosition> visiblePositions,
            Set<ChunkPosition> prefetchPositions
    ) {
        long deadline = System.nanoTime() + URGENT_UPLOAD_BUDGET_NS;
        int attempts = 0;
        Iterator<ChunkPosition> iterator = urgentUploads.iterator();
        while (iterator.hasNext()
                && attempts < MAX_URGENT_UPLOADS_PER_FRAME
                && System.nanoTime() < deadline) {
            ChunkPosition position = iterator.next();
            iterator.remove();
            attempts++;
            if (!visiblePositions.contains(position)) {
                if (prefetchPositions.contains(position)) {
                    enqueueBackground(position);
                } else {
                    dirtyNotVisible.add(position);
                }
                continue;
            }
            int uploadedBefore = profilingUploadedChunkCount;
            if (refreshAllocation(position, visiblePositions, prefetchPositions)
                    && profilingUploadedChunkCount > uploadedBefore) {
                profilingUrgentUploadedChunkCount++;
                prefetchedAllocations.remove(position);
            } else if (!intentionalDarkFallbacks.contains(position)) {
                deferredUploads.add(position);
            }
        }
        profilingSkippedRetryCount += urgentUploads.size();
    }

    private void processBackgroundUploads(
            Set<ChunkPosition> visiblePositions,
            Set<ChunkPosition> prefetchPositions
    ) {
        if (!urgentUploads.isEmpty()) {
            profilingSkippedRetryCount += backgroundUploads.size() + deferredUploads.size();
            return;
        }
        long deadline = System.nanoTime() + BACKGROUND_UPLOAD_BUDGET_NS;
        int attempts = 0;
        while (attempts < MAX_BACKGROUND_UPLOADS_PER_FRAME && System.nanoTime() < deadline) {
            ChunkPosition position = pollFirst(backgroundUploads);
            if (position == null) {
                position = pollFirst(deferredUploads);
            }
            if (position == null) {
                break;
            }
            if (!visiblePositions.contains(position) && !prefetchPositions.contains(position)) {
                dirtyNotVisible.add(position);
                continue;
            }
            attempts++;
            int uploadedBefore = profilingUploadedChunkCount;
            if (refreshAllocation(position, visiblePositions, prefetchPositions)
                    && profilingUploadedChunkCount > uploadedBefore) {
                profilingBackgroundUploadedChunkCount++;
                if (!visiblePositions.contains(position)) {
                    prefetchedAllocations.add(position);
                    profilingPrefetchedUploadedChunkCount++;
                }
            } else if (!intentionalDarkFallbacks.contains(position)) {
                deferredUploads.add(position);
            }
        }
        profilingSkippedRetryCount += backgroundUploads.size() + deferredUploads.size();
    }

    private void enqueueBackground(ChunkPosition position) {
        if (urgentUploads.contains(position)) {
            return;
        }
        deferredUploads.remove(position);
        backgroundUploads.add(position);
    }

    private boolean refreshAllocation(
            ChunkPosition position,
            Set<ChunkPosition> visiblePositions,
            Set<ChunkPosition> prefetchPositions
    ) {
        ChunkUpload upload = chunkManager.getChunkUpload(position);
        if (upload == null || !hasRenderableFaces(upload.meshData())) {
            freeAllocation(position);
            removeFromPending(position);
            intentionalDarkFallbacks.add(position);
            return true;
        }

        Chunk[] neighborhood = resolveNeighborhood(position);
        if (isEntireNeighborhoodDark(neighborhood)) {
            freeAllocation(position);
            removeFromPending(position);
            intentionalDarkFallbacks.add(position);
            profilingRefreshedAllocationCount++;
            return true;
        }

        IntBuffer packedLight = packLightDataWithChunkBorder(neighborhood);
        ChunkLightArena.Allocation previous = allocations.get(position);
        ChunkLightArena.Allocation allocation = lightArena.upload(packedLight, previous);
        while (allocation == null && evictOneOutsideInterest(visiblePositions, prefetchPositions)) {
            packedLight.rewind();
            allocation = lightArena.upload(packedLight, null);
        }
        while (allocation == null
                && visiblePositions.contains(position)
                && evictOneNonVisible(visiblePositions)) {
            packedLight.rewind();
            allocation = lightArena.upload(packedLight, null);
        }
        profilingRefreshedAllocationCount++;
        if (allocation == null) {
            allocations.remove(position);
            profilingAllocationFailureCount++;
            return false;
        }

        allocations.put(position, allocation);
        deferredUploads.remove(position);
        intentionalDarkFallbacks.remove(position);
        profilingUploadedChunkCount++;
        return true;
    }

    private boolean evictOneOutsideInterest(
            Set<ChunkPosition> visiblePositions,
            Set<ChunkPosition> prefetchPositions
    ) {
        Iterator<Map.Entry<ChunkPosition, ChunkLightArena.Allocation>> iterator = allocations.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ChunkPosition, ChunkLightArena.Allocation> entry = iterator.next();
            if (isEvictionProtected(entry.getKey(), visiblePositions, prefetchPositions, true)) {
                continue;
            }
            lightArena.free(entry.getValue());
            prefetchedAllocations.remove(entry.getKey());
            iterator.remove();
            profilingFreedAllocationCount++;
            profilingEvictionCount++;
            return true;
        }
        return false;
    }

    private boolean evictOneNonVisible(Set<ChunkPosition> visiblePositions) {
        Iterator<Map.Entry<ChunkPosition, ChunkLightArena.Allocation>> iterator = allocations.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ChunkPosition, ChunkLightArena.Allocation> entry = iterator.next();
            if (isEvictionProtected(entry.getKey(), visiblePositions, Set.of(), false)) {
                continue;
            }
            lightArena.free(entry.getValue());
            prefetchedAllocations.remove(entry.getKey());
            iterator.remove();
            profilingFreedAllocationCount++;
            profilingEvictionCount++;
            return true;
        }
        return false;
    }

    private void freeAllocation(ChunkPosition position) {
        ChunkLightArena.Allocation allocation = allocations.remove(position);
        prefetchedAllocations.remove(position);
        if (allocation != null && lightArena != null) {
            lightArena.free(allocation);
            profilingFreedAllocationCount++;
        }
    }

    private void removeFromPending(ChunkPosition position) {
        dirtyNotVisible.remove(position);
        urgentUploads.remove(position);
        backgroundUploads.remove(position);
        deferredUploads.remove(position);
        intentionalDarkFallbacks.remove(position);
    }

    private int countVisibleFallbacks(Set<ChunkPosition> visiblePositions) {
        int count = 0;
        for (ChunkPosition position : visiblePositions) {
            if (!allocations.containsKey(position)
                    && (urgentUploads.contains(position)
                    || backgroundUploads.contains(position)
                    || deferredUploads.contains(position))) {
                count++;
            }
        }
        return count;
    }

    private void fillUniformSkyFallback(int skyLevel) {
        stagingBuffer.clear();
        int packedLight = ChunkLighting.pack(0, 0, 0, skyLevel) & 0xFFFF;
        int packedPair = packedLight | packedLight << 16;
        for (int index = 0; index < PACKED_LIGHT_INTS; index++) {
            stagingBuffer.put(packedPair);
        }
        int packedDirectSky = skyLevel * 0x11111111;
        for (int index = 0; index < PACKED_DIRECT_SKY_INTS; index++) {
            stagingBuffer.put(packedDirectSky);
        }
        stagingBuffer.flip();
    }

    private int representativeFallbackSkyLevel(ChunkPosition position) {
        ChunkUpload upload = chunkManager.getChunkUpload(position);
        if (upload == null) {
            return 0;
        }
        Chunk chunk = upload.chunk();
        int[] samples = {0, Chunk.SIZE / 2, Chunk.SIZE - 1};
        int diffuseSky = 0;
        for (int y : samples) {
            for (int z : samples) {
                for (int x : samples) {
                    int directSky = chunk.getDirectSkyLight(x, y, z);
                    if (directSky == ChunkLighting.MAX_SKY_LIGHT) {
                        return directSky;
                    }
                    diffuseSky = Math.max(diffuseSky,
                            ChunkLighting.getSky(chunk.getPackedLight(x, y, z)));
                    diffuseSky = Math.max(diffuseSky, directSky);
                }
            }
        }
        return diffuseSky;
    }

    private static ChunkPosition pollFirst(Set<ChunkPosition> positions) {
        Iterator<ChunkPosition> iterator = positions.iterator();
        if (!iterator.hasNext()) {
            return null;
        }
        ChunkPosition position = iterator.next();
        iterator.remove();
        return position;
    }

    static UploadPriority classifyUpload(
            boolean visible,
            boolean prefetched,
            boolean resident,
            boolean dirty,
            boolean priorityChange
    ) {
        if (!dirty) {
            return UploadPriority.NONE;
        }
        if (visible && (priorityChange || !resident)) {
            return UploadPriority.URGENT;
        }
        if (visible || prefetched) {
            return UploadPriority.BACKGROUND;
        }
        return UploadPriority.NONE;
    }

    static boolean isEvictionProtected(
            ChunkPosition position,
            Set<ChunkPosition> visiblePositions,
            Set<ChunkPosition> prefetchPositions,
            boolean protectPrefetch
    ) {
        return visiblePositions.contains(position)
                || protectPrefetch && prefetchPositions.contains(position);
    }

    static List<ChunkPosition> nearestFirst(Set<ChunkPosition> positions, Vector3fc cameraPosition) {
        List<ChunkPosition> sorted = new ArrayList<>(positions);
        sorted.sort(Comparator.comparingDouble(position -> distanceSquared(position, cameraPosition)));
        return sorted;
    }

    private static void reorderNearestFirst(Set<ChunkPosition> positions, Vector3fc cameraPosition) {
        if (positions.size() < 2) {
            return;
        }
        List<ChunkPosition> sorted = nearestFirst(positions, cameraPosition);
        positions.clear();
        positions.addAll(sorted);
    }

    private static double distanceSquared(ChunkPosition position, Vector3fc cameraPosition) {
        double centerX = ((double) position.x() + 0.5d) * Chunk.SIZE;
        double centerY = ((double) position.y() + 0.5d) * Chunk.SIZE;
        double centerZ = ((double) position.z() + 0.5d) * Chunk.SIZE;
        double dx = centerX - cameraPosition.x();
        double dy = centerY - cameraPosition.y();
        double dz = centerZ - cameraPosition.z();
        return dx * dx + dy * dy + dz * dz;
    }

    enum UploadPriority {
        NONE,
        BACKGROUND,
        URGENT
    }

    static Set<ChunkPosition> affectedConsumers(ChunkPosition center, int boundaryMask) {
        int[] xOffsets = axisOffsets(boundaryMask, ChunkManager.LIGHT_BOUNDARY_LOW_X,
                ChunkManager.LIGHT_BOUNDARY_HIGH_X);
        int[] yOffsets = axisOffsets(boundaryMask, ChunkManager.LIGHT_BOUNDARY_LOW_Y,
                ChunkManager.LIGHT_BOUNDARY_HIGH_Y);
        int[] zOffsets = axisOffsets(boundaryMask, ChunkManager.LIGHT_BOUNDARY_LOW_Z,
                ChunkManager.LIGHT_BOUNDARY_HIGH_Z);
        Set<ChunkPosition> positions = new LinkedHashSet<>(xOffsets.length * yOffsets.length * zOffsets.length);
        for (int y : yOffsets) {
            for (int z : zOffsets) {
                for (int x : xOffsets) {
                    positions.add(new ChunkPosition(center.x() + x, center.y() + y, center.z() + z));
                }
            }
        }
        return positions;
    }

    private static int[] axisOffsets(int mask, int lowBit, int highBit) {
        boolean low = (mask & lowBit) != 0;
        boolean high = (mask & highBit) != 0;
        if (low && high) return new int[]{-1, 0, 1};
        if (low) return new int[]{-1, 0};
        if (high) return new int[]{0, 1};
        return new int[]{0};
    }

    private IntBuffer packLightDataWithChunkBorder(Chunk[] neighborhood) {
        stagingBuffer.clear();
        for (int y = -1; y <= Chunk.SIZE; y++) {
            for (int z = -1; z <= Chunk.SIZE; z++) {
                for (int x = -1; x <= Chunk.SIZE; x += 2) {
                    int low = samplePackedLight(neighborhood, x, y, z) & 0xFFFF;
                    int high = x + 1 <= Chunk.SIZE
                            ? (samplePackedLight(neighborhood, x + 1, y, z) & 0xFFFF) << 16
                            : 0;
                    stagingBuffer.put(low | high);
                }
            }
        }

        int packedDirectSky = 0;
        int packedDirectSkyCount = 0;
        for (int y = -1; y <= Chunk.SIZE; y++) {
            for (int z = -1; z <= Chunk.SIZE; z++) {
                for (int x = -1; x <= Chunk.SIZE; x++) {
                    packedDirectSky |= (sampleDirectSkyLight(neighborhood, x, y, z) & 0xF)
                            << (packedDirectSkyCount * 4);
                    packedDirectSkyCount++;
                    if (packedDirectSkyCount == 8) {
                        stagingBuffer.put(packedDirectSky);
                        packedDirectSky = 0;
                        packedDirectSkyCount = 0;
                    }
                }
            }
        }
        if (packedDirectSkyCount != 0) {
            stagingBuffer.put(packedDirectSky);
        }
        stagingBuffer.flip();
        return stagingBuffer;
    }

    private static boolean isEntireNeighborhoodDark(Chunk[] neighborhood) {
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
                            centerPosition.z() + offsetZ);
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
            Chunk centerChunk = neighborhood[neighborhoodIndex(0, 0, 0)];
            if (centerChunk == null) {
                return 0;
            }
            return centerChunk.getPackedLight(clampLocalCoordinate(localX),
                    clampLocalCoordinate(localY), clampLocalCoordinate(localZ));
        }
        return chunk.getPackedLight(remapLocalCoordinate(localX), remapLocalCoordinate(localY),
                remapLocalCoordinate(localZ));
    }

    private int sampleDirectSkyLight(Chunk[] neighborhood, int localX, int localY, int localZ) {
        int chunkOffsetX = chunkOffset(localX);
        int chunkOffsetY = chunkOffset(localY);
        int chunkOffsetZ = chunkOffset(localZ);
        Chunk chunk = neighborhood[neighborhoodIndex(chunkOffsetX, chunkOffsetY, chunkOffsetZ)];
        if (chunk == null) {
            Chunk centerChunk = neighborhood[neighborhoodIndex(0, 0, 0)];
            if (centerChunk == null) {
                return 0;
            }
            return centerChunk.getDirectSkyLight(clampLocalCoordinate(localX),
                    clampLocalCoordinate(localY), clampLocalCoordinate(localZ));
        }
        return chunk.getDirectSkyLight(remapLocalCoordinate(localX), remapLocalCoordinate(localY),
                remapLocalCoordinate(localZ));
    }

    private static boolean hasRenderableFaces(ChunkMeshData meshData) {
        return meshData.opaque().faceCount() > 0
                || meshData.cutout().faceCount() > 0
                || meshData.transparent().faceCount() > 0;
    }

    private static int chunkOffset(int localCoordinate) {
        if (localCoordinate < 0) return -1;
        if (localCoordinate >= Chunk.SIZE) return 1;
        return 0;
    }

    private static int remapLocalCoordinate(int localCoordinate) {
        if (localCoordinate < 0) return Chunk.SIZE - 1;
        if (localCoordinate >= Chunk.SIZE) return 0;
        return localCoordinate;
    }

    private static int clampLocalCoordinate(int localCoordinate) {
        return Math.max(0, Math.min(Chunk.SIZE - 1, localCoordinate));
    }

    private static int neighborhoodIndex(int offsetX, int offsetY, int offsetZ) {
        return (offsetX + 1) + ((offsetZ + 1) * 3) + ((offsetY + 1) * 9);
    }
}
