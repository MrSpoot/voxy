package org.weaw.engine.graphics.pipeline;

/** Runtime quality state used by effects that can scale without changing world simulation. */
public final class AdaptiveGraphicsQuality {
    public enum Level {
        HIGH(160, 1, true),
        MEDIUM(120, 2, true),
        LOW(80, 4, false);

        private final int cloudGridSide;
        private final int autoExposureIntervalFrames;
        private final boolean waterWavesEnabled;

        Level(int cloudGridSide, int autoExposureIntervalFrames, boolean waterWavesEnabled) {
            this.cloudGridSide = cloudGridSide;
            this.autoExposureIntervalFrames = autoExposureIntervalFrames;
            this.waterWavesEnabled = waterWavesEnabled;
        }

        public int cloudGridSide() {
            return cloudGridSide;
        }

        public int autoExposureIntervalFrames() {
            return autoExposureIntervalFrames;
        }

        public boolean waterWavesEnabled() {
            return waterWavesEnabled;
        }
    }

    private boolean enabled = !Boolean.getBoolean("voxy.disableAdaptiveQuality");
    private Level level = Level.HIGH;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            level = Level.HIGH;
        }
    }

    public Level getLevel() {
        return level;
    }

    void setLevel(Level level) {
        this.level = level;
    }
}
