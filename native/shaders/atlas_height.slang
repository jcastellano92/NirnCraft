// Exact seeded noise for the terrain height (Vulkan compute, and the same source compiled to C++ as the CPU
// reference): the WorldNoise evaluations of the mod's TamrielHeight.height for a batch of columns, bit-identical to
// Minecraft 26.3's NoiseStack of PerlinNoise layers. The Java side (AtlasHeightGpu) runs height() itself and is
// served these values, so everything but the noises stays the mod's own code.
//
// Per column the caller asks for any of the SLOTS noise calls height() can make (S_* below, the coordinates are
// height()'s expressions); slot s of column k is written to outVals[s * count + k] (float bits). A column comes back
// flagged (outFlags[k] = 1) when the kernel cannot prove one of its values exact; the caller then uses the CPU.
//
// Why the values are Java's bits (docs/GPU-WORLDGEN-PLAN.md section 4):
//  * Java float/double arithmetic is IEEE binary32/binary64, round to nearest even, denormals kept, no contraction.
//  * Here every arithmetic instruction is NoContraction (slangc -fp-mode precise; spv_exact refuses the build
//    otherwise) and the module requests RoundingModeRTE and SignedZeroInfNanPreserve for 32 and 64 bit (the host
//    refuses devices without them). Only +, -, *, conversions and comparisons run on the hardware; floor is integer
//    truncation; no division, no transcendentals.
//  * Denormal handling is NOT relied on (NVIDIA offers no DenormPreserve):
//    - the double coordinate path never meets a denormal: block coordinates are integers below 2^31, the slot
//      constants lie in [0.2, 400], layer frequencies and offsets are 0 or in [2^-60, 2^60] (checked on the host),
//      so every product, sum, wrap and lattice fraction is zero or above 2^-120; the fraction is checked to be zero
//      or above 2^-100 before it becomes a float (else the column is flagged);
//    - the float path is tracked (CF): a column is flagged when any operation yields a nonzero below 2^-100 or a
//      zero from nonzero factors. With operands zero or above 2^-100 a sum cannot be subnormal (that needs both
//      below 2^-103) and products are checked, so no denormal is an input or exact result of an unflagged operation:
//      flush-to-zero and denormal-preserving devices agree.
//  * Java's left-to-right evaluation and promotions are mirrored literally; double constants are written as their
//    IEEE bit patterns, so no compiler parses decimals.
//
// Layouts (32-bit words):
//  noise    [0..7] first layer, layer count of hills, detail, ridges, dunes; then 76 words per layer:
//           frequency, offsetX, offsetY, offsetZ (doubles, lo/hi), amplitude (float bits), 3 pad, 256 perm bytes
//  params   (push constants) x0, z0, step, width, count: column k is block (x0 + (k % width) * step, z0 + (k / width) * step)
//  slots    one word per column: bit s = compute slot s
//  outVals  SLOTS * count words, slot-major; outFlags one word per column (1 = flagged; unrequested slots are 0)

struct HeightParams {
    int x0, z0, step, width, count;
};
[[vk::push_constant]] ConstantBuffer<HeightParams> params;
[[vk::binding(0, 0)]] StructuredBuffer<uint> noiseTab;
[[vk::binding(1, 0)]] StructuredBuffer<uint> slots;
[[vk::binding(2, 0)]] RWStructuredBuffer<uint> outVals;
[[vk::binding(3, 0)]] RWStructuredBuffer<uint> outFlags;

static const uint LAYER_WORDS = 76;
static const uint SLOTS = 10;
static const uint N_HILLS = 0, N_DETAIL = 1, N_RIDGES = 2, N_DUNES = 3;

// Java double literals, as their bit patterns
static const double K_2_1 = asdouble(0xCCCCCCCDu, 0x4000CCCCu);   // 2.1
static const double K_1_7 = asdouble(0x33333333u, 0x3FFB3333u);   // 1.7
static const double K_3_3 = asdouble(0x66666666u, 0x400A6666u);   // 3.3
static const double K_0_22 = asdouble(0xC28F5C29u, 0x3FCC28F5u);  // 0.22
static const double K_0_8 = asdouble(0x9999999Au, 0x3FE99999u);   // 0.8
static const double K_0_35 = asdouble(0x66666666u, 0x3FD66666u);  // 0.35
static const double K_0_2 = asdouble(0x9999999Au, 0x3FC99999u);   // 0.2
static const double K_1_9 = asdouble(0x66666666u, 0x3FFE6666u);   // 1.9
static const double K_3_6 = asdouble(0xCCCCCCCDu, 0x400CCCCCu);   // 3.6
static const double K_HALF_ROUND_OFF = asdouble(0xFFFFFFFFu, 0x416FFFFFu);  // Math.nextDown(16777216.0)
static const double K_ROUND_OFF = asdouble(0x00000000u, 0x41800000u);       // 33554432.0 = 2^25
static const double K_INV_ROUND_OFF = asdouble(0x00000000u, 0x3E600000u);   // 2^-25 (x / 2^25 == x * 2^-25 exactly)
static const double MIN_FRACTION = asdouble(0x00000000u, 0x39B00000u);      // 2^-100
static const float MIN_F = asfloat(0x0D800000u);                            // 2^-100: smallest tracked float

