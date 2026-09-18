package org.weaw.network.protocol;

public final class Protocol {
    public static final int VERSION = 3;
    public static final int DEFAULT_PORT = 25565;
    public static final int DEFAULT_MAX_PLAYERS = 16;
    public static final int MAX_PLAYERS = 16;
    public static final int MIN_VIEW_DISTANCE = 2;
    public static final int DEFAULT_VIEW_DISTANCE = 12;
    public static final int MAX_VIEW_DISTANCE = 32;
    public static final int MAX_FRAME_BYTES = 2 * 1024 * 1024;
    public static final int MAX_PLAYER_NAME_BYTES = 32;

    private Protocol() {
    }
}
