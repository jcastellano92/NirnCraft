#include "groups.hpp"

#include <algorithm>
#include <array>
#include <bit>
#include <chrono>
#include <cstring>
#include <unordered_map>

namespace atlas {
namespace {

// the texel of face f over micro-voxel (x, y, z) of a block (AtlasLabExport's layout; shaders/atlas_groups.slang texel)
int texelOf(int f, int x, int y, int z) {
    if (f <= 1) return x + 4 * z;
    if (f <= 3) return x + 4 * (3 - y);
    return z + 4 * (3 - y);
}

int bitOf(int x, int y, int z) { return x + 4 * (z + 4 * y); }

// A content voxel: where it is, what it is, its micro-voxels as seen along each axis.
struct Prim {
    uint8_t v[3];
    uint32_t cell;      // material | VOXEL_SHAPED
    uint64_t mask;      // resolved micro-voxels (cut-out applied; with face masks: those with any)
    const SubVoxels* faces = nullptr;  // (masked) its sub-voxels, where it has micro-voxels only partly covered
    bool partial = false;
    uint16_t lo[3], hi[3];  // its tight box, region micro-voxels
    uint16_t qlo[3], qhi[3];  // the same in quarter micro-voxels, clipped to its content's box (GroupInput::boxes)
    uint32_t weight;    // micro-voxels: the SAH's content
    uint16_t cover[3];  // per axis a: the micro columns along a with content, bit u + 4 * v over ((a + 1) % 3, (a + 2) % 3)
    uint32_t dlo[3], dhi[3];  // per axis, 2 bits a column: how deep the first content lies from the low / high face
};

void describe(Prim& p, uint32_t box = BOX_FULL) {
    uint64_t m = p.mask;
    int lo[3] = {4, 4, 4}, hi[3] = {-1, -1, -1};
    for (int b = 0; b < 64; b++) {
        if (!((m >> b) & 1)) continue;
        int q[3] = {b & 3, b >> 4, (b >> 2) & 3};
        for (int a = 0; a < 3; a++) lo[a] = std::min(lo[a], q[a]), hi[a] = std::max(hi[a], q[a]);
    }
    for (int a = 0; a < 3; a++) {
        p.lo[a] = uint16_t(p.v[a] * 4 + lo[a]);
        p.hi[a] = uint16_t(p.v[a] * 4 + hi[a] + 1);
        int a1 = (a + 1) % 3, a2 = (a + 2) % 3;
        p.cover[a] = 0;
        p.dlo[a] = p.dhi[a] = 0;
        if (m == SHAPE_FULL) {
            p.cover[a] = 0xFFFF;
            continue;
        }
        for (int v = 0; v < 4; v++)
            for (int u = 0; u < 4; u++) {
                int first = -1, last = -1;
                for (int k = 0; k < 4; k++) {
                    int q[3];
                    q[a] = k, q[a1] = u, q[a2] = v;
                    if ((m >> bitOf(q[0], q[1], q[2])) & 1) {
                        if (first < 0) first = k;
                        last = k;
                    }
                }
                if (first < 0) continue;
                int t = u + 4 * v;
                p.cover[a] |= uint16_t(1u << t);
                p.dlo[a] |= uint32_t(first) << (2 * t);
                p.dhi[a] |= uint32_t(3 - last) << (2 * t);
            }
    }
    p.weight = uint32_t(std::popcount(m));
    int blo[3], bhi[3];
    unpackBox(box, blo, bhi);
    for (int a = 0; a < 3; a++) {
        int ql = std::max(p.lo[a] * 4, p.v[a] * 16 + blo[a]), qh = std::min(p.hi[a] * 4, p.v[a] * 16 + bhi[a]);
        if (qh <= ql) ql = p.lo[a] * 4, qh = p.hi[a] * 4;  // (a box that misses its micro-voxels: theirs)
        p.qlo[a] = uint16_t(ql), p.qhi[a] = uint16_t(qh);
    }
}

// a node's box: its content's in quarter micro-voxels (what is written and drawn; the SAH), and its prims'
// micro-voxels' (what the errors and holes are measured on)
struct Box {
    int lo[3], hi[3];
    int mlo[3], mhi[3];
    void clear() {
        for (int a = 0; a < 3; a++) lo[a] = mlo[a] = 1 << 20, hi[a] = mhi[a] = -1;
    }
    void add(const Prim& p) {
        for (int a = 0; a < 3; a++) {
            lo[a] = std::min(lo[a], int(p.qlo[a])), hi[a] = std::max(hi[a], int(p.qhi[a]));
            mlo[a] = std::min(mlo[a], int(p.lo[a])), mhi[a] = std::max(mhi[a], int(p.hi[a]));
        }
    }
    Box micro() const {
        Box m = *this;
        for (int a = 0; a < 3; a++) m.lo[a] = mlo[a], m.hi[a] = mhi[a];
        return m;
    }
    void add(const Box& b) {
        for (int a = 0; a < 3; a++) {
            lo[a] = std::min(lo[a], b.lo[a]), hi[a] = std::max(hi[a], b.hi[a]);
            mlo[a] = std::min(mlo[a], b.mlo[a]), mhi[a] = std::max(mhi[a], b.mhi[a]);
        }
    }
    float area() const {
        if (hi[0] < lo[0]) return 0;
        float dx = float(hi[0] - lo[0]), dy = float(hi[1] - lo[1]), dz = float(hi[2] - lo[2]);
        return dx * dy + dy * dz + dz * dx;
    }
};

class Builder {
public:
    Builder(std::vector<Prim>& prims, const GroupLimits& lim) : prims_(prims), lim_(lim) {}

