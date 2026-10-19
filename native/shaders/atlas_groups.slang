// The content-shaped groups (docs/ATLAS-LOD.md; built by src/groups.cpp): per region a binary hierarchy of boxes over
// its micro-voxels (4 per voxel side), walked front to back. A node is drawn whole, as a box whose faces are masked
// textures of the content under them, where the flattening error of the face the ray enters by is under the pixel's
// footprint there (times a threshold dithered per pixel and frame, which the host's TAA averages: no pop between
// levels), else it is opened; a flat solid leaf is always drawn; a small voxel leaf bigger than the pixel is stepped
// through voxel by voxel, and micro-voxel by micro-voxel inside a shaped block (a slab, a stair, a fence, cut-out
// leaves). The first hit ends the ray. atlas_lod.slang's groupsMain is the experiment this follows, rule for rule.
//
// groupData (uint4; PackedScene::groups):
//  [0]            header: x the material -> shape table (word offset), y the first shape (uint4), z the table's length
//  shapes         32 uint4 each: mask lo, mask hi, flags (1 cut-out), mean ARGB; 96 face texels (down, up, north,
//                 south, west, east x 16, ARGB sRGB, alpha 0 = the mean); 64 alpha bytes (cut-out coverage)
//  per region     (RegionGpu.groups) its nodes from x (uint4): x / y / z = lo | hi << 12 | low face's error code << 24 |
//                 high face's << 28 (micro-voxels, region-local), w = type << 30 | first child (relative); its brick
//                 columns from y (words): 64 x 64 of slot offset | first row << 18 | rows << 24; its slots from z (a
//                 brick's first word + 1); its bricks from w: occupancy (2 words), then the occupied voxels' cells of 16
//                 bits in order, two to a word (material | 0x8000: takes its shape)

static const uint G_INNER = 0u;
static const uint G_FLAT = 1u;
static const uint G_VOXELS = 2u;
static const uint G_CHILD = 0x3FFFFFFFu;
static const int G_STACK = 64;
static const uint G_CELL_SHAPED = 0x8000u;
static const uint G_CELL_MASKED = 0x4000u;
static const uint G_CELL_MATERIAL = 0x3FFFu;
static const uint G_SLOT_MASKS = 0x80000000u;
static const int G_FACE_LOW[3] = { 4, 0, 2 };   // west, down, north: the face a ray going + along the axis enters
static const int G_FACE_HIGH[3] = { 5, 1, 3 };  // east, up, south
static const float G_ERR[16] = { 0.0, 1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 8.0, 10.0, 12.0, 16.0, 24.0, 32.0, 64.0, 128.0, 4096.0 };

// set by farMain for its primary rays: trace regions' groups (ATLAS_FRAME_GROUPS); the pixel's footprint per block of
// distance and its dithered threshold; the view distance where the current chord starts
static bool gGroups = false;
static float gGroupPa = 0.001;
static float gGroupTol = 1.0;
static float gChordT = 0.0;

uint gWord(uint w) {
    uint4 q = groupData[w >> 2];
    uint k = w & 3u;
    return k == 0u ? q.x : (k == 1u ? q.y : (k == 2u ? q.z : q.w));
}

