// Shared layouts and lighting for atlas_core kernels. The C++ mirror is native/src/gpu_layout.hpp; keep both in
// sync (static_asserts there check sizes). All members are 16-byte vectors so std140 and C++ agree.

static const uint FRAME_CLOUDS = 0x1u;
static const uint FRAME_SHADOWS = 0x2u;
static const uint FRAME_ATMOS = 0x4u;
static const uint FRAME_ACTORS = 0x8u;
static const uint FRAME_SKY = 0x10u;         // draw atlas's sky behind misses (harness); in game the pack draws it
static const uint FRAME_NEAR_DEPTH = 0x20u;  // nearDepth holds the near field's view distance: covered pixels skip
static const uint FRAME_FLAT_NORMALS = 0x40u;  // face normals only (cheaper; a quality setting)
static const uint FRAME_CLOUDS_QUARTER = 0x80u;  // cloud pass at quarter instead of half resolution (performance)
static const uint FRAME_DEBUG_LOD = 0x100u;
static const uint FRAME_DEBUG_ALBEDO = 0x400u;        // surfaces output their albedo (the pack lab's G-buffer)
static const uint FRAME_SEE_THROUGH_WATER = 0x800u;  // rays pass water voxels (the bed under a lake, for the lab)
static const uint FRAME_DEBUG_WEATHER = 0x200u;  // ground shows the sampled weather column (cov, tower, curtain)
// the near field's average ambient darkening (sky occlusion x vertex AO x SSAO) that far voxels cannot compute
static const float FAR_AMBIENT_OCCLUSION = 0.78;
static const uint FRAME_FILL_NEAR = 0x2000u;   // (abi 0.12) fill pixels the near field left empty, from the camera
static const uint FRAME_LOD_DITHER = 0x1000u;  // dithered LOD transitions (per-pixel segment phase)
static const uint FRAME_GROUPS = 0x4000u;      // (abi 0.14) primary rays trace the content-shaped groups
static const uint FRAME_VANILLA_LIGHT = 0x10000u;  // (abi 0.14, optional) Minecraft's own light: no pack, the near field vanilla
static const uint FRAME_LIGHT_LOW = 0x8000u;   // (abi 0.14) cheap far light: constant sky occlusion, unshadowed block light
static const uint FRAME_NEAR_LIGHT = 0x20000u; // (abi 0.15) light the near field's covered pixels with the far light
static const uint FRAME_PRIMARY_NEAR = 0x80000u;      // (abi 0.17) the near field from atlas's primary rays (atlas.h)
static const uint FRAME_PRIMARY_NEAR_LIT = 0x100000u; // (abi 0.17) ... composited (atlas.h)
static const uint FRAME_GPU_LIGHTS = 0x80000000u;  // (internal: the Vulkan backend) the far lights culled, ranked and
                                                    // binned on the GPU (lightCullMain), not by lights.cpp
static const uint FRAME_NEAR_SPLIT = 0x40000u; // (internal: the Vulkan backend) the near light traced at half resolution
                                               // (nearLightMain) and upsampled (nearUpsampleMain), not in farMain
// the shader pack's ambient floor (deferred.fsh: albedo x (sky + floor)), under every far land surface as under the near
static const float3 FAR_AMBIENT_FLOOR = float3(2.0e-8, 2.0e-8, 3.0e-8);  // physical: starlight and airglow  // (night: the eye's exposure lifted a brighter floor to a lit ground)
// the look (shadeSurfaceAmb): how much the sky fills the shade (1: the physical value: the air's sky light, multiple
// scattering included, against the sun's transmitted light) and the warm bounce off lit ground where the sky rays did
// not trace it (the constant; gBounce; about the ground's albedo)
static const float LOOK_AMBIENT = 1.0;
static const float LOOK_BOUNCE = 0.25;
// (the aerial haze's strength is the air's scale, AtlasAir.airScale: 2.5 m of air a block)
static const int MAX_LODS = 8;

struct LodGpu {
    int4 origin;   // region coordinates of table cell (0, 0, 0); w = world y of region row 0 (the floor, -64)
    int4 dim;      // table size in regions; w = offset into regionTable
};

struct RegionGpu {
    int4 origin;   // world block origin of voxel (0, 0, 0); w = lod
    uint4 bases;   // node base, leaf base, root (node word offset), height-mip base
    uint4 runs;    // material run base, run count
    uint4 groups;  // its content-shaped groups (atlas_groups.slang): first node, brick columns, slots, bricks; x ~0: none
};

