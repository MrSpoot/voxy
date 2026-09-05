package org.weaw.game;

import org.joml.Vector3i;
import org.weaw.game.utils.BlockCatalog;
import org.weaw.game.utils.BlockDefinition;
import org.weaw.game.utils.Blocks;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Immutable block snapshot containing a one-block halo around a chunk. */
public final class ChunkMeshingSnapshot {
    private static final int EXTENDED_SIZE = Chunk.SIZE + 2;

    private final Vector3i position;
    private final BlockCatalog blockCatalog;
    private final short[] blocks;
    private final int transparencyLayerMask;
    private final boolean waterLayerPresent;
    private final boolean genericTransparentLayerPresent;

    public static ChunkMeshingSnapshot capture(
            Chunk chunk,
            WorldBlockProvider blockProvider,
            BooleanSupplier cancelled
    ) {
        Objects.requireNonNull(chunk, "chunk");
        Objects.requireNonNull(blockProvider, "blockProvider");
        Objects.requireNonNull(cancelled, "cancelled");
        Vector3i position = new Vector3i(chunk.getPosition());
        short[] blocks = new short[EXTENDED_SIZE * EXTENDED_SIZE * EXTENDED_SIZE];
        int originX = position.x * Chunk.SIZE;
        int originY = position.y * Chunk.SIZE;
        int originZ = position.z * Chunk.SIZE;

        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Chunk meshing snapshot cancelled");
        }
        blockProvider.fillBlockRegion(
                originX - 1,
                originY - 1,
                originZ - 1,
                EXTENDED_SIZE,
                EXTENDED_SIZE,
                EXTENDED_SIZE,
                blocks
        );
        if (cancelled.getAsBoolean()) {
            throw new CancellationException("Chunk meshing snapshot cancelled");
        }
        for (int localY = 0; localY < Chunk.SIZE; localY++) {
            for (int localZ = 0; localZ < Chunk.SIZE; localZ++) {
                for (int localX = 0; localX < Chunk.SIZE; localX++) {
                    blocks[index(localX, localY, localZ)] = chunk.getBlock(localX, localY, localZ);
                }
            }
        }
        int transparencyLayerMask = 0;
        for (org.weaw.game.utils.BlockDefinition.TransparencyType type
                : org.weaw.game.utils.BlockDefinition.TransparencyType.values()) {
            if (chunk.containsTransparencyType(type)) {
                transparencyLayerMask |= 1 << type.ordinal();
            }
        }
        BlockDefinition waterDefinition = chunk.getBlockCatalog().getBlock(Blocks.WATER.getStableId());
        short waterBlockId = waterDefinition == null ? Short.MIN_VALUE : waterDefinition.getId();
        return new ChunkMeshingSnapshot(
                position,
                chunk.getBlockCatalog(),
                blocks,
                transparencyLayerMask,
                waterDefinition != null && chunk.containsBlock(waterBlockId),
                chunk.containsTransparencyTypeExcept(
                        BlockDefinition.TransparencyType.TRANSPARENT,
                        waterBlockId
                )
        );
    }

    private ChunkMeshingSnapshot(
            Vector3i position,
            BlockCatalog blockCatalog,
            short[] blocks,
            int transparencyLayerMask,
            boolean waterLayerPresent,
            boolean genericTransparentLayerPresent
    ) {
        this.position = position;
        this.blockCatalog = blockCatalog;
        this.blocks = blocks;
        this.transparencyLayerMask = transparencyLayerMask;
        this.waterLayerPresent = waterLayerPresent;
        this.genericTransparentLayerPresent = genericTransparentLayerPresent;
    }

    public short getBlock(int localX, int localY, int localZ) {
        if (localX < -1 || localX > Chunk.SIZE
                || localY < -1 || localY > Chunk.SIZE
                || localZ < -1 || localZ > Chunk.SIZE) {
            throw new IndexOutOfBoundsException(
                    "Meshing coordinates outside one-block halo: " + localX + ", " + localY + ", " + localZ
            );
        }
        return blocks[index(localX, localY, localZ)];
    }

    public Vector3i position() {
        return new Vector3i(position);
    }

    public BlockCatalog blockCatalog() {
        return blockCatalog;
    }

    public int sampledBlockCount() {
        return blocks.length;
    }

    public boolean containsTransparencyType(org.weaw.game.utils.BlockDefinition.TransparencyType type) {
        return (transparencyLayerMask & (1 << type.ordinal())) != 0;
    }

    public boolean hasWaterLayer() {
        return waterLayerPresent;
    }

    public boolean hasGenericTransparentLayer() {
        return genericTransparentLayerPresent;
    }

    private static int index(int localX, int localY, int localZ) {
        return (localX + 1)
                + ((localZ + 1) * EXTENDED_SIZE)
                + ((localY + 1) * EXTENDED_SIZE * EXTENDED_SIZE);
    }
}
