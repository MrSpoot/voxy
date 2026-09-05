package org.weaw.game.utils;

import java.util.Collection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, injectable catalogue of block definitions. */
public final class BlockCatalog {
    private final Map<String, BlockDefinition> blocksByStableId;
    private final BlockDefinition[] blocksByRuntimeId;
    private final BlockDefinition air;

    public static BlockCatalog create(Collection<BlockDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        if (definitions.size() > Short.MAX_VALUE + 1) {
            throw new IllegalArgumentException("A block catalogue cannot contain more than 32768 definitions");
        }
        Map<String, BlockDefinition> byStableId = new LinkedHashMap<>();
        for (BlockDefinition definition : definitions) {
            Objects.requireNonNull(definition, "block definition");
            String stableId = definition.getStableId();
            validateStableId(stableId);
            if (byStableId.putIfAbsent(stableId, definition) != null) {
                throw new IllegalStateException("Duplicate block stable id: " + stableId);
            }
        }
        BlockDefinition air = byStableId.get("voxy:air");
        if (air == null) {
            throw new IllegalStateException("A block catalogue must define voxy:air");
        }

        BlockDefinition[] byRuntimeId = new BlockDefinition[byStableId.size()];
        int runtimeId = 0;
        for (BlockDefinition definition : byStableId.values()) {
            definition.setRuntimeId((short) runtimeId);
            byRuntimeId[runtimeId] = definition;
            runtimeId++;
        }
        return new BlockCatalog(byStableId, byRuntimeId, air);
    }

    public static BlockCatalog createDefault() {
        return createDefault(List.of());
    }

    static BlockCatalog createDefault(Collection<BlockDefinition> additionalDefinitions) {
        List<BlockDefinition> definitions = new ArrayList<>(List.of(
                Blocks.AIR, Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.STONE, Blocks.SAND,
                Blocks.WOOD_LOG, Blocks.TEST, Blocks.RED_LAMP, Blocks.GREEN_LAMP,
                Blocks.BLUE_LAMP, Blocks.WHITE_LAMP, Blocks.LEAVES, Blocks.GLASS, Blocks.WATER
        ));
        definitions.addAll(additionalDefinitions);
        return create(definitions);
    }

    private BlockCatalog(
            Map<String, BlockDefinition> blocksByStableId,
            BlockDefinition[] blocksByRuntimeId,
            BlockDefinition air
    ) {
        this.blocksByStableId = Collections.unmodifiableMap(new LinkedHashMap<>(blocksByStableId));
        this.blocksByRuntimeId = blocksByRuntimeId.clone();
        this.air = air;
    }

    public BlockDefinition getBlock(short runtimeId) {
        int index = runtimeId;
        return index >= 0 && index < blocksByRuntimeId.length ? blocksByRuntimeId[index] : null;
    }

    public BlockDefinition getBlock(String stableId) {
        return blocksByStableId.get(stableId);
    }

    public short getRuntimeId(String stableId) {
        BlockDefinition block = getBlock(stableId);
        if (block == null) {
            throw new IllegalArgumentException("Unknown block stable id: " + stableId);
        }
        return block.getId();
    }

    public String getStableId(short runtimeId) {
        BlockDefinition block = getBlock(runtimeId);
        if (block == null) {
            throw new IllegalArgumentException("Unknown block runtime id: " + runtimeId);
        }
        return block.getStableId();
    }

    public Map<String, BlockDefinition> getRegisteredBlocks() {
        return blocksByStableId;
    }

    /** Number of contiguous runtime ids available in this catalogue. */
    public int size() {
        return blocksByRuntimeId.length;
    }

    public BlockDefinition air() {
        return air;
    }

    private static void validateStableId(String stableId) {
        if (stableId == null || stableId.isBlank()) {
            throw new IllegalArgumentException("Block stable id cannot be blank");
        }
        if (!stableId.contains(":")) {
            throw new IllegalArgumentException("Block stable id must be namespaced: " + stableId);
        }
    }
}
