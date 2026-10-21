// Micro-voxel bricks (docs/ATLAS-WORLD-STORE.md section 4; native/src/bricks.hpp has the layout and the voxeliser): a
// level-0 voxel whose material has a brick (a door, a rail, redstone, a lectern, a torch, a crop: every block whose
// model is not a full cube) is met by its 16 x 16 x 16 micro-voxels (1/16 block): the ray walks them from where it
// entered the voxel; the first filled one is the hit (its face the normal, its palette entry the colour and emission).
// Primary rays (brickHit) and secondary rays (brickMisses) alike. Read only; every walk bounded (a ray crosses at most
// 46 micro-voxels of a brick).
//
// Included by atlas_far.slang after atlas_groups.slang (srgbLinear); declared after every binding of atlas_far.slang
// (the CPU target's globals follow declaration order: gpu_layout.hpp FarGlobals ends with brickData).

[[vk::binding(40, 0)]] StructuredBuffer<uint> brickData;

// Material mat's brick (its first word in brickData), 0 when it has none; an animated one's frame by the frame's time
// (frame.sunDir.w, seconds: 20 game ticks a second)
uint brickOf(uint mat) {
    uint n = brickData[0];
    uint b = mat < n ? brickData[1u + mat] : 0u;
    if ((b & 0x80000000u) != 0u) {
        b &= 0x7FFFFFFFu;
        uint h = brickData[b];
        uint frames = max(h & 0xFFFFu, 1u), ticks = max(h >> 16, 1u);
        uint f = uint(max(frame.sunDir.w, 0.0) * 20.0 / float(ticks)) % frames;
        b = brickData[b + 1u + f];
    }
    return b;
}

struct BrickHit {
    bool hit;
    float t;        // along the ray from where it entered the voxel (the caller's units: blocks)
    int axis;       // the axis of the micro-voxel face it entered by
    float3 albedo;  // linear
    float emission; // 0..1 (the material's emission at this micro-voxel's brightness)
};

// Whether micro-voxel m (0..15 each) of brick b is filled; rank: its index among the brick's filled ones
bool brickCell(uint b, int3 m, uint gLo, uint gHi, out uint rank) {
    rank = 0u;
    uint g = uint((m.x >> 2) + 4 * ((m.z >> 2) + 4 * (m.y >> 2)));
    bool on = g < 32u ? ((gLo >> g) & 1u) != 0u : ((gHi >> (g - 32u)) & 1u) != 0u;
    if (!on) return false;
    uint gr = g < 32u ? countbits(gLo & ((1u << g) - 1u)) : countbits(gLo) + countbits(gHi & ((1u << (g - 32u)) - 1u));
    uint at = b + 4u + 3u * gr;
    uint lo = brickData[at], hi = brickData[at + 1u];
    uint i = uint((m.x & 3) + 4 * ((m.z & 3) + 4 * (m.y & 3)));
    bool f = i < 32u ? ((lo >> i) & 1u) != 0u : ((hi >> (i - 32u)) & 1u) != 0u;
    if (!f) return false;
    rank = brickData[at + 2u] + (i < 32u ? countbits(lo & ((1u << i) - 1u)) : countbits(lo) + countbits(hi & ((1u << (i - 32u)) - 1u)));
    return true;
}

