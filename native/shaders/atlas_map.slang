// The map of Nirn in the client generator's kernels (docs/ATLAS-CLIENT-GENERATION.md): TamrielMap.sample (the Tamriel
// raster, its hydrology and sharp peaks, the fixed foreign lands and the sea beyond) from the words AtlasGenMap packs
// (mapData, uploaded once). A literal port of the Java (com.nirncraft.tamriel.*): Java's float / double evaluation
// order and promotions are kept term for term, divisions and square roots go through the exact helpers (divD, divF,
// sqrtD) and the transcendental calls through atlas_exact.slang's fdlibm (StrictMath), so a sample equals Java's bit
// for bit. Included by atlas_gen.slang after atlas_exact.slang.

// ---- the map words (AtlasGenMap) -------------------------------------------------------------------------------
static const uint M_MAGIC = 0x50414D41u;
static const uint MH_RASTER = 2, MH_REGIONS = 3, MH_HYDRO = 4, MH_PEAKS = 5, MH_LANDS = 6, MH_TERRAIN = 7;
uint mw(uint i) { return mapData[i]; }
int mi(uint i) { return int(mapData[i]); }
float mf(uint i) { return asfloat(mapData[i]); }
double md(uint i) { return asdouble(mapData[i], mapData[i + 1]); }
uint mh(uint i) { return mapData[i]; }

// ---- TamrielMap / MapRaster constants -------------------------------------------------------------------------
static const int T_CELL = 32, T_MIN_X = -15360, T_MIN_Z = -5120, T_W = 880, T_H = 672;
static const int T_MAX_X = T_MIN_X + T_W * T_CELL, T_MAX_Z = T_MIN_Z + T_H * T_CELL;
static const int T_SEA = 63;
static const uint CH = 15;  // MapRaster.CH: interleaved floats per cell
static const uint RC_ELEV = 0, RC_COAST = 1, RC_RIV_D = 2, RC_RIV_HW = 3, RC_LAKE_SD = 4, RC_WATER = 5, RC_RIDGE = 6, RC_ROUGH = 7,
                  RC_CRAG = 8, RC_DUNES = 9, RC_WET = 10, RC_ROAD = 11, RC_BORDER = 12, RC_RAINSHADOW = 13, RC_HEAT = 14;
static const float NO_WATER = -1000.0f;
static const float BLEND_RANGE = 80.0f;
static const double TWO_PI_D = asdouble(0x54442D18u, 0x401921FBu);  // Math.PI * 2
static const double DBL_MAX = asdouble(0xFFFFFFFFu, 0x7FEFFFFFu);
static const float F_NAN = asfloat(0x7FC00000u);

// Math.max / Math.min (double)
double jmaxD(double a, double b) {
    if (a != a) return a;
    if (a == 0.0l && b == 0.0l && hiW(a) == int(0x80000000u) && loW(a) == 0) return b;
    return a >= b ? a : b;
}
double jminD(double a, double b) {
    if (a != a) return a;
    if (a == 0.0l && b == 0.0l && hiW(b) == int(0x80000000u) && loW(b) == 0) return b;
    return a <= b ? a : b;
}
double clampJ(double v, double lo, double hi) { return v < lo ? lo : (v > hi ? hi : v); }  // MapNoise.clamp
int clampMaxI(int v, int max) { return v < 0 ? 0 : (v > max ? max : v); }  // MapRaster.clampI

// ---- MapNoise ---------------------------------------------------------------------------------------------------
int mnHash(int x, int z, int seed) {
    uint h = uint(x) * 374761393u + uint(z) * 668265263u + uint(seed) * 1442695041u;
    h = (h ^ (h >> 13)) * 1274126177u;
    return int(h ^ (h >> 16));
}
double mnRand(int x, int z, int seed) { return double(uint(mnHash(x, z, seed)) & 0xFFFFFFu) * asdouble(0u, 0x3E700000u); }  // / 2^24
double mnLattice(int x, int z, int seed) { return divI(int(uint(mnHash(x, z, seed)) & 0xFFFFFFu), 0x7FFFFF) - 1.0l; }
double mnValue(double x, double z, double scale, int seed, inout uint bad) {
    double fx = divD(x, scale, bad), fz = divD(z, scale, bad);
    int ix = floorD(fx, bad), iz = floorD(fz, bad);
    double tx = fx - double(ix), tz = fz - double(iz);
    tx = tx * tx * (3.0l - 2.0l * tx);
    tz = tz * tz * (3.0l - 2.0l * tz);
    double a = mnLattice(ix, iz, seed), b = mnLattice(ix + 1, iz, seed);
    double c = mnLattice(ix, iz + 1, seed), d = mnLattice(ix + 1, iz + 1, seed);
    double ab = a + (b - a) * tx;
    return ab + (c + (d - c) * tx - ab) * tz;
}
double mnFbm(double x, double z, double scale, int octaves, int seed, inout uint bad) {
    double tot = 0.0l, amp = 1.0l, norm = 0.0l;
    for (int o = 0; o < octaves; o++) {
        tot = tot + mnValue(x, z, scale, seed + o * 31, bad) * amp;
        norm = norm + amp;
        amp = amp * 0.5l;
        scale = scale * 0.5l;
    }
    return divD(tot, norm, bad);
}
double mnRidged(double x, double z, double scale, int octaves, int seed, inout uint bad) {
    double tot = 0.0l, amp = 1.0l, norm = 0.0l;
    for (int o = 0; o < octaves; o++) {
        double n = 1.0l - absD2(mnValue(x, z, scale, seed + o * 17, bad));
        tot = tot + n * n * amp;
        norm = norm + amp;
        amp = amp * 0.5l;
        scale = scale * 0.5l;
    }
    return divD(tot, norm, bad);
}
double mnSmooth(double e0, double e1, double x, inout uint bad) {  // MapNoise.smoothstep
    double t = divD(x - e0, e1 - e0, bad);
    t = t < 0.0l ? 0.0l : (t > 1.0l ? 1.0l : t);
    return t * t * (3.0l - 2.0l * t);
}

