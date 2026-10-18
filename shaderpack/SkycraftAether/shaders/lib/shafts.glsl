// Light shafts from real occlusion (SHAFT_MARCH): the key light's visibility marched along the view ray through the
// air the rest of the pack already has. Nothing is painted on: a shaft is wherever the sun (or the night's moon)
// actually reaches the air, and the dark between shafts is the air it does not.
//  * near (to SHAFT_NEAR blocks, within the shadow map): the shadow map (cave mouths, ceiling holes, windows, doors,
//    trees) x aether's sun map (mountains, cloud gaps)
//  * far (to SHAFT_FAR, aether's sun map only): cloud and mountain shadows (crepuscular rays)
// The air: under a roof or underground (the roof cache, the terrain surface) the pack's air has no sun in it, so the
// dust there (SHAFT_DUST_IN) gains the key light where it reaches (forward-scattering phase): beams through windows,
// doors, cave mouths and ceiling holes. Outdoors the haze and the ground fog / mists already carry the sun
// (lib/atmosphere.glsl, lib/media.glsl), so their shadowed parts give it back: the dark between shafts in a misty
// forest or under broken cloud. Open daylight is never brightened; nothing glows where the light cannot reach.
// The result is a scalar k (shafts = k x keyLightRadiance), kept in colortex4's alpha; composite1.fsh smooths it over
// the 4 x 4 block the per-pixel step offsets (Bayer) interleave over, so VL_SAMPLES steps read as 16x as many.
// Cost: VL_SAMPLES (x the LIGHT_SHAFTS knob, fewer far away) steps of one hardware shadow compare, one sun-map texel and
// one roof texel, plus a third as many far steps of one sun-map texel; nothing for pixels under water. Callers declare
// gbufferModelViewInverse, cameraPosition, frameTimeCounter and rainStrength before including this.
#ifndef SKYCRAFT_SHAFTS
#define SKYCRAFT_SHAFTS

#include "/lib/lighting.glsl"
#include "/lib/fog.glsl"
#include "/lib/media.glsl"
#include "/lib/quality.glsl"

const float SHAFT_DUST_IN = 0.012;    // still air under a roof or underground: dust (a beam across a room shows)
const float SHAFT_NEAR = 160.0;       // the shadow-mapped march, blocks (at most the shadow distance)
const float SHAFT_FAR = 3000.0;       // the sun-map march beyond it
const float SHAFT_HAZE = 2.6;         // the low haze's share of the sky's Mie glow over SHAFT_FAR, per aerial optical depth

// a 4 x 4 Bayer offset 0..1 (composite1.fsh averages the 4 x 4 block: all 16 offsets)
float shaftBayer(vec2 px) {
    ivec2 p = ivec2(px) & 3;
    int i = p.x + p.y * 4;
    const float m[16] = float[16](0.0, 8.0, 2.0, 10.0, 12.0, 4.0, 14.0, 6.0, 3.0, 11.0, 1.0, 9.0, 15.0, 7.0, 13.0, 5.0);
    return (m[i] + 0.5) / 16.0;
}

// Henyey-Greenstein relative to isotropic scattering (1 = isotropic)
float shaftHG(float mu, float g) {
    return (1.0 - g * g) / pow(max(1.0 + g * g - 2.0 * g * mu, 1e-4), 1.5);
}

// the view ray in aether's sun map, linear in t: map uv, depth along the light, the coarse bias (lighting.glsl aetherSun)
struct SunRay { bool ok; vec2 uv0; vec2 duv; float s0; float ds; float bias; int size; };

SunRay sunRay(vec3 dir) {
    SunRay r = SunRay(false, vec2(0.0), vec2(0.0), 0.0, 0.0, 0.0, 0);
#ifdef AETHER_FAR_FIELD
    ivec2 sz = textureSize(aetherSunMap, 0);
    int S = sz.x;
    if (S < 2 || sz.y != S + 1) return r;
    vec2 q[7];
    for (int k = 0; k < 7; k++) q[k] = texelFetch(aetherSunMap, ivec2(k, S), 0).rg;
    if (q[6].y != 1.0) return r;
    vec3 c = vec3(q[0].x, q[0].y, q[1].x);
    float he = q[1].y;
    vec3 X = vec3(q[2].x, q[2].y, q[3].x), Y = vec3(q[3].y, q[4].x, q[4].y), L = vec3(q[5].x, q[5].y, q[6].x);
    vec3 r0 = cameraPosition - c;
    r.uv0 = vec2(dot(r0, X), dot(r0, Y)) / he * 0.5 + 0.5;
    r.duv = vec2(dot(dir, X), dot(dir, Y)) / he * 0.5;
    r.s0 = dot(r0, L);
    r.ds = dot(dir, L);
    r.bias = 2.0 * (2.0 * he / float(S)) + 1.5;
    r.size = S;
    r.ok = true;
#endif
    return r;
}

