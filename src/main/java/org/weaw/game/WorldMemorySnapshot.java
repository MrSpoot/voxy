package org.weaw.game;

public record WorldMemorySnapshot(
        long estimatedCpuResidentBytes,
        long reservedInFlightBytes,
        long maxCpuResidentBytes,
        long heapUsedBytes,
        long heapMaxBytes,
        int loadedChunks,
        int compactLightingChunks,
        int expandedLightingChunks,
        int requestedRenderDistanceChunks,
        int effectiveRenderDistanceChunks,
        int rejectedLoadCount,
        boolean sparseChunkStreamingEnabled,
        int desiredMaterializedChunks,
        int virtualEmptyChunks,
        int virtualUniformChunks,
        int interactionBubbleChunks,
        int classificationCacheColumns,
        long classificationCacheHits,
        long classificationCacheMisses,
        PressureState pressureState
) {
    public enum PressureState {
        NORMAL,
        SUSPENDED,
        EMERGENCY
    }
}
