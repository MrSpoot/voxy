package org.weaw.engine.graphics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weaw.engine.graphics.pipeline.RenderContext;
import org.weaw.engine.graphics.pipeline.RenderPipeline;
import org.weaw.engine.graphics.pipeline.passes.CutoutChunkRenderPass;
import org.weaw.engine.graphics.pipeline.passes.DebugImGuiPass;
import org.weaw.engine.graphics.pipeline.passes.OpaqueChunkRenderPass;
import org.weaw.engine.graphics.textures.BlockTextureManager;
import org.weaw.engine.graphics.utils.Camera;
import org.weaw.engine.input.InputManager;
import org.weaw.engine.window.Window;
import org.weaw.game.World;
import org.weaw.game.WorldStreamer;

public class Renderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(Renderer.class);

    private final Window window;
    private final World world;
    private final InputManager inputManager;
    private WorldStreamer worldStreamer;
    // Multi-pass rendering pipeline
    private RenderPipeline pipeline;
    private RenderContext context;

    public Renderer(Window window, World world, InputManager inputManager) {
        this.window = window;
        this.world = world;
        this.inputManager = inputManager;
    }

    public void create(){
        LOGGER.info("Create Renderer with multi-pass pipeline");

        // Create render context (shared state for all passes)
        context = new RenderContext(window.getWidth(), window.getHeight());
        BlockTextureManager blockTextureManager = new BlockTextureManager();
        blockTextureManager.create();
        context.setBlockTextureManager(blockTextureManager);
        context.initializeSharedChunkGeometry();
        worldStreamer = new WorldStreamer(world.getChunkManager());

        // Create and configure render pipeline
        pipeline = new RenderPipeline(context);
        pipeline.addPass(new OpaqueChunkRenderPass(world.getChunkManager()));
        pipeline.addPass(new CutoutChunkRenderPass(world.getChunkManager()));
        pipeline.addPass(new DebugImGuiPass(window, inputManager));

        pipeline.create();

        LOGGER.info("Multi-pass renderer created successfully");
    }

    /**
     * Render a frame using the multi-pass pipeline.
     * All rendering logic is now delegated to individual passes.
     */
    public void render(Camera camera) {
        // Update camera in context
        context.setCamera(camera);
        worldStreamer.update(camera.getPosition());

        // Execute all passes in sequence
        pipeline.execute();
    }

    /**
     * Handle window resize - recreates FBOs and notifies all passes.
     * Call this when window dimensions change.
     */
    public void resize(int width, int height) {
        LOGGER.info("Renderer resize: {}x{}", width, height);
        pipeline.resize(width, height);
    }

    public void cleanup(){
        LOGGER.info("Cleaning up Renderer");

        // Cleanup rendering pipeline (cleans up all passes and FBOs)
        if (pipeline != null) {
            pipeline.cleanup();
        }
        if (worldStreamer != null) {
            worldStreamer.close();
            worldStreamer = null;
        }

        LOGGER.info("Renderer cleanup complete");
    }
}
