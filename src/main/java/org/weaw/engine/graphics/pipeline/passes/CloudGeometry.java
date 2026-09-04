package org.weaw.engine.graphics.pipeline.passes;

/** Pure geometry calculations shared by the cloud pass and its tests. */
final class CloudGeometry {
    private CloudGeometry() {
    }

    static float thickness(float cellSize) {
        return Math.max(1.0f, cellSize * 0.25f);
    }

    static float centeredBaseY(float altitude, float cellSize) {
        return altitude - thickness(cellSize) * 0.5f;
    }
}
