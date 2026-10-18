// Storm light from the mod's discharges (lightning_sources: four 3D channels with their leader progress and strobe;
// the mod draws the channels themselves through gbuffers_basic). Light from the channel, not a screen flash: the
// ground and walls facing it, the rain and mist around it, the cloud deck over it. Outdoors only: under a roof, in a
// cave or in a dungeon it is zero (an underground flash is a defect). LIGHTNING_LIGHT 0 turns it off.
#ifndef SKYCRAFT_LIGHTNING
#define SKYCRAFT_LIGHTNING

#include "/lib/modtex.glsl"
#include "/lib/air.glsl"

const vec3 LIGHTNING_COLOUR = vec3(0.72, 0.83, 1.12);

int gStrikes = 0;
Discharge gStrike[4];
bool gStrikesLoaded = false;

void loadStrikes() {
    if (gStrikesLoaded) return;
    gStrikesLoaded = true;
#if LIGHTNING_LIGHT > 0
    gStrikes = lightningCount();
    for (int i = 0; i < 4; i++) if (i < gStrikes) gStrike[i] = discharge(i);
#endif
}

// radiance the channels shed into the air at p (player space); exposure: outdoorExposure there
vec3 lightningAir(vec3 p, float exposure) {
    loadStrikes();
    if (gStrikes == 0 || exposure <= 0.0) return vec3(0.0);
    float e = 0.0;
    for (int i = 0; i < 4; i++) {
        if (i >= gStrikes) break;
        vec3 q = dischargeNearest(gStrike[i], p) - p;
        float d2 = dot(q, q), d = sqrt(d2);
        // a hot narrow core, a broad scattered shoulder, kilometres of weaker underbelly light
        e += dischargePulse(gStrike[i]) * (22000.0 / (625.0 + d2) + 0.65 / (1.0 + d2 / 360000.0)) * exp(-d * 0.00065);
    }
    return LIGHTNING_COLOUR * min(e, 30.0) * exposure;
}

// irradiance on a surface at p with normal n; skyLight: the light map's sky term (no leaks through sealed roofs)
vec3 lightningSurface(vec3 p, vec3 n, float skyLight, float exposure) {
    loadStrikes();
    if (gStrikes == 0 || exposure <= 0.0) return vec3(0.0);
    float e = 0.0;
    for (int i = 0; i < 4; i++) {
        if (i >= gStrikes) break;
        Discharge s = gStrike[i];
        float pulse = dischargePulse(s);
        vec3 q = dischargeNearest(s, p) - p;
        float d2 = dot(q, q);
        float facing = max(0.0, dot(n, q * inversesqrt(max(1.0, d2))));
        e += pulse * 20000.0 / (3600.0 + d2) * (0.12 + 0.88 * facing);
        if (s.state.b > 0.5 && s.state.g > 0.995) {
            // the ground strike: a point raised inside the plasma column lights flat ground directly
            q = s.p[3] + vec3(0.0, 12.0, 0.0) - p;
            d2 = dot(q, q);
            facing = max(0.0, dot(n, q * inversesqrt(max(1.0, d2))));
            e += pulse * 110000.0 / (900.0 + d2) * (0.18 + 0.82 * facing);
        }
    }
    return LIGHTNING_COLOUR * min(e, 24.0) * smoothstep(0.0, 0.65, skyLight) * exposure;
}

// the sky and cloud deck lit by the channels, seen along dir (camera outdoors only): a glow towards each strike and a
// broad flicker over the whole sky while one is near
vec3 lightningSky(vec3 dir, float camExposure) {
    loadStrikes();
    if (gStrikes == 0 || camExposure <= 0.0) return vec3(0.0);
    vec3 c = vec3(0.0);
    for (int i = 0; i < 4; i++) {
        if (i >= gStrikes) break;
        Discharge s = gStrike[i];
        vec3 mid = mix(s.p[0], s.p[3], 0.3);  // the upper channel, in the cloud
        float d = length(mid);
        float cosA = dot(dir, mid / max(d, 1.0));
        float near = 1.0 / (1.0 + d * d / 4.0e6);  // within ~2 km a strike fills the sky
        c += LIGHTNING_COLOUR * dischargePulse(s) * near * (0.25 + 2.5 * pow(max(cosA, 0.0), 6.0));
    }
    return c * smoothstep(-0.15, 0.1, dir.y) * camExposure;
}

#endif
