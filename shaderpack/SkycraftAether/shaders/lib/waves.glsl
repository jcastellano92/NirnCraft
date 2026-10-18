// Water surface: a sum of waves whose size and heading follow the body of water and the live weather (lib/weather.glsl
// waterWind): the long swell runs with the wind, shorter chop spreads wider around it; open water (sea, big lakes)
// swells, sheltered ponds barely ripple, gales raise everything; rivers flow; rain rings the surface.
// Each wave is exp(sin) shaped (sharp crests, broad troughs, smooth everywhere) and drags the next ones' phase a
// little, so crests bunch like real chop instead of crossing as straight lines. A slow world-space envelope gathers
// the waves into groups and calmer patches, so no stretch of water repeats. The spectrum runs from a long swell down
// to sub-block ripples; a wave is dropped before it shrinks under a few pixels, and the slope it carried becomes
// roughness (waveRoughness), so far water turns into a broad glitter instead of shimmering.
// aether's far water (aether_far.slang farWaveNormal) evaluates the same spectrum at the default 12 waves: keep the
// constants, the spread table and the envelope in step.
#ifndef SKYCRAFT_WAVES
#define SKYCRAFT_WAVES

#include "/lib/noise.glsl"

#define WAVE_COUNT (WATER_WAVES * 4 + 4)  // 8 / 12 / 16

// heading of each wave around the wind, in quarter turns at full spread (long waves keep close to the wind)
const float WAVE_SPREAD[16] = float[16](0.0, 0.35, -0.42, 0.6, -0.7, 0.18, -0.25, 0.85, -0.9, 0.5, -0.55, 0.05,
                                        0.95, -0.38, 0.72, -0.8);

// blocks per pixel at `dist` (70 degree fov at about 1080 rows; close enough to set wave LOD)
float waterFootprint(float dist) { return 0.02 + dist * 0.0013; }

// wind.y = windiness 0..1: a fresher wind builds longer, taller waves
float waveLongest(float openness, float windiness) { return mix(4.0, 26.0, openness) * (0.6 + 0.8 * windiness); }

// amplitude of the first (longest) wave in blocks
float waveAmp(float openness, float windiness) {
    return mix(0.006, 0.07, openness) * (0.35 + 1.9 * windiness) * sqrt(12.0 / float(WAVE_COUNT));
}

// wave groups: a slow, drifting envelope (0.55 .. 1.45) over a few hundred blocks
float waveGroups(vec2 p, float time) {
    return 0.55 + 0.9 * (valueNoise(p / 170.0 + time * 0.015) * 0.65 + valueNoise(p / 61.0 - time * 0.03) * 0.35);
}

// height (x) and its gradient d/dx, d/dz (yz) of the wave field. openness: 0 a sheltered pond .. 1 the open sea (from
// how much sky the surface sees and how big the body is); wind: (heading radians, windiness 0..1); flow: flow direction
// * speed in blocks/s (rivers; 0 for still water)
vec3 waveField(vec2 p, float time, float openness, vec2 wind, vec2 flow, float dist) {
    p -= flow * time;
    float wl = waveLongest(openness, wind.y);
    float decay = pow(0.7 / wl, 1.0 / float(WAVE_COUNT - 1));  // down to 0.7-block ripples
    float amp = waveAmp(openness, wind.y) * waveGroups(p, time);
    float foot = waterFootprint(dist);
    float drag = 0.08 + 0.3 * openness;
    float h = 0.0;
    vec2 g = vec2(0.0);
    for (int i = 0; i < 16; i++) {
        if (i >= WAVE_COUNT) break;
        float fade = smoothstep(3.0 * foot, 8.0 * foot, wl);
        if (fade <= 0.0) break;  // the rest are shorter still
        float spread = 0.25 + 0.75 * float(i) / float(WAVE_COUNT - 1);
        float ang = wind.x + WAVE_SPREAD[i] * spread * 1.5707963;
        vec2 d = vec2(cos(ang), sin(ang));
        float k = 6.2831853 / wl;
        float x = dot(d, p) * k - time * sqrt(9.81 * k);  // deep-water dispersion, travelling along d
        float e = exp(sin(x) - 1.0);
        float de = e * cos(x);
        float a = amp * fade;
        h += a * e;
        g += a * de * k * d;
        p -= d * de * a * drag * 6.0;  // drag: later waves ride on this one
        wl *= decay;
        amp *= decay;
    }
    return vec3(h, g);
}

