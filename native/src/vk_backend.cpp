// Vulkan compute backend for the aether_far kernel.
//  * Host path (harness, tests): scene in host-visible buffers (BAR when available), outputs read back.
//  * GL path (game): outputs published into images whose memory is exported to the game's OpenGL context
//    (VK_KHR_external_memory_win32 <-> GL_EXT_memory_object_win32), ordered by shared semaphores, so the frame never
//    touches the CPU. Two frame slots: the CPU records frame N+1 while the GPU still runs frame N.
#include <vulkan/vulkan.h>
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <vulkan/vulkan_win32.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <mutex>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "aether_cloud_spv.h"
#include "aether_sunmap_spv.h"    // generated: the same kernel source's cloudMain entry
#include "aether_far_spv.h"      // generated: aether_far_spv[], aether_far_spv_size (bytes)
#include "aether_ingest_spv.h"   // generated
#include "aether_publish_spv.h"  // generated
#include "backend.hpp"
#include "gl_api.hpp"

namespace aether {
namespace {

constexpr uint32_t BINDINGS = 25;
constexpr uint32_t SLOTS = 2;
// far kernel bindings
constexpr uint32_t B_FRAME = 0, B_PALETTE = 7, B_COLOR = 8, B_NORMAL = 9, B_DEPTH = 10, B_WEATHER = 11,
                   B_COUNTERS = 12, B_NOISE = 13, B_NEAR = 14, B_ACTORS = 15, B_HEIGHTMIP = 16, B_CLOUD = 17,
                   B_CLOUD_DEPTH = 18, B_PREV_SURFACE = 19, B_SURFACE = 20, B_NOISE_TEX = 21, B_PREV_CLOUD = 22,
                   B_PREV_CLOUD_DEPTH = 23, B_SUNMAP = 24;
// the scene's buffers (two copies, staged off the render thread)
constexpr uint32_t SCENE_BINDINGS[] = {1, 2, 3, 4, 5, 6, B_HEIGHTMIP};
bool isScene(uint32_t b) { return (b >= 1 && b <= 6) || b == B_HEIGHTMIP; }
// shared images
constexpr int IMG_COLOR = 0, IMG_NORMAL = 1, IMG_DEPTH = 2, IMG_NEAR = 3, IMAGES = 4;

// GL compute pass: the near field's depth buffer -> view distance in blocks (1e30 for sky) in the shared nearDepth
// image. Same bottom-up rows as GL; the Vulkan ingest pass flips them for the far kernel.
const char* LINEARIZE_GLSL = R"GLSL(#version 430
layout(local_size_x = 8, local_size_y = 8) in;
layout(binding = 15) uniform sampler2D aetherNearDepth;
layout(r32f, binding = 7) uniform writeonly image2D aetherNearOut;
uniform mat4 aetherInvProj;
uniform vec2 aetherSize;
uniform int aetherZeroToOne;
uniform int aetherReversed;
void main() {
    ivec2 p = ivec2(gl_GlobalInvocationID.xy);
    if (p.x >= int(aetherSize.x) || p.y >= int(aetherSize.y)) return;
    // aether may render below the window's resolution (dynamic scale): an aether pixel takes the farthest of four
    // samples over its footprint, so it is skipped only where near geometry covers all of it (at scale 1 all four
    // land on the same texel: the exact depth)
    ivec2 full = textureSize(aetherNearDepth, 0);
    vec2 f = vec2(full) / aetherSize;
    float dist = 0.0;
    for (int k = 0; k < 4; k++) {
        vec2 o = vec2(k & 1, k >> 1) * 0.5 + 0.25;
        ivec2 q = clamp(ivec2((vec2(p) + o) * f), ivec2(0), full - 1);
        float d = texelFetch(aetherNearDepth, q, 0).r;
        bool sky = aetherReversed != 0 ? d <= 0.0 : d >= 1.0;
        float z = aetherZeroToOne != 0 ? d : d * 2.0 - 1.0;
        vec2 ndc = (vec2(q) + 0.5) / vec2(full) * 2.0 - 1.0;
        vec4 v = aetherInvProj * vec4(ndc, z, 1.0);
        dist = max(dist, sky ? 1e30 : length(v.xyz / v.w));
    }
    imageStore(aetherNearOut, p, vec4(dist));
}
)GLSL";

bool perSlot(uint32_t b) {
    return b == B_FRAME || b == B_PALETTE || b == B_COLOR || b == B_NORMAL || b == B_DEPTH || b == B_WEATHER ||
           b == B_COUNTERS || b == B_NEAR || b == B_ACTORS || b == B_CLOUD || b == B_CLOUD_DEPTH || b == B_SURFACE;
}

VKAPI_ATTR VkBool32 VKAPI_CALL onValidation(VkDebugUtilsMessageSeverityFlagBitsEXT sev, VkDebugUtilsMessageTypeFlagsEXT,
                                            const VkDebugUtilsMessengerCallbackDataEXT* d, void*) {
    std::fprintf(stderr, "[vulkan %s] %s\n", sev >= VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT ? "error" : "warning",
                 d->pMessage);
    return VK_FALSE;
}

// Where a buffer lives. The host-visible device-local (BAR) heap is often only 214-256 MB without Resizable BAR, so
// nothing big may depend on it: frame outputs and the scene are device-local, filled by GPU copies.
enum class Mem { HostWrite, Readback, DeviceOnly, Staging };

struct Buf {
    VkBuffer buf = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    void* map = nullptr;
    VkDeviceSize size = 0;
    Mem kind = Mem::HostWrite;
};

struct SharedImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    VkImageView view = VK_NULL_HANDLE;
    HANDLE handle = nullptr;
    GLuint glMem = 0, glTex = 0;
};

struct SharedSemaphore {
    VkSemaphore sem = VK_NULL_HANDLE;
    HANDLE handle = nullptr;
    GLuint gl = 0;
};

// The scene's six buffers. Two copies: the update thread fills the one no in-flight frame reads, frames flip to it.
struct SceneCopy {
    Buf bufs[BINDINGS];  // the SCENE_BINDINGS entries, device-local
    Buf staging;  // host memory the staging thread writes; a GPU copy moves it into bufs
    VkCommandBuffer uploadCmd = VK_NULL_HANDLE;
    VkFence uploadFence = VK_NULL_HANDLE;
    bool uploading = false;  // a copy was submitted; usable once uploadFence signals
    uint64_t revision = UINT64_MAX;
    std::atomic<uint64_t> lastFrame{0};  // last frame number recorded against this copy
    uint32_t generation = 0;  // bumped when a buffer is reallocated (descriptor sets must be rewritten)
};

struct Slot {
    uint64_t frameNumber = 0;  // of its last submission
    int boundCopy = -1;        // scene copy its far descriptor set points at
    VkBuffer boundPrev = VK_NULL_HANDLE;  // the previous frame's surface buffer its cloud pass reads
    bool surfaceFresh = true;  // its surface buffer was (re)allocated: clear to "no surface" before use
    uint32_t boundGeneration = 0;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    VkFence fence = VK_NULL_HANDLE;
    VkQueryPool query = VK_NULL_HANDLE;
    bool inFlight = false;
    VkDescriptorSet farSet = VK_NULL_HANDLE, publishSet = VK_NULL_HANDLE, ingestSet = VK_NULL_HANDLE;
    Buf bufs[BINDINGS];  // only the per-slot bindings are used
    bool farDirty = true, publishDirty = true, ingestDirty = true;
    SharedSemaphore glReady, vkDone;
};

class VulkanBackend final : public Backend {
public:
    ~VulkanBackend() override {
        if (dev_) {
            vkDeviceWaitIdle(dev_);
            destroyInterop();
            for (auto& s : slots_) {
                for (auto& b : s.bufs) release(b);
                if (s.fence) vkDestroyFence(dev_, s.fence, nullptr);
                if (s.query) vkDestroyQueryPool(dev_, s.query, nullptr);
            }
            for (auto& b : shared_) release(b);
            release(noiseStaging_);
            if (noiseSampler_) vkDestroySampler(dev_, noiseSampler_, nullptr);
            if (noiseView_) vkDestroyImageView(dev_, noiseView_, nullptr);
            if (noiseImg_) vkDestroyImage(dev_, noiseImg_, nullptr);
            if (noiseMem_) vkFreeMemory(dev_, noiseMem_, nullptr);
            for (auto& c : copies_) {
                for (auto& b : c.bufs) release(b);
                release(c.staging);
                if (c.uploadFence) vkDestroyFence(dev_, c.uploadFence, nullptr);
            }
            if (uploadPool_) vkDestroyCommandPool(dev_, uploadPool_, nullptr);
            if (pool_) vkDestroyCommandPool(dev_, pool_, nullptr);
            if (dpool_) vkDestroyDescriptorPool(dev_, dpool_, nullptr);
            if (cloudPipe_) vkDestroyPipeline(dev_, cloudPipe_, nullptr);
            if (cloudShader_) vkDestroyShaderModule(dev_, cloudShader_, nullptr);
            if (sunPipe_) vkDestroyPipeline(dev_, sunPipe_, nullptr);
            if (sunShader_) vkDestroyShaderModule(dev_, sunShader_, nullptr);
            destroyPipe(farPipe_, farLayout_, farSetLayout_, farShader_);
            destroyPipe(publishPipe_, publishLayout_, publishSetLayout_, publishShader_);
            destroyPipe(ingestPipe_, ingestLayout_, ingestSetLayout_, ingestShader_);
            vkDestroyDevice(dev_, nullptr);
        }
        if (messenger_) {
            auto destroy = reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(
                vkGetInstanceProcAddr(inst_, "vkDestroyDebugUtilsMessengerEXT"));
            if (destroy) destroy(inst_, messenger_, nullptr);
        }
        if (inst_) vkDestroyInstance(inst_, nullptr);
    }