struct MaterialGpu {
    float4 albedoRough;  // linear rgb, roughness
    float4 emisClass;    // emissive, class, 0, 0
};

struct FrameGpu {
    float4 invViewProj[4];  // columns of the camera-relative inverse view-projection
    int4 camInt;            // integer camera block
    float4 camFrac;         // camera position within that block; w = planet radius (0 = flat)
    float4 sunDir;          // world, towards the sun; w = time in seconds
    float4 params;          // near-field end, max distance, lod bias, pixel angle (radians)
    uint4 dims;             // width, height, flags, lod count
    float4 weatherParams;   // rain factor, 0, 0, far actor count
    LodGpu lods[MAX_LODS];
    float4 prevViewProj[4];  // last frame's camera-relative view-projection (columns), for cloud reprojection
    float4 prevCam;          // last frame's camera relative to this frame's camInt
    uint4 temporal;          // frame index, history valid (0/1), now (ms, World's clock), refinement fade ms
    float4 sunMapCentre;     // sun map centre, camera-relative; w = half extent (blocks)
    float4 sunMapX;          // sun map basis (perpendicular to the key light)
    float4 sunMapY;
    float4 sunMapL;          // the key light the map is traced along
    uint4 sunMapRows;        // first row, row count, size, enabled
    float4 moonDir;          // (abi 0.12) night key light towards the moon, w = 1 when the host set it
    float4 moonRad;          // its radiance (before the night fade)
    float4 jitter;           // (abi 0.13) primary-ray NDC offset x, y; z = 1 when the host jitters (TAA)
    // (abi 0.16) the air (atlas_air.slang; native/src/air.hpp): per-km coefficients at sea level, the lights above the
    // air, the eye's altitude, and the sky's light the host integrated there
    float4 airRayleigh;      // molecules' scattering; w = 1 / aerosol scale height (per km)
    float4 airMieScat;       // aerosols' scattering; w = their asymmetry g
    float4 airMieExt;        // aerosols' extinction; w = km of air per block
    float4 airOzone;         // ozone's absorption at its peak; w = the ground's albedo
    float4 airSun;           // the sun's irradiance above the air; w = the eye's altitude, km
    float4 airMoon;          // the moon's (0 by day)
    float4 airSkyUp;         // skyAmbient: the sky's radiance over the horizon at the eye
    float4 airGround;        // and the ground's under it
    // the GPU-built level-0 grid (voxgrid.hpp, atlas_voxgrid.slang): origin in sections (world section coordinates of
    // cell 0), w = 1 when there is one; dims in sections (x, y, z)
    int4 vgOrigin;
    uint4 vgDims;
    float4 viewProj[4];  // (abi 0.17, FRAME_PRIMARY_NEAR) camera-relative view-projection (columns): the host's NDC depth
    // (abi 0.17, atlas_set_eclipse) an eclipse's occulters (the moons near the sun): centre relative to the camera (km,
    // world axes), w radius (km, 0: none); eclipseC x km a world block, y 1 when any is set
    float4 eclipseA;
    float4 eclipseB;
    float4 eclipseC;
    // (internal) the light by need (api.cpp lightControl): x the footprint scale of the near light's secondary rays (0:
    // level 0 everywhere, the full light), y the share of the split's tiles traced a frame (1: all), z the sun ray's
    // cone (voxels of a level before the next: 0 none), w the bounce's sun ray's cone
    float4 lightCtl;
    // (internal) x (FRAME_GPU_LIGHTS) the air's extinction per block at the eye, for the far lights culled on the GPU
    // (lightCullMain); zw the last frame's jitter (NDC), for the near light's history (nearHistoryAt)
    float4 farLight;
    // the grid's coarse levels 1..6 (voxgrid.hpp): origin x, z (chunks of 16 << k blocks), first chunk word,
    // D | H << 8 | 1 << 16 when the level is there
    int4 vgLevel[6];
    // the continuous level's knobs (ATLAS_LOD, measurements): threshold pattern, the grazing widening's least cos, bias
    float4 lodParams;
};

// the frame's moon, copied by each entry point (the shared lighting has no frame of its own)
static float4 gMoonDir = float4(0.0, 1.0, 0.0, 0.0);
static float4 gMoonRad = float4(0.0, 0.0, 0.0, 0.0);
// One bounce (atlas_far skyVisible, for the next shadeSurfaceAmb, which takes it and puts this default back): rgb what
// the surfaces the sky rays met send back (their own light, averaged over the rays), a the share of rays that met
// none, over which the look's constant bounce stands in for the lit ground (all of it where nothing was traced)
static float4 gBounce = float4(0.0, 0.0, 0.0, 1.0);
// the water's body for shadeSurfaceAmb (negative: the material lit as a surface): what the far kernel traced through it
static float3 gWaterBody = float3(-1.0, -1.0, -1.0);

