// Exact terrain height (Vulkan compute, and the same source compiled to C++ as the CPU reference): the seeded-noise
// part of the mod's TamrielHeight.height for a batch of columns, bit-identical to the Java original.
//
// Split of the work (see AetherHeightGpu.java): the Java side samples the map (TamrielMap.sample), the road/pad plan
// (PlanGround) and every other term that does not depend on the seeded noises, and hands each column over as a
// record of partial products computed by the very Java expressions of TamrielHeight.height (amp, Math.pow(ridge, 1.3),
// the smoothsteps over map channels, the lake/river caps ...). This kernel evaluates the four seeded noises
// (Minecraft 26.3 NoiseStack of PerlinNoise layers, float arithmetic) and the noise-dependent remainder of height(),
// in Java's exact operation order. Columns this kernel cannot prove exact (an operand out of the range the exact
// division and floor below are proven for) come back flagged and the caller computes them on the CPU.
//
// Exactness rules (docs/GPU-WORLDGEN-PLAN.md section 4):
//  * every arithmetic instruction is NoContraction (spv_exact post-pass), float controls: RTE rounding, denormals
//    and signed zero / inf / nan preserved, for 32 and 64 bit (the host refuses devices without them)
//  * only +, -, * and conversions on the hardware; division is computed exactly from them (divRn), floor by integer
//    truncation; no transcendentals (Java's Math.pow is done on the Java side)
//  * Java's left-to-right evaluation and int/float/double promotion are mirrored literally; double constants are
//    given as their IEEE bit patterns, so no compiler parses decimals
//
// Layouts (32-bit words):
//  noise   [0..7]  first layer, layer count of hills, detail, ridges, dunes; then 76 words per layer:
//          frequency, offsetX, offsetY, offsetZ (doubles, lo/hi), amplitude (float bits), 3 pad, 256 perm bytes
//  columns 40 words per column: flags, 0, then 19 doubles (see the C_* indices)
//  params  [0] x0, [1] z0, [2] step, [3] width, [4] count: column k is block (x0 + (k % width) * step, z0 + (k / width) * step)
//  out     2 words per column: the height (double bits); outFlags: 1 = not computed here (CPU column or unproven)

[[vk::binding(0, 0)]] StructuredBuffer<int> params;
[[vk::binding(1, 0)]] StructuredBuffer<uint> noiseTab;
[[vk::binding(2, 0)]] StructuredBuffer<uint> columns;
[[vk::binding(3, 0)]] RWStructuredBuffer<uint> outBits;
[[vk::binding(4, 0)]] RWStructuredBuffer<uint> outFlags;

static const uint LAYER_WORDS = 76;
static const uint COLUMN_WORDS = 40;

// column flags (AetherHeightGpu.java)
static const uint F_OCEAN = 1u, F_RIDGE = 2u, F_PEAK = 4u, F_CRAG = 8u, F_DUNES = 16u, F_WET = 32u, F_WETCH = 64u,
                  F_LAKE_MIN = 128u, F_LAKE_MAX = 256u, F_RIVER_MIN = 512u, F_RIVER_MAX = 1024u, F_PAD = 2048u,
                  F_PLOT = 4096u, F_BED = 8192u, F_CPU = 16384u;
