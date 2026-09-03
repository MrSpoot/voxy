package org.weaw.engine.graphics.pipeline.passes;

import imgui.ImGui;
import org.weaw.engine.graphics.utils.ChunkFaceArena;

import java.util.Locale;

final class ChunkArenaDebugPanel {
    private ChunkArenaDebugPanel() {
    }

    static void render(String label, ChunkFaceArena arena) {
        ImGui.text(label);
        if (arena == null) {
            ImGui.text("Arena not initialized");
            return;
        }

        long capacityBytes = (long) arena.getCapacityInts() * Integer.BYTES;
        long reservedBytes = arena.getReservedInts() * Integer.BYTES;
        long payloadBytes = arena.getPayloadInts() * Integer.BYTES;
        long freeBytes = arena.getFreeInts() * Integer.BYTES;
        ImGui.text(String.format("Capacity: %s", formatBytes(capacityBytes)));
        ImGui.text(String.format(
                "Reserved: %s (%.1f%%)",
                formatBytes(reservedBytes),
                arena.getReservationRatio() * 100.0f
        ));
        ImGui.text(String.format(
                "Payload: %s (%.1f%%)",
                formatBytes(payloadBytes),
                arena.getPayloadRatio() * 100.0f
        ));
        ImGui.text(String.format("Free: %s", formatBytes(freeBytes)));
        ImGui.text(String.format(
                "Allocations: %d | Free spans: %d",
                arena.getActiveAllocationCount(),
                arena.getFreeSpanCount()
        ));
        ImGui.text(String.format(
                "Largest free span: %s | Fragmentation: %.1f%%",
                formatBytes((long) arena.getLargestFreeSpanInts() * Integer.BYTES),
                arena.getFragmentationRatio() * 100.0f
        ));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
}
