package org.weaw.game.utils;

import org.weaw.game.ChunkMeshData;
import org.weaw.game.ChunkMeshingMetrics;
import org.weaw.game.ChunkMeshingSnapshot;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

public final class LegacyChunkMeshBuilder {
    private static final ChunkMeshData.LayerMeshData EMPTY_LAYER = new ChunkMeshData.LayerMeshData(new int[0], 0);
    private static final int FACE_POS_X = 0;
    private static final int FACE_NEG_X = 1;
    private static final int FACE_POS_Y = 2;
    private static final int FACE_NEG_Y = 3;
    private static final int FACE_POS_Z = 4;
    private static final int FACE_NEG_Z = 5;

    private LegacyChunkMeshBuilder() {
    }

    public static ChunkMeshData buildMeshData(
            ChunkMeshingSnapshot snapshot,
            boolean ambientOcclusionEnabled,
            boolean transparentChunksEnabled,
            BooleanSupplier cancelled,
            ChunkMeshingMetrics.Recorder metrics
    ) {
        int initialCapacity = Math.max(estimateVisibleFaces(snapshot), 1);
        int[] opaqueFaces = new int[initialCapacity * 2];
        int[] cutoutFaces = new int[initialCapacity * 2];
        int[] transparentFaces = transparentChunksEnabled ? new int[initialCapacity * 2] : new int[0];
        int opaqueCount = 0;
        int cutoutCount = 0;
        int transparentCount = 0;

        long classificationStartNs = System.nanoTime();
        for (int y = 0; y < org.weaw.game.Chunk.SIZE; y++) {
            throwIfCancelled(cancelled);
            for (int z = 0; z < org.weaw.game.Chunk.SIZE; z++) {
                for (int x = 0; x < org.weaw.game.Chunk.SIZE; x++) {
                    short blockId = snapshot.getBlock(x, y, z);
                    if (blockId == snapshot.blockCatalog().air().getId()) {
                        continue;
                    }

                    BlockDefinition blockDefinition = snapshot.blockCatalog().getBlock(blockId);
                    if (blockDefinition == null) {
                        continue;
                    }
                    if (!transparentChunksEnabled && blockDefinition.isTransparent()) {
                        continue;
                    }

                    int textureIndex = blockDefinition.getTextureIndex();
                    MeshLayer layer = blockDefinition.isTransparent()
                            ? MeshLayer.TRANSPARENT
                            : (blockDefinition.isCutout() ? MeshLayer.CUTOUT : MeshLayer.OPAQUE);

                    if (shouldEmitFace(snapshot, blockDefinition, x + 1, y, z)) {
                        int facePayload = layer == MeshLayer.OPAQUE
                                ? packOpaqueFacePayload(
                                        snapshot,
                                        textureIndex,
                                        ambientOcclusionEnabled,
                                        metrics,
                                        x,
                                        y,
                                        z,
                                        FACE_POS_X
                                )
                                : transparentFacePayload(snapshot, blockDefinition, textureIndex, x, y, z, FACE_POS_X);
                        if (layer == MeshLayer.OPAQUE) {
                            opaqueFaces = ensureCapacity(opaqueFaces, opaqueCount);
                            writeFace(opaqueFaces, opaqueCount++, encodeFace(x, y, z, FACE_POS_X, 1, 1), facePayload);
                        } else if (layer == MeshLayer.CUTOUT) {
                            cutoutFaces = ensureCapacity(cutoutFaces, cutoutCount);
                            writeFace(cutoutFaces, cutoutCount++, encodeFace(x, y, z, FACE_POS_X, 1, 1), facePayload);
                        } else {
                            transparentFaces = ensureCapacity(transparentFaces, transparentCount);
                            writeFace(transparentFaces, transparentCount++, encodeFace(x, y, z, FACE_POS_X, 1, 1), facePayload);
                        }
                    }
                    if (shouldEmitFace(snapshot, blockDefinition, x - 1, y, z)) {
                        int facePayload = layer == MeshLayer.OPAQUE
                                ? packOpaqueFacePayload(
                                        snapshot,
                                        textureIndex,
                                        ambientOcclusionEnabled,
                                        metrics,
                                        x,
                                        y,
                                        z,
                                        FACE_NEG_X
                                )
                                : transparentFacePayload(snapshot, blockDefinition, textureIndex, x, y, z, FACE_NEG_X);
                        if (layer == MeshLayer.OPAQUE) {
                            opaqueFaces = ensureCapacity(opaqueFaces, opaqueCount);
                            writeFace(opaqueFaces, opaqueCount++, encodeFace(x, y, z, FACE_NEG_X, 1, 1), facePayload);
                        } else if (layer == MeshLayer.CUTOUT) {
                            cutoutFaces = ensureCapacity(cutoutFaces, cutoutCount);
                            writeFace(cutoutFaces, cutoutCount++, encodeFace(x, y, z, FACE_NEG_X, 1, 1), facePayload);
                        } else {
                            transparentFaces = ensureCapacity(transparentFaces, transparentCount);
                            writeFace(transparentFaces, transparentCount++, encodeFace(x, y, z, FACE_NEG_X, 1, 1), facePayload);
                        }
                    }
                    if (shouldEmitFace(snapshot, blockDefinition, x, y + 1, z)) {
                        int facePayload = layer == MeshLayer.OPAQUE
                                ? packOpaqueFacePayload(
                                        snapshot,
                                        textureIndex,
                                        ambientOcclusionEnabled,
                                        metrics,
                                        x,
                                        y,
                                        z,
                                        FACE_POS_Y
                                )
                                : transparentFacePayload(snapshot, blockDefinition, textureIndex, x, y, z, FACE_POS_Y);
                        if (layer == MeshLayer.OPAQUE) {
                            opaqueFaces = ensureCapacity(opaqueFaces, opaqueCount);
                            writeFace(opaqueFaces, opaqueCount++, encodeFace(x, y, z, FACE_POS_Y, 1, 1), facePayload);
                        } else if (layer == MeshLayer.CUTOUT) {
                            cutoutFaces = ensureCapacity(cutoutFaces, cutoutCount);
                            writeFace(cutoutFaces, cutoutCount++, encodeFace(x, y, z, FACE_POS_Y, 1, 1), facePayload);
                        } else {
                            transparentFaces = ensureCapacity(transparentFaces, transparentCount);
                            writeFace(transparentFaces, transparentCount++, encodeFace(x, y, z, FACE_POS_Y, 1, 1), facePayload);
                        }
                    }
                    if (shouldEmitFace(snapshot, blockDefinition, x, y - 1, z)) {
                        int facePayload = layer == MeshLayer.OPAQUE
                                ? packOpaqueFacePayload(
                                        snapshot,
                                        textureIndex,
                                        ambientOcclusionEnabled,
                                        metrics,
                                        x,
                                        y,
                                        z,
                                        FACE_NEG_Y
                                )
                                : transparentFacePayload(snapshot, blockDefinition, textureIndex, x, y, z, FACE_NEG_Y);
                        if (layer == MeshLayer.OPAQUE) {
                            opaqueFaces = ensureCapacity(opaqueFaces, opaqueCount);
                            writeFace(opaqueFaces, opaqueCount++, encodeFace(x, y, z, FACE_NEG_Y, 1, 1), facePayload);
                        } else if (layer == MeshLayer.CUTOUT) {
                            cutoutFaces = ensureCapacity(cutoutFaces, cutoutCount);
                            writeFace(cutoutFaces, cutoutCount++, encodeFace(x, y, z, FACE_NEG_Y, 1, 1), facePayload);
                        } else {
                            transparentFaces = ensureCapacity(transparentFaces, transparentCount);
                            writeFace(transparentFaces, transparentCount++, encodeFace(x, y, z, FACE_NEG_Y, 1, 1), facePayload);
                        }
                    }
                    if (shouldEmitFace(snapshot, blockDefinition, x, y, z + 1)) {
                        int facePayload = layer == MeshLayer.OPAQUE
                                ? packOpaqueFacePayload(
                                        snapshot,
                                        textureIndex,
                                        ambientOcclusionEnabled,
                                        metrics,
                                        x,
                                        y,
                                        z,
                                        FACE_POS_Z
                                )
                                : transparentFacePayload(snapshot, blockDefinition, textureIndex, x, y, z, FACE_POS_Z);
                        if (layer == MeshLayer.OPAQUE) {
                            opaqueFaces = ensureCapacity(opaqueFaces, opaqueCount);
                            writeFace(opaqueFaces, opaqueCount++, encodeFace(x, y, z, FACE_POS_Z, 1, 1), facePayload);
                        } else if (layer == MeshLayer.CUTOUT) {
                            cutoutFaces = ensureCapacity(cutoutFaces, cutoutCount);
                            writeFace(cutoutFaces, cutoutCount++, encodeFace(x, y, z, FACE_POS_Z, 1, 1), facePayload);
                        } else {
                            transparentFaces = ensureCapacity(transparentFaces, transparentCount);
                            writeFace(transparentFaces, transparentCount++, encodeFace(x, y, z, FACE_POS_Z, 1, 1), facePayload);
                        }
                    }
                    if (shouldEmitFace(snapshot, blockDefinition, x, y, z - 1)) {
                        int facePayload = layer == MeshLayer.OPAQUE
                                ? packOpaqueFacePayload(
                                        snapshot,
                                        textureIndex,
                                        ambientOcclusionEnabled,
                                        metrics,
                                        x,
                                        y,
                                        z,
                                        FACE_NEG_Z
                                )
                                : transparentFacePayload(snapshot, blockDefinition, textureIndex, x, y, z, FACE_NEG_Z);
                        if (layer == MeshLayer.OPAQUE) {
                            opaqueFaces = ensureCapacity(opaqueFaces, opaqueCount);
                            writeFace(opaqueFaces, opaqueCount++, encodeFace(x, y, z, FACE_NEG_Z, 1, 1), facePayload);
                        } else if (layer == MeshLayer.CUTOUT) {
                            cutoutFaces = ensureCapacity(cutoutFaces, cutoutCount);
                            writeFace(cutoutFaces, cutoutCount++, encodeFace(x, y, z, FACE_NEG_Z, 1, 1), facePayload);
                        } else {
                            transparentFaces = ensureCapacity(transparentFaces, transparentCount);
                            writeFace(transparentFaces, transparentCount++, encodeFace(x, y, z, FACE_NEG_Z, 1, 1), facePayload);
                        }
                    }
                }
            }
        }

        metrics.recordFaceClassification(System.nanoTime() - classificationStartNs);
        long outputStartNs = System.nanoTime();
        ChunkMeshData meshData = new ChunkMeshData(
                new ChunkMeshData.LayerMeshData(trimFaces(opaqueFaces, opaqueCount), opaqueCount),
                new ChunkMeshData.LayerMeshData(trimFaces(cutoutFaces, cutoutCount), cutoutCount),
                transparentChunksEnabled
                        ? new ChunkMeshData.LayerMeshData(trimFaces(transparentFaces, transparentCount), transparentCount)
                        : EMPTY_LAYER
        );
        metrics.recordOutputBuild(System.nanoTime() - outputStartNs);
        return meshData;
    }

