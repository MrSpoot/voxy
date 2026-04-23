package org.weaw.game.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class BlockRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(BlockRegistry.class);

    private static final Map<String, BlockDefinition> BLOCKS_BY_STABLE_ID = new LinkedHashMap<>();
    private static final Map<Short, BlockDefinition> BLOCKS_BY_RUNTIME_ID = new LinkedHashMap<>();
    private static boolean initialized = false;
    private static short nextRuntimeId = 0;

    private BlockRegistry() {
    }

    public static void initialize() {
        if (initialized) {
            return;
        }

        register(Blocks.AIR);
        register(Blocks.GRASS_BLOCK);
        register(Blocks.DIRT);
        register(Blocks.STONE);
        register(Blocks.SAND);
        register(Blocks.WOOD_LOG);
        register(Blocks.TEST);
        register(Blocks.RED_LAMP);
        register(Blocks.GREEN_LAMP);
        register(Blocks.BLUE_LAMP);
        register(Blocks.WHITE_LAMP);
        register(Blocks.LEAVES);
        register(Blocks.GLASS);
        register(Blocks.WATER);

        initialized = true;
        LOGGER.info("Block registry initialized with {} blocks", BLOCKS_BY_STABLE_ID.size());
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static void register(BlockDefinition blockDefinition) {
        if (initialized) {
            throw new IllegalStateException("Cannot register new blocks after registry initialization");
        }

        String stableId = blockDefinition.getStableId();
        validateStableId(stableId);

        if (BLOCKS_BY_STABLE_ID.containsKey(stableId)) {
            throw new IllegalStateException("Duplicate block stable id: " + stableId);
        }

        blockDefinition.setRuntimeId(nextRuntimeId);
        BLOCKS_BY_STABLE_ID.put(stableId, blockDefinition);
        BLOCKS_BY_RUNTIME_ID.put(nextRuntimeId, blockDefinition);
        LOGGER.info("Registered block {} with runtimeId {}", stableId, nextRuntimeId);
        nextRuntimeId++;
    }

    public static BlockDefinition getBlock(short runtimeId) {
        ensureInitialized();
        return BLOCKS_BY_RUNTIME_ID.get(runtimeId);
    }

    public static BlockDefinition getBlock(String stableId) {
        ensureInitialized();
        return BLOCKS_BY_STABLE_ID.get(stableId);
    }

    public static short getRuntimeId(String stableId) {
        BlockDefinition blockDefinition = getBlock(stableId);
        if (blockDefinition == null) {
            throw new IllegalArgumentException("Unknown block stable id: " + stableId);
        }
        return blockDefinition.getId();
    }

    public static String getStableId(short runtimeId) {
        BlockDefinition blockDefinition = getBlock(runtimeId);
        if (blockDefinition == null) {
            throw new IllegalArgumentException("Unknown block runtime id: " + runtimeId);
        }
        return blockDefinition.getStableId();
    }

    public static Map<String, BlockDefinition> getRegisteredBlocks() {
        ensureInitialized();
        return Collections.unmodifiableMap(BLOCKS_BY_STABLE_ID);
    }

    private static void ensureInitialized() {
        if (!initialized) {
            initialize();
        }
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
