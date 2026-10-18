// C++ mirror of native/shaders/aether_shared.slang and the global parameter block of aether_far.slang.
#pragma once
#include <cstddef>
#include <cstdint>

namespace aether {

constexpr int MAX_LODS = 8;

struct alignas(16) LodGpu {
    int32_t origin[4];
    int32_t dim[4];
};

struct alignas(16) RegionGpu {
    int32_t origin[4];
    uint32_t bases[4];
    uint32_t runs[4];
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
};

struct alignas(16) ActorGpu {
    float posYaw[4];  // camera-relative feet (curvature applied), yaw
    float shape[4];   // height, radius, kind, tint bits
};

static_assert(sizeof(LodGpu) == 32);
static_assert(sizeof(ActorGpu) == 32);
static_assert(sizeof(RegionGpu) == 48);
static_assert(sizeof(MaterialGpu) == 32);
static_assert(sizeof(FrameGpu) == 64 + 16 * 6 + 32 * MAX_LODS);

struct Float4 {
    float v[4];
};

// Slang's C++ target: StructuredBuffer<T> is { T* data; size_t count; }, ConstantBuffer<T> is T*.
template <typename T>
struct CpuBuffer {
    T* data;
    size_t count;
};

// Must list the globals of aether_far.slang in declaration order.
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
};

}  // namespace aether
