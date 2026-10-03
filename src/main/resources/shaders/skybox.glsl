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
uniform vec3 uSunDirection;
uniform float uDaylight;
uniform float uTwilight;
uniform vec3 uSunColor;
uniform vec3 uSkyColor;
uniform vec3 uHorizonColor;

in vec2 vUv;
layout(location = 0) out vec4 fragColor;

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

    vec3 sunDirection = normalize(uSunDirection);
    float sunDot = clamp(dot(sunDirection, rayDirection), 0.0, 1.0);
    float horizonBlend = smoothstep(-0.03, 0.20, rayDirection.y);
    vec3 skyColor = mix(uHorizonColor, uSkyColor, horizonBlend);
    skyColor += 0.10 * SUN_RIM_COLOR * pow(sunDot, 64.0) * (uDaylight + uTwilight);
    skyColor += uSunColor * pow(sunDot, 2000.0) * uDaylight;

    float moonDot = clamp(dot(-sunDirection, rayDirection), 0.0, 1.0);
    float moonVisibility = 1.0 - uDaylight;
    const float MOON_RADIUS_COS = 0.9998477;
    float moonEdge = max(fwidth(moonDot) * 1.5, 0.00002);
    float moonDisc = smoothstep(MOON_RADIUS_COS - moonEdge, MOON_RADIUS_COS + moonEdge, moonDot);
    float moonHalo = pow(moonDot, 500.0);
    skyColor += vec3(0.42, 0.50, 0.72) * moonHalo * 0.10 * moonVisibility;
    skyColor += vec3(0.90, 0.93, 1.0) * moonDisc * 1.15 * moonVisibility;

    float starNoise = hash(floor(rayDirection * 400.0));
    float star = smoothstep(0.997, 1.0, starNoise);
    float starIntensity = 1.0 - uDaylight;
    skyColor += vec3(star) * starIntensity;

    fragColor = vec4(skyColor, 1.0);
}
//@endfs
