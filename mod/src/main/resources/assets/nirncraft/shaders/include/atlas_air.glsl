#ifndef NIRNCRAFT_ATLAS_AIR_GLSL
#define NIRNCRAFT_ATLAS_AIR_GLSL
// The air (the sky and the sun through it): a port of native/src/air.hpp, the reference, function for function with
// the same constants (native/shaders/atlas_air.slang is the far field's copy, so the sky behind everything and the far
// field's haze are one model). Checked offline: atlas_pack_test compiles this file on GL and compares its sky with
// atlas's own; tools/dev/air_check.py evaluates its formulas against atlas_air_sky. AtlasSky fills AirInfo from
// atlas_air_coeffs and AirMs from atlas_air_multiscatter (com.nirncraft.atlas.AtlasAir) and marches it into the
// sky-view LUT (atlas_sky_view.fsh) once a frame.
layout(std140) uniform AirInfo {
    vec4 AirRayleigh;  // molecules' scattering per km at sea level; w = 1 / aerosol scale height (per km)
    vec4 AirMieScat;   // aerosols' scattering; w = their asymmetry g
    vec4 AirMieExt;    // aerosols' extinction; w = km of air per block
    vec4 AirOzone;     // ozone's absorption at its peak; w = the ground's albedo
    vec4 AirSun;       // xyz towards the sun; w = the eye's altitude, km
    vec4 AirMoon;      // xyz towards the moon; w the exposure the sky-view LUT is written with (half precision)
    vec4 AirMoonE;     // the moon's irradiance above the air (0 by day)
};
uniform sampler2D AirMs;  // the multiple-scattering LUT, 32 x 32 (u: sun zenith cosine, v: sqrt(altitude / 100 km))

const float AIR_PI = 3.14159265;
const float R_GROUND = 6360.0;
const float R_TOP = 6460.0;
const float H_RAYLEIGH = 8.0;
const float OZONE_PEAK = 25.0;
const float OZONE_HALF = 15.0;
const float SUN_HALF_ANGLE = 0.00465;
const vec3 SUN_IRRADIANCE = vec3(11.181, 11.807, 12.854);
const vec3 NIGHT_GLOW = vec3(2.0e-8, 2.6e-8, 5.0e-8);
const int MS_SIZE = 32;
const int SKY_STEPS = 24;

float airErfcx(float y) { return 1.1283792 / (y + sqrt(y * y + 0.393 * y + 1.2732395)); }

float airChapman(float X, float h, float mu) {
    float x = X + h;
    float up = sqrt(1.5707963 * x) * airErfcx(sqrt(0.5 * x) * abs(mu)) * exp(-h);
    if (mu >= 0.0) return up;
    float x0 = sqrt(max(1.0 - mu * mu, 0.0)) * x;
    return 2.0 * sqrt(1.5707963 * x0) * exp(X - x0) - up;
}

float airOzoneAbove(float h) {
    float u = clamp((h - (OZONE_PEAK - OZONE_HALF)) / (2.0 * OZONE_HALF), 0.0, 1.0);
    return u < 0.5 ? 1.0 - 2.0 * u * u : 2.0 * (1.0 - u) * (1.0 - u);
}

float airHorizonMu(float r) { return -sqrt(max(1.0 - (R_GROUND / r) * (R_GROUND / r), 0.0)); }

vec3 airDepthToSpace(float r, float mu) {
    float h = max(r - R_GROUND, 0.0);
    mu = max(mu, airHorizonMu(r));
    float invHM = AirRayleigh.w;
    float dR = H_RAYLEIGH * airChapman(R_GROUND / H_RAYLEIGH, h / H_RAYLEIGH, mu);
    float dM = airChapman(R_GROUND * invHM, h * invHM, mu) / invHM;
    float s = min(r / (R_GROUND + OZONE_PEAK), 1.0);
    float slant = 1.0 / sqrt(max(1.0 - s * s * (1.0 - mu * mu), 1e-4));
    float dO = 2.0 * OZONE_HALF * 0.5 * airOzoneAbove(h) * slant;
    return AirRayleigh.xyz * dR + AirMieExt.xyz * dM + AirOzone.xyz * dO;
}

vec3 airLightTransmittance(float r, float mu) {
    float vis = smoothstep(-SUN_HALF_ANGLE, SUN_HALF_ANGLE, mu - airHorizonMu(r));
    if (vis <= 0.0) return vec3(0.0);
    return exp(-airDepthToSpace(r, mu)) * vis;
}

float airPhaseRayleigh(float nu) { return 3.0 / (16.0 * AIR_PI) * (1.0 + nu * nu); }
float airPhaseMie(float nu, float g) {
    float g2 = g * g;
    return 3.0 / (8.0 * AIR_PI) * (1.0 - g2) * (1.0 + nu * nu) / ((2.0 + g2) * pow(max(1.0 + g2 - 2.0 * g * nu, 1e-4), 1.5));
}

