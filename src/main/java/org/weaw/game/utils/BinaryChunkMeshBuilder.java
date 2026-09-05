package org.weaw.game.utils;

import org.weaw.game.Chunk;
import org.weaw.game.ChunkMeshData;
import org.weaw.game.ChunkMeshingMetrics;
import org.weaw.game.ChunkMeshingSnapshot;

import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

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
            ChunkMeshingSnapshot snapshot,
            boolean ambientOcclusionEnabled,
            boolean transparentChunksEnabled,
            BooleanSupplier cancelled,
            ChunkMeshingMetrics.Recorder metrics
    ) {
        return new ChunkMeshData(
                buildLayerMeshData(snapshot, BlockDefinition.TransparencyType.OPAQUE, ambientOcclusionEnabled, cancelled, metrics),
                buildLayerMeshData(snapshot, BlockDefinition.TransparencyType.CUTOUT, ambientOcclusionEnabled, cancelled, metrics),
                transparentChunksEnabled
                        ? buildLayerMeshData(
                                snapshot,
                                BlockDefinition.TransparencyType.TRANSPARENT,
                                ambientOcclusionEnabled,
                                cancelled,
                                metrics
                        )
                        : EMPTY_LAYER
        );
    }

    private static ChunkMeshData.LayerMeshData buildLayerMeshData(
            ChunkMeshingSnapshot snapshot,
            BlockDefinition.TransparencyType transparencyType,
            boolean ambientOcclusionEnabled,
            BooleanSupplier cancelled,
            ChunkMeshingMetrics.Recorder metrics
    ) {
        FaceBuffer buffer = new FaceBuffer(Math.max(256, SIZE * SIZE));
        int[] mask = new int[SIZE * SIZE];

        meshPositiveX(snapshot, transparencyType, ambientOcclusionEnabled, mask, buffer, cancelled, metrics);
        meshNegativeX(snapshot, transparencyType, ambientOcclusionEnabled, mask, buffer, cancelled, metrics);
        meshPositiveY(snapshot, transparencyType, ambientOcclusionEnabled, mask, buffer, cancelled, metrics);
        meshNegativeY(snapshot, transparencyType, ambientOcclusionEnabled, mask, buffer, cancelled, metrics);
        meshPositiveZ(snapshot, transparencyType, ambientOcclusionEnabled, mask, buffer, cancelled, metrics);
        meshNegativeZ(snapshot, transparencyType, ambientOcclusionEnabled, mask, buffer, cancelled, metrics);

        long outputStartNs = System.nanoTime();
        ChunkMeshData.LayerMeshData layer = new ChunkMeshData.LayerMeshData(buffer.toArray(), buffer.faceCount());
        metrics.recordOutputBuild(System.nanoTime() - outputStartNs);
        return layer;
    }

    private static void meshPositiveX(
            ChunkMeshingSnapshot snapshot,
            BlockDefinition.TransparencyType transparencyType,
            boolean ambientOcclusionEnabled,
            int[] mask,
            FaceBuffer buffer,
            BooleanSupplier cancelled,
            ChunkMeshingMetrics.Recorder metrics
    ) {
        for (int x = 0; x < SIZE; x++) {
            throwIfCancelled(cancelled);
            final int fixedX = x;
            long classificationStartNs = System.nanoTime();
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    mask[y * SIZE + z] = createMaskEntry(
                            snapshot,
                            transparencyType,
                            ambientOcclusionEnabled,
                            FACE_POS_X,
                            x,
                            y,
                            z,
                            x + 1,
                            y,
                            z,
                            metrics
                    );
                }
            }
            metrics.recordFaceClassification(System.nanoTime() - classificationStartNs);

            long mergeStartNs = System.nanoTime();
            greedyMerge(mask, (startU, startV, width, height, textureIndex) ->
                    buffer.addFace(encodeFace(fixedX, startV, startU, FACE_POS_X, width, height), textureIndex));
            metrics.recordGreedyMerge(System.nanoTime() - mergeStartNs);
        }
    }

    private static void meshNegativeX(
            ChunkMeshingSnapshot snapshot,
            BlockDefinition.TransparencyType transparencyType,
            boolean ambientOcclusionEnabled,
            int[] mask,
            FaceBuffer buffer,
            BooleanSupplier cancelled,
            ChunkMeshingMetrics.Recorder metrics
    ) {
        for (int x = 0; x < SIZE; x++) {
            throwIfCancelled(cancelled);
            final int fixedX = x;
            long classificationStartNs = System.nanoTime();
            for (int y = 0; y < SIZE; y++) {
                for (int z = 0; z < SIZE; z++) {
                    mask[y * SIZE + z] = createMaskEntry(
                            snapshot,
                            transparencyType,
                            ambientOcclusionEnabled,
                            FACE_NEG_X,
                            x,
                            y,
                            z,
                            x - 1,
                            y,
                            z,
                            metrics
                    );
                }
            }
            metrics.recordFaceClassification(System.nanoTime() - classificationStartNs);

            long mergeStartNs = System.nanoTime();
            greedyMerge(mask, (startU, startV, width, height, textureIndex) ->
                    buffer.addFace(encodeFace(fixedX, startV, startU, FACE_NEG_X, width, height), textureIndex));
            metrics.recordGreedyMerge(System.nanoTime() - mergeStartNs);
        }
    }

    private static void meshPositiveY(
            ChunkMeshingSnapshot snapshot,
            BlockDefinition.TransparencyType transparencyType,
            boolean ambientOcclusionEnabled,
            int[] mask,
            FaceBuffer buffer,
            BooleanSupplier cancelled,
            ChunkMeshingMetrics.Recorder metrics
    ) {
        for (int y = 0; y < SIZE; y++) {
            throwIfCancelled(cancelled);
            final int fixedY = y;
            long classificationStartNs = System.nanoTime();
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    mask[z * SIZE + x] = createMaskEntry(
                            snapshot,
                            transparencyType,
                            ambientOcclusionEnabled,
                            FACE_POS_Y,
                            x,
                            y,
                            z,
                            x,
                            y + 1,
                            z,
                            metrics
                    );
                }
            }
            metrics.recordFaceClassification(System.nanoTime() - classificationStartNs);

            long mergeStartNs = System.nanoTime();
            greedyMerge(mask, (startU, startV, width, height, textureIndex) ->
                    buffer.addFace(encodeFace(startU, fixedY, startV, FACE_POS_Y, width, height), textureIndex));
            metrics.recordGreedyMerge(System.nanoTime() - mergeStartNs);
        }
    }

    private static void meshNegativeY(
            ChunkMeshingSnapshot snapshot,
            BlockDefinition.TransparencyType transparencyType,
            boolean ambientOcclusionEnabled,
            int[] mask,
            FaceBuffer buffer,
            BooleanSupplier cancelled,
            ChunkMeshingMetrics.Recorder metrics
    ) {
        for (int y = 0; y < SIZE; y++) {
            throwIfCancelled(cancelled);
            final int fixedY = y;
            long classificationStartNs = System.nanoTime();
            for (int z = 0; z < SIZE; z++) {
                for (int x = 0; x < SIZE; x++) {
                    mask[z * SIZE + x] = createMaskEntry(
                            snapshot,
                            transparencyType,
                            ambientOcclusionEnabled,
                            FACE_NEG_Y,
                            x,
                            y,
                            z,
                            x,
                            y - 1,
                            z,
                            metrics
                    );
                }
            }
            metrics.recordFaceClassification(System.nanoTime() - classificationStartNs);

            long mergeStartNs = System.nanoTime();
            greedyMerge(mask, (startU, startV, width, height, textureIndex) ->
                    buffer.addFace(encodeFace(startU, fixedY, startV, FACE_NEG_Y, width, height), textureIndex));
            metrics.recordGreedyMerge(System.nanoTime() - mergeStartNs);
        }
    }

    private static void meshPositiveZ(
            ChunkMeshingSnapshot snapshot,
            BlockDefinition.TransparencyType transparencyType,
            boolean ambientOcclusionEnabled,
            int[] mask,
            FaceBuffer buffer,
            BooleanSupplier cancelled,
            ChunkMeshingMetrics.Recorder metrics
    ) {
        for (int z = 0; z < SIZE; z++) {
            throwIfCancelled(cancelled);
            final int fixedZ = z;
            long classificationStartNs = System.nanoTime();
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    mask[y * SIZE + x] = createMaskEntry(
                            snapshot,
                            transparencyType,
                            ambientOcclusionEnabled,
                            FACE_POS_Z,
                            x,
                            y,
                            z,
                            x,
                            y,
                            z + 1,
                            metrics
                    );
                }
            }
            metrics.recordFaceClassification(System.nanoTime() - classificationStartNs);

            long mergeStartNs = System.nanoTime();
            greedyMerge(mask, (startU, startV, width, height, textureIndex) ->
                    buffer.addFace(encodeFace(startU, startV, fixedZ, FACE_POS_Z, width, height), textureIndex));
            metrics.recordGreedyMerge(System.nanoTime() - mergeStartNs);
        }
    }

    private static void meshNegativeZ(
            ChunkMeshingSnapshot snapshot,
            BlockDefinition.TransparencyType transparencyType,
            boolean ambientOcclusionEnabled,
            int[] mask,
            FaceBuffer buffer,
            BooleanSupplier cancelled,
            ChunkMeshingMetrics.Recorder metrics
    ) {
        for (int z = 0; z < SIZE; z++) {
            throwIfCancelled(cancelled);
            final int fixedZ = z;
            long classificationStartNs = System.nanoTime();
            for (int y = 0; y < SIZE; y++) {
                for (int x = 0; x < SIZE; x++) {
                    mask[y * SIZE + x] = createMaskEntry(
                            snapshot,
                            transparencyType,
                            ambientOcclusionEnabled,
                            FACE_NEG_Z,
                            x,
                            y,
                            z,
                            x,
                            y,
                            z - 1,
                            metrics
                    );
                }
            }
            metrics.recordFaceClassification(System.nanoTime() - classificationStartNs);

            long mergeStartNs = System.nanoTime();
            greedyMerge(mask, (startU, startV, width, height, textureIndex) ->
                    buffer.addFace(encodeFace(startU, startV, fixedZ, FACE_NEG_Z, width, height), textureIndex));
            metrics.recordGreedyMerge(System.nanoTime() - mergeStartNs);
        }
    }

    private static void greedyMerge(int[] mask, QuadConsumer consumer) {
        int waterTextureIndex = Blocks.WATER.getTextureIndex();
        for (int row = 0; row < SIZE; row++) {
            for (int col = 0; col < SIZE; ) {
                int key = mask[row * SIZE + col];
                if (key == -1) {
                    col++;
                    continue;
                }

                boolean mergeable = TransparentFaceData.textureIndex(key) != waterTextureIndex;
                int width = 1;
                if (mergeable) {
                    while (col + width < SIZE && mask[row * SIZE + col + width] == key) {
                        width++;
                    }
                }

                int height = 1;
                boolean canGrow = mergeable;
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
            ChunkMeshingSnapshot snapshot,
            BlockDefinition.TransparencyType transparencyType,
            boolean ambientOcclusionEnabled,
            int faceDirection,
            int x,
            int y,
            int z,
            int neighborX,
            int neighborY,
            int neighborZ,
            ChunkMeshingMetrics.Recorder metrics
    ) {
        short blockId = snapshot.getBlock(x, y, z);
        if (blockId == snapshot.blockCatalog().air().getId()) {
            return -1;
        }

        BlockDefinition blockDefinition = snapshot.blockCatalog().getBlock(blockId);
        if (blockDefinition == null || blockDefinition.getTransparencyType() != transparencyType) {
            return -1;
        }

        if (!shouldEmitFace(snapshot, blockDefinition, neighborX, neighborY, neighborZ)) {
            return -1;
        }

        if (transparencyType == BlockDefinition.TransparencyType.OPAQUE && ambientOcclusionEnabled) {
            metrics.recordAmbientOcclusionFace();
            int aoPacked = VoxelAmbientOcclusion.computeOpaqueAoPacked(snapshot, x, y, z, faceDirection);
            return VoxelAmbientOcclusion.packOpaqueFaceData(blockDefinition.getTextureIndex(), aoPacked);
        }

        return transparencyType == BlockDefinition.TransparencyType.TRANSPARENT
                ? TransparentFaceData.pack(
                        snapshot,
                        blockDefinition,
                        blockDefinition.getTextureIndex(),
                        x,
                        y,
                        z,
                        faceDirection
                )
                : blockDefinition.getTextureIndex();
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

    private static void throwIfCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Chunk meshing cancelled");
        }
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
