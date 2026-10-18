// Headless Vulkan compute backend for the aether_far kernel. Host-visible buffers (simple, and fast with resizable
// BAR); the in-game path will move the scene to device-local memory and write into shared GL images instead.
#include <vulkan/vulkan.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "aether_far_spv.h"  // generated: const uint32_t aether_far_spv[]; size_t aether_far_spv_size (bytes)
#include "backend.hpp"

namespace aether {
namespace {

constexpr uint32_t BINDINGS = 13;

struct Buf {
    VkBuffer buf = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    void* map = nullptr;
    VkDeviceSize size = 0;
};

class VulkanBackend final : public Backend {
public:
    ~VulkanBackend() override {
        if (dev_) {
            vkDeviceWaitIdle(dev_);
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
        const char* val = std::getenv("AETHER_VK_VALIDATION");
        if (val && *val == '1') {
            ici.enabledLayerCount = 1;
            ici.ppEnabledLayerNames = layers;
        }
        if (vkCreateInstance(&ici, nullptr, &inst_) != VK_SUCCESS) return fail(err, "vkCreateInstance failed");
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
        VkPhysicalDeviceProperties props;
        vkGetPhysicalDeviceProperties(phys_, &props);
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
        if (vkCreateDevice(phys_, &dci, nullptr, &dev_) != VK_SUCCESS) return fail(err, "vkCreateDevice failed");
        vkGetDeviceQueue(dev_, family_, 0, &queue_);

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

        VkDescriptorPoolSize ps[2] = {{VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1}, {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, BINDINGS - 1}};
        VkDescriptorPoolCreateInfo dp{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        dp.maxSets = 1;
        dp.poolSizeCount = 2;
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
        ok &= ensure(8, px * 4, false, err) && ensure(9, px * 4, false, err) && ensure(10, px * 4, false, err);
        ok &= upload(11, in.weather->data(), in.weather->size() * 4, false, err);
        ok &= ensure(12, px * 4, false, err);
        if (!ok) return false;
        sceneRevision_ = s.revision;
        if (descriptorsDirty_) writeDescriptors();

        vkResetCommandBuffer(cmd_, 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(cmd_, &bi);
        if (query_) {
            vkCmdResetQueryPool(cmd_, query_, 0, 2);
            vkCmdWriteTimestamp(cmd_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, query_, 0);
        }
        vkCmdBindPipeline(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, pipe_);
        vkCmdBindDescriptorSets(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, playout_, 0, 1, &set_, 0, nullptr);
        vkCmdDispatch(cmd_, (w + 7) / 8, (h + 7) / 8, 1);
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        mb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(cmd_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0,
                             nullptr, 0, nullptr);
        if (query_) vkCmdWriteTimestamp(cmd_, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, query_, 1);
        vkEndCommandBuffer(cmd_);
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd_;
        vkResetFences(dev_, 1, &fence_);
        if (vkQueueSubmit(queue_, 1, &si, fence_) != VK_SUCCESS) return fail(err, "submit failed");
        if (vkWaitForFences(dev_, 1, &fence_, VK_TRUE, 10'000'000'000ull) != VK_SUCCESS) return fail(err, "GPU timeout/device lost");
        if (query_ && gpuMs) {
            uint64_t ts[2] = {};
            vkGetQueryPoolResults(dev_, query_, 0, 2, sizeof(ts), ts, 8, VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT);
            *gpuMs = double(ts[1] - ts[0]) * timestampPeriod_ / 1e6;
        }
        out.color.resize(px);
        out.normal.resize(px);
        out.depth.resize(px);
        out.counters.resize(px);
        std::memcpy(out.color.data(), bufs_[8].map, px * 4);
        std::memcpy(out.normal.data(), bufs_[9].map, px * 4);
        std::memcpy(out.depth.data(), bufs_[10].map, px * 4);
        std::memcpy(out.counters.data(), bufs_[12].map, px * 4);
        return true;
    }

private:
    static bool fail(std::string& err, const char* m) {
        err = m;
        return false;
    }

    void release(Buf& b) {
        if (b.map) vkUnmapMemory(dev_, b.mem);
        if (b.buf) vkDestroyBuffer(dev_, b.buf, nullptr);
        if (b.mem) vkFreeMemory(dev_, b.mem, nullptr);
        b = {};
    }

    bool ensure(uint32_t i, VkDeviceSize size, bool uniform, std::string& err) {
        size = size < 16 ? 16 : size;
        Buf& b = bufs_[i];
        if (b.size >= size) return true;
        vkDeviceWaitIdle(dev_);
        release(b);
        VkDeviceSize alloc = size + size / 4;  // headroom against regrowth every frame
        VkBufferCreateInfo bc{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bc.size = alloc;
        bc.usage = uniform ? VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT : VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        if (vkCreateBuffer(dev_, &bc, nullptr, &b.buf) != VK_SUCCESS) return fail(err, "vkCreateBuffer");
        VkMemoryRequirements req;
        vkGetBufferMemoryRequirements(dev_, b.buf, &req);
        VkPhysicalDeviceMemoryProperties mp;
        vkGetPhysicalDeviceMemoryProperties(phys_, &mp);
        uint32_t want = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        uint32_t type = UINT32_MAX;
        // prefer device-local host-visible (resizable BAR), else plain host memory
        for (int pass = 0; pass < 2 && type == UINT32_MAX; pass++) {
            uint32_t need = pass == 0 ? want | VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT : want;
            for (uint32_t t = 0; t < mp.memoryTypeCount; t++)
                if ((req.memoryTypeBits & (1u << t)) && (mp.memoryTypes[t].propertyFlags & need) == need) {
                    type = t;
                    break;
                }
        }
        if (type == UINT32_MAX) return fail(err, "no host-visible memory type");
        VkMemoryAllocateInfo ma{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ma.allocationSize = req.size;
        ma.memoryTypeIndex = type;
        if (vkAllocateMemory(dev_, &ma, nullptr, &b.mem) != VK_SUCCESS) {
            // BAR heaps are small on some systems; retry in system memory
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
        descriptorsDirty_ = true;
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
    VkPhysicalDevice phys_ = VK_NULL_HANDLE;
    VkDevice dev_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t family_ = 0;
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
    bool timestamps_ = false;
    float timestampPeriod_ = 1.0f;
    std::vector<Buf> bufs_;
    bool descriptorsDirty_ = true;
    uint64_t sceneRevision_ = UINT64_MAX;
    char deviceName_[300] = "vulkan";
};

}  // namespace

std::unique_ptr<Backend> makeVulkanBackend(std::string& err) {
    auto b = std::make_unique<VulkanBackend>();
    if (!b->init(err)) return nullptr;
    return b;
}

}  // namespace aether
