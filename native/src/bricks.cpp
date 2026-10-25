#include "bricks.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <map>

namespace atlas {

namespace {

float lin(uint32_t c8) { return std::pow(float(c8) / 255.0f, 2.2f); }

// the voxel a sample at p (micro-voxel units, 0..16) of a quad with normal n lands in. An axis-aligned quad on a
// micro-voxel boundary goes into the voxel behind it (inside its element); a zero-thickness element's two faces (a
// rail, a ladder: up and down, or front and back, on the same plane) share the voxel the first of them took
struct Placer {
    // plane (axis, boundary index) x cell on the plane -> the layer taken (+1; 0: none yet)
    std::map<uint64_t, int> taken;
    bool place(const float p[3], const float n[3], int out[3]) {
        int a = std::fabs(n[0]) > 0.999f ? 0 : std::fabs(n[1]) > 0.999f ? 1 : std::fabs(n[2]) > 0.999f ? 2 : -1;
        for (int k = 0; k < 3; k++) {
            // (a model may overshoot its block a little: a piston head's rod, a few custom models; up to two
            // micro-voxels it is kept, at the edge)
            if (!(p[k] > -2.0f && p[k] < 18.0f)) return false;
            out[k] = std::clamp(int(std::floor(p[k])), 0, 15);
        }
        if (a < 0) {
            // slanted (crossed plants, rotated elements): the voxel the point is in, a hair inside
            for (int k = 0; k < 3; k++) out[k] = std::clamp(int(std::floor(p[k] - n[k] * 0.02f)), 0, 15);
            return true;
        }
        float c = p[a];
        float r = std::round(c);
        if (std::fabs(c - r) > 1e-3f) {
            out[a] = std::clamp(int(std::floor(c)), 0, 15);
            return true;
        }
        int b0 = (a + 1) % 3, b1 = (a + 2) % 3;
        uint64_t key = uint64_t(a) << 40 | uint64_t(uint32_t(int(r) + 1)) << 16 | uint64_t(out[b0]) << 8 | uint64_t(out[b1]);
        int want = std::clamp(int(r) - (n[a] > 0 ? 1 : 0), 0, 15);
        auto it = taken.find(key);
        if (it != taken.end()) want = it->second - 1;
        else taken.emplace(key, want + 1);
        out[a] = want;
        return true;
    }
};

}  // namespace

bool voxeliseBrick(const AtlasBrickQuad* quads, uint32_t quadCount, uint32_t m, const AtlasBrickSprite* sprites,
                   uint32_t spriteCount, const uint32_t* texels, uint32_t texelCount, float emission, BrickVoxels& v,
                   uint32_t frame) {
    std::vector<float> acc(4096 * 4, 0.0f);  // linear rgb sums, samples
    std::vector<int32_t> lastQuad(4096, -1);  // the quad that last wrote each voxel: a later quad over it overwrites
    Placer placer;
    bool any = false;
    for (uint32_t qi = 0; qi < quadCount; qi++) {
        const AtlasBrickQuad& q = quads[qi];
        if (q.material != m || q.sprite >= spriteCount) continue;
        const AtlasBrickSprite& sp = sprites[q.sprite];
        uint32_t nf = std::max(1u, sp.frames & 0xFFFFu);
        if (!sp.width || !sp.height || uint64_t(sp.firstTexel) + uint64_t(sp.width) * sp.height * nf > texelCount) continue;
        uint32_t f0 = sp.firstTexel + (frame % nf) * sp.width * sp.height;
        float P[4][3];
        for (int i = 0; i < 4; i++)
            for (int k = 0; k < 3; k++) P[i][k] = q.pos[i][k] * 16.0f;
        float e1[3], e2[3], n[3];
        for (int k = 0; k < 3; k++) e1[k] = P[1][k] - P[0][k], e2[k] = P[3][k] - P[0][k];
        n[0] = e1[1] * e2[2] - e1[2] * e2[1], n[1] = e1[2] * e2[0] - e1[0] * e2[2], n[2] = e1[0] * e2[1] - e1[1] * e2[0];
        float nl = std::sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        if (nl < 1e-6f) continue;
        for (float& c : n) c /= nl;
        // outwards as the quad's face direction says (winding aside)
        static const int DV[6][3] = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        if (q.dir >= 0 && q.dir < 6 && n[0] * DV[q.dir][0] + n[1] * DV[q.dir][1] + n[2] * DV[q.dir][2] < 0)
            for (float& c : n) c = -c;
        float l1 = std::sqrt(e1[0] * e1[0] + e1[1] * e1[1] + e1[2] * e1[2]);
        float l2 = std::sqrt(e2[0] * e2[0] + e2[1] * e2[1] + e2[2] * e2[2]);
        int n1 = std::clamp(int(std::ceil(l1 * 3.0f)), 2, 96), n2 = std::clamp(int(std::ceil(l2 * 3.0f)), 2, 96);
        float tr = float((q.tint >> 16) & 255) / 255.0f, tg = float((q.tint >> 8) & 255) / 255.0f, tb = float(q.tint & 255) / 255.0f;
        for (int j = 0; j < n2; j++)
            for (int i = 0; i < n1; i++) {
                float s = (float(i) + 0.5f) / float(n1), t = (float(j) + 0.5f) / float(n2);
                float w00 = (1 - s) * (1 - t), w10 = s * (1 - t), w11 = s * t, w01 = (1 - s) * t;
                float p[3], uv[2];
                for (int k = 0; k < 3; k++) p[k] = P[0][k] * w00 + P[1][k] * w10 + P[2][k] * w11 + P[3][k] * w01;
                for (int k = 0; k < 2; k++) uv[k] = q.uv[0][k] * w00 + q.uv[1][k] * w10 + q.uv[2][k] * w11 + q.uv[3][k] * w01;
                int px = std::clamp(int(std::floor(uv[0] * float(sp.width))), 0, int(sp.width) - 1);
                int py = std::clamp(int(std::floor(uv[1] * float(sp.height))), 0, int(sp.height) - 1);
                uint32_t c = texels[f0 + uint32_t(py) * sp.width + uint32_t(px)];
                if ((c >> 24) < 128) continue;  // a cut-out texel: nothing there
                int vx[3];
                if (!placer.place(p, n, vx)) continue;
                size_t vi = size_t(vx[0] + 16 * (vx[2] + 16 * vx[1])), at = vi * 4;
                if (lastQuad[vi] != int32_t(qi)) {
                    // (an overlay on the same face, a later quad: its texel, not a mean of the two)
                    if (lastQuad[vi] >= 0) acc[at] = acc[at + 1] = acc[at + 2] = acc[at + 3] = 0.0f;
                    lastQuad[vi] = int32_t(qi);
                }
                acc[at] += lin((c >> 16) & 255) * tr;
                acc[at + 1] += lin((c >> 8) & 255) * tg;
                acc[at + 2] += lin(c & 255) * tb;
                acc[at + 3] += 1.0f;
                any = true;
            }
    }
    v.argb.fill(0);
    v.filled.fill(0);
    v.count = 0;
    v.lightRgb = 0;
    if (!any) return false;
    double lr = 0, lg = 0, lb = 0, er = 0, eg = 0, eb = 0;  // (bright texels' colour; every glowing one's, if none is bright)
    for (int i = 0; i < 4096; i++) {
        float w = acc[size_t(i) * 4 + 3];
        if (w <= 0) continue;
        float rgb[3];
        uint32_t c8[3];
        for (int k = 0; k < 3; k++) {
            rgb[k] = acc[size_t(i) * 4 + size_t(k)] / w;
            c8[k] = uint32_t(std::clamp(std::pow(rgb[k], 1.0f / 2.2f) * 255.0f + 0.5f, 0.0f, 255.0f));
        }
        float lum = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2];
        float s = std::clamp((lum - 0.35f) / 0.45f, 0.0f, 1.0f);
        float e = emission > 0 ? std::clamp(emission * (0.25f + 0.75f * s * s * (3 - 2 * s)), 0.0f, 1.0f) : 0.0f;  // (nearEmission's weights)
        v.argb[size_t(i)] = uint32_t(e * 255.0f + 0.5f) << 24 | c8[0] << 16 | c8[1] << 8 | c8[2];
        float w8 = emission > 0 ? s * s * (3 - 2 * s) : 0.0f;  // (the light's colour: its bright texels', a flame's, not the stick's)
        lr += w8 * rgb[0], lg += w8 * rgb[1], lb += w8 * rgb[2];
        er += e * rgb[0], eg += e * rgb[1], eb += e * rgb[2];
        v.filled[size_t(i)] = 1;
        v.count++;
    }
    if (std::max(lr, std::max(lg, lb)) < 1e-6) lr = er, lg = eg, lb = eb;
    // its coverage: what the outside's empty micro-voxels do not reach (bounded: one pass a voxel)
    {
        std::vector<uint8_t> out(4096, 0);
        std::vector<uint16_t> stack;
        stack.reserve(4096);
        auto push = [&](int x, int y, int z) {
            if (x < 0 || y < 0 || z < 0 || x > 15 || y > 15 || z > 15) return;
            size_t i = size_t(x + 16 * (z + 16 * y));
            if (out[i] || v.filled[i]) return;
            out[i] = 1;
            stack.push_back(uint16_t(i));
        };
        for (int a = 0; a < 16; a++)
            for (int b = 0; b < 16; b++) {
                push(a, b, 0), push(a, b, 15), push(a, 0, b), push(a, 15, b), push(0, a, b), push(15, a, b);
            }
        size_t reached = 0;
        while (!stack.empty()) {
            int i = stack.back();
            stack.pop_back();
            reached++;
            int x = i & 15, z = (i >> 4) & 15, y = i >> 8;
            push(x - 1, y, z), push(x + 1, y, z), push(x, y - 1, z), push(x, y + 1, z), push(x, y, z - 1), push(x, y, z + 1);
        }
        v.coverage = float(4096 - reached) / 4096.0f;
    }
    double mx = std::max(lr, std::max(lg, lb));
    if (mx > 1e-6) {
        auto q = [&](double x) { return uint32_t(std::clamp(std::pow(x / mx, 1.0 / 2.2) * 255.0 + 0.5, 0.0, 255.0)); };
        v.lightRgb = q(lr) << 16 | q(lg) << 8 | q(lb);
    }
    return true;
}

