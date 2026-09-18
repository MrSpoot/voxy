package org.weaw.runtime;

import org.weaw.network.protocol.Protocol;

public record NetworkOptions(
        NetworkMode mode,
        String host,
        int port,
        String playerName,
        int maxPlayers,
        long worldSeed,
        int viewDistance
) {
    public NetworkOptions {
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Network port must be in range [1, 65535]");
        }
        maxPlayers = Math.clamp(maxPlayers, 1, Protocol.MAX_PLAYERS);
        viewDistance = Math.clamp(viewDistance, Protocol.MIN_VIEW_DISTANCE, Protocol.MAX_VIEW_DISTANCE);
    }
}