// column doubles
static const uint C_H0 = 0, C_AMPR = 1, C_SMOOTH = 2, C_SMOOTHK = 3, C_RIDGEK = 4, C_PEAKK = 5, C_CRAGW = 6,
                  C_DUNESK = 7, C_WETLAND = 8, C_WETA = 9, C_WETB = 10, C_LAKE = 11, C_RIVER = 12, C_PADH = 13,
                  C_WPAD = 14, C_PLOTH = 15, C_WPLOT = 16, C_BEDH = 17, C_WBED = 18;

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
static const double K_0_45 = asdouble(0xCCCCCCCDu, 0x3FDCCCCCu);  // 0.45
static const double K_0_4 = asdouble(0x9999999Au, 0x3FD99999u);   // 0.4
static const double K_0_6 = asdouble(0x33333333u, 0x3FE33333u);   // 0.6
static const double K_6_5 = asdouble(0x00000000u, 0x401A0000u);   // 6.5
static const double K_1_5 = asdouble(0x00000000u, 0x3FF80000u);   // 1.5
static const double K_1_8 = asdouble(0xCCCCCCCDu, 0x3FFCCCCCu);   // 1.8
static const double K_2_5 = asdouble(0x00000000u, 0x40040000u);   // 2.5
static const double K_63_4 = asdouble(0x33333333u, 0x404FB333u);  // 63 + 0.4 (javac folds SEA_LEVEL + 0.4)
static const double K_61_8 = asdouble(0x66666666u, 0x404EE666u);  // 63 - 1.2
static const double K_2_4 = asdouble(0x33333333u, 0x40033333u);   // 2.4
static const double K_0_11 = asdouble(0xC28F5C29u, 0x3FBC28F5u);  // 0.11
static const double K_1_4 = asdouble(0x66666666u, 0x3FF66666u);   // 1.4
static const double K_0_9 = asdouble(0xCCCCCCCDu, 0x3FECCCCCu);   // 0.9
static const double K_0_5 = asdouble(0x00000000u, 0x3FE00000u);   // 0.5
static const double K_HALF_ROUND_OFF = asdouble(0xFFFFFFFFu, 0x416FFFFFu);  // Math.nextDown(16777216.0)
static const double K_ROUND_OFF = asdouble(0x00000000u, 0x41800000u);       // 33554432.0 = 2^25
static const double K_INV_ROUND_OFF = asdouble(0x00000000u, 0x3E600000u);   // 2^-25 (x / 2^25 == x * 2^-25 exactly)
static const double K_SPLIT = asdouble(0x02000000u, 0x41A00000u);           // 2^27 + 1 (Veltkamp)
static const double K_TINY = asdouble(0x00000000u, 0x00B00000u);            // 2^-500: exact-division range
static const double K_HUGE = asdouble(0x00000000u, 0x7F300000u);            // 2^500

uint hiWord(double d) {
    uint lo, hi;
    asuint(d, lo, hi);
    return hi;
}
uint loWord(double d) {
    uint lo, hi;
    asuint(d, lo, hi);
    return lo;
}
double dAbs(double d) {
    uint lo, hi;
    asuint(d, lo, hi);
    return asdouble(lo, hi & 0x7FFFFFFFu);
}
bool isNegZero(double d) { return loWord(d) == 0u && hiWord(d) == 0x80000000u; }
bool isNan(double d) { return d != d; }

