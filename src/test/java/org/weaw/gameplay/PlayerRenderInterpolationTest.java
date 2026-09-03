package org.weaw.gameplay;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlayerRenderInterpolationTest {
    @Test
    void interpolatesPositionBetweenSimulationTicks() {
        Player player = new Player();
        player.setPosition(new Vector3f(2.0f, 4.0f, 6.0f));
        player.beginSimulationTick();
        player.move(new Vector3f(8.0f, 4.0f, -2.0f));

        assertEquals(new Vector3f(2.0f, 4.0f, 6.0f), player.sampleRenderPose(0.0f).position());
        assertEquals(new Vector3f(6.0f, 6.0f, 5.0f), player.sampleRenderPose(0.5f).position());
        assertEquals(new Vector3f(10.0f, 8.0f, 4.0f), player.sampleRenderPose(1.0f).position());
    }

    @Test
    void interpolatesYawAcrossTheShortestArc() {
        Player player = new Player();
        player.setRotation(179.0f, 10.0f);
        player.beginSimulationTick();
        player.rotate(2.0f, 20.0f);

        PlayerRenderPose midpoint = player.sampleRenderPose(0.5f);
        assertEquals(-180.0f, midpoint.yaw(), 0.0001f);
        assertEquals(20.0f, midpoint.pitch(), 0.0001f);
        assertEquals(-179.0f, player.sampleRenderPose(1.0f).yaw(), 0.0001f);
    }

    @Test
    void teleportSynchronizesBothPosesWithoutTrailingInterpolation() {
        Player player = new Player();
        player.beginSimulationTick();
        player.move(new Vector3f(10.0f, 0.0f, 0.0f));

        Vector3f destination = new Vector3f(100.0f, 50.0f, -25.0f);
        player.setPose(destination, 45.0f, -15.0f);

        PlayerRenderPose pose = player.sampleRenderPose(0.0f);
        assertEquals(destination, pose.position());
        assertEquals(45.0f, pose.yaw(), 0.0001f);
        assertEquals(-15.0f, pose.pitch(), 0.0001f);
    }
}
