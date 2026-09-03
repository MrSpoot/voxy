package org.weaw.runtime;

import org.joml.Vector3f;
import org.weaw.game.WorldHeightRange;
import org.weaw.game.WorldMemoryBudget;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

public record LaunchOptions(
        BenchmarkOptions benchmark,
        boolean jfrEnabled,
        Path jfrOutputPath,
        boolean runtimeStatsEnabled,
        Path runtimeStatsOutputPath,
        Path runtimeSummaryOutputPath,
        boolean dynamicLightingEnabled,
        boolean lightUploadEnabled,
        boolean ambientOcclusionEnabled,
        boolean remeshEnabled,
        boolean unloadsEnabled,
        boolean transparentChunksEnabled,
        boolean sparseChunkStreamingEnabled,
        WorldHeightRange worldHeightRange,
        WorldMemoryBudget worldMemoryBudget
) {
    private static final long MIB = 1024L * 1024L;

    public static LaunchOptions from(String[] args) {
        Map<String, String> cliOptions = parseCliOptions(args);
        boolean benchmarkEnabled = hasFlag(cliOptions, "benchmark")
                || Boolean.getBoolean("voxy.benchmark");

        BenchmarkOptions defaults = BenchmarkOptions.disabled();
        BenchmarkOptions benchmark = new BenchmarkOptions(
                benchmarkEnabled,
                parseLong(cliOptions, "benchmark-seed", "voxy.benchmark.seed", defaults.seed()),
                parseInt(cliOptions, "benchmark-duration", "voxy.benchmark.durationSeconds", defaults.durationSeconds()),
                parseNonNegativeInt(cliOptions, "benchmark-warmup", "voxy.benchmark.warmupSeconds", defaults.warmupSeconds()),
                parsePositiveInt(cliOptions, "benchmark-loading-timeout", "voxy.benchmark.loadingTimeoutSeconds", defaults.loadingTimeoutSeconds()),
                parseNonNegativeInt(cliOptions, "benchmark-settle", "voxy.benchmark.settleSeconds", defaults.settleSeconds()),
                parseInt(cliOptions, "benchmark-render-distance", "voxy.benchmark.renderDistanceChunks", defaults.renderDistanceChunks()),
                parseWindowWidth(cliOptions, defaults.windowWidth()),
                parseWindowHeight(cliOptions, defaults.windowHeight()),
                parseVector3(cliOptions, "benchmark-spawn", "voxy.benchmark.spawn", defaults.spawn())
        );

        boolean jfrEnabled = benchmarkEnabled
                || hasFlag(cliOptions, "profile-jfr")
                || Boolean.getBoolean("voxy.profile.jfr");
        WorldHeightRange defaultHeightRange = WorldHeightRange.configuredDefault();
        int minChunkY = parseInt(cliOptions, "world-min-chunk-y", "voxy.world.minChunkY", defaultHeightRange.minChunkY());
        int maxChunkY = parseInt(cliOptions, "world-max-chunk-y", "voxy.world.maxChunkY", defaultHeightRange.maxChunkY());
        WorldHeightRange heightRange = minChunkY <= maxChunkY
                ? new WorldHeightRange(minChunkY, maxChunkY)
                : defaultHeightRange;

        WorldMemoryBudget defaultMemoryBudget = WorldMemoryBudget.balanced();
        long cpuBytes = parseMib(cliOptions, "memory-cpu-mib", "voxy.memory.cpuMiB", defaultMemoryBudget.maxCpuResidentBytes());
        long inFlightBytes = parseMib(cliOptions, "memory-inflight-mib", "voxy.memory.inFlightMiB", defaultMemoryBudget.maxInFlightBytes());
        long gpuBytes = parseMib(cliOptions, "memory-gpu-mib", "voxy.memory.gpuMiB", defaultMemoryBudget.maxGpuResidentBytes());
        long gpuTransientBytes = parseMib(
                cliOptions,
                "memory-gpu-transient-mib",
                "voxy.memory.gpuTransientMiB",
                Math.max(defaultMemoryBudget.maxGpuTransientBytes(), gpuBytes)
        );
        WorldMemoryBudget memoryBudget = new WorldMemoryBudget(
                cpuBytes,
                inFlightBytes,
                parsePositiveInt(
                        cliOptions,
                        "memory-max-loaded-chunks",
                        "voxy.memory.maxLoadedChunks",
                        defaultMemoryBudget.maxLoadedChunks()
                ),
                defaultMemoryBudget.cpuStopRatio(),
                defaultMemoryBudget.cpuResumeRatio(),
                defaultMemoryBudget.heapStopRatio(),
                defaultMemoryBudget.heapResumeRatio(),
                gpuBytes,
                Math.max(gpuBytes, gpuTransientBytes)
        );

        return new LaunchOptions(
                benchmark,
                jfrEnabled,
                Paths.get(readString(cliOptions, "jfr-output", "voxy.jfr.output", "target/profile.jfr")),
                benchmarkEnabled
                        || hasFlag(cliOptions, "profile-runtime")
                        || Boolean.getBoolean("voxy.profile.runtime"),
                Paths.get(readString(
                        cliOptions,
                        "runtime-output",
                        "voxy.profile.runtime.output",
                        "target/profiling/runtime-profile.csv"
                )),
                Paths.get(readString(
                        cliOptions,
                        "runtime-summary-output",
                        "voxy.profile.runtime.summary.output",
                        "target/profiling/runtime-summary.json"
                )),
                !hasFlag(cliOptions, "disable-dynamic-lighting")
                        && !Boolean.getBoolean("voxy.disableDynamicLighting"),
                !hasFlag(cliOptions, "disable-light-upload")
                        && !Boolean.getBoolean("voxy.disableLightUpload"),
                !hasFlag(cliOptions, "disable-ao")
                        && !Boolean.getBoolean("voxy.disableAo"),
                !hasFlag(cliOptions, "disable-remesh")
                        && !Boolean.getBoolean("voxy.disableRemesh"),
                !hasFlag(cliOptions, "disable-unloads")
                        && !Boolean.getBoolean("voxy.disableUnloads"),
                !hasFlag(cliOptions, "disable-transparent-chunks")
                        && !Boolean.getBoolean("voxy.disableTransparentChunks"),
                !hasFlag(cliOptions, "disable-sparse-streaming")
                        && Boolean.parseBoolean(System.getProperty("voxy.sparseChunkStreaming", "true")),
                heightRange,
                memoryBudget
        );
    }

    public boolean benchmarkEnabled() {
        return benchmark.enabled();
    }

    private static Map<String, String> parseCliOptions(String[] args) {
        Map<String, String> options = new HashMap<>();
        if (args == null) {
            return options;
        }

        for (String arg : args) {
            if (arg == null || !arg.startsWith("--")) {
                continue;
            }

            String rawOption = arg.substring(2);
            int separator = rawOption.indexOf('=');
            if (separator < 0) {
                options.put(rawOption, "true");
                continue;
            }

            String key = rawOption.substring(0, separator);
            String value = rawOption.substring(separator + 1);
            options.put(key, value);
        }

        return options;
    }

    private static boolean hasFlag(Map<String, String> cliOptions, String key) {
        return Boolean.parseBoolean(cliOptions.getOrDefault(key, "false"));
    }

    private static int parseInt(Map<String, String> cliOptions, String cliKey, String propertyKey, int defaultValue) {
        String rawValue = readString(cliOptions, cliKey, propertyKey, Integer.toString(defaultValue));
        try {
            return Integer.parseInt(rawValue.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static long parseLong(Map<String, String> cliOptions, String cliKey, String propertyKey, long defaultValue) {
        String rawValue = readString(cliOptions, cliKey, propertyKey, Long.toString(defaultValue));
        try {
            return Long.parseLong(rawValue.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static long parseMib(
            Map<String, String> cliOptions,
            String cliKey,
            String propertyKey,
            long defaultBytes
    ) {
        long defaultMib = Math.max(1L, defaultBytes / MIB);
        long valueMib = parseLong(cliOptions, cliKey, propertyKey, defaultMib);
        if (valueMib <= 0L || valueMib > Long.MAX_VALUE / MIB) {
            return defaultBytes;
        }
        return valueMib * MIB;
    }

    private static int parsePositiveInt(
            Map<String, String> cliOptions,
            String cliKey,
            String propertyKey,
            int defaultValue
    ) {
        int value = parseInt(cliOptions, cliKey, propertyKey, defaultValue);
        return value > 0 ? value : defaultValue;
    }

    private static int parseNonNegativeInt(
            Map<String, String> cliOptions,
            String cliKey,
            String propertyKey,
            int defaultValue
    ) {
        int value = parseInt(cliOptions, cliKey, propertyKey, defaultValue);
        return value >= 0 ? value : defaultValue;
    }

    private static Vector3f parseVector3(
            Map<String, String> cliOptions,
            String cliKey,
            String propertyKey,
            Vector3f defaultValue
    ) {
        String rawValue = readString(cliOptions, cliKey, propertyKey, null);
        if (rawValue == null || rawValue.isBlank()) {
            return defaultValue;
        }

        String[] coordinates = rawValue.split(",");
        if (coordinates.length != 3) {
            return defaultValue;
        }

        try {
            return new Vector3f(
                    Float.parseFloat(coordinates[0].trim()),
                    Float.parseFloat(coordinates[1].trim()),
                    Float.parseFloat(coordinates[2].trim())
            );
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static int parseWindowWidth(Map<String, String> cliOptions, int defaultValue) {
        int[] dimensions = parseWindowSize(cliOptions);
        return dimensions == null ? defaultValue : dimensions[0];
    }

    private static int parseWindowHeight(Map<String, String> cliOptions, int defaultValue) {
        int[] dimensions = parseWindowSize(cliOptions);
        return dimensions == null ? defaultValue : dimensions[1];
    }

    private static int[] parseWindowSize(Map<String, String> cliOptions) {
        String rawValue = readString(cliOptions, "benchmark-window", "voxy.benchmark.window", null);
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }

        String normalized = rawValue.toLowerCase();
        String[] dimensions = normalized.split("x");
        if (dimensions.length != 2) {
            return null;
        }

        try {
            return new int[]{
                    Integer.parseInt(dimensions[0].trim()),
                    Integer.parseInt(dimensions[1].trim())
            };
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String readString(
            Map<String, String> cliOptions,
            String cliKey,
            String propertyKey,
            String defaultValue
    ) {
        String cliValue = cliOptions.get(cliKey);
        if (cliValue != null && !cliValue.isBlank()) {
            return cliValue;
        }

        String propertyValue = System.getProperty(propertyKey);
        if (propertyValue != null && !propertyValue.isBlank()) {
            return propertyValue;
        }

        return defaultValue;
    }
}
