//@vs
#version 460 core

layout(std430, binding = 0) readonly buffer FaceBuffer {
    uint faces[];
};

layout(std430, binding = 1) readonly buffer ChunkDrawBuffer {
    uint drawData[];
};

layout(std430, binding = 2) readonly buffer ChunkLightBuffer {
    uint lightData[];
};

uniform mat4 uProjection;
uniform mat4 uView;
uniform int uDebugLightVisualizationEnabled;
const int DEBUG_LIGHT_PADDED_DIMENSION = 34;

out vec2 vTexCoord;
flat out int vFace;
flat out int vTextureIndex;
flat out int vLightOffset;
out vec3 vChunkLocalPosition;
out vec3 vWorldPosition;
flat out int vWaterSurfaceEdge;

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

uint getPackedLight(int lightOffset, int x, int y, int z) {
    int voxelIndex = x + (z * DEBUG_LIGHT_PADDED_DIMENSION) + (y * DEBUG_LIGHT_PADDED_DIMENSION * DEBUG_LIGHT_PADDED_DIMENSION);
    uint packedPair = lightData[lightOffset + (voxelIndex >> 1)];
    if ((voxelIndex & 1) == 0) {
        return packedPair & 0xFFFFu;
    }
    return packedPair >> 16u;
}

vec3 getDebugLightColor(uint packedLight) {
    vec3 lightRgb = vec3(
        float(packedLight & 0xFu),
        float((packedLight >> 4u) & 0xFu),
        float((packedLight >> 8u) & 0xFu)
    ) / 15.0;
    return lightRgb;
}

void main() {
    int drawBase = gl_BaseInstance * 5;
    int faceOffset = int(drawData[drawBase]);
    int lightOffset = int(drawData[drawBase + 1]);
    int faceIndex = faceOffset + (gl_InstanceID * 2);
    int faceData = int(faces[faceIndex]);
    int facePayload = int(faces[faceIndex + 1]);
    int textureIndex = facePayload & 0xFFFF;

    int x = faceData & 31;
    int y = (faceData >> 5) & 31;
    int z = (faceData >> 10) & 31;
    int face = (faceData >> 15) & 7;
    float quadWidth = float(((faceData >> 18) & 31) + 1);
    float quadHeight = float(((faceData >> 23) & 31) + 1);
    vec2 uv = getQuadUv(gl_VertexID);

    vec3 chunkOrigin = vec3(
        int(drawData[drawBase + 2]),
        int(drawData[drawBase + 3]),
        int(drawData[drawBase + 4])
    );
    vec3 localPosition = vec3(x, y, z) + getFaceVertexOffset(face, uv, quadWidth, quadHeight);
    vec3 worldPosition = chunkOrigin + localPosition;
    gl_Position = uProjection * uView * vec4(worldPosition, 1.0);

    vTexCoord = vec2(uv.x * quadWidth, uv.y * quadHeight);
    vFace = face;
    vTextureIndex = textureIndex;
    vLightOffset = lightOffset;
    vChunkLocalPosition = localPosition;
    vWorldPosition = worldPosition;
    vWaterSurfaceEdge = (facePayload >> 16) & 1;
}
//@endvs

//@gs
#version 460 core

layout(triangles) in;
layout(triangle_strip, max_vertices = 3) out;

uniform mat4 uProjection;
uniform mat4 uView;
uniform int uWaterTextureIndex;
uniform int uWaterWavesEnabled;
uniform float uWaterSurfaceInset;
uniform float uWaterWaveAmplitude;
uniform float uWaterWaveSpeed;
uniform float uWaterWaveLength;
uniform float uWaterTime;
uniform vec3 uCameraPosition;

in vec2 vTexCoord[];
flat in int vFace[];
flat in int vTextureIndex[];
flat in int vLightOffset[];
in vec3 vChunkLocalPosition[];
in vec3 vWorldPosition[];
flat in int vWaterSurfaceEdge[];

out vec2 gTexCoord;
flat out int gFace;
flat out int gTextureIndex;
flat out int gLightOffset;
out vec3 gChunkLocalPosition;
flat out vec3 gNormal;
out float gCameraDistance;

vec3 getWaterBaseNormal(int face) {
    if (face == 0) return vec3(1.0, 0.0, 0.0);
    if (face == 1) return vec3(-1.0, 0.0, 0.0);
    if (face == 2) return vec3(0.0, 1.0, 0.0);
    if (face == 3) return vec3(0.0, -1.0, 0.0);
    if (face == 4) return vec3(0.0, 0.0, 1.0);
    return vec3(0.0, 0.0, -1.0);
}

