// Java-semantics helpers for the overlays' road stage (atlas_overlays_roads.slang), copied from atlas_gen.slang's
// "exact helpers" block (keep in step): what atlas_map.slang needs from its includer (floorD, divF, divI, jmaxF ...).

// ---- exact helpers (Java semantics) -------------------------------------------------------------------------
float absF(float f) { return asfloat(asuint(f) & 0x7FFFFFFFu); }
double absD(double d) { return d < 0.0 ? -d : (d == 0.0 ? 0.0 : d); }
bool isNaNF(float f) { return f != f; }
// Math.min / Math.max (float)
float jminF(float a, float b) {
    if (a != a) return a;
    if (a == 0.0f && b == 0.0f && asuint(b) == 0x80000000u) return b;
    return a <= b ? a : b;
}
float jmaxF(float a, float b) {
    if (a != a) return a;
    if (a == 0.0f && b == 0.0f && asuint(a) == 0x80000000u) return b;
    return a >= b ? a : b;
}
float clampF(float v, float lo, float hi) { return v < lo ? lo : jminF(v, hi); }  // Mth.clamp(float)
double clampD(double v, double lo, double hi) { return v < lo ? lo : (v <= hi ? v : (v != v ? v : hi)); }
float lerpF(float t, float a, float b) { return a + t * (b - a); }  // Mth.lerp(float)

// (int) Math.floor(d) for |d| < 2^31; anything else flags
int floorD(double d) {
    if (!(d > -2147483647.0l && d < 2147483647.0l)) {
        markBad(1u);
        return 0;
    }
    int i = int(d);
    if (double(i) > d) i = i - 1;
    return i;
}
int floorF(float f) { return floorD(double(f)); }

// a / b correctly rounded (IEEE float division): the quotient from a double division, then the float among it and its
// two neighbours whose residual a - c * b (exact in double: c * b has at most 48 bits and is within a factor of two
// of a) is smallest. A quotient of two floats is never halfway between two floats, so there is no tie.
[noinline]
float divF(float a, float b) {
    if (a != a || b != b) return asfloat(0x7FC00000u);
    float q = float(double(a) / double(b));
    if (b == 0.0f || a == 0.0f || absF(q) > 3.0e38f || absF(q) < 1.0e-36f) {
        if (b == 0.0f) {
            if (a == 0.0f) return asfloat(0x7FC00000u);
            return asfloat(((asuint(a) ^ asuint(b)) & 0x80000000u) | 0x7F800000u);
        }
        if (a == 0.0f) return asfloat((asuint(a) ^ asuint(b)) & 0x80000000u);
        markBad(1u);  // extreme ranges: not proven here
        return q;
    }
    double da = double(a), db = double(b);
    float best = q;
    double r = absD(da - double(q) * db);
    float up = asfloat(asuint(q) + 1u), dn = asfloat(asuint(q) - 1u);
    double ru = absD(da - double(up) * db), rd = absD(da - double(dn) * db);
    if (ru < r) { best = up; r = ru; }
    if (rd < r) { best = dn; }
    return best;
}

// (double) n / (double) d correctly rounded, for integers |n| < 2^31, 0 < d < 2^31: long division to 55 bits + sticky
double divI(int n, int d) {
    if (n == 0) return 0.0;
    bool neg = n < 0;
    uint64_t N = uint64_t(neg ? -int64_t(n) : int64_t(n));
    uint64_t D = uint64_t(d);
    uint64_t q = N / D, r = N - q * D;
    int frac = 0;
    uint64_t lim = uint64_t(1) << 55;
    while (q < lim) {
        r = r << 1;
        q = q << 1;
        if (r >= D) { q = q | 1u; r = r - D; }
        frac++;
    }
    // q has 56+ bits now (55 when the integer part was already big): keep 53, round to nearest even
    int drop = 0;
    uint64_t t = q;
    while (t >= (uint64_t(1) << 53)) { t = t >> 1; drop++; }
    uint64_t rest = q - (t << uint(drop));
    uint64_t half = uint64_t(1) << uint(drop - 1);
    bool sticky = r != 0u;
    if (rest > half || (rest == half && (sticky || (t & 1u) != 0u))) t = t + 1u;
    // value = t * 2^(drop - frac)
    int e = drop - frac;
    double v = double(t);  // t <= 2^53: exact
    // multiply by 2^e (|e| < 1000, no over/underflow for our ranges)
    while (e > 0) { int s = e > 60 ? 60 : e; v = v * double(uint64_t(1) << uint(s)); e -= s; }
    while (e < 0) { int s = -e > 60 ? 60 : -e; v = v * asdouble(0u, uint(1023 - s) << 20); e += s; }
    return neg ? -v : v;
}

// (float) of a non-negative long below 2^63, round to nearest even
float u64ToF(uint64_t v) {
    if (v < (uint64_t(1) << 24)) return float(uint(v));
    int sh = 0;
    uint64_t t = v;
    while (t >= (uint64_t(1) << 24)) { t = t >> 1; sh++; }
    uint64_t rest = v - (t << uint(sh));
    uint64_t half = uint64_t(1) << uint(sh - 1);
    if (rest > half || (rest == half && (t & 1u) != 0u)) t = t + 1u;
    if (t == (uint64_t(1) << 24)) { t = t >> 1; sh++; }
    // t * 2^sh, t < 2^24, sh <= 40: exact in float
    return float(uint(t)) * asfloat(uint(127 + sh) << 23);
}

// Math.round(float) (Java's bit version)
int jroundF(float a) {
    int bits = int(asuint(a));
    int biasedExp = (bits & 0x7F800000) >> 23;
    int shift = (24 - 2 + 127) - biasedExp;
    if ((shift & -32) == 0) {
        int r = (bits & 0x007FFFFF) | 0x00800000;
        if (bits < 0) r = -r;
        return ((r >> shift) + 1) >> 1;
    }
    float t = a;
    if (t != t) return 0;
    if (t >= 2147483647.0f) return 2147483647;
    if (t <= -2147483648.0f) return int(0x80000000u);
    return int(t);
}

// Java's % and floorMod / floorDiv for ints
// (b > 0) divisions of non-negative operands only: drivers disagree on negative ones (found: floorDiv(y, 40))
int floorDivI(int a, int b) { return a >= 0 ? int(uint(a) / uint(b)) : -int((uint(-(a + 1)) / uint(b)) + 1u); }
int jrem(int a, int b) { return a >= 0 ? int(uint(a) % uint(b)) : -int(uint(-a) % uint(b)); }  // Java's %
int floorModI(int a, int b) { return a - floorDivI(a, b) * b; }

