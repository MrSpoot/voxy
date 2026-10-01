package org.weaw.persistence;

import org.weaw.network.protocol.Protocol;

import java.util.List;
import java.util.Map;

/** Versioned, machine-local client preferences. */
public record ClientSettings(
        int formatVersion,
        String locale,
        float uiScale,
        int windowWidth,
        int windowHeight,
        boolean fullscreen,
        boolean vsync,
        int frameRateLimit,
        float fieldOfView,
        int renderDistanceChunks,
        GraphicsPreferences graphicsPreferences,
        float mouseSensitivity,
        String playerName,
        String activeProfileKey,
        int defaultPort,
        int defaultViewDistance,
        int defaultMaxPlayers,
        Map<String, BindingSetting> bindings,
        List<RecentServer> recentServers
) {
    public static final int CURRENT_FORMAT_VERSION = 2;

    public ClientSettings {
        locale = "en".equalsIgnoreCase(locale) ? "en" : "fr";
        uiScale = Math.clamp(uiScale, 0.75f, 2.0f);
        windowWidth = Math.clamp(windowWidth, 960, 7680);
        windowHeight = Math.clamp(windowHeight, 540, 4320);
        frameRateLimit = frameRateLimit <= 0 ? 0 : Math.clamp(frameRateLimit, 30, 360);
        fieldOfView = Math.clamp(fieldOfView, 60.0f, 120.0f);
        renderDistanceChunks = Math.clamp(
                renderDistanceChunks,
                Protocol.MIN_VIEW_DISTANCE,
                Protocol.MAX_VIEW_DISTANCE
        );
        graphicsPreferences = graphicsPreferences == null ? GraphicsPreferences.defaults() : graphicsPreferences;
        mouseSensitivity = Math.clamp(mouseSensitivity, 0.01f, 1.0f);
        playerName = normalizePlayerName(playerName);
        activeProfileKey = normalizeProfileKey(activeProfileKey);
        defaultPort = Math.clamp(defaultPort, 1, 65_535);
        defaultViewDistance = Math.clamp(
                defaultViewDistance,
                Protocol.MIN_VIEW_DISTANCE,
                Protocol.MAX_VIEW_DISTANCE
        );
        defaultMaxPlayers = Math.clamp(defaultMaxPlayers, 1, Protocol.MAX_PLAYERS);
        bindings = Map.copyOf(bindings == null ? Map.of() : bindings);
        recentServers = List.copyOf(recentServers == null ? List.of() : recentServers.stream().limit(10).toList());
    }

    public static ClientSettings defaults() {
        String name = System.getProperty("user.name", "Player").replaceAll("[^A-Za-z0-9_-]", "_");
        if (name.isBlank()) {
            name = "Player";
        }
        if (name.length() > 24) {
            name = name.substring(0, 24);
        }
        return new ClientSettings(
                CURRENT_FORMAT_VERSION,
                "fr",
                1.0f,
                1280,
                720,
                false,
                true,
                0,
                90.0f,
                Protocol.DEFAULT_VIEW_DISTANCE,
                GraphicsPreferences.defaults(),
                0.15f,
                name,
                "default",
                Protocol.DEFAULT_PORT,
                Protocol.DEFAULT_VIEW_DISTANCE,
                Protocol.DEFAULT_MAX_PLAYERS,
                Map.of(),
                List.of()
        );
    }

    public ClientSettings withRecentServer(String host, int port) {
        RecentServer recent = new RecentServer(host, port, System.currentTimeMillis());
        List<RecentServer> updated = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(recent),
                        recentServers.stream().filter(entry -> !entry.sameEndpoint(recent))
                )
                .limit(10)
                .toList();
        return copy(bindings, updated);
    }

    public ClientSettings copy(Map<String, BindingSetting> nextBindings, List<RecentServer> nextRecentServers) {
        return new ClientSettings(
                CURRENT_FORMAT_VERSION, locale, uiScale, windowWidth, windowHeight, fullscreen, vsync, frameRateLimit,
                fieldOfView, renderDistanceChunks, graphicsPreferences, mouseSensitivity, playerName, activeProfileKey, defaultPort,
                defaultViewDistance, defaultMaxPlayers, nextBindings, nextRecentServers
        );
    }

    public ClientSettings withActiveProfile(PlayerProfile profile) {
        return new ClientSettings(
                CURRENT_FORMAT_VERSION, locale, uiScale, windowWidth, windowHeight, fullscreen, vsync, frameRateLimit,
                fieldOfView, renderDistanceChunks, graphicsPreferences, mouseSensitivity, profile.displayName(), profile.key(), defaultPort,
                defaultViewDistance, defaultMaxPlayers, bindings, recentServers
        );
    }

    public ClientSettings withRuntimePreferences(
            String nextLocale,
            float nextUiScale,
            boolean nextVsync,
            int nextFrameRateLimit,
            float nextFieldOfView,
            int nextRenderDistance,
            GraphicsPreferences nextGraphics,
            float nextMouseSensitivity,
            Map<String, BindingSetting> nextBindings
    ) {
        return new ClientSettings(
                CURRENT_FORMAT_VERSION, nextLocale, nextUiScale, windowWidth, windowHeight, fullscreen, nextVsync,
                nextFrameRateLimit, nextFieldOfView, nextRenderDistance, nextGraphics, nextMouseSensitivity,
                playerName, activeProfileKey, defaultPort, defaultViewDistance, defaultMaxPlayers,
                nextBindings, recentServers
        );
    }

    public ClientSettings withDisplayMode(int width, int height, boolean nextFullscreen) {
        return new ClientSettings(
                CURRENT_FORMAT_VERSION, locale, uiScale, width, height, nextFullscreen, vsync, frameRateLimit,
                fieldOfView, renderDistanceChunks, graphicsPreferences, mouseSensitivity, playerName, activeProfileKey,
                defaultPort, defaultViewDistance, defaultMaxPlayers, bindings, recentServers
        );
    }

    private static String normalizePlayerName(String value) {
        String normalized = value == null ? "Player" : value.trim();
        if (!normalized.matches("[\\p{L}0-9_-]{1,24}")) {
            return "Player";
        }
        return normalized;
    }

    private static String normalizeProfileKey(String value) {
        if (value == null || value.isBlank()) {
            return "default";
        }
        try {
            return StorageOptions.validateKey(value.trim(), "profile");
        } catch (IllegalArgumentException ignored) {
            return "default";
        }
    }

    public record BindingSetting(String type, int code) {
    }

    public record RecentServer(String host, int port, long lastConnectedAtEpochMillis) {
        public RecentServer {
            host = host == null ? "" : host.trim();
            port = Math.clamp(port, 1, 65_535);
        }

        boolean sameEndpoint(RecentServer other) {
            return port == other.port && host.equalsIgnoreCase(other.host);
        }

        public String label() {
            return host.contains(":") ? "[" + host + "]:" + port : host + ":" + port;
        }
    }
}
