// The client generator's pages made on the GPU (docs/ATLAS-CLIENT-GENERATION.md): the 2D Tamriel inputs Java's
// AtlasGenPages computed per chunk, from the map words (AtlasGenMap: the raster, hydrology, peaks, foreign lands, the
// ocean isles, the road and street plans, the biome palettes) and the program (the four height noises, the climate
// router's entries, the fields' kinds). A literal port of the mod's Java, term for term as atlas_map.slang:
//   TamrielHeight.height   (com.nirncraft.tamriel.terrain), with OceanIsles.surfaceHeight and PlanGround's bed / pad /
//                          plot (com.nirncraft.worldplan: RoadPlan, StreetPlan)
//   TamrielChannel.value   (+ PlanGround.influence on the road and overhang channels)
//   the map water word     (AtlasGenPages: MapWaterFeature's test)
//   the quart biomes       (TamrielBiomeSource.getNoiseBiome with Palettes and BiomeNoise, in ChunkGenerator
//                          .createBiomes' order: a column's climate from its first quart, y = -64)
// Included by atlas_gen.slang after atlas_map.slang. Tile plans beyond Tamriel (TilePlanner) are not on the GPU: Java
// uses one only where its planner has finished that tile, so outside Tamriel a road shaped by one differs (flagged in
// the docs; the raster covers every playable province).

// ---- the map words, continued (AtlasGenMap) --------------------------------------------------------------------
static const uint MH_ISLES = 8, MH_ROADS = 9, MH_STREETS = 10, MH_PAL = 11, MH_CONSTS = 12;
// program scalars (AtlasGenProgram): the climate entries (6 start / end pairs: temperature, humidity, continentalness,
// erosion, depth, weirdness), the fields' kinds, the height noises' stacks
static const uint S_CLIMATE = 96, S_FKINDS = 108, S_HNOISE = 109, S_PAGEGEN = 113, S_CLIMFIELDS = 114;
static const uint FK_HEIGHT = 0;  // else 1 + TamrielChannel.Channel ordinal
static const uint CHN_ELEVATION = 0, CHN_CONT = 1, CHN_EROSION = 2, CHN_RIDGES = 3, CHN_RIVER = 4, CHN_LAKE = 5,
                  CHN_ROUGH = 6, CHN_CRAG = 7, CHN_DUNES = 8, CHN_WET = 9, CHN_WATERLEVEL = 10, CHN_COAST = 11,
                  CHN_ROAD = 12, CHN_OVERHANG = 13;
static const uint BIOME_NONE = 0xFFFFu;

uint mconst(uint k) { return mw(mh(MH_CONSTS) + k); }
float mconstF(uint k) { return asfloat(mw(mh(MH_CONSTS) + k)); }
// MH_CONSTS: PlanGround FLAT_PAD, TRAIL_PAD, SHOULDER_MIN, SHOULDER_MAX, OUTER, RAMP, EPS, CALM; PlanTerrain.PAD_BLEND;
// StreetPlan.PAD_BLEND; TamrielHeight.MAX_SURFACE (double, 2 words)
static const uint KC_FLAT = 0, KC_TRAIL = 1, KC_SHMIN = 2, KC_SHMAX = 3, KC_OUTER = 4, KC_RAMP = 5, KC_EPS = 6, KC_CALM = 7,
                  KC_PADBLEND = 8, KC_STREETBLEND = 9, KC_MAXSURF = 10;

// (bx, bz, k) triples sorted by (bx, bz): k of the key, or -1
int keyFind(uint at, uint n, int bx, int bz) {
    int lo = 0, hi = int(n) - 1;
    while (lo <= hi) {
        int mid = int(uint(lo + hi) >> 1);
        uint w = at + uint(mid) * 3u;
        int kx = mi(w), kz = mi(w + 1u);
        if (kx == bx && kz == bz) return mi(w + 2u);
        if (kx < bx || (kx == bx && kz < bz)) lo = mid + 1;
        else hi = mid - 1;
    }
    return -1;
}

float smoothPG(float t) { return t * t * (3.0f - 2.0f * t); }  // PlanGround.smooth / StreetPlan.smooth

// ---- PlanGround ------------------------------------------------------------------------------------------------
// roads (MH_ROADS): [0] keys, [1] keysAt, [2] bucketOffAt, [3] bucketEdgesAt, [4] vx, [5] vz, [6] vh, [7] vg, [8] varc,
// [9] vspan, [10] vseg, [11] invLen2, [12] segHalfWidth, [13] segKind, [14] spanOff, [15] spanFrom, [16] spanTo,
// [17] siteKeys, [18] siteKeysAt, [19] siteOffAt, [20] siteListAt, [21] siteX, [22] siteZ, [23] siteRadius, [24] sitePad
uint rdw(uint k) { return mw(mh(MH_ROADS) + k); }
float rdF(uint arr, int i) { return mf(rdw(arr) + uint(i)); }
int rdI(uint arr, int i) { return mi(rdw(arr) + uint(i)); }
static const int SPAN_BRIDGE = 3, SPAN_FORD = 5, SPAN_TUNNEL = 8;

