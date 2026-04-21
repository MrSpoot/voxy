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

uniform sampler2D uSceneTexture;
uniform int uEnabled;
uniform float uExposure;
uniform float uContrast;
uniform float uSaturation;
uniform float uVibrance;
uniform float uGamma;
uniform float uTemperature;

in vec2 vTexCoord;

out vec4 fragColor;

vec3 applyContrast(vec3 color, float contrast) {
    return (color - 0.5) * contrast + 0.5;
}

vec3 applySaturation(vec3 color, float saturation) {
    float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
    return mix(vec3(luminance), color, saturation);
}

vec3 applyVibrance(vec3 color, float vibrance) {
    float maxChannel = max(color.r, max(color.g, color.b));
    float minChannel = min(color.r, min(color.g, color.b));
    float colorfulness = clamp(maxChannel - minChannel, 0.0, 1.0);
    float amount = vibrance * (1.0 - colorfulness);
    return applySaturation(color, 1.0 + amount);
}

vec3 applyTemperature(vec3 color, float temperature) {
    vec3 warm = vec3(1.0 + temperature, 1.0, 1.0 - temperature);
    return color * warm;
}

void main() {
    vec4 scene = texture(uSceneTexture, vTexCoord);
    vec3 color = scene.rgb;

    if (uEnabled != 0) {
        color *= exp2(uExposure);
        color = applyContrast(color, uContrast);
        color = applySaturation(color, uSaturation);
        color = applyVibrance(color, uVibrance);
        color = applyTemperature(color, uTemperature);
        color = max(color, vec3(0.0));
        color = pow(color, vec3(1.0 / max(uGamma, 0.01)));
    }

    fragColor = vec4(clamp(color, 0.0, 1.0), scene.a);
}
//@endfs
