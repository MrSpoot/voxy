package org.weaw.gameplay;

public record TargetedBlock(
        int blockX,
        int blockY,
        int blockZ,
        int placeX,
        int placeY,
        int placeZ
) {
}
