#version 330
#extension GL_ARB_separate_shader_objects : require
#include <nirncraft:atlas_air.glsl>
#include <nirncraft:atlas_night.glsl>
// The sky's emitters in every direction (com.nirncraft.atlas.AtlasSky, atlas_night.glsl: the aurora, the stars and the
// galaxy, the moons, meteors, the airglow, through the air), six faces side by side (+X -X +Y -Y +Z -Z, the layout
// near_forward.fsh skyCubeAt reads): the water mirrors the sky off screen with it, the air's own sky (the sky-view LUT)
// added under it there. Linear radiance, pre-exposed for the half-float target: rgb x NightTarget.x, alpha
// NightTarget.x (the reader divides by it).
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    float fx = texCoord.x * 6.0;
    float f = floor(fx);
    vec2 uv = vec2(fx - f, 1.0 - texCoord.y) * 2.0 - 1.0;
    vec3 d;
    if (f < 0.5) d = vec3(1.0, -uv.y, -uv.x);
    else if (f < 1.5) d = vec3(-1.0, -uv.y, uv.x);
    else if (f < 2.5) d = vec3(uv.x, 1.0, uv.y);
    else if (f < 3.5) d = vec3(uv.x, -1.0, -uv.y);
    else if (f < 4.5) d = vec3(uv.x, -uv.y, 1.0);
    else d = vec3(-uv.x, -uv.y, -1.0);
    d = normalize(d);
    vec3 L = nightSky(d, NightTarget.y, nightDither(gl_FragCoord.xy), NIGHT_ALL, 12);
    float pre = max(NightTarget.x, 1e-6);
    fragColor = vec4(L * pre, pre);
}
