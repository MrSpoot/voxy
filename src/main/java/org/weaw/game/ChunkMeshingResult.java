package org.weaw.game;

import java.util.Objects;

public record ChunkMeshingResult(ChunkMeshData meshData, ChunkMeshingMetrics metrics) {
    public ChunkMeshingResult {
        Objects.requireNonNull(meshData, "meshData");
        Objects.requireNonNull(metrics, "metrics");
    }
}
