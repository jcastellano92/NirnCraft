// The client's decoration on the GPU (atlas_feat_*, docs/ATLAS-CLIENT-GENERATION.md stage 4): shaders/atlas_features.slang
// on a Vulkan compute device (GPU only; the CPU fallback is a later job). One batch: terrain slots (generator ids and their
// quart biomes and placement mask pages), the origins decorated in isolation (each with its 5 x 5 slots), the targets
// assembled from their 3 x 3 origins' writes (the gather order: atlas_features.slang). Self-contained like the
// generator's standalone mode (its own device); GPU -> GPU with the generator's terrain is the next step.
#include "vk_loader.hpp"  // first: VK_NO_PROTOTYPES

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "atlas/atlas.h"
#include "atlas_feat_hm_spv.h"  // generated: slangc + spv_exact, one module per kernel
#include "atlas_feat_init_spv.h"
#include "atlas_feat_copy_spv.h"
#include "atlas_feat_apply_spv.h"
#include "atlas_feat_pack_spv.h"
#include "atlas_feat_ore_spv.h"
#include "atlas_feat_cover_spv.h"
#include "atlas_feat_rock_spv.h"
#include "atlas_feat_tree_spv.h"
#include "atlas_feat_freeze_spv.h"
#include "atlas_feat_disk_spv.h"
#include "atlas_feat_spring_spv.h"
#include "atlas_feat_lake_spv.h"
#include "atlas_feat_aux_spv.h"
#include "atlas_feat_sapply_spv.h"


using namespace atlas;

