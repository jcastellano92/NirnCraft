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
#include <thread>
#include <unordered_map>
#include <vector>

#include "atlas/atlas.h"
#include "atlas_feat_hm_spv.h"  // generated: slangc + spv_exact, one module per kernel
#include "atlas_feat_mask_spv.h"
#include "atlas_feat_farplaces_spv.h"
#include "atlas_feat_fartrees_spv.h"
#include "atlas_feat_farcanopy_spv.h"
#include "atlas_feat_vein_spv.h"
#include "atlas_feat_lakew_spv.h"
#include "atlas_feat_rockw_spv.h"
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
constexpr uint32_t NB = 13, WIDE_W = 44u * 4096u;
// pipelines: hm, init, copy, apply, pack, then one a feature type (atlas_features.slang FT_*: 1 ore .. 8 lake)
constexpr uint32_t P_HM = 0, P_INIT = 1, P_COPY = 2, P_APPLY = 3, P_PACK = 4, P_FEAT = 5, FT_TYPES = 9, P_AUX = P_FEAT + FT_TYPES - 1,
                   PIPES = P_AUX + 1;
constexpr uint32_t R_TYPE = 0;
// the counters buffer: [0] the origins with placements left; (dev, atlas_features.slang FEAT_CLOCK) [16 + k] the clock
// probes' sums, [48 + k] their longest
constexpr uint32_t CNT_BYTES = 1024, CLK_N = 32;

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
    // the store's aux kernel over n chunks ((cx, cz) each) with the generator's map: n * AUX_W words (the quarts not)
    virtual bool probeAux(const std::vector<uint32_t>& map, uint32_t n, const int32_t* xz, uint32_t* out, std::string& err) = 0;
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
            for (Buf* b : {&prog_, &ids_, &aux_, &org_, &scratch_, &out_, &tgt_, &wide_, &cnt_, &staging_, &map_}) release(*b);
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
            p.budget = type == 4 && !std::getenv("ATLAS_FEAT_TREES_AT_ONCE") ? 1u : 1u << 30;
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
                                       atlas_feat_lake_spv, atlas_feat_aux_spv};
        const size_t size[PIPES] = {atlas_feat_hm_spv_size,   atlas_feat_init_spv_size,  atlas_feat_copy_spv_size,
                                    atlas_feat_apply_spv_size, atlas_feat_pack_spv_size, atlas_feat_ore_spv_size,
                                    atlas_feat_cover_spv_size, atlas_feat_rock_spv_size, atlas_feat_tree_spv_size,
                                    atlas_feat_freeze_spv_size, atlas_feat_disk_spv_size, atlas_feat_spring_spv_size,
                                    atlas_feat_lake_spv_size, atlas_feat_aux_spv_size};
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
                  ensure(cnt_, CNT_BYTES, false, err);
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
        Buf* bs[NB] = {&prog_, &ids_, &aux_, &org_, &scratch_, &out_, &tgt_, &wide_, &cnt_, map_.buf ? &map_ : &prog_,
                       &prog_, &prog_, &prog_};  // (9: the map; 10, 11: the overlays' boxes, 12 the far planes: the stages')
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
    Buf prog_, ids_, aux_, org_, scratch_, out_, tgt_, wide_, cnt_, staging_, map_;

public:
    bool probeAux(const std::vector<uint32_t>& map, uint32_t n, const int32_t* xz, uint32_t* out, std::string& err) override {
        if (dead_) return fail(err, "the decoration's device was lost earlier");
        vkDeviceWaitIdle(dev_);
        std::vector<int32_t> list(size_t(n) * 3);
        for (uint32_t i = 0; i < n; i++) {
            list[i * 3] = int32_t(i);
            list[i * 3 + 1] = xz[i * 2];
            list[i * 3 + 2] = xz[i * 2 + 1];
        }
        bool ok = ensure(map_, map.size() * 4, false, err) && ensure(tgt_, list.size() * 4, false, err) &&
                  ensure(aux_, size_t(n) * AUX_W * 4, false, err) && ensure(wide_, size_t(n) * ATLAS_GEN_STORE_AUX_WORDS * 4, false, err);
        if (!ok) return false;
        writeSet();
        if (!upload(map_, map.data(), map.size() * 4, err) || !upload(tgt_, list.data(), list.size() * 4, err)) return false;
        FeatParams p{0, 0, 0, 0, int32_t(n), 0, 0, 1u};
        begin();
        vkCmdBindDescriptorSets(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, layout_, 0, 1, &set_, 0, nullptr);
        vkCmdPushConstants(cmd_, layout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(FeatParams), &p);
        vkCmdBindPipeline(cmd_, VK_PIPELINE_BIND_POINT_COMPUTE, pipes_[P_AUX]);
        vkCmdDispatch(cmd_, (AUX_W + GROUP - 1) / GROUP, n, 1);
        if (!submitWait(err)) return false;
        return download(aux_, 0, out, size_t(n) * AUX_W * 4, err);
    }
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
// pipelines: hm, init, aux, store apply, one a feature type, then the placement mask
constexpr uint32_t S_HM = 0, S_INIT = 1, S_AUX = 2, S_APPLY = 3, S_FEAT = 4, S_MASK = S_FEAT + FT_TYPES - 1, S_FARPLACES = S_MASK + 1,
                   S_FARTREES = S_MASK + 2, S_FARCANOPY = S_MASK + 3, S_VEIN = S_MASK + 4, S_LAKEW = S_MASK + 5, S_ROCKW = S_MASK + 6,
                   S_PIPES = S_MASK + 7;
constexpr uint32_t H_PLACES = 29;  // (AtlasFeatureProgram: the settlements' table)
constexpr uint32_t VEIN_W = 12288;  // (atlas_features.slang featVeinMain: a thread a position of an ore vein's box)

