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
// (abi 0.15, FRAME_NEAR_LIGHT) the near field's surface info per pixel (RGBA8 packed, r low: block light, sky light,
// octahedral normal; row 0 top) and the near light out (irradiance on a white diffuse surface, key light visibility)
[[vk::binding(26, 0)]] StructuredBuffer<uint> nearInfo;
[[vk::binding(27, 0)]] RWStructuredBuffer<float4> outNearLight;
// (FRAME_NEAR_SPLIT) the previous frame's near light, for nearTemporalMain (a pixel's worth when it had none)
[[vk::binding(28, 0)]] StructuredBuffer<float4> prevNearLight;
// the level-0 grid built on the GPU (native/src/voxgrid.hpp; atlas_voxgrid.slang builds it): chunk words, block words,
// block bitmaps, the sections' raw ids (two a word); frame.vgOrigin / vgDims place it
[[vk::binding(29, 0)]] StructuredBuffer<uint> vgChunks;
[[vk::binding(30, 0)]] StructuredBuffer<uint> vgBlocks;
[[vk::binding(31, 0)]] StructuredBuffer<uint64_t> vgBits;
[[vk::binding(32, 0)]] StructuredBuffer<uint> vgPool;
// (abi 0.16) the near field's textures (voxgrid.hpp packTextures): 16 x 16 a face, as mip chains
[[vk::binding(33, 0)]] StructuredBuffer<uint> texData;

#include "atlas_air.slang"
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
static const float WATER_SIGMA = 0.083;  // light lost through water: exp(-0.083) per block (8 %)
static const float3 WATER_ABSORB = float3(0.30, 0.075, 0.045);  // per block, per channel (red first)
static bool gSecondary = false;
static float gTrans = 1.0;
static float gThrough = 0.0;
static float gSecT = 0.0;
static float gWaterChord = 0.0;  // blocks of water the last secondary ray passed through
// (ATLAS_ABLATE, measurements of the secondary rays, docs/ATLAS-RENDERER-PLAN.md "GPU-first") bit 21: their level-0
// regions skip the height mip; bit 22: foliage passes them free (no shape, coverage or attenuation work); bits 23-25 a
// per-pixel counter of the near light's secondary rays into `counters` (gSecCount): 1 traceRegion iterations, 2
// height-mip skips, 3 height-mip probes, 4 node loads, 5 solid voxels met (material lookups), 6 voxels passed (foliage,
// water, shapes), 7 rays; bit 26: count in level-0 regions only
static uint gSecAblate = 0u;
static uint gSecMode = 0u;
static uint gSecCount = 0u;
void secCount(uint mode, int lod) {
    if (gSecondary && gSecMode == mode && ((gSecAblate & 67108864u) == 0u || lod == 0)) gSecCount++;
}

// The face a ray entered voxel v through: the axis whose slab it entered last (robust for rays that graze a boundary
// plane, unlike the last DDA crossing). ro / rd / inv / stepPos: the ray in the voxel's units.
int entryAxis(int3 v, float3 ro, float3 rd, float3 inv, float3 stepPos) {
    float3 tNear = (float3(v) + (float3(1, 1, 1) - stepPos) - ro) * inv;
    if (abs(rd.x) <= 1e-12) tNear.x = -1e30;
    if (abs(rd.y) <= 1e-12) tNear.y = -1e30;
    if (abs(rd.z) <= 1e-12) tNear.z = -1e30;
    return tNear.x >= tNear.y && tNear.x >= tNear.z ? 0 : (tNear.y >= tNear.z ? 1 : 2);
}

