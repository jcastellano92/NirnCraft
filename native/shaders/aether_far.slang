// aether_core far-field marcher: one compute kernel, compiled twice from this file:
//   slangc -target spirv  -> Vulkan (game and GPU harness)
//   slangc -target cpp    -> the CPU reference backend (parity tests, machines without Vulkan)
// so the two can only differ by floating point, never by algorithm.
//
// Space: everything is relative to the integer camera block (frame.camInt) in float, exact enough out to ~64 km.
// Curvature: the view frame is flat; a point at horizontal distance d from the camera stands at world altitude
// y + d^2 / (2R) (the rasterised near field applies the matching vertex drop y' = y - d^2 / (2R)). A view ray
// o + t D is therefore the parabola o + t D + up * k t^2 in world-flat space, k = |D_xz|^2 / (2R), walked as
// straight chords per segment (error k S^2 / 4: 0.02 blocks for S = 1 km at the real-Earth radius).

#include "aether_shared.slang"

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

#include "aether_actors.slang"
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

#include "aether_clouds.slang"

static const int TRACE_MISS = 0;
static const int TRACE_HIT = 1;
static const int TRACE_UNCOVERED = 2;

struct TraceResult {
    float t;       // chord parameter of the hit
    uint mat;
    int axis;      // axis of the face crossed into the hit voxel
    int lod;
    uint steps;
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
                bool passWater = (frame.dims.z & FRAME_SEE_THROUGH_WATER) != 0 && uint(palette[hitMat].emisClass.y) == 2u;
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
                float3 ro = (p0 - regionOriginRel(r)) / s;
                if (traceRegion(r, ro, d / s, u, uExit, 1e-3, res)) {
                    res.lod = lod;
                    return TRACE_HIT;
                }
            }
        }
        if (uExit >= len) break;
        // into the neighbour across the face we leave by, by index: re-flooring q a hair past that face is below
        // float precision in table units (a cell is 256 voxels), lands back in the same cell, and would re-trace its
        // region from outside it (clamped to its boundary layer: false hits one voxel past a region floor/ceiling)
        if (tt.x <= tt.y && tt.x <= tt.z) c.x += stepC.x;
        else if (tt.y <= tt.z) c.y += stepC.y;
        else c.z += stepC.z;
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
float traceView(float3 o, float3 dir, float t0, float t1, inout TraceResult res) {
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
    for (int seg = 0; seg < 768; seg++) {
        if (t >= t1) break;
        int want = int(clamp(floor(log2(max(t * pixelAngle, 1.0))), 0.0, float(lodCount - 1)));
        if (!finer) lod = pinLeft > 0 ? pinned : max(lod, want);
        float s = float(1 << lod);
        float tn = min(t + 192.0 * s, t1);
        float3 a = worldAt(o, dir, k, t);
        float3 b = worldAt(o, dir, k, tn);
        // rising above the world top: the parabola only climbs further from here
        if (a.y > worldTop && dir.y + 2.0 * k * t >= 0.0) break;
        float len = tn - t;
        float3 d = (b - a) / len;
        float uncExit;
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
        finer = false;
        gapEnd = 1e30;
    }
    return -1.0;
}