// The ray l + d t (l: where it entered the voxel, voxel-local 0..1; d in blocks per t) through brick b, from the voxel
// face it entered by (axis); the first filled micro-voxel it meets, or hit = false when it leaves the voxel first.
[noinline]
BrickHit brickHit(uint b, float3 l, float3 d, int axis) {
    BrickHit r;
    r.hit = false;
    r.t = 0.0;
    r.axis = axis;
    r.albedo = float3(0, 0, 0);
    r.emission = 0.0;
    uint gLo = brickData[b], gHi = brickData[b + 1u];
    float3 o = clamp(l, float3(0, 0, 0), float3(1, 1, 1)) * 16.0;
    float3 dq = d * 16.0;
    int3 m = clamp(int3(floor(o)), int3(0, 0, 0), int3(15, 15, 15));
    int3 st = int3(dq.x > 0.0 ? 1 : -1, dq.y > 0.0 ? 1 : -1, dq.z > 0.0 ? 1 : -1);
    float3 inv = float3(abs(dq.x) > 1e-12 ? 1.0 / dq.x : 1e30, abs(dq.y) > 1e-12 ? 1.0 / dq.y : 1e30,
                        abs(dq.z) > 1e-12 ? 1.0 / dq.z : 1e30);
    float3 nx = (float3(m) + float3(dq.x > 0.0 ? 1.0 : 0.0, dq.y > 0.0 ? 1.0 : 0.0, dq.z > 0.0 ? 1.0 : 0.0) - o) * inv;
    nx = float3(abs(dq.x) > 1e-12 ? nx.x : 1e30, abs(dq.y) > 1e-12 ? nx.y : 1e30, abs(dq.z) > 1e-12 ? nx.z : 1e30);
    float3 dl = abs(inv);
    float tc = 0.0;
    int ax = axis;
    for (int k = 0; k < 48; k++) {
        uint rank;
        if (brickCell(b, m, gLo, gHi, rank)) {
            uint head = brickData[b + 2u];
            uint ng = head & 0xFFFFu;
            uint w = brickData[b + 4u + 3u * ng + (rank >> 2)];
            uint pi = (w >> ((rank & 3u) * 8u)) & 255u;
            uint c = brickData[b + brickData[b + 3u] + pi];
            r.hit = true;
            r.t = tc;
            r.axis = ax;
            r.albedo = srgbLinear(c);
            r.emission = float(c >> 24) / 255.0;
            return r;
        }
        int a = nx.x <= nx.y && nx.x <= nx.z ? 0 : (nx.y <= nx.z ? 1 : 2);
        tc = nx[a];
        m[a] += st[a];
        if (m[a] < 0 || m[a] > 15) return r;
        nx[a] += dl[a];
        ax = a;
    }
    return r;
}

// Whether a secondary ray from voxel-local point l along d passes brick b without meeting a filled micro-voxel.
[noinline]
bool brickMisses(uint b, float3 l, float3 d) {
    BrickHit h = brickHit(b, l, d, 0);
    return !h.hit;
}

// ---- the grid's emitters as lights (gridLightMain) ------------------------------------------------------------------
// Every block that emits (a torch, a lantern, glowstone, lava, a lit furnace, a candle, NirnCraft's embers: any light
// level, brickData's light table) in the level-0 grid within GRID_LIGHT_R blocks of the eye (GRID_LIGHT_RY up and down)
// is a light this frame, found on the GPU from the grid itself (an edit lights or darkens at once, no server journal),
// in its own colour: each emitter a candidate where a 4 x 4 x 4 of blocks holds up to four, else one for them all
// (their centroid, their brightest level, their colours' mean), appended to lightCand as lightCullMain appends the index's records (kind 2: the record's
// own colour; record ids from GRID_LIGHT_ID up). The journal's records inside that box, where the grid holds the
// chunk, are left out (gridLightsCover): one light a lamp.
static const int GRID_LIGHT_R = 96;
static const int GRID_LIGHT_RY = 48;
static const uint GRID_LIGHT_ID = 0x00800000u;  // (+ 5 a 4 x 4 x 4: four single lights, one cluster)
static const uint GRID_LIGHT_THREADS = uint((2 * GRID_LIGHT_R / 4) * (2 * GRID_LIGHT_R / 4) * (2 * GRID_LIGHT_RY / 4));

// material mat's light word (bricks.hpp: rgb | level << 24), 0 none
uint brickLightOf(uint mat) {
    uint n = brickData[0];
    return mat < n ? brickData[1u + n + mat] : 0u;
}

// the box's first block (world): the eye's 4-block cell less the radii
int3 gridLightBase() {
    int3 c = int3(frame.camInt.x >> 2, frame.camInt.y >> 2, frame.camInt.z >> 2) * 4;
    return c - int3(GRID_LIGHT_R, GRID_LIGHT_RY, GRID_LIGHT_R);
}

