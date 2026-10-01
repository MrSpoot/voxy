package org.weaw.persistence;

import java.nio.file.Path;
import java.util.Objects;

public record StorageOptions(
        Path dataDirectory,
        String worldKey,
        String worldName,
        String profileKey,
        String requestedPlayerName,
        int autosaveSeconds,
        int simulationDistanceChunks,
        int defaultRenderDistanceChunks,
        boolean seedExplicit,
        boolean heightExplicit
) {
    public static final int DEFAULT_AUTOSAVE_SECONDS = 60;

    public StorageOptions {
        dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath().normalize();
        worldKey = validateKey(worldKey, "world");
        profileKey = validateKey(profileKey, "profile");
        requestedPlayerName = requestedPlayerName == null || requestedPlayerName.isBlank()
                ? null
                : requestedPlayerName.trim();
        worldName = worldName == null || worldName.isBlank() ? worldKey : worldName.trim();
        if (worldName.length() > 128) {
            throw new IllegalArgumentException("World name cannot exceed 128 characters");
        }
        if (autosaveSeconds < 0) {
            throw new IllegalArgumentException("Autosave interval cannot be negative");
        }
        if (simulationDistanceChunks < 2 || simulationDistanceChunks > 64) {
            throw new IllegalArgumentException("Simulation distance must be in range [2, 64]");
        }
        if (defaultRenderDistanceChunks < 2 || defaultRenderDistanceChunks > 64) {
            throw new IllegalArgumentException("Default render distance must be in range [2, 64]");
        }
    }

    public static String validateKey(String value, String label) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException("Invalid " + label + " key: " + value);
        }
        return value;
    }
}
