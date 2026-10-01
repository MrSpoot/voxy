package org.weaw.persistence;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class WorldSaveMigrationRegistry {
    private final Map<Integer, WorldSaveMigrator> migrations = new HashMap<>();

    public WorldSaveMigrationRegistry(List<WorldSaveMigrator> registered) {
        for (WorldSaveMigrator migrator : registered) {
            if (migrator.targetVersion() != migrator.sourceVersion() + 1) {
                throw new IllegalArgumentException("Save migrations must target the adjacent version");
            }
            if (migrations.putIfAbsent(migrator.sourceVersion(), migrator) != null) {
                throw new IllegalArgumentException("Duplicate migration from version " + migrator.sourceVersion());
            }
        }
    }

    public WorldManifest migrateToCurrent(WorldManifest source, Path path) {
        if (source.formatVersion() > WorldManifest.CURRENT_FORMAT_VERSION) {
            throw new WorldSaveException(
                    WorldSaveException.Kind.INCOMPATIBLE, path,
                    "World save version " + source.formatVersion() + " is newer than supported version "
                            + WorldManifest.CURRENT_FORMAT_VERSION
            );
        }
        WorldManifest current = source;
        while (current.formatVersion() < WorldManifest.CURRENT_FORMAT_VERSION) {
            WorldSaveMigrator migrator = migrations.get(current.formatVersion());
            if (migrator == null) {
                throw new WorldSaveException(
                        WorldSaveException.Kind.INCOMPATIBLE, path,
                        "No migration is registered from world save version " + current.formatVersion()
                );
            }
            current = migrator.migrate(current);
            if (current == null || current.formatVersion() != migrator.targetVersion()) {
                throw new IllegalStateException("Save migrator did not produce its declared target version");
            }
        }
        return current;
    }
}
