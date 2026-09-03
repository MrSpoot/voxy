package org.weaw.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

public final class RuntimeProfilingSummaryCollector {
    private static final int DEFAULT_WARMUP_FRAME_COUNT = Integer.getInteger("voxy.profile.runtime.warmupFrames", 10);

    private final List<RuntimeFrameProfile> frames = new ArrayList<>();

    public void recordFrame(RuntimeFrameProfile frameProfile) {
        frames.add(Objects.requireNonNull(frameProfile, "frameProfile"));
    }

    public boolean isEmpty() {
        return frames.isEmpty();
    }

    public Path writeSummary(Path outputPath, LaunchOptions launchOptions) throws IOException {
        Objects.requireNonNull(outputPath, "outputPath");
        Objects.requireNonNull(launchOptions, "launchOptions");

        Path absolutePath = outputPath.toAbsolutePath().normalize();
        Path parent = absolutePath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        List<RuntimeFrameProfile> snapshot = List.copyOf(frames);
        int warmupFrames = Math.min(DEFAULT_WARMUP_FRAME_COUNT, snapshot.size());
        List<RuntimeFrameProfile> steadyStateFrames = warmupFrames >= snapshot.size()
                ? List.of()
                : snapshot.subList(warmupFrames, snapshot.size());

        MetricSummary allFrameMs = summarize(snapshot, RuntimeFrameProfile::frameMs);
        MetricSummary steadyStateFrameMs = summarize(steadyStateFrames, RuntimeFrameProfile::frameMs);

        String json = buildJson(
                snapshot,
                steadyStateFrames,
                warmupFrames,
                launchOptions,
                allFrameMs,
                steadyStateFrameMs
        );
        Files.writeString(absolutePath, json);
        return absolutePath;
    }