struct Bed {
    float weight, height;
    int span;  // the nearest road's
    float padWeight, padHeight, plotWeight, plotHeight;
};
void bedClear(inout Bed b) {
    b.weight = 0.0f;
    b.height = F_NAN;
    b.span = 0;
    b.padWeight = 0.0f;
    b.padHeight = F_NAN;
    b.plotWeight = 0.0f;
    b.plotHeight = F_NAN;
}
int spanAtR(int s, int v) {
    for (int k = rdI(14, s); k < rdI(14, s + 1); k++)
        if (v >= rdI(15, k) && v <= rdI(16, k)) return k;
    return -1;
}
// PlanGround.bed (the authored plan)
[noinline]
bool planBed(int x, int z, inout Bed b) {
    bedClear(b);
    if (rdw(0) == 0u) return false;
    int k = keyFind(rdw(1), rdw(0), x >> 5, z >> 5);
    if (k < 0) return false;
    float OUTER = mconstF(KC_OUTER);
    float px = float(x), pz = float(z);
    int n = 0;
    int cEdge[4];
    float cD2[4], cT[4];
    for (int c0 = 0; c0 < 4; c0++) { cEdge[c0] = 0; cD2[c0] = 0.0f; cT[c0] = 0.0f; }
    int j1 = rdI(2, k + 1);
    for (int j = rdI(2, k); j < j1; j++) {
        int i = rdI(3, j);
        float ax = rdF(4, i), az = rdF(5, i);
        float dx = rdF(4, i + 1) - ax, dz = rdF(5, i + 1) - az;
        float t = ((px - ax) * dx + (pz - az) * dz) * rdF(11, i);
        t = t < 0.0f ? 0.0f : (t > 1.0f ? 1.0f : t);
        float qx = ax + dx * t - px, qz = az + dz * t - pz;
        float d2 = qx * qx + qz * qz;
        if (d2 >= OUTER * OUTER) continue;
        int slot = -1;
        for (int c = 0; c < n; c++) {
            int e = cEdge[c];
            int de = e - i;
            if (rdI(10, e) == rdI(10, i) && (de < 0 ? -de : de) <= 4) {
                slot = c;
                break;
            }
        }
        if (slot >= 0) {
            if (d2 >= cD2[slot]) continue;
        } else if (n < 4) {
            slot = n;
            n++;
        } else {
            int worst = 0;
            for (int c = 1; c < 4; c++) if (cD2[c] > cD2[worst]) worst = c;
            if (d2 >= cD2[worst]) continue;
            slot = worst;
        }
        cEdge[slot] = i;
        cD2[slot] = d2;
        cT[slot] = t;
    }
    if (n == 0) return false;
    float FLAT = mconstF(KC_FLAT), TRAIL = mconstF(KC_TRAIL), SHMIN = mconstF(KC_SHMIN), SHMAX = mconstF(KC_SHMAX);
    float RAMP = mconstF(KC_RAMP), EPS = mconstF(KC_EPS);
    float sumW = 0.0f, sumWH = 0.0f, maxW = 0.0f, nearest = asfloat(0x7F7FFFFFu);
    for (int c = 0; c < n; c++) {
        int i = cEdge[c], s = rdI(10, i);
        float t = cT[c], d = float(sqrtD(double(cD2[c])));
        float hw = rdF(12, s);
        int kind = rdI(13, s);
        float h = rdF(6, i) + (rdF(6, i + 1) - rdF(6, i)) * t;
        float g = rdF(7, i) + (rdF(7, i + 1) - rdF(7, i)) * t;
        float along = rdF(8, i) + (rdF(8, i + 1) - rdF(8, i)) * t;
        int v = t < 0.5f ? i : i + 1;
        int span = rdI(9, v);
        float r = hw + (kind == 3 ? TRAIL : FLAT);
        float len = jminF(SHMAX, SHMIN + 0.75f * absF(g - h));
        len = jmaxF(3.0f, jminF(len, OUTER - r));
        float w = d <= r ? 1.0f : (d >= r + len ? 0.0f : 1.0f - smoothPG(divF(d - r, len)));
        if (span == SPAN_BRIDGE || span == SPAN_TUNNEL) {
            int sp = spanAtR(s, v);
            if (sp >= 0) {
                float a0 = rdF(8, rdI(15, sp)), a1 = rdF(8, rdI(16, sp));
                float into = jmaxF(0.0f, jminF(along - a0, a1 - along)) - FLAT;
                float room = (a1 - a0) - 2.0f * FLAT;
                if (room > 0.5f) {
                    float ramp = jminF(RAMP, 0.4f * room);
                    float fade = jmaxF(0.0f, jminF(1.0f, divF(into, ramp)));
                    w = w * (1.0f - smoothPG(fade));
                }
            }
        }
        sumW = sumW + w * w;
        sumWH = sumWH + w * w * (h + EPS);
        if (w > maxW) maxW = w;
        if (d < nearest) {
            nearest = d;
            b.span = span;
        }
    }
    if (maxW <= 0.0f) return false;
    b.weight = maxW;
    b.height = divF(sumWH, sumW);
    return true;
}
// PlanGround.pad
[noinline]
bool planPad(int x, int z, inout Bed b) {
    b.padWeight = 0.0f;
    b.padHeight = F_NAN;
    if (rdw(17) == 0u) return false;
    int k = keyFind(rdw(18), rdw(17), x >> 8, z >> 8);
    if (k < 0) return false;
    float BLEND = mconstF(KC_PADBLEND);
    float bestW = 0.0f, pad = F_NAN;
    int j1 = rdI(19, k + 1);
    for (int j = rdI(19, k); j < j1; j++) {
        int i = rdI(20, j);
        float dx = float(x - rdI(21, i)), dz = float(z - rdI(22, i));
        float d = float(sqrtD(double(dx * dx + dz * dz))), r = float(rdI(23, i));
        if (d >= r + BLEND) continue;
        float w = d <= r ? 1.0f : 1.0f - divF(d - r, BLEND);
        w = w * w * (3.0f - 2.0f * w);
        if (w > bestW) {
            bestW = w;
            pad = rdF(24, i);
        }
    }
    if (bestW <= 0.0f) return false;
    b.padWeight = bestW;
    b.padHeight = pad;
    return true;
}
// StreetPlan.pad (MH_STREETS): [0] keys, [1] keysAt, [2] plotOff, [3] plots, [4] edgeOff, [5] edges, [6] pPad,
// [7] pMinX, [8] pMinZ, [9] pMaxX, [10] pMaxZ, [11] eClass, [12] ea, [13] eb, [14] nx, [15] nz, [16] nh, [17] ew, [18] eInv
uint stw(uint k) { return mw(mh(MH_STREETS) + k); }
float stF(uint arr, int i) { return mf(stw(arr) + uint(i)); }
int stI(uint arr, int i) { return mi(stw(arr) + uint(i)); }
[noinline]
bool streetPad(int x, int z, inout Bed b) {
    b.plotWeight = 0.0f;
    b.plotHeight = F_NAN;
    if (stw(0) == 0u) return false;
    int k = keyFind(stw(1), stw(0), x >> 4, z >> 4);
    if (k < 0) return false;
    float BLEND = mconstF(KC_STREETBLEND);
    float bestW = 0.0f, h = F_NAN;
    int j1 = stI(2, k + 1);
    for (int j = stI(2, k); j < j1; j++) {
        int p = stI(3, j);
        if (stI(6, p) == int(0x80000000u)) continue;  // NO_PAD
        int ox = x < stI(7, p) ? stI(7, p) - x : (x > stI(9, p) ? x - stI(9, p) : 0);
        int oz = z < stI(8, p) ? stI(8, p) - z : (z > stI(10, p) ? z - stI(10, p) : 0);
        float d = float(ox > oz ? ox : oz) - 1.0f;
        float w = d <= 0.0f ? 1.0f : (d >= BLEND ? 0.0f : 1.0f - smoothPG(divF(d, BLEND)));
        if (w > bestW) {
            bestW = w;
            h = float(stI(6, p)) + 0.5f;
        }
    }
    if (bestW < 1.0f) {
        float px = float(x) + 0.5f, pz = float(z) + 0.5f;
        int e1 = stI(4, k + 1);
        for (int j = stI(4, k); j < e1; j++) {
            int e = stI(5, j);
            int c = stI(11, e);
            if (c == 3 || c == 5 || c == 4) continue;  // PATH, BRIDGE, STAIRS: painted, not shaped
            int ia = stI(12, e), ib = stI(13, e);
            float ax = stF(14, ia), az = stF(15, ia);
            float ddx = stF(14, ib) - ax, ddz = stF(15, ib) - az;
            float t = ((px - ax) * ddx + (pz - az) * ddz) * stF(18, e);
            t = t < 0.0f ? 0.0f : (t > 1.0f ? 1.0f : t);
            float qx = ax + ddx * t - px, qz = az + ddz * t - pz;
            float d = float(sqrtD(double(qx * qx + qz * qz))) - stF(17, e) * 0.5f - 1.0f;
            if (d >= BLEND) continue;
            float w = d <= 0.0f ? 1.0f : 1.0f - smoothPG(divF(d, BLEND));
            if (w > bestW) {
                bestW = w;
                float nha = stF(16, ia);
                h = float(floorD(double(nha + (stF(16, ib) - nha) * t + 0.5f))) + 0.5f;
            }
        }
    }
    if (bestW <= 0.0f) return false;
    b.plotWeight = bestW;
    b.plotHeight = h;
    return true;
}
// PlanGround.influence
[noinline]
float planInfluence(int x, int z) {
    Bed b;
    bedClear(b);
    float w = 0.0f;
    if (planBed(x, z, b)) w = b.weight;
    if (planPad(x, z, b)) w = jmaxF(w, b.padWeight);
    if (streetPad(x, z, b)) w = jmaxF(w, b.plotWeight);
    return w;
}

// ---- OceanIsles.surfaceHeight -----------------------------------------------------------------------------------
// isles (MH_ISLES): [0] cx0, [1] cz0, [2] w, [3] h, [4] gridAt (record or -1 per cell), [5] recordsAt; a record: centre
// x, z, site x, z, type, radius, peak (float), pad, then unit(hash, 8), unit(hash, 9), unit(hash, 10) as doubles
static const double PI_DD = asdouble(0x54442D18u, 0x400921FBu);
[noinline]
double isleHeight(int x, int z, double cd, double hillsN, double detailN) {
    double NAN_D = asdouble(0u, 0x7FF80000u);
    if (cd >= -96.0l) return NAN_D;
    uint I = mh(MH_ISLES);
    int cx = floorDivI(x, 512), cz = floorDivI(z, 512);
    int gx = cx - mi(I), gz = cz - mi(I + 1u);
    if (gx < 0 || gz < 0 || gx >= mi(I + 2u) || gz >= mi(I + 3u)) return NAN_D;
    int rec = mi(mw(I + 4u) + uint(gz * mi(I + 2u) + gx));
    if (rec < 0) return NAN_D;
    uint R = mw(I + 5u) + uint(rec) * 14u;
    int centerX = mi(R), centerZ = mi(R + 1u), siteX = mi(R + 2u), siteZ = mi(R + 3u), type = mi(R + 4u), radius = mi(R + 5u);
    double peak = double(mf(R + 6u));
    double u8 = md(R + 8u), u9 = md(R + 10u), u10 = md(R + 12u);
    double dx = double(x - centerX), dz = double(z - centerZ);
    double r = double(radius);
    double outer = r + 36.0l;
    if (dx * dx + dz * dz > outer * outer) return NAN_D;
    double ang = atan2S(dz, dx);
    double stretch = 1.0l + 0.18l * cosS(2.0l * ang + u8 * 6.283l);
    double d = hypotS(dx, dz) * stretch - detailN * 3.2l - hillsN * 2.5l;
    if (d > outer) return NAN_D;
    double sea = double(T_SEA);
    if (type == 0) {  // sea stack
        double tMain = clampJ(1.0l - divD(d, r), 0.0l, 1.0l);
        double hMain = sea - 14.0l + (14.0l + peak) * powS(tMain, 1.35l) + jmaxD(0.0l, detailN) * 2.5l * tMain;
        double satAng = u9 * PI_DD * 2.0l;
        double sx = double(centerX) + cosS(satAng) * (r * 0.85l);
        double sz = double(centerZ) + sinS(satAng) * (r * 0.85l);
        double sd = hypotS(double(x) - sx, double(z) - sz) - detailN * 1.8l;
        double tSat = clampJ(1.0l - divD(sd, r * 0.48l), 0.0l, 1.0l);
        double hSat = sea - 14.0l + (14.0l + peak * 0.7l) * powS(tSat, 1.4l);
        return jmaxD(hMain, hSat);
    }
    if (type == 1) {  // atoll
        double ringR = r * 0.58l;
        double band = absD2(d - ringR);
        double ringW = r * 0.30l;
        double tRing = clampJ(1.0l - divD(band, ringW), 0.0l, 1.0l);
        double sr = tRing * tRing * (3.0l - 2.0l * tRing);
        double hRing = d < ringR ? sea - 2.2l + (2.2l + peak) * sr : sea - 14.0l + (14.0l + peak) * sr;
        double cdx = double(x - siteX), cdz = double(z - siteZ);
        double cdCay = hypotS(cdx, cdz) - detailN * 2.0l;
        double tCay = clampJ(1.0l - divD(cdCay, r * 0.42l), 0.0l, 1.0l);
        double hCay = sea - 8.0l + (8.0l + peak + 1.5l) * (tCay * tCay * (3.0l - 2.0l * tCay));
        return jmaxD(hRing, hCay);
    }
    if (type == 2) {  // cay
        if (d >= outer) return NAN_D;
        if (d > r) {
            double shoal = mnSmooth(outer, r, d);
            return sea - 16.0l + 16.0l * shoal;
        }
        double t = clampJ(1.0l - divD(d, r), 0.0l, 1.0l);
        double crown = t * t * (3.0l - 2.0l * t);
        return sea + 0.5l + peak * crown + detailN * 0.8l * crown;
    }
    // sandbar
    double dir = u10 * PI_DD;
    double co = cosS(dir), si = sinS(dir);
    double lx = dx * co + dz * si;
    double lz = -dx * si + dz * co - 0.012l * lx * lx;
    double ed = hypotS(lx * 0.62l, lz * 1.25l) - detailN * 2.2l;
    if (ed > outer) return NAN_D;
    if (ed > r) {
        double shoal = mnSmooth(outer, r, ed);
        return sea - 14.0l + 14.0l * shoal;
    }
    double t = clampJ(1.0l - divD(ed, r), 0.0l, 1.0l);
    return sea + 0.4l + peak * (t * t * (3.0l - 2.0l * t));
}

