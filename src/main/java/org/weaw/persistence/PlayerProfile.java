package org.weaw.persistence;

import java.util.UUID;

public record PlayerProfile(
        int formatVersion,
        UUID id,
        String key,
        String displayName,
        long createdAtEpochMillis,
        long updatedAtEpochMillis
) {
    public static final int CURRENT_FORMAT_VERSION = 1;
}
