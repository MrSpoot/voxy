package org.weaw.gameplay;

import org.joml.Vector3f;

public class Player {
    private final Vector3f position = new Vector3f();
    private final Vector3f previousPosition = new Vector3f();
    private float yaw = -90.0f;
    private float pitch = 0.0f;
    private float previousYaw = yaw;
    private float previousPitch = pitch;
    private float verticalVelocity = 0.0f;
    private boolean grounded = false;
    private boolean noclip = false;

    public Vector3f getPosition() {
        return new Vector3f(position);
    }

    public void setPosition(Vector3f position) {
        this.position.set(position);
        this.previousPosition.set(position);
    }

    public void setRotation(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = Math.max(-89.0f, Math.min(89.0f, pitch));
        this.previousYaw = this.yaw;
        this.previousPitch = this.pitch;
    }

    public void setPose(Vector3f position, float yaw, float pitch) {
        setPosition(position);
        setRotation(yaw, pitch);
    }

    public void beginSimulationTick() {
        previousPosition.set(position);
        previousYaw = yaw;
        previousPitch = pitch;
    }

    public PlayerRenderPose sampleRenderPose(float interpolationAlpha) {
        float alpha = Math.max(0.0f, Math.min(1.0f, interpolationAlpha));
        Vector3f interpolatedPosition = new Vector3f(previousPosition).lerp(position, alpha);
        float interpolatedYaw = normalizeDegrees(previousYaw + shortestAngleDelta(previousYaw, yaw) * alpha);
        float interpolatedPitch = previousPitch + (pitch - previousPitch) * alpha;
        return new PlayerRenderPose(interpolatedPosition, interpolatedYaw, interpolatedPitch);
    }

    public void move(Vector3f offset) {
        if (offset.lengthSquared() == 0.0f) {
            return;
        }
        position.add(offset);
    }

    public void setY(float y) {
        position.y = y;
    }

    public void moveRelative(Vector3f localOffset) {
        if (localOffset.lengthSquared() == 0.0f) {
            return;
        }

        Vector3f forward = getForward();
        Vector3f right = getRight();

        position.add(new Vector3f(forward).mul(localOffset.z));
        position.add(new Vector3f(right).mul(localOffset.x));
        position.y += localOffset.y;
    }

    public void rotate(float yawDelta, float pitchDelta) {
        if (yawDelta == 0.0f && pitchDelta == 0.0f) {
            return;
        }
        yaw += yawDelta;
        pitch += pitchDelta;
        pitch = Math.max(-89.0f, Math.min(89.0f, pitch));
    }

    public Vector3f getForward() {
        return new Vector3f(
                (float) (Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch))),
                (float) Math.sin(Math.toRadians(pitch)),
                (float) (Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)))
        ).normalize();
    }

    public Vector3f getRight() {
        return new Vector3f(getForward()).cross(new Vector3f(0.0f, 1.0f, 0.0f)).normalize();
    }

    public float getYaw() {
        return yaw;
    }

    public float getPitch() {
        return pitch;
    }

    public float getVerticalVelocity() {
        return verticalVelocity;
    }

    public void setVerticalVelocity(float verticalVelocity) {
        this.verticalVelocity = verticalVelocity;
    }

    public boolean isGrounded() {
        return grounded;
    }

    public void setGrounded(boolean grounded) {
        this.grounded = grounded;
    }

    public boolean isNoclip() {
        return noclip;
    }

    public void setNoclip(boolean noclip) {
        this.noclip = noclip;
        if (noclip) {
            verticalVelocity = 0.0f;
            grounded = false;
        }
    }

    public void toggleNoclip() {
        setNoclip(!noclip);
    }

    private static float shortestAngleDelta(float fromDegrees, float toDegrees) {
        return normalizeDegrees(toDegrees - fromDegrees);
    }

    private static float normalizeDegrees(float degrees) {
        float normalized = degrees % 360.0f;
        if (normalized >= 180.0f) {
            normalized -= 360.0f;
        } else if (normalized < -180.0f) {
            normalized += 360.0f;
        }
        return normalized;
    }
}
