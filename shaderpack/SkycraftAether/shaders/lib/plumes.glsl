// Smoke columns from the mod's smoke sources (SmokeSourceTexture): source 0 is the Red Mountain volcano (a kilometre
// of ash leaning downwind, glowing over the vent at night), 1..23 are the strongest nearby fires (Helgen burning,
// burning farms), which the mod also draws as sprite smoke through gbuffers_textured: their volumes here are opt-in
// (SMOKE_FIRE_VOLUMES) so the two never double. Each column is a bounded volume: the view ray is clipped to the
// column's box once and only that interval is marched (PLUME_STEPS), so a column costs nothing off its box. Billows
// rise with the mod's clock; the column leans with the weather's wind; soot is lit by the sky, a little sun, its
// own fire at the base and the storm's lightning; no smoke under a roof.
#ifndef SKYCRAFT_PLUMES
#define SKYCRAFT_PLUMES

#include "/lib/modtex.glsl"
#include "/lib/air.glsl"
#include "/lib/lightning.glsl"
#include "/lib/atmosphere.glsl"
#include "/lib/quality.glsl"

float valueNoise3(vec3 p) {
    vec3 i = floor(p), f = p - i;
    vec3 u = f * f * (3.0 - 2.0 * f);
    float a = hash13(i), b = hash13(i + vec3(1.0, 0.0, 0.0)), c = hash13(i + vec3(0.0, 1.0, 0.0)), d = hash13(i + vec3(1.0, 1.0, 0.0));
    float e = hash13(i + vec3(0.0, 0.0, 1.0)), g = hash13(i + vec3(1.0, 0.0, 1.0)), h = hash13(i + vec3(0.0, 1.0, 1.0)), k = hash13(i + vec3(1.0));
    return mix(mix(mix(a, b, u.x), mix(c, d, u.x), u.y), mix(mix(e, g, u.x), mix(h, k, u.x), u.y), u.z);
}

// the wind a column leans with: the weather's heading at its foot (unit vector)
vec2 plumeWind(vec2 xz) {
    if (!haveWeather()) return vec2(0.8, 0.6);
    float turns = weatherTexel(weatherDyn, ivec2(floor(xz / 64.0))).b;
    return vec2(cos(turns * 6.2831853), sin(turns * 6.2831853));
}

// density of a column at world point w (time: seconds of the mod's clock)
float plumeDensity(SmokePlume s, vec2 wind, vec3 w, float time) {
    float h = w.y - s.y;
    if (h <= 0.0 || h >= s.height) return 0.0;
    float rise = h / s.height;
    float radius = s.radius * (0.25 + 1.6 * sqrt(rise));
    vec2 off = w.xz - s.xz - wind * h * (0.18 + 0.45 * rise);
    float r2 = dot(off, off);
    if (r2 > radius * radius * 2.0) return 0.0;
    vec3 np = vec3(off.x, h - time * 3.5, off.y) / max(10.0, radius * 0.6);
    float billow = 0.65 * valueNoise3(np) + 0.35 * valueNoise3(np * 2.4 + 5.3);
    float radial = 1.0 - smoothstep(0.0, radius * (0.7 + billow * 0.6), sqrt(r2));
    return radial * smoothstep(0.0, 0.05, rise) * (1.0 - smoothstep(0.78, 1.0, rise)) * s.strength * (0.12 + 0.36 * billow);
}

