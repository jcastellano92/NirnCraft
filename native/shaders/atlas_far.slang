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

// (-DATLAS_FAR_SPLIT=1: farMain built for the unified field's split frames alone, FRAME_PRIMARY_NEAR_LIT with
// FRAME_NEAR_SPLIT, the game's: the primary rays and their G-buffer; the light is nearLightMain's. Every other path
// (the near depth's, the far field's own light) compiled out, and with it most of the trace's call sites: the host
// draws such frames with it, atlas_farsplit.spv)
#ifndef ATLAS_FAR_SPLIT
#define ATLAS_FAR_SPLIT 0
#endif
static const bool FAR_SPLIT = ATLAS_FAR_SPLIT != 0;
// (the trace's walks and cores: real calls in the general kernels, so a cold driver compile stays at a minute or two;
// inlined into one function in the split frames' farMain, which is built in the background)
#if ATLAS_FAR_SPLIT
#define TRACE_CALL
#else
#define TRACE_CALL [noinline]
#endif

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
// view distance of the near field per pixel (row 0 top); (PRIMARY_NEAR_LIT, FRAME_NEAR_SPLIT) farMain writes its own hits'
[[vk::binding(14, 0)]] RWStructuredBuffer<float> nearDepth;

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
// (PRIMARY_NEAR_LIT, FRAME_NEAR_SPLIT: farMain writes its hits' normals for the light passes)
[[vk::binding(26, 0)]] RWStructuredBuffer<uint> nearInfo;
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
// (FRAME_GPU_LIGHTS) the far lights on the GPU (lightCullMain, lightAdmitMain, lightBinMain): the index as lights.cpp
// packs it (header, cells, records), this frame's candidates (four float4 each: the ActorGpu record the kernel reads,
// then where it lands on screen), and the counters, the score histogram and every screen tile's list
[[vk::binding(34, 0)]] StructuredBuffer<float4> lightSrc;
[[vk::binding(35, 0)]] RWStructuredBuffer<float4> lightCand;
[[vk::binding(36, 0)]] RWStructuredBuffer<uint> lightList;
// (FRAME_NEAR_SPLIT under PRIMARY_NEAR_LIT) how many of the pixel's own traced samples its history holds (nearTemporalMain),
// this frame's and the last frame's
[[vk::binding(37, 0)]] RWStructuredBuffer<uint> outNearAge;
[[vk::binding(38, 0)]] StructuredBuffer<uint> prevNearAge;
// (FRAME_NEAR_SPLIT under PRIMARY_NEAR_LIT) the water over a pixel's surface (farMain): its own light, the sky it
// mirrors and its glint (rgb, physical, through the air in front); a: the water's surface, view distance (0: none), or
// -1: the eye is under water, the air (beyond the surface) already in it and in the albedo's factor. nearTemporalMain
// adds it to the composite; the publish pass hands the surface's distance to the host (the near light's alpha), whose
// entities behind it take the water's medium

[[vk::binding(39, 0)]] RWStructuredBuffer<float4> waterAdd;

#include "atlas_air.slang"
#include "atlas_clouds.slang"

static const int TRACE_MISS = 0;
static const int TRACE_HIT = 1;
static const int TRACE_UNCOVERED = 2;
static const int TRACE_CAPPED = 3;  // (inside traceLod) out of steps: answered as TRACE_UNCOVERED, nothing further tried

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
// the trace's result, thread-private: the chain traceView -> traceLod -> traceVoxGrid / traceLodRegions -> traceRegion /
// traceGroups -> drawBox / stepLeaf -> groupHit, vgTexel works on it instead of forwarding an inout TraceResult (Slang
// inlines a callee whose inout parameter is forwarded into another one: farMain's SPIR-V grew to 24 MB at -O1 and its
// -O3 compile took ~14 min and ~30 GB). Callers outside the chain copy their own in and out.
static TraceResult gTr;
static TraceResult sTr;  // (traceSecondaryCore's own)
static TraceResult cTr;  // (traceLodCore's own)

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
static float cLeafGap;  // (traceLodCore's own)

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
static bool sSecondary;  // (traceSecondaryCore's own)
static bool cSecondary;  // (traceLodCore's own)
static float gTrans = 1.0;
static float sTrans;  // (traceSecondaryCore's own)
static float cTrans;  // (traceLodCore's own)
static float gThrough = 0.0;
static float sThrough;  // (traceSecondaryCore's own)
static float cThrough;  // (traceLodCore's own)
static float gSecT = 0.0;
static float sSecT;  // (traceSecondaryCore's own)
static float cSecT;  // (traceLodCore's own)
static float gWaterChord = 0.0;
static float sWaterChord;  // (traceSecondaryCore's own)
static float cWaterChord;  // (traceLodCore's own)  // blocks of water the last secondary ray passed through
// (ATLAS_ABLATE, measurements of the secondary rays, docs/ATLAS-RENDERER-PLAN.md "GPU-first") bit 21: their level-0
// regions skip the height mip; bit 22: foliage passes them free (no shape, coverage or attenuation work); bits 23-25 a
// per-pixel counter of the near light's secondary rays into `counters` (gSecCount): 1 traceRegion iterations, 2
// height-mip skips, 3 height-mip probes, 4 node loads, 5 solid voxels met (material lookups), 6 voxels passed (foliage,
// water, shapes), 7 rays; bit 26: count in level-0 regions only
static uint gSecAblate = 0u;
static uint sSecAblate;  // (traceSecondaryCore's own)
static uint cSecAblate;  // (traceLodCore's own)
static uint gSecMode = 0u;
static uint sSecMode;  // (traceSecondaryCore's own)
static uint cSecMode;  // (traceLodCore's own)
static uint gSecCount = 0u;
static uint sSecCount;  // (traceSecondaryCore's own)
static uint cSecCount;  // (traceLodCore's own)
// the view's own rays (traceView sets it, nothing else): their leaf gaps, shapes, texels and water rules; the other
// rays that are not secondary (reflections, the sun map, the cheap shadow) see blocks as the secondary ones' voxels do
static bool gPrimary = false;
static bool sPrimary;  // (traceSecondaryCore's own)
static bool cPrimary;  // (traceLodCore's own)
// the trace's kind in one word, for the [noinline] trace calls' arguments (TraceIn.mode)
static const uint TM_SECONDARY = 1u;
static const uint TM_SKIP_WATER = 2u;
static const uint TM_GROUPS = 4u;
static const uint TM_PRIMARY = 8u;
// A ray's step budget (gTr.steps: every grid, region and group step it took): past it a trace answers
// TRACE_UNCOVERED and the ray gives up on finer data (a grid starved of level-0 chunks sent rays through 24 runs x 6
// coarse levels x 4096 steps each: a 5 s GPU timeout). A view ray rarely takes a tenth of it.
static const uint RAY_STEPS = 24576u;
// what one gap's coarse-level walk (vgCoarseRun) may spend of it, over all its runs and levels
static const uint COARSE_RUN_STEPS = 3072u;

// where a ray through voxel v (entered at t; ro / rd / inv / stepPos in the voxel's units) leaves it
float voxelExit(int3 v, float3 ro, float3 rd, float3 inv, float3 stepPos, float t) {
    float3 tv = (float3(v) + stepPos - ro) * inv;
    tv = float3(abs(rd.x) > 1e-12 && tv.x > t ? tv.x : 1e30, abs(rd.y) > 1e-12 && tv.y > t ? tv.y : 1e30,
                abs(rd.z) > 1e-12 && tv.z > t ? tv.z : 1e30);
    return min(tv.x, min(tv.y, tv.z));
}
void secCount(uint mode, int lod) {
    if (sSecondary && sSecMode == mode && ((sSecAblate & 67108864u) == 0u || lod == 0)) sSecCount++;
}
// (traceLodCore's copy: its statics are traceLodCore's own)
void secCountC(uint mode, int lod) {
    if (cSecondary && cSecMode == mode && ((cSecAblate & 67108864u) == 0u || lod == 0)) cSecCount++;
}
// (the hot loops: secCount's switches read once a walk, the mode counted at level `lod`, 0 none) The thread-private
// statics live in local memory wherever the trace is a real call (NVIDIA honours [noinline]): a walk that reads them
// every step, and keeps gTr.steps there, waited on memory a step (the far pass 10x Oct 20's at the same steps).
uint secCountMode(int lod) { return cSecondary && ((cSecAblate & 67108864u) == 0u || lod == 0) ? cSecMode : 0u; }

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
    secCountC(5u, rl);
    bool pass = ((frame.dims.z & FRAME_SEE_THROUGH_WATER) != 0 && hitCls == 2u) || primaryPassesWater(hitCls, t);
    if (cSecondary && hitCls == 1u && (cSecAblate & 4194304u) != 0u) pass = true;  // (free foliage)
    if (cSecondary && !pass && rl == 0 && secondaryShapeMissesC(hitMat, ro + rd * t - float3(v), rd)) pass = true;
    float sigma = cSecondary && !pass ? secondarySigmaC(hitMat, hitCls) : -1.0;
    if (sigma >= 0.0) {
        // a secondary ray through foliage or a plant: attenuated over its chord through this voxel, then on
        float chord = clamp(voxelExit(v, ro, rd, inv, stepPos, t) - t, 0.0, 2.0 * float(1 << rl));
        float x0 = max(cSecT + t, 0.0);
        // E[exp(-0.45 x)] over where in this stretch the first opaque texel lies, weighted by what got here
        cThrough += cTrans * exp(-0.45 * x0) * (sigma / (sigma + 0.45)) * (1.0 - exp(-(sigma + 0.45) * chord));
        cTrans *= exp(-sigma * chord);
        if (hitCls == 2u) cWaterChord += chord;
        pass = cTrans > 0.004;
        if (!pass) cTrans = 0.0;
    }
    if (pass) secCountC(6u, rl);
    if (!pass && hitCls == 1u && cLeafGap > 0.0 && cPrimary) {
        // (hashed by the world voxel and level, so every structure holding the voxel shows the same gaps)
        uint phase = frame.jitter.z > 0.5 ? (frame.temporal.x & 7u) : 0u;
        uint hh = (uint(wv.x) * 73856093u) ^ (uint(wv.y) * 19349663u) ^ (uint(wv.z) * 83492791u) ^ (uint(rl) * 2654435761u) ^
                  (phase * 374761393u);
        hh = (hh ^ (hh >> 13)) * 1274126177u;
        pass = float(hh & 0xFFFFu) / 65535.0 < cLeafGap;
    }
    return !pass;
}

