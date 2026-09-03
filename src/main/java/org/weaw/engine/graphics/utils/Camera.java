package org.weaw.engine.graphics.utils;

import lombok.Getter;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Camera {

    private static final Logger LOGGER = LoggerFactory.getLogger(Camera.class);

    @Getter
    private final float fov;
    @Getter
    private float aspectRatio;
    private final float near = 0.1f;
    private final float far = 2500f;
    @Getter
    private long visibilityVersion;

    private final Vector3f position = new Vector3f();
    private float pitch = 0; // top/bot
    private float yaw = -90; // left/right

    public Camera(float fov, float aspectRatio) {
        this.aspectRatio = aspectRatio;
        this.fov = fov;
        LOGGER.debug("Camera created. FOV: {} - Aspect Ratio: {}", this.fov, this.aspectRatio);
    }

    public void move(Vector3f offset) {
        if (offset.lengthSquared() == 0.0f) {
            return;
        }
        position.add(offset);
        visibilityVersion++;
    }

    public void rotate(float yawDelta, float pitchDelta) {
        if (yawDelta == 0.0f && pitchDelta == 0.0f) {
            return;
        }
        yaw += yawDelta;
        pitch += pitchDelta;
        pitch = Math.max(-89f, Math.min(89f, pitch));
        visibilityVersion++;
    }

    public void moveRelative(Vector3f localOffset) {
        Vector3f forward = getForward();
        Vector3f right = new Vector3f(forward).cross(new Vector3f(0, 1, 0)).normalize();

        position.add(new Vector3f(forward).mul(localOffset.z));
        position.add(new Vector3f(right).mul(localOffset.x));
        position.y += localOffset.y;
        visibilityVersion++;
    }

    public Vector3f getUp(){
        return getUp(new Vector3f());
    }

    public Vector3f getUp(Vector3f destination) {
        float yawRadians = (float) Math.toRadians(yaw);
        float pitchRadians = (float) Math.toRadians(pitch);
        float sinPitch = (float) Math.sin(pitchRadians);
        float cosPitch = (float) Math.cos(pitchRadians);
        return destination.set(
                -(float) Math.cos(yawRadians) * sinPitch,
                cosPitch,
                -(float) Math.sin(yawRadians) * sinPitch
        ).normalize();
    }

    public Vector3f getRight() {
        return getRight(new Vector3f());
    }

    public Vector3f getRight(Vector3f destination) {
        float yawRadians = (float) Math.toRadians(yaw);
        return destination.set(
                -(float) Math.sin(yawRadians),
                0.0f,
                (float) Math.cos(yawRadians)
        ).normalize();
    }

    public Vector3f getForward() {
        return getForward(new Vector3f());
    }

    public Vector3f getForward(Vector3f destination) {
        return destination.set(
                (float) (Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch))),
                (float) Math.sin(Math.toRadians(pitch)),
                (float) (Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)))
        ).normalize();
    }


    public Vector3f getTarget() {
        return new Vector3f(position).add(getForward());
    }

    public Matrix4f getViewMatrix() {
        return getViewMatrix(new Matrix4f());
    }

    public Matrix4f getViewMatrix(Matrix4f dest) {
        float yawRadians = (float) Math.toRadians(yaw);
        float pitchRadians = (float) Math.toRadians(pitch);
        float cosPitch = (float) Math.cos(pitchRadians);
        float forwardX = (float) (Math.cos(yawRadians) * cosPitch);
        float forwardY = (float) Math.sin(pitchRadians);
        float forwardZ = (float) (Math.sin(yawRadians) * cosPitch);

        return dest.identity().lookAt(
                position.x,
                position.y,
                position.z,
                position.x + forwardX,
                position.y + forwardY,
                position.z + forwardZ,
                0.0f,
                1.0f,
                0.0f
        );
    }

    public Matrix4f getProjectionMatrix() {
        return new Matrix4f().perspective(
                (float) Math.toRadians(fov),
                aspectRatio,
                far,
                near,
                true
        );
    }

    public Matrix4f getProjectionMatrix(Matrix4f dest) {
        return dest.identity().perspective(
                (float) Math.toRadians(fov),
                aspectRatio,
                far,
                near,
                true
        );
    }

    public Matrix4f getViewProjectionMatrix() {
        return getProjectionMatrix().mul(getViewMatrix());
    }

    public FrustumIntersection createFrustumIntersection() {
        return new FrustumIntersection(getViewProjectionMatrix());
    }

    public void setPosition(Vector3f position) {
        this.position.set(position);
        visibilityVersion++;
    }

    public void setRotation(float yaw, float pitch) {
        float clampedPitch = Math.max(-89f, Math.min(89f, pitch));
        if (Float.compare(this.yaw, yaw) == 0 && Float.compare(this.pitch, clampedPitch) == 0) {
            return;
        }
        this.yaw = yaw;
        this.pitch = clampedPitch;
        visibilityVersion++;
    }

    public void setPose(Vector3f position, float yaw, float pitch) {
        boolean changed = !this.position.equals(position);
        float clampedPitch = Math.max(-89f, Math.min(89f, pitch));
        changed = changed || Float.compare(this.yaw, yaw) != 0 || Float.compare(this.pitch, clampedPitch) != 0;
        if (!changed) {
            return;
        }
        this.position.set(position);
        this.yaw = yaw;
        this.pitch = clampedPitch;
        visibilityVersion++;
    }

    public void setAspectRatio(float aspectRatio) {
        if (Float.compare(this.aspectRatio, aspectRatio) == 0) {
            return;
        }
        this.aspectRatio = aspectRatio;
        visibilityVersion++;
    }

    public Vector3f getPosition() {
        return new Vector3f(position);
    }

    public float getPitch() {
        return pitch;
    }

    public float getYaw() {
        return yaw;
    }

    public float getNear() {
        return near;
    }

    public float getFar() {
        return far;
    }
}
