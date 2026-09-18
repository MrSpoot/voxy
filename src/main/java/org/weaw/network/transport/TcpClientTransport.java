package org.weaw.network.transport;

import org.weaw.network.protocol.ClientMessage;
import org.weaw.network.protocol.MessageCodec;
import org.weaw.network.protocol.ServerMessage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TcpClientTransport implements ClientTransport {
    private static final int QUEUE_CAPACITY = 64;

    private final Socket socket;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final ArrayBlockingQueue<ClientMessage> outbound = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final ArrayBlockingQueue<ServerMessage> inbound = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean open = new AtomicBoolean(true);
    private volatile String closeReason = "";
    private final Thread readerThread;
    private final Thread writerThread;

    public TcpClientTransport(String host, int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(Objects.requireNonNull(host, "host"), port), 10_000);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        input = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        output = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        readerThread = Thread.ofVirtual().name("voxy-client-network-read").unstarted(this::readLoop);
        writerThread = Thread.ofVirtual().name("voxy-client-network-write").unstarted(this::writeLoop);
        readerThread.start();
        writerThread.start();
    }

    @Override
    public boolean send(ClientMessage message) {
        return open.get() && outbound.offer(Objects.requireNonNull(message, "message"));
    }

    @Override
    public ServerMessage poll() {
        return inbound.poll();
    }

    @Override
    public int inboundBacklog() {
        return inbound.size();
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }

    @Override
    public String closeReason() {
        return closeReason;
    }

    private void readLoop() {
        try {
            while (open.get()) {
                byte[] frame = FrameIo.readFrame(input);
                if (frame == null) {
                    closeInternal("server closed connection");
                    return;
                }
                inbound.put(MessageCodec.decodeServer(frame));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            closeInternal("network read failed: " + exception.getMessage());
        }
    }

    private void writeLoop() {
        try {
            while (open.get()) {
                ClientMessage message = outbound.take();
                FrameIo.writeFrame(output, MessageCodec.encodeClient(message));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            closeInternal("network write failed: " + exception.getMessage());
        }
    }

    @Override
    public void close() {
        if (open.get()) {
            send(new ClientMessage.Disconnect());
        }
        closeInternal("client closed");
    }

    private void closeInternal(String reason) {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        closeReason = reason;
        if (readerThread != Thread.currentThread()) {
            readerThread.interrupt();
        }
        if (writerThread != Thread.currentThread()) {
            writerThread.interrupt();
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