float absF(float f) { return asfloat(asuint(f) & 0x7FFFFFFFu); }

// ---- tracked float arithmetic: the value plus "this column is not provably exact" ------------------------------

struct CF {
    float v;
    uint bad;
};

uint tinyF(float r) { return (r != 0.0f && absF(r) < MIN_F) ? 1u : 0u; }
CF cf(float v) {
    CF r;
    r.v = v;
    r.bad = tinyF(v) | (v != v ? 1u : 0u);
    return r;
}
CF mkF(float v, uint bad) {
    CF r;
    r.v = v;
    r.bad = bad | tinyF(v);
    return r;
}

CF operator+(CF a, CF b) { return mkF(a.v + b.v, a.bad | b.bad); }
CF operator-(CF a, CF b) { return mkF(a.v - b.v, a.bad | b.bad); }
CF operator*(CF a, CF b) {
    float r = a.v * b.v;
    return mkF(r, a.bad | b.bad | ((r == 0.0f && a.v != 0.0f && b.v != 0.0f) ? 1u : 0u));
}
CF operator-(CF a, float b) { return a - cf(b); }
CF operator+(CF a, float b) { return a + cf(b); }
CF operator*(CF a, float b) { return a * cf(b); }
CF operator*(float a, CF b) { return cf(a) * b; }

// ---- Minecraft 26.3 noise: NoiseStack$Perlin of PerlinNoise layers ----------------------------------------------

// GradientNoise.GRADIENT
static const int GRAD[48] = {1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1, 0, 1, 0, 1, -1, 0, 1, 1, 0, -1, -1, 0, -1,
                             0, 1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1, 1, 1, 0, 0, -1, 1, -1, 1, 0, 0, -1, -1};

double tabD(uint w) { return asdouble(noiseTab[w], noiseTab[w + 1]); }

int permute(uint layer, int i) {
    uint k = uint(i & 255);
    uint word = noiseTab[layer + 12u + (k >> 2)];
    return int((word >> ((k & 3u) * 8u)) & 255u);
}

// GradientNoise.gradDot: GRADIENT[h & 15].dot(x, y, z) in float
CF gradDot(int h, CF x, CF y, CF z) {
    int g = (h & 15) * 3;
    return float(GRAD[g]) * x + float(GRAD[g + 1]) * y + float(GRAD[g + 2]) * z;
}

// Mth.smoothstep(float), Mth.lerp(float), lerp2, lerp3
CF smoothF(CF t) { return t * t * t * (t * (t * 6.0f - 15.0f) + 10.0f); }
CF lerpF(CF t, CF a, CF b) { return a + t * (b - a); }
CF lerp2F(CF t1, CF t2, CF a, CF b, CF c, CF d) { return lerpF(t2, lerpF(t1, a, b), lerpF(t1, c, d)); }
CF lerp3F(CF t1, CF t2, CF t3, CF a, CF b, CF c, CF d, CF e, CF f, CF g, CF h) {
    return lerpF(t3, lerp2F(t1, t2, a, b, c, d), lerp2F(t1, t2, e, f, g, h));
}

// (int) Math.floor(d) for |d| < 2^31 (anything else is flagged): truncation, one down for negative fractions
int floorInt(double d, inout uint bad) {
    if (!(d > -2147483647.0 && d < 2147483647.0)) {
        bad = 1u;
        return 0;
    }
    int i = int(d);
    if (double(i) > d) i = i - 1;
    return i;
}

// GradientNoise.wrap (SharedConstants.DEBUG_ENABLE_FARLANDS is false: the host checks)
double wrapD(double x, inout uint bad) {
    if (x >= -K_HALF_ROUND_OFF && x < K_HALF_ROUND_OFF) return x;
    double t = x * K_INV_ROUND_OFF + 0.5;  // at least 0.5 in magnitude here
    return x - double(floorInt(t, bad)) * K_ROUND_OFF;  // Math.floor(t) is the int floor: |t| < 2^31 is checked
}

// (float) (X - (double) Mth.floor(X)): the lattice fraction, exact (X - floor(X) is representable)
CF fraction(double X, int i, uint bad) {
    double d = X - double(i);
    if (d != 0.0 && d < MIN_FRACTION) bad = 1u;  // d >= 0: would be a float below 2^-100
    CF r = cf(float(d));
    r.bad |= bad;
    return r;
}

