package org.weaw.persistence;

/** A future save version adds one adjacent migration and registers it with WorldRepository. */
public interface WorldSaveMigrator {
    int sourceVersion();

    int targetVersion();

    WorldManifest migrate(WorldManifest source);
}
