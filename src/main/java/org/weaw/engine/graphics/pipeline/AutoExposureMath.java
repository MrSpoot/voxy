package org.weaw.engine.graphics.pipeline;

public final class AutoExposureMath {
    public static final float MINIMUM_LUMINANCE = 1.0e-4f;
    public static final float MAXIMUM_FRAME_DELTA_SECONDS = 0.1f;

    private AutoExposureMath() {
    }

    public static float targetExposureEv(
            float averageLuminance,
            float targetLuminance,
            float compensationEv,
            float minimumExposureEv,
            float maximumExposureEv
    ) {
        float safeLuminance = Math.max(MINIMUM_LUMINANCE, finiteOr(averageLuminance, MINIMUM_LUMINANCE));
        float safeTarget = Math.max(MINIMUM_LUMINANCE, finiteOr(targetLuminance, 0.18f));
        float safeMinimum = finiteOr(minimumExposureEv, -4.0f);
        float safeMaximum = finiteOr(maximumExposureEv, 4.0f);
        float minimum = Math.min(safeMinimum, safeMaximum);
        float maximum = Math.max(safeMinimum, safeMaximum);
        float exposure = (float) (Math.log(safeTarget / safeLuminance) / Math.log(2.0))
                + finiteOr(compensationEv, 0.0f);
        return Math.max(minimum, Math.min(maximum, exposure));
    }

    public static float adaptExposure(
            float currentExposureEv,
            float targetExposureEv,
            float frameDeltaSeconds,
            float darkenSpeed,
            float brightenSpeed
    ) {
        float current = finiteOr(currentExposureEv, 0.0f);
        float target = finiteOr(targetExposureEv, current);
        float delta = Math.max(0.0f, Math.min(
                MAXIMUM_FRAME_DELTA_SECONDS,
                finiteOr(frameDeltaSeconds, 0.0f)
        ));
        float speed = target < current ? darkenSpeed : brightenSpeed;
        speed = Math.max(0.0f, finiteOr(speed, 0.0f));
        float blend = 1.0f - (float) Math.exp(-speed * delta);
        return current + (target - current) * blend;
    }

    private static float finiteOr(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }
}
