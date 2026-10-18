// Nirn's sky from the mod's celestial state (SmokeSourceTexture row 1): Masser and Secunda on their own orbits and
// phases (8- and 9-day cycles, Secunda inclined), lit from the sun, dimming the stars around them; the night's key
// light is the brighter moon (LUNAR_KEY: its colour, strength and direction, with the shadow map turned to follow
// it); the aurora from the mod's live packets (AuroraMath: six curtains, their colour state and activity) and the
// light it sheds on the ground; the mod's sky options (stars, galaxy, Secunda, aurora) and night-sky visibility
// (light pollution, moon glare). Without the mod: the vanilla moon and the pack's own stars and aurora.
#ifndef SKYCRAFT_CELESTIAL
#define SKYCRAFT_CELESTIAL

#include "/lib/modtex.glsl"
#include "/lib/noise.glsl"

#ifdef MOD_SKY
uniform sampler2D masserPhases;   // 4 x 4 phase atlas
uniform sampler2D secundaPhases;
#endif

// the mod's sky state is there (the smoke header's options texel carries a set alpha... or any option on)
bool haveModSky() {
#if defined MOD_SKY && !defined SKYCRAFT_REALM
    return haveSmoke() && haveAir();
#else
    return false;
#endif
}

// (stars, galaxy, Secunda, aurora) toggles of the mod's config
vec4 modSkyOptions() { return haveModSky() ? texelFetch(smokeSourcesTex, ivec2(7, 1), 0) : vec4(1.0); }

// NightSkyState: (visibility, pollution, Masser light, Secunda light)
vec4 modNightSky() { return haveModSky() ? texelFetch(smokeSourcesTex, ivec2(3, 1), 0) : vec4(1.0, 0.0, 1.0, 1.0); }

float skyClockDays() {
    vec4 b = texBytes(smokeSourcesTex, ivec2(1, 1));
    return (b.r + b.g * 256.0 + b.b * 65536.0 + b.a * 16777216.0 + texelFetch(smokeSourcesTex, ivec2(11, 1), 0).r) / 24000.0;
}

float skySeconds() {
    vec4 b = texBytes(smokeSourcesTex, ivec2(13, 1));
    return (b.r + b.g * 256.0 + b.b * 65536.0 + texelFetch(smokeSourcesTex, ivec2(11, 1), 0).r) * 0.05;
}

vec3 axisRotate(vec3 p, vec3 axis, float angle) {
    return p * cos(angle) + cross(axis, p) * sin(angle) + axis * dot(axis, p) * (1.0 - cos(angle));
}

struct Moons { vec3 masser; vec3 secunda; vec2 weight; };  // weight: lit fraction x above the horizon

Moons moons(vec3 sun) {
    float days = skyClockDays();
    float tilt = sunPathRotation * 0.01745329252;
    vec3 axis = vec3(0.0, sin(tilt), cos(tilt));
    Moons m;
    m.masser = axisRotate(-sun, axis, fract((days - 0.75) / 8.0) * 6.2831853);
    m.secunda = axisRotate(axisRotate(-sun, axis, fract((days - 0.75) / 9.0 + 0.375) * 6.2831853), vec3(1.0, 0.0, 0.0), -0.41887902);
    m.weight = vec2(0.5 - 0.5 * dot(sun, m.masser), 0.5 - 0.5 * dot(sun, m.secunda))
             * smoothstep(vec2(-0.035), vec2(0.10), vec2(m.masser.y, m.secunda.y));
    vec4 ns = modNightSky();
    m.weight *= vec2(ns.b, ns.a);
    if (modSkyOptions().b < 0.5) m.weight.y = 0.0;
    return m;
}

// the night's key light: the brighter moon's direction (Secunda only when it outshines Masser) and the two moons'
// light (Masser's warm reflection, Secunda's smaller cool one)
vec3 lunarKeyDir(Moons m) {
    float second = smoothstep(0.8, 1.2, m.weight.y * 0.20 / max(0.001, m.weight.x * 0.80));
    return normalize(mix(m.masser, m.secunda, second));
}

vec3 lunarRadiance(Moons m) {
    return (vec3(1.0, 0.72, 0.78) * m.weight.x * 0.80 + vec3(0.78, 0.88, 1.0) * m.weight.y * 0.20) * 0.16;
}

