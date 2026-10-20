#version 330
#extension GL_ARB_separate_shader_objects : require
// Infinite ATLAS near pass: translucent terrain (water, glass, ice) drawn forward over the resolved frame
// (com.nirncraft.atlas.AtlasNearPass), lit with the same light and look as the opaque terrain: the key light, the sky
// over the surface (vanilla's sky light as its visibility), the block light, then the pack's display. Water (its
// fragments are found by the water sprites' texels) is shaded as the far field's water: small waves, the sky reflected
// by Fresnel, the key light's glint. Water is a medium: what lies behind the surface (the bed, near or far) is seen
// refracted through the waves and absorbed along the path through the water (red first, then green: shallows clear,
// depths blue-green), the water's own scattering lit by the sky and the sun in the biome's water colour; the surface
// mirrors our frame where a reflected ray finds it, the sky elsewhere.

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
vec3 sceneAt(vec2 uv, float depth) {  // camera-relative world position of the frame's depth at uv
    vec4 w = inverse(ProjMat * ModelViewMat) * vec4(uv * 2.0 - 1.0, depth, 1.0);
    return w.xyz / w.w;
}

vec4 screenReflection(vec3 origin, vec3 dir) {
    mat4 vp = ProjMat * ModelViewMat;
    float t = 0.3, prev = 0.0;
    for (int i = 0; i < 64; i++) {
        vec3 q = origin + dir * t;
        vec4 c = vp * vec4(q, 1.0);
        if (c.w <= 0.0) break;
        vec3 ndc = c.xyz / c.w;
        vec2 uv = ndc.xy * 0.5 + 0.5;
        if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) break;
        float scene = texture(SceneDepth, uv).r;
        // reverse-Z: the scene is in front of the ray point when its depth is larger
        if (scene > ndc.z && scene > 0.0) {
            // the crossing between the last step and this one (8 halvings), then: a real hit, not the ray passing
            // behind a thin thing far in front of it (the scene point within the step's length of the ray)
            float lo = prev, hi = t;
            for (int k = 0; k < 8; k++) {
                float m = 0.5 * (lo + hi);
                vec4 cm = vp * vec4(origin + dir * m, 1.0);
                vec2 um = cm.xy / cm.w * 0.5 + 0.5;
                if (texture(SceneDepth, um).r > cm.z / cm.w) hi = m; else lo = m;
            }
            vec4 ch = vp * vec4(origin + dir * hi, 1.0);
            vec2 uh = ch.xy / ch.w * 0.5 + 0.5;
            float sd = texture(SceneDepth, uh).r;
            vec3 hitP = sceneAt(uh, sd);
            if (length(hitP - (origin + dir * hi)) > max(1.0, (t - prev) * 2.0)) return vec4(0.0);
            float fade = smoothstep(0.0, 0.08, min(min(uh.x, 1.0 - uh.x), min(uh.y, 1.0 - uh.y)));
            return vec4(texture(SceneColor, uh).rgb, fade);
        }
        prev = t;
        t = t * 1.08 + 0.15;
    }
    return vec4(0.0);
}

bool isWater(vec2 uv) {
    return (all(greaterThanEqual(uv, WaterStill.xy)) && all(lessThanEqual(uv, WaterStill.zw)))
        || (all(greaterThanEqual(uv, WaterFlow.xy)) && all(lessThanEqual(uv, WaterFlow.zw)));
}

// wind waves on a level water surface: six octaves across directions (wavelengths 9 to 0.7 blocks, the shorter
// steeper), each faded out where it is finer than the pixel's footprint on the water (foot, blocks): far water calms
// instead of aliasing into a tilted shimmer. The normal at world xz, time in seconds
vec3 waveNormal(vec2 p, float t, float foot) {
    vec2 g = vec2(0.0);
    float k = 0.7, amp = 0.05, w = 0.0;
    for (int i = 0; i < 6; i++) {
        float ang = float(i) * 2.399 + 0.6;  // the golden angle: directions that never line up
        vec2 d = vec2(cos(ang), sin(ang));
        float speed = sqrt(9.81 * k) * 0.6;  // deep-water dispersion, slowed for blocks
        float lambda = 6.2832 / k;
        float keep = 1.0 - smoothstep(0.5, 2.0, foot * 2.0 / lambda);
        g += d * cos(dot(p, d) * k + t * speed + float(i) * 1.7) * amp * k * keep;
        k *= 1.62;
        amp *= 0.62;
    }
    return normalize(vec3(-g.x, 1.0, -g.y));
}

