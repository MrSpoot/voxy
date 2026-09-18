package org.weaw.network.protocol;

import org.weaw.gameplay.PlayerInput;

public sealed interface ClientMessage permits
        ClientMessage.Hello,
        ClientMessage.PlayerCommand,
        ClientMessage.SetHotbarSlot,
        ClientMessage.SwapHotbarSlots,
        ClientMessage.Disconnect {

    record Hello(int protocolVersion, long catalogFingerprint, String playerName, int viewDistance) implements ClientMessage {
    }

    record PlayerCommand(
            long sequence,
            long clientTick,
            PlayerInput input,
            int selectedHotbarSlot
    ) implements ClientMessage {
    }

    record SetHotbarSlot(long sequence, int slot, String stableBlockId) implements ClientMessage {
    }

    record SwapHotbarSlots(long sequence, int firstSlot, int secondSlot) implements ClientMessage {
    }

    record Disconnect() implements ClientMessage {
    }
}