#ifdef MOD_SKY
// a moon's disc (atlas of 16 phases, the phase from the sun's angle to it); rgb linear radiance, a coverage
vec4 moonDisc(sampler2D atlas, vec3 ray, vec3 moon, float size, vec3 sun, vec3 tint) {
    float forward = dot(ray, moon);
    if (forward < 0.96) return vec4(0.0);
    vec3 ref = abs(moon.y) < 0.96 ? vec3(0.0, 1.0, 0.0) : vec3(0.0, 0.0, 1.0);
    vec3 right = normalize(cross(ref, moon));
    vec3 ps = sun - moon * dot(sun, moon);
    if (dot(ps, ps) > 1e-4) right = normalize(ps);  // the lit limb faces the sun
    vec3 up = cross(moon, right);
    vec2 d = vec2(dot(ray, right), dot(ray, up)) / (forward * size);
    if (max(abs(d.x), abs(d.y)) >= 1.0) return vec4(0.0);
    float cycle = acos(clamp(-dot(sun, moon), -1.0, 1.0)) / 6.2831853;
    float phase = cycle * 16.0;
    float p0 = floor(phase), p1 = mod(p0 + 1.0, 16.0);
    vec2 uv = vec2(d.x, -d.y) * 0.5 + 0.5;
    vec2 t0 = vec2(mod(p0, 4.0), floor(p0 / 4.0)), t1 = vec2(mod(p1, 4.0), floor(p1 / 4.0));
    vec4 tx = mix(textureLod(atlas, (t0 + uv) * 0.25, 0.0), textureLod(atlas, (t1 + uv) * 0.25, 0.0), fract(phase));
    vec3 surf = pow(tx.rgb, vec3(2.2)) * 2.6;
    surf = surf / (vec3(1.0) + surf) * 1.45;
    // the dark limb faintly lit by Nirn, strongest near the new moon
    float lit = 0.5 + 0.5 * cos(cycle * 6.2831853);
    surf += pow(textureLod(atlas, uv * 0.25, 0.0).rgb, vec3(2.2)) * vec3(0.005, 0.009, 0.016) * (1.0 - lit);
    return vec4(surf * tint, tx.a * smoothstep(-0.035, 0.055, moon.y));
}

// the moons over the sky (premultiplied over c) and how much they veil the stars around them (0..1)
vec4 modMoons(vec3 ray, vec3 sun, Moons m, out float starVeil) {
    vec4 ns = modNightSky();
    float glareM = exp(-max(0.0, 1.0 - dot(ray, m.masser)) * 20.0) * m.weight.x;
    float glareS = exp(-max(0.0, 1.0 - dot(ray, m.secunda)) * 75.0) * m.weight.y;
    starVeil = 1.0 - exp(-4.5 * glareM - 2.5 * glareS) * (1.0 - 0.8 * pow(dot(m.weight, vec2(0.8, 0.2)), 0.65));
    vec3 glow = (vec3(0.85, 0.68, 0.82) * glareM + vec3(0.75, 0.83, 1.0) * glareS) * 0.008 * smoothstep(0.05, -0.2, sun.y);
    vec4 big = moonDisc(masserPhases, ray, m.masser, 0.20, sun, vec3(1.08, 0.77, 0.83));
    vec4 small = modSkyOptions().b > 0.5 ? moonDisc(secundaPhases, ray, m.secunda, 0.075, sun, vec3(0.85, 0.93, 1.08)) : vec4(0.0);
    vec3 c = glow;
    c = mix(c, big.rgb, big.a);
    c = mix(c, small.rgb, small.a);
    return vec4(c, max(big.a, small.a));
}

