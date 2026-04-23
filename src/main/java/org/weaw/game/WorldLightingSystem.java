package org.weaw.game;

import org.weaw.game.ChunkManager.ChunkPosition;
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

    public void rebuildLightingAround(ChunkManager chunkManager, Set<ChunkPosition> affectedPositions) {
        Objects.requireNonNull(chunkManager, "chunkManager");
        Objects.requireNonNull(affectedPositions, "affectedPositions");
        if (affectedPositions.isEmpty()) {
            return;
        }

        Set<ChunkPosition> targetPositions = expandPositions(affectedPositions, BLOCK_LIGHT_CHUNK_RADIUS);
        Set<ChunkPosition> calculationPositions = targetPositions;

        Map<ChunkPosition, Chunk> loadedChunks = snapshotLoadedChunks(chunkManager, calculationPositions);
        if (loadedChunks.isEmpty()) {
            return;
        }

        Set<ChunkPosition> loadedTargetPositions = new HashSet<>();
        for (ChunkPosition position : targetPositions) {
            if (loadedChunks.containsKey(position)) {
                loadedTargetPositions.add(position);
            }
        }
        if (loadedTargetPositions.isEmpty()) {
            return;
        }

        Queue<LightNode> queue = new ArrayDeque<>();
        clearLighting(loadedChunks, loadedTargetPositions);
        seedEmitters(loadedChunks, queue);
        propagate(queue, loadedChunks);
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

    private void clearLighting(Map<ChunkPosition, Chunk> loadedChunks, Set<ChunkPosition> targetPositions) {
        for (ChunkPosition position : targetPositions) {
            Chunk chunk = loadedChunks.get(position);
            if (chunk == null) {
                continue;
            }
            chunk.clearLighting();
        }
    }

    private void seedEmitters(Map<ChunkPosition, Chunk> loadedChunks, Queue<LightNode> queue) {
        for (Map.Entry<ChunkPosition, Chunk> entry : loadedChunks.entrySet()) {
            ChunkPosition position = entry.getKey();
            Chunk chunk = entry.getValue();
            int originX = position.x() * Chunk.SIZE;
            int originY = position.y() * Chunk.SIZE;
            int originZ = position.z() * Chunk.SIZE;

            for (int y = 0; y < Chunk.SIZE; y++) {
                for (int z = 0; z < Chunk.SIZE; z++) {
                    for (int x = 0; x < Chunk.SIZE; x++) {
                        BlockDefinition block = BlockRegistry.getBlock(chunk.getBlock(x, y, z));
                        if (block == null || !block.isLightEmitter()) {
                            continue;
                        }

                        int red = block.getLightEmissionRed();
                        int green = block.getLightEmissionGreen();
                        int blue = block.getLightEmissionBlue();
                        chunk.setLight(x, y, z, red, green, blue, 0);
                        queue.add(new LightNode(
                                originX + x,
                                originY + y,
                                originZ + z,
                                red,
                                green,
                                blue
                        ));
                    }
                }
            }
        }
    }

    private void propagate(Queue<LightNode> queue, Map<ChunkPosition, Chunk> loadedChunks) {
        while (!queue.isEmpty()) {
            LightNode node = queue.poll();
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
                    continue;
                }

                int localX = Math.floorMod(nextWorldX, Chunk.SIZE);
                int localY = Math.floorMod(nextWorldY, Chunk.SIZE);
                int localZ = Math.floorMod(nextWorldZ, Chunk.SIZE);

                BlockDefinition nextBlock = BlockRegistry.getBlock(nextChunk.getBlock(localX, localY, localZ));
                if (nextBlock != null && nextBlock.blocksLight() && !nextBlock.isLightEmitter()) {
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
                    continue;
                }

                int sky = ChunkLighting.getSky(currentPackedLight);
                nextChunk.setLight(localX, localY, localZ, nextRed, nextGreen, nextBlue, sky);
                queue.add(new LightNode(nextWorldX, nextWorldY, nextWorldZ, nextRed, nextGreen, nextBlue));
            }
        }
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
}
