package org.weaw.game;

import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.utils.BlockCatalog;
import org.weaw.game.utils.BlockDefinition;
import org.weaw.game.utils.BlockRegistry;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

public final class WorldLightingSystem {
    public static final int BLOCK_LIGHT_MAX_LEVEL = 15;
    public static final int BLOCK_LIGHT_CHUNK_RADIUS = (BLOCK_LIGHT_MAX_LEVEL + Chunk.SIZE - 1) / Chunk.SIZE;

    private static final int[][] NEIGHBOR_OFFSETS = {
            {1, 0, 0},
            {-1, 0, 0},
            {0, 1, 0},
            {0, -1, 0},
            {0, 0, 1},
            {0, 0, -1}
    };

    private final BlockCatalog blockCatalog;

    public WorldLightingSystem() {
        this(BlockRegistry.getDefaultCatalog());
    }

    public WorldLightingSystem(BlockCatalog blockCatalog) {
        this.blockCatalog = Objects.requireNonNull(blockCatalog, "blockCatalog");
    }

    public WorldLightingProfilingSnapshot rebuildLightingAround(ChunkManager chunkManager, Set<ChunkPosition> affectedPositions) {
        Objects.requireNonNull(chunkManager, "chunkManager");
        Objects.requireNonNull(affectedPositions, "affectedPositions");
        if (affectedPositions.isEmpty()) {
            return WorldLightingProfilingSnapshot.empty();
        }

        Set<ChunkPosition> targetPositions = expandPositions(affectedPositions, BLOCK_LIGHT_CHUNK_RADIUS);
        Set<ChunkPosition> calculationPositions = targetPositions;

        long snapshotLoadedChunksStartNs = System.nanoTime();
        Map<ChunkPosition, Chunk> loadedChunks = snapshotLoadedChunks(chunkManager, calculationPositions);
        long snapshotLoadedChunksCpuTimeNs = System.nanoTime() - snapshotLoadedChunksStartNs;
        if (loadedChunks.isEmpty()) {
            return new WorldLightingProfilingSnapshot(
                    snapshotLoadedChunksCpuTimeNs,
                    0L,
                    0L,
                    0L,
                    affectedPositions.size(),
                    targetPositions.size(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0
            );
        }

        Set<ChunkPosition> loadedTargetPositions = new HashSet<>();
        for (ChunkPosition position : targetPositions) {
            if (loadedChunks.containsKey(position)) {
                loadedTargetPositions.add(position);
            }
        }
        if (loadedTargetPositions.isEmpty()) {
            return new WorldLightingProfilingSnapshot(
                    snapshotLoadedChunksCpuTimeNs,
                    0L,
                    0L,
                    0L,
                    affectedPositions.size(),
                    targetPositions.size(),
                    loadedChunks.size(),
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0
            );
        }

        Queue<LightNode> queue = new ArrayDeque<>();
        long clearLightingStartNs = System.nanoTime();
        int clearedChunkCount = clearLighting(loadedChunks, loadedTargetPositions);
        long clearLightingCpuTimeNs = System.nanoTime() - clearLightingStartNs;
        long seedEmittersStartNs = System.nanoTime();
        EmitterSeedResult emitterSeedResult = seedEmitters(loadedChunks, queue);
        long seedEmittersCpuTimeNs = System.nanoTime() - seedEmittersStartNs;
        long propagateStartNs = System.nanoTime();
        PropagationResult propagationResult = propagate(queue, loadedChunks);
        long propagateCpuTimeNs = System.nanoTime() - propagateStartNs;
        return new WorldLightingProfilingSnapshot(
                snapshotLoadedChunksCpuTimeNs,
                clearLightingCpuTimeNs,
                seedEmittersCpuTimeNs,
                propagateCpuTimeNs,
                affectedPositions.size(),
                targetPositions.size(),
                loadedChunks.size(),
                loadedTargetPositions.size(),
                clearedChunkCount,
                emitterSeedResult.emitterCount(),
                emitterSeedResult.seededNodeCount(),
                propagationResult.processedNodeCount(),
                propagationResult.lightWriteCount(),
                propagationResult.blockedByOpaqueCount(),
                propagationResult.missingChunkNeighborCount(),
                propagationResult.noGainCount()
        );
    }

    private Map<ChunkPosition, Chunk> snapshotLoadedChunks(ChunkManager chunkManager, Set<ChunkPosition> positions) {
        Map<ChunkPosition, Chunk> loadedChunks = new HashMap<>(positions.size());
        for (ChunkPosition position : positions) {
            Chunk chunk = chunkManager.getChunk(position.x(), position.y(), position.z());
            if (chunk != null) {
                loadedChunks.put(position, chunk);
            }
        }
        return loadedChunks;
    }

    private int clearLighting(Map<ChunkPosition, Chunk> loadedChunks, Set<ChunkPosition> targetPositions) {
        int clearedChunkCount = 0;
        for (ChunkPosition position : targetPositions) {
            Chunk chunk = loadedChunks.get(position);
            if (chunk == null) {
                continue;
            }
            chunk.clearLighting();
            clearedChunkCount++;
        }
        return clearedChunkCount;
    }

    private EmitterSeedResult seedEmitters(Map<ChunkPosition, Chunk> loadedChunks, Queue<LightNode> queue) {
        int emitterCount = 0;
        int seededNodeCount = 0;
        for (Map.Entry<ChunkPosition, Chunk> entry : loadedChunks.entrySet()) {
            ChunkPosition position = entry.getKey();
            Chunk chunk = entry.getValue();
            if (!chunk.hasLightEmitters()) {
                continue;
            }
            int originX = position.x() * Chunk.SIZE;
            int originY = position.y() * Chunk.SIZE;
            int originZ = position.z() * Chunk.SIZE;
            chunk.forEachLightEmitter((x, y, z, red, green, blue) -> {
                chunk.setLight(x, y, z, red, green, blue, 0);
                queue.add(new LightNode(
                        originX + x,
                        originY + y,
                        originZ + z,
                        red,
                        green,
                        blue
                ));
            });
            emitterCount += chunk.getLightEmitterCount();
            seededNodeCount += chunk.getLightEmitterCount();
        }
        return new EmitterSeedResult(emitterCount, seededNodeCount);
    }

    private PropagationResult propagate(Queue<LightNode> queue, Map<ChunkPosition, Chunk> loadedChunks) {
        int processedNodeCount = 0;
        int lightWriteCount = 0;
        int blockedByOpaqueCount = 0;
        int missingChunkNeighborCount = 0;
        int noGainCount = 0;
        while (!queue.isEmpty()) {
            LightNode node = queue.poll();
             processedNodeCount++;
            if (node.red() <= 1 && node.green() <= 1 && node.blue() <= 1) {
                continue;
            }

            for (int[] offset : NEIGHBOR_OFFSETS) {
                int nextWorldX = node.worldX() + offset[0];
                int nextWorldY = node.worldY() + offset[1];
                int nextWorldZ = node.worldZ() + offset[2];

                ChunkPosition nextChunkPosition = new ChunkPosition(
                        Math.floorDiv(nextWorldX, Chunk.SIZE),
                        Math.floorDiv(nextWorldY, Chunk.SIZE),
                        Math.floorDiv(nextWorldZ, Chunk.SIZE)
                );
                Chunk nextChunk = loadedChunks.get(nextChunkPosition);
                if (nextChunk == null) {
                    missingChunkNeighborCount++;
                    continue;
                }

                int localX = Math.floorMod(nextWorldX, Chunk.SIZE);
                int localY = Math.floorMod(nextWorldY, Chunk.SIZE);
                int localZ = Math.floorMod(nextWorldZ, Chunk.SIZE);

                BlockDefinition nextBlock = blockCatalog.getBlock(nextChunk.getBlock(localX, localY, localZ));
                if (nextBlock != null && nextBlock.blocksLight() && !nextBlock.isLightEmitter()) {
                    blockedByOpaqueCount++;
                    continue;
                }

                int propagatedRed = Math.max(0, node.red() - 1);
                int propagatedGreen = Math.max(0, node.green() - 1);
                int propagatedBlue = Math.max(0, node.blue() - 1);
                if (propagatedRed == 0 && propagatedGreen == 0 && propagatedBlue == 0) {
                    continue;
                }

                short currentPackedLight = nextChunk.getPackedLight(localX, localY, localZ);
                int currentRed = ChunkLighting.getRed(currentPackedLight);
                int currentGreen = ChunkLighting.getGreen(currentPackedLight);
                int currentBlue = ChunkLighting.getBlue(currentPackedLight);

                int nextRed = Math.max(currentRed, propagatedRed);
                int nextGreen = Math.max(currentGreen, propagatedGreen);
                int nextBlue = Math.max(currentBlue, propagatedBlue);
                if (nextRed == currentRed && nextGreen == currentGreen && nextBlue == currentBlue) {
                    noGainCount++;
                    continue;
                }

                int sky = ChunkLighting.getSky(currentPackedLight);
                nextChunk.setLight(localX, localY, localZ, nextRed, nextGreen, nextBlue, sky);
                lightWriteCount++;
                queue.add(new LightNode(nextWorldX, nextWorldY, nextWorldZ, nextRed, nextGreen, nextBlue));
            }
        }
        return new PropagationResult(
                processedNodeCount,
                lightWriteCount,
                blockedByOpaqueCount,
                missingChunkNeighborCount,
                noGainCount
        );
    }

    private Set<ChunkPosition> expandPositions(Set<ChunkPosition> positions, int radiusChunks) {
        Set<ChunkPosition> expanded = new HashSet<>();
        for (ChunkPosition position : positions) {
            for (int offsetX = -radiusChunks; offsetX <= radiusChunks; offsetX++) {
                for (int offsetY = -radiusChunks; offsetY <= radiusChunks; offsetY++) {
                    for (int offsetZ = -radiusChunks; offsetZ <= radiusChunks; offsetZ++) {
                        expanded.add(new ChunkPosition(
                                position.x() + offsetX,
                                position.y() + offsetY,
                                position.z() + offsetZ
                        ));
                    }
                }
            }
        }
        return expanded;
    }

    private record LightNode(int worldX, int worldY, int worldZ, int red, int green, int blue) {
    }

    private record EmitterSeedResult(int emitterCount, int seededNodeCount) {
    }

    private record PropagationResult(
            int processedNodeCount,
            int lightWriteCount,
            int blockedByOpaqueCount,
            int missingChunkNeighborCount,
            int noGainCount
    ) {
    }
}
