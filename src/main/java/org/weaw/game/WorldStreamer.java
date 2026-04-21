package org.weaw.game;

import org.joml.Vector3f;
import org.joml.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.utils.GenerationEngine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WorldStreamer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldStreamer.class);

    private final ChunkManager chunkManager;
    private final WorldBlockProvider blockProvider;
    private final WorldSettings settings;
    private final ExecutorService executor;
    private final int verticalRenderHeight;
    private final int verticalUnloadHeight;
    private final int maxSubmissionsPerUpdate;
    private final int maxPublishesPerUpdate;
    private final int maxQueuedChunkCount;
    private final Queue<CompletedChunk> completedChunks = new ConcurrentLinkedQueue<>();

    private int activeHorizontalRenderRadius = -1;
    private int activeHorizontalUnloadRadius = -1;
    private List<ChunkOffset> sortedDesiredOffsets = List.of();
    private ChunkPosition cachedPlayerChunk;
    private List<ChunkPosition> cachedDesiredPositions = List.of();

    public WorldStreamer(ChunkManager chunkManager, WorldBlockProvider blockProvider) {
        this(chunkManager, blockProvider, new WorldSettings());
    }

    public WorldStreamer(ChunkManager chunkManager, WorldBlockProvider blockProvider, WorldSettings settings) {
        this(chunkManager, blockProvider, settings, 20, 24, 12, 4);
    }

    public WorldStreamer(
            ChunkManager chunkManager,
            WorldBlockProvider blockProvider,
            int horizontalRenderRadius,
            int verticalRenderHeight,
            int horizontalUnloadRadius,
            int verticalUnloadHeight,
            int maxSubmissionsPerUpdate
    ) {
        this(
                chunkManager,
                blockProvider,
                new WorldSettings(horizontalRenderRadius),
                verticalRenderHeight,
                verticalUnloadHeight,
                maxSubmissionsPerUpdate,
                Math.max(1, maxSubmissionsPerUpdate / 3)
        );
    }

    public WorldStreamer(
            ChunkManager chunkManager,
            WorldBlockProvider blockProvider,
            WorldSettings settings,
            int verticalRenderHeight,
            int verticalUnloadHeight,
            int maxSubmissionsPerUpdate,
            int maxPublishesPerUpdate
    ) {
        this.chunkManager = chunkManager;
        this.blockProvider = Objects.requireNonNull(blockProvider, "blockProvider");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.verticalRenderHeight = verticalRenderHeight;
        this.verticalUnloadHeight = Math.max(verticalUnloadHeight, verticalRenderHeight + 4);
        this.maxSubmissionsPerUpdate = Math.max(1, maxSubmissionsPerUpdate);
        this.maxPublishesPerUpdate = Math.max(1, maxPublishesPerUpdate);
        int workerCount = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        this.maxQueuedChunkCount = Math.max(workerCount * 4, this.maxSubmissionsPerUpdate * 2);
        this.executor = Executors.newFixedThreadPool(workerCount);
    }

    public void update(Vector3f playerPosition) {
        ChunkPosition playerChunk = toChunkPosition(playerPosition);
        int requestedRenderRadius = settings.getRenderDistanceChunks();
        boolean renderRadiusChanged = requestedRenderRadius != activeHorizontalRenderRadius;

        if (renderRadiusChanged) {
            activeHorizontalRenderRadius = requestedRenderRadius;
            activeHorizontalUnloadRadius = requestedRenderRadius + 2;
            sortedDesiredOffsets = createSortedDesiredOffsets(activeHorizontalRenderRadius);
        }

        if (renderRadiusChanged || !playerChunk.equals(cachedPlayerChunk)) {
            cachedDesiredPositions = translateDesiredOffsets(playerChunk);
            cachedPlayerChunk = playerChunk;
        }
        publishCompletedChunks();
        unloadFarChunks(playerChunk);
        submitNeededChunks();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void unloadFarChunks(ChunkPosition playerChunk) {
        int minUnloadY = getMinChunkY(playerChunk.y(), verticalUnloadHeight);
        int maxUnloadY = getMaxChunkY(playerChunk.y(), verticalUnloadHeight);
        int unloadRadiusSquared = activeHorizontalUnloadRadius * activeHorizontalUnloadRadius;

        for (ChunkPosition position : chunkManager.snapshotLoadedChunkPositions()) {
            int dx = position.x() - playerChunk.x();
            int dz = position.z() - playerChunk.z();

            boolean outsideCylinder = (dx * dx + dz * dz) > unloadRadiusSquared
                    || position.y() < minUnloadY
                    || position.y() > maxUnloadY;

            if (outsideCylinder) {
                chunkManager.unloadChunk(position);
            }
        }
    }

    private void submitNeededChunks() {
        int availableQueueSlots = maxQueuedChunkCount - chunkManager.getQueuedChunkCount();
        if (availableQueueSlots <= 0) {
            return;
        }

        int submissionBudget = Math.min(maxSubmissionsPerUpdate, availableQueueSlots);
        int submitted = 0;

        for (ChunkPosition position : cachedDesiredPositions) {
            if (submitted >= submissionBudget) {
                break;
            }

            if (!chunkManager.tryMarkChunkQueued(position)) {
                continue;
            }

            submitted++;
            executor.submit(() -> buildChunk(position));
        }
    }

    private void buildChunk(ChunkPosition position) {
        try {
            Chunk chunk = new Chunk(new Vector3i(position.x(), position.y(), position.z()));
            GenerationEngine.generateChunkData(chunk);
            ChunkMeshData meshData = ChunkMesher.buildMeshData(chunk, blockProvider);
            completedChunks.offer(new CompletedChunk(chunk, meshData));
        } catch (Exception exception) {
            LOGGER.error("Chunk generation failed for {}", position, exception);
            chunkManager.clearQueuedChunk(position);
        }
    }

    private void publishCompletedChunks() {
        for (int published = 0; published < maxPublishesPerUpdate; published++) {
            CompletedChunk completedChunk = completedChunks.poll();
            if (completedChunk == null) {
                return;
            }

            chunkManager.publishBuiltChunk(completedChunk.chunk(), completedChunk.meshData());
        }
    }

    private List<ChunkOffset> createSortedDesiredOffsets(int horizontalRenderRadius) {
        List<ChunkOffset> offsets = new ArrayList<>();
        int minRenderY = getMinChunkY(0, verticalRenderHeight);
        int maxRenderY = getMaxChunkY(0, verticalRenderHeight);
        int renderRadiusSquared = horizontalRenderRadius * horizontalRenderRadius;

        for (int offsetY = minRenderY; offsetY <= maxRenderY; offsetY++) {
            for (int offsetZ = -horizontalRenderRadius; offsetZ <= horizontalRenderRadius; offsetZ++) {
                for (int offsetX = -horizontalRenderRadius; offsetX <= horizontalRenderRadius; offsetX++) {
                    int dx = offsetX;
                    int dz = offsetZ;

                    if (dx * dx + dz * dz > renderRadiusSquared) {
                        continue;
                    }

                    offsets.add(new ChunkOffset(offsetX, offsetY, offsetZ));
                }
            }
        }

        offsets.sort(
                Comparator.comparingInt(this::horizontalDistancePriority)
                        .thenComparingInt(this::verticalDistancePriority)
                        .thenComparingInt(this::distancePriority)
        );
        return List.copyOf(offsets);
    }

    private List<ChunkPosition> translateDesiredOffsets(ChunkPosition playerChunk) {
        List<ChunkPosition> positions = new ArrayList<>(sortedDesiredOffsets.size());
        for (ChunkOffset offset : sortedDesiredOffsets) {
            positions.add(new ChunkPosition(
                    playerChunk.x() + offset.x(),
                    playerChunk.y() + offset.y(),
                    playerChunk.z() + offset.z()
            ));
        }
        return positions;
    }

    private ChunkPosition toChunkPosition(Vector3f worldPosition) {
        return new ChunkPosition(
                Math.floorDiv((int) Math.floor(worldPosition.x), Chunk.SIZE),
                Math.floorDiv((int) Math.floor(worldPosition.y), Chunk.SIZE),
                Math.floorDiv((int) Math.floor(worldPosition.z), Chunk.SIZE)
        );
    }

    private int getMinChunkY(int centerChunkY, int totalHeight) {
        int halfBelow = totalHeight / 2;
        return centerChunkY - halfBelow;
    }

    private int getMaxChunkY(int centerChunkY, int totalHeight) {
        int halfAbove = totalHeight - 1 - (totalHeight / 2);
        return centerChunkY + halfAbove;
    }

    private int distancePriority(ChunkOffset offset) {
        return offset.x() * offset.x() + offset.z() * offset.z() + offset.y() * offset.y();
    }

    private int horizontalDistancePriority(ChunkOffset offset) {
        return offset.x() * offset.x() + offset.z() * offset.z();
    }

    private int verticalDistancePriority(ChunkOffset offset) {
        return Math.abs(offset.y());
    }

    private record ChunkOffset(int x, int y, int z) {
    }

    private record CompletedChunk(Chunk chunk, ChunkMeshData meshData) {
    }
}
