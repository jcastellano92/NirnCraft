// Structures from the plans stamped on the GPU (atlas_ovl_*, shaders/atlas_overlays.slang; the Java side is
// com.nirncraft.atlas.AtlasOverlayTapes): the block table, the settlements' op tapes and the street plan stay resident
// on the device; a batch stamps chunk columns of 16-bit block ids in place, one thread per column (the gather
// formulation). Vulkan only (GPU only for now; the CPU fallback is a later job). Self-contained like the decoration
// (its own device); GPU -> GPU between the generator, the decoration and this is the next step.
#include "vk_loader.hpp"  // first: VK_NO_PROTOTYPES

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "atlas/atlas.h"
#include "atlas_ovl_pieces_spv.h"  // generated: slangc + spv_exact, one module per kernel
#include "atlas_ovl_streets_spv.h"
#include "atlas_ovl_sanity_spv.h"
#include "atlas_ovl_connect_spv.h"

using namespace atlas;

namespace {

// keep in step with atlas_overlays.slang and AtlasOverlayTapes
constexpr uint32_t GROUP = 64, NB = 6, PIPES = 4, P_PIECES = 0, P_STREETS = 1, P_SANITY = 2, P_CONNECT = 3;
constexpr uint32_t CHUNK_INTS = ATLAS_OVL_CHUNK_INTS;
constexpr uint32_t BLOCKS_MAGIC = 0x4C424F41u, TAPES_MAGIC = 0x50544F41u, STREETS_MAGIC = 0x52534F41u;

double msSince(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
}

struct OvlParams {
    int32_t count, levels;
    uint32_t kinds;
    int32_t pad;
};

struct Buf {
    VkBuffer buf = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    void* map = nullptr;
    VkDeviceSize size = 0;
};

class VulkanEngine {
public:
    ~VulkanEngine() {
        if (dev_) {
            if (!dead_) vkDeviceWaitIdle(dev_);
            for (Buf* b : {&blocks_, &tapes_, &streets_, &chunks_, &ids_, &snap_, &staging_}) release(*b);
            if (queries_) vkDestroyQueryPool(dev_, queries_, nullptr);
            if (fence_) vkDestroyFence(dev_, fence_, nullptr);
            if (pool_) vkDestroyCommandPool(dev_, pool_, nullptr);
            if (descPool_) vkDestroyDescriptorPool(dev_, descPool_, nullptr);
            for (auto& p : pipes_)
                if (p) vkDestroyPipeline(dev_, p, nullptr);
            for (auto& m : modules_)
                if (m) vkDestroyShaderModule(dev_, m, nullptr);
            if (layout_) vkDestroyPipelineLayout(dev_, layout_, nullptr);
            if (setLayout_) vkDestroyDescriptorSetLayout(dev_, setLayout_, nullptr);
            vkDestroyDevice(dev_, nullptr);
        }
        if (inst_) vkDestroyInstance(inst_, nullptr);
    }
    const char* device() const { return name_; }

