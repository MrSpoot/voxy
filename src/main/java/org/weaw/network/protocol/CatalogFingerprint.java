package org.weaw.network.protocol;

import org.weaw.game.utils.BlockCatalog;
import org.weaw.game.utils.BlockDefinition;

import java.nio.charset.StandardCharsets;

public final class CatalogFingerprint {
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private CatalogFingerprint() {
    }

    public static long compute(BlockCatalog catalog) {
        long hash = FNV_OFFSET;
        for (BlockDefinition block : catalog.getRegisteredBlocks().values()) {
            hash = append(hash, block.getStableId());
            hash = append(hash, block.getTransparencyType().ordinal());
            hash = append(hash, block.isCullSameTypeFaces() ? 1 : 0);
            hash = append(hash, block.getLightEmissionRed());
            hash = append(hash, block.getLightEmissionGreen());
            hash = append(hash, block.getLightEmissionBlue());
            hash = append(hash, block.getLightAttenuation());
        }
        return hash;
    }

    private static long append(long hash, String value) {
        for (byte character : value.getBytes(StandardCharsets.UTF_8)) {
            hash = (hash ^ (character & 0xFF)) * FNV_PRIME;
        }
        return (hash ^ 0xFF) * FNV_PRIME;
    }

    private static long append(long hash, int value) {
        for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
            hash = (hash ^ ((value >>> shift) & 0xFF)) * FNV_PRIME;
        }
        return hash;
    }
}
