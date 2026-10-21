// Exact double and float arithmetic for the GPU (atlas_gen, atlas_map): Java's results bit for bit, from the IEEE
// operations a Vulkan device with RTE and signed zero / inf / NaN preservation does exactly (+, -, *, conversions,
// comparisons) and nothing else. Division and square root are correctly rounded here (a device's are not): an
// approximation, then the neighbour whose residual (exact: Dekker's split product, no fused multiply-add) is
// smallest. The transcendentals are fdlibm's (Java's StrictMath: java.lang.FdLibm), ported operation for
// operation; Java's Math.pow / exp / sin / cos are CPU intrinsics that differ from it in the last bit for about one
// argument in ten, so the generator calls StrictMath for these (docs/ATLAS-CLIENT-GENERATION.md).
//
// fdlibm: Copyright (C) 1993 by Sun Microsystems, Inc. All rights reserved. Developed at SunSoft, a Sun Microsystems,
// Inc. business. Permission to use, copy, modify, and distribute this software is freely granted, provided that this
// notice is preserved.
//
// Every function takes `inout uint g_bad` where an argument lies outside what is proven here (subnormal results,
// sin/cos beyond 2^20 pi / 2): the caller flags the chunk and the server's blocks correct it.

// the flag every function sets where an argument lies outside what is proven (was an inout parameter: Slang
// inlines a callee wherever one is forwarded, which made the page kernels megabytes of SPIR-V); a thread's own,
// cleared at the start of each kernel
static uint g_bad = 0u;

int hiW(double x) {
    uint lo, hi;
    asuint(x, lo, hi);
    return int(hi);
}
int loW(double x) {
    uint lo, hi;
    asuint(x, lo, hi);
    return int(lo);
}
double hiLo(int hi, int lo) { return asdouble(uint(lo), uint(hi)); }
double setHiW(double x, int hi) { return asdouble(uint(loW(x)), uint(hi)); }
double setLoW(double x, int lo) { return asdouble(uint(lo), uint(hiW(x))); }
int ushr(int a, int b) { return int(uint(a) >> uint(b)); }  // Java's >>>

bool isNaND(double x) { return x != x; }
bool isFiniteD(double x) { return (hiW(x) & 0x7ff00000) != 0x7ff00000; }
double absD2(double x) { return asdouble(uint(loW(x)), uint(hiW(x) & 0x7fffffff)); }  // Math.abs(double)
double nextUpD(double x) {  // finite, nonzero x
    uint64_t b = (uint64_t(uint(hiW(x))) << 32) | uint64_t(uint(loW(x)));
    b = x > 0.0l ? b + 1u : b - 1u;
    return asdouble(uint(b & 0xFFFFFFFFu), uint(b >> 32));
}
double nextDownD(double x) {
    uint64_t b = (uint64_t(uint(hiW(x))) << 32) | uint64_t(uint(loW(x)));
    b = x > 0.0l ? b - 1u : b + 1u;
    return asdouble(uint(b & 0xFFFFFFFFu), uint(b >> 32));
}

// a * b = p + e exactly (Dekker; |a|, |b| < 2^996)
static const double SPLITTER = 134217729.0l;  // 2^27 + 1
void twoProd(double a, double b, out double p, out double e) {
    p = a * b;
    double ca = SPLITTER * a, ah = ca - (ca - a), al = a - ah;
    double cb = SPLITTER * b, bh = cb - (cb - b), bl = b - bh;
    e = ((ah * bh - p) + ah * bl + al * bh) + al * bl;
}
// |a - c * b|, exact enough to rank neighbouring c (c * b within a factor 2 of a)
double residual(double a, double c, double b) {
    double p, e;
    twoProd(c, b, p, e);
    return absD2((a - p) - e);
}

