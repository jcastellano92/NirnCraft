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
//  per region     (RegionGpu.groups) its nodes from x (uint4): x / y / z = lo | (hi - 1) << 12 (quarter micro-voxels,
//                 region-local: the content's box) | low face's error code << 24 | high face's << 28 (micro-voxels),
//                 w = type << 30 | holes << 27 | largest error code << 23 | first child (relative); its brick
//                 columns from y (words): 64 x 64 of slot offset | first row << 18 | rows << 24; its slots from z (a
//                 brick's first word + 1); its bricks from w: occupancy (2 words), then the occupied voxels' cells of 16
//                 bits in order, two to a word (material | 0x8000: takes its shape)

static const uint G_INNER = 0u;
static const uint G_FLAT = 1u;
static const uint G_VOXELS = 2u;
static const uint G_CHILD = 0x7FFFFFu;  // (groups.hpp CHILD_MASK; bits 23-29: largest error code, holes per axis)
static const int G_STACK = 24;
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
static uint gAblate = 0u;  // ATLAS_ABLATE (measurements)
static bool gSkipWater = false;  // a second trace through translucent water: water voxels are not there

uint gWord(uint w) {
    uint4 q = groupData[w >> 2];
    uint k = w & 3u;
    return k == 0u ? q.x : (k == 1u ? q.y : (k == 2u ? q.z : q.w));
}

// the cell (material | G_CELL_SHAPED | G_CELL_MASKED, 0 = nothing a ray can meet) of region voxel v (0..255 each);
// a masked one's own micro-voxels in mask
// the last brick looked up (a walk or a leaf's steps stay in one or two): its region and coordinates, where it is
// (0: no brick there), its occupancy and whether it carries masks
static uint gBrickRegion = 0xFFFFFFFFu;
static int gBrickKey = -1;
static uint gBrickAt = 0u;
static uint2 gBrickOcc = uint2(0u, 0u);
static bool gBrickMasks = false;

uint voxelCell(RegionGpu r, int3 v, out uint2 mask, out uint coverAt) {
    mask = uint2(0u, 0u);
    coverAt = 0u;
    int key = (v.x >> 2) | ((v.y >> 2) << 6) | ((v.z >> 2) << 12);
    if (key != gBrickKey || r.groups.w != gBrickRegion) {
        gBrickKey = key;
        gBrickRegion = r.groups.w;
        gBrickAt = 0u;
        uint e = gWord(r.groups.y + uint((v.z >> 2) * 64 + (v.x >> 2)));
        int rows = int(e >> 24);
        int by = (v.y >> 2) - int((e >> 18) & 63u);
        uint b = by < 0 || by >= rows ? 0u : gWord(r.groups.z + (e & 0x3FFFFu) + uint(by));
        if (b != 0u) {
            // the brick: its occupancy (and with G_SLOT_MASKS which voxels carry their own micro-voxels), then the
            // cells of the occupied voxels in order, then the masked ones' micro-voxels
            gBrickMasks = (b & G_SLOT_MASKS) != 0u;
            gBrickAt = r.groups.w + (b & ~G_SLOT_MASKS) - 1u;
            gBrickOcc = uint2(gWord(gBrickAt), gWord(gBrickAt + 1u));
        }
    }
    if (gBrickAt == 0u) return 0u;
    bool masks = gBrickMasks;
    uint at = gBrickAt;
    uint i = uint((v.x & 3) + 4 * ((v.z & 3) + 4 * (v.y & 3)));
    uint lo = gBrickOcc.x, hi = gBrickOcc.y;
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
        uint m = at + head + ((cells + 1u) >> 1) + mrank * 3u;  // (groups.hpp MASKED_WORDS)
        mask = uint2(gWord(m), gWord(m + 1u));
        uint table = gWord(m + 2u);
        coverAt = table != 0u ? at + table : 0u;
    }
    return cell;
}