// Straight ray (sun shadows): 1 if lit.
float traceShadow(float3 p, float3 dir, int lod) {
    TraceResult res;
    res.t = 0; res.mat = 0; res.axis = -1; res.lod = 0; res.steps = 0;
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
    rr.t = 0; rr.mat = 0; rr.axis = -1; rr.lod = 0; rr.steps = 0;
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
        float dd = pd < 1e29 ? pd : 20000.0;
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
                cloudBuf[ci] = lerp(lerp(prevCloudBuf[i00], prevCloudBuf[i10], fr.x), lerp(prevCloudBuf[i01], prevCloudBuf[i11], fr.x), fr.y);
                cloudDepth[ci] = min(min(prevCloudDepth[i00], prevCloudDepth[i10]), min(prevCloudDepth[i01], prevCloudDepth[i11]));
                return;
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
    rr.t = 0; rr.mat = 0; rr.axis = -1; rr.lod = 0; rr.steps = 0;
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
    uint w = frame.dims.x, h = frame.dims.y;
    if (id.x >= w || id.y >= h) return;
    uint pix = id.y * w + id.x;
    float2 ndc = float2((float(id.x) + 0.5) / float(w) * 2.0 - 1.0, 1.0 - (float(id.y) + 0.5) / float(h) * 2.0);
    float3 dir = normalize(unproject(frame, ndc));
    float3 o = frame.camFrac.xyz;
    TraceResult res;
    res.t = 0; res.mat = 0; res.axis = -1; res.lod = 0; res.steps = 0;
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
    float tHit = traceView(o, dir, frame.params.x, frame.params.y, res);
    ActorHit ah;
    ah.t = -1.0;
    ah.n = float3(0, 1, 0);
    ah.albedo = float3(1, 1, 1);
    if ((frame.dims.z & FRAME_ACTORS) != 0 && frame.weatherParams.w > 0.5)
        ah = traceActors(o, dir, tHit >= 0.0 ? tHit : frame.params.y);
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
        float wSmooth = res.lod > 0 ? 0.85 : 0.85 * saturate((tHit - 96.0) / 400.0);
        if ((frame.dims.z & FRAME_FLAT_NORMALS) != 0) wSmooth = 0.0;
        if (wSmooth > 0.0) {
            float3 sn = smoothNormal(res.lod, centre, 1.0);
            if (dot(sn, -dir) > 0.05) n = normalize(lerp(n, sn, wSmooth));
        }
        if ((frame.dims.z & FRAME_SHADOWS) != 0) {
            // same LOD as the hit (a coarser one is fatter and self-shadows); start one voxel out along the face
            float3 faceN = float3(ax == 0 ? (slope.x > 0 ? -1.0 : 1.0) : 0.0, ax == 1 ? (slope.y > 0 ? -1.0 : 1.0) : 0.0,
                                  ax == 2 ? (slope.z > 0 ? -1.0 : 1.0) : 0.0);
            float3 L = keyLightDir(sun);
            sunVis = dot(n, L) > 0.0 && dot(faceN, L) > -0.2
                ? traceShadow(p + faceN * (1.01 * s) + L * (0.5 * s), L, res.lod) : 0.0;
        }
        if ((frame.dims.z & FRAME_CLOUDS) != 0 && sunVis > 0.0) sunVis *= cloudShadow(p, keyLightDir(sun));
        float3 albedo = m.albedoRough.rgb;
        // ambient occlusion: solid neighbours around the air voxel in front of the face (4 taps in the face plane)
        float ao = 1.0;
        {
            float3 fn = float3(ax == 0 ? (slope.x > 0 ? -1.0 : 1.0) : 0.0, ax == 1 ? (slope.y > 0 ? -1.0 : 1.0) : 0.0,
                               ax == 2 ? (slope.z > 0 ? -1.0 : 1.0) : 0.0);
            float3 front = centre + fn * s;
            float3 t1 = ax == 0 ? float3(0, s, 0) : float3(s, 0, 0);
            float3 t2 = ax == 2 ? float3(0, s, 0) : float3(0, 0, s);
            float occ = (occupiedAt(res.lod, front + t1) ? 1.0 : 0.0) + (occupiedAt(res.lod, front - t1) ? 1.0 : 0.0) +
                        (occupiedAt(res.lod, front + t2) ? 1.0 : 0.0) + (occupiedAt(res.lod, front - t2) ? 1.0 : 0.0);
            ao = 1.0 - 0.15 * occ;
        }
        // large-scale tone variation on land (about 24 and 90 blocks), the far-field stand-in for texture detail
        if (cls != 2u) {
            float2 wxz = p.xz + float2(frame.camInt.xz);
            float v = valueNoise(wxz / 24.0) * 0.6 + valueNoise(wxz / 90.0 + 17.0) * 0.4;
            albedo *= 0.86 + 0.28 * v;
        }
        if ((frame.dims.z & FRAME_DEBUG_LOD) != 0) albedo = lodTint(res.lod);
        float3 reflection = float3(0, 0, 0);
        if (cls == 2u) {
            float2 wxz = p.xz + float2(frame.camInt.xz);
            float2 wind = farWaterWind(wxz, length(p.xz));
            // far water is a level sheet: a side face is only the step where its quantised level changes between
            // LODs (seen edge-on near the horizon it drew a dark seam), so every water hit is shaded as the surface
            n = farWaveNormal(wxz, frame.sunDir.w, wind, tHit);
            m.albedoRough.a = farWaterAlpha(wind.y, tHit);
            reflection = waterReflection(p, dir, k, tHit, res.lod, sun, n);
        }
        color = shadeSurface(albedo, m, n, dir, sun, sunVis, cls, ao, reflection);
        depth = tHit;
        color = applyAerial(color, dir, sun, tHit, frame);
        if ((frame.dims.z & FRAME_DEBUG_ALBEDO) != 0) color = albedo;
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
    if ((frame.dims.z & FRAME_CLOUDS) != 0) {
        // the half-resolution deck, where it starts in front of this pixel's surface
        float4 cl;
        float clDepth;
        sampleClouds(id.xy, cl, clDepth);
        if (clDepth < surface) {
            color = color * cl.a + cl.rgb;
            coverage = 1.0 - (1.0 - coverage) * cl.a;
            // depth = the nearest thing drawn, cloud included: lets a composite keep near geometry in front of it
            if (cl.a < 0.99) depth = min(depth, clDepth);
        }
    }
    outSurface[pix] = surface;
    outColor[pix] = float4(color, coverage);
    outNormal[pix] = packNormal(n, cls, sunVis);
    outDepth[pix] = depth;
    counters[pix] = res.steps;  // per-pixel step count (summed on the host; no atomics so the C++ target matches)
}
