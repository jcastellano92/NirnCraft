// atlas_lod: the two far-field finalists of docs/ATLAS-LOD.md (adaptive boxes, content-shaped groups) as one compute
// source, compiled twice like atlas_far:
//   slangc -target spirv -> Vulkan (real timings)
//   slangc -target cpp   -> the CPU backend (exact comparisons against atlas_lab's C++ tracers)
// One pixel per thread, one sample per frame (the frames are TAA's jitter: the host averages them). The rule is
// atlas_lab's, step for step: a node is drawn as one box with masked face textures when its flattening error is
// under the pixel's footprint there (times a dithered threshold), else its children are visited front to back; the
// first hit ends the ray. No recursion: the octree keeps one stack entry per level (the rest of a sorted child list),
// the binary groups one per level (the far child). Layouts: docs/ATLAS-LOD.md, "Slang port".
//
// Buffers (32-bit words, little-endian):
//  nodes   uint4 per node, both hierarchies:
//          x: lo.x | hi.x << 12 | mask << 24        (the content's tight box, micro-voxels, [lo, hi), 12 bits each)
//          y: lo.y | hi.y << 12 | err bits 0..7 << 24
//          z: lo.z | hi.z << 12 | err bits 8..11 << 24 | flags << 28
//          w: the first child
//          err: the flattening error in micro-voxels (an integer; 4095 = at least that)
//          octree: mask = the occupied child octants (k: x + (k & 1), y + (k >> 2), z + ((k >> 1) & 1) halves), the
//          children at w.. in octant order; flags & F_MICRO: the children are single micro-voxels, not stored (the
//          node is a 2^3 cube at lo & ~1: its octants are the micro-voxels; w unused)
//          groups: mask bit 0 = left, bit 1 = right; the present ones at w, w + 1; none: a leaf
//  grid    per block (x fastest, then z, then y): its brick, 0 = no content
//  bricks  uint4 per brick: the block's 4x4x4 occupancy (bit x + 4 * (z + 4 * y); leaves' cutout already hashed in)
//          lo, hi, its state, 0; brick 0 is empty
//  states  100 words per block state: mean RGB (r << 16 | g << 8 | b), tint kind (0 none, 1 grass, 2 foliage,
//          3 water), 2 unused, then 6 faces (down, up, north, south, west, east) x 16 texels ARGB (alpha 0: the
//          state's mean colour)

struct LodFrame {
    float4 eye;     // the camera in volume blocks; w: the tangent-plane size of one pixel
    float4 fwd;     // w: the threshold factor (pixels: atlas_lab tol=)
    float4 right;   // w: the fog's transmittance at the object (1: none)
    float4 up;      // w: 1 = fade hits towards the sky by that transmittance
    float4 light0;  // light factor per face: down, up, north, south
    float4 light1;  // west, east
    float4 sky;
    float4 tint1;   // grass (0..255)
    float4 tint2;   // foliage
    float4 tint3;   // water
    int4 dims;      // the volume in blocks; w: the root node (-1: nothing)
    int4 view;      // width, height, the pixel the view axis goes through (x, y)
    int4 seq;       // x: the frame (Halton jitter and dither seed: atlas_lab's frame + offset)
};

[[vk::binding(0, 0)]] ConstantBuffer<LodFrame> frame;
[[vk::binding(1, 0)]] StructuredBuffer<uint4> nodes;
[[vk::binding(2, 0)]] StructuredBuffer<uint> grid;
[[vk::binding(3, 0)]] StructuredBuffer<uint4> bricks;
[[vk::binding(4, 0)]] StructuredBuffer<uint> states;
[[vk::binding(5, 0)]] RWStructuredBuffer<float4> outColor;  // rgb (0..255, sky and fog applied), a: 1 = hit
[[vk::binding(6, 0)]] RWStructuredBuffer<float> outT;       // the hit's distance along the ray (blocks), -1 sky
[[vk::binding(7, 0)]] RWStructuredBuffer<uint> outId;       // the surface hit (2 * node + 1, a micro-voxel even), 0 sky
[[vk::binding(8, 0)]] RWStructuredBuffer<uint> outSteps;    // nodes visited + face fetches (atlas_lab's steps)

