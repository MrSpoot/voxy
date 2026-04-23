package org.weaw.engine.graphics.pipeline.passes;

import org.weaw.engine.graphics.pipeline.RenderContext;
import org.weaw.engine.graphics.pipeline.resources.GLStateManager;
import org.weaw.engine.graphics.utils.ChunkFaceArena;
import org.weaw.game.ChunkManager;
import org.weaw.game.ChunkManager.ChunkUpload;
import org.weaw.game.ChunkMeshData.LayerMeshData;

import static org.lwjgl.opengl.GL11.GL_CCW;

public class OpaqueChunkRenderPass extends AbstractChunkLayerPass {
    public OpaqueChunkRenderPass(ChunkManager chunkManager) {
        super(chunkManager, "OpaqueChunkRenderPass", "/shaders/chunk-opaque.glsl");
    }

    @Override
    protected void configureState(RenderContext context) {
        var lighting = context.getLightingSettings();
        GLStateManager.setViewport(context.getViewportWidth(), context.getViewportHeight());
        GLStateManager.setDepthTest(true, true);
        GLStateManager.setBlending(false);
        GLStateManager.setCulling(true);
        GLStateManager.setFrontFace(GL_CCW);
        GLStateManager.clear(
                lighting.getSkyRed() * lighting.getSkyIntensity(),
                lighting.getSkyGreen() * lighting.getSkyIntensity(),
                lighting.getSkyBlue() * lighting.getSkyIntensity(),
                1.0f
        );
    }

    @Override
    protected LayerMeshData selectLayerMesh(ChunkUpload upload) {
        return upload.meshData().opaque();
    }

    @Override
    protected ChunkFaceArena selectArena(RenderContext context) {
        return context.getOpaqueChunkFaceArena();
    }

    @Override
    protected boolean includeSharedTextureStats() {
        return true;
    }

    @Override
    protected boolean includeSharedLightStats() {
        return true;
    }
}
