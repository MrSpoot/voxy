//@vs
#version 460 core

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;

out vec2 vUv;

void main() {
    vUv = aTexCoord;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
//@endvs

//@fs
#version 460 core

uniform vec3 uCameraRight;
uniform vec3 uCameraUp;
uniform vec3 uCameraForward;
uniform float uVerticalFov;
uniform float uAspectRatio;
uniform float uTimeOfDay;

in vec2 vUv;
layout(location = 0) out vec4 fragColor;

const vec3 NIGHT_COLOR = vec3(0.15, 0.3, 0.6);
const vec3 HORIZON_COLOR = vec3(0.6, 0.3, 0.4);
const vec3 DAY_COLOR = vec3(0.58, 0.72, 1.0);
const vec3 SUN_COLOR = vec3(1.0, 0.8, 0.6);
const vec3 SUN_RIM_COLOR = vec3(1.0, 0.66, 0.33);

float hash(vec3 position) {
    position = fract(position * 0.3183099 + vec3(0.71, 0.113, 0.419));
    position *= 17.0;
    return fract(position.x * position.y * position.z * (position.x + position.y + position.z));
}

void main() {
    vec2 projectionPosition = vUv * 2.0 - 1.0;
    projectionPosition.x *= uAspectRatio;

    float projectionDistance = 1.0 / tan(uVerticalFov * 0.5);
    vec3 cameraRay = normalize(vec3(projectionPosition, projectionDistance));
    vec3 rayDirection = normalize(
        cameraRay.x * uCameraRight +
        cameraRay.y * uCameraUp +
        cameraRay.z * uCameraForward
    );

    float sunAngle = -uTimeOfDay * 2.0 * 3.14159;
    vec3 sunDirection = normalize(vec3(
        cos(sunAngle) * 0.95,
        sin(sunAngle),
        cos(sunAngle) * 0.4
    ));
    float sunDot = clamp(dot(sunDirection, rayDirection), 0.0, 1.0);
    float sunHeight = sunDirection.y;

    const float nightHeight = -0.8;
    const float dayHeight = 0.3;
    const float horizonLength = dayHeight - nightHeight;
    const float halfHorizonLength = horizonLength * 0.5;
    const float midpoint = nightHeight + halfHorizonLength;

    float nightContribution = clamp((sunHeight - midpoint) * (-1.0 / halfHorizonLength), 0.0, 1.0);
    float horizonContribution = 1.0 - clamp(abs((sunHeight - midpoint) * (-1.0 / halfHorizonLength)), 0.0, 1.0);
    float dayContribution = clamp((sunHeight - midpoint) * (1.0 / halfHorizonLength), 0.0, 1.0);

    vec3 skyColor =
        NIGHT_COLOR * nightContribution +
        HORIZON_COLOR * horizonContribution +
        DAY_COLOR * dayContribution;
    skyColor -= clamp(rayDirection.y, 0.0, 0.5);
    skyColor += 0.4 * SUN_RIM_COLOR * pow(sunDot, 4.0);
    skyColor += SUN_COLOR * pow(sunDot, 2000.0);

    float starNoise = hash(floor(rayDirection * 400.0));
    float star = smoothstep(0.997, 1.0, starNoise);
    float starIntensity = 1.0 - clamp(sunHeight * 4.0, 0.0, 1.0);
    skyColor += vec3(star) * starIntensity;

    fragColor = vec4(skyColor, 1.0);
}
//@endfs