namespace {

// keep in step with atlas_features.slang
constexpr uint32_t GROUP = 64;
constexpr uint32_t CHUNK_W = ATLAS_FEAT_CHUNK_WORDS, AUX_W = ATLAS_FEAT_AUX_WORDS, ORIGIN_W = 28, TARGET_W = 12;
constexpr uint32_t HT_CAP = 32768, SCR_HT = 16, SCR_HM = 16 + HT_CAP * 2, SCR_BIOMES = SCR_HM + 4 * 2304,
                   SCR_TESTED = SCR_BIOMES + 8, SCR_TREE = SCR_TESTED + 12288, TREE_W = 272448,
                   MISC_W = 320 + 512 + 1152 + 864 + 64 + 1024, SCR_W = SCR_TREE + TREE_W + MISC_W;
constexpr uint32_t H_DISPATCH = 19, H_NDISPATCH = 20, MAGIC = 0x41454641u;
constexpr uint32_t TABLE_W = ATLAS_FEAT_TABLE_WORDS;  // meta + the write table, per origin (the isolated check)
static_assert(TABLE_W == 16 + HT_CAP * 2, "table words");
constexpr uint32_t NB = 9, WIDE_W = 44u * 4096u;
// pipelines: hm, init, copy, apply, pack, then one a feature type (atlas_features.slang FT_*: 1 ore .. 8 lake)
constexpr uint32_t P_HM = 0, P_INIT = 1, P_COPY = 2, P_APPLY = 3, P_PACK = 4, P_FEAT = 5, FT_TYPES = 9, PIPES = P_FEAT + FT_TYPES - 1;
constexpr uint32_t R_TYPE = 0;

double msSince(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
}

struct FeatParams {
    int32_t origins, targets;
    uint32_t rec, k;
    int32_t slots;
    uint32_t first, budget, store;
};
struct Batch {
    uint32_t slots, origins, targets;
    const uint32_t* ids;
    const uint32_t* aux;
    const int32_t* org;
    const int32_t* tgt;
};

class Engine {
public:
    virtual ~Engine() = default;
    virtual const char* device() const = 0;
    virtual bool setProgram(const std::vector<uint32_t>& prog, std::string& err) = 0;
    virtual bool run(const Batch& b, uint32_t* outIds, uint32_t* outTables, AtlasFeatTimes& t, std::string& err) = 0;
};

std::vector<uint32_t> dispatchList(const std::vector<uint32_t>& prog) {
    std::vector<uint32_t> d;
    if (prog.size() <= H_NDISPATCH) return d;
    uint32_t at = prog[H_DISPATCH], n = prog[H_NDISPATCH];
    for (uint32_t i = 0; i < n && at + i < prog.size(); i++) d.push_back(prog[at + i]);
    return d;
}

struct Buf {
    VkBuffer buf = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    void* map = nullptr;
    VkDeviceSize size = 0;
};

class VulkanEngine final : public Engine {
public:
    ~VulkanEngine() override {
        if (dev_) {
            if (!dead_) vkDeviceWaitIdle(dev_);
            for (Buf* b : {&prog_, &ids_, &aux_, &org_, &scratch_, &out_, &tgt_, &wide_, &cnt_, &staging_}) release(*b);
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
    const char* device() const override { return name_; }

    bool init(std::string& err) {
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "atlas_feat";
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
        if (best < 0) return fail(err, "no Vulkan 1.2 device with what the exact decoration needs: " + why);
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

    bool setProgram(const std::vector<uint32_t>& prog, std::string& err) override {
        if (dead_) return fail(err, "the decoration's device was lost earlier");
        vkDeviceWaitIdle(dev_);
        dispatch_ = dispatchList(prog);
        types_.assign(prog.size(), 0u);
        for (uint32_t rec : dispatch_)
            if (rec + R_TYPE < prog.size()) types_[rec] = prog[rec + R_TYPE];
        if (!ensure(prog_, prog.size() * 4, false, err)) return false;
        writeSet();
        return upload(prog_, prog.data(), prog.size() * 4, err);
    }

    bool run(const Batch& b, uint32_t* outIds, uint32_t* outTables, AtlasFeatTimes& t, std::string& err) override {
        if (dead_) return fail(err, "the decoration's device was lost earlier");
        auto u0 = std::chrono::steady_clock::now();
        uint32_t nt = std::max(1u, b.targets);
        bool grown = false;
        auto need = [&](Buf& buf, size_t bytes) {
            if (buf.buf && buf.size >= bytes) return true;
            grown = true;
            return ensure(buf, bytes, false, err);
        };
        if (!need(ids_, size_t(b.slots) * CHUNK_W * 4) || !need(aux_, size_t(b.slots) * AUX_W * 4) ||
            !need(org_, size_t(b.origins) * ORIGIN_W * 4) || !need(scratch_, size_t(b.origins) * SCR_W * 4) ||
            !need(out_, size_t(nt) * CHUNK_W * 4) || !need(tgt_, size_t(nt) * TARGET_W * 4) || !need(wide_, size_t(nt) * WIDE_W * 4))
            return false;
        if (grown) writeSet();
        if (!upload(ids_, b.ids, size_t(b.slots) * CHUNK_W * 4, err) || !upload(aux_, b.aux, size_t(b.slots) * AUX_W * 4, err) ||
            !upload(org_, b.org, size_t(b.origins) * ORIGIN_W * 4, err) ||
            (b.targets && !upload(tgt_, b.tgt, size_t(b.targets) * TARGET_W * 4, err)))
            return false;
        t.uploadMs += msSince(u0);
        FeatParams p{int32_t(b.origins), int32_t(b.targets), 0, 0, int32_t(b.slots), 0, 0, 0};
        bool trace = std::getenv("ATLAS_FEAT_TRACE") != nullptr;
        auto dispatch = [&](uint32_t pipe, uint32_t gx, uint32_t gy) {
            vkCmdPushConstants(cmd_, layout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(FeatParams), &p);
            vkCmdBindPipeline(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, pipes_[pipe]);
            vkCmdDispatch(cmd_, gx, gy, 1);
            barrier(VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        };
        // one submission a placed feature: a whole decoration in one would outlast the display driver's watchdog
        auto k0 = std::chrono::steady_clock::now();
        begin();
        vkCmdBindDescriptorSets(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, layout_, 0, 1, &set_, 0, nullptr);
        dispatch(P_HM, 4, b.slots);
        dispatch(P_INIT, 64, b.origins);
        if (!submitWait(err)) return false;
        auto f0 = std::chrono::steady_clock::now();
        uint32_t fi = 0, skip = 0;
        if (const char* sk = std::getenv("ATLAS_FEAT_SKIP"))
            for (const char* c = sk; *c; c++)
                if (*c >= '1' && *c <= '9') skip |= 1u << uint32_t(*c - '0');
        for (uint32_t rec : dispatch_) {
            fi++;
            p.rec = rec;
            uint32_t type = rec < types_.size() ? types_[rec] : 0;
            if (type == 0 || type >= FT_TYPES) continue;  // (the program dispatches only supported types)
            if (skip & (1u << type)) continue;             // ATLAS_FEAT_SKIP: feature types left out (dev checks)
            auto d0 = std::chrono::steady_clock::now();
            // rounds: each thread makes up to `budget` placements a dispatch (trees few: one may take long), the rest in
            // the next, until no origin has any left (the counter)
            p.budget = type == 4 ? 1u : 1u << 30;
            for (uint32_t round = 0;; round++) {
                p.first = round == 0 ? 1u : 0u;
                begin();
                vkCmdFillBuffer(cmd_, cnt_.buf, 0, 64, 0u);
                barrier(VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
                vkCmdBindDescriptorSets(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, layout_, 0, 1, &set_, 0, nullptr);
                dispatch(P_FEAT + type - 1, b.origins, 1);  // a group (one warp) an origin
                // the counter's atomic adds made visible to the copy (dispatch()'s barrier covers compute only)
                barrier(VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT);
                VkBufferCopy c{0, 0, 4};
                vkCmdCopyBuffer(cmd_, cnt_.buf, staging_.buf, 1, &c);
                barrier(VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT);
                if (!submitWait(err))
                    return fail(err, err + " (feature " + std::to_string(fi) + " of " + std::to_string(dispatch_.size()) + ", record " +
                                         std::to_string(rec) + ", type " + std::to_string(type) + ", round " + std::to_string(round) + ")");
                uint32_t left = *static_cast<const uint32_t*>(staging_.map);
                if (left == 0 || round > 100000) break;
            }
            if (trace && msSince(d0) > 200.0)
                std::printf("TRACE slow feature %u/%zu (record %u, type %u): %.0f ms\n", fi, dispatch_.size(), rec, type, msSince(d0)),
                    std::fflush(stdout);
        }
        t.featureMs += msSince(f0);
        auto g0 = std::chrono::steady_clock::now();
        if (b.targets) {
            begin();
            vkCmdBindDescriptorSets(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, layout_, 0, 1, &set_, 0, nullptr);
            dispatch(P_COPY, (WIDE_W + GROUP - 1) / GROUP, b.targets);
            for (uint32_t k = 0; k < 9; k++) {
                p.k = k;
                dispatch(P_APPLY, HT_CAP / GROUP, b.targets);
            }
            dispatch(P_PACK, (CHUNK_W + GROUP - 1) / GROUP, b.targets);
            if (!submitWait(err)) return false;
        }
        t.gatherMs += msSince(g0);
        t.kernelMs += msSince(k0);
        auto r0 = std::chrono::steady_clock::now();
        if (outIds && b.targets && !download(out_, 0, outIds, size_t(b.targets) * CHUNK_W * 4, err)) return false;
        if (outTables)
            for (uint32_t o = 0; o < b.origins; o++)
                if (!download(scratch_, VkDeviceSize(o) * SCR_W * 4, outTables + size_t(o) * TABLE_W, TABLE_W * 4, err)) return false;
        // dev: ATLAS_FEAT_SCRATCH_DUMP=<file> writes each origin's misc scratch (cx, cz, then MISC_W words: the last
        // placement's ore spheres, stack ...), for comparing a kernel's working values with an emulation
        // (only a run with trees writes it: the trees' trace is what it is read for; per-type runs come later)
        bool trees = false;
        for (uint32_t rec : dispatch_) trees = trees || (rec < types_.size() && types_[rec] == 4u);
        const char* sd = std::getenv("ATLAS_FEAT_SCRATCH_DUMP");
        if (sd && (trees || std::getenv("ATLAS_FEAT_SCRATCH_ALL"))) {
            if (FILE* f = std::fopen(sd, "wb")) {
                std::vector<uint32_t> misc(MISC_W);
                for (uint32_t o = 0; o < b.origins; o++) {
                    if (!download(scratch_, (VkDeviceSize(o) * SCR_W + SCR_TREE + TREE_W) * 4, misc.data(), MISC_W * 4, err)) break;
                    std::fwrite(b.org + size_t(o) * ORIGIN_W, 4, 2, f);
                    std::fwrite(misc.data(), 4, MISC_W, f);
                }
                std::fclose(f);
            }
        }
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
        VkPushConstantRange pr{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(FeatParams)};
        VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        pl.setLayoutCount = 1;
        pl.pSetLayouts = &setLayout_;
        pl.pushConstantRangeCount = 1;
        pl.pPushConstantRanges = &pr;
        if (vkCreatePipelineLayout(dev_, &pl, nullptr, &layout_) != VK_SUCCESS) return fail(err, "pipeline layout");
        const uint32_t* code[PIPES] = {atlas_feat_hm_spv,   atlas_feat_init_spv,  atlas_feat_copy_spv,  atlas_feat_apply_spv,
                                       atlas_feat_pack_spv, atlas_feat_ore_spv,   atlas_feat_cover_spv, atlas_feat_rock_spv,
                                       atlas_feat_tree_spv, atlas_feat_freeze_spv, atlas_feat_disk_spv, atlas_feat_spring_spv,
                                       atlas_feat_lake_spv};
        const size_t size[PIPES] = {atlas_feat_hm_spv_size,   atlas_feat_init_spv_size,  atlas_feat_copy_spv_size,
                                    atlas_feat_apply_spv_size, atlas_feat_pack_spv_size, atlas_feat_ore_spv_size,
                                    atlas_feat_cover_spv_size, atlas_feat_rock_spv_size, atlas_feat_tree_spv_size,
                                    atlas_feat_freeze_spv_size, atlas_feat_disk_spv_size, atlas_feat_spring_spv_size,
                                    atlas_feat_lake_spv_size};
        static const char* names[PIPES] = {"hm", "init", "copy", "apply", "pack", "ore", "cover", "rock", "tree", "freeze", "disk", "spring", "lake"};
        bool trace = std::getenv("ATLAS_FEAT_TRACE") != nullptr;
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
                return fail(err, "pipeline " + std::to_string(i));
            if (trace) std::printf("TRACE done pipeline:%s %.1fs (%zu bytes)\n", names[i], msSince(p0) / 1000.0, size[i]), std::fflush(stdout);
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
            qp.queryCount = 4;
            if (vkCreateQueryPool(dev_, &qp, nullptr, &queries_) != VK_SUCCESS) timestamps_ = false;
        }
        bool ok = ensure(staging_, 32u << 20, true, err) && ensure(prog_, 64, false, err) && ensure(ids_, 64, false, err) &&
                  ensure(aux_, 64, false, err) && ensure(org_, 64, false, err) && ensure(scratch_, 64, false, err) &&
                  ensure(out_, 64, false, err) && ensure(tgt_, 64, false, err) && ensure(wide_, 64, false, err) &&
                  ensure(cnt_, 64, false, err);
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
        Buf* bs[NB] = {&prog_, &ids_, &aux_, &org_, &scratch_, &out_, &tgt_, &wide_, &cnt_};
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
            return fail(err, w == VK_TIMEOUT ? "decoration batch timed out" : "device lost");
        }
        return true;
    }
    void barrier(VkAccessFlags src, VkAccessFlags dst, VkPipelineStageFlags from, VkPipelineStageFlags to) {
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = src;
        mb.dstAccessMask = dst;
        vkCmdPipelineBarrier(cmd_, from, to, 0, 1, &mb, 0, nullptr, 0, nullptr);
    }

    std::vector<uint32_t> dispatch_, types_;
    bool dead_ = false, timestamps_ = false;
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
    Buf prog_, ids_, aux_, org_, scratch_, out_, tgt_, wide_, cnt_, staging_;
};

#ifndef ATLAS_FEAT_ALONE
// ---- the decoration as the generator's store stages (atlas_feat_attach) ------------------------------------------
// Its pipelines on the generator's device, its commands recorded into the generator's command buffers (no queue, no
// upload or readback of chunk data: the store's terrain in, the store's blocks out). A call decorates up to 64 origins
// (each with its 5 x 5 store slots): their aux from the store's (featAux), their heightmaps (featHm), each placed
// feature once (one origin a warp), then their writes into the store's blocks (featStoreApply: the gather's order kept
// by priority, whichever origin runs first).
constexpr uint32_t STORE_AUX_W = ATLAS_GEN_STORE_AUX_WORDS, MAX_ORIGINS = 64;
constexpr uint32_t H_GEN2FEAT = 24, H_FEAT2GEN = 25, H_NGEN = 26;
// pipelines: hm, init, aux, store apply, then one a feature type
constexpr uint32_t S_HM = 0, S_INIT = 1, S_AUX = 2, S_APPLY = 3, S_FEAT = 4, S_PIPES = S_FEAT + FT_TYPES - 1;

class StageEngine {
public:
    ~StageEngine() {
        if (!dev_) return;
        for (Buf* b : {&prog_, &staging_, &aux_, &org_, &scratch_, &list_, &cnt_}) release(*b);
        if (descPool_) vkDestroyDescriptorPool(dev_, descPool_, nullptr);
        for (auto& p : pipes_)
            if (p) vkDestroyPipeline(dev_, p, nullptr);
        for (auto& m : modules_)
            if (m) vkDestroyShaderModule(dev_, m, nullptr);
        if (layout_) vkDestroyPipelineLayout(dev_, layout_, nullptr);
        if (setLayout_) vkDestroyDescriptorSetLayout(dev_, setLayout_, nullptr);
    }

    bool init(const AtlasGenDevice& d, const std::vector<uint32_t>& prog, std::string& err) {
        dev_ = VkDevice(d.vkDevice);
        phys_ = VkPhysicalDevice(d.vkPhysicalDevice);
        dispatch_ = dispatchList(prog);
        types_.assign(prog.size(), 0u);
        for (uint32_t rec : dispatch_)
            if (rec + R_TYPE < prog.size()) types_[rec] = prog[rec + R_TYPE];
        VkDescriptorSetLayoutBinding b[NB];
        for (uint32_t i = 0; i < NB; i++) b[i] = {i, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
        VkDescriptorSetLayoutCreateInfo dl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        dl.bindingCount = NB;
        dl.pBindings = b;
        if (vkCreateDescriptorSetLayout(dev_, &dl, nullptr, &setLayout_) != VK_SUCCESS) return fail(err, "set layout");
        VkPushConstantRange pr{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(FeatParams)};
        VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        pl.setLayoutCount = 1;
        pl.pSetLayouts = &setLayout_;
        pl.pushConstantRangeCount = 1;
        pl.pPushConstantRanges = &pr;
        if (vkCreatePipelineLayout(dev_, &pl, nullptr, &layout_) != VK_SUCCESS) return fail(err, "pipeline layout");
        const uint32_t* code[S_PIPES] = {atlas_feat_hm_spv, atlas_feat_init_spv, atlas_feat_aux_spv, atlas_feat_sapply_spv,
                                         atlas_feat_ore_spv, atlas_feat_cover_spv, atlas_feat_rock_spv, atlas_feat_tree_spv,
                                         atlas_feat_freeze_spv, atlas_feat_disk_spv, atlas_feat_spring_spv, atlas_feat_lake_spv};
        const size_t size[S_PIPES] = {atlas_feat_hm_spv_size, atlas_feat_init_spv_size, atlas_feat_aux_spv_size,
                                      atlas_feat_sapply_spv_size, atlas_feat_ore_spv_size, atlas_feat_cover_spv_size,
                                      atlas_feat_rock_spv_size, atlas_feat_tree_spv_size, atlas_feat_freeze_spv_size,
                                      atlas_feat_disk_spv_size, atlas_feat_spring_spv_size, atlas_feat_lake_spv_size};
        for (uint32_t i = 0; i < S_PIPES; i++) {
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
                return fail(err, "pipeline " + std::to_string(i) + " refused");
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
        // the program goes up with the first call's commands (its copy recorded there: no queue of ours)
        bool ok = ensure(prog_, prog.size() * 4, false, err) && ensure(staging_, prog.size() * 4, true, err) &&
                  ensure(org_, size_t(MAX_ORIGINS) * ORIGIN_W * 4, false, err) &&
                  ensure(scratch_, size_t(MAX_ORIGINS) * SCR_W * 4, false, err) &&
                  ensure(list_, size_t(MAX_ORIGINS) * 25 * 4, false, err) && ensure(cnt_, 64, false, err);
        if (!ok) return false;
        std::memcpy(staging_.map, prog.data(), prog.size() * 4);
        progBytes_ = prog.size() * 4;
        return true;
    }

    // the decoration stage: up to 64 chunks, each with its 5 x 5 store slots
    int32_t decorate(const AtlasGenStoreBatch* b) {
        std::string err;
        if (b->radius != 2 || b->count > MAX_ORIGINS) return 1;
        if (!aux_.buf || storeSlots_ != b->slots) {
            release(aux_);
            if (!ensure(aux_, size_t(b->slots) * AUX_W * 4, false, err)) return 2;
            storeSlots_ = b->slots;
        }
        VkCommandBuffer cmd = VkCommandBuffer(b->vkCommandBuffer);
        if (!progUp_) {
            VkBufferCopy c{0, 0, progBytes_};
            vkCmdCopyBuffer(cmd, staging_.buf, prog_.buf, 1, &c);
            progUp_ = true;
        }
        // the origins (cx, cz, the 5 x 5 slots) and the slots they read (each once), in the command buffer
        uint32_t n = b->count;
        const int32_t* nbh = b->neighborsHost;
        std::vector<int32_t> org(size_t(n) * ORIGIN_W, -1), slots;
        std::vector<uint8_t> seen(b->slots, 0);
        for (uint32_t i = 0; i < n; i++) {
            org[size_t(i) * ORIGIN_W] = b->chunks[i * 3];
            org[size_t(i) * ORIGIN_W + 1] = b->chunks[i * 3 + 1];
            for (uint32_t j = 0; j < 25; j++) {
                int32_t sl = nbh[size_t(i) * 25 + j];
                org[size_t(i) * ORIGIN_W + 2 + j] = sl;
                if (sl >= 0 && uint32_t(sl) < b->slots && !seen[size_t(sl)]) {
                    seen[size_t(sl)] = 1;
                    slots.push_back(sl);
                }
            }
        }
        vkCmdUpdateBuffer(cmd, org_.buf, 0, org.size() * 4, org.data());
        vkCmdUpdateBuffer(cmd, list_.buf, 0, std::max<size_t>(1, slots.size()) * 4, slots.empty() ? org.data() : slots.data());
        barrier(cmd, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        // the bindings: the program, the store's terrain, our aux (per store slot), the origins, the scratch, the store's
        // blocks, the slot list, the store's aux, the counter
        VkBuffer bufs[NB] = {prog_.buf, VkBuffer(b->terrain), aux_.buf, org_.buf, scratch_.buf, VkBuffer(b->blocks), list_.buf,
                             VkBuffer(b->aux), cnt_.buf};
        VkDescriptorBufferInfo bi[NB];
        VkWriteDescriptorSet w[NB];
        for (uint32_t i = 0; i < NB; i++) {
            bi[i] = {bufs[i], 0, VK_WHOLE_SIZE};
            w[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
            w[i].dstSet = set_;
            w[i].dstBinding = i;
            w[i].descriptorCount = 1;
            w[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            w[i].pBufferInfo = &bi[i];
        }
        vkUpdateDescriptorSets(dev_, NB, w, 0, nullptr);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout_, 0, 1, &set_, 0, nullptr);
        FeatParams p{int32_t(n), 0, 0, 0, int32_t(slots.size()), 1u, 1u << 30, 1u};
        auto dispatch = [&](uint32_t pipe, uint32_t gx, uint32_t gy) {
            vkCmdPushConstants(cmd, layout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(FeatParams), &p);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipes_[pipe]);
            vkCmdDispatch(cmd, gx, gy, 1);
            barrier(cmd, VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        };
        if (!slots.empty()) {
            dispatch(S_AUX, (AUX_W + GROUP - 1) / GROUP, uint32_t(slots.size()));
            dispatch(S_HM, 4, uint32_t(slots.size()));
        }
        dispatch(S_INIT, 64, n);
        for (uint32_t rec : dispatch_) {
            uint32_t type = rec < types_.size() ? types_[rec] : 0;
            if (type == 0 || type >= FT_TYPES) continue;
            p.rec = rec;
            dispatch(S_FEAT + type - 1, n, 1);  // a group (one warp) an origin; every placement in one dispatch
        }
        dispatch(S_APPLY, HT_CAP / GROUP, n);
        return 0;
    }

private:
    static bool fail(std::string& err, std::string m) {
        err = std::move(m);
        return false;
    }
    static void barrier(VkCommandBuffer cmd, VkAccessFlags src, VkAccessFlags dst, VkPipelineStageFlags from, VkPipelineStageFlags to) {
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = src;
        mb.dstAccessMask = dst;
        vkCmdPipelineBarrier(cmd, from, to, 0, 1, &mb, 0, nullptr, 0, nullptr);
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
        release(b);
        VkBufferCreateInfo bc{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bc.size = size;
        bc.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        if (vkCreateBuffer(dev_, &bc, nullptr, &b.buf) != VK_SUCCESS) return fail(err, "vkCreateBuffer");
        VkMemoryRequirements req;
        vkGetBufferMemoryRequirements(dev_, b.buf, &req);
        VkMemoryPropertyFlags want =
            host ? (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) : VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
        uint32_t type = memoryType(req.memoryTypeBits, want);
        if (type == UINT32_MAX) return fail(err, "no suitable memory type");
        VkMemoryAllocateInfo ma{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ma.allocationSize = req.size;
        ma.memoryTypeIndex = type;
        if (vkAllocateMemory(dev_, &ma, nullptr, &b.mem) != VK_SUCCESS) {
            vkDestroyBuffer(dev_, b.buf, nullptr);
            b.buf = VK_NULL_HANDLE;
            return fail(err, "vkAllocateMemory");
        }
        vkBindBufferMemory(dev_, b.buf, b.mem, 0);
        if (host && vkMapMemory(dev_, b.mem, 0, VK_WHOLE_SIZE, 0, &b.map) != VK_SUCCESS) return fail(err, "vkMapMemory");
        b.size = size;
        return true;
    }

    VkDevice dev_ = VK_NULL_HANDLE;
    VkPhysicalDevice phys_ = VK_NULL_HANDLE;
    std::vector<uint32_t> dispatch_, types_;
    VkShaderModule modules_[S_PIPES] = {};
    VkPipeline pipes_[S_PIPES] = {};
    VkDescriptorSetLayout setLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout layout_ = VK_NULL_HANDLE;
    VkDescriptorPool descPool_ = VK_NULL_HANDLE;
    VkDescriptorSet set_ = VK_NULL_HANDLE;
    Buf prog_, staging_, aux_, org_, scratch_, list_, cnt_;
    size_t progBytes_ = 0;
    bool progUp_ = false;
    uint32_t storeSlots_ = 0;
};

int32_t decorateStage(void* user, const AtlasGenStoreBatch* b) { return static_cast<StageEngine*>(user)->decorate(b); }
// the finish: a chunk is done once every origin around it decorated (its radius 1): nothing to record
int32_t finishStage(void*, const AtlasGenStoreBatch*) { return 0; }
#endif

}  // namespace

struct AtlasFeat {
    std::unique_ptr<Engine> engine;
    std::string error;
    bool program = false;
#ifndef ATLAS_FEAT_ALONE
    // atlas_feat_attach: the generator and the stages registered there
    AtlasGen* gen = nullptr;
    uint32_t stageIds[2] = {0, 0};
    std::unique_ptr<StageEngine> stage;
#endif
};

namespace {
template <typename F>
AtlasStatus guarded(AtlasFeat* h, F f) {
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

ATLAS_API AtlasStatus atlas_feat_create(AtlasBackend backend, AtlasFeat** out) {
    if (!out) return ATLAS_ERR_ARG;
    *out = nullptr;
    return guarded(nullptr, [&] {
        auto h = std::make_unique<AtlasFeat>();
        if (backend == ATLAS_BACKEND_VULKAN) {
            auto vk = std::make_unique<VulkanEngine>();
            std::string err;
            if (!vk->init(err)) {
                std::fprintf(stderr, "[atlas] decoration: %s\n", err.c_str());
                return ATLAS_ERR_UNSUPPORTED;
            }
            h->engine = std::move(vk);
        } else {
            return ATLAS_ERR_UNSUPPORTED;  // GPU only (the CPU fallback is a later job)
        }
        *out = h.release();
        return ATLAS_OK;
    });
}

ATLAS_API void atlas_feat_destroy(AtlasFeat* h) {
#ifndef ATLAS_FEAT_ALONE
    if (h && h->gen) {
        for (uint32_t id : h->stageIds)
            if (id) atlas_gen_remove_stage(h->gen, id);
    }
#endif
    delete h;
}

#ifndef ATLAS_FEAT_ALONE
ATLAS_API AtlasStatus atlas_feat_attach(AtlasGen* gen, const uint32_t* words, uint32_t count, const uint32_t* gen2feat, uint32_t nGen,
                                        const uint32_t* feat2gen, uint32_t nFeat, AtlasFeat** out) {
    if (!gen || !words || count < 32 || words[0] != MAGIC || !out || (gen2feat && !feat2gen) || (!gen2feat && feat2gen)) return ATLAS_ERR_ARG;
    *out = nullptr;
    return guarded(nullptr, [&] {
        auto h = std::make_unique<AtlasFeat>();
        AtlasGenDevice d{};
        if (atlas_gen_device_handles(gen, &d) != ATLAS_OK) return ATLAS_ERR_UNSUPPORTED;
        // the program with the store's tables after it
        std::vector<uint32_t> prog(words, words + count);
        if (gen2feat) {
            prog[H_GEN2FEAT] = uint32_t(prog.size());
            prog.insert(prog.end(), gen2feat, gen2feat + nGen);
            prog[H_NGEN] = nGen;
            prog[H_FEAT2GEN] = uint32_t(prog.size());
            prog.insert(prog.end(), feat2gen, feat2gen + nFeat);
        }
        h->stage = std::make_unique<StageEngine>();
        std::string err;
        if (!h->stage->init(d, prog, err)) {
            std::fprintf(stderr, "[atlas] decoration stages: %s\n", err.c_str());
            return ATLAS_ERR_GPU;
        }
        h->gen = gen;
        AtlasStatus s = atlas_gen_add_store_stage(gen, ATLAS_GEN_STAGE_DECORATION, 2, decorateStage, h->stage.get(), &h->stageIds[0]);
        if (s != ATLAS_OK) return s;
        s = atlas_gen_add_store_stage(gen, ATLAS_GEN_STAGE_DECORATION + 999, 1, finishStage, nullptr, &h->stageIds[1]);
        if (s != ATLAS_OK) return s;
        h->program = true;
        *out = h.release();
        return ATLAS_OK;
    });
}
#endif
ATLAS_API const char* atlas_feat_last_error(AtlasFeat* h) { return h ? h->error.c_str() : "null handle"; }
ATLAS_API const char* atlas_feat_device(AtlasFeat* h) { return h ? h->engine->device() : "null handle"; }

ATLAS_API AtlasStatus atlas_feat_set_program(AtlasFeat* h, const uint32_t* words, uint32_t count) {
    if (!h || !words || count < 32 || words[0] != MAGIC) return ATLAS_ERR_ARG;
    return guarded(h, [&] {
        std::vector<uint32_t> prog(words, words + count);
        if (!h->engine->setProgram(prog, h->error)) return ATLAS_ERR_GPU;
        h->program = true;
        return ATLAS_OK;
    });
}

ATLAS_API AtlasStatus atlas_feat_run(AtlasFeat* h, uint32_t slots, const uint32_t* ids, const uint32_t* aux, uint32_t origins,
                                     const int32_t* org, uint32_t targets, const int32_t* tgt, uint32_t* outIds, uint32_t* outTables,
                                     AtlasFeatTimes* times) {
    if (!h || !ids || !aux || !org || (targets && !tgt) || slots == 0 || origins == 0) return ATLAS_ERR_ARG;
    if (!h->program) return ATLAS_ERR_STATE;
    for (uint32_t o = 0; o < origins; o++)
        for (uint32_t s = 0; s < 25; s++)
            if (org[o * ORIGIN_W + 2 + s] >= int32_t(slots)) return ATLAS_ERR_ARG;
    for (uint32_t t = 0; t < targets; t++) {
        if (tgt[t * TARGET_W] >= int32_t(slots)) return ATLAS_ERR_ARG;
        for (uint32_t k = 0; k < 9; k++)
            if (tgt[t * TARGET_W + 1 + k] >= int32_t(origins)) return ATLAS_ERR_ARG;
    }
    return guarded(h, [&] {
        AtlasFeatTimes t{};
        auto t0 = std::chrono::steady_clock::now();
        Batch b{slots, origins, targets, ids, aux, org, tgt};
        if (!h->engine->run(b, outIds, outTables, t, h->error)) return ATLAS_ERR_GPU;
        t.totalMs = msSince(t0);
        if (times) *times = t;
        return ATLAS_OK;
    });
}
