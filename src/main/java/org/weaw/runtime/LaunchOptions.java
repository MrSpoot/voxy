package org.weaw.runtime;

import org.joml.Vector3f;

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
        boolean transparentChunksEnabled
) {
    public static LaunchOptions from(String[] args) {
        Map<String, String> cliOptions = parseCliOptions(args);
        boolean benchmarkEnabled = hasFlag(cliOptions, "benchmark")
                || Boolean.getBoolean("voxy.benchmark");

        BenchmarkOptions defaults = BenchmarkOptions.disabled();
        BenchmarkOptions benchmark = new BenchmarkOptions(
                benchmarkEnabled,
                parseLong(cliOptions, "benchmark-seed", "voxy.benchmark.seed", defaults.seed()),
                parseInt(cliOptions, "benchmark-duration", "voxy.benchmark.durationSeconds", defaults.durationSeconds()),
                parseInt(cliOptions, "benchmark-render-distance", "voxy.benchmark.renderDistanceChunks", defaults.renderDistanceChunks()),
                parseWindowWidth(cliOptions, defaults.windowWidth()),
                parseWindowHeight(cliOptions, defaults.windowHeight()),
                parseVector3(cliOptions, "benchmark-spawn", "voxy.benchmark.spawn", defaults.spawn())
        );

        boolean jfrEnabled = benchmarkEnabled
                || hasFlag(cliOptions, "profile-jfr")
                || Boolean.getBoolean("voxy.profile.jfr");

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
                        && !Boolean.getBoolean("voxy.disableTransparentChunks")
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
