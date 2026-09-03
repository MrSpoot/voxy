package org.weaw.game;

import org.weaw.game.utils.BinaryChunkMeshBuilder;
import org.weaw.game.utils.LegacyChunkMeshBuilder;

import java.util.Locale;
import java.util.function.BooleanSupplier;

public final class ChunkMesher {
    public enum MeshingMode {
        LEGACY,
        GREEDY
    }

    private static volatile MeshingMode meshingMode = resolveMeshingMode();
    private static volatile boolean ambientOcclusionEnabled = !Boolean.getBoolean("voxy.disableAo");
    private static volatile boolean transparentChunksEnabled = !Boolean.getBoolean("voxy.disableTransparentChunks");

    private ChunkMesher() {
    }

    public static ChunkMeshData buildMeshData(Chunk chunk, WorldBlockProvider blockProvider) {
        return buildMeshDataProfiled(chunk, blockProvider, () -> false).meshData();
    }

    public static ChunkMeshingResult buildMeshDataProfiled(
            Chunk chunk,
            WorldBlockProvider blockProvider,
            BooleanSupplier cancelled
    ) {
        ChunkMeshingMetrics.Recorder metrics = new ChunkMeshingMetrics.Recorder();
        long snapshotStartNs = System.nanoTime();
        ChunkMeshingSnapshot snapshot = ChunkMeshingSnapshot.capture(chunk, blockProvider, cancelled);
        metrics.recordSnapshot(System.nanoTime() - snapshotStartNs, snapshot.sampledBlockCount());

        ChunkMeshData meshData = switch (meshingMode) {
            case GREEDY -> BinaryChunkMeshBuilder.buildMeshData(
                    snapshot,
                    ambientOcclusionEnabled,
                    transparentChunksEnabled,
                    cancelled,
                    metrics
            );
            case LEGACY -> LegacyChunkMeshBuilder.buildMeshData(
                    snapshot,
                    ambientOcclusionEnabled,
                    transparentChunksEnabled,
                    cancelled,
                    metrics
            );
        };
        return new ChunkMeshingResult(meshData, metrics.snapshot());
    }

    public static MeshingMode getMeshingMode() {
        return meshingMode;
    }

    public static void setMeshingMode(MeshingMode meshingMode) {
        ChunkMesher.meshingMode = meshingMode;
    }

    public static boolean isAmbientOcclusionEnabled() {
        return ambientOcclusionEnabled;
    }

    public static void setAmbientOcclusionEnabled(boolean ambientOcclusionEnabled) {
        ChunkMesher.ambientOcclusionEnabled = ambientOcclusionEnabled;
    }

    public static boolean isTransparentChunksEnabled() {
        return transparentChunksEnabled;
    }

    public static void setTransparentChunksEnabled(boolean transparentChunksEnabled) {
        ChunkMesher.transparentChunksEnabled = transparentChunksEnabled;
    }

    private static MeshingMode resolveMeshingMode() {
        String configuredMode = System.getProperty("voxy.mesher", "greedy");
        try {
            return MeshingMode.valueOf(configuredMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return MeshingMode.LEGACY;
        }
    }
}
