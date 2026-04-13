package org.weaw.engine.graphics.pipeline;

import lombok.Getter;
import lombok.Setter;
import org.weaw.engine.graphics.pipeline.resources.RenderTarget;
import org.weaw.engine.graphics.textures.BlockTextureManager;
import org.weaw.engine.graphics.utils.Camera;

import java.util.HashMap;
import java.util.Map;

/**
 * Shared rendering context passed to all render passes.
 *
 * Contains:
 * - Camera and view parameters
 * - Viewport dimensions
 * - World data (chunks, streamer, mesh manager)
 * - Shared render targets (FBOs/textures)
 *
 * Passes can read from and write to render targets via this context.
 */
@Getter
@Setter
public class RenderContext {

    // Camera and viewport
    private Camera camera;
    private int viewportWidth;
    private int viewportHeight;

    // Shared render targets (managed by RenderPipeline)
    // Key examples: "sceneColor", "sceneDepth", "postProcessOutput"
    private final Map<String, RenderTarget> renderTargets = new HashMap<>();
    private final RenderStats renderStats = new RenderStats();
    private BlockTextureManager blockTextureManager;

    public RenderContext(int viewportWidth, int viewportHeight) {
        this.viewportWidth = viewportWidth;
        this.viewportHeight = viewportHeight;
    }

    /**
     * Get a render target by name.
     *
     * @param name Render target name (e.g., "sceneColor")
     * @return RenderTarget or null if not found
     */
    public RenderTarget getRenderTarget(String name) {
        return renderTargets.get(name);
    }

    /**
     * Register a render target.
     *
     * @param name Render target name
     * @param target RenderTarget instance
     */
    public void setRenderTarget(String name, RenderTarget target) {
        renderTargets.put(name, target);
    }

    /**
     * Update viewport dimensions (called on window resize).
     */
    public void updateViewport(int width, int height) {
        this.viewportWidth = width;
        this.viewportHeight = height;
    }

    /**
     * Cleanup all render targets.
     */
    public void cleanup() {
        if (blockTextureManager != null) {
            blockTextureManager.cleanup();
            blockTextureManager = null;
        }
        renderTargets.values().forEach(RenderTarget::cleanup);
        renderTargets.clear();
    }
}
