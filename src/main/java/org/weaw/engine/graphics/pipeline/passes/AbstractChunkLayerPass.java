package org.weaw.engine.graphics.pipeline.passes;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

abstract class AbstractChunkLayerPass implements RenderPass {
    private static final String CHUNK_DRAW_MODE = System.getProperty("voxy.chunkDrawMode", "indirect");
    private static final boolean USE_MULTI_DRAW = !"legacy".equalsIgnoreCase(CHUNK_DRAW_MODE);
    private static final String DRAW_SUBMISSION_MODE = USE_MULTI_DRAW ? "Indirect" : "Legacy";
    private static final int INITIAL_DRAW_BATCH_CAPACITY = Integer.getInteger("voxy.chunkBatchInitialCapacity", 8192);
    private static final int LIGHT_PREFETCH_MAX_FOV_DEGREES = Math.clamp(
            Integer.getInteger("voxy.chunkLightPrefetchFovDegrees", 150), 90, 175);
    private static final int LIGHT_PREFETCH_RADIUS_CHUNKS = Math.max(0,
            Integer.getInteger("voxy.chunkLightPrefetchRadiusChunks", 4));
    private static final float LIGHT_PREFETCH_LOOK_AHEAD_SECONDS = Math.max(0.0f,
            Float.parseFloat(System.getProperty("voxy.chunkLightPrefetchLookAheadSeconds", "0.25")));
    private static final float LIGHT_PREFETCH_MAX_YAW_LEAD_DEGREES = Math.max(0.0f,
            Float.parseFloat(System.getProperty("voxy.chunkLightPrefetchMaxYawLeadDegrees", "60")));

    private final ChunkManager chunkManager;
    private final String name;
    private final String shaderPath;
    private final Map<ChunkPosition, ChunkRenderEntry> renderEntries = new LinkedHashMap<>();
    private final List<ChunkRenderEntry> visibleDraws = new ArrayList<>();
    private final Set<ChunkPosition> deferredUploads = new LinkedHashSet<>();
    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Matrix4f viewMatrix = new Matrix4f();
    private final Matrix4f viewProjectionMatrix = new Matrix4f();
    private final Matrix4f lightPrefetchProjectionMatrix = new Matrix4f();
    private final Matrix4f lightPrefetchViewProjectionMatrix = new Matrix4f();
    private final Matrix4f predictedLightViewMatrix = new Matrix4f();
    private final Matrix4f predictedLightViewProjectionMatrix = new Matrix4f();
    private final FrustumIntersection frustumIntersection = new FrustumIntersection();
    private final FrustumIntersection lightPrefetchFrustumIntersection = new FrustumIntersection();
    private final FrustumIntersection predictedLightPrefetchFrustumIntersection = new FrustumIntersection();

    private Shader shader;
    private ChunkMultiDrawBatch multiDrawBatch;
    private long synchronizedChunkUploadsVersion = Long.MIN_VALUE;
    private ChunkFaceArena currentArena;
    private int residentMeshCountCache;
    private int residentFaceCountCache;
    private long lastDeferredRetryCameraVersion = Long.MIN_VALUE;
    private long lastDeferredRetryUploadsVersion = Long.MIN_VALUE;
    private float lastLightPrefetchYaw;
    private float lastLightPrefetchPitch;
    private boolean lightPrefetchPoseInitialized;

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
        long passStartNs = System.nanoTime();
        boolean requiresLightData = context.isLightDebugVisualizationEnabled()
                || context.isVoxelLightDataEnabled();
        ChunkLightCache lightCache = context.getChunkLightCache();
        long meshUploadStartNs = System.nanoTime();
        synchronizeRenderEntries(context, lightCache);
        long meshUploadCpuTimeNs = System.nanoTime() - meshUploadStartNs;

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

        long lightUploadStartNs = System.nanoTime();
        lightCache.prepareFrame(
                requiresLightData,
                visibleChunkPositions,
                context.getLightPrefetchChunkPositions(),
                context.getCamera().getPosition(),
                context.getRenderStats().getFrameIndex()
        );
        long lightUploadCpuTimeNs = System.nanoTime() - lightUploadStartNs;
        long syncCpuTimeNs = lightUploadCpuTimeNs + meshUploadCpuTimeNs;

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
        shader.setUniform("uVoxelLightDataEnabled", context.isVoxelLightDataEnabled() ? 1 : 0);
        shader.setUniform("uBlockLightEnabled", context.getLightingSettings().isBlockLightEnabled() ? 1 : 0);
        shader.setUniform("uBlockLightIntensity", context.getLightingSettings().getBlockLightIntensity());
        setLightingUniforms(context);
        setLayerUniforms(context, shader);

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
        deferredUploads.clear();
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