// the level-0 chunk word holding world block wb (VG_UNLOADED outside the window)
uint gridChunkWord(int3 wb) {
    VgGrid g = vgGrid(0);
    int3 v = wb - g.origin;
    int3 nb = int3(g.X, g.Y, g.Z) * 16;
    if (v.x < 0 || v.y < 0 || v.z < 0 || v.x >= nb.x || v.y >= nb.y || v.z >= nb.z) return VG_UNLOADED;
    return vgChunks[vgCellG(g, v >> 4)];
}

// Whether a light of the index at camera-relative rp (lightCullMain's rx, ry, rz) is the grid's to give: inside the
// box, its chunk held by the grid, and the grid's lights on (brickData has a light table).
bool gridLightsCover(float3 rp) {
    if (!vgOn() || brickData[0] == 0u) return false;
    int3 wb = int3(floor(rp)) + frame.camInt.xyz;
    int3 lo = gridLightBase(), hi = lo + int3(2 * GRID_LIGHT_R, 2 * GRID_LIGHT_RY, 2 * GRID_LIGHT_R);
    if (any(wb < lo) || any(wb >= hi)) return false;
    return (gridChunkWord(wb) & 3u) != VG_UNLOADED;
}

// One light as a candidate, as lightCullMain appends the index's records (camera-relative to camInt; rid its record id)
[noinline]
void gridLightEmit(uint rid, float3 rp, float3 rgb, float level) {
    float d = length(rp);
    if (d > frame.params.y) return;
    float4 clip = frame.viewProj[0] * rp.x + frame.viewProj[1] * rp.y + frame.viewProj[2] * rp.z + frame.viewProj[3];
    float W = float(frame.dims.x), H = float(frame.dims.y);
    float pa = max(frame.params.w, 1e-5);
    float radius = 0.5;
    float sigma = max(0.5, 0.3 * radius / (max(d, 0.5) * pa));
    float T = exp(-d * frame.farLight.x);
    float halo = 1.5 + 8.0 * (1.0 - T);
    float intensity = level / 15.0;
    float range = 15.0 * intensity + radius + 1.0;
    float screen = max(W, H);
    float lit = d <= 1.6 * range ? screen : 1.6 * range / ((d - range) * pa);
    float reach = min(screen, max(3.0 * sigma + 3.0 * halo, lit));
    float px = W * 0.5, py = H * 0.5;
    if (clip.w > 1e-4) {
        px = (clip.x / clip.w * 0.5 + 0.5) * W;
        py = (0.5 - clip.y / clip.w * 0.5) * H;
    } else if (d > 1.6 * range) {
        return;  // behind the eye and too far to light anything in front of it
    } else {
        reach = screen;  // behind the eye but lighting what is around it: every tile
    }
    if (px + reach < 0.0 || py + reach < 0.0 || px - reach > W || py - reach > H) return;
    float score = intensity * lightFalloff(d);
    uint slotC;
    InterlockedAdd(lightList[0], 1u, slotC);
    if (slotC >= LIGHT_CAND_CAP) return;
    uint bin = lightBin(score);
    float k = 6.0 * score;
    lightCand[slotC * 4u] = float4(rp + frame.camFrac.xyz, sigma);
    lightCand[slotC * 4u + 1u] = float4(rgb * k, 2.0);  // (farLightsOn: rgb x k / (6 falloff) peaks at the intensity)
    lightCand[slotC * 4u + 2u] = float4(d, float(hashU(rid) & 1023u) / 1023.0, halo, 0.0);
    lightCand[slotC * 4u + 3u] = float4(px, py, reach, asfloat(bin | (rid << 8u)));
    InterlockedAdd(lightList[LL_HIST + bin], 1u);
}

