// Wind on foliage: a gusting field over the world, stronger in rain and storms, so neighbouring plants move together
// and a gust visibly crosses a meadow. Plants are rooted: a grass blade's base stays put and its tip moves; hanging
// moss and vines hang from their top; leaves shiver in place.
#ifndef SKYCRAFT_WIND
#define SKYCRAFT_WIND

#include "/lib/noise.glsl"
#include "/lib/materials.glsl"

// worldPos: block position of the vertex; up: how much of the plant's height is below this vertex (0 root .. 1 tip)
vec3 windOffset(vec3 worldPos, float time, float rain, int mat, float up) {
    float strength = WIND_STRENGTH * (0.6 + 1.4 * rain);
    vec2 dir = normalize(vec2(1.0, 0.35));
    // gusts travel along the wind
    float gust = valueNoise(worldPos.xz * 0.035 - dir * time * 0.9) * 0.8 + 0.2;
    float phase = dot(worldPos.xz, dir) * 0.6 + time * (2.2 + rain * 1.5);
    float sway = sin(phase) * 0.6 + sin(phase * 2.3 + worldPos.y) * 0.25;
    vec3 off = vec3(0.0);
    if (mat == MAT_LEAVES) {
        // leaves: a small shiver around their place, the canopy breathing with the gusts
        float sh = sin(time * 3.1 + hash13(floor(worldPos)) * 6.28 + worldPos.x * 1.7);
        off = vec3(dir.x, 0.25, dir.y) * (sh * 0.03 + sway * 0.025) * strength * gust;
    } else if (mat == MAT_PLANT || mat == MAT_PLANT_TOP || mat == MAT_HANGING) {
        float lever = mat == MAT_PLANT_TOP ? 1.0 + up : up;  // the upper half of tall grass carries the lower's sway
        off.xz = dir * (sway * 0.12 + gust * 0.06) * strength * gust * lever;
        off.y = -abs(off.x + off.z) * 0.15 * lever;  // bending shortens the stalk a little
    }
    return off;
}

#endif