    protected void setLayerUniforms(RenderContext context, Shader shader) {
        // Most chunk layers do not require layer-specific uniforms.
    }

    private void setLightingUniforms(RenderContext context) {
        LightingSettings lighting = context.getLightingSettings();
        shader.setUniform("uLightingEnabled", lighting.isEnabled() ? 1 : 0);
        shader.setUniform("uAmbientColor", lighting.getAmbientRed(), lighting.getAmbientGreen(), lighting.getAmbientBlue());
        shader.setUniform("uAmbientIntensity", lighting.getAmbientIntensity());
        shader.setUniform("uShadowStrength", lighting.getShadowStrength());
        shader.setUniform("uSunColor", lighting.getSunRed(), lighting.getSunGreen(), lighting.getSunBlue());
        shader.setUniform("uSunIntensity", lighting.getSunIntensity());
        shader.setUniform("uSunDirection", lighting.getSunDirectionX(), lighting.getSunDirectionY(), lighting.getSunDirectionZ());
        shader.setUniform("uSkyColor", lighting.getSkyRed(), lighting.getSkyGreen(), lighting.getSkyBlue());
        shader.setUniform("uSkyIntensity", lighting.getSkyIntensity());
        shader.setUniform("uVoxelLightGamma", lighting.getVoxelLightGamma());
        shader.setUniform("uVoxelDarknessFloor", lighting.getVoxelDarknessFloor());
    }

    protected void sortVisibleDraws(RenderContext context, List<ChunkRenderEntry> visibleDraws) {
        // Most chunk layers do not need a sort step.
    }

    private void synchronizeRenderEntries(RenderContext context, ChunkLightCache lightCache) {
        long currentVersion = chunkManager.getChunkUploadsVersion();
        if (currentVersion == synchronizedChunkUploadsVersion) {
            retryDeferredUploadsIfNeeded(context, lightCache);
            return;
        }

        ChunkFaceArena arena = selectArena(context);
        currentArena = arena;
        ChunkUploadSync uploadSync = chunkManager.snapshotChunkUploadSync(synchronizedChunkUploadsVersion);

        if (uploadSync.requiresFullSnapshot()) {
            applyFullUploadSnapshot(uploadSync.fullSnapshot(), arena, lightCache, context);
        } else {
            for (ChunkUploadDelta delta : uploadSync.deltas()) {
                applyUploadDelta(delta, arena, lightCache, context);
            }
        }

        recomputeResidentStats();
        synchronizedChunkUploadsVersion = uploadSync.version();
        retryDeferredUploadsIfNeeded(context, lightCache);
    }

    private void applyFullUploadSnapshot(
            Map<ChunkPosition, ChunkUpload> fullSnapshot,
            ChunkFaceArena arena,
            ChunkLightCache lightCache,
            RenderContext context
    ) {
        renderEntries.entrySet().removeIf(entry -> {
            if (!fullSnapshot.containsKey(entry.getKey())) {
                arena.free(entry.getValue().allocation());
                deferredUploads.remove(entry.getKey());
                return true;
            }
            return false;
        });

        for (Map.Entry<ChunkPosition, ChunkUpload> entry : fullSnapshot.entrySet()) {
            upsertRenderEntry(entry.getKey(), entry.getValue(), arena, lightCache, context);
        }
    }

    private void applyUploadDelta(
            ChunkUploadDelta delta,
            ChunkFaceArena arena,
            ChunkLightCache lightCache,
            RenderContext context
    ) {
        if (delta.changeType() == ChunkUploadChangeType.REMOVED) {
            deferredUploads.remove(delta.position());
            ChunkRenderEntry removed = renderEntries.remove(delta.position());
            if (removed != null) {
                arena.free(removed.allocation());
            }
            return;
        }

        if (delta.upload() != null) {
            upsertRenderEntry(delta.position(), delta.upload(), arena, lightCache, context);
        }
    }

