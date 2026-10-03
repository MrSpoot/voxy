package org.weaw.persistence;

import org.weaw.game.WorldHeightRange;
import org.weaw.game.WorldTimeState;
import org.weaw.game.generation.GenerationConfig;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record WorldManifest(
        int formatVersion,
        long generation,
        UUID worldId,
        String worldKey,
        String displayName,
        long seed,
        long createdAtEpochMillis,
        long lastOpenedAtEpochMillis,
        GenerationSettings generationSettings,
        int minChunkY,
        int maxChunkY,
        int simulationDistanceChunks,
        int defaultRenderDistanceChunks,
        int autosaveSeconds,
        WorldTimeState worldTime,
        Map<String, FutureSection> sections,
        Map<String, String> chunks,
        Map<String, String> players
) {
    public static final int CURRENT_FORMAT_VERSION = 3;

    public WorldManifest(int formatVersion, long generation, UUID worldId, String worldKey, String displayName,
                         long seed, long createdAtEpochMillis, long lastOpenedAtEpochMillis,
                         GenerationSettings generationSettings, int minChunkY, int maxChunkY,
                         int simulationDistanceChunks, int defaultRenderDistanceChunks, int autosaveSeconds,
                         Map<String, FutureSection> sections, Map<String, String> chunks, Map<String, String> players) {
        this(formatVersion, generation, worldId, worldKey, displayName, seed, createdAtEpochMillis,
                lastOpenedAtEpochMillis, generationSettings, minChunkY, maxChunkY, simulationDistanceChunks,
                defaultRenderDistanceChunks, autosaveSeconds,
                formatVersion >= CURRENT_FORMAT_VERSION ? WorldTimeState.defaults() : null,
                sections, chunks, players);
    }

    public WorldManifest {
        sections = Map.copyOf(sections == null ? Map.of() : sections);
        chunks = Map.copyOf(chunks == null ? Map.of() : chunks);
        players = Map.copyOf(players == null ? Map.of() : players);
    }

    public static WorldManifest create(
            StorageOptions storage,
            GenerationConfig generation,
            WorldHeightRange heightRange
    ) {
        long now = System.currentTimeMillis();
        Map<String, FutureSection> future = new LinkedHashMap<>();
        future.put("time", new FutureSection(1, true));
        future.put("entities", new FutureSection(0, false));
        future.put("fluids", new FutureSection(0, false));
        return new WorldManifest(
                CURRENT_FORMAT_VERSION,
                0L,
                UUID.randomUUID(),
                storage.worldKey(),
                storage.worldName(),
                generation.seed(),
                now,
                now,
                GenerationSettings.from(generation),
                heightRange.minChunkY(),
                heightRange.maxChunkY(),
                storage.simulationDistanceChunks(),
                storage.defaultRenderDistanceChunks(),
                storage.autosaveSeconds(),
                new WorldTimeState(WorldTimeState.NOON, storage.dayLengthSeconds()),
                future,
                Map.of(),
                Map.of()
        );
    }

    public GenerationConfig generationConfig() {
        return generationSettings.toConfig();
    }

    public WorldHeightRange heightRange() {
        return new WorldHeightRange(minChunkY, maxChunkY);
    }

    public WorldManifest nextGeneration(Map<String, String> nextChunks, Map<String, String> nextPlayers,
                                        WorldTimeState nextWorldTime) {
        return new WorldManifest(
                formatVersion, generation + 1L, worldId, worldKey, displayName, seed,
                createdAtEpochMillis, lastOpenedAtEpochMillis, generationSettings,
                minChunkY, maxChunkY, simulationDistanceChunks, defaultRenderDistanceChunks,
                autosaveSeconds, nextWorldTime, sections, nextChunks, nextPlayers
        );
    }

    public WorldManifest openedNow() {
        return new WorldManifest(
                formatVersion, generation, worldId, worldKey, displayName, seed,
                createdAtEpochMillis, System.currentTimeMillis(), generationSettings,
                minChunkY, maxChunkY, simulationDistanceChunks, defaultRenderDistanceChunks,
                autosaveSeconds, worldTime, sections, chunks, players
        );
    }

    public WorldManifest withRuntimeSettings(StorageOptions storage) {
        return new WorldManifest(
                formatVersion, generation, worldId, worldKey, displayName, seed,
                createdAtEpochMillis, lastOpenedAtEpochMillis, generationSettings,
                minChunkY, maxChunkY,
                storage.simulationDistanceChunks(), storage.defaultRenderDistanceChunks(),
                storage.autosaveSeconds(), worldTime, sections, chunks, players
        );
    }

    public WorldManifest renamed(String nextDisplayName) {
        String normalized = nextDisplayName == null ? "" : nextDisplayName.trim();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException("World name must contain 1 to 128 characters");
        }
        return new WorldManifest(
                formatVersion, generation + 1L, worldId, worldKey, normalized, seed,
                createdAtEpochMillis, lastOpenedAtEpochMillis, generationSettings,
                minChunkY, maxChunkY, simulationDistanceChunks, defaultRenderDistanceChunks,
                autosaveSeconds, worldTime, sections, chunks, players
        );
    }

    public record FutureSection(int version, boolean present) {
    }

    public record GenerationSettings(
            long seed,
            float amplitude,
            int baseHeight,
            int waterLevel,
            float terrainFrequency,
            int terrainOctaves,
            float terrainLacunarity,
            float terrainGain,
            int treeSeedOffset,
            float treeRarity,
            float treeSteepness,
            int generatorVersion
    ) {
        static GenerationSettings from(GenerationConfig config) {
            return new GenerationSettings(
                    config.seed(), config.amplitude(), config.baseHeight(), config.waterLevel(),
                    config.terrainFrequency(), config.terrainOctaves(), config.terrainLacunarity(),
                    config.terrainGain(), config.treeSeedOffset(), config.treeRarity(), config.treeSteepness(),
                    config.generatorVersion()
            );
        }

        GenerationConfig toConfig() {
            return new GenerationConfig(
                    seed, amplitude, baseHeight, waterLevel, terrainFrequency, terrainOctaves,
                    terrainLacunarity, terrainGain, treeSeedOffset, treeRarity, treeSteepness, generatorVersion
            );
        }
    }
}
