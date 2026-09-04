package org.weaw.engine.graphics.pipeline.passes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloudGeometryTest {

    @Test
    void cellSizeDoesNotMoveCloudCenter() {
        float altitude = 192.0f;

        assertCenteredAt(altitude, 4.0f);
        assertCenteredAt(altitude, 16.0f);
        assertCenteredAt(altitude, 32.0f);
    }

    private static void assertCenteredAt(float altitude, float cellSize) {
        float base = CloudGeometry.centeredBaseY(altitude, cellSize);
        float center = base + CloudGeometry.thickness(cellSize) * 0.5f;

        assertEquals(altitude, center, 0.0001f);
    }
}
