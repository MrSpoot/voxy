package org.weaw.network;

import org.weaw.network.client.NetworkClientSession.ClientNetworkStats;
import org.weaw.server.MultiplayerGameServer.ServerNetworkStats;

public record NetworkDebugSnapshot(
        int inboundBacklog,
        int pendingPredictions,
        int pendingMeshes,
        int remotePlayers,
        float lastCorrectionDistance,
        long reconciliations,
        long receivedChunks,
        long receivedLights,
        int connectedPlayers,
        int maxOutboundBacklog,
        int pendingBlockUpdates,
        int pendingLightUpdates,
        long skippedSnapshots,
        long deferredWorldMessages
) {
    public static NetworkDebugSnapshot from(ClientNetworkStats client, ServerNetworkStats server) {
        ServerNetworkStats safeServer = server == null
                ? new ServerNetworkStats(0, 0, 0, 0, 0L, 0L)
                : server;
        return new NetworkDebugSnapshot(
                client.inboundBacklog(),
                client.pendingPredictions(),
                client.pendingMeshes(),
                client.remotePlayers(),
                client.lastCorrectionDistance(),
                client.reconciliations(),
                client.receivedChunkSnapshots(),
                client.receivedLightUpdates(),
                safeServer.connectedPlayers(),
                safeServer.maxOutboundBacklog(),
                safeServer.pendingBlockUpdates(),
                safeServer.pendingLightUpdates(),
                safeServer.skippedSnapshots(),
                safeServer.deferredWorldMessages()
        );
    }
}