// the cell (material | G_CELL_SHAPED | G_CELL_MASKED, 0 = nothing a ray can meet) of region voxel v (0..255 each);
// a masked one's own micro-voxels in mask
uint voxelCell(RegionGpu r, int3 v, out uint2 mask) {
    mask = uint2(0u, 0u);
    uint e = gWord(r.groups.y + uint((v.z >> 2) * 64 + (v.x >> 2)));
    int rows = int(e >> 24);
    int by = (v.y >> 2) - int((e >> 18) & 63u);
    if (by < 0 || by >= rows) return 0u;
    uint b = gWord(r.groups.z + (e & 0x3FFFFu) + uint(by));
    if (b == 0u) return 0u;
    // the brick: its occupancy (and with G_SLOT_MASKS which voxels carry their own micro-voxels), then the cells of
    // the occupied voxels in order, then the masked ones' micro-voxels
    bool masks = (b & G_SLOT_MASKS) != 0u;
    uint at = r.groups.w + (b & ~G_SLOT_MASKS) - 1u;
    uint i = uint((v.x & 3) + 4 * ((v.z & 3) + 4 * (v.y & 3)));
    uint lo = gWord(at), hi = gWord(at + 1u);
    uint rank;
    if (i < 32u) {
        if (((lo >> i) & 1u) == 0u) return 0u;
        rank = countbits(lo & ((1u << i) - 1u));
    } else {
        if (((hi >> (i - 32u)) & 1u) == 0u) return 0u;
        rank = countbits(lo) + countbits(hi & ((1u << (i - 32u)) - 1u));
    }
    uint head = masks ? 4u : 2u;
    uint w = gWord(at + head + (rank >> 1));
    uint cell = (rank & 1u) != 0u ? (w >> 16) : (w & 0xFFFFu);
    if ((cell & G_CELL_MASKED) != 0u) {
        uint mlo = gWord(at + 2u), mhi = gWord(at + 3u);
        uint mrank = i < 32u ? countbits(mlo & ((1u << i) - 1u)) : countbits(mlo) + countbits(mhi & ((1u << (i - 32u)) - 1u));
        uint cells = countbits(lo) + countbits(hi);
        uint m = at + head + ((cells + 1u) >> 1) + mrank * 2u;
        mask = uint2(gWord(m), gWord(m + 1u));
    }
    return cell;
}

// the shape of a material: its first word in groupData, or 0 (a full cube in its palette colour)
uint shapeOf(uint mat) {
    uint4 h = groupData[0];
    if (mat >= h.z) return 0u;
    uint s = gWord(h.x + mat);
    return s == 0u ? 0u : (h.y + (s - 1u) * 32u) * 4u;
}

uint hashU(uint x) {
    x ^= x >> 16;
    x *= 0x7feb352dU;
    x ^= x >> 15;
    x *= 0x846ca68bU;
    x ^= x >> 16;
    return x;
}

// src/groups.hpp cutHash: the same cut-out on the CPU (the builder) and here
uint cutHash(int3 p) {
    uint h3 = hashU(uint(p.z) * 83492791u ^ 0x5EEDu);
    uint h2 = hashU(uint(p.y) * 19349663u ^ h3);
    return hashU(uint(p.x) * 73856093u ^ h2);
}

// whether region micro-voxel q of a voxel holding `cell` has content (its own micro-voxels; its material's shape, the
// cut-out hashed in world space)
bool microFilled(RegionGpu r, uint cell, uint2 own, int3 q) {
    if ((cell & G_CELL_MASKED) != 0u) {
        int3 mq = q & 3;
        uint ob = uint(mq.x + 4 * (mq.z + 4 * mq.y));
        return (((ob < 32u ? own.x >> ob : own.y >> (ob - 32u)) & 1u) != 0u);
    }
    if ((cell & G_CELL_SHAPED) == 0u) return true;
    uint sh = shapeOf(cell & G_CELL_MATERIAL);
    if (sh == 0u) return true;
    int3 m = q & 3;
    uint bit = uint(m.x + 4 * (m.z + 4 * m.y));
    if (((gWord(sh + (bit >> 5)) >> (bit & 31u)) & 1u) == 0u) return false;
    if ((gWord(sh + 2u) & 1u) == 0u) return true;
    uint a = (gWord(sh + 100u + (bit >> 2)) >> ((bit & 3u) * 8u)) & 255u;
    if (a == 255u) return true;
    return (cutHash(r.origin.xyz * 4 + q) >> 24) < a;
}

// the texel of face f over micro-voxel (x, y, z) of a block (AtlasLabExport's layout)
int gTexel(int f, int x, int y, int z) {
    if (f <= 1) return x + 4 * z;
    if (f <= 3) return x + 4 * (3 - y);
    return z + 4 * (3 - y);
}

float3 srgbLinear(uint c) {
    float3 v = float3(float((c >> 16) & 255u), float((c >> 8) & 255u), float(c & 255u)) / 255.0;
    return pow(v, float3(2.2, 2.2, 2.2));
}

