package org.weaw.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class PlayerProfileRepository {
    private final Path profilesDirectory;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public PlayerProfileRepository(Path dataDirectory) {
        this.profilesDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath().normalize().resolve("profiles");
    }

    public List<PlayerProfile> listProfiles() {
        if (!Files.exists(profilesDirectory)) {
            return List.of();
        }
        try (var directories = Files.list(profilesDirectory)) {
            return directories
                    .filter(Files::isDirectory)
                    .map(directory -> directory.resolve("profile.json"))
                    .filter(Files::isRegularFile)
                    .map(path -> {
                        PlayerProfile profile = read(path);
                        validate(profile, path.getParent().getFileName().toString(), path);
                        return profile;
                    })
                    .sorted(Comparator.comparing(PlayerProfile::displayName, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(PlayerProfile::key))
                    .toList();
        } catch (IOException exception) {
            throw io(profilesDirectory, "Unable to list player profiles", exception);
        }
    }

    public Optional<PlayerProfile> find(String profileKey) {
        String key = StorageOptions.validateKey(profileKey, "profile");
        Path profileFile = profilePath(key);
        if (!Files.isRegularFile(profileFile)) {
            return Optional.empty();
        }
        PlayerProfile profile = read(profileFile);
        validate(profile, key, profileFile);
        return Optional.of(profile);
    }

    public PlayerProfile create(String displayName) {
        String normalizedName = validateDisplayName(displayName);
        String baseKey = keyFromDisplayName(normalizedName);
        try {
            Files.createDirectories(profilesDirectory);
            for (int suffix = 1; suffix < 10_000; suffix++) {
                String key = suffix == 1 ? baseKey : suffixedKey(baseKey, suffix);
                Path directory = profilesDirectory.resolve(key).normalize();
                requireWithinRoot(directory);
                try {
                    Files.createDirectory(directory);
                } catch (java.nio.file.FileAlreadyExistsException ignored) {
                    continue;
                }
                long now = System.currentTimeMillis();
                PlayerProfile profile = new PlayerProfile(
                        PlayerProfile.CURRENT_FORMAT_VERSION, UUID.randomUUID(), key, normalizedName, now, now
                );
                write(directory.resolve("profile.json"), profile);
                return profile;
            }
        } catch (IOException exception) {
            throw io(profilesDirectory, "Unable to create player profile", exception);
        }
        throw new IllegalStateException("Unable to allocate a unique player profile key");
    }

    public PlayerProfile rename(String profileKey, String displayName) {
        String key = StorageOptions.validateKey(profileKey, "profile");
        Path profileFile = profilePath(key);
        if (!Files.isRegularFile(profileFile)) {
            throw new IllegalArgumentException("Unknown player profile: " + key);
        }
        PlayerProfile current = read(profileFile);
        validate(current, key, profileFile);
        PlayerProfile renamed = new PlayerProfile(
                current.formatVersion(), current.id(), current.key(), validateDisplayName(displayName),
                current.createdAtEpochMillis(), System.currentTimeMillis()
        );
        write(profileFile, renamed);
        return renamed;
    }

    public PlayerProfile openOrCreate(String profileKey, String requestedDisplayName) {
        String key = StorageOptions.validateKey(profileKey, "profile");
        Path profileFile = profilePath(key);
        Path directory = profileFile.getParent();
        if (Files.exists(profileFile)) {
            PlayerProfile profile = read(profileFile);
            validate(profile, key, profileFile);
            if (requestedDisplayName != null && !requestedDisplayName.isBlank()
                    && !requestedDisplayName.trim().equals(profile.displayName())) {
                profile = new PlayerProfile(
                        profile.formatVersion(), profile.id(), profile.key(), validateDisplayName(requestedDisplayName),
                        profile.createdAtEpochMillis(), System.currentTimeMillis()
                );
                write(profileFile, profile);
            }
            return profile;
        }
        if (Files.exists(directory)) {
            try (var entries = Files.list(directory)) {
                if (entries.findAny().isPresent()) {
                    throw new WorldSaveException(
                            WorldSaveException.Kind.CORRUPT, directory,
                            "Profile directory exists without profile.json: " + directory
                    );
                }
            } catch (IOException exception) {
                throw io(directory, "Unable to inspect profile directory", exception);
            }
        }

        long now = System.currentTimeMillis();
        String fallback = System.getProperty("user.name", "Player").replaceAll("[^A-Za-z0-9_-]", "_");
        if (fallback.isBlank()) {
            fallback = "Player";
        }
        if (fallback.length() > 24) {
            fallback = fallback.substring(0, 24);
        }
        PlayerProfile profile = new PlayerProfile(
                PlayerProfile.CURRENT_FORMAT_VERSION,
                UUID.randomUUID(),
                key,
                validateDisplayName(requestedDisplayName == null || requestedDisplayName.isBlank() ? fallback : requestedDisplayName),
                now,
                now
        );
        write(profileFile, profile);
        return profile;
    }

    private PlayerProfile read(Path path) {
        try {
            return mapper.readValue(Files.readAllBytes(path), PlayerProfile.class);
        } catch (IOException | RuntimeException exception) {
            throw new WorldSaveException(
                    WorldSaveException.Kind.CORRUPT, path,
                    "Profile is malformed and was not overwritten: " + path, exception
            );
        }
    }

    private void write(Path path, PlayerProfile profile) {
        try {
            AtomicFiles.write(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(profile));
        } catch (IOException exception) {
            throw io(path, "Unable to save player profile", exception);
        }
    }

    private static void validate(PlayerProfile profile, String expectedKey, Path path) {
        if (profile == null || profile.id() == null || !expectedKey.equals(profile.key())) {
            throw new WorldSaveException(WorldSaveException.Kind.CORRUPT, path, "Invalid player profile: " + path);
        }
        if (profile.formatVersion() != PlayerProfile.CURRENT_FORMAT_VERSION) {
            throw new WorldSaveException(
                    WorldSaveException.Kind.INCOMPATIBLE, path,
                    "Unsupported player profile version " + profile.formatVersion()
            );
        }
        try {
            validateDisplayName(profile.displayName());
        } catch (IllegalArgumentException exception) {
            throw new WorldSaveException(
                    WorldSaveException.Kind.CORRUPT, path,
                    "Player profile contains an invalid display name: " + path, exception
            );
        }
    }

    public static String validateDisplayName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty() || normalized.length() > 24) {
            throw new IllegalArgumentException("Player name must contain 1 to 24 characters");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (!Character.isLetterOrDigit(character) && character != '_' && character != '-') {
                throw new IllegalArgumentException("Player name contains an unsupported character: " + character);
            }
        }
        return normalized;
    }

    private Path profilePath(String key) {
        Path directory = profilesDirectory.resolve(key).normalize();
        requireWithinRoot(directory);
        return directory.resolve("profile.json");
    }

    private static String keyFromDisplayName(String displayName) {
        String ascii = Normalizer.normalize(displayName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (ascii.isBlank()) {
            ascii = "profile";
        }
        return ascii.substring(0, Math.min(64, ascii.length()));
    }

    private static String suffixedKey(String baseKey, int suffix) {
        String suffixText = "-" + suffix;
        int baseLength = Math.min(baseKey.length(), 64 - suffixText.length());
        return baseKey.substring(0, baseLength) + suffixText;
    }

    private void requireWithinRoot(Path path) {
        if (!path.startsWith(profilesDirectory)) {
            throw new IllegalArgumentException("Profile path escapes the data directory");
        }
    }

    private static WorldSaveException io(Path path, String message, IOException cause) {
        return new WorldSaveException(WorldSaveException.Kind.IO, path, message + ": " + path, cause);
    }
}
