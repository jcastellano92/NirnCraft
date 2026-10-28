// The cold tier's compression (docs/ATLAS-STATUS.md step 5): a section's 4096 values coded with an adaptive binary range
// coder, each voxel predicted from its neighbours already coded (the one before it in x, in z, below it in y: terrain and
// builds are layers and walls, so one of them is nearly always right). The same coder holds a whole section (its ids) and
// an edit delta (KEEP where the generated base stands, the new id where an edit changed it). On the CPU, where the
// disk's file I/O is anyway; a section of a few hundred bytes, an edit delta of tens.
//
// Bytes: the palette's length (varint), its values (varint each, in order of first appearance), then the coded voxels
// (x fastest, then z, then y: i = x + 16 (z + 16 y)). Per voxel: up to three bits "is it this neighbour's value?" (the
// neighbours' distinct values in order x, z, y; each bit's probability by its place and by which neighbours agree), then
// if none, its palette index in a bit tree.
#pragma once
#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace atlas {

namespace codec {

constexpr int PROB_BITS = 12, MOVE = 4;
constexpr uint32_t PROB_ONE = 1u << PROB_BITS, TOP = 1u << 24;

struct Encoder {
    std::vector<uint8_t>& out;
    uint64_t low = 0;
    uint32_t range = 0xFFFFFFFFu;
    uint8_t cache = 0;
    uint64_t cacheSize = 1;
    explicit Encoder(std::vector<uint8_t>& o) : out(o) {}
    void shiftLow() {
        if (uint32_t(low) < 0xFF000000u || (low >> 32) != 0) {
            uint8_t carry = uint8_t(low >> 32);
            uint8_t t = cache;
            do {
                out.push_back(uint8_t(t + carry));
                t = 0xFF;
            } while (--cacheSize != 0);
            cache = uint8_t(low >> 24);
        }
        cacheSize++;
        low = (low & 0x00FFFFFFu) << 8;
    }
    void bit(uint16_t& p, int b) {
        uint32_t bound = (range >> PROB_BITS) * p;
        if (!b) {
            range = bound;
            p = uint16_t(p + ((PROB_ONE - p) >> MOVE));
        } else {
            low += bound;
            range -= bound;
            p = uint16_t(p - (p >> MOVE));
        }
        while (range < TOP) {
            range <<= 8;
            shiftLow();
        }
    }
    void finish() {
        for (int i = 0; i < 5; i++) shiftLow();
    }
};

struct Decoder {
    const uint8_t* p;
    const uint8_t* end;
    uint32_t range = 0xFFFFFFFFu, code = 0;
    Decoder(const uint8_t* b, const uint8_t* e) : p(b), end(e) {
        for (int i = 0; i < 5; i++) code = code << 8 | next();
    }
    uint8_t next() { return p < end ? *p++ : 0; }
    int bit(uint16_t& pr) {
        uint32_t bound = (range >> PROB_BITS) * pr;
        int b;
        if (code < bound) {
            range = bound;
            pr = uint16_t(pr + ((PROB_ONE - pr) >> MOVE));
            b = 0;
        } else {
            code -= bound;
            range -= bound;
            pr = uint16_t(pr - (pr >> MOVE));
            b = 1;
        }
        while (range < TOP) {
            range <<= 8;
            code = code << 8 | next();
        }
        return b;
    }
};

inline void putVarint(std::vector<uint8_t>& o, uint32_t v) {
    while (v >= 0x80) {
        o.push_back(uint8_t(v | 0x80));
        v >>= 7;
    }
    o.push_back(uint8_t(v));
}
inline bool getVarint(const uint8_t*& p, const uint8_t* e, uint32_t& v) {
    v = 0;
    for (int s = 0; s < 35; s += 7) {
        if (p >= e) return false;
        uint8_t b = *p++;
        v |= uint32_t(b & 0x7F) << s;
        if (!(b & 0x80)) return true;
    }
    return false;
}

// the model: the neighbours' bits (place 0-2, which exist 0-7, which agree 0-7) and the index tree
struct Model {
    uint16_t hit[3 * 8 * 8];
    uint16_t tree[4096];
    uint32_t treeSize = 1;
    int bits = 0;
    explicit Model(uint32_t n) {
        std::fill(std::begin(hit), std::end(hit), uint16_t(PROB_ONE / 2));
        while ((1u << bits) < n) bits++;
        treeSize = 1u << bits;
        std::fill(tree, tree + treeSize, uint16_t(PROB_ONE / 2));
    }
};

// voxel i's distinct neighbour values (x, z, y order) and its context bits
inline int neighbours(const uint32_t* v, int i, uint32_t c[3], int& ctx) {
    int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
    uint32_t nb[3];
    bool has[3] = {x > 0, z > 0, y > 0};
    nb[0] = has[0] ? v[i - 1] : 0;
    nb[1] = has[1] ? v[i - 16] : 0;
    nb[2] = has[2] ? v[i - 256] : 0;
    int avail = int(has[0]) | int(has[1]) << 1 | int(has[2]) << 2;
    int eq = int(has[0] && has[1] && nb[0] == nb[1]) | int(has[0] && has[2] && nb[0] == nb[2]) << 1 |
             int(has[1] && has[2] && nb[1] == nb[2]) << 2;
    ctx = avail * 8 + eq;
    int n = 0;
    for (int k = 0; k < 3; k++) {
        if (!has[k]) continue;
        bool seen = false;
        for (int j = 0; j < n; j++) seen |= c[j] == nb[k];
        if (!seen) c[n++] = nb[k];
    }
    return n;
}

}  // namespace codec