// ---- MapSample ----------------------------------------------------------------------------------------------------
struct MapS {
    int region, blendRegion;
    float blendWeight, elevation, ridge, river, lake, coastDistance, waterLevel, riverDistance, riverHalfWidth,
          lakeDistance, roughness, crag, dunes, wetland, road;
    bool inTamriel;
    float peak, calm, slope, rainshadow, heat, aspectX, aspectZ;
};
void msClear(inout MapS s) {
    s.region = 0;
    s.blendRegion = 0;
    s.blendWeight = 0.0f;
    s.elevation = 0.0f;
    s.ridge = 0.0f;
    s.river = 0.0f;
    s.lake = 0.0f;
    s.coastDistance = 0.0f;
    s.waterLevel = F_NAN;
    s.riverDistance = 10000.0f;
    s.riverHalfWidth = 0.0f;
    s.lakeDistance = -10000.0f;
    s.roughness = 0.0f;
    s.crag = 0.0f;
    s.dunes = 0.0f;
    s.wetland = 0.0f;
    s.road = 0.0f;
    s.inTamriel = false;
    s.peak = 0.0f;
    s.calm = 0.0f;
    s.slope = 0.0f;
    s.rainshadow = 0.0f;
    s.heat = 0.0f;
    s.aspectX = 0.0f;
    s.aspectZ = 0.0f;
}
bool msInWater(MapS s) {
    if (isNaNF(s.waterLevel)) return false;
    return s.lakeDistance > 0.0f || s.riverDistance < s.riverHalfWidth;
}
float msWaterDistance(MapS s, inout uint bad) {
    float rd = jmaxF(0.0f, s.riverDistance - s.riverHalfWidth);
    float ld = s.lakeDistance >= 0.0f ? 0.0f : -s.lakeDistance;
    float d = jminF(rd, ld);
    return d > 512.0f ? 512.0f : d;
}
// TamrielMap.deriveStrengths
void deriveStrengths(inout MapS s, inout uint bad) {
    if (isNaNF(s.waterLevel)) {
        s.river = 0.0f;
        s.lake = 0.0f;
        return;
    }
    float over = jmaxF(0.0f, s.riverDistance - s.riverHalfWidth);
    s.river = s.riverHalfWidth >= 3.5f ? jmaxF(0.0f, 1.0f - divF(over, 24.0f, bad)) : 0.0f;
    s.lake = jmaxF(0.0f, jminF(1.0f, 0.5f + divF(s.lakeDistance, 32.0f, bad)));
}

// ---- MapRaster ----------------------------------------------------------------------------------------------------
float rcell(uint c, uint ch) { return mf(mh(MH_RASTER) + c * CH + ch); }
uint rregion(uint c) { return (mw(mh(MH_REGIONS) + (c >> 2)) >> ((c & 3u) * 8u)) & 255u; }
uint rblend(uint c) { return (mw(mh(MH_REGIONS) + ((uint(T_W * T_H) + 3u) >> 2) + (c >> 2)) >> ((c & 3u) * 8u)) & 255u; }