std::vector<uint32_t> encodeBrick(const BrickVoxels& v) {
    // the palette: every distinct ARGB; past 256, colours at 5 bits a channel, then the 256 commonest and the nearest
    std::map<uint32_t, uint32_t> freq;
    for (int i = 0; i < 4096; i++)
        if (v.filled[size_t(i)]) freq[v.argb[size_t(i)]]++;
    auto quant = [](uint32_t c) { return c & 0xFFF8F8F8u; };
    std::vector<uint32_t> pal;
    std::unordered_map<uint32_t, uint8_t> index;
    bool coarse = freq.size() > 256;
    if (coarse) {
        std::map<uint32_t, uint32_t> qf;
        for (auto& [c, n] : freq) qf[quant(c)] += n;
        std::vector<std::pair<uint32_t, uint32_t>> byN(qf.begin(), qf.end());
        std::sort(byN.begin(), byN.end(), [](auto& a, auto& b) { return a.second > b.second || (a.second == b.second && a.first < b.first); });
        for (size_t k = 0; k < byN.size() && k < 256; k++) pal.push_back(byN[k].first);
    } else {
        for (auto& kv : freq) pal.push_back(kv.first);
    }
    auto nearest = [&](uint32_t c) -> uint8_t {
        auto it = index.find(c);
        if (it != index.end()) return it->second;
        int best = 0, bd = INT32_MAX;
        for (size_t k = 0; k < pal.size(); k++) {
            int d = 0;
            for (int s = 0; s < 32; s += 8) {
                int a = int((c >> s) & 255), b = int((pal[k] >> s) & 255);
                d += (a - b) * (a - b);
            }
            if (d < bd) bd = d, best = int(k);
        }
        index.emplace(c, uint8_t(best));
        return uint8_t(best);
    };
    uint64_t gmask = 0;
    std::array<uint64_t, 64> occ{};
    for (int y = 0; y < 16; y++)
        for (int z = 0; z < 16; z++)
            for (int x = 0; x < 16; x++)
                if (v.filled[size_t(x + 16 * (z + 16 * y))]) {
                    int g = (x >> 2) + 4 * ((z >> 2) + 4 * (y >> 2));
                    gmask |= 1ull << g;
                    occ[size_t(g)] |= 1ull << ((x & 3) + 4 * ((z & 3) + 4 * (y & 3)));
                }
    uint32_t ng = uint32_t(std::popcount(gmask));
    std::vector<uint32_t> rec(4 + 3 * size_t(ng), 0u);
    rec[0] = uint32_t(gmask), rec[1] = uint32_t(gmask >> 32);
    std::vector<uint8_t> idx;
    idx.reserve(v.count);
    uint32_t r = 0;
    for (int g = 0; g < 64; g++) {
        if (!(gmask >> g & 1)) continue;
        rec[4 + 3 * size_t(r)] = uint32_t(occ[size_t(g)]);
        rec[5 + 3 * size_t(r)] = uint32_t(occ[size_t(g)] >> 32);
        rec[6 + 3 * size_t(r)] = uint32_t(idx.size());
        int gx = (g & 3) * 4, gz = ((g >> 2) & 3) * 4, gy = (g >> 4) * 4;
        for (int bit = 0; bit < 64; bit++) {
            if (!(occ[size_t(g)] >> bit & 1)) continue;
            int x = gx + (bit & 3), z = gz + ((bit >> 2) & 3), y = gy + (bit >> 4);
            uint32_t c = v.argb[size_t(x + 16 * (z + 16 * y))];
            idx.push_back(coarse ? nearest(quant(c)) : nearest(c));
        }
        r++;
    }
    for (size_t k = 0; k < idx.size(); k += 4) {
        uint32_t w = 0;
        for (size_t j = 0; j < 4 && k + j < idx.size(); j++) w |= uint32_t(idx[k + j]) << (8 * j);
        rec.push_back(w);
    }
    rec[2] = ng | uint32_t(pal.size()) << 16;
    rec[3] = uint32_t(rec.size());
    rec.insert(rec.end(), pal.begin(), pal.end());
    return rec;
}

