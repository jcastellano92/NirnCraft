// The key light: the sun by day; by night the brighter of Masser and Secunda (LUNAR_KEY, with the mod's sky state),
// else the vanilla moon opposite the sun. The shadow map is rendered along Iris' own shadow light (shadowLightPosition:
// the sun, or the vanilla moon), so casters (shadow.vsh) and receivers (lighting.glsl sunShadow) are both turned by
// the same rotation from the key onto that direction: shadows fall from the moon that actually lights the night.
// Callers declare gbufferModelViewInverse.
#ifndef NIRNCRAFT_KEYLIGHT
#define NIRNCRAFT_KEYLIGHT

#include "/lib/atmosphere.glsl"
#include "/lib/celestial.glsl"

uniform vec3 shadowLightPosition;

vec3 keyLightDir(vec3 sun) {
#if defined LUNAR_KEY && defined MOD_SKY
    if (sun.y <= -0.05 && haveModSky()) {
        Moons m = moons(sun);
        if (dot(m.weight, vec2(0.8, 0.2)) > 0.001) return lunarKeyDir(m);
    }
#endif
    return sun.y > -0.05 ? sun : -sun;
}

vec3 keyLightRadiance(vec3 sun) {
    float night = smoothstep(-0.05, -0.2, sun.y);
#if defined LUNAR_KEY && defined MOD_SKY
    if (sun.y <= -0.05 && haveModSky()) return lunarRadiance(moons(sun)) * night;
#endif
    return sun.y > -0.05 ? sunRadiance(sun) : vec3(0.035, 0.05, 0.09) * night;
}

// the rotation turning a onto b (both unit)
mat3 rotationBetween(vec3 a, vec3 b) {
    float c = clamp(dot(a, b), -1.0, 1.0);
    if (c > 0.999999) return mat3(1.0);
    if (c < -0.9999) {
        // half a turn about any axis normal to a
        vec3 axis = normalize(cross(a, abs(a.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
        return mat3(2.0 * axis.x * axis - vec3(1.0, 0.0, 0.0), 2.0 * axis.y * axis - vec3(0.0, 1.0, 0.0),
                    2.0 * axis.z * axis - vec3(0.0, 0.0, 1.0));
    }
    vec3 v = cross(a, b);
    mat3 k = mat3(0.0, v.z, -v.y, -v.z, 0.0, v.x, v.y, -v.x, 0.0);
    return mat3(1.0) + k + k * k / (1.0 + c);
}

// player-space rotation from the key light onto the shadow map's light (identity when they agree: every day)
mat3 shadowKeyRotation(vec3 key) {
    vec3 native = mat3(gbufferModelViewInverse) * shadowLightPosition;
    float l = length(native);
    if (l < 1e-3) return mat3(1.0);
    return rotationBetween(key, native / l);
}

#endif