float sampleWaterWave(vec2 worldPosition, float surfaceInset) {
    if (uWaterWavesEnabled == 0) {
        return 0.0;
    }

    const float TAU = 6.28318530718;
    float wavelength = clamp(uWaterWaveLength, 2.0, 32.0);
    float amplitude = min(
        clamp(uWaterWaveAmplitude, 0.0, 0.12),
        max(surfaceInset - 0.001, 0.0)
    );
    float phaseA = dot(worldPosition, vec2(0.82, 0.57)) * (TAU / wavelength)
        + uWaterTime * uWaterWaveSpeed;
    float phaseB = dot(worldPosition, vec2(-0.35, 0.94)) * (TAU / (wavelength * 0.63))
        - uWaterTime * uWaterWaveSpeed * 0.73;
    return amplitude * (sin(phaseA) * 0.65 + sin(phaseB) * 0.35);
}

void main() {
    int face = vFace[0];
    int textureIndex = vTextureIndex[0];
    bool isWater = textureIndex == uWaterTextureIndex;
    bool isTopFace = face == 2;
    bool isSideFace = face == 0 || face == 1 || face == 4 || face == 5;
    float surfaceInset = clamp(uWaterSurfaceInset, 0.0, 0.5);
    vec3 deformedWorldPositions[3];

    for (int vertex = 0; vertex < 3; vertex++) {
        vec3 worldPosition = vWorldPosition[vertex];
        bool isSurfaceSideVertex = isSideFace
            && vWaterSurfaceEdge[vertex] != 0
            && vTexCoord[vertex].y > 0.5;
        if (isWater && (isTopFace || isSurfaceSideVertex)) {
            worldPosition.y += -surfaceInset + sampleWaterWave(worldPosition.xz, surfaceInset);
        }
        deformedWorldPositions[vertex] = worldPosition;
    }

    vec3 baseNormal = getWaterBaseNormal(face);
    vec3 triangleNormal = normalize(cross(
        deformedWorldPositions[1] - deformedWorldPositions[0],
        deformedWorldPositions[2] - deformedWorldPositions[0]
    ));
    if (dot(triangleNormal, baseNormal) < 0.0) {
        triangleNormal = -triangleNormal;
    }

    for (int vertex = 0; vertex < 3; vertex++) {
        gTexCoord = vTexCoord[vertex];
        gFace = face;
        gTextureIndex = textureIndex;
        gLightOffset = vLightOffset[vertex];
        gChunkLocalPosition = vChunkLocalPosition[vertex];
        gNormal = triangleNormal;
        gCameraDistance = distance(deformedWorldPositions[vertex], uCameraPosition);
        gl_Position = uProjection * uView * vec4(deformedWorldPositions[vertex], 1.0);
        EmitVertex();
    }
    EndPrimitive();
}
//@endgs

//@fs
#version 460 core

uniform sampler2DArray uBlockTextures;
uniform int uDebugLightVisualizationEnabled;
uniform int uLightingEnabled;
uniform int uVoxelLightDataEnabled;
uniform int uBlockLightEnabled;
uniform float uBlockLightIntensity;
uniform vec3 uAmbientColor;
uniform float uAmbientIntensity;
uniform float uShadowStrength;
uniform vec3 uSunColor;
uniform float uSunIntensity;
uniform vec3 uSunDirection;
uniform vec3 uSkyColor;
uniform float uSkyIntensity;
uniform float uVoxelLightGamma;
uniform float uVoxelDarknessFloor;
uniform int uDistanceSofteningEnabled;
uniform float uDistanceSofteningStart;
uniform float uDistanceSofteningEnd;
uniform float uDistantDirectionalStrength;
const int DEBUG_LIGHT_PADDED_DIMENSION = 34;
const int PACKED_LIGHT_COMPONENT_INTS =
    (DEBUG_LIGHT_PADDED_DIMENSION * DEBUG_LIGHT_PADDED_DIMENSION * DEBUG_LIGHT_PADDED_DIMENSION + 1) / 2;

layout(std430, binding = 2) readonly buffer ChunkLightBuffer {
    uint lightData[];
};

in vec2 gTexCoord;
flat in int gFace;
flat in int gTextureIndex;
flat in int gLightOffset;
in vec3 gChunkLocalPosition;
flat in vec3 gNormal;
in float gCameraDistance;

out vec4 fragColor;

float getFaceTextureSlot(int face) {
    if (face == 2) return 0.0;
    if (face == 4) return 1.0;
    if (face == 5) return 2.0;
    if (face == 1) return 3.0;
    if (face == 0) return 4.0;
    return 5.0;
}