std::vector<uint32_t> crossRecord(const AtlasBrickQuad* quads, uint32_t quadCount, uint32_t m, const AtlasBrickSprite* sprites,
                                  uint32_t spriteCount, const uint32_t* texels, uint32_t texelCount, uint32_t frame) {
    // the quads off the axes (a cross's diagonals, a coral fan's tilt, a fire's leaning sheets, a custom plant's
    // turned planes) by their plane; the axis-aligned rest stays voxels (a pot, a stem's box)
    struct Plane {
        float n[3], d;
        std::vector<const AtlasBrickQuad*> qs;
    };
    std::vector<Plane> planes;
    std::vector<AtlasBrickQuad> rest;
    // an axis-aligned quad with no volume behind it: another quad of the material faces the other way on the same plane
    // over the same rectangle (a zero-thickness element's two faces: a crop's four planes, a ladder, a rail, a vine,
    // redstone dust). Voxelised it was a slab 1/16 thick, a crop a stack of chunky slabs up close: a sheet like a cross's
    auto axisOf = [](const AtlasBrickQuad& q, float& at, float lo[2], float hi[2]) {
        int a = -1;
        for (int k = 0; k < 3 && a < 0; k++)
            if (std::fabs(q.pos[0][k] - q.pos[1][k]) < 1e-4f && std::fabs(q.pos[0][k] - q.pos[2][k]) < 1e-4f &&
                std::fabs(q.pos[0][k] - q.pos[3][k]) < 1e-4f)
                a = k;
        if (a < 0) return -1;
        at = q.pos[0][a];
        for (int j = 0; j < 2; j++) {
            int k = (a + 1 + j) % 3;
            lo[j] = std::min(std::min(q.pos[0][k], q.pos[1][k]), std::min(q.pos[2][k], q.pos[3][k]));
            hi[j] = std::max(std::max(q.pos[0][k], q.pos[1][k]), std::max(q.pos[2][k], q.pos[3][k]));
        }
        return a;
    };
    auto zeroThick = [&](uint32_t qi) {
        float at, lo[2], hi[2];
        int a = axisOf(quads[qi], at, lo, hi);
        if (a < 0 || quads[qi].dir < 0 || quads[qi].dir > 5) return false;
        for (uint32_t oj = 0; oj < quadCount; oj++) {
            const AtlasBrickQuad& o = quads[oj];
            if (oj == qi || o.material != m || o.dir < 0 || o.dir > 5 || (o.dir ^ 1) != quads[qi].dir) continue;  // (dir pairs: 0/1, 2/3, 4/5)
            float at2, lo2[2], hi2[2];
            if (axisOf(o, at2, lo2, hi2) != a || std::fabs(at2 - at) > 1e-3f) continue;
            if (std::fabs(lo2[0] - lo[0]) < 1e-3f && std::fabs(hi2[0] - hi[0]) < 1e-3f && std::fabs(lo2[1] - lo[1]) < 1e-3f &&
                std::fabs(hi2[1] - hi[1]) < 1e-3f)
                return true;
        }
        return false;
    };
    for (uint32_t qi = 0; qi < quadCount; qi++) {
        const AtlasBrickQuad& q = quads[qi];
        if (q.material != m) continue;
        float e1[3], e2[3], n[3];
        for (int k = 0; k < 3; k++) e1[k] = q.pos[1][k] - q.pos[0][k], e2[k] = q.pos[3][k] - q.pos[0][k];
        n[0] = e1[1] * e2[2] - e1[2] * e2[1], n[1] = e1[2] * e2[0] - e1[0] * e2[2], n[2] = e1[0] * e2[1] - e1[1] * e2[0];
        float nl = std::sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
        if (nl < 1e-8f) continue;
        for (float& v : n) v /= nl;
        if (std::max(std::fabs(n[0]), std::max(std::fabs(n[1]), std::fabs(n[2]))) > 0.999f && !zeroThick(qi)) {
            rest.push_back(q);
            continue;
        }
        // (one sign for a plane: its first non-zero component positive; a two-sided sheet is one plane)
        int lead = std::fabs(n[0]) > 1e-4f ? 0 : std::fabs(n[1]) > 1e-4f ? 1 : 2;
        if (n[lead] < 0) for (float& v : n) v = -v;
        float d = n[0] * q.pos[0][0] + n[1] * q.pos[0][1] + n[2] * q.pos[0][2];
        Plane* hit = nullptr;
        for (Plane& p : planes)
            if (p.n[0] * n[0] + p.n[1] * n[1] + p.n[2] * n[2] > 0.999f && std::fabs(p.d - d) < 2e-3f) hit = &p;
        if (!hit) {
            if (planes.size() >= 4) return {};  // (more than four sheets: voxels)
            planes.push_back({{n[0], n[1], n[2]}, d, {}});
            hit = &planes.back();
        }
        hit->qs.push_back(&q);
    }
    if (planes.empty()) return {};
    // [0] planes | rest << 8, [1] the rest's voxel record (from c), then per plane 13 floats (n, d, its rectangle's
    // corner O and sides U, V) and 16 x 16 cells over the rectangle
    const size_t PW = 13 + 256;
    std::vector<uint32_t> rec(2 + planes.size() * PW, 0u);
    rec[0] = uint32_t(planes.size()) | (rest.empty() ? 0u : 1u << 8);
    auto put = [&](size_t at, float v) { std::memcpy(&rec[at], &v, 4); };
    for (size_t k = 0; k < planes.size(); k++) {
        const Plane& pl = planes[k];
        // an in-plane basis: U across (horizontal where it can), V = n x U
        float U[3], V[3];
        if (std::fabs(pl.n[1]) < 0.99f) {
            U[0] = pl.n[2], U[1] = 0, U[2] = -pl.n[0];
        } else {
            U[0] = 1, U[1] = 0, U[2] = 0;
        }
        float ul = std::sqrt(U[0] * U[0] + U[1] * U[1] + U[2] * U[2]);
        for (float& v : U) v /= ul;
        V[0] = pl.n[1] * U[2] - pl.n[2] * U[1], V[1] = pl.n[2] * U[0] - pl.n[0] * U[2], V[2] = pl.n[0] * U[1] - pl.n[1] * U[0];
        float s0 = 1e9f, s1 = -1e9f, t0 = 1e9f, t1 = -1e9f;
        for (const AtlasBrickQuad* q : pl.qs)
            for (int i = 0; i < 4; i++) {
                float ss = q->pos[i][0] * U[0] + q->pos[i][1] * U[1] + q->pos[i][2] * U[2];
                float tt = q->pos[i][0] * V[0] + q->pos[i][1] * V[1] + q->pos[i][2] * V[2];
                s0 = std::min(s0, ss), s1 = std::max(s1, ss), t0 = std::min(t0, tt), t1 = std::max(t1, tt);
            }
        s1 = std::max(s1, s0 + 1e-3f), t1 = std::max(t1, t0 + 1e-3f);
        float O[3], Ue[3], Ve[3];
        for (int i = 0; i < 3; i++) O[i] = s0 * U[i] + t0 * V[i] + pl.d * pl.n[i], Ue[i] = (s1 - s0) * U[i], Ve[i] = (t1 - t0) * V[i];
        size_t base = 2 + k * PW;
        for (int i = 0; i < 3; i++) put(base + size_t(i), pl.n[i]), put(base + 4 + size_t(i), O[i]), put(base + 7 + size_t(i), Ue[i]),
                                     put(base + 10 + size_t(i), Ve[i]);
        put(base + 3, pl.d);
        for (int j = 0; j < 16; j++)
            for (int i = 0; i < 16; i++) {
                float sc = s0 + (float(i) + 0.5f) / 16.0f * (s1 - s0), tc = t0 + (float(j) + 0.5f) / 16.0f * (t1 - t0);
                for (const AtlasBrickQuad* qp : pl.qs) {
                    const AtlasBrickQuad& q = *qp;
                    float c2[4][2];
                    for (int v = 0; v < 4; v++) {
                        c2[v][0] = q.pos[v][0] * U[0] + q.pos[v][1] * U[1] + q.pos[v][2] * U[2];
                        c2[v][1] = q.pos[v][0] * V[0] + q.pos[v][1] * V[1] + q.pos[v][2] * V[2];
                    }
                    float e1x = c2[1][0] - c2[0][0], e1y = c2[1][1] - c2[0][1], e3x = c2[3][0] - c2[0][0], e3y = c2[3][1] - c2[0][1];
                    float det = e1x * e3y - e1y * e3x;
                    if (std::fabs(det) < 1e-8f) continue;
                    float rx = sc - c2[0][0], ry = tc - c2[0][1];
                    float st = (rx * e3y - ry * e3x) / det, tt = (e1x * ry - e1y * rx) / det;
                    if (st < -1e-4f || st > 1.0001f || tt < -1e-4f || tt > 1.0001f) continue;
                    float u = q.uv[0][0] + st * (q.uv[1][0] - q.uv[0][0]) + tt * (q.uv[3][0] - q.uv[0][0]);
                    float v = q.uv[0][1] + st * (q.uv[1][1] - q.uv[0][1]) + tt * (q.uv[3][1] - q.uv[0][1]);
                    if (q.sprite >= spriteCount) continue;
                    const AtlasBrickSprite& sp = sprites[q.sprite];
                    uint32_t nf = std::max(1u, sp.frames & 0xFFFFu);
                    if (!sp.width || !sp.height || uint64_t(sp.firstTexel) + uint64_t(sp.width) * sp.height * nf > texelCount) continue;
                    uint32_t f0 = sp.firstTexel + (frame % nf) * sp.width * sp.height;
                    int sx = std::clamp(int(std::floor(u * float(sp.width))), 0, int(sp.width) - 1);
                    int sy = std::clamp(int(std::floor(v * float(sp.height))), 0, int(sp.height) - 1);
                    uint32_t cc = texels[f0 + uint32_t(sy) * sp.width + uint32_t(sx)];
                    if ((cc >> 24) < 128) continue;
                    float tr = float((q.tint >> 16) & 255) / 255.0f, tg = float((q.tint >> 8) & 255) / 255.0f, tb = float(q.tint & 255) / 255.0f;
                    uint32_t r = uint32_t(float((cc >> 16) & 255) * tr + 0.5f), g = uint32_t(float((cc >> 8) & 255) * tg + 0.5f),
                             bl = uint32_t(float(cc & 255) * tb + 0.5f);
                    rec[base + 13 + size_t(i + 16 * j)] = 0xFF000000u | r << 16 | g << 8 | bl;
                }
            }
    }
    if (!rest.empty()) {
        // the axis-aligned rest as voxels (a pot under its plant), after the planes
        BrickVoxels v;
        for (auto& q : rest) q.material = m;
        if (voxeliseBrick(rest.data(), uint32_t(rest.size()), m, sprites, spriteCount, texels, texelCount, 0.0f, v, frame)) {
            std::vector<uint32_t> vr = encodeBrick(v);
            rec[1] = uint32_t(rec.size());
            rec.insert(rec.end(), vr.begin(), vr.end());
        } else {
            rec[0] &= 0xFFu;
        }
    }
    return rec;
}