// the columns along the view ray to distance tEnd, as (in-scattered light, transmittance); camPos world, dir world
vec4 plumesAlong(vec3 camPos, vec3 dir, float tEnd, vec3 sun, float time, float dither, float rain) {
    vec3 S = vec3(0.0);
    float T = 1.0;
#if PLUME_STEPS > 0
    int count = smokeCount();
#ifdef SMOKE_FIRE_VOLUMES
    int last = count;
#else
    int last = min(count, 1);
#endif
    vec3 sd = vec3(abs(dir.x) < 1e-6 ? 1e-6 : dir.x, abs(dir.y) < 1e-6 ? 1e-6 : dir.y, abs(dir.z) < 1e-6 ? 1e-6 : dir.z);
    vec3 inv = 1.0 / sd;
    vec3 amb = skyAmbient(vec3(0, 1, 0), sun);
    vec3 key = sun.y > -0.05 ? sunRadiance(sun) : vec3(0.035, 0.05, 0.09) * smoothstep(-0.05, -0.2, sun.y);
    float mu = dot(dir, sun.y > -0.05 ? sun : -sun);
    float phase = 0.4 + 0.6 * (1.0 - 0.2025) / pow(max(1.2025 - 0.9 * mu, 1e-3), 1.5);
    float glowDay = mix(1.0, 0.3, smoothstep(-0.1, 0.3, sun.y));  // fire light shows against the night
    // adaptive quality: steps through each column's interval (coarser billows); at least 2 (a column never vanishes)
    int steps = qualityCount(PLUME_STEPS, qualityKnob(6, 2, QUALITY_FALLBACK), qualityDither(gl_FragCoord.xy), 2);
    for (int i = 0; i < 24; i++) {
        if (i >= last) break;
        SmokePlume s = smokePlume(i);
        if (s.strength < 0.01 || s.height < 1.0) continue;
        vec2 wind = plumeWind(s.xz);
        vec2 tip = s.xz + wind * s.height * 0.63;
        float pad = s.radius * 2.4;
        vec3 lo = vec3(min(s.xz.x, tip.x) - pad, s.y, min(s.xz.y, tip.y) - pad);
        vec3 hi = vec3(max(s.xz.x, tip.x) + pad, s.y + s.height, max(s.xz.y, tip.y) + pad);
        vec3 a = (lo - camPos) * inv, b = (hi - camPos) * inv;
        vec3 n3 = min(a, b), f3 = max(a, b);
        float enter = max(0.0, max(n3.x, max(n3.y, n3.z)));
        float leave = min(tEnd, min(f3.x, min(f3.y, f3.z)));
        if (leave <= enter || enter > 40000.0) continue;
        float stepLen = (leave - enter) / float(steps);
        float glowScale = 8.0 + s.height * 0.12;  // a fire lights the base of its smoke; the volcano a broad cone
        vec3 Sp = vec3(0.0);
        float Tp = 1.0;
        for (int k = 0; k < PLUME_STEPS; k++) {
            if (k >= steps) break;
            float t = enter + (float(k) + dither) * stepLen;
            vec3 w = camPos + dir * t;
            float sigma = plumeDensity(s, wind, w, time) * 0.18 * (i == 0 ? 1.0 : 0.7 + 0.5 * s.smoke);
            if (sigma <= 1e-5) continue;
            vec3 p = w - camPos;
            if (length(p) < 58.0) sigma *= outdoorExposure(w, camPos);  // never inside a house
            if (sigma <= 1e-5) continue;
            float h = w.y - s.y;
            // heavy fire smoke (a burning town) is black soot; the volcano's ash a lighter grey
            vec3 soot = (amb * vec3(0.26, 0.24, 0.22) + key * 0.12 * phase) * (i == 0 ? 1.0 : 1.0 - 0.18 * s.smoke);
            soot += vec3(1.0, 0.24, 0.045) * s.strength * (i == 0 ? 0.9 : 0.10) * exp(-h / glowScale) * glowDay;
            soot += lightningAir(p, 1.0) * 0.25;
            float alpha = 1.0 - exp(-sigma * stepLen);
            Sp += Tp * soot * alpha;
            Tp *= 1.0 - alpha;
            if (Tp < 0.01) break;
        }
        // the air in front of the column
        float ta = aerialTransmittance(enter, camPos.y, rain);
        S += T * (Sp * ta + aerialFog(dir, sun) * (1.0 - ta) * (1.0 - Tp));
        T *= Tp;
    }
#endif
    return vec4(S, T);
}

#endif
