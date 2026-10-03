package org.weaw.persistence;

import org.weaw.game.WorldTimeState;

import java.util.LinkedHashMap;

/** Adds the persistent day/night clock introduced by save format 3. */
public final class WorldSaveV2ToV3Migrator implements WorldSaveMigrator {
    @Override
    public int sourceVersion() {
        return 2;
    }

    @Override
    public int targetVersion() {
        return 3;
    }

    @Override
    public WorldManifest migrate(WorldManifest source) {
        var sections = new LinkedHashMap<>(source.sections());
        sections.put("time", new WorldManifest.FutureSection(1, true));
        return new WorldManifest(
                3, source.generation(), source.worldId(), source.worldKey(), source.displayName(), source.seed(),
                source.createdAtEpochMillis(), source.lastOpenedAtEpochMillis(), source.generationSettings(),
                source.minChunkY(), source.maxChunkY(), source.simulationDistanceChunks(),
                source.defaultRenderDistanceChunks(), source.autosaveSeconds(), WorldTimeState.defaults(),
                sections, source.chunks(), source.players()
        );
    }
}
