package org.weaw.engine.graphics.pipeline;

public class ColorGradingSettings {
    private final boolean[] enabled = {true};
    private final boolean[] toneMappingEnabled = {true};
    private final float[] exposure = {0.045f};
    private final float[] contrast = {1.18f};
    private final float[] saturation = {1.05f};
    private final float[] vibrance = {0.14f};
    private final float[] gamma = {1.0f};
    private final float[] temperature = {0.05f};

    public boolean isEnabled() {
        return enabled[0];
    }

    public void setEnabled(boolean enabled) {
        this.enabled[0] = enabled;
    }

    public boolean isToneMappingEnabled() {
        return toneMappingEnabled[0];
    }

    public void setToneMappingEnabled(boolean toneMappingEnabled) {
        this.toneMappingEnabled[0] = toneMappingEnabled;
    }

    public float getExposure() {
        return exposure[0];
    }

    public float[] exposureRef() {
        return exposure;
    }

    public float getContrast() {
        return contrast[0];
    }

    public float[] contrastRef() {
        return contrast;
    }

    public float getSaturation() {
        return saturation[0];
    }

    public float[] saturationRef() {
        return saturation;
    }

    public float getVibrance() {
        return vibrance[0];
    }

    public float[] vibranceRef() {
        return vibrance;
    }

    public float getGamma() {
        return gamma[0];
    }

    public float[] gammaRef() {
        return gamma;
    }

    public float getTemperature() {
        return temperature[0];
    }

    public float[] temperatureRef() {
        return temperature;
    }

    public void reset() {
        enabled[0] = true;
        toneMappingEnabled[0] = true;
        exposure[0] = 0.0f;
        contrast[0] = 1.0f;
        saturation[0] = 1.0f;
        vibrance[0] = 0.0f;
        gamma[0] = 1.0f;
        temperature[0] = 0.0f;
    }
}