double warpX(int x, int z, inout uint bad) {
    return mnValue(double(x), double(z), 96.0l, 101, bad) * 16.0l + mnValue(double(x), double(z), 29.0l, 102, bad) * 6.0l;
}
double warpZ(int x, int z, inout uint bad) {
    return mnValue(double(x), double(z), 96.0l, 103, bad) * 16.0l + mnValue(double(x), double(z), 29.0l, 104, bad) * 6.0l;
}
bool rasterCovers(double x, double z) {
    return x >= double(T_MIN_X + T_CELL) && x < double(T_MAX_X - T_CELL) && z >= double(T_MIN_Z + T_CELL) && z < double(T_MAX_Z - T_CELL);
}
int regionAt(double wx, double wz, inout uint bad) {
    int cx = clampMaxI(floorD((wx - double(T_MIN_X)) * 0.03125l, bad), T_W - 1);
    int cz = clampMaxI(floorD((wz - double(T_MIN_Z)) * 0.03125l, bad), T_H - 1);
    return int(rregion(uint(cz * T_W + cx)));
}
int regionIndexRaster(int x, int z, inout uint bad) {
    return regionAt(double(x) + warpX(x, z, bad), double(z) + warpZ(x, z, bad), bad);
}
float rlerp(uint ch, uint o00, uint o10, uint o01, uint o11, float w00, float w10, float w01, float w11) {
    return rcell(o00, ch) * w00 + rcell(o10, ch) * w10 + rcell(o01, ch) * w01 + rcell(o11, ch) * w11;
}
float riverDistanceR(uint o00, uint o10, uint o01, uint o11, float tx, float tz, float bilinear, inout uint bad) {
    float d00 = rcell(o00, RC_RIV_D), d10 = rcell(o10, RC_RIV_D), d01 = rcell(o01, RC_RIV_D), d11 = rcell(o11, RC_RIV_D);
    if (jminF(jminF(d00, d10), jminF(d01, d11)) > float(T_CELL) * 1.5f
            || jmaxF(jmaxF(d00, d10), jmaxF(d01, d11)) >= 127.0f) return bilinear;
    float best = asfloat(0x7F7FFFFFu), value = bilinear;
    for (int m = 0; m < 8; m++) {
        float v00 = d00, v10 = (m & 1) != 0 ? -d10 : d10, v01 = (m & 2) != 0 ? -d01 : d01, v11 = (m & 4) != 0 ? -d11 : d11;
        float b = ((v10 - v00) + (v11 - v01)) * 0.5f, c = ((v01 - v00) + (v11 - v10)) * 0.5f;
        float twist = v00 - v10 - v01 + v11;
        float score = absF(twist) * 0.03125f + absF(float(sqrtD(double(b * b + c * c), bad)) * 0.03125f - 1.0f);
        if (score < best) {
            best = score;
            float a = (v00 + v10 + v01 + v11) * 0.25f - (b + c) * 0.5f;
            value = absF(a + b * tx + c * tz);
        }
    }
    return best < 0.25f ? jminF(bilinear, value) : bilinear;
}
// Catmull-Rom elevation, slope and aspect at the unwarped column
void rasterElevation(int x, int z, inout MapS s, inout uint bad) {
    double fx = double(x - T_MIN_X) * 0.03125l - 0.5l, fz = double(z - T_MIN_Z) * 0.03125l - 0.5l;
    int ix = floorD(fx, bad), iz = floorD(fz, bad);
    double tx = fx - double(ix), tz = fz - double(iz);
    double tx2 = tx * tx, tx3 = tx2 * tx, tz2 = tz * tz, tz3 = tz2 * tz;
    double ax0 = -0.5l * tx3 + tx2 - 0.5l * tx, ax1 = 1.5l * tx3 - 2.5l * tx2 + 1.0l, ax2 = -1.5l * tx3 + 2.0l * tx2 + 0.5l * tx,
           ax3 = 0.5l * tx3 - 0.5l * tx2;
    double bx0 = -1.5l * tx2 + 2.0l * tx - 0.5l, bx1 = 4.5l * tx2 - 5.0l * tx, bx2 = -4.5l * tx2 + 4.0l * tx + 0.5l, bx3 = 1.5l * tx2 - tx;
    double az0 = -0.5l * tz3 + tz2 - 0.5l * tz, az1 = 1.5l * tz3 - 2.5l * tz2 + 1.0l, az2 = -1.5l * tz3 + 2.0l * tz2 + 0.5l * tz,
           az3 = 0.5l * tz3 - 0.5l * tz2;
    double bz0 = -1.5l * tz2 + 2.0l * tz - 0.5l, bz1 = 4.5l * tz2 - 5.0l * tz, bz2 = -4.5l * tz2 + 4.0l * tz + 0.5l, bz3 = 1.5l * tz2 - tz;
    int c0 = clampMaxI(ix - 1, T_W - 1), c1 = clampMaxI(ix, T_W - 1), c2 = clampMaxI(ix + 1, T_W - 1), c3 = clampMaxI(ix + 2, T_W - 1);
    double e = 0.0l, ex = 0.0l, ez = 0.0l;
    for (int j = 0; j < 4; j++) {
        int row = clampMaxI(iz - 1 + j, T_H - 1) * T_W;
        double v0 = double(rcell(uint(row + c0), RC_ELEV)), v1 = double(rcell(uint(row + c1), RC_ELEV));
        double v2 = double(rcell(uint(row + c2), RC_ELEV)), v3 = double(rcell(uint(row + c3), RC_ELEV));
        double r = ax0 * v0 + ax1 * v1 + ax2 * v2 + ax3 * v3;
        double rx = bx0 * v0 + bx1 * v1 + bx2 * v2 + bx3 * v3;
        double wz = j == 0 ? az0 : (j == 1 ? az1 : (j == 2 ? az2 : az3));
        double dz = j == 0 ? bz0 : (j == 1 ? bz1 : (j == 2 ? bz2 : bz3));
        e = e + wz * r;
        ex = ex + wz * rx;
        ez = ez + dz * r;
    }
    s.elevation = float(e);
    double gradNorm = sqrtD(ex * ex + ez * ez, bad);
    s.slope = float(gradNorm * 0.03125l);
    if (gradNorm > 1.0e-4l) {
        s.aspectX = float(divD(-ex, gradNorm, bad));
        s.aspectZ = float(divD(-ez, gradNorm, bad));
    } else {
        s.aspectX = 0.0f;
        s.aspectZ = 0.0f;
    }
}

