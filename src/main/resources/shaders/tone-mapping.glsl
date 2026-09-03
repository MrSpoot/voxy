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

uniform sampler2D uHdrTexture;
uniform sampler2D uAutoExposureTexture;
uniform int uAutoExposureEnabled;
uniform int uColorGradingEnabled;
uniform int uToneMappingEnabled;
uniform float uExposure;
uniform float uContrast;
uniform float uSaturation;
uniform float uVibrance;
uniform float uGamma;
uniform float uTemperature;

in vec2 vTexCoord;

out vec4 fragColor;

vec3 acesToneMap(vec3 color) {
    color *= 0.6;

    const float a = 2.51;
    const float b = 0.03;
    const float c = 2.43;
    const float d = 0.59;
    const float e = 0.14;

    return clamp(
        (color * (a * color + b)) /
        (color * (c * color + d) + e),
        0.0,
        1.0
    );
}

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
    vec4 source = texture(uHdrTexture, vTexCoord);
    vec3 color = max(source.rgb, vec3(0.0));

    float effectiveExposure = uExposure;
    if (uAutoExposureEnabled != 0) {
        effectiveExposure = texelFetch(uAutoExposureTexture, ivec2(0), 0).r;
    }
    color *= exp2(effectiveExposure);

    if (uToneMappingEnabled != 0) {
        color = acesToneMap(color);
    }

    if (uColorGradingEnabled != 0) {
        color = applyContrast(color, uContrast);
        color = applySaturation(color, uSaturation);
        color = applyVibrance(color, uVibrance);
        color = applyTemperature(color, uTemperature);
    }

    color = max(color, vec3(0.0));
    color = pow(color, vec3(1.0 / max(uGamma, 0.01)));

    fragColor = vec4(clamp(color, 0.0, 1.0), source.a);
}
//@endfs
