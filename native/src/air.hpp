// The air: Infinite ATLAS's one atmosphere (the sky, the sun's colour and strength, aerial perspective and the sky's
// light), the REFERENCE every other copy follows line for line, with the same constants:
//   native/shaders/atlas_air.slang                          the far field (and the near light): marches, LUT lookups
//   mod/src/main/resources/assets/nirncraft/shaders/include/atlas_sky.glsl   the game's sky (AtlasSky), reflections
//   tools/dev/air_check.py                                  evaluates the GLSL's formulas against this (atlas_air_*)
// The model (Bruneton & Neyret 2008, Hillaire 2020): a spherical planet's air in three parts, molecules (Rayleigh,
// lambda^-4: the blue sky, the reddened low sun), aerosols (Mie: a Cornette-Shanks lobe round the sun, the haze that
// whitens the horizon; amount, colour and size from what is in the air) and ozone (absorption: the blue of twilight).
// Single scattering is marched; the higher orders come from Hillaire's isotropic multiple-scattering LUT (32 x 32 over
// altitude and sun zenith, built here when the air changes). Transmittance to the sun is analytic: Schueler's Chapman
// approximation per exponential layer, the ozone a thin shell. Regional variation is what is in the air, never other
// physics: AtlasAir (atlas.h) is the climate's handle (air density, aerosol depth / Angstrom exponent / albedo /
// asymmetry / height, ozone, ground albedo).
// Units: lengths in km of air; the world is a scale model of its air, `airScale` km per block (default 2.5 m: the
// haze strength the look had, LOOK_HAZE 2.5 over physical clear air). Radiance in the look's units: the key light
// keyRad = SUN_IRRADIANCE x T / pi (a white surface's irradiance over pi, as the shading takes it), the sky
// L = SUN_IRRADIANCE x the scattering integral.
#pragma once

#include <algorithm>
#include <cmath>
#include <string>
#include <vector>

