package org.weaw.game;

import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.weaw.game.generation.WorldGenerator;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.game.utils.Blocks;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldStreamerTest {
    @BeforeAll
    static void initializeBlocks() {
        BlockRegistry.initialize();
    }

    @Test
    void updateBuildsPublishesAndRemeshesChunksDeterministically() {
        int previousStoneTextureIndex = Blocks.STONE.getTextureIndex();
        ChunkMesher.MeshingMode previousMode = ChunkMesher.getMeshingMode();
        boolean previousAmbientOcclusion = ChunkMesher.isAmbientOcclusionEnabled();
        try {
            Blocks.STONE.setTextureIndex(0);
            ChunkMesher.setMeshingMode(ChunkMesher.MeshingMode.GREEDY);
            ChunkMesher.setAmbientOcclusionEnabled(false);

            ChunkManager manager = new ChunkManager();
            FlatGenerator generator = new FlatGenerator(Blocks.STONE.getId());
            WorldStreamer streamer = new WorldStreamer(
                    manager,
                    new AirBlockProvider(),
                    generator,
                    new WorldSettings(2),
                    1,
                    5,
                    2,
                    1,
                    1,
                    50_000_000L,
                    new DirectExecutorService(),
                    1
            );

            try {
                streamer.update(new Vector3f(0.0f, 0.0f, 0.0f));
                assertEquals(1, streamer.getLastProfilingSnapshot().chunksGenerated());
                assertEquals(1, streamer.getLastProfilingSnapshot().chunksMeshed());
                assertEquals(0, manager.getChunkCount());

                streamer.update(new Vector3f(0.0f, 0.0f, 0.0f));
                ChunkManager.ChunkPosition origin = new ChunkManager.ChunkPosition(0, 0, 0);
                assertTrue(manager.hasChunk(origin));
                assertNotNull(manager.getChunkUpload(origin));
                assertTrue(manager.getChunkUpload(origin).meshData().opaque().faceCount() >= 1);

                ChunkMeshData beforeRemesh = manager.getChunkUpload(origin).meshData();
                manager.setBlockAtWorld(1, 0, 1, Blocks.AIR);
                streamer.markChunkDirtyPriority(origin);

                streamer.update(new Vector3f(0.0f, 0.0f, 0.0f));
                assertEquals(1, streamer.getLastProfilingSnapshot().chunksRemeshed());

                streamer.update(new Vector3f(0.0f, 0.0f, 0.0f));
                assertTrue(manager.getChunkUpload(origin).meshData().opaque().faceCount()
                        > beforeRemesh.opaque().faceCount());
            } finally {
                streamer.close();
            }
        } finally {
            Blocks.STONE.setTextureIndex(previousStoneTextureIndex);
            ChunkMesher.setMeshingMode(previousMode);
            ChunkMesher.setAmbientOcclusionEnabled(previousAmbientOcclusion);
        }
    }

    private record FlatGenerator(short blockId) implements WorldGenerator {
        @Override
        public void generateChunkData(Chunk chunk) {
            short[] blocks = new short[Chunk.TOTAL_BLOCKS];
            java.util.Arrays.fill(blocks, blockId);
            chunk.setAllBlocks(blocks);
        }

        @Override
        public short getBlockAtWorld(int worldX, int worldY, int worldZ) {
            return blockId;
        }

        @Override
        public int getSurfaceHeight(int worldX, int worldZ) {
            return 0;
        }
    }

    private static final class AirBlockProvider implements WorldBlockProvider {
        @Override
        public short getBlockAtWorld(int worldX, int worldY, int worldZ) {
            return Blocks.AIR.getId();
        }
    }

    private static final class DirectExecutorService extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
