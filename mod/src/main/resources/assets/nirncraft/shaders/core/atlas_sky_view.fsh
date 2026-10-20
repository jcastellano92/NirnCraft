#version 330
#extension GL_ARB_separate_shader_objects : require
#include <nirncraft:atlas_air.glsl>
#include <nirncraft:atlas_sky.glsl>
// Infinite ATLAS's sky-view LUT (com.nirncraft.atlas.AtlasSky): the air's sky (atlas_air.glsl) marched for every
// texel's direction (atlas_sky.glsl's mapping), once a frame; the sky and the water's reflections look it up.
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec3 dir = atlasSkyViewDir(texCoord, AirSun.xyz);
    fragColor = vec4(airSkyRadiance(dir) * AirMoon.w, 1.0);  // (pre-exposed: physical night skies within half precision)
}
