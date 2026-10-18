// Forward lighting for the near field: the same sun/sky radiance aether shades the far field with, plus Minecraft's
// light map (sky light for openness, block light for torches) and a sun shadow map.
#ifndef SKYCRAFT_LIGHTING
#define SKYCRAFT_LIGHTING

#include "/lib/atmosphere.glsl"

#ifdef SHADOWS
uniform sampler2DShadow shadowtex0;
uniform mat4 shadowModelView;
uniform mat4 shadowProjection;
const bool shadowHardwareFiltering = true;

vec3 distortShadow(vec3 p) {
    float f = (1.0 - SHADOW_DISTORT) + length(p.xy) * SHADOW_DISTORT;
    return vec3(p.xy / f, p.z * 0.25);
}

// playerPos: camera-relative world position (before curvature); n: world normal; toLight: world direction
float sunShadow(vec3 playerPos, vec3 n, vec3 toLight) {
    float ndl = dot(n, toLight);
    if (ndl <= 0.0) return 0.0;
    float dist = length(playerPos);
    if (dist > shadowDistance) return 1.0;
    // normal offset grows with distance (texel size) and grazing angle
    vec3 p = playerPos + n * (0.06 + dist * 0.004) * (1.5 - ndl);
    vec4 sp = shadowProjection * (shadowModelView * vec4(p, 1.0));
    vec3 s = distortShadow(sp.xyz / sp.w) * 0.5 + 0.5;
    float texel = 1.0 / float(shadowMapResolution);
    float lit = 0.0;
    for (int y = -1; y <= 1; y++)
        for (int x = -1; x <= 1; x++) lit += texture(shadowtex0, vec3(s.xy + vec2(x, y) * texel, s.z - 0.0004));
    float fade = smoothstep(shadowDistance * 0.8, shadowDistance, dist);
    return mix(lit / 9.0, 1.0, fade);
}
#endif

#ifdef AETHER_FAR_FIELD
uniform sampler2D aetherSunMap;

// aether's sun map (docs: native/include/aether/aether.h, aether_set_sun_map): the first occluder along the key light
// over ~4 km around the camera, traced through the far field, plus the clouds' transmittance. Returns (lit 0..1 by
// distant terrain: mountains over valleys, beyond the shadow map's reach; cloud transmittance). (1, 1) without a map.
vec2 aetherSun(vec3 playerPos) {
    ivec2 sz = textureSize(aetherSunMap, 0);
    int S = sz.x;
    if (S < 2 || sz.y != S + 1) return vec2(1.0);
    vec2 q[7];
    for (int k = 0; k < 7; k++) q[k] = texelFetch(aetherSunMap, ivec2(k, S), 0).rg;
    if (q[6].y != 1.0) return vec2(1.0);
    vec3 c = vec3(q[0].x, q[0].y, q[1].x);
    float he = q[1].y;
    vec3 X = vec3(q[2].x, q[2].y, q[3].x), Y = vec3(q[3].y, q[4].x, q[4].y), L = vec3(q[5].x, q[5].y, q[6].x);
    vec3 r = playerPos + cameraPosition - c;
    vec2 uv = vec2(dot(r, X), dot(r, Y)) / he * 0.5 + 0.5;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) return vec2(1.0);
    float s = dot(r, L);
    float texel = 2.0 * he / float(S);
    float bias = 2.0 * texel + 1.5;  // the map is coarse: only occluders well above count (the shadow map has the rest)
    vec2 p = uv * float(S) - 0.5;
    ivec2 i0 = ivec2(floor(p));
    vec2 f = p - vec2(i0);
    float lit = 0.0, cloud = 0.0;
    for (int k = 0; k < 4; k++) {
        ivec2 o = ivec2(k & 1, k >> 1);
        vec2 t = texelFetch(aetherSunMap, clamp(i0 + o, ivec2(0), ivec2(S - 1)), 0).rg;
        float w = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y);
        lit += w * (t.r > s + bias ? 0.0 : 1.0);
        cloud += w * t.g;
    }
    return vec2(lit, cloud);
}
#endif

// albedo linear; lm = light map (block, sky) in 0..1; sun = world direction to the sun; moon = to the moon
vec3 shadeForward(vec3 albedo, vec3 n, vec2 lm, vec3 playerPos, vec3 sun, float emissive) {
    float sky = lm.y * lm.y;
    vec3 toLight = sun.y > -0.05 ? sun : -sun;
    vec3 lightRad = sun.y > -0.05 ? sunRadiance(sun) : vec3(0.035, 0.05, 0.09) * smoothstep(-0.05, -0.2, sun.y);
    float vis = 1.0;
#ifdef SHADOWS
    vis = sunShadow(playerPos, n, toLight);
#else
    vis = clamp(dot(n, toLight), 0.0, 1.0);
#endif
#ifdef AETHER_FAR_FIELD
    vec2 far = aetherSun(playerPos);  // mountains over valleys, cloud shadows
    vis *= far.x * mix(0.25, 1.0, far.y);
#endif
    // no sun where the sky cannot reach (caves): sky light is the light map's own occlusion
    float open = smoothstep(0.05, 0.35, lm.y);
    vec3 direct = lightRad * max(dot(n, toLight), 0.0) * vis * open;
    vec3 ambient = skyAmbient(n, sun) * sky + vec3(0.012, 0.012, 0.016);
    vec3 torch = vec3(1.0, 0.68, 0.38) * pow(lm.x, 3.0) * 1.6;
    return albedo * (direct + ambient + torch) + albedo * emissive * 2.0;
}

#endif