// a / b, correctly rounded (Java's double division). A quotient is never halfway between two doubles: no ties.
[noinline]
double divD(double a, double b) {
    double q = a / b;
    if (!isFiniteD(q) || q == 0.0l || !isFiniteD(a) || !isFiniteD(b)) return q;  // the exact special cases
    int e = (hiW(q) >> 20) & 0x7ff, ea = (hiW(a) >> 20) & 0x7ff, eb = (hiW(b) >> 20) & 0x7ff;
    if (e < 60 || e > 2000 || ea < 60 || ea > 1990 || eb < 60 || eb > 1990) { g_bad = g_bad | 1u; return q; }
    double best = q, r = residual(a, q, b);
    double u = nextUpD(q), d = nextDownD(q);
    double ru = residual(a, u, b), rd = residual(a, d, b);
    if (ru < r) { best = u; r = ru; }
    if (rd < r) best = d;
    // two steps off (a device's division within 2 ulp): one more round from the better side
    if (best != q) {
        double u2 = best > q ? nextUpD(best) : nextDownD(best);
        if (residual(a, u2, b) < residual(a, best, b)) best = u2;
    }
    return best;
}

// Math.sqrt(x), correctly rounded (Newton from the halved exponent, then the neighbour with the smallest residual)
[noinline]
double sqrtD(double x) {
    if (!(x > 0.0l) || !isFiniteD(x)) {
        if (x == 0.0l) return x;  // +-0
        if (x != x || x < 0.0l) return asdouble(0u, 0x7ff80000u);
        return x;  // +inf
    }
    int e = (hiW(x) >> 20) & 0x7ff;
    if (e < 60 || e > 1990) { g_bad = g_bad | 1u; return 0.0l; }
    // a guess with half the exponent, then Newton (approximate divisions are fine here)
    double s = asdouble(0u, uint(((e - 1023) / 2 + 1023) << 20));
    for (int i = 0; i < 7; i++) s = 0.5l * (s + x / s);
    double best = s, r = residual(x, s, s);
    for (int k = 0; k < 2; k++) {
        double u = nextUpD(best), d = nextDownD(best);
        double ru = residual(x, u, u), rd = residual(x, d, d);
        double nb = best, nr = r;
        if (ru < nr) { nb = u; nr = ru; }
        if (rd < nr) { nb = d; nr = rd; }
        best = nb;
        r = nr;
    }
    return best;
}

// ---- fdlibm (java.lang.FdLibm) ----------------------------------------------------------------------------------

static const int EXP_BITS = 0x7ff00000, EXP_SIGNIF_BITS = 0x7fffffff;
static const double HUGE_D = 1.0e+300l;

static const double S1 = asdouble(0x55555549u, 0xBFC55555u), S2 = asdouble(0x1110F8A6u, 0x3F811111u), S3 = asdouble(0x19C161D5u, 0xBF2A01A0u),
                    S4 = asdouble(0x57B1FE7Du, 0x3EC71DE3u), S5 = asdouble(0x8A2B9CEBu, 0xBE5AE5E6u), S6 = asdouble(0x5ACFD57Cu, 0x3DE5D93Au);
double kernelSin(double x, double y, int iy) {
    int ix = hiW(x) & EXP_SIGNIF_BITS;
    if (ix < 0x3e400000) {
        if (int(x) == 0) return x;
    }
    double z = x * x;
    double v = z * x;
    double r = S2 + z * (S3 + z * (S4 + z * (S5 + z * S6)));
    if (iy == 0) return x + v * (S1 + z * r);
    return x - ((z * (0.5l * y - v * r) - y) - v * S1);
}

static const double C1 = asdouble(0x5555554Cu, 0x3FA55555u), C2 = asdouble(0x16C15177u, 0xBF56C16Cu), C3 = asdouble(0x19CB1590u, 0x3EFA01A0u),
                    C4 = asdouble(0x809C52ADu, 0xBE927E4Fu), C5 = asdouble(0xBDB4B1C4u, 0x3E21EE9Eu), C6 = asdouble(0xBE8838D4u, 0xBDA8FAE9u);
double kernelCos(double x, double y) {
    int ix = hiW(x) & EXP_SIGNIF_BITS;
    if (ix < 0x3e400000) {
        if (int(x) == 0) return 1.0l;
    }
    double z = x * x;
    double r = z * (C1 + z * (C2 + z * (C3 + z * (C4 + z * (C5 + z * C6)))));
    if (ix < 0x3FD33333) return 1.0l - (0.5l * z - (z * r - x * y));
    double qx;
    if (ix > 0x3fe90000) qx = 0.28125l;
    else qx = hiLo(ix - 0x00200000, 0);
    double hz = 0.5l * z - qx;
    double a = 1.0l - qx;
    return a - (hz - (z * r - x * y));
}

