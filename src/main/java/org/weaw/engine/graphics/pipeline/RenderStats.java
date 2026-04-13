package org.weaw.engine.graphics.pipeline;

import lombok.Getter;
import org.weaw.engine.graphics.pipeline.resources.RenderTarget;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Getter
public class RenderStats {
    private long frameIndex;
    private long totalPassCpuTimeNs;

    private int drawCalls;
    private int residentMeshCount;
    private int residentFaceCount;
    private int visibleMeshCount;
    private int culledMeshCount;
    private int drawnFaceCount;
    private int drawnTriangleCount;
    private int drawnVertexCount;

    private int textureArrayCount;
    private int chunkDrawBatchCount;
    private int indirectVisibleDrawCount;
    private int indirectDrawCapacity;
    private int renderTargetCount;
    private int renderTargetColorTextureCount;
    private int renderTargetDepthTextureCount;

    private long meshGpuBytes;
    private long indirectBufferGpuBytes;
    private long textureGpuBytes;
    private long renderTargetGpuBytes;

    private long jvmHeapUsedBytes;
    private long jvmHeapCommittedBytes;
    private long jvmHeapMaxBytes;

    private final Map<String, PassStats> passStats = new LinkedHashMap<>();

    public void beginFrame(Map<String, RenderTarget> renderTargets) {
        frameIndex++;
        totalPassCpuTimeNs = 0L;

        drawCalls = 0;
        residentMeshCount = 0;
        residentFaceCount = 0;
        visibleMeshCount = 0;
        culledMeshCount = 0;
        drawnFaceCount = 0;
        drawnTriangleCount = 0;
        drawnVertexCount = 0;

        textureArrayCount = 0;
        chunkDrawBatchCount = 0;
        indirectVisibleDrawCount = 0;
        indirectDrawCapacity = 0;
        meshGpuBytes = 0L;
        indirectBufferGpuBytes = 0L;
        textureGpuBytes = 0L;

        renderTargetCount = 0;
        renderTargetColorTextureCount = 0;
        renderTargetDepthTextureCount = 0;
        renderTargetGpuBytes = 0L;

        for (PassStats stats : passStats.values()) {
            stats.resetFrameData();
        }

        for (RenderTarget renderTarget : renderTargets.values()) {
            renderTargetCount++;
            renderTargetColorTextureCount++;
            if (renderTarget.hasDepth()) {
                renderTargetDepthTextureCount++;
            }
            renderTargetGpuBytes += renderTarget.estimateTotalGpuBytes();
        }

        Runtime runtime = Runtime.getRuntime();
        jvmHeapCommittedBytes = runtime.totalMemory();
        jvmHeapUsedBytes = jvmHeapCommittedBytes - runtime.freeMemory();
        jvmHeapMaxBytes = runtime.maxMemory();
    }

    public void recordPassCpuTime(String passName, long cpuTimeNs) {
        PassStats stats = getOrCreatePassStats(passName);
        stats.cpuTimeNs = cpuTimeNs;
        totalPassCpuTimeNs += cpuTimeNs;
    }

