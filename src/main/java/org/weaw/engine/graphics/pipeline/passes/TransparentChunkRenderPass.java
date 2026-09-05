package org.weaw.engine.graphics.pipeline.passes;

import org.joml.Vector3f;
import org.weaw.engine.graphics.pipeline.RenderContext;
import org.weaw.engine.graphics.pipeline.resources.GLStateManager;
import org.weaw.engine.graphics.utils.ChunkFaceArena;
import org.weaw.engine.graphics.utils.Shader;
import org.weaw.game.Chunk;
import org.weaw.game.ChunkManager;
import org.weaw.game.ChunkManager.ChunkUpload;
import org.weaw.game.ChunkMeshData.LayerMeshData;

import java.util.Comparator;
import java.util.List;

import static org.lwjgl.opengl.GL11.GL_CCW;

public class TransparentChunkRenderPass extends AbstractChunkLayerPass {
    public TransparentChunkRenderPass(ChunkManager chunkManager) {
        super(chunkManager, "TransparentChunkRenderPass", "/shaders/chunk-cutout.glsl");
    }

    @Override
    protected void configureState(RenderContext context) {
        GLStateManager.setViewport(context.getViewportWidth(), context.getViewportHeight());
        GLStateManager.setDepthTest(true, false);
        GLStateManager.setBlending(true);
        GLStateManager.setCulling(true);
        GLStateManager.setFrontFace(GL_CCW);
    }

    @Override
    protected LayerMeshData selectLayerMesh(ChunkUpload upload) {
        return upload.meshData().transparent();
    }

    @Override
    protected ChunkFaceArena selectArena(RenderContext context) {
        return context.getTransparentChunkFaceArena();
    }

    @Override
    protected void sortVisibleDraws(RenderContext context, List<ChunkRenderEntry> visibleDraws) {
        Vector3f cameraPosition = context.getCamera().getPosition();
        visibleDraws.sort(Comparator.comparingDouble(
                entry -> -distanceSquaredToCamera(entry, cameraPosition.x, cameraPosition.y, cameraPosition.z)
        ));
    }

    private static float distanceSquaredToCamera(ChunkRenderEntry entry, float cameraX, float cameraY, float cameraZ) {
        float centerX = entry.originX() + (Chunk.SIZE * 0.5f);
        float centerY = entry.originY() + (Chunk.SIZE * 0.5f);
        float centerZ = entry.originZ() + (Chunk.SIZE * 0.5f);
        float dx = centerX - cameraX;
        float dy = centerY - cameraY;
        float dz = centerZ - cameraZ;
        return dx * dx + dy * dy + dz * dz;
    }

}
