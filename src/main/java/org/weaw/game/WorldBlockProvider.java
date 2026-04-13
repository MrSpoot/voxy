package org.weaw.game;

@FunctionalInterface
public interface WorldBlockProvider {
    short getBlockAtWorld(int worldX, int worldY, int worldZ);
}
