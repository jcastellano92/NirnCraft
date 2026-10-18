#version 330 compatibility
// The one tonemapper for near and far (aether hands the pack linear HDR, so both pass through here): the fitted ACES
// curve (Stephen Hill's RRT + ODT fit, sRGB primaries) for a filmic toe and a highlight roll-off that keeps colour.
#include "/lib/settings.glsl"

uniform sampler2D colortex0;

in vec2 texcoord;

layout(location = 0) out vec4 fragColor;

const mat3 ACES_IN = mat3(0.59719, 0.07600, 0.02840,
                          0.35458, 0.90834, 0.13383,
                          0.04823, 0.01566, 0.83777);
const mat3 ACES_OUT = mat3(1.60475, -0.10208, -0.00327,
                           -0.53108, 1.10813, -0.07276,
                           -0.07367, -0.00605, 1.07602);

vec3 aces(vec3 c) {
    c = ACES_IN * c;
    vec3 a = c * (c + 0.0245786) - 0.000090537;
    vec3 b = c * (0.983729 * c + 0.4329510) + 0.238081;
    return clamp(ACES_OUT * (a / b), 0.0, 1.0);
}

vec3 linearToSrgb(vec3 c) {
    return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c));
}

void main() {
    vec3 c = aces(texture(colortex0, texcoord).rgb * EXPOSURE * 0.8);
    fragColor = vec4(linearToSrgb(c), 1.0);
}
