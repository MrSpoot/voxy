package org.weaw.game;

import org.weaw.game.utils.BlockCatalog;
import org.weaw.game.utils.BlockDefinition;

import java.util.Arrays;
import java.util.Objects;

/** Builds the self-contained light field of a chunk before it becomes visible. */
final class ChunkLightingInitializer {
    private static final int[][] OFFSETS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0},
            {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private final BlockCatalog blockCatalog;
    private final WorldBlockProvider blockProvider;
    private final WorldHeightRange heightRange;
    private final ThreadLocal<Scratch> scratch = ThreadLocal.withInitial(Scratch::new);

    ChunkLightingInitializer(
            BlockCatalog blockCatalog,
            WorldBlockProvider blockProvider,
            WorldHeightRange heightRange
    ) {
        this.blockCatalog = Objects.requireNonNull(blockCatalog, "blockCatalog");
        this.blockProvider = Objects.requireNonNull(blockProvider, "blockProvider");
        this.heightRange = Objects.requireNonNull(heightRange, "heightRange");
    }

    void initialize(Chunk chunk) {
        Scratch work = scratch.get();
        Arrays.fill(work.light, (short) 0);
        Arrays.fill(work.directSky, (byte) 0);
        Arrays.fill(work.queued, false);
        work.read = 0;
        work.write = 0;
        work.count = 0;

        int originX = chunk.getPosition().x * Chunk.SIZE;
        int originY = chunk.getPosition().y * Chunk.SIZE;
        int originZ = chunk.getPosition().z * Chunk.SIZE;
        int chunkTopY = originY + Chunk.SIZE - 1;
        int maxWorldY = (heightRange.maxChunkY() + 1) * Chunk.SIZE - 1;

        for (int localZ = 0; localZ < Chunk.SIZE; localZ++) {
            int worldZ = originZ + localZ;
            for (int localX = 0; localX < Chunk.SIZE; localX++) {
                int worldX = originX + localX;
                int sky = incomingSky(worldX, worldZ, chunkTopY, maxWorldY);
                for (int localY = Chunk.SIZE - 1; localY >= 0; localY--) {
                    BlockDefinition block = definition(chunk.getBlock(localX, localY, localZ));
                    sky = transmitDirectSky(sky, block);
                    int index = index(localX, localY, localZ);
                    work.directSky[index] = (byte) sky;
                    if (sky != 0) {
                        work.light[index] = ChunkLighting.pack(0, 0, 0, sky);
                    }
                }
            }
        }

        chunk.forEachLightEmitter((x, y, z, red, green, blue) -> {
            int index = index(x, y, z);
            short current = work.light[index];
            work.light[index] = ChunkLighting.pack(
                    Math.max(red, ChunkLighting.getRed(current)),
                    Math.max(green, ChunkLighting.getGreen(current)),
                    Math.max(blue, ChunkLighting.getBlue(current)),
                    ChunkLighting.getSky(current)
            );
            enqueue(work, index);
        });

        seedSkyFronts(work);
        propagateInsideChunk(chunk, work);
        chunk.getLighting().replaceWithOwnedData(Arrays.copyOf(work.light, work.light.length));
        chunk.getDirectSkyLight().replaceWithLevels(work.directSky);
        chunk.markLightingInitialized();
    }

    private int incomingSky(int worldX, int worldZ, int chunkTopY, int maxWorldY) {
        int scanStart = Math.min(maxWorldY, blockProvider.getSkyLightScanStartY(worldX, worldZ, maxWorldY));
        int sky = WorldLightingSystem.SKY_LIGHT_MAX_LEVEL;
        for (int worldY = scanStart; worldY > chunkTopY && sky > 0; worldY--) {
            sky = transmitDirectSky(sky, definition(blockProvider.getBlockAtWorld(worldX, worldY, worldZ)));
        }
        return sky;
    }

    private void seedSkyFronts(Scratch work) {
        for (int y = 0; y < Chunk.SIZE; y++) {
            for (int z = 0; z < Chunk.SIZE; z++) {
                for (int x = 0; x < Chunk.SIZE; x++) {
                    int sourceIndex = index(x, y, z);
                    int sourceSky = work.directSky[sourceIndex] & 0xF;
                    if (sourceSky <= 1) {
                        continue;
                    }
                    for (int[] offset : OFFSETS) {
                        int nx = x + offset[0];
                        int ny = y + offset[1];
                        int nz = z + offset[2];
                        if (nx < 0 || ny < 0 || nz < 0
                                || nx >= Chunk.SIZE || ny >= Chunk.SIZE || nz >= Chunk.SIZE) {
                            continue;
                        }
                        if ((work.directSky[index(nx, ny, nz)] & 0xF) < sourceSky - 1) {
                            enqueue(work, sourceIndex);
                            break;
                        }
                    }
                }
            }
        }
    }

    private void propagateInsideChunk(Chunk chunk, Scratch work) {
        while (work.count > 0) {
            int sourceIndex = work.queue[work.read];
            work.read = (work.read + 1) % work.queue.length;
            work.count--;
            work.queued[sourceIndex] = false;
            short source = work.light[sourceIndex];
            int x = sourceIndex % Chunk.SIZE;
            int z = (sourceIndex / Chunk.SIZE) % Chunk.SIZE;
            int y = sourceIndex / (Chunk.SIZE * Chunk.SIZE);

            for (int[] offset : OFFSETS) {
                int nx = x + offset[0];
                int ny = y + offset[1];
                int nz = z + offset[2];
                if (nx < 0 || ny < 0 || nz < 0
                        || nx >= Chunk.SIZE || ny >= Chunk.SIZE || nz >= Chunk.SIZE) {
                    continue;
                }
                BlockDefinition destination = definition(chunk.getBlock(nx, ny, nz));
                if (destination != null && destination.blocksLight()) {
                    continue;
                }
                int loss = WorldLightingSystem.propagationLoss(destination);
                int targetIndex = index(nx, ny, nz);
                short current = work.light[targetIndex];
                short next = ChunkLighting.pack(
                        Math.max(ChunkLighting.getRed(current), Math.max(0, ChunkLighting.getRed(source) - loss)),
                        Math.max(ChunkLighting.getGreen(current), Math.max(0, ChunkLighting.getGreen(source) - loss)),
                        Math.max(ChunkLighting.getBlue(current), Math.max(0, ChunkLighting.getBlue(source) - loss)),
                        Math.max(ChunkLighting.getSky(current), Math.max(0, ChunkLighting.getSky(source) - loss))
                );
                if (next != current) {
                    work.light[targetIndex] = next;
                    enqueue(work, targetIndex);
                }
            }
        }
    }

    private BlockDefinition definition(short blockId) {
        return blockCatalog.getBlock(blockId);
    }

    private static int transmitDirectSky(int level, BlockDefinition block) {
        if (level == 0 || block != null && block.blocksLight()) {
            return 0;
        }
        return Math.max(0, level - (block == null ? 0 : block.getLightAttenuation()));
    }

    private static void enqueue(Scratch work, int index) {
        if (work.queued[index]) {
            return;
        }
        work.queued[index] = true;
        work.queue[work.write] = index;
        work.write = (work.write + 1) % work.queue.length;
        work.count++;
    }

    private static int index(int x, int y, int z) {
        return x + z * Chunk.SIZE + y * Chunk.SIZE * Chunk.SIZE;
    }

    private static final class Scratch {
        private final short[] light = new short[Chunk.TOTAL_BLOCKS];
        private final byte[] directSky = new byte[Chunk.TOTAL_BLOCKS];
        private final int[] queue = new int[Chunk.TOTAL_BLOCKS];
        private final boolean[] queued = new boolean[Chunk.TOTAL_BLOCKS];
        private int read;
        private int write;
        private int count;
    }
}
