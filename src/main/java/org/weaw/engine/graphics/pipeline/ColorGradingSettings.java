package org.weaw.engine.graphics.pipeline;

public class ColorGradingSettings {
    private final boolean[] enabled = {true};
    private final float[] exposure = {0.039f};
    private final float[] contrast = {1.024f};
    private final float[] saturation = {1.095f};
    private final float[] vibrance = {0.319f};
    private final float[] gamma = {1f};
    private final float[] temperature = {0.068f};

    public boolean isEnabled() {
        return enabled[0];
    }

    public void setEnabled(boolean enabled) {
        this.enabled[0] = enabled;
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
        exposure[0] = 0.039f;
        contrast[0] = 1.024f;
        saturation[0] = 1.095f;
        vibrance[0] = 0.319f;
        gamma[0] = 1f;
        temperature[0] = 0.068f;
    }
}
