package org.weaw.game.utils;

import org.weaw.game.Chunk;
import org.weaw.game.ChunkMeshData;
import org.weaw.game.WorldBlockProvider;

import java.util.Arrays;

/**
 * Greedy chunk mesher adapted to the current project model.
 *
 * Each emitted face stores:
 * - uint 0: x/y/z/face/width/height
 * - uint 1: texture array layer
 *
 * The renderer remains on vertex pulling, but the shader now rebuilds quads
 * with variable width/height instead of one voxel face at a time.
 */
public final class BinaryChunkMeshBuilder {
    private static final int SIZE = Chunk.SIZE;
    private static final ChunkMeshData.LayerMeshData EMPTY_LAYER = new ChunkMeshData.LayerMeshData(new int[0], 0);

    private static final int FACE_POS_X = 0;
    private static final int FACE_NEG_X = 1;
    private static final int FACE_POS_Y = 2;
    private static final int FACE_NEG_Y = 3;
    private static final int FACE_POS_Z = 4;
    private static final int FACE_NEG_Z = 5;

    private BinaryChunkMeshBuilder() {
    }

    public static ChunkMeshData buildMeshData(
            Chunk chunk,
            WorldBlockProvider blockProvider,
            boolean ambientOcclusionEnabled,
            boolean transparentChunksEnabled
    ) {
        return new ChunkMeshData(
                buildLayerMeshData(chunk, blockProvider, BlockDefinition.TransparencyType.OPAQUE, ambientOcclusionEnabled),
                buildLayerMeshData(chunk, blockProvider, BlockDefinition.TransparencyType.CUTOUT, ambientOcclusionEnabled),
                transparentChunksEnabled
                        ? buildLayerMeshData(
                                chunk,
                                blockProvider,
                                BlockDefinition.TransparencyType.TRANSPARENT,
                                ambientOcclusionEnabled
                        )
                        : EMPTY_LAYER
        );
    }

    private static ChunkMeshData.LayerMeshData buildLayerMeshData(
            Chunk chunk,
            WorldBlockProvider blockProvider,
            BlockDefinition.TransparencyType transparencyType,
            boolean ambientOcclusionEnabled
    ) {
        FaceBuffer buffer = new FaceBuffer(Math.max(256, SIZE * SIZE));
        int[] mask = new int[SIZE * SIZE];

        meshPositiveX(chunk, blockProvider, transparencyType, ambientOcclusionEnabled, mask, buffer);
        meshNegativeX(chunk, blockProvider, transparencyType, ambientOcclusionEnabled, mask, buffer);
        meshPositiveY(chunk, blockProvider, transparencyType, ambientOcclusionEnabled, mask, buffer);
        meshNegativeY(chunk, blockProvider, transparencyType, ambientOcclusionEnabled, mask, buffer);
        meshPositiveZ(chunk, blockProvider, transparencyType, ambientOcclusionEnabled, mask, buffer);
        meshNegativeZ(chunk, blockProvider, transparencyType, ambientOcclusionEnabled, mask, buffer);

        return new ChunkMeshData.LayerMeshData(buffer.toArray(), buffer.faceCount());
    }

    private static void meshPositiveX(
            Chunk chunk,
            WorldBlockProvider blockProvider,
            BlockDefinition.TransparencyType transparencyType,
            boolean ambientOcclusionEnabled,
            int[] mask,
            FaceBuffer buffer
    ) {
        for (int x = 0; x < SIZE; x++) {
            final int fixedX = x;
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    mask[y * SIZE + z] = createMaskEntry(
                            chunk,
                            blockProvider,
                            transparencyType,
                            ambientOcclusionEnabled,
                            FACE_POS_X,
                            x,
                            y,
                            z,
                            x + 1,
                            y,
                            z
                    );
                }
            }

