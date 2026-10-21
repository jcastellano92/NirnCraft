// Structures from the plans stamped on the GPU (atlas_ovl_*, shaders/atlas_overlays.slang; the Java side is
// com.nirncraft.atlas.AtlasOverlayTapes): the block table, the settlements' op tapes and the street plan stay resident
// on the device; a batch stamps chunk columns in place, one thread per column (the gather formulation): the pieces,
// their sweep (Sanity: removals, then fence / wall / pane shapes), the street painter. Vulkan only (GPU only for now;
// the CPU fallback is a later job).
//
// Two modes. Standalone (atlas_ovl_create): its own device, chunks of packed 16-bit ids uploaded and read back
// (atlas_ovl_run: the parity checks). Stages of the generator (atlas_ovl_create_for_gen: atlas_gen_add_stage at
// ATLAS_GEN_STAGE_PIECES, ATLAS_GEN_STAGE_SWEEPS and the street painter after the generator's paint): on the
// generator's device (atlas_gen_device_handles), recorded into its batch's command buffer between the terrain and the
// pack pass, on its blocks buffer (one 32-bit generator id a block), GPU to GPU, nothing submitted of our own; each
// chunk's tape record and street bucket are looked up here from the batch's chunk positions (the tapes and the street
// plan carry their chunk index), its neighbours from the batch itself.
#include "vk_loader.hpp"  // first: VK_NO_PROTOTYPES

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "atlas/atlas.h"
#include "atlas_ovl_pieces_spv.h"  // generated: slangc + spv_exact, one module per kernel
#include "atlas_ovl_streets_spv.h"
#include "atlas_ovl_sanity_spv.h"
#include "atlas_ovl_connect_spv.h"
#include "atlas_ovl_access_spv.h"
#include "atlas_ovl_roads_spv.h"
#include "atlas_ovl_mbnl_spv.h"

using namespace atlas;