    bool init(std::string& err) {
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "atlas_ovl";
        app.apiVersion = VK_API_VERSION_1_2;
        VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        ici.pApplicationInfo = &app;
        if (!atlas::vkLoadGlobal(err)) return false;
        if (vkCreateInstance(&ici, nullptr, &inst_) != VK_SUCCESS) return fail(err, "vkCreateInstance failed");
        if (!atlas::vkLoadInstance(inst_, err)) return false;
        uint32_t n = 0;
        vkEnumeratePhysicalDevices(inst_, &n, nullptr);
        std::vector<VkPhysicalDevice> devs(n);
        vkEnumeratePhysicalDevices(inst_, &n, devs.data());
        int best = -1, bestScore = -1;
        std::string why;
        const char* want = std::getenv("ATLAS_GEN_DEVICE");
        for (uint32_t i = 0; i < n; i++) {
            std::string reason;
            if (!usable(devs[i], reason)) {
                why += reason + "; ";
                continue;
            }
            VkPhysicalDeviceProperties p;
            vkGetPhysicalDeviceProperties(devs[i], &p);
            int score = p.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 3 : p.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU ? 2 : 1;
            if (want && *want && std::strstr(p.deviceName, want)) score += 10;
            if (score > bestScore) {
                bestScore = score;
                best = int(i);
            }
        }
        if (best < 0) return fail(err, "no Vulkan 1.2 device with what the exact overlays need: " + why);
        phys_ = devs[size_t(best)];
        VkPhysicalDeviceProperties p;
        vkGetPhysicalDeviceProperties(phys_, &p);
        std::snprintf(name_, sizeof(name_), "vulkan (%s)", p.deviceName);
        timestampPeriod_ = p.limits.timestampPeriod;
        uint32_t qn = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(phys_, &qn, nullptr);
        std::vector<VkQueueFamilyProperties> qf(qn);
        vkGetPhysicalDeviceQueueFamilyProperties(phys_, &qn, qf.data());
        family_ = UINT32_MAX;
        for (int pass = 0; pass < 2 && family_ == UINT32_MAX; pass++)
            for (uint32_t i = 0; i < qn; i++)
                if ((qf[i].queueFlags & VK_QUEUE_COMPUTE_BIT) && (pass == 1 || !(qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT))) {
                    family_ = i;
                    timestamps_ = qf[i].timestampValidBits > 0;
                    break;
                }
        if (family_ == UINT32_MAX) return fail(err, "no compute queue");
        float prio = 0.5f;
        VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        qci.queueFamilyIndex = family_;
        qci.queueCount = 1;
        qci.pQueuePriorities = &prio;
        VkPhysicalDeviceFeatures feats{};
        feats.shaderFloat64 = VK_TRUE;
        feats.shaderInt64 = VK_TRUE;
        VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        dci.queueCreateInfoCount = 1;
        dci.pQueueCreateInfos = &qci;
        dci.pEnabledFeatures = &feats;
        if (vkCreateDevice(phys_, &dci, nullptr, &dev_) != VK_SUCCESS) return fail(err, "vkCreateDevice failed");
        vkGetDeviceQueue(dev_, family_, 0, &queue_);
        return build(err);
    }

    // which: 0 the block table, 1 the tapes, 2 the street plan
    bool set(uint32_t which, const uint32_t* words, uint32_t count, std::string& err) {
        if (dead_) return fail(err, "the overlays' device was lost earlier");
        Buf& b = which == 0 ? blocks_ : which == 1 ? tapes_ : streets_;
        if (!ensure(b, size_t(count) * 4, false, err)) return false;
        writeSet();
        if (!upload(b, words, size_t(count) * 4, err)) return false;
        has_[which] = true;
        return true;
    }