float3 unproject(FrameGpu f, float2 ndc) {
    float4 p = f.invViewProj[0] * ndc.x + f.invViewProj[1] * ndc.y + f.invViewProj[2] * 0.5 + f.invViewProj[3];
    return p.xyz / p.w;
}

float3 lodTint(int lod) {
    float3 t[8] = { float3(0.9, 0.2, 0.2), float3(0.9, 0.6, 0.2), float3(0.85, 0.85, 0.2), float3(0.3, 0.85, 0.3),
                    float3(0.2, 0.8, 0.8), float3(0.25, 0.4, 0.9), float3(0.6, 0.3, 0.9), float3(0.9, 0.9, 0.9) };
    return t[clamp(lod, 0, 7)];
}
// a continuous level's tint: the levels' tints blended across it (a gradient, no bands)
float3 lodTintF(float level) {
    float l = clamp(level, 0.0, 7.0);
    int a = int(floor(l));
    return lerp(lodTint(a), lodTint(min(a + 1, 7)), l - float(a));
}

// the air and the light (sky, sun, haze, shadeSurfaceAmb): atlas_air.slang

// Smooth value noise on a world-space grid (stable under camera motion and LOD changes).
// A per-pixel threshold in [0, 1): interleaved gradient noise (Jimenez 2014), spread evenly over small
// neighbourhoods so a dissolve at any progress reads as a fine even stipple; `salt` decorrelates several uses.
float pixelDither(uint2 px, uint salt) {
    float2 p = float2(px) + float2(float(salt) * 47.0, float(salt) * 17.0);
    return frac(52.9829189 * frac(0.06711056 * p.x + 0.00583715 * p.y));
}

float hash21(int2 p) {
    uint h = uint(p.x) * 374761393u + uint(p.y) * 668265263u;
    h = (h ^ (h >> 13)) * 1274126177u;
    return float(h & 0xFFFFFFu) / 16777215.0;
}

float valueNoise(float2 x) {
    float2 i = floor(x), f = x - i;
    f = f * f * (3.0 - 2.0 * f);
    int2 b = int2(i);
    float a = hash21(b), bb = hash21(b + int2(1, 0)), c = hash21(b + int2(0, 1)), d = hash21(b + int2(1, 1));
    return lerp(lerp(a, bb, f.x), lerp(c, d, f.x), f.y);
}

uint packColor(float3 c, float a) {
    // simple filmic curve, then gamma 2.2 (the game composite replaces this with the pack's tonemapper)
    c = c / (1.0 + c);
    c = pow(saturate(c), float3(1.0 / 2.2, 1.0 / 2.2, 1.0 / 2.2));
    uint r = uint(c.x * 255.0 + 0.5), g = uint(c.y * 255.0 + 0.5), b = uint(c.z * 255.0 + 0.5);
    uint aa = uint(saturate(a) * 255.0 + 0.5);
    return r | (g << 8) | (b << 16) | (aa << 24);
}

float2 octEncode(float3 n) {
    n /= (abs(n.x) + abs(n.y) + abs(n.z));
    float2 e = n.xz;
    if (n.y < 0.0) e = (1.0 - abs(e.yx)) * float2(e.x >= 0.0 ? 1.0 : -1.0, e.y >= 0.0 ? 1.0 : -1.0);
    return e * 0.5 + 0.5;
}

// octEncode's inverse (atlas.h AtlasGlFrameIO.nearInfoTex): e in [0, 1]^2 -> unit normal
float3 octDecode(float2 e01) {
    float2 e = e01 * 2.0 - 1.0;
    float3 n = float3(e.x, 1.0 - abs(e.x) - abs(e.y), e.y);
    if (n.y < 0.0) {
        float nx = (1.0 - abs(n.z)) * (n.x >= 0.0 ? 1.0 : -1.0);
        float nz = (1.0 - abs(n.x)) * (n.z >= 0.0 ? 1.0 : -1.0);
        n.x = nx;
        n.z = nz;
    }
    return normalize(n);
}

uint packNormal(float3 n, uint cls, float shadow) {
    float2 e = octEncode(n);
    return uint(saturate(e.x) * 255.0 + 0.5) | (uint(saturate(e.y) * 255.0 + 0.5) << 8) | ((cls & 255u) << 16)
        | (uint(saturate(shadow) * 255.0 + 0.5) << 24);
}
