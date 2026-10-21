// pack_look: the game's light and look in C++, for offline references and tools (atlas_truth lighting=realistic,
// atlas_render shade=pack). The light is the air's (native/src/air.hpp, the clean default air at sea level: what
// AtlasLook hands the near pass); the display is the InfiniteATLAS pack's (shaderpack/InfiniteATLAS/shaders):
//   lib/lighting.glsl    blockLight's warm colour (the hue of a light without the mod's coloured cache)
//   lib/exposure.glsl    the eye's exposure curve (EXPOSURE_KEY, _SLOPE, _MIN, _MAX; AtlasLook's)
//   lib/grade.glsl       nightVision (NIGHT_VISION 2: strength 0.7)
//   final.fsh            exposure x EXPOSURE, the ACES fit (Hill), linear -> sRGB
// (The pack's own lib/atmosphere.glsl keeps the old analytic sky: the Iris path, retired at R4.)
// Not mirrored: the regional grade (a LUT per land), bloom and shafts (composite1), fog (element views are fog-free).
// The sun's path: Minecraft's sky angle (DimensionType.timeOfDay) turned as Iris turns it (CelestialUniforms: Y -90,
// Z sunPathRotation, X angle x 360), sunPathRotation the pack's default -25.
#pragma once

#include <algorithm>
#include <cmath>
#include <thread>
#include <vector>

#include "air.hpp"

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

// the default air and its multiple-scattering LUT, built once (all threads)
inline const air::Coeffs& airC() {
    static const air::Coeffs c = air::coeffs(air::fromClimate(air::Climate{}));
    return c;
}
inline const air::MsLut& airLut() {
    static const air::MsLut lut = [] {
        air::MsLut l;
        air::buildMultiScatter(airC(), l, [](int n, auto f) {
            std::vector<std::thread> th;
            unsigned k = std::max(1u, std::thread::hardware_concurrency());
            for (unsigned i = 0; i < k; i++)
                th.emplace_back([&, i] {
                    for (int j = int(i); j < n; j += int(k)) f(j);
                });
            for (auto& t : th) t.join();
        });
        return l;
    }();
    return lut;
}
inline C3 c3(air::V3 v) { return {v.x, v.y, v.z}; }
// the vanilla moon opposite the sun (no mod sky offline), a real full moon (the core's fallback, api.cpp moonOf)
inline void moonOf(const float sun[3], float dir[3], float rad[3]) {
    const float m[3] = {8.7e-6f, 1.25e-5f, 2.2e-5f};
    for (int i = 0; i < 3; i++) dir[i] = -sun[i], rad[i] = m[i];
}

// the sun's light at sea level (the air's transmittance)
inline C3 sunRadiance(const float s[3]) {
    return c3(air::lightTransmittance(airC(), air::R_GROUND, s[1]) * air::SUN_IRRADIANCE * (1.0f / air::PI));
}

inline C3 skyColor(const float dir[3], const float sun[3]) {
    float md[3], mr[3];
    moonOf(sun, md, mr);
    return c3(air::skyRadiance(airC(), airLut(), 0.0f, dir, air::lightsOf(sun, md, mr)));
}

// skyAmbient(n) = mix(ground, skyUp, n.y * 0.5 + 0.5): the irradiance (over pi) of the air's sky over the horizon
// (skyUp) and the ground under it: what a hemisphere-visibility integral reproduces
inline void skyAmbientParts(const float sun[3], C3& skyUp, C3& ground) {
    float md[3], mr[3];
    moonOf(sun, md, mr);
    air::V3 u, g;
    air::skyLight(airC(), airLut(), 0.0f, air::lightsOf(sun, md, mr), u, g);
    skyUp = c3(u), ground = c3(g);
}

// the key light: the sun through the air, or the vanilla moon opposite it
inline void keyLight(const float sun[3], float dir[3], C3& rad) {
    float md[3], mr[3];
    moonOf(sun, md, mr);
    air::V3 r;
    air::keyLight(airC(), air::SEA_LEVEL_Y, sun, md, mr, dir, r);
    rad = c3(r);
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
