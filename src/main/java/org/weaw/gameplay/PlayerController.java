package org.weaw.gameplay;

import org.joml.Vector3f;
import org.weaw.game.World;

public class PlayerController {
    private final GameplaySettings settings;
    private final Vector3f movement = new Vector3f();

    public PlayerController(GameplaySettings settings) {
        this.settings = settings;
    }

    public void update(Player player, World world, float deltaTime, PlayerInput input) {
        if (input.controlsEnabled()) {
            if (input.toggleNoclip()) {
                player.toggleNoclip();
            }

            updateLook(player, input);

            if (player.isNoclip()) {
                updateNoclipMovement(player, deltaTime, input);
                return;
            }

            updateGroundMovement(player, world, deltaTime, input);
            updateJump(player, input);
        } else if (player.isNoclip()) {
            return;
        }

        updateGravity(player, world, deltaTime);
    }

    private void updateLook(Player player, PlayerInput input) {
        float mouseSensitivity = settings.getMouseSensitivity();
        float yawDelta = input.mouseDeltaX() * mouseSensitivity;
        float pitchDelta = input.mouseDeltaY() * mouseSensitivity;
        player.rotate(yawDelta, pitchDelta);
    }

    private void updateNoclipMovement(Player player, float deltaTime, PlayerInput input) {
        movement.zero();
        collectHorizontalInput(input);

        if (input.moveUp()) {
            movement.y += 1.0f;
        }
        if (input.moveDown()) {
            movement.y -= 1.0f;
        }

        if (movement.lengthSquared() > 0.0f) {
            player.moveRelative(movement.normalize().mul(resolveMoveDistance(deltaTime, input)));
        }
    }

    private void updateGroundMovement(Player player, World world, float deltaTime, PlayerInput input) {
        movement.zero();
        collectHorizontalInput(input);

        if (movement.lengthSquared() == 0.0f) {
            return;
        }

        float yawRadians = (float) Math.toRadians(player.getYaw());
        Vector3f forward = new Vector3f((float) Math.cos(yawRadians), 0.0f, (float) Math.sin(yawRadians));
        Vector3f right = new Vector3f(forward).cross(new Vector3f(0.0f, 1.0f, 0.0f)).normalize();
        Vector3f worldMovement = new Vector3f(forward).mul(movement.z)
                .add(right.mul(movement.x))
                .normalize()
                .mul(resolveMoveDistance(deltaTime, input));

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

    private void updateJump(Player player, PlayerInput input) {
        if (!player.isGrounded() || !input.jump()) {
            return;
        }

        player.setVerticalVelocity(settings.getJumpVelocity());
        player.setGrounded(false);
    }

    private void collectHorizontalInput(PlayerInput input) {
        if (input.moveForward()) {
            movement.z += 1.0f;
        }
        if (input.moveBackward()) {
            movement.z -= 1.0f;
        }
        if (input.moveLeft()) {
            movement.x -= 1.0f;
        }
        if (input.moveRight()) {
            movement.x += 1.0f;
        }
    }

    private float resolveMoveDistance(float deltaTime, PlayerInput input) {
        float speed = settings.getPlayerMoveSpeed() * deltaTime;
        if (input.sprint()) {
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
