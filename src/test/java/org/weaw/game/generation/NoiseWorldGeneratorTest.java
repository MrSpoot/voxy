package org.weaw.game.generation;

import org.joml.Vector3i;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.weaw.game.Chunk;
import org.weaw.game.ChunkPosition;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.game.utils.Blocks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class NoiseWorldGeneratorTest {
    @BeforeAll
    static void initializeBlocks() {
        BlockRegistry.initialize();
    }

    @Test
    void classifiesDeepSurfaceAndHighChunksConservatively() {
        NoiseWorldGenerator generator = new NoiseWorldGenerator(GenerationConfig.defaults());

        ChunkGenerationHint deep = generator.classifyChunk(new ChunkPosition(0, -5, 0));
        ChunkGenerationHint caveBand = generator.classifyChunk(new ChunkPosition(0, -2, 0));
        ChunkGenerationHint topsoilBoundary = generator.classifyChunk(new ChunkPosition(0, -1, 0));
        ChunkGenerationHint surface = generator.classifyChunk(new ChunkPosition(0, 0, 0));
        ChunkGenerationHint high = generator.classifyChunk(new ChunkPosition(0, 1, 0));

        assertEquals(ChunkGenerationHint.Kind.UNIFORM, deep.kind());
        assertEquals(BlockRegistry.getRuntimeId(Blocks.STONE), deep.uniformBlockId());
        assertEquals(ChunkGenerationHint.Kind.MATERIALIZED, caveBand.kind());
        assertEquals(ChunkGenerationHint.Kind.MATERIALIZED, topsoilBoundary.kind());
        assertEquals(ChunkGenerationHint.Kind.MATERIALIZED, surface.kind());
        assertEquals(ChunkGenerationHint.Kind.EMPTY, high.kind());
    }

    @Test
    void reusesAndEvictsColumnClassificationBounds() {
        NoiseWorldGenerator generator = new NoiseWorldGenerator(GenerationConfig.defaults());

        generator.classifyChunk(new ChunkPosition(0, 0, 0));
        generator.classifyChunk(new ChunkPosition(0, 0, 0));
        generator.classifyChunk(new ChunkPosition(0, 0, 0));

        ChunkClassificationCacheStats populated = generator.getChunkClassificationCacheStats();
        assertEquals(1, populated.size());
        assertEquals(1L, populated.misses());
        assertEquals(2L, populated.hits());

        generator.retainChunkClassificationsAround(100, 100, 1);
        assertEquals(0, generator.getChunkClassificationCacheStats().size());
    }

    @Test
    void defaultWorldMaterializesLessThanSixtyPercentOfTheLegacyCylinder() {
        NoiseWorldGenerator generator = new NoiseWorldGenerator(GenerationConfig.defaults());
        int candidates = 0;
        int materialized = 0;
        int empty = 0;
        int uniform = 0;

        for (int z = -16; z <= 16; z++) {
            for (int x = -16; x <= 16; x++) {
                if (x * x + z * z > 16 * 16) {
                    continue;
                }
                for (int y = -4; y <= 3; y++) {
                    candidates++;
                    ChunkGenerationHint hint = generator.classifyChunk(new ChunkPosition(x, y, z));
                    switch (hint.kind()) {
                        case EMPTY -> empty++;
                        case UNIFORM -> uniform++;
                        case MATERIALIZED -> materialized++;
                    }
                }
            }
        }

        assertEquals(6_376, candidates);
        assertTrue(empty > 0);
        assertTrue(uniform > 0);
        assertTrue(
                materialized <= candidates * 0.60,
                "sparse streaming should remove at least 40% of candidates, materialized=" + materialized
        );
    }

    @Test
    void bulkRegionSamplingMatchesScalarSamplingAcrossChunkBoundaries() {
        NoiseWorldGenerator generator = new NoiseWorldGenerator(GenerationConfig.defaults());
        int originX = -33;
        int originY = -3;
        int originZ = 29;
        int sizeX = 35;
        int sizeY = 40;
        int sizeZ = 35;
        short[] sampled = new short[sizeX * sizeY * sizeZ];

        generator.fillBlockRegion(originX, originY, originZ, sizeX, sizeY, sizeZ, sampled);

        for (int y = 0; y < sizeY; y++) {
            for (int z = 0; z < sizeZ; z++) {
                for (int x = 0; x < sizeX; x++) {
                    assertEquals(
                            generator.getBlockAtWorld(originX + x, originY + y, originZ + z),
                            sampled[x + z * sizeX + y * sizeX * sizeZ],
                            "bulk sample mismatch at " + x + ", " + y + ", " + z
                    );
                }
            }
        }
    }

    @Test
    void generationIsDeterministicRegardlessOfChunkOrder() {
        List<ChunkPosition> positions = List.of(
                new ChunkPosition(-2, -2, 1),
                new ChunkPosition(0, -1, 0),
                new ChunkPosition(1, 0, -2),
                new ChunkPosition(3, 1, 2)
        );
        Map<ChunkPosition, short[]> expected = generate(GenerationConfig.defaults(), positions);
        List<ChunkPosition> reversed = new ArrayList<>(positions);
        Collections.reverse(reversed);

        Map<ChunkPosition, short[]> actual = generate(GenerationConfig.defaults(), reversed);

        positions.forEach(position -> assertArrayEquals(expected.get(position), actual.get(position), position.toString()));
    }

    @Test
    void differentSeedsProduceDifferentTerrain() {
        NoiseWorldGenerator first = new NoiseWorldGenerator(GenerationConfig.defaults().withSeed(1L));
        NoiseWorldGenerator second = new NoiseWorldGenerator(GenerationConfig.defaults().withSeed(2L));
        int differences = 0;
        for (int z = -256; z <= 256; z += 16) {
            for (int x = -256; x <= 256; x += 16) {
                if (first.getSurfaceHeight(x, z) != second.getSurfaceHeight(x, z)) {
                    differences++;
                }
            }
        }
        assertNotEquals(0, differences);
    }

    @Test
    void defaultTerrainContainsWaterPlainsMountainsAndSmoothSlopes() {
        GenerationConfig config = GenerationConfig.defaults();
        NoiseWorldGenerator generator = new NoiseWorldGenerator(config);
        int minimum = Integer.MAX_VALUE;
        int maximum = Integer.MIN_VALUE;
        int wetColumns = 0;
        int gentleColumns = 0;
        int steepColumns = 0;
        int mountainColumns = 0;
        int sampledColumns = 0;
        for (int z = -512; z <= 512; z += 8) {
            for (int x = -512; x <= 512; x += 8) {
                int height = generator.getSurfaceHeight(x, z);
                int adjacentSlope = Math.max(
                        Math.abs(height - generator.getSurfaceHeight(x + 1, z)),
                        Math.abs(height - generator.getSurfaceHeight(x, z + 1))
                );
                int shortGrade = Math.max(
                        Math.abs(height - generator.getSurfaceHeight(x + 2, z)),
                        Math.abs(height - generator.getSurfaceHeight(x, z + 2))
                );
                minimum = Math.min(minimum, height);
                maximum = Math.max(maximum, height);
                wetColumns += height <= config.waterLevel() ? 1 : 0;
                gentleColumns += adjacentSlope <= 1 ? 1 : 0;
                steepColumns += shortGrade >= 2 ? 1 : 0;
                mountainColumns += height >= config.baseHeight() + 15 ? 1 : 0;
                sampledColumns++;
            }
        }

        String distribution = "range=" + (maximum - minimum)
                + ", mountains=" + mountainColumns
                + ", smooth=" + gentleColumns
                + ", cliffs=" + steepColumns
                + ", samples=" + sampledColumns;
        assertTrue(maximum - minimum >= 35, "terrain should use most of its configured amplitude: " + distribution);
        assertTrue(wetColumns > 0, "continental noise should create coasts or islands");
        assertTrue(mountainColumns >= sampledColumns * 0.05, "mountain chains should be common enough: " + distribution);
        assertTrue(gentleColumns >= sampledColumns * 0.95, "most slopes should rise by at most one block: " + distribution);
        assertTrue(steepColumns > 0, "some sustained one-block cliff grades should remain: " + distribution);
    }

    @Test
    void defaultTerrainContainsMediumDensityCavesAndSurfaceEntrances() {
        GenerationConfig config = GenerationConfig.defaults();
        NoiseWorldGenerator generator = new NoiseWorldGenerator(config);
        int carved = 0;
        int undergroundSamples = 0;
        int entrances = 0;
        int caveBottom = (int) Math.floor(config.baseHeight() - Math.abs(config.amplitude())) - 35;
        for (int z = -192; z <= 192; z += 3) {
            for (int x = -192; x <= 192; x += 3) {
                int surface = generator.getSurfaceHeight(x, z);
                if (surface > config.waterLevel()
                        && generator.getBlockAtWorld(x, surface, z) == BlockRegistry.getRuntimeId(Blocks.AIR)) {
                    entrances++;
                }
                for (int y = caveBottom; y <= surface; y += 2) {
                    undergroundSamples++;
                    if (generator.getBlockAtWorld(x, y, z) == BlockRegistry.getRuntimeId(Blocks.AIR)) {
                        carved++;
                    }
                }
            }
        }

        float density = carved / (float) undergroundSamples;
        assertTrue(density >= 0.002f && density <= 0.15f, "unexpected cave density: " + density);
        assertTrue(entrances > 0, "at least one dry cave entrance should be discoverable");
    }

    @Test
    void caveFloorIsDeepAndIrregular() {
        GenerationConfig config = GenerationConfig.defaults();
        NoiseWorldGenerator generator = new NoiseWorldGenerator(config);
        int oldFloor = (int) Math.floor(config.baseHeight() - Math.abs(config.amplitude()));
        Set<Integer> lowestCarvedLevels = new HashSet<>();
        int deepest = Integer.MAX_VALUE;
        int shallowest = Integer.MIN_VALUE;

        for (int z = -256; z <= 256; z += 4) {
            for (int x = -256; x <= 256; x += 4) {
                int lowest = Integer.MAX_VALUE;
                int surface = generator.getSurfaceHeight(x, z);
                for (int y = -64; y <= Math.min(surface - 8, oldFloor); y++) {
                    if (generator.getBlockAtWorld(x, y, z) == BlockRegistry.getRuntimeId(Blocks.AIR)) {
                        lowest = y;
                        break;
                    }
                }
                if (lowest != Integer.MAX_VALUE) {
                    lowestCarvedLevels.add(lowest);
                    deepest = Math.min(deepest, lowest);
                    shallowest = Math.max(shallowest, lowest);
                }
            }
        }

        assertTrue(deepest <= oldFloor - 16, "caves should extend well below the old flat floor");
        assertTrue(shallowest - deepest >= 12, "cave floors should not align on one horizontal plane");
        assertTrue(lowestCarvedLevels.size() >= 8, "cave floors should occupy several distinct heights");
    }

    @Test
    void surfaceEntrancesFormModerateWalkableGroups() {
        NoiseWorldGenerator generator = new NoiseWorldGenerator(GenerationConfig.defaults());
        int size = 384;
        int origin = -size / 2;
        boolean[] entrances = new boolean[size * size];
        int roughEntrances = 0;
        for (int localZ = 0; localZ < size; localZ++) {
            for (int localX = 0; localX < size; localX++) {
                int worldX = origin + localX;
                int worldZ = origin + localZ;
                int surface = generator.getSurfaceHeight(worldX, worldZ);
                entrances[localX + localZ * size] = surface > GenerationConfig.defaults().waterLevel()
                        && generator.getBlockAtWorld(worldX, surface, worldZ) == BlockRegistry.getRuntimeId(Blocks.AIR);
                if (entrances[localX + localZ * size]) {
                    int maximumSlope = Math.max(
                            Math.max(
                                    Math.abs(surface - generator.getSurfaceHeight(worldX - 1, worldZ)),
                                    Math.abs(surface - generator.getSurfaceHeight(worldX + 1, worldZ))
                            ),
                            Math.max(
                                    Math.abs(surface - generator.getSurfaceHeight(worldX, worldZ - 1)),
                                    Math.abs(surface - generator.getSurfaceHeight(worldX, worldZ + 1))
                            )
                    );
                    roughEntrances += maximumSlope > 1 ? 1 : 0;
                }
            }
        }

        boolean[] visited = new boolean[entrances.length];
        int walkableGroups = 0;
        boolean reachesDepth = false;
        for (int index = 0; index < entrances.length; index++) {
            if (!entrances[index] || visited[index]) {
                continue;
            }
            ArrayDeque<Integer> pending = new ArrayDeque<>();
            List<Integer> group = new ArrayList<>();
            pending.add(index);
            visited[index] = true;
            while (!pending.isEmpty()) {
                int current = pending.removeFirst();
                group.add(current);
                int x = current % size;
                int z = current / size;
                if (x > 0) {
                    addEntrance(entrances, visited, pending, current - 1);
                }
                if (x + 1 < size) {
                    addEntrance(entrances, visited, pending, current + 1);
                }
                if (z > 0) {
                    addEntrance(entrances, visited, pending, current - size);
                }
                if (z + 1 < size) {
                    addEntrance(entrances, visited, pending, current + size);
                }
            }
            if (group.size() >= 4) {
                walkableGroups++;
                for (int entrance : group) {
                    int worldX = origin + entrance % size;
                    int worldZ = origin + entrance / size;
                    if (hasDescendingAirPath(generator, worldX, worldZ)) {
                        reachesDepth = true;
                        break;
                    }
                }
            }
        }

        assertTrue(walkableGroups >= 2, "several cave entrance groups should be visible");
        assertTrue(walkableGroups <= 64, "entrances should remain moderately frequent");
        assertTrue(reachesDepth, "at least one surface opening should connect to a deeper cave");
        assertEquals(0, roughEntrances, "cave entrances should only open on one-block slopes");
    }

    @Test
    void materializedChunkMatchesScalarSamplingInsideCaveBand() {
        NoiseWorldGenerator generator = new NoiseWorldGenerator(GenerationConfig.defaults());
        for (ChunkPosition position : List.of(
                new ChunkPosition(-1, -3, 1),
                new ChunkPosition(0, -2, -1)
        )) {
            Chunk chunk = new Chunk(new Vector3i(position.x(), position.y(), position.z()));
            generator.generateChunkData(chunk);

            for (int y = 0; y < Chunk.SIZE; y++) {
                for (int z = 0; z < Chunk.SIZE; z++) {
                    for (int x = 0; x < Chunk.SIZE; x++) {
                        assertEquals(
                                generator.getBlockAtWorld(
                                        position.x() * Chunk.SIZE + x,
                                        position.y() * Chunk.SIZE + y,
                                        position.z() * Chunk.SIZE + z
                                ),
                                chunk.getBlock(x, y, z)
                        );
                    }
                }
            }
        }
    }

    private static void addEntrance(
            boolean[] entrances,
            boolean[] visited,
            ArrayDeque<Integer> pending,
            int index
    ) {
        if (entrances[index] && !visited[index]) {
            visited[index] = true;
            pending.add(index);
        }
    }

    private static boolean hasDescendingAirPath(NoiseWorldGenerator generator, int startX, int startZ) {
        int startY = generator.getSurfaceHeight(startX, startZ);
        ArrayDeque<TestBlock> pending = new ArrayDeque<>();
        Set<TestBlock> visited = new HashSet<>();
        pending.add(new TestBlock(startX, startY, startZ));
        while (!pending.isEmpty() && visited.size() < 4_096) {
            TestBlock current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            if (current.y() <= startY - 8) {
                return true;
            }
            for (TestBlock next : List.of(
                    new TestBlock(current.x() - 1, current.y(), current.z()),
                    new TestBlock(current.x() + 1, current.y(), current.z()),
                    new TestBlock(current.x(), current.y() - 1, current.z()),
                    new TestBlock(current.x(), current.y() + 1, current.z()),
                    new TestBlock(current.x(), current.y(), current.z() - 1),
                    new TestBlock(current.x(), current.y(), current.z() + 1)
            )) {
                if (Math.abs(next.x() - startX) <= 12
                        && Math.abs(next.z() - startZ) <= 12
                        && next.y() <= startY
                        && next.y() >= startY - 16
                        && next.y() <= generator.getSurfaceHeight(next.x(), next.z())
                        && generator.getBlockAtWorld(next.x(), next.y(), next.z()) == BlockRegistry.getRuntimeId(Blocks.AIR)
                        && !visited.contains(next)) {
                    pending.add(next);
                }
            }
        }
        return false;
    }

    private record TestBlock(int x, int y, int z) {
    }

    private static Map<ChunkPosition, short[]> generate(GenerationConfig config, List<ChunkPosition> positions) {
        NoiseWorldGenerator generator = new NoiseWorldGenerator(config);
        Map<ChunkPosition, short[]> generated = new HashMap<>();
        for (ChunkPosition position : positions) {
            Chunk chunk = new Chunk(new Vector3i(position.x(), position.y(), position.z()));
            generator.generateChunkData(chunk);
            generated.put(position, chunk.snapshotBlocks());
        }
        return generated;
    }
}
