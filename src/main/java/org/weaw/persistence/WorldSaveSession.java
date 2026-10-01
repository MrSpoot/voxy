package org.weaw.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.weaw.game.ChunkManager.ChunkPosition;
import org.weaw.game.World;
import org.weaw.game.utils.BlockCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WorldSaveSession implements AutoCloseable {
    private final Path worldDirectory;
    private final BlockCatalog catalog;
    private final ObjectMapper mapper;
    private final ChunkEditCodec chunkCodec = new ChunkEditCodec();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("voxy-save-writer").factory()
    );
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Map<UUID, PlayerSaveState> loadedPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerSaveState> rememberedPlayers = new LinkedHashMap<>();
    private volatile WorldManifest manifest;
    private Map<ChunkPosition, Map<Integer, Short>> initialEdits;
    private CompletableFuture<Void> pendingWrite = CompletableFuture.completedFuture(null);

    static WorldSaveSession create(
            Path worldDirectory,
            WorldManifest manifest,
            BlockCatalog catalog,
            ObjectMapper mapper
    ) {
        return new WorldSaveSession(worldDirectory, manifest, catalog, mapper, Map.of(), Map.of());
    }

    static WorldSaveSession open(
            Path worldDirectory,
            WorldManifest manifest,
            BlockCatalog catalog,
            ObjectMapper mapper
    ) {
        Map<ChunkPosition, Map<Integer, Short>> edits = loadChunkEdits(worldDirectory, manifest, catalog);
        Map<UUID, PlayerSaveState> players = loadPlayers(worldDirectory, manifest, catalog, mapper);
        return new WorldSaveSession(worldDirectory, manifest, catalog, mapper, edits, players);
    }

    private WorldSaveSession(
            Path worldDirectory,
            WorldManifest manifest,
            BlockCatalog catalog,
            ObjectMapper mapper,
            Map<ChunkPosition, Map<Integer, Short>> initialEdits,
            Map<UUID, PlayerSaveState> players
    ) {
        this.worldDirectory = worldDirectory;
        this.manifest = manifest;
        this.catalog = catalog;
        this.mapper = mapper;
        this.initialEdits = Map.copyOf(initialEdits);
        this.loadedPlayers.putAll(players);
        this.rememberedPlayers.putAll(players);
    }

    void saveInitial() {
        commit(Map.of(), List.of());
    }

    public WorldManifest manifest() {
        return manifest;
    }

    public Map<ChunkPosition, Map<Integer, Short>> consumeInitialEdits() {
        Map<ChunkPosition, Map<Integer, Short>> result = initialEdits;
        initialEdits = Map.of();
        return result;
    }

    public PlayerSaveState playerState(UUID profileId) {
        return loadedPlayers.get(profileId);
    }

    public synchronized void rememberPlayer(PlayerSaveState state) {
        Objects.requireNonNull(state, "state").validate(catalog);
        rememberedPlayers.put(state.profileId(), state);
    }

    public synchronized CompletableFuture<Void> saveAsync(World world, Collection<PlayerSaveState> players) {
        ensureOpen();
        Map<ChunkPosition, Map<Integer, Short>> edits = world.snapshotSessionEdits();
        List<PlayerSaveState> playerSnapshot = rememberAndSnapshot(players);
        pendingWrite = pendingWrite.handle((ignored, failure) -> null)
                .thenRunAsync(() -> commit(edits, playerSnapshot), writer);
        return pendingWrite;
    }

    public void saveNow(World world, Collection<PlayerSaveState> players) {
        saveAsync(world, players).join();
    }

    private synchronized List<PlayerSaveState> rememberAndSnapshot(Collection<PlayerSaveState> players) {
        for (PlayerSaveState player : players) {
            player.validate(catalog);
            rememberedPlayers.put(player.profileId(), player);
        }
        return List.copyOf(rememberedPlayers.values());
    }

    private synchronized void commit(
            Map<ChunkPosition, Map<Integer, Short>> edits,
            Collection<PlayerSaveState> players
    ) {
        try {
            Map<String, String> chunkRefs = new LinkedHashMap<>();
            for (Map.Entry<ChunkPosition, Map<Integer, Short>> entry : edits.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    continue;
                }
                byte[] bytes = chunkCodec.encode(entry.getKey(), entry.getValue(), catalog);
                String hash = ContentHash.sha256(bytes);
                AtomicFiles.writeImmutable(worldDirectory.resolve("objects/chunks").resolve(hash + ".vxc"), bytes);
                chunkRefs.put(chunkKey(entry.getKey()), hash);
            }

            Map<String, String> playerRefs = new LinkedHashMap<>();
            for (PlayerSaveState player : players) {
                byte[] bytes = mapper.writeValueAsBytes(player);
                String hash = ContentHash.sha256(bytes);
                AtomicFiles.writeImmutable(worldDirectory.resolve("objects/players").resolve(hash + ".json"), bytes);
                playerRefs.put(player.profileId().toString(), hash);
                loadedPlayers.put(player.profileId(), player);
            }

            WorldManifest previous = manifest;
            WorldManifest next = previous.nextGeneration(chunkRefs, playerRefs);
            byte[] manifestBytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(next);
            String manifestHash = ContentHash.sha256(manifestBytes);
            String manifestFile = "%020d-%s.json".formatted(next.generation(), manifestHash);
            AtomicFiles.writeImmutable(worldDirectory.resolve("manifests").resolve(manifestFile), manifestBytes);

            WorldRepository.HeadPointer head = new WorldRepository.HeadPointer(
                    1, next.generation(), manifestFile, manifestHash,
                    null,
                    null,
                    null
            );
            AtomicFiles.write(
                    worldDirectory.resolve("HEAD"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(head)
            );
            manifest = next;
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof WorldSaveException worldSaveException) {
                throw worldSaveException;
            }
            throw new WorldSaveException(
                    WorldSaveException.Kind.IO, worldDirectory,
                    "Unable to commit world save: " + worldDirectory, exception
            );
        }
    }

    private static Map<ChunkPosition, Map<Integer, Short>> loadChunkEdits(
            Path worldDirectory,
            WorldManifest manifest,
            BlockCatalog catalog
    ) {
        ChunkEditCodec codec = new ChunkEditCodec();
        Map<ChunkPosition, Map<Integer, Short>> edits = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : manifest.chunks().entrySet()) {
            String hash = validateHash(entry.getValue(), worldDirectory);
            Path path = worldDirectory.resolve("objects/chunks").resolve(hash + ".vxc");
            byte[] bytes = readAndVerify(path, hash);
            try {
                ChunkEditCodec.DecodedChunk decoded = codec.decode(bytes, catalog);
                if (!entry.getKey().equals(chunkKey(decoded.position()))) {
                    throw new IOException("Chunk key does not match object coordinates");
                }
                edits.put(decoded.position(), decoded.edits());
            } catch (IOException exception) {
                throw WorldRepository.corrupt(path, "Invalid chunk edit object", exception);
            }
        }
        return edits;
    }

    private static Map<UUID, PlayerSaveState> loadPlayers(
            Path worldDirectory,
            WorldManifest manifest,
            BlockCatalog catalog,
            ObjectMapper mapper
    ) {
        Map<UUID, PlayerSaveState> players = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : manifest.players().entrySet()) {
            UUID id;
            try {
                id = UUID.fromString(entry.getKey());
            } catch (IllegalArgumentException exception) {
                throw WorldRepository.corrupt(worldDirectory, "Invalid profile UUID in world manifest", exception);
            }
            String hash = validateHash(entry.getValue(), worldDirectory);
            Path path = worldDirectory.resolve("objects/players").resolve(hash + ".json");
            byte[] bytes = readAndVerify(path, hash);
            try {
                PlayerSaveState state = mapper.readValue(bytes, PlayerSaveState.class);
                state.validate(catalog);
                if (!id.equals(state.profileId())) {
                    throw new IllegalArgumentException("Player object profile UUID mismatch");
                }
                players.put(id, state);
            } catch (IOException | RuntimeException exception) {
                throw WorldRepository.corrupt(path, "Invalid player save object", exception);
            }
        }
        return players;
    }

    private static byte[] readAndVerify(Path path, String expectedHash) {
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (!ContentHash.sha256(bytes).equals(expectedHash)) {
                throw WorldRepository.corrupt(path, "Save object hash mismatch", null);
            }
            return bytes;
        } catch (IOException exception) {
            throw new WorldSaveException(WorldSaveException.Kind.IO, path, "Unable to read save object: " + path, exception);
        }
    }

    private static String validateHash(String hash, Path path) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw WorldRepository.corrupt(path, "Invalid content hash in world manifest", null);
        }
        return hash;
    }

    private static String chunkKey(ChunkPosition position) {
        return position.x() + "," + position.y() + "," + position.z();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("World save session is closed");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            pendingWrite.join();
        } finally {
            writer.shutdown();
        }
    }
}
