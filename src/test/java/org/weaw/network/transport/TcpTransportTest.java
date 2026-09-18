package org.weaw.network.transport;

import org.junit.jupiter.api.Test;
import org.weaw.network.protocol.ClientMessage;
import org.weaw.network.protocol.Protocol;
import org.weaw.network.protocol.ServerMessage;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcpTransportTest {
    @Test
    void carriesFramedMessagesAndDeliversARejectionBeforeClosing() throws IOException {
        try (TcpServerTransport server = new TcpServerTransport(0);
             TcpClientTransport client = new TcpClientTransport("127.0.0.1", server.localPort())) {
            ServerEvent.Connected connected = assertInstanceOf(
                    ServerEvent.Connected.class,
                    await(server::poll)
            );
            ClientMessage.Hello hello = new ClientMessage.Hello(Protocol.VERSION, 123L, "Alice", 8);
            assertTrue(client.send(hello));

            ServerEvent.Message received = assertInstanceOf(ServerEvent.Message.class, await(server::poll));
            assertEquals(connected.connectionId(), received.connectionId());
            assertEquals(hello, received.message());

            server.disconnect(connected.connectionId(), "incompatible test client");

            ServerMessage.Rejected rejection = assertInstanceOf(ServerMessage.Rejected.class, await(client::poll));
            assertEquals("incompatible test client", rejection.reason());
        }
    }

    private static <T> T await(Supplier<T> supplier) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        T value;
        while ((value = supplier.get()) == null && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertNotNull(value, "Timed out waiting for network event");
        return value;
    }
}
