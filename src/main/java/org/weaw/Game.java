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
import org.weaw.game.utils.BlockRegistry;

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

    private double lastTime;

    private boolean wireframe = false;

    public void run() {
        init();
        loop();
        cleanup();
    }

    public void init(){
        LOGGER.info("Initializing");
        BlockRegistry.initialize();

        window = new Window("Voxy", 1280, 720 );
        window.create();

        inputManager = new InputManager(window.getId());
        inputManager.create();

        world = createTestWorld();

        renderer = new Renderer(window, world, inputManager);
        renderer.create();

        // Connect renderer to window for resize notifications
        window.setRenderer(renderer);

        camera = new Camera(90f,window.aspectRatio());
        camera.setPosition(new Vector3f(16.0f, 12.0f, 48.0f));

        lastTime = System.nanoTime() / 1_000_000_000.0; // secondes
    }

    private void loop() {
        LOGGER.info("Starting game loop");

        while (!window.shouldClose()) {
            double now = System.nanoTime() / 1_000_000_000.0;
            float deltaTime = (float)(now - lastTime);
            lastTime = now;

            update(deltaTime);
            render();

            window.update();
//            long end = System.nanoTime();
//            if((end - start) / 1_000_000.0 > 10.0){
//                LOGGER.warn("Game loop took too long: {} ms",(end - start) / 1_000_000.0);
//                LOGGER.warn("Memory usage: {} MB on {} MB", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1_000_000.0, Runtime.getRuntime().totalMemory() / 1_000_000.0);
//            }
        }
    }

    private void cleanup() {
        LOGGER.info("Cleanup Renderer");
        renderer.cleanup();
        LOGGER.info("Cleanup World");
        if (world != null) {
            world.close();
            world = null;
        }
        LOGGER.info("Cleanup Input Manager");
        inputManager.cleanup();
        LOGGER.info("Cleanup windows");
        window.cleanup();
    }

    public static void main(String[] args) {
        new Game().run();
    }

    private World createTestWorld() {
        return new World();
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

        if (window.isCursorLocked()) {
            camera.update(deltaTime, inputManager);
        }
        camera.setAspectRatio(window.aspectRatio());

        world.update(camera.getPosition());
    }

    private void render() {
        renderer.render(camera);
    }
}