[shader("compute")]
[numthreads(64, 1, 1)]
void gridLightMain(uint3 id : SV_DispatchThreadID) {
    uint i = id.x;
    if (i >= GRID_LIGHT_THREADS || !vgOn() || brickData[0] == 0u) return;
    uint nx = uint(2 * GRID_LIGHT_R / 4), ny = uint(2 * GRID_LIGHT_RY / 4);
    int3 cell = int3(int(i % nx), int((i / nx) % ny), int(i / (nx * ny)));
    int3 wb = gridLightBase() + cell * 4;  // the 4 x 4 x 4 blocks' first (aligned to 4: one grid block word)
    uint cw = gridChunkWord(wb);
    uint st = cw & 3u;
    if (st == VG_UNLOADED || st == VG_EMPTY) return;
    VgGrid g = vgGrid(0);
    int3 l = (wb - g.origin) & 15;
    int3 b = l >> 2;
    uint bi = uint(b.x + 4 * (b.z + 4 * b.y));
    bool uni = st == VG_UNIFORM;
    uint slot = cw >> 2;
    uint bw = uni ? 1u : vgBlocks[slot * 64u + bi];
    if ((bw & 1u) == 0u) return;
    uint64_t bits = uni ? ~uint64_t(0) : vgBits[slot * 64u + bi];
    // up to GRID_LIGHT_EACH emitters each a light of its own (a torch beside a lantern: two colours, two shadows);
    // more (a lava pool, a glowstone wall) one light at their centroid, their brightest level, their colours' mean
    float n = 0.0, level = 0.0;
    float3 centre = float3(0, 0, 0), rgb = float3(0, 0, 0);
    uint count = 0u;
    uint each[4] = { 0u, 0u, 0u, 0u };  // (bit q | its light word's index in the 4 x 4 x 4: q << 0, recovered below)
    for (uint q = 0u; q < 64u; q++) {
        if (((bits >> q) & 1) == 0) continue;
        int3 o = int3(int(q & 3u), int(q >> 4), int((q >> 2) & 3u));
        int3 lv = b * 4 + o;
        uint vi = uint(lv.x + 16 * (lv.z + 16 * lv.y));
        uint mat = uni ? (cw >> 2) & 0xFFFFu : (vgPool[slot * 2048u + (vi >> 1)] >> (16u * (vi & 1u))) & 0xFFFFu;
        uint lw = brickLightOf(mat);
        if (lw == 0u) continue;
        float lev = float(lw >> 24);
        if (count < 4u) each[count] = q | (mat << 8u);
        count++;
        n += lev;
        level = max(level, lev);
        centre += (float3(o) + 0.5) * lev;
        rgb += srgbLinear(lw) * lev;
    }
    if (count == 0u) return;
    float3 base = float3(wb - frame.camInt.xyz) - frame.camFrac.xyz;  // the 4 x 4 x 4's corner from the eye
    if (count <= 4u) {
        for (uint k = 0u; k < 4u; k++) {
            if (k >= count) break;
            uint q = each[k] & 255u;
            uint lw = brickLightOf(each[k] >> 8u);
            float3 c = srgbLinear(lw);
            c /= max(max(c.r, max(c.g, c.b)), 1e-6);
            float3 o = float3(float(q & 3u), float(q >> 4), float((q >> 2) & 3u)) + 0.5;
            gridLightEmit(GRID_LIGHT_ID + i * 5u + k, base + o, c, float(lw >> 24));
        }
        return;
    }
    centre /= n;
    rgb /= max(max(rgb.r, max(rgb.g, rgb.b)), 1e-6);
    gridLightEmit(GRID_LIGHT_ID + i * 5u + 4u, base + centre, rgb, level);
}

// ---- emission of a near hit ---------------------------------------------------------------------------------------
// What an emitter's surface adds where atlas's primary rays meet it (the unified field's near composite): every emitter
// (any light level: a redstone torch's head, a candle's flame, magma, lava, glowstone) glows as the far field's do
// (shadeSurfaceAmb: albedo x emission x 2), its emission its light level / 15 times 1 to 4 by its texels' brightness.
// Radiance, before the air.
float3 nearEmission(uint mat, float3 albedo) {
    MaterialGpu m = palette[mat];
    if (uint(m.emisClass.y) != 3u || m.emisClass.x <= 0.0) return float3(0, 0, 0);
    // (its whole surface glows by its texels' own colour, x4 where they are bright: lava orange all over, a torch's
    // flame bright and its dark stick next to nothing)
    float k = m.emisClass.x * (1.0 + 3.0 * smoothstep(0.35, 0.8, dot(albedo, float3(0.2126, 0.7152, 0.0722))));
    return albedo * (k * 2.0);
}
