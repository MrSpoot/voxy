package org.weaw.engine.graphics.pipeline;

public class LightingSettings {
    private final boolean[] enabled = {true};

    private final float[] ambientColor = {0.60f, 0.66f, 0.76f};
    private final float[] ambientIntensity = {0.05f};
    private final float[] shadowStrength = {0.8f};

    private final float[] sunColor = {1.0f, 0.90f, 0.72f};
    private final float[] sunIntensity = {0.85f};

    private final float[] skyColor = {0.47f, 0.68f, 0.90f};
    private final float[] skyIntensity = {0.35f};
    private final float[] voxelLightGamma = {0.85f};
    private final float[] voxelDarknessFloor = {0.04f};

    private final boolean[] distanceSofteningEnabled = {true};
    private final float[] distanceSofteningStartRatio = {0.35f};
    private final float[] distanceSofteningEndRatio = {0.80f};
    private final float[] distantDirectionalStrength = {0.20f};
    private final float[] distantAoStrength = {0.25f};

    private final float[] blockLightIntensity = {1.0f};

    private final float[] sunDirection = {-0.35f, 0.86f, -0.28f};
    private final boolean[] blockLightEnabled = {true};


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

    public float[] shadowStrengthRef() {
        return shadowStrength;
    }

    public float getShadowStrength() {
        return shadowStrength[0];
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

    public float[] voxelLightGammaRef() {
        return voxelLightGamma;
    }

    public float getVoxelLightGamma() {
        return voxelLightGamma[0];
    }

    public float[] voxelDarknessFloorRef() {
        return voxelDarknessFloor;
    }

    public float getVoxelDarknessFloor() {
        return voxelDarknessFloor[0];
    }

    public boolean isDistanceSofteningEnabled() {
        return distanceSofteningEnabled[0];
    }

    public void setDistanceSofteningEnabled(boolean enabled) {
        distanceSofteningEnabled[0] = enabled;
    }

    public float[] distanceSofteningStartRatioRef() {
        return distanceSofteningStartRatio;
    }

    public float getDistanceSofteningStartRatio() {
        return Math.clamp(distanceSofteningStartRatio[0], 0.0f, 0.95f);
    }

    public float[] distanceSofteningEndRatioRef() {
        return distanceSofteningEndRatio;
    }

    public float getDistanceSofteningEndRatio() {
        return Math.clamp(distanceSofteningEndRatio[0], getDistanceSofteningStartRatio() + 0.05f, 1.0f);
    }

    public float[] distantDirectionalStrengthRef() {
        return distantDirectionalStrength;
    }

    public float getDistantDirectionalStrength() {
        return Math.clamp(distantDirectionalStrength[0], 0.0f, 1.0f);
    }

    public float[] distantAoStrengthRef() {
        return distantAoStrength;
    }

    public float getDistantAoStrength() {
        return Math.clamp(distantAoStrength[0], 0.0f, 1.0f);
    }

    public void reset() {
        enabled[0] = true;

        ambientColor[0] = 0.60f;
        ambientColor[1] = 0.66f;
        ambientColor[2] = 0.76f;
        ambientIntensity[0] = 0.08f;
        shadowStrength[0] = 0.8f;

        sunColor[0] = 1.0f;
        sunColor[1] = 0.90f;
        sunColor[2] = 0.72f;
        sunIntensity[0] = 1.0f;

        sunDirection[0] = -0.35f;
        sunDirection[1] = 0.86f;
        sunDirection[2] = -0.28f;

        skyColor[0] = 0.47f;
        skyColor[1] = 0.68f;
        skyColor[2] = 0.90f;
        skyIntensity[0] = 0.35f;
        voxelLightGamma[0] = 0.85f;
        voxelDarknessFloor[0] = 0.04f;

        distanceSofteningEnabled[0] = true;
        distanceSofteningStartRatio[0] = 0.35f;
        distanceSofteningEndRatio[0] = 0.80f;
        distantDirectionalStrength[0] = 0.20f;
        distantAoStrength[0] = 0.25f;

        blockLightEnabled[0] = true;
        blockLightIntensity[0] = 1.0f;
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