    private static int packOpaqueFacePayload(
            ChunkMeshingSnapshot snapshot,
            int textureIndex,
            boolean ambientOcclusionEnabled,
            ChunkMeshingMetrics.Recorder metrics,
            int x,
            int y,
            int z,
            int faceDirection
    ) {
        if (!ambientOcclusionEnabled) {
            return textureIndex;
        }

        metrics.recordAmbientOcclusionFace();
        return VoxelAmbientOcclusion.packOpaqueFaceData(
                textureIndex,
                VoxelAmbientOcclusion.computeOpaqueAoPacked(snapshot, x, y, z, faceDirection)
        );
    }

    private static int transparentFacePayload(
            ChunkMeshingSnapshot snapshot,
            BlockDefinition block,
            int textureIndex,
            int x,
            int y,
            int z,
            int face
    ) {
        return block.isTransparent()
                ? TransparentFaceData.pack(snapshot, block, textureIndex, x, y, z, face)
                : textureIndex;
    }

    private static boolean shouldEmitFace(
            ChunkMeshingSnapshot snapshot,
            BlockDefinition currentBlock,
            int neighborX,
            int neighborY,
            int neighborZ
    ) {
        short neighborBlockId = snapshot.getBlock(neighborX, neighborY, neighborZ);
        if (neighborBlockId == snapshot.blockCatalog().air().getId()) {
            return true;
        }

        BlockDefinition neighborBlock = snapshot.blockCatalog().getBlock(neighborBlockId);
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

    private static int estimateVisibleFaces(ChunkMeshingSnapshot snapshot) {
        int nonAirBlocks = 0;
        for (int y = 0; y < org.weaw.game.Chunk.SIZE; y++) {
            for (int z = 0; z < org.weaw.game.Chunk.SIZE; z++) {
                for (int x = 0; x < org.weaw.game.Chunk.SIZE; x++) {
                    if (snapshot.getBlock(x, y, z) != snapshot.blockCatalog().air().getId()) {
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

    private static void throwIfCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Chunk meshing cancelled");
        }
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
        CUTOUT,
        TRANSPARENT
    }
}
