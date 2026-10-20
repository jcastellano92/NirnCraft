// atlas_core far-field marcher: one compute kernel, compiled twice from this file:
//   slangc -target spirv  -> Vulkan (game and GPU harness)
//   slangc -target cpp    -> the CPU reference backend (parity tests, machines without Vulkan)
// so the two can only differ by floating point, never by algorithm.
//
// Space: everything is relative to the integer camera block (frame.camInt) in float, exact enough out to ~64 km.
// Curvature: the view frame is flat; a point at horizontal distance d from the camera stands at world altitude
// y + d^2 / (2R) (the rasterised near field applies the matching vertex drop y' = y - d^2 / (2R)). A view ray
// o + t D is therefore the parabola o + t D + up * k t^2 in world-flat space, k = |D_xz|^2 / (2R), walked as
// straight chords per segment (error k S^2 / 4: 0.02 blocks for S = 1 km at the real-Earth radius).

#include "atlas_shared.slang"

[[vk::binding(0, 0)]] ConstantBuffer<FrameGpu> frame;
[[vk::binding(1, 0)]] StructuredBuffer<int> regionTable;
[[vk::binding(2, 0)]] StructuredBuffer<RegionGpu> regions;
[[vk::binding(3, 0)]] StructuredBuffer<uint> nodes;
[[vk::binding(4, 0)]] StructuredBuffer<uint64_t> leaves;
[[vk::binding(5, 0)]] StructuredBuffer<uint> runEnd;
[[vk::binding(6, 0)]] StructuredBuffer<uint> runMat;
[[vk::binding(7, 0)]] StructuredBuffer<MaterialGpu> palette;
[[vk::binding(8, 0)]] RWStructuredBuffer<float4> outColor;  // linear HDR, premultiplied; a = coverage
[[vk::binding(9, 0)]] RWStructuredBuffer<uint> outNormal;
[[vk::binding(10, 0)]] RWStructuredBuffer<float> outDepth;
[[vk::binding(11, 0)]] StructuredBuffer<uint> weather;
[[vk::binding(12, 0)]] RWStructuredBuffer<uint> counters;
[[vk::binding(13, 0)]] StructuredBuffer<uint> cloudNoise;
[[vk::binding(14, 0)]] StructuredBuffer<float> nearDepth;  // view distance of the near field per pixel (row 0 top)

#include "atlas_actors.slang"
[[vk::binding(15, 0)]] StructuredBuffer<ActorGpu> actors;
[[vk::binding(16, 0)]] StructuredBuffer<uint> heightMip;  // per region: 2D max-height mip (Svdag::heightMip)
// the half-resolution cloud pass (cloudMain) and what it hands the far pass
[[vk::binding(17, 0)]] RWStructuredBuffer<float4> cloudBuf;    // premultiplied cloud colour (haze applied), a = transmittance
[[vk::binding(18, 0)]] RWStructuredBuffer<float> cloudDepth;   // first cloud distance (1e30 = none)
[[vk::binding(19, 0)]] StructuredBuffer<float> prevSurface;    // last frame's surface distance per full-res pixel
[[vk::binding(20, 0)]] RWStructuredBuffer<float> outSurface;   // this frame's (terrain, actor or near field; 1e30 = sky)
// the cloud noise volume as a hardware-filtered 3D texture (Vulkan); the C++ build samples the cloudNoise buffer
[[vk::binding(21, 0)]] Sampler3D<float4> cloudNoiseTex;
// the previous frame's cloud pass output, for temporal reprojection (declared after the texture: CPU layout order)
[[vk::binding(22, 0)]] StructuredBuffer<float4> prevCloudBuf;
[[vk::binding(23, 0)]] StructuredBuffer<float> prevCloudDepth;
// the sun map: size x (size + 1) texels; rows 0 .. size-1 (occluder's sun-axis coordinate, cloud transmittance),
// row `size` the map's parameters (see sunMapMain)
[[vk::binding(24, 0)]] RWStructuredBuffer<float2> sunMap;
// (ATLAS_FRAME_GROUPS) the content-shaped groups: header, material shapes, per region nodes and brick pool
[[vk::binding(25, 0)]] StructuredBuffer<uint4> groupData;

#include "atlas_clouds.slang"

static const int TRACE_MISS = 0;
static const int TRACE_HIT = 1;
static const int TRACE_UNCOVERED = 2;

struct TraceResult {
    float t;       // chord parameter of the hit
    uint mat;
    int axis;      // axis of the face crossed into the hit voxel
    int lod;
    uint steps;
    float dither;  // 0..1, fixed per pixel: which side of a refinement fade it shows (old below the progress)
    float3 albedo;   // a groups hit: the texel's albedo (linear), when hasAlbedo
    uint hasAlbedo;
};

uint popcount64(uint64_t x) { return countbits(uint(x)) + countbits(uint(x >> 32)); }

uint lookupMaterial(RegionGpu r, uint rank) {
    uint lo = r.runs.x, hi = r.runs.x + r.runs.y;  // first run whose end > rank
    while (lo < hi) {
        uint mid = (lo + hi) >> 1;
        if (runEnd[mid] > rank) hi = mid; else lo = mid + 1;
    }
    return lo < r.runs.x + r.runs.y ? runMat[lo] : 0;
}

// March one region in its voxel units. ro/rd: chord origin and direction in region voxels per unit t.
// the share of foliage voxels a ray passes at the current trace's LOD (set by traceLod: leaves are cutout cubes in the
// near field, light and sky show between them; 0 at coarse LODs, where a forest reads as a mass)
static float gLeafGap = 0.0;

// Secondary rays (sun / moon shadows, sky visibility, block light; traceSecondary): foliage does not stop them, it
// attenuates them by the coverage of its cut-out faces, as light passes a crown's gaps (docs/ATLAS-LOD.md, "A realistic
// reference": a ray crossing a leaf block meets two cut-out faces, each opaque over FOLIAGE_COVER of its texels; so
// a block's transmittance is (1 - FOLIAGE_COVER)^2, an extinction of FOLIAGE_SIGMA per block of path). Accumulated:
// gTrans what passes, gThrough the pack's light-through-leaves estimate E[exp(-0.45 x first leaf)] (FOLIAGE_SSS),
// gSecT the ray's distance where the current chord starts.
static const float FOLIAGE_COVER = 0.65;
static const float FOLIAGE_SIGMA = 2.0996;  // -2 ln(1 - FOLIAGE_COVER)
static bool gSecondary = false;
static float gTrans = 1.0;
static float gThrough = 0.0;
static float gSecT = 0.0;

bool traceRegion(RegionGpu r, float3 ro, float3 rd, float t0, float t1, float eps, inout TraceResult res) {
    float3 inv = float3(abs(rd.x) > 1e-12 ? 1.0 / rd.x : 1e30, abs(rd.y) > 1e-12 ? 1.0 / rd.y : 1e30,
                        abs(rd.z) > 1e-12 ? 1.0 / rd.z : 1e30);
    float3 stepPos = float3(rd.x > 0 ? 1.0 : 0.0, rd.y > 0 ? 1.0 : 0.0, rd.z > 0 ? 1.0 : 0.0);
    uint nodeBase = r.bases.x, leafBase = r.bases.y;
    float t = t0;
    int axis = res.axis;
    // The descent path of the previous step: a step usually stays inside most of its ancestors, so the next descent
    // resumes from the deepest one still containing the new voxel instead of the root; and a step inside the same
    // 4^3 brick reuses its bitmap without touching the tree at all.
    uint pathPtr[6];
    uint pathRank[6];
    pathPtr[0] = r.bases.z;
    pathRank[0] = 0;
    int valid = 0;
    int3 pv = int3(0, 0, 0);
    int3 brickOf = int3(-1, -1, -1);
    uint64_t brickBits = 0;
    uint brickRank = 0;
    uint hmBase = r.bases.w;
    for (int it = 0; it < 1024; it++) {
        if (t >= t1) break;
        res.steps++;
        float3 p = ro + rd * t;
        // Over terrain: find the largest column cell (whole region down to 4x4 voxels) whose highest voxel the ray
        // stays above until it leaves the cell, and skip straight across it. Far rays spend most of their length here.
        bool over = false;
        int lvlOff = 5460;
        for (int k = 6; k >= 0; k--) {
            int per = 64 >> k;
            int sz = 4 << k;
            int2 cxz = clamp(int2(floor(p.xz)) >> (2 + k), int2(0, 0), int2(per - 1, per - 1));
            uint top = heightMip[hmBase + uint(lvlOff) + uint(cxz.y * per + cxz.x)];
            float2 bound = float2(cxz * sz) + float2(rd.x > 0 ? float(sz) : 0.0, rd.z > 0 ? float(sz) : 0.0);
            float2 tx = (bound - ro.xz) * inv.xz;
            tx = float2(tx.x > t ? tx.x : 1e30, tx.y > t ? tx.y : 1e30);
            float te = min(min(tx.x, tx.y), t1);
            float yMin = min(p.y, ro.y + rd.y * te);
            if (yMin >= float(top)) {
                if (te > t) {
                    axis = te >= t1 ? axis : (tx.x <= tx.y ? 0 : 2);
                    t = te + eps;
                    over = true;
                }
                break;
            }
            lvlOff -= (64 >> (k - 1 < 0 ? 0 : k - 1)) * (64 >> (k - 1 < 0 ? 0 : k - 1));
        }
        if (over) continue;
        int3 v = clamp(int3(floor(p)), int3(0, 0, 0), int3(255, 255, 255));
        int cell = 0;
        uint64_t bits = 0;
        uint rank = 0;
        int3 vb = v >> 2;
        if (vb.x == brickOf.x && vb.y == brickOf.y && vb.z == brickOf.z) {
            bits = brickBits;
            rank = brickRank;
        } else {
            int lvl = valid;
            while (lvl > 0) {
                int sh = 8 - lvl;
                int3 a = v >> sh, b = pv >> sh;
                if (a.x == b.x && a.y == b.y && a.z == b.z) break;
                lvl--;
            }
            uint ptr = pathPtr[lvl];
            rank = pathRank[lvl];
            int size = 256 >> lvl;
            while (size > 4) {
                int h = size >> 1;
                uint mask = nodes[nodeBase + ptr] & 0xFFu;
                uint c = ((v.x & h) != 0 ? 1u : 0u) | ((v.z & h) != 0 ? 2u : 0u) | ((v.y & h) != 0 ? 4u : 0u);
                if ((mask & (1u << c)) == 0) { cell = h; break; }
                uint k = countbits(mask);
                uint j = countbits(mask & ((1u << c) - 1u));
                rank += nodes[nodeBase + ptr + 1 + k + j];
                ptr = nodes[nodeBase + ptr + 1 + j];
                size = h;
                lvl++;
                if (lvl < 6) {
                    pathPtr[lvl] = ptr;
                    pathRank[lvl] = rank;
                }
            }
            valid = min(lvl, 5);
            pv = v;
            if (cell == 0) {
                bits = leaves[leafBase + ptr];
                brickOf = vb;
                brickBits = bits;
                brickRank = rank;
            }
        }
        if (cell == 0) {
            int3 l = v & 3;
            uint b = uint(l.x + 4 * l.z + 16 * l.y);
            if (((bits >> b) & 1) != 0) {
                rank += popcount64(bits & ((uint64_t(1) << b) - 1));
                uint hitMat = lookupMaterial(r, rank);
                uint hitCls = uint(palette[hitMat].emisClass.y);
                bool passWater = (frame.dims.z & FRAME_SEE_THROUGH_WATER) != 0 && hitCls == 2u;
                // gaps between leaves: a hashed share of foliage voxels lets the ray through (moving with the TAA
                // phase, so the resolve sees crowns as dappled, not solid; shadow rays get dappled light the same way)
                // a secondary ray meets a level-0 voxel of a shaped material (a snow layer, a slab, a stair, a fence: a
                // full cube to the voxel path) by its micro-voxels: it passes when the shape's are empty along it
                if (gSecondary && !passWater && r.origin.w == 0 && secondaryShapeMisses(hitMat, ro + rd * t - float3(v), rd))
                    passWater = true;
                float sigma = gSecondary && !passWater ? secondarySigma(hitMat, hitCls) : -1.0;
                if (sigma >= 0.0) {
                    // a secondary ray through foliage or a plant: attenuated over its chord through this voxel, then on
                    float3 tv = (float3(v) + stepPos - ro) * inv;
                    tv = float3(abs(rd.x) > 1e-12 && tv.x > t ? tv.x : 1e30, abs(rd.y) > 1e-12 && tv.y > t ? tv.y : 1e30,
                                abs(rd.z) > 1e-12 && tv.z > t ? tv.z : 1e30);
                    float chord = clamp(min(tv.x, min(tv.y, tv.z)) - t, 0.0, 2.0 * float(1 << r.origin.w));
                    float x0 = max(gSecT + t, 0.0);
                    // E[exp(-0.45 x)] over where in this stretch the first opaque texel lies, weighted by what got here
                    gThrough += gTrans * exp(-0.45 * x0) * (sigma / (sigma + 0.45)) * (1.0 - exp(-(sigma + 0.45) * chord));
                    gTrans *= exp(-sigma * chord);
                    passWater = gTrans > 0.004;
                    if (!passWater) gTrans = 0.0;
                }
                if (!passWater && hitCls == 1u && gLeafGap > 0.0 && !gSecondary) {
                    uint phase = frame.jitter.z > 0.5 ? (frame.temporal.x & 7u) : 0u;
                    uint hh = (uint(v.x) * 73856093u) ^ (uint(v.y) * 19349663u) ^ (uint(v.z) * 83492791u) ^ (r.bases.x * 2654435761u) ^ (phase * 374761393u);
                    hh = (hh ^ (hh >> 13)) * 1274126177u;
                    passWater = float(hh & 0xFFFFu) / 65535.0 < gLeafGap;
                }
                if (!passWater) {
                res.t = t;
                res.mat = hitMat;
                // the face we entered through: the axis whose slab the ray entered last (robust for rays that
                // graze a boundary plane, unlike the last DDA crossing)
                float3 vv = float3(v);
                float3 tNear = (vv + (float3(1, 1, 1) - stepPos) - ro) * inv;
                if (abs(rd.x) <= 1e-12) tNear.x = -1e30;
                if (abs(rd.y) <= 1e-12) tNear.y = -1e30;
                if (abs(rd.z) <= 1e-12) tNear.z = -1e30;
                res.axis = tNear.x >= tNear.y && tNear.x >= tNear.z ? 0 : (tNear.y >= tNear.z ? 1 : 2);
                return true;
                }
            }
            uint sb = uint((l.x & 2) + 4 * (l.z & 2) + 16 * (l.y & 2));
            uint64_t sub = (uint64_t(0x33) << sb) | (uint64_t(0x33) << (sb + 16));
            cell = (bits & sub) == 0 ? 2 : 1;
        }
        float3 c0 = float3(v & ~(cell - 1));
        float3 tt = (c0 + stepPos * float(cell) - ro) * inv;
        // a boundary at or behind t (the clamped voxel of a ray grazing a region face) is not an exit
        tt = float3(tt.x > t ? tt.x : 1e30, tt.y > t ? tt.y : 1e30, tt.z > t ? tt.z : 1e30);
        float tn = min(tt.x, min(tt.y, tt.z));
        axis = tt.x <= tn ? 0 : (tt.y <= tn ? 1 : 2);
        t = max(tn, t) + eps;
    }
    res.axis = axis;
    return false;
}

