//@vs
#version 460 core

layout(location = 0) in vec3 aPosition;
layout(location = 1) in vec3 aNormal;

layout(std430, binding = 6) readonly buffer PlayerInstances {
    vec4 instanceData[];
};

uniform mat4 uProjection;
uniform mat4 uView;

out vec3 vNormal;
flat out vec3 vColor;

void main() {
    vec4 pose = instanceData[gl_InstanceID * 2];
    float angle = radians(-pose.w);
    mat2 rotation = mat2(cos(angle), -sin(angle), sin(angle), cos(angle));
    vec2 rotatedPosition = rotation * aPosition.xz;
    vec2 rotatedNormal = rotation * aNormal.xz;
    vec3 worldPosition = pose.xyz + vec3(rotatedPosition.x, aPosition.y, rotatedPosition.y);
    gl_Position = uProjection * uView * vec4(worldPosition, 1.0);
    vNormal = normalize(vec3(rotatedNormal.x, aNormal.y, rotatedNormal.y));
    vColor = instanceData[gl_InstanceID * 2 + 1].rgb;
}
//@endvs

//@fs
#version 460 core

in vec3 vNormal;
flat in vec3 vColor;
out vec4 fragColor;

void main() {
    vec3 lightDirection = normalize(vec3(0.45, 0.85, 0.30));
    float diffuse = 0.45 + max(dot(normalize(vNormal), lightDirection), 0.0) * 0.55;
    fragColor = vec4(vColor * diffuse, 1.0);
}
//@endfs