// the albedo (linear) of face f of region micro-voxel q in a voxel holding `cell`: its face tile's texel, else its
// palette colour
float3 cellAlbedo(uint cell, int f, int3 q) {
    uint mat = cell & G_CELL_MATERIAL;
    uint sh = shapeOf(mat);
    if (sh == 0u) return palette[mat].albedoRough.rgb;
    int3 m = q & 3;
    uint c = gWord(sh + 4u + uint(f * 16 + gTexel(f, m.x, m.y, m.z)));
    if ((c >> 24) == 0u) c = gWord(sh + 3u);
    return srgbLinear(c);
}

// a hit: what the shading needs (the old path's TraceResult, plus the texel's albedo)
void groupHit(inout TraceResult res, float t, uint cell, int axis, float3 dir, int3 q) {
    res.t = t;
    res.mat = cell & G_CELL_MATERIAL;
    res.axis = axis;
    int f = dir[axis] > 0.0 ? G_FACE_LOW[axis] : G_FACE_HIGH[axis];
    res.albedo = cellAlbedo(cell, f, q);
    res.hasAlbedo = 1u;
}

// the ray against box [lo, hi): entry, exit and the axis of the entry plane
bool gSlab(float3 o, float3 inv, float3 lo, float3 hi, out float tn, out float tf, out int ax) {
    float3 a = (lo - o) * inv, b = (hi - o) * inv;
    float3 mn = min(a, b), mx = max(a, b);
    tn = max(mn.x, max(mn.y, mn.z));
    tf = min(mx.x, min(mx.y, mx.z));
    ax = mn.x >= mn.y && mn.x >= mn.z ? 0 : (mn.y >= mn.z ? 1 : 2);
    return tn <= tf;
}

// The masked texture of the face of box [lo, hi) on axis `ax` (its high side when `high`) at point p on it: the first
// content of the micro column under p, walked at most `cap` deep (the face's flattening error + 1: deeper there is
// nothing, so a hole costs that, not the box's depth). True and q / cell set on a hit.
bool faceWalk(RegionGpu r, int3 lo, int3 hi, int ax, bool high, float3 p, int cap, inout uint steps, out int3 qHit,
              out uint cellHit) {
    qHit = int3(0, 0, 0);
    cellHit = 0u;
    int a1 = (ax + 1) % 3, a2 = (ax + 2) % 3;
    int u = clamp(int(floor(p[a1])), lo[a1], hi[a1] - 1), v = clamp(int(floor(p[a2])), lo[a2], hi[a2] - 1);
    int n = min(hi[ax] - lo[ax], cap);
    int lastVox = -1;
    uint cell = 0u;
    uint2 own = uint2(0u, 0u);
    for (int k = 0; k < n; k++) {
        int3 q = int3(0, 0, 0);
        q[ax] = high ? hi[ax] - 1 - k : lo[ax] + k;
        q[a1] = u;
        q[a2] = v;
        int vox = q[ax] >> 2;
        if (vox != lastVox) {
            lastVox = vox;
            cell = voxelCell(r, q >> 2, own);
            steps++;
        }
        if (cell == 0u) continue;
        if (microFilled(r, cell, own, q)) {
            qHit = q;
            cellHit = cell;
            return true;
        }
    }
    return false;
}

// a node drawn whole: the face the ray enters by; through a hole there, the face it leaves by (seen from outside)
bool drawBox(RegionGpu r, uint4 n, float3 o, float3 d, float3 inv, int3 lo, int3 hi, int ax, float te, float u1,
             float3 dir, inout TraceResult res) {
    bool high = d[ax] < 0.0;
    uint code = (n[ax] >> (high ? 28u : 24u)) & 15u;
    int3 q;
    uint cell;
    if (faceWalk(r, lo, hi, ax, high, o + d * te, int(G_ERR[code]) + 1, res.steps, q, cell)) {
        groupHit(res, te, cell, ax, dir, q);
        return true;
    }
    int ax2 = 0;
    float best = 1e30;
    for (int a = 0; a < 3; a++) {
        if (abs(d[a]) < 1e-12) continue;
        float e = (float(d[a] > 0.0 ? hi[a] : lo[a]) - o[a]) * inv[a];
        if (e < best) {
            best = e;
            ax2 = a;
        }
    }
    if (best > u1) return false;  // it leaves past this chord: the next chord opens the box from inside
    bool high2 = d[ax2] > 0.0;
    uint code2 = (n[ax2] >> (high2 ? 28u : 24u)) & 15u;
    if (faceWalk(r, lo, hi, ax2, high2, o + d * best, int(G_ERR[code2]) + 1, res.steps, q, cell)) {
        groupHit(res, best, cell, ax2, dir, q);
        return true;
    }
    return false;
}

