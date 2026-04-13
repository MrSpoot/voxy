package org.weaw.game.utils;

import org.weaw.game.Chunk;
import org.weaw.game.ChunkMeshData;
import org.weaw.game.WorldBlockProvider;

public final class LegacyChunkMeshBuilder {
    private static final int FACE_POS_X = 0;
    private static final int FACE_NEG_X = 1;
    private static final int FACE_POS_Y = 2;
    private static final int FACE_NEG_Y = 3;
    private static final int FACE_POS_Z = 4;
    private static final int FACE_NEG_Z = 5;

    private LegacyChunkMeshBuilder() {
    }

    public static ChunkMeshData buildMeshData(Chunk chunk, WorldBlockProvider blockProvider) {
        int initialCapacity = Math.max(estimateVisibleFaces(chunk), 1);
        int[] opaqueFaces = new int[initialCapacity * 2];
        int[] cutoutFaces = new int[initialCapacity * 2];
        int opaqueCount = 0;
        int cutoutCount = 0;

        for (int y = 0; y < Chunk.SIZE; y++) {
            for (int z = 0; z < Chunk.SIZE; z++) {
                for (int x = 0; x < Chunk.SIZE; x++) {
                    short blockId = chunk.getBlock(x, y, z);
                    if (blockId == Blocks.AIR.getId()) {
                        continue;
                    }

                    BlockDefinition blockDefinition = BlockRegistry.getBlock(blockId);
                    if (blockDefinition == null || blockDefinition.isTransparent()) {
                        continue;
                    }

                    int textureIndex = blockDefinition.getTextureIndex();
                    MeshLayer layer = blockDefinition.isCutout() ? MeshLayer.CUTOUT : MeshLayer.OPAQUE;

                    if (shouldEmitFace(chunk, blockProvider, blockDefinition, x + 1, y, z)) {
                        if (layer == MeshLayer.OPAQUE) {
                            opaqueFaces = ensureCapacity(opaqueFaces, opaqueCount);
                            writeFace(opaqueFaces, opaqueCount++, encodeFace(x, y, z, FACE_POS_X, 1, 1), textureIndex);
                        } else {
                            cutoutFaces = ensureCapacity(cutoutFaces, cutoutCount);
                            writeFace(cutoutFaces, cutoutCount++, encodeFace(x, y, z, FACE_POS_X, 1, 1), textureIndex);
                        }
                    }
                    if (shouldEmitFace(chunk, blockProvider, blockDefinition, x - 1, y, z)) {
                        if (layer == MeshLayer.OPAQUE) {
                            opaqueFaces = ensureCapacity(opaqueFaces, opaqueCount);
                            writeFace(opaqueFaces, opaqueCount++, encodeFace(x, y, z, FACE_NEG_X, 1, 1), textureIndex);
                        } else {
                            cutoutFaces = ensureCapacity(cutoutFaces, cutoutCount);
                            writeFace(cutoutFaces, cutoutCount++, encodeFace(x, y, z, FACE_NEG_X, 1, 1), textureIndex);
                        }
                    }
                    if (shouldEmitFace(chunk, blockProvider, blockDefinition, x, y + 1, z)) {
                        if (layer == MeshLayer.OPAQUE) {
                            opaqueFaces = ensureCapacity(opaqueFaces, opaqueCount);
                            writeFace(opaqueFaces, opaqueCount++, encodeFace(x, y, z, FACE_POS_Y, 1, 1), textureIndex);
                        } else {
                            cutoutFaces = ensureCapacity(cutoutFaces, cutoutCount);
                            writeFace(cutoutFaces, cutoutCount++, encodeFace(x, y, z, FACE_POS_Y, 1, 1), textureIndex);
                        }
                    }
                    if (shouldEmitFace(chunk, blockProvider, blockDefinition, x, y - 1, z)) {
                        if (layer == MeshLayer.OPAQUE) {
                            opaqueFaces = ensureCapacity(opaqueFaces, opaqueCount);
                            writeFace(opaqueFaces, opaqueCount++, encodeFace(x, y, z, FACE_NEG_Y, 1, 1), textureIndex);
                        } else {
                            cutoutFaces = ensureCapacity(cutoutFaces, cutoutCount);
                            writeFace(cutoutFaces, cutoutCount++, encodeFace(x, y, z, FACE_NEG_Y, 1, 1), textureIndex);
                        }
                    }
                    if (shouldEmitFace(chunk, blockProvider, blockDefinition, x, y, z + 1)) {
                        if (layer == MeshLayer.OPAQUE) {
                            opaqueFaces = ensureCapacity(opaqueFaces, opaqueCount);
                            writeFace(opaqueFaces, opaqueCount++, encodeFace(x, y, z, FACE_POS_Z, 1, 1), textureIndex);
                        } else {
                            cutoutFaces = ensureCapacity(cutoutFaces, cutoutCount);
                            writeFace(cutoutFaces, cutoutCount++, encodeFace(x, y, z, FACE_POS_Z, 1, 1), textureIndex);
                        }
                    }
                    if (shouldEmitFace(chunk, blockProvider, blockDefinition, x, y, z - 1)) {
                        if (layer == MeshLayer.OPAQUE) {
                            opaqueFaces = ensureCapacity(opaqueFaces, opaqueCount);
                            writeFace(opaqueFaces, opaqueCount++, encodeFace(x, y, z, FACE_NEG_Z, 1, 1), textureIndex);
                        } else {
                            cutoutFaces = ensureCapacity(cutoutFaces, cutoutCount);
                            writeFace(cutoutFaces, cutoutCount++, encodeFace(x, y, z, FACE_NEG_Z, 1, 1), textureIndex);
                        }
                    }
                }
            }
        }

        return new ChunkMeshData(
                new ChunkMeshData.LayerMeshData(trimFaces(opaqueFaces, opaqueCount), opaqueCount),
                new ChunkMeshData.LayerMeshData(trimFaces(cutoutFaces, cutoutCount), cutoutCount)
        );
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

        return false;
    }

