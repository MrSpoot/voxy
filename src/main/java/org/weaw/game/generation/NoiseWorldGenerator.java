package org.weaw.game.generation;

import org.weaw.game.Chunk;
import org.weaw.game.ChunkPosition;
import org.weaw.game.utils.BlockCatalog;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.game.utils.Blocks;
import org.weaw.game.utils.FastNoiseLite;

import java.util.Iterator;
import java.util.Collection;
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
    private static final int COLUMN_HALO_RADIUS = TREE_HORIZONTAL_RADIUS + 1;
    private static final int TREE_MAX_HEIGHT_ABOVE_SURFACE = 6;
    private static final float CONTINENT_SCALE = 0.18f;
    private static final float REGION_SCALE = 0.35f;
    private static final float RIDGE_SCALE = 0.85f;
    private static final float CAVE_TUNNEL_THRESHOLD = 0.16f;
    private static final float CAVERN_THRESHOLD = 0.72f;
    private static final int CAVE_FLOOR_DEPTH = 27;
    private static final int CAVE_FLOOR_VARIATION = 8;
    private static final int CAVE_FLOOR_FADE_HEIGHT = 8;
    private static final float CAVE_ENTRANCE_START = 0.55f;
    private static final float CAVE_ENTRANCE_FULL = 0.78f;
    private static final int CAVE_ENTRANCE_DEPTH = 14;
    private static final float CAVE_ENTRANCE_BOOST = 0.22f;
    private static final int DEFAULT_CLASSIFICATION_CACHE_COLUMNS = 4096;
    private static final int MAX_PENDING_CLASSIFICATION_COLUMNS = 4096;
    private static final ThreadPoolExecutor CLASSIFICATION_EXECUTOR = createClassificationExecutor();

    private final GenerationConfig config;
    private final ThreadLocal<NoiseSet> noises;
    private final ThreadLocal<GenerationScratch> generationScratch;
    private final ThreadLocal<RecentColumnCache> recentColumns;
    private final Map<ColumnPosition, CompletableFuture<ColumnGenerationData>> classificationCache;
    private final int maxClassificationCacheColumns;
    private final ColumnBounds globalBounds;
    private final short airBlockId;
    private final short grassBlockId;
    private final short dirtBlockId;
    private final short stoneBlockId;
    private final short sandBlockId;
    private final short woodLogBlockId;
    private final short leavesBlockId;
    private final short waterBlockId;
    private long classificationCacheHits;
    private long classificationCacheMisses;

    public NoiseWorldGenerator(GenerationConfig config) {
        this(config, BlockRegistry.getDefaultCatalog());
    }

    public NoiseWorldGenerator(GenerationConfig config, BlockCatalog blockCatalog) {
        this.config = Objects.requireNonNull(config, "config");
        Objects.requireNonNull(blockCatalog, "blockCatalog");
        if (config.generatorVersion() != GenerationConfig.CURRENT_GENERATOR_VERSION) {
            throw new IllegalArgumentException("Unsupported generator version: " + config.generatorVersion());
        }
        this.airBlockId = blockCatalog.getRuntimeId(Blocks.AIR);
        this.grassBlockId = blockCatalog.getRuntimeId(Blocks.GRASS_BLOCK);
        this.dirtBlockId = blockCatalog.getRuntimeId(Blocks.DIRT);
        this.stoneBlockId = blockCatalog.getRuntimeId(Blocks.STONE);
        this.sandBlockId = blockCatalog.getRuntimeId(Blocks.SAND);
        this.woodLogBlockId = blockCatalog.getRuntimeId(Blocks.WOOD_LOG);
        this.leavesBlockId = blockCatalog.getRuntimeId(Blocks.LEAVES);
        this.waterBlockId = blockCatalog.getRuntimeId(Blocks.WATER);
        this.noises = ThreadLocal.withInitial(this::createNoiseSet);
        this.generationScratch = ThreadLocal.withInitial(GenerationScratch::new);
        this.recentColumns = ThreadLocal.withInitial(RecentColumnCache::new);
        int minimumSurfaceY = (int) Math.floor(config.baseHeight() - Math.abs(config.amplitude()));
        int maximumSurfaceY = (int) Math.ceil(config.baseHeight() + Math.abs(config.amplitude()));
        this.globalBounds = new ColumnBounds(
                minimumSurfaceY,
                Math.max(config.waterLevel(), maximumSurfaceY + TREE_MAX_HEIGHT_ABOVE_SURFACE),
                minimumSurfaceY - CAVE_FLOOR_DEPTH - CAVE_FLOOR_VARIATION
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
        NoiseSet noiseSet = noises.get();

        for (int y = 0; y < Chunk.SIZE; y++) {
            int globalY = chunkGlobalY + y;
            int yOffset = y * Chunk.SIZE * Chunk.SIZE;
            for (int z = 0; z < Chunk.SIZE; z++) {
                int zOffset = yOffset + (z * Chunk.SIZE);
                for (int x = 0; x < Chunk.SIZE; x++) {
                    int height = columnData.surfaceHeight(x, z);
                    blocks[zOffset + x] = getTerrainBlock(
                            noiseSet,
                            chunkGlobalX + x,
                            globalY,
                            chunkGlobalZ + z,
                            height,
                            columnData.hasSmoothSlope(x, z)
                    );
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
        short baseBlock = getTerrainBlock(
                noises.get(),
                worldX,
                worldY,
                worldZ,
                columnData.surfaceHeight(worldX - chunkX * Chunk.SIZE, worldZ - chunkZ * Chunk.SIZE),
                columnData.hasSmoothSlope(worldX - chunkX * Chunk.SIZE, worldZ - chunkZ * Chunk.SIZE)
        );
        if (baseBlock != airBlockId) {
            return baseBlock;
        }

        for (int treeX = worldX - 2; treeX <= worldX + 2; treeX++) {
            for (int treeZ = worldZ - 2; treeZ <= worldZ + 2; treeZ++) {
                short treeBlock = getTreeBlockAt(columnData, chunkX, chunkZ, treeX, treeZ, worldX, worldY, worldZ);
                if (treeBlock != airBlockId) {
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

        NoiseSet noiseSet = noises.get();
        for (int z = 0; z < sizeZ; z++) {
            int worldZ = originZ + z;
            for (int x = 0; x < sizeX; x++) {
                int worldX = originX + x;
                ColumnGenerationData data = columnForWorld(columns, minChunkX, minChunkZ, columnCountX, worldX, worldZ);
                int height = data.surfaceHeight(
                        Math.floorMod(worldX, Chunk.SIZE),
                        Math.floorMod(worldZ, Chunk.SIZE)
                );
                boolean smoothSlope = data.hasSmoothSlope(
                        Math.floorMod(worldX, Chunk.SIZE),
                        Math.floorMod(worldZ, Chunk.SIZE)
                );
                for (int y = 0; y < sizeY; y++) {
                    destination[x + z * sizeX + y * sizeX * sizeZ] = getTerrainBlock(
                            noiseSet, worldX, originY + y, worldZ, height, smoothSlope
                    );
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

    private ChunkGenerationHint classifyAgainstBounds(int chunkY, ColumnBounds bounds) {
        int chunkMinY = chunkY * Chunk.SIZE;
        int chunkMaxY = chunkMinY + Chunk.SIZE - 1;

        if (chunkMinY > bounds.maxContentY()) {
            return ChunkGenerationHint.empty();
        }
        if (chunkMaxY < bounds.minSurfaceY() - TOPSOIL_DEPTH
                && chunkMaxY < bounds.caveBottomY()) {
            return ChunkGenerationHint.uniform(stoneBlockId);
        }
        return ChunkGenerationHint.materialized();
    }

    @Override
    public synchronized void retainChunkClassificationsAround(int centerChunkX, int centerChunkZ, int radius) {
        retainChunkClassificationsAround(
                java.util.List.of(new ChunkPosition(centerChunkX, 0, centerChunkZ)),
                radius
        );
    }

    @Override
    public synchronized void retainChunkClassificationsAround(Collection<ChunkPosition> centers, int radius) {
        int retainedRadius = Math.max(0, radius);
        int retainedRadiusSquared = retainedRadius * retainedRadius;
        Iterator<Map.Entry<ColumnPosition, CompletableFuture<ColumnGenerationData>>> iterator =
                classificationCache.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ColumnPosition, CompletableFuture<ColumnGenerationData>> entry = iterator.next();
            ColumnPosition position = entry.getKey();
            boolean retained = centers.stream().anyMatch(center -> {
                int dx = position.x() - center.x();
                int dz = position.z() - center.z();
                return dx * dx + dz * dz <= retainedRadiusSquared;
            });
            if (!retained) {
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

    private TerrainSample sampleTerrain(NoiseSet noiseSet, int worldX, int worldZ) {
        float frequency = config.terrainFrequency();
        float continent = getFractalNoise(
                noiseSet.continent,
                worldX * frequency * CONTINENT_SCALE,
                worldZ * frequency * CONTINENT_SCALE,
                3,
                2.0f,
                0.5f
        );
        float region = getFractalNoise(
                noiseSet.region,
                worldX * frequency * REGION_SCALE,
                worldZ * frequency * REGION_SCALE,
                2,
                2.0f,
                0.5f
        );
        float detail = getFractalNoise(
                noiseSet.detail,
                worldX * frequency,
                worldZ * frequency,
                config.terrainOctaves(),
                config.terrainLacunarity(),
                config.terrainGain()
        );
        float ridgeSource = getFractalNoise(
                noiseSet.ridge,
                worldX * frequency * RIDGE_SCALE,
                worldZ * frequency * RIDGE_SCALE,
                4,
                2.0f,
                0.5f
        );
        float ridge = (float) Math.pow(1.0f - Math.abs(ridgeSource), 2.0);
        float ruggedness = smoothstep(-0.35f, 0.45f, region);
        float plains = 0.55f * continent + 0.18f * detail;
        float mountains = 0.30f * continent + 0.20f * detail + ridge - 0.35f;
        float normalizedHeight = lerp(plains, mountains, ruggedness);
        normalizedHeight = Math.max(-1.0f, Math.min(1.0f, normalizedHeight));
        int surfaceHeight = Math.round(config.baseHeight() + config.amplitude() * normalizedHeight);
        float treeSuitability = 0.20f + 0.65f * (1.0f - ruggedness);
        return new TerrainSample(surfaceHeight, treeSuitability);
    }

    private short getTerrainBlock(
            NoiseSet noiseSet,
            int worldX,
            int worldY,
            int worldZ,
            int height,
            boolean allowEntrance
    ) {
        short baseBlock = getBaseTerrainBlock(worldY, height);
        if (baseBlock == stoneBlockId
                && isCave(noiseSet, worldX, worldY, worldZ, height, allowEntrance)) {
            return airBlockId;
        }
        return baseBlock == stoneBlockId ? getSurfaceMaterial(worldY, height) : baseBlock;
    }

    private short getBaseTerrainBlock(int worldY, int height) {
        if (worldY > height) {
            return worldY <= config.waterLevel() ? waterBlockId : airBlockId;
        }

        return stoneBlockId;
    }

    private short getSurfaceMaterial(int worldY, int height) {
        if (worldY < config.waterLevel() + 1) {
            return worldY >= height - 3 ? sandBlockId : stoneBlockId;
        }

        if (worldY == height) {
            return grassBlockId;
        }

        return worldY >= height - 3 ? dirtBlockId : stoneBlockId;
    }

    private boolean isCave(
            NoiseSet noiseSet,
            int worldX,
            int worldY,
            int worldZ,
            int surfaceHeight,
            boolean allowEntrance
    ) {
        int caveFloorY = getCaveFloorY(noiseSet, worldX, worldZ);
        if (worldY < caveFloorY || worldY > surfaceHeight) {
            return false;
        }

        float entranceStrength = allowEntrance && surfaceHeight > config.waterLevel()
                ? smoothstep(
                        CAVE_ENTRANCE_START,
                        CAVE_ENTRANCE_FULL,
                        noiseSet.entrance.GetNoise(worldX * 0.35f, worldZ * 0.35f)
                )
                : 0.0f;
        int caveCeiling = entranceStrength > 0.0f ? surfaceHeight : surfaceHeight - TOPSOIL_DEPTH;
        if (worldY > caveCeiling) {
            return false;
        }

        float floorFade = smoothstep(caveFloorY, caveFloorY + CAVE_FLOOR_FADE_HEIGHT, worldY);
        int depth = surfaceHeight - worldY;
        float entranceFade = 1.0f - smoothstep(0.0f, CAVE_ENTRANCE_DEPTH, depth);
        float caveA = noiseSet.caveA.GetNoise(worldX * 1.5f, worldY * 1.1f, worldZ * 1.5f);
        float caveB = noiseSet.caveB.GetNoise(worldX * 1.5f, worldY * 1.1f, worldZ * 1.5f);
        float tunnelThreshold = (CAVE_TUNNEL_THRESHOLD
                + CAVE_ENTRANCE_BOOST * entranceStrength * entranceFade) * floorFade;
        boolean tunnel = Math.abs(caveA) + Math.abs(caveB) < tunnelThreshold;
        float cavernThreshold = lerp(1.0f, CAVERN_THRESHOLD, floorFade);
        boolean cavern = worldY <= surfaceHeight - 8
                && noiseSet.cavern.GetNoise(worldX * 0.65f, worldY * 0.45f, worldZ * 0.65f)
                > cavernThreshold;
        return tunnel || cavern;
    }

    private int getCaveFloorY(NoiseSet noiseSet, int worldX, int worldZ) {
        int center = globalBounds.minSurfaceY() - CAVE_FLOOR_DEPTH;
        return center + Math.round(
                noiseSet.caveFloor.GetNoise(worldX * 0.25f, worldZ * 0.25f) * CAVE_FLOOR_VARIATION
        );
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
            return airBlockId;
        }

        int trunkBaseY = data.surfaceHeight(localTreeX, localTreeZ) + 1;
        if (worldX == treeX && worldZ == treeZ && worldY >= trunkBaseY && worldY < trunkBaseY + 4) {
            return woodLogBlockId;
        }

        int dx = worldX - treeX;
        int dy = worldY - (trunkBaseY + 3);
        int dz = worldZ - treeZ;
        int distance = dx * dx + dy * dy + dz * dz;

        if (dy >= 0 && dy <= 2 && distance <= 5) {
            return leavesBlockId;
        }

        return airBlockId;
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
        int minCaveFloorY = Integer.MAX_VALUE;
        int maxContentY = config.waterLevel();
        NoiseSet noiseSet = noises.get();
        int extendedSize = Chunk.SIZE + COLUMN_HALO_RADIUS * 2;
        int[] surfaceHeights = new int[extendedSize * extendedSize];
        float[] treeSuitability = new float[extendedSize * extendedSize];
        boolean[] trees = new boolean[extendedSize * extendedSize];

        for (int localZ = -COLUMN_HALO_RADIUS; localZ < Chunk.SIZE + COLUMN_HALO_RADIUS; localZ++) {
            for (int localX = -COLUMN_HALO_RADIUS; localX < Chunk.SIZE + COLUMN_HALO_RADIUS; localX++) {
                TerrainSample terrain = sampleTerrain(noiseSet, chunkWorldX + localX, chunkWorldZ + localZ);
                int index = (localX + COLUMN_HALO_RADIUS)
                        + (localZ + COLUMN_HALO_RADIUS) * extendedSize;
                surfaceHeights[index] = terrain.surfaceHeight();
                treeSuitability[index] = terrain.treeSuitability();
                if (localX >= 0 && localX < Chunk.SIZE && localZ >= 0 && localZ < Chunk.SIZE) {
                    minSurfaceY = Math.min(minSurfaceY, terrain.surfaceHeight());
                    minCaveFloorY = Math.min(
                            minCaveFloorY,
                            getCaveFloorY(noiseSet, chunkWorldX + localX, chunkWorldZ + localZ)
                    );
                    maxContentY = Math.max(maxContentY, terrain.surfaceHeight());
                }
            }
        }

        for (int localZ = -TREE_HORIZONTAL_RADIUS; localZ < Chunk.SIZE + TREE_HORIZONTAL_RADIUS; localZ++) {
            for (int localX = -TREE_HORIZONTAL_RADIUS; localX < Chunk.SIZE + TREE_HORIZONTAL_RADIUS; localX++) {
                int index = columnIndex(localX, localZ);
                int surfaceY = surfaceHeights[index];
                int treeX = chunkWorldX + localX;
                int treeZ = chunkWorldZ + localZ;
                int maximumSlope = Math.max(
                        Math.max(
                                Math.abs(surfaceY - surfaceHeights[columnIndex(localX - 1, localZ)]),
                                Math.abs(surfaceY - surfaceHeights[columnIndex(localX + 1, localZ)])
                        ),
                        Math.max(
                                Math.abs(surfaceY - surfaceHeights[columnIndex(localX, localZ - 1)]),
                                Math.abs(surfaceY - surfaceHeights[columnIndex(localX, localZ + 1)])
                        )
                );
                if (surfaceY > config.waterLevel()
                        && maximumSlope <= 2
                        && !isCave(noiseSet, treeX, surfaceY, treeZ, surfaceY, maximumSlope <= 1)
                        && shouldPlace(
                                noiseSet.vegetation.GetNoise(treeX, treeZ),
                                treeSuitability[index],
                                treeX,
                                treeZ
                        )) {
                    trees[index] = true;
                    maxContentY = Math.max(maxContentY, surfaceY + TREE_MAX_HEIGHT_ABOVE_SURFACE);
                }
            }
        }

        return new ColumnGenerationData(
                surfaceHeights,
                trees,
                new ColumnBounds(minSurfaceY, maxContentY, minCaveFloorY)
        );
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
                    woodLogBlockId
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
                            leavesBlockId
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
        if (blocks[blockIndex] == airBlockId) {
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

    private void placeTreeIntoRegion(
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
                    treeX, trunkBaseY + offsetY, treeZ, woodLogBlockId
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
                                treeX + offsetX, worldY, treeZ + offsetZ, leavesBlockId
                        );
                    }
                }
            }
        }
    }

    private void writeTreeBlockIfInRegion(
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
        if (blocks[index] == airBlockId) {
            blocks[index] = blockId;
        }
    }

    private boolean shouldPlace(float noiseValue, float suitability, int globalX, int globalZ) {
        float adjusted = noiseValue * 0.2f + 0.2f;
        adjusted = (float) Math.pow(Math.max(0.0f, adjusted), config.treeSteepness()) * suitability;
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

    private NoiseSet createNoiseSet() {
        int seed = (int) config.seed();
        return new NoiseSet(
                createNoise(seed + 101),
                createNoise(seed + 211),
                createNoise(seed),
                createNoise(seed + 307),
                createNoise(seed + config.treeSeedOffset()),
                createNoise(seed + 401),
                createNoise(seed + 503),
                createNoise(seed + 601),
                createNoise(seed + 701),
                createNoise(seed + 809)
        );
    }

    private static float smoothstep(float lower, float upper, float value) {
        float normalized = Math.max(0.0f, Math.min(1.0f, (value - lower) / (upper - lower)));
        return normalized * normalized * (3.0f - 2.0f * normalized);
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static int columnIndex(int localX, int localZ) {
        int extendedSize = Chunk.SIZE + COLUMN_HALO_RADIUS * 2;
        return localX + COLUMN_HALO_RADIUS + (localZ + COLUMN_HALO_RADIUS) * extendedSize;
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

    private record TerrainSample(int surfaceHeight, float treeSuitability) {
    }

    private record ColumnBounds(int minSurfaceY, int maxContentY, int caveBottomY) {
    }

    private record ColumnGenerationData(int[] surfaceHeights, boolean[] trees, ColumnBounds bounds) {
        private int surfaceHeight(int localX, int localZ) {
            return surfaceHeights[index(localX, localZ)];
        }

        private boolean hasTree(int localX, int localZ) {
            return trees[index(localX, localZ)];
        }

        private boolean hasSmoothSlope(int localX, int localZ) {
            int height = surfaceHeight(localX, localZ);
            return Math.abs(height - surfaceHeight(localX - 1, localZ)) <= 1
                    && Math.abs(height - surfaceHeight(localX + 1, localZ)) <= 1
                    && Math.abs(height - surfaceHeight(localX, localZ - 1)) <= 1
                    && Math.abs(height - surfaceHeight(localX, localZ + 1)) <= 1;
        }

        private static int index(int localX, int localZ) {
            int extendedSize = Chunk.SIZE + COLUMN_HALO_RADIUS * 2;
            int x = localX + COLUMN_HALO_RADIUS;
            int z = localZ + COLUMN_HALO_RADIUS;
            if (x < 0 || z < 0 || x >= extendedSize || z >= extendedSize) {
                throw new IndexOutOfBoundsException("Column coordinates outside generation halo: " + localX + ", " + localZ);
            }
            return x + z * extendedSize;
        }
    }

    private record NoiseSet(
            FastNoiseLite continent,
            FastNoiseLite region,
            FastNoiseLite detail,
            FastNoiseLite ridge,
            FastNoiseLite vegetation,
            FastNoiseLite caveA,
            FastNoiseLite caveB,
            FastNoiseLite cavern,
            FastNoiseLite entrance,
            FastNoiseLite caveFloor
    ) {
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
