#version 330 compatibility
// The one tonemapper for near and far (atlas hands the pack linear HDR, so both pass through here):
//  * the eye's adapted exposure (lib/exposure.glsl, kept in colortex8 by composite1.fsh) times the EXPOSURE option
//  * night vision: dim light loses its colour to the rods (lib/grade.glsl)
//  * the fitted ACES curve (Stephen Hill's RRT + ODT fit, sRGB primaries) for a filmic toe and a highlight roll-off
//    that keeps colour
//  * the regional film grade (lib/grade.glsl: a baked LUT per land, blended by where the camera is), eased by
//    overcast and night
#include "/lib/settings.glsl"
#include "/lib/common.glsl"
#include "/lib/exposure.glsl"
#include "/lib/weather.glsl"
#include "/lib/grade.glsl"

uniform sampler2D colortex5;  // the HDR scene with shafts and bloom (composite1.fsh)
uniform sampler2D colortex8;  // the eye
uniform vec3 cameraPosition;
uniform vec3 sunPosition;
uniform mat4 gbufferModelViewInverse;
uniform float rainStrength;

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
    vec3 hdr = texture(colortex5, texcoord).rgb;
#if NIGHT_VISION > 0
    hdr = nightVision(hdr, float(NIGHT_VISION) * 0.35);
#endif
    vec3 c = linearToSrgb(aces(hdr * exp2(exposureNow(colortex8)) * EXPOSURE));
#if defined REGION_GRADE && !defined NIRNCRAFT_REALM
    // the grade eases off under a closed sky and at night (flat light; the rods' look takes over)
    vec3 sun = worldSunDir(gbufferModelViewInverse, sunPosition);
    float night = smoothstep(0.02, -0.15, sun.y);
    float overcast = max(rainStrength, haveWeather() ? smoothstep(0.55, 0.95, weatherSample(weatherField, cameraPosition.xz).r) : 0.0);
    c = regionalGrade(c, cameraPosition.xz, GRADE_STRENGTH * (1.0 - 0.5 * night) * (1.0 - 0.35 * overcast));
#endif
    fragColor = vec4(c, 1.0);
}
