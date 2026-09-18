package org.weaw.network.transport;

import org.weaw.network.protocol.ClientMessage;
import org.weaw.network.protocol.ServerMessage;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LocalTransportPair {
    private static final long CONNECTION_ID = 1L;
    private static final int EVENT_QUEUE_CAPACITY = 2048;
    private static final int OUTBOUND_QUEUE_CAPACITY = 64;

    private final ArrayBlockingQueue<ServerEvent> serverEvents = new ArrayBlockingQueue<>(EVENT_QUEUE_CAPACITY);
    private final ArrayBlockingQueue<ServerMessage> clientMessages = new ArrayBlockingQueue<>(OUTBOUND_QUEUE_CAPACITY);
    private final AtomicBoolean open = new AtomicBoolean(true);
    private volatile String closeReason = "";
    private final ClientTransport client = new LocalClient();
    private final ServerTransport server = new LocalServer();

    public LocalTransportPair() {
        serverEvents.add(new ServerEvent.Connected(CONNECTION_ID));
    }

    public ClientTransport client() {
        return client;
    }

    public ServerTransport server() {
        return server;
    }

    private void closePair(String reason) {
        if (open.compareAndSet(true, false)) {
            closeReason = reason == null ? "closed" : reason;
            serverEvents.offer(new ServerEvent.Disconnected(CONNECTION_ID, closeReason));
        }
    }

    private final class LocalClient implements ClientTransport {
        @Override
        public boolean send(ClientMessage message) {
            return open.get() && serverEvents.offer(new ServerEvent.Message(CONNECTION_ID, message));
        }

        @Override
        public ServerMessage poll() {
            return clientMessages.poll();
        }

        @Override
        public int inboundBacklog() {
            return clientMessages.size();
        }

        @Override
        public boolean isOpen() {
            return open.get();
        }

        @Override
        public String closeReason() {
            return closeReason;
        }

        @Override
        public void close() {
            closePair("client closed");
        }
    }

    private final class LocalServer implements ServerTransport {
        @Override
        public ServerEvent poll() {
            return serverEvents.poll();
        }

        @Override
        public boolean send(long connectionId, ServerMessage message) {
            return connectionId == CONNECTION_ID && open.get() && clientMessages.offer(message);
        }

        @Override
        public int outboundBacklog(long connectionId) {
            return connectionId == CONNECTION_ID ? clientMessages.size() : 0;
        }

        @Override
        public void disconnect(long connectionId, String reason) {
            if (connectionId == CONNECTION_ID) {
                if (messageReason(reason)) {
                    clientMessages.offer(new ServerMessage.Rejected(reason));
                }
                closePair(reason);
            }
        }

        @Override
        public boolean isOpen() {
            return open.get();
        }

        @Override
        public void close() {
            closePair("server closed");
        }

        private boolean messageReason(String reason) {
            return reason != null && !reason.isBlank();
        }
    }
}
