package org.weaw.game;

@FunctionalInterface
public interface WorldBlockProvider {
    short getBlockAtWorld(int worldX, int worldY, int worldZ);

    default int getSkyLightScanStartY(int worldX, int worldZ, int maxWorldY) {
        return maxWorldY;
    }
}