// java.lang.Math.min / max (NaN and signed zero semantics)
double jmin(double a, double b) {
    if (isNan(a)) return a;
    if (a == b && a == 0.0 && isNegZero(b)) return b;
    return a <= b ? a : b;
}
double jmax(double a, double b) {
    if (isNan(a)) return a;
    if (a == b && a == 0.0 && isNegZero(a)) return b;
    return a >= b ? a : b;
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
// Math.floor(d) as a double, same range
double floorD(double d, inout uint bad) {
    if (d == 0.0) return d;  // keeps -0.0
    int i = floorInt(d, bad);
    return double(i);  // nonzero d: the floor is never a zero of the other sign
}

// Exact products (Dekker / Veltkamp): p + e == a * b exactly, for |a|, |b| in the guarded range.
void twoProd(double a, double b, out double p, out double e) {
    p = a * b;
    double ca = K_SPLIT * a;
    double ah = ca - (ca - a);
    double al = a - ah;
    double cb = K_SPLIT * b;
    double bh = cb - (cb - b);
    double bl = b - bh;
    e = ((ah * bh - p) + ah * bl + al * bh) + al * bl;
}

// a / b correctly rounded (IEEE round to nearest even, as Java's ddiv), from correctly rounded + - * only: the
// hardware quotient is just a first guess; one residual correction, then an exact residual decides the rounding.
// Flags (bad = 1) anything outside the proven range instead of guessing.
double divRn(double a, double b, inout uint bad) {
    if (a == 0.0) {
        if (b == 0.0 || isNan(b)) {
            bad = 1u;
            return 0.0;
        }
        return (hiWord(b) & 0x80000000u) != 0u ? -a : a;  // signed zero, as IEEE
    }
    double aa = dAbs(a), ab = dAbs(b);
    if (!(aa >= K_TINY && aa <= K_HUGE && ab >= K_TINY && ab <= K_HUGE)) {
        bad = 1u;
        return 0.0;
    }
    bool neg = ((hiWord(a) ^ hiWord(b)) & 0x80000000u) != 0u;
    double q = divRnFrom(aa, ab, aa / ab, bad);  // the hardware quotient is only the first guess
    return neg ? -q : q;
}

// aa / ab for positive aa, ab in the guarded range, from any first guess q
double divRnFrom(double aa, double ab, double q, inout uint bad) {
    // one correction step
    if (!(q >= K_TINY && q <= K_HUGE)) {
        bad = 1u;
        return 0.0;
    }
    double p, e;
    twoProd(q, ab, p, e);
    double r = (aa - p) - e;
    q = q + r / ab;
    if (!(q >= K_TINY && q <= K_HUGE)) {
        bad = 1u;
        return 0.0;
    }
    // exact residual of q: aa - p is exact when p is within a factor 2 of aa (Sterbenz); the second subtraction is
    // checked to be exact
    twoProd(q, ab, p, e);
    if (!(p >= 0.5 * aa && p <= 2.0 * aa)) {
        bad = 1u;
        return 0.0;
    }
    double d = aa - p;
    r = d - e;
    // Knuth's TwoSum error of d + (-e): zero iff r is the exact residual aa - q * ab
    double bv = r - d;
    if ((d - (r - bv)) + (-e - bv) != 0.0) {
        bad = 1u;
        return 0.0;
    }
    // neighbours of q (q is positive and normal here)
    uint qlo = loWord(q), qhi = hiWord(q);
    uint ulo = qlo + 1u, uhi = qhi + (ulo == 0u ? 1u : 0u);
    uint dlo = qlo - 1u, dhi = qhi - (qlo == 0u ? 1u : 0u);
    double up = asdouble(ulo, uhi), dn = asdouble(dlo, dhi);
    double gapUp = up - q, gapDn = q - dn;  // exact (adjacent doubles)
    if (r > 0.0) {
        double half = (0.5 * gapUp) * ab;  // exact: power of two times ab
        if (!(r < 2.0 * half)) {
            bad = 1u;  // q was not within one ulp: never expected, refuse rather than guess
            return 0.0;
        }
        if (r > half || (r == half && (qlo & 1u) != 0u)) q = up;
    } else if (r < 0.0) {
        double half = (0.5 * gapDn) * ab;
        if (!(-r < 2.0 * half)) {
            bad = 1u;
            return 0.0;
        }
        if (-r > half || (-r == half && (qlo & 1u) != 0u)) q = dn;
    }
    return q;
}

// MapNoise.smoothstep(e0, e1, x) and clamp, exactly as written
double smoothstepD(double e0, double e1, double x, inout uint bad) {
    double t = divRn(x - e0, e1 - e0, bad);
    t = t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
    return t * t * (3.0 - 2.0 * t);
}

// ---- Minecraft 26.3 noise: NoiseStack$Perlin of PerlinNoise layers ----------------------------------------------

static const int GRAD[48] = {1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1, 0, 1, 0, 1, -1, 0, 1, 1, 0, -1, -1, 0, -1,
                             0, 1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1, 1, 1, 0, 0, -1, 1, -1, 1, 0, 0, -1, -1};

double tabD(uint w) { return asdouble(noiseTab[w], noiseTab[w + 1]); }

int permute(uint layer, int i) {
    uint k = uint(i & 255);
    uint word = noiseTab[layer + 12u + (k >> 2)];
    return int((word >> ((k & 3u) * 8u)) & 255u);
}

// GradientNoise.gradDot: GRADIENT[h & 15].dot(x, y, z) in float
float gradDot(int h, float x, float y, float z) {
    int g = (h & 15) * 3;
    return float(GRAD[g]) * x + float(GRAD[g + 1]) * y + float(GRAD[g + 2]) * z;
}

// Mth.smoothstep(float), Mth.lerp(float), lerp2, lerp3
float smoothF(float t) { return t * t * t * (t * (t * 6.0f - 15.0f) + 10.0f); }
float lerpF(float t, float a, float b) { return a + t * (b - a); }
float lerp2F(float t1, float t2, float a, float b, float c, float d) {
    return lerpF(t2, lerpF(t1, a, b), lerpF(t1, c, d));
}
float lerp3F(float t1, float t2, float t3, float a, float b, float c, float d, float e, float f, float g, float h) {
    return lerpF(t3, lerp2F(t1, t2, a, b, c, d), lerp2F(t1, t2, e, f, g, h));
}

// GradientNoise.wrap (SharedConstants.DEBUG_ENABLE_FARLANDS is false)
double wrapD(double x, inout uint bad) {
    if (x >= -K_HALF_ROUND_OFF && x < K_HALF_ROUND_OFF) return x;
    return x - floorD(x * K_INV_ROUND_OFF + 0.5, bad) * K_ROUND_OFF;
}

// PerlinNoise.get(x, y, z) of the layer at word `layer`
float perlin(uint layer, double x, double y, double z, inout uint bad) {
    double X = wrapD(x, bad) + tabD(layer + 2u);
    double Y = wrapD(y, bad) + tabD(layer + 4u);
    double Z = wrapD(z, bad) + tabD(layer + 6u);
    int ix = floorInt(X, bad), iy = floorInt(Y, bad), iz = floorInt(Z, bad);
    float fx = float(X - double(ix)), fy = float(Y - double(iy)), fz = float(Z - double(iz));
    // sampleAndLerp(ix, iy, iz, fx, fy, fz, fy)
    int a = permute(layer, ix), b = permute(layer, ix + 1);
    int aa = permute(layer, a + iy), ab = permute(layer, a + iy + 1);
    int ba = permute(layer, b + iy), bb = permute(layer, b + iy + 1);
    float d000 = gradDot(permute(layer, aa + iz), fx, fy, fz);
    float d100 = gradDot(permute(layer, ba + iz), fx - 1.0f, fy, fz);
    float d010 = gradDot(permute(layer, ab + iz), fx, fy - 1.0f, fz);
    float d110 = gradDot(permute(layer, bb + iz), fx - 1.0f, fy - 1.0f, fz);
    float d001 = gradDot(permute(layer, aa + iz + 1), fx, fy, fz - 1.0f);
    float d101 = gradDot(permute(layer, ba + iz + 1), fx - 1.0f, fy, fz - 1.0f);
    float d011 = gradDot(permute(layer, ab + iz + 1), fx, fy - 1.0f, fz - 1.0f);
    float d111 = gradDot(permute(layer, bb + iz + 1), fx - 1.0f, fy - 1.0f, fz - 1.0f);
    float sx = smoothF(fx), sy = smoothF(fy), sz = smoothF(fz);
    return lerp3F(sx, sy, sz, d000, d100, d010, d110, d001, d101, d011, d111);
}

// WorldNoise.getValue: NoiseStack.get(x, y, z) widened to double
double noise(uint n, double x, double y, double z, inout uint bad) {
    uint first = noiseTab[n * 2u], count = noiseTab[n * 2u + 1u];
    float acc = 0.0f;
    for (uint l = 0; l < count; l++) {
        uint layer = 8u + (first + l) * LAYER_WORDS;
        double f = tabD(layer);
        float amp = asfloat(noiseTab[layer + 8u]);
        acc = acc + amp * perlin(layer, x * f, y * f, z * f, bad);
    }
    return double(acc);
}

static const uint N_HILLS = 0, N_DETAIL = 1, N_RIDGES = 2, N_DUNES = 3;

double colD(uint base, uint i) { return asdouble(columns[base + 2u + i * 2u], columns[base + 3u + i * 2u]); }

// The noise-dependent remainder of TamrielHeight.height(x, z, s), line for line.
double height(int bx, int bz, uint base, uint flags, inout uint bad) {
    double x = double(bx), z = double(bz);
    double h = colD(base, C_H0);
    double hillsN = noise(N_HILLS, x, 0.0, z, bad);
    double detailN = noise(N_DETAIL, x, 0.0, z, bad);
    if ((flags & F_OCEAN) != 0u) {
        // Math.min(h + (hillsN * 5 + detailN * 1.5) * (0.4 + 0.6 * smoothstep(-600, 0, cd)), SEA_LEVEL - 1); the
        // isles are CPU columns
        return jmin(h + (hillsN * 5.0 + detailN * K_1_5) * colD(base, C_AMPR), 62.0);
    }
    double smooth = colD(base, C_SMOOTH);
    // rolling hills: h += amp * s.roughness * (hillsN * 22 + detailN * 6.5 * smooth)
    h = h + colD(base, C_AMPR) * (hillsN * 22.0 + detailN * K_6_5 * smooth);
    if ((flags & F_RIDGE) != 0u) {
        double r1 = 1.0 - dAbs(noise(N_RIDGES, x, 0.0, z, bad));
        double r2 = 1.0 - dAbs(noise(N_DETAIL, x * K_2_1, 37.0, z * K_2_1, bad));
        double high = 1.0 + K_0_6 * smoothstepD(150.0, 300.0, h, bad);
        h = h + colD(base, C_RIDGEK) * high * (r1 * r1 * 36.0 + r1 * r2 * r2 * 12.0 * colD(base, C_SMOOTHK) - 15.0);
    }
    if ((flags & F_PEAK) != 0u) {
        double r1 = 1.0 - dAbs(noise(N_RIDGES, x * K_1_7, 113.0, z * K_1_7, bad));
        double r2 = 1.0 - dAbs(noise(N_DETAIL, x * K_3_3, 131.0, z * K_3_3, bad));
        h = h + colD(base, C_PEAKK) * ((r1 * r1 - K_0_45) + K_0_4 * r1 * (r2 * r2 - K_0_45) * colD(base, C_SMOOTHK));
    }
    if ((flags & F_CRAG) != 0u) {
        double step = 8.0 + 4.0 * hillsN;
        double off = noise(N_DUNES, x * K_0_22, 61.0, z * K_0_22, bad) * 9.0;
        double t = divRn(h + off, step, bad);
        double fl = floorD(t, bad);
        double terraced = (fl + smoothstepD(K_0_5, K_0_9, t - fl, bad)) * step - off;
        h = h + (terraced - h) * colD(base, C_CRAGW);
    }
    if ((flags & F_DUNES) != 0u) {
        double dn = noise(N_DUNES, x * K_0_8 + z * K_0_35, 0.0, z * K_1_7 - x * K_0_2, bad);
        double crest = 1.0 - dAbs(dn);
        h = h + colD(base, C_DUNESK) * (crest * crest * 11.0 - 2.0);
    }
    if ((flags & F_WET) != 0u && h < 73.0) {
        double wl = K_63_4 + detailN * K_1_8 + hillsN * K_0_8 + jmax(0.0, hillsN) * K_2_5;
        h = h + (wl - h) * colD(base, C_WETLAND) * K_0_6;
        if ((flags & F_WETCH) != 0u) {
            double c1 = dAbs(noise(N_RIDGES, x * K_1_9, 57.0, z * K_1_9, bad));
            double c2 = dAbs(noise(N_HILLS, x * K_3_6 + 400.0, 23.0, z * K_3_6, bad)) * K_1_4;
            double k = 1.0 - divRn(jmin(c1, c2), K_0_11, bad);
            if (k > 0.0) {
                double bed = K_61_8 - K_2_4 * k;
                double w = colD(base, C_WETA) * jmin(1.0, k * 3.0) * colD(base, C_WETB);
                h = h + (jmin(bed, h) - h) * w;
            }
        }
    }
    if ((flags & F_LAKE_MIN) != 0u) h = jmin(h, colD(base, C_LAKE));
    if ((flags & F_LAKE_MAX) != 0u) h = jmax(h, colD(base, C_LAKE));
    if ((flags & F_RIVER_MIN) != 0u) h = jmin(h, colD(base, C_RIVER));
    if ((flags & F_RIVER_MAX) != 0u) h = jmax(h, colD(base, C_RIVER));
    if ((flags & F_PAD) != 0u) h = h + (colD(base, C_PADH) - h) * colD(base, C_WPAD);
    if ((flags & F_PLOT) != 0u) h = h + (colD(base, C_PLOTH) - h) * colD(base, C_WPLOT);
    if ((flags & F_BED) != 0u) h = h + (colD(base, C_BEDH) - h) * colD(base, C_WBED);
    return jmin(h, 568.0);
}

// Test entry (C++ target only, tests/height_test.cpp): records of 4 doubles {a, b, first guess, out}: out =
// divRnFrom(a, b, guess), or -1 when it flagged the pair.
[[vk::binding(5, 0)]] RWStructuredBuffer<double> divTest;
[shader("compute")]
[numthreads(64, 1, 1)]
void divTestMain(uint3 id : SV_DispatchThreadID) {
    uint k = id.x;
    if (k * 4u + 3u >= uint(params[4]) * 4u) return;
    uint bad = 0u;
    double q = divRnFrom(divTest[k * 4u], divTest[k * 4u + 1u], divTest[k * 4u + 2u], bad);
    divTest[k * 4u + 3u] = bad != 0u ? -1.0 : q;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void heightMain(uint3 id : SV_DispatchThreadID) {
    uint k = id.x;
    if (k >= uint(params[4])) return;
    int width = params[3];
    int bx = params[0] + int(k % uint(width)) * params[2];
    int bz = params[1] + int(k / uint(width)) * params[2];
    uint base = k * COLUMN_WORDS;
    uint flags = columns[base];
    uint bad = 0u;
    double h = 0.0;
    if ((flags & F_CPU) != 0u) bad = 1u;
    else h = height(bx, bz, base, flags, bad);
    if (bad != 0u) h = 0.0;
    outBits[k * 2u] = loWord(h);
    outBits[k * 2u + 1u] = hiWord(h);
    outFlags[k] = bad;
}
