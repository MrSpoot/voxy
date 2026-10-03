package org.weaw.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.weaw.game.WorldHeightRange;
import org.weaw.game.generation.GenerationConfig;
import org.weaw.game.utils.BlockCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class WorldRepository {
    private final Path worldsDirectory;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final WorldSaveMigrationRegistry migrations;

    public WorldRepository(Path dataDirectory) {
        this(dataDirectory, List.of());
    }

    public WorldRepository(Path dataDirectory, List<WorldSaveMigrator> migrations) {
        worldsDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory")
                .toAbsolutePath().normalize().resolve("worlds");
        List<WorldSaveMigrator> registered = new ArrayList<>(Objects.requireNonNull(migrations, "migrations"));
        registered.add(new WorldSaveV2ToV3Migrator());
        this.migrations = new WorldSaveMigrationRegistry(registered);
    }

    public List<WorldSummary> listWorlds() {
        if (!Files.isDirectory(worldsDirectory)) {
            return List.of();
        }
        List<WorldSummary> summaries = new ArrayList<>();
        try (var entries = Files.list(worldsDirectory)) {
            entries.filter(Files::isDirectory)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .forEach(path -> summaries.add(readSummary(path)));
        } catch (IOException exception) {
            throw new WorldSaveException(
                    WorldSaveException.Kind.IO,
                    worldsDirectory,
                    "Unable to list worlds: " + worldsDirectory,
                    exception
            );
        }
        summaries.sort(Comparator.comparingLong(WorldSummary::lastOpenedAtEpochMillis).reversed());
        return List.copyOf(summaries);
    }

    public void rename(String worldKey, String displayName) {
        Path directory = resolveWorldDirectory(worldKey);
        WorldManifest current = readCurrentManifest(directory, worldKey);
        publishManifest(directory, current, current.renamed(displayName));
    }

    /** Moves a world into the application-owned trash and returns its recovery token. */
    public String moveToTrash(String worldKey) {
        Path source = resolveWorldDirectory(worldKey);
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("Unknown world: " + worldKey);
        }
        String token = Instant.now().toEpochMilli() + "-" + worldKey;
        Path trash = worldsDirectory.resolve(".trash").normalize();
        Path destination = trash.resolve(token).normalize();
        requireWithin(trash, destination, "Trash path escapes the data directory");
        try {
            Files.createDirectories(trash);
            try {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(source, destination);
            }
            return token;
        } catch (IOException exception) {
            throw new WorldSaveException(
                    WorldSaveException.Kind.IO, source, "Unable to move world to trash: " + source, exception
            );
        }
    }

    public void restoreFromTrash(String token) {
        if (token == null || !token.matches("[0-9]+-[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Invalid world recovery token");
        }
        int separator = token.indexOf('-');
        String worldKey = token.substring(separator + 1);
        Path trash = worldsDirectory.resolve(".trash").normalize();
        Path source = trash.resolve(token).normalize();
        Path destination = resolveWorldDirectory(worldKey);
        requireWithin(trash, source, "Trash path escapes the data directory");
        if (Files.exists(destination)) {
            throw new IllegalStateException("A world with key " + worldKey + " already exists");
        }
        try {
            try {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(source, destination);
            }
        } catch (IOException exception) {
            throw new WorldSaveException(
                    WorldSaveException.Kind.IO, source, "Unable to restore world from trash: " + source, exception
            );
        }
    }

    public boolean exists(String worldKey) {
        return Files.isDirectory(resolveWorldDirectory(worldKey));
    }

    public String uniqueWorldKey(String displayName) {
        String base = displayName == null ? "world" : displayName.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("(^[-._]+|[-._]+$)", "");
        if (base.isBlank()) {
            base = "world";
        }
        if (base.length() > 56) {
            base = base.substring(0, 56);
        }
        String candidate = base;
        int suffix = 2;
        while (exists(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    public WorldSaveSession openOrCreate(
            StorageOptions storage,
            GenerationConfig requestedGeneration,
            WorldHeightRange requestedHeightRange,
            BlockCatalog catalog
    ) {
        Path worldDirectory = resolveWorldDirectory(storage.worldKey());
        Path headPath = worldDirectory.resolve("HEAD");
        if (!Files.exists(worldDirectory)) {
            WorldManifest initial = WorldManifest.create(storage, requestedGeneration, requestedHeightRange);
            WorldSaveSession created = WorldSaveSession.create(worldDirectory, initial, catalog, mapper);
            created.saveInitial();
            return created;
        }
        if (!Files.exists(headPath)) {
            Path backup = headPath.resolveSibling("HEAD.bak");
            if (!Files.exists(backup)) {
                throw corrupt(worldDirectory, "World directory exists without a valid HEAD pointer", null);
            }
            headPath = backup;
        }

        HeadPointer head;
        try {
            head = readJson(headPath, HeadPointer.class, "World HEAD is malformed");
        } catch (WorldSaveException exception) {
            Path backup = worldDirectory.resolve("HEAD.bak");
            if (headPath.equals(backup) || !Files.exists(backup)) {
                throw exception;
            }
            headPath = backup;
            head = readJson(headPath, HeadPointer.class, "World HEAD backup is malformed");
        }
        validateHead(head, headPath);
        Path manifestPath = worldDirectory.resolve("manifests").resolve(head.manifestFile()).normalize();
        requireWithin(worldDirectory.resolve("manifests"), manifestPath, "Manifest path escapes the world directory");
        byte[] manifestBytes = readBytes(manifestPath, "Unable to read world manifest");
        if (!ContentHash.sha256(manifestBytes).equals(head.manifestSha256())) {
            throw corrupt(manifestPath, "World manifest hash mismatch", null);
        }
        WorldManifest manifest;
        try {
            manifest = mapper.readValue(manifestBytes, WorldManifest.class);
        } catch (IOException | RuntimeException exception) {
            throw corrupt(manifestPath, "World manifest is malformed", exception);
        }
        manifest = migrations.migrateToCurrent(manifest, manifestPath);
        validateManifest(manifest, storage.worldKey(), manifestPath);
        if (manifest.generation() != head.generation()) {
            throw corrupt(manifestPath, "HEAD generation does not match the world manifest", null);
        }
        if ((storage.seedExplicit() && manifest.seed() != requestedGeneration.seed())
                || (storage.heightExplicit() && (manifest.minChunkY() != requestedHeightRange.minChunkY()
                || manifest.maxChunkY() != requestedHeightRange.maxChunkY()))) {
            throw new WorldSaveException(
                    WorldSaveException.Kind.INCOMPATIBLE,
                    manifestPath,
                    "Existing world generation settings differ from the requested seed or height"
            );
        }
        return WorldSaveSession.open(
                worldDirectory,
                manifest.openedNow().withRuntimeSettings(storage),
                catalog,
                mapper
        );
    }

    private WorldSummary readSummary(Path directory) {
        String key = directory.getFileName().toString();
        try {
            WorldManifest manifest = readCurrentManifest(directory, key);
            return new WorldSummary(
                    key, manifest.worldId(), manifest.displayName(), manifest.seed(),
                    manifest.createdAtEpochMillis(), manifest.lastOpenedAtEpochMillis(),
                    manifest.formatVersion(), WorldSummary.Status.VALID, null
            );
        } catch (WorldSaveException exception) {
            WorldSummary.Status status = exception.kind() == WorldSaveException.Kind.INCOMPATIBLE
                    ? WorldSummary.Status.INCOMPATIBLE
                    : WorldSummary.Status.CORRUPT;
            return new WorldSummary(key, null, key, 0L, 0L, 0L, 0, status, exception.getMessage());
        } catch (RuntimeException exception) {
            return new WorldSummary(
                    key, null, key, 0L, 0L, 0L, 0,
                    WorldSummary.Status.CORRUPT, exception.getMessage()
            );
        }
    }

    private WorldManifest readCurrentManifest(Path directory, String expectedKey) {
        Path headPath = directory.resolve("HEAD");
        if (!Files.exists(headPath)) {
            headPath = directory.resolve("HEAD.bak");
        }
        HeadPointer head = readJson(headPath, HeadPointer.class, "World HEAD is malformed");
        validateHead(head, headPath);
        Path manifestPath = directory.resolve("manifests").resolve(head.manifestFile()).normalize();
        requireWithin(directory.resolve("manifests"), manifestPath, "Manifest path escapes the world directory");
        byte[] bytes = readBytes(manifestPath, "Unable to read world manifest");
        if (!ContentHash.sha256(bytes).equals(head.manifestSha256())) {
            throw corrupt(manifestPath, "World manifest hash mismatch", null);
        }
        try {
            WorldManifest manifest = migrations.migrateToCurrent(mapper.readValue(bytes, WorldManifest.class), manifestPath);
            validateManifest(manifest, expectedKey, manifestPath);
            return manifest;
        } catch (WorldSaveException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw corrupt(manifestPath, "World manifest is malformed", exception);
        }
    }

    private void publishManifest(Path directory, WorldManifest previous, WorldManifest next) {
        try {
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(next);
            String hash = ContentHash.sha256(bytes);
            String file = "%020d-%s.json".formatted(next.generation(), hash);
            AtomicFiles.writeImmutable(directory.resolve("manifests").resolve(file), bytes);
            HeadPointer head = new HeadPointer(
                    1, next.generation(), file, hash,
                    previous.generation(),
                    "%020d-%s.json".formatted(previous.generation(), findManifestHash(directory, previous.generation())),
                    findManifestHash(directory, previous.generation())
            );
            AtomicFiles.write(
                    directory.resolve("HEAD"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(head)
            );
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof WorldSaveException worldSaveException) {
                throw worldSaveException;
            }
            throw new WorldSaveException(
                    WorldSaveException.Kind.IO, directory, "Unable to update world metadata: " + directory, exception
            );
        }
    }

    private String findManifestHash(Path directory, long generation) throws IOException {
        String prefix = "%020d-".formatted(generation);
        try (var entries = Files.list(directory.resolve("manifests"))) {
            String name = entries.map(path -> path.getFileName().toString())
                    .filter(value -> value.startsWith(prefix) && value.endsWith(".json"))
                    .findFirst()
                    .orElseThrow(() -> new IOException("Previous manifest is missing"));
            return name.substring(prefix.length(), name.length() - ".json".length());
        }
    }

    private Path resolveWorldDirectory(String worldKey) {
        String key = StorageOptions.validateKey(worldKey, "world");
        Path directory = worldsDirectory.resolve(key).normalize();
        if (!directory.startsWith(worldsDirectory)) {
            throw new IllegalArgumentException("World path escapes the data directory");
        }
        return directory;
    }

    private <T> T readJson(Path path, Class<T> type, String message) {
        try {
            return mapper.readValue(Files.readAllBytes(path), type);
        } catch (IOException | RuntimeException exception) {
            throw corrupt(path, message, exception);
        }
    }

    private static byte[] readBytes(Path path, String message) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new WorldSaveException(WorldSaveException.Kind.IO, path, message + ": " + path, exception);
        }
    }

    private static void validateHead(HeadPointer head, Path path) {
        if (head == null || head.formatVersion() != 1 || head.generation() < 1L
                || head.manifestFile() == null || head.manifestSha256() == null
                || !head.manifestSha256().matches("[0-9a-f]{64}")) {
            throw corrupt(path, "Invalid world HEAD pointer", null);
        }
    }

    private static void validateManifest(WorldManifest manifest, String expectedKey, Path path) {
        if (manifest == null || manifest.worldId() == null || manifest.generationSettings() == null
                || manifest.worldTime() == null
                || !expectedKey.equals(manifest.worldKey()) || manifest.generation() < 1L
                || manifest.seed() != manifest.generationSettings().seed()
                || manifest.minChunkY() > manifest.maxChunkY()
                || manifest.simulationDistanceChunks() < 2 || manifest.simulationDistanceChunks() > 64
                || manifest.defaultRenderDistanceChunks() < 2 || manifest.defaultRenderDistanceChunks() > 64
                || manifest.autosaveSeconds() < 0) {
            throw corrupt(path, "Invalid world manifest", null);
        }
        if (manifest.formatVersion() != WorldManifest.CURRENT_FORMAT_VERSION) {
            throw corrupt(path, "Migration did not produce the current world format", null);
        }
        if (manifest.generationSettings().generatorVersion() != GenerationConfig.CURRENT_GENERATOR_VERSION) {
            throw new WorldSaveException(
                    WorldSaveException.Kind.INCOMPATIBLE,
                    path,
                    "World generator version " + manifest.generationSettings().generatorVersion()
                            + " is not supported; expected " + GenerationConfig.CURRENT_GENERATOR_VERSION
            );
        }
        for (String section : new String[]{"time", "entities", "fluids"}) {
            if (!manifest.sections().containsKey(section)) {
                throw corrupt(path, "World manifest is missing reserved section " + section, null);
            }
        }
    }

    private static void requireWithin(Path root, Path path, String message) {
        if (!path.startsWith(root.normalize())) {
            throw corrupt(path, message, null);
        }
    }

    static WorldSaveException corrupt(Path path, String message, Throwable cause) {
        return cause == null
                ? new WorldSaveException(WorldSaveException.Kind.CORRUPT, path, message + ": " + path)
                : new WorldSaveException(WorldSaveException.Kind.CORRUPT, path, message + ": " + path, cause);
    }

    public record HeadPointer(
            int formatVersion,
            long generation,
            String manifestFile,
            String manifestSha256,
            Long previousGeneration,
            String previousManifestFile,
            String previousManifestSha256
    ) {
    }
}