    bool run(GroupTree& out, std::string* why) {
        size_t n = prims_.size();
        for (int a = 0; a < 3; a++) {
            order_[a].resize(n);
            for (size_t i = 0; i < n; i++) order_[a][i] = uint32_t(i);
            std::stable_sort(order_[a].begin(), order_[a].end(),
                             [&](uint32_t x, uint32_t y) { return prims_[x].v[a] < prims_[y].v[a]; });
        }
        side_.assign(n, 0);
        scratch_.resize(n);
        prefix_.resize(n + 1);
        Box b;
        b.clear();
        for (const Prim& p : prims_) b.add(p);
        out_ = &out;
        out.nodes.assign(4, 0);
        build(0, 0, uint32_t(n), b, 0);
        if (failed_) {
            if (why) *why = "more than " + std::to_string(lim_.maxNodes) + " nodes";
            out.nodes.clear();
            return false;
        }
        out.depth = depth_;
        return true;
    }

private:
    // the flattening error of each face of the node (micro-voxels: how deep the first content lies under it, the
    // deepest over the face) and whether each axis' faces have holes (columns with nothing in them); qbox in quarter
    // micro-voxels, measured on the micro-voxels it touches
    void analyze(uint32_t b, uint32_t e, const Box& qbox, uint32_t err[6], bool holes[3]) {
        Box box = qbox.micro();
        for (int a = 0; a < 3; a++) {
            int a1 = (a + 1) % 3, a2 = (a + 2) % 3;
            int vlo1 = box.lo[a1] >> 2, vlo2 = box.lo[a2] >> 2;
            int w = ((box.hi[a1] + 3) >> 2) - vlo1, h = ((box.hi[a2] + 3) >> 2) - vlo2;
            covered_.assign(size_t(w) * size_t(h), 0);
            const std::vector<uint32_t>& ord = order_[a];
            uint32_t eLo = 0, eHi = 0;
            for (uint32_t i = b; i < e; i++) {
                const Prim& p = prims_[ord[i]];
                uint16_t& c = covered_[size_t(p.v[a1] - vlo1) + size_t(w) * size_t(p.v[a2] - vlo2)];
                uint32_t nb = p.cover[a] & ~c;
                if (!nb) continue;
                c |= p.cover[a];
                if (p.mask == SHAPE_FULL) {
                    eLo = std::max(eLo, uint32_t(p.v[a] * 4 - box.lo[a]));
                    continue;
                }
                for (; nb; nb &= nb - 1) {
                    int t = std::countr_zero(nb);
                    eLo = std::max(eLo, uint32_t(p.v[a] * 4 + int((p.dlo[a] >> (2 * t)) & 3) - box.lo[a]));
                }
            }
            size_t got = 0;
            for (uint16_t c : covered_) got += size_t(std::popcount(c));
            holes[a] = got != size_t(box.hi[a1] - box.lo[a1]) * size_t(box.hi[a2] - box.lo[a2]);
            std::fill(covered_.begin(), covered_.end(), uint16_t(0));
            for (uint32_t i = e; i-- > b;) {
                const Prim& p = prims_[ord[i]];
                uint16_t& c = covered_[size_t(p.v[a1] - vlo1) + size_t(w) * size_t(p.v[a2] - vlo2)];
                uint32_t nb = p.cover[a] & ~c;
                if (!nb) continue;
                c |= p.cover[a];
                if (p.mask == SHAPE_FULL) {
                    eHi = std::max(eHi, uint32_t(box.hi[a] - (p.v[a] * 4 + 4)));
                    continue;
                }
                for (; nb; nb &= nb - 1) {
                    int t = std::countr_zero(nb);
                    eHi = std::max(eHi, uint32_t(box.hi[a] - (p.v[a] * 4 + 4 - int((p.dhi[a] >> (2 * t)) & 3))));
                }
            }
            err[2 * a] = eLo;
            err[2 * a + 1] = eHi;
        }
    }