vec3 getFaceNormal(int face) {
    if (face == 0) return vec3(1.0, 0.0, 0.0);
    if (face == 1) return vec3(-1.0, 0.0, 0.0);
    if (face == 2) return vec3(0.0, 1.0, 0.0);
    if (face == 3) return vec3(0.0, -1.0, 0.0);
    if (face == 4) return vec3(0.0, 0.0, 1.0);
    return vec3(0.0, 0.0, -1.0);
}

uint getPackedLight(int lightOffset, int x, int y, int z);
float getDirectSkyLevel(int lightOffset, ivec3 sampleCoords);
vec3 getDebugLightColor(uint packedLight);
vec3 getDebugAbsoluteLightColor(uint packedLight);
ivec3 resolveDebugSampleCoords(int face, vec3 localPosition);
vec4 sampleLightComponents(ivec3 sampleCoords);
vec4 sampleSmoothedVoxelLight(int face, vec3 localPosition, ivec3 faceSampleCoords);
float sampleSmoothedDirectSkyLight(int face, vec3 localPosition, ivec3 faceSampleCoords);

float getVoxelLightResponse(float normalizedLevel) {
    float curvedLevel = pow(clamp(normalizedLevel, 0.0, 1.0), max(uVoxelLightGamma, 0.01));
    float easedLevel = curvedLevel * curvedLevel * (3.0 - 2.0 * curvedLevel);
    return mix(clamp(uVoxelDarknessFloor, 0.0, 0.25), 1.0, easedLevel);
}

float getDistanceSofteningFactor() {
    if (uDistanceSofteningEnabled == 0) {
        return 0.0;
    }
    return smoothstep(
        uDistanceSofteningStart,
        max(uDistanceSofteningEnd, uDistanceSofteningStart + 1.0),
        gCameraDistance
    );
}

float getSoftenedSunFactor(vec3 normal, vec3 sunDirection, float distanceSoftening) {
    float directionalSun = max(dot(normal, sunDirection), 0.0);
    float averageSideSun = (abs(sunDirection.x) + abs(sunDirection.z)) * 0.25;
    float neutralSun = max(normal.y * sunDirection.y, 0.0)
        + (1.0 - abs(normal.y)) * averageSideSun;
    float distantSun = mix(neutralSun, directionalSun, clamp(uDistantDirectionalStrength, 0.0, 1.0));
    return mix(directionalSun, distantSun, distanceSoftening);
}

vec3 applyHdrLighting(vec3 albedo, int face) {
    ivec3 faceSampleCoords = resolveDebugSampleCoords(face, gChunkLocalPosition);
    vec4 voxelLight = sampleSmoothedVoxelLight(face, gChunkLocalPosition, faceSampleCoords);
    vec3 blockLight = voxelLight.rgb * uBlockLightIntensity;
    vec3 localLighting = uBlockLightEnabled != 0 ? blockLight : vec3(0.0);
    float skyLevel = uVoxelLightDataEnabled != 0 ? voxelLight.a : 1.0;
    float directSkyLevel = uVoxelLightDataEnabled != 0
        ? sampleSmoothedDirectSkyLight(face, gChunkLocalPosition, faceSampleCoords)
        : 1.0;
    float diffuseSkyVisibility = getVoxelLightResponse(skyLevel);
    float directSunVisibility = pow(clamp(directSkyLevel, 0.0, 1.0), 2.0);

    if (uLightingEnabled == 0) {
        vec3 environmentLighting = vec3(1.0);
        return albedo * (environmentLighting + localLighting);
    }

    vec3 normal = normalize(gNormal);
    vec3 sunDirection = normalize(uSunDirection + vec3(0.0, 0.00001, 0.0));
    float sunFactor = getSoftenedSunFactor(normal, sunDirection, getDistanceSofteningFactor());
    float skyFactor = clamp(normal.y * 0.5 + 0.5, 0.0, 1.0);
    float indirectSkyFactor = mix(0.35, 1.0, skyFactor);

    float blockLightStrength = max(max(localLighting.r, localLighting.g), localLighting.b);
    float localLightLevel = clamp(max(skyLevel, blockLightStrength), 0.0, 1.0);
    float ambientRecovery = getVoxelLightResponse(localLightLevel);
    float ambientVisibility = 1.0 - clamp(uShadowStrength, 0.0, 1.0) * (1.0 - ambientRecovery);
    vec3 ambient = uAmbientColor * uAmbientIntensity * ambientVisibility;
    vec3 sky = uSkyColor * uSkyIntensity * indirectSkyFactor * diffuseSkyVisibility;
    vec3 sun = uSunColor * uSunIntensity * sunFactor * directSunVisibility;
    vec3 environmentLighting = ambient + sky + sun;
    vec3 lighting = environmentLighting + localLighting;

    return albedo * lighting;
}