// ---- Hydrology.apply: waterfalls square the water level ----------------------------------------------------------
void hydrologyApply(int x, int z, inout MapS s, inout uint bad) {
    uint h = mh(MH_HYDRO);
    uint n = mw(h);
    if (n == 0u || isNaNF(s.waterLevel) || s.lakeDistance > 0.0f) return;
    int g0x = mi(h + 1u), g0z = mi(h + 2u), gw = mi(h + 3u), gh = mi(h + 4u);
    uint fallsAt = mw(h + 5u), gridAt = mw(h + 6u);
    int gx = floorDivI(x, 64) - g0x, gz = floorDivI(z, 64) - g0z;
    if (gx < 0 || gz < 0 || gx >= gw || gz >= gh) return;
    uint cell = uint(gz * gw + gx);
    uint from = mw(gridAt + cell), to = mw(gridAt + cell + 1u);
    uint idxAt = gridAt + uint(gw * gh) + 1u;
    for (uint k = from; k < to; k++) {
        uint f = fallsAt + mw(idxAt + k) * 8u;
        float dx = float(x - mi(f)), dz = float(z - mi(f + 1u));
        float ux = mf(f + 2u), uz = mf(f + 3u), up = mf(f + 4u), down = mf(f + 5u), hw = mf(f + 6u);
        float u = dx * ux + dz * uz;
        if (u < -44.0f || u > 44.0f) continue;
        float v = -dx * uz + dz * ux;
        float lim = hw + 18.0f;
        if (absF(v) > lim) continue;
        float raster = s.waterLevel;
        if (raster > up + 6.0f || raster < down - 4.0f) continue;
        float edge = absF(u) <= 20.0f ? 0.0f : jminF(1.0f, divF(absF(u) - 20.0f, 24.0f, bad));
        float side = u < 0.0f ? up : down;
        s.waterLevel = side + (raster - side) * edge * edge * (3.0f - 2.0f * edge);
        if (u > 0.0f && u < 14.0f) s.riverHalfWidth = s.riverHalfWidth + 2.0f * (1.0f - divF(u, 14.0f, bad));
        return;
    }
}

