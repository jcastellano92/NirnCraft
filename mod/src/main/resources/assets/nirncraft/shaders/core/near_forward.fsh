#version 330
#extension GL_ARB_separate_shader_objects : require
// Infinite ATLAS near pass: translucent terrain (water, glass, ice) drawn forward over the resolved frame
// (com.nirncraft.atlas.AtlasNearPass), lit with the same light and look as the opaque terrain: the key light, the sky
// over the surface (vanilla's sky light as its visibility), the block light, then the pack's display. Water (its
// fragments are found by the water sprites' texels) is shaded as the far field's water: small waves, the sky reflected
// by Fresnel, the key light's glint. Not yet: terrain in the reflection, shadow on the surface.

#include <minecraft:fog.glsl>
#include <minecraft:globals.glsl>
#include <minecraft:texture_sampling.glsl>
#include <minecraft:terrainglobals.glsl>
#include <minecraft:projection.glsl>
#include <nirncraft:atlas_light.glsl>
#include <nirncraft:atlas_sky.glsl>

uniform sampler2D Sampler0;
uniform sampler2D SceneColor;  // our resolved frame (displayed), terrain near and far: what the water reflects
uniform sampler2D SceneDepth;  // its depth (reverse-Z)

layout(location = 0) in vec4 vertexColor;
layout(location = 1) in vec2 texCoord0;
layout(location = 2) in vec2 lightLevels;
layout(location = 3) in vec3 viewPos;

layout(location = 0) out vec4 fragColor;

// screen-space reflection: the reflected ray marched across our frame's depth; .a 0 where it leaves the screen or hits
// nothing (the sky model stands in)
vec4 screenReflection(vec3 origin, vec3 dir) {
    mat4 vp = ProjMat * ModelViewMat;
    float t = 0.5;
    for (int i = 0; i < 48; i++) {
        vec3 q = origin + dir * t;
        vec4 c = vp * vec4(q, 1.0);
        if (c.w <= 0.0) break;
        vec3 ndc = c.xyz / c.w;
        vec2 uv = ndc.xy * 0.5 + 0.5;
        if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) break;
        float scene = texture(SceneDepth, uv).r;
        // reverse-Z: the scene is in front of the ray point when its depth is larger
        if (scene > ndc.z && scene > 0.0) {
            float fade = smoothstep(0.0, 0.1, min(min(uv.x, 1.0 - uv.x), min(uv.y, 1.0 - uv.y)));
            return vec4(texture(SceneColor, uv).rgb, fade);
        }
        t *= 1.12;
        t += 0.25;
    }
    return vec4(0.0);
}

bool isWater(vec2 uv) {
    return (all(greaterThanEqual(uv, WaterStill.xy)) && all(lessThanEqual(uv, WaterStill.zw)))
        || (all(greaterThanEqual(uv, WaterFlow.xy)) && all(lessThanEqual(uv, WaterFlow.zw)));
}

// small wind waves on a level water surface (a few sines across directions): the normal at world xz, time in seconds
vec3 waveNormal(vec2 p, float t) {
    vec2 g = vec2(0.0);
    const vec2 d0 = vec2(0.8, 0.6), d1 = vec2(-0.5, 0.86), d2 = vec2(0.2, -0.98);
    g += d0 * cos(dot(p, d0) * 1.7 + t * 1.9) * 0.06;
    g += d1 * cos(dot(p, d1) * 2.9 + t * 2.6) * 0.04;
    g += d2 * cos(dot(p, d2) * 5.3 + t * 3.7) * 0.025;
    return normalize(vec3(-g.x, 1.0, -g.y));
}

void main() {
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize)
                               : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;
    if (color.a < 0.004) discard;
    vec3 n = normalize(cross(dFdx(viewPos), dFdy(viewPos)));
    if (dot(n, viewPos) > 0.0) n = -n;
    vec3 a = abs(n);
    if (max(a.x, max(a.y, a.z)) > 0.99) {  // vanilla's baked face shade out, as in the G-buffer
        float shade = n.y > 0.5 ? 1.0 : n.y < -0.5 ? 0.5 : a.z > 0.5 ? 0.8 : 0.6;
        color.rgb = min(color.rgb / shade, vec3(1.0));
    }
    vec2 lm = clamp(lightLevels / 240.0, 0.0, 1.0);
    vec3 rgb;
    if (isWater(texCoord0)) {
        vec3 world = viewPos - CameraOffset + vec3(CameraBlockPos);
        vec3 wn = n.y > 0.5 ? waveNormal(world.xz, SunDir.w) : n;
        vec3 v = normalize(viewPos);
        float cosv = max(dot(-v, wn), 0.0);
        // Minecraft water is a clear shallow sheet: never a full mirror, the bed keeps showing at grazing angles
        float fresnel = min(0.02 + 0.98 * pow(1.0 - cosv, 5.0), 0.65);
        vec3 r = reflect(v, wn);
        vec3 sky = atlasSkyColor(normalize(vec3(r.x, max(r.y, 0.02), r.z)), SunDir.xyz) * (lm.y * lm.y);
        float open = smoothstep(0.05, 0.35, lm.y);
        vec3 glint = KeyRad.rgb * pow(max(dot(r, KeyDir.xyz), 0.0), 600.0) * 60.0 * open;
        vec3 body = atlasForwardRadianceShadowed(color.rgb, n, lm, atlasShadow(viewPos, n));
        // what the water mirrors: our frame where the reflected ray finds it on screen, the sky model elsewhere
        vec4 hit = screenReflection(viewPos + wn * 0.05, r);
        vec3 skyShown = atlasDisplay(sky + glint, Look.x, Look.y);
        vec3 mirrored = mix(skyShown, hit.rgb, hit.a);
        rgb = mix(atlasDisplay(body, Look.x, Look.y), mirrored, fresnel);
        color.a = mix(color.a, 1.0, fresnel);
    } else {
        rgb = atlasForwardLitAt(color.rgb, n, lm, viewPos);
    }
    float dist = length(viewPos);
    rgb = mix(rgb, FogColor.rgb, linear_fog_value(dist, FogEnvironmentalStart, FogEnvironmentalEnd) * FogColor.a);
    fragColor = vec4(rgb, color.a);
}
