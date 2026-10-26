#pragma once
#include <cstdint>

// Level 0's asks for a GPU section it would take (VoxGrid: gpuStandby_, reportWanted). A section is asked for when it
// was never asked, and asked again once its last ask is `again` batches old, answered or not: an answer the generator
// or the client feed lost (a loaded chunk is never remade) left its "asked" flag set for good, and the hole in the near
// field stayed, drawn by the coarse levels a few blocks from the player (2026-10-26).
namespace vgask {
inline bool due(bool everAsked, uint64_t askedAt, uint64_t batchesNow, uint64_t again) {
    return !everAsked || batchesNow - askedAt >= again;
}
}  // namespace vgask
