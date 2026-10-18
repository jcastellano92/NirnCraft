#include "svdag.hpp"

#include <algorithm>
#include <bit>
#include <cstring>
#include <string_view>

namespace aether {
namespace {

struct WordsHash {
    size_t operator()(const std::vector<uint32_t>& v) const {
        uint64_t h = 1469598103934665603ull;
        for (uint32_t w : v) h = (h ^ w) * 1099511628211ull;
        return size_t(h ^ (h >> 29));
    }
};

class Builder {
public:
    explicit Builder(VoxelSource& src) : src_(src) {}

    Svdag run() {
        colTop_.assign(64 * 64, 0);
        Child c = build(0, 0, 0, REGION);
        if (c.count == 0) {
            out_.root = EMPTY_ROOT;
        } else {
            out_.root = c.ptr;
        }
        out_.solidVoxels = total_;
        // the max-height mip, finest (4-column cells) to coarsest (the whole region)
        out_.heightMip.clear();
        std::vector<uint16_t> level = colTop_;
        for (int per = 64; per >= 1; per >>= 1) {
            out_.heightMip.insert(out_.heightMip.end(), level.begin(), level.end());
            if (per == 1) break;
            int np = per >> 1;
            std::vector<uint16_t> next(size_t(np) * np);
            for (int z = 0; z < np; z++)
                for (int x = 0; x < np; x++) {
                    size_t a = size_t(2 * z) * per + 2 * x;
                    next[size_t(z) * np + x] = std::max(std::max(level[a], level[a + 1]), std::max(level[a + per], level[a + per + 1]));
                }
            level.swap(next);
        }
        return std::move(out_);
    }

private:
    struct Child {
        uint32_t ptr = 0;
        uint64_t count = 0;  // solid voxels in the subtree
    };

    void emit(uint16_t mat, uint64_t n) {
        if (n == 0) return;
        total_ += n;
        if (!out_.runMat.empty() && out_.runMat.back() == mat) {
            out_.runEnd.back() = uint32_t(total_);
        } else {
            out_.runEnd.push_back(uint32_t(total_));
            out_.runMat.push_back(mat);
        }
    }

    uint32_t leaf(uint64_t bits) {
        auto it = leafIds_.find(bits);
        if (it != leafIds_.end()) return it->second;
        uint32_t id = uint32_t(out_.leaves.size());
        out_.leaves.push_back(bits);
        leafIds_.emplace(bits, id);
        return id;
    }

    uint32_t node(const std::vector<uint32_t>& words) {
        auto it = nodeIds_.find(words);
        if (it != nodeIds_.end()) return it->second;
        uint32_t off = uint32_t(out_.nodes.size());
        out_.nodes.insert(out_.nodes.end(), words.begin(), words.end());
        nodeIds_.emplace(words, off);
        return off;
    }

    // A full solid subtree of size n: shared per level, materials still emitted per voxel (as one run).
    void raiseTop(int x, int z, int n, int top) {
        for (int cz = z >> 2; cz < (z + n + 3) >> 2; cz++)
            for (int cx = x >> 2; cx < (x + n + 3) >> 2; cx++) {
                uint16_t& t = colTop_[size_t(cz) * 64 + cx];
                if (top > t) t = uint16_t(top);
            }
    }

    Child full(int n, uint16_t mat, int x, int y, int z) {
        uint64_t count = uint64_t(n) * n * n;
        emit(mat, count);
        raiseTop(x, z, n, y + n);
        return {fullNode(n), count};
    }

    uint32_t fullNode(int n) {
        if (n == BRICK) return leaf(~0ull);
        int level = std::countr_zero(unsigned(n));
        if (fullPtr_[level] != UINT32_MAX) return fullPtr_[level];
        uint32_t sub = fullNode(n / 2);
        uint64_t subCount = uint64_t(n / 2) * (n / 2) * (n / 2);
        std::vector<uint32_t> w(17);
        w[0] = 0xFFu | uint32_t(level) << 8;  // level in the key: equal words mean different things per level
        for (int i = 0; i < 8; i++) {
            w[1 + i] = sub;
            w[9 + i] = uint32_t(subCount * i);
        }
        fullPtr_[level] = node(w);
        return fullPtr_[level];
    }

