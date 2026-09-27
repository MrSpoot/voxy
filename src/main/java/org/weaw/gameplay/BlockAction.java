package org.weaw.gameplay;

import java.util.Objects;

/** Immutable block interaction proposed by a client and validated by the server. */
public record BlockAction(
        Type type,
        int x,
        int y,
        int z,
        short expectedBlockId,
        short replacementBlockId
) {
    public BlockAction {
        Objects.requireNonNull(type, "type");
    }

    public enum Type {
        BREAK,
        PLACE
    }
}
