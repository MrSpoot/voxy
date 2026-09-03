package org.weaw;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weaw.engine.graphics.Renderer;
import org.weaw.engine.graphics.pipeline.RenderStats;
import org.weaw.engine.graphics.utils.ChunkLightCacheProfilingSnapshot;
import org.weaw.engine.graphics.utils.Camera;
import org.weaw.engine.input.InputAction;
import org.weaw.engine.input.InputManager;
import org.weaw.engine.window.Window;
import org.weaw.game.World;
import org.weaw.game.ChunkMesher;
import org.weaw.game.WorldProfilingSnapshot;
import org.weaw.game.WorldMemorySnapshot;
import org.weaw.game.WorldSettings;
import org.weaw.game.generation.GenerationConfig;
import org.weaw.game.generation.NoiseWorldGenerator;
import org.weaw.game.utils.BlockDefinition;
import org.weaw.game.utils.BlockCatalog;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.game.utils.Blocks;
import org.weaw.gameplay.GameplaySession;
import org.weaw.gameplay.GameplaySettings;
import org.weaw.gameplay.PlayerInput;
import org.weaw.gameplay.PlayerRenderPose;
import org.weaw.gameplay.TargetedBlock;
import org.weaw.runtime.BenchmarkController;
import org.weaw.runtime.BenchmarkPhase;
import org.weaw.runtime.JfrProfileRecorder;
import org.weaw.runtime.LaunchOptions;
import org.weaw.runtime.FixedRateUpdateScheduler;
import org.weaw.runtime.FrameEventAccumulator;
import org.weaw.runtime.RuntimeFrameProfile;
import org.weaw.runtime.RuntimeProfilingCsvWriter;
import org.weaw.runtime.RuntimeProfilingSummaryCollector;
import org.weaw.server.GameServer;

import static org.lwjgl.opengl.GL11.GL_FILL;
import static org.lwjgl.opengl.GL11.GL_FRONT_AND_BACK;
import static org.lwjgl.opengl.GL11.GL_LINE;
import static org.lwjgl.opengl.GL11.glPolygonMode;

public class Game {
    private static final Logger LOGGER = LoggerFactory.getLogger(Game.class);
    private static final Vector3f DEFAULT_PLAYER_POSITION = new Vector3f(16.0f, 12.0f, 48.0f);
    private static final int WORLD_STREAMING_UPDATES_PER_SECOND =
            Integer.getInteger("voxy.worldStreamingUpdatesPerSecond", 60);
    private static final int MAX_WORLD_STREAMING_UPDATES_PER_FRAME =
            Integer.getInteger("voxy.maxWorldStreamingUpdatesPerFrame", 2);

    private final LaunchOptions launchOptions;

    private Window window;
    private InputManager inputManager;

    private Renderer renderer;
    private BlockCatalog blockCatalog;
    private Camera camera;
    private World world;
    private GameplaySession gameplaySession;
    private GameServer gameServer;
    private BenchmarkController benchmarkController;
    private JfrProfileRecorder jfrProfileRecorder;
    private RuntimeProfilingCsvWriter runtimeProfilingCsvWriter;
    private RuntimeProfilingSummaryCollector runtimeProfilingSummaryCollector;
    private FixedRateUpdateScheduler worldStreamingScheduler;
    private final FrameEventAccumulator<WorldProfilingSnapshot> worldUpdatesThisFrame = new FrameEventAccumulator<>();

    private double lastTime;
    private float pendingMouseDeltaX;
    private float pendingMouseDeltaY;
    private int pendingScrollDelta;
    private boolean pendingJump;
    private boolean pendingToggleNoclip;
    private boolean pendingBreakBlock;
    private boolean pendingPlaceBlock;

    private boolean wireframe = false;

    public Game() {
        this(LaunchOptions.from(new String[0]));
    }

    public Game(LaunchOptions launchOptions) {
        this.launchOptions = launchOptions;
    }

    public void run() {
        try {
            init();
            loop();
        } finally {
            cleanup();
        }
    }