// the mod's aurora: six curtain packets (centre azimuth, width, hem height, extent; amplitude, seed, speed) and the
// sky's colour state; north is -Z. time: the mod's sky seconds
vec3 modAurora(vec3 ray, vec3 sun, float time) {
    vec4 options = modSkyOptions();
    float activity = texelFetch(smokeSourcesTex, ivec2(15, 1), 0).a;
    if (options.a < 0.5 || activity < 0.002 || ray.y < 0.015) return vec3(0.0);
    vec4 state = texelFetch(smokeSourcesTex, ivec2(5, 1), 0);
    float azimuth = atan(ray.x, -ray.z);
    float elevation = asin(clamp(ray.y, -1.0, 1.0));
    vec3 emission = vec3(0.0);
    for (int k = 0; k < 6; k++) {
        vec4 shape = texelFetch(smokeSourcesTex, ivec2(16 + k * 2, 1), 0);
        vec4 detail = texelFetch(smokeSourcesTex, ivec2(17 + k * 2, 1), 0);
        float amplitude = (detail.r * 255.0 + detail.g * 65280.0) / 65535.0;
        if (amplitude < 0.001) continue;
        float seed = detail.b * 37.0;
        float centre = shape.r * 6.2831853 - 3.14159265;
        float az = atan(sin(azimuth - centre), cos(azimuth - centre));
        float angular = az / max(0.10, shape.g * 1.5);
        float spread = exp(-angular * angular * angular * angular * 1.4);
        if (spread < 0.002) continue;
        float drift = time * (0.025 + detail.a * 0.025);
        // value noise at the frequencies the mod's 256-texel noise lookups had (texel = cell)
        vec2 flow = vec2(valueNoise(vec2(az * 33.0 + drift * 4.4, seed * 44.0 + time * 0.44)),
                         valueNoise(vec2(az * 33.0 + drift * 4.4 + 17.0, seed * 44.0 + time * 0.44 + 5.0)));
        float fold = sin(az * 4.3 + seed + drift) * 0.06 + sin(az * 11.7 - drift * 1.7 + flow.y * 3.0) * 0.025 + (flow.x - 0.5) * 0.12;
        float h = (elevation - (shape.b * 1.5 + fold)) / max(0.10, shape.a);
        if (h < -0.35 || h > 2.2) continue;
        float breakup = valueNoise(vec2(az * 74.0 - drift * 6.4, h * 33.0 + seed * 256.0));
        float base = smoothstep(-0.20, 0.22 + breakup * 0.18, h);
        float deposition = base * exp(-max(h, 0.0) * (2.0 + detail.a));
        float bend = sin(h * 3.8 + time * 0.15 + seed) * (0.015 + 0.025 * detail.a) * clamp(h, 0.0, 1.0);
        float rays = valueNoise(vec2((az + bend) * 180.0 + flow.x * 26.0 - time * 0.64, seed * 256.0 + time * 0.5));
        rays = 0.14 + smoothstep(0.18, 0.83, rays) * 1.3;
        float folds = 0.35 + 0.65 * smoothstep(0.18, 0.75, breakup);
        float pulse = 0.60 + 0.40 * sin(az * 5.0 - time * (0.35 + detail.a * 0.35) + seed);
        vec3 oxygen = mix(vec3(0.055, 0.86, 0.32), vec3(0.10, 0.68, 0.77), state.r * 0.35 + flow.y * 0.25);
        vec3 nitrogen = vec3(0.42, 0.08, 0.65);
        vec3 upper = mix(vec3(0.20, 0.34, 0.60), vec3(0.80, 0.055, 0.07), state.b);
        vec3 col = mix(nitrogen, oxygen, smoothstep(0.01, 0.20, h));
        col = mix(col, upper, smoothstep(0.40, 1.40, h));
        emission += col * deposition * spread * amplitude * rays * folds * pulse * 2.5;
    }
    // the curtains' emission is perceptual in the mod's encoding: linear here, at night only
    return pow(emission, vec3(2.2)) * 0.15 * smoothstep(0.015, 0.11, ray.y) * smoothstep(0.05, -0.15, sun.y);
}
#endif

// the aurora's light on a surface facing n (the mod's spherical-harmonic irradiance), linear
vec3 auroraIrradiance(vec3 n) {
#ifdef MOD_SKY
    if (!haveModSky()) return vec3(0.0);
    vec4 c0 = texelFetch(smokeSourcesTex, ivec2(28, 1), 0);
    if (c0.a < 0.002) return vec3(0.0);
    vec3 cx = texelFetch(smokeSourcesTex, ivec2(29, 1), 0).rgb * 2.0 - 1.0;
    vec3 cy = texelFetch(smokeSourcesTex, ivec2(30, 1), 0).rgb * 2.0 - 1.0;
    vec3 cz = texelFetch(smokeSourcesTex, ivec2(31, 1), 0).rgb * 2.0 - 1.0;
    return max(vec3(0.0), c0.rgb + cx * n.x + cy * n.y + cz * n.z) * 0.02;
#else
    return vec3(0.0);
#endif
}

#endif
