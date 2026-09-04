package org.weaw.game;

import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.utils.BlockCatalog;
import org.weaw.game.utils.BlockDefinition;
import org.weaw.game.utils.BlockRegistry;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongConsumer;

/** Persistent incremental RGB and skylight solver. */
public final class WorldLightingSystem {
    public static final int BLOCK_LIGHT_MAX_LEVEL = 15;
    public static final int SKY_LIGHT_MAX_LEVEL = 15;
    public static final int LIGHT_CHUNK_RADIUS = 1;

    private static final int BACKGROUND_NODE_BUDGET = Math.max(256,
            Integer.getInteger("voxy.lighting.backgroundNodeBudget", 8_192));
    private static final long BACKGROUND_TIME_BUDGET_NS = Math.max(100_000L,
            Long.getLong("voxy.lighting.backgroundBudgetNs", 400_000L));
    private static final int TIME_CHECK_INTERVAL = 256;
    private static final int FACE_AREA = Chunk.SIZE * Chunk.SIZE;
    private static final int BOUNDARY_CELL_COUNT = 6 * FACE_AREA;
    private static final int[][] OFFSETS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0},
            {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private final BlockCatalog blockCatalog;
    private final WorldBlockProvider blockProvider;
    private final WorldHeightRange heightRange;
    private final ChunkLightingInitializer initializer;
    private final LongWorkQueue priorityChanges = new LongWorkQueue(32);
    private final LongWorkQueue priorityWork = new LongWorkQueue(1_024);
    private final LongWorkQueue backgroundWork = new LongWorkQueue(8_192);
    private final ArrayDeque<BoundaryJob> boundaryJobs = new ArrayDeque<>();
    private final Set<ChunkPosition> queuedBoundaryChunks = new LinkedHashSet<>();
    private final LongIntMap dirtyChunks = new LongIntMap(64);
    private final ChunkLookupCache chunkCache = new ChunkLookupCache();

    public WorldLightingSystem() {
        this(BlockRegistry.getDefaultCatalog(),
                (x, y, z) -> BlockRegistry.getDefaultCatalog().air().getId(),
                WorldHeightRange.configuredDefault());
    }

    public WorldLightingSystem(BlockCatalog blockCatalog) {
        this(blockCatalog, (x, y, z) -> blockCatalog.air().getId(), WorldHeightRange.configuredDefault());
    }

    public WorldLightingSystem(BlockCatalog blockCatalog, WorldBlockProvider blockProvider, WorldHeightRange heightRange) {
        this.blockCatalog = Objects.requireNonNull(blockCatalog, "blockCatalog");
        this.blockProvider = Objects.requireNonNull(blockProvider, "blockProvider");
        this.heightRange = Objects.requireNonNull(heightRange, "heightRange");
        this.initializer = new ChunkLightingInitializer(blockCatalog, blockProvider, heightRange);
    }

    public void initializeChunk(Chunk chunk) {
        initializer.initialize(chunk);
    }

    public void ensureInitialized(Chunk chunk) {
        if (!chunk.isLightingInitialized()) {
            initializer.initialize(chunk);
        }
    }

    public void enqueueChunkBoundary(ChunkPosition position) {
        if (queuedBoundaryChunks.add(position)) {
            boundaryJobs.addLast(new BoundaryJob(position));
        }
    }

    public void enqueueBlockChange(int worldX, int worldY, int worldZ) {
        if (heightRange.contains(Math.floorDiv(worldY, Chunk.SIZE))) {
            priorityChanges.add(packPosition(worldX, worldY, worldZ));
        }
    }

    public boolean hasPendingWork() {
        return !priorityChanges.isEmpty() || !priorityWork.isEmpty()
                || !backgroundWork.isEmpty() || !boundaryJobs.isEmpty();
    }

    int getPendingPriorityChangeCount() {
        return priorityChanges.size() + priorityWork.size();
    }

    int getPendingBackgroundWorkCount() {
        return boundaryJobs.size() + backgroundWork.size();
    }

    public void clearPendingWork() {
        priorityChanges.clear();
        priorityWork.clear();
        backgroundWork.clear();
        boundaryJobs.clear();
        queuedBoundaryChunks.clear();
        dirtyChunks.clear();
    }

    public WorldLightingUpdateResult processFrame(ChunkManager chunkManager) {
        return process(chunkManager, true);
    }

    public WorldLightingUpdateResult processPriority(ChunkManager chunkManager) {
        return process(chunkManager, false);
    }

    private WorldLightingUpdateResult process(ChunkManager chunkManager, boolean includeBackground) {
        Objects.requireNonNull(chunkManager, "chunkManager");
        dirtyChunks.clear();
        chunkCache.reset(chunkManager);
        long start = System.nanoTime();
        int seeded = 0;

        while (!priorityChanges.isEmpty()) {
            long packed = priorityChanges.poll();
            int x = unpackX(packed);
            int y = unpackY(packed);
            int z = unpackZ(packed);
            seeded += recomputeDirectSkyColumn(x, z, priorityWork);
            enqueueCellAndNeighbors(priorityWork, x, y, z);
            seeded += 7;
        }

        ProcessingCounters counters = new ProcessingCounters();
        drainRelaxationQueue(chunkManager, priorityWork, Integer.MAX_VALUE, Long.MAX_VALUE, counters);

        Map<ChunkPosition, Integer> priorityChanged = snapshotDirtyChunks();
        int marked = chunkManager.markChunksLightUpdated(priorityChanged, true);
        Set<ChunkPosition> changedPositions = new LinkedHashSet<>(priorityChanged.keySet());
        int dirtyChunkCount = priorityChanged.size();
        dirtyChunks.clear();

        long backgroundDeadline = System.nanoTime() + BACKGROUND_TIME_BUDGET_NS;
        int backgroundSteps = 0;
        while (includeBackground && backgroundSteps < BACKGROUND_NODE_BUDGET) {
            if ((backgroundSteps & (TIME_CHECK_INTERVAL - 1)) == 0
                    && System.nanoTime() >= backgroundDeadline) {
                break;
            }
            if (!backgroundWork.isEmpty()) {
                relaxCell(backgroundWork.poll(), backgroundWork, counters);
                backgroundSteps++;
                continue;
            }
            BoundaryJob job = boundaryJobs.peekFirst();
            if (job == null) {
                break;
            }
            if (job.cursor == 0) {
                Chunk chunk = chunkManager.getChunk(job.position.x(), job.position.y(), job.position.z());
                if (chunk != null) {
                    ensureInitialized(chunk);
                }
            }
            seedNextBoundaryCell(job);
            backgroundSteps++;
            seeded++;
            if (job.cursor >= BOUNDARY_CELL_COUNT) {
                boundaryJobs.removeFirst();
                queuedBoundaryChunks.remove(job.position);
            }
        }

        Map<ChunkPosition, Integer> backgroundChanged = snapshotDirtyChunks();
        marked += chunkManager.markChunksLightUpdated(backgroundChanged, false);
        changedPositions.addAll(backgroundChanged.keySet());
        dirtyChunkCount += backgroundChanged.size();
        long elapsed = System.nanoTime() - start;
        WorldLightingProfilingSnapshot profiling = new WorldLightingProfilingSnapshot(
                0L, 0L, Math.max(0L, elapsed - counters.propagationCpuTimeNs), counters.propagationCpuTimeNs,
                priorityChanges.size() + boundaryJobs.size(), boundaryJobs.size(), chunkManager.getChunkCount(),
                dirtyChunkCount, 0, counters.emitterSources, seeded, counters.processed, counters.writes,
                counters.blocked, counters.missing, counters.noGain);
        return new WorldLightingUpdateResult(profiling, Set.copyOf(changedPositions), marked);
    }

    /** Compatibility/reference entry point retained for focused lighting tests. */
    public WorldLightingUpdateResult rebuildLightingAround(ChunkManager chunkManager, Set<ChunkPosition> affectedPositions) {
        Objects.requireNonNull(chunkManager, "chunkManager");
        Objects.requireNonNull(affectedPositions, "affectedPositions");
        if (affectedPositions.isEmpty()) {
            return WorldLightingUpdateResult.empty();
        }
        dirtyChunks.clear();
        chunkCache.reset(chunkManager);
        List<ChunkPosition> loaded = chunkManager.snapshotLoadedChunkPositions();
        for (ChunkPosition position : loaded) {
            Chunk chunk = chunkManager.getChunk(position.x(), position.y(), position.z());
            if (chunk == null) {
                continue;
            }
            int[] before = chunk.getLighting().packToIntArray();
            initializer.initialize(chunk);
            if (!Arrays.equals(before, chunk.getLighting().packToIntArray())) {
                dirtyChunks.merge(packPosition(position.x(), position.y(), position.z()),
                        ChunkManager.LIGHT_BOUNDARY_ALL);
            }
            enqueueChunkBoundary(position);
        }
        ProcessingCounters counters = new ProcessingCounters();
        while (!boundaryJobs.isEmpty() || !backgroundWork.isEmpty()) {
            if (!backgroundWork.isEmpty()) {
                relaxCell(backgroundWork.poll(), backgroundWork, counters);
                continue;
            }
            BoundaryJob job = boundaryJobs.peekFirst();
            seedNextBoundaryCell(job);
            if (job.cursor >= BOUNDARY_CELL_COUNT) {
                boundaryJobs.removeFirst();
                queuedBoundaryChunks.remove(job.position);
            }
        }
        Map<ChunkPosition, Integer> changedChunks = snapshotDirtyChunks();
        Set<ChunkPosition> changedPositions = Set.copyOf(changedChunks.keySet());
        int marked = chunkManager.markChunksLightUpdated(changedChunks, false);
        WorldLightingProfilingSnapshot profiling = new WorldLightingProfilingSnapshot(
                0L, 0L, 0L, counters.propagationCpuTimeNs, affectedPositions.size(), loaded.size(),
                loaded.size(), dirtyChunks.size(), 0, counters.emitterSources, 0, counters.processed,
                counters.writes, counters.blocked, counters.missing, counters.noGain);
        return new WorldLightingUpdateResult(profiling, changedPositions, marked);
    }

    private int recomputeDirectSkyColumn(int worldX, int worldZ, LongWorkQueue targetQueue) {
        int minWorldY = heightRange.minChunkY() * Chunk.SIZE;
        int maxWorldY = (heightRange.maxChunkY() + 1) * Chunk.SIZE - 1;
        int scanStart = Math.min(maxWorldY, blockProvider.getSkyLightScanStartY(worldX, worldZ, maxWorldY));
        int sky = SKY_LIGHT_MAX_LEVEL;
        int changed = 0;
        for (int worldY = maxWorldY; worldY >= minWorldY; worldY--) {
            if (worldY <= scanStart) {
                sky = transmitDirectSky(sky, definition(blockProvider.getBlockAtWorld(worldX, worldY, worldZ)));
            }
            Chunk chunk = chunkCache.get(worldX, worldY, worldZ);
            if (chunk == null) {
                continue;
            }
            int localX = Math.floorMod(worldX, Chunk.SIZE);
            int localY = Math.floorMod(worldY, Chunk.SIZE);
            int localZ = Math.floorMod(worldZ, Chunk.SIZE);
            int previous = chunk.getDirectSkyLight().get(localX, localY, localZ);
            if (previous != sky) {
                chunk.getDirectSkyLight().set(localX, localY, localZ, sky);
                dirtyChunks.merge(packPosition(
                                Math.floorDiv(worldX, Chunk.SIZE),
                                Math.floorDiv(worldY, Chunk.SIZE),
                                Math.floorDiv(worldZ, Chunk.SIZE)),
                        boundaryMask(localX, localY, localZ));
                targetQueue.add(packPosition(worldX, worldY, worldZ));
                changed++;
            }
        }
        return changed;
    }

    private void seedNextBoundaryCell(BoundaryJob job) {
        int face = job.cursor / FACE_AREA;
        int offset = job.cursor % FACE_AREA;
        int a = offset % Chunk.SIZE;
        int b = offset / Chunk.SIZE;
        int localX;
        int localY;
        int localZ;
        int dx = 0;
        int dy = 0;
        int dz = 0;
        switch (face) {
            case 0 -> { localX = 0; localY = b; localZ = a; dx = -1; }
            case 1 -> { localX = Chunk.SIZE - 1; localY = b; localZ = a; dx = 1; }
            case 2 -> { localX = a; localY = 0; localZ = b; dy = -1; }
            case 3 -> { localX = a; localY = Chunk.SIZE - 1; localZ = b; dy = 1; }
            case 4 -> { localX = a; localY = b; localZ = 0; dz = -1; }
            default -> { localX = a; localY = b; localZ = Chunk.SIZE - 1; dz = 1; }
        }
        int x = job.position.x() * Chunk.SIZE + localX;
        int y = job.position.y() * Chunk.SIZE + localY;
        int z = job.position.z() * Chunk.SIZE + localZ;
        if (chunkCache.get(x, y, z) != null) {
            backgroundWork.add(packPosition(x, y, z));
        }
        if (chunkCache.get(x + dx, y + dy, z + dz) != null) {
            backgroundWork.add(packPosition(x + dx, y + dy, z + dz));
        }
        job.cursor++;
    }

    private void drainRelaxationQueue(ChunkManager manager, LongWorkQueue queue, int nodeBudget,
                                      long deadline, ProcessingCounters counters) {
        int processed = 0;
        while (!queue.isEmpty() && processed < nodeBudget) {
            if ((processed & (TIME_CHECK_INTERVAL - 1)) == 0 && System.nanoTime() >= deadline) {
                return;
            }
            relaxCell(queue.poll(), queue, counters);
            processed++;
        }
    }

    private void relaxCell(long packedPosition, LongWorkQueue targetQueue, ProcessingCounters counters) {
        long start = System.nanoTime();
        int worldX = unpackX(packedPosition);
        int worldY = unpackY(packedPosition);
        int worldZ = unpackZ(packedPosition);
        Chunk chunk = chunkCache.get(worldX, worldY, worldZ);
        if (chunk == null) {
            counters.missing++;
            counters.propagationCpuTimeNs += System.nanoTime() - start;
            return;
        }
        int localX = Math.floorMod(worldX, Chunk.SIZE);
        int localY = Math.floorMod(worldY, Chunk.SIZE);
        int localZ = Math.floorMod(worldZ, Chunk.SIZE);
        BlockDefinition block = definition(chunk.getBlock(localX, localY, localZ));
        int red = block == null ? 0 : block.getLightEmissionRed();
        int green = block == null ? 0 : block.getLightEmissionGreen();
        int blue = block == null ? 0 : block.getLightEmissionBlue();
        int sky = chunk.getDirectSkyLight().get(localX, localY, localZ);
        if (block != null && block.isLightEmitter()) {
            counters.emitterSources++;
        }
        if (block == null || !block.blocksLight()) {
            int loss = propagationLoss(block);
            for (int[] offset : OFFSETS) {
                int nx = worldX + offset[0];
                int ny = worldY + offset[1];
                int nz = worldZ + offset[2];
                Chunk neighbor = chunkCache.get(nx, ny, nz);
                if (neighbor == null) {
                    counters.missing++;
                    continue;
                }
                short neighborLight = neighbor.getPackedLight(Math.floorMod(nx, Chunk.SIZE),
                        Math.floorMod(ny, Chunk.SIZE), Math.floorMod(nz, Chunk.SIZE));
                red = Math.max(red, Math.max(0, ChunkLighting.getRed(neighborLight) - loss));
                green = Math.max(green, Math.max(0, ChunkLighting.getGreen(neighborLight) - loss));
                blue = Math.max(blue, Math.max(0, ChunkLighting.getBlue(neighborLight) - loss));
                sky = Math.max(sky, Math.max(0, ChunkLighting.getSky(neighborLight) - loss));
            }
        } else {
            counters.blocked++;
        }
        short current = chunk.getPackedLight(localX, localY, localZ);
        short desired = ChunkLighting.pack(red, green, blue, sky);
        counters.processed++;
        if (current == desired) {
            counters.noGain++;
            counters.propagationCpuTimeNs += System.nanoTime() - start;
            return;
        }
        chunk.setPackedLight(localX, localY, localZ, desired);
        dirtyChunks.merge(packPosition(Math.floorDiv(worldX, Chunk.SIZE),
                        Math.floorDiv(worldY, Chunk.SIZE), Math.floorDiv(worldZ, Chunk.SIZE)),
                boundaryMask(localX, localY, localZ));
        counters.writes++;
        for (int[] offset : OFFSETS) {
            int nx = worldX + offset[0];
            int ny = worldY + offset[1];
            int nz = worldZ + offset[2];
            if (ny >= heightRange.minChunkY() * Chunk.SIZE
                    && ny < (heightRange.maxChunkY() + 1) * Chunk.SIZE
                    && chunkCache.get(nx, ny, nz) != null) {
                targetQueue.add(packPosition(nx, ny, nz));
            }
        }
        counters.propagationCpuTimeNs += System.nanoTime() - start;
    }

    private void enqueueCellAndNeighbors(LongWorkQueue queue, int x, int y, int z) {
        queue.add(packPosition(x, y, z));
        for (int[] offset : OFFSETS) {
            int ny = y + offset[1];
            if (ny >= heightRange.minChunkY() * Chunk.SIZE
                    && ny < (heightRange.maxChunkY() + 1) * Chunk.SIZE) {
                queue.add(packPosition(x + offset[0], ny, z + offset[2]));
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

    /** Indirect skylight and RGB light lose at least one level for every traversed voxel. */
    static int propagationLoss(BlockDefinition destination) {
        return Math.max(1, destination == null ? 0 : destination.getLightAttenuation());
    }

    private static long packPosition(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) z & 0x3FFFFFFL) << 12 | ((long) y & 0xFFFL);
    }

    private static int unpackX(long packed) { return (int) (packed >> 38); }
    private static int unpackY(long packed) { return (int) (packed << 52 >> 52); }
    private static int unpackZ(long packed) { return (int) (packed << 26 >> 38); }

    private Map<ChunkPosition, Integer> snapshotDirtyChunks() {
        if (dirtyChunks.size() == 0) {
            return Map.of();
        }
        Map<ChunkPosition, Integer> positions = new LinkedHashMap<>(dirtyChunks.size());
        dirtyChunks.forEach((packed, mask) -> positions.put(new ChunkPosition(
                unpackX(packed), unpackY(packed), unpackZ(packed)), mask));
        return Map.copyOf(positions);
    }

    private static int boundaryMask(int localX, int localY, int localZ) {
        int mask = 0;
        if (localX == 0) mask |= ChunkManager.LIGHT_BOUNDARY_LOW_X;
        if (localX == Chunk.SIZE - 1) mask |= ChunkManager.LIGHT_BOUNDARY_HIGH_X;
        if (localY == 0) mask |= ChunkManager.LIGHT_BOUNDARY_LOW_Y;
        if (localY == Chunk.SIZE - 1) mask |= ChunkManager.LIGHT_BOUNDARY_HIGH_Y;
        if (localZ == 0) mask |= ChunkManager.LIGHT_BOUNDARY_LOW_Z;
        if (localZ == Chunk.SIZE - 1) mask |= ChunkManager.LIGHT_BOUNDARY_HIGH_Z;
        return mask;
    }

    public record WorldLightingUpdateResult(WorldLightingProfilingSnapshot profilingSnapshot,
                                             Set<ChunkPosition> changedPositions,
                                             int markedChunkCount) {
        private static WorldLightingUpdateResult empty() {
            return new WorldLightingUpdateResult(WorldLightingProfilingSnapshot.empty(), Set.of(), 0);
        }
    }

    private static final class BoundaryJob {
        private final ChunkPosition position;
        private int cursor;
        private BoundaryJob(ChunkPosition position) { this.position = position; }
    }

    private static final class ProcessingCounters {
        private long propagationCpuTimeNs;
        private int processed;
        private int writes;
        private int blocked;
        private int missing;
        private int noGain;
        private int emitterSources;
    }

    private static final class ChunkLookupCache {
        private static final int SIZE = 64;
        private final long[] keys = new long[SIZE];
        private final Chunk[] values = new Chunk[SIZE];
        private final boolean[] occupied = new boolean[SIZE];
        private ChunkManager manager;
        private void reset(ChunkManager manager) {
            this.manager = manager;
            Arrays.fill(occupied, false);
        }
        private Chunk get(int worldX, int worldY, int worldZ) {
            int chunkX = Math.floorDiv(worldX, Chunk.SIZE);
            int chunkY = Math.floorDiv(worldY, Chunk.SIZE);
            int chunkZ = Math.floorDiv(worldZ, Chunk.SIZE);
            long key = packPosition(chunkX, chunkY, chunkZ);
            int slot = mix(key) & (SIZE - 1);
            if (!occupied[slot] || keys[slot] != key) {
                occupied[slot] = true;
                keys[slot] = key;
                values[slot] = manager.getChunk(chunkX, chunkY, chunkZ);
            }
            return values[slot];
        }
    }

    private static final class LongWorkQueue {
        private long[] values;
        private int read;
        private int size;
        private final LongSet queued;
        private LongWorkQueue(int initialCapacity) {
            int capacity = 1;
            while (capacity < initialCapacity) capacity <<= 1;
            values = new long[capacity];
            queued = new LongSet(capacity * 2);
        }
        private boolean add(long value) {
            if (!queued.add(value)) return false;
            ensureCapacity(size + 1);
            values[(read + size) & (values.length - 1)] = value;
            size++;
            return true;
        }
        private long poll() {
            if (size == 0) throw new IllegalStateException("Empty lighting queue");
            long value = values[read];
            read = (read + 1) & (values.length - 1);
            size--;
            queued.remove(value);
            return value;
        }
        private int size() { return size; }
        private boolean isEmpty() { return size == 0; }
        private void clear() { read = 0; size = 0; queued.clear(); }
        private void ensureCapacity(int required) {
            if (required <= values.length) return;
            long[] grown = new long[values.length << 1];
            for (int index = 0; index < size; index++) {
                grown[index] = values[(read + index) & (values.length - 1)];
            }
            values = grown;
            read = 0;
        }
    }

    @FunctionalInterface
    private interface LongIntConsumer {
        void accept(long key, int value);
    }

    /** Primitive map used on the hot lighting path to avoid one allocation per light write. */
    private static final class LongIntMap {
        private long[] keys;
        private int[] values;
        private byte[] states;
        private int size;
        private int used;

        private LongIntMap(int requestedCapacity) {
            int capacity = 16;
            while (capacity < requestedCapacity) capacity <<= 1;
            keys = new long[capacity];
            values = new int[capacity];
            states = new byte[capacity];
        }

        private void merge(long key, int value) {
            if ((used + 1) * 10 >= keys.length * 7) rehash(keys.length << 1);
            int mask = keys.length - 1;
            int slot = mix(key) & mask;
            while (states[slot] != 0) {
                if (states[slot] == 1 && keys[slot] == key) {
                    values[slot] |= value;
                    return;
                }
                slot = (slot + 1) & mask;
            }
            states[slot] = 1;
            keys[slot] = key;
            values[slot] = value;
            size++;
            used++;
        }

        private void clear() {
            Arrays.fill(states, (byte) 0);
            size = 0;
            used = 0;
        }

        private int size() {
            return size;
        }

        private void forEach(LongIntConsumer consumer) {
            for (int index = 0; index < keys.length; index++) {
                if (states[index] == 1) consumer.accept(keys[index], values[index]);
            }
        }

        private void rehash(int capacity) {
            long[] oldKeys = keys;
            int[] oldValues = values;
            byte[] oldStates = states;
            keys = new long[capacity];
            values = new int[capacity];
            states = new byte[capacity];
            size = 0;
            used = 0;
            for (int index = 0; index < oldKeys.length; index++) {
                if (oldStates[index] == 1) merge(oldKeys[index], oldValues[index]);
            }
        }
    }

    private static final class LongSet {
        private long[] keys;
        private byte[] states;
        private int size;
        private int used;
        private LongSet(int requestedCapacity) {
            int capacity = 16;
            while (capacity < requestedCapacity) capacity <<= 1;
            keys = new long[capacity];
            states = new byte[capacity];
        }
        private boolean add(long key) {
            if ((used + 1) * 10 >= keys.length * 7) rehash(keys.length << 1);
            int mask = keys.length - 1;
            int slot = mix(key) & mask;
            int deleted = -1;
            while (states[slot] != 0) {
                if (states[slot] == 1 && keys[slot] == key) return false;
                if (states[slot] == 2 && deleted < 0) deleted = slot;
                slot = (slot + 1) & mask;
            }
            if (deleted >= 0) slot = deleted; else used++;
            states[slot] = 1;
            keys[slot] = key;
            size++;
            return true;
        }
        private void remove(long key) {
            int mask = keys.length - 1;
            int slot = mix(key) & mask;
            while (states[slot] != 0) {
                if (states[slot] == 1 && keys[slot] == key) {
                    states[slot] = 2;
                    size--;
                    if (size == 0) clear();
                    return;
                }
                slot = (slot + 1) & mask;
            }
        }
        private void clear() { Arrays.fill(states, (byte) 0); size = 0; used = 0; }
        private int size() { return size; }
        private void forEach(LongConsumer consumer) {
            for (int index = 0; index < keys.length; index++) {
                if (states[index] == 1) consumer.accept(keys[index]);
            }
        }
        private void rehash(int capacity) {
            long[] oldKeys = keys;
            byte[] oldStates = states;
            keys = new long[capacity];
            states = new byte[capacity];
            size = 0;
            used = 0;
            for (int i = 0; i < oldKeys.length; i++) if (oldStates[i] == 1) add(oldKeys[i]);
        }
    }

    private static int mix(long value) {
        value ^= value >>> 33;
        value *= 0xff51afd7ed558ccdl;
        value ^= value >>> 33;
        return (int) value;
    }
}
