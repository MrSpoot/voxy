package org.weaw.game.utils;

import org.weaw.game.ChunkMeshingSnapshot;

/**
 * Compact voxel AO helpers for opaque faces.
 *
 * AO is stored in the second face uint:
 * - bits 0..15  : texture index
 * - bits 16..23 : 4 AO values (2 bits each, one per vertex)
 */
public final class VoxelAmbientOcclusion {
    private static final int FACE_POS_X = 0;
    private static final int FACE_NEG_X = 1;
    private static final int FACE_POS_Y = 2;
    private static final int FACE_NEG_Y = 3;
    private static final int FACE_POS_Z = 4;
    private static final int FACE_NEG_Z = 5;

    private VoxelAmbientOcclusion() {
    }

    public static int packOpaqueFaceData(int textureIndex, int aoPacked) {
        return (textureIndex & 0xFFFF) | ((aoPacked & 0xFF) << 16);
    }

    public static int computeOpaqueAoPacked(
            ChunkMeshingSnapshot snapshot,
            int x,
            int y,
            int z,
            int faceDirection
    ) {
        AxisVectors axes = switch (faceDirection) {
            case FACE_POS_X -> new AxisVectors(1, 0, 0, 0, 0, -1, 0, 1, 0);
            case FACE_NEG_X -> new AxisVectors(-1, 0, 0, 0, 0, 1, 0, 1, 0);
            case FACE_POS_Y -> new AxisVectors(0, 1, 0, 1, 0, 0, 0, 0, -1);
            case FACE_NEG_Y -> new AxisVectors(0, -1, 0, 1, 0, 0, 0, 0, 1);
            case FACE_POS_Z -> new AxisVectors(0, 0, 1, 1, 0, 0, 0, 1, 0);
            case FACE_NEG_Z -> new AxisVectors(0, 0, -1, -1, 0, 0, 0, 1, 0);
            default -> throw new IllegalArgumentException("Unknown face direction: " + faceDirection);
        };

        int ao0 = computeVertexAo(snapshot, x, y, z, axes, -1, -1);
        int ao1 = computeVertexAo(snapshot, x, y, z, axes, 1, -1);
        int ao2 = computeVertexAo(snapshot, x, y, z, axes, -1, 1);
        int ao3 = computeVertexAo(snapshot, x, y, z, axes, 1, 1);
        return ao0 | (ao1 << 2) | (ao2 << 4) | (ao3 << 6);
    }

    private static int computeVertexAo(
            ChunkMeshingSnapshot snapshot,
            int x,
            int y,
            int z,
            AxisVectors axes,
            int uSign,
            int vSign
    ) {
        int baseX = x + axes.nx();
        int baseY = y + axes.ny();
        int baseZ = z + axes.nz();

        int side1X = baseX + (axes.ux() * uSign);
        int side1Y = baseY + (axes.uy() * uSign);
        int side1Z = baseZ + (axes.uz() * uSign);

        int side2X = baseX + (axes.vx() * vSign);
        int side2Y = baseY + (axes.vy() * vSign);
        int side2Z = baseZ + (axes.vz() * vSign);

        int cornerX = side1X + (axes.vx() * vSign);
        int cornerY = side1Y + (axes.vy() * vSign);
        int cornerZ = side1Z + (axes.vz() * vSign);

        boolean side1 = isOpaqueBlock(snapshot, side1X, side1Y, side1Z);
        boolean side2 = isOpaqueBlock(snapshot, side2X, side2Y, side2Z);
        boolean corner = isOpaqueBlock(snapshot, cornerX, cornerY, cornerZ);

        if (side1 && side2) {
            return 3;
        }
        return (side1 ? 1 : 0) + (side2 ? 1 : 0) + (corner ? 1 : 0);
    }

    private static boolean isOpaqueBlock(
            ChunkMeshingSnapshot snapshot,
            int localX,
            int localY,
            int localZ
    ) {
        short blockId = snapshot.getBlock(localX, localY, localZ);
        BlockDefinition blockDefinition = snapshot.blockCatalog().getBlock(blockId);
        return blockDefinition != null
                && blockDefinition.isOpaque()
                && blockDefinition != snapshot.blockCatalog().air();
    }

    private record AxisVectors(int nx, int ny, int nz, int ux, int uy, int uz, int vx, int vy, int vz) {
    }
}
