package org.weaw.engine.graphics.utils;

import lombok.Getter;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.weaw.engine.input.InputAction;
import org.weaw.engine.input.InputManager;

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

    //TODO Temporary
    private final float moveSpeed = 15.0f;
    private final float mouseSensitivity = 0.15f;

    public Camera(float fov, float aspectRatio) {
        this.aspectRatio = aspectRatio;
        this.fov = fov;
        LOGGER.debug("Camera created. FOV: {} - Aspect Ratio: {}", this.fov, this.aspectRatio);
    }

    //TODO Temporary
    public void update(float dt, InputManager input) {
        Vector3f movement = new Vector3f();

        float speed = moveSpeed * dt;

        if (input.isActionDown(InputAction.MOVE_FORWARD)) movement.z += 1;
        if (input.isActionDown(InputAction.MOVE_BACKWARD)) movement.z -= 1;
        if (input.isActionDown(InputAction.MOVE_LEFT)) movement.x -= 1;
        if (input.isActionDown(InputAction.MOVE_RIGHT)) movement.x += 1;
        if (input.isActionDown(InputAction.SPRINT)) speed *= 2;

        if (movement.lengthSquared() > 0) {
            movement.normalize().mul(speed);
            moveRelative(movement);
        }

        // Rotation via souris
        float dx = input.getMousePosition().deltaX();
        float dy = input.getMousePosition().deltaY();
        rotate(dx * mouseSensitivity, dy * mouseSensitivity);
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
        Vector3f forward = getForward();
        Vector3f right = getRight();
        return new Vector3f(right).cross(forward).normalize();
    }

    public Vector3f getRight() {
        Vector3f forward = getForward();
        return new Vector3f(forward).cross(new Vector3f(0, 1, 0)).normalize();
    }

    public Vector3f getForward() {
        return new Vector3f(
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