namespace {

// keep in step with atlas_overlays.slang and AtlasOverlayTapes
constexpr uint32_t GROUP = 64, NB = 9, PIPES = 7, P_PIECES = 0, P_STREETS = 1, P_SANITY = 2, P_CONNECT = 3, P_ACCESS = 4, P_ROADS = 5,
                   P_MBNL = 6;
// kinds: 1 pieces, 2 streets, 4 sweeps, 8 roads (RoadPaint), 16 the heightmap snapshot the roads read
constexpr uint32_t K_PIECES = 1, K_STREETS = 2, K_SWEEPS = 4, K_ROADS = 8, K_MBNL = 16, K_UNDER = 32, K_SURFACE = 64, K_ALL = 127;
// the tapes' layers (AtlasOverlayTapes.LAYER_*): the settlements', the underground structures', the surface structures'
constexpr uint32_t LAYERS = 3;
// the kernels' chunk records: atlas_ovl_run's ATLAS_OVL_CHUNK_INTS, then the chunk's slot (its place in the ids and
// the snapshot: the batch index, or the generator's store slot)
constexpr uint32_t CHUNK_INTS = ATLAS_OVL_CHUNK_INTS + 1;
constexpr uint32_t BLOCKS_MAGIC = 0x4C424F41u, TAPES_MAGIC = 0x50544F41u, STREETS_MAGIC = 0x52534F41u, ROADS_MAGIC = 0x44524F41u;
constexpr uint32_t GEN_LEVELS = 640;  // the generator's columns: y -64 .. 575

double msSince(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
}

int64_t chunkKey(int32_t cx, int32_t cz) { return (int64_t(cx) << 32) ^ int64_t(uint32_t(cz)); }

struct OvlParams {
    int32_t count, levels;
    uint32_t packed, chunkWords;
    uint32_t chunkBase = 0;  // piecesMain: the layer's chunk records (0, n, 2 n records in)
};

struct Buf {
    VkBuffer buf = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    void* map = nullptr;
    VkDeviceSize size = 0;
};

enum class Mem { Device, Readback, Upload };

// the street painter's stage: after the road paint (StreetPaint runs after RoadPaint in the top layer step); the road
// paint at ATLAS_GEN_STAGE_PAINT; the heightmap the road paint reads, as the server primes it, before the decoration
// (the decoration is one store stage at ATLAS_GEN_STAGE_DECORATION, every step: the dungeons' recorded writes, the
// underground structures' then the surface structures', go before it, after the heightmap snapshot)
constexpr uint32_t STAGE_STREETS = ATLAS_GEN_STAGE_PAINT + 50, STAGE_ROADS = ATLAS_GEN_STAGE_PAINT,
                   STAGE_MBNL = ATLAS_GEN_STAGE_DECORATION - 3, STAGE_UNDER = ATLAS_GEN_STAGE_DECORATION - 2,
                   STAGE_SURFACE = ATLAS_GEN_STAGE_DECORATION - 1;
// the stages: order, the passes, the store radius (the sweeps read their four neighbours as the pieces left them)
struct StageDef {
    uint32_t order, kinds, radius;
};
constexpr StageDef STAGES[7] = {{STAGE_MBNL, K_MBNL, 0},       {STAGE_UNDER, K_UNDER, 0},       {STAGE_SURFACE, K_SURFACE, 0},
                                {ATLAS_GEN_STAGE_PIECES, K_PIECES, 0}, {ATLAS_GEN_STAGE_SWEEPS, K_SWEEPS, 1},
                                {STAGE_ROADS, K_ROADS, 0},     {STAGE_STREETS, K_STREETS, 0}};

class VulkanEngine final {
public:
    ~VulkanEngine() {
        if (dev_) {
            if (!attached_ && !dead_) vkDeviceWaitIdle(dev_);
            for (Buf* b : {&blocks_, &tapes_, &streets_, &chunks_, &ids_, &snap_, &roads_, &mbnl_, &dummy_, &staging_}) release(*b);
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
            if (!attached_) vkDestroyDevice(dev_, nullptr);
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

    // On the generator's device (stages of its batch): pipelines and buffers of our own, its command buffers
    bool attach(VkPhysicalDevice phys, VkDevice dev, std::string& err) {
        attached_ = true;
        phys_ = phys;
        dev_ = dev;
        std::string reason;
        if (!usable(phys_, reason)) return fail(err, "the generator's device cannot run the exact overlays: " + reason);
        VkPhysicalDeviceProperties p;
        vkGetPhysicalDeviceProperties(phys_, &p);
        std::snprintf(name_, sizeof(name_), "vulkan (%s, the generator's)", p.deviceName);
        return build(err);
    }

    // which: 0 the block table, 1 the tapes, 2 the street plan
    bool set(uint32_t which, const uint32_t* words, uint32_t count, std::string& err) {
        std::lock_guard<std::mutex> lock(mu_);
        if (dead_) return fail(err, "the overlays' device was lost earlier");
        if (attached_) {
            // kept here until the generator's next batch (its lock held, its device idle for us) puts it up
            if (which > 3) return fail(err, "which");
            pending_[which].assign(words, words + count);
            dirty_[which] = true;
            if (which == 1) indexTapes();
            if (which == 2) indexStreets();
            has_[which] = true;
            return true;
        }
        if (which == 3) return fail(err, "the road painter needs the generator's map: a stage only");
        Buf& b = which == 0 ? blocks_ : which == 1 ? tapes_ : streets_;
        if (!ensure(b, size_t(count) * 4, Mem::Device, err)) return false;
        writeSet(ids_.buf);
        if (!upload(b, words, size_t(count) * 4, err)) return false;
        has_[which] = true;
        return true;
    }

    void setKinds(uint32_t kinds) {
        std::lock_guard<std::mutex> lock(mu_);
        kinds_ = kinds;
    }

    uint64_t stamped() const { return stamped_; }


    bool run(uint32_t count, const int32_t* chunks, uint32_t levels, uint32_t kinds, uint32_t* ids, AtlasOvlTimes& t, std::string& err) {
        std::lock_guard<std::mutex> lock(mu_);
        if (dead_) return fail(err, "the overlays' device was lost earlier");
        if (attached_) return fail(err, "a stage of the generator: it runs in the generator's batch");
        auto u0 = std::chrono::steady_clock::now();
        size_t idBytes = size_t(count) * levels * 128u * 4u;
        bool grown = false;
        size_t chunkBytes = size_t(count) * CHUNK_INTS * 4;
        std::vector<int32_t> desc(size_t(count) * CHUNK_INTS);
        for (uint32_t i = 0; i < count; i++) {
            std::memcpy(desc.data() + size_t(i) * CHUNK_INTS, chunks + size_t(i) * ATLAS_OVL_CHUNK_INTS, ATLAS_OVL_CHUNK_INTS * 4);
            desc[size_t(i) * CHUNK_INTS + 8] = int32_t(i);
        }
        if (!(chunks_.buf && chunks_.size >= chunkBytes)) grown = true;
        if (!(ids_.buf && ids_.size >= idBytes)) grown = true;
        if ((kinds & 4u) && !(snap_.buf && snap_.size >= idBytes)) grown = true;
        if (!ensure(chunks_, chunkBytes, Mem::Device, err) || !ensure(ids_, idBytes, Mem::Device, err)) return false;
        if ((kinds & 4u) && !ensure(snap_, idBytes, Mem::Device, err)) return false;
        if (grown || rebind_) writeSet(ids_.buf);
        if (!upload(chunks_, desc.data(), chunkBytes, err) || !upload(ids_, ids, idBytes, err)) return false;
        t.uploadMs += msSince(u0);
        OvlParams p{int32_t(count), int32_t(levels), 1u, levels * 128u, 0u};
        auto k0 = std::chrono::steady_clock::now();
        begin();
        if (timestamps_) vkCmdResetQueryPool(cmd_, queries_, 0, 5);
        recordPasses(cmd_, p, kinds, ids_.buf, idBytes, timestamps_);
        barrier(cmd_, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT);
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

    // A stage of the generator's batch (kinds: which of our passes this stage records): inside its command buffer,
    // between the terrain and the pack pass, with its lock held and its previous batch complete. Inert once the
    // generator's store has called us (its store stages do the same work there; a batch out of the store runs the
    // batch stages again before its pack)
    bool record(VkCommandBuffer cmd, VkBuffer blocks, uint32_t chunkWords, uint32_t n, const int32_t* chunkXZ, uint32_t kinds,
                VkBuffer map, std::string& err) {
        std::lock_guard<std::mutex> lock(mu_);
        if (storeSeen_) return true;
        std::unordered_map<int64_t, int32_t> slot;
        slot.reserve(n * 2);
        for (uint32_t i = 0; i < n; i++) slot[chunkKey(chunkXZ[i * 2], chunkXZ[i * 2 + 1])] = int32_t(i);
        auto find = [&](int32_t cx, int32_t cz) {
            auto it = slot.find(chunkKey(cx, cz));
            return it == slot.end() ? -1 : it->second;
        };
        auto fill = [&](uint32_t i, int32_t* d) {
            int32_t cx = chunkXZ[i * 2], cz = chunkXZ[i * 2 + 1];
            d[0] = cx;
            d[1] = cz;
            d[4] = find(cx, cz - 1);
            d[5] = find(cx + 1, cz);
            d[6] = find(cx, cz + 1);
            d[7] = find(cx - 1, cz);
            d[8] = int32_t(i);
        };
        return core(cmd, blocks, chunkWords, n, fill, kinds, map, false, n, err);
    }

    // A store stage (atlas_gen_add_store_stage): the store's chunks given, each in its own slot of the store's blocks,
    // its neighbours (within the stage's radius) in theirs. The sweeps read the neighbours as the pieces left them
    // from a snapshot per slot taken at the pieces' stage (whatever stage a neighbour has passed since), the road paint
    // the heightmap per slot taken before the decoration: the same blocks whatever the batching.
    bool recordStore(const AtlasGenStoreBatch* sb, uint32_t kinds, std::string& err) {
        std::lock_guard<std::mutex> lock(mu_);
        storeSeen_ = true;
        if (sb->slots != storeSlots_) {
            // the store's size: our snapshot and heightmap per slot, and the slots' tape records (the features' masks)
            if (!ensure(snapStore_, size_t(sb->slots) * ATLAS_GEN_STORE_BLOCK_WORDS * 4, Mem::Device, err) ||
                !ensure(mbnlStore_, size_t(sb->slots) * 256 * 4, Mem::Device, err) ||
                !ensure(slotRec_, size_t(sb->slots) * 4, Mem::Upload, err))
                return false;
            storeSlots_ = sb->slots;
            slotChunk_.assign(storeSlots_, INT64_MIN);
            std::memset(slotRec_.map, 0xFF, size_t(storeSlots_) * 4);
        }
        uint32_t n = sb->count, r = sb->radius, w = 2 * r + 1;
        for (uint32_t i = 0; i < n; i++) {
            uint32_t sl = uint32_t(sb->chunks[i * 3 + 2]);
            if (sl >= storeSlots_) return fail(err, "store slot out of range");
            int64_t k = chunkKey(sb->chunks[i * 3], sb->chunks[i * 3 + 1]);
            if (slotChunk_[sl] != k) {
                slotChunk_[sl] = k;
                static_cast<int32_t*>(slotRec_.map)[sl] = has_[1] ? lookup(tapeIndex_[0], k) : -1;
            }
        }
        auto nb = [&](uint32_t i, int32_t dx, int32_t dz) {
            if (r == 0 || !sb->neighborsHost) return -1;
            return sb->neighborsHost[size_t(i) * w * w + size_t(dz + int32_t(r)) * w + size_t(dx + int32_t(r))];
        };
        auto fill = [&](uint32_t i, int32_t* d) {
            d[0] = sb->chunks[i * 3];
            d[1] = sb->chunks[i * 3 + 1];
            d[4] = nb(i, 0, -1);
            d[5] = nb(i, 1, 0);
            d[6] = nb(i, 0, 1);
            d[7] = nb(i, -1, 0);
            d[8] = sb->chunks[i * 3 + 2];
        };
        VkCommandBuffer cmd = static_cast<VkCommandBuffer>(sb->vkCommandBuffer);
        return core(cmd, reinterpret_cast<VkBuffer>(sb->blocks), ATLAS_GEN_STORE_BLOCK_WORDS, n, fill, kinds,
                    reinterpret_cast<VkBuffer>(sb->map), true, storeSlots_, err);
    }

    // The features' view of the settlements' pieces (atlas_ovl_store_boxes): the tapes and each store slot's record
    bool storeBoxes(AtlasOvlStoreBoxes* out) {
        std::lock_guard<std::mutex> lock(mu_);
        out->tapes = uint64_t(tapes_.buf);
        out->tapeWords = uint32_t(tapes_.size / 4);
        out->slotRecords = uint64_t(slotRec_.buf);
        out->slots = storeSlots_;
        return storeSlots_ != 0 && has_[1];
    }

private:
    static int32_t lookup(const std::unordered_map<int64_t, int32_t>& m, int64_t k) {
        auto it = m.find(k);
        return it == m.end() ? -1 : it->second;
    }

    // what changed since the last batch goes up now (the generator waited for its last batch: nothing reads ours);
    // new tapes renumber their records: the store slots' records again
    bool uploadDirty(std::string& err) {
        Buf* bufs[4] = {&blocks_, &tapes_, &streets_, &roads_};
        for (uint32_t w = 0; w < 4; w++) {
            if (!dirty_[w]) continue;
            size_t bytes = pending_[w].size() * 4;
            if (!ensure(*bufs[w], bytes, Mem::Upload, err)) return false;
            std::memcpy(bufs[w]->map, pending_[w].data(), bytes);
            dirty_[w] = false;
            if (w == 1 && storeSlots_)
                for (uint32_t sl = 0; sl < storeSlots_; sl++)
                    static_cast<int32_t*>(slotRec_.map)[sl] = slotChunk_[sl] == INT64_MIN ? -1 : lookup(tapeIndex_[0], slotChunk_[sl]);
        }
        return true;
    }

    // a stage's passes over n chunks of ids (fill: a chunk's cx, cz, neighbours and slot; the records added here)
    template <class Fill>
    bool core(VkCommandBuffer cmd, VkBuffer blocks, uint32_t chunkWords, uint32_t n, Fill fill, uint32_t kinds, VkBuffer map, bool store,
              uint32_t slots, std::string& err) {
        if (dead_ || !has_[0]) return true;  // nothing to stamp yet
        if ((kinds & (K_ROADS | K_MBNL)) && !has_[3]) return true;
        if (!(kinds & (K_ROADS | K_MBNL)) && !has_[1] && !has_[2]) return true;
        if ((kinds & (K_UNDER | K_SURFACE)) && !has_[1]) return true;
        if (!uploadDirty(err)) return false;
        // the chunks' records, a block per layer (the settlements' first: the passes read it; the dungeons' layers
        // n and 2 n records in, read by their stages' piecesMain through chunkBase)
        std::vector<int32_t> desc(size_t(n) * CHUNK_INTS * LAYERS);
        uint32_t any = 0, anyLayer[LAYERS] = {0, 0, 0};
        for (uint32_t i = 0; i < n; i++) {
            int32_t* d = desc.data() + size_t(i) * CHUNK_INTS;
            fill(i, d);
            int64_t k = chunkKey(d[0], d[1]);
            d[2] = has_[1] ? lookup(tapeIndex_[0], k) : -1;
            d[3] = has_[2] ? lookup(bucketIndex_, k) : -1;
            if (d[2] >= 0 || d[3] >= 0) any++;
            for (uint32_t l = 1; l < LAYERS; l++) {
                int32_t* dl = desc.data() + (size_t(l) * n + i) * CHUNK_INTS;
                std::memcpy(dl, d, CHUNK_INTS * sizeof(int32_t));
                dl[2] = has_[1] ? lookup(tapeIndex_[l], k) : -1;
                if (dl[2] >= 0) anyLayer[l]++;
            }
        }
        uint32_t layer = (kinds & K_UNDER) ? 1u : (kinds & K_SURFACE) ? 2u : 0u;
        if (layer && !anyLayer[layer]) return true;  // no dungeon here
        bool roadsToo = (kinds & (K_ROADS | K_MBNL)) != 0;
        // (the store's pieces stage snapshots every chunk, stamped or not: its neighbours' sweeps read them)
        bool snapAll = store && (kinds & K_PIECES);
        if (!layer && !any && !roadsToo && !snapAll) return true;  // no settlement here: nothing recorded
        size_t chunkBytes = desc.size() * 4, idBytes = size_t(n) * chunkWords * 4;
        if (!ensure(chunks_, chunkBytes, Mem::Upload, err)) return false;
        if (!store && (!ensure(snap_, idBytes, Mem::Device, err) || !ensure(mbnl_, size_t(n) * 256 * 4, Mem::Device, err))) return false;
        std::memcpy(chunks_.map, desc.data(), chunkBytes);
        VkBuffer snap = store ? snapStore_.buf : snap_.buf, mbnl = store ? mbnlStore_.buf : mbnl_.buf;
        if (rebind_ || blocks != boundIds_ || map != boundMap_ || snap != boundSnap_ || mbnl != boundMbnl_) writeSet(blocks, map, snap, mbnl);
        OvlParams p{int32_t(n), int32_t(GEN_LEVELS), 0u, chunkWords, 0u};
        (void)slots;
        if (layer) {
            // a dungeon layer: its recorded writes, column by column (piecesMain over the layer's records)
            if (!(kinds_ & kinds)) return true;
            p.chunkBase = layer * n * CHUNK_INTS;
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout_, 0, 1, &set_, 0, nullptr);
            vkCmdPushConstants(cmd, layout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(OvlParams), &p);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipes_[P_PIECES]);
            vkCmdDispatch(cmd, 256 / GROUP, n, 1);
            stamped_ += anyLayer[layer];
            return true;
        }
        if (roadsToo) {
            if ((kinds & kinds_ & K_MBNL) && pipes_[P_MBNL]) {
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout_, 0, 1, &set_, 0, nullptr);
                vkCmdPushConstants(cmd, layout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(OvlParams), &p);
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipes_[P_MBNL]);
                vkCmdDispatch(cmd, 256 / GROUP, n, 1);
            }
            if ((kinds & kinds_ & K_ROADS) && pipes_[P_ROADS]) {
                vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout_, 0, 1, &set_, 0, nullptr);
                vkCmdPushConstants(cmd, layout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(OvlParams), &p);
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipes_[P_ROADS]);
                vkCmdDispatch(cmd, 1, n, 1);  // a workgroup a chunk
            }
            return true;
        }
        recordPasses(cmd, p, kinds & kinds_, blocks, idBytes, false, store);
        if (snapAll) {
            // the pieces' result per slot, for the sweeps of this chunk and of its neighbours
            barrier(cmd, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT);
            std::vector<VkBufferCopy> regions(n);
            VkDeviceSize bytes = VkDeviceSize(chunkWords) * 4;
            for (uint32_t i = 0; i < n; i++) {
                VkDeviceSize at = VkDeviceSize(uint32_t(desc[size_t(i) * CHUNK_INTS + 8])) * bytes;
                regions[i] = {at, at, bytes};
            }
            vkCmdCopyBuffer(cmd, blocks, snapStore_.buf, n, regions.data());
            barrier(cmd, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        }
        if (kinds & 1u) stamped_ += any;
        return true;
    }

    // the passes into cmd, in the server's order: pieces, their sweep, the street painter
    void recordPasses(VkCommandBuffer cmd, const OvlParams& p, uint32_t kinds, VkBuffer ids, VkDeviceSize idBytes, bool ts, bool store = false) {
        uint32_t count = uint32_t(p.count);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, layout_, 0, 1, &set_, 0, nullptr);
        vkCmdPushConstants(cmd, layout_, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(OvlParams), &p);
        if (ts) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queries_, 0);
        if ((kinds & 1u) && has_[0] && has_[1]) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipes_[P_PIECES]);
            vkCmdDispatch(cmd, 256 / GROUP, count, 1);
        }
        barrier(cmd, VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        if (ts) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queries_, 1);
        // the pieces' sweep (Sanity): its neighbour reads from a copy of what the pieces left, then the shapes
        if ((kinds & 4u) && has_[0] && has_[1]) {
            if (!store) {
                barrier(cmd, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT);
                VkBufferCopy c{0, 0, idBytes};
                vkCmdCopyBuffer(cmd, ids, snap_.buf, 1, &c);
                barrier(cmd, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            }
            // as postProcess: Sanity, the entrances' approaches and the indoor stairs (Accessibility), Sanity again
            const uint32_t seq[3] = {P_SANITY, P_ACCESS, P_SANITY};
            for (uint32_t pipe : seq) {
                vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipes_[pipe]);
                vkCmdDispatch(cmd, pipe == P_ACCESS ? 1 : 256 / GROUP, count, 1);
                barrier(cmd, VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            }
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipes_[P_CONNECT]);
            vkCmdDispatch(cmd, 256 / GROUP, count, 1);
            barrier(cmd, VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        }
        if (ts) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queries_, 3);
        if ((kinds & 2u) && has_[0] && has_[2]) {
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipes_[P_STREETS]);
            vkCmdDispatch(cmd, 256 / GROUP, count, 1);
        }
        barrier(cmd, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        if (ts) vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queries_, 2);
    }

    // the tapes' chunk index after the records (AtlasOverlayTapes.tapeWords): (cx, cz, record, layer) per entry
    // (word 3: the stride, 4; an older 3-word index is the settlements' layer alone)
    void indexTapes() {
        for (auto& m : tapeIndex_) m.clear();
        const std::vector<uint32_t>& w = pending_[1];
        if (w.size() < 16) return;
        uint32_t n = w[1], at = w[2], stride = w[3] == 4u ? 4u : 3u;
        for (uint32_t i = 0; i < n && at + i * stride + stride - 1 < w.size(); i++) {
            uint32_t layer = stride == 4u ? w[at + i * stride + 3] : 0u;
            if (layer >= LAYERS) continue;
            tapeIndex_[layer][chunkKey(int32_t(w[at + i * stride]), int32_t(w[at + i * stride + 1]))] = int32_t(w[at + i * stride + 2]);
        }
    }

    // the street plan's buckets by chunk (AtlasOverlayTapes.streetWords: word 13)
    void indexStreets() {
        bucketIndex_.clear();
        const std::vector<uint32_t>& w = pending_[2];
        if (w.size() < 16) return;
        uint32_t n = w[4], at = w[13];
        for (uint32_t k = 0; k < n && at + k * 2 + 1 < w.size(); k++)
            bucketIndex_[chunkKey(int32_t(w[at + k * 2]), int32_t(w[at + k * 2 + 1]))] = int32_t(k);
    }

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
        const uint32_t* code[PIPES] = {atlas_ovl_pieces_spv, atlas_ovl_streets_spv, atlas_ovl_sanity_spv, atlas_ovl_connect_spv,
                                       atlas_ovl_access_spv, atlas_ovl_roads_spv, atlas_ovl_mbnl_spv};
        const size_t size[PIPES] = {atlas_ovl_pieces_spv_size, atlas_ovl_streets_spv_size, atlas_ovl_sanity_spv_size,
                                    atlas_ovl_connect_spv_size, atlas_ovl_access_spv_size, atlas_ovl_roads_spv_size, atlas_ovl_mbnl_spv_size};
        static const char* names[PIPES] = {"pieces", "streets", "sanity", "connect", "access", "roads", "mbnl"};
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
        Mem small = attached_ ? Mem::Upload : Mem::Device;
        bool ok = ensure(blocks_, 64, small, err) && ensure(tapes_, 64, small, err) && ensure(streets_, 64, small, err) &&
                  ensure(chunks_, 64, small, err) && ensure(snap_, 64, Mem::Device, err) && ensure(ids_, 64, Mem::Device, err) &&
                  ensure(roads_, 64, small, err) && ensure(mbnl_, 64, Mem::Device, err) && ensure(dummy_, 64, Mem::Device, err);
        if (!ok) return false;
        if (attached_) {
            writeSet(ids_.buf);  // the generator's blocks take binding 4 at its batches
            return true;
        }
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
        if (!ensure(staging_, 32u << 20, Mem::Readback, err)) return false;
        writeSet(ids_.buf);
        return true;
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
    bool ensure(Buf& b, size_t size, Mem kind, std::string& err) {
        size = std::max<size_t>(size, 64);
        if (b.buf && b.size >= size) return true;
        if (b.buf && !attached_) vkDeviceWaitIdle(dev_);  // (attached: the generator's last batch is complete)
        release(b);
        rebind_ = true;  // a new buffer: the descriptor set names it at the next writeSet (a stage that returns early leaves it to the next)
        VkBufferCreateInfo bc{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bc.size = size;
        bc.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        if (vkCreateBuffer(dev_, &bc, nullptr, &b.buf) != VK_SUCCESS) return fail(err, "vkCreateBuffer");
        VkMemoryRequirements req;
        vkGetBufferMemoryRequirements(dev_, b.buf, &req);
        VkMemoryPropertyFlags hv = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        uint32_t type = UINT32_MAX;
        if (kind == Mem::Readback) {
            type = memoryType(req.memoryTypeBits, hv | VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
            if (type == UINT32_MAX) type = memoryType(req.memoryTypeBits, hv);
        } else if (kind == Mem::Upload) {
            // host-written, device-read: device memory the host can map when there is some, else host memory
            type = memoryType(req.memoryTypeBits, hv | VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
            if (type == UINT32_MAX) type = memoryType(req.memoryTypeBits, hv);
        } else {
            type = memoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        }
        if (type == UINT32_MAX) return fail(err, "no suitable memory type");
        VkMemoryAllocateInfo ma{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ma.allocationSize = req.size;
        ma.memoryTypeIndex = type;
        if (vkAllocateMemory(dev_, &ma, nullptr, &b.mem) != VK_SUCCESS) {
            vkDestroyBuffer(dev_, b.buf, nullptr);
            b.buf = VK_NULL_HANDLE;
            if (kind == Mem::Upload) return ensure(b, size, Mem::Readback, err);  // the host-visible pool ran out: plain host memory
            return fail(err, "vkAllocateMemory (" + std::to_string(size >> 20) + " MiB)");
        }
        vkBindBufferMemory(dev_, b.buf, b.mem, 0);
        if (kind != Mem::Device && vkMapMemory(dev_, b.mem, 0, VK_WHOLE_SIZE, 0, &b.map) != VK_SUCCESS) return fail(err, "vkMapMemory");
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
            barrier(cmd_, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
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
            barrier(cmd_, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_HOST_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT);
            if (!submitWait(err)) return false;
            std::memcpy(static_cast<char*>(data) + done, staging_.map, n);
            done += n;
        }
        return true;
    }
    void writeSet(VkBuffer ids, VkBuffer map = VK_NULL_HANDLE, VkBuffer snap = VK_NULL_HANDLE, VkBuffer mbnl = VK_NULL_HANDLE) {
        if (!snap) snap = snap_.buf;
        if (!mbnl) mbnl = mbnl_.buf;
        VkBuffer bs[NB] = {blocks_.buf, tapes_.buf, streets_.buf, chunks_.buf, ids, snap, roads_.buf, map ? map : dummy_.buf, mbnl};
        VkDescriptorBufferInfo bi[NB];
        VkWriteDescriptorSet w[NB];
        for (uint32_t i = 0; i < NB; i++) {
            bi[i] = {bs[i], 0, VK_WHOLE_SIZE};
            w[i] = {VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
            w[i].dstSet = set_;
            w[i].dstBinding = i;
            w[i].descriptorCount = 1;
            w[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            w[i].pBufferInfo = &bi[i];
        }
        vkUpdateDescriptorSets(dev_, NB, w, 0, nullptr);
        boundIds_ = ids;
        boundSnap_ = snap;
        boundMbnl_ = mbnl;
        rebind_ = false;
        boundMap_ = map;
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
    static void barrier(VkCommandBuffer cmd, VkAccessFlags src, VkAccessFlags dst, VkPipelineStageFlags from, VkPipelineStageFlags to) {
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = src;
        mb.dstAccessMask = dst;
        vkCmdPipelineBarrier(cmd, from, to, 0, 1, &mb, 0, nullptr, 0, nullptr);
    }

    std::mutex mu_;
    bool dead_ = false, timestamps_ = false, attached_ = false, rebind_ = true;
    bool has_[4] = {false, false, false, false}, dirty_[4] = {false, false, false, false};
    std::vector<uint32_t> pending_[4];
    std::unordered_map<int64_t, int32_t> tapeIndex_[LAYERS], bucketIndex_;
    uint32_t kinds_ = K_ALL;
    uint64_t stamped_ = 0;
    VkBuffer boundIds_ = VK_NULL_HANDLE, boundMap_ = VK_NULL_HANDLE, boundSnap_ = VK_NULL_HANDLE, boundMbnl_ = VK_NULL_HANDLE;
    // the generator's store (recordStore): its slots, each slot's chunk (INT64_MIN none), and once it has called us
    // the batch stages stand down
    uint32_t storeSlots_ = 0;
    std::vector<int64_t> slotChunk_;
    bool storeSeen_ = false;
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
    Buf blocks_, tapes_, streets_, chunks_, ids_, snap_, roads_, mbnl_, dummy_, staging_, snapStore_, mbnlStore_, slotRec_;
};

}  // namespace

struct AtlasOvl {
    std::unique_ptr<VulkanEngine> engine;
    std::string error;
    AtlasGen* gen = nullptr;  // atlas_ovl_create_for_gen's: its stages are removed on destroy
    uint32_t stageIds[14] = {};  // the batch stages, then the store stages (0: none)
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

namespace {
uint32_t kindsOf(uint32_t order) {
    for (const StageDef& d : STAGES)
        if (d.order == order) return d.kinds;
    return 0;
}

// the generator's store stage callback (atlas_gen_add_store_stage)
int32_t storeStageFn(void* user, const AtlasGenStoreBatch* b) {
    auto* h = static_cast<AtlasOvl*>(user);
    if (!h || !b || b->version < 1) return -1;
    try {
        std::string err;
        if (!h->engine->recordStore(b, kindsOf(b->order), err)) {
            h->error = err;
            return -1;
        }
        return 0;
    } catch (const std::exception& e) {
        h->error = e.what();
        return -1;
    }
}

// the generator's stage callback (atlas_gen_add_stage): the order says which of our passes
int32_t stageFn(void* user, const AtlasGenStageBatch* b) {
    auto* h = static_cast<AtlasOvl*>(user);
    if (!h || !b || b->version < 1) return -1;
    uint32_t kinds = kindsOf(b->order);
    try {
        std::string err;
        if (!h->engine->record(static_cast<VkCommandBuffer>(b->vkCommandBuffer), reinterpret_cast<VkBuffer>(b->blocks),
                               uint32_t(b->blocksWordsPerChunk), b->chunkCount, b->chunks, kinds, reinterpret_cast<VkBuffer>(b->map), err)) {
            h->error = err;
            return -1;
        }
        return 0;
    } catch (const std::exception& e) {
        h->error = e.what();
        return -1;
    }
}

}  // namespace

ATLAS_API AtlasStatus atlas_ovl_create_for_gen(AtlasGen* gen, AtlasOvl** out) {
    if (!out || !gen) return ATLAS_ERR_ARG;
    *out = nullptr;
    return guarded(nullptr, [&] {
        AtlasGenDevice d{};
        if (atlas_gen_device_handles(gen, &d) != ATLAS_OK || !d.vkDevice) return ATLAS_ERR_UNSUPPORTED;  // a CPU generator
        auto h = std::make_unique<AtlasOvl>();
        auto vk = std::make_unique<VulkanEngine>();
        std::string err;
        if (!vk->attach(static_cast<VkPhysicalDevice>(d.vkPhysicalDevice), static_cast<VkDevice>(d.vkDevice), err)) {
            std::fprintf(stderr, "[atlas] overlays: %s\n", err.c_str());
            return ATLAS_ERR_UNSUPPORTED;
        }
        h->engine = std::move(vk);
        h->gen = gen;
        // as batch stages (a generator without its store) and as store stages (atlas_gen_run_store), each with its radius
        for (uint32_t k = 0; k < 14; k++) {
            const StageDef& d = STAGES[k % 7];
            AtlasStatus s = k < 7 ? atlas_gen_add_stage(gen, d.order, stageFn, h.get(), &h->stageIds[k])
                                  : atlas_gen_add_store_stage(gen, d.order, d.radius, storeStageFn, h.get(), &h->stageIds[k]);
            if (s != ATLAS_OK) {
                for (uint32_t j = 0; j < k; j++) atlas_gen_remove_stage(gen, h->stageIds[j]);
                return s;
            }
        }
        *out = h.release();
        return ATLAS_OK;
    });
}

ATLAS_API void atlas_ovl_destroy(AtlasOvl* h) {
    if (!h) return;
    if (h->gen) {
        for (uint32_t id : h->stageIds) atlas_gen_remove_stage(h->gen, id);  // between batches: nothing of ours in flight after
    }
    delete h;
}
ATLAS_API const char* atlas_ovl_last_error(AtlasOvl* h) { return h ? h->error.c_str() : "null handle"; }
ATLAS_API const char* atlas_ovl_device(AtlasOvl* h) { return h ? h->engine->device() : "null handle"; }

ATLAS_API AtlasStatus atlas_ovl_set(AtlasOvl* h, uint32_t which, const uint32_t* words, uint32_t count) {
    if (!h || !words || count < 16 || which > 3) return ATLAS_ERR_ARG;
    static const uint32_t magic[4] = {BLOCKS_MAGIC, TAPES_MAGIC, STREETS_MAGIC, ROADS_MAGIC};
    if (words[0] != magic[which]) return ATLAS_ERR_ARG;
    if (which == 0 && (words[8] != 7 || count < 16 + words[1] * words[8])) return ATLAS_ERR_ARG;
    if (which == 1 && (words[2] < 16 || uint64_t(words[2]) + uint64_t(words[1]) * (words[3] == 4u ? 4u : 3u) > count)) return ATLAS_ERR_ARG;
    return guarded(h, [&] {
        if (!h->engine->set(which, words, count, h->error)) return ATLAS_ERR_GPU;
        if (which == 0) h->blockCount = words[1];
        if (which == 1) h->tapeWords = count;
        if (which == 2) h->streetBuckets = words[4];
        return ATLAS_OK;
    });
}

ATLAS_API AtlasStatus atlas_ovl_set_kinds(AtlasOvl* h, uint32_t kinds) {
    if (!h) return ATLAS_ERR_ARG;
    h->engine->setKinds(kinds & K_ALL);
    return ATLAS_OK;
}

ATLAS_API uint64_t atlas_ovl_stamped(AtlasOvl* h) { return h ? h->engine->stamped() : 0; }

ATLAS_API AtlasStatus atlas_ovl_run(AtlasOvl* h, uint32_t count, const int32_t* chunks, uint32_t levels, uint32_t kinds,
                                    uint32_t* ids, AtlasOvlTimes* times) {
    if (!h || !chunks || !ids || count == 0 || levels == 0 || levels > 1024) return ATLAS_ERR_ARG;
    if (h->gen) return ATLAS_ERR_STATE;  // a stage: it runs in the generator's batches
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

ATLAS_API AtlasStatus atlas_ovl_store_boxes(AtlasOvl* h, AtlasOvlStoreBoxes* out) {
    if (!h || !out) return ATLAS_ERR_ARG;
    *out = {};
    out->version = 1;
    if (!h->gen) return ATLAS_ERR_STATE;
    return h->engine->storeBoxes(out) ? ATLAS_OK : ATLAS_ERR_STATE;
}