static const uint F_MICRO = 1;
static const int OCT_STACK = 16;    // levels: a 4096 micro-voxel root is 12
static const int GROUP_STACK = 64;  // the deepest group chain the host accepts
static const int FACE_LOW[3] = { 4, 0, 2 };   // west, down, north: the face a ray going + along the axis enters
static const int FACE_HIGH[3] = { 5, 1, 3 };  // east, up, south

uint hash(uint x) {
    x ^= x >> 16;
    x *= 0x7feb352dU;
    x ^= x >> 15;
    x *= 0x846ca68bU;
    x ^= x >> 16;
    return x;
}
float hashf(int x, int y, int z, uint s) {
    uint h = hash(uint(x) * 73856093u ^ hash(uint(y) * 19349663u ^ hash(uint(z) * 83492791u ^ s)));
    return float(h >> 8) * (1.0f / 16777216.0f);
}

struct Ray {
    float3 o, d;
    float pa, tol;
};

struct Hit {
    bool hit;
    float3 c;
    float t;
    uint id, steps;
};

struct Box {
    int3 lo, hi;
    float err;
    uint mask, flags, first;
};

Box unpackNode(uint4 n) {
    Box b;
    b.lo = int3(int(n.x & 0xFFFu), int(n.y & 0xFFFu), int(n.z & 0xFFFu));
    b.hi = int3(int((n.x >> 12) & 0xFFFu), int((n.y >> 12) & 0xFFFu), int((n.z >> 12) & 0xFFFu));
    b.mask = n.x >> 24;
    b.err = float((n.y >> 24) | (((n.z >> 24) & 0xFu) << 8));
    b.flags = n.z >> 28;
    b.first = n.w;
    return b;
}

// ---- the micro-voxels (the brick pool) -----------------------------------------------------------------------------

bool microAt(int3 q) {
    int3 b = q >> 2;
    if (b.x < 0 || b.y < 0 || b.z < 0 || b.x >= frame.dims.x || b.y >= frame.dims.y || b.z >= frame.dims.z) return false;
    uint brick = grid[uint(b.x + frame.dims.x * (b.z + frame.dims.z * b.y))];
    if (brick == 0) return false;
    uint bit = uint((q.x & 3) + 4 * ((q.z & 3) + 4 * (q.y & 3)));
    uint4 k = bricks[brick];
    return (((bit < 32 ? k.x >> bit : k.y >> (bit - 32)) & 1u) != 0);
}

float3 rgb(uint c) { return float3(float((c >> 16) & 255u), float((c >> 8) & 255u), float(c & 255u)); }

// the texel of face f over micro-voxel (x, y, z) of a block (as AtlasLabExport lays the tiles out)
int texel(int f, int x, int y, int z) {
    if (f <= 1) return x + 4 * z;
    if (f <= 3) return x + 4 * (3 - y);
    return z + 4 * (3 - y);
}

// the colour of micro-voxel q's face f, lit
float3 microCol(int3 q, int f) {
    int3 b = q >> 2;
    uint s = bricks[grid[uint(b.x + frame.dims.x * (b.z + frame.dims.z * b.y))]].z * 100u;
    uint c = states[s + 4u + uint(f * 16 + texel(f, q.x & 3, q.y & 3, q.z & 3))];
    float3 col = rgb((c >> 24) != 0 ? c : states[s]);
    uint kind = states[s + 1u];
    float3 t = kind == 1 ? frame.tint1.xyz : kind == 2 ? frame.tint2.xyz : kind == 3 ? frame.tint3.xyz : float3(255.0f, 255.0f, 255.0f);
    col = col * t / 255.0f;
    float l = f == 0 ? frame.light0.x : f == 1 ? frame.light0.y : f == 2 ? frame.light0.z : f == 3 ? frame.light0.w
              : f == 4 ? frame.light1.x : frame.light1.y;
    return col * l;
}

