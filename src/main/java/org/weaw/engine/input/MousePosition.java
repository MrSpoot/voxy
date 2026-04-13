package org.weaw.engine.input;

public final class MousePosition {
    private float deltaX;
    private float deltaY;

    public MousePosition(float deltaX, float deltaY) {
        set(deltaX, deltaY);
    }

    public float deltaX() {
        return deltaX;
    }

    public float deltaY() {
        return deltaY;
    }

    public void set(float deltaX, float deltaY) {
        this.deltaX = deltaX;
        this.deltaY = deltaY;
    }
}
