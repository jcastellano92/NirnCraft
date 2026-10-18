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

#include "aether_far_spv.h"      // generated: aether_far_spv[], aether_far_spv_size (bytes)
#include "aether_ingest_spv.h"   // generated
#include "aether_publish_spv.h"  // generated
#include "backend.hpp"
#include "gl_api.hpp"

namespace aether {
namespace {

constexpr uint32_t BINDINGS = 15;
constexpr uint32_t SLOTS = 2;
// far kernel bindings
constexpr uint32_t B_FRAME = 0, B_PALETTE = 7, B_COLOR = 8, B_NORMAL = 9, B_DEPTH = 10, B_WEATHER = 11,
                   B_COUNTERS = 12, B_NOISE = 13, B_NEAR = 14;
// shared images
constexpr int IMG_COLOR = 0, IMG_NORMAL = 1, IMG_DEPTH = 2, IMG_NEAR = 3, IMAGES = 4;

bool perSlot(uint32_t b) {
    return b == B_FRAME || b == B_PALETTE || b == B_COLOR || b == B_NORMAL || b == B_DEPTH || b == B_WEATHER ||
           b == B_COUNTERS || b == B_NEAR;
}

VKAPI_ATTR VkBool32 VKAPI_CALL onValidation(VkDebugUtilsMessageSeverityFlagBitsEXT sev, VkDebugUtilsMessageTypeFlagsEXT,
                                            const VkDebugUtilsMessengerCallbackDataEXT* d, void*) {
    std::fprintf(stderr, "[vulkan %s] %s\n", sev >= VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT ? "error" : "warning",
                 d->pMessage);
    return VK_FALSE;
}

struct Buf {
    VkBuffer buf = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    void* map = nullptr;
    VkDeviceSize size = 0;
    bool readback = false;
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
    Buf bufs[7];  // bindings 1..6
    uint64_t revision = UINT64_MAX;
    std::atomic<uint64_t> lastFrame{0};  // last frame number recorded against this copy
    uint32_t generation = 0;  // bumped when a buffer is reallocated (descriptor sets must be rewritten)
};

struct Slot {
    uint64_t frameNumber = 0;  // of its last submission
    int boundCopy = -1;        // scene copy its far descriptor set points at
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
            for (auto& c : copies_)
                for (auto& b : c.bufs) release(b);
            if (pool_) vkDestroyCommandPool(dev_, pool_, nullptr);
            if (dpool_) vkDestroyDescriptorPool(dev_, dpool_, nullptr);
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
        if (!createPipe(aether_far_spv, aether_far_spv_size, farTypes, 0, farPipe_, farLayout_, farSetLayout_, farShader_, err))
            return false;
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

        VkDescriptorPoolSize ps[3] = {{VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, SLOTS},
                                      {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, SLOTS * (BINDINGS - 1 + 3 + 1)},
                                      {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, SLOTS * (3 + 1)}};
        VkDescriptorPoolCreateInfo dp{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        dp.maxSets = SLOTS * 3;
        dp.poolSizeCount = 3;
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
        return true;
    }

