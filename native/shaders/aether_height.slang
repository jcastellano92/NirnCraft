// Exact terrain height (Vulkan compute, and the same source compiled to C++ as the CPU reference): the seeded-noise
// part of the mod's TamrielHeight.height for a batch of columns, bit-identical to the Java original.
//
// Split of the work (see AetherHeightGpu.java): the Java side samples the map (TamrielMap.sample), the road/pad plan
// (PlanGround) and every other term that does not depend on the seeded noises, and hands each column over as a
// record of partial products computed by the very Java expressions of TamrielHeight.height (amp, Math.pow(ridge, 1.3),
// the smoothsteps over map channels, the lake/river caps ...). This kernel evaluates the four seeded noises
// (Minecraft 26.3 NoiseStack of PerlinNoise layers, float arithmetic) and the noise-dependent remainder of height(),
// in Java's exact operation order. Columns this kernel cannot prove exact come back flagged and the caller computes
// them on the CPU.
//
// Why the results are Java's bits (docs/GPU-WORLDGEN-PLAN.md section 4):
//  * Java float/double arithmetic is IEEE binary32/binary64, round to nearest even, denormals kept, no contraction.
//  * Here every arithmetic instruction is NoContraction (slangc -fp-mode precise; spv_exact refuses the build
//    otherwise) and the module requests RoundingModeRTE and SignedZeroInfNanPreserve for 32 and 64 bit (the host
//    refuses devices that do not support both). Only +, -, * and conversions run on the hardware: division is
//    rebuilt exactly from them (divRn), floor by integer truncation, no transcendentals (Math.pow is Java-side).
//  * Denormal handling is NOT relied on (NVIDIA does not offer DenormPreserve): every value is tracked in CF / CD,
//    which flag the column whenever an operation yields a nonzero below 2^-100 (float) / 2^-900 (double), or a
//    zero from nonzero factors. Given operands that are zero or above those bounds, a sum cannot be subnormal
//    (that needs both operands below 2^-103 / 2^-1019), and products are checked, so no denormal is ever an input
//    or an exact result of any unflagged operation: flush-to-zero and denormal-preserving devices agree.
//  * Java's left-to-right evaluation and int/float/double promotion are mirrored literally; double constants are
//    written as their IEEE bit patterns, so no compiler parses decimals.
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
static const double K_TINY = asdouble(0x00000000u, 0x20B00000u);            // 2^-500: exact-division range
static const double K_HUGE = asdouble(0x00000000u, 0x5F300000u);            // 2^500
static const double MIN_D = asdouble(0x00000000u, 0x07B00000u);             // 2^-900: smallest tracked double
static const float MIN_F = asfloat(0x0D800000u);                            // 2^-100: smallest tracked float

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
double absD(double d) {
    uint lo, hi;
    asuint(d, lo, hi);
    return asdouble(lo, hi & 0x7FFFFFFFu);
}
float absF(float f) { return asfloat(asuint(f) & 0x7FFFFFFFu); }
bool isNegZero(double d) { return loWord(d) == 0u && hiWord(d) == 0x80000000u; }
bool isNan(double d) { return d != d; }

// ---- tracked arithmetic: the value plus "this column is not provably exact" -----------------------------------

struct CF {
    float v;
    uint bad;
};
struct CD {
    double v;
    uint bad;
};

uint tinyF(float r) { return (r != 0.0f && absF(r) < MIN_F) ? 1u : 0u; }
uint tinyD(double r) { return (r != 0.0 && absD(r) < MIN_D) ? 1u : 0u; }
CF cf(float v) {
    CF r;
    r.v = v;
    r.bad = tinyF(v) | (v != v ? 1u : 0u);
    return r;
}
CD cd(double v) {
    CD r;
    r.v = v;
    r.bad = tinyD(v) | (isNan(v) ? 1u : 0u);
    return r;
}
CF mkF(float v, uint bad) {
    CF r;
    r.v = v;
    r.bad = bad | tinyF(v);
    return r;
}
CD mkD(double v, uint bad) {
    CD r;
    r.v = v;
    r.bad = bad | tinyD(v);
    return r;
}
uint lostF(float r, float a, float b) { return (r == 0.0f && a != 0.0f && b != 0.0f) ? 1u : 0u; }
uint lostD(double r, double a, double b) { return (r == 0.0 && a != 0.0 && b != 0.0) ? 1u : 0u; }

