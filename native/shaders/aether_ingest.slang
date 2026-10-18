// Ingest pass (Vulkan only): the near field's per-pixel view distance, written by the game's GL pipeline into a
// shared R32F image (rows bottom-up), copied into the buffer the far kernel reads (row 0 top). Keeping the far kernel
// on buffers keeps its C++ (CPU reference) build identical.

struct IngestParams {
    uint width;
    uint height;
};

[[vk::push_constant]] ConstantBuffer<IngestParams> pc;
[[vk::binding(0, 0)]] [[vk::image_format("r32f")]] RWTexture2D<float> srcNearDepth;
[[vk::binding(1, 0)]] RWStructuredBuffer<float> dstNearDepth;

[shader("compute")]
[numthreads(8, 8, 1)]
void ingestMain(uint3 id : SV_DispatchThreadID) {
    if (id.x >= pc.width || id.y >= pc.height) return;
    dstNearDepth[id.y * pc.width + id.x] = srcNearDepth[uint2(id.x, pc.height - 1 - id.y)];
}