    bool render(const FrameInputs& in, FrameTargets& out, double* gpuMs, std::string& err) override {
        Slot& s = slots_[0];
        if (!waitSlot(s, gpuMs, err)) return false;
        uint32_t w = in.frame.dims[0], h = in.frame.dims[1];
        size_t px = size_t(w) * h;
        FrameGpu frame = in.frame;
        if (!prepare(s, in, frame, true, err)) return false;
        if (in.nearDepth && !upload(s.bufs[B_NEAR], in.nearDepth, px * 4, false, true, err)) return false;
        refreshDescriptors(s);
        begin(s);
        recordFar(s, w, h);
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

    bool glRender(const FrameInputs& in, double* gpuMs, std::string& err) override {
        if (!attached_) return fail(err, "aether_gl_attach first");
        uint32_t w = in.frame.dims[0], h = in.frame.dims[1];
        if (w != width_ || h != height_) return fail(err, "frame size differs from the attached targets (re-attach)");
        Slot& s = slots_[frameIndex_ % SLOTS];
        if (!waitSlot(s, gpuMs, err)) return false;  // frame N-2: normally long done
        FrameGpu frame = in.frame;
        if (in.wantNearDepth) frame.dims[2] |= 0x20u;  // FRAME_NEAR_DEPTH: read from the shared image
        if (!prepare(s, in, frame, false, err)) return false;
        if (!ensure(s.bufs[B_NEAR], size_t(w) * h * 4, false, false, err)) return false;
        refreshDescriptors(s);

        // GL releases the shared images to Vulkan (after everything GL queued so far, e.g. last frame's composite)
        GLuint tex[IMAGES];
        GLenum layouts[IMAGES];
        for (int i = 0; i < IMAGES; i++) {
            tex[i] = images_[i].glTex;
            layouts[i] = GL_LAYOUT_GENERAL_EXT;
        }
        gl_.SignalSemaphoreEXT(s.glReady.gl, 0, nullptr, IMAGES, tex, layouts);
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
        recordFar(s, w, h);
        computeBarrier(s.cmd);
        vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, publishPipe_);
        vkCmdBindDescriptorSets(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, publishLayout_, 0, 1, &s.publishSet, 0, nullptr);
        vkCmdPushConstants(s.cmd, publishLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pcv), pcv);
        vkCmdDispatch(s.cmd, (w + 7) / 8, (h + 7) / 8, 1);
        imageBarriers(s.cmd, false);
        if (!submit(s, &s.glReady.sem, &s.vkDone.sem, err)) return false;
        firstGlFrame_ = false;
        frameIndex_++;

        // GL commands after this point wait for the Vulkan frame
        gl_.WaitSemaphoreEXT(s.vkDone.gl, 0, nullptr, IMAGES, tex, layouts);
        return true;
    }

    bool stageScene(const PackedScene& sc) override {
        std::lock_guard<std::mutex> lock(sceneMu_);
        int c = current_ == 0 ? 1 : 0;
        if (copies_[c].lastFrame > completedFrame_.load()) return false;  // a frame may still read it
        std::string err;
        if (!uploadScene(copies_[c], sc, err)) return false;
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
            if (lock.owns_lock() && staged_ >= 0 && copies_[staged_].revision == sc.revision) {
                current_ = staged_;  // staged off the render thread: just flip
                staged_ = -1;
            }
            if (lock.owns_lock() && (current_ < 0 || copies_[current_].revision != sc.revision) &&
                (staged_ < 0 || copies_[staged_].revision != sc.revision)) {
                // not staged (no update thread, or both copies were busy): upload here, once nothing reads it
                int c = current_ < 0 ? 0 : current_;
                for (auto& o : slots_)
                    if (!waitSlot(o, nullptr, err)) return false;
                if (!uploadScene(copies_[c], sc, err)) return false;
                current_ = c;
                if (staged_ == c) staged_ = -1;
            }
        }
        if (in.cloudNoise->data() != noiseUploaded_ || !shared_[B_NOISE].buf) {
            for (auto& o : slots_)
                if (!waitSlot(o, nullptr, err)) return false;
            if (!upload(shared_[B_NOISE], in.cloudNoise->data(), in.cloudNoise->size() * 4, false, false, err)) return false;
            noiseUploaded_ = in.cloudNoise->data();
        }
        // host path: outputs in cached system memory (CPU reads from BAR are uncached); GL path: device local
        bool ok = upload(s.bufs[B_FRAME], &frame, sizeof(FrameGpu), true, false, err) &&
                  upload(s.bufs[B_PALETTE], in.palette->data(), in.palette->size() * sizeof(MaterialGpu), false, false, err) &&
                  upload(s.bufs[B_WEATHER], in.weather->data(), in.weather->size() * 4, false, false, err) &&
                  ensure(s.bufs[B_COLOR], px * 16, false, readback, err) && ensure(s.bufs[B_NORMAL], px * 4, false, readback, err) &&
                  ensure(s.bufs[B_DEPTH], px * 4, false, readback, err) && ensure(s.bufs[B_COUNTERS], px * 4, false, readback, err) &&
                  ensure(s.bufs[B_NEAR], 16, false, readback, err);
        return ok;
    }