void BrickStore::setCross(uint32_t m, std::shared_ptr<const std::vector<uint32_t>> record) {
    std::lock_guard<std::mutex> lock(mu_);
    if (m >= cross_.size()) {
        if (!record) return;
        cross_.resize(size_t(m) + 1);
    }
    if (cross_[m] == record) return;
    cross_[m] = std::move(record);
    revision_++;
}

void BrickStore::setOffset(uint32_t m, uint32_t word) {
    std::lock_guard<std::mutex> lock(mu_);
    if (m >= offset_.size()) {
        if (!word) return;
        offset_.resize(size_t(m) + 1, 0u);
    }
    if (offset_[m] == word) return;
    offset_[m] = word;
    revision_++;
}

void BrickStore::setCrossFrames(uint32_t m, std::vector<std::shared_ptr<const std::vector<uint32_t>>> frames, uint32_t ticks) {
    std::lock_guard<std::mutex> lock(mu_);
    if (m >= cross_.size()) cross_.resize(size_t(m) + 1);
    if (m >= crossFrames_.size()) crossFrames_.resize(size_t(m) + 1), crossTicks_.resize(size_t(m) + 1, 1u);
    cross_[m] = frames.empty() ? nullptr : frames[0];
    crossFrames_[m] = std::move(frames);
    crossTicks_[m] = std::max(1u, ticks);
    revision_++;
}

