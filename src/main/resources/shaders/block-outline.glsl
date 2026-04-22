//@vs
#version 460 core

layout(location = 0) in vec3 aPosition;

uniform mat4 uProjection;
uniform mat4 uView;
uniform vec3 uBlockOrigin;

void main() {
    vec3 expandedPosition = ((aPosition - vec3(0.5)) * 1.01) + vec3(0.5);
    vec3 worldPosition = uBlockOrigin + expandedPosition;
    gl_Position = uProjection * uView * vec4(worldPosition, 1.0);
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
