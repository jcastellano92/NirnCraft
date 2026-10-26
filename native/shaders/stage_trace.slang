// Infinite ATLAS: the lean trace stage (docs/ATLAS-RENDER-BLUEPRINT.md, step 6), written from scratch, not derived from
// farMain. One thread a pixel: the view ray (curved with the planet, in chords), each chord walked through one level of
// the GPU grid (level 0 blocks, coarse levels 1..6 voxels of 2^k blocks) and the hit stored in the hit buffer (farHit,
// the record atlas_far.slang farHitStore writes: the shade side, farMain's ATLAS_FAR_PASS 2, reads it).
// Step 6.1-6.5: every level's voxels plain cubes, material and entry axis only, except a coarse level's cube wider than the
// pixel: its level's smooth surface, found after the walk; a grazing hit's second look at a coarser level (no bricks,
// shapes or water medium: farMain's ATLAS_ABLATE 805306368 is the same picture; with 32, the cubes; with 2147483648, no
// grazing look). farMain's choices otherwise:
// the level a chord takes (the footprint's and the residency's at the pixel's threshold, never finer along the ray, the
// chord ending where the pick changes), a coarser level where one does not hold the point, a finer one where no
// coarser does, past a hole where none does; the walk's exits by index (a nudge past a face is below float precision at
// a grazing angle: it re-entered the cell it left and skipped the face crossed).
// Rules from line one: no [noinline], no struct passed to a call, no array indexed at run time, one walk loop; its
// budget row in kernel_budget.json (stage_trace).
// ATLAS_LEAN_CLOCK=1 (its own build): the pixel's counter holds the walk's cycles / 16, not its steps.
#include "atlas_shared.slang"

#ifndef ATLAS_LEAN_CLOCK
#define ATLAS_LEAN_CLOCK 0
#endif

[[vk::binding(0, 0)]] ConstantBuffer<FrameGpu> frame;
[[vk::binding(7, 0)]] StructuredBuffer<MaterialGpu> palette;
[[vk::binding(12, 0)]] RWStructuredBuffer<uint> counters;
[[vk::binding(29, 0)]] StructuredBuffer<uint> vgChunks;
[[vk::binding(30, 0)]] StructuredBuffer<uint> vgBlocks;
[[vk::binding(31, 0)]] StructuredBuffer<uint64_t> vgBits;
[[vk::binding(32, 0)]] StructuredBuffer<uint> vgPool;
[[vk::binding(41, 0)]] RWStructuredBuffer<uint4> farHit;

static const uint ST_EMPTY = 0u, ST_UNLOADED = 2u, ST_UNIFORM = 3u;  // (voxgrid.hpp VG_*; 1 mixed)
static const uint RAY_STEPS = 24576u;  // a ray's steps at most (farMain's)
static const uint LOOP_CAP = 32768u;   // the loop's passes at most (steps, chord and level changes)

float lodR(int k) { return frame.lodR[k >> 2][k & 3]; }

// the continuous level the residency asks for at camera-relative point p (atlas_far.slang lodResident): k + 1 at level
// k's window radius, log-linear between the radii
float lodRes(float3 p, int lodCount) {
    float dc = max(max(abs(p.x), abs(p.z)), 1.0);
    if (dc <= lodR(0)) return 1.0 + log2(dc / lodR(0));
    [loop]
    for (int j = 0; j + 1 < lodCount && j < 7; j++)
        if (dc <= lodR(j + 1)) return float(j + 1) + log2(dc / lodR(j)) / log2(lodR(j + 1) / lodR(j));
    return float(lodCount);
}

// ---- the smooth surface of a coarse level (atlas_far.slang coarseSmoothHit, ATLAS-PLAN 0.2) ----
static const float SMOOTH_COARSE_PX = 1.5;  // a voxel this many footprints wide, or more, is drawn smooth
static const uint HIT_SMOOTH_N = 2u;        // (the record's albedo flags: bit 0 its own albedo, bit 1 a smooth normal)

