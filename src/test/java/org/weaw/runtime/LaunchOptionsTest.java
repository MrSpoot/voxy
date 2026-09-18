package org.weaw.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void parsesBenchmarkPhaseDurations() {
        LaunchOptions options = LaunchOptions.from(new String[]{
                "--benchmark",
                "--benchmark-warmup=3",
                "--benchmark-loading-timeout=45",
                "--benchmark-duration=20",
                "--benchmark-settle=7"
        });

        assertTrue(options.benchmarkEnabled());
        assertEquals(3, options.benchmark().warmupSeconds());
        assertEquals(45, options.benchmark().loadingTimeoutSeconds());
        assertEquals(20, options.benchmark().durationSeconds());
        assertEquals(7, options.benchmark().settleSeconds());
    }

    @Test
    void parsesHostAndDirectConnectionModes() {
        LaunchOptions host = LaunchOptions.from(new String[]{
                "--host", "--port=25570", "--name=Alice", "--view-distance=16"
        });
        LaunchOptions client = LaunchOptions.from(new String[]{"--connect=192.168.1.20:25571", "--name=Bob"});

        assertEquals(NetworkMode.HOST, host.network().mode());
        assertEquals(25570, host.network().port());
        assertEquals("Alice", host.network().playerName());
        assertEquals(16, host.network().viewDistance());
        assertEquals(NetworkMode.CONNECT, client.network().mode());
        assertEquals("192.168.1.20", client.network().host());
        assertEquals(25571, client.network().port());
        assertEquals(12, client.network().viewDistance());
    }

    @Test
    void rejectsConflictingNetworkModes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> LaunchOptions.from(new String[]{"--host", "--connect=localhost"})
        );
    }
}
