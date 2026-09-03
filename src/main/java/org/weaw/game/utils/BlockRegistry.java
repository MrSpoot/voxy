package org.weaw.game.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Compatibility facade. Runtime code should receive a {@link BlockCatalog}. */
public final class BlockRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(BlockRegistry.class);
    private static volatile BlockCatalog defaultCatalog;
    private static final List<BlockDefinition> pendingRegistrations = new ArrayList<>();

    private BlockRegistry() {
    }

    public static synchronized void initialize() {
        if (defaultCatalog != null) {
            return;
        }
        defaultCatalog = BlockCatalog.createDefault(pendingRegistrations);
        pendingRegistrations.clear();
        defaultCatalog.getRegisteredBlocks().forEach((stableId, block) ->
                LOGGER.info("Registered block {} with runtimeId {}", stableId, block.getId()));
        LOGGER.info("Block registry initialized with {} blocks", defaultCatalog.getRegisteredBlocks().size());
    }

    public static boolean isInitialized() {
        return defaultCatalog != null;
    }

    /** @deprecated Build a {@link BlockCatalog} before starting the world. */
    @Deprecated(forRemoval = true)
    public static synchronized void register(BlockDefinition blockDefinition) {
        Objects.requireNonNull(blockDefinition, "blockDefinition");
        if (defaultCatalog != null) {
            throw new IllegalStateException("Cannot register new blocks after registry initialization");
        }
        String stableId = blockDefinition.getStableId();
        if (stableId == null || stableId.isBlank() || !stableId.contains(":")) {
            throw new IllegalArgumentException("Block stable id must be non-blank and namespaced: " + stableId);
        }
        boolean duplicate = pendingRegistrations.stream()
                .anyMatch(existing -> existing.getStableId().equals(stableId));
        if (duplicate) {
            throw new IllegalStateException("Duplicate block stable id: " + stableId);
        }
        pendingRegistrations.add(blockDefinition);
    }

    public static BlockDefinition getBlock(short runtimeId) {
        return getDefaultCatalog().getBlock(runtimeId);
    }

    public static BlockDefinition getBlock(String stableId) {
        return getDefaultCatalog().getBlock(stableId);
    }

    public static short getRuntimeId(String stableId) {
        return getDefaultCatalog().getRuntimeId(stableId);
    }

    public static String getStableId(short runtimeId) {
        return getDefaultCatalog().getStableId(runtimeId);
    }

    public static Map<String, BlockDefinition> getRegisteredBlocks() {
        return getDefaultCatalog().getRegisteredBlocks();
    }

    public static synchronized BlockCatalog getDefaultCatalog() {
        if (defaultCatalog == null) {
            initialize();
        }
        return defaultCatalog;
    }
}
