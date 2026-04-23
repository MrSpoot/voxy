package org.weaw.engine.graphics.pipeline.passes;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.weaw.engine.graphics.pipeline.LightingSettings;
import org.weaw.engine.graphics.pipeline.RenderContext;
import org.weaw.engine.graphics.pipeline.RenderPass;
import org.weaw.engine.graphics.pipeline.RenderStats.ChunkPassMetrics;
import org.weaw.engine.graphics.pipeline.resources.RenderTarget;
import org.weaw.engine.graphics.textures.BlockTextureManager;
import org.weaw.engine.graphics.utils.ChunkFaceArena;
import org.weaw.engine.graphics.utils.ChunkLightCache;
import org.weaw.engine.graphics.utils.ChunkMultiDrawBatch;
import org.weaw.engine.graphics.utils.Shader;
import org.weaw.game.Chunk;
import org.weaw.game.ChunkManager;
import org.weaw.game.ChunkManager.ChunkUploadChangeType;
import org.weaw.game.ChunkManager.ChunkUploadDelta;
import org.weaw.game.ChunkManager.ChunkUploadSync;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.ChunkManager.ChunkUpload;
import org.weaw.game.ChunkMeshData.LayerMeshData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

abstract class AbstractChunkLayerPass implements RenderPass {
    private static final String CHUNK_DRAW_MODE = System.getProperty("voxy.chunkDrawMode", "indirect");
    private static final boolean USE_MULTI_DRAW = !"legacy".equalsIgnoreCase(CHUNK_DRAW_MODE);
    private static final String DRAW_SUBMISSION_MODE = USE_MULTI_DRAW ? "Indirect" : "Legacy";
    private static final int INITIAL_DRAW_BATCH_CAPACITY = Integer.getInteger("voxy.chunkBatchInitialCapacity", 8192);

    private final ChunkManager chunkManager;
    private final String name;
    private final String shaderPath;
    private final Map<ChunkPosition, ChunkRenderEntry> renderEntries = new LinkedHashMap<>();
    private final List<ChunkRenderEntry> visibleDraws = new ArrayList<>();
    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Matrix4f viewMatrix = new Matrix4f();
    private final Matrix4f viewProjectionMatrix = new Matrix4f();
    private final FrustumIntersection frustumIntersection = new FrustumIntersection();

