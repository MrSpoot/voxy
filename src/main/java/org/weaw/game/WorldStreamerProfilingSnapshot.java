package org.weaw.game;

public record WorldStreamerProfilingSnapshot(
        long chunkGenerationCpuTimeNs,
        long chunkMeshCpuTimeNs,
        long chunkPublishCpuTimeNs,
        long chunkUnloadCpuTimeNs,
        int loadedChunks,
        int queuedTasks,
        int pendingRemesh,
        int pendingUploads,
        int pendingUnloads,
        int chunksPublished,
        int chunksUnloaded,
        int chunksGenerated,
        int chunksMeshed,
        int chunksRemeshed
) {
    public static WorldStreamerProfilingSnapshot empty() {
        return new WorldStreamerProfilingSnapshot(
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
                0
        );
    }
}
