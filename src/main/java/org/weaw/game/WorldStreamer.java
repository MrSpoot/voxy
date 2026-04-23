package org.weaw.game;

import org.joml.Vector3f;
import org.joml.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.generation.GenerationConfig;
import org.weaw.game.generation.NoiseWorldGenerator;
import org.weaw.game.generation.WorldGenerator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class WorldStreamer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldStreamer.class);
    private static final long DEFAULT_UPDATE_BUDGET_NS = 2_000_000L;
    private static final int DEFAULT_RESERVED_REMESH_TASK_SLOTS = 2;

    private final ChunkManager chunkManager;
    private final WorldBlockProvider blockProvider;
    private final WorldGenerator worldGenerator;
    private final WorldSettings settings;
    private final ExecutorService executor;
    private final int horizontalUnloadPadding;
    private final int verticalRenderHeight;
    private final int verticalUnloadHeight;
    private final int maxSubmissionsPerUpdate;
    private final int maxPublishesPerUpdate;
    private final int maxQueuedChunkCount;
    private final int reservedRemeshTaskSlots;
    private final long maxUpdateBudgetNs;
    private final Queue<CompletedChunk> completedChunks = new ConcurrentLinkedQueue<>();
    private final Object taskLock = new Object();
    private final Map<ChunkPosition, ChunkBuildTask> activeChunkTasks = new LinkedHashMap<>();
    private final Set<ChunkPosition> priorityDirtyChunkPositions = new LinkedHashSet<>();
    private final Set<ChunkPosition> dirtyChunkPositions = new LinkedHashSet<>();
    private final Set<ChunkPosition> desiredChunkPositions = new HashSet<>();
    private volatile boolean remeshEnabled = !Boolean.getBoolean("voxy.disableRemesh");
    private volatile boolean unloadsEnabled = !Boolean.getBoolean("voxy.disableUnloads");
    private final AtomicLong asyncChunkGenerationCpuTimeNs = new AtomicLong();
    private final AtomicLong asyncChunkMeshCpuTimeNs = new AtomicLong();
    private final AtomicInteger asyncChunksGenerated = new AtomicInteger();
    private final AtomicInteger asyncChunksMeshed = new AtomicInteger();
    private final AtomicInteger asyncChunksRemeshed = new AtomicInteger();

    private int activeHorizontalRenderRadius = -1;
    private int activeHorizontalUnloadRadius = -1;
    private List<ChunkOffset> sortedDesiredOffsets = List.of();
    private ChunkPosition cachedPlayerChunk;
    private List<ChunkPosition> cachedDesiredPositions = List.of();
    private List<ChunkPosition> pendingUnloadPositions = List.of();
    private ChunkPosition pendingUnloadPlayerChunk;
    private int desiredSubmissionCursor;
    private int unloadCursor;
    private long nextBuildToken = 1L;
    private volatile WorldStreamerProfilingSnapshot lastProfilingSnapshot = WorldStreamerProfilingSnapshot.empty();

    public WorldStreamer(ChunkManager chunkManager, WorldBlockProvider blockProvider) {
        this(
                chunkManager,
                blockProvider,
                new NoiseWorldGenerator(GenerationConfig.defaults()),
                new WorldSettings()
        );
    }

    public WorldStreamer(ChunkManager chunkManager, WorldBlockProvider blockProvider, WorldGenerator worldGenerator) {
        this(chunkManager, blockProvider, worldGenerator, new WorldSettings());
    }

    public WorldStreamer(
            ChunkManager chunkManager,
            WorldBlockProvider blockProvider,
            WorldGenerator worldGenerator,
            WorldSettings settings
    ) {
        this(
                chunkManager,
                blockProvider,
                worldGenerator,
                settings,
                20,
                24,
                2,
                12,
                4,
                resolveUpdateBudgetNs()
        );
    }

    public WorldStreamer(
            ChunkManager chunkManager,
            WorldBlockProvider blockProvider,
            WorldGenerator worldGenerator,
            int horizontalRenderRadius,
            int verticalRenderHeight,
            int horizontalUnloadRadius,
            int verticalUnloadHeight,
            int maxSubmissionsPerUpdate
    ) {
        this(
                chunkManager,
                blockProvider,
                worldGenerator,
                new WorldSettings(horizontalRenderRadius),
                verticalRenderHeight,
                verticalUnloadHeight,
                Math.max(2, horizontalUnloadRadius - horizontalRenderRadius),
                maxSubmissionsPerUpdate,
                Math.max(1, maxSubmissionsPerUpdate / 3),
                resolveUpdateBudgetNs()
        );
    }

    public WorldStreamer(
            ChunkManager chunkManager,
            WorldBlockProvider blockProvider,
            WorldGenerator worldGenerator,
            WorldSettings settings,
            int verticalRenderHeight,
            int verticalUnloadHeight,
            int horizontalUnloadPadding,
            int maxSubmissionsPerUpdate,
            int maxPublishesPerUpdate,
            long maxUpdateBudgetNs
    ) {
        this.chunkManager = chunkManager;
        this.blockProvider = Objects.requireNonNull(blockProvider, "blockProvider");
        this.worldGenerator = Objects.requireNonNull(worldGenerator, "worldGenerator");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.verticalRenderHeight = verticalRenderHeight;
        this.verticalUnloadHeight = Math.max(verticalUnloadHeight, verticalRenderHeight + 4);
        this.horizontalUnloadPadding = Math.max(2, horizontalUnloadPadding);
        this.maxSubmissionsPerUpdate = Math.max(1, maxSubmissionsPerUpdate);
        this.maxPublishesPerUpdate = Math.max(1, maxPublishesPerUpdate);
        this.maxUpdateBudgetNs = Math.max(250_000L, maxUpdateBudgetNs);
        int workerCount = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        this.maxQueuedChunkCount = Math.max(workerCount * 4, this.maxSubmissionsPerUpdate * 2);
        this.reservedRemeshTaskSlots = Math.max(
                1,
                Math.min(
                        this.maxQueuedChunkCount - 1,
                        Integer.getInteger("voxy.reservedRemeshTaskSlots", DEFAULT_RESERVED_REMESH_TASK_SLOTS)
                )
        );
        this.executor = Executors.newFixedThreadPool(workerCount);
    }

    public void update(Vector3f playerPosition) {
        long deadlineNs = System.nanoTime() + maxUpdateBudgetNs;
        FrameProfilingAccumulator frameProfiling = new FrameProfilingAccumulator();
        ChunkPosition playerChunk = toChunkPosition(playerPosition);
        int requestedRenderRadius = settings.getRenderDistanceChunks();
        boolean renderRadiusChanged = requestedRenderRadius != activeHorizontalRenderRadius;

        if (renderRadiusChanged) {
            activeHorizontalRenderRadius = requestedRenderRadius;
            activeHorizontalUnloadRadius = requestedRenderRadius + horizontalUnloadPadding;
            sortedDesiredOffsets = createSortedDesiredOffsets(activeHorizontalRenderRadius);
        }

        if (renderRadiusChanged || !playerChunk.equals(cachedPlayerChunk)) {
            cachedDesiredPositions = translateDesiredOffsets(playerChunk);
            synchronized (taskLock) {
                desiredChunkPositions.clear();
                desiredChunkPositions.addAll(cachedDesiredPositions);
            }
            cachedPlayerChunk = playerChunk;
            desiredSubmissionCursor = 0;
            pendingUnloadPlayerChunk = playerChunk;
            pendingUnloadPositions = chunkManager.snapshotLoadedChunkPositions();
            unloadCursor = 0;
            cancelObsoleteLoadTasks();
        }

        publishCompletedChunks(deadlineNs, frameProfiling);
        submitDirtyChunks(deadlineNs);
        submitNeededChunks(deadlineNs);
        processPendingUnloads(deadlineNs, frameProfiling);

        lastProfilingSnapshot = new WorldStreamerProfilingSnapshot(
                asyncChunkGenerationCpuTimeNs.getAndSet(0L),
                asyncChunkMeshCpuTimeNs.getAndSet(0L),
                frameProfiling.chunkPublishCpuTimeNs,
                frameProfiling.chunkUnloadCpuTimeNs,
                chunkManager.getChunkCount(),
                getPendingTaskCount(),
                getPendingRemeshCount(),
                completedChunks.size(),
                Math.max(0, pendingUnloadPositions.size() - unloadCursor),
                frameProfiling.chunksPublished,
                frameProfiling.chunksUnloaded,
                asyncChunksGenerated.getAndSet(0),
                asyncChunksMeshed.getAndSet(0),
                asyncChunksRemeshed.getAndSet(0)
        );
    }

    @Override
    public void close() {
        synchronized (taskLock) {
            activeChunkTasks.clear();
            priorityDirtyChunkPositions.clear();
            dirtyChunkPositions.clear();
            desiredChunkPositions.clear();
        }
        executor.shutdownNow();
    }

    public int getPendingTaskCount() {
        synchronized (taskLock) {
            return activeChunkTasks.size();
        }
    }

    public WorldStreamerProfilingSnapshot getLastProfilingSnapshot() {
        return lastProfilingSnapshot;
    }

    public void markChunkDirty(ChunkPosition position) {
        if (!remeshEnabled) {
            return;
        }
        synchronized (taskLock) {
            if (priorityDirtyChunkPositions.contains(position)) {
                return;
            }
            dirtyChunkPositions.add(position);
        }
    }

    public void markChunkDirtyPriority(ChunkPosition position) {
        if (!remeshEnabled) {
            return;
        }
        synchronized (taskLock) {
            dirtyChunkPositions.remove(position);
            priorityDirtyChunkPositions.add(position);
        }
    }

    public void setRemeshEnabled(boolean remeshEnabled) {
        this.remeshEnabled = remeshEnabled;
        if (!remeshEnabled) {
            synchronized (taskLock) {
                priorityDirtyChunkPositions.clear();
                dirtyChunkPositions.clear();
            }
        }
    }

    public void setUnloadsEnabled(boolean unloadsEnabled) {
        this.unloadsEnabled = unloadsEnabled;
    }

    private void processPendingUnloads(long deadlineNs, FrameProfilingAccumulator frameProfiling) {
        long startNs = System.nanoTime();
        if (!unloadsEnabled) {
            frameProfiling.chunkUnloadCpuTimeNs += System.nanoTime() - startNs;
            return;
        }
        if (pendingUnloadPositions.isEmpty() || pendingUnloadPlayerChunk == null) {
            frameProfiling.chunkUnloadCpuTimeNs += System.nanoTime() - startNs;
            return;
        }

        int minUnloadY = getMinChunkY(pendingUnloadPlayerChunk.y(), verticalUnloadHeight);
        int maxUnloadY = getMaxChunkY(pendingUnloadPlayerChunk.y(), verticalUnloadHeight);
        int unloadRadiusSquared = activeHorizontalUnloadRadius * activeHorizontalUnloadRadius;

        while (unloadCursor < pendingUnloadPositions.size()) {
            ChunkPosition position = pendingUnloadPositions.get(unloadCursor++);
            int dx = position.x() - pendingUnloadPlayerChunk.x();
            int dz = position.z() - pendingUnloadPlayerChunk.z();

            boolean outsideCylinder = (dx * dx + dz * dz) > unloadRadiusSquared
                    || position.y() < minUnloadY
                    || position.y() > maxUnloadY;

            if (outsideCylinder) {
                cancelTask(position);
                removeDirtyChunk(position);
                chunkManager.unloadChunk(position);
                frameProfiling.chunksUnloaded++;
            }

            if (System.nanoTime() >= deadlineNs) {
                frameProfiling.chunkUnloadCpuTimeNs += System.nanoTime() - startNs;
                return;
            }
        }

        if (unloadCursor >= pendingUnloadPositions.size()) {
            pendingUnloadPositions = List.of();
            pendingUnloadPlayerChunk = null;
            unloadCursor = 0;
        }
        frameProfiling.chunkUnloadCpuTimeNs += System.nanoTime() - startNs;
    }

    private void submitNeededChunks(long deadlineNs) {
        if (cachedDesiredPositions.isEmpty() || System.nanoTime() >= deadlineNs) {
            return;
        }

        int remeshReservation = getPendingRemeshCount() > 0 ? reservedRemeshTaskSlots : 0;
        int availableQueueSlots = maxQueuedChunkCount - getPendingTaskCount() - remeshReservation;
        if (availableQueueSlots <= 0) {
            return;
        }

        int submissionBudget = Math.min(maxSubmissionsPerUpdate, availableQueueSlots);
        int submitted = 0;
        int scanned = 0;

        while (submitted < submissionBudget && scanned < cachedDesiredPositions.size()) {
            ChunkPosition position = cachedDesiredPositions.get(desiredSubmissionCursor);
            desiredSubmissionCursor = (desiredSubmissionCursor + 1) % cachedDesiredPositions.size();
            scanned++;

            if (hasActiveTask(position) || chunkManager.hasChunk(position)) {
                if (System.nanoTime() >= deadlineNs) {
                    return;
                }
                continue;
            }

            if (!chunkManager.tryMarkChunkQueued(position)) {
                if (System.nanoTime() >= deadlineNs) {
                    return;
                }
                continue;
            }

            ChunkBuildTask task = registerTask(position, ChunkTaskType.LOAD);
            if (task == null) {
                chunkManager.clearQueuedChunk(position);
                if (System.nanoTime() >= deadlineNs) {
                    return;
                }
                continue;
            }

            submitted++;
            executor.submit(() -> buildChunk(task, null));

            if (System.nanoTime() >= deadlineNs) {
                return;
            }
        }
    }

    private void submitDirtyChunks(long deadlineNs) {
        if (!remeshEnabled) {
            return;
        }
        if (System.nanoTime() >= deadlineNs) {
            return;
        }

        int availableQueueSlots = maxQueuedChunkCount - getPendingTaskCount();
        if (availableQueueSlots <= 0) {
            return;
        }

        int submissionBudget = Math.min(maxSubmissionsPerUpdate, availableQueueSlots);
        int submitted = 0;
        List<ChunkPosition> dirtySnapshot = snapshotDirtyChunks();

        for (ChunkPosition position : dirtySnapshot) {
            if (submitted >= submissionBudget || System.nanoTime() >= deadlineNs) {
                return;
            }
            if (hasActiveTask(position)) {
                continue;
            }

            Chunk chunkSnapshot = chunkManager.copyChunk(position);
            if (chunkSnapshot == null) {
                removeDirtyChunk(position);
                continue;
            }

            ChunkBuildTask task = registerTask(position, ChunkTaskType.REMESH);
            if (task == null) {
                continue;
            }

            removeDirtyChunk(position);
            submitted++;
            executor.submit(() -> buildChunk(task, chunkSnapshot));
        }
    }

    private void buildChunk(ChunkBuildTask task, Chunk chunkSnapshot) {
        if (!transitionTaskState(task.position(), task.token(), ChunkTaskState.QUEUED, ChunkTaskState.BUILDING)) {
            return;
        }

        try {
            Chunk chunk = chunkSnapshot;
            if (task.type() == ChunkTaskType.LOAD) {
                chunk = new Chunk(new Vector3i(task.position().x(), task.position().y(), task.position().z()));
                long generationStartNs = System.nanoTime();
                worldGenerator.generateChunkData(chunk);
                asyncChunkGenerationCpuTimeNs.addAndGet(System.nanoTime() - generationStartNs);
                asyncChunksGenerated.incrementAndGet();
            }

            long meshStartNs = System.nanoTime();
            ChunkMeshData meshData = ChunkMesher.buildMeshData(chunk, blockProvider);
            asyncChunkMeshCpuTimeNs.addAndGet(System.nanoTime() - meshStartNs);
            asyncChunksMeshed.incrementAndGet();
            if (task.type() == ChunkTaskType.REMESH) {
                asyncChunksRemeshed.incrementAndGet();
            }
            completedChunks.offer(new CompletedChunk(task.position(), task.token(), task.type(), chunk, meshData));
        } catch (Exception exception) {
            LOGGER.error("Chunk build failed for {} ({})", task.position(), task.type(), exception);
            failTask(task);
        }
    }

    private void publishCompletedChunks(long deadlineNs, FrameProfilingAccumulator frameProfiling) {
        long startNs = System.nanoTime();
        for (int published = 0; published < maxPublishesPerUpdate; published++) {
            if (System.nanoTime() >= deadlineNs) {
                frameProfiling.chunkPublishCpuTimeNs += System.nanoTime() - startNs;
                return;
            }

            CompletedChunk completedChunk = completedChunks.poll();
            if (completedChunk == null) {
                frameProfiling.chunkPublishCpuTimeNs += System.nanoTime() - startNs;
                return;
            }

            ChunkBuildTask task = getActiveTask(completedChunk.position());
            if (task == null || task.token() != completedChunk.token() || task.type() != completedChunk.type()) {
                continue;
            }

            if (task.type() == ChunkTaskType.LOAD && !isDesiredChunkPosition(task.position())) {
                cancelTask(task.position());
                continue;
            }

            if (!transitionTaskToReady(task.position(), task.token())) {
                continue;
            }

            boolean publishedChunk = true;
            if (task.type() == ChunkTaskType.LOAD) {
                chunkManager.publishBuiltChunk(completedChunk.chunk(), completedChunk.meshData());
            } else {
                publishedChunk = chunkManager.publishRemeshedChunk(completedChunk.position(), completedChunk.meshData());
            }

            completePublishedTask(task.position(), task.token(), publishedChunk);
            if (publishedChunk) {
                frameProfiling.chunksPublished++;
            }
        }
        frameProfiling.chunkPublishCpuTimeNs += System.nanoTime() - startNs;
    }

    private void cancelObsoleteLoadTasks() {
        List<ChunkPosition> obsoletePositions = new ArrayList<>();
        synchronized (taskLock) {
            for (ChunkBuildTask task : activeChunkTasks.values()) {
                if (task.type() == ChunkTaskType.LOAD && !desiredChunkPositions.contains(task.position())) {
                    obsoletePositions.add(task.position());
                }
            }
        }

        for (ChunkPosition position : obsoletePositions) {
            cancelTask(position);
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

    private static long resolveUpdateBudgetNs() {
        Long configuredBudgetMs = Long.getLong("voxy.worldUpdateBudgetMs");
        if (configuredBudgetMs != null) {
            return configuredBudgetMs * 1_000_000L;
        }
        return Long.getLong("voxy.worldUpdateBudgetNs", DEFAULT_UPDATE_BUDGET_NS);
    }

    private ChunkBuildTask registerTask(ChunkPosition position, ChunkTaskType type) {
        synchronized (taskLock) {
            if (activeChunkTasks.containsKey(position)) {
                return null;
            }

            ChunkBuildTask task = new ChunkBuildTask(position, nextBuildToken++, type, ChunkTaskState.QUEUED);
            activeChunkTasks.put(position, task);
            return task;
        }
    }

    private boolean hasActiveTask(ChunkPosition position) {
        synchronized (taskLock) {
            return activeChunkTasks.containsKey(position);
        }
    }

    private ChunkBuildTask getActiveTask(ChunkPosition position) {
        synchronized (taskLock) {
            return activeChunkTasks.get(position);
        }
    }

    private void cancelTask(ChunkPosition position) {
        ChunkBuildTask task;
        synchronized (taskLock) {
            task = activeChunkTasks.remove(position);
            if (task == null) {
                return;
            }
            task.setState(ChunkTaskState.OBSOLETE);
        }

        if (task.type() == ChunkTaskType.LOAD) {
            chunkManager.clearQueuedChunk(position);
        }
    }

    private void failTask(ChunkBuildTask task) {
        boolean removed;
        synchronized (taskLock) {
            ChunkBuildTask currentTask = activeChunkTasks.get(task.position());
            removed = currentTask != null && currentTask.token() == task.token();
            if (removed) {
                currentTask.setState(ChunkTaskState.OBSOLETE);
                activeChunkTasks.remove(task.position());
            }
        }

        if (removed && task.type() == ChunkTaskType.LOAD) {
            chunkManager.clearQueuedChunk(task.position());
        }
        if (removed && task.type() == ChunkTaskType.REMESH) {
            markChunkDirty(task.position());
        }
    }

    private boolean transitionTaskState(
            ChunkPosition position,
            long token,
            ChunkTaskState expectedState,
            ChunkTaskState nextState
    ) {
        synchronized (taskLock) {
            ChunkBuildTask task = activeChunkTasks.get(position);
            if (task == null || task.token() != token || task.state() != expectedState) {
                return false;
            }
            task.setState(nextState);
            return true;
        }
    }

    private boolean transitionTaskToReady(ChunkPosition position, long token) {
        synchronized (taskLock) {
            ChunkBuildTask task = activeChunkTasks.get(position);
            if (task == null || task.token() != token) {
                return false;
            }
            if (task.state() != ChunkTaskState.BUILDING && task.state() != ChunkTaskState.QUEUED) {
                return false;
            }
            task.setState(ChunkTaskState.READY);
            return true;
        }
    }

    private void completePublishedTask(ChunkPosition position, long token, boolean publishedChunk) {
        synchronized (taskLock) {
            ChunkBuildTask task = activeChunkTasks.get(position);
            if (task == null || task.token() != token) {
                return;
            }

            task.setState(publishedChunk ? ChunkTaskState.PUBLISHED : ChunkTaskState.OBSOLETE);
            activeChunkTasks.remove(position);
        }

        if (!publishedChunk) {
            markChunkDirty(position);
        }
    }

    private boolean isDesiredChunkPosition(ChunkPosition position) {
        synchronized (taskLock) {
            return desiredChunkPositions.contains(position);
        }
    }

    private List<ChunkPosition> snapshotDirtyChunks() {
        synchronized (taskLock) {
            if (priorityDirtyChunkPositions.isEmpty()) {
                return List.copyOf(dirtyChunkPositions);
            }

            List<ChunkPosition> snapshot = new ArrayList<>(priorityDirtyChunkPositions.size() + dirtyChunkPositions.size());
            snapshot.addAll(priorityDirtyChunkPositions);
            snapshot.addAll(dirtyChunkPositions);
            return List.copyOf(snapshot);
        }
    }

    private void removeDirtyChunk(ChunkPosition position) {
        synchronized (taskLock) {
            priorityDirtyChunkPositions.remove(position);
            dirtyChunkPositions.remove(position);
        }
    }

    private int getPendingRemeshCount() {
        synchronized (taskLock) {
            return priorityDirtyChunkPositions.size() + dirtyChunkPositions.size();
        }
    }

    private static final class FrameProfilingAccumulator {
        private long chunkPublishCpuTimeNs;
        private long chunkUnloadCpuTimeNs;
        private int chunksPublished;
        private int chunksUnloaded;
    }

    private record ChunkOffset(int x, int y, int z) {
    }

    private record CompletedChunk(
            ChunkPosition position,
            long token,
            ChunkTaskType type,
            Chunk chunk,
            ChunkMeshData meshData
    ) {
    }

    private enum ChunkTaskType {
        LOAD,
        REMESH
    }

    private enum ChunkTaskState {
        QUEUED,
        BUILDING,
        READY,
        PUBLISHED,
        OBSOLETE
    }

    private static final class ChunkBuildTask {
        private final ChunkPosition position;
        private final long token;
        private final ChunkTaskType type;
        private ChunkTaskState state;

        private ChunkBuildTask(ChunkPosition position, long token, ChunkTaskType type, ChunkTaskState state) {
            this.position = position;
            this.token = token;
            this.type = type;
            this.state = state;
        }

        private ChunkPosition position() {
            return position;
        }

        private long token() {
            return token;
        }

        private ChunkTaskType type() {
            return type;
        }

        private ChunkTaskState state() {
            return state;
        }

        private void setState(ChunkTaskState state) {
            this.state = state;
        }
    }
}