    private static int estimateVisibleFaces(Chunk chunk) {
        int nonAirBlocks = 0;
        for (int y = 0; y < Chunk.SIZE; y++) {
            for (int z = 0; z < Chunk.SIZE; z++) {
                for (int x = 0; x < Chunk.SIZE; x++) {
                    if (chunk.getBlock(x, y, z) != Blocks.AIR.getId()) {
                        nonAirBlocks++;
                    }
                }
            }
        }

        return Math.max(nonAirBlocks * 6, 1);
    }

    private static int encodeFace(int x, int y, int z, int faceDirection, int width, int height) {
        return (x & 0x1F)
                | ((y & 0x1F) << 5)
                | ((z & 0x1F) << 10)
                | ((faceDirection & 0x07) << 15)
                | (((width - 1) & 0x1F) << 18)
                | (((height - 1) & 0x1F) << 23);
    }

    private static short getNeighborBlock(Chunk chunk, WorldBlockProvider blockProvider, int neighborX, int neighborY, int neighborZ) {
        if (chunk.isInBounds(neighborX, neighborY, neighborZ)) {
            return chunk.getBlock(neighborX, neighborY, neighborZ);
        }

        int worldX = chunk.getPosition().x * Chunk.SIZE + neighborX;
        int worldY = chunk.getPosition().y * Chunk.SIZE + neighborY;
        int worldZ = chunk.getPosition().z * Chunk.SIZE + neighborZ;
        return blockProvider.getBlockAtWorld(worldX, worldY, worldZ);
    }

    private static int[] ensureCapacity(int[] faceData, int faceCount) {
        int requiredInts = (faceCount + 1) * 2;
        if (requiredInts <= faceData.length) {
            return faceData;
        }

        int[] expanded = new int[Math.max(faceData.length * 2, requiredInts)];
        System.arraycopy(faceData, 0, expanded, 0, faceData.length);
        return expanded;
    }

    private static void writeFace(int[] faceData, int faceIndex, int encodedFace, int textureIndex) {
        int base = faceIndex * 2;
        faceData[base] = encodedFace;
        faceData[base + 1] = textureIndex;
    }

    private static int[] trimFaces(int[] faceData, int faceCount) {
        int[] trimmed = new int[faceCount * 2];
        System.arraycopy(faceData, 0, trimmed, 0, trimmed.length);
        return trimmed;
    }

    private enum MeshLayer {
        OPAQUE,
        CUTOUT
    }
}
