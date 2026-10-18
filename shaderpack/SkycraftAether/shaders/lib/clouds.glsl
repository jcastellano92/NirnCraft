// The pack's own cloud deck, drawn only when aether's far field (and its curved cloud shell) is absent: the mod's
// sky / cloud renderer stands down whenever shaders are active, so without this the sky would be empty. One curved
// layer at the weather's cloud base, shaped by the live weather (weatherField coverage, storm towers and rain
// curtains darken and thicken it), drifting with the wind; self-shadowed towards the sun at FALLBACK_CLOUDS 2. The
// same density shades the ground (fallbackCloudShadow) so a cloud's shadow passes under it. Cost: one plane
// intersection and 3 / 5 value-noise octaves per sky pixel (plus 3 towards the sun at quality 2), nothing where aether draws.
#ifndef SKYCRAFT_CLOUDS
#define SKYCRAFT_CLOUDS

#include "/lib/atmosphere.glsl"
#include "/lib/noise.glsl"
#include "/lib/weather.glsl"

// the deck's base and thickness in world blocks (the mod's condensation level where the weather is known)
float fallbackCloudBase(vec2 worldXZ) {
    return haveWeather() ? clamp(weatherSample(weatherDyn, worldXZ).r * 1024.0, 140.0, 900.0) : 230.0;
}

// cover 0..1, storm 0..1, curtain 0..1 at a world column (a plausible sky from the vanilla rain when no mod)
vec3 fallbackCloudWeather(vec2 worldXZ, float rain) {
    if (!haveWeather()) return vec3(0.42 + 0.5 * rain, 0.3 * rain, 0.5 * rain);
    vec4 w = weatherSample(weatherField, worldXZ);
    return vec3(clamp(max(w.r, w.b * 0.75), 0.0, 1.0), w.g, w.b);
}

// density 0..1 of the deck at a world column; octaves 2 or 4
float fallbackCloudDensity(vec2 worldXZ, float time, vec3 wx, int octaves) {
    vec2 wind = vec2(0.8, 0.6);
    vec2 q = worldXZ / 260.0 - wind * time * 0.008;
    q += vec2(valueNoise(q * 0.31 + 7.1), valueNoise(q * 0.31 - 3.7)) * 1.4;  // domain warp: heaps, not blobs
    float n = (fbm2(q, octaves) - 0.5) * 1.8 + 0.5;  // value-noise fBm clusters near 0.5: widen it
    // fair weather: scattered heaps; overcast: a closed deck; the threshold opens and closes with the cover
    float lo = 0.74 - wx.x * 0.64;
    return smoothstep(lo - 0.04, lo + 0.14, n);
}

// premultiplied (rgb, alpha) of the deck along a view ray from the camera (world dir); 0 where the ray misses it
vec4 fallbackClouds(vec3 dir, vec3 sun, vec3 camPos, float time, float rain) {
#if FALLBACK_CLOUDS > 0
    float base = fallbackCloudBase(camPos.xz);
    float dy = base - camPos.y;
    if (dir.y * dy <= 0.0 || abs(dir.y) < 0.004) return vec4(0.0);
    float t = dy / dir.y;
    if (t > 40000.0) return vec4(0.0);
    vec2 p = camPos.xz + dir.xz * t;
    vec3 wx = fallbackCloudWeather(p, rain);
#if FALLBACK_CLOUDS > 1
    const int OCT = 5;
#else
    const int OCT = 3;
#endif
    float dens = fallbackCloudDensity(p, time, wx, OCT);
    if (dens <= 0.002) return vec4(0.0);
    // light through the deck: less sun where a denser part lies towards it, darker bellies under storms
    float shade = 1.0;
#if FALLBACK_CLOUDS > 1
    vec2 toSun = sun.xz / max(length(sun.xz), 1e-3);
    shade = 1.0 - 0.65 * fallbackCloudDensity(p + toSun * 60.0, time, wx, 3);
#endif
    float storm = clamp(wx.y * 0.8 + wx.z * 0.6, 0.0, 1.0);
    float belly = mix(1.0, 0.38, storm) * mix(1.0, 0.85, dens);
    float mu = dot(dir, sun);
    float phase = 0.55 + 0.45 * pow(max(mu, 0.0), 6.0) + 0.25 * (1.0 - dens) * pow(max(mu, 0.0), 32.0);
    // seen from below the sky light reaches the belly through the deck; from above its sunlit top
    vec3 col = skyAmbient(vec3(0.0, 1.0, 0.0), sun) * (dy > 0.0 ? 1.35 : 1.6) * belly
             + sunRadiance(sun) * shade * phase * belly * 0.55;
    float alpha = clamp(dens * (0.75 + 0.25 * wx.x + 0.3 * storm), 0.0, 1.0);
    // thins towards the horizon (the deck seen edge-on is the haze), and aerial perspective on top
    alpha *= smoothstep(0.004, 0.05, abs(dir.y));
    float tr = aerialTransmittance(t * 0.5, camPos.y, rain);
    col = mix(aerialFog(dir, sun), col, tr);
    return vec4(col * alpha, alpha);
#else
    return vec4(0.0);
#endif
}

// the deck's shadow on a receiver at world position wp: 1 lit .. 0.25 under the thickest cloud
float fallbackCloudShadow(vec3 wp, vec3 toLight, float time, float rain) {
#if FALLBACK_CLOUDS > 0
    if (toLight.y < 0.03) return 1.0;
    float base = fallbackCloudBase(wp.xz);
    float dy = base - wp.y;
    if (dy <= 0.0) return 1.0;
    vec2 p = wp.xz + toLight.xz * (dy / toLight.y);
    vec3 wx = fallbackCloudWeather(p, rain);
    float dens = fallbackCloudDensity(p, time, wx, 2);
    return 1.0 - 0.72 * dens * smoothstep(0.03, 0.15, toLight.y);
#else
    return 1.0;
#endif
}

#endif
