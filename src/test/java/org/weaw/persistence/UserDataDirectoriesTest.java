package org.weaw.persistence;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserDataDirectoriesTest {
    @Test
    void resolvesPlatformSpecificDataDirectories() {
        assertEquals(
                Path.of("C:/Users/Alice/AppData/Roaming", "Voxy"),
                UserDataDirectories.resolve(
                        "Windows 11",
                        Map.of("APPDATA", "C:/Users/Alice/AppData/Roaming"),
                        "C:/Users/Alice"
                )
        );
        assertEquals(
                Path.of("/data/alice", "voxy"),
                UserDataDirectories.resolve("Linux", Map.of("XDG_DATA_HOME", "/data/alice"), "/home/alice")
        );
        assertEquals(
                Path.of("/Users/alice", "Library", "Application Support", "Voxy"),
                UserDataDirectories.resolve("Mac OS X", Map.of(), "/Users/alice")
        );
    }
}