CF operator+(CF a, CF b) { return mkF(a.v + b.v, a.bad | b.bad); }
CF operator-(CF a, CF b) { return mkF(a.v - b.v, a.bad | b.bad); }
CF operator*(CF a, CF b) {
    float r = a.v * b.v;
    return mkF(r, a.bad | b.bad | lostF(r, a.v, b.v));
}
CF operator+(CF a, float b) { return a + cf(b); }
CF operator-(CF a, float b) { return a - cf(b); }
CF operator*(CF a, float b) { return a * cf(b); }
CF operator*(float a, CF b) { return cf(a) * b; }

CD operator+(CD a, CD b) { return mkD(a.v + b.v, a.bad | b.bad); }
CD operator-(CD a, CD b) { return mkD(a.v - b.v, a.bad | b.bad); }
CD operator*(CD a, CD b) {
    double r = a.v * b.v;
    return mkD(r, a.bad | b.bad | lostD(r, a.v, b.v));
}
CD operator+(CD a, double b) { return a + cd(b); }
CD operator-(CD a, double b) { return a - cd(b); }
CD operator*(CD a, double b) { return a * cd(b); }
CD operator+(double a, CD b) { return cd(a) + b; }
CD operator-(double a, CD b) { return cd(a) - b; }
CD operator*(double a, CD b) { return cd(a) * b; }

CD widen(CF f) { return mkD(double(f.v), f.bad); }  // exact
CD absCD(CD a) { return mkD(absD(a.v), a.bad); }

