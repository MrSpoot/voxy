package org.weaw.engine.graphics.pipeline;

public class ColorGradingSettings {
    private final boolean[] enabled = {true};
    private final boolean[] toneMappingEnabled = {true};
    private final boolean[] autoExposureEnabled = {false};

    private final float[] exposure = {0.045f};
    private final float[] minimumExposureEv = {0.045f};
    private final float[] maximumExposureEv = {4.0f};
    private final float[] targetLuminance = {0.18f};
    private final float[] darkenAdaptationSpeed = {3.0f};
    private final float[] brightenAdaptationSpeed = {1.0f};
    private final float[] contrast = {1.0f};
    private final float[] saturation = {1f};
    private final float[] vibrance = {0.14f};
    private final float[] gamma = {2.2f};
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

    public boolean isAutoExposureEnabled() {
        return autoExposureEnabled[0];
    }

    public void setAutoExposureEnabled(boolean autoExposureEnabled) {
        this.autoExposureEnabled[0] = autoExposureEnabled;
    }

    public float getExposure() {
        return exposure[0];
    }

    public float[] exposureRef() {
        return exposure;
    }

    public float getMinimumExposureEv() {
        sanitizeAutoExposure();
        return minimumExposureEv[0];
    }

    public float[] minimumExposureEvRef() {
        return minimumExposureEv;
    }

    public float getMaximumExposureEv() {
        sanitizeAutoExposure();
        return maximumExposureEv[0];
    }

    public float[] maximumExposureEvRef() {
        return maximumExposureEv;
    }

    public float getTargetLuminance() {
        sanitizeAutoExposure();
        return targetLuminance[0];
    }

    public float[] targetLuminanceRef() {
        return targetLuminance;
    }

    public float getDarkenAdaptationSpeed() {
        sanitizeAutoExposure();
        return darkenAdaptationSpeed[0];
    }

    public float[] darkenAdaptationSpeedRef() {
        return darkenAdaptationSpeed;
    }

    public float getBrightenAdaptationSpeed() {
        sanitizeAutoExposure();
        return brightenAdaptationSpeed[0];
    }

    public float[] brightenAdaptationSpeedRef() {
        return brightenAdaptationSpeed;
    }

    public void sanitizeAutoExposure() {
        minimumExposureEv[0] = clamp(minimumExposureEv[0], -12.0f, 12.0f);
        maximumExposureEv[0] = clamp(maximumExposureEv[0], -12.0f, 12.0f);
        if (minimumExposureEv[0] > maximumExposureEv[0]) {
            maximumExposureEv[0] = minimumExposureEv[0];
        }
        targetLuminance[0] = clamp(targetLuminance[0], 0.01f, 1.0f);
        darkenAdaptationSpeed[0] = clamp(darkenAdaptationSpeed[0], 0.01f, 20.0f);
        brightenAdaptationSpeed[0] = clamp(brightenAdaptationSpeed[0], 0.01f, 20.0f);
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
        autoExposureEnabled[0] = false;
        exposure[0] = 0.045f;
        minimumExposureEv[0] = 0.045f;
        maximumExposureEv[0] = 4.0f;
        targetLuminance[0] = 0.18f;
        darkenAdaptationSpeed[0] = 3.0f;
        brightenAdaptationSpeed[0] = 1.0f;
        contrast[0] = 1.18f;
        saturation[0] = 1.05f;
        vibrance[0] = 0.14f;
        gamma[0] = 1.0f;
        temperature[0] = 0.05f;
    }

    private static float clamp(float value, float minimum, float maximum) {
        if (!Float.isFinite(value)) {
            return minimum;
        }
        return Math.max(minimum, Math.min(maximum, value));
    }
}