float3 regionOriginRel(RegionGpu r) {
    return float3(int3(r.origin.xyz) - frame.camInt.xyz);
}

#include "atlas_groups.slang"

// What a secondary ray loses per block of path through a voxel of material `mat` (-1: it stops there, solid): a
// cut-out shape (leaves, plants: the game classes plants as terrain, and a solid cube of grass tuft blocked every
// shadow and sky ray beside it) by its coverage, the share of its micro-voxels filled times their cut-out alpha
// (a crossing meets two such faces of a block: -2 ln(1 - c)); foliage without a shape (the voxel path alone) by
// FOLIAGE_COVER. The last material asked is remembered (a ray crosses a crown of one kind).
static uint gSigMat = 0xFFFFFFFFu;
static float gSigVal = -1.0;
float secondarySigma(uint mat, uint cls) {
    if (mat == gSigMat) return gSigVal;
    float sg = cls == 1u ? FOLIAGE_SIGMA : -1.0;
    uint sh = shapeOf(mat);
    if (sh != 0u) {
        uint4 head = groupData[sh >> 2];
        if ((head.z & 1u) != 0u) {
            float a = 0.0;
            for (uint k = 0u; k < 16u; k++) {
                uint w = gWord(sh + 100u + k);
                uint bits = (k < 8u ? head.x >> (k * 4u) : head.y >> ((k - 8u) * 4u)) & 15u;
                for (uint b = 0u; b < 4u; b++)
                    if (((bits >> b) & 1u) != 0u) a += float((w >> (b * 8u)) & 255u);
            }
            float c = a / (64.0 * 255.0);
            sg = -2.0 * log(max(1.0 - c, 0.02));
        } else {
            sg = -1.0;  // a shaped solid (a slab, a stair): solid to the coarse voxel path
        }
    }
    gSigMat = mat;
    gSigVal = sg;
    return sg;
}

// Whether a ray from local point l (0..1 in the voxel) along d misses the micro-voxels (4 x 4 x 4, AtlasLabExport's
// order) of material `mat`'s partial shape; false for full or unshaped materials and cut-out ones (secondarySigma).
static uint gMaskMat = 0xFFFFFFFFu;
static uint2 gMask = uint2(0u, 0u);
bool secondaryShapeMisses(uint mat, float3 l, float3 d) {
    if (mat != gMaskMat) {
        gMaskMat = mat;
        gMask = uint2(0xFFFFFFFFu, 0xFFFFFFFFu);
        uint sh = shapeOf(mat);
        if (sh != 0u) {
            uint4 head = groupData[sh >> 2];
            if ((head.z & 1u) == 0u) gMask = head.xy;
        }
    }
    if (gMask.x == 0xFFFFFFFFu && gMask.y == 0xFFFFFFFFu) return false;
    float3 q = clamp(l * 4.0, float3(0.001, 0.001, 0.001), float3(3.999, 3.999, 3.999));
    int3 m = int3(floor(q));
    float3 inv = float3(abs(d.x) > 1e-12 ? 1.0 / d.x : 1e30, abs(d.y) > 1e-12 ? 1.0 / d.y : 1e30,
                        abs(d.z) > 1e-12 ? 1.0 / d.z : 1e30);
    int3 st = int3(d.x > 0.0 ? 1 : -1, d.y > 0.0 ? 1 : -1, d.z > 0.0 ? 1 : -1);
    float3 tn = (float3(m) + float3(d.x > 0.0 ? 1.0 : 0.0, d.y > 0.0 ? 1.0 : 0.0, d.z > 0.0 ? 1.0 : 0.0) - q) * inv;
    tn = float3(abs(d.x) > 1e-12 ? tn.x : 1e30, abs(d.y) > 1e-12 ? tn.y : 1e30, abs(d.z) > 1e-12 ? tn.z : 1e30);
    float3 td = abs(inv);
    for (int k = 0; k < 12; k++) {
        uint bit = uint(m.x + 4 * (m.z + 4 * m.y));
        if ((((bit < 32u ? gMask.x >> bit : gMask.y >> (bit - 32u))) & 1u) != 0u) return false;
        int a = tn.x <= tn.y && tn.x <= tn.z ? 0 : (tn.y <= tn.z ? 1 : 2);
        m[a] += st[a];
        if (m[a] < 0 || m[a] > 3) return true;
        tn[a] += td[a];
    }
    return true;
}

// Walk the regions of one LOD along the chord p0 + d u, u in [0, len].
// On TRACE_UNCOVERED, uncExit is where the chord leaves the unloaded region cell (1e30 when it left the LOD's
// window: that LOD has nothing further along this ray at all).
int traceLod(int lod, float3 p0, float3 d, float len, inout TraceResult res, out float uncExit) {
    uncExit = 1e30;
    LodGpu L = frame.lods[lod];
    float s = float(1 << lod);
    float rs = 256.0 * s;
    // world origin of table cell 0 (origin.w is the vertical offset: region rows start at the world floor, y -64)
    int3 cell0 = L.origin.xyz * int(256 << lod) + int3(0, L.origin.w, 0);
    float3 base = float3(cell0 - frame.camInt.xyz);
    float3 q0 = (p0 - base) / rs;
    float3 qd = d / rs;
    float3 inv = float3(abs(qd.x) > 1e-14 ? 1.0 / qd.x : 1e30, abs(qd.y) > 1e-14 ? 1.0 / qd.y : 1e30,
                        abs(qd.z) > 1e-14 ? 1.0 / qd.z : 1e30);
    float u = 0;
    int3 c = int3(floor(q0));
    int3 stepC = int3(qd.x > 0 ? 1 : -1, qd.y > 0 ? 1 : -1, qd.z > 0 ? 1 : -1);
    bool firstCell = true;
    for (int it = 0; it < 64; it++) {
        if (u >= len) break;
        float3 tt = (float3(c) + float3(qd.x > 0 ? 1 : 0, qd.y > 0 ? 1 : 0, qd.z > 0 ? 1 : 0) - q0) * inv;
        // as in traceRegion: a face at or behind u (a ray running inside a region boundary plane) is not an exit
        tt = float3(tt.x > u ? tt.x : 1e30, tt.y > u ? tt.y : 1e30, tt.z > u ? tt.z : 1e30);
        float uExit = min(len, min(tt.x, min(tt.y, tt.z)));
        if (c.y < 0 || c.y >= L.dim.y) {
            // below the world floor or above the world top: nothing there
            if ((c.y < 0 && qd.y <= 0) || (c.y >= L.dim.y && qd.y >= 0)) return TRACE_MISS;
        } else {
            if (c.x < 0 || c.z < 0 || c.x >= L.dim.x || c.z >= L.dim.z) return TRACE_UNCOVERED;
            int idx = regionTable[L.dim.w + (c.y * L.dim.z + c.z) * L.dim.x + c.x];
            if (idx == -2) {
                uncExit = uExit;
                return TRACE_UNCOVERED;
            }
            if (idx >= 0) {
                RegionGpu r = regions[idx];
                bool air = false;  // a fading region that was empty air before: nothing to trace for these pixels
                if (r.runs.z != 0u) {
                    // a refinement fade (World::fading_): pixels whose dither is past the fade's progress still see
                    // what the region replaced, or the coarser LOD when it is new to the window
                    float prog = float(int(frame.temporal.z - r.runs.w)) / float(max(frame.temporal.w, 1u));
                    if (res.dither >= prog) {
                        if (r.runs.z == 0xFFFFFFFFu) {
                            uncExit = uExit;
                            return TRACE_UNCOVERED;
                        }
                        if (r.runs.z == 0xFFFFFFFEu) air = true;
                        else r = regions[r.runs.z - 1u];
                    }
                }
                float3 ro = (p0 - regionOriginRel(r)) / s;
                gLeafGap = lod <= 1 ? 0.3 : 0.0;
                if (!air && gGroups && r.groups.x != 0xFFFFFFFFu) {
                    // the chord's first cell: a ray that starts inside this level's solid (a switch to a coarser level)
                    // hits it at once, as the old path does (the groups hold only what a ray can reach from outside)
                    if (firstCell && r.bases.z != 0xFFFFFFFFu && traceRegion(r, ro, d / s, u, min(u + 1e-3, uExit), 1e-3, res)) {
                        res.lod = lod;
                        return TRACE_HIT;
                    }
                    if (traceGroups(r, ro, d / s, u, uExit, d, res)) {
                        res.lod = lod;
                        return TRACE_HIT;
                    }
                } else if (!air && r.bases.z != 0xFFFFFFFFu && traceRegion(r, ro, d / s, u, uExit, 1e-3, res)) {
                    res.lod = lod;
                    return TRACE_HIT;
                }
            }
        }
        firstCell = false;
        if (uExit >= len) break;
        // into the neighbour across the face we leave by, by index: re-flooring q a hair past that face is below
        // float precision in table units (a cell is 256 voxels), lands back in the same cell, and would re-trace its
        // region from outside it (clamped to its boundary layer: false hits one voxel past a region floor/ceiling)
        // (a ray leaving through an edge or a corner crosses every face it meets there at once: stepping one of them
        // left it in a cell it had already left, whose region the old path's clamp happened to answer for)
        float tm = min(tt.x, min(tt.y, tt.z));
        if (tt.x == tm) c.x += stepC.x;
        if (tt.y == tm) c.y += stepC.y;
        if (tt.z == tm) c.z += stepC.z;
        u = uExit;
    }
    return TRACE_MISS;
}

