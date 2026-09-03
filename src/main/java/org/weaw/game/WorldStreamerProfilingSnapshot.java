package org.weaw.game;

public record WorldStreamerProfilingSnapshot(
        long chunkGenerationCpuTimeNs,
        long chunkMeshCpuTimeNs,
        long chunkMeshingSnapshotCpuTimeNs,
        long chunkMeshingFaceClassificationCpuTimeNs,
        long chunkMeshingGreedyMergeCpuTimeNs,
        long chunkMeshingOutputBuildCpuTimeNs,
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
        int chunksRemeshed,
        int chunkMeshingAmbientOcclusionFaces,
        int chunkMeshingSampledBlocks,
        int cancelledChunkBuilds
) {
    public static WorldStreamerProfilingSnapshot empty() {
        return new WorldStreamerProfilingSnapshot(
                0L,
                0L,
                0L,
                0L,
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
                0,
                0
        );
    }
}
