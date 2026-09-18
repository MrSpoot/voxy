package org.weaw.network.protocol;

import org.joml.Vector3f;

public record NetworkPlayerState(
        long playerId,
        String name,
        Vector3f position,
        float yaw,
        float pitch,
        float verticalVelocity,
        boolean grounded,
        boolean noclip
) {
    public NetworkPlayerState {
        position = new Vector3f(position);
    }

    @Override
    public Vector3f position() {
        return new Vector3f(position);
    }
}
