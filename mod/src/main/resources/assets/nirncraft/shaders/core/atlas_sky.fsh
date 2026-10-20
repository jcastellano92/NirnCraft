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
    // the sun's disc (about half a degree, a soft limb) over the horizon
    float mu = dot(dir, sun);
    float disc = smoothstep(0.99996, 0.99999, mu) * smoothstep(-0.02, 0.02, dir.y);
    c += SunDisc.rgb * disc * 40.0;
    fragColor = vec4(atlasDisplay(c, Look.x, Look.y), 1.0);
}