void BrickStore::set(uint32_t m, std::shared_ptr<const std::vector<uint32_t>> record, uint32_t lightRgb) {
    std::lock_guard<std::mutex> lock(mu_);
    if (m >= byMat_.size()) {
        if (!record) return;
        byMat_.resize(size_t(m) + 1);
        lightRgb_.resize(size_t(m) + 1, 0u);
    }
    if (byMat_[m] == record && lightRgb_[m] == lightRgb && (m >= frames_.size() || frames_[m].empty())) return;
    byMat_[m] = std::move(record);
    lightRgb_[m] = lightRgb;
    if (m < frames_.size()) frames_[m].clear();
    revision_++;
}

void BrickStore::setFrames(uint32_t m, std::vector<std::shared_ptr<const std::vector<uint32_t>>> frames, uint32_t ticks,
                           uint32_t lightRgb) {
    std::lock_guard<std::mutex> lock(mu_);
    if (m >= byMat_.size()) byMat_.resize(size_t(m) + 1), lightRgb_.resize(size_t(m) + 1, 0u);
    if (m >= frames_.size()) frames_.resize(size_t(m) + 1), ticks_.resize(size_t(m) + 1, 1u);
    byMat_[m] = frames.empty() ? nullptr : frames[0];
    frames_[m] = std::move(frames);
    ticks_[m] = std::max(1u, ticks);
    lightRgb_[m] = lightRgb;
    revision_++;
}