// Through a voxel leaf: voxel by voxel from te, micro-voxel by micro-voxel in shaped blocks.
bool stepLeaf(RegionGpu r, float3 o, float3 d, float3 inv, int3 lo, int3 hi, float te, float tEnd, int ax0, float3 dir,
              inout TraceResult res) {
    int3 vlo = lo >> 2, vhi = (hi + 3) >> 2;
    float3 p = o + d * te;
    int3 v = clamp(int3(floor(p * 0.25)), vlo, vhi - 1);
    int3 stepV = int3(d.x > 0.0 ? 1 : -1, d.y > 0.0 ? 1 : -1, d.z > 0.0 ? 1 : -1);
    float3 tDelta = abs(inv) * 4.0;
    float3 tNext = (float3(v * 4) + float3(d.x > 0.0 ? 4.0 : 0.0, d.y > 0.0 ? 4.0 : 0.0, d.z > 0.0 ? 4.0 : 0.0) - o) * inv;
    tNext = float3(abs(d.x) < 1e-12 ? 1e30 : tNext.x, abs(d.y) < 1e-12 ? 1e30 : tNext.y, abs(d.z) < 1e-12 ? 1e30 : tNext.z);
    float t = te;
    // (a chord that starts inside the leaf enters its first voxel by no face: the dominant axis stands in)
    int axis = ax0 >= 0 ? ax0 : (abs(d.x) >= abs(d.y) && abs(d.x) >= abs(d.z) ? 0 : (abs(d.y) >= abs(d.z) ? 1 : 2));
    for (int it = 0; it < 48; it++) {
        uint2 own;
        uint cell = voxelCell(r, v, own);
        res.steps++;
        if (cell != 0u) {
            bool full = (cell & (G_CELL_SHAPED | G_CELL_MASKED)) == 0u
                     || ((cell & G_CELL_MASKED) == 0u && shapeOf(cell & G_CELL_MATERIAL) == 0u);
            if (full) {
                int3 q = clamp(int3(floor(o + d * t)), v * 4, v * 4 + 3);
                groupHit(res, t, cell, axis, dir, q);
                return true;
            }
            // micro-voxels inside this voxel, until it leaves it
            float tv = min(tNext.x, min(tNext.y, tNext.z));
            float3 pm = o + d * t;
            int3 m = clamp(int3(floor(pm)) - v * 4, int3(0, 0, 0), int3(3, 3, 3));
            float3 mNext = (float3(v * 4 + m) + float3(d.x > 0.0 ? 1.0 : 0.0, d.y > 0.0 ? 1.0 : 0.0, d.z > 0.0 ? 1.0 : 0.0) - o) * inv;
            mNext = float3(abs(d.x) < 1e-12 ? 1e30 : mNext.x, abs(d.y) < 1e-12 ? 1e30 : mNext.y, abs(d.z) < 1e-12 ? 1e30 : mNext.z);
            float3 mDelta = abs(inv);
            float tm = t;
            int am = axis;
            for (int k = 0; k < 12; k++) {
                int3 q = v * 4 + m;
                if (microFilled(r, cell, own, q)) {
                    groupHit(res, tm, cell, am, dir, q);
                    return true;
                }
                int a = mNext.x <= mNext.y && mNext.x <= mNext.z ? 0 : (mNext.y <= mNext.z ? 1 : 2);
                tm = mNext[a];
                if (tm > tEnd) return false;
                m[a] += stepV[a];
                if (m[a] < 0 || m[a] > 3) break;
                mNext[a] += mDelta[a];
                am = a;
            }
        }
        int a = tNext.x <= tNext.y && tNext.x <= tNext.z ? 0 : (tNext.y <= tNext.z ? 1 : 2);
        t = tNext[a];
        if (t > tEnd) return false;
        v[a] += stepV[a];
        if (v[a] < vlo[a] || v[a] >= vhi[a]) return false;
        tNext[a] += tDelta[a];
        axis = a;
    }
    return false;
}