// ---- MapPeaks.apply: the sharp peaks ------------------------------------------------------------------------------
// a peak's record: 10 doubles (x, z, height, shape, power, hornHeight, hornRadius, calderaRadius, calderaDepth, jag),
// 4 ints (box), 720 reach floats, shelves (n, 4 doubles each), clear (n, 3 doubles), paths (n, then per path m and
// 3 doubles a point)
double peakReachAt(uint p, double bearing, inout uint bad) {
    double f = divD(bearing + PI_D, TWO_PI_D, bad) * 720.0l;
    int i = floorD(f, bad);
    double t = f - double(i);
    uint rb = p + 24u;  // after 10 doubles and the 4-int box
    float a = mf(rb + uint(floorModI(i, 720))), b = mf(rb + uint(floorModI(i + 1, 720)));
    return double(a) + double(b - a) * t;
}
void peaksApply(int x, int z, inout MapS s, inout uint bad) {
    uint h = mh(MH_PEAKS);
    uint n = mw(h);
    if (n == 0u) return;
    if (x < mi(h + 1u) || x > mi(h + 2u) || z < mi(h + 3u) || z > mi(h + 4u)) return;
    for (uint pi = 0u; pi < n; pi++) {
        uint p = mw(h + 5u + pi);
        if (x < mi(p + 20u) || x > mi(p + 21u) || z < mi(p + 22u) || z > mi(p + 23u)) continue;
        double px = md(p), pz = md(p + 2u), height = md(p + 4u), shape = md(p + 6u), power = md(p + 8u);
        double hornHeight = md(p + 10u), hornRadius = md(p + 12u), calderaRadius = md(p + 14u), calderaDepth = md(p + 16u);
        double jagP = md(p + 18u);
        double dx = double(x) - px, dz = double(z) - pz;
        double r = sqrtD(dx * dx + dz * dz, bad);
        double reach = peakReachAt(p, atan2S(dz, dx, bad), bad);
        if (r >= reach) continue;
        double u = divD(r, reach, bad), ua = powS(u, shape, bad);
        double road = double(s.road);
        double prof = powS(1.0l - ua, power, bad) * (1.0l - 0.85l * road);
        double dprof = divD(divD(power * powS(jmaxD(1.0e-6l, 1.0l - ua), power - 1.0l, bad) * shape * ua, jmaxD(u, 1.0e-6l), bad), reach, bad);
        s.slope = float(jmaxD(double(s.slope), jmaxD(height - hornHeight - double(s.elevation), 0.0l) * dprof * (1.0l - 0.85l * road)));
        uint q = p + 24u + 720u;
        uint nShelves = mw(q);
        uint shelves = q + 1u;
        uint qc = shelves + nShelves * 8u;
        uint nClear = mw(qc);
        uint clears = qc + 1u;
        for (uint c = 0u; c < nClear; c++) {
            uint cc = clears + c * 6u;
            double c0 = md(cc), c1 = md(cc + 2u), c2 = md(cc + 4u);
            prof = prof * mnSmooth(c2 * 0.8l, c2 + 60.0l, hypotS(double(x) - c0, double(z) - c1, bad), bad);
        }
        double base = double(s.elevation);
        double y = base + jmaxD(height - hornHeight - base, 0.0l) * prof;
        double calm = 0.0l;
        if (calderaRadius > 0.0l) {
            if (r >= calderaRadius) {
                double hr = r - calderaRadius;
                if (hr < hornRadius) y = y + hornHeight * powS(1.0l - divD(hr, hornRadius, bad), 1.5l, bad);
            } else {
                double cu = divD(r, calderaRadius, bad);
                y = y + (hornHeight - calderaDepth * powS(1.0l - cu * cu, 1.35l, bad));
                calm = jmaxD(calm, 0.65l * (1.0l - cu * cu));
            }
        } else if (r < hornRadius) {
            y = y + hornHeight * powS(1.0l - divD(r, hornRadius, bad), 1.5l, bad);
        }
        for (uint k = 0u; k < nShelves; k++) {
            uint sh = shelves + k * 8u;
            double s0 = md(sh), s1 = md(sh + 2u), s2 = md(sh + 4u), s3 = md(sh + 6u);
            double d = hypotS(double(x) - s0, double(z) - s1, bad);
            if (d >= s2 + 40.0l) continue;
            double w = 1.0l - mnSmooth(s2, s2 + 40.0l, d, bad);
            y = y + (s3 - y) * w;
            calm = jmaxD(calm, 1.0l - mnSmooth(s2 - 8.0l, s2 + 24.0l, d, bad));
        }
        uint qp = clears + nClear * 6u;
        uint nPaths = mw(qp);
        uint path = qp + 1u;
        for (uint pa = 0u; pa < nPaths; pa++) {
            uint m = mw(path);
            uint pts = path + 1u;
            double best = DBL_MAX, level = 0.0l;
            for (uint i = 0u; i + 1u < m; i++) {
                uint a = pts + i * 6u, b2 = a + 6u;
                double ax = md(a), az = md(a + 2u), bx = md(b2), bz = md(b2 + 2u);
                double vx = bx - ax, vz = bz - az;
                double l2 = vx * vx + vz * vz;
                double t = l2 > 0.0l ? clampJ(divD((double(x) - ax) * vx + (double(z) - az) * vz, l2, bad), 0.0l, 1.0l) : 0.0l;
                double ex = double(x) - (ax + t * vx), ez = double(z) - (az + t * vz);
                double d2 = ex * ex + ez * ez;
                if (d2 < best) {
                    best = d2;
                    level = md(a + 4u) + (md(b2 + 4u) - md(a + 4u)) * t;
                }
            }
            path = pts + m * 6u;
            if (best == DBL_MAX) continue;  // (a single point: no stretch, nowhere near)
            double d = sqrtD(best, bad);
            double bank = 12.0l + jminD(48.0l, 0.8l * absD2(level - y));
            if (d < bank) {
                y = y + (level - y) * (1.0l - mnSmooth(2.5l, bank, d, bad));
                calm = jmaxD(calm, 1.0l - mnSmooth(3.0l, 14.0l, d, bad));
            }
        }
        s.elevation = float(y);
        double jag = jagP * mnSmooth(0.0l, 0.15l, prof, bad) * (1.0l - 0.5l * mnSmooth(0.75l, 1.0l, prof, bad));
        s.peak = float(jmaxD(double(s.peak), jag));
        s.calm = float(jmaxD(double(s.calm), calm));
    }
}