class StageEngine {
public:
    ~StageEngine() {
        if (!dev_) return;
        flushAux();
        for (Buf* b : {&prog_, &staging_, &aux_, &org_, &scratch_, &list_, &cnt_, &dbg_, &left_}) release(*b);
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
                                         atlas_feat_freeze_spv, atlas_feat_disk_spv, atlas_feat_spring_spv, atlas_feat_lake_spv,
                                         atlas_feat_mask_spv, atlas_feat_farplaces_spv, atlas_feat_fartrees_spv,
                                         atlas_feat_farcanopy_spv, atlas_feat_vein_spv, atlas_feat_lakew_spv,
                                         atlas_feat_rockw_spv};
        const size_t size[S_PIPES] = {atlas_feat_hm_spv_size, atlas_feat_init_spv_size, atlas_feat_aux_spv_size,
                                      atlas_feat_sapply_spv_size, atlas_feat_ore_spv_size, atlas_feat_cover_spv_size,
                                      atlas_feat_rock_spv_size, atlas_feat_tree_spv_size, atlas_feat_freeze_spv_size,
                                      atlas_feat_disk_spv_size, atlas_feat_spring_spv_size, atlas_feat_lake_spv_size,
                                      atlas_feat_mask_spv_size, atlas_feat_farplaces_spv_size, atlas_feat_fartrees_spv_size,
                                      atlas_feat_farcanopy_spv_size, atlas_feat_vein_spv_size, atlas_feat_lakew_spv_size,
                                      atlas_feat_rockw_spv_size};
        for (uint32_t i = 0; i < S_PIPES; i++) {
            VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
            smci.codeSize = size[i];
            smci.pCode = code[i];
            if (vkCreateShaderModule(dev_, &smci, nullptr, &modules_[i]) != VK_SUCCESS) return fail(err, "shader module");
        }
        // the pipelines on threads of their own: the driver compiles them side by side (the tree kernel's alone is
        // about half a minute on a cold cache; one after the other they were a minute)
        std::vector<VkResult> res(S_PIPES, VK_SUCCESS);
        std::vector<std::thread> th;
        for (uint32_t i = 0; i < S_PIPES; i++)
            th.emplace_back([&, i] {
                VkComputePipelineCreateInfo cp{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
                cp.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
                cp.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
                cp.stage.module = modules_[i];
                cp.stage.pName = "main";
                cp.layout = layout_;
                res[i] = vkCreateComputePipelines(dev_, VK_NULL_HANDLE, 1, &cp, nullptr, &pipes_[i]);
            });
        for (auto& t : th) t.join();
        for (uint32_t i = 0; i < S_PIPES; i++)
            if (res[i] != VK_SUCCESS) return fail(err, "pipeline " + std::to_string(i) + " refused");
        VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, NB * 2};
        VkDescriptorPoolCreateInfo dp{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        dp.maxSets = 2;
        dp.poolSizeCount = 1;
        dp.pPoolSizes = &ps;
        if (vkCreateDescriptorPool(dev_, &dp, nullptr, &descPool_) != VK_SUCCESS) return fail(err, "descriptor pool");
        VkDescriptorSetAllocateInfo da{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        da.descriptorPool = descPool_;
        da.descriptorSetCount = 1;
        da.pSetLayouts = &setLayout_;
        if (vkAllocateDescriptorSets(dev_, &da, &set_) != VK_SUCCESS) return fail(err, "descriptor set");
        if (vkAllocateDescriptorSets(dev_, &da, &farSet_) != VK_SUCCESS) return fail(err, "descriptor set");  // (the far stage's)
        // the far trees: the settlements a tile asks (the places table's count), the keys' species field (21 bits)
        if (prog.size() > H_PLACES && prog[H_PLACES] && prog[H_PLACES] < prog.size()) places_ = prog[prog[H_PLACES]];
        farOk_ = prog.size() < (1u << 21);
        // the program goes up with the first call's commands (its copy recorded there: no queue of ours)
        bool ok = ensure(prog_, prog.size() * 4, false, err) && ensure(staging_, prog.size() * 4, true, err) &&
                  ensure(org_, size_t(MAX_ORIGINS) * ORIGIN_W * 4, false, err) &&
                  ensure(scratch_, size_t(MAX_ORIGINS) * SCR_W * 4, false, err) &&
                  ensure(list_, size_t(MAX_ORIGINS) * 25 * 3 * 4, false, err) && ensure(cnt_, CNT_BYTES, false, err);
        if (!ok) return false;
        std::memcpy(staging_.map, prog.data(), prog.size() * 4);
        progBytes_ = prog.size() * 4;
        return true;
    }

    // the decoration stage: up to 64 chunks, each with its 5 x 5 store slots
    // The far stage (atlas_gen_add_far_stage): the trees of a far tile as its canopy planes (atlas_features.slang
    // featFarTreesMain): the settlements near it, the trees of its chunks, each column's crown
    int32_t far(const AtlasGenFarBatch* b) {
        if (!farOk_ || b->lod < 1 || b->lod > 6) return 0;
        VkCommandBuffer cmd = VkCommandBuffer(b->vkCommandBuffer);
        if (!progUp_) {
            VkBufferCopy c{0, 0, progBytes_};
            vkCmdCopyBuffer(cmd, staging_.buf, prog_.buf, 1, &c);
            progUp_ = true;
        }
        // (version 2: in parts) part 0 binds the tile and finds its settlements; then `units` tree records a part, each
        // a dispatch over the tile's chunks (a record's trees: seeded on their own, written by atomics: any split is
        // exact); the canopy with the last
        uint32_t part = b->version >= 2 ? b->part : 0, units = b->version >= 2 ? b->units : 0;
        const bool sized = b->version >= 3;  // (the stage sizes its parts itself: farSizedParts)
        if (part == 0) {
            VkBuffer planes = VkBuffer(b->planes);
            VkBuffer bufs[NB] = {prog_.buf, prog_.buf, scratch_.buf, prog_.buf, scratch_.buf, scratch_.buf, prog_.buf,
                                 scratch_.buf, scratch_.buf, VkBuffer(b->map), prog_.buf, prog_.buf, planes};
            VkDescriptorBufferInfo bi[NB];
            VkWriteDescriptorSet w[NB];
            for (uint32_t i = 0; i < NB; i++) {
                bi[i] = {bufs[i], 0, VK_WHOLE_SIZE};
                w[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
                w[i].dstSet = farSet_;
                w[i].dstBinding = i;
                w[i].descriptorCount = 1;
                w[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
                w[i].pBufferInfo = &bi[i];
            }
            vkUpdateDescriptorSets(dev_, NB, w, 0, nullptr);
            vkCmdFillBuffer(cmd, scratch_.buf, 0, 4, 0u);  // (the places list's count)
            barrier(cmd, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        }
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout_, 0, 1, &farSet_, 0, nullptr);
        FeatParams p{b->tx, b->tz, 0, uint32_t(b->lod), int32_t(b->tile), 0u, 0u, 0u};
        auto dispatch = [&](uint32_t pipe, uint32_t gx, uint32_t gy = 1) {
            vkCmdPushConstants(cmd, layout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(FeatParams), &p);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipes_[pipe]);
            vkCmdDispatch(cmd, std::max(1u, gx), std::max(1u, gy), 1);
            barrier(cmd, VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        };
        uint32_t side = b->lod <= 4 ? (16u << uint32_t(b->lod)) : 256u;  // the chunks a tile side the trees run on
        uint32_t groups = (side * side + GROUP - 1) / GROUP;
        if (part == 0) {
            dispatch(S_FARPLACES, (places_ + GROUP - 1) / GROUP);
            if (sized) {
                // the rows of the grid: the dispatch list from its first tree record to its last (the kernel lets the others go)
                farRow0_ = farRows_ = 0;
                for (uint32_t i = 0; i < dispatch_.size(); i++) {
                    uint32_t rec = dispatch_[i];
                    if (rec >= types_.size() || types_[rec] != 4u) continue;
                    if (farRows_ == 0) farRow0_ = i;
                    farRows_ = i - farRow0_ + 1;
                }
                farOff_ = 0;
                farPlan_.clear();
                farFed_ = 0;
            } else if (units == 0) {
                dispatch(S_FARTREES, groups);  // (all at once: an older generator)
                dispatch(S_FARCANOPY, ATLAS_GEN_TILE_COLS / GROUP);
                return 0;
            } else {
            // (a unit: a tree record over a slice of 4096 of the tile's chunks)
            farRecs_.clear();
            for (uint32_t rec : dispatch_)
                if (rec < types_.size() && types_[rec] == 4u)
                    for (uint32_t t0 = 0; t0 < side * side; t0 += FAR_SLICE) farRecs_.push_back({rec, t0});
            farCursor_ = 0;
            }
        }
        if (sized) return farSizedParts(b, part, side * side, p, dispatch);
        for (uint32_t i = 0; i < std::max(1u, units) && farCursor_ < farRecs_.size(); i++) {
            p.rec = farRecs_[farCursor_].first;
            p.first = farRecs_[farCursor_].second;
            farCursor_++;
            dispatch(S_FARTREES, (std::min(FAR_SLICE, side * side - p.first) + GROUP - 1) / GROUP);
        }
        if (farCursor_ < farRecs_.size()) return ATLAS_GEN_STAGE_MORE;
        p.rec = 0;
        p.first = 0;
        dispatch(S_FARCANOPY, ATLAS_GEN_TILE_COLS / GROUP);
        return 0;
    }
    static constexpr uint32_t FAR_SLICE = 4096;
    std::vector<std::pair<uint32_t, uint32_t>> farRecs_;  // (the far stage in parts) (tree record, first chunk) units, the next
    size_t farCursor_ = 0;

    // ---- the far stage sized by itself (AtlasGenFarBatch version 3) ----
    // A part is ONE dispatch of featFarTreesMain: a range of the tile's chunks by the tree records, one record a row of the
    // grid (params.rec = FAR_ROWS), some hundred thousand threads at once. A record a dispatch left the card nearly idle (64
    // groups of 64 threads of the 2000 it holds at a time, each thread a chunk's whole walk): a tile's trees took 0.45 s, and a
    // dispatch's time did not fall with the chunks given it. The range is sized from the parts done (the generator gives each
    // part's own GPU time and keeps at most two in flight): the time a chunk took (all the records), the next range that
    // much for half the budget (ATLAS_GEN_PART_MS, 4), at most twice the last; smaller at once when a part ran long. A tree
    // record is seeded on its own and writes by atomics, so any grouping of (chunk, record) pairs is exact.
    // ATLAS_FAR_PROFILE=1: ATLAS_FAR_SLICE (4096) chunks a part, labelled with the level and the count (the budget's counters
    // then show what a part of that size costs); ATLAS_FAR_SLICE_MAX (4096) caps a part.
    struct FarCost {
        double n = 0;  // (the chunks a part takes at this level now)
    };
    struct FarItem {
        uint32_t n;
    };
    std::unordered_map<uint32_t, FarCost> farCost_;  // (per level)
    std::vector<FarItem> farPlan_;
    size_t farFed_ = 0;
    uint32_t farOff_ = 0, farRow0_ = 0, farRows_ = 0;
    template <class D>
    int32_t farSizedParts(const AtlasGenFarBatch* b, uint32_t part, uint32_t chunks, FeatParams& p, D& dispatch) {
        static const bool profile = std::getenv("ATLAS_FAR_PROFILE") != nullptr;
        static const uint32_t maxN = [] {
            const char* e = std::getenv("ATLAS_FAR_SLICE_MAX");
            return std::max(64u, (e ? uint32_t(std::atoi(e)) : 16384u) & ~63u);
        }();
        static const uint32_t profileN = [] {
            const char* e = std::getenv("ATLAS_FAR_SLICE");
            return std::max(64u, (e ? uint32_t(std::atoi(e)) : 4096u) & ~63u);
        }();
        const double budget = b->budgetUs ? double(b->budgetUs) / 1000.0 : 4.0;
        const uint32_t lod = uint32_t(b->lod);
        FarCost& c = farCost_[lod];
        // The part's own time steers the size. A dispatch of these threads costs a floor (a thread's whole walk, some ms)
        // and little more up to a few thousand chunks: sizing from a time a chunk (time over chunks) shrank the parts to the
        // floor's 64 chunks, 260 parts a tile. The floor is 5 to 7 ms and the time hardly moves with the chunks up to a few
        // hundred, so the part grows by a quarter while it runs under 85 % of the limit (twice the budget, 8 ms: what a
        // submission may take) and is cut to the share of it when it ran over.
        const double limit = budget * 2.0;  // (8 ms: what a submission may take; a limit of 6 ms made twice the parts for the same minutes)
        if (c.n <= 0.0) c.n = 256.0;
        for (; farFed_ < b->partsDone && farFed_ < farPlan_.size(); farFed_++) {
            double t = double(b->partUs[farFed_]) / 1000.0, used = double(farPlan_[farFed_].n);
            if (t > limit) c.n = std::max(256.0, used * limit / t * 0.85);  // (256 at least: beside the frames a part's time holds their share)
            else if (t < limit * 0.85 && used >= c.n * 0.95) c.n = std::min(c.n * 1.25, double(maxN));
        }
        const uint32_t left = chunks - farOff_;
        if (part == 0) c.n = std::min(c.n, 2048.0);  // (a new tile: not sized from the last tile's end alone)
        uint32_t n = profile ? profileN : uint32_t(c.n);
        n = std::min(left, std::max(64u, n & ~63u));
        // (a thread's scratch is a slot of the dispatch: its width times the rows, past the places' list: within the scratch)
        if (farRows_ > 0) {
            uint64_t room = scratch_.size / 4 > 16384u + 12u * farRows_ * 64u ? (scratch_.size / 4 - 16384u) / (12ull * farRows_) : 0;
            n = std::max(64u, std::min<uint32_t>(n, uint32_t(room) & ~63u));
            uint32_t w = (n + GROUP - 1) / GROUP * GROUP;
            p.rec = 0xFFFFFFFFu;  // (FAR_ROWS)
            p.store = farRow0_;
            p.first = farOff_;
            p.budget = w;
            dispatch(S_FARTREES, w / GROUP, farRows_);
        }
        farOff_ += n;
        bool last = farOff_ >= chunks || farRows_ == 0;
        if (last) {
            p.rec = 0;
            p.first = 0;
            p.store = 0;
            p.budget = 0;
            dispatch(S_FARCANOPY, ATLAS_GEN_TILE_COLS / GROUP);
        }
        static const bool trace = std::getenv("ATLAS_FAR_TRACE") != nullptr;
        if (trace && part < 40)
            std::fprintf(stderr, "TRACE far lod %u part %u: %u chunks of %u (target n %.0f), %u rows, done %u, last part %.2f ms\n", lod, part, n, chunks,
                         c.n, farRows_, b->partsDone, b->partsDone ? double(b->partUs[b->partsDone - 1]) / 1000.0 : 0.0);
        if (part < farPlan_.size()) farPlan_.resize(part);
        farPlan_.push_back({n});  // (index = the part: the generator calls once a part, in order)
        char label[48] = {};
        if (profile) std::snprintf(label, sizeof label, "lod %u n %u", lod, n);
        std::memcpy(const_cast<char*>(b->label), label, sizeof label);
        return last ? 0 : ATLAS_GEN_STAGE_MORE;
    }

    // atlas_feat_set_overlays: the overlays whose piece boxes the mask reads (null: none)
    void setOverlays(AtlasOvl* o) { ovl_ = o; }

    int32_t decorate(const AtlasGenStoreBatch* b) {
        std::string err;
        if (b->radius != 2 || b->count > MAX_ORIGINS) return 1;
        // (version 3: the parts are not waited for) a round's counter and time are read once its part is done: until
        // then nothing is recorded (the generator waits for its oldest part in flight, then calls again with this part)
        uint32_t done = b->version >= 3 ? b->partsDone : (b->version >= 2 ? b->part : 0);
        bool decide = b->version >= 2 && b->part > 0 && partRoundRec_ != ~0u && !(partVein_ && partPhase_ < 5);
        if (decide && done <= partRoundPart_) return ATLAS_GEN_STAGE_WAIT;
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
        // (version 2: in parts) the work as steps: the slots' aux, heights and mask, the origins' init, a pass a placed
        // feature, the apply; a part records `units` of them (0: all) and says MORE while some are left. The origins
        // and the slot list go up with part 0 and stay for the parts after it (the generator calls again at once).
        uint32_t part = b->version >= 2 ? b->part : 0, units = b->version >= 2 ? b->units : 0;
        uint32_t n = b->count;
        if (part == 0) {
            // the origins (cx, cz, the 5 x 5 slots) and the slots they read (each once), in the command buffer
            const int32_t* nbh = b->neighborsHost;
            std::vector<int32_t> org(size_t(n) * ORIGIN_W, -1), slots;  // slots: (slot, cx, cz) each
            std::vector<uint8_t> seen(b->slots, 0);
            for (uint32_t i = 0; i < n; i++) {
                int32_t cx = b->chunks[i * 3], cz = b->chunks[i * 3 + 1];
                org[size_t(i) * ORIGIN_W] = cx;
                org[size_t(i) * ORIGIN_W + 1] = cz;
                for (uint32_t j = 0; j < 25; j++) {
                    int32_t sl = nbh[size_t(i) * 25 + j];
                    org[size_t(i) * ORIGIN_W + 2 + j] = sl;
                    if (sl >= 0 && uint32_t(sl) < b->slots && !seen[size_t(sl)]) {
                        seen[size_t(sl)] = 1;
                        slots.insert(slots.end(), {sl, cx + int32_t(j % 5) - 2, cz + int32_t(j / 5) - 2});
                    }
                }
            }
            vkCmdUpdateBuffer(cmd, org_.buf, 0, org.size() * 4, org.data());
            vkCmdUpdateBuffer(cmd, list_.buf, 0, std::max<size_t>(1, slots.size()) * 4, slots.empty() ? org.data() : slots.data());
            barrier(cmd, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            // and the overlays' piece boxes (atlas_ovl_store_boxes, asked each call: their buffers may have grown), when
            // they are on and have tapes (else the program in their place, and the mask without the pieces)
            AtlasOvlStoreBoxes boxes{};
            bool haveBoxes = ovl_ && atlas_ovl_store_boxes(ovl_, &boxes) == ATLAS_OK && boxes.tapes && boxes.slotRecords &&
                             boxes.slots >= b->slots;
            partBoxes_ = haveBoxes;
            partTapes_ = haveBoxes ? VkBuffer(boxes.tapes) : prog_.buf;
            partRecords_ = haveBoxes ? VkBuffer(boxes.slotRecords) : prog_.buf;
            partSlotXZ_ = slots;
            partSlots_ = uint32_t(slots.size() / 3);
            partCursor_ = 0;
            partRound_ = 0;
            partRoundRec_ = ~0u;
            partSteps_.clear();
            if (partSlots_) partSteps_.insert(partSteps_.end(), {STEP_AUX, STEP_HM, STEP_MASK});
            partSteps_.push_back(STEP_INIT);
            uint32_t skip = 0;  // ATLAS_FEAT_SKIP: feature types left out (dev checks)
            if (const char* sk = std::getenv("ATLAS_FEAT_SKIP"))
                for (const char* c = sk; *c; c++)
                    if (*c >= '1' && *c <= '9') skip |= 1u << uint32_t(*c - '0');
            for (uint32_t rec : dispatch_) {
                uint32_t type = rec < types_.size() ? types_[rec] : 0;
                if (type == 0 || type >= FT_TYPES || (skip & (1u << type))) continue;
                partSteps_.push_back(rec);
            }
            partSteps_.push_back(STEP_APPLY);
        }
        // the bindings: the program, the store's terrain, our aux (per store slot), the origins, the scratch, the store's
        // blocks, the slot list, the store's aux, the counter, the map, the overlays' boxes; written at part 0 (nothing of
        // the generator's is in flight then), bound each part
        if (part == 0) {
            VkBuffer bufs[NB] = {prog_.buf, VkBuffer(b->terrain), aux_.buf, org_.buf, scratch_.buf, VkBuffer(b->blocks), list_.buf,
                                 VkBuffer(b->aux), cnt_.buf, VkBuffer(b->map), partTapes_, partRecords_, prog_.buf};
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
        }
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout_, 0, 1, &set_, 0, nullptr);
        uint32_t nslots = partSlots_;
        FeatParams p{int32_t(n), 0, 0, 0, int32_t(nslots), 1u, 1u << 30, 1u};
        auto dispatch = [&](uint32_t pipe, uint32_t gx, uint32_t gy) {
            vkCmdPushConstants(cmd, layout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(FeatParams), &p);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipes_[pipe]);
            vkCmdDispatch(cmd, gx, gy, 1);
            barrier(cmd, VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        };
        uint32_t total = uint32_t(partSteps_.size());
        if (units == 0) {
            // (all at once: an older generator) every step, every placement in one dispatch
            for (; partCursor_ < total; partCursor_++) runStep(cmd, p, dispatch, partSteps_[partCursor_], true, 1u << 30);
            return 0;
        }
        // In parts: a step a part, a placed feature in rounds of `budget` placements an origin (the kernel resumes from
        // the origin's stack: featBody), its budget fitted to about ATLAS_GEN_PART_MS from the last round's time
        // (lastPartUs); the round's count of origins with placements left read back for the next part.
        if (part > 0 && partRoundRec_ != ~0u && partVein_ && partPhase_ < 5) {
            // the round's vein passes, a dispatch a part (featVeinMain: the spheres' pair loop, a group an origin;
            // then clear, first visits, fates, and the fates into the origin's table)
            static const uint32_t order[5] = {3, 0, 1, 2, 4};
            uint32_t phase = partPhase_;
            partPhase_ = phase + 1;
            p.k = order[phase];
            dispatch(S_VEIN, order[phase] == 3 ? 1u : (VEIN_W + 63) / 64, n);
            p.k = 0;
            return ATLAS_GEN_STAGE_MORE;
        }
        if (decide) {
            static const double partMs = [] {
                const char* e = std::getenv("ATLAS_GEN_PART_MS");
                return e && std::atof(e) > 0 ? std::atof(e) : 4.0;
            }();
            // the round's GPU time (version 3: its own part's; before, the generator waited for each part: the last)
            bool v3 = b->version >= 3 && b->partUs;
            double ms = double(v3 ? b->partUs[partRoundPart_] : b->lastPartUs) / 1000.0;
            uint32_t& bud = featBudget_[partRoundRec_];
            // dev: ATLAS_FEAT_PART_TRACE=1: per placed feature its rounds' time and budget (and its vein passes'), to
            // stderr every 5 s
            static const bool ptrace = std::getenv("ATLAS_FEAT_PART_TRACE") != nullptr;
            for (uint32_t ph = 0; ptrace && ph < (partVein_ ? 6u : 1u); ph++) {
                if (ph > 0 && (!v3 || partRoundPart_ + ph >= done)) break;  // (a vein pass not done yet: not counted)
                double pms = ph == 0 ? ms : double(b->partUs[partRoundPart_ + ph]) / 1000.0;
                RecStat& rs = recStats_[partRoundRec_ | ph << 28];
                rs.max = std::max(rs.max, pms);
                rs.sum += pms;
                rs.n++;
                if (pms > 8.0) rs.over++;
                if (pms > 16.0)
                    std::fprintf(stderr, "TRACE feat long round: record %u type %u%s%u round %u budget %u origins %u: %.1f ms\n", partRoundRec_,
                                 types_[partRoundRec_], ph ? " vein pass " : "", ph ? ph - 1 : 0u, partRound_, bud, n, pms);
                rs.minBud = std::min(rs.minBud, bud);
            }
            if (ptrace) {
                auto now = std::chrono::steady_clock::now();
                if (now - recStatsAt_ > std::chrono::seconds(5)) {
                    recStatsAt_ = now;
                    std::vector<std::pair<double, uint32_t>> top;
                    for (auto& [r, st] : recStats_) top.push_back({st.max, r});
                    std::sort(top.rbegin(), top.rend());
                    for (size_t i = 0; i < top.size() && i < 12; i++) {
                        const RecStat& st = recStats_[top[i].second];
                        uint32_t r = top[i].second & 0x0FFFFFFFu, ph = top[i].second >> 28;
                        std::fprintf(stderr, "TRACE feat record %u type %u%s%u: rounds %llu, %.1f ms mean, %.1f max, %llu over 8 ms, budget down to %u\n",
                                     r, types_[r], ph ? " vein pass " : "", ph ? ph - 1 : 0u, (unsigned long long)st.n, st.sum / double(st.n), st.max,
                                     (unsigned long long)st.over, st.minBud);
                        if (clockOn() && ph == 0) {
                            // (the probes: per round, the origins' spans summed, and the longest one; 64 cycles a unit,
                            // in ms at 1.8 GHz; probes 7, 8 count)
                            std::string line;
                            char cb[96];
                            for (uint32_t k = 0; k < CLK_N; k++) {
                                if (!st.clkSum[k]) continue;
                                if (types_[r] == 1 && (k == 7 || k == 8 || (k >= 20 && k < 25)))
                                    std::snprintf(cb, sizeof cb, " [%u] n %llu", k, (unsigned long long)st.clkSum[k]);
                                else if (types_[r] == 1 && k >= 25 && k < 30)
                                    std::snprintf(cb, sizeof cb, " [%u] max %u", k, st.clkMax[k]);
                                else
                                    std::snprintf(cb, sizeof cb, " [%u] %.2f/round max %.2f", k, double(st.clkSum[k]) * 64.0 / 1.8e6 / double(st.n),
                                                  double(st.clkMax[k]) * 64.0 / 1.8e6);
                                line += cb;
                            }
                            if (types_[r] == 1 && staging_.map && size_t(r) + 5 < progBytes_ / 4) {
                                // (an ore's config: size, discard, targets and the first's rule kind)
                                const uint32_t* pw = static_cast<const uint32_t*>(staging_.map);
                                uint32_t cfg = pw[r + 5];
                                if (size_t(cfg) + 3 < progBytes_ / 4) {
                                    float disc;
                                    std::memcpy(&disc, &pw[cfg + 1], 4);
                                    std::snprintf(cb, sizeof cb, " ore size %u discard %.2f targets %u kind %u", pw[cfg], disc, pw[cfg + 2], pw[cfg + 3]);
                                    line += cb;
                                }
                            }
                            std::fprintf(stderr, "TRACE feat clock record %u:%s\n", r, line.c_str());
                        }
                    }
                }
            }
            if (ms > 0.0) {
                // The round's time says what a unit of budget costs only when the round used its budget (origins left: it was
                // cut short by it). A round that finished under it (a tree whose plan ended in a few operations, a feature of
                // two placements) says nothing about the next, and growing on it by 4 sent a commit of 73 ms after a round
                // of 1 (budget 65536 -> a part of 74 ms; 262144 -> 45): it keeps its budget, and a budget grows by 2 a round
                // at most. A round over the target cuts it at once, by up to 8.
                bool cut = left_.map && *static_cast<const uint32_t*>(left_.map) > 0u;
                double want = double(bud) * partMs / ms;
                double hi = ms > partMs ? double(bud) : cut ? double(bud) * 2.0 : double(bud);
                bud = uint32_t(std::clamp(want, std::max(1.0, double(bud) / 8.0), hi));
                bud = std::clamp<uint32_t>(bud, 1u, 1u << 20);
            }
            partLeft_ = left_.map ? *static_cast<const uint32_t*>(left_.map) : 0u;
            if (clockOn() && ptrace && left_.map) {
                const uint32_t* c = static_cast<const uint32_t*>(left_.map);
                RecStat& rs = recStats_[partRoundRec_];
                for (uint32_t k = 0; k < CLK_N; k++) {
                    rs.clkSum[k] += c[16 + k];
                    rs.clkMax[k] = std::max(rs.clkMax[k], c[48 + k]);
                }
            }
            partPhase_ = 0;
            uint32_t left = partLeft_;
            if (left > 0 && partRound_ < 100000) {
                partRound_++;
            } else {
                if (left > 0)
                    std::fprintf(stderr, "[atlas] decoration: record %u stopped after %u rounds with %u origins left (a vein may be lost)\n",
                                 partRoundRec_, partRound_, left);
                partCursor_++;  // (the feature done: the next step)
                partRound_ = 0;
            }
            partRoundRec_ = ~0u;
        }
        if (partCursor_ >= total) return 0;
        uint32_t step = partSteps_[partCursor_];
        if (step < STEP_AUX) {
            std::string err;
            if (!ensure(left_, CNT_BYTES, true, err)) return 3;
            static const bool treePhasesOn = [] {
                const char* e = std::getenv("ATLAS_FEAT_TREE_PHASES");
                return !(e && e[0] == '0');
            }();
            auto it = featBudget_.find(step);
            // (trees: an operation budget with the commit in phases, else one tree a round as before)
            static const bool lakesOn = [] {
                const char* e = std::getenv("ATLAS_FEAT_LAKES");
                return !(e && e[0] == '0');
            }();
            bool lakeParts = lakesOn && types_[step] == 8;
            static const bool rocksOn = [] {
                const char* e = std::getenv("ATLAS_FEAT_ROCKS");
                return !(e && e[0] == '0');
            }();
            bool rockParts = rocksOn && types_[step] == 3;
            uint32_t bud = it != featBudget_.end() ? it->second
                                                   : (featBudget_[step] = types_[step] == 4 ? (treePhasesOn ? 512u : 1u) : (lakeParts || rockParts) ? 4096u : 64u);
            vkCmdFillBuffer(cmd, cnt_.buf, 0, clockOn() ? CNT_BYTES : 4, 0u);
            barrier(cmd, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            // an ore's vein: its block loop on the vein kernel's threads (atlas_features.slang featVeinMain), its three
            // passes the parts after this one, written at the origin's next round; ATLAS_FEAT_VEINS=0: on the one lane
            static const bool veins = [] {
                const char* e = std::getenv("ATLAS_FEAT_VEINS");
                return !(e && e[0] == '0');
            }();
            partVein_ = veins && types_[step] == 1;
            partPhase_ = 0;
            if (partVein_) p.store = 3u;
            // a tree's commit in phases over the rounds (its budget: operations, not placements); ATLAS_FEAT_TREE_PHASES=0 off
            static const bool treePhases = [] {
                const char* e = std::getenv("ATLAS_FEAT_TREE_PHASES");
                return !(e && e[0] == '0');
            }();
            if (treePhases && types_[step] == 4) p.store = 5u;
            // a lake in parts (store | 8): its spots drawn in the round, their cells and its edge tests on the lake
            // kernel's threads right after it (featLakeWideMain), its rest within an operation budget in the rounds
            // after; ATLAS_FEAT_LAKES=0: on the one lane
            if (lakeParts) p.store = 9u;
            // a rock placement in parts (store | 16): each rock's shape on the rock kernel's threads right after the
            // round that issued it (featRockWideMain), its blocks within an operation budget the rounds after;
            // ATLAS_FEAT_ROCKS=0: on the one lane
            if (rockParts) p.store = 17u;
            runStep(cmd, p, dispatch, step, partRound_ == 0, bud);
            if (lakeParts) {
                p.k = 0;
                dispatch(S_LAKEW, (7u * 1176u + 63u) / 64u, n);
                p.k = 1;
                dispatch(S_LAKEW, 2048u / 64u, n);
                p.k = 0;
            }
            if (rockParts) dispatch(S_ROCKW, 65536u / 32u / 64u, n);  // (atlas_features.slang ROCK_BITS: a word a thread)
            p.store = 1u;
            barrier(cmd, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT);
            VkBufferCopy c{0, 0, clockOn() ? CNT_BYTES : 4};
            vkCmdCopyBuffer(cmd, cnt_.buf, left_.buf, 1, &c);
            barrier(cmd, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT);
            partRoundRec_ = step;
            partRoundPart_ = part;
            return ATLAS_GEN_STAGE_MORE;  // (its count read once it is done: another round, or the next step)
        }
        runStep(cmd, p, dispatch, step, true, 1u << 30);
        partCursor_++;
        return partCursor_ < total ? ATLAS_GEN_STAGE_MORE : 0;
    }
    // one step of decorate: a pass over the slots or origins, or a placed feature's round (first: its first round)
    template <class D>
    void runStep(VkCommandBuffer cmd, FeatParams& p, D& dispatch, uint32_t step, bool first, uint32_t budget) {
        uint32_t n = uint32_t(p.origins), nslots = uint32_t(p.slots);
        if (step == STEP_AUX) {
            dispatch(S_AUX, (AUX_W + GROUP - 1) / GROUP, nslots);
        } else if (step == STEP_HM) {
            dispatch(S_HM, 4, nslots);
        } else if (step == STEP_MASK) {
            p.k = partBoxes_ ? 1u : 0u;  // (the mask: k, the boxes bound)
            dispatch(S_MASK, 1, nslots);
            p.k = 0;
        } else if (step == STEP_INIT) {
            // dev: ATLAS_FEAT_AUX_DUMP=<file>: the aux of the slots each call read, (cx, cz, AUX_W words) a record, for
            // the checks against Java (written at the next call or at the end: the generator waits for each submission)
            if (const char* ad = std::getenv("ATLAS_FEAT_AUX_DUMP")) {
                std::string err;
                flushAux();
                dumpPath_ = ad;
                if (nslots && ensure(dbg_, size_t(MAX_ORIGINS) * 25 * AUX_W * 4, true, err)) {
                    std::vector<VkBufferCopy> cs;
                    for (uint32_t i = 0; i < nslots; i++) {
                        cs.push_back({VkDeviceSize(partSlotXZ_[i * 3]) * AUX_W * 4, VkDeviceSize(i) * AUX_W * 4, VkDeviceSize(AUX_W) * 4});
                        pendingXZ_.push_back(partSlotXZ_[i * 3 + 1]);
                        pendingXZ_.push_back(partSlotXZ_[i * 3 + 2]);
                    }
                    vkCmdCopyBuffer(cmd, aux_.buf, dbg_.buf, uint32_t(cs.size()), cs.data());
                    barrier(cmd, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                            VK_PIPELINE_STAGE_HOST_BIT);
                }
            }
            dispatch(S_INIT, 64, n);
        } else if (step == STEP_APPLY) {
            dispatch(S_APPLY, HT_CAP / GROUP, n);
        } else {
            p.rec = step;
            p.first = first ? 1u : 0u;
            p.budget = budget;
            dispatch(S_FEAT + types_[step] - 1, n, 1);  // a group (one warp) an origin
            p.first = 1u;
            p.budget = 1u << 30;
        }
    }
    // (decorate in parts) the call under way: its steps (a placed feature's record, or a STEP_ above every record) and
    // where it is, the slots it reads, the boxes it binds
    static constexpr uint32_t STEP_AUX = 0xFFFFFF00u, STEP_HM = 0xFFFFFF01u, STEP_MASK = 0xFFFFFF02u, STEP_INIT = 0xFFFFFF03u,
                              STEP_APPLY = 0xFFFFFF04u;
    std::vector<uint32_t> partSteps_;
    std::vector<int32_t> partSlotXZ_;
    uint32_t partCursor_ = 0, partSlots_ = 0;
    uint32_t partRound_ = 0, partRoundRec_ = ~0u, partRoundPart_ = 0;
    uint32_t partPhase_ = 0, partLeft_ = 0;  // (an ore's round: its vein pass next, and the round's count of origins left)
    bool partVein_ = false;  // (the feature in rounds: its round, the record the last part ran)
    std::unordered_map<uint32_t, uint32_t> featBudget_;  // per record: placements an origin a round (fitted to the part)
    Buf left_;  // (host) the last round's count of origins with placements left
    struct RecStat {
        double max = 0, sum = 0;
        uint64_t n = 0, over = 0;
        uint32_t minBud = ~0u;
        uint64_t clkSum[CLK_N] = {};
        uint32_t clkMax[CLK_N] = {};
    };
    static bool clockOn() {
        static const bool on = std::getenv("ATLAS_FEAT_CLOCK") && std::getenv("ATLAS_FEAT_CLOCK")[0] == '1';
        return on;
    }
    std::unordered_map<uint32_t, RecStat> recStats_;  // (ATLAS_FEAT_PART_TRACE)
    std::chrono::steady_clock::time_point recStatsAt_ = std::chrono::steady_clock::now();
    bool partBoxes_ = false;
    VkBuffer partTapes_ = VK_NULL_HANDLE, partRecords_ = VK_NULL_HANDLE;

private:
    void flushAux() {
        if (pendingXZ_.empty() || dumpPath_.empty() || !dbg_.map) return;
        if (FILE* f = std::fopen(dumpPath_.c_str(), "ab")) {
            for (size_t i = 0; i < pendingXZ_.size() / 2; i++) {
                std::fwrite(&pendingXZ_[i * 2], 4, 2, f);
                std::fwrite(static_cast<const uint32_t*>(dbg_.map) + i * AUX_W, 4, AUX_W, f);
            }
            std::fclose(f);
        }
        pendingXZ_.clear();
    }
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
    Buf prog_, staging_, aux_, org_, scratch_, list_, cnt_, dbg_;
    std::vector<int32_t> pendingXZ_;
    std::string dumpPath_;
    size_t progBytes_ = 0;
    bool progUp_ = false;
    uint32_t storeSlots_ = 0;
    AtlasOvl* ovl_ = nullptr;
    VkDescriptorSet farSet_ = VK_NULL_HANDLE;
    uint32_t places_ = 0;
    bool farOk_ = false;
};

int32_t decorateStage(void* user, const AtlasGenStoreBatch* b) { return static_cast<StageEngine*>(user)->decorate(b); }
int32_t farStage(void* user, const AtlasGenFarBatch* b) { return static_cast<StageEngine*>(user)->far(b); }
// the far stage's fingerprint (the tile cache's): its kernels' version and the feature program
constexpr uint64_t FAR_TREES_VERSION = 4;
uint64_t farFingerprint(const std::vector<uint32_t>& prog) {
    uint64_t h = 1469598103934665603ull ^ FAR_TREES_VERSION;
    for (uint32_t w : prog)
        for (int i = 0; i < 4; i++) h = (h ^ ((w >> (i * 8)) & 0xFFu)) * 1099511628211ull;
    return h;
}
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
    uint32_t stageIds[3] = {0, 0, 0};
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

ATLAS_API AtlasStatus atlas_feat_probe_aux(AtlasFeat* h, const uint32_t* map, uint32_t mapCount, uint32_t n, const int32_t* chunkXZ,
                                           uint32_t* outAux) {
    if (!h || !h->engine || !map || !chunkXZ || !outAux || n == 0) return ATLAS_ERR_ARG;
    return guarded(h, [&] {
        std::string err;
        std::vector<uint32_t> m(map, map + mapCount);
        if (!h->engine->probeAux(m, n, chunkXZ, outAux, err)) {
            h->error = err;
            return ATLAS_ERR_GPU;
        }
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
        // the far tiles' trees (ATLAS_FEAT_FAR=0: none, the far field's ground bare)
        const char* ff = std::getenv("ATLAS_FEAT_FAR");
        if (!(ff && ff[0] == '0')) {
            s = atlas_gen_add_far_stage(gen, farStage, h->stage.get(), farFingerprint(prog), &h->stageIds[2]);
            if (s != ATLAS_OK) return s;
        }
        h->program = true;
        *out = h.release();
        return ATLAS_OK;
    });
}
ATLAS_API AtlasStatus atlas_feat_set_overlays(AtlasFeat* h, AtlasOvl* ovl) {
    if (!h || !h->stage) return ATLAS_ERR_ARG;
    h->stage->setOverlays(ovl);
    return ATLAS_OK;
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
