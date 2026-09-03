package org.weaw.game;

import org.joml.Vector3i;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.game.utils.Blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkManagerTest {
    @BeforeAll
    static void initializeBlocks() {
        BlockRegistry.initialize();
    }

    @Test
    void queuedChunkCanBeMarkedOnceThenCleared() {
        ChunkManager manager = new ChunkManager();
        ChunkPosition position = new ChunkPosition(1, 2, 3);

        assertTrue(manager.tryMarkChunkQueued(position));
        assertFalse(manager.tryMarkChunkQueued(position));

        manager.clearQueuedChunk(position);

        assertTrue(manager.tryMarkChunkQueued(position));
    }

    @Test
    void publishBuiltChunkMakesChunkAndUploadVisible() {
        ChunkManager manager = new ChunkManager();
        Chunk chunk = new Chunk(new Vector3i(1, 0, -1));
        chunk.setBlock(0, 0, 0, Blocks.STONE);
        ChunkMeshData meshData = emptyMeshData();
        ChunkPosition position = new ChunkPosition(1, 0, -1);

        manager.publishBuiltChunk(chunk, meshData);

        assertTrue(manager.hasChunk(position));
        assertEquals(1, manager.getChunkCount());
        assertEquals(Blocks.STONE.getId(), manager.getBlockAtWorld(Chunk.SIZE, 0, -Chunk.SIZE));
        assertEquals(meshData, manager.getChunkUpload(position).meshData());
    }

    @Test
    void unloadChunkRemovesChunkAndUpload() {
        ChunkManager manager = new ChunkManager();
        Chunk chunk = new Chunk(new Vector3i(0, 0, 0));
        ChunkPosition position = new ChunkPosition(0, 0, 0);

        manager.publishBuiltChunk(chunk, emptyMeshData());
        manager.unloadChunk(position);

        assertFalse(manager.hasChunk(position));
        assertNull(manager.getChunkUpload(position));
        assertEquals(Blocks.AIR.getId(), manager.getBlockAtWorld(0, 0, 0));
    }

    @Test
    void setBlockAtWorldRequiresLoadedChunk() {
        ChunkManager manager = new ChunkManager();

        assertThrows(
                IllegalArgumentException.class,
                () -> manager.setBlockAtWorld(0, 0, 0, Blocks.STONE)
        );
    }

    @Test
    void publishRemeshedChunkOnlyUpdatesLoadedChunks() {
        ChunkManager manager = new ChunkManager();
        ChunkPosition position = new ChunkPosition(0, 0, 0);
        ChunkMeshData initialMesh = emptyMeshData();
        ChunkMeshData updatedMesh = new ChunkMeshData(
                new ChunkMeshData.LayerMeshData(new int[]{1, 2}, 1),
                new ChunkMeshData.LayerMeshData(new int[0], 0),
                new ChunkMeshData.LayerMeshData(new int[0], 0)
        );

        assertFalse(manager.publishRemeshedChunk(position, updatedMesh));

        manager.publishBuiltChunk(new Chunk(new Vector3i(0, 0, 0)), initialMesh);

        assertTrue(manager.publishRemeshedChunk(position, updatedMesh));
        assertEquals(updatedMesh, manager.getChunkUpload(position).meshData());
    }

    private static ChunkMeshData emptyMeshData() {
        ChunkMeshData.LayerMeshData emptyLayer = new ChunkMeshData.LayerMeshData(new int[0], 0);
        return new ChunkMeshData(emptyLayer, emptyLayer, emptyLayer);
    }
}