static const int NPIO2_HW[32] = {
    0x3FF921FB, 0x400921FB, 0x4012D97C, 0x401921FB, 0x401F6A7A, 0x4022D97C, 0x4025FDBB, 0x402921FB, 0x402C463A,
    0x402F6A7A, 0x4031475C, 0x4032D97C, 0x40346B9C, 0x4035FDBB, 0x40378FDB, 0x403921FB, 0x403AB41B, 0x403C463A,
    0x403DD85A, 0x403F6A7A, 0x40407E4C, 0x4041475C, 0x4042106C, 0x4042D97C, 0x4043A28C, 0x40446B9C, 0x404534AC,
    0x4045FDBB, 0x4046C6CB, 0x40478FDB, 0x404858EB, 0x404921FB};
static const double INVPIO2 = asdouble(0x6DC9C883u, 0x3FE45F30u), PIO2_1 = asdouble(0x54400000u, 0x3FF921FBu), PIO2_1T = asdouble(0x1A626331u, 0x3DD0B461u),
                    PIO2_2 = asdouble(0x1A600000u, 0x3DD0B461u), PIO2_2T = asdouble(0x2E037073u, 0x3BA3198Au), PIO2_3 = asdouble(0x2E000000u, 0x3BA3198Au),
                    PIO2_3T = asdouble(0x252049C1u, 0x397B839Au);
// __ieee754_rem_pio2 for |x| <= 2^19 pi / 2 (beyond: g_bad)
[noinline]
int remPio2(double x, out double y0, out double y1) {
    int hx = hiW(x);
    int ix = hx & EXP_SIGNIF_BITS;
    y0 = x;
    y1 = 0.0l;
    if (ix <= 0x3fe921fb) return 0;
    if (ix < 0x4002d97c) {
        double z;
        if (hx > 0) {
            z = x - PIO2_1;
            if (ix != 0x3ff921fb) {
                y0 = z - PIO2_1T;
                y1 = (z - y0) - PIO2_1T;
            } else {
                z = z - PIO2_2;
                y0 = z - PIO2_2T;
                y1 = (z - y0) - PIO2_2T;
            }
            return 1;
        }
        z = x + PIO2_1;
        if (ix != 0x3ff921fb) {
            y0 = z + PIO2_1T;
            y1 = (z - y0) + PIO2_1T;
        } else {
            z = z + PIO2_2;
            y0 = z + PIO2_2T;
            y1 = (z - y0) + PIO2_2T;
        }
        return -1;
    }
    if (ix <= 0x413921fb) {
        double t = absD2(x);
        int n = int(t * INVPIO2 + 0.5l);
        double fn = double(n);
        double r = t - fn * PIO2_1;
        double w = fn * PIO2_1T;
        if (n < 32 && ix != NPIO2_HW[n - 1]) {
            y0 = r - w;
        } else {
            int j = ix >> 20;
            y0 = r - w;
            int i = j - (((hiW(y0)) >> 20) & 0x7ff);
            if (i > 16) {
                t = r;
                w = fn * PIO2_2;
                r = t - w;
                w = fn * PIO2_2T - ((t - r) - w);
                y0 = r - w;
                i = j - (((hiW(y0)) >> 20) & 0x7ff);
                if (i > 49) {
                    t = r;
                    w = fn * PIO2_3;
                    r = t - w;
                    w = fn * PIO2_3T - ((t - r) - w);
                    y0 = r - w;
                }
            }
        }
        y1 = (r - y0) - w;
        if (hx < 0) {
            y0 = -y0;
            y1 = -y1;
            return -n;
        }
        return n;
    }
    if (ix >= EXP_BITS) {
        y0 = x - x;
        y1 = y0;
        return 0;
    }
    g_bad = g_bad | 1u;  // |x| > 2^19 pi / 2: __kernel_rem_pio2 is not ported
    return 0;
}