    private void upsertRenderEntry(
            ChunkPosition position,
            ChunkUpload upload,
            ChunkFaceArena arena,
            ChunkLightCache lightCache,
            RenderContext context
    ) {
        ChunkRenderEntry existing = renderEntries.get(position);
        LayerMeshData layerMesh = selectLayerMesh(upload);

        if (layerMesh.faceCount() == 0) {
            if (existing != null) {
                arena.free(existing.allocation());
                renderEntries.remove(position);
            }
            deferredUploads.remove(position);
            return;
        }

        ChunkFaceArena.Allocation allocation = arena.upload(
                layerMesh.faceData(),
                layerMesh.faceCount(),
                existing != null ? existing.allocation() : null
        );
        if (allocation == null) {
            renderEntries.remove(position);
            allocation = evictFartherChunksAndRetry(position, layerMesh, arena, context);
            if (allocation == null) {
                deferredUploads.add(position);
                return;
            }
        }
        deferredUploads.remove(position);
        renderEntries.put(position, ChunkRenderEntry.create(position, upload.chunk(), allocation, lightCache));
    }

    private ChunkFaceArena.Allocation evictFartherChunksAndRetry(
            ChunkPosition candidatePosition,
            LayerMeshData layerMesh,
            ChunkFaceArena arena,
            RenderContext context
    ) {
        Vector3f cameraPosition = context.getCamera().getPosition();
        double candidateDistance = distanceSquared(candidatePosition, cameraPosition);
        while (!renderEntries.isEmpty()) {
            Map.Entry<ChunkPosition, ChunkRenderEntry> farthest = null;
            double farthestDistance = candidateDistance;
            for (Map.Entry<ChunkPosition, ChunkRenderEntry> entry : renderEntries.entrySet()) {
                double distance = distanceSquared(entry.getKey(), cameraPosition);
                if (distance > farthestDistance) {
                    farthestDistance = distance;
                    farthest = entry;
                }
            }
            if (farthest == null) {
                return null;
            }

            ChunkPosition evictedPosition = farthest.getKey();
            arena.free(farthest.getValue().allocation());
            renderEntries.remove(evictedPosition);
            deferredUploads.add(evictedPosition);

            ChunkFaceArena.Allocation allocation = arena.upload(layerMesh.faceData(), layerMesh.faceCount(), null);
            if (allocation != null) {
                return allocation;
            }
        }
        return null;
    }

    private void retryDeferredUploadsIfNeeded(RenderContext context, ChunkLightCache lightCache) {
        long cameraVersion = context.getCamera().getVisibilityVersion();
        long uploadsVersion = chunkManager.getChunkUploadsVersion();
        if (deferredUploads.isEmpty()
                || (cameraVersion == lastDeferredRetryCameraVersion
                && uploadsVersion == lastDeferredRetryUploadsVersion)) {
            return;
        }
        lastDeferredRetryCameraVersion = cameraVersion;
        lastDeferredRetryUploadsVersion = uploadsVersion;

        List<ChunkPosition> retryPositions = new ArrayList<>(deferredUploads);
        Vector3f cameraPosition = context.getCamera().getPosition();
        retryPositions.sort(java.util.Comparator.comparingDouble(position -> distanceSquared(position, cameraPosition)));
        ChunkFaceArena arena = selectArena(context);
        for (ChunkPosition position : retryPositions) {
            ChunkUpload upload = chunkManager.getChunkUpload(position);
            if (upload == null) {
                deferredUploads.remove(position);
                continue;
            }
            upsertRenderEntry(position, upload, arena, lightCache, context);
        }
        recomputeResidentStats();
    }