    private Shader shader;
    private ChunkMultiDrawBatch multiDrawBatch;
    private long synchronizedChunkUploadsVersion = Long.MIN_VALUE;
    private ChunkFaceArena currentArena;
    private int residentMeshCountCache;
    private int residentFaceCountCache;

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
        multiDrawBatch = new ChunkMultiDrawBatch(INITIAL_DRAW_BATCH_CAPACITY);
    }

    @Override
    public final void execute(RenderContext context) {
        boolean requiresLightData = context.isLightDebugVisualizationEnabled()
                || context.getLightingSettings().isBlockLightEnabled();
        ChunkLightCache lightCache = context.getChunkLightCache();
        long lightUploadStartNs = System.nanoTime();
        lightCache.synchronize(requiresLightData);
        long lightUploadCpuTimeNs = System.nanoTime() - lightUploadStartNs;
        long meshUploadStartNs = System.nanoTime();
        synchronizeRenderEntries(context, lightCache);
        long meshUploadCpuTimeNs = System.nanoTime() - meshUploadStartNs;
        long syncCpuTimeNs = lightUploadCpuTimeNs + meshUploadCpuTimeNs;
        long passStartNs = lightUploadStartNs;

        RenderTarget sceneTarget = context.getRenderTarget("sceneColor");
        if (sceneTarget != null) {
            sceneTarget.bind();
        }

        configureState(context);

        long visibilityStartNs = System.nanoTime();
        context.getCamera().getProjectionMatrix(projectionMatrix);
        context.getCamera().getViewMatrix(viewMatrix);
        viewProjectionMatrix.set(projectionMatrix).mul(viewMatrix);
        frustumIntersection.set(viewProjectionMatrix);

        Set<ChunkPosition> visibleChunkPositions = resolveVisibleChunkPositions(context);
        visibleDraws.clear();

        int residentMeshCount = residentMeshCountCache;
        int residentFaceCount = residentFaceCountCache;
        int visibleMeshCount = 0;
        int drawnFaceCount = 0;
        BlockTextureManager textureManager = context.getBlockTextureManager();
        ChunkFaceArena arena = selectArena(context);
        currentArena = arena;

        if (!renderEntries.isEmpty() && !visibleChunkPositions.isEmpty()) {
            for (ChunkPosition position : visibleChunkPositions) {
                ChunkRenderEntry renderEntry = renderEntries.get(position);
                if (renderEntry == null) {
                    continue;
                }

                visibleMeshCount++;
                drawnFaceCount += renderEntry.allocation().faceCount();
                visibleDraws.add(renderEntry);
            }
        }
        sortVisibleDraws(context, visibleDraws);
        int culledMeshCount = Math.max(0, residentMeshCount - visibleMeshCount);
        long visibilityCpuTimeNs = System.nanoTime() - visibilityStartNs;

        long batchUploadStartNs = System.nanoTime();
        multiDrawBatch.upload(visibleDraws);
        long batchUploadCpuTimeNs = System.nanoTime() - batchUploadStartNs;

        shader.useProgram();
        arena.bind();
        lightCache.bind();
        multiDrawBatch.bind();
        textureManager.bind(0);
        shader.setUniform("uBlockTextures", 0);
        shader.setUniform("uProjection", projectionMatrix);
        shader.setUniform("uView", viewMatrix);
        shader.setUniform("uDebugLightVisualizationEnabled", context.isLightDebugVisualizationEnabled() ? 1 : 0);
        shader.setUniform("uBlockLightEnabled", context.getLightingSettings().isBlockLightEnabled() ? 1 : 0);
        shader.setUniform("uBlockLightIntensity", context.getLightingSettings().getBlockLightIntensity());
        setLightingUniforms(context);

        long drawSubmitStartNs = System.nanoTime();
        if (USE_MULTI_DRAW) {
            multiDrawBatch.drawIndirect();
        } else {
            multiDrawBatch.drawLegacy(visibleDraws);
        }
        long drawSubmitCpuTimeNs = System.nanoTime() - drawSubmitStartNs;

        multiDrawBatch.unbind();
        arena.unbind();
        shader.unbind();

        long accountedCpuTimeNs = syncCpuTimeNs + visibilityCpuTimeNs + batchUploadCpuTimeNs + drawSubmitCpuTimeNs;
        long totalChunkPassCpuTimeNs = System.nanoTime() - passStartNs;
        long otherCpuTimeNs = Math.max(0L, totalChunkPassCpuTimeNs - accountedCpuTimeNs);

        int drawCalls = visibleDraws.isEmpty() ? 0 : (USE_MULTI_DRAW ? 1 : visibleDraws.size());
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
                        arena.getEstimatedGpuBytes()
                                + multiDrawBatch.getEstimatedGpuBytes()
                                + (includeSharedLightStats() ? lightCache.getEstimatedGpuBytes() : 0L),
                        DRAW_SUBMISSION_MODE,
                        multiDrawBatch.getActiveDrawCount(),
                        multiDrawBatch.getDrawCapacity(),
                        multiDrawBatch.getEstimatedGpuBytes(),
                        syncCpuTimeNs,
                        meshUploadCpuTimeNs,
                        lightUploadCpuTimeNs,
                        visibilityCpuTimeNs,
                        batchUploadCpuTimeNs,
                        drawSubmitCpuTimeNs,
                        otherCpuTimeNs,
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
        if (currentArena != null) {
            for (ChunkRenderEntry renderEntry : renderEntries.values()) {
                currentArena.free(renderEntry.allocation());
            }
        }
        renderEntries.clear();
        visibleDraws.clear();

        if (multiDrawBatch != null) {
            multiDrawBatch.cleanup();
            multiDrawBatch = null;
        }
        if (shader != null) {
            shader.cleanup();
            shader = null;
        }
    }

    protected abstract void configureState(RenderContext context);

    protected abstract LayerMeshData selectLayerMesh(ChunkUpload upload);

    protected abstract ChunkFaceArena selectArena(RenderContext context);

    protected boolean includeSharedTextureStats() {
        return false;
    }

    protected boolean includeSharedLightStats() {
        return false;
    }

    private void setLightingUniforms(RenderContext context) {
        LightingSettings lighting = context.getLightingSettings();
        shader.setUniform("uLightingEnabled", lighting.isEnabled() ? 1 : 0);
        shader.setUniform("uAmbientColor", lighting.getAmbientRed(), lighting.getAmbientGreen(), lighting.getAmbientBlue());
        shader.setUniform("uAmbientIntensity", lighting.getAmbientIntensity());
        shader.setUniform("uSunColor", lighting.getSunRed(), lighting.getSunGreen(), lighting.getSunBlue());
        shader.setUniform("uSunIntensity", lighting.getSunIntensity());
        shader.setUniform("uSunDirection", lighting.getSunDirectionX(), lighting.getSunDirectionY(), lighting.getSunDirectionZ());
        shader.setUniform("uSkyColor", lighting.getSkyRed(), lighting.getSkyGreen(), lighting.getSkyBlue());
        shader.setUniform("uSkyIntensity", lighting.getSkyIntensity());
    }

    protected void sortVisibleDraws(RenderContext context, List<ChunkRenderEntry> visibleDraws) {
        // Most chunk layers do not need a sort step.
    }

    private void synchronizeRenderEntries(RenderContext context, ChunkLightCache lightCache) {
        long currentVersion = chunkManager.getChunkUploadsVersion();
        if (currentVersion == synchronizedChunkUploadsVersion) {
            return;
        }

        ChunkFaceArena arena = selectArena(context);
        currentArena = arena;
        ChunkUploadSync uploadSync = chunkManager.snapshotChunkUploadSync(synchronizedChunkUploadsVersion);

        if (uploadSync.requiresFullSnapshot()) {
            applyFullUploadSnapshot(uploadSync.fullSnapshot(), arena, lightCache);
        } else {
            for (ChunkUploadDelta delta : uploadSync.deltas()) {
                applyUploadDelta(delta, arena, lightCache);
            }
        }

        recomputeResidentStats();
        synchronizedChunkUploadsVersion = uploadSync.version();
    }

    private void applyFullUploadSnapshot(
            Map<ChunkPosition, ChunkUpload> fullSnapshot,
            ChunkFaceArena arena,
            ChunkLightCache lightCache
    ) {
        renderEntries.entrySet().removeIf(entry -> {
            if (!fullSnapshot.containsKey(entry.getKey())) {
                arena.free(entry.getValue().allocation());
                return true;
            }
            return false;
        });

        for (Map.Entry<ChunkPosition, ChunkUpload> entry : fullSnapshot.entrySet()) {
            upsertRenderEntry(entry.getKey(), entry.getValue(), arena, lightCache);
        }
    }

    private void applyUploadDelta(ChunkUploadDelta delta, ChunkFaceArena arena, ChunkLightCache lightCache) {
        if (delta.changeType() == ChunkUploadChangeType.REMOVED) {
            ChunkRenderEntry removed = renderEntries.remove(delta.position());
            if (removed != null) {
                arena.free(removed.allocation());
            }
            return;
        }

        if (delta.upload() != null) {
            upsertRenderEntry(delta.position(), delta.upload(), arena, lightCache);
        }
    }

    private void upsertRenderEntry(
            ChunkPosition position,
            ChunkUpload upload,
            ChunkFaceArena arena,
            ChunkLightCache lightCache
    ) {
        ChunkRenderEntry existing = renderEntries.get(position);
        LayerMeshData layerMesh = selectLayerMesh(upload);

        if (layerMesh.faceCount() == 0) {
            if (existing != null) {
                arena.free(existing.allocation());
                renderEntries.remove(position);
            }
            return;
        }

        ChunkFaceArena.Allocation allocation = arena.upload(
                layerMesh.faceData(),
                layerMesh.faceCount(),
                existing != null ? existing.allocation() : null
        );
        renderEntries.put(position, ChunkRenderEntry.create(position, upload.chunk(), allocation, lightCache));
    }

    private void recomputeResidentStats() {
        residentMeshCountCache = renderEntries.size();
        residentFaceCountCache = 0;
        for (ChunkRenderEntry renderEntry : renderEntries.values()) {
            residentFaceCountCache += renderEntry.allocation().faceCount();
        }
    }

    private Set<ChunkPosition> resolveVisibleChunkPositions(RenderContext context) {
        long currentUploadsVersion = chunkManager.getChunkUploadsVersion();
        long currentCameraVersion = context.getCamera().getVisibilityVersion();
        if (context.getChunkVisibilityCameraVersion() == currentCameraVersion
                && context.getChunkVisibilityUploadsVersion() == currentUploadsVersion) {
            return context.getVisibleChunkPositions();
        }

        Set<ChunkPosition> visibleChunkPositions = context.getVisibleChunkPositions();
        visibleChunkPositions.clear();

        for (ChunkPosition position : chunkManager.snapshotChunkUploads().keySet()) {
            float minX = position.x() * Chunk.SIZE;
            float minY = position.y() * Chunk.SIZE;
            float minZ = position.z() * Chunk.SIZE;

            if (frustumIntersection.testAab(
                    minX,
                    minY,
                    minZ,
                    minX + Chunk.SIZE,
                    minY + Chunk.SIZE,
                    minZ + Chunk.SIZE
            )) {
                visibleChunkPositions.add(position);
            }
        }

        context.setChunkVisibilityFrameIndex(context.getRenderStats().getFrameIndex());
        context.setChunkVisibilityCameraVersion(currentCameraVersion);
        context.setChunkVisibilityUploadsVersion(currentUploadsVersion);
        return visibleChunkPositions;
    }

    protected record ChunkRenderEntry(
            ChunkPosition position,
            Chunk chunk,
            ChunkFaceArena.Allocation allocation,
            ChunkLightCache lightCache,
            int originX,
            int originY,
            int originZ
    ) implements ChunkMultiDrawBatch.ChunkDrawSource {
        @Override
        public int faceOffsetInts() {
            return allocation.offsetInts();
        }

        @Override
        public int faceCount() {
            return allocation.faceCount();
        }

        @Override
        public int lightOffsetInts() {
            return lightCache != null ? lightCache.getLightOffsetInts(position) : 0;
        }

        private static ChunkRenderEntry create(
                ChunkPosition position,
                Chunk chunk,
                ChunkFaceArena.Allocation allocation,
                ChunkLightCache lightCache
        ) {
            return new ChunkRenderEntry(
                    position,
                    chunk,
                    allocation,
                    lightCache,
                    chunk.getPosition().x * Chunk.SIZE,
                    chunk.getPosition().y * Chunk.SIZE,
                    chunk.getPosition().z * Chunk.SIZE
            );
        }
    }
}
