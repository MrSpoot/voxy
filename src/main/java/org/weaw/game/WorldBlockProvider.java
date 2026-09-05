package org.weaw.game;

@FunctionalInterface
public interface WorldBlockProvider {
    short getBlockAtWorld(int worldX, int worldY, int worldZ);

    /**
     * Fills a Y-major region ({@code x + z * sizeX + y * sizeX * sizeZ}).
     * Implementations can override this to avoid repeated lookups and locking.
     */
    default void fillBlockRegion(
            int originX,
            int originY,
            int originZ,
            int sizeX,
            int sizeY,
            int sizeZ,
            short[] destination
    ) {
        if (sizeX < 0 || sizeY < 0 || sizeZ < 0
                || destination.length < sizeX * sizeY * sizeZ) {
            throw new IllegalArgumentException("Invalid block region dimensions or destination size");
        }
        for (int y = 0; y < sizeY; y++) {
            int yOffset = y * sizeX * sizeZ;
            for (int z = 0; z < sizeZ; z++) {
                int zOffset = yOffset + z * sizeX;
                for (int x = 0; x < sizeX; x++) {
                    destination[zOffset + x] = getBlockAtWorld(originX + x, originY + y, originZ + z);
                }
            }
        }
    }

    default int getSkyLightScanStartY(int worldX, int worldZ, int maxWorldY) {
        return maxWorldY;
    }
}