[noinline]
double sinS(double x) {  // StrictMath.sin
    int ix = hiW(x) & EXP_SIGNIF_BITS;
    if (ix <= 0x3fe921fb) return kernelSin(x, 0.0l, 0);
    if (ix >= EXP_BITS) return x - x;
    double y0, y1;
    int n = remPio2(x, y0, y1);
    switch (n & 3) {
        case 0: return kernelSin(y0, y1, 1);
        case 1: return kernelCos(y0, y1);
        case 2: return -kernelSin(y0, y1, 1);
        default: return -kernelCos(y0, y1);
    }
}
[noinline]
double cosS(double x) {  // StrictMath.cos
    int ix = hiW(x) & EXP_SIGNIF_BITS;
    if (ix <= 0x3fe921fb) return kernelCos(x, 0.0l);
    if (ix >= EXP_BITS) return x - x;
    double y0, y1;
    int n = remPio2(x, y0, y1);
    switch (n & 3) {
        case 0: return kernelCos(y0, y1);
        case 1: return -kernelSin(y0, y1, 1);
        case 2: return -kernelCos(y0, y1);
        default: return kernelSin(y0, y1, 1);
    }
}

static const uint ATANHI_W[8] = {0x0561BB4Fu, 0x3FDDAC67u, 0x54442D18u, 0x3FE921FBu, 0xD281F69Bu, 0x3FEF730Bu, 0x54442D18u, 0x3FF921FBu};
double ATANHI(int i) { return asdouble(ATANHI_W[i * 2], ATANHI_W[i * 2 + 1]); }
static const uint ATANLO_W[8] = {0x222F65E2u, 0x3C7A2B7Fu, 0x33145C07u, 0x3C81A626u, 0x7AF0CBBDu, 0x3C700788u, 0x33145C07u, 0x3C91A626u};
double ATANLO(int i) { return asdouble(ATANLO_W[i * 2], ATANLO_W[i * 2 + 1]); }
static const uint AT_W[22] = {0x5555550Du, 0x3FD55555u, 0x9998EBC4u, 0xBFC99999u, 0x920083FFu, 0x3FC24924u, 0xFE231671u, 0xBFBC71C6u, 0xC54C206Eu, 0x3FB745CDu, 0xAF749A6Du, 0xBFB3B0F2u, 0xA0D03D51u, 0x3FB10D66u, 0x52DEFD9Au, 0xBFADDE2Du, 0x24760DEBu, 0x3FA97B4Bu, 0x2C6A6C2Fu, 0xBFA2B444u, 0xE322DA11u, 0x3F90AD3Au};
double AT(int i) { return asdouble(AT_W[i * 2], AT_W[i * 2 + 1]); }
[noinline]
double atanS(double x) {  // StrictMath.atan
    int hx = hiW(x);
    int ix = hx & EXP_SIGNIF_BITS;
    int id;
    if (ix >= 0x44100000) {
        if (ix > EXP_BITS || (ix == EXP_BITS && (loW(x) != 0))) return x + x;
        if (hx > 0) return ATANHI(3) + ATANLO(3);
        return -ATANHI(3) - ATANLO(3);
    }
    if (ix < 0x3fdc0000) {
        if (ix < 0x3e200000) {
            if (HUGE_D + x > 1.0l) return x;
        }
        id = -1;
    } else {
        x = absD2(x);
        if (ix < 0x3ff30000) {
            if (ix < 0x3fe60000) {
                id = 0;
                x = divD(2.0l * x - 1.0l, 2.0l + x);
            } else {
                id = 1;
                x = divD(x - 1.0l, x + 1.0l);
            }
        } else {
            if (ix < 0x40038000) {
                id = 2;
                x = divD(x - 1.5l, 1.0l + 1.5l * x);
            } else {
                id = 3;
                x = divD(-1.0l, x);
            }
        }
    }
    double z = x * x;
    double w = z * z;
    double s1 = z * (AT(0) + w * (AT(2) + w * (AT(4) + w * (AT(6) + w * (AT(8) + w * AT(10))))));
    double s2 = w * (AT(1) + w * (AT(3) + w * (AT(5) + w * (AT(7) + w * AT(9)))));
    if (id < 0) return x - x * (s1 + s2);
    z = ATANHI(id) - ((x * (s1 + s2) - ATANLO(id)) - x);
    return (hx < 0) ? -z : z;
}