// Solid voxel at camera-relative point p in a given LOD (false where that LOD has no data).
bool occupiedAt(int lod, float3 p) {
    LodGpu L = frame.lods[lod];
    float s = float(1 << lod);
    int3 cell0 = L.origin.xyz * int(256 << lod) + int3(0, L.origin.w, 0);
    float3 q = (p - float3(cell0 - frame.camInt.xyz)) / s;
    int3 vq = int3(floor(q));
    int3 c = vq >> 8;
    if (any(c < int3(0, 0, 0)) || any(c >= L.dim.xyz)) return false;
    int idx = regionTable[L.dim.w + (c.y * L.dim.z + c.z) * L.dim.x + c.x];
    if (idx < 0) return false;
    RegionGpu r = regions[idx];
    if (r.bases.z == 0xFFFFFFFFu) return false;  // (a region with groups only)
    int3 v = vq & 255;
    uint ptr = r.bases.z;
    for (int size = 256; size > 4; size >>= 1) {
        int h = size >> 1;
        uint mask = nodes[r.bases.x + ptr] & 0xFFu;
        uint ci = ((v.x & h) != 0 ? 1u : 0u) | ((v.z & h) != 0 ? 2u : 0u) | ((v.y & h) != 0 ? 4u : 0u);
        if ((mask & (1u << ci)) == 0) return false;
        ptr = nodes[r.bases.x + ptr + 1 + countbits(mask & ((1u << ci) - 1u))];
    }
    uint64_t bits = leaves[r.bases.y + ptr];
    uint b = uint((v.x & 3) + 4 * (v.z & 3) + 16 * (v.y & 3));
    return ((bits >> b) & 1) != 0;
}

// (measurements) the material of the voxel path's solid voxel at camera-relative p in a LOD, 0xFFFFFFFF where empty
uint materialAt(int lod, float3 p) {
    LodGpu L = frame.lods[lod];
    float s = float(1 << lod);
    int3 cell0 = L.origin.xyz * int(256 << lod) + int3(0, L.origin.w, 0);
    int3 vq = int3(floor((p - float3(cell0 - frame.camInt.xyz)) / s));
    int3 c = vq >> 8;
    if (any(c < int3(0, 0, 0)) || any(c >= L.dim.xyz)) return 0xFFFFFFFFu;
    int idx = regionTable[L.dim.w + (c.y * L.dim.z + c.z) * L.dim.x + c.x];
    if (idx < 0) return 0xFFFFFFFFu;
    RegionGpu r = regions[idx];
    if (r.bases.z == 0xFFFFFFFFu) return 0xFFFFFFFFu;
    int3 v = vq & 255;
    uint ptr = r.bases.z, rank = 0u;
    for (int size = 256; size > 4; size >>= 1) {
        int h = size >> 1;
        uint mask = nodes[r.bases.x + ptr] & 0xFFu;
        uint ci = ((v.x & h) != 0 ? 1u : 0u) | ((v.z & h) != 0 ? 2u : 0u) | ((v.y & h) != 0 ? 4u : 0u);
        if ((mask & (1u << ci)) == 0) return 0xFFFFFFFFu;
        uint k = countbits(mask), j = countbits(mask & ((1u << ci) - 1u));
        rank += nodes[r.bases.x + ptr + 1 + k + j];
        ptr = nodes[r.bases.x + ptr + 1 + j];
    }
    uint64_t bits = leaves[r.bases.y + ptr];
    uint b = uint((v.x & 3) + 4 * (v.z & 3) + 16 * (v.y & 3));
    if (((bits >> b) & 1) == 0) return 0xFFFFFFFFu;
    rank += popcount64(bits & ((uint64_t(1) << b) - 1));
    return lookupMaterial(r, rank);
}

// Whether the far field holds a solid voxel at camera-relative p at the finest level with data there (-1: no level has
// data there, 0: empty, 1: solid).
int occupancyAt(float3 p) {
    int lodCount = min(int(frame.dims.w), 5);
    for (int lod = 0; lod < lodCount; lod++) {
        LodGpu L = frame.lods[lod];
        float s = float(1 << lod);
        int3 cell0 = L.origin.xyz * int(256 << lod) + int3(0, L.origin.w, 0);
        int3 c = int3(floor((p - float3(cell0 - frame.camInt.xyz)) / s)) >> 8;
        if (any(c < int3(0, 0, 0)) || any(c >= L.dim.xyz)) continue;
        int idx = regionTable[L.dim.w + (c.y * L.dim.z + c.z) * L.dim.x + c.x];
        if (idx == -2) continue;
        return occupiedAt(lod, p) ? 1 : 0;
    }
    return -1;
}

// Smoothed surface normal: the negated occupancy gradient by central differences (6 taps, stride `stride` voxels).
float3 smoothNormal(int lod, float3 centre, float stride) {
    float s = float(1 << lod) * stride;
    float3 g = float3(0, 0, 0);
    g.x = (occupiedAt(lod, centre + float3(s, 0, 0)) ? 1.0 : 0.0) - (occupiedAt(lod, centre - float3(s, 0, 0)) ? 1.0 : 0.0);
    g.y = (occupiedAt(lod, centre + float3(0, s, 0)) ? 1.0 : 0.0) - (occupiedAt(lod, centre - float3(0, s, 0)) ? 1.0 : 0.0);
    g.z = (occupiedAt(lod, centre + float3(0, 0, s)) ? 1.0 : 0.0) - (occupiedAt(lod, centre - float3(0, 0, s)) ? 1.0 : 0.0);
    float l = length(g);
    return l > 1e-4 ? -g / l : float3(0, 1, 0);
}

float3 worldAt(float3 o, float3 dir, float k, float t) {
    return o + dir * t + float3(0, k * t * t, 0);
}

// Trace the curved view ray from t0 to t1. Returns the hit distance along the view ray or -1.
// lodDither (0..1 per pixel, 0 = off): the segment grid of every LOD starts at a per-pixel phase. A ray changes LOD
// only at a segment start (the first segment that leaves a level's window, or whose footprint wants a coarser level),
// so with a fixed grid every pixel switches on the same jagged line; with the phase spread over one segment the switch
// becomes a stochastic band a segment wide (dithered LOD transitions, no pop line).
float traceView(float3 o, float3 dir, float t0, float t1, float lodDither, inout TraceResult res) {
    float R = frame.camFrac.w;
    float k = R > 0 ? dot(dir.xz, dir.xz) / (2.0 * R) : 0.0;
    float pixelAngle = frame.params.w * frame.params.z;
    int lodCount = int(frame.dims.w);
    float worldTop = 640.0 - float(frame.camInt.y);
    int lod = 0;
    float t = t0;
    bool finer = false;  // this segment is searching downwards for any LOD with data
    int pinned = -1;     // a finer LOD that had data while the wanted one did not (streaming), and for how long
    int pinLeft = 0;
    float gapEnd = 1e30;  // nearest end of an unloaded region cell seen while searching this segment
    int phasedLod = -1;   // the level whose segment grid was last phase-shifted (lodDither)
    float shrink = 0.0;   // fraction cut off the current segment (its grid's phase)
    for (int seg = 0; seg < 768; seg++) {
        if (t >= t1) break;
        // (the groups choose their own level per node, draw-or-open: the finest level resident is the best source)
        int want = gGroups ? 0 : int(clamp(floor(log2(max(t * pixelAngle, 1.0))), 0.0, float(lodCount - 1)));
        if (!finer) lod = pinLeft > 0 ? pinned : max(lod, want);
        float s = float(1 << lod);
        if (lodDither > 0.0 && lod != phasedLod) {
            phasedLod = lod;
            shrink = 0.9 * frac(lodDither + 0.618034 * float(lod));
        }
        // (groups: longer chords, each one re-enters a region's tree from its root; the chord's sag is still a few
        // hundredths of a block at the real radius)
        float tn = min(t + (gGroups ? 768.0 : 192.0) * s * (1.0 - shrink), t1);
        float3 a = worldAt(o, dir, k, t);
        float3 b = worldAt(o, dir, k, tn);
        // rising above the world top: the parabola only climbs further from here
        if (a.y > worldTop && dir.y + 2.0 * k * t >= 0.0) break;
        float len = tn - t;
        float3 d = (b - a) / len;
        float uncExit;
        gChordT = t;
        int r = traceLod(lod, a, d, len, res, uncExit);
        if (r == TRACE_HIT) return t + res.t;
        if (r == TRACE_UNCOVERED) {
            res.axis = -1;
            pinLeft = 0;
            // chord parameter equals view distance only to O(k): fine for choosing where to resume
            gapEnd = min(gapEnd, uncExit < 1e29 ? t + uncExit : 1e30);
            // coarser first (cheaper, and it is what this distance wants); with nothing coarser, settle for finer
            // data rather than a hole while tiles are still streaming in
            if (!finer && lod + 1 < lodCount) { lod++; continue; }
            if (!finer) {
                lod = min(lod, want);  // levels from here up were all just found empty-handed
                finer = true;
            }
            if (lod > 0) {
                lod--;
                continue;
            }
            // no LOD has this stretch (a streaming gap): resume where the nearest unloaded cell ends and look for
            // land loaded behind it; when every LOD has left its window there is nothing further at all
            if (gapEnd >= 1e29) break;
            t = min(max(gapEnd, t) + 1e-3, t1);
            gapEnd = 1e30;
            finer = false;
            lod = want;
            continue;
        }
        // this segment had data at `lod` and missed; when that took a downward search, stay on that level for a
        // few segments instead of repeating the search every segment
        if (finer) { pinned = lod; pinLeft = 8; }
        else if (pinLeft > 0) pinLeft--;
        t = tn;
        shrink = 0.0;
        finer = false;
        gapEnd = 1e30;
    }
    return -1.0;
}

// Straight ray (sun shadows): 1 if lit.
float traceShadow(float3 p, float3 dir, int lod) {
    TraceResult res;
    res.t = 0; res.mat = 0; res.axis = -1; res.lod = 0; res.steps = 0; res.dither = 0.5; res.albedo = float3(0, 0, 0); res.hasAlbedo = 0u;
    int lodCount = int(frame.dims.w);
    float t = 0;
    float maxT = 2048.0;
    for (int seg = 0; seg < 48; seg++) {
        if (t >= maxT) break;
        float s = float(1 << lod);
        float len = min(192.0 * s, maxT - t);
        float uncExit;
        int r = traceLod(lod, p + dir * t, dir, len, res, uncExit);
        if (r == TRACE_HIT) return 0.0;
        if (r == TRACE_UNCOVERED) {
            if (lod + 1 < lodCount) { lod++; continue; }
            break;
        }
        t += len;
        if (p.y + dir.y * t > 640.0 - float(frame.camInt.y)) break;
    }
    return 1.0;
}

