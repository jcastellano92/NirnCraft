#version 330
#extension GL_ARB_separate_shader_objects : require
#include <nirncraft:atlas_look.glsl>
#include <nirncraft:atlas_sky.glsl>
// Infinite ATLAS's sky (com.nirncraft.atlas.AtlasSky), drawn in place of vanilla's while the near pass owns the frame:
// the air's sky (the far field's own model: its haze fades into it, so the horizon has no seam), looked up in the
// sky-view LUT this frame's air was marched into, and the sun's disc in its transmitted light, through the same
// display as the terrain. NirnCraft's night sky (moons, stars, aurora: atmosphere/client/SkyRenderer) draws over it.
layout(std140) uniform SkyInfo {
    mat4 InvViewProj;  // clip -> camera-relative world direction
    vec4 Sun;          // xyz towards the sun, w unused
    vec4 Look;         // x exposure, y night vision
    vec4 SunDisc;      // the sun's light at the eye (its key light: the air's transmittance), 0 below the horizon
};
uniform sampler2D SkyView;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec4 p = InvViewProj * vec4(texCoord * 2.0 - 1.0, 0.5, 1.0);
    vec3 dir = normalize(p.xyz / p.w);
    vec3 sun = normalize(Sun.xyz);
    vec3 c = atlasSky(SkyView, dir, sun);
    // the sun's disc. High up its glare (near_resolve's sunGlare) swallows it: a bright blob. Low down the long air
    // path dims it until its edge shows, crisp, darker at the rim (limb darkening), flattened by the air's refraction
    // (the lower limb lifted more than the upper), and looking larger against the horizon (the eye's horizon illusion: a
    // gentle enlargement, it is how a sunset looks)
    float e = sun.y;
    float lowSun = 1.0 - smoothstep(0.0, 0.25, e);
    float r0 = 0.0089 * (1.0 + 0.3 * lowSun);              // angular radius, radians
    float squash = 1.0 - 0.15 * (1.0 - smoothstep(0.0, 0.08, e));
    vec3 upW = vec3(0.0, 1.0, 0.0);
    vec3 v = normalize(upW - sun * dot(upW, sun));          // up on the sky, across the disc
    vec3 h = normalize(cross(sun, v));
    float dx = dot(dir, h), dy = dot(dir, v) / squash;
    float along = dot(dir, sun);
    float d = along > 0.0 ? sqrt(dx * dx + dy * dy) : 1.0;  // angular distance from the centre, the flattened disc's
    float aa = max(fwidth(d), 1e-5);
    float inside = 1.0 - smoothstep(r0 - aa, r0 + aa, d);
    float x = clamp(d / r0, 0.0, 1.0);
    float limb = 1.0 - 0.6 * (1.0 - sqrt(max(1.0 - x * x, 0.0)));
    float disc = inside * limb * smoothstep(-0.02, 0.01, dir.y);
    c += SunDisc.rgb * disc * 40.0;
    fragColor = vec4(atlasDisplay(c, Look.x, Look.y), 1.0);
}
