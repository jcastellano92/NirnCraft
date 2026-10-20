// Publish pass (Vulkan only): copies the far kernel's buffers into the images shared with OpenGL, so the game's
// GL pipeline (Sodium/Iris, NirnCraft's pack) samples them as ordinary textures. Keeping the main kernel on buffers
// keeps its C++ (CPU reference) build identical; this copy is ~0.1 ms at 1080p.

struct PublishParams {
    uint width;
    uint height;
    uint nearLight;  // 1: publish the near light too (FRAME_NEAR_LIGHT, abi 0.15)
    float preExposure;  // (abi 0.16) radiance and irradiance scaled by the host's exposure (half precision's range)
};

[[vk::push_constant]] ConstantBuffer<PublishParams> pc;
[[vk::binding(0, 0)]] StructuredBuffer<float4> srcColor;
[[vk::binding(1, 0)]] StructuredBuffer<uint> srcNormal;
[[vk::binding(2, 0)]] StructuredBuffer<float> srcDepth;
[[vk::binding(3, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> dstColor;  // linear HDR, premultiplied
[[vk::binding(4, 0)]] [[vk::image_format("rgba8")]] RWTexture2D<float4> dstNormal;
[[vk::binding(5, 0)]] [[vk::image_format("r32f")]] RWTexture2D<float> dstDepth;
[[vk::binding(6, 0)]] StructuredBuffer<float4> srcNearLight;
[[vk::binding(7, 0)]] [[vk::image_format("rgba16f")]] RWTexture2D<float4> dstNearLight;

float4 unpack8(uint v) {
    return float4(float(v & 255u), float((v >> 8) & 255u), float((v >> 16) & 255u), float(v >> 24)) / 255.0;
}

[shader("compute")]
[numthreads(8, 8, 1)]
void publishMain(uint3 id : SV_DispatchThreadID) {
    if (id.x >= pc.width || id.y >= pc.height) return;
    uint i = id.y * pc.width + id.x;
    // GL textures are bottom-up: row 0 of the image is the bottom of the screen
    uint2 dst = uint2(id.x, pc.height - 1 - id.y);
    float4 c = srcColor[i];
    dstColor[dst] = float4(c.rgb * pc.preExposure, c.a);  // (a: coverage or the near field's transmittance, unscaled)
    dstNormal[dst] = unpack8(srcNormal[i]);
    dstDepth[dst] = srcDepth[i];
    // (a < 0: a pixel the half-resolution near light found under cover, atlas_far.slang nearLightMain)
    if (pc.nearLight != 0u) {
        float4 nl = srcNearLight[i];
        dstNearLight[dst] = float4(nl.rgb * pc.preExposure, max(nl.a, 0.0));
    }
}