// A secondary ray from camera-relative p along unit dir, at most maxT blocks: what passes (0 when something solid is in
// the way, foliage attenuating it on the way; gThrough what the pack's light through leaves keeps). Coarser levels
// where the hit's level has no data, as traceShadow; gStopFoliage: stopped by foliage alone (its light still comes
// through for FOLIAGE_SSS).
static bool gStopFoliage = false;
float traceSecondary(float3 p, float3 dir, float maxT, int lod) {
    TraceResult res;
    res.t = 0; res.mat = 0; res.axis = -1; res.lod = 0; res.steps = 0; res.dither = 0.5; res.albedo = float3(0, 0, 0); res.hasAlbedo = 0u;
    int lodCount = int(frame.dims.w);
    bool prev = gSecondary;
    gSecondary = true;
    gTrans = 1.0;
    gThrough = 0.0;
    gStopFoliage = false;
    float t = 0;
    float vis = 1.0;
    for (int seg = 0; seg < 48; seg++) {
        if (t >= maxT) break;
        float s = float(1 << lod);
        float len = min(192.0 * s, maxT - t);
        float uncExit;
        gSecT = t;
        int r = traceLod(lod, p + dir * t, dir, len, res, uncExit);
        if (r == TRACE_HIT) {
            gStopFoliage = gTrans <= 0.0;
            vis = 0.0;
            break;
        }
        if (r == TRACE_UNCOVERED) {
            if (lod + 1 < lodCount) { lod++; continue; }
            break;
        }
        t += len;
        if (p.y + dir.y * t > 640.0 - float(frame.camInt.y)) break;
    }
    gSecondary = prev;
    if (vis <= 0.0) {
        if (!gStopFoliage) gThrough = 0.0;
        gTrans = 0.0;
        return 0.0;
    }
    gThrough += gTrans;  // what crossed every leaf: exp(-0.45 x) of no first leaf at all is 1
    return gTrans;
}

// Cosine-weighted direction about n from (u, v) in [0, 1)^2 (Duff et al. 2017 basis, as atlas_truth's)
float3 cosineDir(float3 n, float u, float v) {
    float sg = n.z >= 0.0 ? 1.0 : -1.0;
    float a = -1.0 / (sg + n.z), c = n.x * n.y * a;
    float3 tb = float3(1.0 + sg * n.x * n.x * a, sg * c, -sg * n.x);
    float3 bb = float3(c, sg + n.y * n.y * a, -n.y);
    float r = sqrt(u), ph = 6.2831853 * v;
    return normalize(tb * (r * cos(ph)) + bb * (r * sin(ph)) + n * sqrt(max(0.0, 1.0 - u)));
}

// The sky a far surface sees (docs/ATLAS-LOD.md, "Where atlas_core's lighting falls short" 1): the pack's skyAmbient
// split into its two radiances, sky over the horizon and ground under it, each seen through the hemisphere's
// visibility: SKY_RAYS cosine-weighted secondary rays a pixel and frame, SKY_REACH voxels of the hit's level long (a
// cone that widens with the level, so far away valleys and ridges darken, up close eaves and crevices), stratified
// over the TAA's frames (R2 per pixel rotation). The constant FAR_AMBIENT_OCCLUSION and the 4-tap AO stand in when
// the frame asks for cheap light (FRAME_LIGHT_LOW), as before.
static const int SKY_RAYS = 1;
static const float SKY_REACH = 16.0;
float3 skyVisible(uint2 px, float3 o, float3 faceN, int lod, float3 sun, out float vis) {
    float day = smoothstep(-0.12, 0.25, sun.y);
    float3 skyUp = lerp(float3(0.02, 0.025, 0.05), float3(0.33, 0.45, 0.7), day);
    float3 ground = lerp(float3(0.01, 0.01, 0.012), float3(0.2, 0.18, 0.14), day);
    uint fi = frame.jitter.z > 0.5 ? (frame.temporal.x & 15u) : 0u;  // a 16-frame cycle: a still pixel converges to one stratified set
    float ru = pixelDither(px, 11u), rv = pixelDither(px, 13u);
    float s = float(1 << lod);
    uint ab = asuint(frame.jitter.w);  // (ATLAS_ABLATE 8192: two rays, 16384: half the reach; measurements)
    int rays = (ab & 8192u) != 0u ? 2 : SKY_RAYS;
    float reach = (ab & 16384u) != 0u ? 0.5 * SKY_REACH : SKY_REACH;
    float3 sum = float3(0, 0, 0);
    vis = 0.0;
    for (int k = 0; k < rays; k++) {
        uint j = fi * uint(rays) + uint(k);
        float u = frac(ru + float(j) * 0.7548776662), v = frac(rv + float(j) * 0.5698402909);
        float3 w = cosineDir(faceN, u, v);
        float T = traceSecondary(o, w, reach * s, lod);
        sum += (w.y >= 0.0 ? skyUp : ground) * (1.0 - T);
        vis += T;
    }
    vis /= float(rays);
    // a control variate: the open hemisphere's light is known exactly (skyAmbient: the cosine-weighted split of sky
    // and ground radiance about the face), the rays only estimate what is taken from it, so an open face (most of the
    // far field) is exact and noise-free, and only occluded ones carry the rays' noise
    return max(lerp(ground, skyUp, faceN.y * 0.5 + 0.5) - sum / float(rays), float3(0, 0, 0));
}

// Minecraft's own look without a shader pack (FRAME_VANILLA_LIGHT: the near field is vanilla, the far field must read
// the same at the hand-off): the texel times the fixed face shade (down 0.5, up 1, north / south 0.8, west / east 0.6)
// times the lightmap at full sky light for the time of day (1 by day; at night the sky factor 0.24 under the moon's
// blue, through the brightness option's 0.5 gamma: about 0.28, 0.28, 0.5, as atlas_truth's lightmap gives); emitters
// stay at full brightness. No sun shadows, sky visibility, light through leaves or haze: vanilla has none of them.
float3 vanillaLit(float3 albedo, float3 faceN, uint cls, float3 sun) {
    float shade = faceN.y > 0.5 ? 1.0 : faceN.y < -0.5 ? 0.5 : abs(faceN.z) > 0.5 ? 0.8 : 0.6;
    float3 sky = lerp(float3(0.28, 0.28, 0.5), float3(1.0, 1.0, 1.0), smoothstep(-0.2, 0.15, sun.y));
    return cls == 3u ? albedo : albedo * shade * sky;
}

// Far water continues the shader pack's waves (shaderpack lib/waves.glsl waveField at its default 12 waves, open
// water): the same wind-aligned spread table, spectrum, wave-group envelope, drag and footprint fade, so the swell runs
// on across the near/far hand-off until it shrinks under a few pixels. The wind (heading, windiness) comes from the
// same weather columns as the pack's lib/weather.glsl waterWind. What is no longer drawn becomes the glint's
// roughness (farWaterAlpha).
static const int FAR_WAVES = 12;
static const float WAVE_SPREAD[16] = { 0.0, 0.35, -0.42, 0.6, -0.7, 0.18, -0.25, 0.85, -0.9, 0.5, -0.55, 0.05,
                                       0.95, -0.38, 0.72, -0.8 };

float waterFootprint(float dist) { return 0.02 + dist * 0.0013; }

