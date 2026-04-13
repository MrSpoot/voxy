package org.weaw.engine.graphics.pipeline;

import lombok.Getter;
import lombok.Setter;
import org.weaw.engine.graphics.pipeline.resources.RenderTarget;
import org.weaw.engine.graphics.textures.BlockTextureManager;
import org.weaw.engine.graphics.utils.Camera;
import org.weaw.engine.graphics.utils.ChunkFaceArena;
import org.weaw.game.ChunkManager.ChunkPosition;

import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.opengl.GL30C.glDeleteVertexArrays;
import static org.lwjgl.opengl.GL30C.glGenVertexArrays;

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
    private int sharedChunkVao;
    private ChunkFaceArena opaqueChunkFaceArena;
    private ChunkFaceArena cutoutChunkFaceArena;
    private ChunkFaceArena transparentChunkFaceArena;
    private long chunkVisibilityFrameIndex = Long.MIN_VALUE;
    private long chunkVisibilityUploadsVersion = Long.MIN_VALUE;
    private final Set<ChunkPosition> visibleChunkPositions = new HashSet<>();

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

    public void initializeSharedChunkGeometry() {
        if (sharedChunkVao != 0) {
            return;
        }

        sharedChunkVao = glGenVertexArrays();
        opaqueChunkFaceArena = new ChunkFaceArena(sharedChunkVao, 4096);
        cutoutChunkFaceArena = new ChunkFaceArena(sharedChunkVao, 2048);
        transparentChunkFaceArena = new ChunkFaceArena(sharedChunkVao, 2048);
    }

    /**
     * Cleanup all render targets.
     */
    public void cleanup() {
        if (blockTextureManager != null) {
            blockTextureManager.cleanup();
            blockTextureManager = null;
        }
        if (opaqueChunkFaceArena != null) {
            opaqueChunkFaceArena.cleanup();
            opaqueChunkFaceArena = null;
        }
        if (cutoutChunkFaceArena != null) {
            cutoutChunkFaceArena.cleanup();
            cutoutChunkFaceArena = null;
        }
        if (transparentChunkFaceArena != null) {
            transparentChunkFaceArena.cleanup();
            transparentChunkFaceArena = null;
        }
        if (sharedChunkVao != 0) {
            glDeleteVertexArrays(sharedChunkVao);
            sharedChunkVao = 0;
        }
        chunkVisibilityFrameIndex = Long.MIN_VALUE;
        chunkVisibilityUploadsVersion = Long.MIN_VALUE;
        visibleChunkPositions.clear();
        renderTargets.values().forEach(RenderTarget::cleanup);
        renderTargets.clear();
    }
}
