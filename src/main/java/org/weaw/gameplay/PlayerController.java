package org.weaw.gameplay;

import org.joml.Vector3f;
import org.weaw.engine.input.InputAction;
import org.weaw.engine.input.InputManager;
import org.weaw.game.World;

public class PlayerController {
    private final GameplaySettings settings;
    private final Vector3f movement = new Vector3f();

    public PlayerController(GameplaySettings settings) {
        this.settings = settings;
    }

    public void update(Player player, World world, float deltaTime, InputManager inputManager, boolean controlsEnabled) {
        if (controlsEnabled) {
            if (inputManager.isActionPressed(InputAction.TOGGLE_NOCLIP)) {
                player.toggleNoclip();
            }

            updateLook(player, inputManager);

            if (player.isNoclip()) {
                updateNoclipMovement(player, deltaTime, inputManager);
                return;
            }

            updateGroundMovement(player, world, deltaTime, inputManager);
            updateJump(player, inputManager);
        } else if (player.isNoclip()) {
            return;
        }

        updateGravity(player, world, deltaTime);
    }

    private void updateLook(Player player, InputManager inputManager) {
        float mouseSensitivity = settings.getMouseSensitivity();
        float yawDelta = inputManager.getMousePosition().deltaX() * mouseSensitivity;
        float pitchDelta = inputManager.getMousePosition().deltaY() * mouseSensitivity;
        player.rotate(yawDelta, pitchDelta);
    }

    private void updateNoclipMovement(Player player, float deltaTime, InputManager inputManager) {
        movement.zero();
        collectHorizontalInput(inputManager);

        if (inputManager.isActionDown(InputAction.MOVE_UP)) {
            movement.y += 1.0f;
        }
        if (inputManager.isActionDown(InputAction.MOVE_DOWN)) {
            movement.y -= 1.0f;
        }

        if (movement.lengthSquared() > 0.0f) {
            player.moveRelative(movement.normalize().mul(resolveMoveDistance(deltaTime, inputManager)));
        }
    }

    private void updateGroundMovement(Player player, World world, float deltaTime, InputManager inputManager) {
        movement.zero();
        collectHorizontalInput(inputManager);

        if (movement.lengthSquared() == 0.0f) {
            return;
        }

        float yawRadians = (float) Math.toRadians(player.getYaw());
        Vector3f forward = new Vector3f((float) Math.cos(yawRadians), 0.0f, (float) Math.sin(yawRadians));
        Vector3f right = new Vector3f(forward).cross(new Vector3f(0.0f, 1.0f, 0.0f)).normalize();
        Vector3f worldMovement = new Vector3f(forward).mul(movement.z)
                .add(right.mul(movement.x))
                .normalize()
                .mul(resolveMoveDistance(deltaTime, inputManager));

        moveWithHorizontalCollision(player, worldMovement, world);
    }

    private void updateGravity(Player player, World world, float deltaTime) {
        float verticalVelocity = Math.max(
                settings.getTerminalFallSpeed(),
                player.getVerticalVelocity() + settings.getGravity() * deltaTime
        );

        Vector3f position = player.getPosition();
        float nextY = position.y + verticalVelocity * deltaTime;

        if (verticalVelocity <= 0.0f && hasGroundBelow(world, position.x, nextY, position.z)) {
            float feetY = nextY - settings.getPlayerEyeHeight();
            float blockTopY = (float) Math.floor(feetY) + 1.0f;
            player.setY(blockTopY + settings.getPlayerEyeHeight());
            player.setVerticalVelocity(0.0f);
            player.setGrounded(true);
            return;
        }

        player.setY(nextY);
        player.setVerticalVelocity(verticalVelocity);
        player.setGrounded(false);
    }

    private void updateJump(Player player, InputManager inputManager) {
        if (!player.isGrounded() || !inputManager.isActionPressed(InputAction.MOVE_UP)) {
            return;
        }

        player.setVerticalVelocity(settings.getJumpVelocity());
        player.setGrounded(false);
    }

    private void collectHorizontalInput(InputManager inputManager) {
        if (inputManager.isActionDown(InputAction.MOVE_FORWARD)) {
            movement.z += 1.0f;
        }
        if (inputManager.isActionDown(InputAction.MOVE_BACKWARD)) {
            movement.z -= 1.0f;
        }
        if (inputManager.isActionDown(InputAction.MOVE_LEFT)) {
            movement.x -= 1.0f;
        }
        if (inputManager.isActionDown(InputAction.MOVE_RIGHT)) {
            movement.x += 1.0f;
        }
    }

    private float resolveMoveDistance(float deltaTime, InputManager inputManager) {
        float speed = settings.getPlayerMoveSpeed() * deltaTime;
        if (inputManager.isActionDown(InputAction.SPRINT)) {
            speed *= settings.getPlayerSprintMultiplier();
        }
        return speed;
    }

    private boolean hasGroundBelow(World world, float eyeX, float eyeY, float eyeZ) {
        float feetY = eyeY - settings.getPlayerEyeHeight();
        int blockY = (int) Math.floor(feetY);
        float radius = settings.getPlayerCollisionRadius();

        return isSolidAt(world, eyeX - radius, blockY, eyeZ - radius)
                || isSolidAt(world, eyeX - radius, blockY, eyeZ + radius)
                || isSolidAt(world, eyeX + radius, blockY, eyeZ - radius)
                || isSolidAt(world, eyeX + radius, blockY, eyeZ + radius);
    }

    private boolean isSolidAt(World world, float x, int y, float z) {
        return world.isSolidBlockAtWorld((int) Math.floor(x), y, (int) Math.floor(z));
    }

    private void moveWithHorizontalCollision(Player player, Vector3f offset, World world) {
        Vector3f position = player.getPosition();

        if (offset.x != 0.0f && !collides(world, position.x + offset.x, position.y, position.z)) {
            player.move(new Vector3f(offset.x, 0.0f, 0.0f));
            position.x += offset.x;
        }

        if (offset.z != 0.0f && !collides(world, position.x, position.y, position.z + offset.z)) {
            player.move(new Vector3f(0.0f, 0.0f, offset.z));
        }
    }

    private boolean collides(World world, float eyeX, float eyeY, float eyeZ) {
        float radius = settings.getPlayerCollisionRadius();
        float feetY = eyeY - settings.getPlayerEyeHeight() + 0.05f;
        int minY = (int) Math.floor(feetY);
        int maxY = (int) Math.floor(eyeY);

        for (int y = minY; y <= maxY; y++) {
            if (isSolidAt(world, eyeX - radius, y, eyeZ - radius)
                    || isSolidAt(world, eyeX - radius, y, eyeZ + radius)
                    || isSolidAt(world, eyeX + radius, y, eyeZ - radius)
                    || isSolidAt(world, eyeX + radius, y, eyeZ + radius)) {
                return true;
            }
        }

        return false;
    }
}
