package org.weaw.persistence;

import java.nio.file.Path;

public final class WorldSaveException extends RuntimeException {
    public enum Kind { CORRUPT, INCOMPATIBLE, IO }

    private final Kind kind;
    private final Path path;

    public WorldSaveException(Kind kind, Path path, String message) {
        super(message);
        this.kind = kind;
        this.path = path;
    }

    public WorldSaveException(Kind kind, Path path, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.path = path;
    }

    public Kind kind() {
        return kind;
    }

    public Path path() {
        return path;
    }
}
