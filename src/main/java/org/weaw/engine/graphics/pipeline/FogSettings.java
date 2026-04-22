package org.weaw.engine.graphics.pipeline;

public class FogSettings {
    private final boolean[] enabled = {true};
    private final float[] intensity = {0.45f};
    private final float[] startRatio = {0.58f};
    private final float[] endRatio = {0.92f};
    private final float[] density = {1.35f};
    private final float[] color = {0.62f, 0.78f, 0.90f};

    public boolean isEnabled() {
        return enabled[0];
    }

    public void setEnabled(boolean enabled) {
        this.enabled[0] = enabled;
    }

    public float getIntensity() {
        return intensity[0];
    }

    public float[] intensityRef() {
        return intensity;
    }

    public float getStartRatio() {
        return startRatio[0];
    }

    public float[] startRatioRef() {
        return startRatio;
    }

    public float getEndRatio() {
        return Math.max(endRatio[0], startRatio[0] + 0.01f);
    }

    public float[] endRatioRef() {
        return endRatio;
    }

    public float getDensity() {
        return density[0];
    }

    public float[] densityRef() {
        return density;
    }

    public float[] colorRef() {
        return color;
    }

    public float getRed() {
        return color[0];
    }

    public float getGreen() {
        return color[1];
    }

    public float getBlue() {
        return color[2];
    }

    public void reset() {
        enabled[0] = true;
        intensity[0] = 0.45f;
        startRatio[0] = 0.58f;
        endRatio[0] = 0.92f;
        density[0] = 1.35f;
        color[0] = 0.62f;
        color[1] = 0.78f;
        color[2] = 0.90f;
    }
}