// ---- boxes ---------------------------------------------------------------------------------------------------------

// the ray against box [lo, hi) (micro-voxels): entry, exit, and the axis of the entry plane
bool rayBox(Ray r, int3 lo, int3 hi, out float t0, out float t1, out int axis) {
    t0 = -1e30f;
    t1 = 1e30f;
    axis = 0;
    for (int a = 0; a < 3; a++) {
        float l = float(lo[a]) * 0.25f, h = float(hi[a]) * 0.25f;
        if (abs(r.d[a]) < 1e-12f) {
            if (r.o[a] < l || r.o[a] > h) return false;
            continue;
        }
        float ta = (l - r.o[a]) / r.d[a], tb = (h - r.o[a]) / r.d[a];
        if (ta > tb) {
            float sw = ta;
            ta = tb;
            tb = sw;
        }
        if (ta > t0) {
            t0 = ta;
            axis = a;
        }
        t1 = min(t1, tb);
    }
    return t0 <= t1 && t1 > 0;
}

// the masked texture of face (axis, side) of box [lo, hi) at the ray's point p on it: the first content of the
// column under p (none: a hole)
bool boxFace(Ray r, int3 lo, int3 hi, int axis, bool high, float3 p, float t, inout Hit h) {
    int a1 = (axis + 1) % 3, a2 = (axis + 2) % 3;
    int f = high ? FACE_HIGH[axis] : FACE_LOW[axis];
    h.steps++;  // a texture fetch
    int u = clamp(int(floor(p[a1] * 4.0f)), lo[a1], hi[a1] - 1), v = clamp(int(floor(p[a2] * 4.0f)), lo[a2], hi[a2] - 1);
    int n = hi[axis] - lo[axis];
    for (int k = 0; k < n; k++) {
        int3 q = int3(0, 0, 0);
        q[axis] = high ? hi[axis] - 1 - k : lo[axis] + k;
        q[a1] = u;
        q[a2] = v;
        if (microAt(q)) {
            h.hit = true;
            h.t = t;
            h.c = microCol(q, f);
            return true;
        }
    }
    return false;
}

// a node drawn as one box: the face the ray enters by, and through a hole the face it leaves by (seen from outside)
bool drawBox(Ray r, int3 lo, int3 hi, int axis, float t, inout Hit h) {
    if (boxFace(r, lo, hi, axis, r.d[axis] < 0, r.o + r.d * t, t, h)) return true;
    int ax2 = 0;
    float best = 1e30f;
    for (int a = 0; a < 3; a++) {
        if (abs(r.d[a]) < 1e-12f) continue;
        float e = (float(r.d[a] > 0 ? hi[a] : lo[a]) * 0.25f - r.o[a]) / r.d[a];
        if (e < best) {
            best = e;
            ax2 = a;
        }
    }
    return boxFace(r, lo, hi, ax2, r.d[ax2] > 0, r.o + r.d * best, best, h);
}

// draw when the error is under the pixel there (the threshold dithered per pixel and frame), else descend
bool asBox(Ray r, float err, float t) { return err * 0.25f <= r.tol * t * r.pa; }

