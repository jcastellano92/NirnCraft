#version 330
#extension GL_ARB_separate_shader_objects : require
// Infinite ATLAS near pass: translucent terrain (water, glass, ice) drawn forward over the resolved frame (in the unified
// field atlas draws the water: its fragments are discarded here)
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
#include <nirncraft:atlas_water.glsl>

uniform sampler2D Sampler0;
uniform sampler2D SceneColor;  // our resolved frame (displayed), terrain near and far: what the water reflects
uniform sampler2D SceneDepth;  // its depth (reverse-Z)
uniform sampler2D SkyView;
uniform sampler2D SkyFrame;
uniform sampler2D SkyCube;    // the sky's emitters (aurora, stars, moons, meteors) in every direction: six faces side by side

// the sky's emitters seen along camera-relative direction d (SkyCube, AtlasSky's atlas_sky_emit: +X -X +Y -Y +Z -Z, each
// a 90 degree view): linear radiance (stored pre-exposed, the exposure in alpha; nothing where alpha is 0)
vec3 skyCubeAt(vec3 d) {
    vec3 a = abs(d);
    int f;
    vec2 uv;
    if (a.x >= a.y && a.x >= a.z) { f = d.x > 0.0 ? 0 : 1; uv = vec2(d.x > 0.0 ? -d.z : d.z, -d.y) / a.x; }
    else if (a.y >= a.z) { f = d.y > 0.0 ? 2 : 3; uv = vec2(d.x, d.y > 0.0 ? d.z : -d.z) / a.y; }
    else { f = d.z > 0.0 ? 4 : 5; uv = vec2(d.z > 0.0 ? d.x : -d.x, -d.y) / a.z; }
    uv = clamp(uv * 0.5 + 0.5, vec2(0.01), vec2(0.99));
    vec4 t = texture(SkyCube, vec2((float(f) + uv.x) / 6.0, 1.0 - uv.y));
    return t.a > 1e-6 ? t.rgb / t.a : vec3(0.0);
}

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
float wHash(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float wNoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(wHash(i), wHash(i + vec2(1, 0)), f.x), mix(wHash(i + vec2(0, 1)), wHash(i + vec2(1, 1)), f.x), f.y);
}

// Water waves that do not tile: the surface is chop (eight octaves, wavelengths 9 to 0.4 blocks, their directions spread
// about the wind and rotated by a slow noise, the domain warped so no pattern repeats), its strength set by a gust field
// drifting downwind at about 40 blocks (cat's paws: rough patches between calm, glassy stretches), plus long swell where
// the water around is ocean (WaterTint.w: 0 a lake or a pond .. 1 the open sea). Each octave fades where it is finer than
// the pixel's footprint on the water (foot): far water calms instead of aliasing. The normal at world xz, time seconds.
vec3 waveNormal(vec2 p, float t, float foot) {
    vec2 wind = normalize(vec2(0.8, 0.6));
    float ocean = clamp(WaterTint.w, 0.0, 1.0);
    vec2 gp = p / 38.0 - wind * t * 0.12;
    float gust = smoothstep(0.25, 0.85, wNoise(gp) * 0.65 + wNoise(gp * 2.3 + 7.1) * 0.35);
    float rough = mix(0.12, 1.0, gust);                       // glassy calm .. full chop
    vec2 q = p + vec2(wNoise(p / 17.0), wNoise(p / 17.0 + 3.7)) * 6.0;  // the warp: no straight repeating rows
    float turn = (wNoise(p / 60.0 + 11.0) - 0.5) * 1.2;
    vec2 g = vec2(0.0);
    float k = 0.7, amp = 0.05;
    for (int i = 0; i < 8; i++) {
        float ang = atan(wind.y, wind.x) + turn + (float(i) * 2.399 - 3.0) * 0.45;  // spread about the wind
        vec2 d = vec2(cos(ang), sin(ang));
        float speed = sqrt(9.81 * k) * 0.6;
        float lambda = 6.2832 / k;
        float keep = 1.0 - smoothstep(0.5, 2.0, foot * 2.0 / lambda);
        g += d * cos(dot(q, d) * k + t * speed + float(i) * 1.7) * amp * k * keep * rough;
        k *= 1.5;
        amp *= 0.66;
    }
    // the sea's swell: long, regular, little affected by the gusts
    if (ocean > 0.01) {
        float ks = 0.09;
        for (int i = 0; i < 3; i++) {
            float ang = atan(wind.y, wind.x) + float(i) * 0.35 - 0.35;
            vec2 d = vec2(cos(ang), sin(ang));
            g += d * cos(dot(p, d) * ks + t * sqrt(9.81 * ks) * 0.6 + float(i) * 2.1) * 0.35 * ks * ocean;
            ks *= 1.6;
        }
    }
    return normalize(vec3(-g.x, 1.0, -g.y));
}

