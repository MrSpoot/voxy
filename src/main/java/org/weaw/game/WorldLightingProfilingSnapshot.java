package org.weaw.game;

public record WorldLightingProfilingSnapshot(
        long snapshotLoadedChunksCpuTimeNs,
        long clearLightingCpuTimeNs,
        long seedEmittersCpuTimeNs,
        long propagateCpuTimeNs,
        int affectedChunkCount,
        int expandedChunkCount,
        int loadedChunkCount,
        int loadedTargetChunkCount,
        int clearedChunkCount,
        int emitterCount,
        int seededNodeCount,
        int propagationNodeCount,
        int lightWriteCount,
        int blockedByOpaqueCount,
        int missingChunkNeighborCount,
        int noGainCount
) {
    public static WorldLightingProfilingSnapshot empty() {
        return new WorldLightingProfilingSnapshot(
                0L,
                0L,
                0L,
                0L,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0
        );
    }
}
