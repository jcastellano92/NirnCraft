// A stage of the generator's batch (gen_engine.cpp): work recorded into the generator's own command buffer on its own
// device, between the terrain passes and the pack pass, rewriting the batch's blocks in place, GPU to GPU. The
// structures from the plans are one (overlays_engine.cpp: atlas_ovl_create_for_gen); the decoration can be another.
#pragma once

#include "vk_loader.hpp"

#include <cstdint>
#include <string>

struct AtlasGen;

namespace atlas {

class GenStage {
public:
    virtual ~GenStage() = default;
    // Called inside atlas_gen_run / atlas_gen_chunks with the generator's lock held and its previous batch complete:
    // record into cmd what rewrites the batch's blocks before the pack pass. blocks: n chunks of chunkWords words, one
    // 32-bit generator id per block, b = x + 16 z + 256 (y + 64), y -64 .. 575; chunkXZ: n (cx, cz) pairs. The
    // generator binds its own pipeline state again afterwards. False (err set) fails the batch.
    virtual bool record(VkCommandBuffer cmd, VkBuffer blocks, uint32_t chunkWords, uint32_t n, const int32_t* chunkXZ,
                        std::string& err) = 0;
};

// The generator's Vulkan device (false: a CPU generator, or none).
bool genDevice(AtlasGen* gen, VkPhysicalDevice& phys, VkDevice& dev);
// Attaches the generator's stage (nullptr: detaches it), between its batches (takes its lock).
void genSetStage(AtlasGen* gen, GenStage* stage);

}  // namespace atlas
