package org.weaw.engine.graphics.utils;

public record ChunkLightCacheProfilingSnapshot(
        int synchronizeCalls,
        int fullSnapshotCount,
        int deltaCount,
        int refreshedAllocationCount,
        int freedAllocationCount,
        int uploadedChunkCount,
        int residentAllocationCount
) {
    public static ChunkLightCacheProfilingSnapshot empty() {
        return new ChunkLightCacheProfilingSnapshot(0, 0, 0, 0, 0, 0, 0);
    }
}