    void write(uint32_t at, const Box& box, const uint32_t err[6], const bool holes[3], uint32_t type, uint32_t child) {
        uint32_t* w = &out_->nodes[size_t(at) * 4];
        uint32_t most = 0, hb = 0;
        for (int a = 0; a < 3; a++) {
            w[a] = uint32_t(box.lo[a]) | uint32_t(box.hi[a] - 1) << 12 | errCode(err[2 * a]) << 24 | errCode(err[2 * a + 1]) << 28;
            most = std::max(most, std::max(err[2 * a], err[2 * a + 1]));
            hb |= holes[a] ? 1u << a : 0u;
        }
        w[3] = type << 30 | hb << NODE_HOLES_SHIFT | errCode(most) << NODE_MAXERR_SHIFT | child;
    }

    void build(uint32_t at, uint32_t b, uint32_t e, const Box& box, int depth) {
        if (failed_) return;
        depth_ = std::max(depth_, depth);
        uint32_t err[6];
        bool holes[3];
        analyze(b, e, box, err, holes);
        bool flat = !holes[0] && !holes[1] && !holes[2];
        for (int f = 0; f < 6; f++) flat = flat && err[f] == 0;
        if (flat) return write(at, box, err, holes, NODE_FLAT, 0);
        bool small = true;
        for (int a = 0; a < 3; a++) small = small && ((box.mhi[a] + 3) >> 2) - (box.mlo[a] >> 2) <= 4;  // (voxels)
        if (small || e - b == 1 || depth >= GROUP_STACK - 4) return write(at, box, err, holes, NODE_VOXELS, 0);
        // the SAH over every voxel plane of every axis: each side's tight box surface times what it holds
        float best = 3.4e38f;
        int bestAxis = -1, bestPlane = 0;
        Box bestL{}, bestR{};
        for (int a = 0; a < 3; a++) {
            const std::vector<uint32_t>& ord = order_[a];
            // suffix boxes and weights per position (from the end)
            Box acc;
            acc.clear();
            uint64_t wacc = 0;
            for (uint32_t i = e; i-- > b;) {
                acc.add(prims_[ord[i]]);
                wacc += prims_[ord[i]].weight;
                prefix_[i - b] = {acc, wacc};
            }
            acc.clear();
            wacc = 0;
            for (uint32_t i = b; i < e; i++) {
                const Prim& p = prims_[ord[i]];
                acc.add(p);
                wacc += p.weight;
                if (i + 1 == e || prims_[ord[i + 1]].v[a] == p.v[a]) continue;  // split only between voxel planes
                const auto& r = prefix_[i + 1 - b];
                float c = acc.area() * float(wacc) + r.box.area() * float(r.weight);
                if (c < best) {
                    best = c;
                    bestAxis = a;
                    bestPlane = prims_[ord[i + 1]].v[a];
                    bestL = acc;
                    bestR = r.box;
                }
            }
        }
        if (bestAxis < 0) return write(at, box, err, holes, NODE_VOXELS, 0);
        // partition every axis' order stably: the left side first
        uint32_t nl = 0;
        for (uint32_t i = b; i < e; i++) {
            uint32_t k = order_[bestAxis][i];
            side_[k] = prims_[k].v[bestAxis] < bestPlane ? 1 : 0;
            nl += side_[k];
        }
        for (int a = 0; a < 3; a++) {
            std::vector<uint32_t>& ord = order_[a];
            uint32_t l = 0, r = nl;
            for (uint32_t i = b; i < e; i++) scratch_[side_[ord[i]] ? l++ : r++] = ord[i];
            std::copy(scratch_.begin(), scratch_.begin() + (e - b), ord.begin() + b);
        }
        uint32_t first = uint32_t(out_->nodes.size() / 4);
        if (first + 2 > std::min<uint32_t>(lim_.maxNodes, CHILD_MASK)) {
            failed_ = true;
            return;
        }
        out_->nodes.resize(out_->nodes.size() + 8);
        write(at, box, err, holes, NODE_INNER, first);
        build(first, b, b + nl, bestL, depth + 1);
        build(first + 1, b + nl, e, bestR, depth + 1);
    }

