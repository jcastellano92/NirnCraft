#version 330
#extension GL_ARB_separate_shader_objects : require
#include <nirncraft:atlas_look.glsl>
// Infinite ATLAS's sky (com.nirncraft.atlas.AtlasSky), drawn in place of vanilla's while the near pass owns the frame:
// the far field's own sky model (pack_look.hpp skyColor, the shader pack's sky: the far field's haze fades into it, so
// the horizon has no seam) and the sun's disc, through the same display as the terrain. NirnCraft's night sky (moons,
// stars, aurora: atmosphere/client/SkyRenderer) draws over it after.
layout(std140) uniform SkyInfo {
    mat4 InvViewProj;  // clip -> camera-relative world direction
    vec4 Sun;          // xyz towards the sun, w unused
    vec4 Look;         // x exposure, y night vision
};
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

vec3 sunRadiance(vec3 s) {
    float e = s.y, a = clamp(e * 3.0, 0.0, 1.0);
    return mix(vec3(1.0, 0.62, 0.38), vec3(1.0, 0.96, 0.9), a) * (3.2 * smoothstep(-0.04, 0.12, e));
}

vec3 skyColor(vec3 dir, vec3 sun) {
    float mu = dot(dir, sun), up = dir.y;
    float day = smoothstep(-0.12, 0.25, sun.y);
    vec3 zenith = mix(vec3(0.008, 0.012, 0.03), vec3(0.16, 0.34, 0.78), day);
    vec3 horizon = mix(vec3(0.03, 0.035, 0.06), vec3(0.62, 0.75, 0.92), day);
    float dusk = (1.0 - smoothstep(0.0, 0.3, abs(sun.y))) * smoothstep(-0.2, 0.9, mu);
    horizon = mix(horizon, vec3(1.0, 0.5, 0.25), dusk * 0.7);
    float hz = pow(1.0 - clamp(abs(up), 0.0, 1.0), 5.0);
    vec3 c = mix(zenith, horizon, hz);
    if (up < 0.0) c = mix(c, horizon * 0.55, clamp(-up * 6.0, 0.0, 1.0));
    float g = 0.76;
    float mie = (1.0 - g * g) / pow(max(1.0 + g * g - 2.0 * g * mu, 1e-4), 1.5) * 0.012;
    c += sunRadiance(sun) * (mie * 0.5);
    return c * 1.6;
}

void main() {
    vec4 p = InvViewProj * vec4(texCoord * 2.0 - 1.0, 0.5, 1.0);
    vec3 dir = normalize(p.xyz / p.w);
    vec3 sun = normalize(Sun.xyz);
    vec3 c = skyColor(dir, sun);
    // the sun's disc (about half a degree, a soft limb) over the horizon
    float mu = dot(dir, sun);
    float disc = smoothstep(0.99996, 0.99999, mu) * smoothstep(-0.02, 0.02, dir.y);
    c += sunRadiance(sun) * disc * 40.0;
    fragColor = vec4(atlasDisplay(c, Look.x, Look.y), 1.0);
}