// ---- TamrielHeight.height ----------------------------------------------------------------------------------------
float hnoise(uint k, double x, double y, double z) { return stackGet(hdr(S_HNOISE + k), x, y, z); }
[noinline]
double tamrielHeight(int x, int z, MapS s) {
    double h = double(s.elevation);
    double cd = double(s.coastDistance);
    double X = double(x), Z = double(z);
    double hillsN = double(hnoise(0u, X, 0.0l, Z));
    double detailN = double(hnoise(1u, X, 0.0l, Z));
    if (cd < 0.0l) {
        double floor_ = jminD(h + (hillsN * 5.0l + detailN * 1.5l) * (0.4l + 0.6l * mnSmooth(-600.0l, 0.0l, cd)),
                              double(T_SEA - 1));
        if (cd < -96.0l) {
            double isle = isleHeight(x, z, cd, hillsN, detailN);
            if (!isNaND(isle)) {
                double w = mnSmooth(-96.0l, -136.0l, cd);
                return jmaxD(floor_, floor_ + (isle - floor_) * w);
            }
        }
        return floor_;
    }
    float wBed = 0.0f, wPad = 0.0f, wPlot = 0.0f;
    Bed pb;
    bedClear(pb);
    bool inWater = msInWater(s);
    if (planBed(x, z, pb) && (!inWater || pb.span == SPAN_FORD)) wBed = pb.weight;
    bool tidal = s.elevation < float(T_SEA) - 0.5f && cd < 24.0l;
    if (!inWater && !tidal && planPad(x, z, pb)) wPad = pb.padWeight;
    if (!inWater && streetPad(x, z, pb)) wPlot = pb.plotWeight;

    double amp = mnSmooth(0.0l, 56.0l, cd) * (1.0l - 0.75l * double(s.road)) * double(1.0f - s.calm)
            * double(1.0f - mconstF(KC_CALM) * jmaxF(wBed, jmaxF(wPad, wPlot)));
    bool water = !isNaNF(s.waterLevel);
    if (water) {
        double reach = 30.0l + 40.0l * clampJ(divD(double(s.riverHalfWidth - 1.0f), 3.0l), 0.0l, 1.0l);
        amp = amp * jminD(mnSmooth(double(s.riverHalfWidth + 4.0f), double(s.riverHalfWidth) + reach, double(s.riverDistance)),
                          mnSmooth(0.0l, 70.0l, double(-s.lakeDistance)));
    }
    double smooth = double(divF(1.0f, 1.0f + 6.0f * s.slope * s.slope));
    h = h + amp * double(s.roughness) * (hillsN * 22.0l + detailN * 6.5l * smooth);

    double own = jminD(1.0l, divD(double(s.peak), 8.0l));
    if (s.ridge > 0.02f && own < 1.0l) {
        double r1 = 1.0l - absD2(double(hnoise(2u, X, 0.0l, Z)));
        double r2 = 1.0l - absD2(double(hnoise(1u, X * 2.1l, 37.0l, Z * 2.1l)));
        double high = 1.0l + 0.6l * mnSmooth(150.0l, 300.0l, h);
        h = h + amp * (1.0l - own) * powS(double(s.ridge), 1.3l) * high
                * (r1 * r1 * 36.0l + r1 * r2 * r2 * 12.0l * (0.4l + 0.6l * smooth) - 15.0l);
    }
    if (s.peak > 0.0f) {
        double r1 = 1.0l - absD2(double(hnoise(2u, X * 1.7l, 113.0l, Z * 1.7l)));
        double r2 = 1.0l - absD2(double(hnoise(1u, X * 3.3l, 131.0l, Z * 3.3l)));
        h = h + amp * double(s.peak) * ((r1 * r1 - 0.45l) + 0.4l * r1 * (r2 * r2 - 0.45l) * (0.4l + 0.6l * smooth));
    }
    if (s.crag > 0.05f) {
        double steep = mnSmooth(0.25l, 0.85l, double(s.slope));
        double w = double(s.crag) * 0.85l * amp * (0.15l + 0.85l * steep);
        if (w > 0.01l) {
            double step = 8.0l + 4.0l * hillsN;
            double off = double(hnoise(3u, X * 0.22l, 61.0l, Z * 0.22l)) * 9.0l;
            double t = divD(h + off, step);
            double fl = double(floorD(t));
            double terraced = (fl + mnSmooth(0.5l, 0.9l, t - fl)) * step - off;
            h = h + (terraced - h) * w;
        }
    }
    if (s.dunes > 0.02f) {
        double dn = double(hnoise(3u, X * 0.8l + Z * 0.35l, 0.0l, Z * 1.7l - X * 0.2l));
        double crest = 1.0l - absD2(dn);
        h = h + amp * double(s.dunes) * (crest * crest * 11.0l - 2.0l);
    }
    if (s.wetland > 0.05f && h < double(T_SEA + 10)) {
        double wl = double(T_SEA) + 0.4l + detailN * 1.8l + hillsN * 0.8l + jmaxD(0.0l, hillsN) * 2.5l;
        h = h + (wl - h) * double(s.wetland) * 0.6l;
        if (s.wetland > 0.3f) {
            double c1 = absD2(double(hnoise(2u, X * 1.9l, 57.0l, Z * 1.9l)));
            double c2 = absD2(double(hnoise(0u, X * 3.6l + 400.0l, 23.0l, Z * 3.6l))) * 1.4l;
            double k = 1.0l - divD(jminD(c1, c2), 0.11l);
            if (k > 0.0l) {
                double bed = double(T_SEA) - 1.2l - 2.4l * k;
                double w = mnSmooth(0.3l, 0.8l, double(s.wetland)) * jminD(1.0l, k * 3.0l) * (1.0l - 0.8l * double(s.road));
                h = h + (jminD(bed, h) - h) * w;
            }
        }
    }
    if (water) {
        double wl = double(s.waterLevel);
        if (s.lakeDistance > -40.0f) {
            if (s.lakeDistance > 0.0f) h = jminD(h, wl - jminD(12.0l, 1.2l + double(s.lakeDistance) * 0.25l));
            else h = jmaxD(h, wl + 1.0l - double(s.lakeDistance) * 0.04l);
        }
        double hw = double(s.riverHalfWidth);
        double bankReach = 6.0l + 10.0l * clampJ(divD(hw - 1.0l, 3.0l), 0.0l, 1.0l);
        if (hw > 0.0l && double(s.riverDistance) < hw + bankReach) {
            double d = double(s.riverDistance);
            if (d < hw) {
                double depth = clampJ(hw * 0.3l, hw < 4.0l ? 1.0l + 0.25l * hw : 1.5l, 5.0l);
                double u = divD(d, hw);
                h = jminD(h, wl - 0.6l - depth * (1.0l - u * u));
            } else if (s.lakeDistance < -8.0f) {
                h = jmaxD(h, wl + 1.0l + (d - hw) * 0.05l);
            }
        }
    }
    if (wPad > 0.0f) h = h + (double(pb.padHeight) - h) * double(wPad);
    if (wPlot > 0.0f) h = h + (double(pb.plotHeight) - h) * double(wPlot);
    if (wBed > 0.0f) h = h + (double(pb.height) - h) * double(wBed);
    return jminD(h, md(mh(MH_CONSTS) + KC_MAXSURF));
}

