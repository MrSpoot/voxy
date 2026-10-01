package org.weaw;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weaw.engine.graphics.Renderer;
import org.weaw.engine.graphics.pipeline.RenderStats;
import org.weaw.engine.graphics.pipeline.resources.GLStateManager;
import org.weaw.engine.graphics.utils.ChunkLightCacheProfilingSnapshot;
import org.weaw.engine.graphics.utils.Camera;
import org.weaw.engine.input.InputAction;
import org.weaw.engine.input.InputManager;
import org.weaw.engine.ui.CreativeInventoryLayout;
import org.weaw.engine.window.Window;
import org.weaw.client.ClientApplication;
import org.weaw.client.ui.GameUiState;
import org.weaw.game.World;
import org.weaw.game.ChunkMesher;
import org.weaw.game.WorldProfilingSnapshot;
import org.weaw.game.WorldMemorySnapshot;
import org.weaw.game.WorldSettings;
import org.weaw.game.generation.GenerationConfig;
import org.weaw.game.generation.NoiseWorldGenerator;
import org.weaw.game.utils.BlockCatalog;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.gameplay.CreativeInventoryState;
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
import org.weaw.server.MultiplayerGameServer;
import org.weaw.server.DedicatedServerApplication;
import org.weaw.network.client.NetworkClientSession;
import org.weaw.network.NetworkDebugSnapshot;
import org.weaw.network.transport.ClientTransport;
import org.weaw.network.transport.CompositeServerTransport;
import org.weaw.network.transport.LocalTransportPair;
import org.weaw.network.transport.ServerTransport;
import org.weaw.network.transport.TcpClientTransport;
import org.weaw.network.transport.TcpServerTransport;
import org.weaw.runtime.NetworkMode;
import org.weaw.persistence.PlayerProfile;
import org.weaw.persistence.PlayerProfileRepository;
import org.weaw.persistence.ClientSettings;
import org.weaw.persistence.ClientSettingsRepository;
import org.weaw.persistence.WorldManifest;
import org.weaw.persistence.WorldRepository;
import org.weaw.persistence.WorldSaveSession;
import org.weaw.persistence.WorldSaveException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.LockSupport;

import static org.lwjgl.opengl.GL11.GL_FILL;
import static org.lwjgl.opengl.GL11.GL_LINE;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

public class Game {
    private static final Logger LOGGER = LoggerFactory.getLogger(Game.class);
    private static final Vector3f DEFAULT_PLAYER_POSITION = new Vector3f(16.0f, 12.0f, 48.0f);
    private static final int WORLD_STREAMING_UPDATES_PER_SECOND =
            Integer.getInteger("voxy.worldStreamingUpdatesPerSecond", 60);
    private static final int MAX_WORLD_STREAMING_UPDATES_PER_FRAME =
            Integer.getInteger("voxy.maxWorldStreamingUpdatesPerFrame", 2);
    private static final InputAction[] HOTBAR_ACTIONS = {
            InputAction.HOTBAR_SLOT_1,
            InputAction.HOTBAR_SLOT_2,
            InputAction.HOTBAR_SLOT_3,
            InputAction.HOTBAR_SLOT_4,
            InputAction.HOTBAR_SLOT_5,
            InputAction.HOTBAR_SLOT_6,
            InputAction.HOTBAR_SLOT_7,
            InputAction.HOTBAR_SLOT_8,
            InputAction.HOTBAR_SLOT_9
    };

    private final LaunchOptions launchOptions;
    private final GameUiState gameUiState = new GameUiState();

    private Window window;
    private InputManager inputManager;

