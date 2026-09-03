package org.weaw.game;

public record WorldMemoryBudget(
        long maxCpuResidentBytes,
        long maxInFlightBytes,
        int maxLoadedChunks,
        double cpuStopRatio,
        double cpuResumeRatio,
        double heapStopRatio,
        double heapResumeRatio,
        long maxGpuResidentBytes,
        long maxGpuTransientBytes
) {
    private static final long MIB = 1024L * 1024L;

    public WorldMemoryBudget {
        if (maxCpuResidentBytes <= 0 || maxInFlightBytes <= 0 || maxLoadedChunks <= 0
                || maxGpuResidentBytes <= 0 || maxGpuTransientBytes < maxGpuResidentBytes) {
            throw new IllegalArgumentException("Memory budget limits must be positive and consistent");
        }
        validateRatio(cpuResumeRatio, "cpuResumeRatio");
        validateRatio(cpuStopRatio, "cpuStopRatio");
        validateRatio(heapResumeRatio, "heapResumeRatio");
        validateRatio(heapStopRatio, "heapStopRatio");
        if (cpuResumeRatio >= cpuStopRatio || heapResumeRatio >= heapStopRatio) {
            throw new IllegalArgumentException("Resume ratios must be lower than stop ratios");
        }
    }

    public static WorldMemoryBudget balanced() {
        long heapMax = Runtime.getRuntime().maxMemory();
        long adaptiveCpuBudget = clamp((long) (heapMax * 0.35), 384L * MIB, 1536L * MIB);
        long cpuBudget = configuredMib("voxy.memory.cpuMiB", adaptiveCpuBudget);
        long inFlightBudget = configuredMib("voxy.memory.inFlightMiB", 128L * MIB);
        long gpuBudget = configuredMib("voxy.memory.gpuMiB", 512L * MIB);
        long gpuTransientBudget = configuredMib("voxy.memory.gpuTransientMiB", 640L * MIB);
        int configuredMaxChunks = Integer.getInteger("voxy.memory.maxLoadedChunks", 32_768);
        return new WorldMemoryBudget(
                cpuBudget,
                inFlightBudget,
                configuredMaxChunks > 0 ? configuredMaxChunks : 32_768,
                0.90,
                0.80,
                0.75,
                0.65,
                gpuBudget,
                Math.max(gpuBudget, gpuTransientBudget)
        );
    }

    public long cpuStopBytes() {
        return (long) (maxCpuResidentBytes * cpuStopRatio);
    }

    public long cpuResumeBytes() {
        return (long) (maxCpuResidentBytes * cpuResumeRatio);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long configuredMib(String property, long defaultBytes) {
        Long configured = Long.getLong(property);
        if (configured == null || configured <= 0L) {
            return defaultBytes;
        }
        return Math.multiplyExact(configured, MIB);
    }

    private static void validateRatio(double value, String name) {
        if (!Double.isFinite(value) || value <= 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be in ]0, 1]");
        }
    }
}
