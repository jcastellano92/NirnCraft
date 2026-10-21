// A section's ids packed (the host's warm tier and the disk's cold one, docs/ATLAS-STATUS.md step 5): made and expanded
// on the GPU where the ids pass through it anyway (atlas_voxgrid.slang vgPackMain / vgUnpackMain); this C++ mirror is for
// the host's single items (an edit's section, a test) only.
//
// Words (u32):
//   0       header: bits 0-4 the bits an index (0: one id throughout, 1, 2, 4, 8, 16: raw ids), bits 8-16 the palette's
//           length (0..256; 0 with 16 bits), bits 17-31 0
//   1..     the palette, two ids a word (low half first), ceil(n / 2) words
//   then    4096 indices of `bits` bits, voxel i = x + 16 (z + 16 y), packed from bit 0 of each word up (a voxel never
//           straddles two words); with 16 bits the ids themselves (2048 words, the section's own layout)
// A section of one id is the header and one palette word: 8 bytes.
#pragma once
#include <array>
#include <cstdint>
#include <vector>

namespace atlas {

constexpr uint32_t PACK_MAX_WORDS = 1 + 128 + 2048;  // the most a packed section takes (a raw one: 1 + 0 + 2048)

inline uint32_t packBits(const uint32_t* w) { return w[0] & 31u; }
inline uint32_t packPalette(const uint32_t* w) { return (w[0] >> 8) & 511u; }
inline uint32_t packWords(const uint32_t* w) {
    uint32_t b = packBits(w), n = packPalette(w);
    return 1 + (n + 1) / 2 + (b == 0 ? 0 : b == 16 ? 2048 : 4096 * b / 32);
}
// the one id of a uniform section, or ~0u
inline uint32_t packUniform(const uint32_t* w) { return packBits(w) == 0 ? (w[1] & 0xFFFFu) : ~0u; }

// (C++ mirror) pack: the fewest bits that hold the palette (the GPU's palette order may differ: the same ids)
inline std::vector<uint32_t> packSection(const uint16_t* ids) {
    std::vector<uint16_t> pal;
    std::vector<int16_t> where(65536, -1);
    for (int i = 0; i < 4096 && pal.size() <= 256; i++)
        if (where[ids[i]] < 0) where[ids[i]] = int16_t(pal.size()), pal.push_back(ids[i]);
    uint32_t n = uint32_t(pal.size());
    uint32_t bits = n == 1 ? 0 : n <= 2 ? 1 : n <= 4 ? 2 : n <= 16 ? 4 : n <= 256 ? 8 : 16;
    if (bits == 16) n = 0;
    std::vector<uint32_t> w(1 + (n + 1) / 2, 0u);
    w[0] = bits | n << 8;
    for (uint32_t k = 0; k < n; k++) w[1 + k / 2] |= uint32_t(pal[k]) << (16 * (k & 1));
    if (bits == 0) return w;
    if (bits == 16) {
        for (int i = 0; i < 4096; i += 2) w.push_back(uint32_t(ids[i]) | uint32_t(ids[i + 1]) << 16);
        return w;
    }
    size_t at = w.size();
    w.resize(at + 4096 * bits / 32, 0u);
    for (int i = 0; i < 4096; i++) {
        uint32_t bit = uint32_t(i) * bits;
        w[at + bit / 32] |= uint32_t(where[ids[i]]) << (bit & 31u);
    }
    return w;
}

// (C++ mirror) unpack into 4096 ids
inline void unpackSection(const uint32_t* w, uint16_t* ids) {
    uint32_t bits = packBits(w), n = packPalette(w);
    const uint32_t* pal = w + 1;
    const uint32_t* d = pal + (n + 1) / 2;
    if (bits == 0) {
        for (int i = 0; i < 4096; i++) ids[i] = uint16_t(pal[0] & 0xFFFFu);
        return;
    }
    if (bits == 16) {
        for (int i = 0; i < 4096; i++) ids[i] = uint16_t((d[i >> 1] >> (16 * (i & 1))) & 0xFFFFu);
        return;
    }
    uint32_t mask = (1u << bits) - 1u;
    for (int i = 0; i < 4096; i++) {
        uint32_t bit = uint32_t(i) * bits;
        uint32_t k = (d[bit / 32] >> (bit & 31u)) & mask;
        ids[i] = uint16_t((pal[k >> 1] >> (16 * (k & 1))) & 0xFFFFu);
    }
}

// The content hash a section is keyed by on disk (the cold tier: seed + generator version + this): FNV-1a over each run
// of 64 voxels (i = 64 t .. 64 t + 63, two bytes an id, seeded 2166136261 ^ t), the 64 runs' hashes folded the same way in
// order of t. Made by vgPackMain on the GPU (a thread a run); this mirror for the host's single items.
inline uint32_t sectionHash(const uint16_t* ids) {
    uint32_t h = 2166136261u;
    for (uint32_t t = 0; t < 64; t++) {
        uint32_t r = 2166136261u ^ t;
        for (uint32_t i = t * 64; i < t * 64 + 64; i++) {
            r = (r ^ (ids[i] & 0xFFu)) * 16777619u;
            r = (r ^ (ids[i] >> 8)) * 16777619u;
        }
        h = (h ^ r) * 16777619u;
    }
    return h;
}

}  // namespace atlas