    void refreshDescriptors(Slot& s) {
        const SceneCopy& sc = copies_[current_];
        if (s.farDirty || s.boundCopy != current_ || s.boundGeneration != sc.generation || sharedDirty_) {
            VkDescriptorBufferInfo info[BINDINGS];
            VkWriteDescriptorSet wr[BINDINGS];
            for (uint32_t i = 0; i < BINDINGS; i++) {
                const Buf& b = perSlot(i) ? s.bufs[i] : (i >= 1 && i <= 6 ? sc.bufs[i] : shared_[i]);
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
            vkCmdWriteTimestamp(s.cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, s.query, 0);
        }
    }

    void recordFar(Slot& s, uint32_t w, uint32_t h) {
        vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, farPipe_);
        vkCmdBindDescriptorSets(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, farLayout_, 0, 1, &s.farSet, 0, nullptr);
        vkCmdDispatch(s.cmd, (w + 7) / 8, (h + 7) / 8, 1);
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
        if (vkQueueSubmit(queue_, 1, &si, s.fence) != VK_SUCCESS) return fail(err, "submit failed");
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
        vkCmdPipelineBarrier(cmd, acquire ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             acquire ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0,
                             nullptr, 0, nullptr, IMAGES, b);
    }

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
    bool ensure(Buf& b, VkDeviceSize size, bool uniform, bool readback, std::string& err) {
        size = size < 16 ? 16 : size;
        if (b.buf && b.size >= size && b.readback == readback) return true;
        release(b);
        VkDeviceSize alloc = size + size / 4;  // headroom against regrowth every frame
        VkBufferCreateInfo bc{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bc.size = alloc;
        bc.usage = uniform ? VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT : VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        if (vkCreateBuffer(dev_, &bc, nullptr, &b.buf) != VK_SUCCESS) return fail(err, "vkCreateBuffer");
        VkMemoryRequirements req;
        vkGetBufferMemoryRequirements(dev_, b.buf, &req);
        uint32_t want = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        // inputs: prefer device-local host-visible (resizable BAR); readback: prefer host-cached; else plain host
        uint32_t extra = readback ? VK_MEMORY_PROPERTY_HOST_CACHED_BIT : VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
        uint32_t type = memoryType(req.memoryTypeBits, want | extra);
        if (type == UINT32_MAX) type = memoryType(req.memoryTypeBits, want);
        if (type == UINT32_MAX) return fail(err, "no host-visible memory type");
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
        vkMapMemory(dev_, b.mem, 0, VK_WHOLE_SIZE, 0, &b.map);
        b.size = alloc;
        b.readback = readback;
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

    bool uploadScene(SceneCopy& c, const PackedScene& sc, std::string& err) {
        VkBuffer before[7];
        for (int i = 1; i <= 6; i++) before[i] = c.bufs[i].buf;
        bool ok = upload(c.bufs[1], sc.table.data(), sc.table.size() * 4, false, false, err) &&
                  upload(c.bufs[2], sc.regions.data(), sc.regions.size() * sizeof(RegionGpu), false, false, err) &&
                  upload(c.bufs[3], sc.nodes.data(), sc.nodes.size() * 4, false, false, err) &&
                  upload(c.bufs[4], sc.leaves.data(), sc.leaves.size() * 8, false, false, err) &&
                  upload(c.bufs[5], sc.runEnd.data(), sc.runEnd.size() * 4, false, false, err) &&
                  upload(c.bufs[6], sc.runMat.data(), sc.runMat.size() * 4, false, false, err);
        if (!ok) return false;
        for (int i = 1; i <= 6; i++)
            if (c.bufs[i].buf != before[i]) {
                c.generation++;
                break;
            }
        c.revision = sc.revision;
        return true;
    }

    bool upload(Buf& b, const void* data, size_t bytes, bool uniform, bool readback, std::string& err) {
        if (!ensure(b, bytes, uniform, readback, err)) return false;
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
    VkDescriptorPool dpool_ = VK_NULL_HANDLE;
    VkCommandPool pool_ = VK_NULL_HANDLE;

    Slot slots_[SLOTS];
    Buf shared_[BINDINGS];  // only the scene (1-6) and noise (13) bindings are used
    bool sharedDirty_ = true;
    uint32_t sharedDirtySlots_ = 0;
    SceneCopy copies_[2];
    int current_ = -1, staged_ = -1;
    std::mutex sceneMu_;
    uint64_t frameCounter_ = 0;
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
};

}  // namespace

std::unique_ptr<Backend> makeVulkanBackend(std::string& err) {
    auto b = std::make_unique<VulkanBackend>();
    if (!b->init(err)) return nullptr;
    return b;
}

}  // namespace aether
