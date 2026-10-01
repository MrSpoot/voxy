package org.weaw.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientSettingsRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndRestoresVersionedSettingsWithRecentServers() {
        ClientSettingsRepository repository = new ClientSettingsRepository(temporaryDirectory);
        ClientSettings expected = ClientSettings.defaults()
                .withRecentServer("example.org", 25570)
                .withRecentServer("2001:db8::1", 25571);

        repository.save(expected);

        assertEquals(expected, repository.load());
    }

    @Test
    void malformedSettingsAreRejectedWithoutBeingOverwritten() throws Exception {
        Path path = temporaryDirectory.resolve("client-settings.json");
        byte[] malformed = "{ definitely-not-json".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(path, malformed);

        WorldSaveException exception = assertThrows(
                WorldSaveException.class,
                () -> new ClientSettingsRepository(temporaryDirectory).load()
        );

        assertEquals(WorldSaveException.Kind.CORRUPT, exception.kind());
        assertArrayEquals(malformed, Files.readAllBytes(path));
    }

    @Test
    void olderSettingsWithoutAnActiveProfileUseDefault() throws Exception {
        Path path = temporaryDirectory.resolve("client-settings.json");
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(ClientSettings.defaults())
                .replace("\"activeProfileKey\":\"default\",", "");
        Files.writeString(path, json);

        ClientSettings loaded = new ClientSettingsRepository(temporaryDirectory).load();

        assertEquals("default", loaded.activeProfileKey());
    }

    @Test
    void migratesVersionOneGraphicsFieldsToDefaults() throws Exception {
        Path path = temporaryDirectory.resolve("client-settings.json");
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(ClientSettings.defaults())
                .replace("\"formatVersion\":2", "\"formatVersion\":1")
                .replaceAll(",?\"frameRateLimit\":0", "")
                .replaceAll(",?\"graphicsPreferences\":\\{[^}]*}", "");
        Files.writeString(path, json);

        ClientSettings loaded = new ClientSettingsRepository(temporaryDirectory).load();

        assertEquals(ClientSettings.CURRENT_FORMAT_VERSION, loaded.formatVersion());
        assertEquals(0, loaded.frameRateLimit());
        assertEquals(GraphicsPreferences.defaults(), loaded.graphicsPreferences());
    }
}
