package org.weaw;

import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weaw.engine.graphics.Renderer;
import org.weaw.engine.graphics.utils.Camera;
import org.weaw.engine.input.InputAction;
import org.weaw.engine.input.InputManager;
import org.weaw.engine.window.Window;
import org.weaw.game.World;
import org.weaw.game.generation.GenerationConfig;
import org.weaw.game.generation.NoiseWorldGenerator;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.gameplay.GameplaySession;
import org.weaw.gameplay.GameplaySettings;
import org.weaw.gameplay.TargetedBlock;

import static org.lwjgl.opengl.GL11.GL_FILL;
import static org.lwjgl.opengl.GL11.GL_FRONT_AND_BACK;
import static org.lwjgl.opengl.GL11.GL_LINE;
import static org.lwjgl.opengl.GL11.glPolygonMode;

public class Game {
    private static final Logger LOGGER = LoggerFactory.getLogger(Game.class);

    private Window window;
    private InputManager inputManager;

    private Renderer renderer;
    private Camera camera;
    private World world;
    private GameplaySession gameplaySession;

    private double lastTime;

    private boolean wireframe = false;

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

        window = new Window("Voxy", 1920, 1080 );
        window.create();

        inputManager = new InputManager(window.getId());
        inputManager.create();

        world = createTestWorld();
        gameplaySession = new GameplaySession(world, new GameplaySettings());
        gameplaySession.setPlayerPosition(new Vector3f(16.0f, 12.0f, 48.0f));

        renderer = new Renderer(window, world, inputManager, BlockRegistry.getRegisteredBlocks().values());
        renderer.create();

        // Connect renderer to window for resize notifications
        window.setRenderer(renderer);

        camera = new Camera(90f,window.aspectRatio());
        syncCameraToPlayer();

        lastTime = System.nanoTime() / 1_000_000_000.0; // secondes
    }

    private void loop() {
        LOGGER.info("Starting game loop");

        while (!window.shouldClose()) {
            long frameStartNs = System.nanoTime();
            double now = System.nanoTime() / 1_000_000_000.0;
            float deltaTime = (float)(now - lastTime);
            lastTime = now;

            long updateStartNs = System.nanoTime();
            update(deltaTime);
            long updateCpuTimeNs = System.nanoTime() - updateStartNs;

            long renderStartNs = System.nanoTime();
            render();
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
//            long end = System.nanoTime();
//            if((end - start) / 1_000_000.0 > 10.0){
//                LOGGER.warn("Game loop took too long: {} ms",(end - start) / 1_000_000.0);
//                LOGGER.warn("Memory usage: {} MB on {} MB", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_000_000.0, Runtime.getRuntime().totalMemory() / 1_000_000.0);
//            }
        }
    }

    private void cleanup() {
        safeCleanup("Renderer", () -> {
            if (renderer != null) {
                renderer.cleanup();
                renderer = null;
            }
        });
        safeCleanup("World", () -> {
            if (world != null) {
                world.close();
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
        new Game().run();
    }

    private World createTestWorld() {
        return new World(new NoiseWorldGenerator(GenerationConfig.defaults()));
    }

    private void handleInputModes() {
        if (inputManager.isActionPressed(InputAction.TOGGLE_MOUSE_LOCK)) {
            window.toggleCursorLock();
            inputManager.resetMouseDelta();
        }
    }

    private void update(float deltaTime) {
        inputManager.update();
        handleInputModes();

        if (inputManager.isActionDown(InputAction.QUIT)) {
            window.close();
        }

        if (inputManager.isActionPressed(InputAction.TOGGLE_WIREFRAME)) {
            glPolygonMode(GL_FRONT_AND_BACK, wireframe ? GL_FILL : GL_LINE);
            wireframe = !wireframe;
        }

        gameplaySession.update(deltaTime, inputManager, window.isCursorLocked());
        updateRenderInteractionTarget();
        syncCameraToPlayer();
        camera.setAspectRatio(window.aspectRatio());
    }

    private void render() {
        renderer.render(camera);
    }

    private void syncCameraToPlayer() {
        camera.setPose(
                gameplaySession.getPlayer().getPosition(),
                gameplaySession.getPlayer().getYaw(),
                gameplaySession.getPlayer().getPitch()
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
                targetedBlock.blockZ()
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
}