// 1: a hit (gTr), 0: none, 2: it ran out of steps first (its iteration cap or the ray's budget: not a miss)
TRACE_CALL
int traceRegion(RegionGpu r, float3 ro, float3 rd, float t0, float t1, float eps) {
    float3 inv = float3(abs(rd.x) > 1e-12 ? 1.0 / rd.x : 1e30, abs(rd.y) > 1e-12 ? 1.0 / rd.y : 1e30,
                        abs(rd.z) > 1e-12 ? 1.0 / rd.z : 1e30);
    float3 stepPos = float3(rd.x > 0 ? 1.0 : 0.0, rd.y > 0 ? 1.0 : 0.0, rd.z > 0 ? 1.0 : 0.0);
    uint nodeBase = r.bases.x, leafBase = r.bases.y;
    float t = t0;
    int axis = cTr.axis;
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
    bool useMip = !(cSecondary && rl == 0 && (cSecAblate & 2097152u) != 0u);
    // (the ray's step count and the counter's switches in registers for the walk, cTr.steps written back at each exit)
    uint steps = cTr.steps;
    uint scm = secCountMode(rl);
    for (int it = 0; it < 1024; it++) {
        if (t >= t1) {
            cTr.steps = steps;
            cTr.axis = axis;
            return 0;
        }
        if (steps >= RAY_STEPS) break;
        steps++;
        if (scm == 1u) cSecCount++;
        float3 p = ro + rd * t;
        // Over terrain: find the largest column cell (whole region down to 4x4 voxels) whose highest voxel the ray
        // stays above until it leaves the cell, and skip straight across it. Far rays spend most of their length here.
        bool over = false;
        int lvlOff = 5460;
        for (int k = useMip ? 6 : -1; k >= 0; k--) {
            if (scm == 3u) cSecCount++;
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
                    if (scm == 2u) cSecCount++;
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
                if (scm == 4u) cSecCount++;
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
                cTr.steps = steps;  // (the hit's callees see the ray's count)
                if (voxelStops(hitMat, v, (r.origin.xyz >> rl) + v, rl, ro, rd, inv, stepPos, t)) {
                    cTr.t = t;
                    cTr.mat = hitMat;
                    cTr.axis = entryAxis(v, ro, rd, inv, stepPos);
                    return 1;
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
    cTr.steps = steps;
    cTr.axis = axis;
    return 2;
}

float3 regionOriginRel(RegionGpu r) {
    return float3(int3(r.origin.xyz) - frame.camInt.xyz);
}

#include "atlas_groups.slang"
#include "atlas_bricks.slang"  // (the micro-voxel bricks: non-cube blocks at 1/16 block)
// (FRAME_FAR_AMORT) each pixel's primary hit, this frame's and the last's (farHitStore), two words a pixel. (The last
// globals: gpu_layout.hpp FarGlobals lists them in declaration order for the C++ build, which never reads them)
[[vk::binding(41, 0)]] RWStructuredBuffer<uint4> farHit;
[[vk::binding(42, 0)]] StructuredBuffer<uint4> prevFarHit;

// What a secondary ray loses per block of path through a voxel of material `mat` (-1: it stops there, solid): a
// cut-out shape (leaves, plants: the game classes plants as terrain, and a solid cube of grass tuft blocked every
// shadow and sky ray beside it) by its coverage, the share of its micro-voxels filled times their cut-out alpha
// (a crossing meets two such faces of a block: -2 ln(1 - c)); foliage without a shape (the voxel path alone) by
// FOLIAGE_COVER. The last material asked is remembered (a ray crosses a crown of one kind).
static uint gSigMat = 0xFFFFFFFFu;
static uint cSigMat;  // (traceLodCore's own)
static float gSigVal = -1.0;
static float cSigVal;  // (traceLodCore's own)
float secondarySigmaIn(uint mat, uint cls, uint4 head0, inout uint cacheMat, inout float cacheVal) {
    if (mat == cacheMat) return cacheVal;
    // water lets light through as it goes (a lake's bed lit by the sun and sky through it, 8 % lost a block): as solid,
    // every surface under water was black to the near light (its sun and sky rays stopped at the surface)
    float sg = cls == 1u ? FOLIAGE_SIGMA : cls == 2u ? WATER_SIGMA : -1.0;
    uint sh = shapeOfIn(head0, mat);
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
    cacheMat = mat;
    cacheVal = sg;
    return sg;
}
// (each trace world its own cache: the outside's, traceLodCore's)
float secondarySigma(uint mat, uint cls) { return secondarySigmaIn(mat, cls, gHead, gSigMat, gSigVal); }
float secondarySigmaC(uint mat, uint cls) { return secondarySigmaIn(mat, cls, cHead, cSigMat, cSigVal); }
// Whether a ray from local point l (0..1 in the voxel) along d misses the micro-voxels (4 x 4 x 4, AtlasLabExport's
// order) of material `mat`'s partial shape; false for full or unshaped materials and cut-out ones (secondarySigma).
static uint gMaskMat = 0xFFFFFFFFu;
static uint cMaskMat;  // (traceLodCore's own)
static uint2 gMask = uint2(0u, 0u);
static uint2 cMask;  // (traceLodCore's own)
bool secondaryShapeMissesIn(uint mat, float3 l, float3 d, uint4 head0, inout uint maskMat, inout uint2 mask) {
    uint br = brickOf(mat);  // (a block with a brick: its micro-voxels, atlas_bricks.slang)
    if (br != 0u) return brickMisses(br, l, d);
    if (mat != maskMat) {
        maskMat = mat;
        mask = uint2(0xFFFFFFFFu, 0xFFFFFFFFu);
        uint sh = shapeOfIn(head0, mat);
        if (sh != 0u) {
            uint4 head = groupData[sh >> 2];
            if ((head.z & 1u) == 0u) mask = head.xy;
        }
    }
    if (mask.x == 0xFFFFFFFFu && mask.y == 0xFFFFFFFFu) return false;
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
        if ((((bit < 32u ? mask.x >> bit : mask.y >> (bit - 32u))) & 1u) != 0u) return false;
        int a = tn.x <= tn.y && tn.x <= tn.z ? 0 : (tn.y <= tn.z ? 1 : 2);
        m[a] += st[a];
        if (m[a] < 0 || m[a] > 3) return true;
        tn[a] += td[a];
    }
    return true;
}
// (each trace world its own cache: the outside's, traceLodCore's)
bool secondaryShapeMisses(uint mat, float3 l, float3 d) { return secondaryShapeMissesIn(mat, l, d, gHead, gMaskMat, gMask); }
bool secondaryShapeMissesC(uint mat, float3 l, float3 d) { return secondaryShapeMissesIn(mat, l, d, cHead, cMaskMat, cMask); }
// ---- the level-0 grid (native/src/voxgrid.hpp: layout; atlas_voxgrid.slang builds it on the GPU) -------------------
static const uint VG_EMPTY = 0u;
static const uint VG_MIXED = 1u;
static const uint VG_UNLOADED = 2u;
static const uint VG_UNIFORM = 3u;  // one id throughout (bits 2-17), no slot: read as a MIXED chunk all of that id
// (primary rays) the threshold a cut-out face's texel coverage is tested against (farMain: a per-pixel one, rotated
// under the host's TAA, which averages a part-covered texel's coverage)
static float gAlphaXi = 0.5;
static float sAlphaXi;  // (traceSecondaryCore's own)
static float cAlphaXi;  // (traceLodCore's own)
// (ATLAS_ABLATE 134217728, measurements: level 0 from the SVDAG regions alone, as before the grid)
// (ATLAS_ABLATE 33554432, measurements: a primary hit's albedo says what drew it: green a brick, red a shape's micro-voxels, blue
// a textured cube, yellow the groups' boxes)
bool dbgPath() { return (asuint(frame.jitter.w) & 33554432u) != 0u; }
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

// A level of the grid: level 0 (blocks) or a coarse level k (voxels of 2^k blocks, chunks of 16 of them; voxgrid.hpp),
// one structure from level 0 out. (ATLAS_ABLATE 1073741824, measurements: the coarse levels off, the regions alone)
struct VgGrid {
    int k;
    int3 origin;  // world block of the window's first voxel corner
    int ox, oz;   // the window's origin, chunks (their cells in memory at (c + origin) mod D)
    int X, Y, Z;  // chunks
    uint base;    // its first chunk word
};
bool vgLevelOn(int k) {
    if (k == 0) return vgOn();
    if (k > 6 || !vgOn()) return false;
    return ((frame.vgLevel[clamp(k, 1, 6) - 1].w >> 16) & 1) != 0 && (asuint(frame.jitter.w) & 1073741824u) == 0u;
}
VgGrid vgGrid(int k) {
    VgGrid g;
    g.k = k;
    if (k == 0) {
        g.origin = frame.vgOrigin.xyz * 16;
        g.ox = frame.vgOrigin.x;
        g.oz = frame.vgOrigin.z;
        g.X = int(frame.vgDims.x);
        g.Y = int(frame.vgDims.y);
        g.Z = int(frame.vgDims.z);
        g.base = 0u;
    } else {
        int4 L = frame.vgLevel[clamp(k, 1, 6) - 1];  // (clamped: inlined with k 0 this branch is dead, its index -1)
        int cs = 16 << k;
        g.origin = int3(L.x * cs, -64, L.y * cs);
        g.ox = L.x;
        g.oz = L.y;
        g.X = L.w & 255;
        g.Y = (L.w >> 8) & 255;
        g.Z = g.X;
        g.base = uint(L.z);
    }
    return g;
}
uint vgCellG(VgGrid g, int3 c) {
    int px = (c.x + g.ox) % g.X, pz = (c.z + g.oz) % g.Z;
    px += px < 0 ? g.X : 0;
    pz += pz < 0 ? g.Z : 0;
    return g.base + uint((c.y * g.Z + pz) * g.X + px);
}

// Where a chord p0 + d u standing at u in a chunk without data (or outside the window: `left`) reaches the next chunk
// with data: the run of unloaded chunks the regions answer for. An UNLOADED chunk carries a box of unloaded chunks
// around it (vgGrowMain, as an EMPTY one's of empty ones): left through its far side, one load a box (at most 64).
// c0: the chunk the walk stopped in (traceVoxGrid's vStop >> 4, by index: re-flooring the point at a grazing angle
// found the loaded chunk before it and skipped the run); exitAxis / exitTo: the voxel index across the face the run is
// left by (traceVoxGrid's seed: the walk resumes there, not back inside the box; -1 none)
float vgUnloadedEnd(VgGrid g, float3 p0, float3 d, float u, float u1, int3 c0, out bool left, out int exitAxis, out int exitTo) {
    left = false;
    exitAxis = -1;
    exitTo = 0;
    float cs = float(16 << g.k);
    float3 ro = (p0 - float3(g.origin - frame.camInt.xyz)) / cs;
    float3 dc = d / cs;
    float3 inv = float3(abs(dc.x) > 1e-14 ? 1.0 / dc.x : 1e30, abs(dc.y) > 1e-14 ? 1.0 / dc.y : 1e30,
                        abs(dc.z) > 1e-14 ? 1.0 / dc.z : 1e30);
    int3 st = int3(dc.x > 0.0 ? 1 : -1, dc.y > 0.0 ? 1 : -1, dc.z > 0.0 ? 1 : -1);
    float t = u;
    int3 c = c0;
    for (int it = 0; it < 64; it++) {
        if (t >= u1) return u1;
        if (c.x < 0 || c.y < 0 || c.z < 0 || c.x >= g.X || c.y >= g.Y || c.z >= g.Z) {
            left = true;  // (from here the window has nothing: the regions take the rest)
            return u1;
        }
        uint cw = vgChunks[vgCellG(g, c)];
        if ((cw & 3u) != VG_UNLOADED) return t;
        int3 lo = c - int3(int((cw >> 2) & 31u), int((cw >> 12) & 31u), int((cw >> 22) & 31u));
        int3 hi = c + int3(int((cw >> 7) & 31u), int((cw >> 17) & 31u), int((cw >> 27) & 31u)) + 1;
        float3 tt = (float3(dc.x > 0.0 ? hi.x : lo.x, dc.y > 0.0 ? hi.y : lo.y, dc.z > 0.0 ? hi.z : lo.z) - ro) * inv;
        tt = float3(abs(dc.x) > 1e-14 ? tt.x : 1e30, abs(dc.y) > 1e-14 ? tt.y : 1e30, abs(dc.z) > 1e-14 ? tt.z : 1e30);
        float tn = min(tt.x, min(tt.y, tt.z));
        int a = tt.x <= tn ? 0 : (tt.y <= tn ? 1 : 2);
        // the cell across the box's face it leaves by (by index on that axis, as traceVoxGrid's exit)
        int3 nc = int3(floor(ro + dc * tn));
        nc[a] = st[a] > 0 ? hi[a] : lo[a] - 1;
        c = clamp(nc, lo, hi - 1);  // (the face lies within the box on the other axes)
        c[a] = nc[a];
        t = max(t, tn);
        exitAxis = a;
        exitTo = st[a] > 0 ? hi[a] * 16 : lo[a] * 16 - 1;
    }
    return min(t, u1);
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
void vgTexel(uint mat, float3 lp, float3 d, int ax, float tView) {
    uint at = vgTexture(mat);
    if (at == 0u) return;
    int f = d[ax] > 0.0 ? G_FACE_LOW[ax] : G_FACE_HIGH[ax];
    float3 q = clamp(lp, float3(0, 0, 0), float3(0.9999, 0.9999, 0.9999));
    float2 uv = f <= 1 ? q.xz : (f <= 3 ? float2(q.x, 1.0 - q.y) : float2(q.z, 1.0 - q.y));
    uv = clamp(uv, float2(0, 0), float2(0.9999, 0.9999));
    float fp = tView * frame.params.w * frame.params.z / max(abs(d[ax]), 0.2);
    float4 c = texSample(at, f, uv.x, uv.y, fp);
    if (c.a < 0.5 && cTr.hasAlbedo != 0u) return;
    cTr.albedo = c.rgb;
    cTr.hasAlbedo = 1u;
}

// ---- coarse voxels as smooth surfaces (ATLAS-PLAN 0.2: detail as the eye resolves it) ----
// A coarse level's voxel wider than the pixel's footprint (the footprint's own level not resident: a streaming gap, a
// window's edge) is not drawn as the cube it is stored as: its level's occupancy is a density (each voxel's centre 1 when
// solid, 0 when air), interpolated trilinearly, the surface where it reaches one half; the colour the solid corners'
// albedo by their weights; the normal the density's gradient. A mountain at a coarse level reads as a smooth slope in
// blended colour, never stair-stepped boxes in mottled per-voxel colours. (ATLAS_ABLATE 32: the cubes, as before)
static const float SMOOTH_COARSE_PX = 1.5;  // a voxel this many footprints wide, or more, is drawn smooth

// the material of grid-local voxel v of level grid g: 0 air, 0xFFFFFFFF no data (outside the window, an unloaded chunk)
uint vgMatG(VgGrid g, int3 v) {
    if (v.x < 0 || v.y < 0 || v.z < 0 || v.x >= g.X * 16 || v.y >= g.Y * 16 || v.z >= g.Z * 16) return 0xFFFFFFFFu;
    uint cw = vgChunks[vgCellG(g, v >> 4)];
    uint st = cw & 3u;
    if (st == VG_UNLOADED) return 0xFFFFFFFFu;
    if (st == VG_EMPTY) return 0u;
    if (st == VG_UNIFORM) return (cw >> 2) & 0xFFFFu;
    int3 l = v & 15;
    uint i = uint(l.x + 16 * (l.z + 16 * l.y));
    return (vgPool[(cw >> 2) * 2048u + (i >> 1)] >> (16u * (i & 1u))) & 0xFFFFu;
}

// a voxel's share of the density: 1 what this ray stops at, 0 air and water a ray passes (skipWater: the trace on
// under a water surface, or water seen through); no data (an unloaded neighbour, the window's edge) counts as the hit
// voxel's own, solid, so the surface keeps no lip where the level's data ends
float smoothOcc(uint m, bool skipWater) {
    if (m == 0u) return 0.0;
    if (m == 0xFFFFFFFFu) return 1.0;
    return skipWater && uint(palette[m].emisClass.y) == 2u ? 0.0 : 1.0;
}

// the density (trilinear over the eight voxel centres around a point) as central differences over a voxel either side of q
// from the 32 voxel centres they share, not 48: the 2 x 2 x 2 around q and a 4-voxel arm each side of it on each axis
float3 vgDensityGrad(VgGrid g, float3 q, bool skipWater) {
    q -= 0.5;
    int3 b = int3(floor(q));
    float3 w = q - float3(b);
    float c[8];  // (x + 2 y + 4 z, as vgDensityG's)
    [unroll]
    for (int n = 0; n < 8; n++) c[n] = smoothOcc(vgMatG(g, b + int3(n & 1, (n >> 1) & 1, (n >> 2) & 1)), skipWater);
    float3 gr = float3(0, 0, 0);
    [unroll]
    for (int n = 0; n < 4; n++) {
        int a = n & 1, e = (n >> 1) & 1;
        float xl = smoothOcc(vgMatG(g, b + int3(-1, a, e)), skipWater), xh = smoothOcc(vgMatG(g, b + int3(2, a, e)), skipWater);
        float yl = smoothOcc(vgMatG(g, b + int3(a, -1, e)), skipWater), yh = smoothOcc(vgMatG(g, b + int3(a, 2, e)), skipWater);
        float zl = smoothOcc(vgMatG(g, b + int3(a, e, -1)), skipWater), zh = smoothOcc(vgMatG(g, b + int3(a, e, 2)), skipWater);
        // (x: the pair (y, z) = (a, e); y: (x, z) = (a, e); z: (x, y) = (a, e))
        float wx = (a != 0 ? w.y : 1.0 - w.y) * (e != 0 ? w.z : 1.0 - w.z);
        float wy = (a != 0 ? w.x : 1.0 - w.x) * (e != 0 ? w.z : 1.0 - w.z);
        float wz = (a != 0 ? w.x : 1.0 - w.x) * (e != 0 ? w.y : 1.0 - w.y);
        gr.x += wx * (lerp(c[1 + 2 * a + 4 * e], xh, w.x) - lerp(xl, c[2 * a + 4 * e], w.x));
        gr.y += wy * (lerp(c[a + 2 + 4 * e], yh, w.y) - lerp(yl, c[a + 4 * e], w.y));
        gr.z += wz * (lerp(c[a + 2 * e + 4], zh, w.z) - lerp(zl, c[a + 2 * e], w.z));
    }
    return gr;
}

struct SmoothHit {
    bool hit;
    float t;        // the surface along the walk's parameter
    float3 n;       // its normal (unit, grid axes: the world's)
    float3 albedo;  // the solid corners' albedo by their weights (linear)
};

// The density's crossing along ro + d u (grid-local voxel units, u the walk's parameter) for u in [u0, u1), from the
// cell of eight voxel centres around each sample (the last cell's corners kept: a march stays in two or three cells).
// (inlined: once, after the view ray's walk, smoothPrimaryHit. As a [noinline] call its VgGrid and SmoothHit went through
// memory: the split kernel's shared memory 7936 -> 16640 bytes a group and its far pass 25 % slower)
SmoothHit coarseSmoothHit(VgGrid g, float3 ro, float3 d, float u0, float u1, bool skipWater) {
    SmoothHit sh;
    sh.hit = false;
    sh.t = u1;
    sh.n = float3(0, 1, 0);
    sh.albedo = float3(0, 0, 0);
    int3 cellAt = int3(-0x7FFFFFFF, 0, 0);
    uint mats[8];
    float occ[8];
    [unroll]
    for (int k = 0; k < 8; k++) { mats[k] = 0u; occ[k] = 0.0; }
    float fPrev = -1.0, uPrev = u0;
    const int N = 10;
    float du = (u1 - u0) / float(N);
    float u = u0;
    float3 w3 = float3(0, 0, 0);
    for (int it = 0; it < N + 5; it++) {
        // (N even samples, then bisection between the last one outside and the first one inside)
        float3 q = ro + d * u - 0.5;  // (voxel centres at integer + 0.5: the cell's lower corner floor(q))
        int3 b = int3(floor(q));
        if (any(b != cellAt)) {
            cellAt = b;
            [unroll]
            for (int k = 0; k < 8; k++) {
                uint m = vgMatG(g, b + int3(k & 1, (k >> 1) & 1, (k >> 2) & 1));
                mats[k] = m;
                occ[k] = smoothOcc(m, skipWater);
            }
        }
        w3 = q - float3(b);
        float x0 = lerp(occ[0], occ[1], w3.x), x1 = lerp(occ[2], occ[3], w3.x);
        float x2 = lerp(occ[4], occ[5], w3.x), x3 = lerp(occ[6], occ[7], w3.x);
        float f = lerp(lerp(x0, x1, w3.y), lerp(x2, x3, w3.y), w3.z);
        if (it < N) {
            if (f >= 0.5) {
                if (it == 0) {  // (inside at once: the surface at the march's start)
                    sh.hit = true;
                    u1 = u;
                    break;
                }
                sh.hit = true;
                // bisection on [uPrev, u]
                u1 = u;
                u0 = uPrev;
                u = 0.5 * (u0 + u1);
                it = N - 1;  // (the next iterations bisect)
                continue;
            }
            uPrev = u;
            fPrev = f;
            u += du;
            if (it == N - 1) return sh;  // (no crossing: the ray passes this voxel's corner)
            continue;
        }
        // bisection
        if (f >= 0.5) u1 = u; else u0 = u;
        u = 0.5 * (u0 + u1);
    }
    if (!sh.hit) return sh;
    sh.t = u1;
    // the normal: minus the density's gradient over a voxel either side of the crossing (central differences: a terrace's
    // one-voxel ramp and its flat top share their light across the step, a slope reads as a slope)
    float3 qh = ro + d * sh.t;
    float3 gr = vgDensityGrad(g, qh, skipWater);
    if (dot(gr, gr) < 1e-8) {  // (flat over the stencil: the crossing's own cell)
        gr.x = lerp(lerp(occ[1] - occ[0], occ[3] - occ[2], w3.y), lerp(occ[5] - occ[4], occ[7] - occ[6], w3.y), w3.z);
        gr.y = lerp(lerp(occ[2] - occ[0], occ[3] - occ[1], w3.x), lerp(occ[6] - occ[4], occ[7] - occ[5], w3.x), w3.z);
        gr.z = lerp(lerp(occ[4] - occ[0], occ[5] - occ[1], w3.x), lerp(occ[6] - occ[2], occ[7] - occ[3], w3.x), w3.y);
    }
    float gl = length(gr);
    sh.n = gl > 1e-5 ? -gr / gl : -normalize(d);
    // the colour: the solid corners' albedo by their trilinear weights
    float3 acc = float3(0, 0, 0);
    float wsum = 0.0;
    [unroll]
    for (int k = 0; k < 8; k++) {
        if (occ[k] <= 0.0 || mats[k] == 0xFFFFFFFFu) continue;  // (no data: no colour of its own)
        float wk = ((k & 1) != 0 ? w3.x : 1.0 - w3.x) * (((k >> 1) & 1) != 0 ? w3.y : 1.0 - w3.y) * (((k >> 2) & 1) != 0 ? w3.z : 1.0 - w3.z);
        acc += palette[mats[k]].albedoRough.rgb * wk;
        wsum += wk;
    }
    sh.albedo = wsum > 1e-6 ? acc / wsum : float3(0.5, 0.5, 0.5);
    return sh;
}

// (TraceResult.hasAlbedo) bit 0 its albedo is the hit's own, bit 1 a smooth normal in bits 16-31 (octahedral, 8 bits each),
// bit 2 alone: a coarse level's cube that wants its smooth surface (traceView's smoothPrimaryHit clears it)
static const uint HIT_SMOOTH_N = 2u;
static const uint HIT_SMOOTH_WANT = 4u;
uint packSmoothN(float3 n) {
    float2 e = octEncode(n);
    return 1u | HIT_SMOOTH_N | (uint(saturate(e.x) * 255.0 + 0.5) << 16) | (uint(saturate(e.y) * 255.0 + 0.5) << 24);
}
float3 hitSmoothN(uint hasAlbedo) {
    return octDecode(float2(float((hasAlbedo >> 16) & 255u), float((hasAlbedo >> 24) & 255u)) / 255.0);
}

// The chord p0 + d u (camera-relative), u in [u0, u1), through the grid: TRACE_HIT (res filled), TRACE_MISS (it crossed
// [u0, u1) through chunks with data), else TRACE_UNCOVERED and uStop where it met a chunk without data or left the
// window (the SVDAG regions answer from there). A step reads the chunk word; inside a chunk with blocks the block word;
// inside a block with voxels its bitmap: an empty chunk or block is left through the far side of its empty box, an
// empty voxel through its empty 2^3 octant when it has one, as traceRegion's bricks are. A coarse level (g.k > 0) the
// same in its voxels (u still in blocks along dw), its voxels cubes of their material, as the regions' levels are.
// stepCap: the ray's steps (gTr.steps) it may reach (then TRACE_CAPPED); seedAxis / seedTo: the voxel index the walk starts
// at on that axis (vgUnloadedEnd's exit; -1: none); vStop: the voxel (grid-local) it stopped in
TRACE_CALL
int traceVoxGrid(VgGrid g, float3 p0, float3 dw, float u0, float u1, uint stepCap, int seedAxis, int seedTo, out float uStop,
                 out int3 vStop) {
    uStop = u0;
    vStop = int3(-1, -1, -1);
    int3 gBlock = g.origin;
    float vs = float(1 << g.k);
    float3 ro = (p0 - float3(gBlock - frame.camInt.xyz)) / vs;  // grid-local voxels
    float3 d = dw / vs;
    int3 gVox = int3(gBlock.x >> g.k, gBlock.y >> g.k, gBlock.z >> g.k);  // (world voxel of the first one)
    int X = g.X, Y = g.Y, Z = g.Z;
    int3 nb = int3(X, Y, Z) * 16;
    float3 inv = float3(abs(d.x) > 1e-12 ? 1.0 / d.x : 1e30, abs(d.y) > 1e-12 ? 1.0 / d.y : 1e30,
                        abs(d.z) > 1e-12 ? 1.0 / d.z : 1e30);
    float3 stepPos = float3(d.x > 0 ? 1.0 : 0.0, d.y > 0 ? 1.0 : 0.0, d.z > 0 ? 1.0 : 0.0);
    bool3 up = bool3(d.x > 0, d.y > 0, d.z > 0);
    float t = u0;
    int exitAxis = seedAxis, exitTo = seedTo;
    // (the ray's step count and the counter's switches in registers for the walk, cTr.steps written back at each exit)
    uint steps = cTr.steps;
    uint scm = secCountMode(0);
    for (int it = 0; it < 4096; it++) {
        if (t >= u1) {
            cTr.steps = steps;
            return TRACE_MISS;
        }
        if (steps >= stepCap) {
            cTr.steps = steps;
            uStop = t;
            return TRACE_CAPPED;
        }
        steps++;
        if (scm == 1u) cSecCount++;
        // (a point exactly on a cell face going down that axis lies in the cell below: else that face's exit is at t,
        // not after it, and the ray left a camera standing on a chunk's floor through the box's far sides instead)
        float3 p = ro + d * t;
        float3 pf = floor(p);
        int3 v = int3(pf) - int3(d.x < 0 && p.x == pf.x ? 1 : 0, d.y < 0 && p.y == pf.y ? 1 : 0, d.z < 0 && p.z == pf.z ? 1 : 0);
        if (exitAxis >= 0) v[exitAxis] = exitTo;
        vStop = v;
        if (v.x < 0 || v.y < 0 || v.z < 0 || v.x >= nb.x || v.y >= nb.y || v.z >= nb.z) {
            cTr.steps = steps;
            uStop = t;
            return TRACE_UNCOVERED;
        }
        int3 c = v >> 4;
        uint cw = vgChunks[vgCellG(g, c)];
        uint st = cw & 3u;
        if (st == VG_UNLOADED) {
            cTr.steps = steps;
            uStop = t;
            return TRACE_UNCOVERED;
        }
        int3 lo, hi;  // the empty box the ray leaves next, grid-local blocks, [lo, hi)
        if (st == VG_EMPTY) {
            int3 en = int3(int((cw >> 2) & 31u), int((cw >> 12) & 31u), int((cw >> 22) & 31u));
            int3 ep = int3(int((cw >> 7) & 31u), int((cw >> 17) & 31u), int((cw >> 27) & 31u));
            lo = (c - en) * 16;
            hi = (c + ep + 1) * 16;
            if (scm == 2u) cSecCount++;
        } else {
            // (VG_UNIFORM: every block full, every voxel the id; the same steps as a MIXED chunk's from here, so a
            // uniform lake, leaf or glass section is cut out, passed and lit as the mixed ones are)
            bool uni = st == VG_UNIFORM;
            uint slot = cw >> 2;
            int3 l = v & 15;
            int3 b = l >> 2;
            uint bi = uint(b.x + 4 * (b.z + 4 * b.y));
            uint bw = uni ? 1u : vgBlocks[slot * 64u + bi];
            if (scm == 4u) cSecCount++;
            if ((bw & 1u) == 0u) {
                int3 en = int3(int((bw >> 2) & 3u), int((bw >> 6) & 3u), int((bw >> 10) & 3u));
                int3 ep = int3(int((bw >> 4) & 3u), int((bw >> 8) & 3u), int((bw >> 12) & 3u));
                lo = c * 16 + (b - en) * 4;
                hi = c * 16 + (b + ep + 1) * 4;
            } else {
                uint64_t bits = uni ? ~uint64_t(0) : vgBits[slot * 64u + bi];
                int3 q = v & 3;
                uint bt = uint(q.x + 4 * q.z + 16 * q.y);
                if (((bits >> bt) & 1) != 0) {
                    cTr.steps = steps;  // (the hit's callees see the ray's count)
                    uint i = uint(l.x + 16 * (l.z + 16 * l.y));
                    uint mat = uni ? (cw >> 2) & 0xFFFFu : (vgPool[slot * 2048u + (i >> 1)] >> (16u * (i & 1u))) & 0xFFFFu;
                    if (g.k > 0) {
                        // a coarse level's voxel: a cube of its material (the regions' levels' rule), unless it is wider
                        // than the primary ray's footprint: then the level's smooth surface (coarseSmoothHit)
                        // (the smooth surface itself is found once the view ray is done, traceView's smoothPrimaryHit: in
                        // the walk, a call inside the divergent step loop, it cost the horizon view's far pass 0.7 s)
                        float foot = (cChordT + t) * frame.params.w * frame.params.z;
                        bool smooth = cPrimary && vs >= SMOOTH_COARSE_PX * foot && (cAblate & 32u) == 0u;
                        if (voxelStops(mat, v, v + gVox, g.k, ro, d, inv, stepPos, t)) {
                            cTr.t = t;
                            cTr.mat = mat;
                            cTr.axis = entryAxis(v, ro, d, inv, stepPos);
                            cTr.lod = g.k;
                            if (smooth) cTr.hasAlbedo = HIT_SMOOTH_WANT;
                            return TRACE_HIT;
                        }
                        lo = v;
                        hi = v + 1;
                    } else {
                    // a primary ray meets a shaped block (a slab, a stair, a fence, a plant, cut-out leaves) by its
                    // micro-voxels, textured by its face tiles, as the groups draw it (ATLAS_ABLATE 268435456: cubes)
                    uint sh = !cPrimary || (asuint(frame.jitter.w) & 268435456u) != 0u ? 0u : shapeOfC(mat);
                    uint wcls = uint(palette[mat].emisClass.y);
                    bool seeWater = ((frame.dims.z & FRAME_SEE_THROUGH_WATER) != 0 && wcls == 2u) || primaryPassesWater(wcls, t);
                    // a block with a micro-voxel brick (a door, a rail, a torch: atlas_bricks.slang): its 1/16 voxels
                    // (a waterlogged fence, stair or candle is a water-class material with a brick: water fills its cell
                    // around the brick, so a ray from the air meets the water first, as plain water's (voxelStops below:
                    // the surface, then farMain traces on through it), and the pass through the water meets the brick;
                    // plain water has none. Its brick at once drew it dry: no water in a waterlogged block)
                    uint br0 = cPrimary ? brickOf(mat) : 0u;
                    bool logged = wcls == 2u && br0 != 0u;
                    uint br = (logged ? seeWater : !seeWater) ? br0 : 0u;
                    if (br != 0u) {
                        // (its walk is up to 48 micro-voxels: counted against the ray's budget as a few steps)
                        cTr.steps += 4u;
                        steps += 4u;
                        BrickHit bh = brickHit(br, ro + d * t - float3(v) - brickOffset(mat, v + gBlock), d, entryAxis(v, ro, d, inv, stepPos));
                        if (bh.hit) {
                            cTr.t = t + bh.t;
                            cTr.mat = mat;
                            cTr.axis = bh.axis;
                            cTr.lod = 0;
                            cTr.albedo = dbgPath() ? float3(0.0, 0.5, 0.0) : bh.albedo;
                            cTr.hasAlbedo = 1u;
                            return TRACE_HIT;
                        }
                    } else if (sh != 0u && !seeWater && !logged) {
                        float tHit;
                        int ax = entryAxis(v, ro, d, inv, stepPos);
                        int3 q;
                        // a whole cube of cut-out texels (leaves) with its 16 x 16 texture: the face it is entered by,
                        // by the coverage of its texels under the pixel against the pixel's threshold: through the gaps
                        // the ray goes on to the branches and the sky behind (the shape's 4 x 4 coverage hashed per
                        // micro-voxel drew the gaps as dark specks); ATLAS_ABLATE 536870912: no cut-outs
                        uint4 head = groupData[sh >> 2];
                        uint tex = (head.z & 1u) != 0u && (head.x & head.y) == 0xFFFFFFFFu && (asuint(frame.jitter.w) & 536870912u) == 0u
                                 ? vgTexture(mat) : 0u;
                        if (tex != 0u) {
                            int f = d[ax] > 0.0 ? G_FACE_LOW[ax] : G_FACE_HIGH[ax];
                            float3 lq = clamp(ro + d * t - float3(v), float3(0, 0, 0), float3(0.9999, 0.9999, 0.9999));
                            float2 uv = f <= 1 ? lq.xz : (f <= 3 ? float2(lq.x, 1.0 - lq.y) : float2(lq.z, 1.0 - lq.y));
                            float fp = (cChordT + t) * frame.params.w * frame.params.z / max(abs(d[ax]), 0.2);
                            float4 c = texSample(tex, f, uv.x, uv.y, fp);
                            if (c.a > cAlphaXi) {
                                cTr.t = t;
                                cTr.mat = mat;
                                cTr.axis = ax;
                                cTr.lod = 0;
                                cTr.albedo = c.rgb;
                                cTr.hasAlbedo = 1u;
                                return TRACE_HIT;
                            }
                        } else if (vgShapeHit(sh, v, v + gBlock, ro, d, t, tHit, ax, q)) {
                            cTr.t = tHit;
                            cTr.mat = mat;
                            cTr.axis = ax;
                            cTr.lod = 0;
                            int f = d[ax] > 0.0 ? G_FACE_LOW[ax] : G_FACE_HIGH[ax];
                            cTr.albedo = cellAlbedo(mat | G_CELL_SHAPED, f, q);
                            cTr.hasAlbedo = 1u;
                            vgTexel(mat, ro + d * tHit - float3(v), d, ax, cChordT + tHit);
                            if (dbgPath()) cTr.albedo = float3(0.5, 0.0, 0.0);
                            return TRACE_HIT;
                        }
                    } else if (voxelStops(mat, v, v + gBlock, 0, ro, d, inv, stepPos, t)) {
                        cTr.t = t;
                        cTr.mat = mat;
                        cTr.axis = entryAxis(v, ro, d, inv, stepPos);
                        cTr.lod = 0;
                        if (cPrimary) vgTexel(mat, ro + d * t - float3(v), d, cTr.axis, cChordT + t);
                        if (dbgPath() && cPrimary) cTr.albedo = float3(0.0, 0.0, 0.5), cTr.hasAlbedo = 1u;
                        return TRACE_HIT;
                    }
                    lo = v;
                    hi = v + 1;
                    }
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
    cTr.steps = steps;
    uStop = t;
    return TRACE_CAPPED;  // (its iteration cap: not a gap)
}

// A run [u0, u1) of the chord p0 + d u through level-0 chunks the grid does not hold yet (the grid owns level 0): at
// each point the finest of its coarse levels that holds it, never a CPU level 0 or the tiles' stand-in where real
// blocks are known; the regions (level 0's: the tiles) only over what no grid level holds. TRACE_HIT (gTr, its t from
// p0), TRACE_MISS, TRACE_UNCOVERED with uncExit (from p0) as the regions report it, or TRACE_CAPPED: its steps spent
// (COARSE_RUN_STEPS over all its runs and levels: a grid starved of level-0 chunks made this 24 x 6 walks of 4096 steps a
// ray, a 5 s GPU timeout).
// whether level grid g holds camera-relative p: its chunk there in the window and not UNLOADED (one word)
bool vgHoldsAt(VgGrid g, float3 p) {
    int3 v = (int3(floor(p)) + frame.camInt.xyz - g.origin) >> g.k;
    if (any(v < int3(0, 0, 0)) || v.x >= g.X * 16 || v.y >= g.Y * 16 || v.z >= g.Z * 16) return false;
    return (vgChunks[vgCellG(g, v >> 4)] & 3u) != VG_UNLOADED;
}

int vgCoarseRun(float3 p0, float3 d, float u0, float u1, out float uncExit) {
    uncExit = 1e30;
    float u = u0;
    uint cap = min(RAY_STEPS, cTr.steps + COARSE_RUN_STEPS);
    for (int it = 0; it < 24 && u < u1; it++) {
        // the finest coarse level holding the point (one chunk word a level) is where the walk starts: a gap costs a jump
        // to the level that answers, not a walk and an unloaded run's scan on every finer level that does not (one walk
        // call site below: the split build inlines it)
        int kFrom = 1;
        {
            float3 pu = p0 + d * u;
            for (int k = 1; k <= 6; k++)
                if (vgLevelOn(k) && ((frame.vgLevel[clamp(k, 1, 6) - 1].w >> 17) & 1) != 0 && vgHoldsAt(vgGrid(k), pu)) {
                    kFrom = k;
                    break;
                }
        }
        bool on = false;
        float next = u1;  // (where some level holds data again)
        for (int k = kFrom; k <= 6; k++) {
            if (!vgLevelOn(k) || ((frame.vgLevel[clamp(k, 1, 6) - 1].w >> 17) & 1) == 0) continue;
            VgGrid gk = vgGrid(k);
            float uk;
            int3 vk;
            cLeafGap = k <= 1 ? 0.3 : 0.0;  // (the level's own rule, as traceLod's: else the window's edge showed a canopy seam)
            int rk = traceVoxGrid(gk, p0, d, u, u1, cap, -1, 0, uk, vk);
            if (rk == TRACE_HIT || rk == TRACE_MISS || rk == TRACE_CAPPED) return rk;
            if (uk > u + 1e-4) {
                // this level held [u, uk) and missed there: on from the finest level again
                u = uk;
                on = true;
                break;
            }
            bool left;
            int ea, et;
            next = min(next, vgUnloadedEnd(gk, p0, d, uk, u1, vk >> 4, left, ea, et));
        }
        if (on) continue;
        // no grid level holds u: the regions to where one does
        float ue;
        cSecT += u;
        int q = traceLodRegions(0, p0 + d * u, d, max(next - u, 1e-3), ue);
        cSecT -= u;
        if (q == TRACE_HIT) {
            cTr.t += u;
            return TRACE_HIT;
        }
        if (q == TRACE_UNCOVERED) {
            uncExit = ue < 1e29 ? ue + u : 1e30;
            return TRACE_UNCOVERED;
        }
        if (cTr.steps >= cap) return TRACE_CAPPED;
        u = max(next, u + 1e-3);
    }
    return u < u1 ? TRACE_CAPPED : TRACE_MISS;
}

// Walk one LOD along the chord p0 + d u, u in [0, len]: TRACE_HIT (gTr), TRACE_MISS, or TRACE_UNCOVERED with uncExit where
// the chord leaves the unloaded region cell (1e30 when it left the LOD's window, or ran out of steps: that LOD has
// nothing further along this ray).
// ([noinline]: SPIR-V optimisers inline every call; traceLod and what it calls, once per call site, made farMain 26 MB of
// SPIR-V at -O1, a ~30 GB, 14 min -O3 compile and a driver JIT that stalled: one copy, called. Also traceSecondary,
// cloudNoiseAt.)
//
// The trace's state crosses that call as arguments and comes back as a result (TraceIn / TraceOut), never through the
// thread-private statics alone: NVIDIA's compiler (and slangc) treat a [noinline] call as pure and merge calls whose
// arguments are the same, whatever statics differ (farMain's water-skip retrace came back with the water hit it skipped).
// With every input an argument, the same arguments do mean the same result.
//
// Each called core owns its state (2026-10-25): inside traceLodCore every helper uses the c-prefixed copies of the
// statics (cTr, cSecondary, ...), set from TraceIn at entry; inside traceSecondaryCore the s-prefixed ones (sTr,
// ...), set from SecIn; the outside (the entry points) keeps the g-ones. A static touched by two functions that are real
// calls (NVIDIA honours [noinline]) is a module-scope Private global in local memory, every access a trip there (the far
// pass ran 10x Oct 20's, then a single function with none), and its value across the call is the compiler's guess (a
// build's picture depended on how it merged the calls). A copy used by one function becomes that function's locals:
// every kernel has 0 Private globals (spirv-dis | grep -c 'OpVariable.*Private'). A helper shared by two worlds takes
// the state it needs as parameters (shapeOfIn, secondarySigmaIn, secLevelAtIn) with a thin wrapper each;
// the copies have no initializers (Slang stores those at the entry point's start), the root sets them on entry.
struct TraceIn {
    uint mode;       // TM_*: secondary, skip water, groups, primary
    uint ablate;     // gAblate
    uint secAblate;  // gSecAblate
    uint secMode;    // gSecMode
    uint secCount;   // gSecCount
    float chordT;    // gChordT: the view distance where the stretch starts
    float secT;      // gSecT: the distance where the chord starts, along its ray
    float alphaXi;   // gAlphaXi
    float groupPa;   // gGroupPa
    float groupTol;  // gGroupTol
    float trans;     // gTrans, gThrough, gWaterChord: a secondary ray's attenuation so far
    float through;
    float waterChord;
    uint4 head;      // gHead
    TraceResult tr;  // gTr as it stands (its dither, its entry axis, whether it has a texel's albedo, its steps)
};
struct TraceOut {
    int code;
    float uncExit;
    TraceResult tr;
    float trans;
    float through;
    float waterChord;
    uint secCount;
};
TraceIn traceInNow() {
    TraceIn ti;
    ti.mode = (gSecondary ? TM_SECONDARY : 0u) | (gSkipWater ? TM_SKIP_WATER : 0u) | (gGroups ? TM_GROUPS : 0u) |
              (gPrimary ? TM_PRIMARY : 0u);
    ti.ablate = gAblate;
    ti.secAblate = gSecAblate;
    ti.secMode = gSecMode;
    ti.secCount = gSecCount;
    ti.chordT = gChordT;
    ti.secT = gSecT;
    ti.alphaXi = gAlphaXi;
    ti.groupPa = gGroupPa;
    ti.groupTol = gGroupTol;
    ti.trans = gTrans;
    ti.through = gThrough;
    ti.waterChord = gWaterChord;
    ti.head = gHead;
    ti.tr = gTr;
    return ti;
}
// (traceSecondaryCore's copy: its statics are traceSecondaryCore's own)
TraceIn traceInNowS() {
    TraceIn ti;
    ti.mode = (sSecondary ? TM_SECONDARY : 0u) | (sSkipWater ? TM_SKIP_WATER : 0u) | (sGroups ? TM_GROUPS : 0u) |
              (sPrimary ? TM_PRIMARY : 0u);
    ti.ablate = sAblate;
    ti.secAblate = sSecAblate;
    ti.secMode = sSecMode;
    ti.secCount = sSecCount;
    ti.chordT = sChordT;
    ti.secT = sSecT;
    ti.alphaXi = sAlphaXi;
    ti.groupPa = sGroupPa;
    ti.groupTol = sGroupTol;
    ti.trans = sTrans;
    ti.through = sThrough;
    ti.waterChord = sWaterChord;
    ti.head = sHead;
    ti.tr = sTr;
    return ti;
}
void traceInApply(TraceIn ti) {
    sSecondary = (ti.mode & TM_SECONDARY) != 0u;
    sSkipWater = (ti.mode & TM_SKIP_WATER) != 0u;
    sGroups = (ti.mode & TM_GROUPS) != 0u;
    sPrimary = (ti.mode & TM_PRIMARY) != 0u;
    sAblate = ti.ablate;
    sSecAblate = ti.secAblate;
    sSecMode = ti.secMode;
    sSecCount = ti.secCount;
    sChordT = ti.chordT;
    sSecT = ti.secT;
    sAlphaXi = ti.alphaXi;
    sGroupPa = ti.groupPa;
    sGroupTol = ti.groupTol;
    sTrans = ti.trans;
    sThrough = ti.through;
    sWaterChord = ti.waterChord;
    sHead = ti.head;
    sTr = ti.tr;
}
// (traceLodCore's copy: its statics are traceLodCore's own)
void traceInApplyC(TraceIn ti) {
    cSecondary = (ti.mode & TM_SECONDARY) != 0u;
    cSkipWater = (ti.mode & TM_SKIP_WATER) != 0u;
    cGroups = (ti.mode & TM_GROUPS) != 0u;
    cPrimary = (ti.mode & TM_PRIMARY) != 0u;
    cAblate = ti.ablate;
    cSecAblate = ti.secAblate;
    cSecMode = ti.secMode;
    cSecCount = ti.secCount;
    cChordT = ti.chordT;
    cSecT = ti.secT;
    cAlphaXi = ti.alphaXi;
    cGroupPa = ti.groupPa;
    cGroupTol = ti.groupTol;
    cTrans = ti.trans;
    cThrough = ti.through;
    cWaterChord = ti.waterChord;
    cHead = ti.head;
    cTr = ti.tr;
}

TRACE_CALL
TraceOut traceLodCore(TraceIn ti, int lod, float3 p0, float3 d, float len) {
    cGroups = false;
    cGroupPa = 0.001;
    cGroupTol = 1.0;
    cChordT = 0.0;
    cAblate = 0u;
    cSkipWater = false;
    cBrickRegion = 0xFFFFFFFFu;
    cBrickKey = -1;
    cBrickAt = 0u;
    cBrickOcc = uint2(0u, 0u);
    cBrickMasks = false;
    cHead = uint4(0u, 0u, 0u, 0u);
    cLeafGap = 0.0;
    cSecondary = false;
    cTrans = 1.0;
    cThrough = 0.0;
    cSecT = 0.0;
    cWaterChord = 0.0;
    cSecAblate = 0u;
    cSecMode = 0u;
    cSecCount = 0u;
    cPrimary = false;
    cSigMat = 0xFFFFFFFFu;
    cSigVal = -1.0;
    cMaskMat = 0xFFFFFFFFu;
    cMask = uint2(0u, 0u);
    cAlphaXi = 0.5;
    traceInApplyC(ti);
    float uncExit;
    int code = traceLodBody(lod, p0, d, len, uncExit);
    if (code == TRACE_CAPPED) {
        code = TRACE_UNCOVERED;
        uncExit = 1e30;
    }
    TraceOut to;
    to.code = code;
    to.uncExit = uncExit;
    to.tr = cTr;
    to.trans = cTrans;
    to.through = cThrough;
    to.waterChord = cWaterChord;
    to.secCount = cSecCount;
    return to;
}

// (the trace's callers: the statics in, the result out)
int traceLod(int lod, float3 p0, float3 d, float len, out float uncExit) {
    TraceOut to = traceLodCore(traceInNow(), lod, p0, d, len);
    gTr = to.tr;
    gTrans = to.trans;
    gThrough = to.through;
    gWaterChord = to.waterChord;
    gSecCount = to.secCount;
    uncExit = to.uncExit;
    return to.code;
}
// (traceSecondaryCore's copy: its statics are traceSecondaryCore's own)
int traceLodS(int lod, float3 p0, float3 d, float len, out float uncExit) {
    TraceOut to = traceLodCore(traceInNowS(), lod, p0, d, len);
    sTr = to.tr;
    sTrans = to.trans;
    sThrough = to.through;
    sWaterChord = to.waterChord;
    sSecCount = to.secCount;
    uncExit = to.uncExit;
    return to.code;
}

int traceLodBody(int lod, float3 p0, float3 d, float len, out float uncExit) {
    uncExit = 1e30;
    if (cTr.steps >= RAY_STEPS) return TRACE_CAPPED;  // (the ray's budget spent: nothing finer is tried)
    // the GPU-built grid's level first (real blocks), the regions (the tiles; level 0 the sections past the grid) only
    // over the runs of chunks where it has no data: the grid again past each
    // (the content-shaped groups keep level-0 primary rays where a frame asks for them unless the grid owns level 0:
    // vgDims.w, ATLAS_VOXGRID_LEVEL0, where the SVDAG level 0 no longer follows the sections)
    // (a level holding no data at all, vgOrigin.w / vgLevel.w bit 2 of the second byte pair: the regions alone)
    // (level 0 with the coarse levels behind its gaps, vgDims.w bits 1 and 4: traced even holding nothing itself)
    bool hasData = lod == 0 ? (frame.vgOrigin.w & 2) != 0 || (frame.vgDims.w & 5u) == 5u
                            : lod <= 6 && ((frame.vgLevel[clamp(lod, 1, 6) - 1].w >> 17) & 1) != 0;
    bool grid = hasData && vgLevelOn(lod) && (lod > 0 || !cGroups || frame.vgDims.w != 0u);
    float u = 0.0;
    if (grid) {
        VgGrid g = vgGrid(lod);
        int seedAxis = -1, seedTo = 0;
        for (int run = 0; run < 16; run++) {
            cLeafGap = lod <= 1 ? 0.3 : 0.0;
            float uStop;
            int3 vStop;
            int r = traceVoxGrid(g, p0, d, u, len, RAY_STEPS, seedAxis, seedTo, uStop, vStop);
            if (r != TRACE_UNCOVERED) return r;
            if (uStop >= len) return TRACE_MISS;
            bool left;
            float uEnd = vgUnloadedEnd(g, p0, d, uStop, len, vStop >> 4, left, seedAxis, seedTo);
            // (the grid owns level 0: a run of level-0 chunks it does not hold yet inside its window is the best of its
            // coarse levels that holds it, never a CPU level 0 or the tiles' stand-in where real blocks are known; the
            // ray stays at level 0 past the run. vgDims.w bit 4, off with ATLAS_VOXGRID_FALLBACK=0: the regions as before)
            if (lod == 0 && (frame.vgDims.w & 5u) == 5u) {  // (left: to the window's edge, then the coarse levels' own)
                float ue;
                int q = vgCoarseRun(p0, d, uStop, uEnd, ue);
                if (q == TRACE_HIT || q == TRACE_CAPPED) return q;
                if (q == TRACE_UNCOVERED) {
                    uncExit = ue;
                    return TRACE_UNCOVERED;
                }
            } else {
                float ue;
                cSecT += uStop;  // (the ray's distance where the regions' chord starts)
                int q = traceLodRegions(lod, p0 + d * uStop, d, uEnd - uStop, ue);
                cSecT -= uStop;
                if (q == TRACE_HIT) {
                    cTr.t += uStop;
                    return TRACE_HIT;
                }
                if (q == TRACE_UNCOVERED) {
                    uncExit = ue < 1e29 ? ue + uStop : 1e30;
                    return TRACE_UNCOVERED;
                }
            }
            if (left || uEnd >= len) return TRACE_MISS;
            // (resumed past the run at the voxel across its far face, seedAxis / seedTo, by index as the walk's own exits
            // are: re-flooring the point a hair past it at a grazing angle restarted inside the run's box)
            u = uEnd + 1e-3;
        }
    }
    // (no grid at this level, or a chord that crossed many runs: the regions for the rest)
    cSecT += u;
    int r0 = traceLodRegions(lod, p0 + d * u, d, len - u, uncExit);
    cSecT -= u;
    if (u > 0.0) {
        if (r0 == TRACE_HIT) cTr.t += u;
        if (uncExit < 1e29) uncExit += u;
    }
    return r0;
}

// Walk the regions of one LOD along the chord p0 + d u, u in [0, len]. On TRACE_UNCOVERED, uncExit is where the chord
// leaves the unloaded region cell (1e30 when it left the LOD's window: that LOD has nothing further along this ray at
// all); a region or its groups that ran out of steps (their caps, the ray's budget) answer TRACE_UNCOVERED too, not a
// miss (a hole where the walk gave up).
TRACE_CALL
int traceLodRegions(int lod, float3 p0, float3 d, float len, out float uncExit) {
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
        if (u >= len) return TRACE_MISS;
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
                    if (cTr.dither >= prog) {
                        if (r.runs.z == 0xFFFFFFFFu) {
                            uncExit = uExit;
                            return TRACE_UNCOVERED;
                        }
                        if (r.runs.z == 0xFFFFFFFEu) air = true;
                        else r = regions[r.runs.z - 1u];
                    }
                }
                float3 ro = (p0 - regionOriginRel(r)) / s;
                cLeafGap = lod <= 1 ? 0.3 : 0.0;
                int got = 0;  // (1 a hit, 2 out of steps)
                if (!air && cGroups && r.groups.x != 0xFFFFFFFFu) {
                    // the chord's first cell: a ray that starts inside this level's solid (a switch to a coarser level)
                    // hits it at once, as the old path does (the groups hold only what a ray can reach from outside)
                    if (firstCell && r.bases.z != 0xFFFFFFFFu) got = traceRegion(r, ro, d / s, u, min(u + 1e-3, uExit), 1e-3);
                    if (got == 0) got = traceGroups(r, ro, d / s, u, uExit, d);
                } else if (!air && r.bases.z != 0xFFFFFFFFu) {
                    got = traceRegion(r, ro, d / s, u, uExit, 1e-3);
                }
                if (got == 1) {
                    cTr.lod = lod;
                    if (dbgPath() && cPrimary) cTr.albedo = float3(0.5, 0.5, 0.0), cTr.hasAlbedo = 1u;
                    return TRACE_HIT;
                }
                if (got == 2) return TRACE_UNCOVERED;  // (uncExit 1e30: given up along this ray at this level)
            }
        }
        firstCell = false;
        if (uExit >= len) return TRACE_MISS;
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
    return TRACE_UNCOVERED;  // (its cell cap: not a miss)
}

// The level-0 grid's id at camera-relative p: -1 where it has no data (outside its window, a chunk without data), else
// the voxel's material (0: air)
int vgVoxel(float3 p) { return vgVoxelL(0, p); }
int vgVoxelL(int k, float3 p) {
    if (!vgLevelOn(k)) return -1;
    VgGrid g = vgGrid(k);
    int3 v = (int3(floor(p)) + frame.camInt.xyz - g.origin) >> k;
    int X = g.X, Y = g.Y, Z = g.Z;
    if (v.x < 0 || v.y < 0 || v.z < 0 || v.x >= X * 16 || v.y >= Y * 16 || v.z >= Z * 16) return -1;
    int3 c = v >> 4;
    uint cw = vgChunks[vgCellG(g, c)];
    if ((cw & 3u) == VG_UNLOADED) return -1;
    if ((cw & 3u) == VG_EMPTY) return 0;
    if ((cw & 3u) == VG_UNIFORM) return int((cw >> 2) & 0xFFFFu);
    int3 l = v & 15;
    uint i = uint(l.x + 16 * (l.z + 16 * l.y));
    return int((vgPool[(cw >> 2) * 2048u + (i >> 1)] >> (16u * (i & 1u))) & 0xFFFFu);
}

// Solid voxel at camera-relative point p in a given LOD (false where that LOD has no data).
bool occupiedAt(int lod, float3 p) {
    {
        int g = vgVoxelL(lod, p);
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
    {
        int g = vgVoxelL(lod, p);
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

// ---- the level a ray takes: one rule for every ray (primary rays here, secondary rays' cones, the near light) ---------
// A continuous level, never a ring: what a pixel covers, and what the levels held cover. A ray takes level
// floor(level + xi) for a per-pixel threshold xi: the stochastic choice between the two levels around it, the share of
// the coarser one the fraction, so the change from one level to the next spreads over the whole octave of distance
// (the host's TAA averages it to a gradient). The rings (each level's window of regions, the grid's windows inside
// them) are what is resident only.

// The continuous level of a footprint: log2 of the blocks a pixel covers there, dist x pixelAngle, widened at grazing
// angles by 1 / |cos| against the surface (cosTheta 1: face on, or not known yet), at most 2x: one level (a block face's
// cos is the staircase's, not the slope's: 4x took far fields and shores two levels down, their rivers and beaches lost)
float lodFromFootprint(float dist, float pixelAngle, float cosTheta) {
    // (ATLAS_LOD's second and third knobs: the least cos, 0.5 by default; a bias)
    float minCos = clamp(frame.lodParams.y, 0.05, 1.0);
    return log2(max(dist * pixelAngle / clamp(abs(cosTheta), minCos, 1.0), 1e-6)) + frame.lodParams.z;
}
// the level taken for continuous level `level` at threshold xi (0..1)
int lodPick(float level, float xi, int lodCount) { return clamp(int(floor(level + xi)), 0, lodCount - 1); }
// the per-pixel threshold (farMain: interleaved gradient noise, rotated by the golden ratio each frame under the host's
// TAA; 0.5 elsewhere)
static float gLodXi = 0.5;
static float sLodXi;  // (traceSecondaryCore's own)

// Residency: each level's window (its region table) as the inner radius r_k it covers around the camera (Chebyshev,
// blocks). The continuous level a point needs for what is held there: k + 1 at r_k, log-linear between the r_k (and
// 1 + log2(d / r_0) inside r_0), so a level is left over the octave before its window ends and its successor, whose
// window is about twice as wide, is always there for the blend: no hole, no ring.
float lodWindowR(int k) {
    LodGpu L = frame.lods[k];
    int span = 256 << k;
    float lox = float(L.origin.x * span - frame.camInt.x) - frame.camFrac.x;
    float loz = float(L.origin.z * span - frame.camInt.z) - frame.camFrac.z;
    float hix = lox + float(L.dim.x * span), hiz = loz + float(L.dim.z * span);
    float r = max(min(min(-lox, hix), min(-loz, hiz)), 1.0);
    // a level whose real blocks are the grid's alone (its regions the tiles' fallback, vgDims.w bit 2 for level 0):
    // what it holds is the grid's window
    if (k <= 6 && (k == 0 ? (frame.vgDims.w & 2u) != 0u && vgOn() : vgLevelOn(k))) {
        VgGrid g = vgGrid(k);
        float gs = float(g.X * (16 << k));
        float gx = float(g.origin.x - frame.camInt.x) - frame.camFrac.x, gz = float(g.origin.z - frame.camInt.z) - frame.camFrac.z;
        r = min(r, max(min(min(-gx, gx + gs), min(-gz, gz + gs)), 1.0));
    }
    return r;
}
// (the radii: FrameGpu.lodR, api.cpp lodRadii's port of lodWindowR above, once a frame: filled in every thread of five
// kernels they were two arrays of eight in local memory)
float lodRAt(int k) { return frame.lodR[k >> 2][k & 3]; }
float lodResident(float3 p) {
    int n = int(frame.dims.w);
    float d = max(max(abs(p.x), abs(p.z)), 1.0);
    float r0 = lodRAt(0);
    if (d <= r0) return 1.0 + log2(d / r0);
    for (int k = 0; k + 1 < n; k++) {
        float rk = lodRAt(k), rn = lodRAt(k + 1);
        if (d <= rn) return float(k + 1) + log2(d / rk) / log2(rn / rk);
    }
    return float(n);
}
// the distance (Chebyshev) at which lodResident reaches `level`
float lodResidentDist(float level) {
    int n = int(frame.dims.w);
    if (level <= 1.0) return lodRAt(0) * exp2(level - 1.0);
    int k = min(int(floor(level)) - 1, 6);
    if (k + 1 >= n) return 1e30;
    return lodRAt(k) * pow(lodRAt(k + 1) / lodRAt(k), level - float(k + 1));
}

// the continuous level of the last primary hit (FRAME_DEBUG_LOD shows it)
static float gHitLod = 0.0;
// where the stretch that hit starts along the view ray (smoothPrimaryHit searches no further back: a finer level, or this
// level's walk, found air before it)
static float gHitFrom = 0.0;

// Trace the curved view ray from t0 to t1. Returns the hit distance along the view ray or -1.
// Each stretch of the ray takes the level of its continuous level (lodFromFootprint at its distance, lodResident at its
// place) at the pixel's threshold, to where that pick would change; where the level has no data (a streaming gap) a
// coarser one, else a finer one. A hit is looked at again with its face's grazing angle: when that footprint wants a
// coarser level, the coarser level's surface near it is the hit (ATLAS_ABLATE 2147483648: not).
// (ATLAS_ABLATE 1048576, measurements: the levels by ring as before: the finest level with data (groups: from level 0;
// else floor(log2(footprint))), its segment grid phased per pixel by lodDither: a stochastic band a segment wide)
float traceViewBody(float3 o, float3 dir, float t0, float t1, float lodDither) {
    bool rings = (gAblate & 1048576u) != 0u;
    int phasedLod = -1;  // (rings) the level whose segment grid was last phase-shifted
    float shrink = 0.0;  // (rings) fraction cut off the current segment
    float R = frame.camFrac.w;
    float k = R > 0 ? dot(dir.xz, dir.xz) / (2.0 * R) : 0.0;
    float pa = frame.params.w * frame.params.z;
    int lodCount = int(frame.dims.w);
    float worldTop = 640.0 - float(frame.camInt.y);
    // (the Chebyshev distance from the camera grows by this a block along the ray)
    float hz = max(max(abs(dir.x), abs(dir.z)), 1e-4);
    float xi = gLodXi;
    int groupsFrom = gGroups ? min(int((gAblate >> 16u) & 7u), lodCount - 1) : 0;  // (ATLAS_ABLATE bits 16-18)
    int lod = 0;
    float t = t0;
    bool finer = false;   // this stretch is searching downwards for any level with data
    int pinned = -1;      // a finer level that had data while the wanted one did not (streaming), and for how long
    int pinLeft = 0;
    float gapEnd = 1e30;  // nearest end of an unloaded region cell seen while searching this stretch
    // (a grazing hit's second look: the coarser level over [t, grazeEnd), the finer hit kept for when it has none)
    bool graze = false;
    float grazeEnd = 0.0, fineT = 0.0, fineFrom = 0.0;
    TraceResult fine = gTr;
    for (int seg = 0; seg < 768; seg++) {
        if (t >= t1) break;
        float3 a = worldAt(o, dir, k, t);
        // rising above the world top: the parabola only climbs further from here
        if (!graze && a.y > worldTop && dir.y + 2.0 * k * t >= 0.0) break;
        float lc = rings || graze ? 0.0 : max(lodFromFootprint(t, pa, 1.0), lodResident(a));
        int want = graze ? lod : max(lodPick(lc, xi, lodCount), groupsFrom);
        if (rings) want = gGroups ? groupsFrom : int(clamp(floor(log2(max(t * pa, 1.0))), 0.0, float(lodCount - 1)));
        if (!finer && !graze) lod = pinLeft > 0 ? pinned : max(lod, want);
        float s = float(1 << lod);
        // where this pick would change: the footprint, or the place, reaching lod + 1 at the threshold
        float tEnd = graze ? grazeEnd : 1e30;
        if (rings && lodDither > 0.0 && lod != phasedLod) {
            phasedLod = lod;
            shrink = 0.9 * frac(lodDither + 0.618034 * float(lod));
        }
        if (!graze && !rings && lod == want && lod + 1 < lodCount) {
            float target = float(lod + 1) - xi;
            float tF = exp2(target) / pa;
            float tR = (lodResidentDist(target) - max(abs(o.x), abs(o.z))) / hz;
            tEnd = max(min(tF, tR), t + 0.5);
        }
        // (groups: longer chords, each one re-enters a region's tree from its root; the chord's sag is still a few
        // hundredths of a block at the real radius)
        float tn = min(min(t + (gGroups ? 768.0 : 192.0) * s * (1.0 - shrink), tEnd), t1);
        float3 b = worldAt(o, dir, k, tn);
        float len = tn - t;
        float3 d = (b - a) / len;
        float uncExit;
        gChordT = t;
        int r = traceLod(lod, a, d, len, uncExit);
        if (graze) {
            // the coarser level's surface near the grazing hit, else the finer hit as it was
            if (r == TRACE_HIT) {
                gHitFrom = t;
                return t + gTr.t;
            }
            gTr = fine;
            gHitFrom = fineFrom;
            return fineT;
        }
        if (r == TRACE_HIT) {
            float th = t + gTr.t;
            float3 ph = a + d * gTr.t;
            float cs = gTr.axis >= 0 ? abs(d[gTr.axis]) : 1.0;
            float lh = rings ? float(lod) : max(lodFromFootprint(th, pa, cs), lodResident(ph));
            gHitLod = rings ? float(lod) : max(lh, float(lod));
            int k2 = lodPick(lh, xi, lodCount);
            // (a coarse cube that wants its smooth surface, HIT_SMOOTH_WANT, has no second look: its cube face's cos is
            // the staircase's, not the surface's; the smooth surface is what the footprint asks for there)
            if (!rings && (gAblate & 2147483648u) == 0u && k2 > lod && !finer && gTr.hasAlbedo != HIT_SMOOTH_WANT) {
                // seen at a grazing angle, its footprint wants a coarser level: that level's surface around the hit,
                // from a little in front of it (the next stretch)
                float s2 = float(1 << k2);
                fine = gTr;
                fineT = th;
                fineFrom = t;
                graze = true;
                lod = k2;
                grazeEnd = min(th + 3.0 * s2, t1);
                t = max(t0, th - 3.0 * s2);
                gTr.axis = -1;
                gTr.hasAlbedo = 0u;  // (the coarser level's own surface: not the finer hit's texel)
                continue;
            }
            gHitFrom = t;
            return th;
        }
        if (r == TRACE_UNCOVERED) {
            gTr.axis = -1;
            pinLeft = 0;
            // chord parameter equals view distance only to O(k): fine for choosing where to resume
            gapEnd = min(gapEnd, uncExit < 1e29 ? t + uncExit : 1e30);
            // coarser first (cheaper, and what lies past this level's data wants); with nothing coarser, settle for
            // finer data rather than a hole while tiles are still streaming in
            if (!finer && lod + 1 < lodCount) { lod++; continue; }
            if (!finer) {
                lod = min(lod, want);  // levels from here up were all just found empty-handed
                finer = true;
            }
            if (lod > 0) {
                lod--;
                continue;
            }
            // no level has this stretch (a streaming gap): resume where the nearest unloaded cell ends and look for
            // land loaded behind it; when every level has left its window there is nothing further at all
            if (gapEnd >= 1e29) break;
            t = min(max(gapEnd, t) + max(1e-3, t * 1e-6), t1);  // (a step float keeps past 16 km)
            gapEnd = 1e30;
            finer = false;
            lod = want;
            continue;
        }
        // this stretch had data at `lod` and missed; when that took a downward search, stay on that level for a few
        // stretches instead of repeating the search every one
        if (finer) { pinned = lod; pinLeft = 8; }
        else if (pinLeft > 0) pinLeft--;
        t = tn;
        shrink = 0.0;
        finer = false;
        gapEnd = 1e30;
    }
    return -1.0;
}

// A coarse level's cube hit by a primary ray where its voxel is wider than the pixel (traceVoxGrid's HIT_SMOOTH_WANT): its
// level's smooth surface (coarseSmoothHit) along the ray's tangent there (its parameter the view ray's), from a voxel
// before the cube's entry (a concave corner's surface stands in front of it; never before the hitting stretch's start,
// gHitFrom) to a voxel and a half past it (a convex edge's lies inside; never past t1). Once a ray, after its walk: one
// call where the warp's rays are done, not one inside the walk's divergent loop. Returns the hit, or -1: no crossing (the
// ray passes the voxel's corner: traceView walks on past it).
float smoothPrimaryHit(float3 o, float3 dir, float t1, float tHit) {
    gTr.hasAlbedo = 0u;
    int k = clamp(gTr.lod, 1, 6);
    VgGrid g = vgGrid(k);
    float vs = float(1 << k);
    float R = frame.camFrac.w;
    float kc = R > 0 ? dot(dir.xz, dir.xz) / (2.0 * R) : 0.0;
    float3 tg = dir + float3(0, 2.0 * kc * tHit, 0);
    float3 ro = (worldAt(o, dir, kc, tHit) - float3(g.origin - frame.camInt.xyz)) / vs;
    bool skipW = gSkipWater || (frame.dims.z & FRAME_SEE_THROUGH_WATER) != 0u;
    float back = -min(vs, max(tHit - gHitFrom, 0.0));
    SmoothHit sh = coarseSmoothHit(g, ro, tg / vs, back, min(1.5 * vs, max(t1 - tHit, 0.0)), skipW);
    if (!sh.hit) return -1.0;
    // (inside the density already where the search starts: no crossing in front of the cube was seen, the cube's entry
    // is the surface (a neighbour without data counts as solid: above a chunk on its own the search started inside and
    // the surface stood a voxel above the data, the coarse test's 74 for 76)
    if (sh.t <= back + 1e-6) sh.t = 0.0;
    float3 an = abs(sh.n);
    gTr.t += sh.t;
    gTr.axis = an.x >= an.y && an.x >= an.z ? 0 : (an.y >= an.z ? 1 : 2);
    gTr.albedo = sh.albedo;
    gTr.hasAlbedo = packSmoothN(sh.n);
    return tHit + sh.t;
}

// The view's ray (traceViewBody): a primary ray (gPrimary) from its own start (gSecT 0: the distance of a chord's
// start along the view ray is gChordT + gSecT)
float traceView(float3 o, float3 dir, float t0, float t1, float lodDither) {
    gPrimary = true;
    gSecT = 0.0;
    float r = -1.0;
    // (one walk call site: a smooth search that finds no crossing walks on once from a voxel and a half past the cube,
    // its second miss keeps the cube)
    for (int pass = 0; pass < 2; pass++) {
        r = traceViewBody(o, dir, t0, t1, lodDither);
        if (r < 0.0 || gTr.hasAlbedo != HIT_SMOOTH_WANT) break;
        float vs = float(1 << clamp(gTr.lod, 1, 6));
        float rs = smoothPrimaryHit(o, dir, t1, r);
        if (rs >= 0.0) {
            r = rs;
            break;
        }
        if (pass == 1) break;  // (the cube)
        t0 = r + 1.5 * vs;
        if (t0 >= t1) {
            r = -1.0;
            break;
        }
        gTr.axis = -1;
    }
    gPrimary = false;
    return r;
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

// (secondary rays) their cone, the same rule as the primary rays' (lodFromFootprint, lodPick): the footprint at the
// ray's origin (blocks) and its growth per block travelled; gSecSpread 0: none, the ray keeps its level (coarser only
// where that one has no data). The far field's rays start at their hit's footprint and widen as the pixel's cone does.
static float gSecFoot0 = 0.0;
static float sSecFoot0;  // (traceSecondaryCore's own)
static float gSecSpread = 0.0;
static float sSecSpread;  // (traceSecondaryCore's own)
// the level a secondary ray takes at t (never finer than `lod`, the one it is on), and its stretch cut where the pick
// changes; cap: the coarsest it may take
int secLevelAtIn(float3 p, float3 dir, float t, int lod, int cap, float foot0, float spread, inout float len, float xi) {
    if (spread <= 0.0) return lod;
    float lc = max(log2(max(foot0 + t * spread, 1e-6)), lodResident(p + dir * t));
    int want = min(max(lod, lodPick(lc, xi, cap + 1)), max(lod, cap));
    if (want < cap) {
        float tl = (exp2(float(want + 1) - xi) - foot0) / spread;
        if (tl > t) len = min(len, tl - t);
    }
    return want;
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
        float len = min(192.0 * float(1 << lod), maxT - t);
        lod = secLevelAtIn(p, dir, t, lod, lodCount - 1, gSecFoot0, gSecSpread, len, gLodXi);
        float s = float(1 << lod);
        len = min(len, 192.0 * s);
        float uncExit;
        gTr = res;
        int r = traceLod(lod, p + dir * t, dir, len, uncExit);
        res = gTr;
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
// 1280x800 (the near light's sun ray; its bounce's as much again). 0 (and no gSecFoot): gSecSpread's cone, if any (the
// far field's hits: secLevelAt). Every footprint here is lodFromFootprint's.
static float gSecCone = 0.0;
static float sSecCone;  // (traceSecondaryCore's own)
static const int SEC_CONE_LOD = 4;
// (the light by need, nearLightAt) the cone's level steps, each surface point's own: gSecCone x a factor in [1, 2) fixed to
// the point (surfaceRand), so where a ray turns coarser is spread over the octave across a face (no ring where it changes)
static float gSecConeJit = 1.0;
static float sSecConeJit;  // (traceSecondaryCore's own)
// the bounce's own sun ray (bounceFrom): its cone (0: none); bounced light is soft, its occluders need no fine voxels
static float gBounceCone = 0.0;
// (nearLightAt) the pixel's footprint (blocks, 0: none): a secondary ray may go a level coarser every SEC_FOOT_VOXELS
// voxels while the next level's voxels are no wider than it (their errors then fall inside one pixel)
static float gSecFoot = 0.0;
static float sSecFoot;  // (traceSecondaryCore's own)
static const float SEC_FOOT_VOXELS = 4.0;
// the sample's index in the light's cycles (sky rays' strata, the far lights' emitter points, the dithers below): the
// split's own trace count of the pixel (nearLightMain), else -1: the frame's (the TAA's frames)
static int gSampleIndex = -1;
uint lightSample() {
    if (gSampleIndex >= 0) return uint(gSampleIndex);
    return frame.jitter.z > 0.5 ? frame.temporal.x : 0u;
}
// a uniform number in [0, 1) fixed to the surface point (camera-relative p; world eighths of a block) and use (salt):
// where a ray's level steps is the same for that point frame after frame (the history's test of the key light's
// visibility against its own then sees only real change), the points of a face spread over the octave
float surfaceRand(float3 p, uint salt) {
    int3 q = int3(floor((p + float3(frame.camInt.xyz)) * 8.0));
    uint h = hashU(uint(q.x) * 73856093u ^ (uint(q.y) * 19349663u) ^ (uint(q.z) * 83492791u) ^ (salt * 2654435761u));
    return float(h & 0xFFFFFFu) / 16777216.0;
}
// (the secondary ray's own state and results as arguments and a result, as traceLod's: see TraceIn. Two rays from the
// same point with different cones, footprints or modes are different calls)
struct SecIn {
    TraceIn base;    // the trace state the ray's chords start from (traceInNow)
    float cone;      // gSecCone, gSecConeJit, gSecFoot, gSecSpread, gSecFoot0, gLodXi
    float coneJit;
    float foot;
    float spread;
    float foot0;
    float lodXi;
};
struct SecOut {
    float vis;
    float trans;
    float through;
    float waterChord;
    uint stopFoliage;
    float hitT;
    uint hitMat;
    int hitAxis;
    int hitLod;
    uint secCount;
};
TRACE_CALL
SecOut traceSecondaryCore(SecIn si, float3 p, float3 dir, float maxT, int lod) {
    sGroups = false;
    sGroupPa = 0.001;
    sGroupTol = 1.0;
    sChordT = 0.0;
    sAblate = 0u;
    sSkipWater = false;
    sHead = uint4(0u, 0u, 0u, 0u);
    sSecondary = false;
    sTrans = 1.0;
    sThrough = 0.0;
    sSecT = 0.0;
    sWaterChord = 0.0;
    sSecAblate = 0u;
    sSecMode = 0u;
    sSecCount = 0u;
    sPrimary = false;
    sAlphaXi = 0.5;
    sLodXi = 0.5;
    sSecFoot0 = 0.0;
    sSecSpread = 0.0;
    sSecCone = 0.0;
    sSecConeJit = 1.0;
    sSecFoot = 0.0;
    // (its state is its own, s*: the caller's is never touched, so nothing is put back; its result is all it gives)
    traceInApply(si.base);
    sPrimary = false;
    sSkipWater = false;
    sSecCone = si.cone;
    sSecConeJit = si.coneJit;
    sSecFoot = si.foot;
    sSecSpread = si.spread;
    sSecFoot0 = si.foot0;
    sLodXi = si.lodXi;
    TraceResult res;
    res.t = 0; res.mat = 0; res.axis = -1; res.lod = 0; res.steps = 0; res.dither = 0.5; res.albedo = float3(0, 0, 0); res.hasAlbedo = 0u;
    int lodCount = int(frame.dims.w);
    sSecondary = true;
    secCount(7u, 0);
    sTrans = 1.0;
    sThrough = 0.0;
    sWaterChord = 0.0;
    SecOut so;
    so.stopFoliage = 0u;
    so.hitT = 0.0;
    so.hitMat = 0xFFFFFFFFu;
    so.hitAxis = 1;
    so.hitLod = 0;
    float t = 0;
    float vis = 1.0;
    // (the far field's rays: the pixel's cone, the primary rays' continuous rule, over the levels' radii, filled here)
    bool farCone = sSecSpread > 0.0 && sSecCone <= 0.0 && sSecFoot <= 0.0;
    for (int seg = 0; seg < 48; seg++) {
        if (t >= maxT) break;
        float s = float(1 << lod);
        float len = min(192.0 * s, maxT - t);
        if (farCone) {
            lod = secLevelAtIn(p, dir, t, lod, lodCount - 1, sSecFoot0, sSecSpread, len, sLodXi);
            len = min(len, 192.0 * float(1 << lod));
        } else if ((sSecCone > 0.0 || sSecFoot >= 2.0 * s) && lod < SEC_CONE_LOD && lod + 1 < lodCount) {
            // (the footprint's coarsening while the next level's voxels are no wider than it, then the sun's cone)
            float edge = (sSecFoot >= 2.0 * s ? SEC_FOOT_VOXELS : (sSecCone > 0.0 ? sSecCone : 1e30)) * s * sSecConeJit;
            // (a ray going up) never while it is still within two of the next level's voxels of the surface it left
            // (its height over a level surface, dir.y a block): at a low sun a ray skims its own ground, and the next level's fatter
            // voxels there shadowed the surface itself (sunlit ground striped dark at dawn and dusk)
            if (dir.y > 0.05) edge = max(edge, min(4.0 * s / dir.y, 96.0 * s));
            if (t >= edge) { lod++; continue; }
            len = min(len, edge - t);
        }
        float uncExit;
        sSecT = t;
        sTr = res;
        int r = traceLodS(lod, p + dir * t, dir, len, uncExit);
        res = sTr;
        if (r == TRACE_HIT) {
            so.stopFoliage = sTrans <= 0.0 ? 1u : 0u;
            so.hitT = t + res.t;
            so.hitMat = res.mat;
            so.hitAxis = res.axis;
            so.hitLod = res.lod;
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
    if (vis <= 0.0) {
        if (so.stopFoliage == 0u) sThrough = 0.0;
        sTrans = 0.0;
    } else {
        sThrough += sTrans;  // what crossed every leaf: exp(-0.45 x) of no first leaf at all is 1
    }
    so.vis = vis <= 0.0 ? 0.0 : sTrans;
    so.trans = sTrans;
    so.through = sThrough;
    so.waterChord = sWaterChord;
    so.secCount = sSecCount;
    return so;
}

// A secondary ray (the statics in: its cone, footprint, the trace state; its results out into the statics callers read)
float traceSecondaryAs(float3 p, float3 dir, float maxT, int lod, float cone, float coneJit, float foot, float spread) {
    SecIn si;
    si.base = traceInNow();
    si.base.mode &= ~(TM_PRIMARY | TM_SKIP_WATER);
    si.base.tr.steps = 0u;
    si.cone = cone;
    si.coneJit = coneJit;
    si.foot = foot;
    si.spread = spread;
    si.foot0 = gSecFoot0;
    si.lodXi = gLodXi;
    SecOut so = traceSecondaryCore(si, p, dir, maxT, lod);
    gTrans = so.trans;
    gThrough = so.through;
    gWaterChord = so.waterChord;
    gStopFoliage = so.stopFoliage != 0u;
    gSecCount = so.secCount;
    if (so.vis <= 0.0) {
        gSecHitT = so.hitT;
        gSecHitMat = so.hitMat;
        gSecHitAxis = so.hitAxis;
        gSecHitLod = so.hitLod;
    }
    return so.vis;
}
float traceSecondary(float3 p, float3 dir, float maxT, int lod) {
    return traceSecondaryAs(p, dir, maxT, lod, gSecCone, gSecConeJit, gSecFoot, gSecSpread);
}

// Whether camera-relative p lies under solid cover (rock over a cave, a roof; leaves are no cover): a ray straight up
// stopped by something solid before the world top. A point the key light reaches is never under it (callers ask only
// where it does not). Clobbers traceSecondary's globals.
// (never as a cone, nor coarsened by the pixel's footprint: a roof is found at the level the point is lit at, rock
// overhead at a coarse level can be open; a footprint-coarsened roof ray found false roofs, black-air speckle)
bool solidRoof(float3 p, int lod) {
    return traceSecondaryAs(p, float3(0, 1, 0), 2048.0, lod, 0.0, 1.0, 0.0, 0.0) <= 0.0 && !gStopFoliage;
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
        float cone = gSecCone;
        gSecCone = gBounceCone;
        vis = traceSecondary(hp + L * (0.05 * hs), L, 2048.0, hl);
        gSecCone = cone;
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
    uint fi = lightSample() & 15u;  // a 16-sample cycle: a still pixel converges to one stratified set
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
        gTr = rr;
        int hit = traceLod(l, o + r * tr, r, len, uncExit);
        rr = gTr;
        if (hit == TRACE_HIT) {
            MaterialGpu m = palette[rr.mat];
            // a grazing reflection that skims back into the water sees the water's own sky, not a dark "top"
            if (uint(m.emisClass.y) == 2u) return sky;
            // shaded by the face it met (a shore's sides darker than its tops), unshadowed: soft and cheap at
            // reflection scale
            float3 n = float3(0, 1, 0);
            if (rr.axis >= 0) {
                n = float3(0, 0, 0);
                n[rr.axis] = r[rr.axis] > 0.0 ? -1.0 : 1.0;
            }
            float3 c = m.albedoRough.rgb * (keyLightRadiance(sun) * saturate(dot(n, keyLightDir(sun))) * 0.8 + skyAmbient(n, sun));
            return applyAerial(c, r, sun, tr + rr.t, frame);
        }
        if (hit == TRACE_UNCOVERED) {
            if (l + 1 < lodCount) { l++; continue; }
            break;
        }
        tr += len;
        if (o.y + r.y * tr > 640.0 - float(frame.camInt.y)) break;
        // (a level coarser every 96 of its voxels: what a reflection shows far off needs no fine voxels)
        if (tr > 96.0 * float(1 << l) && l + 1 < lodCount) l++;
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

// The far lights of this pixel's 32 px tile: where its list starts and how long it is (false: no lights this frame), and
// its k-th light (li: an index of the light, the same for every tile it is in). Either the lists lights.cpp packed
// after the far actors (start: the tile's first index entry) or, under FRAME_GPU_LIGHTS, the lists lightBinMain built
// (start: the tile's list in lightList).
static const uint LIGHT_TILE_PX = 32u;
static const uint LIGHTS_PER_TILE = 64u;
static const uint LL_HIST = 4u;      // lightList: [0] candidates, [1] taken from the threshold bin, [2..3] unused,
static const uint LL_TILES = 260u;   // [4, 260) the score histogram, then (1 + LIGHTS_PER_TILE) words a tile
static const uint LIGHT_BINS = 256u;
bool lightTile(uint2 px, out uint start, out uint count) {
    start = 0u;
    count = 0u;
    uint L = uint(frame.weatherParams.y);
    if (L == 0u) return false;
    uint tilesX = (frame.dims.x + 31u) / 32u, tilesY = (frame.dims.y + 31u) / 32u;
    uint tile = (px.y / 32u) * tilesX + px.x / 32u;
    if ((frame.dims.z & FRAME_GPU_LIGHTS) != 0u) {
        start = LL_TILES + tile * (1u + LIGHTS_PER_TILE);
        count = min(lightList[start], LIGHTS_PER_TILE);
        return count > 0u;
    }
    uint A = uint(frame.weatherParams.w);
    uint tableBase = A + L, indexBase = tableBase + (tilesX * tilesY + 5u) / 6u;
    start = actorWord(tableBase + tile / 6u, (tile % 6u) * 2u);
    count = min(actorWord(tableBase + tile / 6u, (tile % 6u) * 2u + 1u), 64u);
    return count > 0u;
}

ActorGpu lightOfTile(uint start, uint k, out uint li) {
    if ((frame.dims.z & FRAME_GPU_LIGHTS) != 0u) {
        uint slot = lightList[start + 1u + k];
        ActorGpu l;
        l.posYaw = lightCand[slot * 4u];
        l.shape = lightCand[slot * 4u + 1u];
        l.motion = lightCand[slot * 4u + 2u];
        li = asuint(lightCand[slot * 4u + 3u].w) >> 8u;  // (its record: the same light the same index every frame)
        return l;
    }
    uint L = uint(frame.weatherParams.y), A = uint(frame.weatherParams.w);
    uint tilesX = (frame.dims.x + 31u) / 32u, tilesY = (frame.dims.y + 31u) / 32u;
    uint indexBase = A + L + (tilesX * tilesY + 5u) / 6u;
    uint e = start + k;
    li = actorWord(indexBase + e / 12u, e % 12u);
    return actors[A + li];
}

// The glow of the far lights over this pixel: each light of the pixel's tile that nothing nearer hides (its distance
// against the pixel's surface, with a voxel of slack) adds a core about a pixel wide (or its own size up close) and a
// halo that widens with the haze between it and the camera. Town lights show from dusk to dawn, fires flicker, lava
// pulses, spray is lit by the sun and sky.
float3 farLights(uint2 px, float3 o, float3 dir, float surface, float3 sun) {
    uint start, count;
    if (!lightTile(px, start, count)) return float3(0, 0, 0);
    float night = smoothstep(0.12, -0.1, sun.y);
    float day = smoothstep(-0.12, 0.2, sun.y);
    float pa = max(frame.params.w, 1e-5);
    float t = frame.sunDir.w;
    float3 sum = float3(0, 0, 0);
    for (uint k = 0u; k < count; k++) {
        uint li;
        ActorGpu l = lightOfTile(start, k, li);
        float dist = l.motion.x;
        float slack = max(3.0, dist * pa * 3.0) + l.motion.w;
        if (surface < dist - slack) continue;  // terrain, a building or an actor in front of it
        uint kind = uint(l.shape.w + 0.5);
        float vis = kind == 0u ? night
                  : kind == 1u ? (0.1 + 0.9 * night) * (0.82 + 0.18 * sin(t * 11.0 + l.motion.y * 7.0) * sin(t * 4.3 + l.motion.y))
                  : kind == 2u ? 0.85 + 0.15 * sin(t * 1.3 + l.motion.y)
                  : kind == 4u ? 1.0  // (a block's own emitter: steady)
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
        // (a block emitter of the grid, LIGHT_BLOCK: its surface draws it, near as Minecraft draws a lantern; no core
        // or halo over it, which drew a ring round every lamp)
        if (kind == 4u) {
            core = 0.0;
            glow = 0.0;
        }
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
    uint start, count;
    if (!lightTile(px, start, count)) return float3(0, 0, 0);
    float night = smoothstep(0.12, -0.1, sun.y);
    uint fi = lightSample() & 15u;  // a 16-sample cycle: a still pixel converges to one stratified set
    float s = float(1 << lod);
    float3 sum = float3(0, 0, 0);
    for (uint k = 0u; k < count; k++) {
        uint li;
        ActorGpu l = lightOfTile(start, k, li);
        uint kind = uint(l.shape.w + 0.5);
        if (kind == 3u) continue;  // spray is lit, it lights nothing
        float vis = kind == 0u ? night : 1.0;
        if (vis <= 0.001) continue;
        // the record's radiance is scaled for the glow (6 x intensity x falloff(dist), lights.cpp): undo it
        float fall = 1.0 / pow(1.0 + l.motion.x / 1500.0, 1.5);
        float3 base = l.shape.xyz / (6.0 * fall);
        float inten = max(base.r, max(base.g, base.b));
        if (inten <= 1e-4) continue;
        float level = 15.0 * min(inten, 1.0) + (kind == 2u ? l.motion.w : 0.0);  // (kind 4: its level, steady)
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
        if (kind == 2u || kind == 4u) {  // (lava, a block emitter: the record's own hue at the warm hue's luminance)
            float3 h = base / inten;
            hue = h * (dot(BLOCK_LIGHT_HUE, float3(0.2126, 0.7152, 0.0722)) / max(dot(h, float3(0.2126, 0.7152, 0.0722)), 1e-4));
        }
        sum += hue * (E * vis * occ * BLOCK_LIGHT_SCALE);
    }
    return sum;
}

// ---- the far lights culled, ranked and binned on the GPU (FRAME_GPU_LIGHTS) -----------------------------------------
// lights.cpp's Lights::frame, as three passes before farMain (the CPU's version stays for the CPU backend): it cost the
// render thread 25-28 ms a frame live (every light of every cell in reach projected, sorted and binned each frame).
// lightSrc (lights.cpp packGpu, uploaded when the lights change): [0] cells, records, the cluster distance, the budget;
// a float4 a cell (its 1 km cell's coordinates); three a record: (x, y, z within the cell's corner, radius), (linear
// rgb, intensity), (kind, flicker seed, cell, 1 for a far cluster).
//  lightCullMain (a thread a record): the cell's fine lights within the cluster distance, its clusters past it; the
//    kind by the time of day; the near field's radius and the far plane; projected, its reach on screen (glow, halo,
//    the walls it lights) against the frame; each survivor appended to lightCand with its score (intensity x falloff)
//    counted in a log-score histogram.
//  lightAdmitMain (a thread a candidate): the budget's threshold from the histogram, brightest first (the bin it falls
//    in shares what is left of the budget first come).
//  lightBinMain (a group a 32 px tile): the tile's candidates by the same histogram, its brightest LIGHTS_PER_TILE.
static const uint LIGHT_CAND_CAP = 65536u;
float lightFalloff(float d) { return 1.0 / pow(1.0 + d / 1500.0, 1.5); }
uint lightBin(float score) { return uint(clamp((log2(max(score, 1e-12)) + 24.0) * 8.0, 0.0, float(LIGHT_BINS - 1u))); }
static const uint LIGHT_REJECTED = 0xFFFFFFFFu;

[shader("compute")]
[numthreads(64, 1, 1)]
void lightCullMain(uint3 id : SV_DispatchThreadID) {
    float4 head = lightSrc[0];
    uint cells = asuint(head.x), records = asuint(head.y);
    float clusterDist = head.z;
    uint i = id.x;
    if (i >= records) return;
    uint rb = 1u + cells + 3u * i;
    float4 r0 = lightSrc[rb], r1 = lightSrc[rb + 1u], r2 = lightSrc[rb + 2u];
    uint kind = asuint(r2.x), cell = asuint(r2.z);
    bool coarse = asuint(r2.w) != 0u;
    float sunY = frame.sunDir.y;
    if (kind == 0u && !(sunY < 0.14)) return;   // town lights from dusk to dawn
    if (kind == 3u && !(sunY > -0.12)) return;  // spray by day
    float4 c = lightSrc[1u + cell];
    int cx = asint(c.x), cz = asint(c.y);
    // the cell's corner from the eye (exact: integer blocks first)
    float x0 = float(cx * 1024 - frame.camInt.x) - frame.camFrac.x, z0 = float(cz * 1024 - frame.camInt.z) - frame.camFrac.z;
    float nearEnd = frame.params.x, maxDist = frame.params.y;
    float dx = max(max(x0, -(x0 + 1024.0)), 0.0), dz = max(max(z0, -(z0 + 1024.0)), 0.0);
    float cnear = sqrt(dx * dx + dz * dz);
    if (cnear > maxDist) return;
    float fx = max(abs(x0), abs(x0 + 1024.0)), fz = max(abs(z0), abs(z0 + 1024.0));
    // (the unified field has no near field of the host's: every light is atlas's, however near)
    bool unifiedField = (frame.dims.z & FRAME_PRIMARY_NEAR_LIT) != 0u;
    if (unifiedField) nearEnd = 0.0;
    if (sqrt(fx * fx + fz * fz) < nearEnd) return;
    if (coarse != (cnear > clusterDist)) return;
    float rx = x0 + r0.x, ry = r0.y - float(frame.camInt.y) - frame.camFrac.y, rz = z0 + r0.z;
    float h2 = rx * rx + rz * rz;
    float R = frame.camFrac.w;
    if (R > 0.0) ry -= h2 / (2.0 * R);
    float d = sqrt(h2 + ry * ry), radius = r0.w;
    if (d < nearEnd + radius || d > maxDist) return;
    if (gridLightsCover(float3(rx, ry, rz) + frame.camFrac.xyz)) return;  // (the grid's own emitter: gridLightMain)
    float4 clip = frame.viewProj[0] * rx + frame.viewProj[1] * ry + frame.viewProj[2] * rz + frame.viewProj[3];
    // (behind the eye: nothing on screen, unless near enough to light what is in front of it: every tile, as
    // gridLightEmit's; unified field only, where a light that near is atlas's)
    float behindRange = 15.0 * min(r1.w, 1.0) + (kind == 2u ? radius : 0.0) + 1.0;
    bool behind = clip.w <= 1e-4;
    if (behind && !(unifiedField && kind != 3u && d <= 1.6 * behindRange)) return;
    float W = float(frame.dims.x), H = float(frame.dims.y);
    float px = behind ? 0.5 * W : (clip.x / clip.w * 0.5 + 0.5) * W, py = behind ? 0.5 * H : (0.5 - clip.y / clip.w * 0.5) * H;
    float pa = max(frame.params.w, 1e-5);
    float sigma = max(0.5, 0.3 * radius / (d * pa));
    float T = exp(-d * frame.farLight.x);
    float halo = 1.5 + 8.0 * (1.0 - T);
    float intensity = r1.w;
    float range = kind == 3u ? 0.0 : 15.0 * min(intensity, 1.0) + (kind == 2u ? radius : 0.0) + 1.0;
    float screen = max(W, H);
    float lit = range <= 0.0 ? 0.0 : d <= 1.6 * range ? screen : 1.6 * range / ((d - range) * pa);
    float reach = min(screen, max(3.0 * sigma + 3.0 * halo, lit));
    if (px + reach < 0.0 || py + reach < 0.0 || px - reach > W || py - reach > H) return;
    float score = intensity * lightFalloff(d);
    uint slot;
    InterlockedAdd(lightList[0], 1u, slot);
    if (slot >= LIGHT_CAND_CAP) return;
    uint bin = lightBin(score);
    float k = 6.0 * score;
    // the kernel's record (as lights.cpp writes it: camera-relative to camInt), then where it lands on screen
    lightCand[slot * 4u] = float4(rx + frame.camFrac.x, ry + frame.camFrac.y, rz + frame.camFrac.z, sigma);
    lightCand[slot * 4u + 1u] = float4(r1.xyz * k, float(kind));
    lightCand[slot * 4u + 2u] = float4(d, r2.y, halo, radius);
    // (w: the score's bin, then the record: a tile's list is ordered by it, so its sum is the same whatever order the
    // candidates were appended in, frame after frame)
    lightCand[slot * 4u + 3u] = float4(px, py, reach, asfloat(bin | (i << 8u)));
    InterlockedAdd(lightList[LL_HIST + bin], 1u);
}

// The bin the budget ends in (brightest first) and how many of it still fit: -1 when everything does.
groupshared int gsLightT;
groupshared uint gsLightRem;
void lightThreshold(uint budget, uint histBase, uint tid) {
    if (tid == 0u) {
        uint cum = 0u;
        gsLightT = -1;
        gsLightRem = 0u;
        for (int b = int(LIGHT_BINS) - 1; b >= 0; b--) {
            uint nb = lightList[histBase + uint(b)];
            if (cum + nb > budget) {
                gsLightT = b;
                gsLightRem = budget - cum;
                break;
            }
            cum += nb;
        }
    }
    GroupMemoryBarrierWithGroupSync();
}

[shader("compute")]
[numthreads(64, 1, 1)]
void lightAdmitMain(uint3 id : SV_DispatchThreadID, uint3 gt : SV_GroupThreadID) {
    uint budget = asuint(lightSrc[0].w);
    lightThreshold(budget, LL_HIST, gt.x);
    uint n = min(lightList[0], LIGHT_CAND_CAP), i = id.x;
    if (i >= n) return;
    uint bin = asuint(lightCand[i * 4u + 3u].w) & 255u;
    if (gsLightT < 0 || int(bin) > gsLightT) return;
    bool keep = false;
    if (int(bin) == gsLightT) {
        uint taken;
        InterlockedAdd(lightList[1], 1u, taken);
        keep = taken < gsLightRem;
    }
    if (!keep) lightCand[i * 4u + 3u].w = asfloat(LIGHT_REJECTED);
}

groupshared uint gsTileHist[256];
groupshared uint gsTileSlot[64];
groupshared uint gsTileKey[64];
groupshared uint gsTileCount;
groupshared uint gsTileTaken;
groupshared int gsTileT;
groupshared uint gsTileRem;

bool lightCovers(float4 sc, uint tx, uint ty) {
    if (asuint(sc.w) == LIGHT_REJECTED) return false;
    float t = float(LIGHT_TILE_PX);
    int x0 = int(floor((sc.x - sc.z) / t)), x1 = int(floor((sc.x + sc.z) / t));
    int y0 = int(floor((sc.y - sc.z) / t)), y1 = int(floor((sc.y + sc.z) / t));
    return int(tx) >= x0 && int(tx) <= x1 && int(ty) >= y0 && int(ty) <= y1;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void lightBinMain(uint3 gid : SV_GroupID, uint3 gt : SV_GroupThreadID) {
    uint tilesX = (frame.dims.x + 31u) / 32u, tilesY = (frame.dims.y + 31u) / 32u;
    uint tile = gid.x;
    if (tile >= tilesX * tilesY) return;  // (uniform over the group)
    uint tx = tile % tilesX, ty = tile / tilesX, tid = gt.x;
    for (uint b = tid; b < LIGHT_BINS; b += 64u) gsTileHist[b] = 0u;
    if (tid == 0u) {
        gsTileCount = 0u;
        gsTileTaken = 0u;
    }
    GroupMemoryBarrierWithGroupSync();
    uint n = min(lightList[0], LIGHT_CAND_CAP);
    for (uint i = tid; i < n; i += 64u) {
        float4 sc = lightCand[i * 4u + 3u];
        if (lightCovers(sc, tx, ty)) InterlockedAdd(gsTileHist[asuint(sc.w) & 255u], 1u);
    }
    GroupMemoryBarrierWithGroupSync();
    if (tid == 0u) {
        uint cum = 0u;
        gsTileT = -1;
        gsTileRem = 0u;
        for (int b = int(LIGHT_BINS) - 1; b >= 0; b--) {
            uint nb = gsTileHist[uint(b)];
            if (cum + nb > LIGHTS_PER_TILE) {
                gsTileT = b;
                gsTileRem = LIGHTS_PER_TILE - cum;
                break;
            }
            cum += nb;
        }
    }
    GroupMemoryBarrierWithGroupSync();
    uint base = LL_TILES + tile * (1u + LIGHTS_PER_TILE);
    for (uint i = tid; i < n; i += 64u) {
        float4 sc = lightCand[i * 4u + 3u];
        if (!lightCovers(sc, tx, ty)) continue;
        int bin = int(asuint(sc.w) & 255u);
        if (gsTileT >= 0 && bin < gsTileT) continue;
        if (gsTileT >= 0 && bin == gsTileT) {
            uint taken;
            InterlockedAdd(gsTileTaken, 1u, taken);
            if (taken >= gsTileRem) continue;
        }
        uint k;
        InterlockedAdd(gsTileCount, 1u, k);
        if (k < LIGHTS_PER_TILE) {
            gsTileSlot[k] = i;
            gsTileKey[k] = asuint(sc.w) >> 8u;
        }
    }
    GroupMemoryBarrierWithGroupSync();
    // the list in record order (each entry's rank among the others'): the same lights sum the same way every frame
    uint m = min(gsTileCount, LIGHTS_PER_TILE);
    if (tid < m) {
        uint key = gsTileKey[tid], rank = 0u;
        for (uint j = 0u; j < m; j++) rank += gsTileKey[j] < key || (gsTileKey[j] == key && j < tid) ? 1u : 0u;
        lightList[base + 1u + rank] = gsTileSlot[tid];
    }
    if (tid == 0u) lightList[base] = m;
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
        gTr = rr;
        int hit = traceLod(l, O + d * t, d, len, uncExit);
        rr = gTr;
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
    gHead = groupData[0];
    gAblate = asuint(frame.jitter.w);
    gSecAblate = gAblate;
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
    airAtPoint(p);  // (an eclipse: the sun's share there)
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
        // (the unified field: a coarse level's hit, a smooth surface inside its level's cube: out of that solid along the
        // normal, else its rays start inside the cube and the surface is black)
        int hitLod = (frame.dims.z & FRAME_PRIMARY_NEAR_LIT) != 0u ? int(255u - ((info >> 8) & 255u)) : 0;
        if (hitLod > 0 && hitLod <= 6) {
            float hs = float(1 << hitLod);
            for (int i = 0; i < 3 && occupiedAt(hitLod, so); i++) so += N * (0.5 * hs);
        }
    }
    // The light by need (docs/ATLAS-RENDERER-PLAN.md "The light by need"): a pixel cannot show the light's detail finer
    // than its footprint there (the blocks one pixel covers: distance x the pixel's angle x lightCtl.x), so its secondary
    // rays need no finer voxels where they are that far from the surface: each starts at level 0 (the surface's own
    // nooks and steps shade it as they do up close: lifting the origin out of a coarse voxel brightened far slopes) and
    // goes a level coarser every SEC_FOOT_VOXELS voxels until its voxels are the footprint (gSecFoot), the steps
    // dithered per surface point (surfaceRand) so quality fades with distance, never steps; past that the sun's cone. 0: none.
    // (atlas's own field only, PRIMARY_NEAR: the raster near field's pixels are near; a voxel two blocks wide is
    // sub-pixel only past ~1.1 km at 1280 x 800, and a ray takes it only 4 blocks from the surface)
    gSecFoot = 0.0;
    if (frame.lightCtl.x > 0.0 && !cheapLight && (frame.dims.z & (FRAME_PRIMARY_NEAR | FRAME_PRIMARY_NEAR_LIT)) != 0u) {
        // (the one footprint rule, lodFromFootprint: its level as blocks)
        gSecFoot = exp2(lodFromFootprint(nd, frame.params.w * frame.lightCtl.x, 1.0)) * exp2(surfaceRand(p, 3u) - 0.5);
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
    // The sun ray widens as the sun's own cone (lightCtl.z voxels of a level before the next: about the inverse of the
    // sun's 0.53 degrees, so an occluder's voxel never outgrows its penumbra there; a tree's crown within ~100 blocks
    // stays at the receiver's level, the blobby crowns a 32-voxel cone drew are gone), its steps dithered per surface point
    gSecCone = frame.lightCtl.z;
    gSecConeJit = exp2(surfaceRand(p, 5u));
    if ((frame.dims.z & FRAME_SHADOWS) != 0 && (ablate & 4u) == 0u) {
        if (dot(N, L) <= 0.0) sunVis = 0.0;
        else if (untrusted) sunVis = eyeRoofed() ? 0.0 : 1.0;  // (in a cave: rock, most likely)
        else if (cheapLight) sunVis = traceShadow(p + N * 1.01 + L * 0.5, L, 0);
        else sunVis = traceSecondary(so + L * 0.05, L, 2048.0, 0);
    }
    gSecCone = 0.0;
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
        gBounceCone = frame.lightCtl.w;
        gSecConeJit = exp2(surfaceRand(p, 7u));
        ambient = skyVisible(px, so, N, 0, sun, gNearRoofed, true, skyVis) + FAR_AMBIENT_FLOOR;
        gBounceCone = 0.0;
    }
    // under water: the sky's light is what the water above lets down, red first then green (the sky rays reach 16 blocks
    // and saw the surface's light undimmed past that): its depth from the sun ray's way out of the water
    if (waterChord > 0.0) ambient *= exp(-WATER_ABSORB * (waterChord * max(L.y, 0.2)));
    MaterialGpu white;
    white.albedoRough = float4(1, 1, 1, 0.8);
    white.emisClass = float4(0, 0, 0, 0);
    float3 irr = shadeSurfaceAmb(float3(1, 1, 1), white, N, dir, sun, sunVis, 0u, ambient, float3(0, 0, 0));
    // (_LIT, atlas composites it itself) the sun through water per channel, red lost first, then green (the ray's
    // grey WATER_SIGMA a block is its mean); the host's resolve does this itself from a (non-unified)
    if (waterChord > 0.0 && (frame.dims.z & FRAME_PRIMARY_NEAR_LIT) != 0u)
        irr += keyLightRadiance(sun) * (max(dot(N, L), 0.0) * sunVis) * (exp(-(WATER_ABSORB - WATER_SIGMA) * waterChord) - 1.0);
    gSecCone = 0.0;
    if ((ablate & 8u) == 0u) irr += farLightsOn(px, p, so, N, 0, sun, false, cheapLight);
    gSecFoot = 0.0;
    // the host composites albedo x this x T + inscatter with one transmittance (its luminance): the air's colour
    // through it (the blue lost first) rides in the light
    float3 Tr = aerialT(dir, nd);
    irr *= Tr / max(dot(Tr, float3(0.2126, 0.7152, 0.0722)), 1e-4);
    return float4(irr, waterChord > 0.0 ? 1.0 + waterChord : sunVis);
}

/// ---- the near light at half resolution (FRAME_NEAR_SPLIT) -------------------------------------------------------
// The near light's rays (the sun's, the sky's, the bounce's) for every pixel of the near field cost 5 ms a frame at
// 1280x800 in a forest town (Falkreath), most of atlas's frame: they scale with the pixels traced. nearLightMain traces
// one pixel of each 2 x 2 block, a different one each frame of the TAA's cycle (so a still view gathers all four),
// nearUpsampleMain fills the other three from the traced pixels around them on the same surface (the near field's own
// depth and normal: a shadow edge on one face stays put, light does not leak across a silhouette). A traced pixel under
// cover carries a = -1 (the publish pass clamps it to 0): with the eye under cover too its air is unlit.
//
// The light by need (FRAME_PRIMARY_NEAR_LIT: atlas's own field; docs/ATLAS-RENDERER-PLAN.md "The light by need"): the
// light is diffuse, so what a surface receives does not change as the camera moves, only as the sun, the sky and the
// world do; the history (nearTemporalMain) carries it along the camera's motion, and a tile need not be traced every
// frame. nearLightMain's groups are 16 x 16 pixel tiles; each is traced on the frames its own phase accumulator crosses
// an integer at lightCtl.y a frame (1: every frame, the old split; 0.25: every fourth, staggered), and every frame while
// any of its pixels holds fewer than NEAR_SAMPLES_YOUNG samples of its own (a disocclusion, a new surface: never a
// frame of noise or a hole). Its 2 x 2 phase is the next of the four each time it is traced, so every pixel is traced in
// turn; the traced pixel flags itself (SPLIT_TRACED in its info). Where nothing was traced around a pixel this frame
// the upsample marks it SPLIT_SKIPPED: the history alone. The composite (albedo x light x T + inscatter) is
// nearTemporalMain's, after the history.
static const float SPLIT_SKIPPED = -2.0;
static const uint SPLIT_TRACED = 1u;  // (nearInfo's low byte, PRIMARY_NEAR_LIT) traced this frame
// a tile is traced every frame while a pixel of it holds fewer of its own samples than this (a fresh view, a
// disocclusion: its noise settles in a quarter of a second, not four)
static const uint NEAR_SAMPLES_YOUNG = 4u;
bool splitPrimary() { return (frame.dims.z & FRAME_PRIMARY_NEAR_LIT) != 0u; }
// the tile's traces before this frame and whether it is scheduled for this one (its phase: a hash of the tile)
uint splitTraces(uint2 tile, out bool scheduled) {
    float r = frame.lightCtl.y;  // (0: unset, every frame)
    if (!splitPrimary() || r <= 0.0 || r >= 1.0 || frame.jitter.z < 0.5) {
        scheduled = true;
        return frame.jitter.z > 0.5 ? frame.temporal.x : 0u;
    }
    float phi = float(hashU(tile.x * 73856093u ^ (tile.y * 19349663u)) & 0xFFFFu) / 65536.0;
    float k = float(frame.temporal.x & 0xFFFFu);
    float a = floor(phi + k * r), b = floor(phi + (k + 1.0) * r);
    scheduled = b > a;
    return uint(a);
}
uint2 splitOffsetOf(uint traces) {
    uint k = traces & 3u;
    // (0,0) (1,1) (1,0) (0,1): diagonal pairs first, so two frames already cover both rows and columns
    return uint2((k == 1u || k == 2u) ? 1u : 0u, (k == 1u || k == 3u) ? 1u : 0u);
}
uint2 nearSplitOffset() {
    uint k = frame.jitter.z > 0.5 ? (frame.temporal.x & 3u) : 0u;
    return splitOffsetOf(k);
}

float3 pixelRay(uint2 px, uint w, uint h) {
    float2 ndc = float2((float(px.x) + 0.5) / float(w) * 2.0 - 1.0, 1.0 - (float(px.y) + 0.5) / float(h) * 2.0)
               + frame.jitter.xy;
    return normalize(unproject(frame, ndc));
}

// The previous frame's near light at this pixel's surface (reprojected through its camera and view-projection, where
// the previous frame's surface lay at the same distance); the weight found (0: none, the point was hidden or off
// screen then), and how many of the pixel's own samples it holds (prevNearAge, as weighted as the light)
// (bit 8 of an age: the pixel lay under solid cover, nearLightAt's roof test; its air is unlit when the eye is too)
static const uint NEAR_AGE_ROOFED = 256u;
float nearHistoryAt(uint2 px, float nd, out float4 hist, out uint samples, out bool roofed) {
    uint w = frame.dims.x, h = frame.dims.y;
    hist = float4(0, 0, 0, 0);
    samples = 0u;
    roofed = false;
    float hw = 0.0;
    float ages = 0.0, roofs = 0.0;
    uint count, stride;
    prevNearLight.GetDimensions(count, stride);
    if (count < w * h) return 0.0;
    float3 dir = pixelRay(px, w, h);
    float R = frame.camFrac.w;
    float k = R > 0 ? dot(dir.xz, dir.xz) / (2.0 * R) : 0.0;
    float3 X = worldAt(frame.camFrac.xyz, dir, k, nd) - frame.prevCam.xyz;
    float4 clip = frame.prevViewProj[0] * X.x + frame.prevViewProj[1] * X.y + frame.prevViewProj[2] * X.z + frame.prevViewProj[3];
    if (clip.w <= 1e-4) return 0.0;
    // (the last frame's pixel whose jittered ray went there: its jitter taken off, or a still view's history would be
    // resampled half a pixel askew every frame of the TAA's cycle and blur)
    float2 pn = clip.xy / clip.w - frame.farLight.zw;
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
            uint age = prevNearAge[qp];
            ages += float(age & 255u) * bw;
            roofs += (age & NEAR_AGE_ROOFED) != 0u ? bw : 0.0;
        }
    }
    samples = hw > 1e-4 ? uint(ages / hw + 0.5) : 0u;
    roofed = roofs > 0.5 * hw && hw > 1e-4;
    return hw;
}

groupshared uint gsSplitFresh;
[shader("compute")]
[numthreads(8, 8, 1)]
void nearLightMain(uint3 id : SV_DispatchThreadID, uint3 gid : SV_GroupID, uint3 gt : SV_GroupThreadID) {
    airBegin();
    gEyeRoofed = -1;
    uint w = frame.dims.x, h = frame.dims.y;
    bool primary = splitPrimary();
    bool scheduled = true;
    uint traces = primary ? splitTraces(gid.xy, scheduled) : (frame.jitter.z > 0.5 ? frame.temporal.x : 0u);
    // (a tile traced off its schedule, for its young history: the frame's phase, so the four pixels take turns)
    uint2 q = id.xy * 2u + splitOffsetOf(primary && !scheduled ? frame.temporal.x : traces);
    bool inside = q.x < w && q.y < h;
    uint pix = inside ? q.y * w + q.x : 0u;
    float nd = inside ? nearDepth[pix] : 1e30;
    if (primary && !scheduled) {
        // not this tile's frame: traced all the same while a pixel of it has a young history (NEAR_SAMPLES_YOUNG)
        if (gt.x == 0u && gt.y == 0u) gsSplitFresh = 0u;
        GroupMemoryBarrierWithGroupSync();
        bool fresh = false;
        for (uint k = 0u; k < 4u && !fresh; k++) {
            uint2 c = id.xy * 2u + uint2(k & 1u, k >> 1u);
            if (c.x >= w || c.y >= h) continue;
            float cd = nearDepth[c.y * w + c.x];
            if (!(cd < 1e29)) continue;
            // (the history at the same pixel, not reprojected: exact for a still view, and a moving one's changed
            // depth counts as fresh; a quarter of the cost of the history's own lookup)
            uint cp = c.y * w + c.x;
            uint count, stride;
            prevNearAge.GetDimensions(count, stride);
            fresh = count < w * h || (prevNearAge[cp] & 255u) < NEAR_SAMPLES_YOUNG ||
                    abs(prevSurface[cp] - cd) > 0.03 * cd + 0.1;
        }
        if (fresh) InterlockedOr(gsSplitFresh, 1u);
        GroupMemoryBarrierWithGroupSync();
        if (gsSplitFresh == 0u) return;  // (nothing traced: the upsample marks its pixels SPLIT_SKIPPED)
    }
    if (!inside || !(nd < 1e29)) return;  // not the near field's (farMain wrote its zero)
    gAblate = asuint(frame.jitter.w);
    gSecAblate = gAblate;
    gHead = groupData[0];  // (the material shapes: secondary rays, the grid's level 0)
    // (PRIMARY_NEAR_LIT) a pixel is traced at most once every four frames: its sky strata and dithers step once a
    // trace. The traced pixel says so in its info's low byte (atlas's own hits leave it 0), for the upsample and history
    gSampleIndex = primary ? int(frame.temporal.x >> 2u) : -1;
    uint info = nearInfo[pix];
    float4 nl = nearLightAt(q, frame.camFrac.xyz, pixelRay(q, w, h), nd, info, frame.sunDir.xyz);
    if (gNearRoofed) nl.a = -1.0;
    outNearLight[pix] = nl;
    if (primary) nearInfo[pix] = info | SPLIT_TRACED;
    if (primary && (gAblate & 536870912u) != 0u) counters[pix] = 1u;
}

// water caustics (atlas_water.glsl atlasCaustic, after Dave Hoskins' "Tileable Water Caustic"): bright lines where the
// waves focus the sun, 0..~1.5, at world xz (blocks), time t (seconds)
float waterCaustic(float2 xz, float t) {
    const float TAU = 6.28318530718;
    float2 q = xz / 7.0 * TAU;
    float2 p = q - TAU * floor(q / TAU) - 250.0;
    float2 i = p;
    float c = 1.0;
    const float inten = 0.005;
    for (int n = 0; n < 4; n++) {
        float tt = t * 0.45 * (1.0 - (3.5 / float(n + 1)));
        i = p + float2(cos(tt - i.x) + sin(tt + i.y), sin(tt - i.y) + cos(tt + i.x));
        c += 1.0 / length(float2(p.x / (sin(i.x + tt) / inten), p.y / (cos(i.y + tt) / inten)));
    }
    c /= 4.0;
    c = 1.17 - pow(c, 1.4);
    return clamp(pow(abs(c), 8.0), 0.0, 1.5);
}
// (_LIT) what the waves over a point under chord blocks of water make of the sun reaching it, added to its irradiance:
// focused lines near the surface, evening out deeper (mean about none). nd: the point's view distance, N its normal
float3 causticSun(float3 dir, float nd, float3 N, float chord) {
    float R = frame.camFrac.w;
    float k = R > 0 ? dot(dir.xz, dir.xz) / (2.0 * R) : 0.0;
    float3 p = worldAt(frame.camFrac.xyz, dir, k, nd);
    airAtPoint(p);
    float3 sun = frame.sunDir.xyz, L = keyLightDir(sun);
    float3 sunPart = keyLightRadiance(sun) * max(dot(N, L), 0.0) * exp(-WATER_ABSORB * chord);
    // (world xz modulo the pattern's 7-block period, from the integer camera block: exact far from the origin)
    float2 base = float2(frame.camInt.x % 7, frame.camInt.z % 7);
    float c = waterCaustic(base + (p + L * chord).xz, frame.sunDir.w);
    return sunPart * (lerp(1.0, 0.25 + 4.0 * c, exp(-chord / 16.0)) - 1.0);
}

float3 nearInfoNormal(uint info) {
    return octDecode(float2(float((info >> 16) & 255u), float(info >> 24)) / 255.0);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void nearUpsampleMain(uint3 id : SV_DispatchThreadID) {
    airBegin();
    gEyeRoofed = -1;
    // (eyeRoofed's ray: the material shapes and the measurements' switches, as every other entry's; without them
    // plants, fences and panes overhead counted as solid cover)
    gHead = groupData[0];
    gAblate = asuint(frame.jitter.w);
    gSecAblate = gAblate;
    uint w = frame.dims.x, h = frame.dims.y;
    if (id.x >= w || id.y >= h) return;
    uint pix = id.y * w + id.x;
    float nd = nearDepth[pix];
    if (!(nd < 1e29)) return;
    bool primary = splitPrimary();
    uint2 off = nearSplitOffset();
    bool traced = primary ? (nearInfo[pix] & SPLIT_TRACED) != 0u : (id.x & 1u) == off.x && (id.y & 1u) == off.y;
    float roofed;
    if (traced) {
        float a0 = outNearLight[pix].a;
        roofed = a0 < 0.0 && a0 > -1.5 ? 1.0 : 0.0;
    } else {
        // the traced pixels of the 3 x 3 blocks around: weighted by distance on screen, depth and normal alike
        float3 n = nearInfoNormal(nearInfo[pix]);
        int2 b = int2(id.xy >> 1);
        float4 sum = float4(0, 0, 0, 0);
        float wsum = 0.0, rsum = 0.0, best = 1e30;
        float4 nearest = float4(0, 0, 0, SPLIT_SKIPPED);
        float nearestRoof = 0.0;
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                int2 bb = b + int2(i, j);
                if (bb.x < 0 || bb.y < 0) continue;
                int2 r = bb * 2 + int2(off);
                if (primary) {
                    // (the block's traced pixel, if any: its tile's phase this frame)
                    r = int2(-1, -1);
                    for (uint k = 0u; k < 4u; k++) {
                        int2 c = bb * 2 + int2(int(k & 1u), int(k >> 1u));
                        // (only a pixel of the field this frame: a mark left on one that is sky now ghosted a silhouette)
                        if (c.x < int(w) && c.y < int(h) && (nearInfo[uint(c.y) * w + uint(c.x)] & SPLIT_TRACED) != 0u &&
                            nearDepth[uint(c.y) * w + uint(c.x)] < 1e29)
                            r = c;
                    }
                    if (r.x < 0) continue;
                }
                if (r.x >= int(w) || r.y >= int(h)) continue;
                uint rp = uint(r.y) * w + uint(r.x);
                float rd = nearDepth[rp];
                if (!(rd < 1e29)) continue;
                float4 v = outNearLight[rp];
                if (v.a < -1.5) continue;  // (its tile was not traced this frame)
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
        // nothing on the same surface around (a lone pixel of a thin thing): the nearest in depth (none traced near
        // it this frame: SPLIT_SKIPPED, the history alone)
        float4 v = wsum > 1e-4 ? sum / wsum : nearest;
        roofed = wsum > 1e-4 ? rsum / wsum : nearestRoof;
        // (PRIMARY_NEAR_LIT: the composite comes after the history; the mark goes with it)
        if (primary && roofed > 0.5 && v.a >= 0.0) v.a = -1.0;
        outNearLight[pix] = v;
    }
    if (!primary && roofed > 0.5 && eyeRoofed()) outColor[pix].rgb = float3(0, 0, 0);  // under cover at both ends: unlit air
}

// The near light's own history (FRAME_NEAR_SPLIT, after nearUpsampleMain): each pixel's light is one ray of each kind,
// traced at a quarter of the pixels, and the host's TAA cannot average that noise (its neighbourhood clamp throws it
// out): sky-lit walls under eaves and shaded ground shimmered frame to frame (6 % of the pixels of a still view moved
// by more than 8 levels). Here the surface point is reprojected into the previous frame (its camera and
// view-projection, as the clouds' are) and blended with the light found there, where the previous frame's surface lay
// at the same distance (else the point was hidden or off screen then: this frame's light alone). An exponential
// average of about NEAR_HISTORY frames: still views converge, a moving camera keeps its history on the surfaces.
// (PRIMARY_NEAR_LIT) then the composite: albedo (farMain left it in the colour) x light x T + inscatter, as farMain's
// full-rate path writes it, and the key light's visibility into the normal's alpha.
static const float NEAR_HISTORY = 8.0;
static const uint NEAR_SAMPLES = 8u;  // (PRIMARY_NEAR_LIT) a pixel's own samples averaged, at most

[shader("compute")]
[numthreads(8, 8, 1)]
void nearTemporalMain(uint3 id : SV_DispatchThreadID) {
    airBegin();
    gEyeRoofed = -1;
    gHead = groupData[0];  // (eyeRoofed's and the fallback light's rays, as nearLightMain's)
    gAblate = asuint(frame.jitter.w);
    gSecAblate = gAblate;
    uint w = frame.dims.x, h = frame.dims.y;
    if (id.x >= w || id.y >= h) return;
    uint pix = id.y * w + id.x;
    float nd = nearDepth[pix];
    if (!(nd < 1e29)) return;
    bool primary = splitPrimary();
    float4 cur = outNearLight[pix];
    bool skipped = cur.a < -1.5;
    bool roofed = cur.a < 0.0 && !skipped;
    cur.a = max(cur.a, 0.0);  // (the under-cover mark has done its work in the upsample)
    float4 hist;
    uint samples;
    bool histRoofed;
    float hw = nearHistoryAt(id.xy, nd, hist, samples, histRoofed);
    if (skipped) roofed = histRoofed;  // (no roof test this frame: the history's)
    uint2 off = nearSplitOffset();
    bool traced = !skipped && (primary ? (nearInfo[pix] & SPLIT_TRACED) != 0u : (id.x & 1u) == off.x && (id.y & 1u) == off.y);
    if (primary) {
        // (PRIMARY_NEAR_LIT) the history holds the pixel's own traced samples, averaged as they come up to
        // NEAR_SAMPLES (then a running average of that many): a still view converges to its own light, exact at
        // shadow edges. Its neighbours' light (the upsample) fills a pixel with none of its own yet (a disocclusion, a
        // new surface) and counts as half a sample after, only where it agrees about the sun; where nothing was traced
        // this frame the history stands.
        float4 hv = hw > 1e-4 ? hist / hw : float4(0, 0, 0, 0);
        uint n = hw > 0.25 ? samples : 0u;
        if (skipped && hw <= 0.25) {
            // a tile off its schedule whose pixel has no history (a disocclusion its freshness test did not see: the
            // test reads the same pixel, not the reprojected point): traced here, rather than black for frames
            gSampleIndex = int(frame.temporal.x >> 2u);
            float4 nl = nearLightAt(id.xy, frame.camFrac.xyz, pixelRay(id.xy, w, h), nd, nearInfo[pix], frame.sunDir.xyz);
            roofed = gNearRoofed;
            cur = nl;
            n = 1u;
        } else if (skipped) {
            cur = hv;
        } else if (traced) {
            // (a history of the neighbours' light alone counts as one sample)
            float prior = float(n) + (n == 0u && hw > 0.25 ? 1.0 : 0.0);
            n = min(n + 1u, NEAR_SAMPLES);
            // the key light's visibility is one deterministic ray, not noise: where the history disagrees with it (an
            // edge the history's resampling blurred as the camera moved, a shadow the sun or an edit moved) the
            // sample counts as much as that says, and the count restarts with it
            float stale = saturate(abs(min(cur.a, 1.0) - min(hv.a, 1.0)) * 2.0 - 0.1);
            float a = max(1.0 / min(prior + 1.0, float(NEAR_SAMPLES)), stale);
            cur = lerp(hv, cur, a);
            if (stale > 0.5) n = 1u;
        } else if (n == 0u && hw > 0.25) {
            cur = lerp(hv, cur, 0.5);
        } else if (n == 1u) {
            // one sample of its own: the neighbours' light as a third of one where it agrees about the key light (the
            // same side of a shadow edge), the first noise down sooner after a disocclusion. Past that none: their light
            // is another surface's (a far slope's pixels are its steps' tops and sides: blended in, the shaded sides
            // came out 4 % brighter than they are)
            float agree = saturate(1.0 - abs(cur.a - hv.a) * 4.0);
            cur = lerp(hv, cur, agree * 0.25);
        } else if (n > 0u) {
            cur = hv;
        }
        outNearAge[pix] = n | (roofed ? NEAR_AGE_ROOFED : 0u);
    } else {
        // a traced pixel (one in four each frame) counts for more than the upsampled ones in between; where nothing was
        // traced this frame the history stands
        float a = traced ? 2.0 / NEAR_HISTORY : 1.0 / NEAR_HISTORY;
        if (skipped) cur = hw > 1e-4 ? hist / hw : float4(0, 0, 0, 0);
        else if (hw > 0.25) cur = lerp(hist / hw, cur, a);
    }
    outNearLight[pix] = cur;
    if (primary) {
        float3 dir = pixelRay(id.xy, w, h);
        // the water over the surface (farMain: waterAdd, its albedo already through it): its own light, and the air
        // from where the ray left it (the eye under water: none in front of the bed)
        float4 wa = waterAdd[pix];
        AirPath air = aerialPathTo(dir, wa.a < 0.0 ? 0.0 : nd);  // (a < 0: the eye under water, its air in the colour)
        float T = dot(air.transmittance, float3(0.2126, 0.7152, 0.0722));
        float3 ins = roofed && eyeRoofed() ? float3(0, 0, 0) : air.inscatter;  // under cover at both ends: unlit air
        float3 albedo = outColor[pix].rgb;
        float3 light = cur.rgb;
        // the sun through water shaped by the waves over the point (caustics, at full rate: they move)
        if (cur.a > 1.0) light = max(light + causticSun(dir, nd, nearInfoNormal(nearInfo[pix]), cur.a - 1.0), float3(0, 0, 0));
        float3 c = albedo * light * T + ins + wa.rgb;
        // the far lights' glow and the cloud deck in front of the surface, as the far field's pixels have them
        float surface = nd;
        c += farLights(id.xy, frame.camFrac.xyz, dir, surface, frame.sunDir.xyz);
        if ((frame.dims.z & FRAME_CLOUDS) != 0) {
            float4 cl;
            float clDepth;
            sampleClouds(id.xy, cl, clDepth);
            if (clDepth < surface && wa.a >= 0.0) c = c * cl.a + cl.rgb;
        }
        outColor[pix] = float4(c, 1.0);
        outNormal[pix] = (outNormal[pix] & 0x00FFFFFFu) | (uint(saturate(cur.a) * 255.0 + 0.5) << 24);
    }
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

// A ray (not a secondary one) that passes water: a trace with water skipped (gSkipWater: the bed under the surface, the
// eye under water), and (ATLAS_FRAME_PRIMARY_NEAR, the G-buffer: the host rasters the water surface itself) a view ray
// in the near field. Its distance is the view's: the chord's start, the sub-chord's and t along it (t alone, a chord's
// own parameter, made far water vanish in bands where a chord began past nearFieldEnd). Under _LIT (the unified field)
// atlas draws every water surface itself (farMain), near and far.
bool primaryPassesWater(uint cls, float t) {
    if (cls != 2u || cSecondary) return false;
    if (cSkipWater) return true;
    return cPrimary && cChordT + cSecT + t < frame.params.x &&
           (frame.dims.z & (FRAME_PRIMARY_NEAR | FRAME_PRIMARY_NEAR_LIT)) == FRAME_PRIMARY_NEAR;
}

// ---- water in the unified field (ATLAS_FRAME_PRIMARY_NEAR_LIT): atlas draws every water surface, near and far ----------
// One model at every distance (no near / far line): the surface's waves (farWaveNormal, the pack's), Schlick Fresnel
// against what it mirrors (waterReflection: a traced ray, the sky and its cloud deck), the key light's glint, and the
// medium: what lies behind the surface (the bed, a shore, land past a pool) traced on along the view ray with water
// skipped and lit by the one light like any land hit (the split's light passes: the sun through the water, the sky down
// to it), absorbed over its path through the water (red first, then green), the water's own scattering filling what
// is lost. Past WATER_SEE blocks of water nothing behind shows (clear water's green is down to 1 % by then). The eye
// under water sees the same medium from inside: the bed through it, the surface from below (Snell's window: the world
// above through it, outside it total internal reflection, the water's own light).
// The result is affine in what lies behind: seen = behind x A + B (A, B per channel, physical; B before the air in front).
static const float WATER_SEE = 96.0;

// the water's colour (the biome's, on its material; Minecraft's grey texture near: clear lake water then)
float3 waterTintOf(uint mat) {
    float3 tint = palette[mat].albedoRough.rgb;
    float mx = max(tint.r, max(tint.g, tint.b)), mn = min(tint.r, min(tint.g, tint.b));
    if (mx < 1e-3 || (mx - mn) / mx < 0.2) tint = float3(0.05, 0.19, 0.78);
    return tint;
}
// extinction a block (absorption, up to five times clear water's in murky water, and scattering)
float3 waterExtinction(float mk) { return WATER_ABSORB * (1.0 + 4.0 * mk) + 0.04 * (1.0 + 6.0 * mk); }
// The water's own light, as an infinitely deep body of it sends back: the sky over it and the key light through it
// (sunVis: whether it reaches the surface there), scattered in the water's colour, dimmed with depth under the surface
// (the light let down that far). The share it scatters back is deep water's irradiance reflectance: about 3 % in blue
// for clear water, 1 % green, almost no red; murky water greener (remote-sensing reflectance x pi: clear 0.002 /
// 0.012 / 0.035, a lake's sediment and algae 0.01 / 0.02 / 0.012). (Bug 7: three to four times that before, a bright
// teal lake bed under a night's adaptation.)
float3 waterInLight(float3 sun, float sunVis, float mk, float3 tint, float depth) {
    float3 L = keyLightDir(sun);
    float3 up = float3(0, 1, 0);
    float3 refl = lerp(float3(0.002, 0.012, 0.035), float3(0.01, 0.02, 0.012), mk) * lerp(float3(1, 1, 1), saturate(tint * 2.0), 0.5);
    float3 lit = skyAmbient(up, sun) * LOOK_AMBIENT + keyLightRadiance(sun) * (max(L.y, 0.0) * sunVis);
    return lit * refl * exp(-WATER_ABSORB * (1.0 + 4.0 * mk) * max(depth, 0.0) * 0.6);
}
// the surface's normal at camera-relative p (dist from the eye: the waves too fine to draw fade): rivers run their
// waves downstream and break into white water (foam) on fast runs and rapids; the wind's elsewhere
float3 waterNormalAt(float3 p, uint mat, float dist, out float foam, out float2 wind) {
    MaterialGpu m = palette[mat];
    float2 wxz = p.xz + float2(frame.camInt.xz);
    wind = farWaterWind(wxz, length(p.xz));
    foam = 0.0;
    float flow = m.emisClass.w;  // 0 still (sea, lakes), 1 calm, 2 fast, 3 rapids (the dressing's river flow)
    if (flow > 0.5) {
        float2 fd = float2(cos(m.emisClass.z), sin(m.emisClass.z));
        float speed = 1.5 * flow;  // blocks per second
        wind = float2(m.emisClass.z, saturate(0.25 + 0.22 * flow));
        float u = dot(wxz, fd), v = dot(wxz, float2(-fd.y, fd.x));
        float streak = valueNoise(float2(u / 7.0 - frame.sunDir.w * speed / 7.0, v / 1.6)) * 0.7
                     + valueNoise(float2(u / 2.5 - frame.sunDir.w * speed / 2.5, v / 0.8)) * 0.3;
        foam = flow > 2.5 ? smoothstep(0.25, 0.6, streak) * 0.8 : flow > 1.5 ? smoothstep(0.55, 0.85, streak) * 0.35 : 0.0;
        return farWaveNormal(wxz - fd * (frame.sunDir.w * speed), frame.sunDir.w, wind, dist);
    }
    return farWaveNormal(wxz, frame.sunDir.w, wind, dist);
}

// The surface seen from above at view distance tW (its hit: the material, level), what lies behind it pathLen blocks of
// water further on (WATER_SEE or more: nothing). A, B as above; sunVis the key light's at the surface (out).
void waterFromAbove(uint2 px, float3 o, float3 dir, float tW, uint mat, int lod, float pathLen, float3 sun, out float3 A,
                    out float3 B, out float sunVis, out float3 wn) {
    float R = frame.camFrac.w;
    float k = R > 0 ? dot(dir.xz, dir.xz) / (2.0 * R) : 0.0;
    float3 p = worldAt(o, dir, k, tW);
    airAtPoint(p);
    float3 slope = normalize(dir + float3(0, 2.0 * k * tW, 0));
    float foam;
    float2 wind;
    wn = waterNormalAt(p, mat, tW, foam, wind);
    float3 L = keyLightDir(sun);
    float s = float(1 << lod);
    // the key light at the surface: shadowed by what stands over the water (a shore's cliff, a bank's trees), the clouds
    sunVis = 1.0;
    if ((frame.dims.z & FRAME_SHADOWS) != 0u && (gAblate & 4u) == 0u)
        sunVis = L.y > 0.0 ? traceSecondary(p + float3(0, 0.05 * s, 0) + L * (0.05 * s), L, 2048.0, lod) : 0.0;
    if ((frame.dims.z & FRAME_CLOUDS) != 0u && sunVis > 0.0) sunVis *= cloudShadow(p, L);
    float cosv = saturate(dot(-slope, wn));
    float F = 0.02 + 0.98 * pow(1.0 - cosv, 5.0);
    float3 refl = (gAblate & 16u) != 0u ? float3(0, 0, 0) : waterReflection(p, dir, k, tW, lod, sun, wn);
    // the glint: the key light's lobe at the alpha of the waves too fine to draw here (shadeSurfaceAmb's)
    float alpha = clamp(farWaterAlpha(wind.y, tW) + foam * 0.2, 0.005, 0.5);
    float e = 2.0 / (alpha * alpha) - 2.0;
    float spec = pow(saturate(dot(reflect(slope, wn), L)), e) * (e + 8.0) / 25.1327 * 0.37 * sunVis;
    float3 tint = waterTintOf(mat);
    float mk = waterMurk(tint);
    float3 Tw = pathLen >= WATER_SEE ? float3(0, 0, 0) : exp(-waterExtinction(mk) * pathLen);
    float3 own = waterInLight(sun, sunVis, mk, tint, 0.0);
    A = (1.0 - F) * Tw;
    B = (1.0 - F) * own * (1.0 - Tw) + F * refl + keyLightRadiance(sun) * spec;
    if (foam > 0.0) {
        // white water: a bright rough surface over the river
        float3 up = float3(0, 1, 0);
        float3 white = float3(0.85, 0.88, 0.9) * (keyLightRadiance(sun) * saturate(dot(up, L)) * sunVis + skyAmbient(up, sun));
        A *= 1.0 - foam;
        B = lerp(B, white, foam);
    }
}

// The eye under water, eyeDepth blocks under the surface: what the ray sees through the water. tHit: what it met (-1:
// nothing). A ray going up leaves the water at the surface's plane over the eye (eyeDepth / dir.y: a lake's surface is
// level); one going down or along stays in it to what it meets. A, B as above; airFrom: the view distance where the
// air begins (past tHit: none).
void waterFromBelow(float3 o, float3 dir, float tHit, uint mat, float eyeDepth, float3 sun, out float3 A, out float3 B,
                    out float airFrom) {
    float3 tint = waterTintOf(mat);
    float mk = waterMurk(tint);
    float3 ext = waterExtinction(mk);
    // the water's light along the ray is its light about one scattering length out that way (atlas_water.glsl
    // atlasUnderwater's rule: things fade into exactly the open water's colour)
    float reach = 1.0 / max(dot(ext, float3(0.2126, 0.7152, 0.0722)), 1e-3);
    float3 own = waterInLight(sun, 1.0, mk, tint, eyeDepth - dir.y * min(reach, 24.0));
    float wOut = dir.y > 1e-3 ? eyeDepth / dir.y : 1e30;
    if (tHit >= 0.0 && tHit <= wOut + 0.05) {
        // the bed (or a wall) under water: all the way through it
        float3 Tw = exp(-ext * tHit);
        A = Tw;
        B = own * (1.0 - Tw);
        airFrom = tHit;
        return;
    }
    if (wOut >= 1e29) {
        // down or along, nothing met: the open water as far as it goes
        A = float3(0, 0, 0);
        B = own;
        airFrom = 1e30;
        return;
    }
    float3 Tw = exp(-ext * wOut);
    airFrom = wOut;
    {
        // out through the surface from below: Snell's window (refracted, n 1.333 -> 1: past 48.6 degrees off the
        // vertical total internal reflection, the water's own light), Fresnel at the transmitted angle
        float R0 = frame.camFrac.w;
        float k = R0 > 0 ? dot(dir.xz, dir.xz) / (2.0 * R0) : 0.0;
        float foam;
        float2 wind;
        float3 wb = -waterNormalAt(worldAt(o, dir, k, wOut), mat, wOut, foam, wind);
        float3 t = refract(dir, wb, 1.333);
        float R = 1.0;
        if (dot(t, t) > 1e-6) R = 0.02 + 0.98 * pow(1.0 - saturate(dot(normalize(t), -wb)), 5.0);
        float3 deep = waterInLight(sun, 1.0, mk, tint, eyeDepth + 4.0);
        A = Tw * (1.0 - R);
        B = own * (1.0 - Tw) + Tw * R * deep;
    }
}

// The eye's depth under the water's surface (blocks; the column of water over it), or -1: the eye is not in water
float eyeWaterDepth(out uint mat) {
    float3 o = frame.camFrac.xyz;
    mat = materialAt(0, o);
    if (mat == 0xFFFFFFFFu || uint(palette[mat].emisClass.y) != 2u) return -1.0;
    float y0 = floor(o.y);
    for (int k = 1; k <= 48; k++) {
        uint m = materialAt(0, float3(o.x, y0 + float(k) + 0.5, o.z));
        if (m == 0xFFFFFFFFu || uint(palette[m].emisClass.y) != 2u) return y0 + float(k) - o.y;
    }
    return 48.0;
}

// ---- the far range's primary rays across frames (FRAME_FAR_AMORT) ---------------------------------------------------
// A primary ray's hit where a block covers no more than a pixel (the footprint: distance x pixel angle >= 1 block, or the
// sky) is what the last frame's ray there met, reprojected (the camera moves a little against such distances): the pass
// keeps each pixel's hit (farHitStore: farHit, this frame's; prevFarHit, the last frame's) and a pixel takes the last
// frame's at its reprojected point while that point agrees about the surface (the same distance from the last camera)
// and the hit is younger than FAR_AMORT_N frames. Every 8 x 8 tile is traced whole one frame in FAR_AMORT_N, staggered
// by a hash of the tile (a warp's rays are traced together or not at all: one traced lane in a warp costs the warp's
// whole walk). Pixels drive it, never a distance: the footprint and the reprojection decide. What moves or shines is
// not in the hit: the movers (traceActors) and the light (nearLightMain, its lights) are this frame's, every frame.
static const uint FAR_AMORT_N = 4u;
// (the store: tHit, tW, the hit's material and albedo flags; its axis, level and the water's level, the water's
// material, the albedo as halves, the frame it was traced: frame.temporal.x's low 16 bits)
// (inlined: as a [noinline] call its two TraceResults went through memory, and the split kernel's shared memory grew
// from 7936 to 16640 bytes a group, the L1 the walk lives on with it: its far pass 40 % slower with this off)
void farHitStore(uint pix, float tHit, float tW, TraceResult res, TraceResult wRes, uint stamp) {
    uint i = pix * 2u;
    farHit[i] = uint4(asuint(tHit), asuint(tW), res.mat, res.hasAlbedo);
    uint packed = uint(res.axis + 1) | (uint(clamp(res.lod, 0, 15)) << 2) | (uint(clamp(wRes.lod, 0, 15)) << 6);
    farHit[i + 1u] = uint4(packed, wRes.mat, f32tof16(res.albedo.x) | (f32tof16(res.albedo.y) << 16),
                           f32tof16(res.albedo.z) | ((stamp & 0xFFFFu) << 16));
}
// the last frame's hit pixel px (its ray dir) takes, at its reprojected point: its index in prevFarHit (0xFFFFFFFF: none,
// the pixel traces its own) and d, this ray's distance to that hit's first surface (the water's where it met water)
// (scalars only across the call: a [noinline] call passing TraceResults went through memory, the kernel's shared memory
// grew from 7936 to 16640 bytes a group and the far pass was 40 % slower)
[noinline]  // (out of farMain's inlined body: it reads no thread-private static, its inputs are arguments)
uint farHitSource(uint2 px, float3 dir, out float d) {
    uint w = frame.dims.x, h = frame.dims.y;
    d = -1.0;
    // (the tile's own frame: traced whole)
    uint2 tile = px / 8u;
    if (((hashU(tile.x * 73856093u ^ (tile.y * 19349663u)) + frame.temporal.x) % FAR_AMORT_N) == 0u) return 0xFFFFFFFFu;
    uint n = w * h, count, stride;
    prevFarHit.GetDimensions(count, stride);
    if (count < 2u * n) return 0xFFFFFFFFu;  // (no last frame of this size)
    prevSurface.GetDimensions(count, stride);
    if (count < n) return 0xFFFFFFFFu;
    float pa = frame.params.w * frame.params.z;
    // the point the last frame's surface along this ray stood at (its distance here at the same pixel: the camera
    // moves little against a far one), reprojected into the last frame
    float ds = prevSurface[px.y * w + px.x];
    bool sky = !(ds < 1e29);
    if (!sky && ds * pa < 1.0) return 0xFFFFFFFFu;  // (near: its own ray)
    float R = frame.camFrac.w;
    float k = R > 0 && !sky ? dot(dir.xz, dir.xz) / (2.0 * R) : 0.0;  // (the sky: a direction)
    float3 X = worldAt(frame.camFrac.xyz, dir, k, sky ? 1e7 : ds) - frame.prevCam.xyz;
    float4 clip = frame.prevViewProj[0] * X.x + frame.prevViewProj[1] * X.y + frame.prevViewProj[2] * X.z + frame.prevViewProj[3];
    if (clip.w <= 1e-4) return 0xFFFFFFFFu;
    float2 pn = clip.xy / clip.w - frame.farLight.zw;  // (the last frame's jitter taken off, as nearHistoryAt)
    int2 q = int2(floor(float2((pn.x * 0.5 + 0.5) * float(w), (0.5 - pn.y * 0.5) * float(h))));
    if (any(q < int2(0, 0)) || q.x >= int(w) || q.y >= int(h)) return 0xFFFFFFFFu;
    uint i = (uint(q.y) * w + uint(q.x)) * 2u;
    uint4 a = prevFarHit[i], b = prevFarHit[i + 1u];
    if (((frame.temporal.x - (b.w >> 16)) & 0xFFFFu) >= FAR_AMORT_N) return 0xFFFFFFFFu;  // (traced FAR_AMORT_N frames ago or more)
    float tq = asfloat(a.x), wq = asfloat(a.y);
    float tf = tq >= 0.0 ? tq : wq;  // (what prevSurface holds: the bed under water, the water's surface over deep water)
    if (sky) {
        if (tf >= 0.0) return 0xFFFFFFFFu;  // (the sky here, a surface there: a silhouette moved)
        return i;
    }
    // the same surface: the last camera's distance to the point agrees with what its ray there met
    if (!(tf >= 0.0) || tf * pa < 1.0) return 0xFFFFFFFFu;
    float lx = length(X);
    if (abs(lx - tf) > 0.02 * tf + 1.0) return 0xFFFFFFFFu;
    d = ds + (tf - lx);  // (first order: the point the hit was, along this ray)
    return i;
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
    // the threshold of the continuous level (lodPick): even over small neighbourhoods, and under the host's TAA rotated
    // by the golden ratio each frame, so its resolve averages the two levels' share into a gradient
    {
        // (ATLAS_LOD's first knob, measurements: 1 a constant, 2 per 2 x 2 pixels, 3 per 8 x 4: one warp's)
        int mode = int(frame.lodParams.x);
        uint2 cell = mode == 2 ? id.xy / 2u : (mode == 3 ? uint2(id.x / 8u, id.y / 4u) : id.xy);
        gLodXi = mode == 1 ? 0.5
                           : frac(pixelDither(cell, 11u) + (frame.jitter.z > 0.5 ? 0.618034 * float(frame.temporal.x & 255u) : 0.0));
        gAlphaXi = frac(pixelDither(id.xy, 13u) + (frame.jitter.z > 0.5 ? 0.618034 * float(frame.temporal.x & 255u) : 0.0));
    }
    float3 sun = frame.sunDir.xyz;

    // the rasterised near field already covers this pixel: only the air in front of it (cloud, if the camera is up
    // in the deck) is ours. In tunnels and towns that skips most of the screen.
    // (ATLAS_FRAME_PRIMARY_NEAR / _LIT) atlas's primary rays are the near field: every pixel traced from the camera
    bool primaryNear = (frame.dims.z & (FRAME_PRIMARY_NEAR | FRAME_PRIMARY_NEAR_LIT)) != 0u;
    if (!FAR_SPLIT && (frame.dims.z & FRAME_NEAR_DEPTH) != 0 && !primaryNear) {
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
    // (_LIT, the unified field) atlas draws water itself, near and far: the eye under water traces with water skipped
    // from the start (the medium is applied below)
    bool unified = (frame.dims.z & FRAME_PRIMARY_NEAR_LIT) != 0u;
    uint eyeMat = 0u;
    float eyeDepth = unified ? eyeWaterDepth(eyeMat) : -1.0;
    bool eyeWater = eyeDepth >= 0.0;
    float tHit = -1.0;
    float tW = -1.0;
    TraceResult wRes = res;
    // (FRAME_FAR_AMORT) the last frame's hit where this pixel may take it
    bool amort = (frame.dims.z & FRAME_FAR_AMORT) != 0u && !eyeWater;
    uint hitStamp = frame.temporal.x;
    float hitD = -1.0;
    uint hitSrc = amort ? farHitSource(id.xy, dir, hitD) : 0xFFFFFFFFu;
    bool taken = hitSrc != 0xFFFFFFFFu;
    if (taken) {
        // (the record, farHitStore's: res keeps this pixel's own dither)
        uint4 ha = prevFarHit[hitSrc], hb = prevFarHit[hitSrc + 1u];
        float tq = asfloat(ha.x), wq = asfloat(ha.y);
        float tf = tq >= 0.0 ? tq : wq;
        tHit = tq < 0.0 || hitD < 0.0 ? -1.0 : hitD + (tq - tf);
        tW = wq < 0.0 || hitD < 0.0 ? -1.0 : hitD + (wq - tf);
        res.mat = ha.z;
        res.hasAlbedo = ha.w;
        res.axis = int(hb.x & 3u) - 1;
        res.lod = int((hb.x >> 2) & 15u);
        res.albedo = float3(f16tof32(hb.z & 0xFFFFu), f16tof32(hb.z >> 16), f16tof32(hb.w & 0xFFFFu));
        res.steps = 0u;
        res.t = 0.0;
        wRes = res;
        wRes.mat = hb.y;
        wRes.lod = int((hb.x >> 6) & 15u);
        hitStamp = hb.w >> 16;
    }
    if (!taken) {
        hitStamp = frame.temporal.x;
        gSkipWater = eyeWater;
        gTr = res;
        // (under water a ray that does not reach the surface within WATER_SEE blocks sees nothing past that: no further)
        float tEye = eyeWater && !(dir.y > 1e-3 && eyeDepth / dir.y < WATER_SEE) ? WATER_SEE * 1.5 : frame.params.y;
        // pass 0 the view ray; pass 1, a water surface met from above: (PRIMARY_NEAR) in the near field the host rasters
        // it, a primary ray goes on to what is under it (the groups' cells and the SVDAG have no near-only pass, so a
        // second trace with water skipped); (_LIT) atlas draws it at any distance, and what lies behind it is traced on
        // from it, WATER_SEE blocks at most. (One traceView call site for both: each site is one more inlined copy of
        // the whole walk, a megabyte of the split kernel's code)
        float ta = tStart, tb = tEye;
        for (int pass = 0; pass < 2; pass++) {
            float th = traceView(o, dir, ta, tb, lodDither);
            tHit = th;
            res = gTr;
            gSkipWater = false;
            if (pass == 1) {
                // (_LIT) what it met past WATER_SEE blocks of water is not seen: deep water (the water is taken to go on
                // to what the ray met: a pool's wall it leaves by is rare)
                if (unified && tHit >= 0.0 && tHit - tW >= WATER_SEE) tHit = -1.0;
                break;
            }
            wRes = res;
            if (!(primaryNear && !eyeWater && tHit >= 0.0 && (unified || tHit < frame.params.x) &&
                  uint(palette[res.mat].emisClass.y) == 2u))
                break;
            tW = unified ? tHit : -1.0;
            // a fresh result: only the pixel's dither carries over (the water hit's texel albedo tinted the bed)
            TraceResult r2;
            r2.t = 0; r2.mat = 0; r2.axis = -1; r2.lod = 0; r2.steps = res.steps; r2.dither = res.dither;
            r2.albedo = float3(0, 0, 0); r2.hasAlbedo = 0u;
            gSkipWater = true;
            gTr = r2;
            ta = unified ? tHit : tStart;
            tb = unified ? min(tHit + WATER_SEE * 1.5, frame.params.y) : frame.params.y;
        }
    }
    if (amort) farHitStore(pix, tHit, tW, res, wRes, hitStamp);
    gGroups = false;  // secondary rays (shadows, reflections, block light) keep the old path
    gSecAblate = gAblate;
    gSecMode = (gAblate >> 23) & 7u;
    gSecCount = 0u;
    ActorHit ah;
    ah.t = -1.0;
    ah.n = float3(0, 1, 0);
    ah.albedo = float3(1, 1, 1);
    if ((frame.dims.z & FRAME_ACTORS) != 0 && frame.weatherParams.w > 0.5)
        ah = traceActors(o, dir, frame.params.x, tW >= 0.0 ? tW : tHit >= 0.0 ? tHit : frame.params.y, pixelDither(id.xy, phase + 2u));
    bool actorFirst = ah.t >= 0.0;
    // (_LIT) the water in front of what the ray met (waterA x behind + waterB, waterB before the air in front of the
    // water: its haze, waterAir, applied here) and the view distance where the air begins (the eye under water)
    float3 waterA = float3(1, 1, 1), waterB = float3(0, 0, 0);
    float airFrom = 0.0, waterSunVis = 1.0;
    float3 waterN = float3(0, 1, 0);
    bool watered = unified && !actorFirst && (tW >= 0.0 || eyeWater);
    if (watered) {
        if (eyeWater) {
            waterFromBelow(o, dir, tHit, eyeMat, eyeDepth, sun, waterA, waterB, airFrom);
            if (tHit >= 0.0) {
                // the air lies behind the water here (out of the surface, through Snell's window): its haze is seen
                // through the water as what lies beyond it is; folded in, the composite adds no air of its own
                AirPath beyond = aerialPathTo(dir, max(tHit - airFrom, 0.0));
                waterB += waterA * beyond.inscatter;
                waterA *= beyond.transmittance;
            }
        } else {
            float wEnd = tHit >= 0.0 ? tHit : 1e30;
            gSecFoot0 = tW * frame.params.w * frame.params.z;
            gSecSpread = frame.params.w * frame.params.z;
            waterFromAbove(id.xy, o, dir, tW, wRes.mat, wRes.lod, min(wEnd - tW, WATER_SEE), sun, waterA, waterB, waterSunVis, waterN);
            gSecFoot0 = 0.0;
            gSecSpread = 0.0;
            waterB *= aerialT(dir, tW);  // (the air between the eye and the surface; its inscatter: the composite's)
        }
    }
    // (ATLAS_FRAME_PRIMARY_NEAR) a hit nearer than nearFieldEnd is the near field's: its surface, lit by the near light
    // (nearLightAt, as the host's raster pixels are under FRAME_NEAR_LIGHT); the G-buffer for the host's resolve, or
    // (_LIT) composited here. atlas.h ATLAS_FRAME_PRIMARY_NEAR has the layout.
    // (_LIT, the unified field) every land hit at any distance takes this one light (traced sun, sky, bounce, haze): no
    // second model past nearFieldEnd, so no line; a hit behind water (the bed, the shore under it) too, the water over it
    // (waterA, waterB)
    bool oneLight = unified && tHit >= 0.0 && uint(palette[res.mat].emisClass.y) != 2u;
    if (primaryNear && !actorFirst && tHit >= 0.0 && (tHit < frame.params.x || oneLight)) {
        MaterialGpu m = palette[res.mat];
        uint cls = uint(m.emisClass.y);
        int ax = res.axis < 0 ? 1 : res.axis;
        float3 n = float3(0, 0, 0);
        n[ax] = dir[ax] > 0.0 ? -1.0 : 1.0;  // the face entered (blocks, near: no smoothing)
        if ((res.hasAlbedo & HIT_SMOOTH_N) != 0u) n = hitSmoothN(res.hasAlbedo);  // (a coarse level's smooth surface)
        float3 albedo = res.hasAlbedo != 0u ? res.albedo : m.albedoRough.rgb;
        float2 e = octEncode(n);
        // (byte 8-15, the host's sky light where the host writes the info: in the unified field atlas's own, 255 - the hit's
        // level, nearLightAt's origin out of that level's solid)
        uint info = ((255u - uint(clamp(res.lod, 0, 7))) << 8) | (uint(saturate(e.x) * 255.0 + 0.5) << 16) | (uint(saturate(e.y) * 255.0 + 0.5) << 24);
        // (what the host's depth test sees: the bed, so what swims over it shows, the water's medium on it from the
        // surface's distance, waterAdd.a; under water, where the ray left the water)
        float tDepth = watered && eyeWater ? min(tHit, max(airFrom, 0.05)) : tHit;
        if (FAR_SPLIT || (unified && (frame.dims.z & FRAME_NEAR_SPLIT) != 0u)) {
            // (the split, the light by need) the hit for the light passes: its distance and normal; its albedo (through
            // the water over it) in the colour until nearTemporalMain composites it, the water's own light and where the
            // air begins in waterAdd, the key light's visibility there too
            nearDepth[pix] = tHit;
            nearInfo[pix] = info;
            outColor[pix] = float4(albedo * waterA, 1.0);
            // (an emitter's own glow rides with the water's light: added after the light, atlas_bricks.slang nearEmission;
            // through the water over it and the air in front, as the light's composite is)
            float3 glow = nearEmission(res.mat, albedo) * waterA * (eyeWater ? float3(1, 1, 1) : aerialT(dir, tHit));
            waterAdd[pix] = float4(waterB + glow, eyeWater ? -1.0 : watered ? tW : 0.0);
            outNormal[pix] = packNormal(n, (cls & 0x7Fu) | 0x80u, 0.0);
            outDepth[pix] = hostDepth(dir, tDepth);
            outSurface[pix] = tHit;
            // (ATLAS_ABLATE 536870912, measurements: the counters count the pixels the split traces, nearLightMain)
            counters[pix] = (gAblate & 536870912u) != 0u ? 0u : res.steps;
            return;
        }
        float4 nl = nearLightAt(id.xy, o, dir, tHit, info, sun);
        outNearLight[pix] = nl;
        float4 c = float4(albedo, 1.0);
        if (unified) {
            AirPath air = aerialPathTo(dir, eyeWater ? 0.0 : tHit);  // (under water: the air is in waterA, waterB)
            float T = dot(air.transmittance, float3(0.2126, 0.7152, 0.0722));
            float3 ins = gNearRoofed && eyeRoofed() ? float3(0, 0, 0) : air.inscatter;  // under cover at both ends: unlit air
            c = float4(albedo * waterA * nl.rgb * T + ins + waterB, 1.0);
            c.rgb += nearEmission(res.mat, albedo) * waterA * T;  // (an emitter's own glow, atlas_bricks.slang)
            // light through leaves (the unified field's FOLIAGE_SSS): the key light (sun, or moon at night) reaching the
            // leaf through the crown in front of it towards the light (a traced ray, attenuated by every leaf it
            // crosses, this one's too: a crown's edge passes most, its heart little), sent on through the leaf tissue
            // in its own colour, strongest looking towards the light: the backlit glow, bright at the crown's rim where
            // the light crossed few leaves, dim and diffuse inside. ATLAS_ABLATE 1024: none
            if (cls == 1u && (frame.dims.z & FRAME_SHADOWS) != 0u && (gAblate & 1024u) == 0u) {
                float3 L = keyLightDir(sun);
                float R0 = frame.camFrac.w;
                float k0 = R0 > 0 ? dot(dir.xz, dir.xz) / (2.0 * R0) : 0.0;
                float3 ph = worldAt(o, dir, k0, tHit) + n * 0.02;  // (on the curved view ray, as the hit is)
                gSecSpread = 0.0;
                gSecCone = 0.0;
                float vis = traceSecondary(ph + L * 0.05, L, 96.0, res.lod);
                // (the secondary ray counts a leaf crossed as opaque but for its gaps; its tissue lets about a third
                // through as well: per crossing 0.09 of the gaps becomes about 0.4, the ray's transmittance to the 0.4)
                float through = pow(saturate(max(vis, gThrough)), 0.4);
                float fwd = max(dot(dir, L), 0.0);
                float back = 0.2 + 1.2 * fwd * fwd * fwd;  // forward scattering: towards the light the most
                float gain = frame.lodParams.w > 0.0 ? frame.lodParams.w : 1.0;  // (ATLAS_LOD's fourth knob)
                c.rgb += albedo * waterA * keyLightRadiance(sun) * (through * back * 0.5 * gain) * T;
            }
            // the far lights' glow and the cloud deck in front of it, as the far field's pixels have them
            float tFront = watered && !eyeWater ? tW : tDepth;  // (the nearest surface: the water's)
            c.rgb += farLights(id.xy, o, dir, tFront, sun);
            if ((frame.dims.z & FRAME_CLOUDS) != 0) {
                float4 cl;
                float clDepth;
                sampleClouds(id.xy, cl, clDepth);
                if (clDepth < tFront) c.rgb = c.rgb * cl.a + cl.rgb;
            }
        }
        outColor[pix] = c;
        outNormal[pix] = packNormal(n, (cls & 0x7Fu) | 0x80u, nl.a);
        outDepth[pix] = hostDepth(dir, tDepth);
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
        airAtPoint(o + dir * ah.t);
        if ((frame.dims.z & FRAME_CLOUDS) != 0) sunVis = cloudShadow(o + dir * ah.t, keyLightDir(sun));
        MaterialGpu am;
        am.albedoRough = float4(ah.albedo, 0.8);
        am.emisClass = float4(0, 0, 0, 0);
        color = shadeSurface(ah.albedo, am, n, dir, sun, sunVis, 0u, 1.0, float3(0, 0, 0));
        depth = ah.t;
        color = applyAerial(color, dir, sun, ah.t, frame);
    } else if (watered) {
        // (_LIT) the water with nothing seen behind it: deep water from above (its own light, the sky it mirrors, the
        // glint; then the air in front), or from under water the open water, the surface from below and, through
        // Snell's window, the sky (the host's drawn sky behind it, by the window's share: the coverage)
        cls = 2u;
        n = eyeWater ? float3(0, -1, 0) : waterN;
        sunVis = waterSunVis;
        if (eyeWater) {
            depth = airFrom > 0.0 ? airFrom : 1e30;
            color = waterB + ((frame.dims.z & FRAME_SKY) != 0 ? skyColor(dir, sun) * waterA : float3(0, 0, 0));
        } else {
            // (the host's depth: WATER_SEE past the surface, so what swims under it shows, in the water's medium)
            depth = tW + WATER_SEE;
            color = waterB + aerialPathTo(dir, tW).inscatter;
        }
    } else if (!FAR_SPLIT && tHit >= 0.0) {  // (the split's hits all went to its light passes above)
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
        if ((res.hasAlbedo & HIT_SMOOTH_N) != 0u) n = hitSmoothN(res.hasAlbedo);  // (a coarse level's smooth surface)
        float3 p = worldAt(o, dir, k, tHit);
        airAtPoint(p);  // the key light at the hit (a peak keeps the sun longer; an eclipse's shadow where it falls)
        float s = float(1 << res.lod);
        // the hit's secondary rays (shadow, sky, bounce) go on as the pixel's cone: its footprint here, widening as it does
        gSecFoot0 = tHit * frame.params.w * frame.params.z;
        gSecSpread = frame.params.w * frame.params.z;
        // blocky up close (it is Minecraft), smooth once voxels shrink towards a pixel: stops contour striping
        // centre of the hit voxel on the world voxel grid (multiples of s; y -64 is a multiple for s <= 64)
        float3 ci = float3(frame.camInt.xyz);
        // (the camera's block split into whole voxels of the level and the rest: p + camInt in float lost the voxel at
        // large world coordinates)
        int3 cq = frame.camInt.xyz >> res.lod;
        float3 cr = float3(frame.camInt.xyz - (cq << res.lod)) / s;
        float3 centre = (floor(p / s + cr - n * 0.5) + 0.5 - cr) * s;
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
                    gTr = r2;
                    float t2 = traceView(o, dir, tStart, frame.params.y, lodDither);
                    r2 = gTr;
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
        // (the continuous level at the hit; the level taken is its pick, lodPick)
        if ((frame.dims.z & FRAME_DEBUG_LOD) != 0) albedo = lodTintF(gHitLod);
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
    if (watered && !actorFirst && coverage < 1.0)  // (under water, the sky through the window: by A's share)
        coverage = eyeWater ? 1.0 - saturate(dot(waterA, float3(0.2126, 0.7152, 0.0722))) : 1.0;
    float surface = actorFirst ? ah.t : (tHit >= 0.0 ? tHit : watered ? (eyeWater ? depth : tW) : 1e30);
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
    if (primaryNear && (frame.dims.z & FRAME_NEAR_SPLIT) != 0u) {
        // (the water's surface for the host, as the split's pixels carry it)
        if (unified) waterAdd[pix] = float4(0.0, 0.0, 0.0, watered && !eyeWater ? tW : 0.0);
        nearDepth[pix] = 1e30;  // (nor the light passes')
        nearInfo[pix] = 0u;     // (a SPLIT_TRACED mark of a frame before ghosted a silhouette into the sky: nearUpsampleMain)
    }
    outColor[pix] = float4(color, coverage);
    outNormal[pix] = packNormal(n, cls, sunVis);
    outDepth[pix] = primaryNear ? hostDepth(dir, depth) : depth;  // (PRIMARY_NEAR: the host's NDC depth, every pixel)
    counters[pix] = (gAblate & 536870912u) != 0u ? 0u : res.steps;  // per-pixel step count (summed on the host; no atomics so the C++ target matches)
}
