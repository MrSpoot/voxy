package org.weaw.runtime;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkControllerTest {
    @Test
    void advancesThroughNamedPhasesAndStopsLoadingOnConvergence() {
        BenchmarkController controller = new BenchmarkController(options(2, 10, 3, 1));

        assertEquals(BenchmarkPhase.WARMUP, controller.currentFrame().phase());
        assertEquals(BenchmarkPhase.LOADING, controller.update(2.0d, false).phase());
        assertEquals(BenchmarkPhase.LOADING, controller.update(1.5d, false).phase());

        BenchmarkController.BenchmarkFrame traversal = controller.update(0.1d, true);
        assertEquals(BenchmarkPhase.TRAVERSAL, traversal.phase());
        assertTrue(traversal.loadingConverged());
        assertEquals(1.5d, traversal.loadingDurationSeconds(), 0.0001d);

        assertEquals(BenchmarkPhase.SETTLE, controller.update(3.0d, true).phase());
        assertEquals(BenchmarkPhase.COMPLETE, controller.update(1.0d, true).phase());
        assertTrue(controller.isComplete());
    }

    @Test
    void loadingTimeoutIsReportedWithoutFalseConvergence() {
        BenchmarkController controller = new BenchmarkController(options(0, 2, 1, 0));

        controller.update(2.0d, false);

        assertEquals(BenchmarkPhase.TRAVERSAL, controller.phase());
        assertFalse(controller.loadingConverged());
        assertEquals(2.0d, controller.loadingDurationSeconds(), 0.0001d);
    }

    private static BenchmarkOptions options(int warmup, int loadingTimeout, int traversal, int settle) {
        return new BenchmarkOptions(
                true,
                42L,
                traversal,
                warmup,
                loadingTimeout,
                settle,
                16,
                1280,
                720,
                new Vector3f(0.0f, 48.0f, 0.0f)
        );
    }
}
