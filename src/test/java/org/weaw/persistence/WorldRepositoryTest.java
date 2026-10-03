package org.weaw.persistence;

import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.weaw.game.World;
import org.weaw.game.WorldMemoryBudget;
import org.weaw.game.WorldSettings;
import org.weaw.game.WorldTimeState;
import org.weaw.game.generation.GenerationConfig;
import org.weaw.game.generation.NoiseWorldGenerator;
import org.weaw.game.utils.BlockCatalog;
import org.weaw.game.utils.BlockRegistry;
import org.weaw.game.utils.Blocks;
import org.weaw.gameplay.GameplaySession;
import org.weaw.gameplay.GameplaySettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @BeforeAll
    static void initializeBlocks() {
        BlockRegistry.initialize();
    }

    @Test
    void restoresSeedChunkEditsAndPlayerStateAfterRestart() {
        StorageOptions storage = storage("alpha");
        UUID profileId = UUID.randomUUID();
        GenerationConfig requested = GenerationConfig.defaults().withSeed(1234L);

        WorldSaveSession firstSave = open(storage, requested);
        World firstWorld = createWorld(firstSave);
        firstWorld.setDynamicLightingEnabled(false);
        assertTrue(firstWorld.trySetBlockAtWorld(3, 4, 5, Blocks.RED_LAMP));
        GameplaySession gameplay = new GameplaySession(firstWorld, new GameplaySettings());
        gameplay.setPlayerPose(new Vector3f(12.5f, 33.0f, -7.25f), 42.0f, -12.0f);
        gameplay.getPlayer().setNoclip(true);
        gameplay.getHotbar().select(4);
        firstSave.saveNow(firstWorld, List.of(PlayerSaveState.capture(profileId, "Alice", gameplay)));
        firstSave.close();
        firstWorld.close();

        WorldSaveSession reopened = open(storage, requested);
        World restoredWorld = createWorld(reopened);
        restoredWorld.setDynamicLightingEnabled(false);
        assertEquals(1234L, reopened.manifest().seed());
        assertEquals(
                GenerationConfig.CURRENT_GENERATOR_VERSION,
                reopened.manifest().generationSettings().generatorVersion()
        );
        assertEquals(BlockRegistry.getRuntimeId(Blocks.RED_LAMP), restoredWorld.getBlockAtWorld(3, 4, 5));

        GameplaySession restoredGameplay = new GameplaySession(restoredWorld, new GameplaySettings());
        reopened.playerState(profileId).restore(restoredGameplay, BlockRegistry.getDefaultCatalog());
        assertEquals(new Vector3f(12.5f, 33.0f, -7.25f), restoredGameplay.getPlayer().getPosition());
        assertEquals(42.0f, restoredGameplay.getPlayer().getYaw());
        assertTrue(restoredGameplay.getPlayer().isNoclip());
        assertEquals(4, restoredGameplay.getHotbar().getSelectedIndex());
        reopened.close();
        restoredWorld.close();
    }

    @Test
    void differentWorldKeysNeverShareState() {
        GenerationConfig alphaGeneration = GenerationConfig.defaults().withSeed(11L);
        GenerationConfig betaGeneration = GenerationConfig.defaults().withSeed(22L);
        WorldSaveSession alpha = open(storage("alpha"), alphaGeneration);
        World alphaWorld = createWorld(alpha);
        alphaWorld.setDynamicLightingEnabled(false);
        alphaWorld.trySetBlockAtWorld(1, 2, 3, Blocks.BLUE_LAMP);
        alpha.saveNow(alphaWorld, List.of());
        UUID alphaId = alpha.manifest().worldId();
        alpha.close();
        alphaWorld.close();

        WorldSaveSession beta = open(storage("beta"), betaGeneration);
        World betaWorld = createWorld(beta);
        betaWorld.setDynamicLightingEnabled(false);
        assertNotEquals(alphaId, beta.manifest().worldId());
        assertEquals(22L, beta.manifest().seed());
        assertNotEquals(BlockRegistry.getRuntimeId(Blocks.BLUE_LAMP), betaWorld.getBlockAtWorld(1, 2, 3));
        beta.close();
        betaWorld.close();
    }

    @Test
    void restoresWorldTimeAfterRestart() {
        StorageOptions storage = storage("world-time");
        GenerationConfig generation = GenerationConfig.defaults().withSeed(52L);
        WorldSaveSession session = open(storage, generation);
        World world = createWorld(session);
        WorldTimeState savedTime = new WorldTimeState(0.8125, storage.dayLengthSeconds());
        session.saveNow(world, List.of(), savedTime);
        session.close();
        world.close();

        WorldSaveSession reopened = open(storage, generation);
        assertEquals(savedTime, reopened.manifest().worldTime());
        reopened.close();
    }

    @Test
    void migratesVersionTwoTimeToNoon() {
        WorldManifest current = WorldManifest.create(
                storage("legacy-time"), GenerationConfig.defaults(), org.weaw.game.WorldHeightRange.DEFAULT);
        WorldManifest versionTwo = copyWithVersion(current, 2);

        WorldManifest migrated = new WorldSaveMigrationRegistry(List.of(new WorldSaveV2ToV3Migrator()))
                .migrateToCurrent(versionTwo, temporaryDirectory.resolve("v2.json"));

        assertEquals(WorldTimeState.defaults(), migrated.worldTime());
        assertEquals(new WorldManifest.FutureSection(1, true), migrated.sections().get("time"));
    }

    @Test
    void missingHeadFallsBackToTheLastCommittedGeneration() throws Exception {
        StorageOptions storage = storage("recoverable");
        GenerationConfig generation = GenerationConfig.defaults().withSeed(77L);
        WorldSaveSession session = open(storage, generation);
        World world = createWorld(session);
        world.setDynamicLightingEnabled(false);
        world.trySetBlockAtWorld(2, 2, 2, Blocks.GREEN_LAMP);
        session.saveNow(world, List.of());
        session.close();
        world.close();

        Path head = temporaryDirectory.resolve("worlds/recoverable/HEAD");
        assertTrue(Files.exists(head.resolveSibling("HEAD.bak")));
        Files.delete(head);

        WorldSaveSession recovered = open(storage, generation);
        World recoveredWorld = createWorld(recovered);
        assertNotEquals(BlockRegistry.getRuntimeId(Blocks.GREEN_LAMP), recoveredWorld.getBlockAtWorld(2, 2, 2));
        recovered.close();
        recoveredWorld.close();
    }

    @Test
    void corruptObjectIsRejectedWithoutBeingRewritten() throws Exception {
        StorageOptions storage = storage("corrupt");
        GenerationConfig generation = GenerationConfig.defaults().withSeed(99L);
        WorldSaveSession session = open(storage, generation);
        World world = createWorld(session);
        world.setDynamicLightingEnabled(false);
        world.trySetBlockAtWorld(4, 4, 4, Blocks.STONE);
        session.saveNow(world, List.of());
        String hash = session.manifest().chunks().values().iterator().next();
        session.close();
        world.close();

        Path object = temporaryDirectory.resolve("worlds/corrupt/objects/chunks/" + hash + ".vxc");
        byte[] corruptBytes = new byte[]{1, 2, 3, 4};
        Files.write(object, corruptBytes);

        WorldSaveException exception = assertThrows(
                WorldSaveException.class,
                () -> open(storage, generation)
        );
        assertEquals(WorldSaveException.Kind.CORRUPT, exception.kind());
        assertArrayEquals(corruptBytes, Files.readAllBytes(object));
    }

    @Test
    void migrationRegistryRequiresAdjacentExplicitMigrations() {
        StorageOptions storage = storage("migration");
        WorldManifest current = WorldManifest.create(
                storage,
                GenerationConfig.defaults().withSeed(1L),
                org.weaw.game.WorldHeightRange.DEFAULT
        );
        WorldManifest versionOne = copyWithVersion(current, 1);
        Path path = temporaryDirectory.resolve("legacy-manifest.json");

        assertEquals(
                WorldSaveException.Kind.INCOMPATIBLE,
                assertThrows(
                        WorldSaveException.class,
                        () -> new WorldSaveMigrationRegistry(List.of()).migrateToCurrent(versionOne, path)
                ).kind()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new WorldSaveMigrationRegistry(List.of(new WorldSaveMigrator() {
                    @Override
                    public int sourceVersion() {
                        return 0;
                    }

                    @Override
                    public int targetVersion() {
                        return 2;
                    }

                    @Override
                    public WorldManifest migrate(WorldManifest source) {
                        return copyWithVersion(source, 2);
                    }
                }))
        );
    }

    @Test
    void rejectsWorldFromPreviousGeneratorVersion() {
        StorageOptions storage = storage("previous-generator");
        GenerationConfig current = GenerationConfig.defaults().withSeed(42L);
        int previousVersion = current.generatorVersion() - 1;
        GenerationConfig previous = new GenerationConfig(
                current.seed(),
                current.amplitude(),
                current.baseHeight(),
                current.waterLevel(),
                current.terrainFrequency(),
                current.terrainOctaves(),
                current.terrainLacunarity(),
                current.terrainGain(),
                current.treeSeedOffset(),
                current.treeRarity(),
                current.treeSteepness(),
                previousVersion
        );
        open(storage, previous).close();

        WorldSaveException exception = assertThrows(
                WorldSaveException.class,
                () -> open(storage, current)
        );

        assertEquals(WorldSaveException.Kind.INCOMPATIBLE, exception.kind());
        assertTrue(exception.getMessage().contains("World generator version " + previousVersion));
    }

    @Test
    void listsRenamesAndRecoversWorldsFromTrash() {
        WorldRepository repository = new WorldRepository(temporaryDirectory);
        WorldSaveSession created = open(storage("menu-world"), GenerationConfig.defaults().withSeed(314L));
        created.close();

        assertEquals(1, repository.listWorlds().size());
        assertEquals("menu-world", repository.listWorlds().getFirst().displayName());

        repository.rename("menu-world", "Mon monde");
        assertEquals("Mon monde", repository.listWorlds().getFirst().displayName());

        String recoveryToken = repository.moveToTrash("menu-world");
        assertTrue(repository.listWorlds().isEmpty());
        repository.restoreFromTrash(recoveryToken);
        assertEquals("Mon monde", repository.listWorlds().getFirst().displayName());
    }

    private WorldSaveSession open(StorageOptions storage, GenerationConfig generation) {
        BlockCatalog catalog = BlockRegistry.getDefaultCatalog();
        return new WorldRepository(temporaryDirectory).openOrCreate(
                storage, generation, org.weaw.game.WorldHeightRange.DEFAULT, catalog
        );
    }

    private static World createWorld(WorldSaveSession save) {
        WorldManifest manifest = save.manifest();
        return new World(
                new NoiseWorldGenerator(manifest.generationConfig()),
                new WorldSettings(
                        manifest.simulationDistanceChunks(),
                        manifest.defaultRenderDistanceChunks(),
                        manifest.heightRange(),
                        WorldMemoryBudget.balanced(),
                        false
                ),
                BlockRegistry.getDefaultCatalog(),
                save.consumeInitialEdits()
        );
    }

    private StorageOptions storage(String worldKey) {
        return new StorageOptions(
                temporaryDirectory,
                worldKey,
                worldKey,
                "default",
                "Alice",
                60,
                2,
                2,
                false,
                false
        );
    }

    private static WorldManifest copyWithVersion(WorldManifest source, int version) {
        return new WorldManifest(
                version,
                source.generation(),
                source.worldId(),
                source.worldKey(),
                source.displayName(),
                source.seed(),
                source.createdAtEpochMillis(),
                source.lastOpenedAtEpochMillis(),
                source.generationSettings(),
                source.minChunkY(),
                source.maxChunkY(),
                source.simulationDistanceChunks(),
                source.defaultRenderDistanceChunks(),
                source.autosaveSeconds(),
                source.sections(),
                Map.of(),
                Map.of()
        );
    }
}
