#version 330
#extension GL_ARB_separate_shader_objects : require
#include <nirncraft:atlas_look.glsl>
#include <nirncraft:atlas_air.glsl>
#include <nirncraft:atlas_sky.glsl>
#include <nirncraft:atlas_night.glsl>
// Infinite ATLAS's sky (com.nirncraft.atlas.AtlasSky), drawn in place of vanilla's while the near pass owns the frame:
// one sky, every layer in it: the air's sky (the far field's own model: its haze fades into it, so the horizon has no
// seam), looked up in the sky-view LUT this frame's air was marched into, the sky's own lights through that air
// (atlas_night.glsl: the aurora's volume over the auroral oval, the stars, the galaxy and the birthsigns, Masser and
// Secunda with their phases and eclipses, meteors, the airglow) and the sun's disc in its transmitted light, through the same
// display as the terrain. (NirnCraft's mesh sky, atmosphere/client/SkyRenderer, is the fallback where this one does
// not draw: a shader pack, the near pass off.)
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
    vec3 c = atlasSky(SkyView, dir, sun, Look.x);
    // the sky's lights at the pixel's own size (a star's light kept in its footprint), the aurora's march dithered
    float pix = length(fwidth(dir)) * 0.7071;
    c += nightSky(dir, pix, nightDither(gl_FragCoord.xy), NIGHT_ALL, 24);
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
    // (an eclipse: the moons' discs over it, their limbs' relief breaking the last light into beads: atlas_moons.glsl)
    if (disc > 0.0) c += SunDisc.rgb * disc * 40.0 * (1.0 - moonCoverSun(dir, pix));
    vec3 shown = atlasDisplay(c, Look.x, Look.y);
    // a little more vibrance in the low sun's sky (the display's shoulder greys the twilight bands): saturation up to
    // 1.25 as the sun nears the horizon, none at noon
    float vib = 1.0 + 0.25 * (1.0 - smoothstep(0.0, 0.3, sun.y)) * smoothstep(-0.2, -0.02, sun.y + 0.1);
    float g = dot(shown, vec3(0.2126, 0.7152, 0.0722));
    fragColor = vec4(clamp(mix(vec3(g), shown, vib), 0.0, 1.0), 1.0);
}
