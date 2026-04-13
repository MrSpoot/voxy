//@vs
#version 460 core

layout(std430, binding = 0) readonly buffer FaceBuffer {
    uint faces[];
};

uniform mat4 uProjection;
uniform mat4 uView;
uniform mat4 uModel;
uniform sampler2DArray uBlockTextures;

out vec2 vTexCoord;
flat out int vFace;
flat out int vTextureIndex;

vec3 getFaceVertexOffset(int face, int vertexIndex) {
    vec2 uv;

    if (vertexIndex == 0) {
        uv = vec2(0.0, 0.0);
    } else if (vertexIndex == 1) {
        uv = vec2(1.0, 0.0);
    } else if (vertexIndex == 2) {
        uv = vec2(0.0, 1.0);
    } else {
        uv = vec2(1.0, 1.0);
    }

    if (face == 0) {
        return vec3(1.0, uv.y, uv.x);          // +X
    } else if (face == 1) {
        return vec3(0.0, uv.y, 1.0 - uv.x);    // -X
    } else if (face == 2) {
        return vec3(uv.x, 1.0, uv.y);          // +Y
    } else if (face == 3) {
        return vec3(uv.x, 0.0, 1.0 - uv.y);    // -Y
    } else if (face == 4) {
        return vec3(uv.x, uv.y, 1.0);          // +Z
    }

    return vec3(1.0 - uv.x, uv.y, 0.0);        // -Z
}

float getFaceTextureSlot(int face) {
    if (face == 2) return 0.0; // top
    if (face == 4) return 1.0; // front (+Z)
    if (face == 5) return 2.0; // back (-Z)
    if (face == 1) return 3.0; // west (-X)
    if (face == 0) return 4.0; // east (+X)
    return 5.0;                // bottom
}

void main() {
    int faceData = int(faces[gl_InstanceID]);

    int x = faceData & 31;
    int y = (faceData >> 5) & 31;
    int z = (faceData >> 10) & 31;
    int face = (faceData >> 15) & 7;
    int textureIndex = (faceData >> 18) & 16383;

    vec3 worldPosition = vec3(x, y, z) + getFaceVertexOffset(face, gl_VertexID);
    gl_Position = uProjection * uView * uModel * vec4(worldPosition, 1.0);

    if (gl_VertexID == 0) {
        vTexCoord = vec2(0.0, 0.0);
    } else if (gl_VertexID == 1) {
        vTexCoord = vec2(1.0, 0.0);
    } else if (gl_VertexID == 2) {
        vTexCoord = vec2(0.0, 1.0);
    } else {
        vTexCoord = vec2(1.0, 1.0);
    }

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
    if (face == 2) return 0.0; // top
    if (face == 4) return 1.0; // front (+Z)
    if (face == 5) return 2.0; // back (-Z)
    if (face == 1) return 3.0; // west (-X)
    if (face == 0) return 4.0; // east (+X)
    return 5.0;                // bottom
}

void main() {
    float faceSlot = getFaceTextureSlot(vFace);
    vec2 correctedUv = vTexCoord;
    if (vFace == 4 || vFace == 5 || vFace == 1 || vFace == 0) {
        correctedUv.y = 1.0 - correctedUv.y;
    }

    vec2 atlasCoord = vec2((faceSlot + correctedUv.x) / 6.0, correctedUv.y);
    vec4 texel = texture(uBlockTextures, vec3(atlasCoord, float(vTextureIndex)));
    if (texel.a <= 0.05) {
        discard;
    }
    fragColor = texel;
}
//@endfs