// the octree: a stack entry per level is (base, the rest of the level's sorted children: count << 28 | 3-bit
// indices, nearest first); base = the first child, or 0x80000000 | the node whose micro-voxels they are
Hit traceBoxes(Ray r) {
    Hit h = { false, float3(0, 0, 0), 0, 0, 0 };
    int root = frame.dims.w;
    if (root < 0) return h;
    uint2 stack[OCT_STACK];
    int sp = 0;
    uint node = uint(root), micro = 0xFFFFFFFFu, slot = 0;  // the next to visit: a node, or micro-voxel `slot` of `micro`
    for (;;) {
        Box b;
        uint sid;
        bool leaf;
        if (micro == 0xFFFFFFFFu) {
            b = unpackNode(nodes[node]);
            leaf = b.mask == 0;
            sid = node * 2u + 1u;
        } else {
            int3 o = (unpackNode(nodes[micro]).lo & ~1) + int3(int(slot & 1u), int(slot >> 2), int((slot >> 1) & 1u));
            b.lo = o;
            b.hi = o + 1;
            b.err = 0;
            b.mask = 0;
            b.flags = 0;
            b.first = 0;
            leaf = true;
            sid = (micro * 8u + slot) * 2u + 2u;
        }
        float t0, t1;
        int axis;
        if (rayBox(r, b.lo, b.hi, t0, t1, axis)) {
            h.steps++;
            float t = max(t0, 0.0f);
            if (leaf || asBox(r, b.err, t)) {
                h.id = sid;
                if (drawBox(r, b.lo, b.hi, axis, t, h)) return h;
            } else {
                // the children the ray meets, sorted by entry (atlas_lab sorts (entry, index) pairs)
                float ct[8];
                uint ci[8];
                int k = 0;
                bool isMicro = (b.flags & F_MICRO) != 0;
                int3 o = b.lo & ~1;
                uint j = 0;
                for (uint s = 0; s < 8; s++) {
                    if (((b.mask >> s) & 1u) == 0) continue;
                    int3 clo, chi;
                    if (isMicro) {
                        clo = o + int3(int(s & 1u), int(s >> 2), int((s >> 1) & 1u));
                        chi = clo + 1;
                    } else {
                        Box c = unpackNode(nodes[b.first + j]);
                        clo = c.lo;
                        chi = c.hi;
                    }
                    uint idx = isMicro ? s : j;
                    j++;
                    float a0, a1;
                    int ax;
                    if (!rayBox(r, clo, chi, a0, a1, ax)) continue;
                    int m = k++;
                    while (m > 0 && ct[m - 1] > a0) {
                        ct[m] = ct[m - 1];
                        ci[m] = ci[m - 1];
                        m--;
                    }
                    ct[m] = a0;
                    ci[m] = idx;
                }
                if (k > 0 && sp < OCT_STACK) {
                    uint list = 0;
                    for (int m = k - 1; m >= 0; m--) list = (list << 3) | ci[m];
                    stack[sp++] = uint2(isMicro ? (0x80000000u | node) : b.first, list | (uint(k) << 28));
                }
            }
        }
        if (sp == 0) break;
        uint2 e = stack[sp - 1];
        uint n = e.y >> 28;
        uint next = e.y & 7u;
        if (n == 1) sp--;
        else stack[sp - 1] = uint2(e.x, ((e.y & 0x0FFFFFFFu) >> 3) | ((n - 1) << 28));
        if ((e.x & 0x80000000u) != 0) {
            micro = e.x & 0x7FFFFFFFu;
            slot = next;
        } else {
            micro = 0xFFFFFFFFu;
            node = e.x + next;
        }
    }
    return h;
}

// ---- groups --------------------------------------------------------------------------------------------------------

