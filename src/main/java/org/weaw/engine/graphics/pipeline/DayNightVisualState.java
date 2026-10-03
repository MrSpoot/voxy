package org.weaw.engine.graphics.pipeline;

/** Uniform-only lighting values sampled from the current world-time phase. */
public record DayNightVisualState(
        float sunX, float sunY, float sunZ,
        float daylight, float twilight,
        float sunRed, float sunGreen, float sunBlue,
        float ambientRed, float ambientGreen, float ambientBlue, float ambientMultiplier,
        float skyRed, float skyGreen, float skyBlue, float skyMultiplier,
        float horizonRed, float horizonGreen, float horizonBlue,
        float fogRed, float fogGreen, float fogBlue,
        float cloudRed, float cloudGreen, float cloudBlue,
        float exposureOffset
) {
}