// ---- MapRaster.sample ---------------------------------------------------------------------------------------------
void rasterSample(int x, int z, inout MapS s, inout uint bad) {
    double wx = double(x) + warpX(x, z, bad), wz = double(z) + warpZ(x, z, bad);
    double fx = (wx - double(T_MIN_X)) * 0.03125l - 0.5l, fz = (wz - double(T_MIN_Z)) * 0.03125l - 0.5l;
    int ix = clampMaxI(floorD(fx, bad), T_W - 2), iz = clampMaxI(floorD(fz, bad), T_H - 2);
    float tx = float(clampJ(fx - double(ix), 0.0l, 1.0l)), tz = float(clampJ(fz - double(iz), 0.0l, 1.0l));
    uint o00 = uint(iz * T_W + ix), o10 = o00 + 1u, o01 = o00 + uint(T_W), o11 = o01 + 1u;
    float w00 = (1.0f - tx) * (1.0f - tz), w10 = tx * (1.0f - tz), w01 = (1.0f - tx) * tz, w11 = tx * tz;

    s.inTamriel = true;
    rasterElevation(x, z, s, bad);
    s.coastDistance = rlerp(RC_COAST, o00, o10, o01, o11, w00, w10, w01, w11);
    s.riverDistance = riverDistanceR(o00, o10, o01, o11, tx, tz, rlerp(RC_RIV_D, o00, o10, o01, o11, w00, w10, w01, w11), bad);
    s.lakeDistance = rlerp(RC_LAKE_SD, o00, o10, o01, o11, w00, w10, w01, w11);
    s.ridge = rlerp(RC_RIDGE, o00, o10, o01, o11, w00, w10, w01, w11);
    s.roughness = rlerp(RC_ROUGH, o00, o10, o01, o11, w00, w10, w01, w11);
    s.crag = rlerp(RC_CRAG, o00, o10, o01, o11, w00, w10, w01, w11);
    s.dunes = rlerp(RC_DUNES, o00, o10, o01, o11, w00, w10, w01, w11);
    s.wetland = rlerp(RC_WET, o00, o10, o01, o11, w00, w10, w01, w11);
    s.road = rlerp(RC_ROAD, o00, o10, o01, o11, w00, w10, w01, w11);
    s.rainshadow = rlerp(RC_RAINSHADOW, o00, o10, o01, o11, w00, w10, w01, w11);
    s.heat = rlerp(RC_HEAT, o00, o10, o01, o11, w00, w10, w01, w11);

    float ws = 0.0f, wl = 0.0f, hw = 0.0f;
    if (rcell(o00, RC_WATER) > NO_WATER) { ws = ws + w00; wl = wl + w00 * rcell(o00, RC_WATER); hw = hw + w00 * rcell(o00, RC_RIV_HW); }
    if (rcell(o10, RC_WATER) > NO_WATER) { ws = ws + w10; wl = wl + w10 * rcell(o10, RC_WATER); hw = hw + w10 * rcell(o10, RC_RIV_HW); }
    if (rcell(o01, RC_WATER) > NO_WATER) { ws = ws + w01; wl = wl + w01 * rcell(o01, RC_WATER); hw = hw + w01 * rcell(o01, RC_RIV_HW); }
    if (rcell(o11, RC_WATER) > NO_WATER) { ws = ws + w11; wl = wl + w11 * rcell(o11, RC_WATER); hw = hw + w11 * rcell(o11, RC_RIV_HW); }
    if (ws > 1.0e-4f) {
        s.waterLevel = divF(wl, ws, bad);
        s.riverHalfWidth = divF(hw, ws, bad);
    } else {
        s.waterLevel = F_NAN;
        s.riverHalfWidth = 0.0f;
    }

    hydrologyApply(x, z, s, bad);

    int rc = regionAt(wx, wz, bad);
    s.region = rc;
    int cx = clampMaxI(floorD((wx - double(T_MIN_X)) * 0.03125l, bad), T_W - 1);
    int cz = clampMaxI(floorD((wz - double(T_MIN_Z)) * 0.03125l, bad), T_H - 1);
    int partner = int(rblend(uint(cz * T_W + cx)));
    float border = rlerp(RC_BORDER, o00, o10, o01, o11, w00, w10, w01, w11);
    if (partner != rc && border < BLEND_RANGE) {
        s.blendRegion = partner;
        s.blendWeight = 0.5f * (1.0f - divF(jmaxF(0.0f, border), BLEND_RANGE, bad));
    } else {
        s.blendRegion = rc;
        s.blendWeight = 0.0f;
    }
    deriveStrengths(s, bad);
    peaksApply(x, z, s, bad);
}

// ---- Beyond: the fixed foreign lands and the sea beyond the raster ----------------------------------------------
// a land: shape (1 archipelago), seed, mountains (float), pad, then doubles cx, cz, rx, rz, islandScale, reach
static const uint LAND_WORDS = 16;
uint landAt(uint i) { return mh(MH_LANDS) + 1u + i * LAND_WORDS; }
uint landCount() { return mw(mh(MH_LANDS)); }
// the region indices R_ICE .. R_ABECEAN in Beyond's order
static const uint R_ICE = 0, R_FOREST = 1, R_TSAESCI = 2, R_KAMAL = 3, R_TANGMO = 4, R_KAPOTUN = 5, R_YOKUDA = 6,
                  R_SUNKEN = 7, R_PYANDONEA = 8, R_THRAS = 9, R_GHOSTS = 10, R_PADOMAIC = 11, R_ELTHERIC = 12, R_ABECEAN = 13;