            greedyMerge(mask, (startU, startV, width, height, textureIndex) ->
                    buffer.addFace(encodeFace(fixedX, startV, startU, FACE_POS_X, width, height), textureIndex));
        }
    }

    private static void meshNegativeX(
            Chunk chunk,
            WorldBlockProvider blockProvider,
            BlockDefinition.TransparencyType transparencyType,
            boolean ambientOcclusionEnabled,
            int[] mask,
            FaceBuffer buffer
    ) {
        for (int x = 0; x < SIZE; x++) {
            final int fixedX = x;
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    mask[y * SIZE + z] = createMaskEntry(
                            chunk,
                            blockProvider,
                            transparencyType,
                            ambientOcclusionEnabled,
                            FACE_NEG_X,
                            x,
                            y,
                            z,
                            x - 1,
                            y,
                            z
                    );
                }
            }

            greedyMerge(mask, (startU, startV, width, height, textureIndex) ->
                    buffer.addFace(encodeFace(fixedX, startV, startU, FACE_NEG_X, width, height), textureIndex));
        }
    }

    private static void meshPositiveY(
            Chunk chunk,
            WorldBlockProvider blockProvider,
            BlockDefinition.TransparencyType transparencyType,
            boolean ambientOcclusionEnabled,
            int[] mask,
            FaceBuffer buffer
    ) {
        for (int y = 0; y < SIZE; y++) {
            final int fixedY = y;
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    mask[z * SIZE + x] = createMaskEntry(
                            chunk,
                            blockProvider,
                            transparencyType,
                            ambientOcclusionEnabled,
                            FACE_POS_Y,
                            x,
                            y,
                            z,
                            x,
                            y + 1,
                            z
                    );
                }
            }

            greedyMerge(mask, (startU, startV, width, height, textureIndex) ->
                    buffer.addFace(encodeFace(startU, fixedY, startV, FACE_POS_Y, width, height), textureIndex));
        }
    }

    private static void meshNegativeY(
            Chunk chunk,
            WorldBlockProvider blockProvider,
            BlockDefinition.TransparencyType transparencyType,
            boolean ambientOcclusionEnabled,
            int[] mask,
            FaceBuffer buffer
    ) {
        for (int y = 0; y < SIZE; y++) {
            final int fixedY = y;
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    mask[z * SIZE + x] = createMaskEntry(
                            chunk,
                            blockProvider,
                            transparencyType,
                            ambientOcclusionEnabled,
                            FACE_NEG_Y,
                            x,
                            y,
                            z,
                            x,
                            y - 1,
                            z
                    );
                }
            }

            greedyMerge(mask, (startU, startV, width, height, textureIndex) ->
                    buffer.addFace(encodeFace(startU, fixedY, startV, FACE_NEG_Y, width, height), textureIndex));
        }
    }

    private static void meshPositiveZ(
            Chunk chunk,
            WorldBlockProvider blockProvider,
            BlockDefinition.TransparencyType transparencyType,
            boolean ambientOcclusionEnabled,
            int[] mask,
            FaceBuffer buffer
    ) {
        for (int z = 0; z < SIZE; z++) {
            final int fixedZ = z;
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    mask[y * SIZE + x] = createMaskEntry(
                            chunk,
                            blockProvider,
                            transparencyType,
                            ambientOcclusionEnabled,
                            FACE_POS_Z,
                            x,
                            y,
                            z,
                            x,
                            y,
                            z + 1
                    );
                }
            }

            greedyMerge(mask, (startU, startV, width, height, textureIndex) ->
                    buffer.addFace(encodeFace(startU, startV, fixedZ, FACE_POS_Z, width, height), textureIndex));
        }
    }

    private static void meshNegativeZ(
            Chunk chunk,
            WorldBlockProvider blockProvider,
            BlockDefinition.TransparencyType transparencyType,
            boolean ambientOcclusionEnabled,
            int[] mask,
            FaceBuffer buffer
    ) {
        for (int z = 0; z < SIZE; z++) {
            final int fixedZ = z;
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    mask[y * SIZE + x] = createMaskEntry(
                            chunk,
                            blockProvider,
                            transparencyType,
                            ambientOcclusionEnabled,
                            FACE_NEG_Z,
                            x,
                            y,
                            z,
                            x,
                            y,
                            z - 1
                    );
                }
            }

            greedyMerge(mask, (startU, startV, width, height, textureIndex) ->
                    buffer.addFace(encodeFace(startU, startV, fixedZ, FACE_NEG_Z, width, height), textureIndex));
        }
    }

    private static void greedyMerge(int[] mask, QuadConsumer consumer) {
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; ) {
                int key = mask[row * SIZE + col];
                if (key == -1) {
                    col++;
                    continue;
                }

                int width = 1;
                while (col + width < SIZE && mask[row * SIZE + col + width] == key) {
                    width++;
                }

                int height = 1;
                boolean canGrow = true;
                while (row + height < SIZE && canGrow) {
                    for (int dx = 0; dx < width; dx++) {
                        if (mask[(row + height) * SIZE + col + dx] != key) {
                            canGrow = false;
                            break;
                        }
                    }
                    if (canGrow) {
                        height++;
                    }
                }

                for (int dy = 0; dy < height; dy++) {
                    Arrays.fill(mask, (row + dy) * SIZE + col, (row + dy) * SIZE + col + width, -1);
                }

                consumer.emit(col, row, width, height, key);
                col += width;
            }
        }
    }

    private static int createMaskEntry(
            Chunk chunk,
            WorldBlockProvider blockProvider,
            BlockDefinition.TransparencyType transparencyType,
            boolean ambientOcclusionEnabled,
            int faceDirection,
            int x,
            int y,
            int z,
            int neighborX,
            int neighborY,
            int neighborZ
    ) {
        short blockId = chunk.getBlock(x, y, z);
        if (blockId == Blocks.AIR.getId()) {
            return -1;
        }

        BlockDefinition blockDefinition = BlockRegistry.getBlock(blockId);
        if (blockDefinition == null || blockDefinition.getTransparencyType() != transparencyType) {
            return -1;
        }

        if (!shouldEmitFace(chunk, blockProvider, blockDefinition, neighborX, neighborY, neighborZ)) {
            return -1;
        }

        if (transparencyType == BlockDefinition.TransparencyType.OPAQUE && ambientOcclusionEnabled) {
            int aoPacked = VoxelAmbientOcclusion.computeOpaqueAoPacked(chunk, blockProvider, x, y, z, faceDirection);
            return VoxelAmbientOcclusion.packOpaqueFaceData(blockDefinition.getTextureIndex(), aoPacked);
        }

        return blockDefinition.getTextureIndex();
    }

    private static boolean shouldEmitFace(
            Chunk chunk,
            WorldBlockProvider blockProvider,
            BlockDefinition currentBlock,
            int neighborX,
            int neighborY,
            int neighborZ
    ) {
        short neighborBlockId = getNeighborBlock(chunk, blockProvider, neighborX, neighborY, neighborZ);
        if (neighborBlockId == Blocks.AIR.getId()) {
            return true;
        }

        BlockDefinition neighborBlock = BlockRegistry.getBlock(neighborBlockId);
        if (neighborBlock == null) {
            return true;
        }

        if (neighborBlock == currentBlock) {
            return !currentBlock.isCullSameTypeFaces();
        }

        if (currentBlock.isOpaque()) {
            return !neighborBlock.isOpaque();
        }

        if (currentBlock.isCutout()) {
            return neighborBlock.isTransparent() || (neighborBlock.isCutout() && neighborBlock != currentBlock);
        }

        if (currentBlock.isTransparent()) {
            return !neighborBlock.isTransparent() || neighborBlock != currentBlock;
        }

        return false;
    }

    private static short getNeighborBlock(
            Chunk chunk,
            WorldBlockProvider blockProvider,
            int neighborX,
            int neighborY,
            int neighborZ
    ) {
        if (chunk.isInBounds(neighborX, neighborY, neighborZ)) {
            return chunk.getBlock(neighborX, neighborY, neighborZ);
        }

        int worldX = chunk.getPosition().x * SIZE + neighborX;
        int worldY = chunk.getPosition().y * SIZE + neighborY;
        int worldZ = chunk.getPosition().z * SIZE + neighborZ;
        return blockProvider.getBlockAtWorld(worldX, worldY, worldZ);
    }

    /**
     * Pack: x(5) | y(5) | z(5) | face(3) | width-1(5) | height-1(5)
     */
    private static int encodeFace(int x, int y, int z, int faceDirection, int width, int height) {
        return (x & 0x1F)
                | ((y & 0x1F) << 5)
                | ((z & 0x1F) << 10)
                | ((faceDirection & 0x07) << 15)
                | (((width - 1) & 0x1F) << 18)
                | (((height - 1) & 0x1F) << 23);
    }

    @FunctionalInterface
    private interface QuadConsumer {
        void emit(int startU, int startV, int width, int height, int textureIndex);
    }

    private static final class FaceBuffer {
        private int[] data;
        private int faceCount;

        private FaceBuffer(int initialFaceCapacity) {
            data = new int[Math.max(initialFaceCapacity * 2, 2)];
        }

        private void addFace(int encodedFace, int textureIndex) {
            int dataIndex = faceCount * 2;
            ensureCapacity(dataIndex + 2);
            data[dataIndex] = encodedFace;
            data[dataIndex + 1] = textureIndex;
            faceCount++;
        }

        private void ensureCapacity(int requiredInts) {
            if (requiredInts <= data.length) {
                return;
            }
            data = Arrays.copyOf(data, Math.max(data.length * 2, requiredInts));
        }

        private int[] toArray() {
            return Arrays.copyOf(data, faceCount * 2);
        }

        private int faceCount() {
            return faceCount;
        }
    }
}
