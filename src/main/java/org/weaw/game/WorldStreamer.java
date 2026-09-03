package org.weaw.game;

import org.joml.Vector3f;
import org.joml.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.generation.GenerationConfig;
import org.weaw.game.generation.ChunkClassificationCacheStats;
import org.weaw.game.generation.ChunkGenerationHint;
import org.weaw.game.generation.NoiseWorldGenerator;
import org.weaw.game.generation.WorldGenerator;
import org.weaw.game.utils.BlockCatalog;

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
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class WorldStreamer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldStreamer.class);
    private static final long DEFAULT_UPDATE_BUDGET_NS = 2_000_000L;
    private static final int DEFAULT_RESERVED_REMESH_TASK_SLOTS = 2;
    private static final long TASK_MEMORY_RESERVATION_BYTES = 2L * 1024L * 1024L;
    private static final int INTERACTION_BUBBLE_RADIUS_CHUNKS = 1;

    private final ChunkManager chunkManager;
    private final BlockCatalog blockCatalog;
    private final WorldBlockProvider blockProvider;
    private final WorldGenerator worldGenerator;
    private final WorldSettings settings;
    private final WorldHeightRange heightRange;
    private final WorldMemoryBudget memoryBudget;
    private final ExecutorService executor;
    private final int horizontalUnloadPadding;
    private final int maxSubmissionsPerUpdate;
    private final int maxPublishesPerUpdate;
    private final int maxQueuedChunkCount;
    private final int reservedRemeshTaskSlots;
    private final long maxUpdateBudgetNs;
    private final boolean sparseChunkStreamingEnabled;
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
    private final AtomicLong asyncMeshingSnapshotCpuTimeNs = new AtomicLong();
    private final AtomicLong asyncMeshingFaceClassificationCpuTimeNs = new AtomicLong();
    private final AtomicLong asyncMeshingGreedyMergeCpuTimeNs = new AtomicLong();
    private final AtomicLong asyncMeshingOutputBuildCpuTimeNs = new AtomicLong();
    private final AtomicInteger asyncMeshingAmbientOcclusionFaces = new AtomicInteger();
    private final AtomicInteger asyncMeshingSampledBlocks = new AtomicInteger();
    private final AtomicInteger asyncCancelledBuilds = new AtomicInteger();
    private final AtomicInteger asyncChunksGenerated = new AtomicInteger();
    private final AtomicInteger asyncChunksMeshed = new AtomicInteger();
    private final AtomicInteger asyncChunksRemeshed = new AtomicInteger();

    private int activeHorizontalRenderRadius = -1;
    private int requestedHorizontalRenderRadius = -1;
    private int memoryLimitedRenderRadius = WorldSettings.MAX_RENDER_DISTANCE_CHUNKS;
    private int activeHorizontalUnloadRadius = -1;
    private List<ChunkOffset> sortedDesiredOffsets = List.of();
    private ChunkPosition cachedPlayerChunk;
    private List<ChunkPosition> cachedDesiredPositions = List.of();
    private List<ChunkPosition> pendingUnloadPositions = List.of();
    private ChunkPosition pendingUnloadPlayerChunk;
    private int desiredSubmissionCursor;
    private int unloadCursor;
    private long nextBuildToken = 1L;
    private long reservedInFlightBytes;
    private int rejectedLoadCount;
    private int desiredMaterializedChunkCount;
    private int virtualEmptyChunkCount;
    private int virtualUniformChunkCount;
    private int interactionBubbleChunkCount;
    private WorldMemorySnapshot.PressureState memoryPressureState = WorldMemorySnapshot.PressureState.NORMAL;
    private volatile WorldStreamerProfilingSnapshot lastProfilingSnapshot = WorldStreamerProfilingSnapshot.empty();
    private volatile WorldMemorySnapshot lastMemorySnapshot;

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
        this(
                chunkManager,
                blockProvider,
                worldGenerator,
                settings,
                verticalRenderHeight,
                verticalUnloadHeight,
                horizontalUnloadPadding,
                maxSubmissionsPerUpdate,
                maxPublishesPerUpdate,
                maxUpdateBudgetNs,
                Math.max(1, Runtime.getRuntime().availableProcessors() - 1)
        );
    }

    private WorldStreamer(
            ChunkManager chunkManager,
            WorldBlockProvider blockProvider,
            WorldGenerator worldGenerator,
            WorldSettings settings,
            int verticalRenderHeight,
            int verticalUnloadHeight,
            int horizontalUnloadPadding,
            int maxSubmissionsPerUpdate,
            int maxPublishesPerUpdate,
            long maxUpdateBudgetNs,
            int workerCount
    ) {
        this(
                chunkManager,
                blockProvider,
                worldGenerator,
                settings,
                verticalRenderHeight,
                verticalUnloadHeight,
                horizontalUnloadPadding,
                maxSubmissionsPerUpdate,
                maxPublishesPerUpdate,
                maxUpdateBudgetNs,
                Executors.newFixedThreadPool(workerCount),
                workerCount
        );
    }

    WorldStreamer(
            ChunkManager chunkManager,
            WorldBlockProvider blockProvider,
            WorldGenerator worldGenerator,
            WorldSettings settings,
            int verticalRenderHeight,
            int verticalUnloadHeight,
            int horizontalUnloadPadding,
            int maxSubmissionsPerUpdate,
            int maxPublishesPerUpdate,
            long maxUpdateBudgetNs,
            ExecutorService executor,
            int workerCount
    ) {
        this.chunkManager = Objects.requireNonNull(chunkManager, "chunkManager");
        this.blockCatalog = chunkManager.getBlockCatalog();
        this.blockProvider = Objects.requireNonNull(blockProvider, "blockProvider");
        this.worldGenerator = Objects.requireNonNull(worldGenerator, "worldGenerator");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.heightRange = settings.getHeightRange();
        this.memoryBudget = settings.getMemoryBudget();
        this.horizontalUnloadPadding = Math.max(2, horizontalUnloadPadding);
        this.maxSubmissionsPerUpdate = Math.max(1, maxSubmissionsPerUpdate);
        this.maxPublishesPerUpdate = Math.max(1, maxPublishesPerUpdate);
        this.maxUpdateBudgetNs = Math.max(250_000L, maxUpdateBudgetNs);
        this.sparseChunkStreamingEnabled = settings.isSparseChunkStreamingEnabled();
        workerCount = Math.max(1, workerCount);
        int memoryLimitedQueueCount = Math.max(1, (int) (memoryBudget.maxInFlightBytes() / TASK_MEMORY_RESERVATION_BYTES));
        this.maxQueuedChunkCount = Math.min(
                memoryLimitedQueueCount,
                Math.max(workerCount * 4, this.maxSubmissionsPerUpdate * 2)
        );
        this.reservedRemeshTaskSlots = Math.max(
                1,
                Math.min(
                        this.maxQueuedChunkCount - 1,
                        Integer.getInteger("voxy.reservedRemeshTaskSlots", DEFAULT_RESERVED_REMESH_TASK_SLOTS)
                )
        );
        this.executor = Objects.requireNonNull(executor, "executor");
        this.lastMemorySnapshot = createMemorySnapshot();
    }

    public void update(Vector3f playerPosition) {
        long deadlineNs = System.nanoTime() + maxUpdateBudgetNs;
        FrameProfilingAccumulator frameProfiling = new FrameProfilingAccumulator();
        ChunkPosition playerChunk = toChunkPosition(playerPosition);
        int requestedRenderRadius = settings.getRenderDistanceChunks();
        if (requestedRenderRadius != requestedHorizontalRenderRadius) {
            requestedHorizontalRenderRadius = requestedRenderRadius;
            memoryLimitedRenderRadius = limitRadiusByChunkCount(requestedRenderRadius, memoryBudget.maxLoadedChunks());
        }

        updateMemoryPressure();
        if (memoryPressureState != WorldMemorySnapshot.PressureState.NORMAL) {
            reduceMemoryLimitedRadius(requestedRenderRadius);
        }

        int effectiveRenderRadius = Math.min(requestedRenderRadius, memoryLimitedRenderRadius);
        boolean renderRadiusChanged = effectiveRenderRadius != activeHorizontalRenderRadius;

        if (renderRadiusChanged) {
            activeHorizontalRenderRadius = effectiveRenderRadius;
            activeHorizontalUnloadRadius = effectiveRenderRadius + horizontalUnloadPadding;
            sortedDesiredOffsets = createSortedDesiredOffsets(activeHorizontalRenderRadius);
        }

        if (renderRadiusChanged || !playerChunk.equals(cachedPlayerChunk)) {
            if (sparseChunkStreamingEnabled) {
                worldGenerator.retainChunkClassificationsAround(
                        playerChunk.x(),
                        playerChunk.z(),
                        activeHorizontalUnloadRadius
                );
            }
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
                asyncMeshingSnapshotCpuTimeNs.getAndSet(0L),
                asyncMeshingFaceClassificationCpuTimeNs.getAndSet(0L),
                asyncMeshingGreedyMergeCpuTimeNs.getAndSet(0L),
                asyncMeshingOutputBuildCpuTimeNs.getAndSet(0L),
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
                asyncChunksRemeshed.getAndSet(0),
                asyncMeshingAmbientOcclusionFaces.getAndSet(0),
                asyncMeshingSampledBlocks.getAndSet(0),
                asyncCancelledBuilds.getAndSet(0)
        );
        lastMemorySnapshot = createMemorySnapshot();
    }

    @Override
    public void close() {
        synchronized (taskLock) {
            activeChunkTasks.clear();
            priorityDirtyChunkPositions.clear();
            dirtyChunkPositions.clear();
            desiredChunkPositions.clear();
            reservedInFlightBytes = 0L;
        }
        executor.shutdownNow();
    }

    public int getPendingTaskCount() {
        synchronized (taskLock) {
            return activeChunkTasks.size();
        }
    }

    public boolean isConverged() {
        List<ChunkPosition> desiredPositions;
        synchronized (taskLock) {
            if (!activeChunkTasks.isEmpty() || !completedChunks.isEmpty()) {
                return false;
            }
            desiredPositions = List.copyOf(desiredChunkPositions);
        }
        if (desiredPositions.isEmpty()) {
            return false;
        }
        for (ChunkPosition position : desiredPositions) {
            if (!chunkManager.hasChunk(position)) {
                return false;
            }
        }
        return true;
    }

    public WorldStreamerProfilingSnapshot getLastProfilingSnapshot() {
        return lastProfilingSnapshot;
    }

    public WorldMemorySnapshot getLastMemorySnapshot() {
        return lastMemorySnapshot;
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

    synchronized boolean materializeChunkForEdit(ChunkPosition position) {
        if (!heightRange.contains(position.y())) {
            return false;
        }
        if (chunkManager.hasChunk(position)) {
            return true;
        }

        cancelTask(position);
        try {
            Chunk chunk = new Chunk(new Vector3i(position.x(), position.y(), position.z()), blockCatalog);
            long generationStartNs = System.nanoTime();
            worldGenerator.generateChunkData(chunk);
            asyncChunkGenerationCpuTimeNs.addAndGet(System.nanoTime() - generationStartNs);
            asyncChunksGenerated.incrementAndGet();

            long projectedBytes = chunkManager.getEstimatedResidentBytes()
                    + ChunkManager.estimateResidentBytes(chunk, null);
            if (projectedBytes > memoryBudget.maxCpuResidentBytes()
                    || chunkManager.getChunkCount() >= memoryBudget.maxLoadedChunks()) {
                rejectedLoadCount++;
                return false;
            }

            chunkManager.addChunk(chunk);
            markChunkDirtyPriority(position);
            return true;
        } catch (RuntimeException exception) {
            LOGGER.error("Unable to materialize chunk {} for an edit", position, exception);
            return false;
        }
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

        int minUnloadY = heightRange.minChunkY();
        int maxUnloadY = heightRange.maxChunkY();
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
        if (!canSubmitMemoryTask()) {
            rejectedLoadCount++;
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
            if (!canSubmitMemoryTask()) {
                rejectedLoadCount++;
                return;
            }
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

            if (!canReserveTaskMemory()) {
                return;
            }

            Chunk chunkSnapshot = chunkManager.copyChunkForMeshing(position);
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
            throwIfTaskCancelled(task);
            Chunk chunk = chunkSnapshot;
            if (task.type() == ChunkTaskType.LOAD) {
                chunk = new Chunk(
                        new Vector3i(task.position().x(), task.position().y(), task.position().z()),
                        blockCatalog
                );
                long generationStartNs = System.nanoTime();
                try {
                    worldGenerator.generateChunkData(chunk);
                } finally {
                    asyncChunkGenerationCpuTimeNs.addAndGet(System.nanoTime() - generationStartNs);
                }
                asyncChunksGenerated.incrementAndGet();
                throwIfTaskCancelled(task);
            }

            long meshStartNs = System.nanoTime();
            ChunkMeshingResult meshingResult;
            try {
                meshingResult = ChunkMesher.buildMeshDataProfiled(
                        chunk,
                        blockProvider,
                        () -> isTaskCancelled(task)
                );
            } finally {
                asyncChunkMeshCpuTimeNs.addAndGet(System.nanoTime() - meshStartNs);
            }
            ChunkMeshingMetrics meshingMetrics = meshingResult.metrics();
            asyncMeshingSnapshotCpuTimeNs.addAndGet(meshingMetrics.snapshotCpuTimeNs());
            asyncMeshingFaceClassificationCpuTimeNs.addAndGet(meshingMetrics.faceClassificationCpuTimeNs());
            asyncMeshingGreedyMergeCpuTimeNs.addAndGet(meshingMetrics.greedyMergeCpuTimeNs());
            asyncMeshingOutputBuildCpuTimeNs.addAndGet(meshingMetrics.outputBuildCpuTimeNs());
            asyncMeshingAmbientOcclusionFaces.addAndGet(meshingMetrics.ambientOcclusionFaceCount());
            asyncMeshingSampledBlocks.addAndGet(meshingMetrics.sampledBlockCount());
            asyncChunksMeshed.incrementAndGet();
            if (task.type() == ChunkTaskType.REMESH) {
                asyncChunksRemeshed.incrementAndGet();
            }
            throwIfTaskCancelled(task);
            completedChunks.offer(new CompletedChunk(
                    task.position(),
                    task.token(),
                    task.type(),
                    chunk,
                    meshingResult.meshData()
            ));
        } catch (CancellationException exception) {
            if (failTask(task)) {
                asyncCancelledBuilds.incrementAndGet();
            }
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

            Chunk residentChunk = task.type() == ChunkTaskType.REMESH
                    ? chunkManager.getChunk(task.position().x(), task.position().y(), task.position().z())
                    : completedChunk.chunk();
            if (residentChunk == null) {
                cancelTask(task.position());
                continue;
            }
            long completedBytes = ChunkManager.estimateResidentBytes(residentChunk, completedChunk.meshData());
            long replacedBytes = task.type() == ChunkTaskType.REMESH
                    ? chunkManager.getEstimatedResidentBytes(completedChunk.position())
                    : 0L;
            long projectedBytes = chunkManager.getEstimatedResidentBytes() - replacedBytes + completedBytes;
            int projectedChunkCount = chunkManager.getChunkCount() + (task.type() == ChunkTaskType.LOAD ? 1 : 0);
            if (projectedBytes > memoryBudget.maxCpuResidentBytes()
                    || projectedChunkCount > memoryBudget.maxLoadedChunks()) {
                rejectedLoadCount++;
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
        int minRenderY = heightRange.minChunkY();
        int maxRenderY = heightRange.maxChunkY();
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
        int materializedCount = 0;
        int emptyCount = 0;
        int uniformCount = 0;
        int bubbleCount = 0;

        for (ChunkOffset offset : sortedDesiredOffsets) {
            ChunkPosition position = new ChunkPosition(
                    playerChunk.x() + offset.x(),
                    offset.y(),
                    playerChunk.z() + offset.z()
            );
            if (!sparseChunkStreamingEnabled) {
                positions.add(position);
                materializedCount++;
                continue;
            }

            boolean interactionBubble = isInInteractionBubble(position, playerChunk);
            ChunkGenerationHint hint = worldGenerator.classifyChunk(position);
            if (interactionBubble || hint.requiresMaterialization()) {
                positions.add(position);
                materializedCount++;
                if (interactionBubble) {
                    bubbleCount++;
                }
            } else if (hint.kind() == ChunkGenerationHint.Kind.EMPTY) {
                emptyCount++;
            } else {
                uniformCount++;
            }
        }

        positions.sort(
                Comparator.comparingInt((ChunkPosition position) -> isInInteractionBubble(position, playerChunk) ? 0 : 1)
                        .thenComparingInt(position -> horizontalDistanceFromPlayer(position, playerChunk))
                        .thenComparingInt(position -> Math.abs(position.y() - playerChunk.y()))
        );
        desiredMaterializedChunkCount = materializedCount;
        virtualEmptyChunkCount = emptyCount;
        virtualUniformChunkCount = uniformCount;
        interactionBubbleChunkCount = bubbleCount;
        return List.copyOf(positions);
    }

    private static boolean isInInteractionBubble(ChunkPosition position, ChunkPosition playerChunk) {
        return Math.abs(position.x() - playerChunk.x()) <= INTERACTION_BUBBLE_RADIUS_CHUNKS
                && Math.abs(position.y() - playerChunk.y()) <= INTERACTION_BUBBLE_RADIUS_CHUNKS
                && Math.abs(position.z() - playerChunk.z()) <= INTERACTION_BUBBLE_RADIUS_CHUNKS;
    }

    private static int horizontalDistanceFromPlayer(ChunkPosition position, ChunkPosition playerChunk) {
        int dx = position.x() - playerChunk.x();
        int dz = position.z() - playerChunk.z();
        return dx * dx + dz * dz;
    }

    private ChunkPosition toChunkPosition(Vector3f worldPosition) {
        return new ChunkPosition(
                Math.floorDiv((int) Math.floor(worldPosition.x), Chunk.SIZE),
                Math.floorDiv((int) Math.floor(worldPosition.y), Chunk.SIZE),
                Math.floorDiv((int) Math.floor(worldPosition.z), Chunk.SIZE)
        );
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

    private void updateMemoryPressure() {
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        long heapMax = runtime.maxMemory();
        long residentBytes = chunkManager.getEstimatedResidentBytes();
        long accountedBytes = residentBytes + reservedInFlightBytes;

        boolean emergency = residentBytes > memoryBudget.maxCpuResidentBytes()
                || heapUsed >= (long) (heapMax * memoryBudget.heapStopRatio());
        if (emergency) {
            memoryPressureState = WorldMemorySnapshot.PressureState.EMERGENCY;
            return;
        }

        boolean stop = accountedBytes >= memoryBudget.cpuStopBytes()
                || chunkManager.getChunkCount() >= memoryBudget.maxLoadedChunks();
        if (memoryPressureState == WorldMemorySnapshot.PressureState.NORMAL && stop) {
            memoryPressureState = WorldMemorySnapshot.PressureState.SUSPENDED;
            return;
        }

        boolean canResume = accountedBytes <= memoryBudget.cpuResumeBytes()
                && heapUsed <= (long) (heapMax * memoryBudget.heapResumeRatio())
                && chunkManager.getChunkCount() < memoryBudget.maxLoadedChunks();
        if (memoryPressureState != WorldMemorySnapshot.PressureState.NORMAL && canResume) {
            memoryPressureState = WorldMemorySnapshot.PressureState.NORMAL;
        }
    }

    private void reduceMemoryLimitedRadius(int requestedRadius) {
        int loadedChunks = chunkManager.getChunkCount();
        long residentBytes = chunkManager.getEstimatedResidentBytes();
        if (loadedChunks == 0 || residentBytes == 0) {
            return;
        }

        long targetChunkCount = Math.max(
                heightRange.chunkCount(),
                (long) loadedChunks * memoryBudget.cpuResumeBytes() / residentBytes
        );
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        long heapResumeBytes = (long) (runtime.maxMemory() * memoryBudget.heapResumeRatio());
        if (heapUsed > heapResumeBytes) {
            long heapTargetChunkCount = Math.max(
                    heightRange.chunkCount(),
                    (long) loadedChunks * heapResumeBytes / heapUsed
            );
            targetChunkCount = Math.min(targetChunkCount, heapTargetChunkCount);
        }
        targetChunkCount = Math.min(targetChunkCount, memoryBudget.maxLoadedChunks());
        int targetRadius = limitRadiusByChunkCount(requestedRadius, (int) Math.min(Integer.MAX_VALUE, targetChunkCount));
        memoryLimitedRenderRadius = Math.min(memoryLimitedRenderRadius, targetRadius);
    }

    private int limitRadiusByChunkCount(int requestedRadius, int chunkLimit) {
        int radius = Math.max(0, requestedRadius);
        while (radius > 0
                && desiredChunkCount(radius) > chunkLimit) {
            radius--;
        }
        return radius;
    }

    private int desiredChunkCount(int radius) {
        int columns = 0;
        int radiusSquared = radius * radius;
        for (int z = -radius; z <= radius; z++) {
            for (int x = -radius; x <= radius; x++) {
                if (x * x + z * z <= radiusSquared) {
                    columns++;
                }
            }
        }
        return columns * heightRange.chunkCount();
    }

    private boolean canSubmitMemoryTask() {
        return memoryPressureState == WorldMemorySnapshot.PressureState.NORMAL && canReserveTaskMemory();
    }

    private boolean canReserveTaskMemory() {
        synchronized (taskLock) {
            return reservedInFlightBytes + TASK_MEMORY_RESERVATION_BYTES <= memoryBudget.maxInFlightBytes()
                    && chunkManager.getEstimatedResidentBytes() + reservedInFlightBytes < memoryBudget.cpuStopBytes();
        }
    }

    private WorldMemorySnapshot createMemorySnapshot() {
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        ChunkClassificationCacheStats classificationStats = worldGenerator.getChunkClassificationCacheStats();
        return new WorldMemorySnapshot(
                chunkManager.getEstimatedResidentBytes(),
                reservedInFlightBytes,
                memoryBudget.maxCpuResidentBytes(),
                heapUsed,
                runtime.maxMemory(),
                chunkManager.getChunkCount(),
                chunkManager.getCompactLightingChunkCount(),
                chunkManager.getExpandedLightingChunkCount(),
                Math.max(0, requestedHorizontalRenderRadius),
                Math.max(0, activeHorizontalRenderRadius),
                rejectedLoadCount,
                sparseChunkStreamingEnabled,
                desiredMaterializedChunkCount,
                virtualEmptyChunkCount,
                virtualUniformChunkCount,
                interactionBubbleChunkCount,
                classificationStats.size(),
                classificationStats.hits(),
                classificationStats.misses(),
                memoryPressureState
        );
    }

    private ChunkBuildTask registerTask(ChunkPosition position, ChunkTaskType type) {
        synchronized (taskLock) {
            if (activeChunkTasks.containsKey(position)
                    || reservedInFlightBytes + TASK_MEMORY_RESERVATION_BYTES > memoryBudget.maxInFlightBytes()) {
                return null;
            }

            ChunkBuildTask task = new ChunkBuildTask(position, nextBuildToken++, type, ChunkTaskState.QUEUED);
            activeChunkTasks.put(position, task);
            reservedInFlightBytes += TASK_MEMORY_RESERVATION_BYTES;
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
            releaseTaskReservation();
        }

        if (task.type() == ChunkTaskType.LOAD) {
            chunkManager.clearQueuedChunk(position);
        }
        asyncCancelledBuilds.incrementAndGet();
    }

    private boolean failTask(ChunkBuildTask task) {
        boolean removed;
        synchronized (taskLock) {
            ChunkBuildTask currentTask = activeChunkTasks.get(task.position());
            removed = currentTask != null && currentTask.token() == task.token();
            if (removed) {
                currentTask.setState(ChunkTaskState.OBSOLETE);
                activeChunkTasks.remove(task.position());
                releaseTaskReservation();
            }
        }

        if (removed && task.type() == ChunkTaskType.LOAD) {
            chunkManager.clearQueuedChunk(task.position());
        }
        if (removed && task.type() == ChunkTaskType.REMESH) {
            markChunkDirty(task.position());
        }
        return removed;
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
            releaseTaskReservation();
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

    private boolean isTaskCancelled(ChunkBuildTask candidate) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        synchronized (taskLock) {
            ChunkBuildTask current = activeChunkTasks.get(candidate.position());
            return current == null
                    || current.token() != candidate.token()
                    || current.state() != ChunkTaskState.BUILDING
                    || (current.type() == ChunkTaskType.LOAD
                    && !desiredChunkPositions.contains(current.position()));
        }
    }

    private void throwIfTaskCancelled(ChunkBuildTask task) {
        if (isTaskCancelled(task)) {
            throw new CancellationException("Chunk build cancelled");
        }
    }

    private void releaseTaskReservation() {
        reservedInFlightBytes = Math.max(0L, reservedInFlightBytes - TASK_MEMORY_RESERVATION_BYTES);
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

    int getPendingRemeshCount() {
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