// One region's groups along the chord ro + rd u, u in [u0, u1] (region voxel units, as traceRegion takes them).
bool traceGroups(RegionGpu r, float3 ro, float3 rd, float u0, float u1, float3 dir, inout TraceResult res) {
    float3 o = ro * 4.0, d = rd * 4.0;  // micro-voxels
    float3 inv = float3(abs(d.x) > 1e-12 ? 1.0 / d.x : 1e30, abs(d.y) > 1e-12 ? 1.0 / d.y : 1e30,
                        abs(d.z) > 1e-12 ? 1.0 / d.z : 1e30);
    float microBlocks = 0.25 * float(1 << r.origin.w);
    // a node is drawn when its error (blocks) is under tol x the pixel's footprint at the distance it is entered
    float drawK = gGroupTol * gGroupPa / microBlocks;
    uint base = r.groups.x;
    uint stack[G_STACK];
    int sp = 0;
    uint node = 0u;
    for (int it = 0; it < 4096; it++) {
        uint4 n = groupData[base + node];
        int3 lo = int3(int(n.x & 0xFFFu), int(n.y & 0xFFFu), int(n.z & 0xFFFu));
        int3 hi = int3(int((n.x >> 12) & 0xFFFu), int((n.y >> 12) & 0xFFFu), int((n.z >> 12) & 0xFFFu));
        float tn, tf;
        int ax;
        bool visit = gSlab(o, inv, float3(lo), float3(hi), tn, tf, ax) && tf >= u0 && tn <= u1;
        if (visit) {
            res.steps++;
            float te = max(tn, u0);
            uint type = n.w >> 30;
            bool inside = tn < u0;  // the chord starts in it: its faces are behind
            uint code = (n[ax] >> (d[ax] < 0.0 ? 28u : 24u)) & 15u;
            bool fine = G_ERR[code] <= drawK * (gChordT + te);
            // drawn whole: a flat leaf, or any node whose error is under the pixel (not one the chord starts in);
            // stepped through: a leaf otherwise; opened: an inner node otherwise
            bool draw = !inside && (type == G_FLAT || fine);
            if (draw) {
                if (drawBox(r, n, o, d, inv, lo, hi, ax, te, u1, dir, res)) return true;
            } else if (type != G_INNER) {
                if (stepLeaf(r, o, d, inv, lo, hi, te, min(tf, u1), inside ? -1 : ax, dir, res)) return true;
            } else {
                // open it: the nearer child next, the farther on the stack
                uint c = base + (n.w & G_CHILD);
                uint4 a = groupData[c], b = groupData[c + 1u];
                float ta0, ta1, tb0, tb1;
                int axa, axb;
                bool va = gSlab(o, inv, float3(int3(int(a.x & 0xFFFu), int(a.y & 0xFFFu), int(a.z & 0xFFFu))),
                                float3(int3(int((a.x >> 12) & 0xFFFu), int((a.y >> 12) & 0xFFFu), int((a.z >> 12) & 0xFFFu))),
                                ta0, ta1, axa) && ta1 >= u0 && ta0 <= u1;
                bool vb = gSlab(o, inv, float3(int3(int(b.x & 0xFFFu), int(b.y & 0xFFFu), int(b.z & 0xFFFu))),
                                float3(int3(int((b.x >> 12) & 0xFFFu), int((b.y >> 12) & 0xFFFu), int((b.z >> 12) & 0xFFFu))),
                                tb0, tb1, axb) && tb1 >= u0 && tb0 <= u1;
                uint rel = n.w & G_CHILD;
                if (va && vb) {
                    bool aFirst = ta0 <= tb0;
                    if (sp < G_STACK) stack[sp++] = aFirst ? rel + 1u : rel;
                    node = aFirst ? rel : rel + 1u;
                    continue;
                }
                if (va || vb) {
                    node = va ? rel : rel + 1u;
                    continue;
                }
            }
        }
        if (sp == 0) break;
        node = stack[--sp];
    }
    return false;
}
