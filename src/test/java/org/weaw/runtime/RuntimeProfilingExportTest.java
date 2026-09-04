package org.weaw.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeProfilingExportTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void csvHeaderAndRowsStayAlignedAfterAppendingSparseColumns() throws Exception {
        RuntimeFrameProfile profile = profile(Map.of());

        String[] header = RuntimeFrameProfile.csvHeader().split(",", -1);
        String[] row = profile.toCsvRow().split(",", -1);

        assertEquals(header.length, row.length);
        assertEquals("frame", header[0]);
        assertEquals("classification_cache_hit_percent", header[header.length - 1]);
    }

    @Test
    void summaryKeepsCorrectEventTotalsAndReportsSparseState() throws Exception {
        RuntimeProfilingSummaryCollector collector = new RuntimeProfilingSummaryCollector();
        collector.recordFrame(profile(Map.ofEntries(
                Map.entry("worldStreamingUpdates", 1),
                Map.entry("benchmarkPhase", BenchmarkPhase.WARMUP.name()),
                Map.entry("chunksGenerated", 3),
                Map.entry("chunksPublished", 2),
                Map.entry("chunkMeshingSnapshotMs", 1.25d),
                Map.entry("cancelledChunkBuilds", 4),
                Map.entry("lightCacheDeferredUploads", 12),
                Map.entry("lightCacheAllocationFailures", 2),
                Map.entry("sparseStreamingEnabled", true),
                Map.entry("desiredMaterializedChunks", 100),
                Map.entry("virtualEmptyChunks", 200),
                Map.entry("virtualUniformChunks", 300),
                Map.entry("legacyCandidateChunks", 600),
                Map.entry("avoidedChunkCandidates", 500),
                Map.entry("chunkAvoidancePercent", 83.3333d),
                Map.entry("classificationCacheHits", 8L),
                Map.entry("classificationCacheMisses", 2L),
                Map.entry("classificationCacheHitPercent", 80.0d),
                Map.entry("loadedChunks", 90),
                Map.entry("queuedTasks", 1),
                Map.entry("pendingUploads", 2)
        )));
        collector.recordFrame(profile(Map.ofEntries(
                Map.entry("benchmarkPhase", BenchmarkPhase.SETTLE.name()),
                Map.entry("sparseStreamingEnabled", true),
                Map.entry("desiredMaterializedChunks", 100),
                Map.entry("virtualEmptyChunks", 200),
                Map.entry("virtualUniformChunks", 300),
                Map.entry("legacyCandidateChunks", 600),
                Map.entry("avoidedChunkCandidates", 500),
                Map.entry("chunkAvoidancePercent", 83.3333d),
                Map.entry("classificationCacheHits", 8L),
                Map.entry("classificationCacheMisses", 2L),
                Map.entry("classificationCacheHitPercent", 80.0d),
                Map.entry("loadedChunks", 100)
        )));

        Path output = temporaryDirectory.resolve("summary.json");
        collector.writeSummary(output, LaunchOptions.from(new String[]{"--benchmark"}));
        String json = Files.readString(output);

        assertTrue(json.contains("\"schema_version\": 5"));
        assertTrue(json.contains("\"warmup_frames_excluded\": 1"));
        assertTrue(json.contains("\"world_streaming_update_count\": 1"));
        assertTrue(json.contains("\"chunks_generated\": 3"));
        assertTrue(json.contains("\"chunks_published\": 2"));
        assertTrue(json.contains("\"chunk_meshing_snapshot_ms\""));
        assertTrue(json.contains("\"cancelled_chunk_builds\": 4"));
        assertTrue(json.contains("\"light_cache\""));
        assertTrue(json.contains("\"deferred_uploads\""));
        assertTrue(json.contains("\"allocation_failures\""));
        assertTrue(json.contains("\"new_visible_misses\""));
        assertTrue(json.contains("\"prefetch_uploads\""));
        assertTrue(json.contains("\"prefetch_hits\""));
        assertTrue(json.contains("\"sparse_streaming\""));
        assertTrue(json.contains("\"classification_cache_hit_percent\": 80.0000"));
        assertTrue(json.contains("\"converged\": true"));
        assertTrue(json.contains("\"benchmark_phases\""));
    }

    private static RuntimeFrameProfile profile(Map<String, Object> overrides) throws Exception {
        RecordComponent[] components = RuntimeFrameProfile.class.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            RecordComponent component = components[index];
            parameterTypes[index] = component.getType();
            Object override = overrides.get(component.getName());
            arguments[index] = override == null ? defaultValue(component.getType()) : override;
        }
        return RuntimeFrameProfile.class.getDeclaredConstructor(parameterTypes).newInstance(arguments);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == double.class) {
            return 0.0d;
        }
        if (type == boolean.class) {
            return false;
        }
        return "NORMAL";
    }
}