    Child build(int x, int y, int z, int n) {
        uint16_t mat = 0;
        BoxClass cls = src_.classify(x, y, z, n, mat);
        if (cls == BoxClass::Air) return {};
        if (cls == BoxClass::Uniform) return full(n, mat, x, y, z);
        if (n == BRICK) {
            uint16_t m[64];
            src_.brick(x, y, z, m);
            uint64_t bits = 0;
            uint64_t count = 0;
            // source bricks are x + 4y + 16z; leaf bits are y-major (x + 4z + 16y) so terrain layers form long runs
            for (int b = 0; b < 64; b++) {
                int i = b & 3, k = (b >> 2) & 3, j = b >> 4;
                uint16_t v = m[i + 4 * j + 16 * k];
                if (v) {
                    bits |= 1ull << b;
                    emit(v, 1);
                    count++;
                }
            }
            if (!bits) return {};
            // highest occupied layer of this brick (bits are y-major: layer j is bits 16j..16j+15)
            int topLayer = 3;
            while (topLayer > 0 && ((bits >> (16 * topLayer)) & 0xFFFF) == 0) topLayer--;
            raiseTop(x, z, BRICK, y + topLayer + 1);
            return {leaf(bits), count};
        }
        int h = n / 2;
        Child kids[8];
        uint32_t mask = 0;
        uint64_t count = 0;
        for (int c = 0; c < 8; c++) {
            kids[c] = build(x + (c & 1) * h, y + ((c >> 2) & 1) * h, z + ((c >> 1) & 1) * h, h);
            if (kids[c].count) {
                mask |= 1u << c;
                count += kids[c].count;
            }
        }
        if (!mask) return {};
        int k = std::popcount(mask);
        std::vector<uint32_t> w(1 + 2 * k);
        w[0] = mask | uint32_t(std::countr_zero(unsigned(n))) << 8;
        uint64_t prefix = 0;
        int j = 0;
        for (int c = 0; c < 8; c++) {
            if (!(mask & (1u << c))) continue;
            w[1 + j] = kids[c].ptr;
            w[1 + k + j] = uint32_t(prefix);
            prefix += kids[c].count;
            j++;
        }
        return {node(w), count};
    }

    VoxelSource& src_;
    Svdag out_;
    uint64_t total_ = 0;
    std::vector<uint16_t> colTop_;  // per 4x4-voxel column of the region: highest occupied voxel y + 1
    std::unordered_map<uint64_t, uint32_t> leafIds_;
    std::unordered_map<std::vector<uint32_t>, uint32_t, WordsHash> nodeIds_;
    uint32_t fullPtr_[9] = {UINT32_MAX, UINT32_MAX, UINT32_MAX, UINT32_MAX, UINT32_MAX,
                            UINT32_MAX, UINT32_MAX, UINT32_MAX, UINT32_MAX};
};

}  // namespace

Svdag buildSvdag(VoxelSource& src) { return Builder(src).run(); }

uint16_t Svdag::materialOfRank(uint64_t rank) const {
    auto it = std::upper_bound(runEnd.begin(), runEnd.end(), uint32_t(rank));
    if (it == runEnd.end()) return 0;
    return runMat[size_t(it - runEnd.begin())];
}

uint16_t Svdag::at(int x, int y, int z) const {
    if (root == EMPTY_ROOT || x < 0 || y < 0 || z < 0 || x >= REGION || y >= REGION || z >= REGION) return 0;
    uint32_t ptr = root;
    uint64_t rank = 0;
    for (int size = REGION; size > BRICK; size >>= 1) {
        int h = size >> 1;
        int c = ((x & h) ? 1 : 0) | ((z & h) ? 2 : 0) | ((y & h) ? 4 : 0);
        uint32_t mask = nodes[ptr] & 0xFFu;
        if (!(mask & (1u << c))) return 0;
        int k = std::popcount(mask);
        int j = std::popcount(mask & ((1u << c) - 1));
        rank += nodes[ptr + 1 + k + j];
        ptr = nodes[ptr + 1 + j];
    }
    uint64_t bits = leaves[ptr];
    int b = (x & 3) + 4 * (z & 3) + 16 * (y & 3);
    if (!(bits >> b & 1)) return 0;
    rank += std::popcount(bits & ((1ull << b) - 1));
    return materialOfRank(rank);
}

}  // namespace aether
