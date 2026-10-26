// Infinite ATLAS: the lean trace stage (docs/ATLAS-RENDER-BLUEPRINT.md, step 6), written from scratch, not derived from
// farMain. One thread a pixel: the view ray (curved with the planet, in chords), each chord walked through one level of
// the GPU grid (level 0 blocks, coarse levels 1..6 voxels of 2^k blocks) and the hit stored in the hit buffer (farHit,
// the record atlas_far.slang farHitStore writes: the shade side reads it).
// Step 6.1, the ceiling: every level's voxels plain cubes, material and entry axis only (no bricks, shapes, smooth
// surfaces, water medium or grazing look). Rules from line one: no [noinline], no struct passed to a call, no array
// indexed at run time, one walk loop; its budget row in kernel_budget.json (stage_trace).
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
static const uint LEAN_STEPS = 8192u;                                // a ray's steps at most

[shader("compute")]
[numthreads(8, 8, 1)]
void traceMain(uint3 id : SV_DispatchThreadID) {
    uint w = frame.dims.x, h = frame.dims.y;
    if (id.x >= w || id.y >= h) return;
    uint pix = id.y * w + id.x;
#if ATLAS_LEAN_CLOCK
    uint64_t clk0 = clockARB();
#endif
    // the ray: the host's jitter as farMain's, the per-pixel threshold of the continuous level as farMain's (gLodXi)
    float2 ndc = float2((float(id.x) + 0.5) / float(w) * 2.0 - 1.0, 1.0 - (float(id.y) + 0.5) / float(h) * 2.0) + frame.jitter.xy;
    float3 dir = normalize(unproject(frame, ndc));
    float3 o = frame.camFrac.xyz;
    float xi = frac(pixelDither(id.xy, 11u) + (frame.jitter.z > 0.5 ? 0.618034 * float(frame.temporal.x & 255u) : 0.0));
    float R = frame.camFrac.w;
    float kc = R > 0 ? dot(dir.xz, dir.xz) / (2.0 * R) : 0.0;  // (the parabola: y + kc t^2)
    float pa = frame.params.w * frame.params.z;                // (blocks a pixel covers per block of distance)
    float worldTop = 640.0 - float(frame.camInt.y);
    float tMax = frame.params.y;
    int lodCount = min(int(frame.dims.w), 7);
    bool gridOn = frame.vgOrigin.w != 0;

    // the walk's state: the chord [t0, t0 + len) of the view ray (origin a, direction d), its level k and that level's
    // grid (its origin and window), the voxel walk's parameter u along the chord (blocks), the face it entered by
    float t0 = 0.0, len = 0.0, u = 0.0;
    float3 ro = float3(0, 0, 0), dv = float3(0, 0, 1), inv = float3(1e30, 1e30, 1e30);
    int k = -1, gx = 0, gz = 0, X = 1, Y = 1, Z = 1, axis = 1;
    uint base = 0u;
    int3 gOrigin = int3(0, 0, 0);
    bool newChord = true, coarser = false;
    float tHit = -1.0;
    uint mat = 0u, steps = 0u;
    [loop]
    for (uint it = 0u; it < LEAN_STEPS; it++) {
        if (newChord || coarser) {
            if (newChord) {
                t0 += len;
                if (t0 >= tMax || !gridOn) break;
                float3 a = o + dir * t0 + float3(0, kc * t0 * t0, 0);
                if (a.y > worldTop && dir.y + 2.0 * kc * t0 >= 0.0) break;  // (above the world, climbing)
                // the level: the footprint's and the residency's, at the pixel's threshold (farMain's lodPick)
                float dc = max(max(abs(a.x), abs(a.z)), 1.0);
                float minCos = clamp(frame.lodParams.y, 0.05, 1.0);
                float lf = log2(max(t0 * pa, 1e-6)) + frame.lodParams.z;
                float lr = float(lodCount);
                float r0 = frame.lodR[0].x;
                if (dc <= r0) lr = 1.0 + log2(dc / r0);
                else {
                    [loop]
                    for (int j = 0; j + 1 < lodCount; j++) {
                        float rj = frame.lodR[j >> 2][j & 3], rn = frame.lodR[(j + 1) >> 2][(j + 1) & 3];
                        if (dc <= rn) {
                            lr = float(j + 1) + log2(dc / rj) / log2(rn / rj);
                            break;
                        }
                    }
                }
                k = clamp(int(floor(max(lf, lr) + xi)), 0, 6);
                len = min(192.0 * float(1 << k), tMax - t0);
                float tn = t0 + len;
                float3 b = o + dir * tn + float3(0, kc * tn * tn, 0);
                dv = (b - a) / len;  // (the chord's direction: |dv| ~ 1, u ~ the view ray's distance from t0)
                ro = a;              // (camera-relative; made grid-local below)
                u = 0.0;
                axis = 1;
            } else {
                // this level does not hold the point: the next coarser one, from where the walk stands
                ro = ro * float(1 << k) + float3(gOrigin - frame.camInt.xyz);  // (back to camera-relative blocks)
                dv *= float(1 << k);
                k++;
                if (k > 6) {
                    newChord = true;  // (no level holds it: on to the next chord)
                    coarser = false;
                    k = 6;
                    continue;
                }
            }
            // level k's grid (atlas_far.slang vgGrid): its window and first chunk word
            bool on;
            if (k == 0) {
                gOrigin = frame.vgOrigin.xyz * 16;
                gx = frame.vgOrigin.x;
                gz = frame.vgOrigin.z;
                X = int(frame.vgDims.x);
                Y = int(frame.vgDims.y);
                Z = int(frame.vgDims.z);
                base = 0u;
                on = true;
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
                on = ((L.w >> 16) & 1) != 0 && ((L.w >> 17) & 1) != 0;
            }
            float vs = float(1 << k);
            if (!coarser) ro -= float3(gOrigin - frame.camInt.xyz);
            else ro -= float3(gOrigin - frame.camInt.xyz);
            ro /= vs;
            dv /= vs;
            inv = float3(abs(dv.x) > 1e-12 ? 1.0 / dv.x : 1e30, abs(dv.y) > 1e-12 ? 1.0 / dv.y : 1e30, abs(dv.z) > 1e-12 ? 1.0 / dv.z : 1e30);
            newChord = false;
            coarser = false;
            if (!on) {
                coarser = true;
                continue;
            }
        }
        if (u >= len) {
            newChord = true;
            continue;
        }
        steps++;
        // the voxel the walk stands in (grid-local), its chunk's word
        float3 p = ro + dv * u;
        int3 v = int3(floor(p));
        if (any(v < int3(0, 0, 0)) || v.x >= X * 16 || v.y >= Y * 16 || v.z >= Z * 16) {
            coarser = true;
            continue;
        }
        int3 c = v >> 4;
        int px = (c.x + gx) % X, pz = (c.z + gz) % Z;
        px += px < 0 ? X : 0;
        pz += pz < 0 ? Z : 0;
        uint cw = vgChunks[base + uint((c.y * Z + pz) * X + px)];
        uint st = cw & 3u;
        if (st == ST_UNLOADED) {
            coarser = true;
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
                    break;
                }
                uint sb = uint((q.x & 2) + 4 * (q.z & 2) + 16 * (q.y & 2));
                uint64_t sub = (uint64_t(0x33) << sb) | (uint64_t(0x33) << (sb + 16));
                int cell = (bits & sub) == 0 ? 2 : 1;
                lo = v & ~(cell - 1);
                hi = lo + cell;
            }
        }
        // out of the box through its nearest face (the face crossed is the next voxel's entry axis)
        float3 tt = (float3(dv.x > 0.0 ? hi.x : lo.x, dv.y > 0.0 ? hi.y : lo.y, dv.z > 0.0 ? hi.z : lo.z) - ro) * inv;
        tt = float3(tt.x > u ? tt.x : 1e30, tt.y > u ? tt.y : 1e30, tt.z > u ? tt.z : 1e30);
        float tn = min(tt.x, min(tt.y, tt.z));
        axis = tt.x <= tn ? 0 : (tt.y <= tn ? 1 : 2);
        u = max(tn, u) + 1e-3;
    }
    // the hit record (atlas_far.slang farHitStore's layout: tHit, tW, material, albedo flags; axis, level, water's level,
    // steps; the water's material; the albedo as halves, the frame)
    uint i2 = pix * 2u;
    farHit[i2] = uint4(asuint(tHit), asuint(-1.0), mat, 0u);
    farHit[i2 + 1u] = uint4(uint(axis + 1) | (uint(max(k, 0)) << 2) | (min(steps, 0x3FFFFFu) << 10), 0u, 0u,
                            (frame.temporal.x & 0xFFFFu) << 16);
#if ATLAS_LEAN_CLOCK
    counters[pix] = uint((clockARB() - clk0) >> 4);
#else
    counters[pix] = steps;
#endif
}