    struct Suffix {
        Box box;
        uint64_t weight;
    };

    std::vector<Prim>& prims_;
    GroupLimits lim_;
    GroupTree* out_ = nullptr;
    std::vector<uint32_t> order_[3];
    std::vector<uint8_t> side_;
    std::vector<uint32_t> scratch_;
    std::vector<Suffix> prefix_;
    std::vector<uint16_t> covered_;
    int depth_ = 0;
    bool failed_ = false;
};

struct WordsHash {
    size_t operator()(const std::vector<uint32_t>& v) const {
        uint64_t h = 1469598103934665603ull;
        for (uint32_t w : v) h = (h ^ w) * 1099511628211ull;
        return size_t(h ^ (h >> 29));
    }
};

// The brick pool: every content voxel's cell, in deduplicated bricks of 4 x 4 x 4 voxels found through per-column
// slot lists (the kernel's voxelCell).
void buildPool(const std::vector<Prim>& prims, GroupTree& out) {
    std::vector<int32_t> at(64 * 64 * 64, -1);  // brick (x + 64 * (z + 64 * y)) -> its cells in raw
    std::vector<std::array<uint32_t, 32>> raw;
    std::vector<uint64_t> rawMasks;  // per raw brick 64 voxels' own micro-voxels (masked ones)
    std::vector<const SubVoxels*> rawFaces;  // and their sub-voxels
    for (const Prim& p : prims) {
        int bx = p.v[0] >> 2, by = p.v[1] >> 2, bz = p.v[2] >> 2;
        int32_t& k = at[size_t(bx + 64 * (bz + 64 * by))];
        if (k < 0) {
            k = int32_t(raw.size());
            raw.push_back({});
            rawMasks.resize(rawMasks.size() + 64, 0);
            rawFaces.resize(rawFaces.size() + 64, nullptr);
        }
        uint32_t m = p.cell & 0xFFFFu;
        bool masked = (p.cell & VOXEL_MASKED) && (p.mask != SHAPE_FULL || p.partial);
        uint32_t cell = (m & CELL_MATERIAL) | ((p.cell & VOXEL_SHAPED) && m <= CELL_MATERIAL ? CELL_SHAPED : 0u) |
                        (masked ? CELL_MASKED : 0u);
        int i = (p.v[0] & 3) + 4 * ((p.v[2] & 3) + 4 * (p.v[1] & 3));
        raw[size_t(k)][size_t(i >> 1)] |= cell << (16 * (i & 1));
        if (masked) {
            rawMasks[size_t(k) * 64 + size_t(i)] = p.mask;
            rawFaces[size_t(k) * 64 + size_t(i)] = p.partial ? p.faces : nullptr;
        }
    }
    // a brick as stored: which of its 64 voxels hold something (2 words), then only those cells, two to a word, in
    // order (most bricks of a terrain skin hold a third of their voxels); identical ones once
    std::unordered_map<std::vector<uint32_t>, uint32_t, WordsHash> unique;
    std::vector<uint32_t> id(raw.size()), packed;
    std::vector<uint8_t> hasMasks(raw.size(), 0);
    for (size_t k = 0; k < raw.size(); k++) {
        uint64_t occ = 0, mocc = 0;
        for (int i = 0; i < 64; i++) {
            uint32_t cell = (raw[k][size_t(i >> 1)] >> (16 * (i & 1))) & 0xFFFFu;
            if (cell) occ |= 1ull << i;
            if (cell & CELL_MASKED) mocc |= 1ull << i;
        }
        hasMasks[k] = mocc != 0;
        packed.assign(mocc ? 4 : 2, 0);
        packed[0] = uint32_t(occ);
        packed[1] = uint32_t(occ >> 32);
        if (mocc) packed[2] = uint32_t(mocc), packed[3] = uint32_t(mocc >> 32);
        uint32_t n = 0;
        for (int i = 0; i < 64; i++) {
            uint32_t cell = (raw[k][size_t(i >> 1)] >> (16 * (i & 1))) & 0xFFFFu;
            if (!cell) continue;
            if (!(n & 1)) packed.push_back(0);
            packed.back() |= cell << (16 * (n & 1));
            n++;
        }
        // the masked voxels' micro-voxels and where their sub-voxels are, then those tables (MASKED_WORDS, SubVoxels)
        std::vector<size_t> tableSlot;
        std::vector<const SubVoxels*> tables;
        for (int i = 0; i < 64; i++)
            if ((mocc >> i) & 1) {
                uint64_t m = rawMasks[k * 64 + size_t(i)];
                packed.push_back(uint32_t(m));
                packed.push_back(uint32_t(m >> 32));
                packed.push_back(0);
                if (const SubVoxels* f = rawFaces[k * 64 + size_t(i)]) tableSlot.push_back(packed.size() - 1), tables.push_back(f);
            }
        for (size_t t = 0; t < tables.size(); t++) {
            const SubVoxels& f = *tables[t];
            packed[tableSlot[t]] = uint32_t(packed.size());  // (brick-relative)
            packed.push_back(uint32_t(f.partial));
            packed.push_back(uint32_t(f.partial >> 32));
            for (int b = 0; b < 64; b++)
                if ((f.partial >> b) & 1) {
                    packed.push_back(uint32_t(f.sub[size_t(b)]));
                    packed.push_back(uint32_t(f.sub[size_t(b)] >> 32));
                }
        }
        auto it = unique.find(packed);
        if (it == unique.end()) {
            it = unique.emplace(packed, uint32_t(out.bricks.size())).first;
            out.bricks.insert(out.bricks.end(), packed.begin(), packed.end());
        }
        id[k] = it->second;
    }
    out.brickCount = uint32_t(unique.size());
    out.dir.assign(64 * 64, 0);
    for (int bz = 0; bz < 64; bz++)
        for (int bx = 0; bx < 64; bx++) {
            int y0 = 64, y1 = -1;
            for (int by = 0; by < 64; by++)
                if (at[size_t(bx + 64 * (bz + 64 * by))] >= 0) y0 = std::min(y0, by), y1 = std::max(y1, by);
            if (y1 < 0) continue;
            uint32_t off = uint32_t(out.slots.size());
            for (int by = y0; by <= y1; by++) {
                int32_t k = at[size_t(bx + 64 * (bz + 64 * by))];
                out.slots.push_back(k < 0 ? 0u : (id[size_t(k)] + 1u) | (hasMasks[size_t(k)] ? SLOT_MASKS : 0u));
            }
            out.dir[size_t(bx + 64 * bz)] = off | uint32_t(y0) << 18 | uint32_t(y1 - y0 + 1) << 24;
        }
}

}  // namespace

void MaterialShape::deriveAlpha() {
    std::memset(alpha, 255, sizeof alpha);
    if (!(flags & SHAPE_CUTOUT)) return;
    // atlas_lab loadBlocks: per micro-voxel the mean alpha of the face texels over it, among the faces showing anything
    bool content[6];
    for (int f = 0; f < 6; f++) {
        content[f] = false;
        for (int t = 0; t < 16; t++) content[f] |= (faces[f][t] >> 24) != 0;
    }
    for (int m = 0; m < 64; m++) {
        int x = m & 3, z = (m >> 2) & 3, y = m >> 4;
        uint32_t s = 0, k = 0;
        for (int f = 0; f < 6; f++)
            if (content[f]) {
                s += faces[f][texelOf(f, x, y, z)] >> 24;
                k++;
            }
        alpha[m] = uint8_t(k ? (s + k / 2) / k : 255);
    }
}

GroupTree buildGroupTree(const GroupInput& in, const ShapeSet& shapes, const GroupLimits& lim, std::string* why) {
    auto t0 = std::chrono::steady_clock::now();
    GroupTree out;
    // the content: voxels of this region row with an open neighbour (air, a shaped block's empty part, cut-out leaves)
    auto open = [&](int x, int row, int z) {
        int64_t i = in.index(x, row, z);
        if (i == -1) return true;
        if (i == -2) return false;
        uint32_t c = in.vox[size_t(i)];
        if (c == 0) return true;
        if (c & VOXEL_MASKED) {  // (its micro-voxels, or a content box short of the voxel's)
            auto m = in.masks.find(uint32_t(i));
            return (m != in.masks.end() && m->second != SHAPE_FULL) || in.boxes.count(uint32_t(i)) != 0 ||
                   in.faces.count(uint32_t(i)) != 0;
        }
        if (!(c & VOXEL_SHAPED)) return false;
        const MaterialShape* s = shapes.at(c & 0xFFFFu);
        return s && s->open();
    };
    std::vector<Prim> prims;
    for (int z = 0; z < 256; z++)
        for (int x = 0; x < 256; x++) {
            int c = GroupInput::col(x, z);
            int r0 = std::max(in.lo[size_t(c)], in.rowY0), r1 = std::min(in.hi[size_t(c)], in.rowY0 + 256);
            for (int row = r0; row < r1; row++) {
                uint32_t cell = in.at(x, row, z);
                if (cell == 0 || cell == 0xFFFFFFFFu) continue;
                if (!open(x, row + 1, z) && !open(x, row - 1, z) && !open(x - 1, row, z) && !open(x + 1, row, z) &&
                    !open(x, row, z - 1) && !open(x, row, z + 1))
                    continue;
                Prim p{};
                p.v[0] = uint8_t(x);
                p.v[1] = uint8_t(row - in.rowY0);
                p.v[2] = uint8_t(z);
                p.cell = cell;
                p.mask = SHAPE_FULL;
                if (cell & VOXEL_MASKED) {
                    uint32_t vi = uint32_t(in.index(x, row, z));
                    auto m = in.masks.find(vi);
                    if (m != in.masks.end()) p.mask = m->second;
                    auto fc = in.faces.find(vi);
                    if (fc != in.faces.end() && fc->second.partial) p.faces = &fc->second, p.partial = true;
                } else if (cell & VOXEL_SHAPED) {
                    const MaterialShape* s = shapes.at(cell & 0xFFFFu);
                    if (s) {
                        p.mask = s->mask;
                        if (s->flags & SHAPE_CUTOUT) {
                            int mx = in.microOrigin[0] + x * 4, my = in.microOrigin[1] + p.v[1] * 4, mz = in.microOrigin[2] + z * 4;
                            for (int b = 0; b < 64; b++) {
                                if (!((p.mask >> b) & 1) || s->alpha[b] == 255) continue;
                                if ((cutHash(mx + (b & 3), my + (b >> 4), mz + ((b >> 2) & 3)) >> 24) >= s->alpha[b])
                                    p.mask &= ~(1ull << b);
                            }
                        }
                    }
                }
                if (!p.mask) continue;
                uint32_t vb = BOX_FULL;
                if (cell & VOXEL_MASKED) {
                    auto bx = in.boxes.find(uint32_t(in.index(x, row, z)));
                    if (bx != in.boxes.end()) vb = bx->second;
                }
                describe(p, vb);
                prims.push_back(p);
            }
        }
    out.prims = uint32_t(prims.size());
    if (!prims.empty()) {
        Builder bld(prims, lim);
        if (bld.run(out, why)) buildPool(prims, out);
        else out = GroupTree{};
    }
    out.buildMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    return out;
}

}  // namespace atlas