// ---- TamrielChannel.value (+ PlanGround.influence) ---------------------------------------------------------------
double continentalness(double cd) {
    if (cd < 0.0l) return jmaxD(-1.2l, -0.19l + divD(cd, 1500.0l));
    if (cd < 64.0l) return -0.19l + divD(0.08l * cd, 64.0l);
    return -0.11l + 1.11l * jminD(1.0l, divD(cd - 64.0l, 2500.0l));
}
[noinline]
double channelValue(uint ch, int x, int z, MapS s) {
    double cd = double(s.coastDistance);
    if (ch == CHN_ELEVATION) return double(s.elevation);
    if (ch == CHN_CONT) return continentalness(cd);
    if (ch == CHN_EROSION)
        return 0.75l - 1.5l * clampJ(double(s.roughness) * 0.55l + double(s.ridge) * 0.65l + double(s.crag) * 0.15l, 0.0l, 1.0l);
    if (ch == CHN_RIDGES) return double(s.ridge);
    if (ch == CHN_RIVER) return double(s.river);
    if (ch == CHN_LAKE) return double(s.lake);
    if (ch == CHN_ROUGH) return double(s.roughness);
    if (ch == CHN_CRAG) return double(s.crag);
    if (ch == CHN_DUNES) return double(s.dunes);
    if (ch == CHN_WET) return double(s.wetland);
    if (ch == CHN_WATERLEVEL) return isNaNF(s.waterLevel) ? -64.0l : double(s.waterLevel);
    if (ch == CHN_COAST) return cd;
    if (ch == CHN_ROAD) {
        double v = double(jmaxF(s.road, s.calm));
        return jmaxD(v, double(planInfluence(x, z)));
    }
    // overhang
    double v = mnSmooth(0.3l, 0.8l, double(s.ridge)) * (0.35l + 0.65l * double(s.crag)) * mnSmooth(30.0l, 120.0l, cd)
            * double(1.0f - s.calm) * (1.0l - 0.8l * double(s.road));
    return v * (1.0l - double(planInfluence(x, z)));  // (TamrielChannel.compute holds the influence in a double)
}

// ---- BiomeNoise, Palettes' hashes, Math.round -------------------------------------------------------------------
int bnHash(int x, int z, int seed) {
    uint h = (uint(x) * 0x27D4EB2Du) ^ (uint(z) * 0x165667B1u) ^ (uint(seed) * 0x5BD1E995u);
    h = h ^ (h >> 15);
    h = h * 0x2C1B3C6Du;
    h = h ^ (h >> 12);
    h = h * 0x297A2D39u;
    h = h ^ (h >> 15);
    return int(h);
}
double bnUnit(int h) { return double(int(uint(h) & 0xFFFFFFu)) * asdouble(0u, 0x3E800000u) - 1.0l; }  // / 2^23 - 1
double bnFade(double t) { return t * t * (3.0l - 2.0l * t); }
[noinline]
double bnValue2(double x, double z, int seed) {
    int x0 = floorD(x), z0 = floorD(z);
    double fx = bnFade(x - double(x0)), fz = bnFade(z - double(z0));
    double a = bnUnit(bnHash(x0, z0, seed)), b = bnUnit(bnHash(x0 + 1, z0, seed));
    double c = bnUnit(bnHash(x0, z0 + 1, seed)), d = bnUnit(bnHash(x0 + 1, z0 + 1, seed));
    double ab = a + (b - a) * fx, cd = c + (d - c) * fx;
    return ab + (cd - ab) * fz;
}
double bnValue3(double x, double y, double z, int seed) {
    int y0 = floorD(y);
    double fy = bnFade(y - double(y0));
    double lo = bnValue2(x, z, bnHash(y0, 0, seed)), hi = bnValue2(x, z, bnHash(y0 + 1, 0, seed));
    return lo + (hi - lo) * fy;
}
double bnFbm2(double x, double z, int seed) {
    return bnValue2(x, z, seed) * 0.7l + bnValue2(x * 2.3l, z * 2.3l, seed + 17) * 0.3l;
}
double bnCdf(double v) {
    double p = divD(1.0l, 1.0l + expS(-1.702l * v));
    return jminD(0.99999l, jmaxD(0.0l, p));
}
// Math.round(double) (Java's bit version), as int (the callers' values are small)
int jroundD(double a) {
    int hi = hiW(a), lo = loW(a);
    int biasedExp = (hi >> 20) & 0x7FF;
    int shift = (53 - 2 + 1023) - biasedExp;
    if ((shift & -64) == 0) {
        int64_t r = (int64_t(uint(hi) & 0xFFFFFu) << 32) | int64_t(uint(lo));
        r = r | (int64_t(1) << 52);
        if (hi < 0) r = -r;
        return int(((r >> uint(shift)) + int64_t(1)) >> 1u);
    }
    // (long) a: |a| < 2^-12 (0) or already integral
    return biasedExp < 1023 ? 0 : int(a);
}
float palHashUnitX(int x, int z, int salt) {
    uint h = uint(x) * 374761393u + uint(z) * 668265263u + uint(salt) * 1442695041u;
    h = (h ^ (h >> 13)) * 1274126177u;
    h = h ^ (h >> 16);
    return divF(float(int(h & 0xFFFFu)), 32767.5f) - 1.0f;
}
[noinline]
float microNoise(int qx, int qz, int salt) {
    int step = 6;
    int gx = floorDivI(qx, step), gz = floorDivI(qz, step);
    float tx = divF(float(qx - gx * step), 6.0f);
    float tz = divF(float(qz - gz * step), 6.0f);
    tx = tx * tx * (3.0f - 2.0f * tx);
    tz = tz * tz * (3.0f - 2.0f * tz);
    float v00 = palHashUnitX(gx, gz, salt), v10 = palHashUnitX(gx + 1, gz, salt);
    float v01 = palHashUnitX(gx, gz + 1, salt), v11 = palHashUnitX(gx + 1, gz + 1, salt);
    return (1.0f - tz) * ((1.0f - tx) * v00 + tx * v10) + tz * ((1.0f - tx) * v01 + tx * v11);
}

// ---- the palettes (MH_PAL) -----------------------------------------------------------------------------------------
// [0] regions, [1] regionsAt (palette, cave class per region), [2] fallback palette, [3] fallback cave class,
// [4] subterranean biomes (14 ids), [5] palettes, [6] palettesAt (offset per palette). A palette: [0] uplandY [1] peakY
// [2] summitY [3] micro rules [4] microAt [5] linear? [6] radial? [7] 0, [8..19] the contexts' choices, [20..31] linear
// (6 doubles), [32..41] radial (5 doubles). Choices: n, then (upper float, biome) pairs. A micro rule: biome, 16 floats.
static const uint CTX_OCEAN = 0, CTX_DEEP_OCEAN = 1, CTX_BEACH = 2, CTX_RIVER = 3, CTX_LAKE = 4, CTX_MARSH = 5,
                  CTX_LOWLAND = 6, CTX_UPLAND = 7, CTX_PEAK = 8, CTX_SUMMIT = 9, CTX_CAVE = 10, CTX_DEEP_CAVE = 11;
// the subterranean biomes, in TamrielBiomeSource's field order
static const uint SB_NORDIC = 0, SB_ICE = 1, SB_DWEMER = 2, SB_FALMER = 3, SB_LAVA = 4, SB_MUSHROOM = 5, SB_AYLEID = 6,
                  SB_VALENWOOD = 7, SB_HIST = 8, SB_ELSWEYR = 9, SB_SUMMERSET = 10, SB_YOKUDAN = 11, SB_SUBMERGED = 12,
                  SB_ABYSSAL = 13;
