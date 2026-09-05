//@vs
#version 460 core

uniform vec3 uCameraPosition;
uniform float uCloudBaseAltitude;
uniform float uCloudCellSize;
uniform float uCloudDensity;
uniform float uCloudSize;
uniform float uWindOffset;
uniform int uGridSide;

const int PATTERN_PERIOD_CELLS = 4096;

flat out vec3 vCloudOrigin;
flat out float vCloudCellSize;
flat out float vVariation;
flat out int vVisible;

int wrapCoordinate(int value, int period) {
    int wrapped = value % period;
    return wrapped < 0 ? wrapped + period : wrapped;
}

float hashCell(ivec2 cell, int period) {
    uvec2 wrapped = uvec2(
        wrapCoordinate(cell.x, period),
        wrapCoordinate(cell.y, period)
    );
    uint state = wrapped.x * 0x9E3779B9u + wrapped.y * 0x85EBCA6Bu + 0xC2B2AE35u;
    state ^= state >> 16u;
    state *= 0x7FEB352Du;
    state ^= state >> 15u;
    state *= 0x846CA68Bu;
    state ^= state >> 16u;
    return float(state & 0x00FFFFFFu) / float(0x01000000u);
}

float valueNoise(vec2 position, int period) {
    ivec2 base = ivec2(floor(position));
    vec2 fraction = fract(position);
    vec2 smoothFraction = fraction * fraction * (3.0 - 2.0 * fraction);

    float c00 = hashCell(base, period);
    float c10 = hashCell(base + ivec2(1, 0), period);
    float c01 = hashCell(base + ivec2(0, 1), period);
    float c11 = hashCell(base + ivec2(1, 1), period);
    return mix(
        mix(c00, c10, smoothFraction.x),
        mix(c01, c11, smoothFraction.x),
        smoothFraction.y
    );
}

float cloudShape(ivec2 cloudCell) {
    vec2 position = vec2(cloudCell);
    float cloudSize = clamp(uCloudSize, 0.25, 4.0);
    float broadShape = valueNoise(position / (16.0 * cloudSize), PATTERN_PERIOD_CELLS / 16);
    float localShape = valueNoise(position / (8.0 * cloudSize), PATTERN_PERIOD_CELLS / 8);
    return broadShape * 0.72 + localShape * 0.28;
}

void main() {
    int gridSide = max(uGridSide, 2);
    int gridX = gl_InstanceID % gridSide;
    int gridZ = gl_InstanceID / gridSide;
    ivec2 gridOffset = ivec2(gridX, gridZ) - ivec2(gridSide / 2);

    float cellSize = max(uCloudCellSize, 1.0);
    vec2 wind = vec2(uWindOffset, 0.0);
    ivec2 centerCloudCell = ivec2(floor((uCameraPosition.xz - wind) / cellSize));
    ivec2 cloudCell = centerCloudCell + gridOffset;

    float edgeDistance = max(abs(float(gridOffset.x)), abs(float(gridOffset.y))) / float(gridSide / 2);
    float edgePenalty = smoothstep(0.82, 1.0, edgeDistance) * 0.32;
    float threshold = mix(0.82, 0.38, clamp(uCloudDensity, 0.0, 1.0)) + edgePenalty;

    vCloudOrigin = vec3(
        float(cloudCell.x) * cellSize + uWindOffset,
        uCloudBaseAltitude,
        float(cloudCell.y) * cellSize
    );
    vCloudCellSize = cellSize;
    vVariation = mix(0.96, 1.04, hashCell(cloudCell, PATTERN_PERIOD_CELLS));
    vVisible = cloudShape(cloudCell) > threshold ? 1 : 0;
    gl_Position = vec4(vCloudOrigin, 1.0);
}
//@endvs

//@gs
#version 460 core

layout(points) in;
layout(triangle_strip, max_vertices = 24) out;

uniform mat4 uProjection;
uniform mat4 uView;

flat in vec3 vCloudOrigin[];
flat in float vCloudCellSize[];
flat in float vVariation[];
flat in int vVisible[];

flat out int gFace;
flat out float gVariation;

vec3 getFaceVertexOffset(int face, vec2 uv, vec3 size) {
    if (face == 0) return vec3(size.x, uv.y * size.y, (1.0 - uv.x) * size.z);
    if (face == 1) return vec3(0.0, uv.y * size.y, uv.x * size.z);
    if (face == 2) return vec3(uv.x * size.x, size.y, (1.0 - uv.y) * size.z);
    if (face == 3) return vec3(uv.x * size.x, 0.0, uv.y * size.z);
    if (face == 4) return vec3(uv.x * size.x, uv.y * size.y, size.z);
    return vec3((1.0 - uv.x) * size.x, uv.y * size.y, 0.0);
}

void emitFace(int face, vec3 origin, vec3 size) {
    const vec2 QUAD_UVS[4] = vec2[](
        vec2(0.0, 0.0),
        vec2(1.0, 0.0),
        vec2(0.0, 1.0),
        vec2(1.0, 1.0)
    );

    gFace = face;
    gVariation = vVariation[0];
    for (int vertex = 0; vertex < 4; vertex++) {
        vec3 worldPosition = origin + getFaceVertexOffset(face, QUAD_UVS[vertex], size);
        gl_Position = uProjection * uView * vec4(worldPosition, 1.0);
        EmitVertex();
    }
    EndPrimitive();
}

void main() {
    if (vVisible[0] == 0) {
        return;
    }

    float cellSize = vCloudCellSize[0];
    vec3 size = vec3(cellSize, max(1.0, cellSize * 0.25), cellSize);
    for (int face = 0; face < 6; face++) {
        emitFace(face, vCloudOrigin[0], size);
    }
}
//@endgs

//@fs
#version 460 core

flat in int gFace;
flat in float gVariation;

layout(location = 0) out vec4 fragColor;

void main() {
    vec3 cloudColor;
    if (gFace == 2) {
        cloudColor = vec3(1.08, 1.10, 1.15);
    } else if (gFace == 3) {
        cloudColor = vec3(0.64, 0.68, 0.76);
    } else {
        cloudColor = vec3(0.82, 0.86, 0.94);
    }
    fragColor = vec4(cloudColor * gVariation, 1.0);
}
//@endfs
