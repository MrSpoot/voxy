package org.weaw.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Atomic storage for global client preferences. */
public final class ClientSettingsRepository {
    private final Path settingsPath;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public ClientSettingsRepository(Path dataDirectory) {
        settingsPath = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath().normalize().resolve("client-settings.json");
    }

    public ClientSettings load() {
        if (!Files.exists(settingsPath)) {
            return ClientSettings.defaults();
        }
        try {
            JsonNode parsed = mapper.readTree(Files.readAllBytes(settingsPath));
            if (!(parsed instanceof ObjectNode root)) {
                throw new IOException("Client settings root must be an object");
            }
            int version = root.path("formatVersion").asInt(1);
            if (version == 1) {
                root.put("formatVersion", ClientSettings.CURRENT_FORMAT_VERSION);
                if (!root.has("frameRateLimit")) {
                    root.put("frameRateLimit", 0);
                }
                root.set("graphicsPreferences", mapper.valueToTree(GraphicsPreferences.defaults()));
            } else if (version != ClientSettings.CURRENT_FORMAT_VERSION) {
                throw new WorldSaveException(
                        WorldSaveException.Kind.INCOMPATIBLE,
                        settingsPath,
                        "Unsupported client settings version " + version
                );
            }
            return mapper.treeToValue(root, ClientSettings.class);
        } catch (WorldSaveException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new WorldSaveException(
                    WorldSaveException.Kind.CORRUPT,
                    settingsPath,
                    "Client settings are malformed and were not overwritten: " + settingsPath,
                    exception
            );
        }
    }

    public void save(ClientSettings settings) {
        Objects.requireNonNull(settings, "settings");
        try {
            AtomicFiles.write(
                    settingsPath,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(settings)
            );
        } catch (IOException exception) {
            throw new WorldSaveException(
                    WorldSaveException.Kind.IO,
                    settingsPath,
                    "Unable to save client settings: " + settingsPath,
                    exception
            );
        }
    }
}
