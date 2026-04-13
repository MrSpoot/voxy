package org.weaw.engine.graphics.pipeline.passes;

import org.weaw.engine.graphics.pipeline.RenderContext;
import org.weaw.engine.graphics.pipeline.resources.GLStateManager;
import org.weaw.game.ChunkManager;
import org.weaw.game.ChunkManager.ChunkUpload;
import org.weaw.game.ChunkMeshData.LayerMeshData;

import static org.lwjgl.opengl.GL11.GL_CCW;

public class CutoutChunkRenderPass extends AbstractChunkLayerPass {
    public CutoutChunkRenderPass(ChunkManager chunkManager) {
        super(chunkManager, "CutoutChunkRenderPass", "/shaders/chunk-cutout.glsl");
    }

    @Override
    protected void configureState(RenderContext context) {
        GLStateManager.setViewport(context.getViewportWidth(), context.getViewportHeight());
        GLStateManager.setDepthTest(true, true);
        GLStateManager.setBlending(false);
        GLStateManager.setCulling(true);
        GLStateManager.setFrontFace(GL_CCW);
    }

    @Override
    protected LayerMeshData selectLayerMesh(ChunkUpload upload) {
        return upload.meshData().cutout();
    }
}