    public void init(){
        LOGGER.info("Initializing");
        BlockRegistry.initialize();
        blockCatalog = BlockRegistry.getDefaultCatalog();

        window = new Window(
                "Voxy",
                launchOptions.benchmarkEnabled() ? launchOptions.benchmark().windowWidth() : 1920,
                launchOptions.benchmarkEnabled() ? launchOptions.benchmark().windowHeight() : 1080
        );
        window.create();

        inputManager = new InputManager(window.getId());
        inputManager.create();

        world = createTestWorld();
        applyRuntimeIsolationOptions();
        gameplaySession = new GameplaySession(world, new GameplaySettings());
        gameServer = new GameServer(world, gameplaySession);
        worldStreamingScheduler = new FixedRateUpdateScheduler(
                WORLD_STREAMING_UPDATES_PER_SECOND,
                MAX_WORLD_STREAMING_UPDATES_PER_FRAME
        );
        configureSession();

        renderer = new Renderer(
                window,
                world,
                inputManager,
                blockCatalog.getRegisteredBlocks().values(),
                launchOptions.transparentChunksEnabled()
        );
        renderer.create();
        renderer.getContext().getLightingSettings().setBlockLightEnabled(launchOptions.lightUploadEnabled());

        // Connect renderer to window for resize notifications
        window.setRenderer(renderer);

        camera = new Camera(90f,window.aspectRatio());
        syncCameraToPlayer(1.0f);
        startProfilingIfNeeded();
        startRuntimeProfilingIfNeeded();

        lastTime = System.nanoTime() / 1_000_000_000.0; // secondes
    }

    private void loop() {
        LOGGER.info("Starting game loop");

        while (!window.shouldClose()) {
            worldUpdatesThisFrame.reset();
            long frameStartNs = System.nanoTime();
            double now = System.nanoTime() / 1_000_000_000.0;
            float deltaTime = (float)(now - lastTime);
            lastTime = now;

            long updateStartNs = System.nanoTime();
            update(deltaTime);
            long updateCpuTimeNs = System.nanoTime() - updateStartNs;

            long renderStartNs = System.nanoTime();
            render(deltaTime);
            long renderCpuTimeNs = System.nanoTime() - renderStartNs;

            long windowStartNs = System.nanoTime();
            window.update();
            long windowCpuTimeNs = System.nanoTime() - windowStartNs;

            renderer.getContext().getRenderStats().recordFrameCpuTimes(
                    updateCpuTimeNs,
                    renderCpuTimeNs,
                    windowCpuTimeNs,
                    System.nanoTime() - frameStartNs
            );
            writeRuntimeProfilingFrame(deltaTime);
//            long end = System.nanoTime();
//            if((end - start) / 1_000_000.0 > 10.0){
//                LOGGER.warn("Game loop took too long: {} ms",(end - start) / 1_000_000.0);
//                LOGGER.warn("Memory usage: {} MB on {} MB", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_000_000.0, Runtime.getRuntime().totalMemory() / 1_000_000.0);
//            }
        }
    }

    private void cleanup() {
        safeCleanup("JFR profile", () -> {
            if (jfrProfileRecorder != null) {
                jfrProfileRecorder.close();
                LOGGER.info("JFR profile exported to {}", jfrProfileRecorder.getOutputPath());
                jfrProfileRecorder = null;
            }
        });
        safeCleanup("Runtime profiling CSV", () -> {
            if (runtimeProfilingCsvWriter != null) {
                runtimeProfilingCsvWriter.close();
                LOGGER.info("Runtime profiling CSV exported to {}", runtimeProfilingCsvWriter.getOutputPath());
                runtimeProfilingCsvWriter = null;
            }
        });
        safeCleanup("Runtime profiling summary", () -> {
            if (runtimeProfilingSummaryCollector != null && !runtimeProfilingSummaryCollector.isEmpty()) {
                try {
                    LOGGER.info(
                            "Runtime profiling summary exported to {}",
                            runtimeProfilingSummaryCollector.writeSummary(
                                    launchOptions.runtimeSummaryOutputPath(),
                                    launchOptions
                            )
                    );
                } catch (Exception exception) {
                    throw new IllegalStateException("Unable to export runtime profiling summary", exception);
                }
            }
            runtimeProfilingSummaryCollector = null;
        });
        safeCleanup("Renderer", () -> {
            if (renderer != null) {
                renderer.cleanup();
                renderer = null;
            }
        });
        safeCleanup("Game Server", () -> {
            if (gameServer != null) {
                gameServer.close();
                gameServer = null;
                world = null;
            }
        });
        safeCleanup("Input Manager", () -> {
            if (inputManager != null) {
                inputManager.cleanup();
                inputManager = null;
            }
        });
        safeCleanup("Window", () -> {
            if (window != null) {
                window.cleanup();
                window = null;
            }
        });
    }