// the binary hierarchy: the nearer child next, the farther one on the stack
Hit traceGroups(Ray r) {
    Hit h = { false, float3(0, 0, 0), 0, 0, 0 };
    int root = frame.dims.w;
    if (root < 0) return h;
    uint stack[GROUP_STACK];
    int sp = 0;
    uint node = uint(root);
    for (;;) {
        Box b = unpackNode(nodes[node]);
        float t0, t1;
        int axis;
        bool descended = false;
        if (rayBox(r, b.lo, b.hi, t0, t1, axis)) {
            h.steps++;
            float t = max(t0, 0.0f);
            if ((b.mask & 3u) == 0 || asBox(r, b.err, t)) {
                h.id = node * 2u + 1u;
                if (drawBox(r, b.lo, b.hi, axis, t, h)) return h;
            } else {
                int kids[2] = { -1, -1 };
                float tk[2] = { 1e30f, 1e30f };
                uint j = b.first;
                if ((b.mask & 1u) != 0) kids[0] = int(j++);
                if ((b.mask & 2u) != 0) kids[1] = int(j);
                for (int i = 0; i < 2; i++) {
                    if (kids[i] < 0) continue;
                    Box c = unpackNode(nodes[uint(kids[i])]);
                    float a0, a1;
                    int ax;
                    if (!rayBox(r, c.lo, c.hi, a0, a1, ax)) kids[i] = -1;
                    else tk[i] = a0;
                }
                int first = tk[0] <= tk[1] ? 0 : 1;
                int nearKid = kids[first], farKid = kids[1 - first];
                if (nearKid >= 0) {
                    if (farKid >= 0 && sp < GROUP_STACK) stack[sp++] = uint(farKid);
                    node = uint(nearKid);
                    descended = true;
                } else if (farKid >= 0) {
                    node = uint(farKid);
                    descended = true;
                }
            }
        }
        if (descended) continue;
        if (sp == 0) break;
        node = stack[--sp];
    }
    return h;
}

// ---- the frame -----------------------------------------------------------------------------------------------------

// pixel (px, py)'s ray this frame: atlas_lab's Halton (2, 3) jitter and its dithered threshold
Ray pixelRay(int px, int py) {
    int k = frame.seq.x + 1;
    float jx = 0, jy = 0, fr = 0.5f;
    for (int i = k; i != 0; i /= 2) {
        jx += fr * float(i % 2);
        fr *= 0.5f;
    }
    fr = 1.0f / 3.0f;
    for (int i = k; i != 0; i /= 3) {
        jy += fr * float(i % 3);
        fr /= 3.0f;
    }
    float pa = frame.eye.w;
    float ox = (float(px - frame.view.z) + jx) * pa, oy = (float(py - frame.view.w) + jy) * pa;
    float3 a = frame.fwd.xyz + frame.right.xyz * ox - frame.up.xyz * oy;
    float inv = 1.0f / sqrt(a.x * a.x + a.y * a.y + a.z * a.z);
    Ray r;
    r.o = frame.eye.xyz;
    r.d = a * inv;
    r.pa = pa;
    float dither = 0.7f + 0.6f * hashf(px, py, frame.seq.x, 0xB0C5u);
    r.tol = frame.fwd.w * dither / max(frame.right.w, 0.02f);
    return r;
}

void writePixel(uint i, Hit h) {
    float3 c = h.hit ? h.c : frame.sky.xyz;
    if (frame.up.w > 0 && h.hit) {
        float tr = frame.right.w;
        c = c * tr + frame.sky.xyz * (1.0f - tr);
    }
    outColor[i] = float4(c, h.hit ? 1.0f : 0.0f);
    outT[i] = h.hit ? h.t : -1.0f;
    outId[i] = h.hit ? h.id : 0u;
    outSteps[i] = h.steps;
}

[numthreads(8, 8, 1)]
void boxesMain(uint3 id: SV_DispatchThreadID) {
    if (int(id.x) >= frame.view.x || int(id.y) >= frame.view.y) return;
    writePixel(id.x + uint(frame.view.x) * id.y, traceBoxes(pixelRay(int(id.x), int(id.y))));
}

[numthreads(8, 8, 1)]
void groupsMain(uint3 id: SV_DispatchThreadID) {
    if (int(id.x) >= frame.view.x || int(id.y) >= frame.view.y) return;
    writePixel(id.x + uint(frame.view.x) * id.y, traceGroups(pixelRay(int(id.x), int(id.y))));
}
