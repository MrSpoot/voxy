package org.weaw.network.transport;

import org.junit.jupiter.api.Test;
import org.weaw.network.protocol.ClientMessage;
import org.weaw.network.protocol.ServerMessage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTransportPairTest {
    @Test
    void transportsMessagesInBothDirectionsAndReportsClosure() {
        LocalTransportPair pair = new LocalTransportPair();

        ServerEvent.Connected connected = assertInstanceOf(ServerEvent.Connected.class, pair.server().poll());
        assertTrue(pair.client().send(new ClientMessage.Disconnect()));
        ServerEvent.Message clientMessage = assertInstanceOf(ServerEvent.Message.class, pair.server().poll());
        assertEquals(connected.connectionId(), clientMessage.connectionId());
        assertInstanceOf(ClientMessage.Disconnect.class, clientMessage.message());

        assertTrue(pair.server().send(connected.connectionId(), new ServerMessage.PlayerLeft(9L)));
        ServerMessage.PlayerLeft serverMessage = assertInstanceOf(ServerMessage.PlayerLeft.class, pair.client().poll());
        assertEquals(9L, serverMessage.playerId());

        pair.server().disconnect(connected.connectionId(), "test rejection");
        ServerMessage.Rejected rejection = assertInstanceOf(ServerMessage.Rejected.class, pair.client().poll());
        assertEquals("test rejection", rejection.reason());
        assertEquals("test rejection", pair.client().closeReason());
    }
}