// Whether a ray stops at the solid voxel v (of level rl; wv: the same voxel in world voxels of that level) it met at t,
// shared by the SVDAG regions (traceRegion) and the level-0 grid (traceVoxGrid). Water seen through (the lab), foliage
// free (a measurement); a secondary ray meets a level-0 voxel of a shaped material (a snow layer, a slab, a stair, a
// fence: a full cube to the voxel path) by its micro-voxels and passes when the shape's are empty along it, and is
// attenuated through foliage and plants over its chord in the voxel; a primary ray sees gaps between leaves: a hashed
// share of foliage voxels lets it through (moving with the TAA phase, so the resolve sees crowns as dappled, not solid).
bool voxelStops(uint hitMat, int3 v, int3 wv, int rl, float3 ro, float3 rd, float3 inv, float3 stepPos, float t) {
    uint hitCls = uint(palette[hitMat].emisClass.y);
    secCount(5u, rl);
    bool pass = ((frame.dims.z & FRAME_SEE_THROUGH_WATER) != 0 && hitCls == 2u) || primaryPassesWater(hitCls, t);
    if (gSecondary && hitCls == 1u && (gSecAblate & 4194304u) != 0u) pass = true;  // (free foliage)
    if (gSecondary && !pass && rl == 0 && secondaryShapeMisses(hitMat, ro + rd * t - float3(v), rd)) pass = true;
    float sigma = gSecondary && !pass ? secondarySigma(hitMat, hitCls) : -1.0;
    if (sigma >= 0.0) {
        // a secondary ray through foliage or a plant: attenuated over its chord through this voxel, then on
        float3 tv = (float3(v) + stepPos - ro) * inv;
        tv = float3(abs(rd.x) > 1e-12 && tv.x > t ? tv.x : 1e30, abs(rd.y) > 1e-12 && tv.y > t ? tv.y : 1e30,
                    abs(rd.z) > 1e-12 && tv.z > t ? tv.z : 1e30);
        float chord = clamp(min(tv.x, min(tv.y, tv.z)) - t, 0.0, 2.0 * float(1 << rl));
        float x0 = max(gSecT + t, 0.0);
        // E[exp(-0.45 x)] over where in this stretch the first opaque texel lies, weighted by what got here
        gThrough += gTrans * exp(-0.45 * x0) * (sigma / (sigma + 0.45)) * (1.0 - exp(-(sigma + 0.45) * chord));
        gTrans *= exp(-sigma * chord);
        if (hitCls == 2u) gWaterChord += chord;
        pass = gTrans > 0.004;
        if (!pass) gTrans = 0.0;
    }
    if (pass) secCount(6u, rl);
    if (!pass && hitCls == 1u && gLeafGap > 0.0 && !gSecondary) {
        // (hashed by the world voxel and level, so every structure holding the voxel shows the same gaps)
        uint phase = frame.jitter.z > 0.5 ? (frame.temporal.x & 7u) : 0u;
        uint hh = (uint(wv.x) * 73856093u) ^ (uint(wv.y) * 19349663u) ^ (uint(wv.z) * 83492791u) ^ (uint(rl) * 2654435761u) ^
                  (phase * 374761393u);
        hh = (hh ^ (hh >> 13)) * 1274126177u;
        pass = float(hh & 0xFFFFu) / 65535.0 < gLeafGap;
    }
    return !pass;
}

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
    int rl = r.origin.w;
    bool useMip = !(gSecondary && rl == 0 && (gSecAblate & 2097152u) != 0u);
    for (int it = 0; it < 1024; it++) {
        if (t >= t1) break;
        res.steps++;
        secCount(1u, rl);
        float3 p = ro + rd * t;
        // Over terrain: find the largest column cell (whole region down to 4x4 voxels) whose highest voxel the ray
        // stays above until it leaves the cell, and skip straight across it. Far rays spend most of their length here.
        bool over = false;
        int lvlOff = 5460;
        for (int k = useMip ? 6 : -1; k >= 0; k--) {
            secCount(3u, rl);
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
                    secCount(2u, rl);
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
                secCount(4u, rl);
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
                if (voxelStops(hitMat, v, (r.origin.xyz >> rl) + v, rl, ro, rd, inv, stepPos, t)) {
                    res.t = t;
                    res.mat = hitMat;
                    res.axis = entryAxis(v, ro, rd, inv, stepPos);
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
    // water lets light through as it goes (a lake's bed lit by the sun and sky through it, 8 % lost a block): as solid,
    // every surface under water was black to the near light (its sun and sky rays stopped at the surface)
    float sg = cls == 1u ? FOLIAGE_SIGMA : cls == 2u ? WATER_SIGMA : -1.0;
    uint sh = shapeOf(mat);
    // (water's shape is a full cube, the fluid's: its class decides, not the shape, which made it a shaped solid)
    if (sh != 0u && cls != 2u) {
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

// ---- the level-0 grid (native/src/voxgrid.hpp: layout; atlas_voxgrid.slang builds it on the GPU) -------------------
static const uint VG_EMPTY = 0u;
static const uint VG_MIXED = 1u;
static const uint VG_UNLOADED = 2u;
// (ATLAS_ABLATE 134217728, measurements: level 0 from the SVDAG regions alone, as before the grid)
bool vgOn() { return frame.vgOrigin.w != 0 && (asuint(frame.jitter.w) & 134217728u) == 0u; }

// the cell of the window's chunk c (logical, from its origin): in memory at (c + origin) mod D (voxgrid.hpp phys), so
// the window follows the camera without moving the chunks it keeps
uint vgCell(int3 c) {
    int X = int(frame.vgDims.x), Z = int(frame.vgDims.z);
    int px = (c.x + frame.vgOrigin.x) % X, pz = (c.z + frame.vgOrigin.z) % Z;
    px += px < 0 ? X : 0;
    pz += pz < 0 ? Z : 0;
    return uint((c.y * Z + pz) * X + px);
}

// A primary ray through a level-0 voxel v (grid-local; wv in world blocks) of shaped material `sh` (shapeOf), entered at
// t through `axis`: its 4 x 4 x 4 micro-voxels as the groups have them (voxelAt / microFilled: the shape's mask, a cut-out
// shape's alpha hashed per world micro-voxel), walked to where the ray leaves the voxel. True: it met one at tHit, entering
// through `axis`, micro-voxel qHit (world).
bool vgShapeHit(uint sh, int3 v, int3 wv, float3 ro, float3 d, float t, out float tHit, inout int axis, out int3 qHit) {
    uint4 head = groupData[sh >> 2];  // mask lo, mask hi, flags (1: cut-out), mean
    uint alphaAt = (head.z & 1u) != 0u && (asuint(frame.jitter.w) & 536870912u) == 0u ? sh + 100u : 0u;  // (536870912: no cut-outs)
    float3 oq = (ro - float3(v)) * 4.0, dq = d * 4.0;  // the voxel's micro-voxels
    float3 p = oq + dq * t;
    int3 c = clamp(int3(floor(p)), int3(0, 0, 0), int3(3, 3, 3));
    int3 st = int3(dq.x > 0.0 ? 1 : -1, dq.y > 0.0 ? 1 : -1, dq.z > 0.0 ? 1 : -1);
    float3 inv = float3(abs(dq.x) > 1e-12 ? 1.0 / dq.x : 1e30, abs(dq.y) > 1e-12 ? 1.0 / dq.y : 1e30,
                        abs(dq.z) > 1e-12 ? 1.0 / dq.z : 1e30);
    float3 nx = (float3(c) + float3(dq.x > 0.0 ? 1.0 : 0.0, dq.y > 0.0 ? 1.0 : 0.0, dq.z > 0.0 ? 1.0 : 0.0) - oq) * inv;
    nx = float3(abs(dq.x) < 1e-12 ? 1e30 : nx.x, abs(dq.y) < 1e-12 ? 1e30 : nx.y, abs(dq.z) < 1e-12 ? 1e30 : nx.z);
    float3 dl = abs(inv);
    float tc = t;
    tHit = t;
    qHit = wv * 4 + c;
    for (int k = 0; k < 12; k++) {
        uint bit = uint(c.x + 4 * (c.z + 4 * c.y));
        bool filled = (((bit < 32u ? head.x >> bit : head.y >> (bit - 32u))) & 1u) != 0u;
        if (filled && alphaAt != 0u) {
            uint a = (gWord(alphaAt + (bit >> 2)) >> ((bit & 3u) * 8u)) & 255u;
            filled = a == 255u || (cutHash(wv * 4 + c) >> 24) < a;
        }
        if (filled) {
            tHit = tc;
            qHit = wv * 4 + c;
            return true;
        }
        int a = nx.x <= nx.y && nx.x <= nx.z ? 0 : (nx.y <= nx.z ? 1 : 2);
        tc = nx[a];
        c[a] += st[a];
        if (c[a] < 0 || c[a] > 3) return false;
        nx[a] += dl[a];
        axis = a;
    }
    return false;
}

// (abi 0.16) A material's texture: its first word in texData, or 0 (none: its face tiles or palette colour)
uint vgTexture(uint mat) {
    if (mat >= texData[0]) return 0u;
    uint s = texData[1u + mat];
    return s == 0u ? 0u : 1u + texData[0] + (s - 1u) * 6u * 341u;
}

// face f of texture `at` at (u, v) in 0..1 over a footprint of fp blocks: linear rgb, a = coverage; its mip chain (16, 8,
// 4, 2, 1), nearest within a level, blended between the two nearest levels (as cardColour)
float4 texSample(uint at, int f, float u, float v, float fp) {
    static const uint MIP_AT[5] = { 0u, 256u, 320u, 336u, 340u };
    float lv = clamp(log2(max(fp * 16.0, 1.0)), 0.0, 4.0);
    int l0 = int(lv), l1 = min(4, l0 + 1);
    int n0 = 16 >> l0, n1 = 16 >> l1;
    uint base = at + uint(f) * 341u;
    uint c0 = texData[base + MIP_AT[l0] + uint(min(int(u * float(n0)), n0 - 1) + n0 * min(int(v * float(n0)), n0 - 1))];
    uint c1 = texData[base + MIP_AT[l1] + uint(min(int(u * float(n1)), n1 - 1) + n1 * min(int(v * float(n1)), n1 - 1))];
    float k = lv - float(l0);
    return float4(lerp(srgbLinear(c0), srgbLinear(c1), k), lerp(float(c0 >> 24), float(c1 >> 24), k) / 255.0);
}

// A level-0 hit's texel (primary rays): the material's 16 x 16 face texture at the hit point p (voxel-local 0..1) on the
// face the ray entered by, over the pixel's footprint there; a covered texel replaces res's albedo (a cut-out's
// uncovered texel keeps its face tile's colour)
void vgTexel(inout TraceResult res, uint mat, float3 lp, float3 d, int ax, float tView) {
    uint at = vgTexture(mat);
    if (at == 0u) return;
    int f = d[ax] > 0.0 ? G_FACE_LOW[ax] : G_FACE_HIGH[ax];
    float3 q = clamp(lp, float3(0, 0, 0), float3(0.9999, 0.9999, 0.9999));
    float2 uv = f <= 1 ? q.xz : (f <= 3 ? float2(q.x, 1.0 - q.y) : float2(q.z, 1.0 - q.y));
    uv = clamp(uv, float2(0, 0), float2(0.9999, 0.9999));
    float fp = tView * frame.params.w * frame.params.z / max(abs(d[ax]), 0.2);
    float4 c = texSample(at, f, uv.x, uv.y, fp);
    if (c.a < 0.5 && res.hasAlbedo != 0u) return;
    res.albedo = c.rgb;
    res.hasAlbedo = 1u;
}

// The chord p0 + d u (camera-relative), u in [u0, u1), through the grid: TRACE_HIT (res filled), TRACE_MISS (it crossed
// [u0, u1) through chunks with data), else TRACE_UNCOVERED and uStop where it met a chunk without data or left the
// window (the SVDAG regions answer from there). A step reads the chunk word; inside a chunk with blocks the block word;
// inside a block with voxels its bitmap: an empty chunk or block is left through the far side of its empty box, an
// empty voxel through its empty 2^3 octant when it has one, as traceRegion's bricks are.
int traceVoxGrid(float3 p0, float3 d, float u0, float u1, inout TraceResult res, out float uStop) {
    uStop = u0;
    int3 gBlock = frame.vgOrigin.xyz * 16;
    float3 ro = p0 - float3(gBlock - frame.camInt.xyz);  // grid-local blocks
    int X = int(frame.vgDims.x), Y = int(frame.vgDims.y), Z = int(frame.vgDims.z);
    int3 nb = int3(X, Y, Z) * 16;
    float3 inv = float3(abs(d.x) > 1e-12 ? 1.0 / d.x : 1e30, abs(d.y) > 1e-12 ? 1.0 / d.y : 1e30,
                        abs(d.z) > 1e-12 ? 1.0 / d.z : 1e30);
    float3 stepPos = float3(d.x > 0 ? 1.0 : 0.0, d.y > 0 ? 1.0 : 0.0, d.z > 0 ? 1.0 : 0.0);
    bool3 up = bool3(d.x > 0, d.y > 0, d.z > 0);
    float t = u0;
    int exitAxis = -1, exitTo = 0;
    for (int it = 0; it < 4096; it++) {
        if (t >= u1) return TRACE_MISS;
        res.steps++;
        secCount(1u, 0);
        // (a point exactly on a cell face going down that axis lies in the cell below: else that face's exit is at t,
        // not after it, and the ray left a camera standing on a chunk's floor through the box's far sides instead)
        float3 p = ro + d * t;
        float3 pf = floor(p);
        int3 v = int3(pf) - int3(d.x < 0 && p.x == pf.x ? 1 : 0, d.y < 0 && p.y == pf.y ? 1 : 0, d.z < 0 && p.z == pf.z ? 1 : 0);
        if (exitAxis >= 0) v[exitAxis] = exitTo;
        if (v.x < 0 || v.y < 0 || v.z < 0 || v.x >= nb.x || v.y >= nb.y || v.z >= nb.z) {
            uStop = t;
            return TRACE_UNCOVERED;
        }
        int3 c = v >> 4;
        uint cw = vgChunks[vgCell(c)];
        uint st = cw & 3u;
        if (st == VG_UNLOADED) {
            uStop = t;
            return TRACE_UNCOVERED;
        }
        int3 lo, hi;  // the empty box the ray leaves next, grid-local blocks, [lo, hi)
        if (st == VG_EMPTY) {
            int3 en = int3(int((cw >> 2) & 31u), int((cw >> 12) & 31u), int((cw >> 22) & 31u));
            int3 ep = int3(int((cw >> 7) & 31u), int((cw >> 17) & 31u), int((cw >> 27) & 31u));
            lo = (c - en) * 16;
            hi = (c + ep + 1) * 16;
            secCount(2u, 0);
        } else {
            uint slot = cw >> 2;
            int3 l = v & 15;
            int3 b = l >> 2;
            uint bi = uint(b.x + 4 * (b.z + 4 * b.y));
            uint bw = vgBlocks[slot * 64u + bi];
            secCount(4u, 0);
            if ((bw & 1u) == 0u) {
                int3 en = int3(int((bw >> 2) & 3u), int((bw >> 6) & 3u), int((bw >> 10) & 3u));
                int3 ep = int3(int((bw >> 4) & 3u), int((bw >> 8) & 3u), int((bw >> 12) & 3u));
                lo = c * 16 + (b - en) * 4;
                hi = c * 16 + (b + ep + 1) * 4;
            } else {
                uint64_t bits = vgBits[slot * 64u + bi];
                int3 q = v & 3;
                uint bt = uint(q.x + 4 * q.z + 16 * q.y);
                if (((bits >> bt) & 1) != 0) {
                    uint i = uint(l.x + 16 * (l.z + 16 * l.y));
                    uint mat = (vgPool[slot * 2048u + (i >> 1)] >> (16u * (i & 1u))) & 0xFFFFu;
                    // a primary ray meets a shaped block (a slab, a stair, a fence, a plant, cut-out leaves) by its
                    // micro-voxels, textured by its face tiles, as the groups draw it (ATLAS_ABLATE 268435456: cubes)
                    uint sh = gSecondary || (asuint(frame.jitter.w) & 268435456u) != 0u ? 0u : shapeOf(mat);
                    uint wcls = uint(palette[mat].emisClass.y);
                    bool seeWater = ((frame.dims.z & FRAME_SEE_THROUGH_WATER) != 0 && wcls == 2u) || primaryPassesWater(wcls, t);
                    if (sh != 0u && !seeWater) {
                        float tHit;
                        int ax = entryAxis(v, ro, d, inv, stepPos);
                        int3 q;
                        if (vgShapeHit(sh, v, v + gBlock, ro, d, t, tHit, ax, q)) {
                            res.t = tHit;
                            res.mat = mat;
                            res.axis = ax;
                            res.lod = 0;
                            int f = d[ax] > 0.0 ? G_FACE_LOW[ax] : G_FACE_HIGH[ax];
                            res.albedo = cellAlbedo(mat | G_CELL_SHAPED, f, q);
                            res.hasAlbedo = 1u;
                            vgTexel(res, mat, ro + d * tHit - float3(v), d, ax, gChordT + tHit);
                            return TRACE_HIT;
                        }
                    } else if (voxelStops(mat, v, v + gBlock, 0, ro, d, inv, stepPos, t)) {
                        res.t = t;
                        res.mat = mat;
                        res.axis = entryAxis(v, ro, d, inv, stepPos);
                        res.lod = 0;
                        if (!gSecondary) vgTexel(res, mat, ro + d * t - float3(v), d, res.axis, gChordT + t);
                        return TRACE_HIT;
                    }
                    lo = v;
                    hi = v + 1;
                } else {
                    uint sb = uint((q.x & 2) + 4 * (q.z & 2) + 16 * (q.y & 2));
                    uint64_t sub = (uint64_t(0x33) << sb) | (uint64_t(0x33) << (sb + 16));
                    int cell = (bits & sub) == 0 ? 2 : 1;
                    lo = v & ~(cell - 1);
                    hi = lo + cell;
                }
            }
        }
        float3 tt = (float3(up.x ? hi.x : lo.x, up.y ? hi.y : lo.y, up.z ? hi.z : lo.z) - ro) * inv;
        tt = float3(tt.x > t ? tt.x : 1e30, tt.y > t ? tt.y : 1e30, tt.z > t ? tt.z : 1e30);
        float tn = min(tt.x, min(tt.y, tt.z));
        t = max(tn, t) + 1e-3;
        // the cell across the face it leaves by, by index: at a grazing angle the 1e-3 step moves the point less across
        // that face than float rounding, flooring it put the ray back in the cell it left, whose exit on that axis then
        // lay behind: it left through another face and skipped the voxels across the first one
        exitAxis = tt.x <= tn ? 0 : (tt.y <= tn ? 1 : 2);
        exitTo = exitAxis == 0 ? (up.x ? hi.x : lo.x - 1) : exitAxis == 1 ? (up.y ? hi.y : lo.y - 1) : (up.z ? hi.z : lo.z - 1);
    }
    uStop = t;
    return TRACE_UNCOVERED;
}

// Walk the regions of one LOD along the chord p0 + d u, u in [0, len].
// On TRACE_UNCOVERED, uncExit is where the chord leaves the unloaded region cell (1e30 when it left the LOD's
// window: that LOD has nothing further along this ray at all).
int traceLod(int lod, float3 p0, float3 d, float len, inout TraceResult res, out float uncExit) {
    uncExit = 1e30;
    // level 0: the GPU-built grid first, the regions only past where it has no data
    float uGrid = 0.0;
    // (the content-shaped groups keep primary rays where a frame asks for them, the grid having no shapes yet, unless
    // the grid owns level 0: vgDims.w, ATLAS_VOXGRID_LEVEL0, where the SVDAG level 0 no longer follows the sections)
    if (lod == 0 && vgOn() && (!gGroups || frame.vgDims.w != 0u)) {
        gLeafGap = 0.3;
        int g = traceVoxGrid(p0, d, 0.0, len, res, uGrid);
        if (g != TRACE_UNCOVERED) return g;
        if (uGrid >= len) return TRACE_MISS;
        p0 += d * uGrid;
        len -= uGrid;
    }
    gSecT += uGrid;  // (the secondary ray's distance where the chord starts)
    int r0 = traceLodRegions(lod, p0, d, len, res, uncExit);
    gSecT -= uGrid;
    if (uGrid > 0.0) {
        if (r0 == TRACE_HIT) res.t += uGrid;
        if (uncExit < 1e29) uncExit += uGrid;
    }
    return r0;
}

int traceLodRegions(int lod, float3 p0, float3 d, float len, inout TraceResult res, out float uncExit) {
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

// The level-0 grid's id at camera-relative p: -1 where it has no data (outside its window, a chunk without data), else
// the voxel's material (0: air)
int vgVoxel(float3 p) {
    if (!vgOn()) return -1;
    int3 v = int3(floor(p)) + frame.camInt.xyz - frame.vgOrigin.xyz * 16;
    int X = int(frame.vgDims.x), Y = int(frame.vgDims.y), Z = int(frame.vgDims.z);
    if (v.x < 0 || v.y < 0 || v.z < 0 || v.x >= X * 16 || v.y >= Y * 16 || v.z >= Z * 16) return -1;
    int3 c = v >> 4;
    uint cw = vgChunks[vgCell(c)];
    if ((cw & 3u) == VG_UNLOADED) return -1;
    if ((cw & 3u) == VG_EMPTY) return 0;
    int3 l = v & 15;
    uint i = uint(l.x + 16 * (l.z + 16 * l.y));
    return int((vgPool[(cw >> 2) * 2048u + (i >> 1)] >> (16u * (i & 1u))) & 0xFFFFu);
}

// Solid voxel at camera-relative point p in a given LOD (false where that LOD has no data).
bool occupiedAt(int lod, float3 p) {
    if (lod == 0) {
        int g = vgVoxel(p);
        if (g >= 0) return g != 0;
    }
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
    if (lod == 0) {
        int g = vgVoxel(p);
        if (g >= 0) return g != 0 ? uint(g) : 0xFFFFFFFFu;
    }
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
        // (ATLAS_ABLATE bits 16-18, measurements: the groups start from that level, to look at a coarse level up close)
        int want = gGroups ? min(int((gAblate >> 16u) & 7u), lodCount - 1)
                           : int(clamp(floor(log2(max(t * pixelAngle, 1.0))), 0.0, float(lodCount - 1)));
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

// The colour the offline vanilla look shows for a hit (FRAME_DEBUG_ALBEDO, linear): its surface card, else its albedo
// times its face's vanilla shade.
float3 vanillaLook(float3 o, float3 dir, float tHit, TraceResult r) {
    float R = frame.camFrac.w;
    float k = R > 0 ? dot(dir.xz, dir.xz) / (2.0 * R) : 0.0;
    float3 slope = dir + float3(0, 2.0 * k * tHit, 0);
    int ax = r.axis;
    if (ax < 0) ax = abs(slope.y) > abs(slope.x) && abs(slope.y) > abs(slope.z) ? 1 : (abs(slope.x) > abs(slope.z) ? 0 : 2);
    float3 fn0 = float3(ax == 0 ? (slope.x > 0 ? -1.0 : 1.0) : 0.0, ax == 1 ? (slope.y > 0 ? -1.0 : 1.0) : 0.0,
                        ax == 2 ? (slope.z > 0 ? -1.0 : 1.0) : 0.0);
    float3 pw = worldAt(o, dir, k, tHit) + float3(frame.camInt.xyz);
    int3 blk = int3(floor(pw - fn0 * 0.01));
    int f = ax == 1 ? (fn0.y < 0.0 ? 0 : 1) : ax == 2 ? (fn0.z < 0.0 ? 2 : 3) : (fn0.x < 0.0 ? 4 : 5);
    const float SHADE[6] = { 0.5, 1.0, 0.8, 0.8, 0.6, 0.6 };
    uint card = cardOf(blk, f);
    if (card == 0u) return (r.hasAlbedo != 0u ? r.albedo : palette[r.mat].albedoRough.rgb) * pow(SHADE[f], 2.2);
    int a1 = (ax + 1) % 3, a2 = (ax + 2) % 3;
    float3 lp = pw - float3(blk);
    float fp = tHit * frame.params.w / max(abs(dot(dir, fn0)), 0.2);
    return cardColour(card, clamp(lp[a1], 0.0, 0.9999), clamp(lp[a2], 0.0, 0.9999), fp);
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
static float gSecHitT = 0.0;  // where the last secondary ray was stopped (blocks from its origin; set on a hit)
static uint gSecHitMat = 0xFFFFFFFFu;  // and what stopped it: its material, the axis of the face it entered, its level
static int gSecHitAxis = 1;
static int gSecHitLod = 0;
// gSecCone (the near light's rays): the ray widens as a cone, a level coarser each time it has gone gSecCone voxels of
// its level (lod 0 to gSecCone blocks, lod 1 to twice that, ... up to SEC_CONE_LOD): what shadows a point from far off
// needs no finer voxels than its distance resolves, and a 2048-block ray through a forest at lod 0 cost 3 ms a frame at
// 1280x800 (the near light's sun ray; its bounce's as much again). 0: the ray keeps its level (the far field's rays).
static float gSecCone = 0.0;
static const int SEC_CONE_LOD = 4;
float traceSecondary(float3 p, float3 dir, float maxT, int lod) {
    TraceResult res;
    res.t = 0; res.mat = 0; res.axis = -1; res.lod = 0; res.steps = 0; res.dither = 0.5; res.albedo = float3(0, 0, 0); res.hasAlbedo = 0u;
    int lodCount = int(frame.dims.w);
    bool prev = gSecondary;
    gSecondary = true;
    secCount(7u, 0);
    gTrans = 1.0;
    gThrough = 0.0;
    gStopFoliage = false;
    gWaterChord = 0.0;
    float t = 0;
    float vis = 1.0;
    for (int seg = 0; seg < 48; seg++) {
        if (t >= maxT) break;
        float s = float(1 << lod);
        float len = min(192.0 * s, maxT - t);
        if (gSecCone > 0.0 && lod < SEC_CONE_LOD && lod + 1 < lodCount) {
            float edge = gSecCone * s;
            if (t >= edge) { lod++; continue; }
            len = min(len, edge - t);
        }
        float uncExit;
        gSecT = t;
        int r = traceLod(lod, p + dir * t, dir, len, res, uncExit);
        if (r == TRACE_HIT) {
            gStopFoliage = gTrans <= 0.0;
            gSecHitT = t + res.t;
            gSecHitMat = res.mat;
            gSecHitAxis = res.axis;
            gSecHitLod = res.lod;
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

// Whether camera-relative p lies under solid cover (rock over a cave, a roof; leaves are no cover): a ray straight up
// stopped by something solid before the world top. A point the key light reaches is never under it (callers ask only
// where it does not). Clobbers traceSecondary's globals.
bool solidRoof(float3 p, int lod) {
    // (never as a cone: a roof is found at the level the point is lit at, rock overhead at a coarse level can be open)
    float cone = gSecCone;
    gSecCone = 0.0;
    bool roof = traceSecondary(p, float3(0, 1, 0), 2048.0, lod) <= 0.0 && !gStopFoliage;
    gSecCone = cone;
    return roof;
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

// One bounce of the key light (docs/ATLAS-RENDERER-PLAN.md, "One bounce"): what the surface the last secondary ray
// (from o along w) met sends back along it, as the look lights that surface: its albedo x (the key light x N.L through one
// more secondary ray from it, plus its sky where it is open). inOpen: the shading point is out in the open, and so is
// what it sees; else (under cover) a surface the key light does not reach sends its sky only where no solid cover lies
// over it (a pit's shaded floor past a tunnel's mouth; a cave's walls send nothing). Clobbers traceSecondary's globals.
float3 bounceFrom(float3 o, float3 w, float3 sun, bool inOpen) {
    uint mat = gSecHitMat;
    int ax = clamp(gSecHitAxis, 0, 2), hl = gSecHitLod;
    float hs = float(1 << hl);
    float3 nh = float3(0, 0, 0);
    nh[ax] = w[ax] > 0.0 ? -1.0 : 1.0;
    // on the plane of the face the ray entered (a voxel boundary of the hit's level), a hair outside it
    float3 ci = float3(frame.camInt.xyz);
    float3 hp = o + w * gSecHitT;
    hp[ax] = round((hp[ax] + ci[ax]) / hs) * hs - ci[ax] + nh[ax] * (0.02 * hs);
    float3 alb = mat == 0xFFFFFFFFu ? float3(0.3, 0.3, 0.3) : palette[mat].albedoRough.rgb;
    float3 L = keyLightDir(sun);
    float ndl = dot(nh, L);
    float vis = (frame.dims.z & FRAME_SHADOWS) != 0u ? 0.0 : 1.0;
    if (ndl > 0.0 && vis == 0.0) {
        vis = traceSecondary(hp + L * (0.05 * hs), L, 2048.0, hl);
        if ((frame.dims.z & FRAME_CLOUDS) != 0u && vis > 0.0) vis *= cloudShadow(hp, L);
    }
    float sky = 1.0;
    if (!inOpen && vis <= 0.0 && solidRoof(hp, hl)) sky = 0.0;
    return alb * (keyLightRadiance(sun) * (max(ndl, 0.0) * vis) + skyAmbient(nh, sun) * (LOOK_AMBIENT * sky));
}

// The sky a far surface sees (docs/ATLAS-LOD.md, "Where atlas_core's lighting falls short" 1): the pack's skyAmbient
// split into its two radiances, sky over the horizon and ground under it, each seen through the hemisphere's
// visibility: SKY_RAYS cosine-weighted secondary rays a pixel and frame, SKY_REACH voxels of the hit's level long (a
// cone that widens with the level, so far away valleys and ridges darken, up close eaves and crevices), stratified
// over the TAA's frames (R2 per pixel rotation). The constant FAR_AMBIENT_OCCLUSION and the 4-tap AO stand in when
// the frame asks for cheap light (FRAME_LIGHT_LOW), as before.
// enclosed (the point lies under solid cover: solidRoof) the rays reach ENCLOSED_REACH voxels: in a cave, a mine or a
// hall the sky is what its mouth or its windows show, not whatever lies past 16 blocks of the floor (a cavern's far
// wall, an unlit tunnel). In the open the reach stays short: what hides the sky from a valley floor further out is sunlit
// ground, whose bounce the short reach stands in for. withBounce (the near light): a ray that meets a surface brings
// back its light (bounceFrom, into gBounce for shadeSurfaceAmb); the far field keeps the look's constant (its level-0
// pixels bouncing too cost 1.8 ms more a frame at 1080p, 43 % of the far pass, where the near light's costs 0.2 ms).
static const int SKY_RAYS = 1;
static const float SKY_REACH = 16.0;
static const float ENCLOSED_REACH = 64.0;
float3 skyVisible(uint2 px, float3 o, float3 faceN, int lod, float3 sun, bool enclosed, bool withBounce, out float vis) {
    float3 skyUp = frame.airSkyUp.xyz, ground = frame.airGround.xyz;  // skyAmbient's two radiances (the air's light)
    uint fi =frame.jitter.z > 0.5 ? (frame.temporal.x & 15u) : 0u;  // a 16-frame cycle: a still pixel converges to one stratified set
    float ru = pixelDither(px, 11u), rv = pixelDither(px, 13u);
    float s = float(1 << lod);
    uint ab = asuint(frame.jitter.w);  // (ATLAS_ABLATE 8192: two rays, 16384: half the reach; measurements)
    int rays = (ab & 8192u) != 0u ? 2 : SKY_RAYS;
    float reach = (ab & 16384u) != 0u ? 0.5 * SKY_REACH : SKY_REACH;
    // one bounce (bounceFrom): a ray that meets a surface brings back that surface's own light instead of none
    // (ATLAS_ABLATE 524288: none, the look's constant bounce alone, as before)
    bool bounce = withBounce && (ab & 524288u) == 0u;
    float3 back = float3(0, 0, 0);
    float traced = 0.0;
    float3 sum = float3(0, 0, 0), seen = float3(0, 0, 0);
    vis = 0.0;
    for (int k = 0; k < rays; k++) {
        uint j = fi * uint(rays) + uint(k);
        float u = frac(ru + float(j) * 0.7548776662), v = frac(rv + float(j) * 0.5698402909);
        float3 w = cosineDir(faceN, u, v);
        float T = traceSecondary(o, w, reach * s, lod);
        if (T <= 0.0 && bounce) {
            back += bounceFrom(o, w, sun, !enclosed);
            traced += 1.0;
        } else if (enclosed && T > 0.0) {
            // on to the enclosed reach; what stops it there sends its light back when it is lit or open to the sky (a
            // pit's sunlit floor beyond a tunnel's mouth), none under cover (a cavern's far wall). Without the bounce:
            // such an open face counts as the sky, what the short reach stands in for in the open
            float3 o2 = o + w * (reach * s);
            float T2 = traceSecondary(o2, w, (ENCLOSED_REACH - reach) * s, lod);
            if (T2 <= 0.0 && bounce) {
                back += T * bounceFrom(o2, w, sun, false);
                traced += T;
            } else if (T2 <= 0.0 && !gStopFoliage && !solidRoof(o2 + w * (gSecHitT - 0.05 * s), lod)) {
                T2 = 1.0;
            }
            T *= T2;
        }
        float3 rad = w.y >= 0.0 ? skyUp : ground;
        sum += rad * (1.0 - T);
        seen += rad * T;
        vis += T;
    }
    vis /= float(rays);
    if (bounce) gBounce = float4(back / float(rays), 1.0 - traced / float(rays));
    // under cover: what the rays see, unbiased (the control variate below, clamped at 0 per sample, leaves a wall that
    // sees nothing a quarter of the sky's excess over the ground: a closed cave's walls glowed); the resolve's filter
    // and the TAA average its noise
    if (enclosed) return seen / float(rays);
    // a control variate: the open hemisphere's light is known exactly (skyAmbient: the cosine-weighted split of sky
    // and ground radiance about the face), the rays only estimate what is taken from it, so an open face (most of the
    // far field) is exact and noise-free, and only occluded ones carry the rays' noise
    return max(lerp(ground, skyUp, faceN.y * 0.5 + 0.5) - sum / float(rays), float3(0, 0, 0));
}

// The camera under solid cover (the eye in a cave, a mine, a hall), asked once per pixel at most (coherent: the same ray
// for every pixel). With a surface under cover too, the air between them is not sunlit: no aerial inscatter (a cave's far
// end showed the sky's haze, opened up by the eye's exposure underground), the extinction stays.
static int gEyeRoofed = -1;
bool eyeRoofed() {
    if (gEyeRoofed < 0) gEyeRoofed = solidRoof(frame.camFrac.xyz, 0) ? 1 : 0;
    return gEyeRoofed == 1;
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

// Water as a medium (as near_forward.fsh's near water): the bed seen through the surface along the refracted ray,
// absorbed over the path (WATER_ABSORB a block, up to five times that in murky water: the biome's water colour, the
// material's albedo, dull and green), the water's own scattering of the sky and the sun filling what is lost.
// 64 blocks of water or more: deep, its own colour alone. The far water was an opaque sheet (the near field's clear
// shallows ended at the hand-off in a stark line).
float waterMurk(float3 tint) {
    float mx = max(tint.r, max(tint.g, tint.b)), mn = min(tint.r, min(tint.g, tint.b));
    float sat = mx > 1e-3 ? (mx - mn) / mx : 0.0;
    float greenish = saturate((tint.g - tint.b) * 3.0 + 0.3);
    return saturate((1.0 - sat) * 1.3 + greenish * 0.6 - 0.35);
}
float3 waterBody(float3 p, float3 dir, float3 wn, int lod, float3 sun, float3 tint) {
    // the real sections' water carries Minecraft's grey water texture (the biome tints it near): grey is no murk, clear
    // lake water then (a grey patch where the real blocks had arrived)
    {
        float mx = max(tint.r, max(tint.g, tint.b)), mn = min(tint.r, min(tint.g, tint.b));
        if (mx < 1e-3 || (mx - mn) / mx < 0.2) tint = float3(0.05, 0.19, 0.78);
    }
    float3 t = refract(dir, wn, 1.0 / 1.333);
    if (dot(t, t) < 1e-6) t = float3(dir.x, -abs(dir.y), dir.z);
    t = normalize(t);
    float s = float(1 << lod);
    float vis = traceSecondary(p - wn * (0.05 * s) + t * 0.01, t, 64.0, lod);
    float path = vis > 0.0 ? 64.0 : gSecHitT;
    float3 up = float3(0, 1, 0);
    float3 L = keyLightDir(sun);
    float3 bed = float3(0, 0, 0);
    if (vis <= 0.0 && gSecHitMat != 0xFFFFFFFFu) {
        // the bed lit simply: the sun through the water above it (as deep as the path down, near enough), the sky
        float3 a = palette[gSecHitMat].albedoRough.rgb;
        float down = path * max(-t.y, 0.2);
        float3 Tsun = exp(-WATER_ABSORB * down);
        bed = a * (keyLightRadiance(sun) * max(L.y, 0.0) * Tsun + skyAmbient(up, sun) * LOOK_AMBIENT * 0.6 * Tsun);
    }
    float mk = waterMurk(tint);
    float3 T = exp(-WATER_ABSORB * (1.0 + 4.0 * mk) * path - 0.04 * (1.0 + 6.0 * mk) * path);
    float3 inLight = (skyAmbient(up, sun) * LOOK_AMBIENT + keyLightRadiance(sun) * max(L.y, 0.0) * 0.5)
                   * lerp(float3(0.05, 0.11, 0.13), float3(0.09, 0.11, 0.08), mk) * saturate(tint * 2.0);
    return bed * T + inLight * (float3(1, 1, 1) - T);
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
        float T = dot(aerialT(dir, dist), float3(0.2126, 0.7152, 0.0722));
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
static const float BLOCK_LIGHT_SCALE = 0.001;  // physical: a lamp lights its block at ~1/1000 of noon (atlas_look.glsl the same)
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
        sum += hue * (E * vis * occ * BLOCK_LIGHT_SCALE);
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
    airBegin();
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
        AirPath air = aerialPathTo(dir, cl.firstHit);
        c.rgb = cl.scatter * air.transmittance + air.inscatter * (1.0 - cl.transmittance);
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
    airBegin();
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

// The near light (FRAME_NEAR_LIGHT, atlas.h): what reaches a WHITE diffuse surface of the rasterised near field at view
// distance nd along this pixel's ray, its normal from the info texel, lit with farMain's own terms for a land hit at
// LOD 0 (below): the key light x N.L through the traced secondary ray and the cloud shadow, the traced sky plus the
// pack's floor, the far lights; the cheap terms under FRAME_LIGHT_LOW. Block light is the host's. The albedo factors out
// of shadeSurfaceAmb exactly (a diffuse class, no emission): albedo x this == farMain's colour before the haze, but for
// the one bounce (skyVisible withBounce), which only the near light traces.
// rgb: irradiance; a: the key light's visibility. gNearRoofed: the surface lies under solid cover (solidRoof).
static bool gNearRoofed = false;
float4 nearLightAt(uint2 px, float3 o, float3 dir, float nd, uint info, float3 sun) {
    float R = frame.camFrac.w;
    float k = R > 0 ? dot(dir.xz, dir.xz) / (2.0 * R) : 0.0;
    float3 p = worldAt(o, dir, k, nd);  // (the near field's vertices carry the matching curvature drop)
    airAt(p.y + float(frame.camInt.y));
    float3 N = octDecode(float2(float((info >> 16) & 255u), float(info >> 24)) / 255.0);
    uint ablate = asuint(frame.jitter.w);
    bool cheapLight = (frame.dims.z & FRAME_LIGHT_LOW) != 0u || (ablate & 256u) != 0u;
    // the secondary rays' origin: the surface nudged off along its normal as a groups hit's is, and like a drawn group's
    // face out of a solid voxel of the voxel path upwards (the near mesh and atlas's voxels may disagree by a hair)
    float3 ci = float3(frame.camInt.xyz);
    float3 so = p + N * max(0.02, nd * 3e-5);
    if (!cheapLight) {
        for (int i = 0; i < 3; i++) {
            uint om = materialAt(0, so);
            if (om == 0xFFFFFFFFu || secondaryShapeMisses(om, frac(so + ci), float3(0, 1, 0)) || secondarySigma(om, uint(palette[om].emisClass.y)) >= 0.0)
                break;
            so.y = floor(so.y + ci.y) + 1.02 - ci.y;
        }
    }
    // still inside a solid voxel: atlas's world disagrees with the drawn one here (the tiles' stand-in houses, solid
    // and wider than the real ones, until the loaded chunks' blocks are up; a snow layer atlas holds as a block). Traced
    // from inside, the sun and sky rays were stopped at once: sunlit walls black. Lit untraced instead (the key light
    // by the normal, the open sky's ambient), as the cheap light does
    bool untrusted = false;
    if (!cheapLight) {
        uint om = materialAt(0, so);
        untrusted = om != 0xFFFFFFFFu && !secondaryShapeMisses(om, frac(so + ci), float3(0, 1, 0)) &&
                    secondarySigma(om, uint(palette[om].emisClass.y)) < 0.0;
    }
    float3 L = keyLightDir(sun);
    float sunVis = 1.0;
    // (no cone: past 32 blocks the coarser levels' fat crowns cast blobby shadows off the trees; it saved 0.4 ms)
    gSecCone = 0.0;
    if ((frame.dims.z & FRAME_SHADOWS) != 0 && (ablate & 4u) == 0u) {
        if (dot(N, L) <= 0.0) sunVis = 0.0;
        else if (untrusted) sunVis = eyeRoofed() ? 0.0 : 1.0;  // (in a cave: rock, most likely)
        else if (cheapLight) sunVis = traceShadow(p + N * 1.01 + L * 0.5, L, 0);
        else sunVis = traceSecondary(so + L * 0.05, L, 2048.0, 0);
    }
    // the sun reached it through water alone: a = 1 + the water's chord (the resolve draws its caustics at full rate)
    float waterChord = sunVis > 0.0 && gWaterChord > 0.05 && sunVis >= 0.98 * exp(-WATER_SIGMA * gWaterChord) ? gWaterChord : 0.0;
    if ((frame.dims.z & FRAME_CLOUDS) != 0 && sunVis > 0.0) sunVis *= cloudShadow(p, L);
    float3 ambient;
    gNearRoofed = false;
    if (untrusted && eyeRoofed()) {
        ambient = FAR_AMBIENT_FLOOR;  // the eye under rock: the floor alone, under cover (unlit air in front of it)
        gNearRoofed = true;
    } else if (cheapLight || untrusted) {
        ambient = skyAmbient(N, sun) * FAR_AMBIENT_OCCLUSION + FAR_AMBIENT_FLOOR;
    } else {
        float skyVis;
        // (ATLAS_ABLATE 512, measurements: never enclosed, the sky's short reach and the haze everywhere as before)
        gNearRoofed = sunVis <= 0.0 && (ablate & 512u) == 0u && solidRoof(so, 0);
        ambient = skyVisible(px, so, N, 0, sun, gNearRoofed, true, skyVis) + FAR_AMBIENT_FLOOR;
    }
    // under water: the sky's light is what the water above lets down, red first then green (the sky rays reach 16 blocks
    // and saw the surface's light undimmed past that): its depth from the sun ray's way out of the water
    if (waterChord > 0.0) ambient *= exp(-WATER_ABSORB * (waterChord * max(L.y, 0.2)));
    MaterialGpu white;
    white.albedoRough = float4(1, 1, 1, 0.8);
    white.emisClass = float4(0, 0, 0, 0);
    float3 irr = shadeSurfaceAmb(float3(1, 1, 1), white, N, dir, sun, sunVis, 0u, ambient, float3(0, 0, 0));
    gSecCone = 0.0;
    if ((ablate & 8u) == 0u) irr += farLightsOn(px, p, so, N, 0, sun, false, cheapLight);
    // the host composites albedo x this x T + inscatter with one transmittance (its luminance): the air's colour
    // through it (the blue lost first) rides in the light
    float3 Tr = aerialT(dir, nd);
    irr *= Tr / max(dot(Tr, float3(0.2126, 0.7152, 0.0722)), 1e-4);
    return float4(irr, waterChord > 0.0 ? 1.0 + waterChord : sunVis);
}

// ---- the near light at half resolution (FRAME_NEAR_SPLIT) -------------------------------------------------------
// The near light's rays (the sun's, the sky's, the bounce's) for every pixel of the near field cost 5 ms a frame at
// 1280x800 in a forest town (Falkreath), most of atlas's frame: they scale with the pixels traced. nearLightMain traces
// one pixel of each 2 x 2 block, a different one each frame of the TAA's cycle (so a still view gathers all four),
// nearUpsampleMain fills the other three from the traced pixels around them on the same surface (the near field's own
// depth and normal: a shadow edge on one face stays put, light does not leak across a silhouette). A traced pixel under
// cover carries a = -1 (the publish pass clamps it to 0): with the eye under cover too its air is unlit.
uint2 nearSplitOffset() {
    uint k = frame.jitter.z > 0.5 ? (frame.temporal.x & 3u) : 0u;
    // (0,0) (1,1) (1,0) (0,1): diagonal pairs first, so two frames already cover both rows and columns
    return uint2((k == 1u || k == 2u) ? 1u : 0u, (k == 1u || k == 3u) ? 1u : 0u);
}

float3 pixelRay(uint2 px, uint w, uint h) {
    float2 ndc = float2((float(px.x) + 0.5) / float(w) * 2.0 - 1.0, 1.0 - (float(px.y) + 0.5) / float(h) * 2.0)
               + frame.jitter.xy;
    return normalize(unproject(frame, ndc));
}

[shader("compute")]
[numthreads(8, 8, 1)]
void nearLightMain(uint3 id : SV_DispatchThreadID) {
    airBegin();
    gEyeRoofed = -1;
    uint w = frame.dims.x, h = frame.dims.y;
    uint2 q = id.xy * 2u + nearSplitOffset();
    if (q.x >= w || q.y >= h) return;
    uint pix = q.y * w + q.x;
    float nd = nearDepth[pix];
    if (!(nd < 1e29)) return;  // not the near field's (farMain wrote its zero)
    gAblate = asuint(frame.jitter.w);
    gSecAblate = gAblate;
    gHead = groupData[0];  // (the material shapes: secondary rays, the grid's level 0)
    float4 nl = nearLightAt(q, frame.camFrac.xyz, pixelRay(q, w, h), nd, nearInfo[pix], frame.sunDir.xyz);
    if (gNearRoofed) nl.a = -1.0;
    outNearLight[pix] = nl;
}

float3 nearInfoNormal(uint info) {
    return octDecode(float2(float((info >> 16) & 255u), float(info >> 24)) / 255.0);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void nearUpsampleMain(uint3 id : SV_DispatchThreadID) {
    gEyeRoofed = -1;
    uint w = frame.dims.x, h = frame.dims.y;
    if (id.x >= w || id.y >= h) return;
    uint pix = id.y * w + id.x;
    float nd = nearDepth[pix];
    if (!(nd < 1e29)) return;
    uint2 off = nearSplitOffset();
    bool traced = (id.x & 1u) == off.x && (id.y & 1u) == off.y;
    float roofed;
    if (traced) {
        roofed = outNearLight[pix].a < 0.0 ? 1.0 : 0.0;
    } else {
        // the traced pixels of the 3 x 3 blocks around: weighted by distance on screen, depth and normal alike
        float3 n = nearInfoNormal(nearInfo[pix]);
        int2 b = int2(id.xy >> 1);
        float4 sum = float4(0, 0, 0, 0);
        float wsum = 0.0, rsum = 0.0, best = 1e30;
        float4 nearest = float4(0, 0, 0, 0);
        float nearestRoof = 0.0;
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                int2 r = (b + int2(i, j)) * 2 + int2(off);
                if (r.x < 0 || r.y < 0 || r.x >= int(w) || r.y >= int(h)) continue;
                uint rp = uint(r.y) * w + uint(r.x);
                float rd = nearDepth[rp];
                if (!(rd < 1e29)) continue;
                float4 v = outNearLight[rp];
                float isRoof = v.a < 0.0 ? 1.0 : 0.0;
                v.a = max(v.a, 0.0);
                float dz = abs(rd - nd);
                if (dz < best) { best = dz; nearest = v; nearestRoof = isRoof; }
                float2 dp = float2(r) - float2(id.xy);
                float ws = exp(-0.5 * dot(dp, dp));
                float wz = exp(-dz / (0.02 * nd + 0.05));
                float wn = pow(max(dot(n, nearInfoNormal(nearInfo[rp])), 0.0), 16.0);
                float wt = ws * wz * wn;
                sum += v * wt;
                rsum += isRoof * wt;
                wsum += wt;
            }
        }
        // nothing on the same surface around (a lone pixel of a thin thing): the nearest in depth
        float4 v = wsum > 1e-4 ? sum / wsum : nearest;
        roofed = wsum > 1e-4 ? rsum / wsum : nearestRoof;
        outNearLight[pix] = v;
    }
    if (roofed > 0.5 && eyeRoofed()) outColor[pix].rgb = float3(0, 0, 0);  // under cover at both ends: unlit air
}

// The near light's own history (FRAME_NEAR_SPLIT, after nearUpsampleMain): each pixel's light is one ray of each kind,
// traced at a quarter of the pixels, and the host's TAA cannot average that noise (its neighbourhood clamp throws it
// out): sky-lit walls under eaves and shaded ground shimmered frame to frame (6 % of the pixels of a still view moved
// by more than 8 levels). Here the surface point is reprojected into the previous frame (its camera and
// view-projection, as the clouds' are) and blended with the light found there, where the previous frame's surface lay
// at the same distance (else the point was hidden or off screen then: this frame's light alone). An exponential
// average of about NEAR_HISTORY frames: still views converge, a moving camera keeps its history on the surfaces.
static const float NEAR_HISTORY = 8.0;

[shader("compute")]
[numthreads(8, 8, 1)]
void nearTemporalMain(uint3 id : SV_DispatchThreadID) {
    uint w = frame.dims.x, h = frame.dims.y;
    if (id.x >= w || id.y >= h) return;
    uint pix = id.y * w + id.x;
    float nd = nearDepth[pix];
    if (!(nd < 1e29)) return;
    float4 cur = outNearLight[pix];
    cur.a = max(cur.a, 0.0);  // (the under-cover mark has done its work in the upsample)
    uint count, stride;
    prevNearLight.GetDimensions(count, stride);
    float4 hist = float4(0, 0, 0, 0);
    float hw = 0.0;
    if (count >= w * h) {
        float3 dir = pixelRay(id.xy, w, h);
        float R = frame.camFrac.w;
        float k = R > 0 ? dot(dir.xz, dir.xz) / (2.0 * R) : 0.0;
        float3 X = worldAt(frame.camFrac.xyz, dir, k, nd) - frame.prevCam.xyz;
        float4 clip = frame.prevViewProj[0] * X.x + frame.prevViewProj[1] * X.y + frame.prevViewProj[2] * X.z + frame.prevViewProj[3];
        if (clip.w > 1e-4) {
            float2 pn = clip.xy / clip.w;
            float hx = (pn.x * 0.5 + 0.5) * float(w) - 0.5, hy = (0.5 - pn.y * 0.5) * float(h) - 0.5;
            float dist = length(X), tol = 0.03 * dist + 0.1;
            int2 b = int2(floor(float2(hx, hy)));
            float2 fr = float2(hx, hy) - float2(b);
            for (int j = 0; j <= 1; j++) {
                for (int i = 0; i <= 1; i++) {
                    int2 q = b + int2(i, j);
                    if (q.x < 0 || q.y < 0 || q.x >= int(w) || q.y >= int(h)) continue;
                    uint qp = uint(q.y) * w + uint(q.x);
                    if (abs(prevSurface[qp] - dist) > tol) continue;  // another surface there then
                    float4 v = prevNearLight[qp];
                    if (dot(v.rgb, float3(1, 1, 1)) <= 0.0 && v.a <= 0.0) continue;  // it was not lit then
                    float bw = (i == 0 ? 1.0 - fr.x : fr.x) * (j == 0 ? 1.0 - fr.y : fr.y);
                    hist += v * bw;
                    hw += bw;
                }
            }
        }
    }
    // a traced pixel (one in four each frame) counts for more than the upsampled ones in between
    uint2 off = nearSplitOffset();
    bool traced = (id.x & 1u) == off.x && (id.y & 1u) == off.y;
    float a = traced ? 2.0 / NEAR_HISTORY : 1.0 / NEAR_HISTORY;
    if (hw > 0.25) cur = lerp(hist / hw, cur, a);
    outNearLight[pix] = cur;
}

// (ATLAS_FRAME_PRIMARY_NEAR) the host's NDC depth (clip z / w through frame.viewProj, the inverse of its
// invViewProj) of the point at view distance t along dir; nothing there (t 1e30): the value at infinity along dir
float hostDepth(float3 dir, float t) {
    float3 X = dir * min(t, 1e7);
    float4 clip = frame.viewProj[0] * X.x + frame.viewProj[1] * X.y + frame.viewProj[2] * X.z + frame.viewProj[3];
    float z = abs(clip.w) > 1e-20 ? clip.z / clip.w : -1.0;
    // past the host's far plane (the far field goes on to the horizon): minus the distance, so the host still knows
    // how far (its haze) and that it is behind everything it rasters (the sky: -1e7)
    return z >= 0.0 && z <= 1.0 ? z : -min(t, 1e7);
}

// (ATLAS_FRAME_PRIMARY_NEAR / _LIT) a primary ray in the near field passes water: the host rasters the water surface
// itself (its waves, reflections, refraction of the bed atlas draws under it)
bool primaryPassesWater(uint cls, float t) {
    return !gSecondary && cls == 2u && t < frame.params.x && (frame.dims.z & (FRAME_PRIMARY_NEAR | FRAME_PRIMARY_NEAR_LIT)) != 0u;
}

[shader("compute")]
[numthreads(8, 8, 1)]
void farMain(uint3 id : SV_DispatchThreadID) {
    airBegin();
    gEyeRoofed = -1;
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
    // (ATLAS_FRAME_PRIMARY_NEAR / _LIT) atlas's primary rays are the near field: every pixel traced from the camera
    bool primaryNear = (frame.dims.z & (FRAME_PRIMARY_NEAR | FRAME_PRIMARY_NEAR_LIT)) != 0u;
    if ((frame.dims.z & FRAME_NEAR_DEPTH) != 0 && !primaryNear) {
        float nd = nearDepth[pix];
        // (with FRAME_NEAR_LIGHT the host's near pass drew every covered pixel, however far: all of them are its, lit
        // here; past nearFieldEnd they were traced as far and that colour landed in the host's inscatter, the far
        // field's own trees and tiles over the near pass's)
        if (nd < frame.params.x || ((frame.dims.z & FRAME_NEAR_LIGHT) != 0u && nd < 1e29)) {
            // (FRAME_NEAR_LIGHT) the near surface lit here, and the air in front of it as (inscatter, transmittance):
            // the host composites albedo x nearLight x a + rgb
            bool nearLit = (frame.dims.z & FRAME_NEAR_LIGHT) != 0u;
            float4 c = float4(0, 0, 0, 0);
            if (nearLit) {
                // (the host's composite takes one transmittance: the path's own, luminance-weighted; chromatic only
                // past the near field, where the far field composites itself)
                AirPath air = aerialPathTo(dir, nd);
                c = float4(air.inscatter, dot(air.transmittance, float3(0.2126, 0.7152, 0.0722)));
                // (FRAME_NEAR_SPLIT: the light comes from nearLightMain, the unlit air from nearUpsampleMain)
                if ((frame.dims.z & FRAME_NEAR_SPLIT) == 0u) {
                    gAblate = asuint(frame.jitter.w);
                    gSecAblate = gAblate;
                    gSecMode = (gAblate >> 23) & 7u;
                    gSecCount = 0u;
                    gHead = groupData[0];  // (the material shapes: secondary rays, the grid's level 0)
                    outNearLight[pix] = nearLightAt(id.xy, o, dir, nd, nearInfo[pix], sun);
                    if (gNearRoofed && eyeRoofed()) c.rgb = float3(0, 0, 0);  // under cover at both ends: unlit air
                }
            }
            float cd = 1e30;
            if ((frame.dims.z & FRAME_CLOUDS) != 0) {
                float4 cl;
                float clDepth;
                sampleClouds(id.xy, cl, clDepth);
                if (clDepth < nd) {
                    // the cloud over the haze in front of the surface (cl: premultiplied, a = transmittance)
                    c = nearLit ? float4(cl.rgb + c.rgb * cl.a, c.a * cl.a) : float4(cl.rgb, 1.0 - cl.a);
                    if (cl.a < 0.99) cd = clDepth;
                }
            }
            outColor[pix] = c;
            outNormal[pix] = 0;
            outDepth[pix] = cd;
            outSurface[pix] = nd;
            counters[pix] = gSecCount;  // (0 unless the secondary rays' counter is asked for: ATLAS_ABLATE bits 23-25)
            return;
        }
    }
    // the near field drew nothing here (a chunk still loading): fill from the camera so the place shows the far
    // field instead of a hole (only in the open: FRAME_FILL_NEAR)
    float tStart = primaryNear ? 0.0 : frame.params.x;
    // (2e30: every sample of the pixel's footprint was sky; 1e30 is a near silhouette's edge, never filled)
    // and only below the horizon: a missing chunk is a hole in the ground; above the horizon an empty near pixel is the
    // real sky, and filling it drew the far field's own copies of the buildings and trees around the camera into it
    // (Helgen's keep as a dark slab in the sky)
    if (!primaryNear && (frame.dims.z & FRAME_NEAR_DEPTH) != 0 && (frame.dims.z & FRAME_FILL_NEAR) != 0 && nearDepth[pix] >= 1.5e30
        && dir.y < 0.0)
        tStart = 2.0;
    // the content-shaped groups for this pixel's primary ray (ATLAS_FRAME_GROUPS): the footprint per block of distance
    // and the threshold, dithered per pixel (and per frame under the host's TAA, which averages the levels)
    gGroups = (frame.dims.z & FRAME_GROUPS) != 0u;
    gHead = groupData[0];  // (the material shapes: groups, secondary rays, the grid's level 0)
    gAblate = asuint(frame.jitter.w);
    gGroupPa = frame.params.w * frame.params.z;
    gGroupTol = 0.7 + 0.6 * pixelDither(id.xy, phase + 5u);
    float tHit = traceView(o, dir, tStart, frame.params.y, lodDither, res);
    // (PRIMARY_NEAR) near water is the host's to raster: a primary ray meeting it in the near field goes on to what is
    // under it (the groups' cells and the SVDAG have no near-only pass, so a second trace with water skipped)
    if (primaryNear && tHit >= 0.0 && tHit < frame.params.x && uint(palette[res.mat].emisClass.y) == 2u) {
        gSkipWater = true;
        tHit = traceView(o, dir, tStart, frame.params.y, lodDither, res);
        gSkipWater = false;
    }
    gGroups = false;  // secondary rays (shadows, reflections, block light) keep the old path
    ActorHit ah;
    ah.t = -1.0;
    ah.n = float3(0, 1, 0);
    ah.albedo = float3(1, 1, 1);
    if ((frame.dims.z & FRAME_ACTORS) != 0 && frame.weatherParams.w > 0.5)
        ah = traceActors(o, dir, frame.params.x, tHit >= 0.0 ? tHit : frame.params.y, pixelDither(id.xy, phase + 2u));
    bool actorFirst = ah.t >= 0.0;
    // (ATLAS_FRAME_PRIMARY_NEAR) a hit nearer than nearFieldEnd is the near field's: its surface, lit by the near light
    // (nearLightAt, as the host's raster pixels are under FRAME_NEAR_LIGHT); the G-buffer for the host's resolve, or
    // (_LIT) composited here. atlas.h ATLAS_FRAME_PRIMARY_NEAR has the layout.
    // (_LIT, the unified field) every land hit at any distance takes this one light (traced sun, sky, bounce, haze): no
    // second model past nearFieldEnd, so no line; water past the near field keeps its medium (waterBody) below
    bool oneLight = (frame.dims.z & FRAME_PRIMARY_NEAR_LIT) != 0u && tHit >= 0.0 && uint(palette[res.mat].emisClass.y) != 2u;
    if (primaryNear && !actorFirst && tHit >= 0.0 && (tHit < frame.params.x || oneLight)) {
        MaterialGpu m = palette[res.mat];
        uint cls = uint(m.emisClass.y);
        int ax = res.axis < 0 ? 1 : res.axis;
        float3 n = float3(0, 0, 0);
        n[ax] = dir[ax] > 0.0 ? -1.0 : 1.0;  // the face entered (blocks, near: no smoothing)
        float3 albedo = res.hasAlbedo != 0u ? res.albedo : m.albedoRough.rgb;
        float2 e = octEncode(n);
        uint info = (255u << 8) | (uint(saturate(e.x) * 255.0 + 0.5) << 16) | (uint(saturate(e.y) * 255.0 + 0.5) << 24);
        gSecAblate = gAblate;
        gSecMode = (gAblate >> 23) & 7u;
        gSecCount = 0u;
        float4 nl = nearLightAt(id.xy, o, dir, tHit, info, sun);
        outNearLight[pix] = nl;
        float4 c = float4(albedo, 1.0);
        if ((frame.dims.z & FRAME_PRIMARY_NEAR_LIT) != 0u) {
            AirPath air = aerialPathTo(dir, tHit);
            float T = dot(air.transmittance, float3(0.2126, 0.7152, 0.0722));
            float3 ins = gNearRoofed && eyeRoofed() ? float3(0, 0, 0) : air.inscatter;  // under cover at both ends: unlit air
            c = float4(albedo * nl.rgb * T + ins, 1.0);
        }
        outColor[pix] = c;
        outNormal[pix] = packNormal(n, (cls & 0x7Fu) | 0x80u, nl.a);
        outDepth[pix] = hostDepth(dir, tHit);
        outSurface[pix] = tHit;
        counters[pix] = res.steps;
        return;
    }

    float3 color = float3(0, 0, 0);
    float3 n = float3(0, 1, 0);
    uint cls = 0;
    float depth = 1e30;
    float sunVis = 1.0;
    if (actorFirst) {
        n = ah.n;
        cls = 4u;  // far actor
        airAt(o.y + dir.y * ah.t + float(frame.camInt.y));
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
        airAt(p.y + float(frame.camInt.y));  // the key light at the hit's height (a peak keeps the sun longer)
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
                // translucent water as the game blends it (the offline vanilla look, where the cards are): the card at
                // its alpha over what the ray meets without the water (a second trace, water voxels skipped); the
                // game draws no faces between water blocks, so what is behind is the first thing that is not water
                float wa = cls == 2u && (frame.dims.z & (FRAME_DEBUG_ALBEDO | FRAME_GROUPS)) == (FRAME_DEBUG_ALBEDO | FRAME_GROUPS)
                         ? cardAlpha(card, clamp(lp[a1], 0.0, 0.9999), clamp(lp[a2], 0.0, 0.9999), fp) : 1.0;
                if (wa < 0.99) {
                    TraceResult r2;
                    r2.t = 0; r2.mat = 0; r2.axis = -1; r2.lod = 0; r2.steps = 0; r2.dither = res.dither;
                    r2.albedo = float3(0, 0, 0); r2.hasAlbedo = 0u;
                    gGroups = true;
                    gSkipWater = true;
                    float t2 = traceView(o, dir, tStart, frame.params.y, lodDither, r2);
                    gSkipWater = false;
                    gGroups = false;
                    if (t2 >= 0.0) {  // (blended as the game's framebuffer does: on the sRGB values)
                        const float3 g = float3(1.0 / 2.2, 1.0 / 2.2, 1.0 / 2.2), G = float3(2.2, 2.2, 2.2);
                        albedo = pow(lerp(pow(max(vanillaLook(o, dir, t2, r2), 0.0), g), pow(max(cc, 0.0), g), wa), G);
                    }
                }
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
            // (ATLAS_ABLATE 2097152: the opaque sheet, as before)
            if ((ablate & 2097152u) == 0u && !cheapLight) gWaterBody = waterBody(p, dir, n, res.lod, sun, m.albedoRough.rgb);
        }
        float3 ambient;
        float skyVis = 1.0;
        bool roofed = false;  // under solid cover (a cave past the near field): the sky's long reach, no haze
        if (cls == 2u) {
            ambient = skyAmbient(n, sun) * ao;
        } else if (cheapLight) {
            // the near field's sky light is darkened by sky occlusion, vertex AO and SSAO that a far voxel cannot see:
            // land takes their average (FAR_AMBIENT_OCCLUSION, calibrated against fid-* near / far pairs)
            ambient = skyAmbient(n, sun) * (ao * FAR_AMBIENT_OCCLUSION) + FAR_AMBIENT_FLOOR;
        } else {
            // the sky this face sees (traced visibility of the hemisphere), the pack's floor under it; at levels 0 and
            // 1 under solid cover over the enclosed reach (coarser levels' 16 voxels reach far enough already)
            roofed = res.lod <= 1 && sunVis <= 0.0 && (ablate & 512u) == 0u && solidRoof(so, res.lod);
            ambient = skyVisible(id.xy, so, faceN, res.lod, sun, roofed, false, skyVis) + FAR_AMBIENT_FLOOR;
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
        if (roofed && eyeRoofed()) color *= aerialT(dir, tHit);  // under cover at both ends: unlit air
        else if (!vanillaLight) color = applyAerial(color, dir, sun, tHit, frame);
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
    if ((frame.dims.z & FRAME_NEAR_LIGHT) != 0u) outNearLight[pix] = float4(0, 0, 0, 0);  // not the near field's
    outColor[pix] = float4(color, coverage);
    outNormal[pix] = packNormal(n, cls, sunVis);
    outDepth[pix] = primaryNear ? hostDepth(dir, depth) : depth;  // (PRIMARY_NEAR: the host's NDC depth, every pixel)
    counters[pix] = res.steps;  // per-pixel step count (summed on the host; no atomics so the C++ target matches)
}