uint pw(uint k) { return mw(mh(MH_PAL) + k); }
uint subBiome(uint k) { return mw(pw(4) + k); }
uint palAt(uint p) { return mw(pw(6) + p); }
uint palChoices(uint pal, uint ctx) { return mw(pal + 8u + ctx); }
// Choices.pick(v): BIOME_NONE stands for "@surface"
uint choicesPick(uint c, double v) {
    uint n = mw(c);
    for (uint i = 0u; i < n; i++)
        if (v < double(mf(c + 1u + i * 2u))) return mw(c + 2u + i * 2u);
    return mw(c + 2u + (n - 1u) * 2u);
}
// Palette.bias
[noinline]
double palBias(uint pal, double v, int x, int z) {
    if (mw(pal + 5u) != 0u) {
        uint L = pal + 20u;
        double t = divD((double(x) - md(L)) * md(L + 4u) + (double(z) - md(L + 2u)) * md(L + 6u), md(L + 8u));
        v = v + md(L + 10u) * jmaxD(-1.0l, jminD(1.0l, t));
    }
    if (mw(pal + 6u) != 0u) {
        uint Rr = pal + 32u;
        double d = hypotS(double(x) - md(Rr), double(z) - md(Rr + 2u));
        double t = jmaxD(0.0l, jminD(1.0l, divD(d - md(Rr + 4u), md(Rr + 6u) - md(Rr + 4u))));
        v = v * (md(Rr + 8u) + (1.0l - md(Rr + 8u)) * t * t * (3.0l - 2.0l * t));
    }
    return jmaxD(0.0l, jminD(0.99999l, v));
}
// Palette.pickMicro: the winning rule's word offset, or 0
static const double RAD_TO_DEG = asdouble(0x1A63C1F8u, 0x404CA5DCu);  // Math.toDegrees' 57.29577951308232
[noinline]
uint pickMicro(uint pal, int qx, int qz, MapS s) {
    uint n = mw(pal + 3u);
    if (n == 0u) return 0u;
    float wd = msWaterDistance(s);
    float cd = jmaxF(0.0f, s.coastDistance);
    float effAx = s.aspectX * s.slope + microNoise(qx, qz, 701) * 0.14f;
    float effAz = s.aspectZ * s.slope + microNoise(qx, qz, 702) * 0.14f;
    float aspectDeg = float(atan2S(double(effAx), double(-effAz)) * RAD_TO_DEG);
    if (aspectDeg < 0.0f) aspectDeg = aspectDeg + 360.0f;
    uint at = mw(pal + 4u);
    for (uint k = 0u; k < n; k++) {
        uint r = at + k * 17u;
        float d = microNoise(qx, qz, int(k) * 31 + 17);
        float lo = mf(r + 1u), hi = mf(r + 2u);
        if (lo > -1.0e5f || hi < 1.0e5f) {
            float ev = s.elevation + d * 8.0f;
            if (ev < lo || ev > hi) continue;
        }
        lo = mf(r + 3u); hi = mf(r + 4u);
        if (lo > -1.0e5f || hi < 1.0e5f) {
            float sv = jmaxF(0.0f, s.slope + d * 0.035f);
            if (sv < lo || sv > hi) continue;
        }
        lo = mf(r + 5u); hi = mf(r + 6u);
        if (lo > -1.0e5f || hi < 1.0e5f) {
            float rv = s.rainshadow + d * 0.05f;
            if (rv < lo || rv > hi) continue;
        }
        lo = mf(r + 7u); hi = mf(r + 8u);
        if (lo > -1.0e5f || hi < 1.0e5f) {
            float hv = s.heat > 0.01f ? s.heat + d * 0.03f : 0.0f;
            if (hv < lo || hv > hi) continue;
        }
        lo = mf(r + 9u); hi = mf(r + 10u);
        if (lo > -1.0e5f || hi < 1.0e5f) {
            float wv = jmaxF(0.0f, wd + d * 12.0f);
            if (wv < lo || wv > hi) continue;
        }
        lo = mf(r + 11u); hi = mf(r + 12u);
        if (lo > -1.0e5f || hi < 1.0e5f) {
            float cv = jmaxF(0.0f, cd + d * 24.0f);
            if (cv < lo || cv > hi) continue;
        }
        lo = mf(r + 13u); hi = mf(r + 14u);
        if (lo > -1.0e5f || hi < 1.0e5f) {
            float rgv = s.ridge + d * 0.04f;
            if (rgv < lo || rgv > hi) continue;
        }
        lo = mf(r + 15u); hi = mf(r + 16u);
        if (!(lo <= 0.0f && hi >= 360.0f)) {
            float a = aspectDeg + d * 18.0f;
            // Java's float % 360 (exact: |a| < 720 here)
            float ad = absF(a) >= 360.0f ? (a < 0.0f ? a + 360.0f : a - 360.0f) : a;
            if (ad < 0.0f) ad = ad + 360.0f;
            bool ok = lo <= hi ? (ad >= lo && ad <= hi) : (ad >= lo || ad <= hi);
            if (!ok) continue;
        }
        return r;
    }
    return 0u;
}

// Climate's quantise / unquantise: (long) (v * 10000F), then (float) / 10000F
float climateQ(float v) {
    float m = v * 10000.0f;
    int q = m != m ? 0 : int(m);  // the climate's range: |v * 10000| < 2^31
    return divF(float(q), 10000.0f);
}

// a quart column's own part (TamrielBiomeSource.Column) and its climate
struct BCol {
    MapS s;
    uint pal;       // the palette's word offset
    uint cave;      // the region's cave class (pickProvinceCaveBiome)
    uint micro;     // the micro rule (word offset) or 0
    double noise;
    int jitter;
    int qx, qz;
    float temp, hum, cont, ero, weird, surface;
};

// TamrielMap.regionIndex (warped raster or the full sample), then Regions.byIndex and Palettes.of as tables
[noinline]
void regionPalette(int x, int z, inout BCol c) {
    int idx;
    if (rasterCovers(double(x), double(z)) && !nearForeign(x, z)) idx = regionIndexRaster(x, z);
    else idx = tamrielSample(x, z).region;
    if (idx >= 0 && idx < int(pw(0))) {
        c.pal = palAt(mw(pw(1) + uint(idx) * 2u));
        c.cave = mw(pw(1) + uint(idx) * 2u + 1u);
    } else {
        if (idx < 0) g_bad = g_bad | 16u;  // a generated region: the endless lands are off
        c.pal = palAt(pw(2));
        c.cave = pw(3);
    }
}

[noinline]
BCol biomeColumn(int qx, int qz) {
    BCol c;
    int x = qx << 2, z = qz << 2;
    c.qx = qx;
    c.qz = qz;
    c.s = tamrielSample(x, z);
    int h = bnHash(qx, qz, 7);
    double X = double(x), Z = double(z);
    int wx = jroundD(bnFbm2(divD(X, 160.0l), divD(Z, 160.0l), 101) * 36.0l
            + bnValue2(divD(X, 40.0l), divD(Z, 40.0l), 111) * 24.0l + (double(h & 31) - 15.5l) * 1.4l);
    int wz = jroundD(bnFbm2(divD(X, 160.0l), divD(Z, 160.0l), 202) * 36.0l
            + bnValue2(divD(X, 40.0l), divD(Z, 40.0l), 222) * 24.0l + (double((h >> 5) & 31) - 15.5l) * 1.4l);
    c.pal = 0u;
    c.cave = 0u;
    regionPalette(x + wx, z + wz, c);
    c.micro = c.s.coastDistance >= 0.0f && !msInWater(c.s) ? pickMicro(c.pal, qx, qz, c.s) : 0u;
    c.noise = bnFbm2(divD(X, 320.0l), divD(Z, 320.0l), 303);
    c.jitter = jroundD(bnValue2(divD(X, 48.0l), divD(Z, 48.0l), 404) * 10.0l + double((h >> 8) & 7) - 3.5l);
    return c;
}

[noinline]
float climateEntry(uint k, int x, int y, int z) {
    return climateQ(vmPointRange(hdr(S_CLIMATE + k * 2u), hdr(S_CLIMATE + k * 2u + 1u), x, y, z));
}

