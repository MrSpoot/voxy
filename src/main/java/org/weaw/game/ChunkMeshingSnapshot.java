package org.weaw.game;

import org.joml.Vector3i;
import org.weaw.game.utils.BlockCatalog;

import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/** Immutable block snapshot containing a one-block halo around a chunk. */
public final class ChunkMeshingSnapshot {
    private static final int EXTENDED_SIZE = Chunk.SIZE + 2;

    private final Vector3i position;
    private final BlockCatalog blockCatalog;
    private final short[] blocks;

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

        for (int localY = -1; localY <= Chunk.SIZE; localY++) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Chunk meshing snapshot cancelled");
            }
            for (int localZ = -1; localZ <= Chunk.SIZE; localZ++) {
                for (int localX = -1; localX <= Chunk.SIZE; localX++) {
                    short block = chunk.isInBounds(localX, localY, localZ)
                            ? chunk.getBlock(localX, localY, localZ)
                            : blockProvider.getBlockAtWorld(
                                    originX + localX,
                                    originY + localY,
                                    originZ + localZ
                            );
                    blocks[index(localX, localY, localZ)] = block;
                }
            }
        }
        return new ChunkMeshingSnapshot(position, chunk.getBlockCatalog(), blocks);
    }

    private ChunkMeshingSnapshot(Vector3i position, BlockCatalog blockCatalog, short[] blocks) {
        this.position = position;
        this.blockCatalog = blockCatalog;
        this.blocks = blocks;
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

    private static int index(int localX, int localY, int localZ) {
        return (localX + 1)
                + ((localZ + 1) * EXTENDED_SIZE)
                + ((localY + 1) * EXTENDED_SIZE * EXTENDED_SIZE);
    }
}