// the material of grid-local voxel v of a level's grid (its window and first chunk word): 0 air, 0xFFFFFFFF no data
uint matAt(int3 v, int gx, int gz, int X, int Y, int Z, uint base) {
    // (no branches: the chunk word and the pool word read unconditionally, at a clamped chunk and slot 0's word where
    // the chunk is not mixed; the answer selected after. A branch per state serialised the eight corners' reads. Slot 0
    // exists whenever a level can be hit: the pool is sized by its slots, at least one with any data)
    int3 hiV = int3(X, Y, Z) * 16 - 1;
    bool inside = all(v >= int3(0, 0, 0)) && all(v <= hiV);
    int3 c = clamp(v, int3(0, 0, 0), hiV) >> 4;
    int px = (c.x + gx) % X, pz = (c.z + gz) % Z;
    px += px < 0 ? X : 0;
    pz += pz < 0 ? Z : 0;
    uint cw = vgChunks[base + uint((c.y * Z + pz) * X + px)];
    uint st = cw & 3u;
    int3 l = v & 15;
    uint i = uint(l.x + 16 * (l.z + 16 * l.y));
    uint pw = vgPool[(st == 1u ? (cw >> 2) * 2048u : 0u) + (i >> 1)];
    uint m = st == ST_EMPTY ? 0u : (st == ST_UNIFORM ? (cw >> 2) & 0xFFFFu : (st == ST_UNLOADED ? 0xFFFFFFFFu : (pw >> (16u * (i & 1u))) & 0xFFFFu));
    return inside ? m : 0xFFFFFFFFu;
}

// a voxel's share of the density: 1 solid (and no data: the hit voxel's own, no lip where the level's data ends), 0 air
// and water a ray passes (skipWater)
float occOf(uint m, bool skipWater) {
    if (m == 0u) return 0.0;
    if (m == 0xFFFFFFFFu) return 1.0;
    return skipWater && uint(palette[m].emisClass.y) == 2u ? 0.0 : 1.0;
}

// whether level k's grid holds data and is on (farMain's hasData / vgLevelOn / the measurement bits)
bool levelOn(int k) {
    uint ablate = asuint(frame.jitter.w);
    bool vgOn = frame.vgOrigin.w != 0 && (ablate & 134217728u) == 0u;
    if (k == 0) return vgOn && ((frame.vgOrigin.w & 2) != 0 || (frame.vgDims.w & 5u) == 5u);
    int4 L = frame.vgLevel[clamp(k, 1, 6) - 1];
    return vgOn && k <= 6 && ((L.w >> 16) & 1) != 0 && ((L.w >> 17) & 1) != 0 && (ablate & 1073741824u) == 0u;
}

