package org.weaw.game;

import org.joml.Vector3i;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.game.utils.Blocks;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ChunkMeshingSnapshotTest {
    @BeforeAll
    static void initializeBlocks() {
        BlockRegistry.initialize();
    }

    @Test
    void capturesAnImmutableChunkAndOneBlockHalo() {
        Chunk chunk = new Chunk(new Vector3i(-2, 3, 4));
        chunk.setBlock(1, 2, 3, Blocks.DIRT);
        AtomicInteger haloQueries = new AtomicInteger();
        WorldBlockProvider provider = (x, y, z) -> {
            haloQueries.incrementAndGet();
            return Blocks.STONE.getId();
        };

        ChunkMeshingSnapshot snapshot = ChunkMeshingSnapshot.capture(chunk, provider, () -> false);
        chunk.setBlock(1, 2, 3, Blocks.SAND);

        assertEquals(Blocks.DIRT.getId(), snapshot.getBlock(1, 2, 3));
        assertEquals(Blocks.STONE.getId(), snapshot.getBlock(-1, 2, 3));
        assertEquals(34 * 34 * 34, snapshot.sampledBlockCount());
        assertEquals((34 * 34 * 34) - Chunk.TOTAL_BLOCKS, haloQueries.get());
        assertEquals(new Vector3i(-2, 3, 4), snapshot.position());
    }

    @Test
    void cooperativelyCancelsCapture() {
        Chunk chunk = new Chunk(new Vector3i());
        AtomicInteger checks = new AtomicInteger();
        assertThrows(CancellationException.class, () -> ChunkMeshingSnapshot.capture(
                chunk,
                (x, y, z) -> Blocks.AIR.getId(),
                () -> checks.incrementAndGet() >= 2
        ));
    }
}
