package org.weaw.engine.graphics.pipeline;

import org.weaw.game.WorldTimeState;

/** Pure, continuous mapping from world time to render uniforms. */
public final class DayNightCycle {
    private DayNightCycle() {
    }

    public static DayNightVisualState sample(double rawPhase) {
        double phase = WorldTimeState.normalize(rawPhase);
        double angle = (phase - 0.25) * Math.PI * 2.0;
        float x = (float) (Math.cos(angle) * 0.92);
        float y = (float) Math.sin(angle);
        float z = (float) (Math.cos(angle) * 0.38);
        float inverseLength = 1.0f / (float) Math.sqrt(x * x + y * y + z * z);
        x *= inverseLength;
        y *= inverseLength;
        z *= inverseLength;

        float daylight = smoothstep(-0.12f, 0.22f, y);
        float twilight = 1.0f - smoothstep(0.02f, 0.38f, Math.abs(y));
        float warm = twilight * (1.0f - 0.35f * daylight);

        return new DayNightVisualState(
                x, y, z, daylight, twilight,
                lerp(0.46f, 1.0f, daylight), lerp(0.53f, 0.96f, daylight), lerp(0.72f, 0.88f, daylight),
                lerp(0.35f, 1.0f, daylight), lerp(0.40f, 1.0f, daylight), lerp(0.60f, 1.0f, daylight),
                lerp(0.55f, 1.0f, daylight),
                lerp(0.015f, 0.20f, daylight), lerp(0.020f, 0.48f, daylight), lerp(0.045f, 0.95f, daylight),
                lerp(0.30f, 1.0f, daylight),
                lerp(0.025f, 0.32f, daylight) + 0.10f * warm,
                lerp(0.030f, 0.58f, daylight) + 0.04f * warm,
                lerp(0.060f, 0.88f, daylight),
                lerp(0.04f, 0.32f, daylight) + 0.08f * warm,
                lerp(0.06f, 0.55f, daylight) + 0.03f * warm,
                lerp(0.12f, 0.82f, daylight),
                lerp(0.05f, 1.0f, daylight), lerp(0.06f, 1.0f, daylight), lerp(0.09f, 1.0f, daylight),
                lerp(0.45f, 0.0f, daylight)
        );
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * amount;
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.clamp((value - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }
}
