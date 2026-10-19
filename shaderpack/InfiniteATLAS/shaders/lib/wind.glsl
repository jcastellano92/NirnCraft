// Wind on foliage: a gusting field over the world, stronger in rain and storms, so neighbouring plants move together
// and a gust visibly crosses a meadow. Plants are rooted: a grass blade's base stays put and its tip moves; hanging
// moss and vines hang from their top; leaves shiver in place.
#ifndef NIRNCRAFT_WIND
#define NIRNCRAFT_WIND

#include "/lib/noise.glsl"
#include "/lib/materials.glsl"
#include "/lib/weather.glsl"
#include "/lib/modtex.glsl"

// the wind plants feel: the weather's heading over this column and the mod's live surface wind (0 calm .. 1 gale),
// so a meadow bends the way the clouds and the smoke go; a steady breeze without the mod
vec3 surfaceWind(vec2 worldXZ, float rain) {
    if (!haveWeather()) return vec3(normalize(vec2(1.0, 0.35)), 0.3 + 0.7 * rain);
    float turns = weatherTexel(weatherDyn, ivec2(floor(worldXZ / 64.0))).b;
    float gale = haveSmoke() ? smokeWind() : 0.3;
    return vec3(cos(turns * 6.2831853), sin(turns * 6.2831853), clamp(max(gale, rain * 0.7), 0.0, 1.0));
}

// worldPos: block position of the vertex; up: how much of the plant's height is below this vertex (0 root .. 1 tip)
vec3 windOffset(vec3 worldPos, float time, float rain, int mat, float up) {
    vec3 sw = surfaceWind(worldPos.xz, rain);
    float strength = WIND_STRENGTH * (0.45 + 1.6 * sw.z);
    vec2 dir = sw.xy;
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
