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
    for (int it = 0; it < 1024; it++) {
        if (t >= t1) break;
        res.steps++;
        float3 p = ro + rd * t;
        int3 v = clamp(int3(floor(p)), int3(0, 0, 0), int3(255, 255, 255));
        uint ptr = r.bases.z;
        uint rank = 0;
        int size = 256;
        int cell = 0;
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
        }
        if (cell == 0) {
            uint64_t bits = leaves[leafBase + ptr];
            int3 l = v & 3;
            uint b = uint(l.x + 4 * l.z + 16 * l.y);
            if (((bits >> b) & 1) != 0) {
                rank += popcount64(bits & ((uint64_t(1) << b) - 1));
                res.t = t;
                res.mat = lookupMaterial(r, rank);
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
    for (int it = 0; it < 64; it++) {
        if (u >= len) break;
        float3 q = q0 + qd * u;
        int3 c = int3(floor(q));
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
        u = max(uExit, u) + 1e-4 * s;
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

// Smoothed surface normal: the negated occupancy gradient over a 3x3x3 stencil with a stride of `stride` voxels.
float3 smoothNormal(int lod, float3 centre, float stride) {
    float s = float(1 << lod) * stride;
    float3 g = float3(0, 0, 0);
    for (int dz = -1; dz <= 1; dz++)
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                float3 o = float3(float(dx), float(dy), float(dz));
                if (occupiedAt(lod, centre + o * s)) g += o / dot(o, o);
            }
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
            if ((frame.dims.z & FRAME_CLOUDS) != 0) {
                CloudResult cl = marchClouds(o, dir, nd, sun, id.xy);
                if (cl.firstHit >= 0.0) c = float4(cl.scatter, 1.0 - cl.transmittance);
            }
            outColor[pix] = c;
            outNormal[pix] = 0;
            outDepth[pix] = 1e30;
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
        if ((frame.dims.z & FRAME_CLOUDS) != 0) sunVis = cloudShadow(o + dir * ah.t, sun);
        MaterialGpu am;
        am.albedoRough = float4(ah.albedo, 0.8);
        am.emisClass = float4(0, 0, 0, 0);
        color = shadeSurface(ah.albedo, am, n, dir, sun, sunVis, 0u);
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
        if (wSmooth > 0.0) {
            float3 sn = smoothNormal(res.lod, centre, 1.0);
            if (dot(sn, -dir) > 0.05) n = normalize(lerp(n, sn, wSmooth));
        }
        if ((frame.dims.z & FRAME_SHADOWS) != 0) {
            // same LOD as the hit (a coarser one is fatter and self-shadows); start one voxel out along the face
            float3 faceN = float3(ax == 0 ? (slope.x > 0 ? -1.0 : 1.0) : 0.0, ax == 1 ? (slope.y > 0 ? -1.0 : 1.0) : 0.0,
                                  ax == 2 ? (slope.z > 0 ? -1.0 : 1.0) : 0.0);
            sunVis = dot(n, sun) > 0.0 && dot(faceN, sun) > -0.2
                ? traceShadow(p + faceN * (1.01 * s) + sun * (0.5 * s), sun, res.lod) : 0.0;
        }
        if ((frame.dims.z & FRAME_CLOUDS) != 0 && sunVis > 0.0) sunVis *= cloudShadow(p, sun);
        float3 albedo = m.albedoRough.rgb;
        if ((frame.dims.z & FRAME_DEBUG_LOD) != 0) albedo = lodTint(res.lod);
        color = shadeSurface(albedo, m, n, dir, sun, sunVis, cls);
        depth = tHit;
        color = applyAerial(color, dir, sun, tHit, frame);
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
    if ((frame.dims.z & FRAME_CLOUDS) != 0) {
        CloudResult cl = marchClouds(o, dir, actorFirst ? ah.t : (tHit >= 0.0 ? tHit : frame.params.y), sun, id.xy);
        if (cl.firstHit >= 0.0) {
            // the deck's own aerial perspective: distant storms fade into the horizon haze like the terrain does
            // (the background behind the cloud already carries its own haze)
            float trA = aerialTransmittance(cl.firstHit, frame);
            float3 scatter = cl.scatter * trA + aerialFog(dir, sun) * (1.0 - trA) * (1.0 - cl.transmittance);
            color = color * cl.transmittance + scatter;
            coverage = 1.0 - (1.0 - coverage) * cl.transmittance;
        }
    }
    outColor[pix] = float4(color, coverage);
    outNormal[pix] = packNormal(n, cls, sunVis);
    outDepth[pix] = depth;
    counters[pix] = res.steps;  // per-pixel step count (summed on the host; no atomics so the C++ target matches)
}
