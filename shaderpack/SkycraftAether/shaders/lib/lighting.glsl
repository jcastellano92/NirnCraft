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
    // no sun where the sky cannot reach (caves): sky light is the light map's own occlusion
    float open = smoothstep(0.05, 0.35, lm.y);
    vec3 direct = lightRad * max(dot(n, toLight), 0.0) * vis * open;
    vec3 ambient = skyAmbient(n, sun) * sky + vec3(0.012, 0.012, 0.016);
    vec3 torch = vec3(1.0, 0.68, 0.38) * pow(lm.x, 3.0) * 1.6;
    return albedo * (direct + ambient + torch) + albedo * emissive * 2.0;
}

#endif
