// pack_look: the InfiniteATLAS shader pack's light and look in C++, for offline references and tools (atlas_truth
// lighting=realistic, atlas_render shade=pack). A mirror of shaderpack/InfiniteATLAS/shaders:
//   lib/atmosphere.glsl  sunRadiance, skyColor, skyAmbient (themselves a port of native/shaders/atlas_shared.slang)
//   lib/keylight.glsl    the key light: the sun by day, the vanilla moon opposite it by night (no mod sky offline)
//   lib/lighting.glsl    blockLight's warm colour (the hue of a light without the mod's coloured cache)
//   lib/exposure.glsl    the eye's exposure curve (EXPOSURE_KEY, _SLOPE, _MIN, _MAX)
//   lib/grade.glsl       nightVision (NIGHT_VISION 2: strength 0.7)
//   final.fsh            exposure x EXPOSURE, the ACES fit (Hill), linear -> sRGB
// Not mirrored: the regional grade (a LUT per land), bloom and shafts (composite1), fog (element views are fog-free).
// The sun's path: Minecraft's sky angle (DimensionType.timeOfDay) turned as Iris turns it (CelestialUniforms: Y -90,
// Z sunPathRotation, X angle x 360), sunPathRotation the pack's default -25.
#pragma once

#include <algorithm>
#include <cmath>

