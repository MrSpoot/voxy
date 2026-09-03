//@vs
#version 460 core

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;

out vec2 vTexCoord;

void main() {
    vTexCoord = aTexCoord;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
//@endvs

//@fs
#version 460 core

uniform sampler2D uLogLuminanceTexture;
uniform sampler2D uPreviousExposureTexture;
uniform float uCompensationEv;
uniform float uMinimumExposureEv;
uniform float uMaximumExposureEv;
uniform float uTargetLuminance;
uniform float uDarkenSpeed;
uniform float uBrightenSpeed;
uniform float uDeltaTime;

layout(location = 0) out float outExposureEv;

const float MINIMUM_LUMINANCE = 0.0001;
const float MAXIMUM_DELTA_TIME = 0.1;

void main() {
    float averageLuminance = exp(clamp(texelFetch(uLogLuminanceTexture, ivec2(0), 0).r, -20.0, 20.0));
    float targetExposureEv = log2(
        max(uTargetLuminance, MINIMUM_LUMINANCE) /
        max(averageLuminance, MINIMUM_LUMINANCE)
    ) + uCompensationEv;
    targetExposureEv = clamp(targetExposureEv, uMinimumExposureEv, uMaximumExposureEv);

    float previousExposureEv = clamp(
        texelFetch(uPreviousExposureTexture, ivec2(0), 0).r,
        uMinimumExposureEv,
        uMaximumExposureEv
    );
    float adaptationSpeed = targetExposureEv < previousExposureEv ? uDarkenSpeed : uBrightenSpeed;
    float blend = 1.0 - exp(-max(adaptationSpeed, 0.0) * clamp(uDeltaTime, 0.0, MAXIMUM_DELTA_TIME));
    outExposureEv = mix(previousExposureEv, targetExposureEv, blend);
}
//@endfs
