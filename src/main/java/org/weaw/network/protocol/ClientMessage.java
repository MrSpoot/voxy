package org.weaw.network.protocol;

import org.weaw.gameplay.BlockAction;
import org.weaw.gameplay.PlayerInput;

import java.util.UUID;

public sealed interface ClientMessage permits
        ClientMessage.Hello,
        ClientMessage.PlayerCommand,
        ClientMessage.SetHotbarSlot,
        ClientMessage.SwapHotbarSlots,
        ClientMessage.SetViewDistance,
        ClientMessage.Disconnect {

    record Hello(
            int protocolVersion,
            long catalogFingerprint,
            UUID profileId,
            String playerName,
            int viewDistance
    ) implements ClientMessage {
        public Hello(int protocolVersion, long catalogFingerprint, String playerName, int viewDistance) {
            this(
                    protocolVersion,
                    catalogFingerprint,
                    UUID.nameUUIDFromBytes(("legacy:" + playerName).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    playerName,
                    viewDistance
            );
        }
    }

    record PlayerCommand(
            long sequence,
            long clientTick,
            PlayerInput input,
            int selectedHotbarSlot,
            BlockAction blockAction
    ) implements ClientMessage {
    }

    record SetHotbarSlot(long sequence, int slot, String stableBlockId) implements ClientMessage {
    }

    record SwapHotbarSlots(long sequence, int firstSlot, int secondSlot) implements ClientMessage {
    }

    record SetViewDistance(int viewDistance) implements ClientMessage {
    }

    record Disconnect() implements ClientMessage {
    }
}
