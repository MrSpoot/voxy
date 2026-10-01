package org.weaw.engine.graphics.utils;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CameraBasisTest {

    @Test
    void allocationFreeBasisMatchesExistingCameraDirections() {
        Camera camera = new Camera(90.0f, 16.0f / 9.0f);
        camera.setRotation(37.0f, -24.0f);

        Vector3f forward = camera.getForward(new Vector3f());
        Vector3f right = camera.getRight(new Vector3f());
        Vector3f up = camera.getUp(new Vector3f());

        assertVectorEquals(camera.getForward(), forward);
        assertVectorEquals(camera.getRight(), right);
        assertVectorEquals(camera.getUp(), up);
    }

    @Test
    void cameraBasisIsNormalizedAndOrthogonal() {
        Camera camera = new Camera(90.0f, 16.0f / 9.0f);
        camera.setRotation(-112.0f, 61.0f);

        Vector3f forward = camera.getForward(new Vector3f());
        Vector3f right = camera.getRight(new Vector3f());
        Vector3f up = camera.getUp(new Vector3f());

        assertEquals(1.0f, forward.length(), 0.0001f);
        assertEquals(1.0f, right.length(), 0.0001f);
        assertEquals(1.0f, up.length(), 0.0001f);
        assertEquals(0.0f, forward.dot(right), 0.0001f);
        assertEquals(0.0f, forward.dot(up), 0.0001f);
        assertEquals(0.0f, right.dot(up), 0.0001f);
    }

    @Test
    void fieldOfViewCanChangeAtRuntimeAndInvalidValuesAreClamped() {
        Camera camera = new Camera(90.0f, 16.0f / 9.0f);
        long initialVersion = camera.getVisibilityVersion();

        camera.setFov(105.0f);
        assertEquals(105.0f, camera.getFov());
        assertEquals(initialVersion + 1, camera.getVisibilityVersion());

        camera.setFov(500.0f);
        assertEquals(120.0f, camera.getFov());

        long clampedVersion = camera.getVisibilityVersion();
        camera.setFov(120.0f);
        assertEquals(clampedVersion, camera.getVisibilityVersion());
    }

    private static void assertVectorEquals(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, 0.0001f);
        assertEquals(expected.y, actual.y, 0.0001f);
        assertEquals(expected.z, actual.z, 0.0001f);
    }
}
