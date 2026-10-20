#version 330
#extension GL_ARB_separate_shader_objects : require
// Infinite ATLAS's light meter (com.nirncraft.atlas.AtlasMeter): the finished frame (sky, sun, terrain, water, entities)
// down to a small grid of log luminances the eye's adaptation reads back. Each output texel averages 4 x 4 taps over its
// tile of the frame; r: the mean log2 luminance (linear, as displayed), g: the brightest tap's, b: the tile's weight
// (centre-weighted: the eye meters what it looks at).

uniform sampler2D Frame;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

vec3 toLinear(vec3 c) {
    return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
}

void main() {
    vec2 tile = 1.0 / vec2(32.0, 18.0);
    vec2 base = (floor(texCoord / tile)) * tile;
    float sum = 0.0, mx = -20.0;
    for (int j = 0; j < 4; j++)
        for (int i = 0; i < 4; i++) {
            vec2 uv = base + (vec2(float(i), float(j)) + 0.5) / 4.0 * tile;
            float l = dot(toLinear(texture(Frame, uv).rgb), vec3(0.2126, 0.7152, 0.0722));
            float lg = log2(max(l, 1e-4));
            sum += lg;
            mx = max(mx, lg);
        }
    vec2 c = (base + 0.5 * tile) * 2.0 - 1.0;
    float w = exp(-dot(c * vec2(1.0, 1.4), c * vec2(1.0, 1.4)) * 1.6);
    fragColor = vec4(sum / 16.0, mx, w, 1.0);
}