    const char* name() const override { return deviceName_; }

    bool init(std::string& err) {
        if (!createInstance(err) || !pickDevice(err) || !createDevice(err)) return false;
        // far kernel: binding 0 uniform, the rest storage buffers
        std::vector<VkDescriptorType> farTypes(BINDINGS, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
        farTypes[B_FRAME] = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        farTypes[B_NOISE_TEX] = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        if (!createPipe(aether_far_spv, aether_far_spv_size, farTypes, 0, farPipe_, farLayout_, farSetLayout_, farShader_, err))
            return false;
        {
            // the cloud pass: same source, same bindings, same descriptor set as the far pass
            VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
            smci.codeSize = aether_cloud_spv_size;
            smci.pCode = aether_cloud_spv;
            if (vkCreateShaderModule(dev_, &smci, nullptr, &cloudShader_) != VK_SUCCESS) return fail(err, "cloud shader");
            VkComputePipelineCreateInfo cp{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
            cp.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
            cp.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
            cp.stage.module = cloudShader_;
            cp.stage.pName = "main";
            cp.layout = farLayout_;
            if (vkCreateComputePipelines(dev_, VK_NULL_HANDLE, 1, &cp, nullptr, &cloudPipe_) != VK_SUCCESS)
                return fail(err, "cloud pipeline");
            // and the sun map pass
            smci.codeSize = aether_sunmap_spv_size;
            smci.pCode = aether_sunmap_spv;
            if (vkCreateShaderModule(dev_, &smci, nullptr, &sunShader_) != VK_SUCCESS) return fail(err, "sun map shader");
            cp.stage.module = sunShader_;
            if (vkCreateComputePipelines(dev_, VK_NULL_HANDLE, 1, &cp, nullptr, &sunPipe_) != VK_SUCCESS)
                return fail(err, "sun map pipeline");
        }
        std::vector<VkDescriptorType> pubTypes = {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
                                                  VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                                                  VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE};
        if (!createPipe(aether_publish_spv, aether_publish_spv_size, pubTypes, 8, publishPipe_, publishLayout_,
                        publishSetLayout_, publishShader_, err))
            return false;
        std::vector<VkDescriptorType> ingTypes = {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER};
        if (!createPipe(aether_ingest_spv, aether_ingest_spv_size, ingTypes, 8, ingestPipe_, ingestLayout_, ingestSetLayout_,
                        ingestShader_, err))
            return false;

        VkDescriptorPoolSize ps[4] = {{VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, SLOTS},
                                      {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, SLOTS * (BINDINGS - 1 + 3 + 1)},
                                      {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, SLOTS * (3 + 1)},
                                      {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, SLOTS}};
        VkDescriptorPoolCreateInfo dp{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        dp.maxSets = SLOTS * 3;
        dp.poolSizeCount = 4;
        dp.pPoolSizes = ps;
        if (vkCreateDescriptorPool(dev_, &dp, nullptr, &dpool_) != VK_SUCCESS) return fail(err, "descriptor pool");
        VkCommandPoolCreateInfo cpi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        cpi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        cpi.queueFamilyIndex = family_;
        if (vkCreateCommandPool(dev_, &cpi, nullptr, &pool_) != VK_SUCCESS) return fail(err, "command pool");
        for (auto& s : slots_) {
            VkCommandBufferAllocateInfo cba{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
            cba.commandPool = pool_;
            cba.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
            cba.commandBufferCount = 1;
            if (vkAllocateCommandBuffers(dev_, &cba, &s.cmd) != VK_SUCCESS) return fail(err, "command buffer");
            VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
            vkCreateFence(dev_, &fci, nullptr, &s.fence);
            if (timestamps_) {
                VkQueryPoolCreateInfo q{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
                q.queryType = VK_QUERY_TYPE_TIMESTAMP;
                q.queryCount = 2;
                vkCreateQueryPool(dev_, &q, nullptr, &s.query);
            }
            VkDescriptorSetLayout layouts[3] = {farSetLayout_, publishSetLayout_, ingestSetLayout_};
            VkDescriptorSet sets[3];
            VkDescriptorSetAllocateInfo da{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
            da.descriptorPool = dpool_;
            da.descriptorSetCount = 3;
            da.pSetLayouts = layouts;
            if (vkAllocateDescriptorSets(dev_, &da, sets) != VK_SUCCESS) return fail(err, "descriptor sets");
            s.farSet = sets[0];
            s.publishSet = sets[1];
            s.ingestSet = sets[2];
        }
        // scene uploads are recorded by whichever thread stages a scene (under sceneMu_): their own pool
        VkCommandPoolCreateInfo upi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        upi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        upi.queueFamilyIndex = family_;
        if (vkCreateCommandPool(dev_, &upi, nullptr, &uploadPool_) != VK_SUCCESS) return fail(err, "upload pool");
        for (auto& c : copies_) {
            VkCommandBufferAllocateInfo cba{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
            cba.commandPool = uploadPool_;
            cba.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
            cba.commandBufferCount = 1;
            if (vkAllocateCommandBuffers(dev_, &cba, &c.uploadCmd) != VK_SUCCESS) return fail(err, "upload command buffer");
            VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
            vkCreateFence(dev_, &fci, nullptr, &c.uploadFence);
        }
        return true;
    }

    bool render(const FrameInputs& in, FrameTargets& out, double* gpuMs, std::string& err) override {
        Slot& s = slots_[frameIndex_++ % SLOTS];
        if (!waitSlot(s, gpuMs, err)) return false;
        uint32_t w = in.frame.dims[0], h = in.frame.dims[1];
        size_t px = size_t(w) * h;
        FrameGpu frame = in.frame;
        if (!prepare(s, in, frame, true, err)) return false;
        if (in.nearDepth && !upload(s.bufs[B_NEAR], in.nearDepth, px * 4, false, Mem::HostWrite, err)) return false;
        refreshDescriptors(s);
        begin(s);
        recordFar(s, w, h, (frame.dims[2] & 0x1u) != 0);
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(s.cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0,
                             nullptr, 0, nullptr);
        if (!submit(s, nullptr, nullptr, err) || !waitSlot(s, gpuMs, err)) return false;
        out.color.resize(px * 4);
        out.normal.resize(px);
        out.depth.resize(px);
        out.counters.resize(px);
        std::memcpy(out.color.data(), s.bufs[B_COLOR].map, px * 16);
        std::memcpy(out.normal.data(), s.bufs[B_NORMAL].map, px * 4);
        std::memcpy(out.depth.data(), s.bufs[B_DEPTH].map, px * 4);
        std::memcpy(out.counters.data(), s.bufs[B_COUNTERS].map, px * 4);
        return true;
    }

    // ---- GL interop ------------------------------------------------------------------------------------------

    bool glAttach(uint32_t w, uint32_t h, GlTargets& out, std::string& err) override {
        if (!interopCapable_) return fail(err, "Vulkan device lacks VK_KHR_external_memory_win32/semaphore_win32");
        if (attached_) glDetach();
        if (!gl_.load(err)) return false;
        // the GL context must live on the same physical GPU as our Vulkan device
        GLint nUuid = 0;
        gl_.GetIntegerv(GL_NUM_DEVICE_UUIDS_EXT, &nUuid);
        bool same = false;
        for (GLint i = 0; i < nUuid && !same; i++) {
            GLubyte u[16] = {};
            gl_.GetUnsignedBytei_vEXT(GL_DEVICE_UUID_EXT, GLuint(i), u);
            same = std::memcmp(u, deviceUuid_, 16) == 0;
        }
        if (!same) {
            err = std::string("the OpenGL context (") + reinterpret_cast<const char*>(gl_.GetString(GL_RENDERER)) +
                  ") is not on the Vulkan device " + deviceName_;
            return false;
        }
        const VkFormat fmt[IMAGES] = {VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R32_SFLOAT,
                                      VK_FORMAT_R32_SFLOAT};
        const GLenum glFmt[IMAGES] = {GL_RGBA16F, GL_RGBA8, GL_R32F, GL_R32F};
        bool ok = true;
        for (int i = 0; i < IMAGES && ok; i++) ok = createShared(images_[i], fmt[i], glFmt[i], w, h, err);
        for (auto& s : slots_) ok = ok && createSemaphore(s.glReady, err) && createSemaphore(s.vkDone, err);
        if (!ok) {
            destroyInterop();
            return false;
        }
        // nothing covers anything until the GL side writes the near field's distances
        float uncovered = 1e30f;
        gl_.ClearTexImage(images_[IMG_NEAR].glTex, 0, GL_RED, GL_FLOAT, &uncovered);
        if (gl_.GetError() != GL_NO_ERROR) {
            destroyInterop();
            return fail(err, "OpenGL error while importing the shared images");
        }
        width_ = w;
        height_ = h;
        firstGlFrame_ = true;
        attached_ = true;
        frameIndex_ = 0;
        for (auto& s : slots_) s.publishDirty = s.ingestDirty = true;
        out = {images_[IMG_COLOR].glTex, images_[IMG_NORMAL].glTex, images_[IMG_DEPTH].glTex, images_[IMG_NEAR].glTex, w, h};
        return true;
    }

    bool glRender(const FrameInputs& in, const GlFrameIO* io, double* gpuMs, std::string& err) override {
        if (!attached_) return fail(err, "aether_gl_attach first");
        uint32_t w = in.frame.dims[0], h = in.frame.dims[1];
        if (w != width_ || h != height_) return fail(err, "frame size differs from the attached targets (re-attach)");
        Slot& s = slots_[frameIndex_ % SLOTS];
        if (!waitSlot(s, gpuMs, err)) return false;  // frame N-2: normally long done
        FrameGpu frame = in.frame;
        if (in.wantNearDepth) frame.dims[2] |= 0x20u;  // FRAME_NEAR_DEPTH: read from the shared image
        if (!prepare(s, in, frame, false, err)) return false;
        if (!ensure(s.bufs[B_NEAR], size_t(w) * h * 4, false, Mem::DeviceOnly, err)) return false;
        refreshDescriptors(s);

        if (io && io->nearDepthTex && !linearizeNearDepth(*io, w, h, err)) return false;
        if (sunSize_ != sunImageSize_) {
            for (auto& o : slots_)
                if (!waitSlot(o, nullptr, err)) return false;
            destroyShared(sunImage_);
            sunImageSize_ = 0;
            if (sunSize_) {
                if (!createShared(sunImage_, VK_FORMAT_R32G32_SFLOAT, GL_RG32F, sunSize_, sunSize_ + 1, err)) return false;
                sunImageSize_ = sunSize_;
                sunImageFresh_ = true;
            }
        }

        // GL releases the shared images to Vulkan (after everything GL queued so far, e.g. last frame's composite)
        GLuint tex[IMAGES + 1];
        GLenum layouts[IMAGES + 1];
        for (int i = 0; i < IMAGES; i++) {
            tex[i] = images_[i].glTex;
            layouts[i] = GL_LAYOUT_GENERAL_EXT;
        }
        GLuint nTex = IMAGES;
        if (sunImage_.glTex) {
            tex[IMAGES] = sunImage_.glTex;
            layouts[IMAGES] = GL_LAYOUT_GENERAL_EXT;
            nTex++;
        }
        gl_.SignalSemaphoreEXT(s.glReady.gl, 0, nullptr, nTex, tex, layouts);
        gl_.Flush();

        begin(s);
        imageBarriers(s.cmd, true);
        uint32_t pcv[2] = {w, h};
        if (in.wantNearDepth) {
            vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ingestPipe_);
            vkCmdBindDescriptorSets(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, ingestLayout_, 0, 1, &s.ingestSet, 0, nullptr);
            vkCmdPushConstants(s.cmd, ingestLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pcv), pcv);
            vkCmdDispatch(s.cmd, (w + 7) / 8, (h + 7) / 8, 1);
            computeBarrier(s.cmd);
        }
        recordFar(s, w, h, (frame.dims[2] & 0x1u) != 0);
        computeBarrier(s.cmd);
        if (sunDispatchRows_ && sunImage_.image) copySunToImage(s.cmd);
        vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, publishPipe_);
        vkCmdBindDescriptorSets(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, publishLayout_, 0, 1, &s.publishSet, 0, nullptr);
        vkCmdPushConstants(s.cmd, publishLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pcv), pcv);
        vkCmdDispatch(s.cmd, (w + 7) / 8, (h + 7) / 8, 1);
        imageBarriers(s.cmd, false);
        if (!submit(s, &s.glReady.sem, &s.vkDone.sem, err)) return false;
        firstGlFrame_ = false;
        frameIndex_++;

        // GL commands after this point wait for the Vulkan frame
        gl_.WaitSemaphoreEXT(s.vkDone.gl, 0, nullptr, nTex, tex, layouts);
        if (io) {
            // hand the frame to the caller's own textures (e.g. what a shader pack samples via customTexture)
            const GLuint dst[3] = {io->dstColor, io->dstNormal, io->dstDepth};
            for (int i = 0; i < 3; i++)
                if (dst[i])
                    gl_.CopyImageSubData(images_[i].glTex, GL_TEXTURE_2D, 0, 0, 0, 0, dst[i], GL_TEXTURE_2D, 0, 0, 0, 0,
                                         GLsizei(w), GLsizei(h), 1);
            if (io->dstSunMap && sunImage_.glTex)
                gl_.CopyImageSubData(sunImage_.glTex, GL_TEXTURE_2D, 0, 0, 0, 0, io->dstSunMap, GL_TEXTURE_2D, 0, 0, 0, 0,
                                     GLsizei(sunSize_), GLsizei(sunSize_ + 1), 1);
        }
        return true;
    }

    bool stageScene(const PackedScene& sc) override {
        std::lock_guard<std::mutex> lock(sceneMu_);
        int c = current_ == 0 ? 1 : 0;
        if (copies_[c].lastFrame > completedFrame_.load()) return false;  // a frame may still read it
        if (copies_[c].uploading && vkGetFenceStatus(dev_, copies_[c].uploadFence) != VK_SUCCESS) return false;
        std::string err;
        if (!uploadScene(copies_[c], sc, false, err)) return false;
        staged_ = c;
        return true;
    }

    void glDetach() override {
        if (dev_) vkDeviceWaitIdle(dev_);
        for (auto& s : slots_) s.inFlight = false;
        destroyInterop();
    }

private:
    static bool fail(std::string& err, const char* m) {
        err = m;
        return false;
    }

    // GL state the pass touches is saved and restored: Minecraft's renderer caches its own bindings.
    bool linearizeNearDepth(const GlFrameIO& io, uint32_t w, uint32_t h, std::string& err) {
        GLuint depthTex = io.nearDepthTex;
        if (depthTex == 0xFFFFFFFFu) {
            // the depth attachment of whatever draw framebuffer the game (or a shader pack) has bound right now
            GLint fbo = 0, type = 0, name = 0;
            gl_.GetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &fbo);
            if (fbo != 0) {
                gl_.GetNamedFramebufferAttachmentParameteriv(GLuint(fbo), GL_DEPTH_ATTACHMENT,
                                                             GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE, &type);
                if (GLenum(type) == GL_TEXTURE)  // a renderbuffer cannot be sampled
                    gl_.GetNamedFramebufferAttachmentParameteriv(GLuint(fbo), GL_DEPTH_ATTACHMENT,
                                                                 GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME, &name);
            }
            depthTex = GLuint(name);
            if (!depthTex) {
                // nothing to read this frame: mark every pixel uncovered rather than reuse a stale image
                float uncovered = 1e30f;
                gl_.ClearTexImage(images_[IMG_NEAR].glTex, 0, GL_RED, GL_FLOAT, &uncovered);
                return true;
            }
        }
        if (!linProgram_) {
            GLuint sh = gl_.CreateShader(GL_COMPUTE_SHADER);
            gl_.ShaderSource(sh, 1, &LINEARIZE_GLSL, nullptr);
            gl_.CompileShader(sh);
            GLint ok = 0;
            gl_.GetShaderiv(sh, GL_COMPILE_STATUS, &ok);
            if (!ok) {
                char log[1024] = {};
                gl_.GetShaderInfoLog(sh, sizeof(log), nullptr, log);
                gl_.DeleteShader(sh);
                err = std::string("near-depth GL shader: ") + log;
                return false;
            }
            linProgram_ = gl_.CreateProgram();
            gl_.AttachShader(linProgram_, sh);
            gl_.LinkProgram(linProgram_);
            gl_.DeleteShader(sh);
            gl_.GetProgramiv(linProgram_, GL_LINK_STATUS, &ok);
            if (!ok) return fail(err, "near-depth GL program link failed");
            uInvProj_ = gl_.GetUniformLocation(linProgram_, "aetherInvProj");
            uSize_ = gl_.GetUniformLocation(linProgram_, "aetherSize");
            uZeroToOne_ = gl_.GetUniformLocation(linProgram_, "aetherZeroToOne");
            uReversed_ = gl_.GetUniformLocation(linProgram_, "aetherReversed");
            gl_.CreateSamplers(1, &linSampler_);
            gl_.SamplerParameteri(linSampler_, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            gl_.SamplerParameteri(linSampler_, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            gl_.SamplerParameteri(linSampler_, GL_TEXTURE_COMPARE_MODE, GL_NONE);
        }
        gl_.ProgramUniformMatrix4fv(linProgram_, uInvProj_, 1, 0, io.nearInvProjection);
        gl_.ProgramUniform2f(linProgram_, uSize_, float(w), float(h));
        GLint zeroToOne = GLint(io.depthZeroToOne);
        if (io.depthZeroToOne == 0xFFFFFFFFu) {
            GLint mode = 0;
            gl_.GetIntegerv(GL_CLIP_DEPTH_MODE, &mode);
            zeroToOne = GLenum(mode) == GL_ZERO_TO_ONE ? 1 : 0;
        }
        gl_.ProgramUniform1i(linProgram_, uZeroToOne_, zeroToOne);
        gl_.ProgramUniform1i(linProgram_, uReversed_, GLint(io.reversedZ));
        GLint prevProgram = 0, prevActive = 0, prevTex = 0, prevSampler = 0, prevImage = 0;
        gl_.GetIntegerv(GL_CURRENT_PROGRAM, &prevProgram);
        gl_.GetIntegerv(GL_ACTIVE_TEXTURE, &prevActive);
        gl_.ActiveTexture(GL_TEXTURE0 + 15);
        gl_.GetIntegerv(GL_TEXTURE_BINDING_2D, &prevTex);
        gl_.GetIntegerv(GL_SAMPLER_BINDING, &prevSampler);
        gl_.GetIntegeri_v(GL_IMAGE_BINDING_NAME, 7, &prevImage);
        gl_.ActiveTexture(GLenum(prevActive));

        gl_.UseProgram(linProgram_);
        gl_.BindTextureUnit(15, depthTex);
        gl_.BindSampler(15, linSampler_);
        gl_.BindImageTexture(7, images_[IMG_NEAR].glTex, 0, 0, 0, GL_WRITE_ONLY, GL_R32F);
        gl_.DispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
        gl_.MemoryBarrierGL(GL_ALL_BARRIER_BITS);

        gl_.UseProgram(GLuint(prevProgram));
        gl_.BindTextureUnit(15, GLuint(prevTex));
        gl_.BindSampler(15, GLuint(prevSampler));
        gl_.BindImageTexture(7, GLuint(prevImage), 0, 0, 0, GL_WRITE_ONLY, GL_R32F);
        return true;
    }

    // The 64^3 RGBA8 cloud noise as a sampled 3D image (linear filter, repeat): one hardware trilinear fetch per
    // noise lookup instead of eight buffer reads. Uploaded through a staging buffer; callers waited all slots.
    bool uploadNoiseTexture(const std::vector<uint32_t>& vol, std::string& err) {
        if (!noiseImg_) {
            VkImageCreateInfo ic{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
            ic.imageType = VK_IMAGE_TYPE_3D;
            ic.format = VK_FORMAT_R8G8B8A8_UNORM;
            ic.extent = {64, 64, 64};
            ic.mipLevels = 1;
            ic.arrayLayers = 1;
            ic.samples = VK_SAMPLE_COUNT_1_BIT;
            ic.tiling = VK_IMAGE_TILING_OPTIMAL;
            ic.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
            if (vkCreateImage(dev_, &ic, nullptr, &noiseImg_) != VK_SUCCESS) return fail(err, "noise image");
            VkMemoryRequirements req;
            vkGetImageMemoryRequirements(dev_, noiseImg_, &req);
            VkMemoryAllocateInfo ma{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
            ma.allocationSize = req.size;
            ma.memoryTypeIndex = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (vkAllocateMemory(dev_, &ma, nullptr, &noiseMem_) != VK_SUCCESS) return fail(err, "noise memory");
            vkBindImageMemory(dev_, noiseImg_, noiseMem_, 0);
            VkImageViewCreateInfo vc{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
            vc.image = noiseImg_;
            vc.viewType = VK_IMAGE_VIEW_TYPE_3D;
            vc.format = VK_FORMAT_R8G8B8A8_UNORM;
            vc.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            if (vkCreateImageView(dev_, &vc, nullptr, &noiseView_) != VK_SUCCESS) return fail(err, "noise view");
            VkSamplerCreateInfo sc{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
            sc.magFilter = VK_FILTER_LINEAR;
            sc.minFilter = VK_FILTER_LINEAR;
            sc.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
            sc.addressModeU = sc.addressModeV = sc.addressModeW = VK_SAMPLER_ADDRESS_MODE_REPEAT;
            if (vkCreateSampler(dev_, &sc, nullptr, &noiseSampler_) != VK_SUCCESS) return fail(err, "noise sampler");
        }
        if (!upload(noiseStaging_, vol.data(), vol.size() * 4, false, Mem::Staging, err)) return false;
        VkCommandBuffer cmd = copies_[0].uploadCmd;  // idle: callers waited every slot and upload under sceneMu_
        std::lock_guard<std::mutex> scene(sceneMu_);
        if (copies_[0].uploading) vkWaitForFences(dev_, 1, &copies_[0].uploadFence, VK_TRUE, UINT64_MAX);
        copies_[0].uploading = false;
        vkResetCommandBuffer(cmd, 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(cmd, &bi);
        VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
        b.image = noiseImg_;
        b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0,
                             nullptr, 1, &b);
        VkBufferImageCopy region{};
        region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        region.imageExtent = {64, 64, 64};
        vkCmdCopyBufferToImage(cmd, noiseStaging_.buf, noiseImg_, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
        b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
        b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0,
                             nullptr, 1, &b);
        vkEndCommandBuffer(cmd);
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd;
        vkResetFences(dev_, 1, &copies_[0].uploadFence);
        {
            std::lock_guard<std::mutex> q(queueMu_);
            if (vkQueueSubmit(queue_, 1, &si, copies_[0].uploadFence) != VK_SUCCESS) return fail(err, "noise upload");
        }
        vkWaitForFences(dev_, 1, &copies_[0].uploadFence, VK_TRUE, UINT64_MAX);
        return true;
    }

    // ---- setup ---------------------------------------------------------------------------------------------

    bool createInstance(std::string& err) {
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "aether_core";
        app.apiVersion = VK_API_VERSION_1_3;
        VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        ici.pApplicationInfo = &app;
        const char* layers[] = {"VK_LAYER_KHRONOS_validation"};
        const char* instExt[] = {VK_EXT_DEBUG_UTILS_EXTENSION_NAME};
        const char* val = std::getenv("AETHER_VK_VALIDATION");
        bool validate = val && *val == '1';
        if (validate) {
            ici.enabledLayerCount = 1;
            ici.ppEnabledLayerNames = layers;
            ici.enabledExtensionCount = 1;
            ici.ppEnabledExtensionNames = instExt;
        }
        if (vkCreateInstance(&ici, nullptr, &inst_) != VK_SUCCESS) return fail(err, "vkCreateInstance failed");
        if (validate) {
            auto create = reinterpret_cast<PFN_vkCreateDebugUtilsMessengerEXT>(
                vkGetInstanceProcAddr(inst_, "vkCreateDebugUtilsMessengerEXT"));
            VkDebugUtilsMessengerCreateInfoEXT mi{VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT};
            mi.messageSeverity = VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
            mi.messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT;
            mi.pfnUserCallback = onValidation;
            if (create) create(inst_, &mi, nullptr, &messenger_);
            std::fprintf(stderr, "[vulkan] validation %s\n", messenger_ ? "on" : "requested but unavailable");
        }
        return true;
    }

    bool pickDevice(std::string& err) {
        uint32_t n = 0;
        vkEnumeratePhysicalDevices(inst_, &n, nullptr);
        std::vector<VkPhysicalDevice> devs(n);
        vkEnumeratePhysicalDevices(inst_, &n, devs.data());
        int best = -1, bestScore = -1;
        for (uint32_t i = 0; i < n; i++) {
            VkPhysicalDeviceProperties p;
            vkGetPhysicalDeviceProperties(devs[i], &p);
            VkPhysicalDeviceFeatures f;
            vkGetPhysicalDeviceFeatures(devs[i], &f);
            if (!f.shaderInt64 || p.apiVersion < VK_API_VERSION_1_3) continue;
            int score = p.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 2 : 1;
            if (score > bestScore) {
                bestScore = score;
                best = int(i);
            }
        }
        if (best < 0) return fail(err, "no Vulkan 1.3 device with shaderInt64");
        phys_ = devs[size_t(best)];
        VkPhysicalDeviceIDProperties idp{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ID_PROPERTIES};
        VkPhysicalDeviceProperties2 props2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
        props2.pNext = &idp;
        vkGetPhysicalDeviceProperties2(phys_, &props2);
        std::memcpy(deviceUuid_, idp.deviceUUID, VK_UUID_SIZE);
        std::snprintf(deviceName_, sizeof(deviceName_), "vulkan (%s)", props2.properties.deviceName);
        timestampPeriod_ = props2.properties.limits.timestampPeriod;
        uint32_t qn = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(phys_, &qn, nullptr);
        std::vector<VkQueueFamilyProperties> qf(qn);
        vkGetPhysicalDeviceQueueFamilyProperties(phys_, &qn, qf.data());
        family_ = UINT32_MAX;
        for (uint32_t i = 0; i < qn; i++)
            if (qf[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
                family_ = i;
                timestamps_ = qf[i].timestampValidBits > 0;
                break;
            }
        return family_ != UINT32_MAX || fail(err, "no compute queue");
    }

    bool createDevice(std::string& err) {
        // interop extensions are optional: without them the host path still works
        uint32_t en = 0;
        vkEnumerateDeviceExtensionProperties(phys_, nullptr, &en, nullptr);
        std::vector<VkExtensionProperties> ext(en);
        vkEnumerateDeviceExtensionProperties(phys_, nullptr, &en, ext.data());
        auto has = [&](const char* nm) {
            for (auto& e : ext)
                if (std::strcmp(e.extensionName, nm) == 0) return true;
            return false;
        };
        std::vector<const char*> enable;
        if (has(VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME) && has(VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME)) {
            enable.push_back(VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME);
            enable.push_back(VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME);
            interopCapable_ = true;
        }
        float prio = 1.0f;
        VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        qci.queueFamilyIndex = family_;
        qci.queueCount = 1;
        qci.pQueuePriorities = &prio;
        VkPhysicalDeviceFeatures feats{};
        feats.shaderInt64 = VK_TRUE;
        VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        dci.queueCreateInfoCount = 1;
        dci.pQueueCreateInfos = &qci;
        dci.pEnabledFeatures = &feats;
        dci.enabledExtensionCount = uint32_t(enable.size());
        dci.ppEnabledExtensionNames = enable.data();
        if (vkCreateDevice(phys_, &dci, nullptr, &dev_) != VK_SUCCESS) return fail(err, "vkCreateDevice failed");
        vkGetDeviceQueue(dev_, family_, 0, &queue_);
        if (interopCapable_) {
            getMemoryHandle_ = reinterpret_cast<PFN_vkGetMemoryWin32HandleKHR>(vkGetDeviceProcAddr(dev_, "vkGetMemoryWin32HandleKHR"));
            getSemaphoreHandle_ = reinterpret_cast<PFN_vkGetSemaphoreWin32HandleKHR>(vkGetDeviceProcAddr(dev_, "vkGetSemaphoreWin32HandleKHR"));
            interopCapable_ = getMemoryHandle_ && getSemaphoreHandle_;
        }
        return true;
    }

    bool createPipe(const uint32_t* code, size_t bytes, const std::vector<VkDescriptorType>& types, uint32_t pushBytes,
                    VkPipeline& pipe, VkPipelineLayout& layout, VkDescriptorSetLayout& setLayout, VkShaderModule& module,
                    std::string& err) {
        VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        smci.codeSize = bytes;
        smci.pCode = code;
        if (vkCreateShaderModule(dev_, &smci, nullptr, &module) != VK_SUCCESS) return fail(err, "shader module");
        std::vector<VkDescriptorSetLayoutBinding> b(types.size());
        for (uint32_t i = 0; i < types.size(); i++) b[i] = {i, types[i], 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        VkDescriptorSetLayoutCreateInfo dl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        dl.bindingCount = uint32_t(b.size());
        dl.pBindings = b.data();
        if (vkCreateDescriptorSetLayout(dev_, &dl, nullptr, &setLayout) != VK_SUCCESS) return fail(err, "set layout");
        VkPushConstantRange pr{VK_SHADER_STAGE_COMPUTE_BIT, 0, pushBytes};
        VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        pl.setLayoutCount = 1;
        pl.pSetLayouts = &setLayout;
        pl.pushConstantRangeCount = pushBytes ? 1 : 0;
        pl.pPushConstantRanges = pushBytes ? &pr : nullptr;
        if (vkCreatePipelineLayout(dev_, &pl, nullptr, &layout) != VK_SUCCESS) return fail(err, "pipeline layout");
        VkComputePipelineCreateInfo cp{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        cp.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
        cp.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        cp.stage.module = module;
        cp.stage.pName = "main";
        cp.layout = layout;
        if (vkCreateComputePipelines(dev_, VK_NULL_HANDLE, 1, &cp, nullptr, &pipe) != VK_SUCCESS) return fail(err, "pipeline");
        return true;
    }

    void destroyPipe(VkPipeline& p, VkPipelineLayout& l, VkDescriptorSetLayout& s, VkShaderModule& m) {
        if (p) vkDestroyPipeline(dev_, p, nullptr);
        if (l) vkDestroyPipelineLayout(dev_, l, nullptr);
        if (s) vkDestroyDescriptorSetLayout(dev_, s, nullptr);
        if (m) vkDestroyShaderModule(dev_, m, nullptr);
        p = VK_NULL_HANDLE;
        l = VK_NULL_HANDLE;
        s = VK_NULL_HANDLE;
        m = VK_NULL_HANDLE;
    }

    // ---- per frame -------------------------------------------------------------------------------------------

    bool prepare(Slot& s, const FrameInputs& in, const FrameGpu& frame, bool readback, std::string& err) {
        uint32_t w = frame.dims[0], h = frame.dims[1];
        size_t px = size_t(w) * h;
        const PackedScene& sc = *in.scene;
        {
            // never block the frame on a stage in progress: keep drawing the current copy until it is done
            std::unique_lock<std::mutex> lock(sceneMu_, std::try_to_lock);
            if (!lock.owns_lock() && current_ < 0) lock.lock();
            // the very first frame has nothing else to draw: it waits for a staged upload still in flight
            if (lock.owns_lock() && current_ < 0 && staged_ >= 0 && copies_[staged_].revision == sc.revision)
                vkWaitForFences(dev_, 1, &copies_[staged_].uploadFence, VK_TRUE, UINT64_MAX);
            if (lock.owns_lock() && staged_ >= 0 && copies_[staged_].revision == sc.revision &&
                vkGetFenceStatus(dev_, copies_[staged_].uploadFence) == VK_SUCCESS) {
                current_ = staged_;  // staged off the render thread: just flip
                staged_ = -1;
            }
            bool stale = current_ >= 0 && copies_[current_].revision != sc.revision;
            staleFrames_ = stale ? staleFrames_ + 1 : 0;
            // a newer scene the update thread could not stage (both copies were busy) is normally staged by its
            // next call within a frame or two; only upload here for the first scene or when it stays stale
            if (lock.owns_lock() && (current_ < 0 || (stale && staleFrames_ > 30)) &&
                (staged_ < 0 || copies_[staged_].revision != sc.revision)) {
                // not staged (no update thread, or both copies were busy): upload here, once nothing reads it
                int c = current_ < 0 ? 0 : current_;
                for (auto& o : slots_)
                    if (!waitSlot(o, nullptr, err)) return false;
                if (!uploadScene(copies_[c], sc, true, err)) return false;
                current_ = c;
                if (staged_ == c) staged_ = -1;
            }
        }
        if (in.cloudNoise->data() != noiseUploaded_ || !shared_[B_NOISE].buf) {
            for (auto& o : slots_)
                if (!waitSlot(o, nullptr, err)) return false;
            if (!upload(shared_[B_NOISE], in.cloudNoise->data(), in.cloudNoise->size() * 4, false, Mem::HostWrite, err)) return false;
            if (!uploadNoiseTexture(*in.cloudNoise, err)) return false;
            noiseUploaded_ = in.cloudNoise->data();
            sharedDirty_ = true;  // every slot's set re-points at the new image
            sharedDirtySlots_ = 0;
        }
        // the sun map: one persistent device buffer (frames run in order on our queue), progressively refreshed
        {
            uint32_t S = frame.sunMapRows[3] ? frame.sunMapRows[2] : 0;
            VkDeviceSize bytes = S ? VkDeviceSize(S) * (S + 1) * 8 : 16;
            if (S != sunSize_ || !shared_[B_SUNMAP].buf) {
                for (auto& o : slots_)
                    if (!waitSlot(o, nullptr, err)) return false;
                release(shared_[B_SUNMAP]);
                if (!ensure(shared_[B_SUNMAP], bytes, false, Mem::DeviceOnly, err)) return false;
                sunSize_ = S;
                sharedDirty_ = true;
                sharedDirtySlots_ = 0;
            }
            sunDispatchRows_ = S ? frame.sunMapRows[1] : 0;
        }
        // host path: outputs in cached system memory (CPU reads from BAR are uncached); GL path: device local only
        Mem outKind = readback ? Mem::Readback : Mem::DeviceOnly;
        cloudDiv_ = (frame.dims[2] & 0x80u) ? 4u : 2u;  // FRAME_CLOUDS_QUARTER
        bool ok = upload(s.bufs[B_FRAME], &frame, sizeof(FrameGpu), true, Mem::HostWrite, err) &&
                  upload(s.bufs[B_PALETTE], in.palette->data(), in.palette->size() * sizeof(MaterialGpu), false, Mem::HostWrite, err) &&
                  upload(s.bufs[B_WEATHER], in.weather->data(), in.weather->size() * 4, false, Mem::HostWrite, err) &&
                  upload(s.bufs[B_ACTORS], in.actors.data(), in.actors.size() * sizeof(ActorGpu), false, Mem::HostWrite, err) &&
                  ensure(s.bufs[B_COLOR], px * 16, false, outKind, err) && ensure(s.bufs[B_NORMAL], px * 4, false, outKind, err) &&
                  ensure(s.bufs[B_DEPTH], px * 4, false, outKind, err) && ensure(s.bufs[B_COUNTERS], px * 4, false, outKind, err) &&
                  ensure(s.bufs[B_NEAR], 16, false, readback ? Mem::HostWrite : Mem::DeviceOnly, err) &&
                  ensure(s.bufs[B_CLOUD], size_t((w + 1) / 2) * ((h + 1) / 2) * 16, false, Mem::DeviceOnly, err) &&
                  ensure(s.bufs[B_CLOUD_DEPTH], size_t((w + 1) / 2) * ((h + 1) / 2) * 4, false, Mem::DeviceOnly, err);
        if (ok) {
            VkBuffer before = s.bufs[B_SURFACE].buf;
            ok = ensure(s.bufs[B_SURFACE], px * 4, false, Mem::DeviceOnly, err);
            if (ok && s.bufs[B_SURFACE].buf != before) s.surfaceFresh = true;  // filled with "no surface" in record
        }
        return ok;
    }

    void refreshDescriptors(Slot& s) {
        const SceneCopy& sc = copies_[current_];
        int prev = prevSlot_ < 0 ? int(&s - slots_) : prevSlot_;
        VkBuffer prevBuf = slots_[prev].bufs[B_SURFACE].buf;  // (its cloud buffers are allocated with it)
        if (s.farDirty || s.boundCopy != current_ || s.boundGeneration != sc.generation || sharedDirty_ || s.boundPrev != prevBuf) {
            s.boundPrev = prevBuf;
            VkDescriptorBufferInfo info[BINDINGS];
            VkWriteDescriptorSet wr[BINDINGS];
            VkDescriptorImageInfo noiseInfo{noiseSampler_, noiseView_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
            for (uint32_t i = 0; i < BINDINGS; i++) {
                if (i == B_NOISE_TEX) {
                    wr[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
                    wr[i].dstSet = s.farSet;
                    wr[i].dstBinding = i;
                    wr[i].descriptorCount = 1;
                    wr[i].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                    wr[i].pImageInfo = &noiseInfo;
                    continue;
                }
                const Slot& ps = slots_[prev];
                const Buf& b = i == B_PREV_SURFACE ? ps.bufs[B_SURFACE]
                             : i == B_PREV_CLOUD ? ps.bufs[B_CLOUD]
                             : i == B_PREV_CLOUD_DEPTH ? ps.bufs[B_CLOUD_DEPTH]
                             : perSlot(i) ? s.bufs[i] : (isScene(i) ? sc.bufs[i] : shared_[i]);
                info[i] = {b.buf, 0, VK_WHOLE_SIZE};
                wr[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
                wr[i].dstSet = s.farSet;
                wr[i].dstBinding = i;
                wr[i].descriptorCount = 1;
                wr[i].descriptorType = i == B_FRAME ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
                wr[i].pBufferInfo = &info[i];
            }
            vkUpdateDescriptorSets(dev_, BINDINGS, wr, 0, nullptr);
            s.farDirty = false;
            s.boundCopy = current_;
            s.boundGeneration = sc.generation;
            sharedDirtySlots_ |= 1u << uint32_t(&s - slots_);
            if (sharedDirtySlots_ == (1u << SLOTS) - 1) sharedDirty_ = false;
        }
        if (attached_ && s.publishDirty) {
            VkDescriptorBufferInfo bi[3] = {{s.bufs[B_COLOR].buf, 0, VK_WHOLE_SIZE},
                                            {s.bufs[B_NORMAL].buf, 0, VK_WHOLE_SIZE},
                                            {s.bufs[B_DEPTH].buf, 0, VK_WHOLE_SIZE}};
            VkDescriptorImageInfo ii[3];
            VkWriteDescriptorSet wr[6];
            for (uint32_t i = 0; i < 6; i++) {
                wr[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
                wr[i].dstSet = s.publishSet;
                wr[i].dstBinding = i;
                wr[i].descriptorCount = 1;
                if (i < 3) {
                    wr[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
                    wr[i].pBufferInfo = &bi[i];
                } else {
                    ii[i - 3] = {VK_NULL_HANDLE, images_[i - 3].view, VK_IMAGE_LAYOUT_GENERAL};
                    wr[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                    wr[i].pImageInfo = &ii[i - 3];
                }
            }
            vkUpdateDescriptorSets(dev_, 6, wr, 0, nullptr);
            s.publishDirty = false;
        }
        if (attached_ && s.ingestDirty) {
            VkDescriptorImageInfo ii{VK_NULL_HANDLE, images_[IMG_NEAR].view, VK_IMAGE_LAYOUT_GENERAL};
            VkDescriptorBufferInfo bi{s.bufs[B_NEAR].buf, 0, VK_WHOLE_SIZE};
            VkWriteDescriptorSet wr[2] = {{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}, {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET}};
            wr[0].dstSet = wr[1].dstSet = s.ingestSet;
            wr[0].dstBinding = 0;
            wr[0].descriptorCount = 1;
            wr[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            wr[0].pImageInfo = &ii;
            wr[1].dstBinding = 1;
            wr[1].descriptorCount = 1;
            wr[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            wr[1].pBufferInfo = &bi;
            vkUpdateDescriptorSets(dev_, 2, wr, 0, nullptr);
            s.ingestDirty = false;
        }
    }

    void begin(Slot& s) {
        vkResetCommandBuffer(s.cmd, 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(s.cmd, &bi);
        if (s.query) {
            vkCmdResetQueryPool(s.cmd, s.query, 0, 2);
            // at the compute stage, which the GL-ready semaphore wait blocks: the time is aether's own work only
            vkCmdWriteTimestamp(s.cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, s.query, 0);
        }
    }

    void recordFar(Slot& s, uint32_t w, uint32_t h, bool clouds) {
        // the previous frame (other slot) wrote the surfaces this one's cloud pass reads, and will next overwrite
        // ours: order against everything submitted before
        computeBarrier(s.cmd);
        if (s.surfaceFresh) {
            vkCmdFillBuffer(s.cmd, s.bufs[B_SURFACE].buf, 0, VK_WHOLE_SIZE, 0x7149F2CAu);  // 1e30f: no surface yet
            VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
            mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            vkCmdPipelineBarrier(s.cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0,
                                 nullptr, 0, nullptr);
            s.surfaceFresh = false;
        }
        vkCmdBindDescriptorSets(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, farLayout_, 0, 1, &s.farSet, 0, nullptr);
        if (clouds) {
            vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, cloudPipe_);
            uint32_t dv = cloudDiv_;
            vkCmdDispatch(s.cmd, ((w + dv - 1) / dv + 7) / 8, ((h + dv - 1) / dv + 7) / 8, 1);
            computeBarrier(s.cmd);
        }
        vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, farPipe_);
        vkCmdDispatch(s.cmd, (w + 7) / 8, (h + 7) / 8, 1);
        if (sunDispatchRows_) {
            vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, sunPipe_);
            vkCmdDispatch(s.cmd, (sunSize_ + 7) / 8, (sunDispatchRows_ + 1 + 7) / 8, 1);
        }
        prevSlot_ = int(&s - slots_);
    }

    static void computeBarrier(VkCommandBuffer cmd) {
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0,
                             nullptr, 0, nullptr);
    }

    bool submit(Slot& s, VkSemaphore* wait, VkSemaphore* signal, std::string& err) {
        if (s.query) vkCmdWriteTimestamp(s.cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, s.query, 1);
        vkEndCommandBuffer(s.cmd);
        VkPipelineStageFlags stage = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &s.cmd;
        if (wait) {
            si.waitSemaphoreCount = 1;
            si.pWaitSemaphores = wait;
            si.pWaitDstStageMask = &stage;
        }
        if (signal) {
            si.signalSemaphoreCount = 1;
            si.pSignalSemaphores = signal;
        }
        vkResetFences(dev_, 1, &s.fence);
        {
            std::lock_guard<std::mutex> q(queueMu_);  // the staging thread submits scene copies to the same queue
            if (vkQueueSubmit(queue_, 1, &si, s.fence) != VK_SUCCESS) return fail(err, "submit failed");
        }
        s.inFlight = true;
        s.frameNumber = ++frameCounter_;
        copies_[current_].lastFrame.store(s.frameNumber);  // current_ only changes on this thread
        return true;
    }

    // Waits for the slot's previous submission and reports its GPU time.
    bool waitSlot(Slot& s, double* gpuMs, std::string& err) {
        if (!s.inFlight) return true;
        if (vkWaitForFences(dev_, 1, &s.fence, VK_TRUE, 10'000'000'000ull) != VK_SUCCESS) return fail(err, "GPU timeout/device lost");
        s.inFlight = false;
        // frames complete in submission order on our single queue
        if (s.frameNumber > completedFrame_.load()) completedFrame_.store(s.frameNumber);
        if (s.query && gpuMs) {
            uint64_t ts[2] = {};
            vkGetQueryPoolResults(dev_, s.query, 0, 2, sizeof(ts), ts, 8, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
            *gpuMs = double(ts[1] - ts[0]) * timestampPeriod_ / 1e6;
        }
        return true;
    }

    // acquire: from GL (external queue family) for writing; release: back to GL.
    void imageBarriers(VkCommandBuffer cmd, bool acquire) {
        VkImageMemoryBarrier b[IMAGES];
        for (int i = 0; i < IMAGES; i++) {
            b[i] = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            b[i].image = images_[i].image;
            b[i].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            if (acquire) {
                // nearDepth was cleared by GL at attach: keep its contents even on the first frame
                bool undefined = firstGlFrame_ && i != IMG_NEAR;
                b[i].oldLayout = undefined ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_GENERAL;
                b[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
                b[i].srcQueueFamilyIndex = undefined ? VK_QUEUE_FAMILY_IGNORED : VK_QUEUE_FAMILY_EXTERNAL;
                b[i].dstQueueFamilyIndex = undefined ? VK_QUEUE_FAMILY_IGNORED : family_;
                b[i].srcAccessMask = 0;
                b[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            } else {
                b[i].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
                b[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
                b[i].srcQueueFamilyIndex = family_;
                b[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
                b[i].srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
                b[i].dstAccessMask = 0;
            }
        }
        uint32_t n = IMAGES;
        VkImageMemoryBarrier all[IMAGES + 1];
        for (int i = 0; i < IMAGES; i++) all[i] = b[i];
        if (sunImage_.image) {
            VkImageMemoryBarrier& sb = all[n++];
            sb = b[0];
            sb.image = sunImage_.image;
            bool undefined = acquire && sunImageFresh_;
            if (acquire) {
                sb.oldLayout = undefined ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_GENERAL;
                sb.srcQueueFamilyIndex = undefined ? VK_QUEUE_FAMILY_IGNORED : VK_QUEUE_FAMILY_EXTERNAL;
                sb.dstQueueFamilyIndex = undefined ? VK_QUEUE_FAMILY_IGNORED : family_;
                sb.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            } else {
                sb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            }
            if (acquire) sunImageFresh_ = false;
        }
        vkCmdPipelineBarrier(cmd, acquire ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT |
                                                                                   VK_PIPELINE_STAGE_TRANSFER_BIT,
                             acquire ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT
                                     : VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                             0, 0, nullptr, 0, nullptr, n, all);
    }

    // The sun map buffer into its shared image (whole image: a few MB, a few microseconds of copy).
    void copySunToImage(VkCommandBuffer cmd) {
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &mb, 0, nullptr,
                             0, nullptr);
        VkBufferImageCopy region{};
        region.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
        region.imageExtent = {sunSize_, sunSize_ + 1, 1};
        vkCmdCopyBufferToImage(cmd, shared_[B_SUNMAP].buf, sunImage_.image, VK_IMAGE_LAYOUT_GENERAL, 1, &region);
    }

    void destroyShared(SharedImage& s) {
        bool glOk = wglGetCurrentContext() != nullptr;
        if (glOk) {
            if (s.glTex) gl_.DeleteTextures(1, &s.glTex);
            if (s.glMem) gl_.DeleteMemoryObjectsEXT(1, &s.glMem);
        }
        if (s.view) vkDestroyImageView(dev_, s.view, nullptr);
        if (s.image) vkDestroyImage(dev_, s.image, nullptr);
        if (s.mem) vkFreeMemory(dev_, s.mem, nullptr);
        if (s.handle) CloseHandle(s.handle);
        s = {};
    }

public:
    bool readSunMap(std::vector<float>& out, std::string& err) override {
        out.clear();
        if (!sunSize_ || !shared_[B_SUNMAP].buf) return true;
        for (auto& o : slots_)
            if (!waitSlot(o, nullptr, err)) return false;
        VkDeviceSize bytes = VkDeviceSize(sunSize_) * (sunSize_ + 1) * 8;
        if (!ensure(sunReadback_, bytes, false, Mem::Readback, err)) return false;
        std::lock_guard<std::mutex> scene(sceneMu_);
        if (copies_[0].uploading) vkWaitForFences(dev_, 1, &copies_[0].uploadFence, VK_TRUE, UINT64_MAX);
        copies_[0].uploading = false;
        VkCommandBuffer cmd = copies_[0].uploadCmd;
        vkResetCommandBuffer(cmd, 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(cmd, &bi);
        VkBufferCopy rc{0, 0, bytes};
        vkCmdCopyBuffer(cmd, shared_[B_SUNMAP].buf, sunReadback_.buf, 1, &rc);
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
        vkEndCommandBuffer(cmd);
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd;
        vkResetFences(dev_, 1, &copies_[0].uploadFence);
        {
            std::lock_guard<std::mutex> q(queueMu_);
            if (vkQueueSubmit(queue_, 1, &si, copies_[0].uploadFence) != VK_SUCCESS) return fail(err, "sun map readback");
        }
        vkWaitForFences(dev_, 1, &copies_[0].uploadFence, VK_TRUE, UINT64_MAX);
        out.resize(size_t(bytes / 4));
        std::memcpy(out.data(), sunReadback_.map, size_t(bytes));
        return true;
    }

private:

    // ---- resources -------------------------------------------------------------------------------------------

    uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags need) {
        VkPhysicalDeviceMemoryProperties mp;
        vkGetPhysicalDeviceMemoryProperties(phys_, &mp);
        for (uint32_t t = 0; t < mp.memoryTypeCount; t++)
            if ((bits & (1u << t)) && (mp.memoryTypes[t].propertyFlags & need) == need) return t;
        return UINT32_MAX;
    }

    bool createShared(SharedImage& s, VkFormat fmt, GLenum glFmt, uint32_t w, uint32_t h, std::string& err) {
        VkExternalMemoryImageCreateInfo ext{VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
        ext.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
        VkImageCreateInfo ic{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        ic.pNext = &ext;
        ic.imageType = VK_IMAGE_TYPE_2D;
        ic.format = fmt;
        ic.extent = {w, h, 1};
        ic.mipLevels = 1;
        ic.arrayLayers = 1;
        ic.samples = VK_SAMPLE_COUNT_1_BIT;
        ic.tiling = VK_IMAGE_TILING_OPTIMAL;
        ic.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT |
                   VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        ic.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        if (vkCreateImage(dev_, &ic, nullptr, &s.image) != VK_SUCCESS) return fail(err, "vkCreateImage (shared)");
        VkMemoryRequirements req;
        vkGetImageMemoryRequirements(dev_, s.image, &req);
        VkMemoryDedicatedAllocateInfo ded{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
        ded.image = s.image;
        VkExportMemoryAllocateInfo exp{VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO};
        exp.pNext = &ded;
        exp.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
        VkMemoryAllocateInfo ma{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ma.pNext = &exp;
        ma.allocationSize = req.size;
        ma.memoryTypeIndex = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (ma.memoryTypeIndex == UINT32_MAX || vkAllocateMemory(dev_, &ma, nullptr, &s.mem) != VK_SUCCESS)
            return fail(err, "vkAllocateMemory (shared)");
        vkBindImageMemory(dev_, s.image, s.mem, 0);
        VkMemoryGetWin32HandleInfoKHR hi{VK_STRUCTURE_TYPE_MEMORY_GET_WIN32_HANDLE_INFO_KHR};
        hi.memory = s.mem;
        hi.handleType = VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT;
        if (getMemoryHandle_(dev_, &hi, &s.handle) != VK_SUCCESS) return fail(err, "vkGetMemoryWin32HandleKHR");
        VkImageViewCreateInfo vc{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        vc.image = s.image;
        vc.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vc.format = fmt;
        vc.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        if (vkCreateImageView(dev_, &vc, nullptr, &s.view) != VK_SUCCESS) return fail(err, "vkCreateImageView");

        gl_.CreateMemoryObjectsEXT(1, &s.glMem);
        GLint dedicated = 1;
        gl_.MemoryObjectParameterivEXT(s.glMem, GL_DEDICATED_MEMORY_OBJECT_EXT, &dedicated);
        gl_.ImportMemoryWin32HandleEXT(s.glMem, req.size, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, s.handle);
        gl_.CreateTextures(GL_TEXTURE_2D, 1, &s.glTex);
        gl_.TextureParameteri(s.glTex, GL_TEXTURE_TILING_EXT, GLint(GL_OPTIMAL_TILING_EXT));
        gl_.TextureStorageMem2DEXT(s.glTex, 1, glFmt, GLsizei(w), GLsizei(h), s.glMem, 0);
        gl_.TextureParameteri(s.glTex, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        gl_.TextureParameteri(s.glTex, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        gl_.TextureParameteri(s.glTex, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        gl_.TextureParameteri(s.glTex, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        return true;
    }

    bool createSemaphore(SharedSemaphore& s, std::string& err) {
        VkExportSemaphoreCreateInfo exp{VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO};
        exp.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
        VkSemaphoreCreateInfo sc{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        sc.pNext = &exp;
        if (vkCreateSemaphore(dev_, &sc, nullptr, &s.sem) != VK_SUCCESS) return fail(err, "vkCreateSemaphore (shared)");
        VkSemaphoreGetWin32HandleInfoKHR hi{VK_STRUCTURE_TYPE_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR};
        hi.semaphore = s.sem;
        hi.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
        if (getSemaphoreHandle_(dev_, &hi, &s.handle) != VK_SUCCESS) return fail(err, "vkGetSemaphoreWin32HandleKHR");
        gl_.GenSemaphoresEXT(1, &s.gl);
        gl_.ImportSemaphoreWin32HandleEXT(s.gl, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, s.handle);
        return true;
    }

    void destroyInterop() {
        if (attached_) {
            destroyShared(sunImage_);
            sunImageSize_ = 0;
        }
        bool glOk = attached_ && wglGetCurrentContext() != nullptr;
        for (auto& s : images_) {
            if (glOk) {
                if (s.glTex) gl_.DeleteTextures(1, &s.glTex);
                if (s.glMem) gl_.DeleteMemoryObjectsEXT(1, &s.glMem);
            }
            if (s.view) vkDestroyImageView(dev_, s.view, nullptr);
            if (s.image) vkDestroyImage(dev_, s.image, nullptr);
            if (s.mem) vkFreeMemory(dev_, s.mem, nullptr);
            if (s.handle) CloseHandle(s.handle);
            s = {};
        }
        for (auto& sl : slots_) {
            for (SharedSemaphore* sem : {&sl.glReady, &sl.vkDone}) {
                if (glOk && sem->gl) gl_.DeleteSemaphoresEXT(1, &sem->gl);
                if (sem->sem) vkDestroySemaphore(dev_, sem->sem, nullptr);
                if (sem->handle) CloseHandle(sem->handle);
                *sem = {};
            }
        }
        if (glOk) {
            if (linProgram_) gl_.DeleteProgram(linProgram_);
            if (linSampler_) gl_.DeleteSamplers(1, &linSampler_);
        }
        linProgram_ = 0;
        linSampler_ = 0;
        attached_ = false;
    }

    void release(Buf& b) {
        if (b.map) vkUnmapMemory(dev_, b.mem);
        if (b.buf) vkDestroyBuffer(dev_, b.buf, nullptr);
        if (b.mem) vkFreeMemory(dev_, b.mem, nullptr);
        b = {};
    }

    // (Re)allocates b when too small or in the wrong memory kind. The caller guarantees the GPU is not using it
    // (per-slot buffers: the slot was waited; shared buffers: all slots were waited).
    bool ensure(Buf& b, VkDeviceSize size, bool uniform, Mem kind, std::string& err) {
        size = size < 16 ? 16 : size;
        if (b.buf && b.size >= size && b.kind == kind) return true;
        release(b);
        VkDeviceSize alloc = size + size / 4;  // headroom against regrowth every frame
        VkBufferCreateInfo bc{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bc.size = alloc;
        bc.usage = (uniform ? VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT : VK_BUFFER_USAGE_STORAGE_BUFFER_BIT) |
                   VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        if (vkCreateBuffer(dev_, &bc, nullptr, &b.buf) != VK_SUCCESS) return fail(err, "vkCreateBuffer");
        VkMemoryRequirements req;
        vkGetBufferMemoryRequirements(dev_, b.buf, &req);
        uint32_t want = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        uint32_t type = UINT32_MAX;
        if (kind == Mem::DeviceOnly) {
            type = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        } else {
            // small inputs: device-local host-visible (BAR) when it fits; readback: host-cached; staging: system memory
            uint32_t extra = kind == Mem::Readback ? VK_MEMORY_PROPERTY_HOST_CACHED_BIT
                           : kind == Mem::HostWrite ? VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT : 0u;
            type = memoryType(req.memoryTypeBits, want | extra);
            if (type == UINT32_MAX) type = memoryType(req.memoryTypeBits, want);
        }
        if (type == UINT32_MAX) return fail(err, "no suitable memory type");
        VkMemoryAllocateInfo ma{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ma.allocationSize = req.size;
        ma.memoryTypeIndex = type;
        if (vkAllocateMemory(dev_, &ma, nullptr, &b.mem) != VK_SUCCESS) {
            // BAR heaps are small on some systems; retry in system memory
            VkPhysicalDeviceMemoryProperties mp;
            vkGetPhysicalDeviceMemoryProperties(phys_, &mp);
            type = UINT32_MAX;
            for (uint32_t t = 0; t < mp.memoryTypeCount; t++)
                if ((req.memoryTypeBits & (1u << t)) && (mp.memoryTypes[t].propertyFlags & want) == want &&
                    !(mp.memoryTypes[t].propertyFlags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)) {
                    type = t;
                    break;
                }
            ma.memoryTypeIndex = type;
            if (type == UINT32_MAX || vkAllocateMemory(dev_, &ma, nullptr, &b.mem) != VK_SUCCESS) return fail(err, "vkAllocateMemory");
        }
        vkBindBufferMemory(dev_, b.buf, b.mem, 0);
        if (kind != Mem::DeviceOnly) vkMapMemory(dev_, b.mem, 0, VK_WHOLE_SIZE, 0, &b.map);
        b.size = alloc;
        b.kind = kind;
        // whichever set points at this buffer must be rewritten
        if (&b >= shared_ && &b < shared_ + BINDINGS) {
            sharedDirty_ = true;
            sharedDirtySlots_ = 0;
        }
        for (auto& s : slots_)
            if (&b >= s.bufs && &b < s.bufs + BINDINGS) {
                s.farDirty = true;
                if (&b == &s.bufs[B_COLOR] || &b == &s.bufs[B_NORMAL] || &b == &s.bufs[B_DEPTH]) s.publishDirty = true;
                if (&b == &s.bufs[B_NEAR]) s.ingestDirty = true;
            }
        return true;
    }

    // Scene arrays -> staging (CPU) -> device-local buffers (GPU copy). The caller holds sceneMu_ and guarantees no
    // frame reads this copy. wait: block until the copy is done (render-thread fallback).
    bool uploadScene(SceneCopy& c, const PackedScene& sc, bool wait, std::string& err) {
        if (c.uploading) vkWaitForFences(dev_, 1, &c.uploadFence, VK_TRUE, UINT64_MAX);
        c.uploading = false;
        const void* src[BINDINGS] = {};
        VkDeviceSize bytes[BINDINGS] = {}, offset[BINDINGS] = {}, total = 0;
        src[1] = sc.table.data();
        bytes[1] = sc.table.size() * 4;
        src[2] = sc.regions.data();
        bytes[2] = sc.regions.size() * sizeof(RegionGpu);
        src[3] = sc.nodes.data();
        bytes[3] = sc.nodes.size() * 4;
        src[4] = sc.leaves.data();
        bytes[4] = sc.leaves.size() * 8;
        src[5] = sc.runEnd.data();
        bytes[5] = sc.runEnd.size() * 4;
        src[6] = sc.runMat.data();
        bytes[6] = sc.runMat.size() * 4;
        src[B_HEIGHTMIP] = sc.heightMip.data();
        bytes[B_HEIGHTMIP] = sc.heightMip.size() * 4;
        for (uint32_t i : SCENE_BINDINGS) {
            offset[i] = total;
            total += (bytes[i] + 255) & ~VkDeviceSize(255);
        }
        bool realloc = false;
        for (uint32_t i : SCENE_BINDINGS) {
            VkBuffer before = c.bufs[i].buf;
            if (!ensure(c.bufs[i], bytes[i], false, Mem::DeviceOnly, err)) return false;
            realloc |= c.bufs[i].buf != before;
        }
        if (realloc) c.generation++;
        if (!ensure(c.staging, total, false, Mem::Staging, err)) return false;
        for (uint32_t i : SCENE_BINDINGS)
            if (bytes[i]) std::memcpy(static_cast<char*>(c.staging.map) + offset[i], src[i], size_t(bytes[i]));
        vkResetCommandBuffer(c.uploadCmd, 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(c.uploadCmd, &bi);
        for (uint32_t i : SCENE_BINDINGS) {
            if (!bytes[i]) continue;
            VkBufferCopy region{offset[i], 0, bytes[i]};
            vkCmdCopyBuffer(c.uploadCmd, c.staging.buf, c.bufs[i].buf, 1, &region);
        }
        // later submissions on this queue (the frames) read what the copies wrote
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(c.uploadCmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb,
                             0, nullptr, 0, nullptr);
        vkEndCommandBuffer(c.uploadCmd);
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &c.uploadCmd;
        vkResetFences(dev_, 1, &c.uploadFence);
        {
            std::lock_guard<std::mutex> q(queueMu_);
            if (vkQueueSubmit(queue_, 1, &si, c.uploadFence) != VK_SUCCESS) return fail(err, "scene upload submit failed");
        }
        c.uploading = true;
        c.revision = sc.revision;
        if (wait) vkWaitForFences(dev_, 1, &c.uploadFence, VK_TRUE, UINT64_MAX);
        return true;
    }

    bool upload(Buf& b, const void* data, size_t bytes, bool uniform, Mem kind, std::string& err) {
        if (!ensure(b, bytes, uniform, kind, err)) return false;
        if (bytes) std::memcpy(b.map, data, bytes);
        return true;
    }

    VkInstance inst_ = VK_NULL_HANDLE;
    VkDebugUtilsMessengerEXT messenger_ = VK_NULL_HANDLE;
    VkPhysicalDevice phys_ = VK_NULL_HANDLE;
    VkDevice dev_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t family_ = 0;
    uint8_t deviceUuid_[VK_UUID_SIZE] = {};
    char deviceName_[300] = "vulkan";
    bool timestamps_ = false;
    float timestampPeriod_ = 1.0f;

    VkShaderModule farShader_ = VK_NULL_HANDLE, publishShader_ = VK_NULL_HANDLE, ingestShader_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout farSetLayout_ = VK_NULL_HANDLE, publishSetLayout_ = VK_NULL_HANDLE, ingestSetLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout farLayout_ = VK_NULL_HANDLE, publishLayout_ = VK_NULL_HANDLE, ingestLayout_ = VK_NULL_HANDLE;
    VkPipeline farPipe_ = VK_NULL_HANDLE, publishPipe_ = VK_NULL_HANDLE, ingestPipe_ = VK_NULL_HANDLE;
    VkPipeline cloudPipe_ = VK_NULL_HANDLE;
    VkPipeline sunPipe_ = VK_NULL_HANDLE;
    VkShaderModule sunShader_ = VK_NULL_HANDLE;
    uint32_t sunSize_ = 0;          // size of the sun map buffer / image now
    uint32_t sunDispatchRows_ = 0;  // this frame's sun map rows (0: no pass)
    SharedImage sunImage_;          // the GL-shared sun map image (RG32F, size x size + 1), when attached
    uint32_t sunImageSize_ = 0;
    bool sunImageFresh_ = false;
    Buf sunReadback_;
    VkShaderModule cloudShader_ = VK_NULL_HANDLE;
    int prevSlot_ = -1;  // slot of the last recorded frame (its surfaces feed the next cloud pass)
    uint32_t cloudDiv_ = 2;  // cloud pass resolution divisor for the frame being recorded
    VkImage noiseImg_ = VK_NULL_HANDLE;
    VkDeviceMemory noiseMem_ = VK_NULL_HANDLE;
    VkImageView noiseView_ = VK_NULL_HANDLE;
    VkSampler noiseSampler_ = VK_NULL_HANDLE;
    Buf noiseStaging_;
    VkDescriptorPool dpool_ = VK_NULL_HANDLE;
    VkCommandPool pool_ = VK_NULL_HANDLE;

    Slot slots_[SLOTS];
    Buf shared_[BINDINGS];  // only the scene (1-6) and noise (13) bindings are used
    bool sharedDirty_ = true;
    uint32_t sharedDirtySlots_ = 0;
    SceneCopy copies_[2];
    int current_ = -1, staged_ = -1;
    std::mutex sceneMu_;
    std::mutex queueMu_;  // vkQueueSubmit from the render and staging threads
    VkCommandPool uploadPool_ = VK_NULL_HANDLE;
    uint64_t frameCounter_ = 0;
    int staleFrames_ = 0;
    std::atomic<uint64_t> completedFrame_{0};
    const void* noiseUploaded_ = nullptr;
    uint64_t frameIndex_ = 0;

    // interop
    bool interopCapable_ = false, attached_ = false, firstGlFrame_ = true;
    PFN_vkGetMemoryWin32HandleKHR getMemoryHandle_ = nullptr;
    PFN_vkGetSemaphoreWin32HandleKHR getSemaphoreHandle_ = nullptr;
    GlApi gl_;
    SharedImage images_[IMAGES];
    uint32_t width_ = 0, height_ = 0;
    GLuint linProgram_ = 0, linSampler_ = 0;
    GLint uInvProj_ = -1, uSize_ = -1, uZeroToOne_ = -1, uReversed_ = -1;
};

}  // namespace

std::unique_ptr<Backend> makeVulkanBackend(std::string& err) {
    auto b = std::make_unique<VulkanBackend>();
    if (!b->init(err)) return nullptr;
    return b;
}

}  // namespace aether
