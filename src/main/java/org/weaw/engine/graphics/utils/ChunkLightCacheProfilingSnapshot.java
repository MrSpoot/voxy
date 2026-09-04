package org.weaw.engine.graphics.utils;

public record ChunkLightCacheProfilingSnapshot(
        int synchronizeCalls,
        int fullSnapshotCount,
        int deltaCount,
        int refreshedAllocationCount,
        int freedAllocationCount,
        int uploadedChunkCount,
        int residentAllocationCount,
        int deferredUploadCount,
        int allocationFailureCount,
        int evictionCount,
        int skippedRetryCount,
        int urgentUploadedChunkCount,
        int backgroundUploadedChunkCount,
        int newVisibleMissingCount,
        int prefetchedUploadedChunkCount,
        int prefetchHitCount,
        int fallbackChunkCount
) {
    public static ChunkLightCacheProfilingSnapshot empty() {
        return new ChunkLightCacheProfilingSnapshot(0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }
}