// TamrielBiomeSource.context
uint biomeContext(BCol c, int y, float depth) {
    MapS s = c.s;
    if (depth > 0.2f || (s.coastDistance < 24.0f && y < T_SEA - 4 && depth > 0.14f))
        return y < 0 || depth > 1.0f ? CTX_DEEP_CAVE : CTX_CAVE;
    float h = float(y) + depth * 128.0f;
    bool sea = s.coastDistance < 0.0f || c.cont < -0.19f;
    if (sea && h < float(T_SEA - 1)) return c.cont < -0.455f || h < 36.0f ? CTX_DEEP_OCEAN : CTX_OCEAN;
    if (h < float(T_SEA + 4)) {
        if (s.lake > 0.45f) return CTX_LAKE;
        if (s.river > 0.45f || (absF(c.weird) < 0.05f && c.cont > -0.11f && h < float(T_SEA + 1))) return CTX_RIVER;
        if ((s.coastDistance >= 0.0f && s.coastDistance < float(12 + (c.jitter & 7))) || (c.cont >= -0.19f && c.cont < -0.11f))
            return CTX_BEACH;
        if (h < float(T_SEA + 3) && c.ero > 0.45f) return CTX_MARSH;
    }
    int hj = int(h) + c.jitter;
    uint p = c.pal;
    if (hj >= mi(p + 2u)) return CTX_SUMMIT;
    if (hj >= mi(p + 1u)) return CTX_PEAK;
    if (hj >= mi(p) || (s.ridge > 0.6f && hj >= mi(p) - 25)) return CTX_UPLAND;
    return CTX_LOWLAND;
}
double biomeClimate(BCol c) {
    double t = double(c.temp), hum = double(c.hum);
    return bnCdf(divD(0.55l * hum + 0.3l * t + 0.45l * c.noise, 0.22l));
}
[noinline]
uint surfaceBiome(BCol c) {
    if (c.micro != 0u && mw(c.micro) != BIOME_NONE) return mw(c.micro);
    float e = c.s.elevation;
    uint p = c.pal;
    uint ctx = e >= float(mi(p + 1u)) ? CTX_PEAK : (e >= float(mi(p)) ? CTX_UPLAND : CTX_LOWLAND);
    uint b = choicesPick(palChoices(p, ctx), palBias(p, biomeClimate(c), c.qx << 2, c.qz << 2));
    return b != BIOME_NONE ? b : choicesPick(palChoices(p, CTX_LOWLAND), 0.5l);
}
uint subterraneanOverride(BCol c, uint ctx, int y, double v) {
    MapS s = c.s;
    bool deepOceanCol = s.coastDistance < -36.0f || c.cont < -0.455f || c.surface < 36.0f;
    if (deepOceanCol && y < 20 && subBiome(SB_ABYSSAL) != BIOME_NONE) return subBiome(SB_ABYSSAL);
    if (s.coastDistance < 24.0f && y < T_SEA && (s.coastDistance < 0.0f || v < 0.58l) && subBiome(SB_SUBMERGED) != BIOME_NONE)
        return subBiome(SB_SUBMERGED);
    if (s.heat > 0.35f && v < 0.72l) {
        if (ctx == CTX_DEEP_CAVE && v < 0.32l && subBiome(SB_DWEMER) != BIOME_NONE) return subBiome(SB_DWEMER);
        if (subBiome(SB_LAVA) != BIOME_NONE) return subBiome(SB_LAVA);
    }
    return BIOME_NONE;
}
// pickProvinceCaveBiome by the region's class (AtlasGenMap.caveClass: the same tests on the region id)
uint provinceCave(BCol c, uint ctx, int y, double v) {
    if (ctx == CTX_CAVE && y > 24 && v > 0.65l) return BIOME_NONE;
    bool deep = ctx == CTX_DEEP_CAVE || y < 8;
    uint k = c.cave;
    if (k == 1u || k == 2u) {  // skyrim; 2: winterhold or the pale
        if (k == 2u || c.s.elevation > 220.0f) return deep && v < 0.4l ? subBiome(SB_FALMER) : subBiome(SB_ICE);
        if (deep) return v < 0.5l ? subBiome(SB_FALMER) : subBiome(SB_DWEMER);
        return v < 0.7l ? subBiome(SB_NORDIC) : subBiome(SB_MUSHROOM);
    }
    if (k == 3u) return deep && v < 0.4l ? subBiome(SB_DWEMER) : subBiome(SB_NORDIC);  // high rock
    if (k == 4u) return subBiome(SB_AYLEID);                                        // cyrodiil
    if (k == 5u) return subBiome(SB_MUSHROOM);                                      // morrowind: telvannis, bitter coast
    if (k == 6u) return deep && v < 0.55l ? subBiome(SB_DWEMER) : subBiome(SB_LAVA);  // morrowind
    if (k == 7u) return subBiome(SB_VALENWOOD);
    if (k == 8u) return subBiome(SB_HIST);
    if (k == 9u) return subBiome(SB_ELSWEYR);
    if (k == 10u) return subBiome(SB_SUMMERSET);
    if (k == 11u) return deep && v < 0.5l ? subBiome(SB_DWEMER) : subBiome(SB_YOKUDAN);  // hammerfell
    if (k == 12u) return subBiome(SB_YOKUDAN);                                         // yokuda
    if (k == 13u) return subBiome(SB_ICE);                                             // atmora, kamal
    if (k == 14u) return deep && y < 20 ? subBiome(SB_ABYSSAL) : subBiome(SB_SUBMERGED);  // sea, thras, pyandonea
    return BIOME_NONE;
}
// TamrielBiomeSource.getNoiseBiome after the column's climate (BIOME_NONE: Java would throw; the chunk is flagged)
[noinline]
uint noiseBiome(BCol c, int qy) {
    int y = qy << 2;
    float depth = divF(c.surface - float(y), 128.0f);
    uint ctx = biomeContext(c, y, depth);
    if (ctx == CTX_LOWLAND || ctx == CTX_UPLAND || ctx == CTX_PEAK || ctx == CTX_SUMMIT || ctx == CTX_MARSH)
        if (c.micro != 0u && mw(c.micro) != BIOME_NONE) return mw(c.micro);
    double v;
    bool cave = ctx == CTX_CAVE || ctx == CTX_DEEP_CAVE;
    if (cave) {
        v = bnCdf(divD(bnValue3(divD(double(c.qx), 24.0l), divD(double(qy), 12.0l), divD(double(c.qz), 24.0l), 505),
                       0.33l));
        uint o = subterraneanOverride(c, ctx, y, v);
        if (o != BIOME_NONE) return o;
    } else {
        v = palBias(c.pal, biomeClimate(c), c.qx << 2, c.qz << 2);
    }
    uint b = choicesPick(palChoices(c.pal, ctx), v);
    if (b != BIOME_NONE) return b;
    if (cave) {
        uint r = provinceCave(c, ctx, y, v);
        if (r != BIOME_NONE) return r;
    }
    return surfaceBiome(c);
}

// ---- the page passes (atlas_gen_run / chunks with the map set: the engine's own pages) ------------------------------
// pageJobs: per job cx, cz, slot, full
uint pageBiomeWords() { return 4u * 4u * uint(QY) / 2u; }

