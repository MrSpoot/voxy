package org.weaw.game;

public record ChunkMeshingMetrics(
        long snapshotCpuTimeNs,
        long faceClassificationCpuTimeNs,
        long greedyMergeCpuTimeNs,
        long outputBuildCpuTimeNs,
        int ambientOcclusionFaceCount,
        int sampledBlockCount
) {
    public static final class Recorder {
        private long snapshotCpuTimeNs;
        private long faceClassificationCpuTimeNs;
        private long greedyMergeCpuTimeNs;
        private long outputBuildCpuTimeNs;
        private int ambientOcclusionFaceCount;
        private int sampledBlockCount;

        public void recordSnapshot(long elapsedNs, int samples) {
            snapshotCpuTimeNs += elapsedNs;
            sampledBlockCount += samples;
        }

        public void recordFaceClassification(long elapsedNs) {
            faceClassificationCpuTimeNs += elapsedNs;
        }

        public void recordGreedyMerge(long elapsedNs) {
            greedyMergeCpuTimeNs += elapsedNs;
        }

        public void recordOutputBuild(long elapsedNs) {
            outputBuildCpuTimeNs += elapsedNs;
        }

        public void recordAmbientOcclusionFace() {
            ambientOcclusionFaceCount++;
        }

        public ChunkMeshingMetrics snapshot() {
            return new ChunkMeshingMetrics(
                    snapshotCpuTimeNs,
                    faceClassificationCpuTimeNs,
                    greedyMergeCpuTimeNs,
                    outputBuildCpuTimeNs,
                    ambientOcclusionFaceCount,
                    sampledBlockCount
            );
        }
    }
}
