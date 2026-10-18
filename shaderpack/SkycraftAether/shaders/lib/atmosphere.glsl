// Sun, sky and aerial perspective: a line-for-line port of native/shaders/aether_shared.slang, so the near field
// fogs into exactly the haze aether's far field ends in. Change both together.
#ifndef SKYCRAFT_ATMOSPHERE
#define SKYCRAFT_ATMOSPHERE

#ifdef SKYCRAFT_REALM
// realms (shaders/realm/, dimension.properties: the Nether / Deadlands, the End / Sovngarde, the Shivering Isles,
// Apocrypha, the Soul Cairn, Coldharbour): no terrestrial sky, sun or haze. Their air is the authored fog the mod
// sets (fogColor, fogStart / fogEnd), their sky the dome the mod draws (gbuffers_skybasic / skytextured -> colortex7)
uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;
vec3 realmFog() { return pow(max(fogColor, vec3(0.0)), vec3(2.2)); }
#endif

vec3 sunRadiance(vec3 sun) {
#ifdef SKYCRAFT_REALM
    return realmFog() * 0.6 * smoothstep(-0.04, 0.12, sun.y);  // a day-night realm's light takes the realm's hue
#endif
    float e = sun.y;
    vec3 warm = vec3(1.0, 0.62, 0.38);
    vec3 noon = vec3(1.0, 0.96, 0.9);
    float a = clamp(e * 3.0, 0.0, 1.0);
    return mix(warm, noon, a) * (3.2 * smoothstep(-0.04, 0.12, e));
}

vec3 skyColor(vec3 dir, vec3 sun) {
#ifdef SKYCRAFT_REALM
    return realmFog() * (1.0 + 0.2 * clamp(dir.y, 0.0, 1.0));
#endif
    float mu = dot(dir, sun);
    float up = dir.y;
    float day = smoothstep(-0.12, 0.25, sun.y);
    vec3 zenith = mix(vec3(0.008, 0.012, 0.03), vec3(0.16, 0.34, 0.78), day);
    vec3 horizon = mix(vec3(0.03, 0.035, 0.06), vec3(0.62, 0.75, 0.92), day);
    float dusk = (1.0 - smoothstep(0.0, 0.3, abs(sun.y))) * smoothstep(-0.2, 0.9, mu);
    horizon = mix(horizon, vec3(1.0, 0.5, 0.25), dusk * 0.7);
    float hz = pow(1.0 - clamp(abs(up), 0.0, 1.0), 5.0);
    vec3 c = mix(zenith, horizon, hz);
    if (up < 0.0) c = mix(c, horizon * 0.55, clamp(-up * 6.0, 0.0, 1.0));
    float g = 0.76;
    float mie = (1.0 - g * g) / pow(max(1.0 + g * g - 2.0 * g * mu, 1e-4), 1.5) * 0.012;
    c += sunRadiance(sun) * mie * 0.5;
    if (mu > 0.99995 && up > -0.01) c += sunRadiance(sun) * 18.0;
    return c * 1.6;
}

vec3 skyAmbient(vec3 n, vec3 sun) {
#ifdef SKYCRAFT_REALM
    return realmFog() * mix(0.35, 0.7, n.y * 0.5 + 0.5) + vec3(0.02);
#endif
    float day = smoothstep(-0.12, 0.25, sun.y);
    vec3 skyUp = mix(vec3(0.02, 0.025, 0.05), vec3(0.33, 0.45, 0.7), day);
    vec3 ground = mix(vec3(0.01, 0.01, 0.012), vec3(0.2, 0.18, 0.14), day);
    return mix(ground, skyUp, n.y * 0.5 + 0.5);
}

// camY: camera altitude in blocks; rain: 0..1
float aerialTransmittance(float t, float camY, float rain) {
#ifdef SKYCRAFT_REALM
    return fogEnd > fogStart + 1.0 ? 1.0 - smoothstep(fogStart, fogEnd, t) : 1.0;
#endif
    float h = max(camY - 63.0, 0.0);
    float density = exp(-h / 1200.0);
    float beta = (1.0 / 22000.0) * (1.0 + 3.0 * rain);
    return exp(-t * beta * density);
}

vec3 aerialFog(vec3 dir, vec3 sun) {
#ifdef SKYCRAFT_REALM
    return realmFog();
#endif
    return skyColor(normalize(vec3(dir.x, max(dir.y, 0.02), dir.z)), sun) * 0.9;
}

vec3 applyAerial(vec3 c, vec3 dir, vec3 sun, float t, float camY, float rain) {
    return mix(aerialFog(dir, sun), c, aerialTransmittance(t, camY, rain));
}

#endif
