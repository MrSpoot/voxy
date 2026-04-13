//@vs
#version 460 core

layout(std430, binding = 0) readonly buffer FaceBuffer {
    uint faces[];
};

uniform mat4 uProjection;
uniform mat4 uView;
uniform mat4 uModel;

out vec2 vTexCoord;
flat out int vFace;
flat out int vTextureIndex;

vec2 getQuadUv(int vertexIndex) {
    if (vertexIndex == 0) {
        return vec2(0.0, 0.0);
    } else if (vertexIndex == 1) {
        return vec2(1.0, 0.0);
    } else if (vertexIndex == 2) {
        return vec2(0.0, 1.0);
    }

    return vec2(1.0, 1.0);
}

vec3 getFaceVertexOffset(int face, vec2 uv, float quadWidth, float quadHeight) {
    if (face == 0) {
        return vec3(1.0, uv.y * quadHeight, (1.0 - uv.x) * quadWidth);
    } else if (face == 1) {
        return vec3(0.0, uv.y * quadHeight, uv.x * quadWidth);
    } else if (face == 2) {
        return vec3(uv.x * quadWidth, 1.0, (1.0 - uv.y) * quadHeight);
    } else if (face == 3) {
        return vec3(uv.x * quadWidth, 0.0, uv.y * quadHeight);
    } else if (face == 4) {
        return vec3(uv.x * quadWidth, uv.y * quadHeight, 1.0);
    }

    return vec3((1.0 - uv.x) * quadWidth, uv.y * quadHeight, 0.0);
}

void main() {
    int faceIndex = gl_InstanceID * 2;
    int faceData = int(faces[faceIndex]);
    int textureIndex = int(faces[faceIndex + 1]);

    int x = faceData & 31;
    int y = (faceData >> 5) & 31;
    int z = (faceData >> 10) & 31;
    int face = (faceData >> 15) & 7;
    float quadWidth = float(((faceData >> 18) & 31) + 1);
    float quadHeight = float(((faceData >> 23) & 31) + 1);
    vec2 uv = getQuadUv(gl_VertexID);

    vec3 worldPosition = vec3(x, y, z) + getFaceVertexOffset(face, uv, quadWidth, quadHeight);
    gl_Position = uProjection * uView * uModel * vec4(worldPosition, 1.0);

    vTexCoord = vec2(uv.x * quadWidth, uv.y * quadHeight);
    vFace = face;
    vTextureIndex = textureIndex;
}
//@endvs

//@fs
#version 460 core

uniform sampler2DArray uBlockTextures;

in vec2 vTexCoord;
flat in int vFace;
flat in int vTextureIndex;

out vec4 fragColor;

float getFaceTextureSlot(int face) {
    if (face == 2) return 0.0;
    if (face == 4) return 1.0;
    if (face == 5) return 2.0;
    if (face == 1) return 3.0;
    if (face == 0) return 4.0;
    return 5.0;
}

void main() {
    float faceSlot = getFaceTextureSlot(vFace);
    vec2 correctedUv = vTexCoord;
    if (vFace == 4 || vFace == 5 || vFace == 1 || vFace == 0) {
        correctedUv.y = 1.0 - correctedUv.y;
    }

    correctedUv = fract(correctedUv);

    vec2 atlasCoord = vec2((faceSlot + correctedUv.x) / 6.0, correctedUv.y);
    vec4 texel = texture(uBlockTextures, vec3(atlasCoord, float(vTextureIndex)));
    if (texel.a <= 0.05) {
        discard;
    }
    fragColor = texel;
}
//@endfs
