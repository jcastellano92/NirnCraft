// Ingest pass (Vulkan only): the near field's per-pixel view distance, written by the game's GL pipeline into a
// shared R32F image (rows bottom-up), copied into the buffer the far kernel reads (row 0 top). Keeping the far kernel
// on buffers keeps its C++ (CPU reference) build identical. With the near light (abi 0.15) also the near field's
// surface info (RGBA8 image -> packed uint, r in the low byte).

struct IngestParams {
    uint width;
    uint height;
    uint info;  // 1: copy the surface info too (FRAME_NEAR_LIGHT)
};

[[vk::push_constant]] ConstantBuffer<IngestParams> pc;
[[vk::binding(0, 0)]] [[vk::image_format("r32f")]] RWTexture2D<float> srcNearDepth;
[[vk::binding(1, 0)]] RWStructuredBuffer<float> dstNearDepth;
[[vk::binding(2, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> srcNearInfo;
[[vk::binding(3, 0)]] RWStructuredBuffer<uint> dstNearInfo;

[shader("compute")]
[numthreads(8, 8, 1)]
void ingestMain(uint3 id : SV_DispatchThreadID) {
    if (id.x >= pc.width || id.y >= pc.height) return;
    uint2 src = uint2(id.x, pc.height - 1 - id.y);
    dstNearDepth[id.y * pc.width + id.x] = srcNearDepth[src];
    if (pc.info != 0u) {
        float4 v = saturate(srcNearInfo[src]);
        dstNearInfo[id.y * pc.width + id.x] = uint(v.x * 255.0 + 0.5) | (uint(v.y * 255.0 + 0.5) << 8)
                                            | (uint(v.z * 255.0 + 0.5) << 16) | (uint(v.w * 255.0 + 0.5) << 24);
    }
}
