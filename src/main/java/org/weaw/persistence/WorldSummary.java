package org.weaw.persistence;

import java.util.UUID;

/** Metadata-only view used by the world selection screen. */
public record WorldSummary(
        String worldKey,
        UUID worldId,
        String displayName,
        long seed,
        long createdAtEpochMillis,
        long lastOpenedAtEpochMillis,
        int formatVersion,
        Status status,
        String problem
) {
    public enum Status {
        VALID,
        CORRUPT,
        INCOMPATIBLE
    }

    public boolean playable() {
        return status == Status.VALID;
    }
}
