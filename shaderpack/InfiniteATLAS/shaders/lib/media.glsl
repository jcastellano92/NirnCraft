// The air between the eye and the scene, near field: a short march over the first MEDIA_RANGE blocks (fixed,
// quadratically spaced intervals, so a moving depth edge only clips the last one), then the closed-form ground fog
// beyond. The march is what makes the air local:
//  * outdoor fog (the mod's ground-fog drive over the terrain surface + the pack's dawn / night / wet mists), rain
//    mist under rain bands, ash haze in volcanic air: only where the sky reaches (lib/air.glsl outdoorExposure), so
//    fog never fills a house, a cave or a dungeon and a window still shows it outside
//  * dungeon air (the mod's DungeonAir volumes): a thin haze and a low creeping layer over the floor, tinted by style,
//    lit only by the lamps and lights inside; campfire smoke in rooms; shouts push it aside
//  * halos: every lamp, torch, fire and crystal (the mod's coloured block-light cache) and the mod's point lights
//    scatter in whatever air is there, plus a trace of clear air (HALO_STEPS)
//  * lightning light in the mist and rain around a channel, outdoors only
// Cost: MEDIA_STEPS iterations (LOW 4 .. ULTRA 16) of a roof texel, a lamp cell trilinear (within 26 blocks, halos
// only) and the room / light loops when the mod sends rooms or lights; weather is read twice per pixel, not per step.
#ifndef NIRNCRAFT_MEDIA
#define NIRNCRAFT_MEDIA

#include "/lib/air.glsl"
#include "/lib/lightning.glsl"
#include "/lib/fog.glsl"
#include "/lib/quality.glsl"

const float MEDIA_RANGE = 128.0;
const float HALO_AIR = 0.0012;  // the clear-air share of a lamp's halo, per block

// what the last applyMedia call's outdoor air scattered towards the eye of the sun's light (luminance): the most the
// light shafts (lib/shafts.glsl) may take back where that air lies in shadow
float gMediaSun = 0.0;

// the weather's aerosol band at a world column (categorical: nearest cell, never blended): 0 rain cloud, 1 snow,
// 2 dust, 3 volcanic ash / blight
int aerosolBand(vec2 worldXZ) {
    if (!haveWeather()) return 0;
    ivec2 c = ivec2(floor(worldXZ / 64.0));
    return clamp(int(floor(weatherTexel(weatherField, c).a * 4.0)), 0, 3);
}

// the mod's ground fog drive (weather dynamics a) as extinction per block, h blocks over the terrain surface
float modGroundFog(float drive, float h) {
    return (0.0005 * drive + 0.075 * drive * drive * drive) * (1.0 - smoothstep(4.0, 60.0, h));
}

// outdoor fog extinction at world point w (before exposure); dyn / wf: weather there; band: aerosol band
// the mod's weather-off switch (data texel 19,0 g): no mists, fog banks or storm haze in the near air, only the
// distance haze the far field has too (while the world is matched near to far, weather comes back on its own branch)
bool weatherOff() {
    return haveSmoke() && texelFetch(smokeSourcesTex, ivec2(19, 0), 0).g > 0.5;
}

float outdoorSigma(vec3 w, vec4 dyn, vec4 wf, int band, vec3 sun, float wet, float rain) {
    if (weatherOff()) return 0.0;
    float surface = haveWeather() ? max(63.0, dyn.g * 1024.0) : 63.0;
    float h = w.y - surface;
    if (h < -2.0) return 0.0;  // the coarse terrain: below it there is no outdoor air
    float sigma = 0.0;
#ifdef FOG_LAYERS
    // the pack's own mists pool over the land (the same layer the far closed form uses)
    sigma = groundFogDensity(sun, wet, rain) * exp(-max(h, 0.0) / 14.0);
#endif
    if (haveWeather()) {
        sigma += modGroundFog(dyn.a, h);
        // rain mist under the cloud base where a rain band falls
        if (band == 0 && w.y < dyn.r * 1024.0) sigma += 0.025 * pow(smoothstep(0.2, 0.75, wf.b), 3.0) * wf.g;
        // ash: a brown haze through the lower air of volcanic country
        if (band == 3) sigma += (0.0025 + 0.004 * wf.b) * (1.0 - smoothstep(30.0, 260.0, h));
        // snow: blowing snow in a blizzard
        if (band == 1) sigma += 0.012 * pow(smoothstep(0.3, 0.8, wf.b), 2.0) * (1.0 - smoothstep(10.0, 90.0, h));
    }
    return sigma;
}

