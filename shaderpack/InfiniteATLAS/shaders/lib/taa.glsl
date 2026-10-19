// Temporal anti-aliasing (TAA): each frame the whole scene is drawn a fraction of a pixel off (an 8-frame Halton
// (2, 3) sequence: gbuffers move their vertices, the mod's far field its rays by atlas_set_jitter) and composite1.fsh
// blends it into the history of the frames before, found where this pixel was last frame (near field: its depth;
// far field: atlas's distance; sky: its direction). Edges, thin distant detail and the per-pixel dithers then
// average over the pixel instead of stepping or crawling. Clipping the history to what this frame's neighbourhood
// can be keeps moving things from smearing.
#ifndef NIRNCRAFT_TAA
#define NIRNCRAFT_TAA

#include "/lib/modtex.glsl"

uniform int frameCounter;

float taaHalton(int i, int b) {
    float f = 1.0, r = 0.0;
    while (i > 0) {
        f /= float(b);
        r += f * float(i % b);
        i /= b;
    }
    return r;
}

// this frame's offset in pixels (-0.5 .. 0.5), none without TAA
vec2 taaJitter() {
#ifdef TAA
    // the mod's index (data texel 20,0) when it is there: its far field takes the same offset this frame
    int i = frameCounter % 8 + 1;
    if (haveSmoke()) {
        vec4 t = texelFetch(smokeSourcesTex, ivec2(20, 0), 0);
        if (t.a > 0.5) i = int(t.r * 255.0 + 0.5) % 8 + 1;
    }
    return vec2(taaHalton(i, 2), taaHalton(i, 3)) - 0.5;
#else
    return vec2(0.0);
#endif
}

// a phase for screen-fixed noise (0 .. 7 with TAA, 0 without): the resolve averages a pattern that moves
float taaPhase() {
#ifdef TAA
    return float(frameCounter % 8);
#else
    return 0.0;
#endif
}

#endif