// the key light through distant terrain and the clouds at t along the ray (1 off the map)
float sunRayVis(SunRay r, float t) {
#ifdef AETHER_FAR_FIELD
    if (!r.ok) return 1.0;
    vec2 uv = r.uv0 + r.duv * t;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return 1.0;
    vec2 tx = texelFetch(aetherSunMap, min(ivec2(uv * float(r.size)), ivec2(r.size - 1)), 0).rg;
    return (tx.r > r.s0 + r.ds * t + r.bias ? 0.0 : 1.0) * mix(0.06, 1.0, tx.g);
#else
    return 1.0;
#endif
}

// how open to the sky a point of the march is, cheaply: the roof cache's texel within its reach (as outdoorExposure),
// beyond it the coarse terrain surface over the camera (one weather read per pixel instead of four per step)
float shaftExposure(vec3 w, bool air, float surface) {
    if (air && max(abs(w.x - cameraPosition.x), abs(w.z - cameraPosition.z)) <= 58.0) {
        vec4 d = texBytes(fogRadianceTex, ivec2(floor(w.xz / 2.0)) & ivec2(63));
        if (d.a >= 128.0) {
            float roof = bytes16(d.g, d.b) - 64.0;
            return smoothstep(roof + 0.05, roof + 0.75, w.y);
        }
    }
    return 1.0 - smoothstep(4.0, 12.0, surface - w.y);
}

