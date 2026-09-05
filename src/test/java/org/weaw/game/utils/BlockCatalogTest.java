package org.weaw.game.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BlockCatalogTest {
    @Test
    void assignsStableRuntimeIdsAndRecognizesCustomAir() {
        BlockDefinition air = block("voxy:air");
        BlockDefinition stone = block("test:stone");
        BlockCatalog catalog = BlockCatalog.create(List.of(air, stone));

        assertEquals(0, catalog.getRuntimeId("voxy:air"));
        assertEquals(1, catalog.getRuntimeId("test:stone"));
        assertSame(stone, catalog.getBlock((short) 1));
        assertEquals(2, catalog.size());
        assertEquals(null, catalog.getBlock((short) -1));
        assertEquals(null, catalog.getBlock((short) 2));
        assertEquals("test:stone", catalog.getStableId((short) 1));
        assertFalse(air.isSolid());
        assertFalse(air.blocksLight());
    }

    @Test
    void rejectsMissingAirAndDuplicateStableIds() {
        assertThrows(IllegalStateException.class, () -> BlockCatalog.create(List.of(block("test:stone"))));
        assertThrows(IllegalStateException.class, () -> BlockCatalog.create(List.of(
                block("voxy:air"),
                block("test:stone"),
                block("test:stone")
        )));
    }

    private static BlockDefinition block(String id) {
        return new BlockDefinition(id, null, BlockDefinition.TransparencyType.OPAQUE, true);
    }
}