vec3 bandTint(int band) {
    return band == 3 ? vec3(0.62, 0.55, 0.50) : band == 2 ? vec3(1.0, 0.84, 0.62) : band == 1 ? vec3(1.05, 1.08, 1.12)
                     : vec3(1.0);
}

// colour after the air along dir to distance t (t >= 1e29: the sky). marched = false: only the closed form (for
// reflections and refractions, where a second march would cost too much)
vec3 applyMedia(vec3 col, vec3 dir, float t, vec3 sun, vec3 camPos, float wet, float rain, float time, float dither,
                bool marched) {
    vec3 w0 = camPos, w1 = camPos + dir * min(t, MEDIA_RANGE);
    vec4 dyn1 = haveWeather() ? weatherSample(weatherDyn, w1.xz) : vec4(0.23, 63.0 / 1024.0, 0.0, 0.0);
    vec4 wf1 = haveWeather() ? weatherSample(weatherField, w1.xz) : vec4(0.0);
    int band = aerosolBand(camPos.xz);
    float mu = dot(dir, sun);
    vec3 litOut = (skyAmbient(vec3(0, 1, 0), sun) * 1.4 + sunRadiance(sun) * (0.08 + 0.35 * pow(max(mu, 0.0), 6.0)))
                * bandTint(band);
    float sunLum = dot(sunRadiance(sun) * (0.08 + 0.35 * pow(max(mu, 0.0), 6.0)) * bandTint(band), vec3(0.2126, 0.7152, 0.0722));
#ifdef WEATHER_SKY
    // the air under a storm cell is lit by a dimmer, greyer sky
    if (haveWeather()) {
        vec4 wc = weatherSample(weatherField, camPos.xz);
        float shade = clamp(smoothstep(0.62, 0.92, wc.r) * 0.45 + wc.g * 0.35 + wc.b * 0.45, 0.0, 0.85);
        litOut *= 1.0 - 0.45 * shade;
        sunLum *= 1.0 - 0.45 * shade;
    }
#endif
    gMediaSun = 0.0;
    float T = 1.0;
    vec3 S = vec3(0.0);
    // adaptive quality: the march's steps (fewer in front of far pixels first; 0 leaves the closed form alone) and the
    // halo steps (dither: the per-pixel count rounding, spatially stable)
    float qDither = qualityDither(gl_FragCoord.xy);
    int mediaSteps = 0;
    float qm = qualityKnob(6, 0, QUALITY_FALLBACK), mediaFade = qualityFade(qm, 0.1);
#if MEDIA_STEPS > 0
    mediaSteps = marched && qm > 0.0 ? qualityCount(MEDIA_STEPS, qm * qualityDistance(t), qDither, 1) : 0;
    if (mediaSteps > 0) {
        // fading in or out (the knob on its way between 0 and its first level): the march's share scales with it and
        // the closed form below starts that much nearer, so the near air eases between the two
        vec3 S0 = S;
        float T0 = T;
        loadAir(camPos);
        vec4 dyn0 = haveWeather() ? weatherSample(weatherDyn, w0.xz) : dyn1;
        vec4 wf0 = haveWeather() ? weatherSample(weatherField, w0.xz) : wf1;
        float nearEnd = min(t, MEDIA_RANGE);
        for (int i = 0; i < MEDIA_STEPS; i++) {
            if (i >= mediaSteps) break;
            float a = float(i) / float(mediaSteps), b = float(i + 1) / float(mediaSteps);
            float s0 = MEDIA_RANGE * a * a, s1 = min(nearEnd, MEDIA_RANGE * b * b);
            if (s1 <= s0) break;
            float len = s1 - s0;
            float dm = mix(s0, s1, 0.5 + (dither - 0.5) * 0.5);
            vec3 p = dir * dm;
            vec3 w = p + camPos;
            float f = dm / max(nearEnd, 1.0);
            float exposure = outdoorExposure(w, camPos);
            float sOut = exposure > 0.0 ? outdoorSigma(w, mix(dyn0, dyn1, f), mix(wf0, wf1, f), band, sun, wet, rain)
                                          * exposure : 0.0;
            vec4 room = gRooms > 0 ? roomMedium(p, camPos, time) : vec4(0.0);
            vec3 storm = lightningAir(p, exposure);
            float ext = sOut + room.a;
            float alpha = 1.0 - exp(-ext * len);
            vec3 c = ext > 1e-7 ? (sOut * (litOut * exposure + storm) + room.a * room.rgb * vec3(0.012)) / ext : vec3(0.0);
            S += T * (c * alpha + storm * 0.25 * HALO_AIR * len);
            gMediaSun += ext > 1e-7 ? T * alpha * sOut * exposure / ext * sunLum : 0.0;
            T *= 1.0 - alpha;
        }
        S = mix(S0, S, mediaFade);
        T = mix(T0, T, mediaFade);
        gMediaSun *= mediaFade;
    }
#endif
#if HALO_STEPS > 0
    // halos: lamps and the mod's point lights live within ~30 blocks, in 4-block cells: a dedicated even march there
    // (the main one is too coarse that far out), scattering in the local fog, the dungeon air and a trace of clear air
    float qh = qualityKnob(6, 1, QUALITY_FALLBACK);
    int haloSteps = marched && qh > 0.0 ? qualityCount(HALO_STEPS, qh, qDither, 1) : 0;
    vec3 haloS0 = S;
    if (haloSteps > 0 && (haveAir() || gLights > 0)) {
        loadAir(camPos);
        float end = min(t, 30.0);
        float stepLen = end / float(haloSteps);
        float Th = 1.0;
        for (int i = 0; i < HALO_STEPS; i++) {
            if (i >= haloSteps) break;
            float dm = (float(i) + dither) * stepLen;
            vec3 p = dir * dm;
            vec3 w = p + camPos;
            vec3 emit = airLampRadiance(w, camPos);
            if (gLights > 0) emit += localLights(p, vec3(0.0), false, camPos);
            if (dot(emit, emit) <= 1e-8) continue;
            float exposure = outdoorExposure(w, camPos);
            float sOut = exposure > 0.0 ? outdoorSigma(w, dyn1, wf1, band, sun, wet, rain) * exposure : 0.0;
            vec4 room = gRooms > 0 ? roomMedium(p, camPos, time) : vec4(0.0, 0.0, 0.0, 0.0);
            vec3 tint = sOut + room.a > 1e-7 ? (vec3(sOut) + room.rgb * room.a) / (sOut + room.a) : vec3(1.0);
            float sigma = sOut + room.a;
#ifdef NIRNCRAFT_REALM
            // a realm's authored fog carries the light of its lamps too (its extinction is the realm fog itself)
            float realmSigma = fogEnd > fogStart + 1.0 ? 1.0 / (fogEnd - fogStart) : 0.0;
            S += Th * emit * (tint * (sigma + HALO_AIR) + realmFog() / max(dot(realmFog(), vec3(0.333)), 0.05) * realmSigma * 0.5) * stepLen;
#else
            S += Th * emit * tint * (sigma + HALO_AIR) * stepLen;
#endif
            Th *= exp(-sigma * stepLen);
        }
        S = mix(haloS0, S, qualityFade(qh, 0.1));  // halos fade in and out with the knob
    }
#endif
    // beyond the march (or all of it, unmarched): the closed-form ground layer, outdoors only
    float from = mediaSteps > 0 ? MEDIA_RANGE * mediaFade : 0.0;
    if (t > from && t < 1e29) {  // the bare sky keeps its own horizon haze
        float to = min(t, 3000.0);
        float base = max(63.0, dyn1.g * 1024.0);
        float density = 0.0;
#ifdef FOG_LAYERS
        density = groundFogDensity(sun, wet, rain);
#endif
        if (haveWeather()) density += 0.0005 * dyn1.a + 0.075 * dyn1.a * dyn1.a * dyn1.a;
        // the layer pools over the land; underground it does not reach (and must not grow without bound)
        float camY = max(camPos.y, base - 6.0);
        float od = heightFogDepth(camY, dir.y, to, density, base, 14.0) - heightFogDepth(camY, dir.y, min(from, to), density, base, 14.0);
        float gate = outdoorExposure(camPos + dir * max(from, min(t, 48.0)), camPos);
        float tr = exp(-max(od, 0.0) * gate);
        S += T * litOut * (1.0 - tr);
        gMediaSun += T * (1.0 - tr) * sunLum;
        T *= tr;
    }
    return col * T + S;
}

#endif