// water's absorption per block (red first, then green) and its scattering colour per block of path
const vec3 WATER_ABSORB = vec3(0.30, 0.075, 0.045);

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
        float dist = length(viewPos);
        vec3 v = viewPos / dist;
        float foot = dist * length(fwidth(v)) / max(abs(v.y), 0.05);  // the pixel's footprint on the level surface
        vec3 wn = n.y > 0.5 ? waveNormal(world.xz, SunDir.w, foot) : n;
        float cosv = max(dot(-v, wn), 0.0);
        float fresnel = 0.02 + 0.98 * pow(1.0 - cosv, 5.0);
        vec3 r = reflect(v, wn);
        vec3 sky = atlasSkyColor(normalize(vec3(r.x, max(r.y, 0.02), r.z)), SunDir.xyz) * (lm.y * lm.y);
        float open = smoothstep(0.05, 0.35, lm.y);
        float lit = atlasShadow(viewPos, n);
        vec3 glint = KeyRad.rgb * pow(max(dot(r, KeyDir.xyz), 0.0), 600.0) * 60.0 * open * lit;
        // what lies behind: the frame refracted by the waves (where the offset point still lies behind the surface),
        // through the water's path to it
        mat4 vp = ProjMat * ModelViewMat;
        vec4 c0 = vp * vec4(viewPos, 1.0);
        vec2 uv0 = c0.xy / c0.w * 0.5 + 0.5;
        float d0 = texture(SceneDepth, uv0).r;
        float path0 = d0 > 0.0 ? max(length(sceneAt(uv0, d0)) - dist, 0.0) : 64.0;
        vec2 uvR = uv0 + (wn.xz - n.xz) * 0.06 * min(path0, 4.0) / max(dist, 1.0);
        float dR = texture(SceneDepth, uvR).r;
        bool behind = dR < c0.z / c0.w;  // (reverse-Z: farther is smaller)
        vec2 uvB = behind ? uvR : uv0;
        float dB = behind ? dR : d0;
        float path = dB > 0.0 ? max(length(sceneAt(uvB, dB)) - dist, 0.0) : 64.0;
        vec3 bed = texture(SceneColor, uvB).rgb;
        vec3 T = exp(-WATER_ABSORB * path);
        // the water's own light: the sky over it and the sun through it, scattered back up in the biome's water colour
        // (vanilla's vertex tint), as much as the path is long
        vec3 tint = clamp(vertexColor.rgb * 1.2, 0.0, 1.0);
        vec3 inLight = (SkyUp.rgb * 3.0 * (lm.y * lm.y) + KeyRad.rgb * max(KeyDir.y, 0.0) * 0.5 * lit * open)
                     * vec3(0.05, 0.11, 0.13) * tint;
        vec3 scatter = atlasDisplay(inLight, Look.x, Look.y) * (vec3(1.0) - T);
        vec3 seen = bed * T + scatter;
        // what the water mirrors: our frame where the reflected ray finds it on screen, the sky model elsewhere
        vec4 hit = screenReflection(viewPos + wn * 0.05, r);
        vec3 skyShown = atlasDisplay(sky + glint, Look.x, Look.y);
        vec3 mirrored = mix(skyShown, hit.rgb, hit.a);
        rgb = mix(seen, mirrored, fresnel);
        color.a = 1.0;  // the whole medium is here: nothing behind is blended in again
    } else {
        rgb = atlasForwardLitAt(color.rgb, n, lm, viewPos);
    }
    float dist = length(viewPos);
    rgb = mix(rgb, FogColor.rgb, linear_fog_value(dist, FogEnvironmentalStart, FogEnvironmentalEnd) * FogColor.a);
    fragColor = vec4(rgb, color.a);
}
