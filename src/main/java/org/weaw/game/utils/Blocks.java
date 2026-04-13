package org.weaw.game.utils;

import static org.weaw.game.utils.BlockDefinition.TransparencyType.CUTOUT;
import static org.weaw.game.utils.BlockDefinition.TransparencyType.OPAQUE;
import static org.weaw.game.utils.BlockDefinition.TransparencyType.TRANSPARENT;

public final class Blocks {
    public static final BlockDefinition AIR = new BlockDefinition("voxy:air", null, OPAQUE, true);
    public static final BlockDefinition GRASS_BLOCK = new BlockDefinition("voxy:grass_block", "/textures/grass_block_full.png", OPAQUE, true);
    public static final BlockDefinition DIRT = new BlockDefinition("voxy:dirt", "/textures/dirt_full.png", OPAQUE, true);
    public static final BlockDefinition STONE = new BlockDefinition("voxy:stone", "/textures/stone_full.png", OPAQUE, true);
    public static final BlockDefinition SAND = new BlockDefinition("voxy:sand", "/textures/sand_full.png", OPAQUE, true);
    public static final BlockDefinition WOOD_LOG = new BlockDefinition("voxy:wood_log", "/textures/wood_log_full.png", OPAQUE, true);
    public static final BlockDefinition TEST = new BlockDefinition("voxy:test", "/textures/test_full.png", OPAQUE, true);
    public static final BlockDefinition LEAVES = new BlockDefinition("voxy:leaves", "/textures/leave_full.png", CUTOUT, false);
    public static final BlockDefinition GLASS = new BlockDefinition("voxy:glass", "/textures/glass_full.png", TRANSPARENT, true);
    public static final BlockDefinition WATER = new BlockDefinition("voxy:water", "/textures/water_full.png", TRANSPARENT, true);

    private Blocks() {
    }
}
