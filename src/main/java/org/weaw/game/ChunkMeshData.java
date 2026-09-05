package org.weaw.game;

public record ChunkMeshData(
        LayerMeshData opaque,
        LayerMeshData cutout,
        LayerMeshData transparent,
        LayerMeshData water
) {
    private static final LayerMeshData EMPTY_LAYER = new LayerMeshData(new int[0], 0);

    public ChunkMeshData(LayerMeshData opaque, LayerMeshData cutout, LayerMeshData transparent) {
        this(opaque, cutout, transparent, EMPTY_LAYER);
    }

    public long estimateRetainedBytes() {
        return 40L + opaque.estimateRetainedBytes() + cutout.estimateRetainedBytes()
                + transparent.estimateRetainedBytes() + water.estimateRetainedBytes();
    }

    public record LayerMeshData(int[] faceData, int faceCount) {
        public long estimateRetainedBytes() {
            return 24L + 16L + (long) faceData.length * Integer.BYTES;
        }
    }
}