namespace packlook {

struct C3 {
    float r = 0, g = 0, b = 0;
};
inline C3 operator+(C3 a, C3 b) { return {a.r + b.r, a.g + b.g, a.b + b.b}; }
inline C3 operator*(C3 a, float s) { return {a.r * s, a.g * s, a.b * s}; }
inline C3 operator*(C3 a, C3 b) { return {a.r * b.r, a.g * b.g, a.b * b.b}; }
inline C3 mix(C3 a, C3 b, float t) { return {a.r + (b.r - a.r) * t, a.g + (b.g - a.g) * t, a.b + (b.b - a.b) * t}; }
inline float lum(C3 c) { return 0.2126f * c.r + 0.7152f * c.g + 0.0722f * c.b; }
inline float sat(float x) { return std::clamp(x, 0.f, 1.f); }
// GLSL smoothstep, also with edge0 > edge1 (the pack writes the night fades that way)
inline float smooth(float e0, float e1, float x) {
    float t = sat((x - e0) / (e1 - e0));
    return t * t * (3 - 2 * t);
}

constexpr float PI = 3.14159265358979f;

// Minecraft's sky angle (0 at noon, 0.25 sunset, 0.5 midnight, 0.75 sunrise) for a day time in ticks
inline float skyAngle(float ticks) {
    double f = std::fmod(double(ticks) / 24000.0 - 0.25, 1.0);
    if (f < 0) f += 1;
    double f1 = 0.5 - std::cos(f * 3.14159265358979) / 2.0;
    return float((f * 2.0 + f1) / 3.0);
}

// world direction towards the sun (x east, y up, z south), Iris' celestial transform with sunPathRotation (degrees)
inline void sunDirAt(float ticks, float pathRotation, float out[3]) {
    float th = skyAngle(ticks) * 2 * PI, ph = pathRotation * PI / 180;
    out[0] = -std::sin(th);
    out[1] = std::cos(th) * std::cos(ph);
    out[2] = -std::cos(th) * std::sin(ph);
}

inline C3 sunRadiance(const float s[3]) {
    float e = s[1], a = sat(e * 3);
    return mix(C3{1.f, 0.62f, 0.38f}, C3{1.f, 0.96f, 0.9f}, a) * (3.2f * smooth(-0.04f, 0.12f, e));
}

inline C3 skyColor(const float dir[3], const float sun[3]) {
    float mu = dir[0] * sun[0] + dir[1] * sun[1] + dir[2] * sun[2], up = dir[1];
    float day = smooth(-0.12f, 0.25f, sun[1]);
    C3 zenith = mix({0.008f, 0.012f, 0.03f}, {0.16f, 0.34f, 0.78f}, day);
    C3 horizon = mix({0.03f, 0.035f, 0.06f}, {0.62f, 0.75f, 0.92f}, day);
    float dusk = (1 - smooth(0.f, 0.3f, std::fabs(sun[1]))) * smooth(-0.2f, 0.9f, mu);
    horizon = mix(horizon, {1.f, 0.5f, 0.25f}, dusk * 0.7f);
    float hz = std::pow(1 - sat(std::fabs(up)), 5.f);
    C3 c = mix(zenith, horizon, hz);
    if (up < 0) c = mix(c, horizon * 0.55f, sat(-up * 6));
    float g = 0.76f;
    float mie = (1 - g * g) / std::pow(std::max(1 + g * g - 2 * g * mu, 1e-4f), 1.5f) * 0.012f;
    c = c + sunRadiance(sun) * (mie * 0.5f);
    return c * 1.6f;
}

// skyAmbient(n) = mix(ground, skyUp, n.y * 0.5 + 0.5): the irradiance (over pi) of a sky of radiance skyUp over the
// horizon and a ground of radiance `ground` under it, exactly: what a hemisphere-visibility integral reproduces
inline void skyAmbientParts(const float sun[3], C3& skyUp, C3& ground) {
    float day = smooth(-0.12f, 0.25f, sun[1]);
    skyUp = mix({0.02f, 0.025f, 0.05f}, {0.33f, 0.45f, 0.7f}, day);
    ground = mix({0.01f, 0.01f, 0.012f}, {0.2f, 0.18f, 0.14f}, day);
}

// the key light (lib/keylight.glsl without the mod's sky): the sun, or the vanilla moon opposite it
inline void keyLight(const float sun[3], float dir[3], C3& rad) {
    if (sun[1] > -0.05f) {
        for (int i = 0; i < 3; i++) dir[i] = sun[i];
        rad = sunRadiance(sun);
        return;
    }
    for (int i = 0; i < 3; i++) dir[i] = -sun[i];
    rad = C3{0.035f, 0.05f, 0.09f} * smooth(-0.05f, -0.2f, sun[1]);
}

// lib/lighting.glsl blockLight's hue (linear), the pack's ambient floor (deferred.fsh)
inline C3 blockLightHue() { return {1.f, 0.62f, 0.32f}; }
inline C3 ambientFloor() { return {0.012f, 0.012f, 0.016f}; }

// lib/exposure.glsl
constexpr float EXPOSURE_KEY = -0.79f, EXPOSURE_SLOPE = 0.72f, EXPOSURE_MIN = -1.6f, EXPOSURE_MAX = 2.3f;
inline float exposureTarget(float logL) { return std::clamp(EXPOSURE_KEY - EXPOSURE_SLOPE * logL, EXPOSURE_MIN, EXPOSURE_MAX); }

// The eye adapted to a canonical scene of the time of day (offline there is no landscape around an element to meter):
// mid grey (albedo 0.18) under the open sky, lit by the key light and the sky. Noon gives log2 -0.23 (x0.85; the pack
// noted noon land at x0.8), dusk opens up, night hits EXPOSURE_MAX.
inline float canonicalExposure(const float sun[3]) {
    float kd[3];
    C3 kr, up, gr;
    keyLight(sun, kd, kr);
    skyAmbientParts(sun, up, gr);
    float L = 0.18f * (lum(kr) * std::max(kd[1], 0.f) + lum(up));
    return exposureTarget(std::log2(std::max(L, 1e-5f)));
}

// lib/grade.glsl nightVision
inline C3 nightVision(C3 c, float strength) {
    float l = lum(c);
    float m = sat((std::log2(0.08f) - std::log2(std::max(l, 1e-6f))) / 3.6f) * strength;
    float rod = 0.04f * c.r + 0.5f * c.g + 0.46f * c.b;
    return mix(c, C3{0.6f, 0.8f, 1.2f} * (rod / 0.787f), m);
}

// final.fsh: aces(), Stephen Hill's fit (column-major GLSL matrices written out row by row here)
inline C3 aces(C3 c) {
    C3 i{0.59719f * c.r + 0.35458f * c.g + 0.04823f * c.b, 0.07600f * c.r + 0.90834f * c.g + 0.01566f * c.b,
         0.02840f * c.r + 0.13383f * c.g + 0.83777f * c.b};
    auto rrt = [](float v) { return (v * (v + 0.0245786f) - 0.000090537f) / (v * (0.983729f * v + 0.4329510f) + 0.238081f); };
    C3 r{rrt(i.r), rrt(i.g), rrt(i.b)};
    return {sat(1.60475f * r.r - 0.53108f * r.g - 0.07367f * r.b), sat(-0.10208f * r.r + 1.10813f * r.g - 0.00605f * r.b),
            sat(-0.00327f * r.r - 0.07276f * r.g + 1.07602f * r.b)};
}

inline float linearToSrgb(float v) { return v <= 0.0031308f ? v * 12.92f : 1.055f * std::pow(v, 1.f / 2.4f) - 0.055f; }

struct Look {
    float exposure = 0;        // log2 (the eye) ...
    float exposureOption = 1;  // ... times the EXPOSURE option
    float nightVision = 0.7f;  // NIGHT_VISION 2 x 0.35
};

// HDR scene radiance -> display sRGB 0..1, as final.fsh does it (without the regional grade)
inline C3 display(C3 hdr, const Look& k) {
    if (k.nightVision > 0) hdr = nightVision(hdr, k.nightVision);
    C3 c = aces(hdr * (std::exp2(k.exposure) * k.exposureOption));
    return {linearToSrgb(c.r), linearToSrgb(c.g), linearToSrgb(c.b)};
}

// the flat background the element tier draws behind an element (offline frames have no sky dome): the pack's sky 20
// degrees over the horizon, a quarter turn from the sun's bearing
inline C3 backgroundSky(const float sun[3]) {
    float h = std::sqrt(sun[0] * sun[0] + sun[2] * sun[2]);
    float bx = h > 1e-4f ? -sun[2] / h : 1.f, bz = h > 1e-4f ? sun[0] / h : 0.f;
    float e = 20 * PI / 180;
    float dir[3] = {bx * std::cos(e), std::sin(e), bz * std::cos(e)};
    return skyColor(dir, sun);
}

}  // namespace packlook