[shader("compute")]
[numthreads(8, 8, 1)]
void traceMain(uint3 id : SV_DispatchThreadID) {
    uint w = frame.dims.x, h = frame.dims.y;
    if (id.x >= w || id.y >= h) return;
    uint pix = id.y * w + id.x;
#if ATLAS_LEAN_CLOCK
    uint64_t clk0 = clockARB();
#endif
    int k = 0;
    float tHit = -1.0;
    uint mat = 0u, steps = 0u;
    int axis = 1;
    bool noSmooth = (asuint(frame.jitter.w) & 32u) != 0u;
    // (farMain's search passes water where its walk does: FRAME_SEE_THROUGH_WATER or under water. This walk stops at water
    // still, so the search does too until the water medium lands)
    bool skipW = false;
    float tStart = 0.0, hitFrom = 0.0;  // (the walk's start; the start of the chord that hit)
    uint hasAlbedo = 0u;
    float3 alb = float3(0, 0, 0);
    // (6.5, the grazing look: a hit whose face's footprint wants a coarser level than its chord's walks that level
    // once around it (atlas_far.slang traceViewBody's second look; ATLAS_ABLATE 2147483648: not). Its window [tStart,
    // gEnd) at level gK; the finer hit kept for when that level has no surface there)
    bool noGraze = (asuint(frame.jitter.w) & 2147483648u) != 0u;
    bool grazing = false;
    int gK = 0, fineAxis = 1, fineK = 0;
    float gEnd = 0.0, fineT = -1.0, fineFrom = 0.0;
    uint fineMat = 0u;
    int pass = 0;
    // (two passes at most: a smooth search that finds no crossing walks on once from a voxel and a half past the cube, its
    // second miss keeps the cube: farMain's traceView; each pass a walk and its grazing look at most. One walk loop: the
    // passes and the looks run it again, never a second copy. The
    // ray's set-up and the walk's state live inside a pass: set up outside, they were live across the smooth search, and
    // the driver's 72 registers and 4352 bytes of shared memory a group were 64 and 768)
    [loop]
    for (int wk = 0; wk < 4; wk++) {
        // the ray: the host's jitter as farMain's, the per-pixel threshold of the continuous level as farMain's gLodXi
        float2 ndc = float2((float(id.x) + 0.5) / float(w) * 2.0 - 1.0, 1.0 - (float(id.y) + 0.5) / float(h) * 2.0) + frame.jitter.xy;
        float3 dir = normalize(unproject(frame, ndc));
        float3 o = frame.camFrac.xyz;
        int mode = int(frame.lodParams.x);
        uint2 cell = mode == 2 ? id.xy / 2u : (mode == 3 ? uint2(id.x / 8u, id.y / 4u) : id.xy);
        float xi = mode == 1 ? 0.5 : frac(pixelDither(cell, 11u) + (frame.jitter.z > 0.5 ? 0.618034 * float(frame.temporal.x & 255u) : 0.0));
        float R = frame.camFrac.w;
        float kc = R > 0 ? dot(dir.xz, dir.xz) / (2.0 * R) : 0.0;  // (the parabola: y + kc t^2)
        float pa = frame.params.w * frame.params.z;                // (blocks a pixel covers per block of distance)
        float worldTop = 640.0 - float(frame.camInt.y);
        float tMax = frame.params.y;
        int lodCount = int(frame.dims.w);
        float hz = max(max(abs(dir.x), abs(dir.z)), 1e-4);  // (the Chebyshev distance's growth a block along the ray)

        // the walk's state: the chord [t0, t0 + len) of the view ray (origin a, direction d) and its level kc0; the level k
        // walked (kc0, or coarser where kc0 does not hold the point, or finer where nothing coarser does), its grid; the
        // voxel walk's parameter u along the chord (blocks); the face the walk left by, as a voxel index (exitAxis, exitTo)
        float t0 = tStart, len = 0.0, u = 0.0;
        float3 a = o, d = dir, ro = float3(0, 0, 0), dv = float3(0, 0, 1), inv = float3(1e30, 1e30, 1e30);
        int lodC = 0, gx = 0, gz = 0, X = 1, Y = 1, Z = 1, exitAxis = -1, exitTo = 0;
        uint base = 0u;
        int3 gOrigin = int3(0, 0, 0);
        bool chord = true, level = false, finer = false;
        tHit = -1.0;
        [loop]
        for (uint it = 0u; it < LOOP_CAP; it++) {
            if (chord) {
                // the next chord: from the end of this one, its level the footprint's and the residency's (never finer
                // than the last chord's), to where that pick would change
                if (grazing && len > 0.0) break;  // (a grazing look: its one chord)
                t0 += len;
                if (t0 >= tMax) break;
                a = o + dir * t0 + float3(0, kc * t0 * t0, 0);
                if (!grazing && a.y > worldTop && dir.y + 2.0 * kc * t0 >= 0.0) break;  // (above the world, climbing)
                hitFrom = t0;
                float lf = log2(max(t0 * pa, 1e-6)) + frame.lodParams.z;
                int want = grazing ? gK : clamp(int(floor(max(lf, lodRes(a, lodCount)) + xi)), 0, lodCount - 1);
                lodC = grazing ? gK : max(lodC, want);
                float s = float(1 << lodC);
                float tEnd = grazing ? gEnd : 1e30;
                if (!grazing && lodC == want && lodC + 1 < lodCount) {
                    // (farMain's tEnd: the footprint, or the place, reaching lod + 1 at the threshold)
                    float target = float(lodC + 1) - xi;
                    float tF = exp2(target) / pa;
                    float rd;
                    if (target <= 1.0) rd = lodR(0) * exp2(target - 1.0);
                    else {
                        int kk = min(int(floor(target)) - 1, 6);
                        rd = kk + 1 >= lodCount ? 1e30 : lodR(kk) * pow(lodR(kk + 1) / lodR(kk), target - float(kk + 1));
                    }
                    float tR = (rd - max(abs(o.x), abs(o.z))) / hz;
                    tEnd = max(min(tF, tR), t0 + 0.5);
                }
                float tn = min(min(t0 + 192.0 * s, tEnd), tMax);
                len = tn - t0;
                float3 b = o + dir * tn + float3(0, kc * tn * tn, 0);
                d = (b - a) / len;  // (|d| ~ 1: u ~ the view ray's distance from t0)
                u = 0.0;
                k = min(lodC, 6);
                finer = false;
                chord = false;
                level = true;
            }
            if (level) {
                // level k's grid (atlas_far.slang vgGrid): its window and first chunk word; the chord in its voxels
                if (!levelOn(k)) {
                    if (grazing) break;  // (a grazing look is its level's alone: without data, the finer hit stands)
                    // (no data at this level: coarser, else finer, else past it)
                    if (!finer && k < 6) {
                        k++;
                        lodC = max(lodC, k);  // (as farMain's lod: the coarser level stays for the chords after)
                    }
                    else if (!finer && lodC > 0) {
                        finer = true;
                        k = min(lodC, 6) - 1;
                    } else if (finer && k > 0) {
                        k--;
                    } else {
                        chord = true;
                    }
                    continue;
                }
                if (k == 0) {
                    gOrigin = frame.vgOrigin.xyz * 16;
                    gx = frame.vgOrigin.x;
                    gz = frame.vgOrigin.z;
                    X = int(frame.vgDims.x);
                    Y = int(frame.vgDims.y);
                    Z = int(frame.vgDims.z);
                    base = 0u;
                } else {
                    int4 L = frame.vgLevel[k - 1];
                    int cs = 16 << k;
                    gOrigin = int3(L.x * cs, -64, L.y * cs);
                    gx = L.x;
                    gz = L.y;
                    X = L.w & 255;
                    Y = (L.w >> 8) & 255;
                    Z = X;
                    base = uint(L.z);
                }
                float vs = float(1 << k);
                ro = (a - float3(gOrigin - frame.camInt.xyz)) / vs;
                dv = d / vs;
                inv = float3(abs(dv.x) > 1e-12 ? 1.0 / dv.x : 1e30, abs(dv.y) > 1e-12 ? 1.0 / dv.y : 1e30, abs(dv.z) > 1e-12 ? 1.0 / dv.z : 1e30);
                exitAxis = -1;
                level = false;
            }
            if (u >= len) {
                chord = true;
                continue;
            }
            if (steps >= RAY_STEPS) break;  // (capped: the counter shows RAY_STEPS)
            steps++;
            // the voxel the walk stands in (grid-local): a point exactly on a face going down that axis lies in the cell
            // below; the cell entered across the face left by, by index (farMain's walk)
            float3 p = ro + dv * u;
            float3 pf = floor(p);
            int3 v = int3(pf) - int3(dv.x < 0 && p.x == pf.x ? 1 : 0, dv.y < 0 && p.y == pf.y ? 1 : 0, dv.z < 0 && p.z == pf.z ? 1 : 0);
            if (exitAxis >= 0) v[exitAxis] = exitTo;
            bool held = !(any(v < int3(0, 0, 0)) || v.x >= X * 16 || v.y >= Y * 16 || v.z >= Z * 16);
            uint cw = 0u, st = ST_UNLOADED;
            int3 c = v >> 4;
            if (held) {
                int px = (c.x + gx) % X, pz = (c.z + gz) % Z;
                px += px < 0 ? X : 0;
                pz += pz < 0 ? Z : 0;
                cw = vgChunks[base + uint((c.y * Z + pz) * X + px)];
                st = cw & 3u;
            }
            if (st == ST_UNLOADED) {
                if (grazing) break;  // (as farMain's look: its level's walk uncovered, the finer hit stands)
                // (outside level k's window: a finer level's window lies inside it, only a coarser one can hold the point;
                // level 6's is the widest, and past it the grid holds nothing along this ray: the far range's misses)
                if (!held && k >= 6) break;
                // where this level's chunk ends along the chord (the hole's skip, when no level holds the point)
                float3 lc = float3(c * 16), hc = lc + 16.0;
                float3 tc = (float3(dv.x > 0.0 ? hc.x : lc.x, dv.y > 0.0 ? hc.y : lc.y, dv.z > 0.0 ? hc.z : lc.z) - ro) * inv;
                tc = float3(tc.x > u ? tc.x : 1e30, tc.y > u ? tc.y : 1e30, tc.z > u ? tc.z : 1e30);
                float skip = min(tc.x, min(tc.y, tc.z)) + 1e-3 - u;
                // the chord from here: coarser (the chord's rest; the level stays for the chords after, as farMain's lod),
                // else finer (inside this level's window only), else past the hole
                a = a + d * u;
                len -= u;
                t0 += u;
                u = 0.0;
                if (!finer && k < 6) {
                    k++;
                    lodC = max(lodC, k);
                } else if (held && !finer && lodC > 0) {
                    finer = true;
                    k = min(lodC, 6) - 1;
                } else if (finer && k > 0) {
                    k--;
                } else {
                    float sk = min(max(skip, 1e-3), len);
                    a = a + d * sk;
                    t0 += sk;
                    hitFrom = t0;
                    len -= sk;
                    k = min(lodC, 6);
                    finer = false;
                }
                level = true;
                continue;
            }
            int3 lo, hi;  // the empty box the walk leaves next, grid-local voxels [lo, hi)
            if (st == ST_EMPTY) {
                int3 en = int3(int((cw >> 2) & 31u), int((cw >> 12) & 31u), int((cw >> 22) & 31u));
                int3 ep = int3(int((cw >> 7) & 31u), int((cw >> 17) & 31u), int((cw >> 27) & 31u));
                lo = (c - en) * 16;
                hi = (c + ep + 1) * 16;
            } else {
                bool uni = st == ST_UNIFORM;
                uint slot = cw >> 2;
                int3 l = v & 15;
                int3 bb = l >> 2;
                uint bi = uint(bb.x + 4 * (bb.z + 4 * bb.y));
                uint bw = uni ? 1u : vgBlocks[slot * 64u + bi];
                if ((bw & 1u) == 0u) {
                    int3 en = int3(int((bw >> 2) & 3u), int((bw >> 6) & 3u), int((bw >> 10) & 3u));
                    int3 ep = int3(int((bw >> 4) & 3u), int((bw >> 8) & 3u), int((bw >> 12) & 3u));
                    lo = c * 16 + (bb - en) * 4;
                    hi = c * 16 + (bb + ep + 1) * 4;
                } else {
                    uint64_t bits = uni ? ~uint64_t(0) : vgBits[slot * 64u + bi];
                    int3 q = v & 3;
                    if (((bits >> uint(q.x + 4 * q.z + 16 * q.y)) & 1) != 0) {
                        uint i = uint(l.x + 16 * (l.z + 16 * l.y));
                        mat = uni ? (cw >> 2) & 0xFFFFu : (vgPool[slot * 2048u + (i >> 1)] >> (16u * (i & 1u))) & 0xFFFFu;
                        tHit = t0 + u;
                        // the face entered: the axis whose slab the ray entered last (farMain's entryAxis)
                        float3 sp = float3(dv.x > 0.0 ? 1.0 : 0.0, dv.y > 0.0 ? 1.0 : 0.0, dv.z > 0.0 ? 1.0 : 0.0);
                        float3 tNear = (float3(v) + (1.0 - sp) - ro) * inv;
                        if (abs(dv.x) <= 1e-12) tNear.x = -1e30;
                        if (abs(dv.y) <= 1e-12) tNear.y = -1e30;
                        if (abs(dv.z) <= 1e-12) tNear.z = -1e30;
                        axis = tNear.x >= tNear.y && tNear.x >= tNear.z ? 0 : (tNear.y >= tNear.z ? 1 : 2);
                        break;
                    }
                    uint sb = uint((q.x & 2) + 4 * (q.z & 2) + 16 * (q.y & 2));
                    uint64_t sub = (uint64_t(0x33) << sb) | (uint64_t(0x33) << (sb + 16));
                    int cl = (bits & sub) == 0 ? 2 : 1;
                    lo = v & ~(cl - 1);
                    hi = lo + cl;
                }
            }
            // out of the box through its nearest face, into the cell across it by index
            float3 tt = (float3(dv.x > 0.0 ? hi.x : lo.x, dv.y > 0.0 ? hi.y : lo.y, dv.z > 0.0 ? hi.z : lo.z) - ro) * inv;
            tt = float3(tt.x > u ? tt.x : 1e30, tt.y > u ? tt.y : 1e30, tt.z > u ? tt.z : 1e30);
            float tn = min(tt.x, min(tt.y, tt.z));
            u = max(tn, u) + 1e-3;
            exitAxis = tt.x <= tn ? 0 : (tt.y <= tn ? 1 : 2);
            exitTo = exitAxis == 0 ? (dv.x > 0.0 ? hi.x : lo.x - 1) : exitAxis == 1 ? (dv.y > 0.0 ? hi.y : lo.y - 1) : (dv.z > 0.0 ? hi.z : lo.z - 1);
        }
        if (grazing) {
            // (the grazing look done: its hit, else the finer hit as it was)
            grazing = false;
            if (tHit < 0.0) {
                tHit = fineT;
                mat = fineMat;
                axis = fineAxis;
                k = fineK;
                hitFrom = fineFrom;
                break;  // (final: it did not want its smooth surface)
            }
        } else if (tHit >= 0.0 && !noGraze && !finer) {
            // the hit's own footprint (its face's cos: a grazing face's pixel covers more) and the residency there; when
            // that wants a coarser level than the chord's, the grazing look: that level from 3 of its voxels in front of
            // the hit to 3 past it. (Not for a coarse cube that wants its smooth surface: its cube face's cos is the
            // staircase's, not the surface's)
            float vh = float(1 << k);
            bool wantSmooth = k > 0 && !noSmooth && vh >= SMOOTH_COARSE_PX * tHit * pa;
            float cs = clamp(abs(d[axis]), clamp(frame.lodParams.y, 0.05, 1.0), 1.0);
            float lh = max(log2(max(tHit * pa / cs, 1e-6)) + frame.lodParams.z, lodRes(o + dir * tHit + float3(0, kc * tHit * tHit, 0), lodCount));
            int k2 = clamp(int(floor(lh + xi)), 0, lodCount - 1);
            if (!wantSmooth && k2 > lodC) {
                fineT = tHit;
                fineMat = mat;
                fineAxis = axis;
                fineK = k;
                fineFrom = hitFrom;
                gK = k2;
                float s2 = float(1 << k2);
                gEnd = min(tHit + 3.0 * s2, tMax);
                tStart = max(tStart, tHit - 3.0 * s2);  // (the look's start; a pass 1 sets its own)
                grazing = true;
                continue;
            }
        }
        // 6.3: a coarse level's cube wider than the pixel's footprint is drawn as its level's smooth surface (its occupancy
        // a density, trilinear between voxel centres, the surface where it reaches one half: atlas_far.slang
        // smoothPrimaryHit and coarseSmoothHit), searched along the ray's tangent at the hit from a voxel before the cube's
        // entry (never before the chord that hit) to a voxel and a half past it. Once a ray, after its walk: not inside
        // the walk's divergent loop
        float vs = float(1 << k);
        if (tHit < 0.0 || k == 0 || noSmooth || vs < SMOOTH_COARSE_PX * tHit * pa) break;
        float3 rs = (o + dir * tHit + float3(0, kc * tHit * tHit, 0) - float3(gOrigin - frame.camInt.xyz)) / vs;
        float3 ds = (dir + float3(0, 2.0 * kc * tHit, 0)) / vs;
        float back = -min(vs, max(tHit - hitFrom, 0.0));
        float u0 = back, u1 = min(1.5 * vs, max(tMax - tHit, 0.0));
        int3 cellAt = int3(-0x7FFFFFFF, 0, 0);
        uint m0 = 0u, m1 = 0u, m2 = 0u, m3 = 0u, m4 = 0u, m5 = 0u, m6 = 0u, m7 = 0u;
        float o0 = 0.0, o1 = 0.0, o2 = 0.0, o3 = 0.0, o4 = 0.0, o5 = 0.0, o6 = 0.0, o7 = 0.0;
        const int N = 10;
        float du = (u1 - u0) / float(N);
        float us = u0, uPrev = u0;
        float3 w3 = float3(0, 0, 0);
        bool crossed = false;
        [loop]
        for (int si = 0; si < N + 5; si++) {
            // (N even samples, then bisection between the last one outside and the first one inside; the cell of eight
            // voxel centres around a sample read again only when the sample leaves it)
            float3 q = rs + ds * us - 0.5;
            int3 b = int3(floor(q));
            if (any(b != cellAt)) {
                cellAt = b;
                m0 = matAt(b, gx, gz, X, Y, Z, base);
                m1 = matAt(b + int3(1, 0, 0), gx, gz, X, Y, Z, base);
                m2 = matAt(b + int3(0, 1, 0), gx, gz, X, Y, Z, base);
                m3 = matAt(b + int3(1, 1, 0), gx, gz, X, Y, Z, base);
                m4 = matAt(b + int3(0, 0, 1), gx, gz, X, Y, Z, base);
                m5 = matAt(b + int3(1, 0, 1), gx, gz, X, Y, Z, base);
                m6 = matAt(b + int3(0, 1, 1), gx, gz, X, Y, Z, base);
                m7 = matAt(b + int3(1, 1, 1), gx, gz, X, Y, Z, base);
                o0 = occOf(m0, skipW); o1 = occOf(m1, skipW); o2 = occOf(m2, skipW); o3 = occOf(m3, skipW);
                o4 = occOf(m4, skipW); o5 = occOf(m5, skipW); o6 = occOf(m6, skipW); o7 = occOf(m7, skipW);
            }
            w3 = q - float3(b);
            float f = lerp(lerp(lerp(o0, o1, w3.x), lerp(o2, o3, w3.x), w3.y), lerp(lerp(o4, o5, w3.x), lerp(o6, o7, w3.x), w3.y), w3.z);
            if (si < N) {
                if (f >= 0.5) {
                    crossed = true;
                    u1 = us;
                    if (si == 0) break;  // (inside at once: the surface at the search's start)
                    u0 = uPrev;  // (bisection on [uPrev, us] from here)
                    us = 0.5 * (u0 + u1);
                    si = N - 1;
                    continue;
                }
                uPrev = us;
                us += du;
                if (si == N - 1) break;  // (no crossing: the ray passes the voxel's corner)
                continue;
            }
            if (f >= 0.5) u1 = us; else u0 = us;
            us = 0.5 * (u0 + u1);
        }
        if (crossed) {
            // the colour: the solid corners' albedo by their trilinear weights (no data: no colour of its own)
            float3 acc = float3(0, 0, 0);
            float wsum = 0.0;
            float3 v0 = 1.0 - w3;
            if (o0 > 0.0 && m0 != 0xFFFFFFFFu) { float wk = v0.x * v0.y * v0.z; acc += palette[m0].albedoRough.rgb * wk; wsum += wk; }
            if (o1 > 0.0 && m1 != 0xFFFFFFFFu) { float wk = w3.x * v0.y * v0.z; acc += palette[m1].albedoRough.rgb * wk; wsum += wk; }
            if (o2 > 0.0 && m2 != 0xFFFFFFFFu) { float wk = v0.x * w3.y * v0.z; acc += palette[m2].albedoRough.rgb * wk; wsum += wk; }
            if (o3 > 0.0 && m3 != 0xFFFFFFFFu) { float wk = w3.x * w3.y * v0.z; acc += palette[m3].albedoRough.rgb * wk; wsum += wk; }
            if (o4 > 0.0 && m4 != 0xFFFFFFFFu) { float wk = v0.x * v0.y * w3.z; acc += palette[m4].albedoRough.rgb * wk; wsum += wk; }
            if (o5 > 0.0 && m5 != 0xFFFFFFFFu) { float wk = w3.x * v0.y * w3.z; acc += palette[m5].albedoRough.rgb * wk; wsum += wk; }
            if (o6 > 0.0 && m6 != 0xFFFFFFFFu) { float wk = v0.x * w3.y * w3.z; acc += palette[m6].albedoRough.rgb * wk; wsum += wk; }
            if (o7 > 0.0 && m7 != 0xFFFFFFFFu) { float wk = w3.x * w3.y * w3.z; acc += palette[m7].albedoRough.rgb * wk; wsum += wk; }
            alb = wsum > 1e-6 ? acc / wsum : float3(0.5, 0.5, 0.5);
            // (the crossing's own cell's gradient, for where the stencil below is flat)
            float3 gc = float3(lerp(lerp(o1 - o0, o3 - o2, w3.y), lerp(o5 - o4, o7 - o6, w3.y), w3.z),
                               lerp(lerp(o2 - o0, o3 - o1, w3.x), lerp(o6 - o4, o7 - o5, w3.x), w3.z),
                               lerp(lerp(o4 - o0, o5 - o1, w3.x), lerp(o6 - o2, o7 - o3, w3.x), w3.y));
            // the normal: minus the density's gradient, central differences over a voxel either side of the crossing
            // (atlas_far.slang vgDensityGrad), by lines: along each axis, through each of the cell's four voxel pairs
            // across it, four voxels (one before the cell, its two, one after); the cell's eight centres read once, as
            // a mask, each line reads its two arms
            float3 qh = rs + ds * u1 - 0.5;
            int3 b = int3(floor(qh));
            float3 wq = qh - float3(b);
            uint cm = 0u;
            [unroll]
            for (int j = 0; j < 8; j++)
                cm |= occOf(matAt(b + int3(j & 1, (j >> 1) & 1, (j >> 2) & 1), gx, gz, X, Y, Z, base), skipW) > 0.0 ? 1u << uint(j) : 0u;
            float3 gr = float3(0, 0, 0);
            [loop]
            for (int ln = 0; ln < 12; ln++) {
                int ax = ln >> 2, ia = ln & 1, ie = (ln >> 1) & 1;
                int3 sv = ax == 0 ? int3(1, 0, 0) : (ax == 1 ? int3(0, 1, 0) : int3(0, 0, 1));
                int3 o3i = ax == 0 ? int3(0, ia, ie) : (ax == 1 ? int3(ia, 0, ie) : int3(ia, ie, 0));
                int3 p0 = b + o3i;
                uint j0 = uint(o3i.x + 2 * o3i.y + 4 * o3i.z), j1 = j0 + uint(sv.x + 2 * sv.y + 4 * sv.z);
                float l0 = occOf(matAt(p0 - sv, gx, gz, X, Y, Z, base), skipW);
                float l1 = float((cm >> j0) & 1u);
                float l2 = float((cm >> j1) & 1u);
                float l3 = occOf(matAt(p0 + 2 * sv, gx, gz, X, Y, Z, base), skipW);
                float wa = ax == 0 ? wq.x : (ax == 1 ? wq.y : wq.z);
                float w1 = ax == 0 ? wq.y : wq.x, w2 = ax == 2 ? wq.y : wq.z;  // (the other two axes, in order)
                float gv = (ia != 0 ? w1 : 1.0 - w1) * (ie != 0 ? w2 : 1.0 - w2) * (lerp(l2, l3, wa) - lerp(l0, l1, wa));
                gr += float3(ax == 0 ? gv : 0.0, ax == 1 ? gv : 0.0, ax == 2 ? gv : 0.0);
            }
            if (dot(gr, gr) < 1e-8) gr = gc;
            float gl = length(gr);
            float3 n = gl > 1e-5 ? -gr / gl : -normalize(ds);
            // (inside the density where the search starts: the cube's entry is the surface)
            tHit += u1 <= back + 1e-6 ? 0.0 : u1;
            float3 an = abs(n);
            axis = an.x >= an.y && an.x >= an.z ? 0 : (an.y >= an.z ? 1 : 2);
            float2 e = octEncode(n);
            hasAlbedo = 1u | HIT_SMOOTH_N | (uint(saturate(e.x) * 255.0 + 0.5) << 16) | (uint(saturate(e.y) * 255.0 + 0.5) << 24);
            break;
        }
        if (pass == 1) break;  // (the cube)
        pass = 1;
        tStart = tHit + 1.5 * vs;
        if (tStart >= tMax) {
            tHit = -1.0;
            break;
        }
    }
    // the hit record (atlas_far.slang farHitStore's layout: tHit, tW, material, albedo flags; axis, level, water's level,
    // steps; the water's material; the albedo as halves, the frame: stamped stale, so farMain's amortisation never takes
    // a lean record after a switch)
    uint i2 = pix * 2u;
    farHit[i2] = uint4(asuint(tHit), asuint(-1.0), mat, hasAlbedo);
    farHit[i2 + 1u] = uint4(uint(axis + 1) | (uint(k) << 2) | (min(steps, 0x3FFFFFu) << 10), 0u,
                            f32tof16(alb.x) | (f32tof16(alb.y) << 16), f32tof16(alb.z) | (((frame.temporal.x - 0x8000u) & 0xFFFFu) << 16));
#if ATLAS_LEAN_CLOCK
    counters[pix] = uint((clockARB() - clk0) >> 4);
#else
    counters[pix] = steps;  // (RAY_STEPS: capped)
#endif
}