// 4096 values (ids, or an edit delta's) to bytes, appended to out
inline void encodeGrid(const uint32_t* vals, std::vector<uint8_t>& out) {
    std::vector<uint32_t> pal;
    std::vector<uint32_t> idx(4096);
    {
        std::vector<std::pair<uint32_t, uint32_t>> seen;  // (value, index), small: linear
        for (int i = 0; i < 4096; i++) {
            if (i > 0 && vals[i] == vals[i - 1]) {
                idx[size_t(i)] = idx[size_t(i) - 1];
                continue;
            }
            uint32_t k = ~0u;
            for (auto& [val, at] : seen)
                if (val == vals[i]) {
                    k = at;
                    break;
                }
            if (k == ~0u) {
                k = uint32_t(pal.size());
                pal.push_back(vals[i]);
                seen.push_back({vals[i], k});
            }
            idx[size_t(i)] = k;
        }
    }
    codec::putVarint(out, uint32_t(pal.size()));
    for (uint32_t p : pal) codec::putVarint(out, p);
    if (pal.size() == 1) return;
    codec::Model m(uint32_t(pal.size()));
    codec::Encoder e(out);
    for (int i = 0; i < 4096; i++) {
        uint32_t c[3];
        int ctx;
        int n = codec::neighbours(idx.data(), i, c, ctx);
        bool done = false;
        for (int j = 0; j < n && !done; j++) {
            int b = idx[size_t(i)] == c[j];
            e.bit(m.hit[j * 64 + ctx], b);
            done = b;
        }
        if (done) continue;
        uint32_t node = 1, v = idx[size_t(i)];
        for (int b = m.bits - 1; b >= 0; b--) {
            int bit = int((v >> b) & 1u);
            e.bit(m.tree[node], bit);
            node = node * 2 + uint32_t(bit);
            if (node >= m.treeSize) node = 1;  // (the tree's last level: no probability needed past it)
        }
    }
    e.finish();
}

// bytes to 4096 values; false: malformed
inline bool decodeGrid(const uint8_t* p, size_t size, uint32_t* vals) {
    const uint8_t* e = p + size;
    uint32_t n;
    if (!codec::getVarint(p, e, n) || n == 0 || n > 4096) return false;
    uint32_t pal[4096];
    for (uint32_t i = 0; i < n; i++)
        if (!codec::getVarint(p, e, pal[i])) return false;
    if (n == 1) {
        std::fill(vals, vals + 4096, pal[0]);
        return true;
    }
    codec::Model m(n);
    codec::Decoder d(p, e);
    uint32_t idx[4096];
    for (int i = 0; i < 4096; i++) {
        uint32_t c[3];
        int ctx;
        int k = codec::neighbours(idx, i, c, ctx);
        bool done = false;
        for (int j = 0; j < k && !done; j++)
            if (d.bit(m.hit[j * 64 + ctx])) {
                idx[size_t(i)] = c[j];
                done = true;
            }
        if (done) continue;
        uint32_t node = 1, v = 0;
        for (int b = m.bits - 1; b >= 0; b--) {
            int bit = d.bit(m.tree[node]);
            v = v << 1 | uint32_t(bit);
            node = node * 2 + uint32_t(bit);
            if (node >= m.treeSize) node = 1;
        }
        if (v >= n) return false;
        idx[size_t(i)] = v;
    }
    for (int i = 0; i < 4096; i++) vals[i] = pal[idx[size_t(i)]];
    return true;
}

}  // namespace atlas
