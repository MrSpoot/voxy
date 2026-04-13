package org.weaw.game;

public record ChunkMeshData(LayerMeshData opaque, LayerMeshData cutout) {
    public record LayerMeshData(int[] faceData, int faceCount) {
    }
}
