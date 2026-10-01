package org.weaw.client.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServerAddressTest {
    @Test
    void parsesHostIpv4AndBracketedIpv6WithoutResolvingDns() {
        assertEquals(new ServerAddress("play.example.org", 25565),
                ServerAddress.parse("play.example.org", 25565));
        assertEquals(new ServerAddress("192.168.1.42", 25570),
                ServerAddress.parse("192.168.1.42:25570", 25565));
        assertEquals(new ServerAddress("2001:db8::1", 25571),
                ServerAddress.parse("[2001:db8::1]:25571", 25565));
        assertEquals("[2001:db8::1]:25571",
                ServerAddress.parse("[2001:db8::1]:25571", 25565).display());
    }

    @Test
    void rejectsMalformedEndpoints() {
        assertThrows(IllegalArgumentException.class, () -> ServerAddress.parse("", 25565));
        assertThrows(IllegalArgumentException.class, () -> ServerAddress.parse("host:abc", 25565));
        assertThrows(IllegalArgumentException.class, () -> ServerAddress.parse("[2001:db8::1", 25565));
        assertThrows(IllegalArgumentException.class, () -> ServerAddress.parse("bad host", 25565));
        assertThrows(IllegalArgumentException.class, () -> ServerAddress.parse("999.1.2.3", 25565));
        assertThrows(IllegalArgumentException.class, () -> ServerAddress.parse("[2001:::1]", 25565));
    }
}
