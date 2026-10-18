#ifndef SKYCRAFT_COMMON
#define SKYCRAFT_COMMON

vec3 srgbToLinear(vec3 c) { return pow(c, vec3(2.2)); }

// world-space direction towards the sun (not the shadow light, which flips to the moon at night)
vec3 worldSunDir(mat4 modelViewInverse, vec3 sunPositionView) {
    return normalize(mat3(modelViewInverse) * sunPositionView);
}

#endif
