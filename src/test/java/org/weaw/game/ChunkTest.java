package org.weaw.game;

import org.joml.Vector3i;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.game.utils.Blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkTest {
    @BeforeAll
    static void initializeBlocks() {
        BlockRegistry.initialize();
    }

    @Test
    void newChunkStartsAsUniformAir() {
        Chunk chunk = new Chunk(new Vector3i(0, 0, 0));

        assertTrue(chunk.isUniform());
        assertEquals(Blocks.AIR.getId(), chunk.getUniformBlockId());
        assertEquals(Blocks.AIR.getId(), chunk.getBlock(0, 0, 0));
        assertEquals(Blocks.AIR.getId(), chunk.getBlock(Chunk.SIZE - 1, Chunk.SIZE - 1, Chunk.SIZE - 1));
    }

    @Test
    void setBlockSwitchesToPaletteStorageAndPreservesValues() {
        Chunk chunk = new Chunk(new Vector3i(0, 0, 0));

        chunk.setBlock(3, 4, 5, Blocks.STONE);

        assertFalse(chunk.isUniform());
        assertEquals(Blocks.STONE.getId(), chunk.getBlock(3, 4, 5));
        assertEquals(Blocks.AIR.getId(), chunk.getBlock(3, 4, 4));
    }

    @Test
    void paletteCollapsesBackToUniformWhenOnlyOneBlockRemains() {
        Chunk chunk = new Chunk(new Vector3i(0, 0, 0));

        chunk.setBlock(1, 2, 3, Blocks.STONE);
        chunk.setBlock(1, 2, 3, Blocks.AIR);

        assertTrue(chunk.isUniform());
        assertEquals(Blocks.AIR.getId(), chunk.getUniformBlockId());
    }

    @Test
    void setAllBlocksUsesUniformStorageForUniformInput() {
        Chunk chunk = new Chunk(new Vector3i(0, 0, 0));
        short[] blocks = new short[Chunk.TOTAL_BLOCKS];
        java.util.Arrays.fill(blocks, Blocks.DIRT.getId());

        chunk.setAllBlocks(blocks);

        assertTrue(chunk.isUniform());
        assertEquals(Blocks.DIRT.getId(), chunk.getUniformBlockId());
        assertEquals(Blocks.DIRT.getId(), chunk.getBlock(12, 8, 4));
    }

    @Test
    void setAllBlocksBuildsPrimitivePaletteAndPreservesValues() {
        Chunk chunk = new Chunk(new Vector3i(0, 0, 0));
        short[] blocks = new short[Chunk.TOTAL_BLOCKS];
        for (int index = 0; index < blocks.length; index++) {
            blocks[index] = (index & 1) == 0 ? Blocks.STONE.getId() : Blocks.DIRT.getId();
        }

        chunk.setAllBlocks(blocks);

        assertFalse(chunk.isUniform());
        assertEquals(Blocks.STONE.getId(), chunk.getBlock(0, 0, 0));
        assertEquals(Blocks.DIRT.getId(), chunk.getBlock(1, 0, 0));
    }

    @Test
    void copyIsIndependentFromSource() {
        Chunk source = new Chunk(new Vector3i(0, 0, 0));
        source.setBlock(2, 3, 4, Blocks.STONE);

        Chunk copy = source.copy();
        source.setBlock(2, 3, 4, Blocks.DIRT);

        assertEquals(Blocks.STONE.getId(), copy.getBlock(2, 3, 4));
        assertEquals(Blocks.DIRT.getId(), source.getBlock(2, 3, 4));
    }

    @Test
    void meshingCopyDoesNotCopyLightingStorage() {
        Chunk source = new Chunk(new Vector3i(0, 0, 0));
        source.setBlock(2, 3, 4, Blocks.STONE);
        source.setLight(2, 3, 4, 15, 0, 0, 0);

        Chunk copy = source.copyForMeshing();

        assertEquals(Blocks.STONE.getId(), copy.getBlock(2, 3, 4));
        assertTrue(copy.getLighting().isAllDark());
    }

    @Test
    void outOfBoundsCoordinatesThrow() {
        Chunk chunk = new Chunk(new Vector3i(0, 0, 0));

        assertThrows(IndexOutOfBoundsException.class, () -> chunk.getBlock(-1, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> chunk.getBlock(Chunk.SIZE, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> chunk.setBlock(0, Chunk.SIZE, 0, Blocks.STONE));
    }
}