uint getPackedLight(int lightOffset, int x, int y, int z) {
    int voxelIndex = x + (z * DEBUG_LIGHT_PADDED_DIMENSION) + (y * DEBUG_LIGHT_PADDED_DIMENSION * DEBUG_LIGHT_PADDED_DIMENSION);
    uint packedPair = lightData[lightOffset + (voxelIndex >> 1)];
    if ((voxelIndex & 1) == 0) {
        return packedPair & 0xFFFFu;
    }
    return packedPair >> 16u;
}

vec3 getDebugLightColor(uint packedLight) {
    return vec3(
        float(packedLight & 0xFu),
        float((packedLight >> 4u) & 0xFu),
        float((packedLight >> 8u) & 0xFu)
    ) / 15.0;
}

vec3 getDebugAbsoluteLightColor(uint packedLight) {
    float red = float(packedLight & 0xFu);
    float green = float((packedLight >> 4u) & 0xFu);
    float blue = float((packedLight >> 8u) & 0xFu);
    float sky = float((packedLight >> 12u) & 0xFu);
    float level = max(max(red, green), max(blue, sky)) / 15.0;
    return vec3(level);
}

vec4 sampleLightComponents(ivec3 sampleCoords) {
    uint packedLight = getPackedLight(gLightOffset, sampleCoords.x, sampleCoords.y, sampleCoords.z);
    return vec4(
        getDebugLightColor(packedLight),
        float((packedLight >> 12u) & 0xFu) / 15.0
    );
}

float getDirectSkyLevel(int lightOffset, ivec3 sampleCoords) {
    int voxelIndex = sampleCoords.x
        + sampleCoords.z * DEBUG_LIGHT_PADDED_DIMENSION
        + sampleCoords.y * DEBUG_LIGHT_PADDED_DIMENSION * DEBUG_LIGHT_PADDED_DIMENSION;
    uint packedLevels = lightData[lightOffset + PACKED_LIGHT_COMPONENT_INTS + (voxelIndex >> 3)];
    uint shift = uint(voxelIndex & 7) * 4u;
    return float((packedLevels >> shift) & 0xFu) / 15.0;
}

ivec3 resolveDebugSampleCoords(int face, vec3 localPosition) {
    float epsilon = 0.001;
    int sampleX = clamp(int(floor(localPosition.x)), -1, 32);
    int sampleY = clamp(int(floor(localPosition.y)), -1, 32);
    int sampleZ = clamp(int(floor(localPosition.z)), -1, 32);

    if (face == 0) sampleX = clamp(int(floor(localPosition.x + epsilon)), -1, 32);
    else if (face == 1) sampleX = clamp(int(floor(localPosition.x - epsilon)), -1, 32);
    else if (face == 2) sampleY = clamp(int(floor(localPosition.y + epsilon)), -1, 32);
    else if (face == 3) sampleY = clamp(int(floor(localPosition.y - epsilon)), -1, 32);
    else if (face == 4) sampleZ = clamp(int(floor(localPosition.z + epsilon)), -1, 32);
    else sampleZ = clamp(int(floor(localPosition.z - epsilon)), -1, 32);

    return ivec3(sampleX + 1, sampleY + 1, sampleZ + 1);
}

