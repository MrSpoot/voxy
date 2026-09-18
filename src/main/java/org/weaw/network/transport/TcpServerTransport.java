package org.weaw.network.transport;

import org.weaw.network.protocol.ClientMessage;
import org.weaw.network.protocol.MessageCodec;
import org.weaw.network.protocol.ServerMessage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

public final class TcpServerTransport implements ServerTransport {
    private static final int EVENT_QUEUE_CAPACITY = 8192;
    private static final int CLIENT_QUEUE_CAPACITY = 64;

    private final ServerSocket serverSocket;
    private final ArrayBlockingQueue<ServerEvent> events = new ArrayBlockingQueue<>(EVENT_QUEUE_CAPACITY);
    private final Map<Long, Peer> peers = new ConcurrentHashMap<>();
    private final AtomicLong nextConnectionId = new AtomicLong(1L);
    private final AtomicBoolean open = new AtomicBoolean(true);

    public TcpServerTransport(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        Thread.ofVirtual().name("voxy-server-network-accept").start(this::acceptLoop);
    }

    public int localPort() {
        return serverSocket.getLocalPort();
    }

    @Override
    public ServerEvent poll() {
        return events.poll();
    }

    @Override
    public boolean send(long connectionId, ServerMessage message) {
        Peer peer = peers.get(connectionId);
        if (peer == null || peer.closing.get() || !peer.outbound.offer(message)) {
            return false;
        }
        return true;
    }

    @Override
    public int outboundBacklog(long connectionId) {
        Peer peer = peers.get(connectionId);
        return peer == null ? 0 : peer.outbound.size() + peer.inFlight.get();
    }

    @Override
    public void disconnect(long connectionId, String reason) {
        Peer peer = peers.get(connectionId);
        if (peer != null) {
            if (reason != null && !reason.isBlank()) {
                peer.rejectAndClose(reason);
            } else {
                peer.close("disconnected");
            }
        }
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    private void acceptLoop() {
        try {
            while (open.get()) {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                long connectionId = nextConnectionId.getAndIncrement();
                Peer peer = new Peer(connectionId, socket);
                peers.put(connectionId, peer);
                events.put(new ServerEvent.Connected(connectionId));
                peer.start();
            }
        } catch (SocketException ignored) {
            // Expected while closing the listening socket.
        } catch (IOException | InterruptedException exception) {
            if (open.get()) {
                close();
            }
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        for (Peer peer : peers.values()) {
            peer.close("server closed");
        }
        peers.clear();
    }

    private final class Peer {
        private final long id;
        private final Socket socket;
        private final DataInputStream input;
        private final DataOutputStream output;
        private final ArrayBlockingQueue<ServerMessage> outbound = new ArrayBlockingQueue<>(CLIENT_QUEUE_CAPACITY);
        private final AtomicBoolean connected = new AtomicBoolean(true);
        private final AtomicBoolean closing = new AtomicBoolean();
        private final AtomicInteger inFlight = new AtomicInteger();
        private Thread readerThread;
        private Thread writerThread;

        private Peer(long id, Socket socket) throws IOException {
            this.id = id;
            this.socket = socket;
            input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        private void start() {
            readerThread = Thread.ofVirtual().name("voxy-peer-" + id + "-read").unstarted(this::readLoop);
            writerThread = Thread.ofVirtual().name("voxy-peer-" + id + "-write").unstarted(this::writeLoop);
            readerThread.start();
            writerThread.start();
        }

        private void readLoop() {
            try {
                while (connected.get()) {
                    byte[] frame = FrameIo.readFrame(input);
                    if (frame == null) {
                        close("client closed connection");
                        return;
                    }
                    ClientMessage message = MessageCodec.decodeClient(frame);
                    if (!events.offer(new ServerEvent.Message(id, message))) {
                        close("server event queue overflow");
                        return;
                    }
                }
            } catch (IOException exception) {
                close("network read failed: " + exception.getMessage());
            }
        }

        private void writeLoop() {
            try {
                while (connected.get()) {
                    ServerMessage message = outbound.take();
                    inFlight.incrementAndGet();
                    try {
                        synchronized (output) {
                            FrameIo.writeFrame(output, MessageCodec.encodeServer(message));
                        }
                    } finally {
                        inFlight.decrementAndGet();
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                close("network write failed: " + exception.getMessage());
            }
        }

        private void rejectAndClose(String reason) {
            if (!connected.get() || !closing.compareAndSet(false, true)) {
                return;
            }
            outbound.clear();
            outbound.offer(new ServerMessage.Rejected(reason));
            Thread.ofVirtual().name("voxy-peer-" + id + "-close").start(() -> {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    close(reason);
                }
            });
        }

        private void close(String reason) {
            if (!connected.compareAndSet(true, false)) {
                return;
            }
            peers.remove(id, this);
            if (readerThread != null && readerThread != Thread.currentThread()) {
                readerThread.interrupt();
            }
            if (writerThread != null && writerThread != Thread.currentThread()) {
                writerThread.interrupt();
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            events.offer(new ServerEvent.Disconnected(id, reason));
        }
    }
}