    private static double distanceSquared(ChunkPosition position, Vector3f cameraPosition) {
        double centerX = ((double) position.x() + 0.5) * Chunk.SIZE;
        double centerY = ((double) position.y() + 0.5) * Chunk.SIZE;
        double centerZ = ((double) position.z() + 0.5) * Chunk.SIZE;
        double dx = centerX - cameraPosition.x;
        double dy = centerY - cameraPosition.y;
        double dz = centerZ - cameraPosition.z;
        return dx * dx + dy * dy + dz * dz;
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
        Set<ChunkPosition> prefetchChunkPositions = context.getLightPrefetchChunkPositions();
        visibleChunkPositions.clear();
        prefetchChunkPositions.clear();

        float prefetchFov = Math.min(context.getCamera().getFov() + 60.0f, LIGHT_PREFETCH_MAX_FOV_DEGREES);
        lightPrefetchProjectionMatrix.identity().perspective(
                (float) Math.toRadians(prefetchFov),
                context.getCamera().getAspectRatio(),
                context.getCamera().getFar(),
                context.getCamera().getNear(),
                true
        );
        lightPrefetchViewProjectionMatrix.set(lightPrefetchProjectionMatrix).mul(viewMatrix);
        lightPrefetchFrustumIntersection.set(lightPrefetchViewProjectionMatrix);
        Vector3f cameraPosition = context.getCamera().getPosition();
        float cameraYaw = context.getCamera().getYaw();
        float cameraPitch = context.getCamera().getPitch();
        float predictedYaw = cameraYaw;
        float predictedPitch = cameraPitch;
        if (lightPrefetchPoseInitialized) {
            float deltaSeconds = Math.clamp(context.getFrameDeltaSeconds(), 1.0f / 240.0f, 0.1f);
            float yawVelocity = wrapDegrees(cameraYaw - lastLightPrefetchYaw) / deltaSeconds;
            float pitchVelocity = (cameraPitch - lastLightPrefetchPitch) / deltaSeconds;
            predictedYaw += Math.clamp(
                    yawVelocity * LIGHT_PREFETCH_LOOK_AHEAD_SECONDS,
                    -LIGHT_PREFETCH_MAX_YAW_LEAD_DEGREES,
                    LIGHT_PREFETCH_MAX_YAW_LEAD_DEGREES
            );
            predictedPitch = Math.clamp(
                    predictedPitch + pitchVelocity * LIGHT_PREFETCH_LOOK_AHEAD_SECONDS,
                    -89.0f,
                    89.0f
            );
        }
        setViewMatrix(predictedLightViewMatrix, cameraPosition, predictedYaw, predictedPitch);
        predictedLightViewProjectionMatrix.set(lightPrefetchProjectionMatrix).mul(predictedLightViewMatrix);
        predictedLightPrefetchFrustumIntersection.set(predictedLightViewProjectionMatrix);
        lastLightPrefetchYaw = cameraYaw;
        lastLightPrefetchPitch = cameraPitch;
        lightPrefetchPoseInitialized = true;
        int cameraChunkX = (int) Math.floor(cameraPosition.x / Chunk.SIZE);
        int cameraChunkY = (int) Math.floor(cameraPosition.y / Chunk.SIZE);
        int cameraChunkZ = (int) Math.floor(cameraPosition.z / Chunk.SIZE);
        int prefetchRadiusSquared = LIGHT_PREFETCH_RADIUS_CHUNKS * LIGHT_PREFETCH_RADIUS_CHUNKS;

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
                prefetchChunkPositions.add(position);
                continue;
            }

            int chunkDx = position.x() - cameraChunkX;
            int chunkDy = position.y() - cameraChunkY;
            int chunkDz = position.z() - cameraChunkZ;
            boolean insideNearPrefetchSphere = chunkDx * chunkDx + chunkDy * chunkDy + chunkDz * chunkDz
                    <= prefetchRadiusSquared;
            if (insideNearPrefetchSphere || lightPrefetchFrustumIntersection.testAab(
                    minX,
                    minY,
                    minZ,
                    minX + Chunk.SIZE,
                    minY + Chunk.SIZE,
                    minZ + Chunk.SIZE
            ) || predictedLightPrefetchFrustumIntersection.testAab(
                    minX,
                    minY,
                    minZ,
                    minX + Chunk.SIZE,
                    minY + Chunk.SIZE,
                    minZ + Chunk.SIZE
            )) {
                prefetchChunkPositions.add(position);
            }
        }

        context.setChunkVisibilityFrameIndex(context.getRenderStats().getFrameIndex());
        context.setChunkVisibilityCameraVersion(currentCameraVersion);
        context.setChunkVisibilityUploadsVersion(currentUploadsVersion);
        return visibleChunkPositions;
    }

    private static void setViewMatrix(
            Matrix4f destination,
            Vector3f position,
            float yawDegrees,
            float pitchDegrees
    ) {
        float yaw = (float) Math.toRadians(yawDegrees);
        float pitch = (float) Math.toRadians(pitchDegrees);
        float cosPitch = (float) Math.cos(pitch);
        float forwardX = (float) Math.cos(yaw) * cosPitch;
        float forwardY = (float) Math.sin(pitch);
        float forwardZ = (float) Math.sin(yaw) * cosPitch;
        destination.identity().lookAt(
                position.x,
                position.y,
                position.z,
                position.x + forwardX,
                position.y + forwardY,
                position.z + forwardZ,
                0.0f,
                1.0f,
                0.0f
        );
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360.0f;
        if (wrapped > 180.0f) {
            return wrapped - 360.0f;
        }
        if (wrapped < -180.0f) {
            return wrapped + 360.0f;
        }
        return wrapped;
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
