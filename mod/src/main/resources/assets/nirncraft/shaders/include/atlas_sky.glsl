#ifndef NIRNCRAFT_ATLAS_SKY_GLSL
#define NIRNCRAFT_ATLAS_SKY_GLSL
// The far field's sky model (native/shaders: sunRadiance, skyColor; pack_look.hpp), one for our sky, water's
// reflections and anything else that shows the sky: linear HDR, sun = unit vector towards the sun.

vec3 atlasSunRadiance(vec3 s) {
    float e = s.y, a = clamp(e * 3.0, 0.0, 1.0);
    return mix(vec3(1.0, 0.62, 0.38), vec3(1.0, 0.96, 0.9), a) * (3.2 * smoothstep(-0.04, 0.12, e));
}

vec3 atlasSkyColor(vec3 dir, vec3 sun) {
    float mu = dot(dir, sun), up = dir.y;
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
    c += atlasSunRadiance(sun) * (mie * 0.5);
    return c * 1.6;
}

#endif
