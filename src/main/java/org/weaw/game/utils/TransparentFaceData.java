package org.weaw.game.utils;

import org.weaw.game.ChunkMeshingSnapshot;

final class TransparentFaceData {
    static final int TEXTURE_INDEX_MASK = 0xFFFF;
    static final int WATER_SURFACE_EDGE_BIT = 1 << 16;

    private TransparentFaceData() {
    }

    static int pack(
            ChunkMeshingSnapshot snapshot,
            BlockDefinition block,
            int textureIndex,
            int x,
            int y,
            int z,
            int face
    ) {
        if (!isWater(block) || face == 2 || face == 3 || hasWaterAbove(snapshot, x, y, z)) {
            return textureIndex;
        }
        return textureIndex | WATER_SURFACE_EDGE_BIT;
    }

    static int textureIndex(int payload) {
        return payload & TEXTURE_INDEX_MASK;
    }

    private static boolean hasWaterAbove(ChunkMeshingSnapshot snapshot, int x, int y, int z) {
        BlockDefinition above = snapshot.blockCatalog().getBlock(snapshot.getBlock(x, y + 1, z));
        return above != null && isWater(above);
    }

    private static boolean isWater(BlockDefinition block) {
        return Blocks.WATER.getStableId().equals(block.getStableId());
    }
}
