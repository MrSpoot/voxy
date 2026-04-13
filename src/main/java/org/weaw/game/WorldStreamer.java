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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WorldStreamer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldStreamer.class);

    private final ChunkManager chunkManager;
    private final ExecutorService executor;
    private final int horizontalRenderRadius;
    private final int verticalRenderHeight;
    private final int horizontalUnloadRadius;
    private final int verticalUnloadHeight;
    private final int maxSubmissionsPerUpdate;
    private final List<ChunkOffset> sortedDesiredOffsets;

    private ChunkPosition cachedPlayerChunk;
    private List<ChunkPosition> cachedDesiredPositions = List.of();

    public WorldStreamer(ChunkManager chunkManager) {
        this(chunkManager, 16, 20, 15, 24, 4);
    }

    public WorldStreamer(
            ChunkManager chunkManager,
            int horizontalRenderRadius,
            int verticalRenderHeight,
            int horizontalUnloadRadius,
            int verticalUnloadHeight,
            int maxSubmissionsPerUpdate
    ) {
        this.chunkManager = chunkManager;
        this.horizontalRenderRadius = horizontalRenderRadius;
        this.verticalRenderHeight = verticalRenderHeight;
        this.horizontalUnloadRadius = horizontalUnloadRadius;
        this.verticalUnloadHeight = verticalUnloadHeight;
        this.maxSubmissionsPerUpdate = maxSubmissionsPerUpdate;
        this.sortedDesiredOffsets = createSortedDesiredOffsets();

        int workerCount = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        this.executor = Executors.newFixedThreadPool(workerCount);
    }

    public void update(Vector3f playerPosition) {
        ChunkPosition playerChunk = toChunkPosition(playerPosition);
        if (!playerChunk.equals(cachedPlayerChunk)) {
            cachedDesiredPositions = translateDesiredOffsets(playerChunk);
            cachedPlayerChunk = playerChunk;
            unloadFarChunks(playerChunk);
        }
        submitNeededChunks();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void unloadFarChunks(ChunkPosition playerChunk) {
        int minUnloadY = getMinChunkY(playerChunk.y(), verticalUnloadHeight);
        int maxUnloadY = getMaxChunkY(playerChunk.y(), verticalUnloadHeight);
        int unloadRadiusSquared = horizontalUnloadRadius * horizontalUnloadRadius;

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
        int submitted = 0;

        for (ChunkPosition position : cachedDesiredPositions) {
            if (submitted >= maxSubmissionsPerUpdate) {
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
            ChunkMeshData meshData = ChunkMesher.buildMeshData(chunk, GenerationEngine::getBlockAtWorld);
            chunkManager.publishBuiltChunk(chunk, meshData);
        } catch (Exception exception) {
            LOGGER.error("Chunk generation failed for {}", position, exception);
            chunkManager.clearQueuedChunk(position);
        }
    }

    private List<ChunkOffset> createSortedDesiredOffsets() {
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

        offsets.sort(Comparator.comparingInt(this::distancePriority));
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

    private record ChunkOffset(int x, int y, int z) {
    }
}
