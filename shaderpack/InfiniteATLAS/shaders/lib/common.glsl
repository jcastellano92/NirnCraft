#ifndef NIRNCRAFT_COMMON
#define NIRNCRAFT_COMMON

vec3 srgbToLinear(vec3 c) { return pow(c, vec3(2.2)); }

// world-space direction towards the sun (not the shadow light, which flips to the moon at night)
vec3 worldSunDir(mat4 modelViewInverse, vec3 sunPositionView) {
    return normalize(mat3(modelViewInverse) * sunPositionView);
}

// HDR that may feed a mip chain (bloom, the eye's average): one NaN / Inf pixel spreads through every mip above it
// and bloom smears that into whole black 64-px-aligned blocks (seen in game at Helgen); keep only finite light
vec3 finite(vec3 c) {
    c = mix(c, vec3(0.0), bvec3(isnan(c.r) || isinf(c.r), isnan(c.g) || isinf(c.g), isnan(c.b) || isinf(c.b)));
    return clamp(c, vec3(0.0), vec3(60000.0));
}
float finite(float x) { return (isnan(x) || isinf(x)) ? 0.0 : x; }

#endif
