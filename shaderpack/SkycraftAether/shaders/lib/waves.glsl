// Water surface: a sum of waves in scattered directions whose size follows the body of water and the weather: open
// water (sea, big lakes) swells, sheltered ponds barely ripple, storms raise everything; rivers flow.
// Each wave is exp(sin) shaped (sharp crests, broad troughs, smooth everywhere) and drags the next ones' phase a
// little, so crests bunch like real chop instead of crossing as straight lines. The spectrum runs from a long swell
// down to sub-block ripples; a wave is dropped before it shrinks under a few pixels, and the slope it carried becomes
// roughness (waveRoughness), so far water turns into a broad glitter instead of shimmering.
#ifndef SKYCRAFT_WAVES
#define SKYCRAFT_WAVES

#include "/lib/noise.glsl"

#define WAVE_COUNT (WATER_WAVES * 4 + 4)  // 8 / 12 / 16

// blocks per pixel at `dist` (70 degree fov at about 1080 rows; close enough to set wave LOD)
float waterFootprint(float dist) { return 0.02 + dist * 0.0013; }

float waveLongest(float openness) { return mix(4.0, 26.0, openness); }

// amplitude of the first (longest) wave in blocks
float waveAmp(float openness, float storm) { return mix(0.006, 0.07, openness) * (1.0 + 1.6 * storm) * sqrt(12.0 / float(WAVE_COUNT)); }

// height (x) and its gradient d/dx, d/dz (yz) of the wave field. openness: 0 a sheltered pond .. 1 the open sea (from
// how much sky the surface sees and how big the body is); storm 0..1; flow: flow direction * speed in blocks/s
// (rivers; 0 for still water)
vec3 waveField(vec2 p, float time, float openness, float storm, vec2 flow, float dist) {
    p -= flow * time;
    float wl = waveLongest(openness);
    float decay = pow(0.7 / wl, 1.0 / float(WAVE_COUNT - 1));  // down to 0.7-block ripples
    float amp = waveAmp(openness, storm);
    float foot = waterFootprint(dist);
    float drag = 0.08 + 0.3 * openness;
    float h = 0.0;
    vec2 g = vec2(0.0);
    float seed = 0.7;
    for (int i = 0; i < 16; i++) {
        if (i >= WAVE_COUNT) break;
        float fade = smoothstep(3.0 * foot, 8.0 * foot, wl);
        if (fade <= 0.0) break;  // the rest are shorter still
        vec2 d = vec2(sin(seed), cos(seed));
        float k = 6.2831853 / wl;
        float x = dot(d, p) * k + time * sqrt(9.81 * k);  // deep-water dispersion
        float e = exp(sin(x) - 1.0);
        float de = e * cos(x);
        float a = amp * fade;
        h += a * e;
        g += a * de * k * d;
        p -= d * de * a * drag * 6.0;  // drag: later waves ride on this one
        wl *= decay;
        amp *= decay;
        seed += 1232.399963;
    }
    return vec3(h, g);
}

float waveHeight(vec2 p, float time, float openness, float storm, vec2 flow, float dist) {
    return waveField(p, time, openness, storm, flow, dist).x;
}

// surface normal of the wave field (world, y up)
vec3 waveNormal(vec2 p, float time, float openness, float storm, vec2 flow, float dist) {
    vec2 g = waveField(p, time, openness, storm, flow, dist).yz;
    // rivers: a flow-aligned ripple on top, stronger where the water runs fast (rapids)
    float speed = length(flow);
    if (speed > 0.01) {
        vec2 q = p - flow * time * 1.3;
        float fade = 1.0 - smoothstep(0.07, 0.2, waterFootprint(dist));  // ~0.6-block ripples
        g += (vec2(valueNoise(q * 1.7), valueNoise(q * 1.7 + 19.3)) - 0.5) * min(speed * 0.08, 0.5) * fade;
    }
    return normalize(vec3(-g.x, 1.0, -g.y));
}

// specular roughness of the water at `dist`: calm glassy water up close; the slope of the waves too small to resolve
// widens the highlight with distance (a glitter path to the sun instead of sparkling pixels)
float waveRoughness(float openness, float storm, float dist) {
    float foot = waterFootprint(dist);
    float wl = waveLongest(openness);
    // slope each wave carries is about amp * k (the same for every wave: amp and wl decay together)
    float slope = waveAmp(openness, storm) * 6.2831853 / wl * 0.6;
    float lost = 0.0;
    float decay = pow(0.7 / wl, 1.0 / float(WAVE_COUNT - 1));
    for (int i = 0; i < 16; i++) {
        if (i >= WAVE_COUNT) break;
        lost += 1.0 - smoothstep(3.0 * foot, 8.0 * foot, wl);
        wl *= decay;
    }
    // GGX alpha ~ sqrt(2) * the rms slope of what is no longer drawn (aether's far water uses the same: aether_far.slang
    // farWater); the pack's roughness is perceptual (alpha = rough^2)
    float alpha = sqrt(0.00005 + 2.0 * slope * slope * lost) + storm * 0.03;
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
