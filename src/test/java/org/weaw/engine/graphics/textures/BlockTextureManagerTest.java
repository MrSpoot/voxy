package org.weaw.engine.graphics.textures;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockTextureManagerTest {

    @Test
    void derivesSquareFaceSizeFromHorizontalAtlas() {
        assertEquals(16, BlockTextureManager.faceWidth(96, 16));
    }

    @Test
    void rejectsInvalidAtlasDimensions() {
        assertThrows(IllegalArgumentException.class, () -> BlockTextureManager.faceWidth(95, 16));
        assertThrows(IllegalArgumentException.class, () -> BlockTextureManager.faceWidth(96, 15));
    }

    @Test
    void mapsEachBlockFaceToAnIndependentArrayLayer() {
        assertEquals(0, BlockTextureManager.textureLayer(0, 0));
        assertEquals(5, BlockTextureManager.textureLayer(0, 5));
        assertEquals(6, BlockTextureManager.textureLayer(1, 0));
        assertEquals(17, BlockTextureManager.textureLayer(2, 5));
    }

    @Test
    void rejectsInvalidLayerCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> BlockTextureManager.textureLayer(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> BlockTextureManager.textureLayer(0, -1));
        assertThrows(IllegalArgumentException.class, () -> BlockTextureManager.textureLayer(0, 6));
    }
}
