package org.weaw.game.generation;

/**
 * Cheap, conservative description of a procedural chunk.
 *
 * <p>Only {@link Kind#MATERIALIZED} chunks need voxel storage and a mesh. Empty
 * and uniform chunks remain implicit and are resolved through
 * {@link WorldGenerator#getBlockAtWorld(int, int, int)}.</p>
 */
public record ChunkGenerationHint(Kind kind, short uniformBlockId) {
    public enum Kind {
        EMPTY,
        UNIFORM,
        MATERIALIZED
    }

    private static final ChunkGenerationHint EMPTY = new ChunkGenerationHint(Kind.EMPTY, (short) 0);
    private static final ChunkGenerationHint MATERIALIZED = new ChunkGenerationHint(Kind.MATERIALIZED, (short) 0);

    public ChunkGenerationHint {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
    }

    public static ChunkGenerationHint empty() {
        return EMPTY;
    }

    public static ChunkGenerationHint uniform(short blockId) {
        return new ChunkGenerationHint(Kind.UNIFORM, blockId);
    }

    public static ChunkGenerationHint materialized() {
        return MATERIALIZED;
    }

    public boolean requiresMaterialization() {
        return kind == Kind.MATERIALIZED;
    }
}
