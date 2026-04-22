package org.weaw.engine.graphics.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weaw.engine.graphics.pipeline.resources.RenderTarget;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL30.GL_RGBA16F;

/**
 * Manages and executes a sequence of render passes.
 *
 * Responsibilities:
 * - Create and manage shared render targets (FBOs/textures)
 * - Execute passes in order
 * - Handle window resize (recreate render targets, notify passes)
 * - Cleanup all resources
 *
 * Usage:
 * <pre>
 * RenderPipeline pipeline = new RenderPipeline(context);
 * pipeline.addPass(new OpaqueGeometryPass());
 * pipeline.addPass(new TransparentPass());
 * pipeline.addPass(new PostProcessPass());
 * pipeline.create();
 *
 * // Each frame:
 * pipeline.execute();
 *
 * // On resize:
 * pipeline.resize(newWidth, newHeight);
 * </pre>
 */
public class RenderPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger(RenderPipeline.class);

    private final RenderContext context;
    private final List<RenderPass> passes = new ArrayList<>();

    /**
     * Create a new render pipeline.
     *
     * @param context Shared rendering context
     */
    public RenderPipeline(RenderContext context) {
        this.context = context;
    }

    /**
     * Add a render pass to the pipeline.
     * Passes are executed in the order they are added.
     *
     * @param pass Render pass to add
     */
    public void addPass(RenderPass pass) {
        passes.add(pass);
        LOGGER.info("Added render pass: {}", pass.getName());
    }

    /**
     * Initialize pipeline: create shared resources and all passes.
     */
    public void create() {
        LOGGER.info("Creating RenderPipeline with {} passes", passes.size());

        createSharedRenderTargets();
        LOGGER.info("GPU Profiler initialized");

        // Initialize all passes
        for (RenderPass pass : passes) {
            LOGGER.info("Creating pass: {}", pass.getName());
            pass.create();
        }

        LOGGER.info("RenderPipeline created successfully");
    }

    /**
     * Execute all render passes in order.
     */
    public void execute() {
        context.getRenderStats().beginFrame(context.getRenderTargets());
        context.resetCurrentColorTarget();
        for (RenderPass pass : passes) {
            long start = System.nanoTime();
            pass.execute(context);
            context.getRenderStats().recordPassCpuTime(pass.getName(), System.nanoTime() - start);
        }
    }

    /**
     * Handle window/viewport resize.
     * Recreates render targets and notifies all passes.
     *
     * @param width New viewport width
     * @param height New viewport height
     */
    public void resize(int width, int height) {
        LOGGER.info("Resizing RenderPipeline: {}x{} -> {}x{}",
                    context.getViewportWidth(), context.getViewportHeight(), width, height);

        // Update context
        context.updateViewport(width, height);

        // Resize shared render targets
        for (RenderTarget target : context.getRenderTargets().values()) {
            target.resize(width, height);
        }

        // Notify all passes
        for (RenderPass pass : passes) {
            pass.resize(width, height);
        }
    }

    /**
     * Cleanup all resources.
     */
    public void cleanup() {
        LOGGER.info("Cleaning up RenderPipeline");

        // Cleanup passes
        for (RenderPass pass : passes) {
            LOGGER.info("Cleaning up pass: {}", pass.getName());
            pass.cleanup();
        }
        passes.clear();

        // Cleanup context (render targets)
        context.cleanup();

        LOGGER.info("RenderPipeline cleanup complete");
    }

    /**
     * Get the render context.
     */
    public RenderContext getContext() {
        return context;
    }

    // ========================================================================
    // Private Methods
    // ========================================================================

    /**
     * Create shared render targets used by multiple passes.
     *
     * Common targets:
     * - "sceneColor": Main scene color output (used by geometry passes)
     * - "sceneDepth": Scene depth buffer (shared for depth testing)
     */
    private void createSharedRenderTargets() {
        int width = context.getViewportWidth();
        int height = context.getViewportHeight();

        // Main scene render target (color + depth)
        // Used by: OpaquePass (write), TransparentPass (read depth, write color), PostProcessPass (read)
        RenderTarget sceneTarget = new RenderTarget("sceneColor", width, height, true, GL_RGBA16F);
        context.setRenderTarget("sceneColor", sceneTarget);

        RenderTarget postProcessTarget = new RenderTarget("postProcessColor", width, height, false, GL_RGBA16F);
        context.setRenderTarget("postProcessColor", postProcessTarget);

        RenderTarget antiAliasTarget = new RenderTarget("antiAliasColor", width, height, false, GL_RGBA16F);
        context.setRenderTarget("antiAliasColor", antiAliasTarget);

        LOGGER.info("Created shared render targets");
    }
}
