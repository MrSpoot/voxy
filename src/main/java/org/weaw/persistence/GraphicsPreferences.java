package org.weaw.persistence;

/** Persisted player-facing graphics configuration. */
public record GraphicsPreferences(
        GraphicsPreset preset,
        AntiAliasingMode antiAliasing,
        boolean cloudsEnabled,
        boolean waterWavesEnabled,
        boolean lightingEnabled,
        boolean blockLightingEnabled,
        boolean toneMappingEnabled,
        boolean autoExposureEnabled,
        float exposure,
        float contrast,
        float saturation,
        float gamma
) {
    public GraphicsPreferences {
        preset = preset == null ? GraphicsPreset.AUTO : preset;
        antiAliasing = antiAliasing == null ? AntiAliasingMode.FXAA : antiAliasing;
        exposure = clampFinite(exposure, -4.0f, 4.0f, 0.045f);
        contrast = clampFinite(contrast, 0.5f, 2.0f, 1.0f);
        saturation = clampFinite(saturation, 0.0f, 2.0f, 1.0f);
        gamma = clampFinite(gamma, 0.5f, 3.0f, 2.2f);
    }

    public static GraphicsPreferences defaults() {
        return forPreset(GraphicsPreset.AUTO, null);
    }

    public static GraphicsPreferences forPreset(GraphicsPreset preset, GraphicsPreferences previous) {
        GraphicsPreferences base = previous == null
                ? new GraphicsPreferences(
                        GraphicsPreset.AUTO, AntiAliasingMode.FXAA,
                        true, true, true, true, true, true,
                        0.045f, 1.0f, 1.0f, 2.2f
                )
                : previous;
        return switch (preset) {
            case AUTO -> base.withQuality(GraphicsPreset.AUTO, AntiAliasingMode.FXAA, true, true, true, true, true, true);
            case LOW -> base.withQuality(GraphicsPreset.LOW, AntiAliasingMode.OFF, false, false, true, false, true, false);
            case MEDIUM -> base.withQuality(GraphicsPreset.MEDIUM, AntiAliasingMode.FXAA, true, false, true, true, true, true);
            case HIGH -> base.withQuality(GraphicsPreset.HIGH, AntiAliasingMode.MSAA_4X, true, true, true, true, true, true);
            case CUSTOM -> new GraphicsPreferences(
                    GraphicsPreset.CUSTOM, base.antiAliasing, base.cloudsEnabled, base.waterWavesEnabled,
                    base.lightingEnabled, base.blockLightingEnabled, base.toneMappingEnabled,
                    base.autoExposureEnabled, base.exposure, base.contrast, base.saturation, base.gamma
            );
        };
    }

    public GraphicsPreferences customized(
            AntiAliasingMode antiAliasing,
            boolean cloudsEnabled,
            boolean waterWavesEnabled,
            boolean lightingEnabled,
            boolean blockLightingEnabled,
            boolean toneMappingEnabled,
            boolean autoExposureEnabled,
            float exposure,
            float contrast,
            float saturation,
            float gamma
    ) {
        return new GraphicsPreferences(
                GraphicsPreset.CUSTOM, antiAliasing, cloudsEnabled, waterWavesEnabled, lightingEnabled,
                blockLightingEnabled, toneMappingEnabled, autoExposureEnabled,
                exposure, contrast, saturation, gamma
        );
    }

    private GraphicsPreferences withQuality(
            GraphicsPreset preset,
            AntiAliasingMode antiAliasing,
            boolean clouds,
            boolean waves,
            boolean lighting,
            boolean blockLighting,
            boolean toneMapping,
            boolean autoExposure
    ) {
        return new GraphicsPreferences(
                preset, antiAliasing, clouds, waves, lighting, blockLighting, toneMapping, autoExposure,
                exposure, contrast, saturation, gamma
        );
    }

    private static float clampFinite(float value, float minimum, float maximum, float fallback) {
        return Float.isFinite(value) ? Math.clamp(value, minimum, maximum) : fallback;
    }
}
