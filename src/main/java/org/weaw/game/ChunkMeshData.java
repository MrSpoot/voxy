package org.weaw.game;

public record ChunkMeshData(LayerMeshData opaque, LayerMeshData cutout, LayerMeshData transparent) {
    public record LayerMeshData(int[] faceData, int faceCount) {
    }
}