float airDistanceToTop(float r, float mu) {
    float d = r * r * (mu * mu - 1.0) + R_TOP * R_TOP;
    return max(-r * mu + sqrt(max(d, 0.0)), 0.0);
}
float airDistanceToGround(float r, float mu) {
    float d = r * r * (mu * mu - 1.0) + R_GROUND * R_GROUND;
    if (mu >= 0.0 || d < 0.0) return -1.0;
    return max(-r * mu - sqrt(d), 0.0);
}

// air.hpp msLookup: the grid's nodes are the texel centres (u * (MS_SIZE - 1) + 0.5) / MS_SIZE
vec3 airMs(float h, float muS) {
    float u = clamp(muS * 0.5 + 0.5, 0.0, 1.0);
    float v = sqrt(clamp(h / (R_TOP - R_GROUND), 0.0, 1.0));
    return texture(AirMs, (vec2(u, v) * float(MS_SIZE - 1) + 0.5) / float(MS_SIZE)).rgb;
}

// air.hpp integrate: single scattering of both lights, the higher orders from the LUT (quadratic steps: a sky ray)
vec3 airIntegrate(float r, float mu, float nuS, float muS, vec3 ES, float nuM, float muM, vec3 EM, float tEnd,
                  bool ground) {
    bool moon = EM.x + EM.y + EM.z > 0.0;
    float g = AirMieScat.w;
    float pRS = airPhaseRayleigh(nuS), pMS = airPhaseMie(nuS, g), pRM = airPhaseRayleigh(nuM), pMM = airPhaseMie(nuM, g);
    float invHM = AirRayleigh.w;
    vec3 T = vec3(1.0), L = vec3(0.0);
    float prev = 0.0;
    for (int s = 0; s < SKY_STEPS; s++) {
        float a = float(s + 1) / float(SKY_STEPS), m = (float(s) + 0.5) / float(SKY_STEPS);
        float t1 = tEnd * a * a;
        float t = tEnd * m * m;
        float dt = t1 - prev;
        prev = t1;
        float rt = sqrt(max(r * r + 2.0 * r * mu * t + t * t, 1.0));
        float ht = max(rt - R_GROUND, 0.0);
        float dR = exp(-ht / H_RAYLEIGH), dM = exp(-ht * invHM);
        float dO = max(0.0, 1.0 - abs(ht - OZONE_PEAK) / OZONE_HALF);
        vec3 sR = AirRayleigh.xyz * dR, sM = AirMieScat.xyz * dM;
        vec3 ext = sR + AirMieExt.xyz * dM + AirOzone.xyz * dO;
        vec3 stepT = exp(-ext * dt);
        vec3 w = (1.0 - stepT) / max(ext, vec3(1e-7));
        float muSt = (r * muS + t * nuS) / rt;
        vec3 S = ((sR * pRS + sM * pMS) * airLightTransmittance(rt, muSt) + (sR + sM) * airMs(ht, muSt)) * ES;
        if (moon) {
            float muMt = (r * muM + t * nuM) / rt;
            S += ((sR * pRM + sM * pMM) * airLightTransmittance(rt, muMt) + (sR + sM) * airMs(ht, muMt)) * EM;
        }
        L += T * S * w;
        T *= stepT;
    }
    if (ground) {
        float rg = sqrt(max(r * r + 2.0 * r * mu * tEnd + tEnd * tEnd, 1.0));
        float muG = (r * muS + tEnd * nuS) / rg;
        vec3 lit = airLightTransmittance(rg, muG) * ES * max(muG, 0.0);
        if (moon) {
            float muGM = (r * muM + tEnd * nuM) / rg;
            lit += airLightTransmittance(rg, muGM) * EM * max(muGM, 0.0);
        }
        L += T * lit * (AirOzone.w / AIR_PI);
    }
    return L;
}

// air.hpp skyRadiance from the eye: the sky's radiance along unit direction dir (y up), both lights, the night's glow
vec3 airSkyRadiance(vec3 dir) {
    vec3 sun = AirSun.xyz, moon = AirMoon.xyz;
    float r = R_GROUND + AirSun.w;
    float tg = airDistanceToGround(r, dir.y);
    float tEnd = tg >= 0.0 ? tg : airDistanceToTop(r, dir.y);
    return airIntegrate(r, dir.y, dot(dir, sun), sun.y, SUN_IRRADIANCE, dot(dir, moon), moon.y, AirMoonE.rgb, tEnd,
                        tg >= 0.0) + NIGHT_GLOW;
}

#endif