    public void recordChunkPass(String passName, ChunkPassMetrics metrics) {
        PassStats stats = getOrCreatePassStats(passName);
        stats.residentMeshCount = metrics.residentMeshCount();
        stats.residentFaceCount = metrics.residentFaceCount();
        stats.visibleMeshCount = metrics.visibleMeshCount();
        stats.culledMeshCount = metrics.culledMeshCount();
        stats.drawCalls = metrics.drawCalls();
        stats.drawnFaceCount = metrics.drawnFaceCount();
        stats.drawnTriangleCount = metrics.drawnTriangleCount();
        stats.drawnVertexCount = metrics.drawnVertexCount();
        stats.meshGpuBytes = metrics.meshGpuBytes();
        stats.submissionMode = metrics.submissionMode();
        stats.indirectVisibleDrawCount = metrics.indirectVisibleDrawCount();
        stats.indirectDrawCapacity = metrics.indirectDrawCapacity();
        stats.indirectBufferGpuBytes = metrics.indirectBufferGpuBytes();
        stats.syncCpuTimeNs = metrics.syncCpuTimeNs();
        stats.visibilityCpuTimeNs = metrics.visibilityCpuTimeNs();
        stats.batchUploadCpuTimeNs = metrics.batchUploadCpuTimeNs();
        stats.drawSubmitCpuTimeNs = metrics.drawSubmitCpuTimeNs();
        stats.textureArrayCount = metrics.textureArrayCount();
        stats.textureGpuBytes = metrics.textureGpuBytes();

        residentMeshCount += metrics.residentMeshCount();
        residentFaceCount += metrics.residentFaceCount();
        visibleMeshCount += metrics.visibleMeshCount();
        culledMeshCount += metrics.culledMeshCount();
        drawCalls += metrics.drawCalls();
        drawnFaceCount += metrics.drawnFaceCount();
        drawnTriangleCount += metrics.drawnTriangleCount();
        drawnVertexCount += metrics.drawnVertexCount();
        meshGpuBytes += metrics.meshGpuBytes();
        chunkDrawBatchCount++;
        indirectVisibleDrawCount += metrics.indirectVisibleDrawCount();
        indirectDrawCapacity += metrics.indirectDrawCapacity();
        indirectBufferGpuBytes += metrics.indirectBufferGpuBytes();
        textureArrayCount += metrics.textureArrayCount();
        textureGpuBytes += metrics.textureGpuBytes();
    }

    public long getTotalEstimatedGpuBytes() {
        return meshGpuBytes + textureGpuBytes + renderTargetGpuBytes;
    }

    public Collection<PassStats> getPassStats() {
        return Collections.unmodifiableCollection(passStats.values());
    }

    private PassStats getOrCreatePassStats(String passName) {
        return passStats.computeIfAbsent(passName, PassStats::new);
    }

    @Getter
    public static final class PassStats {
        private final String name;
        private long cpuTimeNs;
        private int drawCalls;
        private int residentMeshCount;
        private int residentFaceCount;
        private int visibleMeshCount;
        private int culledMeshCount;
        private int drawnFaceCount;
        private int drawnTriangleCount;
        private int drawnVertexCount;
        private int textureArrayCount;
        private long meshGpuBytes;
        private String submissionMode;
        private int indirectVisibleDrawCount;
        private int indirectDrawCapacity;
        private long indirectBufferGpuBytes;
        private long syncCpuTimeNs;
        private long visibilityCpuTimeNs;
        private long batchUploadCpuTimeNs;
        private long drawSubmitCpuTimeNs;
        private long textureGpuBytes;

        private PassStats(String name) {
            this.name = name;
            resetFrameData();
        }

        private void resetFrameData() {
            cpuTimeNs = 0L;
            drawCalls = 0;
            residentMeshCount = 0;
            residentFaceCount = 0;
            visibleMeshCount = 0;
            culledMeshCount = 0;
            drawnFaceCount = 0;
            drawnTriangleCount = 0;
            drawnVertexCount = 0;
            textureArrayCount = 0;
            meshGpuBytes = 0L;
            submissionMode = "N/A";
            indirectVisibleDrawCount = 0;
            indirectDrawCapacity = 0;
            indirectBufferGpuBytes = 0L;
            syncCpuTimeNs = 0L;
            visibilityCpuTimeNs = 0L;
            batchUploadCpuTimeNs = 0L;
            drawSubmitCpuTimeNs = 0L;
            textureGpuBytes = 0L;
        }
    }

    public record ChunkPassMetrics(
            int residentMeshCount,
            int residentFaceCount,
            int visibleMeshCount,
            int culledMeshCount,
            int drawCalls,
            int drawnFaceCount,
            int drawnTriangleCount,
            int drawnVertexCount,
            long meshGpuBytes,
            String submissionMode,
            int indirectVisibleDrawCount,
            int indirectDrawCapacity,
            long indirectBufferGpuBytes,
            long syncCpuTimeNs,
            long visibilityCpuTimeNs,
            long batchUploadCpuTimeNs,
            long drawSubmitCpuTimeNs,
            int textureArrayCount,
            long textureGpuBytes
    ) {
    }
}
