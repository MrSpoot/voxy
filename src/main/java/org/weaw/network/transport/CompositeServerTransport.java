package org.weaw.network.transport;

import org.weaw.network.protocol.ServerMessage;

import java.util.List;

public final class CompositeServerTransport implements ServerTransport {
    private static final long LOCAL_ID_MASK = 0x00FF_FFFF_FFFF_FFFFL;
    private final List<ServerTransport> transports;
    private int pollCursor;

    public CompositeServerTransport(List<ServerTransport> transports) {
        if (transports.isEmpty() || transports.size() > 255) {
            throw new IllegalArgumentException("Composite transport needs between 1 and 255 children");
        }
        this.transports = List.copyOf(transports);
    }

    @Override
    public ServerEvent poll() {
        for (int checked = 0; checked < transports.size(); checked++) {
            int index = (pollCursor + checked) % transports.size();
            ServerEvent event = transports.get(index).poll();
            if (event == null) {
                continue;
            }
            pollCursor = (index + 1) % transports.size();
            long compositeId = compose(index, event.connectionId());
            return switch (event) {
                case ServerEvent.Connected ignored -> new ServerEvent.Connected(compositeId);
                case ServerEvent.Message message -> new ServerEvent.Message(compositeId, message.message());
                case ServerEvent.Disconnected disconnected -> new ServerEvent.Disconnected(compositeId, disconnected.reason());
            };
        }
        return null;
    }

    @Override
    public boolean send(long connectionId, ServerMessage message) {
        int index = transportIndex(connectionId);
        return index < transports.size() && transports.get(index).send(localId(connectionId), message);
    }

    @Override
    public int outboundBacklog(long connectionId) {
        int index = transportIndex(connectionId);
        return index < transports.size() ? transports.get(index).outboundBacklog(localId(connectionId)) : 0;
    }

    @Override
    public void disconnect(long connectionId, String reason) {
        int index = transportIndex(connectionId);
        if (index < transports.size()) {
            transports.get(index).disconnect(localId(connectionId), reason);
        }
    }

    @Override
    public boolean isOpen() {
        return transports.stream().anyMatch(ServerTransport::isOpen);
    }

    @Override
    public void close() {
        for (ServerTransport transport : transports) {
            transport.close();
        }
    }

    private static long compose(int index, long localId) {
        if ((localId & ~LOCAL_ID_MASK) != 0L) {
            throw new IllegalArgumentException("Child connection id is too large");
        }
        return ((long) index << 56) | localId;
    }

    private static int transportIndex(long compositeId) {
        return (int) ((compositeId >>> 56) & 0xFF);
    }

    private static long localId(long compositeId) {
        return compositeId & LOCAL_ID_MASK;
    }
}