void BrickStore::setCoverage(uint32_t m, int level) {
    std::lock_guard<std::mutex> lock(mu_);
    if (m >= coverage_.size()) {
        if (level < 0) return;
        coverage_.resize(size_t(m) + 1, int8_t(-1));
    }
    coverage_[m] = int8_t(std::clamp(level, -1, 63));
}

int BrickStore::coverage(uint32_t m) const {
    std::lock_guard<std::mutex> lock(mu_);
    return m < coverage_.size() ? coverage_[m] : -1;
}

uint32_t BrickStore::lightRgb(uint32_t m) const {
    std::lock_guard<std::mutex> lock(mu_);
    return m < lightRgb_.size() ? lightRgb_[m] : 0u;
}

std::shared_ptr<const std::vector<uint32_t>> BrickStore::intern(std::vector<uint32_t> rec) {
    uint64_t h = 0xcbf29ce484222325ull;
    for (uint32_t w : rec) h = (h ^ w) * 0x100000001b3ull;
    std::lock_guard<std::mutex> lock(mu_);
    auto& same = unique_[h];
    for (auto& s : same)
        if (*s == rec) return s;
    auto p = std::make_shared<const std::vector<uint32_t>>(std::move(rec));
    same.push_back(p);
    return p;
}

uint64_t BrickStore::revision() const {
    std::lock_guard<std::mutex> lock(mu_);
    return revision_;
}

