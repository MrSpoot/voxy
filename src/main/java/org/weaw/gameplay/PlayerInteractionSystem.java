package org.weaw.gameplay;

import org.joml.Vector3f;
import org.weaw.game.World;
import org.weaw.game.utils.BlockDefinition;
import org.weaw.game.utils.Blocks;

public class PlayerInteractionSystem {
    private static final float RAY_STEP = 0.05f;

    private final GameplaySettings settings;
    private final PlayerHotbar hotbar;
    private TargetedBlock targetedBlock;

    public PlayerInteractionSystem(GameplaySettings settings, PlayerHotbar hotbar) {
        this.settings = settings;
        this.hotbar = hotbar;
    }

    public void update(Player player, World world, PlayerInput input) {
        hotbar.cycle(input.scrollDelta());
        targetedBlock = raycastBlock(player, world);
        if (!input.breakBlock()
                && !input.placeBlock()) {
            return;
        }

        if (targetedBlock == null) {
            return;
        }

        if (input.breakBlock()) {
            world.trySetBlockAtWorld(targetedBlock.blockX(), targetedBlock.blockY(), targetedBlock.blockZ(), Blocks.AIR);
            return;
        }

        BlockDefinition selectedBlock = hotbar.getSelectedBlock();
        if (input.placeBlock()
                && selectedBlock != null
                && !wouldOverlapPlayer(player, targetedBlock.placeX(), targetedBlock.placeY(), targetedBlock.placeZ())) {
            world.trySetBlockAtWorld(targetedBlock.placeX(), targetedBlock.placeY(), targetedBlock.placeZ(), selectedBlock);
        }
    }

    public BlockDefinition getSelectedBlock() {
        return hotbar.getSelectedBlock();
    }

    public void setSelectedBlock(BlockDefinition selectedBlock) {
        if (selectedBlock == null || selectedBlock == Blocks.AIR) {
            return;
        }
        hotbar.setSlot(hotbar.getSelectedIndex(), selectedBlock);
    }

    public TargetedBlock getTargetedBlock() {
        return targetedBlock;
    }

    private TargetedBlock raycastBlock(Player player, World world) {
        Vector3f origin = player.getPosition();
        Vector3f direction = player.getForward();

        int previousX = (int) Math.floor(origin.x);
        int previousY = (int) Math.floor(origin.y);
        int previousZ = (int) Math.floor(origin.z);

        for (float distance = 0.0f; distance <= settings.getBlockInteractionReach(); distance += RAY_STEP) {
            float sampleX = origin.x + direction.x * distance;
            float sampleY = origin.y + direction.y * distance;
            float sampleZ = origin.z + direction.z * distance;

            int blockX = (int) Math.floor(sampleX);
            int blockY = (int) Math.floor(sampleY);
            int blockZ = (int) Math.floor(sampleZ);

            if (blockX == previousX && blockY == previousY && blockZ == previousZ && distance > 0.0f) {
                continue;
            }

            if (world.containsChunkAtWorld(blockX, blockY, blockZ)
                    && world.isSolidBlockAtWorld(blockX, blockY, blockZ)) {
                return new TargetedBlock(blockX, blockY, blockZ, previousX, previousY, previousZ);
            }

            previousX = blockX;
            previousY = blockY;
            previousZ = blockZ;
        }

        return null;
    }

    private boolean wouldOverlapPlayer(Player player, int blockX, int blockY, int blockZ) {
        Vector3f eye = player.getPosition();
        float radius = settings.getPlayerCollisionRadius();
        float minPlayerX = eye.x - radius;
        float maxPlayerX = eye.x + radius;
        float minPlayerY = eye.y - settings.getPlayerEyeHeight();
        float maxPlayerY = eye.y;
        float minPlayerZ = eye.z - radius;
        float maxPlayerZ = eye.z + radius;

        return blockX < maxPlayerX
                && blockX + 1.0f > minPlayerX
                && blockY < maxPlayerY
                && blockY + 1.0f > minPlayerY
                && blockZ < maxPlayerZ
                && blockZ + 1.0f > minPlayerZ;
    }

}
