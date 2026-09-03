package org.weaw.gameplay;

public record PlayerInput(
        boolean controlsEnabled,
        boolean moveForward,
        boolean moveBackward,
        boolean moveLeft,
        boolean moveRight,
        boolean moveUp,
        boolean moveDown,
        boolean jump,
        boolean sprint,
        boolean toggleNoclip,
        boolean breakBlock,
        boolean placeBlock,
        float mouseDeltaX,
        float mouseDeltaY,
        int scrollDelta
) {
    public static PlayerInput disabled() {
        return new PlayerInput(
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                0.0f,
                0.0f,
                0
        );
    }

    public PlayerInput withoutFrameTransitions() {
        return new PlayerInput(
                controlsEnabled,
                moveForward,
                moveBackward,
                moveLeft,
                moveRight,
                moveUp,
                moveDown,
                false,
                sprint,
                false,
                false,
                false,
                0.0f,
                0.0f,
                0
        );
    }
}