static const double TINY_D = 1.0e-300l, PI_O_4 = asdouble(0x54442D18u, 0x3FE921FBu), PI_O_2 = asdouble(0x54442D18u, 0x3FF921FBu),
                    PI_LO = asdouble(0x33145C07u, 0x3CA1A626u), PI_D = asdouble(0x54442D18u, 0x400921FBu);
[noinline]
double atan2S(double y, double x) {  // StrictMath.atan2
    int hx = hiW(x);
    int ix = hx & EXP_SIGNIF_BITS;
    int lx = loW(x);
    int hy = hiW(y);
    int iy = hy & EXP_SIGNIF_BITS;
    int ly = loW(y);
    if (isNaND(x) || isNaND(y)) return x + y;
    if (((hx - 0x3ff00000) | lx) == 0) return atanS(y);
    int m = ((hy >> 31) & 1) | ((hx >> 30) & 2);
    if ((iy | ly) == 0) {
        switch (m) {
            case 0:
            case 1: return y;
            case 2: return PI_D + TINY_D;
            default: return -PI_D - TINY_D;
        }
    }
    if ((ix | lx) == 0) return (hy < 0) ? -PI_O_2 - TINY_D : PI_O_2 + TINY_D;
    if (ix == EXP_BITS) {
        if (iy == EXP_BITS) {
            switch (m) {
                case 0: return PI_O_4 + TINY_D;
                case 1: return -PI_O_4 - TINY_D;
                case 2: return 3.0l * PI_O_4 + TINY_D;
                default: return -3.0l * PI_O_4 - TINY_D;
            }
        } else {
            switch (m) {
                case 0: return 0.0l;
                case 1: return -0.0l;
                case 2: return PI_D + TINY_D;
                default: return -PI_D - TINY_D;
            }
        }
    }
    if (iy == EXP_BITS) return (hy < 0) ? -PI_O_2 - TINY_D : PI_O_2 + TINY_D;
    int k = (iy - ix) >> 20;
    double z;
    if (k > 60) z = PI_O_2 + 0.5l * PI_LO;
    else if (hx < 0 && k < -60) z = 0.0l;
    else z = atanS(absD2(divD(y, x)));
    switch (m) {
        case 0: return z;
        case 1: return -z;
        case 2: return PI_D - (z - PI_LO);
        default: return (z - PI_LO) - PI_D;
    }
}

[noinline]
double hypotS(double x, double y) {  // StrictMath.hypot (finite, normal-range arguments)
    double a = absD2(x);
    double b = absD2(y);
    if (!isFiniteD(a) || !isFiniteD(b)) {
        if (a == asdouble(0u, 0x7ff00000u) || b == asdouble(0u, 0x7ff00000u)) return asdouble(0u, 0x7ff00000u);
        return a + b;
    }
    if (b > a) {
        double tmp = a;
        a = b;
        b = tmp;
    }
    int ha = hiW(a);
    int hb = hiW(b);
    if ((ha - hb) > 0x3c00000) return a + b;
    if (a > asdouble(0xFFFFFFFFu, 0x5F300000u) || b < asdouble(0x00000000u, 0x20B00000u)) {
        if (b == 0.0l) return a;
        g_bad = g_bad | 1u;  // the rescaled ranges are not ported
        return a;
    }
    double t1, t2;
    double w = a - b;
    if (w > b) {
        t1 = 0.0l;
        t1 = setHiW(t1, ha);
        t2 = a - t1;
        w = sqrtD(t1 * t1 - (b * (-b) - t2 * (a + t1)));
    } else {
        a = a + a;
        double y1 = 0.0l;
        y1 = setHiW(y1, hb);
        double y2 = b - y1;
        t1 = 0.0l;
        t1 = setHiW(t1, ha + 0x00100000);
        t2 = a - t1;
        w = sqrtD(t1 * y1 - (w * (-w) - (t1 * y2 + t2 * b)));
    }
    return w;
}