vec4 sampleSmoothedVoxelLight(int face, vec3 localPosition, ivec3 faceSampleCoords) {
    vec3 samplePosition = clamp(
        localPosition + vec3(0.5),
        vec3(0.0),
        vec3(float(DEBUG_LIGHT_PADDED_DIMENSION - 1) - 0.001)
    );
    ivec3 base = ivec3(floor(samplePosition));
    vec3 fraction = fract(samplePosition);

    if (face == 0 || face == 1) {
        vec4 c00 = sampleLightComponents(ivec3(faceSampleCoords.x, base.y, base.z));
        vec4 c10 = sampleLightComponents(ivec3(faceSampleCoords.x, base.y + 1, base.z));
        vec4 c01 = sampleLightComponents(ivec3(faceSampleCoords.x, base.y, base.z + 1));
        vec4 c11 = sampleLightComponents(ivec3(faceSampleCoords.x, base.y + 1, base.z + 1));
        return mix(mix(c00, c10, fraction.y), mix(c01, c11, fraction.y), fraction.z);
    }
    if (face == 2 || face == 3) {
        vec4 c00 = sampleLightComponents(ivec3(base.x, faceSampleCoords.y, base.z));
        vec4 c10 = sampleLightComponents(ivec3(base.x + 1, faceSampleCoords.y, base.z));
        vec4 c01 = sampleLightComponents(ivec3(base.x, faceSampleCoords.y, base.z + 1));
        vec4 c11 = sampleLightComponents(ivec3(base.x + 1, faceSampleCoords.y, base.z + 1));
        return mix(mix(c00, c10, fraction.x), mix(c01, c11, fraction.x), fraction.z);
    }

    vec4 c00 = sampleLightComponents(ivec3(base.x, base.y, faceSampleCoords.z));
    vec4 c10 = sampleLightComponents(ivec3(base.x + 1, base.y, faceSampleCoords.z));
    vec4 c01 = sampleLightComponents(ivec3(base.x, base.y + 1, faceSampleCoords.z));
    vec4 c11 = sampleLightComponents(ivec3(base.x + 1, base.y + 1, faceSampleCoords.z));
    return mix(mix(c00, c10, fraction.x), mix(c01, c11, fraction.x), fraction.y);
}

float sampleSmoothedDirectSkyLight(int face, vec3 localPosition, ivec3 faceSampleCoords) {
    vec3 samplePosition = clamp(
        localPosition + vec3(0.5),
        vec3(0.0),
        vec3(float(DEBUG_LIGHT_PADDED_DIMENSION - 1) - 0.001)
    );
    ivec3 base = ivec3(floor(samplePosition));
    vec3 fraction = fract(samplePosition);

    if (face == 0 || face == 1) {
        float c00 = getDirectSkyLevel(gLightOffset, ivec3(faceSampleCoords.x, base.y, base.z));
        float c10 = getDirectSkyLevel(gLightOffset, ivec3(faceSampleCoords.x, base.y + 1, base.z));
        float c01 = getDirectSkyLevel(gLightOffset, ivec3(faceSampleCoords.x, base.y, base.z + 1));
        float c11 = getDirectSkyLevel(gLightOffset, ivec3(faceSampleCoords.x, base.y + 1, base.z + 1));
        return mix(mix(c00, c10, fraction.y), mix(c01, c11, fraction.y), fraction.z);
    }
    if (face == 2 || face == 3) {
        float c00 = getDirectSkyLevel(gLightOffset, ivec3(base.x, faceSampleCoords.y, base.z));
        float c10 = getDirectSkyLevel(gLightOffset, ivec3(base.x + 1, faceSampleCoords.y, base.z));
        float c01 = getDirectSkyLevel(gLightOffset, ivec3(base.x, faceSampleCoords.y, base.z + 1));
        float c11 = getDirectSkyLevel(gLightOffset, ivec3(base.x + 1, faceSampleCoords.y, base.z + 1));
        return mix(mix(c00, c10, fraction.x), mix(c01, c11, fraction.x), fraction.z);
    }

    float c00 = getDirectSkyLevel(gLightOffset, ivec3(base.x, base.y, faceSampleCoords.z));
    float c10 = getDirectSkyLevel(gLightOffset, ivec3(base.x + 1, base.y, faceSampleCoords.z));
    float c01 = getDirectSkyLevel(gLightOffset, ivec3(base.x, base.y + 1, faceSampleCoords.z));
    float c11 = getDirectSkyLevel(gLightOffset, ivec3(base.x + 1, base.y + 1, faceSampleCoords.z));
    return mix(mix(c00, c10, fraction.x), mix(c01, c11, fraction.x), fraction.y);
}

void main() {
    if (uDebugLightVisualizationEnabled != 0) {
        ivec3 sampleCoords = resolveDebugSampleCoords(gFace, gChunkLocalPosition);
        fragColor = vec4(getDebugAbsoluteLightColor(getPackedLight(gLightOffset, sampleCoords.x, sampleCoords.y, sampleCoords.z)), 1.0);
        return;
    }

    float faceSlot = getFaceTextureSlot(gFace);
    vec2 correctedUv = gTexCoord;
    if (gFace == 4 || gFace == 5 || gFace == 1 || gFace == 0) {
        correctedUv.y = 1.0 - correctedUv.y;
    }

    correctedUv = fract(correctedUv);

    vec2 atlasCoord = vec2((faceSlot + correctedUv.x) / 6.0, correctedUv.y);
    vec4 texel = texture(uBlockTextures, vec3(atlasCoord, float(gTextureIndex)));
    fragColor = vec4(applyHdrLighting(texel.rgb, gFace), texel.a);
}
//@endfs