    public static void main(String[] args) {
        new Game(LaunchOptions.from(args)).run();
    }

    private World createTestWorld() {
        GenerationConfig config = GenerationConfig.defaults();
        int renderDistance = WorldSettings.DEFAULT_RENDER_DISTANCE_CHUNKS;
        if (launchOptions.benchmarkEnabled()) {
            config = config.withSeed(launchOptions.benchmark().seed());
            renderDistance = launchOptions.benchmark().renderDistanceChunks();
        }
        WorldSettings settings = new WorldSettings(
                renderDistance,
                launchOptions.worldHeightRange(),
                launchOptions.worldMemoryBudget(),
                launchOptions.sparseChunkStreamingEnabled()
        );
        return new World(new NoiseWorldGenerator(config), settings, blockCatalog);
    }

    private void handleInputModes() {
        if (inputManager.isActionPressed(InputAction.TOGGLE_MOUSE_LOCK)) {
            window.toggleCursorLock();
            inputManager.resetMouseDelta();
        }
    }

    private void update(float deltaTime) {
        inputManager.update();

        if (inputManager.isActionDown(InputAction.QUIT)) {
            window.close();
            return;
        }

        if (launchOptions.benchmarkEnabled()) {
            updateBenchmark(deltaTime);
            return;
        }

        handleInputModes();

        if (inputManager.isActionPressed(InputAction.TOGGLE_WIREFRAME)) {
            glPolygonMode(GL_FRONT_AND_BACK, wireframe ? GL_FILL : GL_LINE);
            wireframe = !wireframe;
        }

        accumulatePlayerInputFrame();
        int simulationTicks = gameServer.update(deltaTime, samplePlayerInput());
        if (simulationTicks > 0) {
            clearConsumedPlayerInput();
        }
        updateWorldStreaming(deltaTime);
        syncSelectedBlockHud();
        updateRenderInteractionTarget();
    }

    private void updateBenchmark(float deltaTime) {
        BenchmarkPhase previousPhase = benchmarkController.phase();
        BenchmarkController.BenchmarkFrame frame = benchmarkController.update(deltaTime, world.isStreamingConverged());
        if (frame.phase() != previousPhase) {
            LOGGER.info("Benchmark phase: {} -> {}", previousPhase, frame.phase());
        }
        gameServer.updateBenchmarkPose(frame.position(), frame.yaw(), frame.pitch());
        updateWorldStreaming(deltaTime);
        renderer.getContext().clearBlockOutlineTarget();

        if (benchmarkController.isComplete()) {
            LOGGER.info(
                    "Benchmark completed after {} seconds (loadingConverged={}, loadingDuration={}s), closing window",
                    String.format(java.util.Locale.ROOT, "%.2f", benchmarkController.totalElapsedSeconds()),
                    benchmarkController.loadingConverged(),
                    String.format(java.util.Locale.ROOT, "%.2f", benchmarkController.loadingDurationSeconds())
            );
            window.close();
        }
    }

    private void render(float deltaTime) {
        syncCameraToPlayer(gameServer.getInterpolationAlpha());
        camera.setAspectRatio(window.aspectRatio());
        renderer.getContext().setFrameDeltaSeconds(Math.max(0.0f, deltaTime));
        renderer.render(camera);
    }

    private void updateWorldStreaming(float deltaTime) {
        worldStreamingScheduler.update(
                deltaTime,
                () -> {
                    world.update(gameplaySession.getPlayer().getPosition());
                    worldUpdatesThisFrame.add(world.getLastProfilingSnapshot());
                }
        );
    }

