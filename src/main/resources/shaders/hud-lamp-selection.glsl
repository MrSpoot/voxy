//@vs
#version 460 core

uniform vec3 uViewport;
uniform float uSlotSizePx;
uniform float uSlotGapPx;
uniform float uBottomOffsetPx;

out vec2 vUv;
flat out int vSlotIndex;

const vec2 QUAD_VERTICES[4] = vec2[](
    vec2(0.0, 0.0),
    vec2(1.0, 0.0),
    vec2(0.0, 1.0),
    vec2(1.0, 1.0)
);

void main() {
    vec2 uv = QUAD_VERTICES[gl_VertexID];
    float totalWidth = uSlotSizePx * 4.0 + uSlotGapPx * 3.0;
    float startX = (uViewport.x - totalWidth) * 0.5;
    float slotX = startX + float(gl_InstanceID) * (uSlotSizePx + uSlotGapPx);
    float slotY = uViewport.y - uBottomOffsetPx - uSlotSizePx;

    vec2 pixelPosition = vec2(
        slotX + uv.x * uSlotSizePx,
        slotY + uv.y * uSlotSizePx
    );
    vec2 ndc = vec2(
        (pixelPosition.x / max(uViewport.x, 1.0)) * 2.0 - 1.0,
        1.0 - (pixelPosition.y / max(uViewport.y, 1.0)) * 2.0
    );

    gl_Position = vec4(ndc, 0.0, 1.0);
    vUv = uv;
    vSlotIndex = gl_InstanceID;
}
//@endvs

//@fs
#version 460 core

uniform vec3 uSlotColors[4];
uniform int uSelectedIndex;

in vec2 vUv;
flat in int vSlotIndex;

out vec4 fragColor;

void main() {
    vec3 slotColor = uSlotColors[vSlotIndex];
    float edge = min(min(vUv.x, 1.0 - vUv.x), min(vUv.y, 1.0 - vUv.y));
    float outerBorder = edge < 0.07 ? 1.0 : 0.0;
    float innerBorder = edge < 0.15 ? 1.0 : 0.0;
    bool selected = vSlotIndex == uSelectedIndex;

    vec3 fillColor = mix(slotColor * 0.35, slotColor, selected ? 1.0 : 0.78);
    vec3 borderColor = selected ? vec3(1.0, 0.96, 0.82) : vec3(0.08, 0.08, 0.08);
    vec3 accentColor = selected ? vec3(1.0) : borderColor;

    vec3 color = fillColor;
    if (innerBorder > 0.5) {
        color = mix(fillColor, accentColor, selected ? 0.55 : 0.25);
    }
    if (outerBorder > 0.5) {
        color = borderColor;
    }

    fragColor = vec4(color, 0.92);
}
//@endfs
