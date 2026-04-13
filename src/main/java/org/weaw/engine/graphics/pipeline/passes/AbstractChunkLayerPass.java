package org.weaw.engine.graphics.pipeline.passes;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.weaw.engine.graphics.pipeline.RenderContext;
import org.weaw.engine.graphics.pipeline.RenderPass;
import org.weaw.engine.graphics.pipeline.RenderStats.ChunkPassMetrics;
import org.weaw.engine.graphics.textures.BlockTextureManager;
import org.weaw.engine.graphics.utils.Mesh;
import org.weaw.engine.graphics.utils.Shader;
import org.weaw.game.Chunk;
import org.weaw.game.ChunkManager;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.ChunkManager.ChunkUpload;
import org.weaw.game.ChunkMeshData.LayerMeshData;

import java.util.LinkedHashMap;
import java.util.Map;

abstract class AbstractChunkLayerPass implements RenderPass {
    private final ChunkManager chunkManager;
    private final String name;
    private final String shaderPath;
    private final Map<ChunkPosition, ChunkRenderEntry> renderEntries = new LinkedHashMap<>();
    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Matrix4f viewMatrix = new Matrix4f();
    private final Matrix4f viewProjectionMatrix = new Matrix4f();
    private final FrustumIntersection frustumIntersection = new FrustumIntersection();

    private Shader shader;
    private long synchronizedChunkUploadsVersion = Long.MIN_VALUE;

    protected AbstractChunkLayerPass(ChunkManager chunkManager, String name, String shaderPath) {
        this.chunkManager = chunkManager;
        this.name = name;
        this.shaderPath = shaderPath;
    }

    @Override
    public final String getName() {
        return name;
    }

    @Override
    public final void create() {
        shader = new Shader(shaderPath);
    }

    @Override
    public final void execute(RenderContext context) {
        synchronizeRenderEntries();
        configureState(context);
        context.getCamera().getProjectionMatrix(projectionMatrix);
        context.getCamera().getViewMatrix(viewMatrix);
        viewProjectionMatrix.set(projectionMatrix).mul(viewMatrix);
        frustumIntersection.set(viewProjectionMatrix);
        int residentMeshCount = 0;
        int residentFaceCount = 0;
        int visibleMeshCount = 0;
        int culledMeshCount = 0;
        int drawCalls = 0;
        int drawnFaceCount = 0;
        long meshGpuBytes = 0L;
        BlockTextureManager textureManager = context.getBlockTextureManager();

        shader.useProgram();
        textureManager.bind(0);
        shader.setUniform("uBlockTextures", 0);
        shader.setUniform("uProjection", projectionMatrix);
        shader.setUniform("uView", viewMatrix);

        for (ChunkRenderEntry renderEntry : renderEntries.values()) {
            Mesh mesh = renderEntry.mesh();
            if (mesh != null) {
                residentMeshCount++;
                residentFaceCount += mesh.getInstanceCount();
                meshGpuBytes += mesh.getEstimatedGpuBytes();
            }

            if (!isChunkVisible(renderEntry, frustumIntersection)) {
                culledMeshCount++;
                continue;
            }

            shader.setUniform("uModel", renderEntry.modelMatrix());

            if (mesh != null) {
                visibleMeshCount++;
                if (mesh.getInstanceCount() > 0) {
                    drawCalls++;
                    drawnFaceCount += mesh.getInstanceCount();
                }
                mesh.render();
            }
        }

        shader.unbind();

        context.getRenderStats().recordChunkPass(
                getName(),
                new ChunkPassMetrics(
                        residentMeshCount,
                        residentFaceCount,
                        visibleMeshCount,
                        culledMeshCount,
                        drawCalls,
                        drawnFaceCount,
                        drawnFaceCount * 2,
                        drawnFaceCount * 4,
                        meshGpuBytes,
                        includeSharedTextureStats() && textureManager != null && textureManager.getTextureArrayId() != 0 ? 1 : 0,
                        includeSharedTextureStats() && textureManager != null ? textureManager.getEstimatedGpuBytes() : 0L
                )
        );
    }

    @Override
    public final void resize(int width, int height) {
        // No pass-local resources to resize for now.
    }

    @Override
    public final void cleanup() {
        for (ChunkRenderEntry renderEntry : renderEntries.values()) {
            if (renderEntry.mesh() != null) {
                renderEntry.mesh().cleanup();
            }
        }
        renderEntries.clear();

        if (shader != null) {
            shader.cleanup();
            shader = null;
        }
    }

    protected abstract void configureState(RenderContext context);

    protected abstract LayerMeshData selectLayerMesh(ChunkUpload upload);

    protected boolean includeSharedTextureStats() {
        return false;
    }

    private void synchronizeRenderEntries() {
        long currentVersion = chunkManager.getChunkUploadsVersion();
        if (currentVersion == synchronizedChunkUploadsVersion) {
            return;
        }

        Map<ChunkPosition, ChunkUpload> currentUploads = chunkManager.snapshotChunkUploads();

        renderEntries.entrySet().removeIf(entry -> {
            if (!currentUploads.containsKey(entry.getKey())) {
                if (entry.getValue().mesh() != null) {
                    entry.getValue().mesh().cleanup();
                }
                return true;
            }
            return false;
        });

        for (Map.Entry<ChunkPosition, ChunkUpload> entry : currentUploads.entrySet()) {
            ChunkRenderEntry existing = renderEntries.get(entry.getKey());
            if (existing != null && existing.chunk() == entry.getValue().chunk()) {
                continue;
            }

            LayerMeshData layerMesh = selectLayerMesh(entry.getValue());
            if (existing != null && existing.mesh() != null) {
                existing.mesh().update(layerMesh.faceData(), layerMesh.faceCount());
                renderEntries.put(entry.getKey(), existing.withChunk(entry.getValue().chunk()));
                continue;
            }

            Mesh mesh = new Mesh(layerMesh.faceData(), layerMesh.faceCount());
            renderEntries.put(entry.getKey(), ChunkRenderEntry.create(entry.getValue().chunk(), mesh));
        }

        synchronizedChunkUploadsVersion = currentVersion;
    }

    private boolean isChunkVisible(ChunkRenderEntry renderEntry, FrustumIntersection frustumIntersection) {
        return frustumIntersection.testAab(
                renderEntry.minX(),
                renderEntry.minY(),
                renderEntry.minZ(),
                renderEntry.maxX(),
                renderEntry.maxY(),
                renderEntry.maxZ()
        );
    }

    private record ChunkRenderEntry(
            Chunk chunk,
            Mesh mesh,
            Matrix4f modelMatrix,
            float minX,
            float minY,
            float minZ,
            float maxX,
            float maxY,
            float maxZ
    ) {
        private static ChunkRenderEntry create(Chunk chunk, Mesh mesh) {
            float minX = chunk.getPosition().x * Chunk.SIZE;
            float minY = chunk.getPosition().y * Chunk.SIZE;
            float minZ = chunk.getPosition().z * Chunk.SIZE;
            return new ChunkRenderEntry(
                    chunk,
                    mesh,
                    new Matrix4f().translation(minX, minY, minZ),
                    minX,
                    minY,
                    minZ,
                    minX + Chunk.SIZE,
                    minY + Chunk.SIZE,
                    minZ + Chunk.SIZE
            );
        }

        private ChunkRenderEntry withChunk(Chunk chunk) {
            return new ChunkRenderEntry(
                    chunk,
                    mesh,
                    modelMatrix,
                    minX,
                    minY,
                    minZ,
                    maxX,
                    maxY,
                    maxZ
            );
        }
    }
}