namespace air {

struct V3 {
    float x = 0, y = 0, z = 0;
};
inline V3 v3(float s) { return {s, s, s}; }
inline V3 operator+(V3 a, V3 b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
inline V3 operator-(V3 a, V3 b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
inline V3 operator*(V3 a, V3 b) { return {a.x * b.x, a.y * b.y, a.z * b.z}; }
inline V3 operator*(V3 a, float s) { return {a.x * s, a.y * s, a.z * s}; }
inline V3 operator/(V3 a, V3 b) { return {a.x / b.x, a.y / b.y, a.z / b.z}; }
inline V3 vexp(V3 a) { return {std::exp(a.x), std::exp(a.y), std::exp(a.z)}; }
inline V3 vmax(V3 a, float m) { return {std::max(a.x, m), std::max(a.y, m), std::max(a.z, m)}; }
inline float lum(V3 c) { return 0.2126f * c.x + 0.7152f * c.y + 0.0722f * c.z; }
inline float sat(float x) { return std::clamp(x, 0.0f, 1.0f); }
inline float smooth(float e0, float e1, float x) {
    float t = sat((x - e0) / (e1 - e0));
    return t * t * (3 - 2 * t);
}

constexpr float PI = 3.14159265358979f;
// geometry, km
constexpr float R_GROUND = 6360.0f, R_TOP = 6460.0f;
constexpr float H_RAYLEIGH = 8.0f;
constexpr float OZONE_PEAK = 25.0f, OZONE_HALF = 15.0f;  // the layer: a tent 10 - 40 km
// standard sea-level air at 680 / 550 / 440 nm (the channels' wavelengths), per km (Hillaire 2020's Earth)
constexpr V3 RAYLEIGH = {5.802e-3f, 13.558e-3f, 33.1e-3f};
constexpr V3 OZONE = {0.650e-3f, 1.881e-3f, 0.085e-3f};  // at the layer's peak, ~300 DU
constexpr V3 LAMBDA_REL = {680.0f / 550.0f, 1.0f, 440.0f / 550.0f};  // for the aerosols' Angstrom law
constexpr float SUN_HALF_ANGLE = 0.00465f;  // radians: the disc's share over the horizon
// the sun above the air, in the look's units, white-balanced: the noon key light (sun at the zenith, the default
// air, sea level) is (3.2, 3.07, 2.88) = the look's old noon (1, 0.96, 0.9) x 3.2; times pi: an irradiance
constexpr V3 SUN_IRRADIANCE = {11.181f, 11.807f, 12.854f};
// the night's own sky (airglow, starlight): a floor under the moonlit air, radiance
constexpr V3 NIGHT_GLOW = {0.0008f, 0.0011f, 0.0024f};
constexpr float SEA_LEVEL_Y = 63.0f;  // world y of the air's ground
constexpr int MS_SIZE = 32;           // the multiple-scattering LUT: MS_SIZE x MS_SIZE (sun zenith x altitude)
constexpr int SKY_STEPS = 24;         // a sky ray (quadratic spacing)
constexpr int AERIAL_STEPS = 8;       // a path to a surface (even spacing)

// What is in the air (atlas.h AtlasAir; the climate sets it, AtlasAir.java)
struct Params {
    float airDensity = 1.0f;     // molecules: sea-level density over the standard atmosphere's (pressure, temperature)
    float aerosolDepth = 0.0657f;  // aerosol optical depth at 550 nm, the vertical column (fromClimate's clean air)
    float angstrom = 1.242f;     // its Angstrom exponent: 0 grey dust, 0.4 sea salt / mist, 1.3 haze, 1.8 smoke
    V3 aerosolAlbedo = {0.9635f, 0.9635f, 0.9544f};  // single-scattering albedo per channel (dust < 1 in blue: tinted)
    float aerosolG = 0.727f;     // Cornette-Shanks asymmetry: the glow round the sun
    float aerosolHeight = 1.2f;  // aerosol scale height, km
    float ozone = 1.0f;          // ozone column over 300 DU
    float groundAlbedo = 0.2f;   // the ground the sky stands on (multiple scattering, rays below the horizon)
    float airScale = 0.0025f;    // km of air per block
};

// ---- the climate's handle: what is in the air from the weather at the eye (atlas.h AtlasClimate) ------------------
// An external mixture of aerosol components, each a known kind (optical depth at 550 nm when fully present, Angstrom
// exponent, single-scattering albedo per channel, asymmetry, scale height): the region's background by its aerosol
// band (WeatherFieldTexture: water, snow, sand, ash) plus what the weather adds (sea salt by the coast, a dust storm,
// smoke and ash, mist, rain, blowing snow). The water-loving ones (the continental, arctic and sea-salt backgrounds)
// swell with relative humidity (Haenel's growth, gamma 0.5 against 40 %): more, larger (whiter, flatter) and
// brighter particles. Molecules follow pressure and temperature (density ~ p / T). Mixed by optical depth.
struct Climate {
    float temperatureC = 15.0f;
    float relativeHumidity = 0.5f;
    float pressure = 1.0f;                    // sea-level pressure over the standard atmosphere's
    float background[4] = {1, 0, 0, 0};       // the aerosol band's weights: water, snow, sand, ash (eased by the host)
    float dust = 0, ash = 0, snow = 0, rain = 0, fog = 0, coast = 0;  // 0..1 each
};

struct Aerosol {
    float tau, angstrom;
    V3 albedo;
    float g, height;
    bool swells;  // grows with humidity
};
// the components (dry, at 40 % relative humidity)
constexpr Aerosol AEROSOL_CONTINENTAL = {0.06f, 1.3f, {0.96f, 0.96f, 0.95f}, 0.72f, 1.2f, true};
constexpr Aerosol AEROSOL_ARCTIC = {0.03f, 1.1f, {0.97f, 0.97f, 0.97f}, 0.74f, 1.0f, true};
constexpr Aerosol AEROSOL_DESERT = {0.20f, 0.4f, {0.97f, 0.93f, 0.86f}, 0.72f, 2.0f, false};
constexpr Aerosol AEROSOL_VOLCANIC = {0.30f, 1.2f, {0.93f, 0.89f, 0.85f}, 0.70f, 2.5f, false};
constexpr Aerosol AEROSOL_SEA_SALT = {0.08f, 0.3f, {0.995f, 0.995f, 0.995f}, 0.78f, 0.8f, true};
constexpr Aerosol AEROSOL_DUST_STORM = {1.20f, 0.15f, {0.97f, 0.92f, 0.83f}, 0.70f, 2.5f, false};
constexpr Aerosol AEROSOL_SMOKE = {1.50f, 1.7f, {0.90f, 0.86f, 0.82f}, 0.68f, 2.5f, false};
constexpr Aerosol AEROSOL_MIST = {1.20f, 0.1f, {0.998f, 0.998f, 0.998f}, 0.82f, 0.5f, false};
constexpr Aerosol AEROSOL_RAIN = {0.40f, 0.0f, {0.995f, 0.995f, 0.995f}, 0.85f, 1.5f, false};
constexpr Aerosol AEROSOL_SNOW = {0.50f, 0.0f, {0.998f, 0.998f, 0.998f}, 0.80f, 0.6f, false};
// the ground under each band's sky (multiple scattering: a snowfield brightens the sky over it)
constexpr float GROUND_ALBEDO[4] = {0.2f, 0.6f, 0.35f, 0.12f};

inline Params fromClimate(const Climate& k) {
    struct Part {
        const Aerosol* a;
        float w;
    } parts[] = {{&AEROSOL_CONTINENTAL, k.background[0]}, {&AEROSOL_ARCTIC, k.background[1]},
                 {&AEROSOL_DESERT, k.background[2]},      {&AEROSOL_VOLCANIC, k.background[3]},
                 {&AEROSOL_SEA_SALT, k.coast},            {&AEROSOL_DUST_STORM, k.dust},
                 {&AEROSOL_SMOKE, k.ash},                 {&AEROSOL_MIST, k.fog},
                 {&AEROSOL_RAIN, k.rain},                 {&AEROSOL_SNOW, k.snow}};
    float rh = std::clamp(k.relativeHumidity, 0.0f, 0.95f);
    float grow = std::min(std::pow(std::max(1.0f - rh, 0.05f) / 0.6f, -0.5f), 1.5f);  // Haenel: (1 - RH)^-gamma, 1 at 40 %; capped (until weather owns humidity)
    float tau = 0, ang = 0, gw = 0, gs = 0, hs = 0;
    V3 sc = v3(0), ex = v3(0);
    for (const Part& p : parts) {
        float w = std::clamp(p.w, 0.0f, 1.0f);
        if (w <= 0.0f) continue;
        const Aerosol& a = *p.a;
        float t = a.tau * w, al = a.angstrom, g = a.g;
        V3 ssa = a.albedo;
        if (a.swells) {
            t *= grow;
            al /= std::sqrt(grow);
            ssa = v3(1) - (v3(1) - ssa) * (1.0f / grow);
            g += (0.8f - g) * (1.0f - 1.0f / grow);
        }
        V3 tl = {t * std::pow(LAMBDA_REL.x, -al), t, t * std::pow(LAMBDA_REL.z, -al)};
        tau += t;
        ang += t * al;
        ex = ex + tl;
        sc = sc + tl * ssa;
        gw += t * ssa.y * g;
        gs += t * ssa.y;
        hs += t * a.height;
    }
    Params o;
    o.airDensity = std::max(k.pressure, 0.3f) * 288.15f / (273.15f + std::clamp(k.temperatureC, -60.0f, 60.0f));
    o.aerosolDepth = tau;
    o.angstrom = tau > 0 ? ang / tau : 1.3f;
    o.aerosolAlbedo = tau > 0 ? sc / vmax(ex, 1e-9f) : V3{0.97f, 0.97f, 0.97f};
    o.aerosolG = gs > 0 ? gw / gs : 0.76f;
    o.aerosolHeight = tau > 0 ? hs / tau : 1.2f;
    float bw = 0, ga = 0;
    for (int i = 0; i < 4; i++) {
        bw += std::max(k.background[i], 0.0f);
        ga += std::max(k.background[i], 0.0f) * GROUND_ALBEDO[i];
    }
    o.groundAlbedo = bw > 0 ? ga / bw : 0.25f;
    return o;
}

// the evidence's and the tools' named airs (atlas_render air=...): the climate each stands for
inline Climate namedClimate(const char* name) {
    Climate k;
    std::string n = name ? name : "";
    if (n == "humid" || n == "misty") {  // a warm wet morning by the sea: swollen haze, mist
        k.temperatureC = 20, k.relativeHumidity = 0.9f, k.fog = 0.1f, k.coast = 0.6f;
    } else if (n == "dusty") {  // the desert's air in a breeze (a haboob is dust 1)
        k.temperatureC = 32, k.relativeHumidity = 0.2f, k.background[0] = 0, k.background[2] = 1, k.dust = 0.25f;
    } else if (n == "smoke" || n == "ash") {  // the ashlands near a vent
        k.temperatureC = 25, k.relativeHumidity = 0.35f, k.background[0] = 0, k.background[3] = 1, k.ash = 0.5f;
    } else if (n == "snow") {  // a cold clear day over snow
        k.temperatureC = -10, k.relativeHumidity = 0.6f, k.background[0] = 0, k.background[1] = 1;
    } else if (n == "salt" || n == "coast") {  // a breezy shore
        k.relativeHumidity = 0.75f, k.coast = 1.0f;
    }
    return k;  // "clean": temperate continental air at 50 %
}

// Per-km coefficients at sea level (the ozone's at its peak), what the marches read (FrameGpu air*)
struct Coeffs {
    V3 rayleigh, mieScat, mieExt, ozone;
    float invHMie = 1, g = 0.76f, groundAlbedo = 0.25f, airScale = 0.0025f;
};

inline Coeffs coeffs(const Params& p) {
    Coeffs c;
    c.rayleigh = RAYLEIGH * std::max(p.airDensity, 0.0f);
    float hm = std::clamp(p.aerosolHeight, 0.2f, 8.0f);
    float b = std::max(p.aerosolDepth, 0.0f) / hm;
    c.mieExt = {b * std::pow(LAMBDA_REL.x, -p.angstrom), b, b * std::pow(LAMBDA_REL.z, -p.angstrom)};
    V3 a = {sat(p.aerosolAlbedo.x), sat(p.aerosolAlbedo.y), sat(p.aerosolAlbedo.z)};
    c.mieScat = c.mieExt * a;
    c.ozone = OZONE * std::max(p.ozone, 0.0f);
    c.invHMie = 1.0f / hm;
    c.g = std::clamp(p.aerosolG, 0.0f, 0.95f);
    c.groundAlbedo = sat(p.groundAlbedo);
    c.airScale = std::clamp(p.airScale, 1e-5f, 0.1f);
    return c;
}

// ---- the shared functions (ported as they are) ---------------------------------------------------------------------

// exp(y^2) erfc(y) for y >= 0 (Abramowitz & Stegun 7.1.13's form, its constant fitted: 1.3 % at most)
inline float erfcx(float y) { return 1.1283792f / (y + std::sqrt(y * y + 0.393f * y + 1.2732395f)); }

// The Chapman function times exp(-h): the optical depth to space of an exponential layer of unit density at the
// ground, in scale heights, from altitude h (scale heights) at zenith cosine mu; X = the planet's radius in scale
// heights. Above the horizontal the large-X form sqrt(pi x / 2) erfcx(sqrt(x / 2) mu) (x = X + h: 0.1 % against
// the integral); below it the identity through the tangent point (Schueler, GPU Pro 3).
inline float chapman(float X, float h, float mu) {
    float x = X + h;
    float up = std::sqrt(1.5707963f * x) * erfcx(std::sqrt(0.5f * x) * std::fabs(mu)) * std::exp(-h);
    if (mu >= 0.0f) return up;
    float x0 = std::sqrt(std::max(1.0f - mu * mu, 0.0f)) * x;
    return 2.0f * std::sqrt(1.5707963f * x0) * std::exp(X - x0) - up;
}

// the share of the ozone column above altitude h (the tent 10 - 40 km)
inline float ozoneAbove(float h) {
    float u = sat((h - (OZONE_PEAK - OZONE_HALF)) / (2.0f * OZONE_HALF));  // 0 at 10 km, 1 at 40 km
    return u < 0.5f ? 1.0f - 2.0f * u * u : 2.0f * (1.0f - u) * (1.0f - u);
}

// the cosine of the planet's horizon seen from radius r (rays below it meet the ground)
inline float horizonMu(float r) { return -std::sqrt(std::max(1.0f - (R_GROUND / r) * (R_GROUND / r), 0.0f)); }

// optical depth from radius r towards zenith cosine mu, to space (clamped at the horizon: grazing the ground)
inline V3 depthToSpace(const Coeffs& c, float r, float mu) {
    float h = std::max(r - R_GROUND, 0.0f);
    mu = std::max(mu, horizonMu(r));
    float hm = 1.0f / c.invHMie;
    float dR = H_RAYLEIGH * chapman(R_GROUND / H_RAYLEIGH, h / H_RAYLEIGH, mu);
    float dM = hm * chapman(R_GROUND * c.invHMie, h * c.invHMie, mu);
    // the ozone as a thin shell at its peak: the column above h times the slant through the shell
    float s = std::min(r / (R_GROUND + OZONE_PEAK), 1.0f);
    float slant = 1.0f / std::sqrt(std::max(1.0f - s * s * (1.0f - mu * mu), 1e-4f));
    float dO = 2.0f * OZONE_HALF * 0.5f * ozoneAbove(h) * slant;  // the tent's area: OZONE_HALF km of peak density
    return c.rayleigh * dR + c.mieExt * dM + c.ozone * dO;
}

// The sun's (or moon's) light reaching radius r with zenith cosine mu: the transmittance to space times the share of
// the disc over the planet's horizon
inline V3 lightTransmittance(const Coeffs& c, float r, float mu) {
    float vis = smooth(-SUN_HALF_ANGLE, SUN_HALF_ANGLE, mu - horizonMu(r));
    if (vis <= 0.0f) return v3(0.0f);
    return vexp(depthToSpace(c, r, mu) * -1.0f) * vis;
}

inline float phaseRayleigh(float nu) { return 3.0f / (16.0f * PI) * (1.0f + nu * nu); }
inline float phaseMie(float nu, float g) {
    float g2 = g * g;
    return 3.0f / (8.0f * PI) * (1.0f - g2) * (1.0f + nu * nu)
         / ((2.0f + g2) * std::pow(std::max(1.0f + g2 - 2.0f * g * nu, 1e-4f), 1.5f));
}

// densities at altitude h: molecules, aerosols, ozone
inline void densities(const Coeffs& c, float h, float& dR, float& dM, float& dO) {
    dR = std::exp(-h / H_RAYLEIGH);
    dM = std::exp(-h * c.invHMie);
    dO = std::max(0.0f, 1.0f - std::fabs(h - OZONE_PEAK) / OZONE_HALF);
}

// distance along zenith cosine mu from radius r to the top of the air, and to the ground (-1: misses it)
inline float distanceToTop(float r, float mu) {
    float d = r * r * (mu * mu - 1.0f) + R_TOP * R_TOP;
    return std::max(-r * mu + std::sqrt(std::max(d, 0.0f)), 0.0f);
}
inline float distanceToGround(float r, float mu) {
    float d = r * r * (mu * mu - 1.0f) + R_GROUND * R_GROUND;
    if (mu >= 0.0f || d < 0.0f) return -1.0f;
    return std::max(-r * mu - std::sqrt(d), 0.0f);
}

// The multiple-scattering LUT (Hillaire 2020, Psi_ms per unit irradiance): u = sun zenith cosine * 0.5 + 0.5,
// v = sqrt(altitude / (R_TOP - R_GROUND)) (texels gathered low, where the aerosols are). Texel centres, bilinear.
struct MsLut {
    std::vector<V3> t = std::vector<V3>(MS_SIZE * MS_SIZE);
};
inline V3 msLookup(const MsLut& lut, float h, float muS) {
    float u = sat(muS * 0.5f + 0.5f) * (MS_SIZE - 1);
    float v = std::sqrt(sat(h / (R_TOP - R_GROUND))) * (MS_SIZE - 1);
    int x0 = std::min(int(u), MS_SIZE - 2), y0 = std::min(int(v), MS_SIZE - 2);
    float fx = u - x0, fy = v - y0;
    auto at = [&](int x, int y) { return lut.t[y * MS_SIZE + x]; };
    V3 a = at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx, b = at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx;
    return a * (1 - fy) + b * fy;
}

// One texel of the LUT: 64 directions over the sphere, 20 steps each (Hillaire's numbers), isotropic phase
inline V3 msTexel(const Coeffs& c, int i, int j) {
    float muS = (float(i) / (MS_SIZE - 1)) * 2.0f - 1.0f;
    float vv = float(j) / (MS_SIZE - 1);
    float h = std::max(vv * vv * (R_TOP - R_GROUND), 0.001f);
    float r = R_GROUND + h;
    const int SQ = 8, STEPS = 20;
    V3 L2 = v3(0), F = v3(0);
    for (int a = 0; a < SQ; a++)
        for (int b = 0; b < SQ; b++) {
            float mu = 1.0f - 2.0f * (float(a) + 0.5f) / SQ;  // uniform over the sphere
            float phi = 2.0f * PI * (float(b) + 0.5f) / SQ;
            float st = std::sqrt(std::max(1.0f - mu * mu, 0.0f));
            // the sun in the x-y plane: nu = dir . sun
            float sunX = std::sqrt(std::max(1.0f - muS * muS, 0.0f));
            float nu = st * std::cos(phi) * sunX + mu * muS;
            float tg = distanceToGround(r, mu);
            float tEnd = tg >= 0.0f ? tg : distanceToTop(r, mu);
            float dt = tEnd / STEPS;
            V3 T = v3(1), L = v3(0), f = v3(0);
            for (int s = 0; s < STEPS; s++) {
                float t = (float(s) + 0.5f) * dt;
                float rt = std::sqrt(r * r + 2.0f * r * mu * t + t * t);
                float ht = rt - R_GROUND;
                float muSt = (r * muS + t * nu) / rt;
                float dR, dM, dO;
                densities(c, ht, dR, dM, dO);
                V3 scat = c.rayleigh * dR + c.mieScat * dM;
                V3 ext = c.rayleigh * dR + c.mieExt * dM + c.ozone * dO;
                V3 stepT = vexp(ext * -dt);
                V3 w = (v3(1) - stepT) / vmax(ext, 1e-7f);  // the step's integral of exp(-ext s)
                V3 sunT = lightTransmittance(c, rt, muSt);
                L = L + T * scat * sunT * w * (1.0f / (4.0f * PI));
                f = f + T * scat * w;
                T = T * stepT;
            }
            if (tg >= 0.0f) {  // the ground: lit, Lambertian
                float muG = (r * muS + tg * nu) / R_GROUND;
                L = L + T * lightTransmittance(c, R_GROUND, muG) * (c.groundAlbedo / PI * std::max(muG, 0.0f));
            }
            L2 = L2 + L;
            F = F + f;
        }
    // the second order (mean over the sphere x 4 pi x the isotropic phase) and the transfer fraction f_ms likewise;
    // all orders: L2 / (1 - f_ms)
    float n = 1.0f / float(SQ * SQ);
    L2 = L2 * n;
    F = F * n;
    return L2 / (v3(1) - V3{std::min(F.x, 0.99f), std::min(F.y, 0.99f), std::min(F.z, 0.99f)});
}

// The two lights of the air: the sun, and the moon (irradiance 0: none; the host fades it out by day)
struct Lights {
    float sun[3] = {0, 1, 0};
    V3 sunE;
    float moon[3] = {0, 1, 0};
    V3 moonE;
};

// Light scattered towards the eye along a ray from radius r (zenith cosine mu; nuS, nuM its cosines to the sun and
// moon, muS, muM their zenith cosines at the eye) over [0, tEnd] km in `steps` steps (quadratic spacing for sky rays,
// even for paths to a surface): inscatter and transmittance. ground: the ray ends on the planet (its lit ground added).
struct Path {
    V3 inscatter, transmittance;
};
inline Path integrate(const Coeffs& c, const MsLut& lut, float r, float mu, float nuS, float muS, V3 ES, float nuM,
                      float muM, V3 EM, float tEnd, int steps, bool quadratic, bool ground) {
    bool moon = EM.x + EM.y + EM.z > 0.0f;
    float pRS = phaseRayleigh(nuS), pMS = phaseMie(nuS, c.g), pRM = phaseRayleigh(nuM), pMM = phaseMie(nuM, c.g);
    V3 T = v3(1), L = v3(0);
    float prev = 0.0f;
    for (int s = 0; s < steps; s++) {
        float a = float(s + 1) / float(steps), m = (float(s) + 0.5f) / float(steps);
        float t1 = quadratic ? tEnd * a * a : tEnd * a;
        float t = quadratic ? tEnd * m * m : tEnd * m;
        float dt = t1 - prev;
        prev = t1;
        float rt = std::sqrt(std::max(r * r + 2.0f * r * mu * t + t * t, 1.0f));
        float ht = std::max(rt - R_GROUND, 0.0f);
        float dR, dM, dO;
        densities(c, ht, dR, dM, dO);
        V3 sR = c.rayleigh * dR, sM = c.mieScat * dM;
        V3 ext = sR + c.mieExt * dM + c.ozone * dO;
        V3 stepT = vexp(ext * -dt);
        V3 w = (v3(1) - stepT) / vmax(ext, 1e-7f);  // the step's integral of exp(-ext s): stable in thick air
        float muSt = (r * muS + t * nuS) / rt;
        V3 S = ((sR * pRS + sM * pMS) * lightTransmittance(c, rt, muSt) + (sR + sM) * msLookup(lut, ht, muSt)) * ES;
        if (moon) {
            float muMt = (r * muM + t * nuM) / rt;
            S = S + ((sR * pRM + sM * pMM) * lightTransmittance(c, rt, muMt) + (sR + sM) * msLookup(lut, ht, muMt)) * EM;
        }
        L = L + T * S * w;
        T = T * stepT;
    }
    if (ground) {
        float rg = std::sqrt(std::max(r * r + 2.0f * r * mu * tEnd + tEnd * tEnd, 1.0f));
        float muG = (r * muS + tEnd * nuS) / rg;
        V3 lit = lightTransmittance(c, rg, muG) * ES * std::max(muG, 0.0f);
        if (moon) {
            float muGM = (r * muM + tEnd * nuM) / rg;
            lit = lit + lightTransmittance(c, rg, muGM) * EM * std::max(muGM, 0.0f);
        }
        L = L + T * lit * (c.groundAlbedo / PI);
    }
    return {L, T};
}

inline float dot3(const float a[3], const float b[3]) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

// The sky's radiance along unit direction dir (y up) seen from altitude h (km): both lights, the night's glow
inline V3 skyRadiance(const Coeffs& c, const MsLut& lut, float h, const float dir[3], const Lights& l) {
    float r = R_GROUND + std::max(h, 0.0f);
    float mu = dir[1];
    float tg = distanceToGround(r, mu);
    float tEnd = tg >= 0.0f ? tg : distanceToTop(r, mu);
    return integrate(c, lut, r, mu, dot3(dir, l.sun), l.sun[1], l.sunE, dot3(dir, l.moon), l.moon[1], l.moonE, tEnd,
                     SKY_STEPS, true, tg >= 0.0f).inscatter + NIGHT_GLOW;
}

// The air between the eye (altitude h, km) and a surface tKm away along dir: inscatter (the night's glow through it
// too) and transmittance; the path stops at the top of the air
inline Path aerialPath(const Coeffs& c, const MsLut& lut, float h, const float dir[3], float tKm, const Lights& l) {
    float r = R_GROUND + std::max(h, 0.0f);
    float mu = dir[1];
    float tEnd = std::min(tKm, distanceToTop(r, mu));
    Path p = integrate(c, lut, r, mu, dot3(dir, l.sun), l.sun[1], l.sunE, dot3(dir, l.moon), l.moon[1], l.moonE, tEnd,
                       AERIAL_STEPS, false, false);
    p.inscatter = p.inscatter + NIGHT_GLOW * (v3(1) - p.transmittance);
    return p;
}

// The sky's light on surfaces at altitude h (the look's skyAmbient: up = the irradiance of an upward face over pi,
// ground = the ground's radiance under the horizon, lit by that sky; the sunlit ground's bounce is the look's own,
// LOOK_BOUNCE): a cosine-weighted average of the sky over the upper hemisphere, 6 rings x 12 bearings
inline void skyLight(const Coeffs& c, const MsLut& lut, float h, const Lights& l, V3& up, V3& ground) {
    const int RINGS = 6, BEARINGS = 12;
    V3 sum = v3(0);
    for (int i = 0; i < RINGS; i++) {
        float mu = std::sqrt((float(i) + 0.5f) / RINGS);  // cosine-weighted rings
        float st = std::sqrt(std::max(1.0f - mu * mu, 0.0f));
        for (int k = 0; k < BEARINGS; k++) {
            float phi = 2.0f * PI * (float(k) + 0.5f) / BEARINGS;
            float d[3] = {st * std::cos(phi), mu, st * std::sin(phi)};
            sum = sum + skyRadiance(c, lut, h, d, l);
        }
    }
    up = sum * (1.0f / float(RINGS * BEARINGS));
    ground = up * c.groundAlbedo;
}

// ---- the host side -------------------------------------------------------------------------------------------------

// all MS_SIZE^2 texels; `run(n, f)` runs f over [0, n) (a parallel loop where the caller has one)
template <typename Run>
inline void buildMultiScatter(const Coeffs& c, MsLut& lut, Run&& run) {
    run(MS_SIZE * MS_SIZE, [&](int k) { lut.t[k] = msTexel(c, k % MS_SIZE, k / MS_SIZE); });
}

// the air's height above sea level for a world y (km)
inline float altitudeOf(const Coeffs& c, float worldY) { return std::max(worldY - SEA_LEVEL_Y, 0.0f) * c.airScale; }

// The lights for a frame: the sun's irradiance above the air, and the moon's (the host's moon radiance, a key light,
// taken as above the air: E = pi x radiance), faded out while the sun is up (it adds nothing there but cost)
inline Lights lightsOf(const float sun[3], const float moon[3], const float moonRad[3]) {
    Lights l;
    for (int i = 0; i < 3; i++) l.sun[i] = sun[i];
    l.sunE = SUN_IRRADIANCE;
    if (moon && moonRad) {
        for (int i = 0; i < 3; i++) l.moon[i] = moon[i];
        l.moonE = V3{moonRad[0], moonRad[1], moonRad[2]} * (PI * smooth(0.1f, 0.0f, sun[1]));
    }
    return l;
}

// The key light (atlas_air.slang keyLightRadiance): the sun's transmitted light while it is up, else the moon's,
// faded in as the sun goes down (the shading's switch at sun.y -0.05); at world height worldY
inline void keyLight(const Coeffs& c, float worldY, const float sun[3], const float moon[3], const float moonRad[3],
                     float dir[3], V3& rad) {
    float r = R_GROUND + altitudeOf(c, worldY);
    if (sun[1] > -0.05f) {
        for (int i = 0; i < 3; i++) dir[i] = sun[i];
        rad = lightTransmittance(c, r, sun[1]) * SUN_IRRADIANCE * (1.0f / PI);
        return;
    }
    for (int i = 0; i < 3; i++) dir[i] = moon[i];
    rad = lightTransmittance(c, r, moon[1]) * V3{moonRad[0], moonRad[1], moonRad[2]} * smooth(-0.05f, -0.2f, sun[1]);
}

}  // namespace air
