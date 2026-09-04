package org.weaw.game;

import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.generation.ChunkGenerationHint;
import org.weaw.game.generation.WorldGenerator;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.game.utils.Blocks;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldStreamerTest {
    private static final long MIB = 1024L * 1024L;
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

    @Test
    void hardChunkBudgetLimitsEffectiveRadiusAndUsesAbsoluteWorldHeight() {
        ChunkManager manager = new ChunkManager();
        WorldMemoryBudget budget = new WorldMemoryBudget(
                16L * MIB,
                4L * MIB,
                1,
                0.90,
                0.80,
                0.95,
                0.90,
                16L * MIB,
                20L * MIB
        );
        WorldSettings settings = new WorldSettings(2, new WorldHeightRange(0, 0), budget);
        WorldStreamer streamer = new WorldStreamer(
                manager,
                new AirBlockProvider(),
                new FlatGenerator(Blocks.AIR.getId()),
                settings,
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
            streamer.update(new Vector3f(0.0f, 3200.0f, 0.0f));
            streamer.update(new Vector3f(0.0f, 3200.0f, 0.0f));

            assertEquals(1, manager.getChunkCount());
            assertTrue(manager.hasChunk(0, 0, 0));
            assertEquals(0, streamer.getLastMemorySnapshot().effectiveRenderDistanceChunks());
            assertTrue(streamer.getLastMemorySnapshot().estimatedCpuResidentBytes() <= budget.maxCpuResidentBytes());
        } finally {
            streamer.close();
        }
    }

    @Test
    void sparseStreamingSkipsImplicitChunksButKeepsTheInteractionBubble() {
        ChunkManager manager = new ChunkManager();
        SparseFlatGenerator generator = new SparseFlatGenerator();
        WorldSettings settings = new WorldSettings(
                2,
                new WorldHeightRange(-2, 0),
                WorldMemoryBudget.balanced()
        );
        WorldStreamer streamer = new WorldStreamer(
                manager,
                generator,
                generator,
                settings,
                1,
                5,
                2,
                20,
                20,
                50_000_000L,
                new DirectExecutorService(),
                1
        );

        try {
            streamer.update(new Vector3f(0.0f, -32.0f, 0.0f));
            WorldMemorySnapshot sparseSnapshot = streamer.getLastMemorySnapshot();

            assertEquals(27, sparseSnapshot.desiredMaterializedChunks());
            assertEquals(27, sparseSnapshot.interactionBubbleChunks());
            assertEquals(3, sparseSnapshot.virtualEmptyChunks());
            assertEquals(9, sparseSnapshot.virtualUniformChunks());

            for (int update = 0; update < 40 && manager.getChunkCount() < 27; update++) {
                streamer.update(new Vector3f(0.0f, -32.0f, 0.0f));
            }
            assertEquals(27, generator.generatedChunks);
            assertEquals(27, manager.getChunkCount());
            assertTrue(manager.hasChunk(0, -2, 0));
            assertTrue(manager.hasChunk(0, -1, 0));
            assertTrue(manager.hasChunk(0, 0, 0));
            assertFalse(manager.hasChunk(-2, -1, 0));
            assertFalse(manager.hasChunk(2, -1, 0));
        } finally {
            streamer.close();
        }
    }

    @Test
    void movingCancelsAnInProgressBuildWithoutPublishingOrLeakingItsReservation() throws Exception {
        ChunkManager manager = new ChunkManager();
        BlockingFirstGenerator generator = new BlockingFirstGenerator();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        WorldSettings settings = new WorldSettings(
                2,
                new WorldHeightRange(0, 0),
                WorldMemoryBudget.balanced(),
                false
        );
        WorldStreamer streamer = new WorldStreamer(
                manager,
                generator,
                generator,
                settings,
                1,
                5,
                2,
                1,
                1,
                50_000_000L,
                executor,
                1
        );

        try {
            streamer.update(new Vector3f(0.0f, 0.0f, 0.0f));
            assertTrue(generator.firstBuildStarted.await(5, TimeUnit.SECONDS));

            streamer.update(new Vector3f(Chunk.SIZE * 100.0f, 0.0f, 0.0f));
            int cancellations = streamer.getLastProfilingSnapshot().cancelledChunkBuilds();
            generator.allowFirstBuildToFinish.countDown();
            assertTrue(generator.firstBuildFinished.await(5, TimeUnit.SECONDS));

            long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadlineNs) {
                streamer.update(new Vector3f(Chunk.SIZE * 100.0f, 0.0f, 0.0f));
                cancellations += streamer.getLastProfilingSnapshot().cancelledChunkBuilds();
                if (cancellations > 0 && streamer.getPendingTaskCount() == 0) {
                    break;
                }
                Thread.sleep(1L);
            }

            assertTrue(cancellations > 0);
            assertFalse(manager.hasChunk(0, 0, 0));
            assertEquals(0L, streamer.getLastMemorySnapshot().reservedInFlightBytes());
        } finally {
            generator.allowFirstBuildToFinish.countDown();
            streamer.close();
        }
    }

    @Test
    void interactionRemeshPublishesWhileBackgroundGenerationIsBlocked() throws Exception {
        ChunkManager manager = new ChunkManager();
        BlockingFirstGenerator generator = new BlockingFirstGenerator();
        ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
        ExecutorService interactionExecutor = Executors.newSingleThreadExecutor();
        WorldSettings settings = new WorldSettings(
                2,
                new WorldHeightRange(0, 0),
                WorldMemoryBudget.balanced(),
                false
        );

        ChunkPosition origin = new ChunkPosition(0, 0, 0);
        Chunk originChunk = new Chunk(new org.joml.Vector3i(0, 0, 0));
        short[] blocks = new short[Chunk.TOTAL_BLOCKS];
        java.util.Arrays.fill(blocks, Blocks.STONE.getId());
        originChunk.setAllBlocks(blocks);
        manager.publishBuiltChunk(originChunk, emptyMeshData());

        WorldStreamer streamer = new WorldStreamer(
                manager,
                generator,
                generator,
                settings,
                1,
                5,
                2,
                4,
                1,
                50_000_000L,
                backgroundExecutor,
                interactionExecutor,
                2
        );

        try {
            Vector3f playerPosition = new Vector3f(0.0f, 0.0f, 0.0f);
            streamer.update(playerPosition);
            assertTrue(generator.firstBuildStarted.await(5, TimeUnit.SECONDS));

            manager.setBlockAtWorld(1, 1, 1, Blocks.AIR);
            streamer.markChunkDirtyPriority(origin);
            streamer.submitInteractionRemeshes();

            long deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean interactionPublished = false;
            while (System.nanoTime() < deadlineNs) {
                streamer.update(playerPosition);
                if (manager.getChunkUpload(origin).meshData().opaque().faceCount() > 0) {
                    interactionPublished = true;
                    break;
                }
                Thread.sleep(1L);
            }

            assertTrue(interactionPublished);
            assertEquals(1L, generator.firstBuildFinished.getCount());
        } finally {
            generator.allowFirstBuildToFinish.countDown();
            streamer.close();
        }
    }

    @Test
    void interactionResultPublishesBeforeAnEarlierCompletedLoad() {
        ChunkManager manager = new ChunkManager();
        ChunkPosition origin = new ChunkPosition(0, 0, 0);
        Chunk originChunk = filledChunk(origin, Blocks.STONE.getId());
        manager.publishBuiltChunk(originChunk, emptyMeshData());

        DirectExecutorService executor = new DirectExecutorService();
        WorldStreamer streamer = new WorldStreamer(
                manager,
                new AirBlockProvider(),
                new FlatGenerator(Blocks.AIR.getId()),
                new WorldSettings(2, new WorldHeightRange(0, 0), WorldMemoryBudget.balanced(), false),
                1,
                5,
                2,
                4,
                1,
                50_000_000L,
                executor,
                2
        );

        try {
            Vector3f playerPosition = new Vector3f(0.0f, 0.0f, 0.0f);
            streamer.update(playerPosition);

            manager.setBlockAtWorld(1, 1, 1, Blocks.AIR);
            streamer.markChunkDirtyPriority(origin);
            streamer.submitInteractionRemeshes();
            streamer.update(playerPosition);

            assertTrue(manager.getChunkUpload(origin).meshData().opaque().faceCount() > 0);
            assertEquals(1, manager.getChunkCount());

            streamer.update(playerPosition);
            assertEquals(2, manager.getChunkCount());
        } finally {
            streamer.close();
        }
    }

    @Test
    void repeatedInteractionPublishesOnlyTheLatestChunkState() {
        ChunkManager manager = new ChunkManager();
        ChunkPosition origin = new ChunkPosition(0, 0, 0);
        manager.publishBuiltChunk(filledChunk(origin, Blocks.AIR.getId()), emptyMeshData());

        WorldMemoryBudget budget = new WorldMemoryBudget(
                16L * MIB,
                16L * MIB,
                1,
                0.90,
                0.80,
                0.95,
                0.90,
                16L * MIB,
                20L * MIB
        );
        DirectExecutorService executor = new DirectExecutorService();
        WorldStreamer streamer = new WorldStreamer(
                manager,
                new AirBlockProvider(),
                new FlatGenerator(Blocks.AIR.getId()),
                new WorldSettings(2, new WorldHeightRange(0, 0), budget, false),
                1,
                5,
                2,
                4,
                1,
                50_000_000L,
                executor,
                2
        );

        try {
            manager.setBlockAtWorld(1, 1, 1, Blocks.STONE);
            streamer.markChunkDirtyPriority(origin);
            streamer.submitInteractionRemeshes();

            manager.setBlockAtWorld(1, 1, 1, Blocks.AIR);
            streamer.markChunkDirtyPriority(origin);
            streamer.submitInteractionRemeshes();

            streamer.update(new Vector3f(0.0f, 0.0f, 0.0f));

            assertEquals(0, manager.getChunkUpload(origin).meshData().opaque().faceCount());
            assertEquals(0, streamer.getPendingTaskCount());
            assertEquals(0L, streamer.getLastMemorySnapshot().reservedInFlightBytes());
        } finally {
            streamer.close();
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

    private static final class SparseFlatGenerator implements WorldGenerator, WorldBlockProvider {
        private int generatedChunks;

        @Override
        public void generateChunkData(Chunk chunk) {
            generatedChunks++;
        }

        @Override
        public short getBlockAtWorld(int worldX, int worldY, int worldZ) {
            return Blocks.AIR.getId();
        }

        @Override
        public int getSurfaceHeight(int worldX, int worldZ) {
            return 0;
        }

        @Override
        public ChunkGenerationHint classifyChunk(ChunkManager.ChunkPosition position) {
            return position.x() < 0
                    ? ChunkGenerationHint.empty()
                    : ChunkGenerationHint.uniform(Blocks.STONE.getId());
        }
    }

    private static final class AirBlockProvider implements WorldBlockProvider {
        @Override
        public short getBlockAtWorld(int worldX, int worldY, int worldZ) {
            return Blocks.AIR.getId();
        }
    }

    private static final class BlockingFirstGenerator implements WorldGenerator, WorldBlockProvider {
        private final CountDownLatch firstBuildStarted = new CountDownLatch(1);
        private final CountDownLatch allowFirstBuildToFinish = new CountDownLatch(1);
        private final CountDownLatch firstBuildFinished = new CountDownLatch(1);
        private final AtomicInteger generationCount = new AtomicInteger();

        @Override
        public void generateChunkData(Chunk chunk) {
            if (generationCount.incrementAndGet() != 1) {
                return;
            }
            firstBuildStarted.countDown();
            try {
                allowFirstBuildToFinish.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                firstBuildFinished.countDown();
            }
        }

        @Override
        public short getBlockAtWorld(int worldX, int worldY, int worldZ) {
            return Blocks.AIR.getId();
        }

        @Override
        public int getSurfaceHeight(int worldX, int worldZ) {
            return 0;
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

    private static ChunkMeshData emptyMeshData() {
        ChunkMeshData.LayerMeshData emptyLayer = new ChunkMeshData.LayerMeshData(new int[0], 0);
        return new ChunkMeshData(emptyLayer, emptyLayer, emptyLayer);
    }

    private static Chunk filledChunk(ChunkPosition position, short blockId) {
        Chunk chunk = new Chunk(new org.joml.Vector3i(position.x(), position.y(), position.z()));
        short[] blocks = new short[Chunk.TOTAL_BLOCKS];
        java.util.Arrays.fill(blocks, blockId);
        chunk.setAllBlocks(blocks);
        return chunk;
    }
}
