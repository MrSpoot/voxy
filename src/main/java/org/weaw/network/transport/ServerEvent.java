package org.weaw.network.transport;

import org.weaw.network.protocol.ClientMessage;

public sealed interface ServerEvent permits ServerEvent.Connected, ServerEvent.Message, ServerEvent.Disconnected {
    long connectionId();

    record Connected(long connectionId) implements ServerEvent {
    }

    record Message(long connectionId, ClientMessage message) implements ServerEvent {
    }

    record Disconnected(long connectionId, String reason) implements ServerEvent {
    }
}