[shader("compute")]
[numthreads(64, 1, 1)]
void pageColsMain(uint3 id : SV_DispatchThreadID) {
    uint j = id.y, i = id.x;
    if (i >= 256u || j >= uint(params.chunks)) return;
    int cx = pageJobs[j * 4u], cz = pageJobs[j * 4u + 1u], slot = pageJobs[j * 4u + 2u];
    int x = (cx << 4) + int(i & 15u), z = (cz << 4) + int(i >> 4);
    uint base = uint(slot) * pageWords();
    uint nf = hdr(H_FIELDS);
    g_bad = 0u;
    MapS s = tamrielSample(x, z);
    for (uint f = 0u; f < nf; f++) {
        uint kind = prog[hdr(S_FKINDS) + f];
        double v;
        if (kind == FK_HEIGHT) v = tamrielHeight(x, z, s);
        else if (kind - 1u <= CHN_OVERHANG) v = channelValue(kind - 1u, x, z, s);
        else {
            g_bad = g_bad | 32u;
            v = 0.0l;
        }
        pages[base + f * 256u + i] = asuint(float(v));
    }
    uint word = W_NONE;
    if (!msInWater(s)) {
        if (s.wetland >= 0.5f) word = W_MARSH;
    } else {
        int top = -floorD(-double(s.waterLevel)) - 1;  // (int) Math.ceil(waterLevel) - 1
        if (top >= T_SEA) word = W_MAP | (uint(top) << 16);
    }
    pages[base + nf * 256u + i] = word;
    for (uint k = i; k < pageBiomeWords(); k += 256u) pages[base + nf * 256u + 256u + k] = 0u;
    if (g_bad != 0u) pageBad[uint(slot)] = 1u;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void pageBiomesMain(uint3 id : SV_DispatchThreadID) {
    uint t = id.x;
    uint j = t >> 4;
    if (j >= uint(params.chunks)) return;
    if (pageJobs[j * 4u + 3u] == 0) return;
    int cx = pageJobs[j * 4u], cz = pageJobs[j * 4u + 1u], slot = pageJobs[j * 4u + 2u];
    int lqx = int((t & 15u) >> 2), lqz = int(t & 3u);
    g_bad = 0u;
    BCol c = biomeColumn((cx << 2) + lqx, (cz << 2) + lqz);
    // the climate at the column's first quart (createBiomes asks bottom up: qy -16, block y -64)
    int bx = c.qx << 2, bz = c.qz << 2, by = MIN_Y;
    c.temp = climateEntry(0u, bx, by, bz);
    c.hum = climateEntry(1u, bx, by, bz);
    c.cont = climateEntry(2u, bx, by, bz);
    c.ero = climateEntry(3u, bx, by, bz);
    float depth0 = climateEntry(4u, bx, by, bz);
    c.weird = climateEntry(5u, bx, by, bz);
    c.surface = float(by) + depth0 * 128.0f;
    uint base = uint(slot) * pageWords() + hdr(H_FIELDS) * 256u + 256u;
    uint i0 = uint(lqx * 4 + lqz) * uint(QY);
    for (int qy = 0; qy < QY; qy += 2) {
        uint b0 = noiseBiome(c, qy - 16), b1 = noiseBiome(c, qy - 15);
        if (b0 == BIOME_NONE || b1 == BIOME_NONE) g_bad = g_bad | 64u;
        pages[base + ((i0 + uint(qy)) >> 1)] = (b0 & 0xFFFFu) | (b1 << 16);
    }
    if (g_bad != 0u) pageBad[uint(slot)] = 1u;
}

// ---- far tiles (atlas_gen_tiles): AtlasExactSource.fill's columns on the device ------------------------------------
// pageJobs: per tile lod, tx, tz, 0; outIds: per tile (4 planes of TILE_COLS words) TILE_COLS surface floats ((float) TamrielHeight.height at the
// column's centre block, row-major), then TILE_COLS water floats (AtlasExactSource.waterAt); pageBad[tile] flags
static const uint TILE_SIDE = 256, TILE_COLS = 65536;
// a tile's planes: surface, water, top | under (the biome at the top first), the biome at the top + 1 / water, the
// biome under the top (tileBiomeMain's, for tileSurfMain)
static const uint TILE_PLANES = 5;
[shader("compute")]
[numthreads(64, 1, 1)]
void tileMain(uint3 id : SV_DispatchThreadID) {
    uint t = id.y + uint(params.pad1), c = id.x;  // (pad1: the first tile of this submission)
    if (c >= TILE_COLS || t >= uint(params.chunks)) return;
    int lod = pageJobs[t * 4u], tx = pageJobs[t * 4u + 1u], tz = pageJobs[t * 4u + 2u];
    int s = 1 << uint(lod);
    int bx = tx * (int(TILE_SIDE) << uint(lod)) + (s >> 1), bz = tz * (int(TILE_SIDE) << uint(lod)) + (s >> 1);
    int x = bx + int(c & 255u) * s, z = bz + int(c >> 8) * s;
    g_bad = 0u;
    MapS m = tamrielSample(x, z);
    double h = tamrielHeight(x, z, m);
    // waterTop: rivers and lakes at or above sea level, filled to ceil(level)
    float top = F_NAN;
    if (msInWater(m) && m.waterLevel >= float(T_SEA)) top = float(-floorD(-double(m.waterLevel)));
    float w = h < double(T_SEA) ? float(T_SEA) : F_NAN;
    if (!isNaNF(top)) w = isNaNF(w) ? top : jmaxF(w, top);
    float water = double(w) > h ? w : F_NAN;
    outIds[t * TILE_COLS * TILE_PLANES + c] = asuint(float(h));
    outIds[t * TILE_COLS * TILE_PLANES + TILE_COLS + c] = asuint(water);
    if (g_bad != 0u) pageBad[t] = 1u;
}

// ---- the far columns' surface (atlas_gen_tiles with blocks): AtlasSurface.column on the device --------------------
// the quart biome as chunks store it (createBiomes: the column's climate from y -64), at any quart
[noinline]
void biomeClimate0(inout BCol c) {
    int bx = c.qx << 2, bz = c.qz << 2, by = MIN_Y;
    c.temp = climateEntry(0u, bx, by, bz);
    c.hum = climateEntry(1u, bx, by, bz);
    c.cont = climateEntry(2u, bx, by, bz);
    c.ero = climateEntry(3u, bx, by, bz);
    float depth0 = climateEntry(4u, bx, by, bz);
    c.weird = climateEntry(5u, bx, by, bz);
    c.surface = float(by) + depth0 * 128.0f;
}
// the column fields at a quart column's block (what the climate entries read there), for column(): no page out here
[noinline]
void ownColumnFields(BCol c) {
    int x = c.qx << 2, z = c.qz << 2;
    uint nf = hdr(H_FIELDS);
    g_colX = x;
    g_colZ = z;
    uint want = hdr(S_CLIMFIELDS);
    for (uint f = 0u; f < nf && f < 16u; f++) {
        g_colVals[f] = 0.0f;
        if (((want >> f) & 1u) == 0u) continue;  // (not read by the climate entries)
        uint kind = prog[hdr(S_FKINDS) + f];
        double v = kind == FK_HEIGHT ? tamrielHeight(x, z, c.s) : channelValue(kind - 1u, x, z, c.s);
        g_colVals[f] = float(v);
    }
    if (nf > 16u) g_bad = g_bad | 32u;
    g_colOwn = true;
}
[noinline]
uint quartBiomeFar(int qx, int qy, int qz) {
    BCol c = biomeColumn(qx, qz);
    ownColumnFields(c);
    biomeClimate0(c);
    g_colOwn = false;
    int y = qy < -16 ? -16 : (qy > 159 ? 159 : qy);  // ChunkAccess.getNoiseBiome clamps to the chunk
    return noiseBiome(c, y);
}
// BiomeManager.getBiome over those
uint farBiomeAt(int x, int y, int z) {
    int3 q = zoomQuart(x, y, z);
    return quartBiomeFar(q.x, q.y, q.z);
}
// AtlasSurface.surfaceH: the WORLD_SURFACE_WG height the surface pass sees (the ground, or the sea over it)
int farSurfaceH(float surface) {
    int top = -floorD(-double(surface)) - 1;
    int sea = int(hdr(S_SEA));
    int w = top < sea - 1 ? sea : int(0x80000000u);
    return top + 1 > w ? top + 1 : w;
}
float tileSurface(uint t, int i, int j) { return asfloat(outIds[t * TILE_COLS * TILE_PLANES + uint(j) * TILE_SIDE + uint(i)]); }

// the biomes a far column's surface reads (BiomeManager.getBiome at its top block, the one under it, and its top + 1 or
// the water's top: AtlasSurface's snow and ice tests): plane 2 = top | under << 16, plane 3 = the last (tileSurfMain
// then puts the blocks into plane 2)
[shader("compute")]
[numthreads(64, 1, 1)]
void tileBiomeMain(uint3 id : SV_DispatchThreadID) {
    // a thread per (column, lookup): 0 the top block's biome (plane 2), 1 the one under it (plane 4), 2 the top + 1 /
    // the water's top (plane 3); one lookup a thread (several in one thread faulted the device: see the commit)
    uint t = id.y + uint(params.pad1), k = id.x / TILE_COLS, c = id.x % TILE_COLS;
    if (k >= 3u || t >= uint(params.chunks)) return;
    if (params.pad0 > 0 && c >= uint(params.pad0)) return;  // (dev: ATLAS_GEN_DEBUG_COLS)
    int lod = pageJobs[t * 4u], tx = pageJobs[t * 4u + 1u], tz = pageJobs[t * 4u + 2u];
    int s = 1 << uint(lod), n = int(TILE_SIDE);
    int i = int(c & 255u), j = int(c >> 8);
    int x = tx * (n << uint(lod)) + i * s + (s >> 1), z = tz * (n << uint(lod)) + j * s + (s >> 1);
    g_bad = 0u;
    float surface = tileSurface(t, i, j);
    float water = asfloat(outIds[t * TILE_COLS * TILE_PLANES + TILE_COLS + c]);
    int top = -floorD(-double(surface)) - 1;
    bool dry = isNaNF(water) || water <= surface;
    int y = k == 0u ? top : (k == 1u ? top - 1 : (dry ? top + 1 : (-floorD(-double(water)) - 1)));
    int3 q = zoomQuart(x, y, z);
    uint b = quartBiomeFar(q.x, q.y, q.z);
    uint plane = k == 0u ? 2u : (k == 1u ? 4u : 3u);
    outIds[t * TILE_COLS * TILE_PLANES + TILE_COLS * plane + c] = b;
    if (g_bad != 0u) pageBad[t] = 1u;
}

// outIds per tile: surface, water (tileMain), then top | under << 16 (generator ids) and the biome at the column's top
// (tileBiomeMain's)
[shader("compute")]
[numthreads(64, 1, 1)]
void tileSurfMain(uint3 id : SV_DispatchThreadID) {
    uint t = id.y + uint(params.pad1), c = id.x;  // (pad1: the first tile of this submission)
    if (c >= TILE_COLS || t >= uint(params.chunks)) return;
    int lod = pageJobs[t * 4u], tx = pageJobs[t * 4u + 1u], tz = pageJobs[t * 4u + 2u];
    int s = 1 << uint(lod), n = int(TILE_SIDE);
    int i = int(c & 255u), j = int(c >> 8);
    int x = tx * (n << uint(lod)) + i * s + (s >> 1), z = tz * (n << uint(lod)) + j * s + (s >> 1);
    g_bad = 0u;
    float surface = tileSurface(t, i, j);
    float water = asfloat(outIds[t * TILE_COLS * TILE_PLANES + TILE_COLS + c]);
    int gx, gz;
    if (lod == 0) {
        int lx = x & 15, lz = z & 15;
        int ia = i + 1 < i + 15 - lx ? i + 1 : i + 15 - lx, ib = i - 1 > i - lx ? i - 1 : i - lx;
        int ja = j + 1 < j + 15 - lz ? j + 1 : j + 15 - lz, jb = j - 1 > j - lz ? j - 1 : j - lz;
        gx = farSurfaceH(tileSurface(t, ia, j)) - farSurfaceH(tileSurface(t, ib, j));
        gz = farSurfaceH(tileSurface(t, i, ja)) - farSurfaceH(tileSurface(t, i, jb));
    } else {
        int i0 = i - 1 > 0 ? i - 1 : 0, i1 = i + 1 < n - 1 ? i + 1 : n - 1, k0 = j - 1 > 0 ? j - 1 : 0, k1 = j + 1 < n - 1 ? j + 1 : n - 1;
        gx = jroundF(divF(float(farSurfaceH(tileSurface(t, i1, j)) - farSurfaceH(tileSurface(t, i0, j))) * 2.0f,
                          float((i1 - i0) * s)));
        gz = jroundF(divF(float(farSurfaceH(tileSurface(t, i, k1)) - farSurfaceH(tileSurface(t, i, k0))) * 2.0f,
                          float((k1 - k0) * s)));
    }
    // AtlasSurface.column: the rule at the top block (stone depth 1 above, 64 below) and the one under it (2, 63)
    int top = -floorD(-double(surface)) - 1;
    int sea = int(hdr(S_SEA));
    bool isSea = top < sea - 1;
    SurfaceCtx sc;
    sc.ci = 0u; sc.lx = 0; sc.lz = 0;
    sc.far = true;
    sc.x = x;
    sc.z = z;
    sc.gradX = gx;
    sc.gradZ = gz;
    sc.surfaceDepth = surfaceDepthAt(x, z);
    sc.haveSecondary = false;
    sc.secondary = 0.0l;
    // the preliminary surface (chunk_surface_level: the height field, the column's own surface here)
    sc.haveMinSurface = true;
    sc.minSurface = floorF(surface) + sc.surfaceDepth - 8;
    sc.waterHeight = isSea ? sea : int(0x80000000u);
    uint biomes = (outIds[t * TILE_COLS * TILE_PLANES + TILE_COLS * 2u + c] & 0xFFFFu)
                | (outIds[t * TILE_COLS * TILE_PLANES + TILE_COLS * 4u + c] << 16);
    sc.y = top;
    sc.stoneAbove = 1;
    sc.stoneBelow = 64;
    sc.haveBiome = true;
    sc.biome = biomes & 0xFFFFu;
    int topB = runRules(sc);
    sc.y = top - 1;
    sc.stoneAbove = 2;
    sc.stoneBelow = 63;
    sc.haveBiome = true;
    sc.biome = biomes >> 16;
    int under = runRules(sc);
    uint stone = hdr(S_STONE);
    uint tb = topB < 0 ? stone : uint(topB), ub = under < 0 ? stone : uint(under);
    outIds[t * TILE_COLS * TILE_PLANES + TILE_COLS * 2u + c] = (tb & 0xFFFFu) | (ub << 16);
    if (g_bad != 0u) pageBad[t] = 1u;
}

// ---- far chunks (atlas_gen_far): a lod-k tile's 16 x 16 level-k chunk columns, from its planes ----------------------
// (tileMain, tileBiomeMain, tileSurfMain first). A level-k chunk is 16^3 voxels of 2^k blocks, rows of them from y -64
// (the grid's coarse level k: VoxGrid::ingestCoarseGpu). A voxel is the far World build's terrain voxel
// (TileVoxels::columnVoxel without the overlays' canopy and structures) of its column: the rule's top block within a
// voxel of the surface, the block under it FAR_FILL_DEPTH below that, stone deeper, water up to the water's surface.
// farOut: [0] mixed chunks so far, [1] capacity, [2..3] 0; then per chunk (tile column major, rows inner) its kind
// (0 mixed, 1 air, 2 | material << 8 uniform) and its slot (mixed); then the slots' 2048 words each
static const float FAR_FILL_DEPTH = 3.0f;  // world.hpp FILL_DEPTH
uint farRows(int lod) { return (44u + (1u << uint(lod)) - 1u) >> uint(lod); }
uint farMat(uint biome, uint id) { return params.outMode == 1u ? igMat(biome, id) & 0xFFFFu : id; }
uint farVoxel(uint t, uint c, float yc, float s) {
    uint P = t * TILE_COLS * TILE_PLANES;
    float surf = asfloat(outIds[P + c]), w = asfloat(outIds[P + TILE_COLS + c]);
    uint blocks = outIds[P + TILE_COLS * 2u + c], biome = outIds[P + TILE_COLS * 3u + c];
    if (yc < surf) {
        float depth = surf - yc;
        if (depth <= s) return farMat(biome, blocks & 0xFFFFu);
        if (depth <= s + FAR_FILL_DEPTH) return farMat(biome, blocks >> 16);
        return farMat(biome, hdr(S_STONE));
    }
    if (!isNaNF(w) && yc < w) return farMat(biome, hdr(S_WATER));
    return 0u;
}
// one thread per level-k chunk: its kind, and its voxels when mixed (a slot taken from the counter)
[shader("compute")]
[numthreads(64, 1, 1)]
void farChunkMain(uint3 id : SV_DispatchThreadID) {
    uint t = id.y + uint(params.pad1), k = id.x;
    if (t >= uint(params.chunks)) return;
    int lod = pageJobs[t * 4u];
    uint rows = farRows(lod);
    if (k >= 256u * rows) return;
    uint cc = k / rows, cy = k % rows;
    uint ccx = cc & 15u, ccz = cc >> 4;
    float s = float(1 << uint(lod));
    uint chunkWord = 4u + (t * 256u * rows + k) * 2u;
    uint total = uint(params.chunks) * 256u * farRows(lod);  // (every job of a call has the same lod)
    uint slotBase = 4u + total * 2u;
    // classify: the first voxel's material, and whether every other one equals it
    uint first = 0u;
    bool uniform = true;
    for (uint v = 0u; v < 4096u && uniform; v++) {
        uint vx = v & 15u, vz = (v >> 4) & 15u, vy = v >> 8;
        uint c = (ccz * 16u + vz) * TILE_SIDE + ccx * 16u + vx;
        float yc = float(MIN_Y) + (float(cy * 16u + vy) + 0.5f) * s;
        uint m = farVoxel(t, c, yc, s);
        if (v == 0u) first = m;
        else if (m != first) uniform = false;
    }
    if (uniform) {
        farOut[chunkWord] = first == 0u ? 1u : (2u | (first << 8));
        farOut[chunkWord + 1u] = 0xFFFFFFFFu;
        return;
    }
    uint slot;
    InterlockedAdd(farOut[0], 1u, slot);
    if (slot >= farOut[1]) {  // no room: the host asks again with more
        farOut[chunkWord] = 0xFFFFFFFEu;
        farOut[chunkWord + 1u] = 0xFFFFFFFFu;
        return;
    }
    farOut[chunkWord] = 0u;
    farOut[chunkWord + 1u] = slot;
    uint base = slotBase + slot * 2048u;
    for (uint v = 0u; v < 4096u; v += 2u) {
        uint m2[2];
        for (uint q = 0u; q < 2u; q++) {
            uint vv = v + q;
            uint vx = vv & 15u, vz = (vv >> 4) & 15u, vy = vv >> 8;
            uint c = (ccz * 16u + vz) * TILE_SIDE + ccx * 16u + vx;
            float yc = float(MIN_Y) + (float(cy * 16u + vy) + 0.5f) * s;
            m2[q] = farVoxel(t, c, yc, s);
        }
        farOut[base + (v >> 1)] = (m2[0] & 0xFFFFu) | (m2[1] << 16);
    }
}