    private void accumulatePlayerInputFrame() {
        if (!window.isCursorLocked()) {
            inputManager.getMouseScroll();
            clearConsumedPlayerInput();
            return;
        }

        pendingMouseDeltaX += inputManager.getMousePosition().deltaX();
        pendingMouseDeltaY += inputManager.getMousePosition().deltaY();
        pendingScrollDelta += inputManager.getMouseScroll();
        pendingJump |= inputManager.isActionPressed(InputAction.MOVE_UP);
        pendingToggleNoclip |= inputManager.isActionPressed(InputAction.TOGGLE_NOCLIP);
        pendingBreakBlock |= inputManager.isActionPressed(InputAction.BREAK_BLOCK);
        pendingPlaceBlock |= inputManager.isActionPressed(InputAction.PLACE_BLOCK);
    }

    private PlayerInput samplePlayerInput() {
        return new PlayerInput(
                window.isCursorLocked(),
                inputManager.isActionDown(InputAction.MOVE_FORWARD),
                inputManager.isActionDown(InputAction.MOVE_BACKWARD),
                inputManager.isActionDown(InputAction.MOVE_LEFT),
                inputManager.isActionDown(InputAction.MOVE_RIGHT),
                inputManager.isActionDown(InputAction.MOVE_UP),
                inputManager.isActionDown(InputAction.MOVE_DOWN),
                pendingJump,
                inputManager.isActionDown(InputAction.SPRINT),
                pendingToggleNoclip,
                pendingBreakBlock,
                pendingPlaceBlock,
                pendingMouseDeltaX,
                pendingMouseDeltaY,
                pendingScrollDelta
        );
    }

    private void clearConsumedPlayerInput() {
        pendingMouseDeltaX = 0.0f;
        pendingMouseDeltaY = 0.0f;
        pendingScrollDelta = 0;
        pendingJump = false;
        pendingToggleNoclip = false;
        pendingBreakBlock = false;
        pendingPlaceBlock = false;
    }

    private void syncCameraToPlayer(float interpolationAlpha) {
        PlayerRenderPose renderPose = gameplaySession.sampleRenderPose(interpolationAlpha);
        camera.setPose(
                renderPose.position(),
                renderPose.yaw(),
                renderPose.pitch()
        );
    }

    private void updateRenderInteractionTarget() {
        if (!window.isCursorLocked()) {
            renderer.getContext().clearBlockOutlineTarget();
            return;
        }

        TargetedBlock targetedBlock = gameplaySession.getTargetedBlock();
        if (targetedBlock == null) {
            renderer.getContext().clearBlockOutlineTarget();
            return;
        }

        renderer.getContext().setBlockOutlineTarget(
                targetedBlock.blockX(),
                targetedBlock.blockY(),
                targetedBlock.blockZ(),
                targetedBlock.placeX(),
                targetedBlock.placeY(),
                targetedBlock.placeZ()
        );
    }

    private void syncSelectedBlockHud() {
        BlockDefinition selectedBlock = gameplaySession.getSelectedBlock();
        int hotbarIndex = 3;
        if (selectedBlock == Blocks.RED_LAMP) {
            hotbarIndex = 0;
        } else if (selectedBlock == Blocks.GREEN_LAMP) {
            hotbarIndex = 1;
        } else if (selectedBlock == Blocks.BLUE_LAMP) {
            hotbarIndex = 2;
        }
        renderer.getContext().setSelectedLampHotbarIndex(hotbarIndex);
    }

    private void safeCleanup(String label, Runnable cleanupAction) {
        try {
            LOGGER.info("Cleanup {}", label);
            cleanupAction.run();
        } catch (Exception exception) {
            LOGGER.error("Cleanup {} failed", label, exception);
        }
    }

    private void configureSession() {
        if (!launchOptions.benchmarkEnabled()) {
            gameplaySession.setPlayerPosition(DEFAULT_PLAYER_POSITION);
            return;
        }

        benchmarkController = new BenchmarkController(launchOptions.benchmark());
        BenchmarkController.BenchmarkFrame initialFrame = benchmarkController.currentFrame();
        gameplaySession.setPlayerPose(initialFrame.position(), initialFrame.yaw(), initialFrame.pitch());

        LOGGER.info(
                "Benchmark mode enabled: warmup={}s loadingTimeout={}s traversal={}s settle={}s seed={} renderDistance={} window={}x{}",
                launchOptions.benchmark().warmupSeconds(),
                launchOptions.benchmark().loadingTimeoutSeconds(),
                launchOptions.benchmark().durationSeconds(),
                launchOptions.benchmark().settleSeconds(),
                launchOptions.benchmark().seed(),
                launchOptions.benchmark().renderDistanceChunks(),
                launchOptions.benchmark().windowWidth(),
                launchOptions.benchmark().windowHeight()
        );
    }