// the shape of a material: its first word in groupData, or 0 (a full cube in its palette colour)
static uint4 gHead = uint4(0u, 0u, 0u, 0u);  // groupData[0], loaded once a ray (farMain)
uint shapeOf(uint mat) {
    uint4 h = gHead;
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

// A voxel as the micro-voxel tests need it, fetched once: its cell, its micro-voxels (its own, its material's shape,
// or all of them) and where its cut-out alpha is (0: none)
struct Vox {
    uint cell;
    uint2 bits;
    uint alphaAt;
    uint coverAt;  // a masked voxel's face mask table (groups.hpp FaceMasks, MASKED_WORDS), 0: none
};

Vox voxelAt(RegionGpu r, int3 v) {
    Vox x;
    x.alphaAt = 0u;
    x.cell = voxelCell(r, v, x.bits, x.coverAt);
    if (gSkipWater && x.cell != 0u && uint(palette[x.cell & G_CELL_MATERIAL].emisClass.y) == 2u) x.cell = 0u;
    if ((x.cell & G_CELL_MASKED) != 0u || x.cell == 0u) return x;
    x.bits = uint2(0xFFFFFFFFu, 0xFFFFFFFFu);
    if ((x.cell & G_CELL_SHAPED) == 0u) return x;
    uint sh = shapeOf(x.cell & G_CELL_MATERIAL);
    if (sh == 0u) return x;
    uint4 head = groupData[sh >> 2];  // mask lo, mask hi, flags, mean (a shape starts on a uint4)
    x.bits = head.xy;
    if ((head.z & 1u) != 0u) x.alphaAt = sh + 100u;
    return x;
}

bool voxFull(Vox x) { return x.bits.x == 0xFFFFFFFFu && x.bits.y == 0xFFFFFFFFu && x.alphaAt == 0u && x.coverAt == 0u; }

// Whether region micro-voxel q of voxel x has content: 0 none, 1 solid, 2 only partly (a coarse voxel's micro-voxel with
// sub-voxels, groups.hpp SubVoxels: `masks` then holds its 4 x 4 x 4 quarters, low and high word, and what a ray meets of
// it is them, gHullRay / gHullColumn); the cut-out hashed in world space
uint microFilled(RegionGpu r, Vox x, int3 q, out uint3 masks) {
    masks = uint3(0u, 0u, 0u);
    int3 m = q & 3;
    uint bit = uint(m.x + 4 * (m.z + 4 * m.y));
    if ((((bit < 32u ? x.bits.x >> bit : x.bits.y >> (bit - 32u))) & 1u) == 0u) return 0u;
    if (x.coverAt != 0u) {
        uint plo = gWord(x.coverAt), phi = gWord(x.coverAt + 1u);
        bool partial = bit < 32u ? ((plo >> bit) & 1u) != 0u : ((phi >> (bit - 32u)) & 1u) != 0u;
        if (!partial) return 1u;
        uint rank = bit < 32u ? countbits(plo & ((1u << bit) - 1u)) : countbits(plo) + countbits(phi & ((1u << (bit - 32u)) - 1u));
        uint w = x.coverAt + 2u + rank * 2u;
        masks = uint3(gWord(w), gWord(w + 1u), 0u);
        return 2u;
    }
    if (x.alphaAt == 0u) return 1u;
    uint a = (gWord(x.alphaAt + (bit >> 2)) >> ((bit & 3u) * 8u)) & 255u;
    if (a == 255u) return 1u;
    return (cutHash(r.origin.xyz * 4 + q) >> 24) < a ? 1u : 0u;
}

// A partly covered micro-voxel's content as rays meet it: its 4 x 4 x 4 sub-voxels (bit x + 4 (z + 4 y) of the two
// words), marched by the ray (gHullRay) or, in a drawn node's face walk, the column under the texel (gHullColumn).
bool gHullCell(uint3 m, int3 c) {
    uint b = uint(c.x + 4 * (c.z + 4 * c.y));
    return ((b < 32u ? m.x >> b : m.y >> (b - 32u)) & 1u) != 0u;
}
// the ray o + d t through the hull of micro-voxel q between tIn and tOut: the first quarter it meets (t, its axis)
bool gHullRay(uint3 m, int3 q, float3 o, float3 d, float tIn, float tOut, inout float t, inout int axis) {
    float3 oq = (o - float3(q)) * 4.0, dq = d * 4.0;  // in quarters of this micro-voxel
    float3 p = oq + dq * tIn;
    int3 c = clamp(int3(floor(p)), int3(0, 0, 0), int3(3, 3, 3));
    int3 st = int3(dq.x > 0.0 ? 1 : -1, dq.y > 0.0 ? 1 : -1, dq.z > 0.0 ? 1 : -1);
    float3 inv = float3(abs(dq.x) > 1e-12 ? 1.0 / dq.x : 1e30, abs(dq.y) > 1e-12 ? 1.0 / dq.y : 1e30,
                        abs(dq.z) > 1e-12 ? 1.0 / dq.z : 1e30);
    float3 nx = (float3(c) + float3(dq.x > 0.0 ? 1.0 : 0.0, dq.y > 0.0 ? 1.0 : 0.0, dq.z > 0.0 ? 1.0 : 0.0) - oq) * inv;
    nx = float3(abs(dq.x) < 1e-12 ? 1e30 : nx.x, abs(dq.y) < 1e-12 ? 1e30 : nx.y, abs(dq.z) < 1e-12 ? 1e30 : nx.z);
    float3 dl = abs(inv);
    float tc = tIn;
    int ax = axis;
    for (int k = 0; k < 10; k++) {
        if (gHullCell(m, c)) {
            t = tc;
            axis = ax;
            return true;
        }
        int a = nx.x <= nx.y && nx.x <= nx.z ? 0 : (nx.y <= nx.z ? 1 : 2);
        tc = nx[a];
        if (tc > tOut) return false;
        c[a] += st[a];
        if (c[a] < 0 || c[a] > 3) return false;
        nx[a] += dl[a];
        ax = a;
    }
    return false;
}
// the hull's column along axis ax under point p (micro-voxels): whether any quarter of it is there
bool gHullColumn(uint3 m, int3 q, int ax, float3 p) {
    int3 c = clamp(int3(floor((p - float3(q)) * 4.0)), int3(0, 0, 0), int3(3, 3, 3));
    for (int k = 0; k < 4; k++) {
        c[ax] = k;
        if (gHullCell(m, c)) return true;
    }
    return false;
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

// The surface card of face f of world block b (atlas_set_surface_cards): its first word, or 0 (none).
uint cardOf(int3 b, int f) {
    uint head = groupData[0].w;
    if (head == 0u) return 0u;
    uint4 hd = groupData[head];
    uint mask = hd.x - 1u;
    uint h = cutHash(int3(b.x, b.y, b.z ^ int(uint(f) * 0x9E3779B1u))) & mask;
    for (int k = 0; k < 64; k++) {
        uint4 e = groupData[hd.y + h];
        if (e.w == 0u) return 0u;
        if (int(e.x) == b.x && int(e.y) == b.y && int(e.z) == b.z && (e.w & 7u) == uint(f + 1))
            return hd.z + (e.w >> 3) * 341u;
        h = (h + 1u) & mask;
    }
    return 0u;
}

// a card's colour (linear) at (u, v) in 0..1 over a footprint of fp blocks: its mip chain (16, 8, 4, 2, 1), blended
// between the two nearest levels as a trilinear sampler would
float3 cardColour(uint at, float u, float v, float fp) {
    float lv = clamp(log2(max(fp * 16.0, 1.0)), 0.0, 4.0);
    int l0 = int(lv), l1 = min(4, l0 + 1);
    static const uint MIP_AT[5] = { 0u, 256u, 320u, 336u, 340u };
    int n0 = 16 >> l0, n1 = 16 >> l1;
    uint c0 = gWord(at + MIP_AT[l0] + uint(min(int(u * float(n0)), n0 - 1) + n0 * min(int(v * float(n0)), n0 - 1)));
    uint c1 = gWord(at + MIP_AT[l1] + uint(min(int(u * float(n1)), n1 - 1) + n1 * min(int(v * float(n1)), n1 - 1)));
    return lerp(srgbLinear(c0), srgbLinear(c1), lv - float(l0));
}

// a card's coverage (alpha, 0..1) at (u, v) over a footprint of fp blocks: its mips' top bytes, blended as cardColour
float cardAlpha(uint at, float u, float v, float fp) {
    float lv = clamp(log2(max(fp * 16.0, 1.0)), 0.0, 4.0);
    int l0 = int(lv), l1 = min(4, l0 + 1);
    static const uint MIP_AT[5] = { 0u, 256u, 320u, 336u, 340u };
    int n0 = 16 >> l0, n1 = 16 >> l1;
    uint c0 = gWord(at + MIP_AT[l0] + uint(min(int(u * float(n0)), n0 - 1) + n0 * min(int(v * float(n0)), n0 - 1)));
    uint c1 = gWord(at + MIP_AT[l1] + uint(min(int(u * float(n1)), n1 - 1) + n1 * min(int(v * float(n1)), n1 - 1)));
    return lerp(float(c0 >> 24), float(c1 >> 24), lv - float(l0)) / 255.0;
}

// a hit: what the shading needs (the old path's TraceResult, plus the texel's albedo)
void groupHit(float t, uint cell, int axis, float3 dir, int3 q) {
    gTr.t = t;
    gTr.mat = cell & G_CELL_MATERIAL;
    gTr.axis = axis;
    int f = dir[axis] > 0.0 ? G_FACE_LOW[axis] : G_FACE_HIGH[axis];
    gTr.albedo = cellAlbedo(cell, f, q);
    gTr.hasAlbedo = 1u;
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
bool faceWalk(RegionGpu r, int3 lo, int3 hi, int ax, bool high, float3 p, float3 o, float3 d, int cap, inout uint steps, out int3 qHit,
              out uint cellHit) {
    qHit = int3(0, 0, 0);
    cellHit = 0u;
    int a1 = (ax + 1) % 3, a2 = (ax + 2) % 3;
    int u = clamp(int(floor(p[a1])), lo[a1], hi[a1] - 1), v = clamp(int(floor(p[a2])), lo[a2], hi[a2] - 1);
    int n = min(hi[ax] - lo[ax], cap);
    int lastVox = -1;
    Vox vx;
    vx.cell = 0u;
    vx.bits = uint2(0u, 0u);
    vx.alphaAt = 0u;
    for (int k = 0; k < n; k++) {
        int3 q = int3(0, 0, 0);
        q[ax] = high ? hi[ax] - 1 - k : lo[ax] + k;
        q[a1] = u;
        q[a2] = v;
        int vox = q[ax] >> 2;
        if (vox != lastVox) {
            lastVox = vox;
            vx = voxelAt(r, q >> 2);
            steps++;
        }
        if (vx.cell == 0u) {
            k += 3 - (high ? (3 - (q[ax] & 3)) : (q[ax] & 3));  // the rest of this empty voxel
            continue;
        }
        uint3 hm;
        uint f = microFilled(r, vx, q, hm);
        if (f == 1u || (f == 2u && gHullColumn(hm, q, ax, p))) {
            qHit = q;
            cellHit = vx.cell;
            return true;
        }
    }
    return false;
}

// a node's content box in micro-voxels (fractional: quarter micro-voxels), and the micro-voxels it touches
void gBox(uint4 n, out float3 flo, out float3 fhi) {
    flo = float3(float(n.x & 0xFFFu), float(n.y & 0xFFFu), float(n.z & 0xFFFu)) * 0.25;
    fhi = float3(float(((n.x >> 12) & 0xFFFu) + 1u), float(((n.y >> 12) & 0xFFFu) + 1u), float(((n.z >> 12) & 0xFFFu) + 1u)) * 0.25;
}

// a node drawn whole: the face the ray enters by; through a hole there, the face it leaves by (seen from outside)
bool drawBox(RegionGpu r, uint4 n, float3 o, float3 d, float3 inv, int3 lo, int3 hi, float3 flo, float3 fhi, int ax,
             float te, float u1, float3 dir) {
    bool high = d[ax] < 0.0;
    uint code = (n[ax] >> (high ? 28u : 24u)) & 15u;
    int3 q;
    uint cell;
    if ((gAblate & 128u) != 0u) {  // (ATLAS_ABLATE 128, measurements: the box itself, no texel walk)
        gTr.t = te;
        gTr.mat = 1u;
        gTr.axis = ax;
        gTr.albedo = float3(0.5, 0.5, 0.5);
        gTr.hasAlbedo = 1u;
        return true;
    }
    if (faceWalk(r, lo, hi, ax, high, o + d * te, o, d, int(G_ERR[code]) + 1, gTr.steps, q, cell)) {
        groupHit(te, cell, ax, dir, q);
        return true;
    }
    int ax2 = 0;
    float best = 1e30;
    for (int a = 0; a < 3; a++) {
        if (abs(d[a]) < 1e-12) continue;
        float e = ((d[a] > 0.0 ? fhi[a] : flo[a]) - o[a]) * inv[a];
        if (e < best) {
            best = e;
            ax2 = a;
        }
    }
    if (best > u1) return false;  // it leaves past this chord: the next chord opens the box from inside
    bool high2 = d[ax2] > 0.0;
    uint code2 = (n[ax2] >> (high2 ? 28u : 24u)) & 15u;
    if (faceWalk(r, lo, hi, ax2, high2, o + d * best, o, d, int(G_ERR[code2]) + 1, gTr.steps, q, cell)) {
        groupHit(best, cell, ax2, dir, q);
        return true;
    }
    return false;
}

// Through a voxel leaf: voxel by voxel from te, micro-voxel by micro-voxel in shaped blocks.
bool stepLeaf(RegionGpu r, float3 o, float3 d, float3 inv, int3 lo, int3 hi, float te, float tEnd, int ax0, float3 dir) {
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
        Vox vx = voxelAt(r, v);
        uint cell = vx.cell;
        gTr.steps++;
        if (cell != 0u) {
            bool full = voxFull(vx);
            if (full) {
                int3 q = clamp(int3(floor(o + d * t)), v * 4, v * 4 + 3);
                groupHit(t, cell, axis, dir, q);
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
                uint3 hm;
                uint f = microFilled(r, vx, q, hm);
                int a = mNext.x <= mNext.y && mNext.x <= mNext.z ? 0 : (mNext.y <= mNext.z ? 1 : 2);
                if (f == 1u) {
                    groupHit(tm, cell, am, dir, q);
                    return true;
                }
                if (f == 2u) {
                    float th = tm;
                    int ah = am;
                    if (gHullRay(hm, q, o, d, tm, min(mNext[a], tEnd), th, ah)) {
                        groupHit(th, cell, ah, dir, q);
                        return true;
                    }
                }
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
bool traceGroups(RegionGpu r, float3 ro, float3 rd, float u0, float u1, float3 dir) {
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
        float3 flo, fhi;
        gBox(n, flo, fhi);
        int3 lo = int3(floor(flo)), hi = int3(ceil(fhi));  // the micro-voxels it touches
        float tn, tf;
        int ax;
        bool visit = gSlab(o, inv, flo, fhi, tn, tf, ax) && tf >= u0 && tn <= u1;
        if (visit) {
            gTr.steps++;
            float te = max(tn, u0);
            uint type = n.w >> 30;
            // the chord starts in it: its faces are behind. (A node whose face lies on the region's face, entered there:
            // tn and u0 are the same plane in float and may land either side; taken as inside it was stepped with the
            // dominant axis for its entry face, and a chunk's wall on a region border lost its card and its normal.)
            bool inside = tn < u0 - 1e-4 * max(1.0, u0);
            uint code = (n[ax] >> (d[ax] < 0.0 ? 28u : 24u)) & 15u;
            // the entry face's error, if that face has no holes; else every face's (groups.hpp: through a hole a ray
            // reaches content the entry face's error says nothing about)
            if (((n.w >> (27u + uint(ax))) & 1u) != 0u) code = (n.w >> 23u) & 15u;
            bool fine = G_ERR[code] <= drawK * (gChordT + te);
            // drawn whole: a flat leaf, or any node whose error is under the pixel (not one the chord starts in);
            // stepped through: a leaf otherwise; opened: an inner node otherwise
            // (ATLAS_ABLATE 32768, measurements: never draw a node whole for its error, open or step it)
            // A node the ray leaves past this chord's end is opened, never drawn: drawn, a ray entering it through a
            // hole in its face skipped everything inside it for the rest of the chord, and the next chord started
            // inside it (a house seen through its own wall, the lit interior behind: the far pass's see-through holes,
            // moving with the chords' dithered starts). A flat leaf's faces are covered: its entry face always hits.
            bool fits = tf <= u1 || type == G_FLAT;
            // ... nor one about as small as the pixel: drawn whole, its content is flattened onto the face the ray
            // enters by, an error the size of the node itself (a far tree or chunk of a pixel or two drew 1.3 - 1.4
            // times its area); stepped, it is its own (a few rays meet it, so it costs little)
            float3 ext = fhi - flo;
            fits = fits && (type == G_FLAT || max(ext.x, max(ext.y, ext.z)) * microBlocks > 2.0 * gGroupPa * (gChordT + te));
            bool draw = !inside && (type == G_FLAT || (fine && fits && (gAblate & 32768u) == 0u) || (gAblate & 64u) != 0u);
            if (draw) {
                if (drawBox(r, n, o, d, inv, lo, hi, flo, fhi, ax, te, u1, dir)) return true;
            } else if (type != G_INNER) {
                if (stepLeaf(r, o, d, inv, lo, hi, te, min(tf, u1), inside ? -1 : ax, dir)) return true;
            } else {
                // open it: the nearer child next, the farther on the stack
                uint c = base + (n.w & G_CHILD);
                uint4 a = groupData[c], b = groupData[c + 1u];
                float ta0, ta1, tb0, tb1;
                int axa, axb;
                float3 alo, ahi, blo, bhi;
                gBox(a, alo, ahi);
                gBox(b, blo, bhi);
                bool va = gSlab(o, inv, alo, ahi, ta0, ta1, axa) && ta1 >= u0 && ta0 <= u1;
                bool vb = gSlab(o, inv, blo, bhi, tb0, tb1, axb) && tb1 >= u0 && tb0 <= u1;
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
