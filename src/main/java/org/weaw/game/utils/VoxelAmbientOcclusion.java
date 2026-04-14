package org.weaw.game.utils;

import org.weaw.game.Chunk;
import org.weaw.game.WorldBlockProvider;

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
            Chunk chunk,
            WorldBlockProvider blockProvider,
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

        int ao0 = computeVertexAo(chunk, blockProvider, x, y, z, axes, -1, -1);
        int ao1 = computeVertexAo(chunk, blockProvider, x, y, z, axes, 1, -1);
        int ao2 = computeVertexAo(chunk, blockProvider, x, y, z, axes, -1, 1);
        int ao3 = computeVertexAo(chunk, blockProvider, x, y, z, axes, 1, 1);
        return ao0 | (ao1 << 2) | (ao2 << 4) | (ao3 << 6);
    }

    private static int computeVertexAo(
            Chunk chunk,
            WorldBlockProvider blockProvider,
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

        boolean side1 = isOpaqueBlock(chunk, blockProvider, side1X, side1Y, side1Z);
        boolean side2 = isOpaqueBlock(chunk, blockProvider, side2X, side2Y, side2Z);
        boolean corner = isOpaqueBlock(chunk, blockProvider, cornerX, cornerY, cornerZ);

        if (side1 && side2) {
            return 3;
        }
        return (side1 ? 1 : 0) + (side2 ? 1 : 0) + (corner ? 1 : 0);
    }

    private static boolean isOpaqueBlock(
            Chunk chunk,
            WorldBlockProvider blockProvider,
            int localX,
            int localY,
            int localZ
    ) {
        short blockId;
        if (chunk.isInBounds(localX, localY, localZ)) {
            blockId = chunk.getBlock(localX, localY, localZ);
        } else {
            int worldX = chunk.getPosition().x * Chunk.SIZE + localX;
            int worldY = chunk.getPosition().y * Chunk.SIZE + localY;
            int worldZ = chunk.getPosition().z * Chunk.SIZE + localZ;
            blockId = blockProvider.getBlockAtWorld(worldX, worldY, worldZ);
        }

        BlockDefinition blockDefinition = BlockRegistry.getBlock(blockId);
        return blockDefinition != null && blockDefinition.isOpaque() && blockDefinition != Blocks.AIR;
    }

    private record AxisVectors(int nx, int ny, int nz, int ux, int uy, int uz, int vx, int vy, int vz) {
    }
}
