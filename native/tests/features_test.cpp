// The decoration's offline check (docs/ATLAS-CLIENT-GENERATION.md stage 4): a dump written by AtlasGenFeaturesGpuTest
// (the feature program, the terrain slots, each origin's decoration in isolation by Java's own feature code, each
// target's decoration of its 3 x 3 in Java's order) against atlas_feat_run on the C++ build and on Vulkan.
//
//   atlas_feat_test <dump> [cpu|vulkan|both] [report.txt]
//
// Reports per backend: the isolated writes (Java's changed blocks against the kernels', by block pair and by the
// feature program's flags), the targets' blocks (all, and those Java's decoration changed), and the times.
#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <map>
#include <string>
#include <unordered_map>
#include <vector>

#include "atlas/atlas.h"

namespace {
constexpr uint32_t CHUNK_W = ATLAS_FEAT_CHUNK_WORDS, AUX_W = ATLAS_FEAT_AUX_WORDS, TABLE_W = ATLAS_FEAT_TABLE_WORDS;
constexpr uint32_t ORIGIN_W = 28, TARGET_W = 12;

struct Reader {
    std::vector<uint8_t> d;
    size_t p = 0;
    bool ok = true;
    int32_t i() {
        if (p + 4 > d.size()) {
            ok = false;
            return 0;
        }
        int32_t v;
        std::memcpy(&v, d.data() + p, 4);
        p += 4;
        return v;
    }
    std::string s() {
        int32_t n = i();
        if (n < 0 || p + size_t(n) > d.size()) {
            ok = false;
            return {};
        }
        std::string r(reinterpret_cast<const char*>(d.data() + p), size_t(n));
        p += size_t(n);
        return r;
    }
    void words(uint32_t* out, size_t n) {
        if (p + n * 4 > d.size()) {
            ok = false;
            return;
        }
        std::memcpy(out, d.data() + p, n * 4);
        p += n * 4;
    }
    // 44 sections: kind 0 air, 1 uniform (id), 2 full (2048 words)
    void sections(uint32_t* out) {
        for (uint32_t s = 0; s < 44; s++) {
            int32_t kind = i();
            uint32_t* w = out + size_t(s) * 2048;
            if (kind == 0) std::fill(w, w + 2048, 0u);
            else if (kind == 1) {
                uint32_t id = uint32_t(i()) & 0xFFFFu;
                std::fill(w, w + 2048, id | (id << 16));
            } else words(w, 2048);
        }
    }
};

double secsSince(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
}
// the program with only the records of one feature type in its dispatch list (the per-type check)
std::vector<uint32_t> onlyType(const std::vector<uint32_t>& prog, uint32_t type) {
    std::vector<uint32_t> p = prog;
    uint32_t at = p[19], n = p[20], k = 0;
    for (uint32_t i = 0; i < n; i++) {
        uint32_t rec = prog[at + i];
        if (prog[rec] == type) p[at + k++] = rec;
    }
    p[20] = k;
    return p;
}

uint32_t idAt(const uint32_t* chunk, uint32_t b) {
    uint32_t w = chunk[b >> 1];
    return (b & 1u) ? (w >> 16) : (w & 0xFFFFu);
}
}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: atlas_feat_test <dump> [cpu|vulkan|both] [report]\n");
        return 2;
    }
    std::string which = argc > 2 ? argv[2] : "both";
    FILE* f = std::fopen(argv[1], "rb");
    if (!f) {
        std::fprintf(stderr, "cannot open %s\n", argv[1]);
        return 2;
    }
    Reader r;
    std::fseek(f, 0, SEEK_END);
    r.d.resize(size_t(std::ftell(f)));
    std::fseek(f, 0, SEEK_SET);
    if (std::fread(r.d.data(), 1, r.d.size(), f) != r.d.size()) return 2;
    std::fclose(f);
    std::printf("TRACE begin load\n");
    int version = 0;
    if (r.i() != 0x504D4446 || (version = r.i()) < 1 || version > 2) {
        std::fprintf(stderr, "not a feature dump\n");
        return 2;
    }
    std::vector<uint32_t> prog(size_t(r.i()));
    r.words(prog.data(), prog.size());
    std::vector<std::string> names(size_t(r.i()));
    for (auto& n : names) n = r.s();
    std::vector<std::string> dispatched(size_t(r.i()));
    for (auto& n : dispatched) n = r.s();
    uint32_t slots = uint32_t(r.i());
    std::vector<uint32_t> ids(size_t(slots) * CHUNK_W), aux(size_t(slots) * AUX_W, 0u);
    std::vector<int32_t> slotXZ(size_t(slots) * 2);
    for (uint32_t s = 0; s < slots; s++) {
        slotXZ[s * 2] = r.i();
        slotXZ[s * 2 + 1] = r.i();
        r.sections(ids.data() + size_t(s) * CHUNK_W);
        r.words(aux.data() + size_t(s) * AUX_W, 1928);
    }
    uint32_t origins = uint32_t(r.i());
    std::vector<int32_t> org(size_t(origins) * ORIGIN_W);
    std::vector<std::unordered_map<uint32_t, uint32_t>> javaIso(origins);
    for (uint32_t o = 0; o < origins; o++) {
        r.words(reinterpret_cast<uint32_t*>(org.data() + size_t(o) * ORIGIN_W), ORIGIN_W);
        int32_t n = r.i();
        for (int32_t k = 0; k < n; k++) {
            uint32_t key = uint32_t(r.i()), id = uint32_t(r.i());
            javaIso[o][key] = id;
        }
    }
    uint32_t targets = uint32_t(r.i());
    std::vector<int32_t> tgt(size_t(targets) * TARGET_W);
    std::vector<uint32_t> javaSeq(size_t(targets) * CHUNK_W);
    for (uint32_t t = 0; t < targets; t++) {
        r.words(reinterpret_cast<uint32_t*>(tgt.data() + size_t(t) * TARGET_W), TARGET_W);
        r.sections(javaSeq.data() + size_t(t) * CHUNK_W);
    }
    // (version 2) each feature type alone: type, name, then per origin its changed blocks
    struct TypeRef {
        uint32_t type;
        std::string name;
        std::vector<std::unordered_map<uint32_t, uint32_t>> iso;
    };
    std::vector<TypeRef> types;
    if (version >= 2) {
        types.resize(size_t(r.i()));
        for (auto& tr : types) {
            tr.type = uint32_t(r.i());
            tr.name = r.s();
            tr.iso.resize(origins);
            for (uint32_t o = 0; o < origins; o++) {
                int32_t n = r.i();
                for (int32_t k = 0; k < n; k++) {
                    uint32_t key = uint32_t(r.i()), id = uint32_t(r.i());
                    tr.iso[o][key] = id;
                }
            }
        }
    }
    if (!r.ok) {
        std::fprintf(stderr, "truncated dump\n");
        return 2;
    }
    // ATLAS_FEAT_ORIGIN0=<k>: leave out the first k origins (no targets); ATLAS_FEAT_ORIGINS=<n>: only n origins (a quick
    // check, or a bisection)
    if (const char* o0 = std::getenv("ATLAS_FEAT_ORIGIN0")) {
        uint32_t k = std::min<uint32_t>(uint32_t(std::atoi(o0)), origins);
        org.erase(org.begin(), org.begin() + size_t(k) * ORIGIN_W);
        javaIso.erase(javaIso.begin(), javaIso.begin() + k);
        for (auto& tr : types) tr.iso.erase(tr.iso.begin(), tr.iso.begin() + k);
        origins -= k;
        targets = 0;
    }
    if (const char* on = std::getenv("ATLAS_FEAT_ORIGINS")) {
        uint32_t n = uint32_t(std::atoi(on));
        if (n > 0 && n < origins) {
            origins = n;
            targets = 0;
        }
    }
    std::printf("TRACE end load\n");
    std::fflush(stdout);
    std::string report;
    char line[512];
    std::snprintf(line, sizeof(line), "dump: %zu program words, %zu blocks, %zu features dispatched, %u slots, %u origins, %u targets\n",
                  prog.size(), names.size(), dispatched.size(), slots, origins, targets);
    report += line;
    auto name = [&](uint32_t id) { return id < names.size() ? names[id] : "#" + std::to_string(id); };
    // the terrain id at an origin's table key
    auto terrainAt = [&](uint32_t o, uint32_t key) -> uint32_t {
        uint32_t li = key - 1;
        int lx = int(li % 48), lz = int((li / 48) % 48), ly = int(li / 2304);
        int dcx = lx / 16 - 1, dcz = lz / 16 - 1;
        int slot = org[size_t(o) * ORIGIN_W + 2 + size_t((dcz + 2) * 5 + (dcx + 2))];
        if (slot < 0) return 0;
        uint32_t b = uint32_t(lx & 15) + 16u * uint32_t(lz & 15) + 256u * uint32_t(ly);
        return idAt(ids.data() + size_t(slot) * CHUNK_W, b);
    };
    struct Iso {
        uint64_t same = 0, onlyJava = 0, onlyGpu = 0, differ = 0, flagged = 0;
        std::map<std::string, uint64_t> pairs;
        std::map<uint32_t, uint64_t> flags;
    };
    auto compareIso = [&](const std::vector<uint32_t>& tables, const std::vector<std::unordered_map<uint32_t, uint32_t>>& java) {
        Iso c;
        for (uint32_t o = 0; o < origins; o++) {
            const uint32_t* tab = tables.data() + size_t(o) * TABLE_W;
            if (tab[1]) {
                c.flagged++;
                c.flags[tab[1]]++;
            }
            std::unordered_map<uint32_t, uint32_t> gpu;
            for (uint32_t sl = 0; sl < 32768; sl++) {
                uint32_t key = tab[16 + sl * 2];
                if (!key) continue;
                uint32_t id = tab[16 + sl * 2 + 1];
                if (id != terrainAt(o, key)) gpu[key] = id;
            }
            for (auto& [key, id] : java[o]) {
                auto it = gpu.find(key);
                if (it == gpu.end()) {
                    c.onlyJava++;
                    c.pairs[name(id) + " -> (terrain " + name(terrainAt(o, key)) + ")"]++;
                } else if (it->second == id) c.same++;
                else {
                    c.differ++;
                    c.pairs[name(id) + " -> " + name(it->second)]++;
                }
            }
            for (auto& [key, id] : gpu)
                if (!java[o].count(key)) {
                    c.onlyGpu++;
                    c.pairs["(terrain " + name(terrainAt(o, key)) + ") -> " + name(id)]++;
                }
        }
        return c;
    };
    int rc = 0;
    for (int backend = 0; backend < 2; backend++) {
        if ((backend == 0 && which == "vulkan") || (backend == 1 && which == "cpu")) continue;
        AtlasFeat* h = nullptr;
        if (atlas_feat_create(backend ? ATLAS_BACKEND_VULKAN : ATLAS_BACKEND_CPU, &h) != ATLAS_OK) {
            report += backend ? "vulkan: unavailable\n" : "cpu: unavailable\n";
            continue;
        }
        if (atlas_feat_set_program(h, prog.data(), uint32_t(prog.size())) != ATLAS_OK) {
            std::fprintf(stderr, "set_program: %s\n", atlas_feat_last_error(h));
            return 1;
        }
        const char* bname = backend ? "vulkan" : "cpu";
        std::printf("TRACE begin gpu-run:%s\n", bname);
        std::fflush(stdout);
        auto g0 = std::chrono::steady_clock::now();
        std::vector<uint32_t> out(size_t(targets) * CHUNK_W), tables(size_t(origins) * TABLE_W);
        AtlasFeatTimes t{}, t2{};
        if (atlas_feat_run(h, slots, ids.data(), aux.data(), origins, org.data(), targets, tgt.data(), out.data(), tables.data(), &t) != ATLAS_OK) {
            std::fprintf(stderr, "run: %s\n", atlas_feat_last_error(h));
            return 1;
        }
        // a second run: the steady cost
        atlas_feat_run(h, slots, ids.data(), aux.data(), origins, org.data(), targets, tgt.data(), out.data(), nullptr, &t2);
        std::snprintf(line, sizeof(line), "\n== %s: kernels %.1f ms (features %.1f, gather %.1f; %.3f ms of features an origin), upload %.1f, readback %.1f, total %.1f (first run %.1f)\n",
                      atlas_feat_device(h), t2.kernelMs, t2.featureMs, t2.gatherMs, t2.featureMs / origins, t2.uploadMs, t2.readbackMs,
                      t2.totalMs, t.totalMs);
        report += line;
        std::printf("TRACE done gpu-run:%s %.1fs\n", bname, secsSince(g0));
        // ---- isolated: every origin's changed blocks ----
        std::printf("TRACE begin compare:%s\n", bname);
        Iso isoAll = compareIso(tables, javaIso);
        uint64_t same = isoAll.same, onlyJava = isoAll.onlyJava, onlyGpu = isoAll.onlyGpu, differ = isoAll.differ, flagged = isoAll.flagged;
        auto& pairs = isoAll.pairs;
        auto& flagCount = isoAll.flags;
        uint64_t javaN = same + onlyJava + differ;
        std::snprintf(line, sizeof(line),
                      "isolated: Java changed %llu blocks; the kernels %llu of them alike (%.4f %%), %llu other, %llu missing; %llu changed only by the kernels; %llu origins flagged\n",
                      (unsigned long long)javaN, (unsigned long long)same, javaN ? 100.0 * same / javaN : 100.0, (unsigned long long)differ,
                      (unsigned long long)onlyJava, (unsigned long long)onlyGpu, (unsigned long long)flagged);
        report += line;
        for (auto& [fl, n] : flagCount) {
            std::snprintf(line, sizeof(line), "  flags %u: %llu origins\n", fl, (unsigned long long)n);
            report += line;
        }
        std::vector<std::pair<uint64_t, std::string>> sorted;
        for (auto& [k, n] : pairs) sorted.push_back({n, k});
        std::sort(sorted.rbegin(), sorted.rend());
        for (size_t i = 0; i < sorted.size() && i < 30; i++) {
            std::snprintf(line, sizeof(line), "  %9llu  %s\n", (unsigned long long)sorted[i].first, sorted[i].second.c_str());
            report += line;
        }
        if (javaN != same || onlyGpu) rc = 1;
        std::printf("TRACE done compare-isolated:%s all-types match %.4f%% (%llu of %llu, %llu extra)\n", bname,
                    javaN ? 100.0 * same / javaN : 100.0, (unsigned long long)same, (unsigned long long)javaN, (unsigned long long)onlyGpu);
        // ---- each feature type alone ----
        for (auto& tr : types) {
            auto p0 = std::chrono::steady_clock::now();
            std::vector<uint32_t> tp = onlyType(prog, tr.type);
            atlas_feat_set_program(h, tp.data(), uint32_t(tp.size()));
            std::vector<uint32_t> tt(size_t(origins) * TABLE_W);
            AtlasFeatTimes ty{};
            atlas_feat_run(h, slots, ids.data(), aux.data(), origins, org.data(), 0, nullptr, nullptr, tt.data(), &ty);
            Iso c = compareIso(tt, tr.iso);
            // ATLAS_FEAT_DIFF=<type name>: the first origins' differing blocks, world positions
            const char* dd = std::getenv("ATLAS_FEAT_DIFF");
            if (dd && tr.name == dd) {
                int shown = 0;
                for (uint32_t o = 0; o < origins && shown < 3; o++) {
                    const uint32_t* tab = tt.data() + size_t(o) * TABLE_W;
                    std::unordered_map<uint32_t, uint32_t> gpu;
                    for (uint32_t sl = 0; sl < 32768; sl++) {
                        uint32_t key = tab[16 + sl * 2];
                        if (key && tab[16 + sl * 2 + 1] != terrainAt(o, key)) gpu[key] = tab[16 + sl * 2 + 1];
                    }
                    std::vector<std::string> lines;
                    int ocx = org[size_t(o) * ORIGIN_W], ocz = org[size_t(o) * ORIGIN_W + 1];
                    auto pos = [&](uint32_t key) {
                        uint32_t li = key - 1;
                        int x = (ocx - 1) * 16 + int(li % 48), z = (ocz - 1) * 16 + int((li / 48) % 48), y = int(li / 2304) - 64;
                        return std::to_string(x) + " " + std::to_string(y) + " " + std::to_string(z);
                    };
                    for (auto& [key, id] : tr.iso[o]) {
                        auto it = gpu.find(key);
                        if (it == gpu.end()) lines.push_back("java only " + pos(key) + " " + name(id));
                        else if (it->second != id) lines.push_back("differ    " + pos(key) + " java " + name(id) + " gpu " + name(it->second));
                        else if (std::getenv("ATLAS_FEAT_DIFF_ALL")) lines.push_back("alike     " + pos(key) + " " + name(id));
                    }
                    for (auto& [key, id] : gpu)
                        if (!tr.iso[o].count(key)) lines.push_back("gpu only  " + pos(key) + " " + name(id));
                    if (lines.empty()) continue;
                    std::sort(lines.begin(), lines.end());
                    report += "    origin " + std::to_string(ocx) + " " + std::to_string(ocz) + ": " + std::to_string(lines.size()) + " differences\n";
                    const char* dn = std::getenv("ATLAS_FEAT_DIFF_N");  // lines per origin (default 60)
                    size_t lim = dn ? size_t(std::atoi(dn)) : 60u;
                    for (size_t i = 0; i < lines.size() && i < lim; i++) report += "      " + lines[i] + "\n";
                    shown++;
                }
            }
            uint64_t n = c.same + c.onlyJava + c.differ;
            std::snprintf(line, sizeof(line), "  type %-7s Java changed %8llu, alike %8llu (%.4f %%), other %llu, missing %llu, extra %llu; flagged %llu; GPU %.2f ms features (%.4f ms an origin)\n",
                          tr.name.c_str(), (unsigned long long)n, (unsigned long long)c.same, n ? 100.0 * c.same / n : 100.0,
                          (unsigned long long)c.differ, (unsigned long long)c.onlyJava, (unsigned long long)c.onlyGpu,
                          (unsigned long long)c.flagged, ty.featureMs, ty.featureMs / origins);
            report += line;
            std::vector<std::pair<uint64_t, std::string>> top;
            for (auto& [k, v] : c.pairs) top.push_back({v, k});
            std::sort(top.rbegin(), top.rend());
            for (size_t i = 0; i < top.size() && i < 8; i++) {
                std::snprintf(line, sizeof(line), "      %9llu  %s\n", (unsigned long long)top[i].first, top[i].second.c_str());
                report += line;
            }
            std::printf("TRACE done type:%s:%s %.1fs match %.4f%% extra %llu\n", bname, tr.name.c_str(), secsSince(p0),
                        n ? 100.0 * c.same / n : 100.0, (unsigned long long)c.onlyGpu);
            std::fflush(stdout);
        }
        atlas_feat_set_program(h, prog.data(), uint32_t(prog.size()));
        // ---- targets: Java's order against the gather ----
        uint64_t all = 0, allSame = 0, changed = 0, changedSame = 0, gpuChanged = 0;
        std::map<std::string, uint64_t> tp;
        for (uint32_t t = 0; t < targets; t++) {
            int slot = tgt[size_t(t) * TARGET_W];
            const uint32_t* ter = ids.data() + size_t(slot) * CHUNK_W;
            const uint32_t* js = javaSeq.data() + size_t(t) * CHUNK_W;
            const uint32_t* gs = out.data() + size_t(t) * CHUNK_W;
            for (uint32_t b = 0; b < 44u * 4096u; b++) {
                uint32_t a = idAt(ter, b), j = idAt(js, b), g = idAt(gs, b);
                all++;
                if (j == g) allSame++;
                else tp[name(j) + " -> " + name(g)]++;
                if (j != a) {
                    changed++;
                    if (j == g) changedSame++;
                }
                if (g != a) gpuChanged++;
            }
        }
        std::snprintf(line, sizeof(line),
                      "targets (Java's order vs the gather): %.5f %% of all blocks; of the %llu blocks Java's decoration changed %.4f %% alike (the kernels changed %llu)\n",
                      all ? 100.0 * allSame / all : 100.0, (unsigned long long)changed, changed ? 100.0 * changedSame / changed : 100.0,
                      (unsigned long long)gpuChanged);
        report += line;
        sorted.clear();
        for (auto& [k, n] : tp) sorted.push_back({n, k});
        std::sort(sorted.rbegin(), sorted.rend());
        for (size_t i = 0; i < sorted.size() && i < 20; i++) {
            std::snprintf(line, sizeof(line), "  %9llu  %s\n", (unsigned long long)sorted[i].first, sorted[i].second.c_str());
            report += line;
        }
        std::printf("TRACE end compare:%s\n", bname);
        atlas_feat_destroy(h);
    }
    std::fputs(report.c_str(), stdout);
    if (argc > 3) {
        FILE* o = std::fopen(argv[3], "wb");
        if (o) {
            std::fputs(report.c_str(), o);
            std::fclose(o);
        }
    }
    return rc;
}
