package org.weaw.game;

import org.weaw.game.utils.BinaryChunkMeshBuilder;
import org.weaw.game.utils.LegacyChunkMeshBuilder;

import java.util.Locale;

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
        return switch (meshingMode) {
            case GREEDY -> BinaryChunkMeshBuilder.buildMeshData(
                    chunk,
                    blockProvider,
                    ambientOcclusionEnabled,
                    transparentChunksEnabled
            );
            case LEGACY -> LegacyChunkMeshBuilder.buildMeshData(
                    chunk,
                    blockProvider,
                    ambientOcclusionEnabled,
                    transparentChunksEnabled
            );
        };
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
