package org.weaw.game;

public record ChunkMeshData(LayerMeshData opaque, LayerMeshData cutout, LayerMeshData transparent) {
    public long estimateRetainedBytes() {
        return 32L + opaque.estimateRetainedBytes() + cutout.estimateRetainedBytes() + transparent.estimateRetainedBytes();
    }

    public record LayerMeshData(int[] faceData, int faceCount) {
        public long estimateRetainedBytes() {
            return 24L + 16L + (long) faceData.length * Integer.BYTES;
        }
    }
}
