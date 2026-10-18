// Adaptive quality v2: continuous knobs the Skycraft mod's controller writes every tick, so the pack scales its loops
// and distances at runtime without recompiling (Iris rebuilds the whole pack when an option changes: a stutter).
// Docs: docs/ADAPTIVE-QUALITY.md. Writer: com.skycraft.render.quality.QualityTexture.
//
//   qualityKnobs   16x1 RGBA8   quality.png   texelFetch only; byte / 255 = knob 0..1 (r is the low byte)
//     texel 0   r 165 (magic), g 1 (layout version), b overall level (information), a 255
//     texel 1   r shadow taps            g shadow softness (penumbra radius, 0.6 .. 1 of SHADOW_SOFTNESS)
//     texel 2   r SSAO samples
//     texel 3   r SSR steps
//     texel 4   r light-shaft samples
//     texel 5   r wave count (4 .. WAVE_COUNT)  g wave LOD (1 = the profile's footprint, 0 = waves fade 2x sooner)
//               b caustics (on above 0.5)        a falls spray taps
//     texel 6   r air march steps        g halo steps            b smoke-column steps
//     texel 7   r fallback cloud octaves (the pack's deck without aether)
//     texel 8   r bloom strength
//     texel 9   r detail falloff start (16 .. 192 blocks)   g counts far away (0.3 .. 1 of the near count)
//     10..15    reserved (0)
//
// 1.0 means the profile's #define (the loop bound, a ceiling); counts scale down from it. Without the texture (the mod
// absent, an old build, the blank placeholder) every knob reads its fallback: QUALITY_FALLBACK (settings.glsl) for the
// counts, which is 1.0 for the fixed profiles (their exact counts) and 0.65 for ADAPTIVE (ULTRA bounds, about HIGH).
#ifndef SKYCRAFT_QUALITY
#define SKYCRAFT_QUALITY

uniform sampler2D qualityKnobs;

bool qualityLive() {
#ifdef ADAPTIVE_QUALITY
    ivec2 s = textureSize(qualityKnobs, 0);
    if (s.x != 16 || s.y != 1) return false;
    vec2 h = texelFetch(qualityKnobs, ivec2(0, 0), 0).rg * 255.0;
    return abs(h.x - 165.0) < 0.5 && abs(h.y - 1.0) < 0.5;
#else
    return false;
#endif
}

// knob `channel` (0 r .. 3 a) of `texel`, or `fallback` without the mod's controller
float qualityKnob(int texel, int channel, float fallback) {
    if (!qualityLive()) return fallback;
    vec4 t = texelFetch(qualityKnobs, ivec2(texel, 0), 0);
    return channel == 0 ? t.r : channel == 1 ? t.g : channel == 2 ? t.b : t.a;
}

// how much of the near count a pixel `dist` blocks away keeps: 1 nearer than the falloff start, easing to the far
// fraction by 4x the start (detail goes first where it shows least). 1 everywhere without the controller.
float qualityDistance(float dist) {
    if (!qualityLive()) return 1.0;
    vec4 t = texelFetch(qualityKnobs, ivec2(9, 0), 0);
    float start = mix(16.0, 192.0, t.r);
    return mix(1.0, mix(0.3, 1.0, t.g), smoothstep(start, start * 4.0, dist));
}

// a loop count: maxN x frac, rounded with a per-pixel dither (0..1) so fractional counts blend between neighbours
// instead of stepping over the whole screen at once; clamped to [minN, maxN]
int qualityCount(int maxN, float frac, float dither, int minN) {
    return clamp(int(floor(float(maxN) * clamp(frac, 0.0, 1.0) + dither)), minN, maxN);
}

// how far a feature has faded in: knobs between 0 and a feature's first useful level (`on`) only pass through on
// their way on or off, and the feature's contribution fades with them instead of popping
float qualityFade(float knob, float on) {
    return clamp(knob / on, 0.0, 1.0);
}

// a spatially stable dither for counts (no frame term: the pattern does not crawl)
float qualityDither(vec2 px) {
    return fract(52.9829189 * fract(dot(floor(px) + vec2(37.0, 11.0), vec2(0.06711056, 0.00583715))) + 0.381966);
}

#endif
