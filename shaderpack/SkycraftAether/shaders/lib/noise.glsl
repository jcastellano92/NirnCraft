// Small hash / value-noise helpers shared by wind, water, sky and fog.
#ifndef SKYCRAFT_NOISE
#define SKYCRAFT_NOISE

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float hash13(vec3 p) {
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);
    return fract((p.x + p.y) * p.z);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash12(i), b = hash12(i + vec2(1, 0)), c = hash12(i + vec2(0, 1)), d = hash12(i + vec2(1, 1));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm2(vec2 p, int octaves) {
    float s = 0.0, a = 0.5, n = 0.0;
    for (int i = 0; i < octaves; i++) {
        s += a * valueNoise(p);
        n += a;
        p = p * 2.03 + vec2(17.1, 9.3);
        a *= 0.5;
    }
    return s / n;
}

// interleaved gradient noise: a stable per-pixel dither
float ign(vec2 px, float frame) {
    px += 5.588238 * mod(frame, 64.0);
    return fract(52.9829189 * fract(0.06711056 * px.x + 0.00583715 * px.y));
}

#endif
