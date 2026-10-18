// Ground fog over the atmosphere's haze: an exponential height layer that pools in valleys and over water at dawn,
// after rain and in marshes, thins at noon and lifts in wind; integrated along the view ray in closed form, lit by
// the sun (forward scattering) and the sky. aether's far field carries the same haze; this is the near field's own.
#ifndef SKYCRAFT_FOG
#define SKYCRAFT_FOG

#include "/lib/atmosphere.glsl"

// sun: world direction to the sun; wetness 0..1 (recent rain / marsh); camY, the camera's world height
float groundFogDensity(vec3 sun, float wetness, float rain) {
#ifdef SKYCRAFT_REALM
    return 0.0;
#endif
    float dawn = 1.0 - smoothstep(0.0, 0.35, abs(sun.y - 0.05));  // morning and evening mists
    float night = smoothstep(0.1, -0.2, sun.y) * 0.4;
    return (0.0035 * dawn + 0.0012 * night + 0.006 * wetness + 0.004 * rain);
}

// optical depth of a layer density * exp(-(y - base) / scale) along a ray from camY travelling t blocks with slope dy
float heightFogDepth(float camY, float dy, float t, float density, float base, float scale) {
    float y0 = camY - base;
    float k = dy / scale;
    float a = density * exp(-y0 / scale);
    return abs(k) < 1e-4 ? a * t : a * (1.0 - exp(-k * t)) / k;
}

vec3 applyGroundFog(vec3 c, vec3 dir, vec3 sun, float t, float camY, float wetness, float rain) {
#ifdef FOG_LAYERS
    float density = groundFogDensity(sun, wetness, rain);
    float od = heightFogDepth(camY, dir.y, min(t, 3000.0), density, 64.0, 14.0);
    float tr = exp(-od);
    float mu = dot(dir, sun);
    vec3 lit = skyAmbient(vec3(0, 1, 0), sun) * 1.4 + sunRadiance(sun) * (0.08 + 0.35 * pow(max(mu, 0.0), 6.0));
    return mix(lit, c, tr);
#else
    return c;
#endif
}

// the air between the eye and a point t blocks along dir, as (in-scattered light, transmittance): aerial perspective
// then the ground fog in front of it, the same as applyAerial + applyGroundFog. For translucents shaded forward
// (premultiplied colour c, coverage a): c * air.a + air.rgb * a; for additive light (a = 0) just c * air.a
vec4 airLight(vec3 dir, vec3 sun, float t, float camY, float wetness, float rain) {
    float ta = aerialTransmittance(t, camY, rain);
    vec3 rgb = aerialFog(dir, sun) * (1.0 - ta);
    float tr = 1.0;
#ifdef FOG_LAYERS
    float density = groundFogDensity(sun, wetness, rain);
    tr = exp(-heightFogDepth(camY, dir.y, min(t, 3000.0), density, 64.0, 14.0));
    float mu = dot(dir, sun);
    vec3 lit = skyAmbient(vec3(0, 1, 0), sun) * 1.4 + sunRadiance(sun) * (0.08 + 0.35 * pow(max(mu, 0.0), 6.0));
    rgb = rgb * tr + lit * (1.0 - tr);
#endif
    return vec4(rgb, ta * tr);
}

#endif
