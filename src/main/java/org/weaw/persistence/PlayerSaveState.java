package org.weaw.persistence;

import org.joml.Vector3f;
import org.weaw.game.utils.BlockCatalog;
import org.weaw.game.utils.BlockDefinition;
import org.weaw.gameplay.GameplaySession;
import org.weaw.gameplay.Player;
import org.weaw.gameplay.PlayerHotbar;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record PlayerSaveState(
        int formatVersion,
        UUID profileId,
        String displayName,
        float x,
        float y,
        float z,
        float yaw,
        float pitch,
        float verticalVelocity,
        boolean grounded,
        boolean noclip,
        List<String> hotbarStableIds,
        int selectedHotbarSlot
) {
    public static final int CURRENT_FORMAT_VERSION = 1;

    public PlayerSaveState {
        hotbarStableIds = java.util.Collections.unmodifiableList(
                new ArrayList<>(hotbarStableIds == null ? List.of() : hotbarStableIds)
        );
    }

    public static PlayerSaveState capture(UUID profileId, String displayName, GameplaySession gameplay) {
        Player player = gameplay.getPlayer();
        Vector3f position = player.getPosition();
        List<String> hotbar = new ArrayList<>(PlayerHotbar.SLOT_COUNT);
        for (BlockDefinition block : gameplay.getHotbar().snapshot()) {
            hotbar.add(block == null ? null : block.getStableId());
        }
        return new PlayerSaveState(
                CURRENT_FORMAT_VERSION, profileId, displayName,
                position.x, position.y, position.z, player.getYaw(), player.getPitch(),
                player.getVerticalVelocity(), player.isGrounded(), player.isNoclip(),
                hotbar, gameplay.getHotbar().getSelectedIndex()
        );
    }

    public void restore(GameplaySession gameplay, BlockCatalog catalog) {
        validate(catalog);
        Player player = gameplay.getPlayer();
        player.setPose(new Vector3f(x, y, z), yaw, pitch);
        player.setNoclip(noclip);
        player.setVerticalVelocity(verticalVelocity);
        player.setGrounded(grounded);
        for (int index = 0; index < PlayerHotbar.SLOT_COUNT; index++) {
            String stableId = index < hotbarStableIds.size() ? hotbarStableIds.get(index) : null;
            gameplay.getHotbar().setSlot(index, stableId == null ? null : catalog.getBlock(stableId));
        }
        gameplay.getHotbar().select(Math.clamp(selectedHotbarSlot, 0, PlayerHotbar.SLOT_COUNT - 1));
    }

    public void validate(BlockCatalog catalog) {
        if (formatVersion != CURRENT_FORMAT_VERSION || profileId == null || displayName == null || displayName.isBlank()
                || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch) || !Float.isFinite(verticalVelocity)
                || hotbarStableIds.size() > PlayerHotbar.SLOT_COUNT
                || selectedHotbarSlot < 0 || selectedHotbarSlot >= PlayerHotbar.SLOT_COUNT) {
            throw new IllegalArgumentException("Invalid persisted player state for " + profileId);
        }
        for (String stableId : hotbarStableIds) {
            if (stableId != null && catalog.getBlock(stableId) == null) {
                throw new IllegalArgumentException("Unknown persisted hotbar block: " + stableId);
            }
        }
    }
}
