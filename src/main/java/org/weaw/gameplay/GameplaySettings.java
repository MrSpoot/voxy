package org.weaw.gameplay;

public class GameplaySettings {
    private float playerMoveSpeed = 5f;
    private float playerSprintMultiplier = 2.0f;
    private float playerEyeHeight = 1.62f;
    private float playerCollisionRadius = 0.3f;
    private float gravity = -24.0f;
    private float jumpHeight = 1.2f;
    private float terminalFallSpeed = -80.0f;
    private float blockInteractionReach = 5.0f;
    private float mouseSensitivity = 0.15f;

    public float getPlayerMoveSpeed() {
        return playerMoveSpeed;
    }

    public void setPlayerMoveSpeed(float playerMoveSpeed) {
        this.playerMoveSpeed = Math.max(0.0f, playerMoveSpeed);
    }

    public float getPlayerSprintMultiplier() {
        return playerSprintMultiplier;
    }

    public void setPlayerSprintMultiplier(float playerSprintMultiplier) {
        this.playerSprintMultiplier = Math.max(1.0f, playerSprintMultiplier);
    }

    public float getMouseSensitivity() {
        return mouseSensitivity;
    }

    public void setMouseSensitivity(float mouseSensitivity) {
        this.mouseSensitivity = Math.max(0.0f, mouseSensitivity);
    }

    public float getPlayerEyeHeight() {
        return playerEyeHeight;
    }

    public void setPlayerEyeHeight(float playerEyeHeight) {
        this.playerEyeHeight = Math.max(0.1f, playerEyeHeight);
    }

    public float getPlayerCollisionRadius() {
        return playerCollisionRadius;
    }

    public void setPlayerCollisionRadius(float playerCollisionRadius) {
        this.playerCollisionRadius = Math.max(0.0f, playerCollisionRadius);
    }

    public float getGravity() {
        return gravity;
    }

    public void setGravity(float gravity) {
        this.gravity = Math.min(0.0f, gravity);
    }

    public float getJumpHeight() {
        return jumpHeight;
    }

    public void setJumpHeight(float jumpHeight) {
        this.jumpHeight = Math.max(0.0f, jumpHeight);
    }

    public float getJumpVelocity() {
        return (float) Math.sqrt(2.0f * Math.abs(gravity) * jumpHeight);
    }

    public float getTerminalFallSpeed() {
        return terminalFallSpeed;
    }

    public void setTerminalFallSpeed(float terminalFallSpeed) {
        this.terminalFallSpeed = Math.min(0.0f, terminalFallSpeed);
    }

    public float getBlockInteractionReach() {
        return blockInteractionReach;
    }

    public void setBlockInteractionReach(float blockInteractionReach) {
        this.blockInteractionReach = Math.max(0.0f, blockInteractionReach);
    }
}
