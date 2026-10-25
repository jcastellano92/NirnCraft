// The whole-map coarse bake (docs/ATLAS-GPU-LOOP.md section 3, step 6): the far path (atlas_gen_far_ingest) run over every
// tile of the coarse levels the map touches, so that the self-baking cache (gencache.hpp: the far tiles' kinds and mixed
// chunks, f<lod>.<rx>.<rz>.gch) holds the whole horizon and a launch only loads it. This file plans the tiles and drives
// them through the public entry points (atlas_gen_far_known, atlas_gen_far_ingest, atlas_gen_cache_peek): the same code
// serves the game (AtlasCoarseBake.java) and the offline tool (tools/gen_bake_tool.cpp).
//
// Keying: the cache's set directory (gen-<fingerprint>/ under the world's store, which is per seed); a changed generator
// fingerprint opens another set and the old one is deleted, so the bake is made again. LATER (step 7): per-stage,
// per-region fingerprints in the region directory entries, so that a changed stage rebakes only the regions it touches.
#pragma once
#include <cstdint>
#include <vector>

#include "atlas/atlas.h"

namespace atlas {

struct BakeTile {
    int32_t lod, tx, tz;
};

// The tiles of levels lodFirst .. lodLast (either direction: the order they are made in) that touch [minX, maxX) x
// [minZ, maxZ) grown by margin blocks; within a level nearest the centre first (distance in tiles of the level).
std::vector<BakeTile> planBake(const AtlasGenBakeSpec& spec);

// Forgets the bake state of a generator (atlas_gen_destroy).
void bakeForget(AtlasGen* h);

}  // namespace atlas