int beyondRegion(uint k) { return mi(mh(MH_LANDS) + 1u + landCount() * LAND_WORDS + k); }
bool mapLoaded() { return mw(mh(MH_LANDS) + 1u + landCount() * LAND_WORDS + 14u) != 0u; }
bool landNear(uint l, double x, double z) {
    double dx = x - md(l + 4u), dz = z - md(l + 6u);
    double reach = md(l + 14u);
    return dx * dx + dz * dz < reach * reach;
}
bool nearForeign(int x, int z) {
    for (uint i = 0u; i < landCount(); i++)
        if (landNear(landAt(i), double(x), double(z))) return true;
    return false;
}
double landField(uint l, double x, double z, inout uint bad) {
    double cx = md(l + 4u), cz = md(l + 6u), rx = md(l + 8u), rz = md(l + 10u);
    int seed = mi(l + 1u);
    double dx = x - cx, dz = z - cz;
    if (mw(l) == 0u) {
        double ux = divD(dx, rx, bad), uz = divD(dz, rz, bad);
        double e = sqrtD(ux * ux + uz * uz, bad);
        double r = sqrtD(rx * rz, bad);
        return (1.0l - e) * r + mnFbm(x, z, 1700.0l, 3, seed, bad) * 700.0l + mnFbm(x, z, 320.0l, 2, seed + 5, bad) * 130.0l;
    }
    double islandScale = md(l + 12u);
    double d2 = divD(dx * dx + dz * dz, rx * rz, bad);
    double m = 1.0l - d2;
    double g = mnFbm(x, z, islandScale, 3, seed, bad) + 0.5l * m - 0.36l;
    return g * islandScale * 0.6l;
}
int fixedRegion(uint li, double x, double z, inout uint bad) {
    uint l = landAt(li);
    if (li == 0u) return (z - md(l + 6u)) + mnFbm(x, z, 900.0l, 2, 61, bad) * 700.0l < -250.0l ? beyondRegion(R_ICE) : beyondRegion(R_FOREST);
    if (li == 1u) {
        double u = x - md(l + 4u) + mnFbm(x, z, 900.0l, 2, 62, bad) * 600.0l;
        double v = z - md(l + 6u) + mnFbm(x, z, 900.0l, 2, 63, bad) * 600.0l;
        if (v < -1500.0l) return beyondRegion(R_KAMAL);
        if (u < -200.0l && v > -300.0l) return beyondRegion(R_TSAESCI);
        return beyondRegion(R_KAPOTUN);
    }
    if (li == 2u) return beyondRegion(R_TANGMO);
    if (li == 3u) return beyondRegion(R_YOKUDA);
    if (li == 4u) return beyondRegion(R_SUNKEN);
    if (li == 5u) return beyondRegion(R_PYANDONEA);
    return beyondRegion(R_THRAS);
}
// Regions.terrain(index): base, relief, rough, crag, dunes, wet, coast (the default for unknown indices)
uint terrainAt(int index) {
    uint t = mh(MH_TERRAIN);
    int n = mi(t);
    int k = index >= 0 && index < n ? index + 1 : 0;
    return t + 1u + uint(k) * 8u;
}
double seaDepth(double d, inout uint bad) {
    return 0.05l * jminD(d, 64.0l) + 75.0l * (1.0l - expS(divD(-jmaxD(d - 64.0l, 0.0l), 1300.0l, bad), bad));
}
void fillLand(int x, int z, double cd, int region, uint t, float mountains, float peak, int seed, inout MapS s, inout uint bad) {
    s.region = region;
    s.blendRegion = region;
    s.blendWeight = 0.0f;
    s.coastDistance = float(cd);
    s.waterLevel = F_NAN;
    s.riverDistance = 10000.0f;
    s.riverHalfWidth = 0.0f;
    s.lakeDistance = -10000.0f;
    s.road = 0.0f;
    if (cd <= 0.0l) {
        s.elevation = float(double(T_SEA) - seaDepth(-cd, bad));
        s.ridge = 0.0f;
        s.roughness = 0.2f;
        s.crag = 0.0f;
        s.dunes = 0.0f;
        s.wetland = 0.0f;
        deriveStrengths(s, bad);
        return;
    }
    double X = double(x), Z = double(z);
    float tBase = mf(t), tRelief = mf(t + 1u), tRough = mf(t + 2u), tCrag = mf(t + 3u), tDunes = mf(t + 4u), tWet = mf(t + 5u),
          tCoast = mf(t + 6u);
    double base = double(tBase) + double(tRelief) * mnFbm(X, Z, 1200.0l, 3, seed + 1, bad)
            + double(tRough * 26.0f) * (mnRidged(X, Z, 1500.0l, 3, seed + 2, bad) - 0.42l);
    double ramp = jmaxD(60.0l, double(tCoast) * 1.8l);
    double beach = double(T_SEA) + 0.05l * jminD(cd, 64.0l);
    double h = beach + (jmaxD(base, beach) - beach) * mnSmooth(48.0l, 48.0l + ramp, cd, bad);
    double ridge = 0.0l;
    if (mountains > 0.0f) {
        double rn = mnRidged(X, Z, 1900.0l, 4, seed + 3, bad);
        double m = mnSmooth(0.42l, 0.85l, rn, bad) * double(mountains) * mnSmooth(150.0l, 1100.0l, cd, bad);
        h = h + jmaxD(0.0l, double(peak) - h) * m * m * (3.0l - 2.0l * m) * 0.9l;
        ridge = m;
    }
    if (tWet > 0.2f) {
        double wv = mnSmooth(0.2l, 0.6l, double(tWet), bad);
        double wl = (double(T_SEA) + 0.7l) + 1.7l * mnFbm(X, Z, 160.0l, 2, 41, bad);
        h = h + (wl - h) * wv * mnSmooth(double(T_SEA + 45), double(T_SEA + 20), h, bad);
        s.wetland = float(wv);
    } else {
        s.wetland = 0.0f;
    }
    s.elevation = float(jminD(h, 372.0l));
    s.ridge = float(ridge);
    s.roughness = float(clampJ(double(tRough) + ridge * 0.45l, 0.0l, 1.0l));
    s.crag = float(clampJ(double(tCrag) + ridge * 0.6l * mnSmooth(110.0l, 200.0l, h, bad), 0.0l, 1.0l));
    s.dunes = tDunes;
    deriveStrengths(s, bad);
}
void mergeForeign(int x, int z, inout MapS s, inout uint bad) {
    int best = -1;
    double bestCd = double(s.coastDistance);
    for (uint i = 0u; i < landCount(); i++) {
        uint l = landAt(i);
        if (!landNear(l, double(x), double(z))) continue;
        double cd = landField(l, double(x), double(z), bad);
        if (cd > bestCd) {
            bestCd = cd;
            best = int(i);
        }
    }
    if (best < 0) return;
    uint bl = landAt(uint(best));
    int fr = fixedRegion(uint(best), double(x), double(z), bad);
    int region = bestCd > 0.0l ? fr : s.region;
    fillLand(x, z, bestCd, region, terrainAt(fr), mf(bl + 2u), 270.0f, mi(bl + 1u), s, bad);
    s.inTamriel = true;
}
double edgeCoast(double ex, double ez, inout uint bad) {
    double fx = (ex - double(T_MIN_X)) * 0.03125l - 0.5l, fz = (ez - double(T_MIN_Z)) * 0.03125l - 0.5l;
    int ix = floorD(fx, bad), iz = floorD(fz, bad);
    double tx = fx - double(ix), tz = fz - double(iz);
    double a = double(rcell(uint(clampMaxI(iz, T_H - 1) * T_W + clampMaxI(ix, T_W - 1)), RC_COAST));
    double b = double(rcell(uint(clampMaxI(iz, T_H - 1) * T_W + clampMaxI(ix + 1, T_W - 1)), RC_COAST));
    double c = double(rcell(uint(clampMaxI(iz + 1, T_H - 1) * T_W + clampMaxI(ix, T_W - 1)), RC_COAST));
    double d = double(rcell(uint(clampMaxI(iz + 1, T_H - 1) * T_W + clampMaxI(ix + 1, T_W - 1)), RC_COAST));
    return (a + (b - a) * tx) * (1.0l - tz) + (c + (d - c) * tx) * tz;
}
int seaRegion(int x, int z, double outside) {
    if (outside > 14000.0l) return 0;
    int dxw = T_MIN_X - x, dxe = x - T_MAX_X, dzn = T_MIN_Z - z, dzs = z - T_MAX_Z;
    int m1 = dxw > dxe ? dxw : dxe, m2 = dzn > dzs ? dzn : dzs;
    int m = m1 > m2 ? m1 : m2;  // (no max: extended instructions are refused)
    if (m == dzn) return beyondRegion(R_GHOSTS);
    if (m == dxe) return z > 9000 ? beyondRegion(R_ELTHERIC) : beyondRegion(R_PADOMAIC);
    if (m == dzs) return x < -6000 ? beyondRegion(R_ABECEAN) : beyondRegion(R_ELTHERIC);
    return z < -1000 ? beyondRegion(R_GHOSTS) : beyondRegion(R_ABECEAN);
}
void beyondSample(int x, int z, inout MapS s, inout uint bad) {
    msClear(s);
    double ex = clampJ(double(x), double(T_MIN_X + 48), double(T_MAX_X - 49));
    double ez = clampJ(double(z), double(T_MIN_Z + 48), double(T_MAX_Z - 49));
    double outside = hypotS(double(x) - ex, double(z) - ez, bad);
    double edgeCd = mapLoaded() ? edgeCoast(ex, ez, bad) - outside : -100000.0l;
    double cd = edgeCd;
    int region = seaRegion(x, z, outside);
    uint terrain = terrainAt(-1);
    float mountains = 0.0f, peak = 260.0f;
    int seed = 0;
    for (uint i = 0u; i < landCount(); i++) {
        uint l = landAt(i);
        if (!landNear(l, double(x), double(z))) continue;
        double c = landField(l, double(x), double(z), bad);
        if (c > cd) {
            cd = c;
            int fr = fixedRegion(i, double(x), double(z), bad);
            terrain = terrainAt(fr);
            mountains = mf(l + 2u);
            peak = 270.0f;
            seed = mi(l + 1u);
            if (c > 0.0l) region = fr;
        }
    }
    fillLand(x, z, cd, region, terrain, mountains, peak, seed, s, bad);
    s.inTamriel = false;
}

// ---- TamrielMap.sample ------------------------------------------------------------------------------------------------
MapS tamrielSample(int x, int z, inout uint bad) {
    MapS s;
    msClear(s);
    s.peak = 0.0f;
    s.calm = 0.0f;
    s.slope = 0.0f;
    s.rainshadow = 0.0f;
    s.heat = 0.0f;
    s.aspectX = 0.0f;
    s.aspectZ = 0.0f;
    if (rasterCovers(double(x), double(z))) {
        rasterSample(x, z, s, bad);
        if (nearForeign(x, z)) mergeForeign(x, z, s, bad);
    } else {
        beyondSample(x, z, s, bad);
    }
    return s;
}