    private String buildJson(
            List<RuntimeFrameProfile> allFrames,
            List<RuntimeFrameProfile> steadyStateFrames,
            int warmupFrames,
            LaunchOptions launchOptions,
            MetricSummary allFrameMs,
            MetricSummary steadyStateFrameMs
    ) {
        long totalChunksGenerated = sum(allFrames, RuntimeFrameProfile::chunksGenerated);
        long totalChunksMeshed = sum(allFrames, RuntimeFrameProfile::chunksMeshed);
        long totalChunksRemeshed = sum(allFrames, RuntimeFrameProfile::chunksRemeshed);
        long totalChunksPublished = sum(allFrames, RuntimeFrameProfile::chunksPublished);
        long totalChunksUnloaded = sum(allFrames, RuntimeFrameProfile::chunksUnloaded);

        DominantStageCounts dominantStageCounts = countDominantStages(steadyStateFrames);

        StringBuilder json = new StringBuilder(2048);
        json.append("{\n");
        appendString(json, 1, "generated_at", Instant.now().toString(), true);
        json.append(indent(1)).append("\"run\": {\n");
        appendNumber(json, 2, "sample_count", allFrames.size(), true);
        appendNumber(json, 2, "warmup_frames_excluded", warmupFrames, true);
        appendBoolean(json, 2, "benchmark_enabled", launchOptions.benchmarkEnabled(), true);
        appendNumber(json, 2, "benchmark_duration_seconds", launchOptions.benchmark().durationSeconds(), true);
        appendNumber(json, 2, "benchmark_render_distance_chunks", launchOptions.benchmark().renderDistanceChunks(), true);
        appendString(json, 2, "runtime_profile_csv", launchOptions.runtimeStatsOutputPath().toString(), true);
        appendString(json, 2, "runtime_summary_json", launchOptions.runtimeSummaryOutputPath().toString(), true);
        appendString(json, 2, "jfr_output", launchOptions.jfrOutputPath().toString(), false);
        json.append(indent(1)).append("},\n");

        json.append(indent(1)).append("\"flags\": {\n");
        appendBoolean(json, 2, "dynamic_lighting_enabled", launchOptions.dynamicLightingEnabled(), true);
        appendBoolean(json, 2, "light_upload_enabled", launchOptions.lightUploadEnabled(), true);
        appendBoolean(json, 2, "ambient_occlusion_enabled", launchOptions.ambientOcclusionEnabled(), true);
        appendBoolean(json, 2, "remesh_enabled", launchOptions.remeshEnabled(), true);
        appendBoolean(json, 2, "unloads_enabled", launchOptions.unloadsEnabled(), true);
        appendBoolean(json, 2, "transparent_chunks_enabled", launchOptions.transparentChunksEnabled(), false);
        json.append(indent(1)).append("},\n");

        appendMetricSummary(json, 1, "frame_ms", allFrameMs, true);
        appendMetricSummary(json, 1, "steady_state_frame_ms", steadyStateFrameMs, true);

        json.append(indent(1)).append("\"stage_averages_ms\": {\n");
        appendMetricSummary(json, 2, "chunk_generation_ms", summarize(allFrames, RuntimeFrameProfile::chunkGenerationMs), true);
        appendMetricSummary(json, 2, "chunk_mesh_ms", summarize(allFrames, RuntimeFrameProfile::chunkMeshMs), true);
        appendMetricSummary(json, 2, "chunk_lighting_ms", summarize(allFrames, RuntimeFrameProfile::chunkLightingMs), true);
        appendMetricSummary(json, 2, "render_ms", summarize(allFrames, RuntimeFrameProfile::renderMs), true);
        appendMetricSummary(json, 2, "gpu_mesh_upload_ms", summarize(allFrames, RuntimeFrameProfile::gpuMeshUploadMs), true);
        appendMetricSummary(json, 2, "gpu_light_upload_ms", summarize(allFrames, RuntimeFrameProfile::gpuLightUploadMs), false);
        json.append(indent(1)).append("},\n");

        json.append(indent(1)).append("\"streaming_counters\": {\n");
        appendMetricSummary(json, 2, "queued_tasks", summarize(allFrames, RuntimeFrameProfile::queuedTasks), true);
        appendMetricSummary(json, 2, "pending_remesh", summarize(allFrames, RuntimeFrameProfile::pendingRemesh), true);
        appendMetricSummary(json, 2, "pending_uploads", summarize(allFrames, RuntimeFrameProfile::pendingUploads), true);
        appendMetricSummary(json, 2, "loaded_chunks", summarize(allFrames, RuntimeFrameProfile::loadedChunks), false);
        json.append(indent(1)).append("},\n");

        json.append(indent(1)).append("\"memory\": {\n");
        appendMetricSummary(json, 2, "world_cpu_resident_bytes", summarize(allFrames, RuntimeFrameProfile::worldCpuResidentBytes), true);
        appendMetricSummary(json, 2, "world_in_flight_bytes", summarize(allFrames, RuntimeFrameProfile::worldInFlightBytes), true);
        appendMetricSummary(json, 2, "chunk_gpu_resident_bytes", summarize(allFrames, RuntimeFrameProfile::chunkGpuResidentBytes), true);
        appendMetricSummary(json, 2, "effective_render_distance_chunks", summarize(allFrames, RuntimeFrameProfile::effectiveRenderDistanceChunks), false);
        json.append(indent(1)).append("},\n");

        json.append(indent(1)).append("\"slow_frames\": {\n");
        appendNumber(json, 2, "over_16_67_ms", countFramesOver(allFrames, 16.67d), true);
        appendNumber(json, 2, "over_33_33_ms", countFramesOver(allFrames, 33.33d), false);
        json.append(indent(1)).append("},\n");

        json.append(indent(1)).append("\"totals\": {\n");
        appendNumber(json, 2, "chunks_generated", totalChunksGenerated, true);
        appendNumber(json, 2, "chunks_meshed", totalChunksMeshed, true);
        appendNumber(json, 2, "chunks_remeshed", totalChunksRemeshed, true);
        appendNumber(json, 2, "chunks_published", totalChunksPublished, true);
        appendNumber(json, 2, "chunks_unloaded", totalChunksUnloaded, false);
        json.append(indent(1)).append("},\n");

        json.append(indent(1)).append("\"steady_state_dominant_stage_frames\": {\n");
        appendNumber(json, 2, "generation", dominantStageCounts.generation(), true);
        appendNumber(json, 2, "meshing", dominantStageCounts.meshing(), true);
        appendNumber(json, 2, "lighting", dominantStageCounts.lighting(), false);
        json.append(indent(1)).append("}\n");
        json.append("}\n");
        return json.toString();
    }

    private static MetricSummary summarize(List<RuntimeFrameProfile> frames, ToDoubleFunction<RuntimeFrameProfile> extractor) {
        if (frames.isEmpty()) {
            return MetricSummary.empty();
        }

        double[] values = new double[frames.size()];
        double total = 0.0d;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < frames.size(); index++) {
            double value = extractor.applyAsDouble(frames.get(index));
            values[index] = value;
            total += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }

