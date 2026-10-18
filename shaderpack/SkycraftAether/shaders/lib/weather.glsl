// The mod's live weather (WeatherFieldTexture, server-authoritative physics): the near 128x128 grid of 64-block cells,
// world-anchored and toroidal (cell = floorMod(floor(world / 64), 128)), the same field aether's clouds read
// (aether_clouds.slang sampleField). weather_field: r coverage, g storm tower (convection), b precipitation curtain,
// a band/flash. weather_dynamics: r cloud base / 1024, g surface / 1024, b wind angle (turns, atan2(z, x)), a ground fog.
#ifndef SKYCRAFT_WEATHER
#define SKYCRAFT_WEATHER

uniform sampler2D weatherField;
uniform sampler2D weatherDyn;

#ifdef SKYCRAFT_REALM
bool haveWeather() { return false; }  // terrestrial weather belongs to the Overworld (DimensionMediaPolicy)
#else
bool haveWeather() { return textureSize(weatherField, 0).x == 128 && textureSize(weatherDyn, 0).x == 128; }
#endif

vec4 weatherTexel(sampler2D t, ivec2 c) { return texelFetch(t, ivec2(c.x & 127, c.y & 127), 0); }

// bilinear in world space across the toroidal grid
vec4 weatherSample(sampler2D t, vec2 worldXZ) {
    vec2 cell = worldXZ / 64.0 - 0.5;
    ivec2 b = ivec2(floor(cell));
    vec2 f = cell - vec2(b);
    vec4 a = weatherTexel(t, b), bb = weatherTexel(t, b + ivec2(1, 0));
    vec4 c = weatherTexel(t, b + ivec2(0, 1)), d = weatherTexel(t, b + ivec2(1, 1));
    return mix(mix(a, bb, f.x), mix(c, d, f.x), f.y);
}

// wind over water: (angle in radians the wind blows towards, windiness 0 calm .. 1 gale). Angles are blended as
// vectors (no seam at the wrap); windiness follows storms, rain curtains and cover. Without the mod's textures: a
// light breeze from the default wind (0.8, 0.6), freshening with rain.
vec2 waterWind(vec2 worldXZ, float rain) {
    if (!haveWeather()) return vec2(0.6435, clamp(0.25 + 0.55 * rain, 0.0, 1.0));
    vec2 cell = worldXZ / 64.0 - 0.5;
    ivec2 b = ivec2(floor(cell));
    vec2 f = cell - vec2(b);
    vec2 dir = vec2(0.0);
    for (int k = 0; k < 4; k++) {
        ivec2 o = ivec2(k & 1, k >> 1);
        float w = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y);
        float turns = weatherTexel(weatherDyn, b + o).b;
        dir += w * vec2(cos(turns * 6.2831853), sin(turns * 6.2831853));
    }
    vec4 wf = weatherSample(weatherField, worldXZ);
    float windiness = clamp(0.18 + 0.5 * wf.g + 0.35 * wf.b + 0.15 * wf.r + 0.25 * rain, 0.0, 1.0);
    return vec2(atan(dir.y, dir.x), windiness);
}

#endif
