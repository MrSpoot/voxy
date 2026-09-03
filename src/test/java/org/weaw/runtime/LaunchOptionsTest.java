package org.weaw.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LaunchOptionsTest {
    private static final long MIB = 1024L * 1024L;

    @Test
    void parsesMemoryAndWorldHeightOverrides() {
        LaunchOptions options = LaunchOptions.from(new String[]{
                "--memory-cpu-mib=700",
                "--memory-inflight-mib=64",
                "--memory-gpu-mib=300",
                "--memory-gpu-transient-mib=400",
                "--memory-max-loaded-chunks=1234",
                "--world-min-chunk-y=-2",
                "--world-max-chunk-y=5"
        });

        assertEquals(700L * MIB, options.worldMemoryBudget().maxCpuResidentBytes());
        assertEquals(64L * MIB, options.worldMemoryBudget().maxInFlightBytes());
        assertEquals(300L * MIB, options.worldMemoryBudget().maxGpuResidentBytes());
        assertEquals(400L * MIB, options.worldMemoryBudget().maxGpuTransientBytes());
        assertEquals(1234, options.worldMemoryBudget().maxLoadedChunks());
        assertEquals(-2, options.worldHeightRange().minChunkY());
        assertEquals(5, options.worldHeightRange().maxChunkY());
    }

    @Test
    void disablesSparseStreamingFromTheCommandLine() {
        LaunchOptions options = LaunchOptions.from(new String[]{"--disable-sparse-streaming"});

        assertFalse(options.sparseChunkStreamingEnabled());
    }
}