void BrickStore::stats(uint32_t& voxels, uint32_t& sheets, uint32_t& table) const {
    std::lock_guard<std::mutex> lock(mu_);
    voxels = sheets = 0;
    for (const auto& r : byMat_) voxels += r ? 1u : 0u;
    for (const auto& r : cross_) sheets += r ? 1u : 0u;
    table = uint32_t(std::max(std::max(byMat_.size(), cross_.size()), offset_.size()));
}

size_t BrickStore::bricks() const {
    std::lock_guard<std::mutex> lock(mu_);
    size_t n = 0;
    for (auto& [h, v] : unique_) n += v.size();
    return n;
}

bool BrickStore::pack(uint64_t have, bool force, const std::vector<uint32_t>& lights, std::vector<uint32_t>& out,
                      uint64_t& revision) const {
    std::lock_guard<std::mutex> lock(mu_);
    if (have == revision_ && !force && !out.empty()) return false;
    revision = revision_;
    uint32_t M = uint32_t(std::max(std::max(std::max(byMat_.size(), lights.size()), cross_.size()), offset_.size()));
    out.assign(1 + 3 * size_t(M), 0u);
    out[0] = M;
    std::copy(lights.begin(), lights.end(), out.begin() + 1 + M);
    std::copy(offset_.begin(), offset_.end(), out.begin() + 1 + 2 * size_t(M));
    std::unordered_map<const std::vector<uint32_t>*, uint32_t> at;
    auto place = [&](const std::shared_ptr<const std::vector<uint32_t>>& r) {
        auto it = at.find(r.get());
        if (it == at.end()) {
            it = at.emplace(r.get(), uint32_t(out.size())).first;
            out.insert(out.end(), r->begin(), r->end());
        }
        return it->second;
    };
    for (uint32_t m = 0; m < M; m++) {
        if (m < crossFrames_.size() && crossFrames_[m].size() > 1) {
            // animated sheets: a set (frames | ticks << 16) of flagged sheet records (brickOf picks, brickHit sees bit 30)
            std::vector<uint32_t> offs;
            for (const auto& r : crossFrames_[m]) offs.push_back(0x40000000u | place(r));
            uint32_t set = uint32_t(out.size());
            out.push_back(uint32_t(offs.size()) | std::min(crossTicks_[m], 0xFFFFu) << 16);
            out.insert(out.end(), offs.begin(), offs.end());
            out[1 + m] = 0x80000000u | set;
            continue;
        }
        if (m < cross_.size() && cross_[m]) {
            out[1 + m] = 0x40000000u | place(cross_[m]);  // (sheets: their planes, atlas_bricks.slang sheetsHit)
            continue;
        }
        if (m >= byMat_.size()) continue;
        if (m < frames_.size() && frames_[m].size() > 1) {
            // an animated set: frames | ticks a frame << 16, then each frame's brick
            std::vector<uint32_t> offs;
            for (const auto& r : frames_[m]) offs.push_back(place(r));
            uint32_t set = uint32_t(out.size());
            out.push_back(uint32_t(offs.size()) | std::min(ticks_[m], 0xFFFFu) << 16);
            out.insert(out.end(), offs.begin(), offs.end());
            out[1 + m] = 0x80000000u | set;
            continue;
        }
        const auto& r = byMat_[m];
        if (r) out[1 + m] = place(r);
    }
    return true;
}

}  // namespace atlas