    private Renderer renderer;
    private BlockCatalog blockCatalog;
    private Camera camera;
    private World world;
    private GameplaySession gameplaySession;
    private CreativeInventoryState creativeInventoryState;
    private GameServer gameServer;
    private MultiplayerGameServer multiplayerServer;
    private NetworkClientSession networkSession;
    private PlayerProfile playerProfile;
    private ClientSettings clientSettings;
    private WorldSaveSession worldSaveSession;
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
    private boolean cursorLockedBeforeInventory;
    private boolean cursorLockedBeforePause;
    private boolean suppressQuitUntilReleased;
    private boolean returnToTitleRequested;
    private boolean sessionStopRequested;
    private boolean ownsWindow;
    private volatile boolean preparationCancelled;
    private volatile LoadProgress loadProgress = new LoadProgress(LoadStage.STARTING, 0.0f);
    private volatile boolean prepared;

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
        prepare();
        window = new Window(
                "Voxy",
                launchOptions.benchmarkEnabled() ? launchOptions.benchmark().windowWidth() : clientSettings.windowWidth(),
                launchOptions.benchmarkEnabled() ? launchOptions.benchmark().windowHeight() : clientSettings.windowHeight(),
                !launchOptions.benchmarkEnabled() && clientSettings.fullscreen()
        );
        window.create();
        ownsWindow = true;
        attach(window);
    }

    /** Performs all blocking, non-OpenGL session preparation. Safe to call on a worker thread. */
    public void prepare() {
        if (prepared) {
            return;
        }
        try {
            LOGGER.info("Initializing");
            updateLoadProgress(LoadStage.REGISTRY, 0.08f);
            BlockRegistry.initialize();
            blockCatalog = BlockRegistry.getDefaultCatalog();
            checkPreparationCancelled();
            updateLoadProgress(LoadStage.SETTINGS, 0.16f);
            try {
                clientSettings = new ClientSettingsRepository(launchOptions.storage().dataDirectory()).load();
            } catch (RuntimeException exception) {
                LOGGER.warn("Unable to load client settings, using defaults: {}", exception.getMessage());
                clientSettings = ClientSettings.defaults();
            }

            if (launchOptions.benchmarkEnabled()) {
                initializeBenchmarkSession();
            } else {
                initializeNetworkSession();
            }
            checkPreparationCancelled();
            prepared = true;
            checkPreparationCancelled();
            updateLoadProgress(LoadStage.READY, 1.0f);
        } catch (RuntimeException | Error exception) {
            cleanup();
            throw exception;
        }
    }

    private void attach(Window targetWindow) {
        window = targetWindow;
        sessionStopRequested = false;
        returnToTitleRequested = false;
        window.setVsync(clientSettings.vsync());
        window.setCursorLocked(true);
        gameUiState.initializeSettings(clientSettings);
        inputManager = new InputManager(window.getId());
        applyClientBindings();
        inputManager.create();
        gameplaySession.getSettings().setMouseSensitivity(clientSettings.mouseSensitivity());
        creativeInventoryState = new CreativeInventoryState(blockCatalog, gameplaySession.getHotbar());

        renderer = new Renderer(
                window,
                world,
                inputManager,
                blockCatalog.getRegisteredBlocks().values(),
                launchOptions.transparentChunksEnabled(),
                creativeInventoryState,
                networkSession == null ? null : networkSession.getRemotePlayers()
        );
        renderer.create();
        renderer.getContext().setGameUiState(gameUiState);
        boolean voxelLightDataEnabled = launchOptions.dynamicLightingEnabled() && launchOptions.lightUploadEnabled();
        renderer.getContext().setVoxelLightDataEnabled(voxelLightDataEnabled);
        renderer.getContext().getLightingSettings().setBlockLightEnabled(voxelLightDataEnabled);
        renderer.applyGraphicsPreferences(clientSettings.graphicsPreferences());
        renderer.getContext().setUiScale(clientSettings.uiScale());

        // Connect renderer to window for resize notifications
        window.setRenderer(renderer);

        camera = new Camera(clientSettings.fieldOfView(), window.aspectRatio());
        syncCameraToPlayer(1.0f);
        startProfilingIfNeeded();
        startRuntimeProfilingIfNeeded();

        lastTime = System.nanoTime() / 1_000_000_000.0; // secondes
    }

    /** Runs a prepared session inside an application-owned window. */
    public SessionOutcome run(Window sharedWindow) {
        try {
            prepare();
            ownsWindow = false;
            attach(sharedWindow);
            loop();
            return returnToTitleRequested ? SessionOutcome.RETURN_TO_TITLE : SessionOutcome.EXIT_APPLICATION;
        } finally {
            cleanup();
        }
    }

    public LoadProgress loadProgress() {
        return loadProgress;
    }

    public void cancelPreparation() {
        preparationCancelled = true;
        if (prepared && window == null) {
            cleanup();
        }
    }

    private void applyClientBindings() {
        for (var entry : clientSettings.bindings().entrySet()) {
            try {
                InputAction action = InputAction.fromId(entry.getKey());
                ClientSettings.BindingSetting binding = entry.getValue();
                if ("mouse".equalsIgnoreCase(binding.type())) {
                    inputManager.bindMouseButton(action, binding.code());
                } else if ("key".equalsIgnoreCase(binding.type())) {
                    inputManager.bindKey(action, binding.code());
                }
            } catch (IllegalArgumentException exception) {
                LOGGER.warn("Ignoring invalid input binding {}", entry.getKey());
            }
        }
    }

    private void initializeBenchmarkSession() {
        updateLoadProgress(LoadStage.WORLD, 0.42f);
        world = createTestWorld();
        applyRuntimeIsolationOptions(world);
        gameplaySession = new GameplaySession(world, new GameplaySettings());
        gameServer = new GameServer(world, gameplaySession);
        worldStreamingScheduler = new FixedRateUpdateScheduler(
                WORLD_STREAMING_UPDATES_PER_SECOND,
                MAX_WORLD_STREAMING_UPDATES_PER_FRAME
        );
        configureSession();
    }

    private void initializeNetworkSession() {
        ClientTransport clientTransport = null;
        try {
            updateLoadProgress(LoadStage.PROFILE, 0.26f);
            playerProfile = new PlayerProfileRepository(launchOptions.storage().dataDirectory()).openOrCreate(
                    launchOptions.storage().profileKey(),
                    launchOptions.storage().requestedPlayerName()
            );
            NetworkMode mode = launchOptions.network().mode();
            checkPreparationCancelled();
            if (mode == NetworkMode.CONNECT) {
                updateLoadProgress(LoadStage.CONNECTING, 0.48f);
                clientTransport = new TcpClientTransport(
                        launchOptions.network().host(),
                        launchOptions.network().port()
                );
            } else {
                updateLoadProgress(LoadStage.WORLD, 0.38f);
                LocalTransportPair local = new LocalTransportPair();
                List<ServerTransport> serverTransports = new ArrayList<>();
                serverTransports.add(local.server());
                if (mode == NetworkMode.HOST) {
                    serverTransports.add(new TcpServerTransport(
                            launchOptions.network().port(),
                            launchOptions.network().lanVisible() ? null : java.net.InetAddress.getLoopbackAddress()
                    ));
                }
                ServerTransport serverTransport = serverTransports.size() == 1
                        ? serverTransports.getFirst()
                        : new CompositeServerTransport(serverTransports);
                World serverWorld = createPersistentWorld();
                checkPreparationCancelled();
                applyRuntimeIsolationOptions(serverWorld);
                multiplayerServer = new MultiplayerGameServer(
                        serverWorld,
                        worldSaveSession.manifest().seed(),
                        serverTransport,
                        launchOptions.network().maxPlayers(),
                        worldSaveSession
                );
                multiplayerServer.start();
                updateLoadProgress(LoadStage.SERVER, 0.62f);
                clientTransport = local.client();
                if (mode == NetworkMode.HOST) {
                    LOGGER.info("Hosting Voxy on port {}", launchOptions.network().port());
                }
            }

            networkSession = new NetworkClientSession(
                    clientTransport,
                    blockCatalog,
                    playerProfile.id(),
                    playerProfile.displayName(),
                    launchOptions.storage().defaultRenderDistanceChunks()
            );
            checkPreparationCancelled();
            updateLoadProgress(LoadStage.HANDSHAKE, 0.78f);
            networkSession.connect();
            checkPreparationCancelled();
            world = networkSession.getClientWorld().world();
            gameplaySession = networkSession.getGameplay();
            updateLoadProgress(LoadStage.WORLD, 0.92f);
        } catch (IOException | RuntimeException exception) {
            if (multiplayerServer != null) {
                multiplayerServer.close();
                multiplayerServer = null;
                worldSaveSession = null;
            }
            if (networkSession != null) {
                networkSession.close();
                networkSession = null;
            } else if (clientTransport != null) {
                clientTransport.close();
            }
            if (exception instanceof CancellationException cancellationException) {
                throw cancellationException;
            }
            throw new IllegalStateException("Unable to initialize multiplayer session", exception);
        }
    }

    private World createPersistentWorld() {
        WorldRepository repository = new WorldRepository(launchOptions.storage().dataDirectory());
        GenerationConfig requestedGeneration = launchOptions.generationConfig();
        worldSaveSession = repository.openOrCreate(
                launchOptions.storage(),
                requestedGeneration,
                launchOptions.worldHeightRange(),
                blockCatalog
        );
        WorldManifest manifest = worldSaveSession.manifest();
        WorldSettings settings = new WorldSettings(
                manifest.simulationDistanceChunks(),
                manifest.defaultRenderDistanceChunks(),
                manifest.heightRange(),
                launchOptions.worldMemoryBudget(),
                launchOptions.sparseChunkStreamingEnabled()
        );
        return new World(
                new NoiseWorldGenerator(manifest.generationConfig()),
                settings,
                blockCatalog,
                worldSaveSession.consumeInitialEdits()
        );
    }

    private void loop() {
        LOGGER.info("Starting game loop");

        while (!window.shouldClose() && !sessionStopRequested) {
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
            limitFrameRate(frameStartNs);
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
            if (window != null) {
                window.setRenderer(null);
            }
            if (renderer != null) {
                renderer.cleanup();
                renderer = null;
            }
        });
        safeCleanup("Game Server", () -> {
            if (networkSession != null) {
                networkSession.close();
                networkSession = null;
                world = null;
            }
            if (multiplayerServer != null) {
                multiplayerServer.close();
                multiplayerServer = null;
                worldSaveSession = null;
            }
            if (gameServer != null) {
                gameServer.close();
                gameServer = null;
                world = null;
            }
            if (worldSaveSession != null) {
                worldSaveSession.close();
                worldSaveSession = null;
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
                window.setCursorLocked(false);
                if (ownsWindow) {
                    window.cleanup();
                }
                window = null;
            }
        });
        prepared = false;
        ownsWindow = false;
    }

    public static void main(String[] args) {
        try {
            LaunchOptions options = LaunchOptions.from(args);
            if (options.network().mode() == NetworkMode.DEDICATED) {
                DedicatedServerApplication.run(options);
                return;
            }
            if (!options.interactiveMenuRequested()) {
                new Game(options).run();
                return;
            }
            new ClientApplication(options).run();
        } catch (WorldSaveException exception) {
            LOGGER.error(
                    "Unable to open persistent data ({} at {}): {}",
                    exception.kind(), exception.path(), exception.getMessage()
            );
            throw exception;
        }
    }

    private World createTestWorld() {
        GenerationConfig config = GenerationConfig.defaults();
        int renderDistance = launchOptions.network().viewDistance();
        if (launchOptions.benchmarkEnabled()) {
            config = config.withSeed(launchOptions.benchmark().seed());
            renderDistance = launchOptions.benchmark().renderDistanceChunks();
        } else {
            config = config.withSeed(launchOptions.network().worldSeed());
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

        if (launchOptions.benchmarkEnabled()) {
            if (inputManager.isActionDown(InputAction.QUIT)) {
                window.requestClose();
                return;
            }
            updateBenchmark(deltaTime);
            return;
        }

        processGameUiCommands();

        boolean inventoryTransition = handleCreativeInventoryInput();
        if (suppressQuitUntilReleased && !inputManager.isActionDown(InputAction.QUIT)) {
            suppressQuitUntilReleased = false;
        }
        if (!creativeInventoryState.isOpen()
                && !inventoryTransition
                && !suppressQuitUntilReleased
                && inputManager.isActionPressed(InputAction.QUIT)) {
            setPaused(!gameUiState.isPaused());
            return;
        }

        if (gameUiState.isPaused()) {
            inputManager.getMouseScroll();
            clearConsumedPlayerInput();
            if (launchOptions.network().mode() != NetworkMode.SOLO) {
                networkSession.update(deltaTime, PlayerInput.disabled());
            }
            updateNetworkDebugSnapshot();
            return;
        }

        if (!creativeInventoryState.isOpen() && !inventoryTransition) {
            handleInputModes();
            handleHotbarKeys();
            if (inputManager.isActionPressed(InputAction.TOGGLE_DEBUG)) {
                gameUiState.toggleDebugVisible();
            }
        }

        if (!creativeInventoryState.isOpen()
                && !inventoryTransition
                && inputManager.isActionPressed(InputAction.TOGGLE_WIREFRAME)) {
            wireframe = !wireframe;
            GLStateManager.setPolygonMode(wireframe ? GL_LINE : GL_FILL);
        }

        int simulationTicks;
        if (creativeInventoryState.isOpen() || inventoryTransition) {
            inputManager.getMouseScroll();
            clearConsumedPlayerInput();
            simulationTicks = networkSession.update(deltaTime, PlayerInput.disabled());
        } else {
            accumulatePlayerInputFrame();
            simulationTicks = networkSession.update(deltaTime, samplePlayerInput());
        }
        if (simulationTicks > 0) {
            clearConsumedPlayerInput();
        }
        if (!networkSession.isOpen()) {
            LOGGER.error("Disconnected from server: {}", networkSession.closeReason());
            returnToTitleRequested = true;
            sessionStopRequested = true;
        }
        updateNetworkDebugSnapshot();
        updateRenderInteractionTarget();
    }

    private void processGameUiCommands() {
        ClientSettings preview = gameUiState.consumeSettingsPreview();
        if (preview != null) {
            applyRuntimeSettings(preview);
        }
        ClientSettings cancelled = gameUiState.consumeSettingsCancel();
        if (cancelled != null) {
            applyRuntimeSettings(cancelled);
        }
        ClientSettings applied = gameUiState.consumeSettingsApply();
        if (applied != null) {
            ClientSettings previous = clientSettings;
            try {
                window.applyDisplayMode(applied.windowWidth(), applied.windowHeight(), applied.fullscreen());
                applyRuntimeSettings(applied);
                new ClientSettingsRepository(launchOptions.storage().dataDirectory()).save(applied);
                clientSettings = applied;
                gameUiState.settingsApplied(applied);
                gameUiState.setStatusMessage("Paramètres enregistrés / Settings saved");
            } catch (RuntimeException exception) {
                try {
                    window.applyDisplayMode(previous.windowWidth(), previous.windowHeight(), previous.fullscreen());
                } catch (RuntimeException restoreException) {
                    exception.addSuppressed(restoreException);
                }
                applyRuntimeSettings(previous);
                gameUiState.setStatusMessage("Erreur paramètres / Settings error: " + exception.getMessage());
            }
        }
        if (gameUiState.consumeResumeRequest()) {
            setPaused(false);
        }
        if (gameUiState.consumeSaveRequest()) {
            if (multiplayerServer != null) {
                multiplayerServer.requestSave();
                gameUiState.setStatusMessage("Sauvegarde lancée / Saving…");
            } else {
                gameUiState.setStatusMessage("Sauvegarde gérée par le serveur / Server-managed save");
            }
        }
        if (gameUiState.consumeReturnToTitleRequest()) {
            if (multiplayerServer != null) {
                multiplayerServer.requestSave();
            }
            returnToTitleRequested = true;
            sessionStopRequested = true;
        }
    }

    private void applyRuntimeSettings(ClientSettings settings) {
        window.setVsync(settings.vsync());
        if (camera != null) {
            camera.setFov(settings.fieldOfView());
        }
        world.getSettings().renderDistanceChunksRef()[0] = settings.renderDistanceChunks();
        gameplaySession.getSettings().setMouseSensitivity(settings.mouseSensitivity());
        renderer.applyGraphicsPreferences(settings.graphicsPreferences());
        renderer.getContext().setUiScale(settings.uiScale());
        for (InputAction action : InputAction.values()) {
            ClientSettings.BindingSetting stored = settings.bindings().get(action.getId());
            if (stored == null) {
                inputManager.resetBinding(action);
            } else if ("mouse".equalsIgnoreCase(stored.type())) {
                inputManager.bindMouseButton(action, stored.code());
            } else {
                inputManager.bindKey(action, stored.code());
            }
        }
        if (networkSession != null) {
            networkSession.requestViewDistance(settings.renderDistanceChunks());
        }
    }

    private void limitFrameRate(long frameStartNs) {
        ClientSettings effective = gameUiState.getSettingsDraft() == null
                ? clientSettings
                : gameUiState.getSettingsDraft();
        if (launchOptions.benchmarkEnabled() || effective == null || effective.frameRateLimit() <= 0) {
            return;
        }
        long targetNs = 1_000_000_000L / effective.frameRateLimit();
        long remaining = targetNs - (System.nanoTime() - frameStartNs);
        if (remaining > 0L) {
            LockSupport.parkNanos(remaining);
        }
    }

    private void updateLoadProgress(LoadStage stage, float fraction) {
        loadProgress = new LoadProgress(stage, fraction);
    }

    private void checkPreparationCancelled() {
        if (preparationCancelled || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Game session loading cancelled");
        }
    }

    public enum SessionOutcome {
        RETURN_TO_TITLE,
        EXIT_APPLICATION
    }

    public enum LoadStage {
        STARTING,
        REGISTRY,
        SETTINGS,
        PROFILE,
        CONNECTING,
        WORLD,
        SERVER,
        HANDSHAKE,
        READY
    }

    public record LoadProgress(LoadStage stage, float fraction) {
    }

    private void setPaused(boolean paused) {
        if (gameUiState.isPaused() == paused) {
            return;
        }
        if (paused) {
            cursorLockedBeforePause = window.isCursorLocked();
            window.setCursorLocked(false);
        } else {
            if (gameUiState.isSettingsOpen() && gameUiState.getSettingsSnapshot() != null) {
                applyRuntimeSettings(gameUiState.getSettingsSnapshot());
                gameUiState.initializeSettings(clientSettings);
                gameUiState.setSettingsOpen(false);
            }
            window.setCursorLocked(cursorLockedBeforePause);
            inputManager.resetMouseDelta();
        }
        gameUiState.setPaused(paused);
        if (multiplayerServer != null && launchOptions.network().mode() == NetworkMode.SOLO) {
            multiplayerServer.setPaused(paused);
        }
    }

    private void updateNetworkDebugSnapshot() {
        renderer.getContext().setNetworkDebugSnapshot(NetworkDebugSnapshot.from(
                networkSession.getNetworkStats(),
                multiplayerServer == null ? null : multiplayerServer.getNetworkStats()
        ));
    }

    private boolean handleCreativeInventoryInput() {
        boolean togglePressed = inputManager.isActionPressed(InputAction.TOGGLE_INVENTORY);
        boolean escapePressed = inputManager.isActionPressed(InputAction.QUIT);

        if (creativeInventoryState.isOpen() && (togglePressed || escapePressed)) {
            creativeInventoryState.close();
            window.setCursorLocked(cursorLockedBeforeInventory);
            inputManager.resetMouseDelta();
            if (escapePressed) {
                suppressQuitUntilReleased = true;
            }
            return true;
        }

        if (!creativeInventoryState.isOpen() && togglePressed) {
            cursorLockedBeforeInventory = window.isCursorLocked();
            creativeInventoryState.open();
            window.setCursorLocked(false);
            inputManager.resetMouseDelta();
            updateCreativeInventoryPointer();
            return true;
        }

        if (creativeInventoryState.isOpen()) {
            updateCreativeInventoryPointer();
        }
        return false;
    }

    private void updateCreativeInventoryPointer() {
        CreativeInventoryLayout layout = CreativeInventoryLayout.forViewport(
                window.getWidth(),
                window.getHeight(),
                gameUiState.getSettingsDraft() == null
                        ? clientSettings.uiScale()
                        : gameUiState.getSettingsDraft().uiScale(),
                true
        );
        float mouseX = (float) inputManager.getMouseX()
                * window.getWidth() / Math.max(1, window.getLogicalWidth());
        float mouseY = (float) inputManager.getMouseY()
                * window.getHeight() / Math.max(1, window.getLogicalHeight());
        creativeInventoryState.updatePointer(
                layout,
                mouseX,
                mouseY,
                inputManager.getMouseScroll(),
                inputManager.isMouseKeyPressed(GLFW_MOUSE_BUTTON_LEFT),
                inputManager.isMouseKeyDown(GLFW_MOUSE_BUTTON_LEFT),
                inputManager.isMouseKeyReleased(GLFW_MOUSE_BUTTON_LEFT)
        );
    }

    private void handleHotbarKeys() {
        for (int index = 0; index < HOTBAR_ACTIONS.length; index++) {
            if (inputManager.isActionPressed(HOTBAR_ACTIONS[index])) {
                networkSession.selectHotbarSlot(index);
                return;
            }
        }
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
            window.requestClose();
        }
    }

    private void render(float deltaTime) {
        float interpolationAlpha = launchOptions.benchmarkEnabled()
                ? gameServer.getInterpolationAlpha()
                : networkSession.interpolationAlpha();
        syncCameraToPlayer(interpolationAlpha);
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
        world.processLightingFrame();
        if (!worldUpdatesThisFrame.isEmpty()) {
            worldUpdatesThisFrame.replaceLast(world.getLastProfilingSnapshot());
        }
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

    private void applyRuntimeIsolationOptions(World targetWorld) {
        targetWorld.setDynamicLightingEnabled(launchOptions.dynamicLightingEnabled());
        targetWorld.setRemeshEnabled(launchOptions.remeshEnabled());
        targetWorld.setUnloadsEnabled(launchOptions.unloadsEnabled());
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
                nanosToMillis(worldProfilingSnapshot.lightingCpuTimeNs()),
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
                nanosToMillis(passCpuTimeNs(renderStats, "WaterChunkRenderPass")),
                nanosToMillis(passCpuTimeNs(renderStats, "BlockOutlinePass")),
                nanosToMillis(passCpuTimeNs(renderStats, "AntiAliasingPass")),
                nanosToMillis(passCpuTimeNs(renderStats, "ToneMappingPass")),
                nanosToMillis(passCpuTimeNs(renderStats, "HudPass")),
                nanosToMillis(passCpuTimeNs(renderStats, "DebugImGuiPass")),
                nanosToMillis(renderStats.getTotalPassGpuTimeNs()),
                nanosToMillis(passGpuTimeNs(renderStats, "OpaqueChunkRenderPass")),
                nanosToMillis(passGpuTimeNs(renderStats, "CutoutChunkRenderPass")),
                nanosToMillis(passGpuTimeNs(renderStats, "TransparentChunkRenderPass")),
                nanosToMillis(passGpuTimeNs(renderStats, "WaterChunkRenderPass")),
                nanosToMillis(passGpuTimeNs(renderStats, "BlockOutlinePass")),
                nanosToMillis(passGpuTimeNs(renderStats, "AntiAliasingPass")),
                nanosToMillis(passGpuTimeNs(renderStats, "ToneMappingPass")),
                nanosToMillis(passGpuTimeNs(renderStats, "HudPass")),
                nanosToMillis(passGpuTimeNs(renderStats, "DebugImGuiPass")),
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
                nanosToMillis(worldProfilingSnapshot.lightingSnapshotLoadedChunksCpuTimeNs()),
                nanosToMillis(worldProfilingSnapshot.lightingClearCpuTimeNs()),
                nanosToMillis(worldProfilingSnapshot.lightingSeedCpuTimeNs()),
                nanosToMillis(worldProfilingSnapshot.lightingPropagateCpuTimeNs()),
                firstWorldProfilingSnapshot.pendingLightingUpdatesBeforeCollection(),
                worldProfilingSnapshot.pendingLightingUpdatesAfterCollection(),
                worldProfilingSnapshot.lightingBatchSize(),
                worldProfilingSnapshot.lightingAffectedChunkCount(),
                worldProfilingSnapshot.lightingExpandedChunkCount(),
                worldProfilingSnapshot.lightingLoadedChunkCount(),
                worldProfilingSnapshot.lightingLoadedTargetChunkCount(),
                worldProfilingSnapshot.lightingMarkedChunkCount(),
                worldProfilingSnapshot.lightingClearedChunkCount(),
                worldProfilingSnapshot.lightingEmitterCount(),
                worldProfilingSnapshot.lightingSeedNodeCount(),
                worldProfilingSnapshot.lightingPropagationNodeCount(),
                worldProfilingSnapshot.lightingLightWriteCount(),
                worldProfilingSnapshot.lightingBlockedByOpaqueCount(),
                worldProfilingSnapshot.lightingMissingChunkNeighborCount(),
                worldProfilingSnapshot.lightingNoGainCount(),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::lightUploadFullSnapshotCount),
                worldUpdatesThisFrame.sumInt(WorldProfilingSnapshot::lightUploadDeltaCount),
                lightCacheProfilingSnapshot.synchronizeCalls(),
                lightCacheProfilingSnapshot.refreshedAllocationCount(),
                lightCacheProfilingSnapshot.freedAllocationCount(),
                lightCacheProfilingSnapshot.uploadedChunkCount(),
                lightCacheProfilingSnapshot.residentAllocationCount(),
                lightCacheProfilingSnapshot.deferredUploadCount(),
                lightCacheProfilingSnapshot.allocationFailureCount(),
                lightCacheProfilingSnapshot.evictionCount(),
                lightCacheProfilingSnapshot.skippedRetryCount(),
                lightCacheProfilingSnapshot.urgentUploadedChunkCount(),
                lightCacheProfilingSnapshot.backgroundUploadedChunkCount(),
                lightCacheProfilingSnapshot.newVisibleMissingCount(),
                lightCacheProfilingSnapshot.prefetchedUploadedChunkCount(),
                lightCacheProfilingSnapshot.prefetchHitCount(),
                lightCacheProfilingSnapshot.fallbackChunkCount(),
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
                renderer.getContext().getAdaptiveGraphicsQuality().getLevel().name(),
                renderer.getContext().getGraphicsVendor(),
                renderer.getContext().getGraphicsRenderer(),
                renderer.getContext().getGraphicsVersion(),
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

    private static long passGpuTimeNs(RenderStats renderStats, String passName) {
        for (RenderStats.PassStats passStats : renderStats.getPassStats()) {
            if (passName.equals(passStats.getName())) {
                return passStats.getGpuTimeNs();
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
