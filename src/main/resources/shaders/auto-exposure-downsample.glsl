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

uniform sampler2D uSourceTexture;
uniform int uFirstPass;
uniform vec2 uOutputSize;

layout(location = 0) out float outLogLuminance;

const vec3 LUMINANCE_WEIGHTS = vec3(0.2126, 0.7152, 0.0722);
const float MINIMUM_LUMINANCE = 0.0001;

float sampleLogLuminance(vec2 uv) {
    vec3 hdrColor = max(texture(uSourceTexture, clamp(uv, vec2(0.0), vec2(1.0))).rgb, vec3(0.0));
    return log(max(dot(hdrColor, LUMINANCE_WEIGHTS), MINIMUM_LUMINANCE));
}

void main() {
    if (uFirstPass != 0) {
        vec2 cellSize = 1.0 / max(uOutputSize, vec2(1.0));
        vec2 center = gl_FragCoord.xy / max(uOutputSize, vec2(1.0));
        vec2 offset = cellSize * 0.25;
        outLogLuminance = 0.25 * (
            sampleLogLuminance(center + vec2(-offset.x, -offset.y)) +
            sampleLogLuminance(center + vec2( offset.x, -offset.y)) +
            sampleLogLuminance(center + vec2(-offset.x,  offset.y)) +
            sampleLogLuminance(center + vec2( offset.x,  offset.y))
        );
        return;
    }

    ivec2 sourceSize = textureSize(uSourceTexture, 0);
    ivec2 maximumCoordinate = sourceSize - ivec2(1);
    ivec2 baseCoordinate = ivec2(gl_FragCoord.xy) * 2;
    float sum =
        texelFetch(uSourceTexture, clamp(baseCoordinate + ivec2(0, 0), ivec2(0), maximumCoordinate), 0).r +
        texelFetch(uSourceTexture, clamp(baseCoordinate + ivec2(1, 0), ivec2(0), maximumCoordinate), 0).r +
        texelFetch(uSourceTexture, clamp(baseCoordinate + ivec2(0, 1), ivec2(0), maximumCoordinate), 0).r +
        texelFetch(uSourceTexture, clamp(baseCoordinate + ivec2(1, 1), ivec2(0), maximumCoordinate), 0).r;
    outLogLuminance = sum * 0.25;
}
//@endfs
