// Micro-voxel bricks (docs/ATLAS-WORLD-STORE.md section 4, the owner's method for non-cube blocks): a block state whose
// model is not a full cube (a door, a rail, redstone, a lectern, a torch, a crop, NirnCraft's 245 custom models) is a
// 16 x 16 x 16 brick of micro-voxels (1/16 block) voxelised once from its model quads and their textures, deduplicated
// per distinct content, referenced from the material id the grid's level-0 cell holds and traced inside the cell
// (atlas_bricks.slang). Voxels only: no CPU path traces them.
//
// The packed buffer (far binding 40, brickData; words):
//   [0]            M: the table's length (materials 0 .. M - 1)
//   [1 + m]        material m's brick: its first word, 0 none; with bit 31 an animated set's first word: frames | game
//                  ticks a frame << 16, then each frame's brick (atlas_bricks.slang brickOf picks it by the frame's time)
//   [1 + 2M + m]   material m's random offset (atlas.h atlas_set_material_offsets; 0 none)
//   [1 + M + m]    material m's light (gridLightMain: the grid's emitters as lights): its colour (sRGB, brightest channel
//                  255) | its light level (0..15) << 24; 0: it emits nothing
//   a brick at b:  [b], [b + 1]  64 bits: which of its 4 x 4 x 4 groups of 4 x 4 x 4 micro-voxels hold any (bit
//                                gx + 4 (gz + 4 gy))
//                  [b + 2]       the groups that do (ng, low 16 bits) | palette entries << 16
//                  [b + 3]       the palette's first word, relative to b
//                  [b + 4 + 3r]  the r-th such group (in bit order): its micro-voxels (two words, bit mx + 4 (mz + 4 my)
//                                inside the group), the index of its first micro-voxel among the brick's filled ones
//                  then          per filled micro-voxel (in group order, then bit order) its palette index, one byte, four
//                                to a word (low byte first)
//                  palette       ARGB: rgb the micro-voxel's colour (sRGB, tints applied), a its emission (0..255)
//   sheets at c (the table's word with bit 30: a model with quads off the axes, a cross plant, a coral fan, a fire's
//   leaning sheets, a turned custom plant: traced as those planes themselves, not voxels, which drew them as slatted
//   columns): [c] planes (up to 4) | 1 << 8 when an axis-aligned rest is voxels too, [c + 1] that rest's voxel record
//   (from c), then per plane 13 floats (normal, distance, its rectangle's corner O and sides U, V) and 16 x 16 ARGB
//   cells over the rectangle (alpha 255 shown, 0 not)
#pragma once
#include <array>
#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <vector>

#include "atlas/atlas.h"

namespace atlas {

// One brick, unpacked: per micro-voxel x + 16 (z + 16 y) its ARGB (a: emission 0..255; 0 = empty, see filled).
struct BrickVoxels {
    std::array<uint32_t, 4096> argb{};
    std::array<uint8_t, 4096> filled{};
    uint32_t count = 0;
    uint32_t lightRgb = 0;  // the emission-weighted mean colour of its glowing micro-voxels (RGB, brightest 255; 0 none)
    float coverage = 0;     // the share of its volume it fills: its micro-voxels and what they enclose (a shell's inside)
};

// Voxelises one material's quads (those of quads[] whose material == m) into v; emission 0..1 the material's (its
// bright texels glow: 2 x smoothstep(0.35, 0.8, luminance), the far field's emitter rule). False when nothing landed.
// frame: which frame of each animated sprite (its own count wrapping it).
bool voxeliseBrick(const AtlasBrickQuad* quads, uint32_t quadCount, uint32_t m, const AtlasBrickSprite* sprites,
                   uint32_t spriteCount, const uint32_t* texels, uint32_t texelCount, float emission, BrickVoxels& v,
                   uint32_t frame = 0);

// The packed record of a brick (the layout above, offsets relative to its first word).
std::vector<uint32_t> encodeBrick(const BrickVoxels& v);

// A sheets record (the layout above) when material m has quads off the axes on at most four planes, else empty.
// frame: as voxeliseBrick's.
std::vector<uint32_t> crossRecord(const AtlasBrickQuad* quads, uint32_t quadCount, uint32_t m, const AtlasBrickSprite* sprites,
                                  uint32_t spriteCount, const uint32_t* texels, uint32_t texelCount, uint32_t frame = 0);

// The context's bricks: per material its (deduplicated) record, packed for the kernels once per revision.
class BrickStore {
public:
    // material m's brick (record nullptr: none)
    void set(uint32_t m, std::shared_ptr<const std::vector<uint32_t>> record, uint32_t lightRgb = 0);
    // material m as a cross (crossRecord; nullptr: none): what its table word points at instead of its voxels
    void setCross(uint32_t m, std::shared_ptr<const std::vector<uint32_t>> record);
    void setOffset(uint32_t m, uint32_t word);
    // animated sheets: a record a frame, `ticks` game ticks each (a fire's, a campfire's flames)
    void setCrossFrames(uint32_t m, std::vector<std::shared_ptr<const std::vector<uint32_t>>> frames, uint32_t ticks);
    // an animated one: a record a frame, `ticks` game ticks each
    void setFrames(uint32_t m, std::vector<std::shared_ptr<const std::vector<uint32_t>>> frames, uint32_t ticks, uint32_t lightRgb);
    // material m's brick's coverage for the grid's coarse vote, 0..63 (BrickVoxels.coverage x 63); -1: no brick
    void setCoverage(uint32_t m, int level);
    int coverage(uint32_t m) const;
    // material m's light colour from its brick (RGB, 0: none: the caller takes its palette colour)
    uint32_t lightRgb(uint32_t m) const;
    // a record equal to `rec` already held, else rec itself (deduplication)
    std::shared_ptr<const std::vector<uint32_t>> intern(std::vector<uint32_t> rec);
    uint64_t revision() const;
    // the buffer (layout above, with the materials' light words `lights`) when the revision moved since `have` or
    // `force` (returns true and fills out)
    bool pack(uint64_t have, bool force, const std::vector<uint32_t>& lights, std::vector<uint32_t>& out, uint64_t& revision) const;
    size_t bricks() const;

private:
    mutable std::mutex mu_;
    std::vector<std::shared_ptr<const std::vector<uint32_t>>> byMat_;
    std::vector<uint32_t> lightRgb_;
    std::vector<std::vector<std::shared_ptr<const std::vector<uint32_t>>>> frames_;  // (animated: every frame's)
    std::vector<uint32_t> ticks_;
    std::vector<int8_t> coverage_;
    std::vector<std::shared_ptr<const std::vector<uint32_t>>> cross_;
    std::vector<uint32_t> offset_;
    std::vector<std::vector<std::shared_ptr<const std::vector<uint32_t>>>> crossFrames_;
    std::vector<uint32_t> crossTicks_;
    std::unordered_map<uint64_t, std::vector<std::shared_ptr<const std::vector<uint32_t>>>> unique_;
    uint64_t revision_ = 0;
};

}  // namespace atlas