// java.lang.Math.min / max (NaN and signed zero semantics)
CD jmin(CD a, CD b) {
    uint bad = a.bad | b.bad;
    if (isNan(a.v)) return mkD(a.v, bad);
    if (a.v == b.v && a.v == 0.0 && isNegZero(b.v)) return mkD(b.v, bad);
    return mkD(a.v <= b.v ? a.v : b.v, bad);
}
CD jmax(CD a, CD b) {
    uint bad = a.bad | b.bad;
    if (isNan(a.v)) return mkD(a.v, bad);
    if (a.v == b.v && a.v == 0.0 && isNegZero(a.v)) return mkD(b.v, bad);
    return mkD(a.v >= b.v ? a.v : b.v, bad);
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
CD floorCD(CD d) {
    if (d.v == 0.0) return d;  // keeps -0.0
    uint bad = d.bad;
    int i = floorInt(d.v, bad);
    return mkD(double(i), bad);  // nonzero d: the floor is never a zero of the other sign
}

// ---- exact division ------------------------------------------------------------------------------------------

// Exact products (Dekker / Veltkamp): p + e == a * b exactly, for a, b in [2^-500, 2^500] (no intermediate is
// subnormal there: the smallest, al * bl, is at least 2^-104 * a * b).
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

// aa / ab for aa, ab in [2^-500, 2^500], from any first guess q: one residual correction, then the exact residual
// decides the rounding (IEEE round to nearest even). Flags anything it cannot prove instead of guessing.
double divRnFrom(double aa, double ab, double q, inout uint bad) {
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
    // exact residual of q: aa - p is exact when p is within a factor 2 of aa (Sterbenz); d - e is checked exact
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
        double half = (0.5 * gapUp) * ab;  // exact: a power of two times ab, at least 2^-553
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

// a / b correctly rounded, as Java's ddiv
CD divRn(CD a, CD b) {
    uint bad = a.bad | b.bad;
    if (a.v == 0.0) {
        if (b.v == 0.0 || isNan(b.v)) return mkD(0.0, 1u);
        return mkD((hiWord(b.v) & 0x80000000u) != 0u ? -a.v : a.v, bad);  // signed zero, as IEEE
    }
    double aa = absD(a.v), ab = absD(b.v);
    if (!(aa >= K_TINY && aa <= K_HUGE && ab >= K_TINY && ab <= K_HUGE)) return mkD(0.0, 1u);
    bool neg = ((hiWord(a.v) ^ hiWord(b.v)) & 0x80000000u) != 0u;
    double q = divRnFrom(aa, ab, aa / ab, bad);  // the hardware quotient is only the first guess
    return mkD(neg ? -q : q, bad);
}

// MapNoise.smoothstep(e0, e1, x), exactly as written
CD smoothstepD(double e0, double e1, CD x) {
    CD t = divRn(x - e0, cd(e1 - e0));
    t = t.v < 0.0 ? mkD(0.0, t.bad) : (t.v > 1.0 ? mkD(1.0, t.bad) : t);
    return t * t * (3.0 - 2.0 * t);
}

// ---- Minecraft 26.3 noise: NoiseStack$Perlin of PerlinNoise layers ----------------------------------------------

static const int GRAD[48] = {1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1, 0, 1, 0, 1, -1, 0, 1, 1, 0, -1, -1, 0, -1,
                             0, 1, 1, 0, -1, 1, 0, 1, -1, 0, -1, -1, 1, 1, 0, 0, -1, 1, -1, 1, 0, 0, -1, -1};

CD tabD(uint w) { return cd(asdouble(noiseTab[w], noiseTab[w + 1])); }

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

// GradientNoise.wrap (SharedConstants.DEBUG_ENABLE_FARLANDS is false)
CD wrapD(CD x) {
    if (x.v >= -K_HALF_ROUND_OFF && x.v < K_HALF_ROUND_OFF) return x;
    return x - floorCD(x * K_INV_ROUND_OFF + 0.5) * K_ROUND_OFF;
}

// (float) (X - (double) floor(X)), the lattice fraction
CF fraction(CD X, int i) {
    CD d = X - double(i);
    return mkF(float(d.v), d.bad);  // d is zero or above 2^-100 (else flagged by mkF): the float is never subnormal
}

// PerlinNoise.get(x, y, z) of the layer at word `layer`
CF perlin(uint layer, CD x, CD y, CD z) {
    CD X = wrapD(x) + tabD(layer + 2u);
    CD Y = wrapD(y) + tabD(layer + 4u);
    CD Z = wrapD(z) + tabD(layer + 6u);
    uint bad = X.bad | Y.bad | Z.bad;
    int ix = floorInt(X.v, bad), iy = floorInt(Y.v, bad), iz = floorInt(Z.v, bad);
    CF fx = fraction(X, ix), fy = fraction(Y, iy), fz = fraction(Z, iz);
    fx.bad |= bad;
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

// WorldNoise.getValue: NoiseStack.get(x, y, z) widened to double
CD noise(uint n, CD x, CD y, CD z) {
    uint first = noiseTab[n * 2u], count = noiseTab[n * 2u + 1u];
    CF acc = cf(0.0f);
    for (uint l = 0; l < count; l++) {
        uint layer = 8u + (first + l) * LAYER_WORDS;
        CD f = tabD(layer);
        CF amp = cf(asfloat(noiseTab[layer + 8u]));
        acc = acc + amp * perlin(layer, x * f, y * f, z * f);
    }
    return widen(acc);
}

static const uint N_HILLS = 0, N_DETAIL = 1, N_RIDGES = 2, N_DUNES = 3;

CD colD(uint base, uint i) { return cd(asdouble(columns[base + 2u + i * 2u], columns[base + 3u + i * 2u])); }

// The noise-dependent remainder of TamrielHeight.height(x, z, s), line for line.
CD height(int bx, int bz, uint base, uint flags) {
    CD x = cd(double(bx)), z = cd(double(bz));
    CD zero = cd(0.0);
    CD h = colD(base, C_H0);
    CD hillsN = noise(N_HILLS, x, zero, z);
    CD detailN = noise(N_DETAIL, x, zero, z);
    if ((flags & F_OCEAN) != 0u) {
        // Math.min(h + (hillsN * 5 + detailN * 1.5) * (0.4 + 0.6 * smoothstep(-600, 0, cd)), SEA_LEVEL - 1); the
        // isles are CPU columns
        return jmin(h + (hillsN * 5.0 + detailN * K_1_5) * colD(base, C_AMPR), cd(62.0));
    }
    CD smooth = colD(base, C_SMOOTH);
    // rolling hills: h += amp * s.roughness * (hillsN * 22 + detailN * 6.5 * smooth)
    h = h + colD(base, C_AMPR) * (hillsN * 22.0 + detailN * K_6_5 * smooth);
    if ((flags & F_RIDGE) != 0u) {
        CD r1 = 1.0 - absCD(noise(N_RIDGES, x, zero, z));
        CD r2 = 1.0 - absCD(noise(N_DETAIL, x * K_2_1, cd(37.0), z * K_2_1));
        CD high = 1.0 + K_0_6 * smoothstepD(150.0, 300.0, h);
        h = h + colD(base, C_RIDGEK) * high * (r1 * r1 * 36.0 + r1 * r2 * r2 * 12.0 * colD(base, C_SMOOTHK) - 15.0);
    }
    if ((flags & F_PEAK) != 0u) {
        CD r1 = 1.0 - absCD(noise(N_RIDGES, x * K_1_7, cd(113.0), z * K_1_7));
        CD r2 = 1.0 - absCD(noise(N_DETAIL, x * K_3_3, cd(131.0), z * K_3_3));
        h = h + colD(base, C_PEAKK) * ((r1 * r1 - K_0_45) + K_0_4 * r1 * (r2 * r2 - K_0_45) * colD(base, C_SMOOTHK));
    }
    if ((flags & F_CRAG) != 0u) {
        CD step = 8.0 + 4.0 * hillsN;
        CD off = noise(N_DUNES, x * K_0_22, cd(61.0), z * K_0_22) * 9.0;
        CD t = divRn(h + off, step);
        CD fl = floorCD(t);
        CD terraced = (fl + smoothstepD(K_0_5, K_0_9, t - fl)) * step - off;
        h = h + (terraced - h) * colD(base, C_CRAGW);
    }
    if ((flags & F_DUNES) != 0u) {
        CD dn = noise(N_DUNES, x * K_0_8 + z * K_0_35, zero, z * K_1_7 - x * K_0_2);
        CD crest = 1.0 - absCD(dn);
        h = h + colD(base, C_DUNESK) * (crest * crest * 11.0 - 2.0);
    }
    if ((flags & F_WET) != 0u && h.v < 73.0) {
        CD wl = K_63_4 + detailN * K_1_8 + hillsN * K_0_8 + jmax(zero, hillsN) * K_2_5;
        h = h + (wl - h) * colD(base, C_WETLAND) * K_0_6;
        if ((flags & F_WETCH) != 0u) {
            CD c1 = absCD(noise(N_RIDGES, x * K_1_9, cd(57.0), z * K_1_9));
            CD c2 = absCD(noise(N_HILLS, x * K_3_6 + 400.0, cd(23.0), z * K_3_6)) * K_1_4;
            CD k = 1.0 - divRn(jmin(c1, c2), cd(K_0_11));
            h.bad |= k.bad;
            if (k.v > 0.0) {
                CD bed = K_61_8 - K_2_4 * k;
                CD w = colD(base, C_WETA) * jmin(cd(1.0), k * 3.0) * colD(base, C_WETB);
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
    return jmin(h, cd(568.0));
}

// Test entry (C++ target only, tests/height_test.cpp): records of 4 doubles {a, b, first guess, out}: out =
// divRnFrom(a, b, guess), or -1 when it flagged the pair.
[[vk::binding(5, 0)]] RWStructuredBuffer<double> divTest;
[shader("compute")]
[numthreads(64, 1, 1)]
void divTestMain(uint3 id : SV_DispatchThreadID) {
    uint k = id.x;
    if (k >= uint(params[4])) return;
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
    CD h = mkD(0.0, 1u);
    if ((flags & F_CPU) == 0u) h = height(bx, bz, base, flags);
    double v = h.bad != 0u ? 0.0 : h.v;
    outBits[k * 2u] = loWord(v);
    outBits[k * 2u + 1u] = hiWord(v);
    outFlags[k] = h.bad != 0u ? 1u : 0u;
}
