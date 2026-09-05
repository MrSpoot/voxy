package org.weaw.game.generation;

import org.weaw.game.Chunk;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.utils.Blocks;
import org.weaw.game.utils.FastNoiseLite;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class NoiseWorldGenerator implements WorldGenerator {
    private static final int TOPSOIL_DEPTH = 3;
    private static final int TREE_HORIZONTAL_RADIUS = 2;
    private static final int TREE_MAX_HEIGHT_ABOVE_SURFACE = 6;
    private static final int DEFAULT_CLASSIFICATION_CACHE_COLUMNS = 4096;
    private static final int MAX_PENDING_CLASSIFICATION_COLUMNS = 4096;
    private static final ThreadPoolExecutor CLASSIFICATION_EXECUTOR = createClassificationExecutor();

    private final GenerationConfig config;
    private final ThreadLocal<FastNoiseLite> noise;
    private final ThreadLocal<FastNoiseLite> treeNoise;
    private final ThreadLocal<GenerationScratch> generationScratch;
    private final ThreadLocal<RecentColumnCache> recentColumns;
    private final Map<ColumnPosition, CompletableFuture<ColumnGenerationData>> classificationCache;
    private final int maxClassificationCacheColumns;
    private final ColumnBounds globalBounds;
    private long classificationCacheHits;
    private long classificationCacheMisses;

    public NoiseWorldGenerator(GenerationConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.noise = ThreadLocal.withInitial(() -> createNoise((int) config.seed()));
        this.treeNoise = ThreadLocal.withInitial(() -> createNoise((int) config.seed() + config.treeSeedOffset()));
        this.generationScratch = ThreadLocal.withInitial(GenerationScratch::new);
        this.recentColumns = ThreadLocal.withInitial(RecentColumnCache::new);
        int minimumSurfaceY = (int) Math.floor(config.baseHeight() - Math.abs(config.amplitude()));
        int maximumSurfaceY = (int) Math.ceil(config.baseHeight() + Math.abs(config.amplitude()));
        this.globalBounds = new ColumnBounds(
                minimumSurfaceY,
                Math.max(config.waterLevel(), maximumSurfaceY + TREE_MAX_HEIGHT_ABOVE_SURFACE)
        );
        this.maxClassificationCacheColumns = Math.max(
                64,
                Integer.getInteger("voxy.chunkClassificationCacheColumns", DEFAULT_CLASSIFICATION_CACHE_COLUMNS)
        );
        this.classificationCache = new LinkedHashMap<>(256, 0.75f, true);
    }

    @Override
    public void generateChunkData(Chunk chunk) {
        int chunkGlobalX = chunk.getPosition().x * Chunk.SIZE;
        int chunkGlobalZ = chunk.getPosition().z * Chunk.SIZE;
        int chunkGlobalY = chunk.getPosition().y * Chunk.SIZE;
        GenerationScratch scratch = generationScratch.get();
        short[] blocks = scratch.blocks;
        ColumnGenerationData columnData = getColumnData(chunk.getPosition().x, chunk.getPosition().z);

        for (int y = 0; y < Chunk.SIZE; y++) {
            int globalY = chunkGlobalY + y;
            int yOffset = y * Chunk.SIZE * Chunk.SIZE;
            for (int z = 0; z < Chunk.SIZE; z++) {
                int zOffset = yOffset + (z * Chunk.SIZE);
                for (int x = 0; x < Chunk.SIZE; x++) {
                    int height = columnData.surfaceHeight(x, z);
                    blocks[zOffset + x] = getBaseTerrainBlock(globalY, height);
                }
            }
        }

        populateTrees(columnData, blocks, chunkGlobalX, chunkGlobalY, chunkGlobalZ);
        chunk.setAllBlocks(blocks);
    }

    @Override
    public short getBlockAtWorld(int worldX, int worldY, int worldZ) {
        int chunkX = Math.floorDiv(worldX, Chunk.SIZE);
        int chunkZ = Math.floorDiv(worldZ, Chunk.SIZE);
        ColumnGenerationData columnData = getColumnData(chunkX, chunkZ);
        short baseBlock = getBaseTerrainBlock(
                worldY,
                columnData.surfaceHeight(worldX - chunkX * Chunk.SIZE, worldZ - chunkZ * Chunk.SIZE)
        );
        if (baseBlock != Blocks.AIR.getId()) {
            return baseBlock;
        }

        for (int treeX = worldX - 2; treeX <= worldX + 2; treeX++) {
            for (int treeZ = worldZ - 2; treeZ <= worldZ + 2; treeZ++) {
                short treeBlock = getTreeBlockAt(columnData, chunkX, chunkZ, treeX, treeZ, worldX, worldY, worldZ);
                if (treeBlock != Blocks.AIR.getId()) {
                    return treeBlock;
                }
            }
        }

        return baseBlock;
    }

    @Override
    public int getSurfaceHeight(int worldX, int worldZ) {
        int chunkX = Math.floorDiv(worldX, Chunk.SIZE);
        int chunkZ = Math.floorDiv(worldZ, Chunk.SIZE);
        return getColumnData(chunkX, chunkZ).surfaceHeight(
                worldX - chunkX * Chunk.SIZE,
                worldZ - chunkZ * Chunk.SIZE
        );
    }

    @Override
    public void fillBlockRegion(
            int originX,
            int originY,
            int originZ,
            int sizeX,
            int sizeY,
            int sizeZ,
            short[] destination
    ) {
        if (sizeX < 0 || sizeY < 0 || sizeZ < 0
                || destination.length < sizeX * sizeY * sizeZ) {
            throw new IllegalArgumentException("Invalid block region dimensions or destination size");
        }
        if (sizeX == 0 || sizeY == 0 || sizeZ == 0) {
            return;
        }

        int minChunkX = Math.floorDiv(originX - TREE_HORIZONTAL_RADIUS, Chunk.SIZE);
        int minChunkZ = Math.floorDiv(originZ - TREE_HORIZONTAL_RADIUS, Chunk.SIZE);
        int maxChunkX = Math.floorDiv(originX + sizeX - 1 + TREE_HORIZONTAL_RADIUS, Chunk.SIZE);
        int maxChunkZ = Math.floorDiv(originZ + sizeZ - 1 + TREE_HORIZONTAL_RADIUS, Chunk.SIZE);
        int columnCountX = maxChunkX - minChunkX + 1;
        ColumnGenerationData[] columns = new ColumnGenerationData[columnCountX * (maxChunkZ - minChunkZ + 1)];
        for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                columns[(chunkX - minChunkX) + (chunkZ - minChunkZ) * columnCountX] =
                        getColumnData(chunkX, chunkZ);
            }
        }

        for (int z = 0; z < sizeZ; z++) {
            int worldZ = originZ + z;
            for (int x = 0; x < sizeX; x++) {
                int worldX = originX + x;
                ColumnGenerationData data = columnForWorld(columns, minChunkX, minChunkZ, columnCountX, worldX, worldZ);
                int height = data.surfaceHeight(
                        Math.floorMod(worldX, Chunk.SIZE),
                        Math.floorMod(worldZ, Chunk.SIZE)
                );
                for (int y = 0; y < sizeY; y++) {
                    destination[x + z * sizeX + y * sizeX * sizeZ] = getBaseTerrainBlock(originY + y, height);
                }
            }
        }

        int maxWorldY = originY + sizeY - 1;
        for (int treeZ = originZ - TREE_HORIZONTAL_RADIUS;
             treeZ < originZ + sizeZ + TREE_HORIZONTAL_RADIUS;
             treeZ++) {
            for (int treeX = originX - TREE_HORIZONTAL_RADIUS;
                 treeX < originX + sizeX + TREE_HORIZONTAL_RADIUS;
                 treeX++) {
                ColumnGenerationData data = columnForWorld(
                        columns, minChunkX, minChunkZ, columnCountX, treeX, treeZ
                );
                int localX = Math.floorMod(treeX, Chunk.SIZE);
                int localZ = Math.floorMod(treeZ, Chunk.SIZE);
                if (!data.hasTree(localX, localZ)) {
                    continue;
                }
                int trunkBaseY = data.surfaceHeight(localX, localZ) + 1;
                if (trunkBaseY > maxWorldY || trunkBaseY + 5 < originY) {
                    continue;
                }
                placeTreeIntoRegion(
                        destination, originX, originY, originZ, sizeX, sizeY, sizeZ,
                        treeX, treeZ, trunkBaseY
                );
            }
        }
    }

    @Override
    public int getSkyLightScanStartY(int worldX, int worldZ, int maxWorldY) {
        // Current generated content can only extend six blocks above the terrain through trees.
        return Math.min(maxWorldY, getSurfaceHeight(worldX, worldZ) + 6);
    }

    @Override
    public ChunkGenerationHint classifyChunk(ChunkPosition position) {
        ChunkGenerationHint globalHint = classifyAgainstBounds(position.y(), globalBounds);
        if (!globalHint.requiresMaterialization()) {
            return globalHint;
        }

        ColumnGenerationData columnData = getReadyColumnDataOrSchedule(position.x(), position.z());
        if (columnData == null) {
            return globalHint;
        }
        return classifyAgainstBounds(position.y(), columnData.bounds());
    }

    private static ChunkGenerationHint classifyAgainstBounds(int chunkY, ColumnBounds bounds) {
        int chunkMinY = chunkY * Chunk.SIZE;
        int chunkMaxY = chunkMinY + Chunk.SIZE - 1;

        if (chunkMinY > bounds.maxContentY()) {
            return ChunkGenerationHint.empty();
        }
        if (chunkMaxY < bounds.minSurfaceY() - TOPSOIL_DEPTH) {
            return ChunkGenerationHint.uniform(Blocks.STONE.getId());
        }
        return ChunkGenerationHint.materialized();
    }

    @Override
    public synchronized void retainChunkClassificationsAround(int centerChunkX, int centerChunkZ, int radius) {
        int retainedRadius = Math.max(0, radius);
        int retainedRadiusSquared = retainedRadius * retainedRadius;
        Iterator<Map.Entry<ColumnPosition, CompletableFuture<ColumnGenerationData>>> iterator =
                classificationCache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ColumnPosition, CompletableFuture<ColumnGenerationData>> entry = iterator.next();
            ColumnPosition position = entry.getKey();
            int dx = position.x() - centerChunkX;
            int dz = position.z() - centerChunkZ;
            if (dx * dx + dz * dz > retainedRadiusSquared) {
                entry.getValue().cancel(false);
                iterator.remove();
            }
        }
    }

    @Override
    public synchronized ChunkClassificationCacheStats getChunkClassificationCacheStats() {
        return new ChunkClassificationCacheStats(
                classificationCache.size(),
                classificationCacheHits,
                classificationCacheMisses
        );
    }

    private int getSurfaceHeight(FastNoiseLite terrainNoise, int worldX, int worldZ) {
        float height = getFractalNoise(
                terrainNoise,
                worldX * config.terrainFrequency(),
                worldZ * config.terrainFrequency(),
                config.terrainOctaves(),
                config.terrainLacunarity(),
                config.terrainGain()
        ) * config.amplitude() + config.baseHeight();
        return (int) height;
    }

    private short getBaseTerrainBlock(int worldY, int height) {
        if (worldY > height) {
            return worldY <= config.waterLevel() ? Blocks.WATER.getId() : Blocks.AIR.getId();
        }

        if (worldY < config.waterLevel() + 1) {
            return worldY >= height - 3 ? Blocks.SAND.getId() : Blocks.STONE.getId();
        }

        if (worldY == height) {
            return Blocks.GRASS_BLOCK.getId();
        }

        return worldY >= height - 3 ? Blocks.DIRT.getId() : Blocks.STONE.getId();
    }

    private short getTreeBlockAt(
            ColumnGenerationData data,
            int chunkX,
            int chunkZ,
            int treeX,
            int treeZ,
            int worldX,
            int worldY,
            int worldZ
    ) {
        int localTreeX = treeX - chunkX * Chunk.SIZE;
        int localTreeZ = treeZ - chunkZ * Chunk.SIZE;
        if (!data.hasTree(localTreeX, localTreeZ)) {
            return Blocks.AIR.getId();
        }

        int trunkBaseY = data.surfaceHeight(localTreeX, localTreeZ) + 1;
        if (worldX == treeX && worldZ == treeZ && worldY >= trunkBaseY && worldY < trunkBaseY + 4) {
            return Blocks.WOOD_LOG.getId();
        }

        int dx = worldX - treeX;
        int dy = worldY - (trunkBaseY + 3);
        int dz = worldZ - treeZ;
        int distance = dx * dx + dy * dy + dz * dz;

        if (dy >= 0 && dy <= 2 && distance <= 5) {
            return Blocks.LEAVES.getId();
        }

        return Blocks.AIR.getId();
    }

    private synchronized ColumnGenerationData getReadyColumnDataOrSchedule(int chunkX, int chunkZ) {
        ColumnPosition key = new ColumnPosition(chunkX, chunkZ);
        CompletableFuture<ColumnGenerationData> cached = classificationCache.get(key);
        if (cached != null) {
            classificationCacheHits++;
            if (cached.isCancelled() || cached.isCompletedExceptionally()) {
                classificationCache.remove(key);
                return null;
            }
            return cached.getNow(null);
        }

        classificationCacheMisses++;
        CompletableFuture<ColumnGenerationData> future = new CompletableFuture<>();
        try {
            CLASSIFICATION_EXECUTOR.execute(() -> {
                if (future.isCancelled()) {
                    return;
                }
                try {
                    future.complete(computeColumnData(chunkX, chunkZ));
                } catch (RuntimeException exception) {
                    future.completeExceptionally(exception);
                }
            });
        } catch (RejectedExecutionException ignored) {
            return null;
        }

        classificationCache.put(key, future);
        evictEldestColumnIfNeeded();
        return null;
    }

    private ColumnGenerationData getColumnData(int chunkX, int chunkZ) {
        RecentColumnCache recent = recentColumns.get();
        ColumnGenerationData local = recent.get(chunkX, chunkZ);
        if (local != null) {
            return local;
        }
        ColumnPosition key = new ColumnPosition(chunkX, chunkZ);
        CompletableFuture<ColumnGenerationData> future;
        synchronized (this) {
            future = classificationCache.get(key);
            if (future != null) {
                classificationCacheHits++;
                if (future.isCancelled() || future.isCompletedExceptionally()) {
                    classificationCache.remove(key);
                    future = null;
                }
            }
            if (future != null) {
                ColumnGenerationData ready = future.getNow(null);
                if (ready != null) {
                    recent.put(chunkX, chunkZ, ready);
                    return ready;
                }
            }
            if (future == null) {
                classificationCacheMisses++;
                future = new CompletableFuture<>();
                classificationCache.put(key, future);
                evictEldestColumnIfNeeded();
            }
        }

        ColumnGenerationData computed = computeColumnData(chunkX, chunkZ);
        future.complete(computed);
        recent.put(chunkX, chunkZ, computed);
        return computed;
    }

    private synchronized void evictEldestColumnIfNeeded() {
        if (classificationCache.size() <= maxClassificationCacheColumns) {
            return;
        }
        Iterator<Map.Entry<ColumnPosition, CompletableFuture<ColumnGenerationData>>> iterator =
                classificationCache.entrySet().iterator();
        Map.Entry<ColumnPosition, CompletableFuture<ColumnGenerationData>> eldest = iterator.next();
        eldest.getValue().cancel(false);
        iterator.remove();
    }

    private ColumnGenerationData computeColumnData(int chunkX, int chunkZ) {
        int chunkWorldX = chunkX * Chunk.SIZE;
        int chunkWorldZ = chunkZ * Chunk.SIZE;
        int minSurfaceY = Integer.MAX_VALUE;
        int maxContentY = config.waterLevel();
        FastNoiseLite terrainNoise = noise.get();
        int extendedSize = Chunk.SIZE + TREE_HORIZONTAL_RADIUS * 2;
        int[] surfaceHeights = new int[extendedSize * extendedSize];
        boolean[] trees = new boolean[extendedSize * extendedSize];

        for (int localZ = -TREE_HORIZONTAL_RADIUS; localZ < Chunk.SIZE + TREE_HORIZONTAL_RADIUS; localZ++) {
            for (int localX = -TREE_HORIZONTAL_RADIUS; localX < Chunk.SIZE + TREE_HORIZONTAL_RADIUS; localX++) {
                int surfaceY = getSurfaceHeight(terrainNoise, chunkWorldX + localX, chunkWorldZ + localZ);
                int index = (localX + TREE_HORIZONTAL_RADIUS)
                        + (localZ + TREE_HORIZONTAL_RADIUS) * extendedSize;
                surfaceHeights[index] = surfaceY;
                if (localX >= 0 && localX < Chunk.SIZE && localZ >= 0 && localZ < Chunk.SIZE) {
                    minSurfaceY = Math.min(minSurfaceY, surfaceY);
                    maxContentY = Math.max(maxContentY, surfaceY);
                }
            }
        }

        FastNoiseLite vegetationNoise = treeNoise.get();
        for (int localZ = -TREE_HORIZONTAL_RADIUS; localZ < Chunk.SIZE + TREE_HORIZONTAL_RADIUS; localZ++) {
            for (int localX = -TREE_HORIZONTAL_RADIUS; localX < Chunk.SIZE + TREE_HORIZONTAL_RADIUS; localX++) {
                int index = (localX + TREE_HORIZONTAL_RADIUS)
                        + (localZ + TREE_HORIZONTAL_RADIUS) * extendedSize;
                int surfaceY = surfaceHeights[index];
                int treeX = chunkWorldX + localX;
                int treeZ = chunkWorldZ + localZ;
                if (surfaceY > config.waterLevel()
                        && shouldPlace(vegetationNoise.GetNoise(treeX, treeZ), treeX, treeZ)) {
                    trees[index] = true;
                    maxContentY = Math.max(maxContentY, surfaceY + TREE_MAX_HEIGHT_ABOVE_SURFACE);
                }
            }
        }

        return new ColumnGenerationData(surfaceHeights, trees, new ColumnBounds(minSurfaceY, maxContentY));
    }

    private static final class GenerationScratch {
        private final short[] blocks = new short[Chunk.TOTAL_BLOCKS];
    }

    private void populateTrees(
            ColumnGenerationData columnData,
            short[] blocks,
            int chunkGlobalX,
            int chunkGlobalY,
            int chunkGlobalZ
    ) {
        int minTreeX = chunkGlobalX - 2;
        int maxTreeX = chunkGlobalX + Chunk.SIZE + 1;
        int minTreeZ = chunkGlobalZ - 2;
        int maxTreeZ = chunkGlobalZ + Chunk.SIZE + 1;
        int chunkMaxWorldY = chunkGlobalY + Chunk.SIZE - 1;

        for (int treeX = minTreeX; treeX <= maxTreeX; treeX++) {
            for (int treeZ = minTreeZ; treeZ <= maxTreeZ; treeZ++) {
                int localTreeX = treeX - chunkGlobalX;
                int localTreeZ = treeZ - chunkGlobalZ;
                if (!columnData.hasTree(localTreeX, localTreeZ)) {
                    continue;
                }

                int trunkBaseY = columnData.surfaceHeight(localTreeX, localTreeZ) + 1;
                int canopyTopY = trunkBaseY + 5;
                if (trunkBaseY > chunkMaxWorldY || canopyTopY < chunkGlobalY) {
                    continue;
                }

                placeTreeIntoChunk(blocks, chunkGlobalX, chunkGlobalY, chunkGlobalZ, treeX, treeZ, trunkBaseY);
            }
        }
    }

    private void placeTreeIntoChunk(
            short[] blocks,
            int chunkGlobalX,
            int chunkGlobalY,
            int chunkGlobalZ,
            int treeX,
            int treeZ,
            int trunkBaseY
    ) {
        for (int offsetY = 0; offsetY < 4; offsetY++) {
            writeTreeBlockIfInChunk(
                    blocks,
                    chunkGlobalX,
                    chunkGlobalY,
                    chunkGlobalZ,
                    treeX,
                    trunkBaseY + offsetY,
                    treeZ,
                    Blocks.WOOD_LOG.getId()
            );
        }

        for (int offsetY = 0; offsetY <= 2; offsetY++) {
            int worldY = trunkBaseY + 3 + offsetY;
            for (int offsetZ = -2; offsetZ <= 2; offsetZ++) {
                for (int offsetX = -2; offsetX <= 2; offsetX++) {
                    int distance = (offsetX * offsetX) + (offsetY * offsetY) + (offsetZ * offsetZ);
                    if (distance > 5) {
                        continue;
                    }

                    writeTreeBlockIfInChunk(
                            blocks,
                            chunkGlobalX,
                            chunkGlobalY,
                            chunkGlobalZ,
                            treeX + offsetX,
                            worldY,
                            treeZ + offsetZ,
                            Blocks.LEAVES.getId()
                    );
                }
            }
        }
    }

    private void writeTreeBlockIfInChunk(
            short[] blocks,
            int chunkGlobalX,
            int chunkGlobalY,
            int chunkGlobalZ,
            int worldX,
            int worldY,
            int worldZ,
            short blockId
    ) {
        int localX = worldX - chunkGlobalX;
        int localY = worldY - chunkGlobalY;
        int localZ = worldZ - chunkGlobalZ;
        if (localX < 0 || localY < 0 || localZ < 0
                || localX >= Chunk.SIZE || localY >= Chunk.SIZE || localZ >= Chunk.SIZE) {
            return;
        }

        int blockIndex = localX + (localZ * Chunk.SIZE) + (localY * Chunk.SIZE * Chunk.SIZE);
        if (blocks[blockIndex] == Blocks.AIR.getId()) {
            blocks[blockIndex] = blockId;
        }
    }

    private static ColumnGenerationData columnForWorld(
            ColumnGenerationData[] columns,
            int minChunkX,
            int minChunkZ,
            int columnCountX,
            int worldX,
            int worldZ
    ) {
        int chunkX = Math.floorDiv(worldX, Chunk.SIZE);
        int chunkZ = Math.floorDiv(worldZ, Chunk.SIZE);
        return columns[(chunkX - minChunkX) + (chunkZ - minChunkZ) * columnCountX];
    }

    private static void placeTreeIntoRegion(
            short[] blocks,
            int originX,
            int originY,
            int originZ,
            int sizeX,
            int sizeY,
            int sizeZ,
            int treeX,
            int treeZ,
            int trunkBaseY
    ) {
        for (int offsetY = 0; offsetY < 4; offsetY++) {
            writeTreeBlockIfInRegion(
                    blocks, originX, originY, originZ, sizeX, sizeY, sizeZ,
                    treeX, trunkBaseY + offsetY, treeZ, Blocks.WOOD_LOG.getId()
            );
        }
        for (int offsetY = 0; offsetY <= 2; offsetY++) {
            int worldY = trunkBaseY + 3 + offsetY;
            for (int offsetZ = -2; offsetZ <= 2; offsetZ++) {
                for (int offsetX = -2; offsetX <= 2; offsetX++) {
                    int distance = offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ;
                    if (distance <= 5) {
                        writeTreeBlockIfInRegion(
                                blocks, originX, originY, originZ, sizeX, sizeY, sizeZ,
                                treeX + offsetX, worldY, treeZ + offsetZ, Blocks.LEAVES.getId()
                        );
                    }
                }
            }
        }
    }

    private static void writeTreeBlockIfInRegion(
            short[] blocks,
            int originX,
            int originY,
            int originZ,
            int sizeX,
            int sizeY,
            int sizeZ,
            int worldX,
            int worldY,
            int worldZ,
            short blockId
    ) {
        int localX = worldX - originX;
        int localY = worldY - originY;
        int localZ = worldZ - originZ;
        if (localX < 0 || localY < 0 || localZ < 0
                || localX >= sizeX || localY >= sizeY || localZ >= sizeZ) {
            return;
        }
        int index = localX + localZ * sizeX + localY * sizeX * sizeZ;
        if (blocks[index] == Blocks.AIR.getId()) {
            blocks[index] = blockId;
        }
    }

    private boolean shouldPlace(float noiseValue, int globalX, int globalZ) {
        float adjusted = noiseValue * 0.2f + 0.2f;
        adjusted = (float) Math.pow(adjusted, config.treeSteepness());
        float random = randomValueBasedOnBlock(globalX, globalZ) * config.treeRarity();
        return adjusted > random;
    }

    private float randomValueBasedOnBlock(int x, int z) {
        return hash2D(x, z);
    }

    private float hash2D(int x, int z) {
        int h = x * 374761393 + z * 668265263;
        h = (h ^ (h >> 13)) * 1274126177;
        return ((h ^ (h >> 16)) & 0x7fffffff) / (float) Integer.MAX_VALUE;
    }

    private FastNoiseLite createNoise(int seed) {
        FastNoiseLite fastNoise = new FastNoiseLite();
        fastNoise.SetSeed(seed);
        return fastNoise;
    }

    private static ThreadPoolExecutor createClassificationExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING_CLASSIFICATION_COLUMNS),
                runnable -> {
                    Thread thread = new Thread(runnable, "voxy-chunk-classifier");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.MIN_PRIORITY);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.prestartCoreThread();
        return executor;
    }

    public static float getFractalNoise(FastNoiseLite noise, float x, float y, int octaves, float lacunarity, float gain) {
        float total = 0f;
        float frequency = 1f;
        float amplitude = 1f;
        float maxAmplitude = 0f;

        for (int i = 0; i < octaves; i++) {
            total += noise.GetNoise(x * frequency, y * frequency) * amplitude;
            maxAmplitude += amplitude;
            frequency *= lacunarity;
            amplitude *= gain;
        }

        return total / maxAmplitude;
    }

    private record ColumnPosition(int x, int z) {
    }

    private record ColumnBounds(int minSurfaceY, int maxContentY) {
    }

    private record ColumnGenerationData(int[] surfaceHeights, boolean[] trees, ColumnBounds bounds) {
        private int surfaceHeight(int localX, int localZ) {
            return surfaceHeights[index(localX, localZ)];
        }

        private boolean hasTree(int localX, int localZ) {
            return trees[index(localX, localZ)];
        }

        private static int index(int localX, int localZ) {
            int extendedSize = Chunk.SIZE + TREE_HORIZONTAL_RADIUS * 2;
            int x = localX + TREE_HORIZONTAL_RADIUS;
            int z = localZ + TREE_HORIZONTAL_RADIUS;
            if (x < 0 || z < 0 || x >= extendedSize || z >= extendedSize) {
                throw new IndexOutOfBoundsException("Column coordinates outside generation halo: " + localX + ", " + localZ);
            }
            return x + z * extendedSize;
        }
    }

    private static final class RecentColumnCache {
        private static final int CAPACITY = 8;
        private final int[] chunkXs = new int[CAPACITY];
        private final int[] chunkZs = new int[CAPACITY];
        private final ColumnGenerationData[] columns = new ColumnGenerationData[CAPACITY];
        private int nextIndex;

        private ColumnGenerationData get(int chunkX, int chunkZ) {
            for (int index = 0; index < CAPACITY; index++) {
                if (columns[index] != null && chunkXs[index] == chunkX && chunkZs[index] == chunkZ) {
                    return columns[index];
                }
            }
            return null;
        }

        private void put(int chunkX, int chunkZ, ColumnGenerationData data) {
            chunkXs[nextIndex] = chunkX;
            chunkZs[nextIndex] = chunkZ;
            columns[nextIndex] = data;
            nextIndex = (nextIndex + 1) % CAPACITY;
        }
    }
}
