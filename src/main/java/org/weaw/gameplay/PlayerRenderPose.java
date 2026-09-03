package org.weaw.gameplay;

import org.joml.Vector3f;

/** Immutable player pose sampled for rendering between two simulation ticks. */
public record PlayerRenderPose(Vector3f position, float yaw, float pitch) {
    public PlayerRenderPose {
        position = new Vector3f(position);
    }

    @Override
    public Vector3f position() {
        return new Vector3f(position);
    }
}
