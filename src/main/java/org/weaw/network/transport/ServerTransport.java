package org.weaw.network.transport;

import org.weaw.network.protocol.ServerMessage;

public interface ServerTransport extends AutoCloseable {
    ServerEvent poll();

    boolean send(long connectionId, ServerMessage message);

    /** Queued plus currently encoded messages for one peer. */
    default int outboundBacklog(long connectionId) {
        return 0;
    }

    void disconnect(long connectionId, String reason);

    boolean isOpen();

    @Override
    void close();
}