// water's absorption per block (red first, then green) and its scattering colour per block of path
const vec3 WATER_ABSORB = vec3(0.30, 0.075, 0.045);

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash21(i), hash21(i + vec2(1, 0)), f.x), mix(hash21(i + vec2(0, 1)), hash21(i + vec2(1, 1)), f.x), f.y);
}

// how murky this water is (0 a clear mountain lake .. 1 a swamp or a pond), from the biome's water colour (vanilla's
// tint on the water's vertices): clear water is a saturated blue, murky water a dull green or grey
float murk(vec3 tint) {
    float mx = max(tint.r, max(tint.g, tint.b)), mn = min(tint.r, min(tint.g, tint.b));
    float sat = mx > 1e-3 ? (mx - mn) / mx : 0.0;
    float greenish = clamp((tint.g - tint.b) * 3.0 + 0.3, 0.0, 1.0);
    return clamp((1.0 - sat) * 1.3 + greenish * 0.6 - 0.35, 0.0, 1.0);
}

void main() {
    vec4 color = (UseRgss == 1 ? sampleRGSS(Sampler0, texCoord0, 1.0f / TextureSize)
                               : sampleNearest(Sampler0, texCoord0, 1.0f / TextureSize)) * vertexColor;
    if (color.a < 0.004) discard;
    // the unified field (KeyDir.w 2): atlas draws the water, its surface, the medium and the eye under it; vanilla's
    // water raster stays out (glass, ice and the other translucents are still drawn here)
    if (KeyDir.w > 1.5 && isWater(texCoord0)) discard;
    vec3 n = normalize(cross(dFdx(viewPos), dFdy(viewPos)));
    if (dot(n, viewPos) > 0.0) n = -n;
    vec3 a = abs(n);
    if (max(a.x, max(a.y, a.z)) > 0.99) {  // vanilla's baked face shade out, as in the G-buffer
        float shade = n.y > 0.5 ? 1.0 : n.y < -0.5 ? 0.5 : a.z > 0.5 ? 0.8 : 0.6;
        color.rgb = min(color.rgb / shade, vec3(1.0));
    }
    vec2 lm = clamp(lightLevels / 240.0, 0.0, 1.0);
    vec3 rgb;
    if (isWater(texCoord0) && KeyRad.w > 0.5 && n.y < -0.5) {
        // the surface seen from below (the eye in water): Snell's window. Inside the critical angle (48.6 degrees off
        // the vertical) the world above, refracted (our frame where it has geometry, the sky model where it has none:
        // the frame holds no sky); outside it total internal reflection, the water's own light; then the water
        // between the eye and the surface
        vec3 world = viewPos - CameraOffset + vec3(CameraBlockPos);
        float dist = length(viewPos);
        vec3 v = viewPos / dist;
        float foot = dist * length(fwidth(v)) / max(abs(v.y), 0.05);
        vec3 wb = -waveNormal(world.xz, SunDir.w, foot);  // towards the eye, below
        vec3 t = refract(v, wb, 1.333);
        float R = 1.0;
        vec3 above = vec3(0.0);
        if (dot(t, t) > 1e-6) {
            float ct = max(dot(t, -wb), 0.0);
            R = 0.02 + 0.98 * pow(1.0 - ct, 5.0);
            mat4 vp = ProjMat * ModelViewMat;
            vec4 ct4 = vp * vec4(t * 1000.0, 1.0);
            vec2 uvT = clamp(ct4.xy / max(ct4.w, 1e-3) * 0.5 + 0.5, vec2(0.0), vec2(1.0));
            above = texture(SceneDepth, uvT).r > 0.0 ? texture(SceneColor, uvT).rgb
                                                       : atlasDisplay(atlasSky(SkyView, normalize(t), SunDir.xyz, Look.x)
                                                                      + skyCubeAt(normalize(t)), Look.x, Look.y);
        }
        // outside the window the surface mirrors the water under it (total internal reflection)
        vec4 under = screenReflection(viewPos - wb * 0.05, reflect(v, wb));
        vec3 mirrorBelow = mix(atlasWaterLightAt(atlasEyeDepth() + 4.0), under.rgb, under.a);
        rgb = atlasUnderwater(mix(above, mirrorBelow, R), dist, v);
        fragColor = vec4(rgb, 1.0);
        return;
    }
    if (isWater(texCoord0)) {
        vec3 world = viewPos - CameraOffset + vec3(CameraBlockPos);
        float dist = length(viewPos);
        vec3 v = viewPos / dist;
        float foot = dist * length(fwidth(v)) / max(abs(v.y), 0.05);  // the pixel's footprint on the level surface
        if (false && abs(n.y) < 0.5) {  // (off: it also whitened the steps between flowing water's levels; falls wait for
            // atlas drawing water, which knows a fall's height and its flow)
            // falling water (a flow's side, a fall): white water, streaks running down, mostly opaque, lit like a
            // surface (vanilla's thin sheet over the rock was all but invisible)
            vec2 fu = vec2(dot(world.xz, vec2(-n.z, n.x)), world.y);
            float streak = valueNoise(vec2(fu.x * 4.0, fu.y * 0.6 + SunDir.w * 3.5)) * 0.6
                         + valueNoise(vec2(fu.x * 9.0, fu.y * 1.5 + SunDir.w * 6.0)) * 0.4;
            // white water: aerated, nearly colourless (a breath of the water's tint in the streaks' shade)
            vec3 tintW = mix(vec3(1.0), clamp(vertexColor.rgb * 1.5, 0.0, 1.0), 0.18);
            vec3 white = mix(vec3(0.62, 0.70, 0.74), vec3(0.95), smoothstep(0.3, 0.75, streak)) * tintW;
            vec3 litW = atlasForwardRadianceShadowed(white, n, lm, atlasShadow(viewPos, n));
            rgb = atlasDisplay(litW, Look.x, Look.y);
            float dist2 = length(viewPos);
            rgb = atlasEyeInWater() ? atlasUnderwater(rgb, dist2, viewPos / max(dist2, 1e-3))
                : atlasMedium(rgb, dist2);
            fragColor = vec4(rgb, mix(0.75, 0.95, smoothstep(0.3, 0.8, streak)));
            return;
        }
        vec3 wn = n.y > 0.5 ? waveNormal(world.xz, SunDir.w, foot) : n;
        float cosv = max(dot(-v, wn), 0.0);
        float fresnel = 0.02 + 0.98 * pow(1.0 - cosv, 5.0);
        vec3 r = reflect(v, wn);
        vec3 sky = atlasSky(SkyView, normalize(vec3(r.x, max(r.y, 0.02), r.z)), SunDir.xyz, Look.x) * (lm.y * lm.y);
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
        // clear water absorbs as WATER_ABSORB (the bed shows to ~15 blocks in blue), murky water up to five times that
        // and scatters more of its own colour (a pond's bed is gone within a block or two)
        vec3 tint = clamp(vertexColor.rgb * 1.2, 0.0, 1.0);
        float mk = murk(tint);
        vec3 T = exp(-WATER_ABSORB * (1.0 + 4.0 * mk) * path - 0.04 * (1.0 + 6.0 * mk) * path);
        // the water's own light: the sky over it and the sun through it, scattered back up in the biome's water colour
        // (vanilla's vertex tint), as much as the path is long
        vec3 inLight = (SkyUp.rgb * LOOK_AMBIENT * (lm.y * lm.y) + KeyRad.rgb * max(KeyDir.y, 0.0) * 0.5 * lit * open)
                     * mix(vec3(0.05, 0.11, 0.13), vec3(0.09, 0.11, 0.08), mk) * tint;
        vec3 scatter = atlasDisplay(inLight, Look.x, Look.y) * (vec3(1.0) - T);
        vec3 seen = bed * T + scatter;
        // what the water mirrors: our frame where the reflected ray finds it on screen, the sky model elsewhere
        vec4 hit = screenReflection(viewPos + wn * 0.05, r);
        // the one sky: the air's and its emitters' (the aurora, the stars, the moons) in the same radiance, one display
        vec3 skyShown = atlasDisplay(sky + skyCubeAt(r) * (lm.y * lm.y) + glint, Look.x, Look.y);
        // past the march (far mountains, the far shore): what lies that way is far enough that the mirror shows it as
        // the frame does in the reflected direction (its parallax is nothing at that range); the sky where the frame
        // has none there (the frame's alpha: our resolve leaves the sky out)
        vec4 cd = vp * vec4(r * 4000.0, 1.0);
        // the sky a sheltered reflection shows is partly hidden (trees and banks over the water): vanilla's sky light
        vec3 distant = skyShown * mix(0.55, 1.0, lm.y * lm.y);
        if (cd.w > 0.0) {
            vec2 ud = cd.xy / cd.w * 0.5 + 0.5;
            // off the screen's sides: the frame's edge column stands in (the shore and trees go on past it), not the sky
            // (a bright unshadowed glow at the left and right edges); above its top the sky
            ud.x = clamp(ud.x, 0.003, 0.997);
            if (ud.y > 0.0 && ud.y < 1.0) {
                vec4 f = texture(SceneColor, ud);
                // where the frame has no geometry: the drawn sky there (aurora, stars, moons, later clouds)
                vec3 drawnSky = texture(SkyFrame, ud).rgb;
                distant = mix(drawnSky, f.rgb / max(f.a, 1e-3), clamp(f.a, 0.0, 1.0)) * mix(0.55, 1.0, lm.y * lm.y);
                distant = mix(skyShown, distant, smoothstep(1.0, 0.94, ud.y));
            }
        }
        vec3 mirrored = mix(distant, hit.rgb, hit.a);
        rgb = mix(seen, mirrored, fresnel);
        // the shore: where the water is a hand deep or less, a wet foamy edge drifting with the waves
        // the shore: a thin broken lace of foam where the waves lap in and out over the shallows (the bed shows through
        // the rest of it), never a band
        float lap = 0.12 + 0.1 * sin(SunDir.w * 1.3 + dot(world.xz, vec2(0.7, 0.4)));
        float edge = 1.0 - smoothstep(0.0, lap + 0.12, path);
        float foam = edge * smoothstep(0.55, 0.85, valueNoise(world.xz * 4.0 + SunDir.w * 0.5)) * 0.55;
        // foam is white: lit by the sky's brightness, not its blue (tinted it read as purple squares)
        float skyLum = dot(SkyUp.rgb, vec3(0.2126, 0.7152, 0.0722));
        rgb = mix(rgb, atlasDisplay((vec3(skyLum) * 2.0 + KeyRad.rgb * max(KeyDir.y, 0.0) * lit) * 0.9, Look.x, Look.y),
                  clamp(foam, 0.0, 0.8) * open);
        color.a = 1.0;  // the whole medium is here: nothing behind is blended in again
    } else {
        rgb = atlasForwardLitAt(color.rgb, n, lm, viewPos);
    }
    float dist = length(viewPos);
    rgb = atlasEyeInWater() ? atlasUnderwater(rgb, dist, viewPos / max(dist, 1e-3))
        : atlasMedium(rgb, dist);
    fragColor = vec4(rgb, color.a);
}
