//@vs
#version 460 core

uniform vec3 uViewport;
uniform float uHalfLengthPx;
uniform float uGapPx;

const vec2 POINTS[8] = vec2[](
    vec2(-1.0, 0.0), vec2(-2.0, 0.0),
    vec2(1.0, 0.0), vec2(2.0, 0.0),
    vec2(0.0, -1.0), vec2(0.0, -2.0),
    vec2(0.0, 1.0), vec2(0.0, 2.0)
);

void main() {
    vec2 point = POINTS[gl_VertexID];
    vec2 pixelOffset = sign(point) * uGapPx + point * uHalfLengthPx;
    vec2 ndcOffset = vec2(
        pixelOffset.x / max(uViewport.x, 1.0),
        pixelOffset.y / max(uViewport.y, 1.0)
    ) * 2.0;

    gl_Position = vec4(ndcOffset, 0.0, 1.0);
}
//@endvs

//@fs
#version 460 core

uniform vec3 uColor;
uniform float uAlpha;

out vec4 fragColor;

void main() {
    fragColor = vec4(uColor, uAlpha);
}
//@endfs