// PerlinNoise.get(x, y, z) of the layer at word `layer`
CF perlin(uint layer, double x, double y, double z) {
    uint bad = 0u;
    double X = wrapD(x, bad) + tabD(layer + 2u);
    double Y = wrapD(y, bad) + tabD(layer + 4u);
    double Z = wrapD(z, bad) + tabD(layer + 6u);
    int ix = floorInt(X, bad), iy = floorInt(Y, bad), iz = floorInt(Z, bad);
    CF fx = fraction(X, ix, bad), fy = fraction(Y, iy, 0u), fz = fraction(Z, iz, 0u);
    // sampleAndLerp(ix, iy, iz, fx, fy, fz, fy)
    int a = permute(layer, ix), b = permute(layer, ix + 1);
    int aa = permute(layer, a + iy), ab = permute(layer, a + iy + 1);
    int ba = permute(layer, b + iy), bb = permute(layer, b + iy + 1);
    CF d000 = gradDot(permute(layer, aa + iz), fx, fy, fz);
    CF d100 = gradDot(permute(layer, ba + iz), fx - 1.0f, fy, fz);
    CF d010 = gradDot(permute(layer, ab + iz), fx, fy - 1.0f, fz);
    CF d110 = gradDot(permute(layer, bb + iz), fx - 1.0f, fy - 1.0f, fz);
    CF d001 = gradDot(permute(layer, aa + iz + 1), fx, fy, fz - 1.0f);
    CF d101 = gradDot(permute(layer, ba + iz + 1), fx - 1.0f, fy, fz - 1.0f);
    CF d011 = gradDot(permute(layer, ab + iz + 1), fx, fy - 1.0f, fz - 1.0f);
    CF d111 = gradDot(permute(layer, bb + iz + 1), fx - 1.0f, fy - 1.0f, fz - 1.0f);
    CF sx = smoothF(fx), sy = smoothF(fy), sz = smoothF(fz);
    return lerp3F(sx, sy, sz, d000, d100, d010, d110, d001, d101, d011, d111);
}

// NoiseStack.get(x, y, z): float sum over the layers, acc + amplitude * layer.get(x * f, y * f, z * f)
CF noise(uint n, double x, double y, double z) {
    uint first = noiseTab[n * 2u], count = noiseTab[n * 2u + 1u];
    CF acc = cf(0.0f);
    for (uint l = 0; l < count; l++) {
        uint layer = 8u + (first + l) * LAYER_WORDS;
        double f = tabD(layer);
        CF amp = cf(asfloat(noiseTab[layer + 8u]));
        acc = acc + amp * perlin(layer, x * f, y * f, z * f);
    }
    return acc;
}

// The noise calls of TamrielHeight.height(x, z, s), with height()'s argument expressions (x, z are the int block
// coordinates widened to double).
CF slot(uint s, double x, double z) {
    switch (s) {
        case 0: return noise(N_HILLS, x, 0.0, z);                                  // hills (x, 0, z)
        case 1: return noise(N_DETAIL, x, 0.0, z);                                 // detail (x, 0, z)
        case 2: return noise(N_RIDGES, x, 0.0, z);                                 // ridge: ridges (x, 0, z)
        case 3: return noise(N_DETAIL, x * K_2_1, 37.0, z * K_2_1);                // ridge: detail (x * 2.1, 37, z * 2.1)
        case 4: return noise(N_RIDGES, x * K_1_7, 113.0, z * K_1_7);               // peak: ridges (x * 1.7, 113, z * 1.7)
        case 5: return noise(N_DETAIL, x * K_3_3, 131.0, z * K_3_3);               // peak: detail (x * 3.3, 131, z * 3.3)
        case 6: return noise(N_DUNES, x * K_0_22, 61.0, z * K_0_22);               // crag: dunes (x * 0.22, 61, z * 0.22)
        case 7: return noise(N_DUNES, x * K_0_8 + z * K_0_35, 0.0, z * K_1_7 - x * K_0_2);  // dunes
        case 8: return noise(N_RIDGES, x * K_1_9, 57.0, z * K_1_9);                // wetland: ridges (x * 1.9, 57, z * 1.9)
        default: return noise(N_HILLS, x * K_3_6 + 400.0, 23.0, z * K_3_6);        // wetland: hills (x * 3.6 + 400, 23, z * 3.6)
    }
}

// One thread per (slot, column), slot-major: a warp works on one slot of neighbouring columns (uniform branches).
[shader("compute")]
[numthreads(64, 1, 1)]
void heightMain(uint3 id : SV_DispatchThreadID) {
    uint count = uint(params.count);
    uint s = id.x / count, k = id.x - s * count;
    if (s >= SLOTS) return;
    int width = params.width;
    int bx = params.x0 + int(k % uint(width)) * params.step;
    int bz = params.z0 + int(k / uint(width)) * params.step;
    CF v = cf(0.0f);
    if ((slots[k] & (1u << s)) != 0u) v = slot(s, double(bx), double(bz));
    outVals[s * count + k] = v.bad != 0u ? 0u : asuint(v.v);
    if (v.bad != 0u) outFlags[k] = 1u;  // racing threads only ever store 1; the host clears the flags
}