float waveHeight(vec2 p, float time, float openness, vec2 wind, vec2 flow, float dist) {
    return waveField(p, time, openness, wind, flow, dist).x;
}

// rain on the water: expanding rings from drops on a jittered grid (two layers), as a slope; fades out by ~40 blocks
vec2 rainRipples(vec2 p, float time, float rain, float dist) {
    float fade = rain * (1.0 - smoothstep(12.0, 40.0, dist));
    if (fade <= 0.0) return vec2(0.0);
    vec2 g = vec2(0.0);
    for (int layer = 0; layer < 2; layer++) {
        vec2 q = p * (layer == 0 ? 1.0 : 1.37) + float(layer) * 17.3;
        // the four cells around q; a ring grows to 0.5 blocks, so no drop outside them can reach it
        vec2 base = floor(q - 0.5);
        for (int k = 0; k < 4; k++) {
            vec2 c = base + vec2(k & 1, k >> 1);
            vec2 centre = c + 0.25 + 0.5 * vec2(hash12(c), hash12(c + 7.7));
            float phase = fract(time * 0.9 + hash12(c + 3.1));  // each drop lands once a period, at its own time
            vec2 r = q - centre;
            float d = length(r);
            float radius = phase * 0.5;
            float ring = (d - radius) * 14.0;
            float w = exp(-ring * ring) * (1.0 - phase) * (1.0 - phase);
            g += (d > 1e-3 ? r / d : vec2(0.0)) * cos(ring * 1.6) * w * 0.35;
        }
    }
    return g * fade;
}

// surface normal of the wave field (world, y up); rain: 0..1 adds the drop rings where rain reaches the water
vec3 waveNormal(vec2 p, float time, float openness, vec2 wind, vec2 flow, float dist, float rain) {
    vec2 g = waveField(p, time, openness, wind, flow, dist).yz;
    // rivers: a flow-aligned ripple on top, stronger where the water runs fast (rapids)
    float speed = length(flow);
    if (speed > 0.01) {
        vec2 q = p - flow * time * 1.3;
        float fade = 1.0 - smoothstep(0.07, 0.2, waterFootprint(dist));  // ~0.6-block ripples
        g += (vec2(valueNoise(q * 1.7), valueNoise(q * 1.7 + 19.3)) - 0.5) * min(speed * 0.08, 0.5) * fade;
    }
    g += rainRipples(p, time, rain, dist);
    return normalize(vec3(-g.x, 1.0, -g.y));
}

// specular roughness of the water at `dist`: calm glassy water up close; the slope of the waves too small to resolve
// widens the highlight with distance (a glitter path to the sun instead of sparkling pixels)
float waveRoughness(float openness, vec2 wind, float dist) {
    float foot = waterFootprint(dist);
    float wl = waveLongest(openness, wind.y);
    // slope each wave carries is about amp * k (the same for every wave: amp and wl decay together)
    float slope = waveAmp(openness, wind.y) * 6.2831853 / wl * 0.6;
    float lost = 0.0;
    float decay = pow(0.7 / wl, 1.0 / float(WAVE_COUNT - 1));
    for (int i = 0; i < 16; i++) {
        if (i >= WAVE_COUNT) break;
        lost += 1.0 - smoothstep(3.0 * foot, 8.0 * foot, wl);
        wl *= decay;
    }
    // GGX alpha ~ sqrt(2) * the rms slope of what is no longer drawn (aether's far water uses the same: aether_far.slang
    // farWaterAlpha); the pack's roughness is perceptual (alpha = rough^2)
    float alpha = sqrt(0.00005 + 2.0 * slope * slope * lost) + wind.y * wind.y * 0.03;
    return clamp(sqrt(alpha), 0.07, 0.6);
}

// caustics on the bed under water: the focusing of the waves, as a cheap cellular pattern
float caustics(vec2 p, float time) {
    vec2 q = p * 0.7;
    float c = 0.0;
    for (int i = 0; i < 2; i++) {
        vec2 g = floor(q), f = fract(q);
        float md = 1.0;
        for (int y = -1; y <= 1; y++)
            for (int x = -1; x <= 1; x++) {
                vec2 o = vec2(x, y);
                vec2 r = o + 0.5 + 0.45 * sin(time * 0.9 + 6.2831 * vec2(hash12(g + o), hash12(g + o + 13.0))) - f;
                md = min(md, dot(r, r));
            }
        c += pow(1.0 - clamp(sqrt(md), 0.0, 1.0), 6.0);
        q = q * 1.7 + 3.1;
    }
    return c;
}

#endif