    bool run(uint32_t count, const int32_t* chunks, uint32_t levels, uint32_t kinds, uint32_t* ids, AtlasOvlTimes& t, std::string& err) {
        if (dead_) return fail(err, "the overlays' device was lost earlier");
        auto u0 = std::chrono::steady_clock::now();
        size_t idBytes = size_t(count) * levels * 128u * 4u;
        bool grown = false;
        size_t chunkBytes = size_t(count) * CHUNK_INTS * 4;
        if (!(chunks_.buf && chunks_.size >= chunkBytes)) grown = true;
        if (!(ids_.buf && ids_.size >= idBytes)) grown = true;
        if ((kinds & 4u) && !(snap_.buf && snap_.size >= idBytes)) grown = true;
        if (!ensure(chunks_, chunkBytes, false, err) || !ensure(ids_, idBytes, false, err)) return false;
        if ((kinds & 4u) && !ensure(snap_, idBytes, false, err)) return false;
        if (grown) writeSet();
        if (!upload(chunks_, chunks, chunkBytes, err) || !upload(ids_, ids, idBytes, err)) return false;
        t.uploadMs += msSince(u0);
        OvlParams p{int32_t(count), int32_t(levels), kinds, 0};
        auto k0 = std::chrono::steady_clock::now();
        begin();
        if (timestamps_) vkCmdResetQueryPool(cmd_, queries_, 0, 5);
        vkCmdBindDescriptorSets(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, layout_, 0, 1, &set_, 0, nullptr);
        vkCmdPushConstants(cmd_, layout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(OvlParams), &p);
        // pieces, then the street painter (the server's order: the step's structures, then its features)
        if (timestamps_) vkCmdWriteTimestamp(cmd_, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queries_, 0);
        if ((kinds & 1u) && has_[0] && has_[1]) {
            vkCmdBindPipeline(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, pipes_[P_PIECES]);
            vkCmdDispatch(cmd_, 256 / GROUP, count, 1);
        }
        barrier(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        if (timestamps_) vkCmdWriteTimestamp(cmd_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queries_, 1);
        // the pieces' sweep (Sanity): its neighbour reads from a copy of what the pieces left, then the shapes
        if ((kinds & 4u) && has_[0] && has_[1]) {
            barrier(VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            VkBufferCopy c{0, 0, idBytes};
            vkCmdCopyBuffer(cmd_, ids_.buf, snap_.buf, 1, &c);
            barrier(VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            vkCmdBindPipeline(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, pipes_[P_SANITY]);
            vkCmdDispatch(cmd_, 256 / GROUP, count, 1);
            barrier(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            vkCmdBindPipeline(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, pipes_[P_CONNECT]);
            vkCmdDispatch(cmd_, 256 / GROUP, count, 1);
            barrier(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        }
        if (timestamps_) vkCmdWriteTimestamp(cmd_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queries_, 3);
        if ((kinds & 2u) && has_[0] && has_[2]) {
            vkCmdBindPipeline(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, pipes_[P_STREETS]);
            vkCmdDispatch(cmd_, 256 / GROUP, count, 1);
        }
        barrier(VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        if (timestamps_) vkCmdWriteTimestamp(cmd_, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queries_, 2);
        if (!submitWait(err)) return false;
        t.kernelMs += msSince(k0);
        if (timestamps_) {
            uint64_t ts[4] = {};
            if (vkGetQueryPoolResults(dev_, queries_, 0, 4, sizeof(ts), ts, sizeof(uint64_t),
                                      VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WAIT_BIT) == VK_SUCCESS) {
                t.piecesMs += double(ts[1] - ts[0]) * timestampPeriod_ * 1e-6;
                t.sweepsMs += double(ts[3] - ts[1]) * timestampPeriod_ * 1e-6;
                t.streetsMs += double(ts[2] - ts[3]) * timestampPeriod_ * 1e-6;
            }
        }
        auto r0 = std::chrono::steady_clock::now();
        if (!download(ids_, 0, ids, idBytes, err)) return false;
        t.readbackMs += msSince(r0);
        return true;
    }

private:
    static bool fail(std::string& err, std::string m) {
        err = std::move(m);
        return false;
    }
    static bool usable(VkPhysicalDevice d, std::string& reason) {
        VkPhysicalDeviceProperties p;
        vkGetPhysicalDeviceProperties(d, &p);
        reason = p.deviceName;
        if (p.apiVersion < VK_API_VERSION_1_2) return reason += ": Vulkan < 1.2", false;
        VkPhysicalDeviceFeatures f;
        vkGetPhysicalDeviceFeatures(d, &f);
        if (!f.shaderFloat64) return reason += ": no shaderFloat64", false;
        if (!f.shaderInt64) return reason += ": no shaderInt64", false;
        VkPhysicalDeviceVulkan12Properties v12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_PROPERTIES};
        VkPhysicalDeviceProperties2 p2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2};
        p2.pNext = &v12;
        vkGetPhysicalDeviceProperties2(d, &p2);
        if (!v12.shaderSignedZeroInfNanPreserveFloat32 || !v12.shaderSignedZeroInfNanPreserveFloat64)
            return reason += ": signed zero / inf / nan not preserved", false;
        if (!v12.shaderRoundingModeRTEFloat32 || !v12.shaderRoundingModeRTEFloat64) return reason += ": no RTE rounding mode", false;
        return true;
    }
    bool build(std::string& err) {
        VkDescriptorSetLayoutBinding b[NB];
        for (uint32_t i = 0; i < NB; i++) b[i] = {i, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        VkDescriptorSetLayoutCreateInfo dl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        dl.bindingCount = NB;
        dl.pBindings = b;
        if (vkCreateDescriptorSetLayout(dev_, &dl, nullptr, &setLayout_) != VK_SUCCESS) return fail(err, "set layout");
        VkPushConstantRange pr{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(OvlParams)};
        VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        pl.setLayoutCount = 1;
        pl.pSetLayouts = &setLayout_;
        pl.pushConstantRangeCount = 1;
        pl.pPushConstantRanges = &pr;
        if (vkCreatePipelineLayout(dev_, &pl, nullptr, &layout_) != VK_SUCCESS) return fail(err, "pipeline layout");
        const uint32_t* code[PIPES] = {atlas_ovl_pieces_spv, atlas_ovl_streets_spv, atlas_ovl_sanity_spv, atlas_ovl_connect_spv};
        const size_t size[PIPES] = {atlas_ovl_pieces_spv_size, atlas_ovl_streets_spv_size, atlas_ovl_sanity_spv_size,
                                    atlas_ovl_connect_spv_size};
        static const char* names[PIPES] = {"pieces", "streets", "sanity", "connect"};
        for (uint32_t i = 0; i < PIPES; i++) {
            auto p0 = std::chrono::steady_clock::now();
            VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
            smci.codeSize = size[i];
            smci.pCode = code[i];
            if (vkCreateShaderModule(dev_, &smci, nullptr, &modules_[i]) != VK_SUCCESS) return fail(err, "shader module");
            VkComputePipelineCreateInfo cp{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
            cp.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
            cp.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
            cp.stage.module = modules_[i];
            cp.stage.pName = "main";
            cp.layout = layout_;
            if (vkCreateComputePipelines(dev_, VK_NULL_HANDLE, 1, &cp, nullptr, &pipes_[i]) != VK_SUCCESS)
                return fail(err, std::string("pipeline ") + names[i]);
            // the driver's pipeline creation (its JIT) per kernel: the TRACE pattern of the other engines
            std::printf("TRACE done pipeline:ovl_%s %.2fs (%zu bytes of SPIR-V)\n", names[i], msSince(p0) / 1000.0, size[i]);
            std::fflush(stdout);
        }
        VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, NB};
        VkDescriptorPoolCreateInfo dp{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        dp.maxSets = 1;
        dp.poolSizeCount = 1;
        dp.pPoolSizes = &ps;
        if (vkCreateDescriptorPool(dev_, &dp, nullptr, &descPool_) != VK_SUCCESS) return fail(err, "descriptor pool");
        VkDescriptorSetAllocateInfo da{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        da.descriptorPool = descPool_;
        da.descriptorSetCount = 1;
        da.pSetLayouts = &setLayout_;
        if (vkAllocateDescriptorSets(dev_, &da, &set_) != VK_SUCCESS) return fail(err, "descriptor set");
        VkCommandPoolCreateInfo cpi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        cpi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        cpi.queueFamilyIndex = family_;
        if (vkCreateCommandPool(dev_, &cpi, nullptr, &pool_) != VK_SUCCESS) return fail(err, "command pool");
        VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        ca.commandPool = pool_;
        ca.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        ca.commandBufferCount = 1;
        if (vkAllocateCommandBuffers(dev_, &ca, &cmd_) != VK_SUCCESS) return fail(err, "command buffer");
        VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        if (vkCreateFence(dev_, &fci, nullptr, &fence_) != VK_SUCCESS) return fail(err, "fence");
        if (timestamps_) {
            VkQueryPoolCreateInfo qp{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
            qp.queryType = VK_QUERY_TYPE_TIMESTAMP;
            qp.queryCount = 5;
            if (vkCreateQueryPool(dev_, &qp, nullptr, &queries_) != VK_SUCCESS) timestamps_ = false;
        }
        bool ok = ensure(staging_, 32u << 20, true, err) && ensure(blocks_, 64, false, err) && ensure(tapes_, 64, false, err) &&
                  ensure(streets_, 64, false, err) && ensure(chunks_, 64, false, err) && ensure(ids_, 64, false, err) &&
                  ensure(snap_, 64, false, err);
        if (ok) writeSet();
        return ok;
    }
    uint32_t memoryType(uint32_t bits, VkMemoryPropertyFlags want) {
        VkPhysicalDeviceMemoryProperties mp;
        vkGetPhysicalDeviceMemoryProperties(phys_, &mp);
        for (uint32_t t = 0; t < mp.memoryTypeCount; t++)
            if ((bits & (1u << t)) && (mp.memoryTypes[t].propertyFlags & want) == want) return t;
        return UINT32_MAX;
    }
    void release(Buf& b) {
        if (!dev_) return;
        if (b.map) vkUnmapMemory(dev_, b.mem);
        if (b.buf) vkDestroyBuffer(dev_, b.buf, nullptr);
        if (b.mem) vkFreeMemory(dev_, b.mem, nullptr);
        b = {};
    }
    bool ensure(Buf& b, size_t size, bool host, std::string& err) {
        size = std::max<size_t>(size, 64);
        if (b.buf && b.size >= size) return true;
        if (b.buf) vkDeviceWaitIdle(dev_);
        release(b);
        VkBufferCreateInfo bc{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bc.size = size;
        bc.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        if (vkCreateBuffer(dev_, &bc, nullptr, &b.buf) != VK_SUCCESS) return fail(err, "vkCreateBuffer");
        VkMemoryRequirements req;
        vkGetBufferMemoryRequirements(dev_, b.buf, &req);
        VkMemoryPropertyFlags hv = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        uint32_t type = host ? memoryType(req.memoryTypeBits, hv | VK_MEMORY_PROPERTY_HOST_CACHED_BIT)
                             : memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        if (type == UINT32_MAX && host) type = memoryType(req.memoryTypeBits, hv);
        if (type == UINT32_MAX) return fail(err, "no suitable memory type");
        VkMemoryAllocateInfo ma{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ma.allocationSize = req.size;
        ma.memoryTypeIndex = type;
        if (vkAllocateMemory(dev_, &ma, nullptr, &b.mem) != VK_SUCCESS) {
            vkDestroyBuffer(dev_, b.buf, nullptr);
            b.buf = VK_NULL_HANDLE;
            return fail(err, "vkAllocateMemory (" + std::to_string(size >> 20) + " MiB)");
        }
        vkBindBufferMemory(dev_, b.buf, b.mem, 0);
        if (host && vkMapMemory(dev_, b.mem, 0, VK_WHOLE_SIZE, 0, &b.map) != VK_SUCCESS) return fail(err, "vkMapMemory");
        b.size = size;
        return true;
    }
    bool upload(Buf& dst, const void* data, size_t bytes, std::string& err) {
        for (size_t done = 0; done < bytes;) {
            size_t n = std::min<size_t>(bytes - done, size_t(staging_.size));
            std::memcpy(staging_.map, static_cast<const char*>(data) + done, n);
            begin();
            VkBufferCopy c{0, done, n};
            vkCmdCopyBuffer(cmd_, staging_.buf, dst.buf, 1, &c);
            barrier(VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            if (!submitWait(err)) return false;
            done += n;
        }
        return true;
    }
    bool download(Buf& src, VkDeviceSize offset, void* data, size_t bytes, std::string& err) {
        for (size_t done = 0; done < bytes;) {
            size_t n = std::min<size_t>(bytes - done, size_t(staging_.size));
            begin();
            VkBufferCopy c{offset + done, 0, n};
            vkCmdCopyBuffer(cmd_, src.buf, staging_.buf, 1, &c);
            barrier(VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT);
            if (!submitWait(err)) return false;
            std::memcpy(static_cast<char*>(data) + done, staging_.map, n);
            done += n;
        }
        return true;
    }
    void writeSet() {
        Buf* bs[NB] = {&blocks_, &tapes_, &streets_, &chunks_, &ids_, &snap_};
        VkDescriptorBufferInfo bi[NB];
        VkWriteDescriptorSet w[NB];
        for (uint32_t i = 0; i < NB; i++) {
            bi[i] = {bs[i]->buf, 0, VK_WHOLE_SIZE};
            w[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
            w[i].dstSet = set_;
            w[i].dstBinding = i;
            w[i].descriptorCount = 1;
            w[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            w[i].pBufferInfo = &bi[i];
        }
        vkUpdateDescriptorSets(dev_, NB, w, 0, nullptr);
    }
    void begin() {
        vkResetCommandBuffer(cmd_, 0);
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(cmd_, &bi);
    }
    bool submitWait(std::string& err) {
        vkEndCommandBuffer(cmd_);
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd_;
        vkResetFences(dev_, 1, &fence_);
        VkResult r = vkQueueSubmit(queue_, 1, &si, fence_);
        if (r != VK_SUCCESS) {
            dead_ = r == VK_ERROR_DEVICE_LOST;
            return fail(err, "vkQueueSubmit failed (" + std::to_string(int(r)) + ")");
        }
        VkResult w = vkWaitForFences(dev_, 1, &fence_, VK_TRUE, 60'000'000'000ull);
        if (w != VK_SUCCESS) {
            dead_ = true;
            return fail(err, w == VK_TIMEOUT ? "overlay batch timed out" : "device lost");
        }
        return true;
    }
    void barrier(VkAccessFlags src, VkAccessFlags dst, VkPipelineStageFlags from, VkPipelineStageFlags to) {
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = src;
        mb.dstAccessMask = dst;
        vkCmdPipelineBarrier(cmd_, from, to, 0, 1, &mb, 0, nullptr, 0, nullptr);
    }

    bool dead_ = false, timestamps_ = false;
    bool has_[3] = {false, false, false};
    float timestampPeriod_ = 1.0f;
    char name_[300] = "vulkan";
    VkInstance inst_ = VK_NULL_HANDLE;
    VkPhysicalDevice phys_ = VK_NULL_HANDLE;
    VkDevice dev_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;
    uint32_t family_ = 0;
    VkShaderModule modules_[PIPES] = {};
    VkPipeline pipes_[PIPES] = {};
    VkDescriptorSetLayout setLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout layout_ = VK_NULL_HANDLE;
    VkDescriptorPool descPool_ = VK_NULL_HANDLE;
    VkDescriptorSet set_ = VK_NULL_HANDLE;
    VkCommandPool pool_ = VK_NULL_HANDLE;
    VkCommandBuffer cmd_ = VK_NULL_HANDLE;
    VkFence fence_ = VK_NULL_HANDLE;
    VkQueryPool queries_ = VK_NULL_HANDLE;
    Buf blocks_, tapes_, streets_, chunks_, ids_, snap_, staging_;
};

}  // namespace

struct AtlasOvl {
    std::unique_ptr<VulkanEngine> engine;
    std::string error;
    uint32_t blockCount = 0, tapeWords = 0, streetBuckets = 0;
};

namespace {
template <typename F>
AtlasStatus guarded(AtlasOvl* h, F f) {
    try {
        return f();
    } catch (const std::bad_alloc&) {
        if (h) h->error = "out of memory";
        return ATLAS_ERR_OUT_OF_MEMORY;
    } catch (const std::exception& e) {
        if (h) h->error = e.what();
        return ATLAS_ERR_INTERNAL;
    }
}
}  // namespace

ATLAS_API AtlasStatus atlas_ovl_create(AtlasBackend backend, AtlasOvl** out) {
    if (!out) return ATLAS_ERR_ARG;
    *out = nullptr;
    return guarded(nullptr, [&] {
        if (backend != ATLAS_BACKEND_VULKAN) return ATLAS_ERR_UNSUPPORTED;  // GPU only (the CPU fallback is a later job)
        auto h = std::make_unique<AtlasOvl>();
        auto vk = std::make_unique<VulkanEngine>();
        std::string err;
        if (!vk->init(err)) {
            std::fprintf(stderr, "[atlas] overlays: %s\n", err.c_str());
            return ATLAS_ERR_UNSUPPORTED;
        }
        h->engine = std::move(vk);
        *out = h.release();
        return ATLAS_OK;
    });
}

ATLAS_API void atlas_ovl_destroy(AtlasOvl* h) { delete h; }
ATLAS_API const char* atlas_ovl_last_error(AtlasOvl* h) { return h ? h->error.c_str() : "null handle"; }
ATLAS_API const char* atlas_ovl_device(AtlasOvl* h) { return h ? h->engine->device() : "null handle"; }

ATLAS_API AtlasStatus atlas_ovl_set(AtlasOvl* h, uint32_t which, const uint32_t* words, uint32_t count) {
    if (!h || !words || count < 16 || which > 2) return ATLAS_ERR_ARG;
    static const uint32_t magic[3] = {BLOCKS_MAGIC, TAPES_MAGIC, STREETS_MAGIC};
    if (words[0] != magic[which]) return ATLAS_ERR_ARG;
    if (which == 0 && (words[8] != 5 || count < 16 + words[1] * words[8])) return ATLAS_ERR_ARG;
    return guarded(h, [&] {
        if (!h->engine->set(which, words, count, h->error)) return ATLAS_ERR_GPU;
        if (which == 0) h->blockCount = words[1];
        if (which == 1) h->tapeWords = count;
        if (which == 2) h->streetBuckets = words[4];
        return ATLAS_OK;
    });
}

ATLAS_API AtlasStatus atlas_ovl_run(AtlasOvl* h, uint32_t count, const int32_t* chunks, uint32_t levels, uint32_t kinds,
                                    uint32_t* ids, AtlasOvlTimes* times) {
    if (!h || !chunks || !ids || count == 0 || levels == 0 || levels > 1024) return ATLAS_ERR_ARG;
    if (h->blockCount == 0) return ATLAS_ERR_STATE;
    for (uint32_t c = 0; c < count; c++) {
        int32_t rec = chunks[c * ATLAS_OVL_CHUNK_INTS + 2], bucket = chunks[c * ATLAS_OVL_CHUNK_INTS + 3];
        if (rec >= 0 && (h->tapeWords == 0 || uint32_t(rec) + 513u > h->tapeWords)) return ATLAS_ERR_ARG;
        if (bucket >= 0 && uint32_t(bucket) >= h->streetBuckets) return ATLAS_ERR_ARG;
        for (uint32_t k = 4; k < 8; k++)
            if (chunks[c * ATLAS_OVL_CHUNK_INTS + k] >= int32_t(count)) return ATLAS_ERR_ARG;
    }
    return guarded(h, [&] {
        AtlasOvlTimes t{};
        auto t0 = std::chrono::steady_clock::now();
        if (!h->engine->run(count, chunks, levels, kinds, ids, t, h->error)) return ATLAS_ERR_GPU;
        t.totalMs = msSince(t0);
        if (times) *times = t;
        return ATLAS_OK;
    });
}
