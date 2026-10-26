// Infinite ATLAS: the lean trace stage (docs/ATLAS-RENDER-BLUEPRINT.md, step 6), written from scratch, not derived from
// farMain. One thread a pixel: the view ray (curved with the planet, in chords), each chord walked through one level of
// the GPU grid (level 0 blocks, coarse levels 1..6 voxels of 2^k blocks) and the hit stored in the hit buffer (farHit,
// the record atlas_far.slang farHitStore writes: the shade side, farMain's ATLAS_FAR_PASS 2, reads it).
// Step 6.1-6.2: every level's voxels plain cubes, material and entry axis only (no bricks, shapes, smooth surfaces,
// water medium or grazing look: farMain's ATLAS_ABLATE 2952790048 is the same picture). farMain's choices otherwise:
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
    float t0 = 0.0, len = 0.0, u = 0.0;
    float3 a = o, d = dir, ro = float3(0, 0, 0), dv = float3(0, 0, 1), inv = float3(1e30, 1e30, 1e30);
    int lodC = 0, k = 0, gx = 0, gz = 0, X = 1, Y = 1, Z = 1, exitAxis = -1, exitTo = 0;
    uint base = 0u;
    int3 gOrigin = int3(0, 0, 0);
    bool chord = true, level = false, finer = false;
    float tHit = -1.0;
    uint mat = 0u, steps = 0u;
    int axis = 1;
    [loop]
    for (uint it = 0u; it < LOOP_CAP; it++) {
        if (chord) {
            // the next chord: from the end of this one, its level the footprint's and the residency's (never finer
            // than the last chord's), to where that pick would change
            t0 += len;
            if (t0 >= tMax) break;
            a = o + dir * t0 + float3(0, kc * t0 * t0, 0);
            if (a.y > worldTop && dir.y + 2.0 * kc * t0 >= 0.0) break;  // (above the world, climbing)
            float dc = max(max(abs(a.x), abs(a.z)), 1.0);
            float lf = log2(max(t0 * pa, 1e-6)) + frame.lodParams.z;
            float lr = float(lodCount);
            if (dc <= lodR(0)) lr = 1.0 + log2(dc / lodR(0));
            else {
                [loop]
                for (int j = 0; j + 1 < lodCount && j < 7; j++)
                    if (dc <= lodR(j + 1)) {
                        lr = float(j + 1) + log2(dc / lodR(j)) / log2(lodR(j + 1) / lodR(j));
                        break;
                    }
            }
            int want = clamp(int(floor(max(lf, lr) + xi)), 0, lodCount - 1);
            lodC = max(lodC, want);
            float s = float(1 << lodC);
            float tEnd = 1e30;
            if (lodC == want && lodC + 1 < lodCount) {
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
    // the hit record (atlas_far.slang farHitStore's layout: tHit, tW, material, albedo flags; axis, level, water's level,
    // steps; the water's material; the albedo as halves, the frame: stamped stale, so farMain's amortisation never takes
    // a lean record after a switch)
    uint i2 = pix * 2u;
    farHit[i2] = uint4(asuint(tHit), asuint(-1.0), mat, 0u);
    farHit[i2 + 1u] = uint4(uint(axis + 1) | (uint(k) << 2) | (min(steps, 0x3FFFFFu) << 10), 0u, 0u,
                            ((frame.temporal.x - 0x8000u) & 0xFFFFu) << 16);
#if ATLAS_LEAN_CLOCK
    counters[pix] = uint((clockARB() - clk0) >> 4);
#else
    counters[pix] = steps;  // (RAY_STEPS: capped)
#endif
}
