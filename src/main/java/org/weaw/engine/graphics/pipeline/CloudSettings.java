package org.weaw.engine.graphics.pipeline;

public class CloudSettings {
    public static final float DEFAULT_ALTITUDE = 145f;
    public static final float DEFAULT_SPEED = 1.5f;
    public static final float DEFAULT_DENSITY = 0.3f;
    public static final float DEFAULT_CELL_SIZE = 14.0f;
    public static final float DEFAULT_CLOUD_SIZE = 0.25f;

    private final boolean[] enabled = {true};
    private final float[] altitude = {DEFAULT_ALTITUDE};
    private final float[] speed = {DEFAULT_SPEED};
    private final float[] density = {DEFAULT_DENSITY};
    private final float[] cellSize = {DEFAULT_CELL_SIZE};
    private final float[] cloudSize = {DEFAULT_CLOUD_SIZE};

    public boolean isEnabled() {
        return enabled[0];
    }

    public void setEnabled(boolean enabled) {
        this.enabled[0] = enabled;
    }

    public float getAltitude() {
        return altitude[0];
    }

    public float[] altitudeRef() {
        return altitude;
    }

    public float getSpeed() {
        return speed[0];
    }

    public float[] speedRef() {
        return speed;
    }

    public float getDensity() {
        return density[0];
    }

    public float[] densityRef() {
        return density;
    }

    public float getCellSize() {
        return cellSize[0];
    }

    public float[] cellSizeRef() {
        return cellSize;
    }

    public float getCloudSize() {
        return cloudSize[0];
    }

    public float[] cloudSizeRef() {
        return cloudSize;
    }

    public void reset() {
        enabled[0] = true;
        altitude[0] = DEFAULT_ALTITUDE;
        speed[0] = DEFAULT_SPEED;
        density[0] = DEFAULT_DENSITY;
        cellSize[0] = DEFAULT_CELL_SIZE;
        cloudSize[0] = DEFAULT_CLOUD_SIZE;
    }
}