static const uint BP_W[4] = {0x00000000u, 0x3FF00000u, 0x00000000u, 0x3FF80000u};
double BP(int i) { return asdouble(BP_W[i * 2], BP_W[i * 2 + 1]); }
static const uint DP_H_W[4] = {0x00000000u, 0x00000000u, 0x40000000u, 0x3FE2B803u};
double DP_H(int i) { return asdouble(DP_H_W[i * 2], DP_H_W[i * 2 + 1]); }
static const uint DP_L_W[4] = {0x00000000u, 0x00000000u, 0x43CFD006u, 0x3E4CFDEBu};
double DP_L(int i) { return asdouble(DP_L_W[i * 2], DP_L_W[i * 2 + 1]); }
[noinline]
double powS(double x, double y) {  // StrictMath.pow
    double z;
    double r, s, t, u, v, w;
    int i, j, k, n;
    double INF = asdouble(0u, 0x7ff00000u);
    if (y == 0.0l) return 1.0l;
    if (isNaND(x) || isNaND(y)) return x + y;
    double y_abs = absD2(y);
    double x_abs = absD2(x);
    if (y == 2.0l) {
        return x * x;
    } else if (y == 0.5l) {
        if (x >= -asdouble(0xffffffffu, 0x7fefffffu)) return sqrtD(x + 0.0l);
    } else if (y_abs == 1.0l) {
        return (y == 1.0l) ? x : divD(1.0l, x);
    } else if (y_abs == INF) {
        if (x_abs == 1.0l) return y - y;
        else if (x_abs > 1.0l) return (y >= 0.0l) ? y : 0.0l;
        else return (y < 0.0l) ? -y : 0.0l;
    }
    int hx = hiW(x);
    int ix = hx & EXP_SIGNIF_BITS;
    int y_is_int = 0;
    if (hx < 0) {
        if (y_abs >= asdouble(0x00000000u, 0x43400000u)) y_is_int = 2;
        else if (y_abs >= 1.0l) {
            int64_t yl = int64_t(y_abs);
            if (double(yl) == y_abs) y_is_int = 2 - int(yl & 1);
        }
    }
    if (x_abs == 0.0l || x_abs == INF || x_abs == 1.0l) {
        z = x_abs;
        if (y < 0.0l) z = divD(1.0l, z);
        if (hx < 0) {
            if (((ix - 0x3ff00000) | y_is_int) == 0) z = asdouble(0u, 0x7ff80000u);
            else if (y_is_int == 1) z = -1.0l * z;
        }
        return z;
    }
    n = (hx >> 31) + 1;
    if ((n | y_is_int) == 0) return asdouble(0u, 0x7ff80000u);
    s = 1.0l;
    if ((n | (y_is_int - 1)) == 0) s = -1.0l;
    double p_h, p_l, t1, t2;
    if (y_abs > asdouble(0xFFFFFFFFu, 0x41E00000u)) {
        double INV_LN2 = asdouble(0x652B82FEu, 0x3FF71547u), INV_LN2_H = asdouble(0x60000000u, 0x3FF71547u), INV_LN2_L = asdouble(0xF85DDF44u, 0x3E54AE0Bu);
        if (x_abs < asdouble(0x00000000u, 0x3FEFFFFFu)) return (y < 0.0l) ? s * INF : s * 0.0l;
        if (x_abs > asdouble(0xFFFFFFFFu, 0x3FF00000u)) return (y > 0.0l) ? s * INF : s * 0.0l;
        t = x_abs - 1.0l;
        w = (t * t) * (0.5l - t * (0.3333333333333333333333l - t * 0.25l));
        u = INV_LN2_H * t;
        v = t * INV_LN2_L - w * INV_LN2;
        t1 = u + v;
        t1 = setLoW(t1, 0);
        t2 = v - (t1 - u);
    } else {
        double CP = asdouble(0xDC3A03FDu, 0x3FEEC709u), CP_H = asdouble(0xE0000000u, 0x3FEEC709u), CP_L = asdouble(0x145B01F5u, 0xBE3E2FE0u);
        double z_h, z_l, ss, s2, s_h, s_l, t_h, t_l;
        n = 0;
        if (ix < 0x00100000) {
            g_bad = g_bad | 1u;  // subnormal x: not ported
            return 0.0l;
        }
        n += ((ix) >> 20) - 0x3ff;
        j = ix & 0x000fffff;
        ix = j | 0x3ff00000;
        if (j <= 0x3988E) k = 0;
        else if (j < 0xBB67A) k = 1;
        else {
            k = 0;
            n += 1;
            ix -= 0x00100000;
        }
        x_abs = setHiW(x_abs, ix);
        double L1 = asdouble(0x33333303u, 0x3FE33333u), L2 = asdouble(0xDB6FABFFu, 0x3FDB6DB6u), L3 = asdouble(0x518F264Du, 0x3FD55555u),
               L4 = asdouble(0xA91D4101u, 0x3FD17460u), L5 = asdouble(0x93C9DB65u, 0x3FCD864Au), L6 = asdouble(0x4A454EEFu, 0x3FCA7E28u);
        u = x_abs - BP(k);
        v = divD(1.0l, x_abs + BP(k));
        ss = u * v;
        s_h = ss;
        s_h = setLoW(s_h, 0);
        t_h = 0.0l;
        t_h = setHiW(t_h, ((ix >> 1) | 0x20000000) + 0x00080000 + (k << 18));
        t_l = x_abs - (t_h - BP(k));
        s_l = v * ((u - s_h * t_h) - s_h * t_l);
        s2 = ss * ss;
        r = s2 * s2 * (L1 + s2 * (L2 + s2 * (L3 + s2 * (L4 + s2 * (L5 + s2 * L6)))));
        r += s_l * (s_h + ss);
        s2 = s_h * s_h;
        t_h = 3.0l + s2 + r;
        t_h = setLoW(t_h, 0);
        t_l = r - ((t_h - 3.0l) - s2);
        u = s_h * t_h;
        v = s_l * t_h + t_l * ss;
        p_h = u + v;
        p_h = setLoW(p_h, 0);
        p_l = v - (p_h - u);
        z_h = CP_H * p_h;
        z_l = CP_L * p_h + p_l * CP + DP_L(k);
        t = double(n);
        t1 = (((z_h + z_l) + DP_H(k)) + t);
        t1 = setLoW(t1, 0);
        t2 = z_l - (((t1 - t) - DP_H(k)) - z_h);
    }
    double y1 = y;
    y1 = setLoW(y1, 0);
    p_l = (y - y1) * t1 + y * t2;
    p_h = y1 * t1;
    z = p_l + p_h;
    j = hiW(z);
    i = loW(z);
    if (j >= 0x40900000) {
        if (((j - 0x40900000) | i) != 0) return s * INF;
        double OVT = 8.0085662595372944372e-0017l;
        if (p_l + OVT > z - p_h) return s * INF;
    } else if ((j & EXP_SIGNIF_BITS) >= 0x4090cc00) {
        if (((j - int(0xc090cc00u)) | i) != 0) return s * 0.0l;
        if (p_l <= z - p_h) return s * 0.0l;
    }
    double P1 = asdouble(0x5555553Eu, 0x3FC55555u), P2 = asdouble(0x16BEBD93u, 0xBF66C16Cu), P3 = asdouble(0xAF25DE2Cu, 0x3F11566Au),
           P4 = asdouble(0xC5D26BF1u, 0xBEBBBD41u), P5 = asdouble(0x72BEA4D0u, 0x3E663769u), LG2 = asdouble(0xFEFA39EFu, 0x3FE62E42u),
           LG2_H = asdouble(0x00000000u, 0x3FE62E43u), LG2_L = asdouble(0x0CA86C39u, 0xBE205C61u);
    i = j & EXP_SIGNIF_BITS;
    k = (i >> 20) - 0x3ff;
    n = 0;
    if (i > 0x3fe00000) {
        n = j + (0x00100000 >> (k + 1));
        k = ((n & EXP_SIGNIF_BITS) >> 20) - 0x3ff;
        t = 0.0l;
        t = setHiW(t, (n & ~(0x000fffff >> k)));
        n = ((n & 0x000fffff) | 0x00100000) >> (20 - k);
        if (j < 0) n = -n;
        p_h -= t;
    }
    t = p_l + p_h;
    t = setLoW(t, 0);
    u = t * LG2_H;
    v = (p_l - (t - p_h)) * LG2 + t * LG2_L;
    z = u + v;
    w = v - (z - u);
    t = z * z;
    t1 = z - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))));
    r = divD(z * t1, t1 - 2.0l) - (w + z * w);
    z = 1.0l - (r - z);
    j = hiW(z);
    j += (n << 20);
    if ((j >> 20) <= 0) {
        g_bad = g_bad | 1u;  // a subnormal result (Math.scalb): not ported
        return 0.0l;
    }
    int z_hi = hiW(z);
    z_hi += (n << 20);
    z = setHiW(z, z_hi);
    return s * z;
}

