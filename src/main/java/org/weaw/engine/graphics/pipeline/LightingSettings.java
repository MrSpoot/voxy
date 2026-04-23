package org.weaw.engine.graphics.pipeline;

public class LightingSettings {
    private final boolean[] enabled = {true};

    private final float[] ambientColor = {0.60f, 0.66f, 0.76f};
    private final float[] ambientIntensity = {0.28f};

    private final float[] sunColor = {1.0f, 0.90f, 0.72f};
    private final float[] sunIntensity = {1.45f};

    private final float[] sunDirection = {-0.35f, 0.86f, -0.28f};

    private final float[] skyColor = {0.47f, 0.68f, 0.90f};
    private final float[] skyIntensity = {0.8f};
    private final boolean[] blockLightEnabled = {true};
    private final float[] blockLightIntensity = {1.15f};

    public boolean isEnabled() {
        return enabled[0];
    }

    public void setEnabled(boolean enabled) {
        this.enabled[0] = enabled;
    }

    public float[] ambientColorRef() {
        return ambientColor;
    }

    public float getAmbientRed() {
        return ambientColor[0];
    }

    public float getAmbientGreen() {
        return ambientColor[1];
    }

    public float getAmbientBlue() {
        return ambientColor[2];
    }

    public float[] ambientIntensityRef() {
        return ambientIntensity;
    }

    public float getAmbientIntensity() {
        return ambientIntensity[0];
    }

    public float[] sunColorRef() {
        return sunColor;
    }

    public float getSunRed() {
        return sunColor[0];
    }

    public float getSunGreen() {
        return sunColor[1];
    }

    public float getSunBlue() {
        return sunColor[2];
    }

    public float[] sunIntensityRef() {
        return sunIntensity;
    }

    public float getSunIntensity() {
        return sunIntensity[0];
    }

    public float[] sunDirectionRef() {
        return sunDirection;
    }

    public float getSunDirectionX() {
        return sunDirection[0];
    }

    public float getSunDirectionY() {
        return sunDirection[1];
    }

    public float getSunDirectionZ() {
        return sunDirection[2];
    }

    public float[] skyColorRef() {
        return skyColor;
    }

    public float getSkyRed() {
        return skyColor[0];
    }

    public float getSkyGreen() {
        return skyColor[1];
    }

    public float getSkyBlue() {
        return skyColor[2];
    }

    public float[] skyIntensityRef() {
        return skyIntensity;
    }

    public float getSkyIntensity() {
        return skyIntensity[0];
    }

    public void reset() {
        enabled[0] = true;
        ambientColor[0] = 0.72f;
        ambientColor[1] = 0.82f;
        ambientColor[2] = 1.0f;
        ambientIntensity[0] = 0.38f;
        sunColor[0] = 1.0f;
        sunColor[1] = 0.94f;
        sunColor[2] = 0.82f;
        sunIntensity[0] = 1.85f;
        sunDirection[0] = -0.35f;
        sunDirection[1] = 0.86f;
        sunDirection[2] = -0.28f;
        skyColor[0] = 0.53f;
        skyColor[1] = 0.78f;
        skyColor[2] = 0.92f;
        skyIntensity[0] = 1.25f;
        blockLightEnabled[0] = true;
        blockLightIntensity[0] = 1.15f;
    }

    public boolean isBlockLightEnabled() {
        return blockLightEnabled[0];
    }

    public void setBlockLightEnabled(boolean enabled) {
        blockLightEnabled[0] = enabled;
    }

    public float[] blockLightIntensityRef() {
        return blockLightIntensity;
    }

    public float getBlockLightIntensity() {
        return blockLightIntensity[0];
    }
}
