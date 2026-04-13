package org.weaw.engine.graphics.pipeline;

/**
 * Represents a single rendering pass in a multi-pass pipeline.
 *
 * Each pass can:
 * - Declare required inputs (previous pass outputs)
 * - Configure GL state (depth test, blending, etc.)
 * - Render geometry or run post-processing
 * - Produce outputs (color/depth textures)
 *
 * Passes are executed in order by RenderPipeline.
 */
public interface RenderPass {

    /**
     * Get the name of this pass (for debugging/logging).
     */
    String getName();

    /**
     * Initialize pass resources (shaders, buffers, etc.).
     * Called once during pipeline setup.
     */
    void create();

    /**
     * Execute this rendering pass.
     *
     * @param context Shared rendering context (camera, resources, world data)
     */
    void execute(RenderContext context);

    /**
     * Handle window/framebuffer resize.
     *
     * @param width New viewport width
     * @param height New viewport height
     */
    void resize(int width, int height);

    /**
     * Cleanup GPU resources (shaders, FBOs, etc.).
     * Called during shutdown.
     */
    void cleanup();
}