static const uint EXP_HALF_W[4] = {0x00000000u, 0x3FE00000u, 0x00000000u, 0xBFE00000u};
double EXP_HALF(int i) { return asdouble(EXP_HALF_W[i * 2], EXP_HALF_W[i * 2 + 1]); }
static const uint LN2HI_W[4] = {0xFEE00000u, 0x3FE62E42u, 0xFEE00000u, 0xBFE62E42u};
double LN2HI(int i) { return asdouble(LN2HI_W[i * 2], LN2HI_W[i * 2 + 1]); }
static const uint LN2LO_W[4] = {0x35793C76u, 0x3DEA39EFu, 0x35793C76u, 0xBDEA39EFu};
double LN2LO(int i) { return asdouble(LN2LO_W[i * 2], LN2LO_W[i * 2 + 1]); }
[noinline]
double expS(double x) {  // StrictMath.exp
    double o_threshold = asdouble(0xFEFA39EFu, 0x40862E42u), u_threshold = asdouble(0xD52D3051u, 0xC0874910u), invln2 = asdouble(0x652B82FEu, 0x3FF71547u);
    double P1 = asdouble(0x5555553Eu, 0x3FC55555u), P2 = asdouble(0x16BEBD93u, 0xBF66C16Cu), P3 = asdouble(0xAF25DE2Cu, 0x3F11566Au),
           P4 = asdouble(0xC5D26BF1u, 0xBEBBBD41u), P5 = asdouble(0x72BEA4D0u, 0x3E663769u);
    double y;
    double hi = 0.0l;
    double lo = 0.0l;
    double c;
    double t;
    int k = 0;
    int hx = hiW(x);
    int xsb = (hx >> 31) & 1;
    hx &= EXP_SIGNIF_BITS;
    if (hx >= 0x40862E42) {
        if (hx >= 0x7ff00000) {
            if (((hx & 0xfffff) | loW(x)) != 0) return x + x;
            return (xsb == 0) ? x : 0.0l;
        }
        if (x > o_threshold) return asdouble(0u, 0x7ff00000u);
        if (x < u_threshold) return 0.0l;
    }
    if (hx > 0x3fd62e42) {
        if (hx < 0x3FF0A2B2) {
            hi = x - LN2HI(xsb);
            lo = LN2LO(xsb);
            k = 1 - xsb - xsb;
        } else {
            k = int(invln2 * x + EXP_HALF(xsb));
            t = double(k);
            hi = x - t * LN2HI(0);
            lo = t * LN2LO(0);
        }
        x = hi - lo;
    } else if (hx < 0x3e300000) {
        if (HUGE_D + x > 1.0l) return 1.0l + x;
    } else {
        k = 0;
    }
    t = x * x;
    c = x - t * (P1 + t * (P2 + t * (P3 + t * (P4 + t * P5))));
    if (k == 0) return 1.0l - (divD(x * c, c - 2.0l) - x);
    y = 1.0l - ((lo - divD(x * c, 2.0l - c)) - hi);
    if (k >= -1021) {
        y = setHiW(y, hiW(y) + (k << 20));
        return y;
    }
    g_bad = g_bad | 1u;  // a subnormal result: not ported
    return 0.0l;
}