        Arrays.sort(values);
        return new MetricSummary(
                total / values.length,
                min,
                max,
                percentile(values, 0.50d),
                percentile(values, 0.95d),
                percentile(values, 0.99d)
        );
    }

    private static long sum(List<RuntimeFrameProfile> frames, java.util.function.ToIntFunction<RuntimeFrameProfile> extractor) {
        long total = 0L;
        for (RuntimeFrameProfile frame : frames) {
            total += extractor.applyAsInt(frame);
        }
        return total;
    }

    private static int countFramesOver(List<RuntimeFrameProfile> frames, double thresholdMs) {
        int count = 0;
        for (RuntimeFrameProfile frame : frames) {
            if (frame.frameMs() > thresholdMs) {
                count++;
            }
        }
        return count;
    }

    private static DominantStageCounts countDominantStages(List<RuntimeFrameProfile> frames) {
        int generation = 0;
        int meshing = 0;
        int lighting = 0;
        for (RuntimeFrameProfile frame : frames) {
            double max = Math.max(frame.chunkGenerationMs(), Math.max(frame.chunkMeshMs(), frame.chunkLightingMs()));
            if (max <= 0.0d) {
                continue;
            }
            if (max == frame.chunkMeshMs()) {
                meshing++;
            } else if (max == frame.chunkLightingMs()) {
                lighting++;
            } else {
                generation++;
            }
        }
        return new DominantStageCounts(generation, meshing, lighting);
    }

    private static double percentile(double[] sortedValues, double percentile) {
        if (sortedValues.length == 0) {
            return 0.0d;
        }

        double position = percentile * (sortedValues.length - 1);
        int lowerIndex = (int) Math.floor(position);
        int upperIndex = (int) Math.ceil(position);
        if (lowerIndex == upperIndex) {
            return sortedValues[lowerIndex];
        }

        double weight = position - lowerIndex;
        return sortedValues[lowerIndex] + ((sortedValues[upperIndex] - sortedValues[lowerIndex]) * weight);
    }

    private static void appendMetricSummary(StringBuilder json, int indentLevel, String name, MetricSummary summary, boolean trailingComma) {
        json.append(indent(indentLevel)).append("\"").append(name).append("\": {\n");
        appendNumber(json, indentLevel + 1, "avg", summary.avg(), true);
        appendNumber(json, indentLevel + 1, "min", summary.min(), true);
        appendNumber(json, indentLevel + 1, "max", summary.max(), true);
        appendNumber(json, indentLevel + 1, "p50", summary.p50(), true);
        appendNumber(json, indentLevel + 1, "p95", summary.p95(), true);
        appendNumber(json, indentLevel + 1, "p99", summary.p99(), false);
        json.append(indent(indentLevel)).append("}");
        if (trailingComma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static void appendString(StringBuilder json, int indentLevel, String key, String value, boolean trailingComma) {
        json.append(indent(indentLevel))
                .append("\"")
                .append(key)
                .append("\": \"")
                .append(escape(value))
                .append("\"");
        if (trailingComma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static void appendBoolean(StringBuilder json, int indentLevel, String key, boolean value, boolean trailingComma) {
        json.append(indent(indentLevel))
                .append("\"")
                .append(key)
                .append("\": ")
                .append(value);
        if (trailingComma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static void appendNumber(StringBuilder json, int indentLevel, String key, long value, boolean trailingComma) {
        json.append(indent(indentLevel))
                .append("\"")
                .append(key)
                .append("\": ")
                .append(value);
        if (trailingComma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static void appendNumber(StringBuilder json, int indentLevel, String key, double value, boolean trailingComma) {
        json.append(indent(indentLevel))
                .append("\"")
                .append(key)
                .append("\": ")
                .append(format(value));
        if (trailingComma) {
            json.append(",");
        }
        json.append("\n");
    }

    private static String indent(int indentLevel) {
        return "  ".repeat(Math.max(0, indentLevel));
    }

    private static String escape(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private record MetricSummary(double avg, double min, double max, double p50, double p95, double p99) {
        private static MetricSummary empty() {
            return new MetricSummary(0.0d, 0.0d, 0.0d, 0.0d, 0.0d, 0.0d);
        }
    }

    private record DominantStageCounts(int generation, int meshing, int lighting) {
    }
}
