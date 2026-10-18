// The eye: physically motivated auto-exposure. composite1.fsh measures the scene's centre-weighted log-average
// luminance from colortex4's mip chain (each tap already the mean of a ~32 x 32 pixel block), turns it into a target
// exposure and adapts towards it at texel 0 of colortex8 (R32F, clear = false: it persists from frame to frame);
// final.fsh exposes the HDR scene by it before the tone curve.
//  * partial adaptation (EXPOSURE_SLOPE < 1): a cave or a night stays darker than noon, as eyes see them
//  * clamped: the darkest scenes are lifted at most EXPOSURE_MAX (dark but readable caves and moonless nights; the
//    daylight outside a cave mouth then blows out), the brightest dimmed at most to EXPOSURE_MIN (snow, sun glare)
//  * asymmetric like eyes: bright after dark settles in about a second (ADAPT_BRIGHTER), dark after bright in three
//    to four (ADAPT_DARKER)
// Cost: one pixel does the 54-tap reduction; every pixel writes 4 bytes of colortex8.
#ifndef SKYCRAFT_EXPOSURE
#define SKYCRAFT_EXPOSURE

const float EXPOSURE_FIXED = -0.3219;  // log2(0.8): the pack's exposure before the eye adapted (and AUTO_EXPOSURE off)
const float EXPOSURE_KEY = -0.79;      // log2 exposure at a scene log-average of 1.0 (pack_lab: noon land keeps the old x0.8)
const float EXPOSURE_SLOPE = 0.72;     // how much of a luminance change the eye undoes (1 = all of it)
const float EXPOSURE_MIN = -1.6;       // log2: the most the eye stops down (x0.33)
const float EXPOSURE_MAX = 2.3;        // log2: the most it opens (x4.9)
const float ADAPT_BRIGHTER = 0.32;     // seconds (time constant) when the scene brightens: settles in ~1 s
const float ADAPT_DARKER = 1.1;        // seconds when it darkens: ~3.5 s

// the adapted exposure (log2) stored at texel 0, or none (-999) before the first frame
float exposureStored(sampler2D t) {
    float v = texelFetch(t, ivec2(0), 0).r;
    return v > 1.0 && v < 128.0 ? v - 64.0 : -999.0;
}

// the exposure (log2) final.fsh applies
float exposureNow(sampler2D t) {
#ifdef AUTO_EXPOSURE
    float e = exposureStored(t);
    return e > -998.0 ? e : EXPOSURE_FIXED;
#else
    return EXPOSURE_FIXED;
#endif
}

// the scene's centre-weighted log2 luminance: 9 x 6 taps of the mip level whose long side is ~32 texels
float sceneLogLuminance(sampler2D hdr, vec2 view) {
    float lod = max(0.0, ceil(log2(max(view.x, view.y))) - 5.0);
    float sum = 0.0, wsum = 0.0;
    for (int j = 0; j < 6; j++) {
        for (int i = 0; i < 9; i++) {
            vec2 uv = (vec2(float(i), float(j)) + 0.5) / vec2(9.0, 6.0);
            vec2 c = (uv - 0.5) * 2.0;
            float w = exp(-dot(c, c) * 1.5);  // what the eye looks at counts most
            float l = dot(textureLod(hdr, uv, lod).rgb, vec3(0.2126, 0.7152, 0.0722));
            sum += w * clamp(log2(max(l, 1e-5)), -16.0, 5.0);
            wsum += w;
        }
    }
    return sum / wsum;
}

float exposureTarget(float logL) {
    return clamp(EXPOSURE_KEY - EXPOSURE_SLOPE * logL, EXPOSURE_MIN, EXPOSURE_MAX);
}

// one frame of adaptation from prev towards target (log2), dt seconds
float exposureAdapt(float prev, float target, float dt) {
    if (prev < -998.0) return target;
    float tau = target < prev ? ADAPT_BRIGHTER : ADAPT_DARKER;
    return prev + (target - prev) * (1.0 - exp(-clamp(dt, 0.0, 0.25) / tau));
}

#endif
