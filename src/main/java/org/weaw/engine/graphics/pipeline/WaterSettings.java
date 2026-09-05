package org.weaw.engine.graphics.pipeline;

public class WaterSettings {
    public static final float DEFAULT_SURFACE_INSET = 0.125f;
    public static final float DEFAULT_WAVE_AMPLITUDE = 0.035f;
    public static final float DEFAULT_WAVE_SPEED = 1.0f;
    public static final float DEFAULT_WAVE_LENGTH = 8.0f;

    private final boolean[] wavesEnabled = {true};
    private final float[] surfaceInset = {DEFAULT_SURFACE_INSET};
    private final float[] waveAmplitude = {DEFAULT_WAVE_AMPLITUDE};
    private final float[] waveSpeed = {DEFAULT_WAVE_SPEED};
    private final float[] waveLength = {DEFAULT_WAVE_LENGTH};

    public boolean areWavesEnabled() {
        return wavesEnabled[0];
    }

    public void setWavesEnabled(boolean enabled) {
        wavesEnabled[0] = enabled;
    }

    public float getSurfaceInset() {
        return surfaceInset[0];
    }

    public float[] surfaceInsetRef() {
        return surfaceInset;
    }

    public float getWaveAmplitude() {
        return waveAmplitude[0];
    }

    public float[] waveAmplitudeRef() {
        return waveAmplitude;
    }

    public float getWaveSpeed() {
        return waveSpeed[0];
    }

    public float[] waveSpeedRef() {
        return waveSpeed;
    }

    public float getWaveLength() {
        return waveLength[0];
    }

    public float[] waveLengthRef() {
        return waveLength;
    }

    public void reset() {
        wavesEnabled[0] = true;
        surfaceInset[0] = DEFAULT_SURFACE_INSET;
        waveAmplitude[0] = DEFAULT_WAVE_AMPLITUDE;
        waveSpeed[0] = DEFAULT_WAVE_SPEED;
        waveLength[0] = DEFAULT_WAVE_LENGTH;
    }
}