// the pack's lib/noise.glsl hash12 / valueNoise, so the wave-group envelope matches across the hand-off
float packHash12(float2 p) {
    float3 p3 = frac(float3(p.x, p.y, p.x) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return frac((p3.x + p3.y) * p3.z);
}

float packValueNoise(float2 p) {
    float2 i = floor(p), f = frac(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = packHash12(i), b = packHash12(i + float2(1, 0)), c = packHash12(i + float2(0, 1)), d = packHash12(i + float2(1, 1));
    return lerp(lerp(a, b, u.x), lerp(c, d, u.x), u.y);
}

// (heading radians, windiness 0..1) over far water: lib/weather.glsl waterWind
float2 farWaterWind(float2 worldXZ, float horizontal) {
    CloudColumn c = sampleColumn(worldXZ, horizontal);
    float windiness = saturate(0.18 + 0.5 * c.weather.g + 0.35 * c.weather.b + 0.15 * c.weather.r + 0.25 * frame.weatherParams.x);
    return float2(c.dynamics.b * 6.2831853, windiness);
}

float farWaveLongest(float windiness) { return 26.0 * (0.6 + 0.8 * windiness); }
float farWaveAmp(float windiness) { return 0.07 * (0.35 + 1.9 * windiness); }

float3 farWaveNormal(float2 p, float time, float2 wind, float dist) {
    float wl = farWaveLongest(wind.y);
    float decay = pow(0.7 / wl, 1.0 / float(FAR_WAVES - 1));
    float amp = farWaveAmp(wind.y) * (0.55 + 0.9 * (packValueNoise(p / 170.0 + time * 0.015) * 0.65 +
                                                     packValueNoise(p / 61.0 - time * 0.03) * 0.35));
    float foot = waterFootprint(dist);
    float2 g = float2(0, 0);
    for (int i = 0; i < FAR_WAVES; i++) {
        float fade = smoothstep(3.0 * foot, 8.0 * foot, wl);
        if (fade <= 0.0) break;
        float spread = 0.25 + 0.75 * float(i) / float(FAR_WAVES - 1);
        float ang = wind.x + WAVE_SPREAD[i] * spread * 1.5707963;
        float2 d = float2(cos(ang), sin(ang));
        float kk = 6.2831853 / wl;
        float x = dot(d, p) * kk - time * sqrt(9.81 * kk);
        float e = exp(sin(x) - 1.0);
        float de = e * cos(x);
        float a = amp * fade;
        g += a * de * kk * d;
        p -= d * de * a * 0.38 * 6.0;
        wl *= decay;
        amp *= decay;
    }
    return normalize(float3(-g.x, 1.0, -g.y));
}

// GGX-like alpha of the glint: the rms slope of the waves too small to draw at this distance (lib/waves.glsl
// waveRoughness with openness 1)
float farWaterAlpha(float windiness, float dist) {
    float foot = waterFootprint(dist);
    float wl = farWaveLongest(windiness);
    float decay = pow(0.7 / wl, 1.0 / float(FAR_WAVES - 1));
    float slope = farWaveAmp(windiness) * 6.2831853 / wl * 0.6;
    float lost = 0.0;
    for (int i = 0; i < FAR_WAVES; i++) {
        lost += 1.0 - smoothstep(3.0 * foot, 8.0 * foot, wl);
        wl *= decay;
    }
    float alpha = sqrt(0.00005 + 2.0 * slope * slope * lost) + windiness * windiness * 0.03;
    return clamp(alpha, 0.005, 0.36);
}

// the sky a far water surface mirrors, with the cloud deck in it: the reflected ray meets the cloud base, where the
// weather columns say how much cloud there is (cheap: no march, the deck's broad shapes only)
float3 reflectedSky(float3 p, float3 r, float3 sun) {
    float3 sky = skyColor(r, sun);
    if ((frame.dims.z & FRAME_CLOUDS) == 0 || r.y < 0.02) return sky;
    float3 ci = float3(frame.camInt.xyz);
    float y = p.y + ci.y;
    CloudColumn here = sampleColumn(p.xz + ci.xz, length(p.xz));
    float base = here.dynamics.r * 1024.0;
    float t = max(base - y, 1.0) / r.y;
    if (t > 30000.0) return sky;
    float2 q = p.xz + r.xz * t;
    CloudColumn c = sampleColumn(q + ci.xz, length(q));
    float cov = c.weather.r + 0.5 * c.weather.g;
    float breakup = valueNoise((q + ci.xz) / 380.0) * 0.6 + valueNoise((q + ci.xz) / 130.0 + 7.0) * 0.4;
    float dens = smoothstep(0.3, 0.75, cov + (breakup - 0.5) * 0.6) * (1.0 - smoothstep(9000.0, 30000.0, t));
    float3 up = float3(0, 1, 0);
    float3 cloud = keyLightRadiance(sun) * 0.28 * (1.0 - 0.6 * c.weather.g) + skyAmbient(up, sun) * 0.85;
    return lerp(sky, cloud, dens * 0.85);
}

// What a far water surface mirrors: a straight reflected ray through the same scene (coarser LOD, no shadows), shaded
// simply, else the sky with its clouds. Only water pixels pay for it.
float3 waterReflection(float3 p, float3 dir, float k, float t, int lod, float3 sun, float3 wn) {
    float3 slope = normalize(dir + float3(0, 2.0 * k * t, 0));
    float3 r = reflect(slope, wn);
    r.y = max(r.y, 0.005);
    r = normalize(r);
    float3 sky = reflectedSky(p, r, sun);
    if (r.y > 0.35) return sky;  // steep reflections only see sky
    TraceResult rr;
    rr.t = 0; rr.mat = 0; rr.axis = -1; rr.lod = 0; rr.steps = 0; rr.dither = 0.5; rr.albedo = float3(0, 0, 0); rr.hasAlbedo = 0u;
    int lodCount = int(frame.dims.w);
    int l = min(lod + 1, lodCount - 1);
    float s = float(1 << lod);
    float3 o = p + float3(0, 1.01 * float(1 << l), 0);  // clear the trace LOD's (coarser) voxel, not only the hit's
    float tr = 0;
    float maxT = 12000.0;
    for (int seg = 0; seg < 48; seg++) {
        if (tr >= maxT) break;
        float len = min(192.0 * float(1 << l), maxT - tr);
        float uncExit;
        int hit = traceLod(l, o + r * tr, r, len, rr, uncExit);
        if (hit == TRACE_HIT) {
            MaterialGpu m = palette[rr.mat];
            // a grazing reflection that skims back into the water sees the water's own sky, not a dark "top"
            if (uint(m.emisClass.y) == 2u) return sky;
            float3 n = float3(0, 1, 0);  // shaded as tops: soft and cheap at reflection scale
            float3 c = m.albedoRough.rgb * (keyLightRadiance(sun) * saturate(dot(n, keyLightDir(sun))) * 0.8 + skyAmbient(n, sun));
            return applyAerial(c, r, sun, tr + rr.t, frame);
        }
        if (hit == TRACE_UNCOVERED) {
            if (l + 1 < lodCount) { l++; continue; }
            break;
        }
        tr += len;
        if (o.y + r.y * tr > 640.0 - float(frame.camInt.y)) break;
    }
    return sky;
}

// ---- far lights (lights.cpp packs them after the far actors, then 32 px tile lists) -------------------------------

// Word k (0..11) of record `rec` of the actor buffer, read as an unsigned integer (the tile tables).
uint actorWord(uint rec, uint k) {
    ActorGpu a = actors[rec];
    float v = k < 4u ? a.posYaw[k] : k < 8u ? a.shape[k - 4u] : a.motion[k - 8u];
    return asuint(v);
}

// The glow of the far lights over this pixel: each light of the pixel's tile that nothing nearer hides (its distance
// against the pixel's surface, with a voxel of slack) adds a core about a pixel wide (or its own size up close) and a
// halo that widens with the haze between it and the camera. Town lights show from dusk to dawn, fires flicker, lava
// pulses, spray is lit by the sun and sky.
float3 farLights(uint2 px, float3 o, float3 dir, float surface, float3 sun) {
    uint L = uint(frame.weatherParams.y);
    if (L == 0u) return float3(0, 0, 0);
    uint A = uint(frame.weatherParams.w);
    uint tilesX = (frame.dims.x + 31u) / 32u, tilesY = (frame.dims.y + 31u) / 32u;
    uint tile = (px.y / 32u) * tilesX + px.x / 32u;
    uint tableBase = A + L, indexBase = tableBase + (tilesX * tilesY + 5u) / 6u;
    uint start = actorWord(tableBase + tile / 6u, (tile % 6u) * 2u);
    uint count = min(actorWord(tableBase + tile / 6u, (tile % 6u) * 2u + 1u), 64u);
    float night = smoothstep(0.12, -0.1, sun.y);
    float day = smoothstep(-0.12, 0.2, sun.y);
    float pa = max(frame.params.w, 1e-5);
    float t = frame.sunDir.w;
    float3 sum = float3(0, 0, 0);
    for (uint k = 0u; k < count; k++) {
        uint e = start + k;
        uint li = actorWord(indexBase + e / 12u, e % 12u);
        ActorGpu l = actors[A + li];
        float dist = l.motion.x;
        float slack = max(3.0, dist * pa * 3.0) + l.motion.w;
        if (surface < dist - slack) continue;  // terrain, a building or an actor in front of it
        uint kind = uint(l.shape.w + 0.5);
        float vis = kind == 0u ? night
                  : kind == 1u ? (0.1 + 0.9 * night) * (0.82 + 0.18 * sin(t * 11.0 + l.motion.y * 7.0) * sin(t * 4.3 + l.motion.y))
                  : kind == 2u ? 0.85 + 0.15 * sin(t * 1.3 + l.motion.y)
                  : day;
        if (vis <= 0.001) continue;
        float3 v = l.posYaw.xyz - o;
        float c = dot(dir, v) / max(length(v), 1e-3);
        float apx = sqrt(max(0.0, 2.0 * (1.0 - c))) / pa;
        float sigma = l.posYaw.w, halo = l.motion.z;
        float T = aerialTransmittance(dist, frame);
        float q = apx / sigma;
        float core = exp(-0.5 * q * q);
        // the glow stands for an emitter the far field cannot draw (a far town's windows and lamps, a block lost in a
        // coarse level); where the far field holds the emitter's own block, its emissive surface draws it, as Minecraft
        // draws a lantern near, and a glow over it counted its light twice (a 1.5-block ball at every lamp up close).
        // Only pixels inside the core ask (a few per light).
        if (kind != 3u && core > 1e-3 && occupancyAt(l.posYaw.xyz) == 1) core = 0.0;
        float glow = (1.0 - T) * 0.06 * exp(-apx / halo);
        float3 rad = l.shape.xyz;
        // spray: a translucent haze lit by the sun and sky, not a light: its intensity is how much of it there is
        // (about a third of a lit white surface at full), without the far-visibility boost point lights get
        if (kind == 3u) rad = (l.shape.xyz / (6.0 / pow(1.0 + dist / 1500.0, 1.5))) * 0.35
                            * (keyLightRadiance(sun) * 0.25 + skyAmbient(float3(0, 1, 0), sun)) / 3.14159;
        sum += rad * vis * (core * T + glow);
    }
    return sum;
}

// The far lights on the surfaces around them as point lights (docs/ATLAS-LOD.md, "Where atlas_core's lighting falls
// short" 3): each light of the pixel's tile within Minecraft's reach R = level + 1 lights it with
// E = I cos / (d^2 + 0.5^2) x (1 - (d / R)^4)^2, I = 9.4 (level / 15)^2 (the realistic reference's point light, matched
// to the pack's block-light curve at 3 - 8 blocks from a level-15 light), the cosine both ways on foliage, and its
// shadow: a secondary ray to a point of the emitter (moving with the TAA's frames: soft), stopping short of the
// emitter's own voxel. In the pack's warm block-light hue (lib/lighting.glsl blockLight; the game sends every real
// chunk's emitter as an orange fire, whose colour is its glow's), lava in its own colour at that luminance. Fires and
// lava light by day as by night (block light is not a time of day); lamps (windows, lanterns) from dusk to dawn.
// FRAME_LIGHT_LOW: unshadowed. p: the hit, camera-relative to camInt; so: the secondary rays' origin off its face.
static const float3 BLOCK_LIGHT_HUE = float3(1.0, 0.62, 0.32);
float3 farLightsOn(uint2 px, float3 p, float3 so, float3 faceN, int lod, float3 sun, bool fol, bool cheap) {
    uint L = uint(frame.weatherParams.y);
    if (L == 0u) return float3(0, 0, 0);
    uint A = uint(frame.weatherParams.w);
    uint tilesX = (frame.dims.x + 31u) / 32u, tilesY = (frame.dims.y + 31u) / 32u;
    uint tile = (px.y / 32u) * tilesX + px.x / 32u;
    uint tableBase = A + L, indexBase = tableBase + (tilesX * tilesY + 5u) / 6u;
    uint start = actorWord(tableBase + tile / 6u, (tile % 6u) * 2u);
    uint count = min(actorWord(tableBase + tile / 6u, (tile % 6u) * 2u + 1u), 64u);
    float night = smoothstep(0.12, -0.1, sun.y);
    uint fi = frame.jitter.z > 0.5 ? (frame.temporal.x & 15u) : 0u;  // a 16-frame cycle: a still pixel converges to one stratified set
    float s = float(1 << lod);
    float3 sum = float3(0, 0, 0);
    for (uint k = 0u; k < count; k++) {
        uint e = start + k;
        uint li = actorWord(indexBase + e / 12u, e % 12u);
        ActorGpu l = actors[A + li];
        uint kind = uint(l.shape.w + 0.5);
        if (kind == 3u) continue;  // spray is lit, it lights nothing
        float vis = kind == 0u ? night : 1.0;
        if (vis <= 0.001) continue;
        // the record's radiance is scaled for the glow (6 x intensity x falloff(dist), lights.cpp): undo it
        float fall = 1.0 / pow(1.0 + l.motion.x / 1500.0, 1.5);
        float3 base = l.shape.xyz / (6.0 * fall);
        float inten = max(base.r, max(base.g, base.b));
        if (inten <= 1e-4) continue;
        float level = 15.0 * min(inten, 1.0) + (kind == 2u ? l.motion.w : 0.0);
        float R = level + 1.0;
        float3 v = l.posYaw.xyz - p;
        float d = length(v);
        if (d >= R || d < 1e-3) continue;
        float c = dot(faceN, v / d);
        if (fol) c = abs(c);
        if (c <= 0.0) continue;
        float x = d / R, x4 = x * x * x * x;
        float E = 9.4 * (level / 15.0) * (level / 15.0) * c / (d * d + 0.25) * (1.0 - x4) * (1.0 - x4);
        float occ = 1.0;
        if (!cheap) {
            // a point of the emitter (a lantern's size), a new one each TAA frame
            uint hh = hashU(li * 2654435761u ^ (fi * 374761393u) ^ (px.x * 73856093u) ^ (px.y * 19349663u));
            float3 jit = float3(float(hh & 1023u), float((hh >> 10) & 1023u), float((hh >> 20) & 1023u)) / 1023.0 - 0.5;
            float3 dv = l.posYaw.xyz + jit * 0.35 - so;
            float dd = length(dv);
            float maxT = dd - 0.75 * s;  // short of the emitter's own voxel (solid in the voxel path)
            if (maxT > 0.05) occ = traceSecondary(so, dv / dd, maxT, lod);
        }
        float3 hue = BLOCK_LIGHT_HUE;
        if (kind == 2u) {
            float3 h = base / inten;
            hue = h * (dot(BLOCK_LIGHT_HUE, float3(0.2126, 0.7152, 0.0722)) / max(dot(h, float3(0.2126, 0.7152, 0.0722)), 1e-4));
        }
        sum += hue * (E * vis * occ);
    }
    return sum;
}

uint cloudDiv() { return (frame.dims.z & FRAME_CLOUDS_QUARTER) != 0 ? 4u : 2u; }
uint2 cloudDims() { uint d = cloudDiv(); return uint2((frame.dims.x + d - 1) / d, (frame.dims.y + d - 1) / d); }

// Bilinear upsample of the half-resolution deck at full-resolution pixel `px`; depth = nearest of the four taps.
void sampleClouds(uint2 px, out float4 c, out float depth) {
    uint2 cd = cloudDims();
    float2 hp = (float2(px) + 0.5) / float(cloudDiv()) - 0.5;
    int2 b = int2(floor(hp));
    float2 f = hp - float2(b);
    int2 b0 = clamp(b, int2(0, 0), int2(cd) - 1), b1 = clamp(b + 1, int2(0, 0), int2(cd) - 1);
    uint i00 = uint(b0.y) * cd.x + uint(b0.x), i10 = uint(b0.y) * cd.x + uint(b1.x);
    uint i01 = uint(b1.y) * cd.x + uint(b0.x), i11 = uint(b1.y) * cd.x + uint(b1.x);
    c = lerp(lerp(cloudBuf[i00], cloudBuf[i10], f.x), lerp(cloudBuf[i01], cloudBuf[i11], f.x), f.y);
    depth = min(min(cloudDepth[i00], cloudDepth[i10]), min(cloudDepth[i01], cloudDepth[i11]));
}

// The cloud shell at half resolution: one ray per 2x2 block, marched up to the block's nearest surface of the last
// frame (so clouds behind a mountain never paint over it), haze applied, stored premultiplied for the far pass.
[shader("compute")]
[numthreads(8, 8, 1)]
void cloudMain(uint3 tid : SV_DispatchThreadID) {
    gMoonDir = frame.moonDir;
    gMoonRad = frame.moonRad;
    uint2 cd = cloudDims();
    // temporal amortisation: a fresh ray for one pixel of every 2x2 block per frame (rotating); the others reproject
    // last frame's deck through its camera. Off-screen reprojections and frames without history march fresh.
    // With history, threads are dealt out by quadrant (the first quadrant takes every block's fresh pixel, the other
    // three its reprojected ones), so a warp is all marching or all copying instead of waiting on one ray in four.
    bool history = frame.temporal.y != 0u;
    uint fresh = frame.temporal.x & 3u;
    uint2 id = tid.xy;
    uint sub = fresh;
    if (history) {
        uint2 hc = (cd + 1u) / 2u;
        uint2 q = uint2(tid.x >= hc.x ? 1u : 0u, tid.y >= hc.y ? 1u : 0u);
        uint2 l = tid.xy - q * hc;
        if (l.x >= hc.x || l.y >= hc.y) return;
        sub = (q.x | (q.y << 1)) ^ fresh;
        id = l * 2u + uint2(sub & 1u, sub >> 1);
    }
    if (id.x >= cd.x || id.y >= cd.y) return;
    uint w = frame.dims.x, h = frame.dims.y;
    uint ci = id.y * cd.x + id.x;
    uint dv = cloudDiv();
    uint2 full = min(id.xy * dv, uint2(w - 1, h - 1));
    float2 ndc = float2((float(id.x * dv) + 0.5 * float(dv)) / float(w) * 2.0 - 1.0,
                        1.0 - (float(id.y * dv) + 0.5 * float(dv)) / float(h) * 2.0);
    float3 dir = normalize(unproject(frame, ndc));
    float3 o = frame.camFrac.xyz;
    float3 sun = frame.sunDir.xyz;
    float limit = frame.params.y;
    if (history && sub != fresh) {
        float pd = prevCloudDepth[ci];
        // an empty texel is reprojected at the terrain behind it (from above that is a few hundred blocks, not the
        // 20000 assumed before: gaps pulled cloud colour in and edges trailed while flying)
        float behind = prevSurface[full.y * w + full.x];
        float dd = pd < 1e29 ? pd : (behind < 1e29 ? min(behind, limit) : 20000.0);
        float3 X = o + dir * dd - frame.prevCam.xyz;
        float4 clip = frame.prevViewProj[0] * X.x + frame.prevViewProj[1] * X.y + frame.prevViewProj[2] * X.z + frame.prevViewProj[3];
        if (clip.w > 1e-4) {
            float2 pn = clip.xy / clip.w;
            float hx = ((pn.x * 0.5 + 0.5) * float(w) - 0.5 * float(dv)) / float(dv);
            float hy = ((0.5 - pn.y * 0.5) * float(h) - 0.5 * float(dv)) / float(dv);
            if (hx >= 0.0 && hy >= 0.0 && hx <= float(cd.x - 1u) && hy <= float(cd.y - 1u)) {
                // a reprojection that lands on a texel (still camera, pure translation by whole texels) takes it as-is
                // rather than blending float-rounding fractions of its neighbours in: a still deck stays bit-stable
                float2 hr = round(float2(hx, hy));
                if (all(abs(float2(hx, hy) - hr) < 0.02)) {
                    uint ih = uint(hr.y) * cd.x + uint(hr.x);
                    cloudBuf[ci] = prevCloudBuf[ih];
                    cloudDepth[ci] = prevCloudDepth[ih];
                    return;
                }
                int2 b = int2(floor(float2(hx, hy)));
                float2 fr = float2(hx, hy) - float2(b);
                int2 b1 = min(b + 1, int2(cd) - 1);
                uint i00 = uint(b.y) * cd.x + uint(b.x), i10 = uint(b.y) * cd.x + uint(b1.x);
                uint i01 = uint(b1.y) * cd.x + uint(b.x), i11 = uint(b1.y) * cd.x + uint(b1.x);
                // at a cloud edge the four disagree (cloud / no cloud): blending them is the screen-door fringe;
                // march this texel fresh instead
                uint n = (prevCloudDepth[i00] < 1e29 ? 1u : 0u) + (prevCloudDepth[i10] < 1e29 ? 1u : 0u)
                       + (prevCloudDepth[i01] < 1e29 ? 1u : 0u) + (prevCloudDepth[i11] < 1e29 ? 1u : 0u);
                if (n == 0u || n == 4u) {
                    cloudBuf[ci] = lerp(lerp(prevCloudBuf[i00], prevCloudBuf[i10], fr.x), lerp(prevCloudBuf[i01], prevCloudBuf[i11], fr.x), fr.y);
                    cloudDepth[ci] = min(min(prevCloudDepth[i00], prevCloudDepth[i10]), min(prevCloudDepth[i01], prevCloudDepth[i11]));
                    return;
                }
            }
        }
    }
    uint x1 = min(full.x + dv - 1u, w - 1u), y1 = min(full.y + dv - 1u, h - 1u);
    limit = min(limit, min(min(prevSurface[full.y * w + full.x], prevSurface[full.y * w + x1]),
                           min(prevSurface[y1 * w + full.x], prevSurface[y1 * w + x1])));
    CloudResult cl = marchClouds(o, dir, limit, sun, id.xy);
    float4 c = float4(0, 0, 0, 1);
    float d = 1e30;
    if (cl.firstHit >= 0.0) {
        // the deck's own aerial perspective: distant storms fade into the horizon haze like the terrain does
        // (whatever is behind the cloud already carries its own haze)
        float trA = aerialTransmittance(cl.firstHit, frame);
        c.rgb = cl.scatter * trA + aerialFog(dir, sun) * (1.0 - trA) * (1.0 - cl.transmittance);
        c.a = cl.transmittance;
        d = cl.transmittance < 0.995 ? cl.firstHit : 1e30;
    }
    // rain shafts under the deck, composited with it in depth order
    RainResult rn = marchRain(o, dir, limit, sun, id.xy);
    if (rn.firstHit >= 0.0 && rn.alpha > 0.002) {
        if (cl.firstHit < 0.0 || rn.firstHit < cl.firstHit) c.rgb = rn.color + (1.0 - rn.alpha) * c.rgb;
        else c.rgb = c.rgb + c.a * rn.color;
        c.a *= 1.0 - rn.alpha;
        d = min(d, rn.firstHit);
    }
    cloudBuf[ci] = c;
    cloudDepth[ci] = d;
}

// One texel of the sun map: the sun line through the map plane at (u, v), traced from the world top down along the
// key light (straight: the map spans a few km, where curvature is a block), coarsest LOD that resolves a texel and
// coarser where that one has no data. Returns the first occluder's coordinate along L from the map centre
// (-1e30: none) and the cloud transmittance towards the light there.
float2 sunTexel(uint i, uint j) {
    float S = float(frame.sunMapRows.z), he = frame.sunMapCentre.w;
    float3 L = frame.sunMapL.xyz, X = frame.sunMapX.xyz, Y = frame.sunMapY.xyz, c = frame.sunMapCentre.xyz;
    float u = ((float(i) + 0.5) / S * 2.0 - 1.0) * he, v = ((float(j) + 0.5) / S * 2.0 - 1.0) * he;
    float3 P = c + X * u + Y * v;
    bool clouds = (frame.dims.z & FRAME_CLOUDS) != 0;
    if (L.y < 0.01) return float2(-1e30, 1.0);  // the light at the horizon: no map
    float top = 641.0 - float(frame.camInt.y), bottom = -64.0 - float(frame.camInt.y);
    float3 O = P + L * ((top - P.y) / L.y);
    float3 d = -L;
    float maxT = min((top - bottom) / L.y, 30000.0);
    int lodCount = int(frame.dims.w);
    int l = clamp(int(floor(log2(max(2.0 * he / S, 1.0)))), 0, lodCount - 1);
    TraceResult rr;
    rr.t = 0; rr.mat = 0; rr.axis = -1; rr.lod = 0; rr.steps = 0; rr.dither = 0.5; rr.albedo = float3(0, 0, 0); rr.hasAlbedo = 0u;
    float t = 0;
    for (int seg = 0; seg < 128; seg++) {
        if (t >= maxT) break;
        float s = float(1 << l);
        float len = min(192.0 * s, maxT - t);
        float uncExit;
        int hit = traceLod(l, O + d * t, d, len, rr, uncExit);
        if (hit == TRACE_HIT) {
            float3 hp = O + d * (t + rr.t);
            return float2(dot(hp - c, L), clouds ? cloudShadow(hp, L) : 1.0);
        }
        if (hit == TRACE_UNCOVERED) {
            if (l + 1 < lodCount) { l++; continue; }
            t += uncExit < 1e29 ? max(uncExit, s) : len;  // nothing at any LOD here: across it
            continue;
        }
        t += len;
    }
    float3 g = P + L * ((63.0 - float(frame.camInt.y) - P.y) / L.y);
    return float2(-1e30, clouds ? cloudShadow(g, L) : 1.0);
}

// The sun map for the near field (the pack shadows the rasterised world with it beyond its own shadow map's
// reach: mountains over valleys, cloud shadows): rows sunMapRows.x .. +y (wrapping), refreshed progressively;
// thread row y == count writes the parameter row: centre (world), half extent, X, Y, L.
[shader("compute")]
[numthreads(8, 8, 1)]
void sunMapMain(uint3 id : SV_DispatchThreadID) {
    gMoonDir = frame.moonDir;
    gMoonRad = frame.moonRad;
    uint S = frame.sunMapRows.z, count = frame.sunMapRows.y;
    if (frame.sunMapRows.w == 0u || id.x >= S || id.y > count) return;
    if (id.y == count) {
        float3 cw = frame.sunMapCentre.xyz + float3(frame.camInt.xyz);
        float3 X = frame.sunMapX.xyz, Y = frame.sunMapY.xyz, L = frame.sunMapL.xyz;
        float2 p[7] = { float2(cw.x, cw.y), float2(cw.z, frame.sunMapCentre.w), float2(X.x, X.y), float2(X.z, Y.x),
                        float2(Y.y, Y.z), float2(L.x, L.y), float2(L.z, 1.0) };
        if (id.x < 7u) sunMap[S * S + id.x] = p[id.x];
        return;
    }
    uint j = (frame.sunMapRows.x + id.y) % S;
    sunMap[j * S + id.x] = sunTexel(id.x, j);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void farMain(uint3 id : SV_DispatchThreadID) {
    gMoonDir = frame.moonDir;
    gMoonRad = frame.moonRad;
    uint w = frame.dims.x, h = frame.dims.y;
    if (id.x >= w || id.y >= h) return;
    uint pix = id.y * w + id.x;
    // the host's sub-pixel jitter (atlas_set_jitter, for its TAA): the ray through a point across the pixel
    float2 ndc = float2((float(id.x) + 0.5) / float(w) * 2.0 - 1.0, 1.0 - (float(id.y) + 0.5) / float(h) * 2.0)
               + frame.jitter.xy;
    float3 dir = normalize(unproject(frame, ndc));
    float3 o = frame.camFrac.xyz;
    TraceResult res;
    res.t = 0; res.mat = 0; res.axis = -1; res.lod = 0; res.steps = 0; res.albedo = float3(0, 0, 0); res.hasAlbedo = 0u;
    // per-pixel dithers: one for refinement fades, a decorrelated one for the LOD segment phase, a third for far
    // actors' fades. Fixed on screen without a temporal resolve; with the host's TAA (jitter set) they move each frame
    // (8 phases), so the resolve averages them into a smooth fade instead of a fixed pattern
    uint phase = frame.jitter.z > 0.5 ? (frame.temporal.x & 7u) * 3u : 0u;
    res.dither = pixelDither(id.xy, phase);
    // the LOD segment phase stays fixed on screen: moving it per frame at a reduced atlas scale made blobs larger
    // than the TAA's neighbourhood, clipped from the history: a flickering fringe on distant ridges
    float lodDither = (frame.dims.z & FRAME_LOD_DITHER) != 0 ? max(pixelDither(id.xy, 1u), 1e-3) : 0.0;
    float3 sun = frame.sunDir.xyz;

    // the rasterised near field already covers this pixel: only the air in front of it (cloud, if the camera is up
    // in the deck) is ours. In tunnels and towns that skips most of the screen.
    if ((frame.dims.z & FRAME_NEAR_DEPTH) != 0) {
        float nd = nearDepth[pix];
        if (nd < frame.params.x) {
            float4 c = float4(0, 0, 0, 0);
            float cd = 1e30;
            if ((frame.dims.z & FRAME_CLOUDS) != 0) {
                float4 cl;
                float clDepth;
                sampleClouds(id.xy, cl, clDepth);
                if (clDepth < nd) {
                    c = float4(cl.rgb, 1.0 - cl.a);
                    if (cl.a < 0.99) cd = clDepth;
                }
            }
            outColor[pix] = c;
            outNormal[pix] = 0;
            outDepth[pix] = cd;
            outSurface[pix] = nd;
            counters[pix] = 0;
            return;
        }
    }
    // the near field drew nothing here (a chunk still loading): fill from the camera so the place shows the far
    // field instead of a hole (only in the open: FRAME_FILL_NEAR)
    float tStart = frame.params.x;
    // (2e30: every sample of the pixel's footprint was sky; 1e30 is a near silhouette's edge, never filled)
    // and only below the horizon: a missing chunk is a hole in the ground; above the horizon an empty near pixel is the
    // real sky, and filling it drew the far field's own copies of the buildings and trees around the camera into it
    // (Helgen's keep as a dark slab in the sky)
    if ((frame.dims.z & FRAME_NEAR_DEPTH) != 0 && (frame.dims.z & FRAME_FILL_NEAR) != 0 && nearDepth[pix] >= 1.5e30
        && dir.y < 0.0)
        tStart = 2.0;
    // the content-shaped groups for this pixel's primary ray (ATLAS_FRAME_GROUPS): the footprint per block of distance
    // and the threshold, dithered per pixel (and per frame under the host's TAA, which averages the levels)
    gGroups = (frame.dims.z & FRAME_GROUPS) != 0u;
    if (gGroups) gHead = groupData[0];  // (also the secondary rays' material shapes: secondarySigma)
    gAblate = asuint(frame.jitter.w);
    gGroupPa = frame.params.w * frame.params.z;
    gGroupTol = 0.7 + 0.6 * pixelDither(id.xy, phase + 5u);
    float tHit = traceView(o, dir, tStart, frame.params.y, lodDither, res);
    gGroups = false;  // secondary rays (shadows, reflections, block light) keep the old path
    ActorHit ah;
    ah.t = -1.0;
    ah.n = float3(0, 1, 0);
    ah.albedo = float3(1, 1, 1);
    if ((frame.dims.z & FRAME_ACTORS) != 0 && frame.weatherParams.w > 0.5)
        ah = traceActors(o, dir, frame.params.x, tHit >= 0.0 ? tHit : frame.params.y, pixelDither(id.xy, phase + 2u));
    bool actorFirst = ah.t >= 0.0;

    float3 color = float3(0, 0, 0);
    float3 n = float3(0, 1, 0);
    uint cls = 0;
    float depth = 1e30;
    float sunVis = 1.0;
    if (actorFirst) {
        n = ah.n;
        cls = 4u;  // far actor
        if ((frame.dims.z & FRAME_CLOUDS) != 0) sunVis = cloudShadow(o + dir * ah.t, keyLightDir(sun));
        MaterialGpu am;
        am.albedoRough = float4(ah.albedo, 0.8);
        am.emisClass = float4(0, 0, 0, 0);
        color = shadeSurface(ah.albedo, am, n, dir, sun, sunVis, 0u, 1.0, float3(0, 0, 0));
        depth = ah.t;
        color = applyAerial(color, dir, sun, ah.t, frame);
    } else if (tHit >= 0.0) {
        MaterialGpu m = palette[res.mat];
        cls = uint(m.emisClass.y);
        int ax = res.axis;
        float R = frame.camFrac.w;
        float k = R > 0 ? dot(dir.xz, dir.xz) / (2.0 * R) : 0.0;
        float3 slope = dir + float3(0, 2.0 * k * tHit, 0);  // tangent of the parabola at the hit
        if (ax < 0) ax = abs(slope.y) > abs(slope.x) && abs(slope.y) > abs(slope.z) ? 1 : (abs(slope.x) > abs(slope.z) ? 0 : 2);
        n = float3(0, 0, 0);
        if (ax == 0) n.x = slope.x > 0 ? -1.0 : 1.0;
        else if (ax == 1) n.y = slope.y > 0 ? -1.0 : 1.0;
        else n.z = slope.z > 0 ? -1.0 : 1.0;
        float3 p = worldAt(o, dir, k, tHit);
        float s = float(1 << res.lod);
        // blocky up close (it is Minecraft), smooth once voxels shrink towards a pixel: stops contour striping
        // centre of the hit voxel on the world voxel grid (multiples of s; y -64 is a multiple for s <= 64)
        float3 ci = float3(frame.camInt.xyz);
        float3 centre = (floor((p + ci) / s - n * 0.5) + 0.5) * s - ci;
        uint ablate = asuint(frame.jitter.w);  // (ATLAS_ABLATE, measurements)
        // cheap far light (FRAME_LIGHT_LOW, a quality setting; ATLAS_ABLATE 256 measures it): the constant sky occlusion,
        // the 4-tap AO and the binary sun shadow of before, unshadowed block light
        bool vanillaLight = (frame.dims.z & FRAME_VANILLA_LIGHT) != 0u;
        bool cheapLight = vanillaLight || (frame.dims.z & FRAME_LIGHT_LOW) != 0u || (ablate & 256u) != 0u;
        // (0.55: smoothed slopes caught more light than the stepped blocks they stand for, the far field read brighter)
        // The groups' hits are real faces at every distance (a slope is a staircase of tops and sides, and so are its
        // blocks): their true face normals light them as the blocks are lit (smoothing brightened slopes the blocks do
        // not have, docs/ATLAS-LOD.md "Where atlas_core's lighting falls short" 4); the voxel path keeps smoothing its
        // coarse cubes against contour striping
        float wSmooth = res.lod > 0 ? 0.55 : 0.55 * saturate((tHit - 96.0) / 400.0);
        if (res.hasAlbedo != 0u && !cheapLight) wSmooth = 0.0;
        if ((frame.dims.z & FRAME_FLAT_NORMALS) != 0) wSmooth = 0.0;
        if ((ablate & 2u) != 0u) wSmooth = 0.0;
        if (wSmooth > 0.0) {
            float3 sn = smoothNormal(res.lod, centre, 1.0);
            if (dot(sn, -dir) > 0.05) n = normalize(lerp(n, sn, wSmooth));
        }
        float3 faceN = float3(ax == 0 ? (slope.x > 0 ? -1.0 : 1.0) : 0.0, ax == 1 ? (slope.y > 0 ? -1.0 : 1.0) : 0.0,
                              ax == 2 ? (slope.z > 0 ? -1.0 : 1.0) : 0.0);
        // where secondary rays start: the hit, lifted off the face to the plane of its voxel in the voxel path they
        // trace (a groups hit may lie inside it: a slab's top in a full voxel) and a hair beyond
        // A groups hit is a face of the real shapes (a snow layer's top a quarter block up its voxel, a drawn box's face up
        // to its flattening error in front of the content), so the origin is the hit itself, nudged off the face: inside
        // a shaped voxel's empty part, secondary rays walk its micro-voxels (secondaryShapeMisses); a voxel-path hit is
        // on its voxel's face. (Lifting it to the voxel's plane picked the wrong voxel whenever the hit was not on one:
        // black faces along every snow edge.)
        float3 so = p + faceN * max(0.02, tHit * 3e-5);
        if (res.hasAlbedo == 0u) {
            so[ax] = centre[ax] + faceN[ax] * (0.51 * s);
        } else if (res.lod == 0 && !cheapLight) {
            // a drawn group's face can lie inside the ground (a box's side between solid blocks, shown where the
            // box is drawn whole): rays from it start in solid and see nothing (a black bar). Such a face stands for
            // the surface over it: out of the column upwards, at most three blocks
            for (int k = 0; k < 3; k++) {
                uint om = materialAt(0, so);
                if (om == 0xFFFFFFFFu || secondaryShapeMisses(om, frac(so + ci), float3(0, 1, 0)) || secondarySigma(om, uint(palette[om].emisClass.y)) >= 0.0)
                    break;
                so.y = floor(so.y + ci.y) + 1.02 - ci.y;
            }
        } else if (res.lod > 0) {
            // a coarse level's groups hit lies on a micro-voxel face, anywhere in a voxel of the coarse voxel path the
            // secondary rays trace (whose mip vote may also differ): out of that voxel's solid along the face
            for (int k = 0; k < 2; k++) {
                if (!occupiedAt(res.lod, so)) break;
                float vc = (floor((so[ax] + ci[ax]) / s) + 0.5) * s - ci[ax];
                so[ax] = vc + faceN[ax] * (0.51 * s);
            }
        }
        bool fol = cls == 1u;
        float through = 0.0;  // the key light reaching a leaf through the crown (FOLIAGE_SSS)
        if ((frame.dims.z & FRAME_SHADOWS) != 0 && (ablate & 4u) == 0u) {
            // same LOD as the hit (a coarser one is fatter and self-shadows); start one voxel out along the face
            float3 L = keyLightDir(sun);
            if (cheapLight) {
                sunVis = dot(n, L) > 0.0 && dot(faceN, L) > -0.2
                    ? traceShadow(p + faceN * (1.01 * s) + L * (0.5 * s), L, res.lod) : 0.0;
            } else if ((dot(n, L) > 0.0 && dot(faceN, L) > -0.2) || fol) {
                // through foliage by its coverage (dappled light under a crown, light through its leaves)
                sunVis = traceSecondary(so + L * (0.05 * s), L, 2048.0, res.lod);
                through = max(sunVis, gThrough);
                if (dot(n, L) <= 0.0) sunVis = 0.0;
            } else {
                sunVis = 0.0;
            }
        }
        if ((frame.dims.z & FRAME_CLOUDS) != 0 && sunVis > 0.0) sunVis *= cloudShadow(p, keyLightDir(sun));
        float3 albedo = res.hasAlbedo != 0u ? res.albedo : m.albedoRough.rgb;
        // a surface card on this face of the block (the game's own colour and light there, atlas_set_surface_cards):
        // filtered over the pixel's footprint on the face; as albedo its vanilla face shade is taken out again
        bool carded = false;
        {
            float3 fn0 = float3(ax == 0 ? (slope.x > 0 ? -1.0 : 1.0) : 0.0, ax == 1 ? (slope.y > 0 ? -1.0 : 1.0) : 0.0,
                                ax == 2 ? (slope.z > 0 ? -1.0 : 1.0) : 0.0);
            float3 pw = p + float3(frame.camInt.xyz);
            int3 blk = int3(floor(pw - fn0 * 0.01));
            int f = ax == 1 ? (fn0.y < 0.0 ? 0 : 1) : ax == 2 ? (fn0.z < 0.0 ? 2 : 3) : (fn0.x < 0.0 ? 4 : 5);
            uint card = cardOf(blk, f);
            if (card != 0u) {
                int a1 = (ax + 1) % 3, a2 = (ax + 2) % 3;
                float3 lp = pw - float3(blk);
                float fp = tHit * frame.params.w / max(abs(dot(dir, fn0)), 0.2);
                float3 cc = cardColour(card, clamp(lp[a1], 0.0, 0.9999), clamp(lp[a2], 0.0, 0.9999), fp);
                const float SHADE[6] = { 0.5, 1.0, 0.8, 0.8, 0.6, 0.6 };
                albedo = (frame.dims.z & FRAME_DEBUG_ALBEDO) != 0 ? cc : cc / SHADE[f];
                carded = true;
            }
        }
        // ambient occlusion: solid neighbours around the air voxel in front of the face (4 taps in the face plane)
        float ao = 1.0;
        if ((ablate & 1u) == 0u && (cls == 2u || cheapLight)) {  // (the traced sky has no use for it)
            float3 fn = float3(ax == 0 ? (slope.x > 0 ? -1.0 : 1.0) : 0.0, ax == 1 ? (slope.y > 0 ? -1.0 : 1.0) : 0.0,
                               ax == 2 ? (slope.z > 0 ? -1.0 : 1.0) : 0.0);
            float3 front = centre + fn * s;
            float3 t1 = ax == 0 ? float3(0, s, 0) : float3(s, 0, 0);
            float3 t2 = ax == 2 ? float3(0, s, 0) : float3(0, 0, s);
            float occ = (occupiedAt(res.lod, front + t1) ? 1.0 : 0.0) + (occupiedAt(res.lod, front - t1) ? 1.0 : 0.0) +
                        (occupiedAt(res.lod, front + t2) ? 1.0 : 0.0) + (occupiedAt(res.lod, front - t2) ? 1.0 : 0.0);
            ao = 1.0 - 0.15 * occ;
        }
        // falling water (waterfalls, stamped as structures): a white sheet streaming down, brighter where it breaks
        bool falls = cls == 5u;
        if (falls) {
            float3 wp = p + float3(frame.camInt.xyz);
            float streak = valueNoise(float2((wp.x + wp.z) / 1.3, wp.y / 4.0 + frame.sunDir.w * 2.5)) * 0.6
                         + valueNoise(float2((wp.x - wp.z) / 0.7, wp.y / 1.8 + frame.sunDir.w * 4.0)) * 0.4;
            albedo = float3(0.78, 0.84, 0.88) * (0.78 + 0.32 * streak);
            cls = 0u;  // lit like a rough diffuse surface: it scatters, it does not mirror
        }
        // large-scale tone variation on land (about 24 and 90 blocks), the far-field stand-in for texture detail
        if (cls != 2u && !falls && res.hasAlbedo == 0u && !carded) {  // (a groups hit has its texels)
            float2 wxz = p.xz + float2(frame.camInt.xz);
            float v = valueNoise(wxz / 24.0) * 0.6 + valueNoise(wxz / 90.0 + 17.0) * 0.4;
            albedo *= 0.95 + 0.10 * v;  // gentle: stronger tone blotches the near field does not have read as a seam
        }
        if ((frame.dims.z & FRAME_DEBUG_LOD) != 0) albedo = lodTint(res.lod);
        float3 reflection = float3(0, 0, 0);
        float foam = 0.0;
        if (cls == 2u) {
            float2 wxz = p.xz + float2(frame.camInt.xz);
            float2 wind = farWaterWind(wxz, length(p.xz));
            // far water is a level sheet: a side face is only the step where its quantised level changes between
            // LODs (seen edge-on near the horizon it drew a dark seam), so every water hit is shaded as the surface
            float flow = m.emisClass.w;  // 0 still (sea, lakes), 1 calm, 2 fast, 3 rapids (the dressing's river flow)
            if (flow > 0.5) {
                // a river: the wave field runs downstream (heading from the flow, short steep waves the faster it
                // goes), carried along at the current's speed; fast runs and rapids break into white water in
                // streaks along the flow
                float2 fd = float2(cos(m.emisClass.z), sin(m.emisClass.z));
                float speed = 1.5 * flow;  // blocks per second
                wind = float2(m.emisClass.z, saturate(0.25 + 0.22 * flow));
                n = farWaveNormal(wxz - fd * (frame.sunDir.w * speed), frame.sunDir.w, wind, tHit);
                float u = dot(wxz, fd), v = dot(wxz, float2(-fd.y, fd.x));
                float streak = valueNoise(float2(u / 7.0 - frame.sunDir.w * speed / 7.0, v / 1.6)) * 0.7
                             + valueNoise(float2(u / 2.5 - frame.sunDir.w * speed / 2.5, v / 0.8)) * 0.3;
                foam = flow > 2.5 ? smoothstep(0.25, 0.6, streak) * 0.8 : flow > 1.5 ? smoothstep(0.55, 0.85, streak) * 0.35 : 0.0;
            } else {
                n = farWaveNormal(wxz, frame.sunDir.w, wind, tHit);
            }
            m.albedoRough.a = farWaterAlpha(wind.y, tHit) + foam * 0.2;
            reflection = (ablate & 16u) != 0u ? float3(0, 0, 0) : waterReflection(p, dir, k, tHit, res.lod, sun, n);
        }
        float3 ambient;
        float skyVis = 1.0;
        if (cls == 2u) {
            ambient = skyAmbient(n, sun) * ao;
        } else if (cheapLight) {
            // the near field's sky light is darkened by sky occlusion, vertex AO and SSAO that a far voxel cannot see:
            // land takes their average (FAR_AMBIENT_OCCLUSION, calibrated against fid-* near / far pairs)
            ambient = skyAmbient(n, sun) * (ao * FAR_AMBIENT_OCCLUSION) + FAR_AMBIENT_FLOOR;
        } else {
            // the sky this face sees (traced visibility of the hemisphere), the pack's floor under it
            ambient = skyVisible(id.xy, so, faceN, res.lod, sun, skyVis) + FAR_AMBIENT_FLOOR;
        }
        // an emitter's texels glow by their own brightness, as the pack's light blocks do (lib/materials.glsl MAT_LIGHT:
        // 4 x smoothstep(0.35, 0.8, luminance): a lantern's flame bright, its iron frame dark); never below the flat
        // glow of a material without texels (lava and fire keep at least that)
        if (cls == 3u && (res.hasAlbedo != 0u || carded) && !cheapLight)
            m.emisClass.x = max(m.emisClass.x, 2.0 * smoothstep(0.35, 0.8, dot(albedo, float3(0.2126, 0.7152, 0.0722))));
        color = shadeSurfaceAmb(albedo, m, n, dir, sun, sunVis, cls, ambient, reflection);
        if (vanillaLight && cls != 2u) color = vanillaLit(albedo, faceN, cls, sun);
        // light through leaves (the pack's FOLIAGE_SSS, deferred.fsh): strongest looking towards the light through them
        if (fol && !cheapLight && !vanillaLight && (ablate & 1024u) == 0u && (frame.dims.z & FRAME_SHADOWS) != 0) {
            float3 L = keyLightDir(sun);
            float back = pow(max(dot(dir, L), 0.0), 4.0) * 0.8 + 0.25;
            color += albedo * keyLightRadiance(sun) * (through * 0.6 * back * 0.5);
        }
        // the lamps, torches, fires and lava around it light it (the near field's block light, ATLAS lights)
        if (cls != 2u && (frame.dims.z & FRAME_DEBUG_ALBEDO) == 0 && (ablate & 8u) == 0u)
            color += albedo * farLightsOn(id.xy, p, so, faceN, res.lod, sun, fol, cheapLight);
        if (foam > 0.0) {
            // white water: a bright rough surface over the river
            float3 up = float3(0, 1, 0);
            float3 white = float3(0.85, 0.88, 0.9) * (keyLightRadiance(sun) * saturate(dot(up, keyLightDir(sun))) * sunVis + skyAmbient(up, sun));
            color = lerp(color, white, foam);
        }
        depth = tHit;
        if (!vanillaLight) color = applyAerial(color, dir, sun, tHit, frame);
        // (ATLAS_ABLATE 4096, measurements: the secondary rays' origin solid in the voxel path, sun and sky visibility)
        // (ATLAS_ABLATE 4096, measurements: one grey value past the shading, readable through any tonemap: the sky
        // visibility; with 2048 whether the secondary rays' origin is in a voxel-path solid)
        if ((ablate & 4096u) != 0u) {
            float vv = (ablate & 2048u) != 0u ? (materialAt(res.lod, so) != 0xFFFFFFFFu ? 1.0 : 0.0) : skyVis;
            color = float3(vv, vv, vv);
        }
        if ((frame.dims.z & FRAME_DEBUG_ALBEDO) != 0) color = albedo;
        if ((frame.dims.z & FRAME_DEBUG_ALBEDO) != 0 && carded) cls = 6u;  // (already shaded: the offline tools leave it)
        if ((frame.dims.z & FRAME_DEBUG_WEATHER) != 0) {
            float3 wpos = p + float3(frame.camInt.xyz);
            CloudColumn dbg = sampleColumn(wpos.xz, length(p.xz));
            float lowerY = dbg.dynamics.r * 1024.0;
            float upperY = lowerY + 72.0 + 120.0 * dbg.weather.r + 300.0 * dbg.weather.g;
            // r: tower, g: cloud top (0 at y 200, 1 at y 1000), b: base (0 at 100, 1 at 400)
            color = float3(dbg.weather.g, (upperY - 200.0) / 800.0, (lowerY - 100.0) / 300.0);
            float hdbg;
            float3 probe = float3(wpos.x, 520.0, wpos.z);
            color.r = cumulusDensity(probe, dbg, length(p.xz), hdbg) > 0.0 ? 1.0 : 0.0;
        }
    } else if ((frame.dims.z & FRAME_SKY) != 0) {
        color = skyColor(dir, sun);
    }
    float coverage = tHit >= 0.0 || actorFirst || (frame.dims.z & FRAME_SKY) != 0 ? 1.0 : 0.0;
    float surface = actorFirst ? ah.t : (tHit >= 0.0 ? tHit : 1e30);
    // far lights, under the cloud deck (clouds in front of the surface veil them with it)
    if ((frame.dims.z & FRAME_DEBUG_ALBEDO) == 0) color += farLights(id.xy, o, dir, surface, sun);
    if ((frame.dims.z & FRAME_CLOUDS) != 0) {
        // the half-resolution deck, where it starts in front of this pixel's surface
        float4 cl;
        float clDepth;
        sampleClouds(id.xy, cl, clDepth);
        if (clDepth < surface) {
            color = color * cl.a + cl.rgb;
            coverage = 1.0 - (1.0 - coverage) * cl.a;
            // depth = the nearest thing drawn, cloud included: lets a composite keep near geometry in front of it.
            // Over terrain a cloud's thin fringe keeps the terrain's distance (the composite fogs by this depth: a
            // fringe at cloud distance left an unfogged ring round every cloud seen from above); the body takes it
            if (cl.a < (surface < 1e29 ? 0.5 : 0.99)) depth = min(depth, clDepth);
        }
    }
    outSurface[pix] = surface;
    outColor[pix] = float4(color, coverage);
    outNormal[pix] = packNormal(n, cls, sunVis);
    outDepth[pix] = depth;
    counters[pix] = res.steps;  // per-pixel step count (summed on the host; no atomics so the C++ target matches)
}
