//@vs
#version 460 core

layout(std430, binding = 5) readonly buffer IconData {
    vec4 iconData[];
};

uniform vec3 uViewport;

out vec2 vUv;
flat out float vTextureLayer;
flat out float vFaceSlot;
flat out float vShade;

const vec2 POSITIONS[18] = vec2[](
    // Top face
    vec2(0.50, 0.04), vec2(0.94, 0.27), vec2(0.50, 0.49),
    vec2(0.50, 0.04), vec2(0.50, 0.49), vec2(0.06, 0.27),
    // Left face
    vec2(0.06, 0.27), vec2(0.50, 0.49), vec2(0.50, 0.94),
    vec2(0.06, 0.27), vec2(0.50, 0.94), vec2(0.06, 0.71),
    // Right face
    vec2(0.50, 0.49), vec2(0.94, 0.27), vec2(0.94, 0.71),
    vec2(0.50, 0.49), vec2(0.94, 0.71), vec2(0.50, 0.94)
);

const vec2 UVS[18] = vec2[](
    vec2(0.50, 0.00), vec2(1.00, 0.50), vec2(0.50, 1.00),
    vec2(0.50, 0.00), vec2(0.50, 1.00), vec2(0.00, 0.50),
    vec2(0.00, 0.00), vec2(1.00, 0.00), vec2(1.00, 1.00),
    vec2(0.00, 0.00), vec2(1.00, 1.00), vec2(0.00, 1.00),
    vec2(0.00, 0.00), vec2(1.00, 0.00), vec2(1.00, 1.00),
    vec2(0.00, 0.00), vec2(1.00, 1.00), vec2(0.00, 1.00)
);

void main() {
    vec4 icon = iconData[gl_InstanceID];
    vec2 pixelPosition = icon.xy + POSITIONS[gl_VertexID] * icon.z;
    vec2 viewport = max(uViewport.xy, vec2(1.0));
    vec2 ndc = vec2(
        pixelPosition.x / viewport.x * 2.0 - 1.0,
        1.0 - pixelPosition.y / viewport.y * 2.0
    );
    int face = gl_VertexID / 6;
    gl_Position = vec4(ndc, 0.0, 1.0);
    vUv = UVS[gl_VertexID];
    vTextureLayer = icon.w;
    vFaceSlot = face == 0 ? 0.0 : (face == 1 ? 4.0 : 1.0);
    vShade = face == 0 ? 1.0 : (face == 1 ? 0.72 : 0.86);
}
//@endvs

//@fs
#version 460 core

uniform sampler2DArray uBlockTextures;

in vec2 vUv;
flat in float vTextureLayer;
flat in float vFaceSlot;
flat in float vShade;

out vec4 fragColor;

void main() {
    vec2 atlasUv = vec2((vFaceSlot + clamp(vUv.x, 0.001, 0.999)) / 6.0, clamp(vUv.y, 0.001, 0.999));
    vec4 texel = texture(uBlockTextures, vec3(atlasUv, vTextureLayer));
    if (texel.a <= 0.05) {
        discard;
    }
    fragColor = vec4(texel.rgb * vShade, texel.a);
}
//@endfs
