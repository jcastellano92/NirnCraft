// Vulkan compute backend for the aether_far kernel.
//  * Host path (harness, tests): scene in host-visible buffers (BAR when available), outputs read back.
//  * GL path (game): outputs published into images whose memory is exported to the game's OpenGL context
//    (VK_KHR_external_memory_win32 <-> GL_EXT_memory_object_win32), ordered by a pair of shared semaphores, so the
//    frame never touches the CPU.
#include <vulkan/vulkan.h>
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <vulkan/vulkan_win32.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "aether_far_spv.h"      // generated: aether_far_spv[], aether_far_spv_size (bytes)
#include "aether_publish_spv.h"  // generated: aether_publish_spv[], aether_publish_spv_size
#include "backend.hpp"
#include "gl_api.hpp"

namespace aether {
namespace {

constexpr uint32_t BINDINGS = 14;

VKAPI_ATTR VkBool32 VKAPI_CALL onValidation(VkDebugUtilsMessageSeverityFlagBitsEXT sev, VkDebugUtilsMessageTypeFlagsEXT,
                                            const VkDebugUtilsMessengerCallbackDataEXT* d, void*) {
    std::fprintf(stderr, "[vulkan %s] %s\n", sev >= VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT ? "error" : "warning",
                 d->pMessage);
    return VK_FALSE;
}
constexpr uint32_t OUT_COLOR = 8, OUT_NORMAL = 9, OUT_DEPTH = 10, OUT_COUNTERS = 12;

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

class VulkanBackend final : public Backend {
public:
    ~VulkanBackend() override {
        if (dev_) {
            vkDeviceWaitIdle(dev_);
            destroyInterop();
            destroyPublish();
            for (auto& b : bufs_) release(b);
            if (fence_) vkDestroyFence(dev_, fence_, nullptr);
            if (query_) vkDestroyQueryPool(dev_, query_, nullptr);
            if (pool_) vkDestroyCommandPool(dev_, pool_, nullptr);
            if (dpool_) vkDestroyDescriptorPool(dev_, dpool_, nullptr);
            if (pipe_) vkDestroyPipeline(dev_, pipe_, nullptr);
            if (playout_) vkDestroyPipelineLayout(dev_, playout_, nullptr);
            if (dlayout_) vkDestroyDescriptorSetLayout(dev_, dlayout_, nullptr);
            if (shader_) vkDestroyShaderModule(dev_, shader_, nullptr);
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
        const VkPhysicalDeviceProperties& props = props2.properties;
        std::snprintf(deviceName_, sizeof(deviceName_), "vulkan (%s)", props.deviceName);
        timestampPeriod_ = props.limits.timestampPeriod;

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
        if (family_ == UINT32_MAX) return fail(err, "no compute queue");

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

        VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        smci.codeSize = aether_far_spv_size;
        smci.pCode = aether_far_spv;
        if (vkCreateShaderModule(dev_, &smci, nullptr, &shader_) != VK_SUCCESS) return fail(err, "shader module");

        VkDescriptorSetLayoutBinding b[BINDINGS];
        for (uint32_t i = 0; i < BINDINGS; i++) {
            b[i] = {};
            b[i].binding = i;
            b[i].descriptorType = i == 0 ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            b[i].descriptorCount = 1;
            b[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }
        VkDescriptorSetLayoutCreateInfo dl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        dl.bindingCount = BINDINGS;
        dl.pBindings = b;
        if (vkCreateDescriptorSetLayout(dev_, &dl, nullptr, &dlayout_) != VK_SUCCESS) return fail(err, "set layout");
        VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        pl.setLayoutCount = 1;
        pl.pSetLayouts = &dlayout_;
        if (vkCreatePipelineLayout(dev_, &pl, nullptr, &playout_) != VK_SUCCESS) return fail(err, "pipeline layout");
        VkComputePipelineCreateInfo cp{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        cp.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
        cp.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        cp.stage.module = shader_;
        cp.stage.pName = "main";
        cp.layout = playout_;
        if (vkCreateComputePipelines(dev_, VK_NULL_HANDLE, 1, &cp, nullptr, &pipe_) != VK_SUCCESS) return fail(err, "pipeline");

        VkDescriptorPoolSize ps[3] = {{VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1},
                                      {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, BINDINGS - 1 + 3},
                                      {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 3}};
        VkDescriptorPoolCreateInfo dp{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        dp.maxSets = 2;
        dp.poolSizeCount = 3;
        dp.pPoolSizes = ps;
        if (vkCreateDescriptorPool(dev_, &dp, nullptr, &dpool_) != VK_SUCCESS) return fail(err, "descriptor pool");
        VkDescriptorSetAllocateInfo da{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        da.descriptorPool = dpool_;
        da.descriptorSetCount = 1;
        da.pSetLayouts = &dlayout_;
        if (vkAllocateDescriptorSets(dev_, &da, &set_) != VK_SUCCESS) return fail(err, "descriptor set");

        VkCommandPoolCreateInfo cpi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        cpi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        cpi.queueFamilyIndex = family_;
        if (vkCreateCommandPool(dev_, &cpi, nullptr, &pool_) != VK_SUCCESS) return fail(err, "command pool");
        VkCommandBufferAllocateInfo cba{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        cba.commandPool = pool_;
        cba.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cba.commandBufferCount = 1;
        if (vkAllocateCommandBuffers(dev_, &cba, &cmd_) != VK_SUCCESS) return fail(err, "command buffer");
        VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        vkCreateFence(dev_, &fci, nullptr, &fence_);
        if (timestamps_) {
            VkQueryPoolCreateInfo q{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
            q.queryType = VK_QUERY_TYPE_TIMESTAMP;
            q.queryCount = 2;
            vkCreateQueryPool(dev_, &q, nullptr, &query_);
        }
        bufs_.resize(BINDINGS);
        return true;
    }

    bool render(const FrameInputs& in, FrameTargets& out, double* gpuMs, std::string& err) override {
        if (!finishInFlight(gpuMs, err)) return false;
        uint32_t w = in.frame.dims[0], h = in.frame.dims[1];
        size_t px = size_t(w) * h;
        if (!prepare(in, true, err)) return false;
        begin();
        recordFar(w, h);
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0,
                             nullptr, 0, nullptr);
        if (!submit(nullptr, nullptr, err)) return false;
        if (!finishInFlight(gpuMs, err)) return false;
        out.color.resize(px);
        out.normal.resize(px);
        out.depth.resize(px);
        out.counters.resize(px);
        std::memcpy(out.color.data(), bufs_[OUT_COLOR].map, px * 4);
        std::memcpy(out.normal.data(), bufs_[OUT_NORMAL].map, px * 4);
        std::memcpy(out.depth.data(), bufs_[OUT_DEPTH].map, px * 4);
        std::memcpy(out.counters.data(), bufs_[OUT_COUNTERS].map, px * 4);
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
        if (!createPublishPipeline(err)) return false;
        const VkFormat fmt[3] = {VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_R32_SFLOAT};
        const GLenum glFmt[3] = {GL_RGBA8, GL_RGBA8, GL_R32F};
        for (int i = 0; i < 3; i++)
            if (!createShared(images_[i], fmt[i], glFmt[i], w, h, err)) {
                destroyInterop();
                return false;
            }
        if (!createSemaphore(glReady_, glReadyHandle_, glReadyGl_, err) || !createSemaphore(vkDone_, vkDoneHandle_, vkDoneGl_, err)) {
            destroyInterop();
            return false;
        }
        if (gl_.GetError() != GL_NO_ERROR) {
            destroyInterop();
            return fail(err, "OpenGL error while importing the shared images");
        }
        width_ = w;
        height_ = h;
        firstGlFrame_ = true;
        attached_ = true;
        publishDirty_ = true;
        out = {images_[0].glTex, images_[1].glTex, images_[2].glTex, w, h};
        return true;
    }

    bool glRender(const FrameInputs& in, double* gpuMs, std::string& err) override {
        if (!attached_) return fail(err, "aether_gl_attach first");
        uint32_t w = in.frame.dims[0], h = in.frame.dims[1];
        if (w != width_ || h != height_) return fail(err, "frame size differs from the attached targets (re-attach)");
        if (!finishInFlight(gpuMs, err)) return false;
        if (!prepare(in, false, err)) return false;
        if (publishDirty_) writePublishDescriptors();

        // GL releases the textures to Vulkan
        GLuint tex[3] = {images_[0].glTex, images_[1].glTex, images_[2].glTex};
        GLenum layouts[3] = {GL_LAYOUT_GENERAL_EXT, GL_LAYOUT_GENERAL_EXT, GL_LAYOUT_GENERAL_EXT};
        gl_.SignalSemaphoreEXT(glReadyGl_, 0, nullptr, 3, tex, layouts);
        gl_.Flush();

        begin();
        imageBarriers(true);
        recordFar(w, h);
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb,
                             0, nullptr, 0, nullptr);
        vkCmdBindPipeline(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, publishPipe_);
        vkCmdBindDescriptorSets(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, publishLayout_, 0, 1, &publishSet_, 0, nullptr);
        uint32_t pcv[2] = {w, h};
        vkCmdPushConstants(cmd_, publishLayout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(pcv), pcv);
        vkCmdDispatch(cmd_, (w + 7) / 8, (h + 7) / 8, 1);
        imageBarriers(false);
        if (!submit(&glReady_, &vkDone_, err)) return false;
        firstGlFrame_ = false;

        // GL commands after this point wait for the Vulkan frame
        gl_.WaitSemaphoreEXT(vkDoneGl_, 0, nullptr, 3, tex, layouts);
        return true;
    }

    void glDetach() override {
        if (dev_) vkDeviceWaitIdle(dev_);
        inFlight_ = false;
        destroyInterop();
    }

private:
    static bool fail(std::string& err, const char* m) {
        err = m;
        return false;
    }

    bool prepare(const FrameInputs& in, bool readback, std::string& err) {
        uint32_t w = in.frame.dims[0], h = in.frame.dims[1];
        size_t px = size_t(w) * h;
        const PackedScene& s = *in.scene;
        bool sceneChanged = s.revision != sceneRevision_;
        bool ok = true;
        ok &= upload(0, &in.frame, sizeof(FrameGpu), true, err);
        if (sceneChanged) {
            ok &= upload(1, s.table.data(), s.table.size() * 4, false, err);
            ok &= upload(2, s.regions.data(), s.regions.size() * sizeof(RegionGpu), false, err);
            ok &= upload(3, s.nodes.data(), s.nodes.size() * 4, false, err);
            ok &= upload(4, s.leaves.data(), s.leaves.size() * 8, false, err);
            ok &= upload(5, s.runEnd.data(), s.runEnd.size() * 4, false, err);
            ok &= upload(6, s.runMat.data(), s.runMat.size() * 4, false, err);
        }
        ok &= upload(7, in.palette->data(), in.palette->size() * sizeof(MaterialGpu), false, err);
        // host path: outputs in cached system memory (CPU reads from BAR are uncached); GL path: device local
        ok &= ensure(OUT_COLOR, px * 4, false, err, readback) && ensure(OUT_NORMAL, px * 4, false, err, readback) &&
              ensure(OUT_DEPTH, px * 4, false, err, readback);
        ok &= upload(11, in.weather->data(), in.weather->size() * 4, false, err);
        ok &= ensure(OUT_COUNTERS, px * 4, false, err, readback);
        if (in.cloudNoise->data() != noiseUploaded_ || !bufs_[13].buf) {
            ok &= upload(13, in.cloudNoise->data(), in.cloudNoise->size() * 4, false, err);
            noiseUploaded_ = in.cloudNoise->data();
        }
        if (!ok) return false;
        sceneRevision_ = s.revision;
        if (descriptorsDirty_) writeDescriptors();
        return true;
    }

    void begin() {
        vkResetCommandBuffer(cmd_, 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(cmd_, &bi);
        if (query_) {
            vkCmdResetQueryPool(cmd_, query_, 0, 2);
            vkCmdWriteTimestamp(cmd_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, query_, 0);
        }
    }

    void recordFar(uint32_t w, uint32_t h) {
        vkCmdBindPipeline(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, pipe_);
        vkCmdBindDescriptorSets(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, playout_, 0, 1, &set_, 0, nullptr);
        vkCmdDispatch(cmd_, (w + 7) / 8, (h + 7) / 8, 1);
    }

    bool submit(VkSemaphore* wait, VkSemaphore* signal, std::string& err) {
        if (query_) vkCmdWriteTimestamp(cmd_, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, query_, 1);
        vkEndCommandBuffer(cmd_);
        VkPipelineStageFlags stage = VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT;
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd_;
        if (wait) {
            si.waitSemaphoreCount = 1;
            si.pWaitSemaphores = wait;
            si.pWaitDstStageMask = &stage;
        }
        if (signal) {
            si.signalSemaphoreCount = 1;
            si.pSignalSemaphores = signal;
        }
        vkResetFences(dev_, 1, &fence_);
        if (vkQueueSubmit(queue_, 1, &si, fence_) != VK_SUCCESS) return fail(err, "submit failed");
        inFlight_ = true;
        return true;
    }

    // Waits for the previous submission (frame resources are single-buffered) and reports its GPU time.
    bool finishInFlight(double* gpuMs, std::string& err) {
        if (!inFlight_) return true;
        if (vkWaitForFences(dev_, 1, &fence_, VK_TRUE, 10'000'000'000ull) != VK_SUCCESS) return fail(err, "GPU timeout/device lost");
        inFlight_ = false;
        if (query_ && gpuMs) {
            uint64_t ts[2] = {};
            vkGetQueryPoolResults(dev_, query_, 0, 2, sizeof(ts), ts, 8, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
            *gpuMs = double(ts[1] - ts[0]) * timestampPeriod_ / 1e6;
        }
        return true;
    }

    // acquire: from GL (external queue family) for writing; release: back to GL.
    void imageBarriers(bool acquire) {
        VkImageMemoryBarrier b[3];
        for (int i = 0; i < 3; i++) {
            b[i] = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            b[i].image = images_[i].image;
            b[i].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            if (acquire) {
                b[i].oldLayout = firstGlFrame_ ? VK_IMAGE_LAYOUT_UNDEFINED : VK_IMAGE_LAYOUT_GENERAL;
                b[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
                b[i].srcQueueFamilyIndex = firstGlFrame_ ? VK_QUEUE_FAMILY_IGNORED : VK_QUEUE_FAMILY_EXTERNAL;
                b[i].dstQueueFamilyIndex = firstGlFrame_ ? VK_QUEUE_FAMILY_IGNORED : family_;
                b[i].srcAccessMask = 0;
                b[i].dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            } else {
                b[i].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
                b[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
                b[i].srcQueueFamilyIndex = family_;
                b[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_EXTERNAL;
                b[i].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
                b[i].dstAccessMask = 0;
            }
        }
        vkCmdPipelineBarrier(cmd_, acquire ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             acquire ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0,
                             nullptr, 0, nullptr, 3, b);
    }

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

    bool createSemaphore(VkSemaphore& sem, HANDLE& handle, GLuint& glSem, std::string& err) {
        VkExportSemaphoreCreateInfo exp{VK_STRUCTURE_TYPE_EXPORT_SEMAPHORE_CREATE_INFO};
        exp.handleTypes = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
        VkSemaphoreCreateInfo sc{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        sc.pNext = &exp;
        if (vkCreateSemaphore(dev_, &sc, nullptr, &sem) != VK_SUCCESS) return fail(err, "vkCreateSemaphore (shared)");
        VkSemaphoreGetWin32HandleInfoKHR hi{VK_STRUCTURE_TYPE_SEMAPHORE_GET_WIN32_HANDLE_INFO_KHR};
        hi.semaphore = sem;
        hi.handleType = VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT;
        if (getSemaphoreHandle_(dev_, &hi, &handle) != VK_SUCCESS) return fail(err, "vkGetSemaphoreWin32HandleKHR");
        gl_.GenSemaphoresEXT(1, &glSem);
        gl_.ImportSemaphoreWin32HandleEXT(glSem, GL_HANDLE_TYPE_OPAQUE_WIN32_EXT, handle);
        return true;
    }

    bool createPublishPipeline(std::string& err) {
        if (publishPipe_) return true;
        VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        smci.codeSize = aether_publish_spv_size;
        smci.pCode = aether_publish_spv;
        if (vkCreateShaderModule(dev_, &smci, nullptr, &publishShader_) != VK_SUCCESS) return fail(err, "publish shader");
        VkDescriptorSetLayoutBinding b[6];
        for (uint32_t i = 0; i < 6; i++) {
            b[i] = {};
            b[i].binding = i;
            b[i].descriptorType = i < 3 ? VK_DESCRIPTOR_TYPE_STORAGE_BUFFER : VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            b[i].descriptorCount = 1;
            b[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }
        VkDescriptorSetLayoutCreateInfo dl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        dl.bindingCount = 6;
        dl.pBindings = b;
        if (vkCreateDescriptorSetLayout(dev_, &dl, nullptr, &publishSetLayout_) != VK_SUCCESS) return fail(err, "publish set layout");
        VkPushConstantRange pr{VK_SHADER_STAGE_COMPUTE_BIT, 0, 8};
        VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        pl.setLayoutCount = 1;
        pl.pSetLayouts = &publishSetLayout_;
        pl.pushConstantRangeCount = 1;
        pl.pPushConstantRanges = &pr;
        if (vkCreatePipelineLayout(dev_, &pl, nullptr, &publishLayout_) != VK_SUCCESS) return fail(err, "publish layout");
        VkComputePipelineCreateInfo cp{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        cp.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
        cp.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        cp.stage.module = publishShader_;
        cp.stage.pName = "main";
        cp.layout = publishLayout_;
        if (vkCreateComputePipelines(dev_, VK_NULL_HANDLE, 1, &cp, nullptr, &publishPipe_) != VK_SUCCESS) return fail(err, "publish pipeline");
        VkDescriptorSetAllocateInfo da{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        da.descriptorPool = dpool_;
        da.descriptorSetCount = 1;
        da.pSetLayouts = &publishSetLayout_;
        if (vkAllocateDescriptorSets(dev_, &da, &publishSet_) != VK_SUCCESS) return fail(err, "publish descriptor set");
        return true;
    }

    void writePublishDescriptors() {
        VkDescriptorBufferInfo bi[3] = {{bufs_[OUT_COLOR].buf, 0, VK_WHOLE_SIZE},
                                        {bufs_[OUT_NORMAL].buf, 0, VK_WHOLE_SIZE},
                                        {bufs_[OUT_DEPTH].buf, 0, VK_WHOLE_SIZE}};
        VkDescriptorImageInfo ii[3];
        VkWriteDescriptorSet wr[6];
        for (uint32_t i = 0; i < 6; i++) {
            wr[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
            wr[i].dstSet = publishSet_;
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
        publishDirty_ = false;
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
        if (glOk) {
            if (glReadyGl_) gl_.DeleteSemaphoresEXT(1, &glReadyGl_);
            if (vkDoneGl_) gl_.DeleteSemaphoresEXT(1, &vkDoneGl_);
        }
        if (glReady_) vkDestroySemaphore(dev_, glReady_, nullptr);
        if (vkDone_) vkDestroySemaphore(dev_, vkDone_, nullptr);
        if (glReadyHandle_) CloseHandle(glReadyHandle_);
        if (vkDoneHandle_) CloseHandle(vkDoneHandle_);
        glReady_ = vkDone_ = VK_NULL_HANDLE;
        glReadyHandle_ = vkDoneHandle_ = nullptr;
        glReadyGl_ = vkDoneGl_ = 0;
        attached_ = false;
    }

    void release(Buf& b) {
        if (b.map) vkUnmapMemory(dev_, b.mem);
        if (b.buf) vkDestroyBuffer(dev_, b.buf, nullptr);
        if (b.mem) vkFreeMemory(dev_, b.mem, nullptr);
        b = {};
    }

    bool ensure(uint32_t i, VkDeviceSize size, bool uniform, std::string& err, bool readback = false) {
        size = size < 16 ? 16 : size;
        Buf& b = bufs_[i];
        if (b.size >= size && b.readback == readback) return true;
        if (inFlight_) {
            double ignored;
            if (!finishInFlight(&ignored, err)) return false;
        }
        vkDeviceWaitIdle(dev_);
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
        descriptorsDirty_ = true;
        if (i == OUT_COLOR || i == OUT_NORMAL || i == OUT_DEPTH) publishDirty_ = true;
        return true;
    }

    bool upload(uint32_t i, const void* data, size_t bytes, bool uniform, std::string& err) {
        if (!ensure(i, bytes, uniform, err)) return false;
        if (bytes) std::memcpy(bufs_[i].map, data, bytes);
        return true;
    }

    void writeDescriptors() {
        VkDescriptorBufferInfo info[BINDINGS];
        VkWriteDescriptorSet wr[BINDINGS];
        for (uint32_t i = 0; i < BINDINGS; i++) {
            info[i] = {bufs_[i].buf, 0, VK_WHOLE_SIZE};
            wr[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
            wr[i].dstSet = set_;
            wr[i].dstBinding = i;
            wr[i].descriptorCount = 1;
            wr[i].descriptorType = i == 0 ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            wr[i].pBufferInfo = &info[i];
        }
        vkUpdateDescriptorSets(dev_, BINDINGS, wr, 0, nullptr);
        descriptorsDirty_ = false;
    }

    VkInstance inst_ = VK_NULL_HANDLE;
    VkDebugUtilsMessengerEXT messenger_ = VK_NULL_HANDLE;
    VkPhysicalDevice phys_ = VK_NULL_HANDLE;
    VkDevice dev_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t family_ = 0;
    uint8_t deviceUuid_[VK_UUID_SIZE] = {};
    VkShaderModule shader_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout dlayout_ = VK_NULL_HANDLE;
    VkPipelineLayout playout_ = VK_NULL_HANDLE;
    VkPipeline pipe_ = VK_NULL_HANDLE;
    VkDescriptorPool dpool_ = VK_NULL_HANDLE;
    VkDescriptorSet set_ = VK_NULL_HANDLE;
    VkCommandPool pool_ = VK_NULL_HANDLE;
    VkCommandBuffer cmd_ = VK_NULL_HANDLE;
    VkFence fence_ = VK_NULL_HANDLE;
    VkQueryPool query_ = VK_NULL_HANDLE;
    bool timestamps_ = false, inFlight_ = false;
    float timestampPeriod_ = 1.0f;
    std::vector<Buf> bufs_;
    bool descriptorsDirty_ = true;
    uint64_t sceneRevision_ = UINT64_MAX;
    const void* noiseUploaded_ = nullptr;
    char deviceName_[300] = "vulkan";

    // interop
    bool interopCapable_ = false, attached_ = false, firstGlFrame_ = true, publishDirty_ = true;
    PFN_vkGetMemoryWin32HandleKHR getMemoryHandle_ = nullptr;
    PFN_vkGetSemaphoreWin32HandleKHR getSemaphoreHandle_ = nullptr;
    GlApi gl_;
    SharedImage images_[3];
    VkSemaphore glReady_ = VK_NULL_HANDLE, vkDone_ = VK_NULL_HANDLE;
    HANDLE glReadyHandle_ = nullptr, vkDoneHandle_ = nullptr;
    GLuint glReadyGl_ = 0, vkDoneGl_ = 0;
    VkShaderModule publishShader_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout publishSetLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout publishLayout_ = VK_NULL_HANDLE;
    VkPipeline publishPipe_ = VK_NULL_HANDLE;
    VkDescriptorSet publishSet_ = VK_NULL_HANDLE;
    uint32_t width_ = 0, height_ = 0;

public:
    void destroyPublish() {
        if (publishPipe_) vkDestroyPipeline(dev_, publishPipe_, nullptr);
        if (publishLayout_) vkDestroyPipelineLayout(dev_, publishLayout_, nullptr);
        if (publishSetLayout_) vkDestroyDescriptorSetLayout(dev_, publishSetLayout_, nullptr);
        if (publishShader_) vkDestroyShaderModule(dev_, publishShader_, nullptr);
        publishPipe_ = VK_NULL_HANDLE;
    }
};

}  // namespace

std::unique_ptr<Backend> makeVulkanBackend(std::string& err) {
    auto b = std::make_unique<VulkanBackend>();
    if (!b->init(err)) return nullptr;
    return b;
}

}  // namespace aether
