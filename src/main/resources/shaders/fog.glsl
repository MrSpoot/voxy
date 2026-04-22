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

uniform sampler2D uColorTexture;
uniform sampler2D uDepthTexture;
uniform int uEnabled;
uniform mat4 uInverseProjection;
uniform vec3 uFogColor;
uniform float uFogStart;
uniform float uFogEnd;
uniform float uFogDensity;
uniform float uFogIntensity;

in vec2 vTexCoord;

out vec4 fragColor;

float viewDistanceFromDepth(float depth, vec2 uv) {
    vec4 clipPosition = vec4(uv * 2.0 - 1.0, depth, 1.0);
    vec4 viewPosition = uInverseProjection * clipPosition;
    viewPosition /= max(abs(viewPosition.w), 0.00001);
    return length(viewPosition.xyz);
}

void main() {
    vec4 color = texture(uColorTexture, vTexCoord);

    if (uEnabled == 0) {
        fragColor = color;
        return;
    }

    float depth = texture(uDepthTexture, vTexCoord).r;
    float viewDistance = viewDistanceFromDepth(depth, vTexCoord);
    float range = max(uFogEnd - uFogStart, 1.0);
    float distanceFactor = clamp((viewDistance - uFogStart) / range, 0.0, 1.0);
    float shapedFactor = 1.0 - exp(-distanceFactor * max(uFogDensity, 0.0));
    float fogFactor = clamp(shapedFactor * uFogIntensity, 0.0, 1.0);

    fragColor = vec4(mix(color.rgb, uFogColor, fogFactor), color.a);
}
//@endfs