    private void applyRuntimeIsolationOptions() {
        world.setDynamicLightingEnabled(launchOptions.dynamicLightingEnabled());
        world.setRemeshEnabled(launchOptions.remeshEnabled());
        world.setUnloadsEnabled(launchOptions.unloadsEnabled());
        ChunkMesher.setAmbientOcclusionEnabled(launchOptions.ambientOcclusionEnabled());
        ChunkMesher.setTransparentChunksEnabled(launchOptions.transparentChunksEnabled());

        if (launchOptions.dynamicLightingEnabled()
                && launchOptions.lightUploadEnabled()
                && launchOptions.ambientOcclusionEnabled()
                && launchOptions.remeshEnabled()
                && launchOptions.unloadsEnabled()
                && launchOptions.transparentChunksEnabled()
                && launchOptions.sparseChunkStreamingEnabled()) {
            return;
        }

        LOGGER.info(
                "Runtime isolation flags: dynamicLighting={} lightUpload={} ao={} remesh={} unloads={} transparentChunks={} sparseStreaming={}",
                launchOptions.dynamicLightingEnabled(),
                launchOptions.lightUploadEnabled(),
                launchOptions.ambientOcclusionEnabled(),
                launchOptions.remeshEnabled(),
                launchOptions.unloadsEnabled(),
                launchOptions.transparentChunksEnabled(),
                launchOptions.sparseChunkStreamingEnabled()
        );
    }

    private void startProfilingIfNeeded() {
        if (!launchOptions.jfrEnabled()) {
            return;
        }

        try {
            jfrProfileRecorder = JfrProfileRecorder.start(launchOptions.jfrOutputPath());
            LOGGER.info("JFR recording started: {}", jfrProfileRecorder.getOutputPath());
        } catch (Exception exception) {
            LOGGER.error("Unable to start JFR recording", exception);
        }
    }

    private void startRuntimeProfilingIfNeeded() {
        if (!launchOptions.runtimeStatsEnabled()) {
            return;
        }

        runtimeProfilingSummaryCollector = new RuntimeProfilingSummaryCollector();
        try {
            runtimeProfilingCsvWriter = RuntimeProfilingCsvWriter.create(launchOptions.runtimeStatsOutputPath());
            LOGGER.info("Runtime profiling CSV started: {}", runtimeProfilingCsvWriter.getOutputPath());
        } catch (Exception exception) {
            LOGGER.error("Unable to start runtime profiling CSV", exception);
        }
    }

