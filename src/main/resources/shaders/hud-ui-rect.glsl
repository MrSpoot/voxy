//@vs
#version 460 core

layout(std430, binding = 4) readonly buffer RectangleData {
    vec4 rectangleData[];
};

uniform vec3 uViewport;

flat out vec4 vColor;

const vec2 QUAD_VERTICES[4] = vec2[](
    vec2(0.0, 0.0),
    vec2(1.0, 0.0),
    vec2(0.0, 1.0),
    vec2(1.0, 1.0)
);

void main() {
    vec2 uv = QUAD_VERTICES[gl_VertexID];
    vec4 bounds = rectangleData[gl_InstanceID * 2];
    vec2 pixelPosition = bounds.xy + uv * bounds.zw;
    vec2 viewport = max(uViewport.xy, vec2(1.0));
    vec2 ndc = vec2(
        pixelPosition.x / viewport.x * 2.0 - 1.0,
        1.0 - pixelPosition.y / viewport.y * 2.0
    );
    gl_Position = vec4(ndc, 0.0, 1.0);
    vColor = rectangleData[gl_InstanceID * 2 + 1];
}
//@endvs

//@fs
#version 460 core

flat in vec4 vColor;
out vec4 fragColor;

void main() {
    fragColor = vColor;
}
//@endfs
