#ifndef NIRNCRAFT_ATLAS_SKY_GLSL
#define NIRNCRAFT_ATLAS_SKY_GLSL
// The sky seen from the eye, for everything that shows it (AtlasSky's sky, water's reflections): the air's sky
// (atlas_air.glsl, a port of native/src/air.hpp: the far field's haze is the same model) marched once a frame into a
// sky-view LUT (Hillaire 2020: u the bearing from the sun's, a whole turn; v the elevation, texels gathered at the
// horizon: v = 0.5 + 0.5 sign(l) sqrt(|l| / (pi / 2))) and looked up here. Linear HDR radiance; sun = unit vector
// towards the sun.

vec2 atlasSkyViewUv(vec3 dir, vec3 sun) {
    float l = asin(clamp(dir.y, -1.0, 1.0));
    float v = 0.5 + 0.5 * sign(l) * sqrt(abs(l) / 1.5707963);
    float az = atan(dir.z, dir.x) - atan(sun.z, sun.x + 1e-9);
    return vec2(fract(az / 6.2831853 + 1.0), v);
}

vec3 atlasSkyViewDir(vec2 uv, vec3 sun) {
    float s = uv.y * 2.0 - 1.0;
    float l = sign(s) * s * s * 1.5707963;
    float az = uv.x * 6.2831853 + atan(sun.z, sun.x + 1e-9);
    return vec3(cos(l) * cos(az), sin(l), cos(l) * sin(az));
}

// the sky's radiance along dir from the sky-view LUT (sampled with linear filtering, repeat across u)
// the air's sky along dir from the sky-view LUT, which is written pre-exposed: divide by the exposure it carries
vec3 atlasSky(sampler2D skyView, vec3 dir, vec3 sun, float preExposure) {
    return textureLod(skyView, atlasSkyViewUv(dir, sun), 0.0).rgb / preExposure;
}

#endif
