// C++ mirror of native/shaders/atlas_shared.slang and the global parameter block of atlas_far.slang.
#pragma once
#include <cstddef>
#include <cstdint>

namespace atlas {

constexpr int MAX_LODS = 8;

struct alignas(16) LodGpu {
    int32_t origin[4];
    int32_t dim[4];
};

struct alignas(16) RegionGpu {
    int32_t origin[4];
    uint32_t bases[4];
    uint32_t runs[4];
    uint32_t groups[4];  // its groups: first node (uint4), brick columns, slots, bricks (words); x = ~0u: none
};

struct alignas(16) MaterialGpu {
    float albedoRough[4];
    float emisClass[4];
};

struct alignas(16) FrameGpu {
    float invViewProj[16];
    int32_t camInt[4];
    float camFrac[4];
    float sunDir[4];
    float params[4];
    uint32_t dims[4];
    float weatherParams[4];
    LodGpu lods[MAX_LODS];
    float prevViewProj[16];
    float prevCam[4];
    uint32_t temporal[4];  // frame index, history valid, now (nowMs), fade duration ms
    // sun map (atlas_set_sun_map): centre camera-relative + half extent; basis X, Y (perpendicular to the key
    // light) and the key light L; rows: first row, row count, size, flags (1 = enabled)
    float sunMapCentre[4];
    float sunMapX[4];
    float sunMapY[4];
    float sunMapL[4];
    uint32_t sunMapRows[4];
    // (abi 0.12) the night key light set by atlas_set_moon: direction (w = 1 when set) and radiance
    float moonDir[4];
    float moonRad[4];
    // (abi 0.13) primary-ray jitter (atlas_set_jitter): NDC offset x, y; z = 1 when set; w unused
    float jitter[4];
    // (abi 0.16) the air (atlas_shared.slang FrameGpu, atlas_air.slang; filled by api.cpp from air.hpp)
    float airRayleigh[4];  // w = 1 / aerosol scale height (per km)
    float airMieScat[4];   // w = asymmetry g
    float airMieExt[4];    // w = km of air per block
    float airOzone[4];     // w = ground albedo
    float airSun[4];       // w = the eye's altitude, km
    float airMoon[4];
    float airSkyUp[4];
    float airGround[4];
};

struct alignas(16) ActorGpu {
    float posYaw[4];  // camera-relative feet (curvature applied), yaw
    float shape[4];   // height, radius, kind, tint bits
    float motion[4];  // phase seed, gait (0 still, 1 glide, 2 flap), opacity, 0
};

static_assert(sizeof(LodGpu) == 32);
static_assert(sizeof(ActorGpu) == 48);
static_assert(sizeof(RegionGpu) == 64);
static_assert(sizeof(MaterialGpu) == 32);
static_assert(sizeof(FrameGpu) == 64 + 16 * 6 + 32 * MAX_LODS + 64 + 16 + 16 + 16 * 5 + 32 + 16 + 16 * 8);

struct Float4 {
    float v[4];
};

struct Float2 {
    float v[2];
};

struct Uint4 {
    uint32_t v[4];
};

// Slang's C++ target: StructuredBuffer<T> is { T* data; size_t count; }, ConstantBuffer<T> is T*.
template <typename T>
struct CpuBuffer {
    T* data;
    size_t count;
};

// Must list the globals of atlas_far.slang in declaration order.
struct FarGlobals {
    FrameGpu* frame;
    CpuBuffer<int32_t> regionTable;
    CpuBuffer<RegionGpu> regions;
    CpuBuffer<uint32_t> nodes;
    CpuBuffer<uint64_t> leaves;
    CpuBuffer<uint32_t> runEnd;
    CpuBuffer<uint32_t> runMat;
    CpuBuffer<MaterialGpu> palette;
    CpuBuffer<Float4> outColor;
    CpuBuffer<uint32_t> outNormal;
    CpuBuffer<float> outDepth;
    CpuBuffer<uint32_t> weather;
    CpuBuffer<uint32_t> counters;
    CpuBuffer<uint32_t> cloudNoise;
    CpuBuffer<float> nearDepth;
    CpuBuffer<ActorGpu> actors;
    CpuBuffer<uint32_t> heightMip;
    CpuBuffer<Float4> cloudBuf;
    CpuBuffer<float> cloudDepth;
    CpuBuffer<float> prevSurface;
    CpuBuffer<float> outSurface;
    void* cloudNoiseTex;  // Texture3D on the C++ target: unused there (the kernel samples cloudNoise by hand)
    CpuBuffer<Float4> prevCloudBuf;
    CpuBuffer<float> prevCloudDepth;
    CpuBuffer<Float2> sunMap;
    CpuBuffer<Uint4> groupData;  // (atlas_groups.slang) PackedScene::groups
    CpuBuffer<uint32_t> nearInfo;   // (abi 0.15) the near field's surface info per pixel, RGBA8 packed
    CpuBuffer<Float4> outNearLight; // (abi 0.15) the near light per pixel (FRAME_NEAR_LIGHT)
};

}  // namespace atlas