    private void writeRuntimeProfilingFrame(float deltaTime) {
        if (runtimeProfilingCsvWriter == null && runtimeProfilingSummaryCollector == null) {
            return;
        }

        RenderStats renderStats = renderer.getContext().getRenderStats();
        WorldProfilingSnapshot worldProfilingSnapshot = worldUpdatesThisFrame.latestOr(world.getLastProfilingSnapshot());
        WorldProfilingSnapshot firstWorldProfilingSnapshot = worldUpdatesThisFrame.firstOr(worldProfilingSnapshot);
        WorldMemorySnapshot worldMemorySnapshot = world.getMemorySnapshot();
        ChunkLightCacheProfilingSnapshot lightCacheProfilingSnapshot =
                renderer.getContext().getChunkLightCache().consumeProfilingSnapshot();

        int avoidedChunkCandidates = worldMemorySnapshot.virtualEmptyChunks()
                + worldMemorySnapshot.virtualUniformChunks();
        int legacyCandidateChunks = worldMemorySnapshot.desiredMaterializedChunks() + avoidedChunkCandidates;
        double chunkAvoidancePercent = percentage(avoidedChunkCandidates, legacyCandidateChunks);
        long classificationQueries = worldMemorySnapshot.classificationCacheHits()
                + worldMemorySnapshot.classificationCacheMisses();
        double classificationCacheHitPercent = percentage(
                worldMemorySnapshot.classificationCacheHits(),
                classificationQueries
        );

        RuntimeFrameProfile frameProfile = new RuntimeFrameProfile(
                renderStats.getFrameIndex(),
                benchmarkController == null ? BenchmarkPhase.MANUAL.name() : benchmarkController.phase().name(),
                benchmarkController == null ? 0.0d : benchmarkController.phaseElapsedSeconds(),
                benchmarkController == null ? 0.0d : benchmarkController.totalElapsedSeconds(),
                world.isStreamingConverged(),
                benchmarkController != null && benchmarkController.loadingConverged(),
                benchmarkController == null ? 0.0d : benchmarkController.loadingDurationSeconds(),
                deltaTime > 0.0f ? 1.0d / deltaTime : 0.0d,
                nanosToMillis(renderStats.getFrameCpuTimeNs()),
                nanosToMillis(renderStats.getUpdateCpuTimeNs()),
                nanosToMillis(renderStats.getRenderCpuTimeNs()),
                nanosToMillis(renderStats.getWindowCpuTimeNs()),
                nanosToMillis(worldUpdatesThisFrame.sumLong(WorldProfilingSnapshot::worldUpdateCpuTimeNs)),
                nanosToMillis(worldUpdatesThisFrame.sumLong(WorldProfilingSnapshot::worldStreamerUpdateCpuTimeNs)),
                nanosToMillis(worldUpdatesThisFrame.sumLong(WorldProfilingSnapshot::lightingCollectionCpuTimeNs)),
                nanosToMillis(worldUpdatesThisFrame.sumLong(WorldProfilingSnapshot::lightingCpuTimeNs)),
                nanosToMillis(worldUpdatesThisFrame.sumLong(WorldProfilingSnapshot::chunkGenerationCpuTimeNs)),
                nanosToMillis(worldUpdatesThisFrame.sumLong(WorldProfilingSnapshot::chunkMeshCpuTimeNs)),
                nanosToMillis(worldUpdatesThisFrame.sumLong(WorldProfilingSnapshot::chunkMeshingSnapshotCpuTimeNs)),
                nanosToMillis(worldUpdatesThisFrame.sumLong(WorldProfilingSnapshot::chunkMeshingFaceClassificationCpuTimeNs)),
                nanosToMillis(worldUpdatesThisFrame.sumLong(WorldProfilingSnapshot::chunkMeshingGreedyMergeCpuTimeNs)),
                nanosToMillis(worldUpdatesThisFrame.sumLong(WorldProfilingSnapshot::chunkMeshingOutputBuildCpuTimeNs)),
                nanosToMillis(worldUpdatesThisFrame.sumLong(WorldProfilingSnapshot::chunkPublishCpuTimeNs)),
                nanosToMillis(worldUpdatesThisFrame.sumLong(WorldProfilingSnapshot::chunkUnloadCpuTimeNs)),
                nanosToMillis(renderStats.getTotalPassCpuTimeNs()),
                nanosToMillis(passCpuTimeNs(renderStats, "OpaqueChunkRenderPass")),
                nanosToMillis(passCpuTimeNs(renderStats, "CutoutChunkRenderPass")),
                nanosToMillis(passCpuTimeNs(renderStats, "TransparentChunkRenderPass")),
                nanosToMillis(passCpuTimeNs(renderStats, "BlockOutlinePass")),
                nanosToMillis(passCpuTimeNs(renderStats, "AntiAliasingPass")),
                nanosToMillis(passCpuTimeNs(renderStats, "ToneMappingPass")),
                nanosToMillis(passCpuTimeNs(renderStats, "HudPass")),
                nanosToMillis(passCpuTimeNs(renderStats, "DebugImGuiPass")),
                passResidentMeshCount(renderStats, "OpaqueChunkRenderPass"),
                passVisibleMeshCount(renderStats, "OpaqueChunkRenderPass"),
                passDrawCalls(renderStats, "OpaqueChunkRenderPass"),
                passDrawnFaceCount(renderStats, "OpaqueChunkRenderPass"),
                nanosToMillis(passMeshUploadCpuTimeNs(renderStats, "OpaqueChunkRenderPass")),
                nanosToMillis(passLightUploadCpuTimeNs(renderStats, "OpaqueChunkRenderPass")),
                passResidentMeshCount(renderStats, "CutoutChunkRenderPass"),
                passVisibleMeshCount(renderStats, "CutoutChunkRenderPass"),
                passDrawCalls(renderStats, "CutoutChunkRenderPass"),
                passDrawnFaceCount(renderStats, "CutoutChunkRenderPass"),
                nanosToMillis(passMeshUploadCpuTimeNs(renderStats, "CutoutChunkRenderPass")),
                nanosToMillis(passLightUploadCpuTimeNs(renderStats, "CutoutChunkRenderPass")),
                passResidentMeshCount(renderStats, "TransparentChunkRenderPass"),
                passVisibleMeshCount(renderStats, "TransparentChunkRenderPass"),
                passDrawCalls(renderStats, "TransparentChunkRenderPass"),
                passDrawnFaceCount(renderStats, "TransparentChunkRenderPass"),
                nanosToMillis(passMeshUploadCpuTimeNs(renderStats, "TransparentChunkRenderPass")),
                nanosToMillis(passLightUploadCpuTimeNs(renderStats, "TransparentChunkRenderPass")),
                nanosToMillis(totalMeshUploadCpuTimeNs(renderStats)),
                nanosToMillis(totalLightUploadCpuTimeNs(renderStats)),
                nanosToMillis(worldUpdatesThisFrame.sumLong(WorldProfilingSnapshot::lightingSnapshotLoadedChunksCpuTimeNs)),
                nanosToMillis(worldUpdatesThisFrame.sumLong(WorldProfilingSnapshot::lightingClearCpuTimeNs)),
                nanosToMillis(worldUpdatesThisFrame.sumLong(WorldProfilingSnapshot::lightingSeedCpuTimeNs)),
                nanosToMillis(worldUpdatesThisFrame.sumLong(WorldProfilingSnapshot::lightingPropagateCpuTimeNs)),
                firstWorldProfilingSnapshot.pendingLightingUpdatesBeforeCollection(),
                worldProfilingSnapshot.pendingLightingUpdatesAfterCollection(),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::lightingBatchSize),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::lightingAffectedChunkCount),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::lightingExpandedChunkCount),
                worldProfilingSnapshot.lightingLoadedChunkCount(),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::lightingLoadedTargetChunkCount),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::lightingMarkedChunkCount),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::lightingClearedChunkCount),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::lightingEmitterCount),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::lightingSeedNodeCount),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::lightingPropagationNodeCount),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::lightingLightWriteCount),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::lightingBlockedByOpaqueCount),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::lightingMissingChunkNeighborCount),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::lightingNoGainCount),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::lightUploadFullSnapshotCount),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::lightUploadDeltaCount),
                lightCacheProfilingSnapshot.synchronizeCalls(),
                lightCacheProfilingSnapshot.refreshedAllocationCount(),
                lightCacheProfilingSnapshot.freedAllocationCount(),
                lightCacheProfilingSnapshot.uploadedChunkCount(),
                lightCacheProfilingSnapshot.residentAllocationCount(),
                worldProfilingSnapshot.loadedChunks(),
                renderer.getContext().getVisibleChunkPositions().size(),
                worldProfilingSnapshot.queuedTasks(),
                worldProfilingSnapshot.pendingRemesh(),
                worldProfilingSnapshot.pendingUploads(),
                worldProfilingSnapshot.pendingUnloads(),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::chunksPublished),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::chunksUnloaded),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::chunksGenerated),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::chunksMeshed),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::chunksRemeshed),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::chunkMeshingAmbientOcclusionFaces),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::chunkMeshingSampledBlocks),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::cancelledChunkBuilds),
                worldMemorySnapshot.estimatedCpuResidentBytes(),
                worldMemorySnapshot.maxCpuResidentBytes(),
                worldMemorySnapshot.reservedInFlightBytes(),
                renderer.getContext().getChunkGpuMemoryBudget().getResidentBytes(),
                worldMemorySnapshot.compactLightingChunks(),
                worldMemorySnapshot.expandedLightingChunks(),
                worldMemorySnapshot.requestedRenderDistanceChunks(),
                worldMemorySnapshot.effectiveRenderDistanceChunks(),
                worldMemorySnapshot.rejectedLoadCount(),
                worldMemorySnapshot.pressureState().name(),
                worldUpdatesThisFrame.size(),
                worldMemorySnapshot.sparseChunkStreamingEnabled(),
                worldMemorySnapshot.desiredMaterializedChunks(),
                worldMemorySnapshot.virtualEmptyChunks(),
                worldMemorySnapshot.virtualUniformChunks(),
                worldMemorySnapshot.interactionBubbleChunks(),
                legacyCandidateChunks,
                avoidedChunkCandidates,
                chunkAvoidancePercent,
                worldMemorySnapshot.classificationCacheColumns(),
                worldMemorySnapshot.classificationCacheHits(),
                worldMemorySnapshot.classificationCacheMisses(),
                classificationCacheHitPercent
        );

        if (runtimeProfilingSummaryCollector != null) {
            runtimeProfilingSummaryCollector.recordFrame(frameProfile);
        }

        if (runtimeProfilingCsvWriter == null) {
            return;
        }

        try {
            runtimeProfilingCsvWriter.writeFrame(frameProfile);
        } catch (Exception exception) {
            LOGGER.error("Unable to write runtime profiling frame", exception);
            safeCleanup("Runtime profiling CSV", () -> {
                if (runtimeProfilingCsvWriter != null) {
                    runtimeProfilingCsvWriter.close();
                    runtimeProfilingCsvWriter = null;
                }
            });
        }
    }

    private static long passCpuTimeNs(RenderStats renderStats, String passName) {
        for (RenderStats.PassStats passStats : renderStats.getPassStats()) {
            if (passName.equals(passStats.getName())) {
                return passStats.getCpuTimeNs();
            }
        }
        return 0L;
    }

    private static int passResidentMeshCount(RenderStats renderStats, String passName) {
        for (RenderStats.PassStats passStats : renderStats.getPassStats()) {
            if (passName.equals(passStats.getName())) {
                return passStats.getResidentMeshCount();
            }
        }
        return 0;
    }

    private static int passVisibleMeshCount(RenderStats renderStats, String passName) {
        for (RenderStats.PassStats passStats : renderStats.getPassStats()) {
            if (passName.equals(passStats.getName())) {
                return passStats.getVisibleMeshCount();
            }
        }
        return 0;
    }

    private static int passDrawCalls(RenderStats renderStats, String passName) {
        for (RenderStats.PassStats passStats : renderStats.getPassStats()) {
            if (passName.equals(passStats.getName())) {
                return passStats.getDrawCalls();
            }
        }
        return 0;
    }

    private static int passDrawnFaceCount(RenderStats renderStats, String passName) {
        for (RenderStats.PassStats passStats : renderStats.getPassStats()) {
            if (passName.equals(passStats.getName())) {
                return passStats.getDrawnFaceCount();
            }
        }
        return 0;
    }

    private static long passMeshUploadCpuTimeNs(RenderStats renderStats, String passName) {
        for (RenderStats.PassStats passStats : renderStats.getPassStats()) {
            if (passName.equals(passStats.getName())) {
                return passStats.getMeshUploadCpuTimeNs();
            }
        }
        return 0L;
    }

    private static long passLightUploadCpuTimeNs(RenderStats renderStats, String passName) {
        for (RenderStats.PassStats passStats : renderStats.getPassStats()) {
            if (passName.equals(passStats.getName())) {
                return passStats.getLightUploadCpuTimeNs();
            }
        }
        return 0L;
    }

    private static long totalMeshUploadCpuTimeNs(RenderStats renderStats) {
        long total = 0L;
        for (RenderStats.PassStats passStats : renderStats.getPassStats()) {
            total += passStats.getMeshUploadCpuTimeNs();
        }
        return total;
    }

    private static long totalLightUploadCpuTimeNs(RenderStats renderStats) {
        long total = 0L;
        for (RenderStats.PassStats passStats : renderStats.getPassStats()) {
            total += passStats.getLightUploadCpuTimeNs();
        }
        return total;
    }

    private static double nanosToMillis(long nanoseconds) {
        return nanoseconds / 1_000_000.0d;
    }

    private static double percentage(long numerator, long denominator) {
        return denominator <= 0L ? 0.0d : numerator * 100.0d / denominator;
    }
}
