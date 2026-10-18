#ifndef SKYCRAFT_CELESTIAL_FRAME
#define SKYCRAFT_CELESTIAL_FRAME
#ifdef OVERWORLD
float SkycraftFrameClock(ivec2 pixel) {
    vec4 bytes = floor(texelFetch(skycraftSmokeTex, pixel, 0) * 255.0 + 0.5);
    return dot(bytes, vec4(1.0, 256.0, 65536.0, 16777216.0));
}
vec3 SkycraftAxisRotate(vec3 p, vec3 axis, float angle) {
    return p * cos(angle) + cross(axis, p) * sin(angle) + axis * dot(axis, p) * (1.0 - cos(angle));
}
vec3 skycraftWorldSun = normalize(vec3(-sin(overworldAngle), cos(overworldAngle) * SUN_ROTATION_DATA));
float skycraftOrbitDays = (SkycraftFrameClock(ivec2(1, 1)) + texelFetch(skycraftSmokeTex, ivec2(11, 1), 0).r) / 24000.0;
vec3 skycraftOrbitAxis = vec3(0.0, sin(sunPathRotation * 0.01745329252), cos(sunPathRotation * 0.01745329252));
vec3 skycraftMasserDirection = SkycraftAxisRotate(-skycraftWorldSun, skycraftOrbitAxis,
        fract((skycraftOrbitDays - 0.75) / 8.0) * 6.2831853);
vec3 skycraftSecundaUninclined = SkycraftAxisRotate(-skycraftWorldSun, skycraftOrbitAxis,
        fract((skycraftOrbitDays - 0.75) / 9.0 + 0.375) * 6.2831853);
vec3 skycraftSecundaDirection = SkycraftAxisRotate(skycraftSecundaUninclined, vec3(1.0, 0.0, 0.0), -0.41887902);
vec2 skycraftMoonWeights = vec2(0.5 - 0.5 * dot(skycraftWorldSun, skycraftMasserDirection),
                              0.5 - 0.5 * dot(skycraftWorldSun, skycraftSecundaDirection))
        * smoothstep(vec2(-0.035), vec2(0.10), vec2(skycraftMasserDirection.y, skycraftSecundaDirection.y));
float skycraftSecondaryKey = smoothstep(0.80, 1.20, skycraftMoonWeights.y * 0.20 / max(0.001, skycraftMoonWeights.x * 0.80));
vec3 skycraftLunarKey = normalize(mix(skycraftMasserDirection, skycraftSecundaDirection, skycraftSecondaryKey));
vec3 skycraftKeyDirection = skycraftWorldSun.y > -0.02 ? skycraftWorldSun
        : dot(skycraftMoonWeights, vec2(0.80, 0.20)) > 0.001 ? skycraftLunarKey : -skycraftWorldSun;

vec3 SkycraftLightVector() {
    return normalize(mat3(gbufferModelView) * skycraftKeyDirection);
}

// Rotate both shadow casters and receivers into the engine's native shadow frame.
// The map retains its resolution/distance; it now follows the actual dominant moon.
mat3 SkycraftShadowRotation() {
    vec3 nativeDirection = skycraftWorldSun * ((timeAngle < 0.5325 || timeAngle > 0.9675) ? 1.0 : -1.0);
    vec3 from = skycraftKeyDirection, to = nativeDirection;
    float c = clamp(dot(from, to), -1.0, 1.0);
    if (c > 0.999999) return mat3(1.0);
    if (c < -0.9999) {
        vec3 axis = normalize(cross(from, abs(from.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
        return mat3(2.0 * axis.x * axis - vec3(1.0, 0.0, 0.0),
                    2.0 * axis.y * axis - vec3(0.0, 1.0, 0.0),
                    2.0 * axis.z * axis - vec3(0.0, 0.0, 1.0));
    }
    vec3 v = cross(from, to);
    mat3 k = mat3(0.0, v.z, -v.y, -v.z, 0.0, v.x, v.y, -v.x, 0.0);
    return mat3(1.0) + k + k * k / (1.0 + c);
}
mat3 skycraftShadowRotation = SkycraftShadowRotation();
#endif
#endif
