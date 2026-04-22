//@vs
#version 460 core

layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;

out vec2 vTexCoord;

void main() {
    vTexCoord = aTexCoord;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
//@endvs

//@fs
#version 460 core

uniform sampler2D uSourceTexture;
uniform vec2 uTexelSize;

in vec2 vTexCoord;

out vec4 fragColor;

float luminance(vec3 color) {
    return dot(color, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec3 rgbNW = texture(uSourceTexture, vTexCoord + vec2(-1.0, -1.0) * uTexelSize).rgb;
    vec3 rgbNE = texture(uSourceTexture, vTexCoord + vec2(1.0, -1.0) * uTexelSize).rgb;
    vec3 rgbSW = texture(uSourceTexture, vTexCoord + vec2(-1.0, 1.0) * uTexelSize).rgb;
    vec3 rgbSE = texture(uSourceTexture, vTexCoord + vec2(1.0, 1.0) * uTexelSize).rgb;
    vec4 center = texture(uSourceTexture, vTexCoord);

    float lumaNW = luminance(rgbNW);
    float lumaNE = luminance(rgbNE);
    float lumaSW = luminance(rgbSW);
    float lumaSE = luminance(rgbSE);
    float lumaM = luminance(center.rgb);

    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));
    float contrast = lumaMax - lumaMin;

    if (contrast < max(0.0312, lumaMax * 0.125)) {
        fragColor = center;
        return;
    }

    vec2 direction;
    direction.x = -((lumaNW + lumaNE) - (lumaSW + lumaSE));
    direction.y = ((lumaNW + lumaSW) - (lumaNE + lumaSE));

    float reduce = max((lumaNW + lumaNE + lumaSW + lumaSE) * 0.03125, 0.0078125);
    float inverseDirection = 1.0 / (min(abs(direction.x), abs(direction.y)) + reduce);
    direction = clamp(direction * inverseDirection, vec2(-8.0), vec2(8.0)) * uTexelSize;

    vec3 rgbA = 0.5 * (
            texture(uSourceTexture, vTexCoord + direction * (1.0 / 3.0 - 0.5)).rgb +
            texture(uSourceTexture, vTexCoord + direction * (2.0 / 3.0 - 0.5)).rgb
    );
    vec3 rgbB = rgbA * 0.5 + 0.25 * (
            texture(uSourceTexture, vTexCoord + direction * -0.5).rgb +
            texture(uSourceTexture, vTexCoord + direction * 0.5).rgb
    );

    float lumaB = luminance(rgbB);
    vec3 filtered = (lumaB < lumaMin || lumaB > lumaMax) ? rgbA : rgbB;
    fragColor = vec4(filtered, center.a);
}
//@endfs
