package org.weaw.network.transport;

import org.weaw.network.protocol.ClientMessage;
import org.weaw.network.protocol.ServerMessage;

public interface ClientTransport extends AutoCloseable {
    boolean send(ClientMessage message);

    ServerMessage poll();

    default int inboundBacklog() {
        return 0;
    }

    boolean isOpen();

    String closeReason();

    @Override
    void close();
}
