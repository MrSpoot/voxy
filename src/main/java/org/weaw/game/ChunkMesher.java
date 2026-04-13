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

    private ChunkMesher() {
    }

    public static ChunkMeshData buildMeshData(Chunk chunk, WorldBlockProvider blockProvider) {
        return switch (meshingMode) {
            case GREEDY -> BinaryChunkMeshBuilder.buildMeshData(chunk, blockProvider);
            case LEGACY -> LegacyChunkMeshBuilder.buildMeshData(chunk, blockProvider);
        };
    }

    public static MeshingMode getMeshingMode() {
        return meshingMode;
    }

    public static void setMeshingMode(MeshingMode meshingMode) {
        ChunkMesher.meshingMode = meshingMode;
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