// the in-scattered key light along a view ray (world dir) to dist, as a multiple k of keyLightRadiance(sun)
// (signed: shadowed fog and haze give back light). px: the pixel; wet, rain: the weather uniforms. Call after the
// pixel's applyMedia: the fog gives back at most the sun light that call put in it (gMediaSun)
float shaftsMarch(vec3 dir, float dist, vec3 sun, vec2 px, float wet, float rain) {
#if defined SHADOWS && !defined SKYCRAFT_REALM
    vec3 L = keyLightDir(sun);
    vec3 key = keyLightRadiance(sun);
    float keyLum = dot(key, vec3(0.2126, 0.7152, 0.0722));
    if (keyLum < 1e-4) return 0.0;
    float q = qualityKnob(4, 0, QUALITY_FALLBACK);
    if (q <= 0.0) return 0.0;
    int steps = qualityCount(VL_SAMPLES, q * qualityDistance(min(dist, SHAFT_NEAR)), qualityDither(px), 2);
    float dither = shaftBayer(px);
    // the sun's share of the fog's light in applyMedia (lib/media.glsl litOut), relative to the key's light: by day
    // the key is the sun (1), by night the moon (the fog had no sun share to lose)
    float sunShare = dot(sunRadiance(sun), vec3(0.2126, 0.7152, 0.0722)) / keyLum;
    float muKey = dot(dir, L), muSun = dot(dir, sun);
    float phaseDust = 0.35 + 0.65 * shaftHG(muKey, 0.6);
    float phaseFog = (0.08 + 0.35 * pow(max(muSun, 0.0), 6.0)) * sunShare;
    // the shadow map along the ray (Iris' shadow light: turned onto the night's key moon as lighting.glsl does)
    mat3 rot = shadowKeyRotation(L);
    vec3 sA = (shadowProjection * (shadowModelView * vec4(0.0, 0.0, 0.0, 1.0))).xyz;
    vec3 sB = (shadowProjection * vec4(mat3(shadowModelView) * (rot * dir), 0.0)).xyz;
    SunRay sr = sunRay(dir);
    // the air the pack already lights (outdoorSigma's layers), from the weather over the camera
    vec4 dyn = haveWeather() ? weatherSample(weatherDyn, cameraPosition.xz) : vec4(0.23, 63.0 / 1024.0, 0.0, 0.0);
    float surface = haveWeather() ? max(63.0, dyn.g * 1024.0) : 63.0;
    float fogDensity = 0.0;
#ifdef FOG_LAYERS
    fogDensity = groundFogDensity(sun, wet, rain);
#endif
    float drive = haveWeather() ? dyn.a : 0.0;
    bool air = haveAir();
    float nearEnd = min(dist, min(SHAFT_NEAR, shadowDistance * 0.95));
    float T = 1.0, dust = 0.0, fog = 0.0, haze = 0.0;
    for (int i = 0; i < steps; i++) {  // a runtime bound: the march is not unrolled (code size, registers)
        // quadratic spacing: more steps in the first blocks (a room, a cave), where shafts are seen up close
        float u0 = float(i) / float(steps), u1 = float(i + 1) / float(steps), um = (float(i) + dither) / float(steps);
        float len = nearEnd * (u1 * u1 - u0 * u0);
        float t = nearEnd * um * um;
        vec3 w = cameraPosition + dir * t;
        vec3 sp = sA + sB * t;
        float vis = 1.0;
        if (max(abs(sp.x), abs(sp.y)) < 1.0) {
            vec3 s = distortShadow(sp) * 0.5 + 0.5;
            vis = texture(shadowtex0, vec3(s.xy, s.z - 0.0002));
        }
        vis *= sunRayVis(sr, t);
        float exposure = shaftExposure(w, air, surface);
        float h = w.y - surface;
        // indoors and underground the pack's air has no sun in it: lit dust adds it. Outdoors the haze and the fog
        // already carry the sun (lib/atmosphere.glsl, lib/media.glsl): the shadowed parts give theirs back
        float sDust = (1.0 - exposure) * SHAFT_DUST_IN;
        float sHaze = exposure * (1.0 / 22000.0) * (1.0 + 3.0 * rain) * exp(-max(w.y - 63.0, 0.0) / 1200.0);
        float sFog = exposure * (fogDensity * exp(-max(h, 0.0) / 14.0) + modGroundFog(drive, h));
        dust += T * sDust * vis * len;
        haze += T * sHaze * (vis - 1.0) * len;
        fog += T * sFog * (vis - 1.0) * len;
        T *= exp(-(sDust + sHaze + sFog) * len);
    }
    // beyond the shadow map: cloud gaps and mountains in the haze and the valley fog (aether's sun map)
    float farEnd = min(dist, SHAFT_FAR);
    if (sr.ok && farEnd > nearEnd + 1.0 && sunShare > 0.0) {
        int farSteps = max(2, steps / 3);
        float ratio = farEnd / max(nearEnd, 1.0);
        float tPrev = nearEnd;
        for (int i = 0; i < farSteps; i++) {
            float tNext = nearEnd * pow(ratio, float(i + 1) / float(farSteps));
            float t = nearEnd * pow(ratio, (float(i) + dither) / float(farSteps));
            float len = tNext - tPrev;
            tPrev = tNext;
            vec3 w = cameraPosition + dir * t;
            float shade = sunRayVis(sr, t) - 1.0;
            float h = w.y - surface;
            float beta = (1.0 / 22000.0) * (1.0 + 3.0 * rain) * exp(-max(w.y - 63.0, 0.0) / 1200.0);
            float sFog = (fogDensity * exp(-max(h, 0.0) / 14.0) + modGroundFog(drive, h)) * shaftExposure(w, false, surface);
            haze += T * beta * shade * len;
            fog += T * sFog * shade * len;
            T *= exp(-(beta + sFog) * len);
        }
    }
    // the haze's sun light: the sky's Mie glow (lib/atmosphere.glsl skyColor: HG 0.76, x0.012 x0.5 x1.6)
    float mie = (1.0 - 0.5776) / pow(max(1.0 + 0.5776 - 1.52 * muSun, 1e-4), 1.5) * 0.0096;
    float k = dust * phaseDust + max(fog * phaseFog, -gMediaSun / keyLum) + haze * SHAFT_HAZE * mie * sunShare;
    return k * qualityFade(q, 0.15);
#else
    return 0.0;
#endif
}

#endif
