#include "sectionstore.hpp"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>

#include "sectioncodec.hpp"
#include "sectionpack.hpp"

namespace atlas {

namespace {
int fdivs(int a, int b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }
// a record (both files): magic, x, y, z, kind | section flags << 8 | layer bits << 16, the section's hash, the base's hash,
// the base's one id, the generator's version, payload A's bytes, payload B's bytes; then A, B (each padded to 4 bytes).
// Whole (.sec): A the section compressed. Delta (.dlt): A the layer compressed (KEEP where the base stands), B the whole
// section compressed (the fallback; may be empty). Replaced (.dlt): no payload (the section is whole in the .sec file).
constexpr uint32_t COLD_MAGIC = 0x3253434Eu;  // "NCS2"
constexpr uint32_t HEAD = 11;
constexpr uint32_t KIND_WHOLE = 0, KIND_DELTA = 1, KIND_REPLACED = 2;
constexpr uint32_t L_KNOWN = 1, L_REPLACED = 2, L_LOST = 4;
constexpr uint32_t KEEP = 0x10000u;               // (a delta's voxel the base keeps)
constexpr uint32_t MAX_PAYLOAD = 64u << 10;       // (a record past this is not one: a torn file)
constexpr size_t OLD_HEAD = 7;                    // (the uncompressed records before: their header's words)
uint32_t pad4(uint32_t n) { return (n + 3u) & ~3u; }

bool fallbackOn() {
    static const bool on = [] {
        const char* e = std::getenv("ATLAS_EDIT_FALLBACK");  // (0: deltas without the whole section beside them)
        return !e || std::atoi(e) != 0;
    }();
    return on;
}

void encodeWords(const std::vector<uint32_t>& words, std::vector<uint8_t>& out) {
    uint16_t ids[4096];
    unpackSection(words.data(), ids);
    uint32_t v[4096];
    for (int i = 0; i < 4096; i++) v[i] = ids[i];
    encodeGrid(v, out);
}

PackedIds decodeWords(const std::vector<uint8_t>& in) {
    uint32_t v[4096];
    if (!decodeGrid(in.data(), in.size(), v)) return nullptr;
    uint16_t ids[4096];
    for (int i = 0; i < 4096; i++) {
        if (v[i] > 0xFFFFu) return nullptr;
        ids[i] = uint16_t(v[i]);
    }
    return std::make_shared<const std::vector<uint32_t>>(packSection(ids));
}

void encodeLayer(const EditLayer& l, std::vector<uint8_t>& out) {
    uint32_t v[4096];
    std::fill(v, v + 4096, KEEP);
    for (uint32_t e : l.edits) v[e >> 16] = e & 0xFFFFu;
    encodeGrid(v, out);
}

bool decodeLayer(const std::vector<uint8_t>& in, EditLayer& l) {
    uint32_t v[4096];
    if (!decodeGrid(in.data(), in.size(), v)) return false;
    l.edits.clear();
    for (uint32_t i = 0; i < 4096; i++)
        if (v[i] != KEEP) l.edits.push_back(i << 16 | (v[i] & 0xFFFFu));
    return true;
}

bool readBytes(const std::string& path, uint64_t offset, uint32_t n, std::vector<uint8_t>& out) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    out.resize(n);
    bool ok = std::fseek(f, long(offset), SEEK_SET) == 0 && std::fread(out.data(), 1, n, f) == n;
    std::fclose(f);
    return ok;
}

// appends a record; the payload's first byte's offset in the file; false: not written
bool appendRecord(const std::string& path, const uint32_t h[HEAD], const std::vector<uint8_t>& a, const std::vector<uint8_t>& b,
                  uint64_t fileBytes, uint64_t& payloadAt, uint64_t& recordBytes) {
    FILE* f = std::fopen(path.c_str(), "ab");
    if (!f) return false;
    static const uint8_t zero[4] = {0, 0, 0, 0};
    bool ok = std::fwrite(h, 4, HEAD, f) == HEAD;
    ok = ok && (a.empty() || std::fwrite(a.data(), 1, a.size(), f) == a.size());
    ok = ok && std::fwrite(zero, 1, pad4(uint32_t(a.size())) - a.size(), f) == pad4(uint32_t(a.size())) - a.size();
    ok = ok && (b.empty() || std::fwrite(b.data(), 1, b.size(), f) == b.size());
    ok = ok && std::fwrite(zero, 1, pad4(uint32_t(b.size())) - b.size(), f) == pad4(uint32_t(b.size())) - b.size();
    std::fclose(f);
    payloadAt = fileBytes + HEAD * 4;
    recordBytes = uint64_t(HEAD) * 4 + pad4(uint32_t(a.size())) + pad4(uint32_t(b.size()));
    return ok;
}

struct Timer {
    double& ms;
    std::chrono::steady_clock::time_point t0 = std::chrono::steady_clock::now();
    explicit Timer(double& m) : ms(m) {}
    ~Timer() { ms += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count(); }
};
}  // namespace

void EditLayer::set(uint32_t voxel, uint16_t id) {
    uint32_t e = voxel << 16 | id;
    auto it = std::lower_bound(edits.begin(), edits.end(), voxel << 16);
    if (it != edits.end() && (*it >> 16) == voxel) *it = e;
    else edits.insert(it, e);
    dirty = true;
}

SectionStore::~SectionStore() { flush(); }

uint64_t SectionStore::regionKey(int sx, int sz) {
    return uint64_t(uint32_t(fdivs(sx, 16))) << 32 | uint32_t(fdivs(sz, 16));
}

std::string SectionStore::regionPath(uint64_t rk, const char* ext) const {
    char b[64];
    std::snprintf(b, sizeof b, "r.%d.%d.%s", int32_t(uint32_t(rk >> 32)), int32_t(uint32_t(rk)), ext);
    return dir_ + "/" + b;
}

void SectionStore::configure(const std::string& dir, uint64_t seed, uint32_t genVersion, uint64_t warmBudget, uint64_t coldBudget) {
    flush();  // (the layers changed so far: to the directory they belong to)
    warmBudget_ = warmBudget;
    coldBudget_ = coldBudget;
    genVersion_ = genVersion;
    cold_.clear();
    coldBytes_ = 0;
    dir_.clear();
    if (dir.empty()) return;
    // (the world's identity: what was cached for another seed is another directory; the generator's version is each
    // record's own: an edit layer outlives a generator change, its base hash tells whether it still applies)
    char sub[64];
    std::snprintf(sub, sizeof sub, "/%016llx", (unsigned long long)seed);
    std::error_code ec;
    std::filesystem::create_directories(dir + sub, ec);
    if (ec) return;
    dir_ = dir + sub;
    // what is on disk already counts against the budget
    for (auto& e : std::filesystem::directory_iterator(dir_, ec)) {
        int rx, rz;
        if (std::sscanf(e.path().filename().string().c_str(), "r.%d.%d.sec", &rx, &rz) != 2 || e.path().extension() != ".sec")
            continue;
        uint64_t rk = uint64_t(uint32_t(rx)) << 32 | uint32_t(rz);
        ColdRegion& cr = cold_[rk];
        cr.fileBytes = uint64_t(e.file_size(ec));
        coldBytes_ += cr.fileBytes;
    }
    // (the layers made before a directory was set, never written: kept over what the disk holds)
    std::vector<std::pair<Key3, LayerPtr>> unwritten;
    for (auto& [k, l] : layers_)
        if (l->dirty) unwritten.push_back({k, l});
    layers_.clear();
    loadLayers();
    for (auto& [k, l] : unwritten) layers_[k] = l;
}

void SectionStore::loadLayers() {
    std::error_code ec;
    deltaBytes_ = 0;
    dltBytes_.clear();
    dltLive_.clear();
    for (auto& e : std::filesystem::directory_iterator(dir_, ec)) {
        int rx, rz;
        if (e.path().extension() != ".dlt" || std::sscanf(e.path().filename().string().c_str(), "r.%d.%d.dlt", &rx, &rz) != 2) continue;
        uint64_t rk = uint64_t(uint32_t(rx)) << 32 | uint32_t(rz);
        FILE* f = std::fopen(e.path().string().c_str(), "rb");
        if (!f) continue;
        uint64_t at = 0;
        uint32_t h[HEAD];
        std::vector<uint8_t> a;
        std::unordered_map<Key3, uint64_t, Key3Hash> live;
        while (std::fread(h, 4, HEAD, f) == HEAD && h[0] == COLD_MAGIC && h[9] <= MAX_PAYLOAD && h[10] <= MAX_PAYLOAD) {
            Key3 k{0, int32_t(h[1]), int32_t(h[2]), int32_t(h[3])};
            uint32_t kind = h[4] & 0xFFu, lb = (h[4] >> 16) & 0xFFu;
            uint64_t recBytes = uint64_t(HEAD) * 4 + pad4(h[9]) + pad4(h[10]);
            auto l = std::make_shared<EditLayer>();
            l->baseHash = h[6];
            l->baseUni = h[7];
            l->baseKnown = (lb & L_KNOWN) != 0;
            l->replaced = (lb & L_REPLACED) != 0 || kind == KIND_REPLACED;
            l->baseLost = (lb & L_LOST) != 0;
            l->dirty = false;
            bool ok = true;
            if (kind == KIND_DELTA && h[9]) {
                a.resize(h[9]);
                ok = std::fread(a.data(), 1, a.size(), f) == a.size() && decodeLayer(a, *l);
            }
            l->fbOffset = at + HEAD * 4 + pad4(h[9]);
            l->fbBytes = h[10];
            if (ok) {
                l->recBytes = uint32_t(recBytes);
                layers_[k] = l;  // (the newest record of a section wins)
                live[k] = recBytes;
            }
            at += recBytes;
            if (std::fseek(f, long(at), SEEK_SET) != 0) break;
        }
        std::fclose(f);
        uint64_t liveBytes = 0;
        for (auto& [k, b] : live) liveBytes += b;
        dltBytes_[rk] = at;
        dltLive_[rk] = liveBytes;
        deltaBytes_ += at;
    }
}

LayerPtr SectionStore::makeLayer(const Key3& k) {
    LayerPtr& l = layers_[k];
    if (!l) l = std::make_shared<EditLayer>();
    return l;
}

SectionStore::ColdRegion& SectionStore::coldRegion(uint64_t rk) {
    ColdRegion& cr = cold_[rk];
    cr.lastUse = ++clock_;
    if (cr.scanned || dir_.empty()) return cr;
    cr.scanned = true;
    FILE* f = std::fopen(regionPath(rk).c_str(), "rb");
    if (!f) return cr;
    uint64_t at = 0;
    uint32_t h[HEAD];
    while (std::fread(h, 4, HEAD, f) == HEAD && h[0] == COLD_MAGIC && h[9] <= MAX_PAYLOAD && h[10] <= MAX_PAYLOAD) {
        Key3 k{0, int32_t(h[1]), int32_t(h[2]), int32_t(h[3])};
        uint64_t recBytes = uint64_t(HEAD) * 4 + pad4(h[9]) + pad4(h[10]);
        if (h[8] != genVersion_) {  // (another build's or generator's record: not read; dead in the file)
            at += recBytes;
            if (std::fseek(f, long(at), SEEK_SET) != 0) break;
            continue;
        }
        auto old = cr.recs.find(k);
        if (old != cr.recs.end()) cr.liveBytes -= uint64_t(HEAD) * 4 + pad4(old->second.bytes);
        cr.recs[k] = {at + HEAD * 4, h[9], h[5], uint8_t(h[4] >> 8)};
        cr.liveBytes += recBytes;
        at += recBytes;
        if (std::fseek(f, long(at), SEEK_SET) != 0) break;
    }
    cr.fileBytes = at;
    std::fclose(f);
    return cr;
}

bool SectionStore::writeCold(const Key3& k, const Rec& r) {
    if (dir_.empty() || !r.words) {
        if (std::getenv("ATLAS_STORE_DEBUG")) std::fprintf(stderr, "store: no dir (%zu) or words\n", dir_.size());
        return false;
    }
    uint64_t rk = regionKey(k.x, k.z);
    ColdRegion& cr = coldRegion(rk);
    auto old = cr.recs.find(k);
    if (old != cr.recs.end() && old->second.hash == r.hash && old->second.flags == r.flags) return true;  // (there already)
    std::vector<uint8_t> a, none;
    {
        Timer t(st_.codecMs);
        encodeWords(*r.words, a);
    }
    uint32_t h[HEAD] = {COLD_MAGIC, uint32_t(k.x), uint32_t(k.y), uint32_t(k.z), KIND_WHOLE | uint32_t(r.flags) << 8, r.hash, 0,
                        EditLayer::MIXED, genVersion_, uint32_t(a.size()), 0};
    uint64_t at = 0, add = 0;
    if (!appendRecord(regionPath(rk), h, a, none, cr.fileBytes, at, add)) {
        if (std::getenv("ATLAS_STORE_DEBUG")) std::fprintf(stderr, "store: cannot append %s\n", regionPath(rk).c_str());
        return false;
    }
    if (old != cr.recs.end()) cr.liveBytes -= uint64_t(HEAD) * 4 + pad4(old->second.bytes);
    cr.recs[k] = {at, uint32_t(a.size()), r.hash, r.flags};
    cr.fileBytes += add;
    cr.liveBytes += add;
    coldBytes_ += add;
    st_.coldWrites++;
    st_.writtenBytes += add;
    st_.wholeEquivBytes += (r.words->size() + OLD_HEAD) * 4;
    if (cr.fileBytes > (1u << 20) && cr.fileBytes > 2 * cr.liveBytes) compact(rk, cr);
    return true;
}

PackedIds SectionStore::readCold(const Key3& k) {
    if (dir_.empty()) return nullptr;
    uint64_t rk = regionKey(k.x, k.z);
    ColdRegion& cr = coldRegion(rk);
    auto it = cr.recs.find(k);
    if (it == cr.recs.end()) return nullptr;
    std::vector<uint8_t> a;
    if (!readBytes(regionPath(rk), it->second.offset, it->second.bytes, a)) return nullptr;
    Timer t(st_.codecMs);
    return decodeWords(a);
}

void SectionStore::writeLayer(const Key3& k, const PackedIds& words) {
    auto li = layers_.find(k);
    if (li == layers_.end() || dir_.empty()) return;
    EditLayer& l = *li->second;
    uint64_t rk = regionKey(k.x, k.z);
    std::vector<uint8_t> a, b;
    uint32_t hash = 0;
    uint8_t flags = EDITED;
    if (!l.replaced && !l.baseLost) {
        Timer t(st_.codecMs);
        encodeLayer(l, a);
        if (words) {
            encodeWords(*words, b);
            uint16_t ids[4096];
            unpackSection(words->data(), ids);
            hash = sectionHash(ids);
        }
    }
    if (words && !l.replaced && !l.baseLost && a.size() > b.size()) {
        // a rewrite: bigger as a delta than whole: whole instead (a regenerated base never replaces it)
        l.replaced = true;
        st_.rewrites++;
    }
    if ((l.replaced || l.baseLost) && words) {
        Rec r;
        r.words = words;
        r.flags = uint8_t(EDITED | (l.baseLost ? BASE_LOST : 0));
        uint16_t ids[4096];
        unpackSection(words->data(), ids);
        r.hash = sectionHash(ids);
        writeCold(k, r);
    }
    uint32_t kind = KIND_DELTA;
    if (l.replaced || l.baseLost) {
        kind = KIND_REPLACED;
        a.clear();
        b.clear();
    } else {
        st_.deltaWrites++;
        st_.wholeEquivBytes += words ? (words->size() + OLD_HEAD) * 4 : 0;
        if (!fallbackOn()) b.clear();
    }
    uint32_t lb = (l.baseKnown ? L_KNOWN : 0) | (l.replaced ? L_REPLACED : 0) | (l.baseLost ? L_LOST : 0);
    uint32_t h[HEAD] = {COLD_MAGIC, uint32_t(k.x), uint32_t(k.y), uint32_t(k.z), kind | uint32_t(flags) << 8 | lb << 16, hash,
                        l.baseHash, l.baseUni, genVersion_, uint32_t(a.size()), uint32_t(b.size())};
    uint64_t& fileBytes = dltBytes_[rk];
    uint64_t at = 0, add = 0;
    if (!appendRecord(regionPath(rk, "dlt"), h, a, b, fileBytes, at, add)) return;
    l.fbOffset = at + pad4(uint32_t(a.size()));
    l.fbBytes = uint32_t(b.size());
    l.dirty = false;
    fileBytes += add;
    uint64_t& live = dltLive_[rk];
    live = live - std::min<uint64_t>(live, l.recBytes) + add;  // (its record before: dead now)
    l.recBytes = uint32_t(add);
    deltaBytes_ += add;
    st_.writtenBytes += add;
    // (rewritten often, a region's file grows with dead records: compacted from the layers in memory)
    if (fileBytes > (256u << 10) && fileBytes > 2 * live) compactLayers(rk);
}

void SectionStore::compactLayers(uint64_t rk) {
    // the region's layers into a new file: their deltas from memory, their fallbacks copied (a layer changed since its
    // record: its edits now, still dirty: written again whole with its section when that goes)
    std::string path = regionPath(rk, "dlt"), tmp = path + ".tmp";
    std::vector<std::pair<Key3, LayerPtr>> mine;
    for (auto& [k, l] : layers_)
        if (regionKey(k.x, k.z) == rk) mine.push_back({k, l});
    FILE* out = std::fopen(tmp.c_str(), "wb");
    if (!out) return;
    std::fclose(out);
    uint64_t at = 0;
    std::vector<std::pair<LayerPtr, std::pair<uint64_t, uint32_t>>> moved;
    for (auto& [k, l] : mine) {
        std::vector<uint8_t> a, b;
        if (!l->replaced && !l->baseLost) {
            encodeLayer(*l, a);
            if (l->fbBytes && !readBytes(path, l->fbOffset, l->fbBytes, b)) b.clear();
        }
        uint32_t kind = l->replaced || l->baseLost ? KIND_REPLACED : KIND_DELTA;
        uint32_t lb = (l->baseKnown ? L_KNOWN : 0) | (l->replaced ? L_REPLACED : 0) | (l->baseLost ? L_LOST : 0);
        uint32_t h[HEAD] = {COLD_MAGIC, uint32_t(k.x), uint32_t(k.y), uint32_t(k.z), kind | uint32_t(EDITED) << 8 | lb << 16, 0,
                            l->baseHash, l->baseUni, genVersion_, uint32_t(a.size()), uint32_t(b.size())};
        uint64_t p = 0, add = 0;
        if (!appendRecord(tmp, h, a, b, at, p, add)) {
            std::remove(tmp.c_str());
            return;
        }
        moved.push_back({l, {p + pad4(uint32_t(a.size())), uint32_t(b.size())}});
        l->recBytes = uint32_t(add);
        at += add;
    }
    std::remove(path.c_str());
    if (std::rename(tmp.c_str(), path.c_str()) != 0) return;
    for (auto& [l, fb] : moved) l->fbOffset = fb.first, l->fbBytes = fb.second;
    deltaBytes_ -= std::min(deltaBytes_, dltBytes_[rk]);
    deltaBytes_ += at;
    dltBytes_[rk] = at;
    dltLive_[rk] = at;
}

PackedIds SectionStore::fallback(const Key3& k) {
    LayerPtr l = layer(k);
    if (!l || !l->fbBytes || dir_.empty()) return nullptr;
    std::vector<uint8_t> b;
    if (!readBytes(regionPath(regionKey(k.x, k.z), "dlt"), l->fbOffset, l->fbBytes, b)) return nullptr;
    Timer t(st_.codecMs);
    return decodeWords(b);
}

void SectionStore::flush() {
    if (dir_.empty()) return;
    for (auto& [k, l] : layers_) {
        if (!l->dirty) continue;
        auto w = warm_.find(k);
        writeLayer(k, w == warm_.end() ? nullptr : w->second.words);
    }
}

void SectionStore::compact(uint64_t rk, ColdRegion& cr) {
    // the live records into a new file (the dead ones: sections written again since)
    std::string path = regionPath(rk), tmp = path + ".tmp";
    FILE* in = std::fopen(path.c_str(), "rb");
    FILE* out = std::fopen(tmp.c_str(), "wb");
    if (!in || !out) {
        if (in) std::fclose(in);
        if (out) std::fclose(out);
        return;
    }
    std::unordered_map<Key3, Loc, Key3Hash> recs;
    uint64_t at = 0;
    std::vector<uint8_t> buf;
    bool ok = true;
    static const uint8_t zero[4] = {0, 0, 0, 0};
    for (auto& [k, loc] : cr.recs) {
        buf.resize(loc.bytes);
        if (std::fseek(in, long(loc.offset), SEEK_SET) != 0 || std::fread(buf.data(), 1, buf.size(), in) != buf.size()) {
            ok = false;
            break;
        }
        uint32_t h[HEAD] = {COLD_MAGIC, uint32_t(k.x), uint32_t(k.y), uint32_t(k.z), KIND_WHOLE | uint32_t(loc.flags) << 8,
                            loc.hash, 0, EditLayer::MIXED, genVersion_, loc.bytes, 0};
        uint32_t padN = pad4(loc.bytes) - loc.bytes;
        ok = std::fwrite(h, 4, HEAD, out) == HEAD && std::fwrite(buf.data(), 1, buf.size(), out) == buf.size() &&
             std::fwrite(zero, 1, padN, out) == padN;
        if (!ok) break;
        recs[k] = {at + HEAD * 4, loc.bytes, loc.hash, loc.flags};
        at += uint64_t(HEAD) * 4 + pad4(loc.bytes);
    }
    std::fclose(in);
    std::fclose(out);
    if (!ok) {
        std::remove(tmp.c_str());
        return;
    }
    std::remove(path.c_str());
    if (std::rename(tmp.c_str(), path.c_str()) != 0) return;
    coldBytes_ -= cr.fileBytes - at;
    cr.recs.swap(recs);
    cr.fileBytes = cr.liveBytes = at;
}

void SectionStore::coldBudget() {
    // over the disk's budget: the least recently used regions' files go (whole sections; the edit layers stay)
    while (coldBytes_ > coldBudget_ && !cold_.empty()) {
        auto lru = cold_.end();
        for (auto it = cold_.begin(); it != cold_.end(); ++it)
            if (it->second.fileBytes && (lru == cold_.end() || it->second.lastUse < lru->second.lastUse)) lru = it;
        if (lru == cold_.end()) break;
        std::remove(regionPath(lru->first).c_str());
        coldBytes_ -= std::min(coldBytes_, lru->second.fileBytes);
        st_.coldEvicted += lru->second.recs.size();
        for (auto& [k, loc] : lru->second.recs) {
            auto w = warm_.find(k);
            if (w != warm_.end()) w->second.onDisk = false;
        }
        cold_.erase(lru);
    }
}

void SectionStore::put(const Key3& k, PackedIds words, uint32_t hash, uint8_t flags) {
    if (!words) return;
    erase(k);
    uint64_t b = uint64_t(words->size()) * 4;
    Rec r;
    r.words = std::move(words);
    r.hash = hash;
    r.flags = flags;
    // (the same section on disk already: no need to write it again when it goes)
    if (!dir_.empty()) {
        LayerPtr l = layer(k);
        if (l && (flags & EDITED) && !l->replaced && !l->baseLost) {
            r.onDisk = !l->dirty;
        } else {
            ColdRegion& cr = coldRegion(regionKey(k.x, k.z));
            auto it = cr.recs.find(k);
            r.onDisk = it != cr.recs.end() && it->second.hash == hash && it->second.flags == flags;
        }
    }
    warm_[k] = std::move(r);
    warmBytes_ += b;
}

PackedIds SectionStore::get(const Key3& k) {
    auto it = warm_.find(k);
    if (it != warm_.end()) {
        st_.hits++;
        return it->second.words;
    }
    st_.misses++;
    Rec r;
    if (PackedIds w = dir_.empty() ? nullptr : readCold(k)) {
        st_.coldHits++;
        const Loc& loc = cold_[regionKey(k.x, k.z)].recs[k];
        r.words = w;
        r.hash = loc.hash;
        r.flags = loc.flags;
        r.onDisk = true;
    } else if (LayerPtr l = layer(k); l && l->selfContained()) {
        // (a layer on a base of one id: whole on its own)
        uint16_t ids[4096];
        std::fill(ids, ids + 4096, uint16_t(l->baseUni));
        l->apply(ids);
        r.words = std::make_shared<const std::vector<uint32_t>>(packSection(ids));
        r.hash = sectionHash(ids);
        r.flags = REGEN | EDITED;
        r.onDisk = !l->dirty;
        st_.coldHits++;
    } else if (LayerPtr l = layer(k); l && !l->baseKnown && l->fbBytes) {
        // (its base never seen: the section as it was last held, until the base comes)
        PackedIds w = fallback(k);
        if (!w) return nullptr;
        uint16_t ids[4096];
        unpackSection(w->data(), ids);
        r.words = w;
        r.hash = sectionHash(ids);
        r.flags = EDITED;
        r.onDisk = !l->dirty;
        st_.coldHits++;
    } else {
        return nullptr;
    }
    PackedIds w = r.words;
    warmBytes_ += uint64_t(w->size()) * 4;
    warm_[k] = std::move(r);
    return w;
}

bool SectionStore::meta(const Key3& k, uint32_t& hash, uint8_t& flags) {
    auto it = warm_.find(k);
    if (it != warm_.end()) {
        hash = it->second.hash;
        flags = it->second.flags;
        return true;
    }
    if (dir_.empty()) return false;
    ColdRegion& cr = coldRegion(regionKey(k.x, k.z));
    auto c = cr.recs.find(k);
    if (c == cr.recs.end()) return false;
    hash = c->second.hash;
    flags = c->second.flags;
    return true;
}

void SectionStore::erase(const Key3& k) {
    auto it = warm_.find(k);
    if (it == warm_.end()) return;
    warmBytes_ -= uint64_t(it->second.words ? it->second.words->size() : 0) * 4;
    warm_.erase(it);
}

void SectionStore::inRange(int sx0, int sz0, int sx1, int sz1, std::vector<std::pair<Key3, PackedIds>>& out) {
    for (auto& [k, r] : warm_)
        if (k.x >= sx0 && k.x < sx1 && k.z >= sz0 && k.z < sz1) out.emplace_back(k, r.words);
    if (dir_.empty()) return;
    // the cold tier's sections there that warm does not hold: read back (coming back somewhere: no stream again)
    std::vector<Key3> want;
    auto kept = [&](int rx, int rz) { return keep_ < 0 || (std::abs(rx - keepRx_) <= keep_ && std::abs(rz - keepRz_) <= keep_); };
    for (int rz = fdivs(sz0, 16); rz <= fdivs(sz1 - 1, 16); rz++)
        for (int rx = fdivs(sx0, 16); rx <= fdivs(sx1 - 1, 16); rx++) {
            uint64_t rk = uint64_t(uint32_t(rx)) << 32 | uint32_t(rz);
            auto f = cold_.find(rk);
            if (f == cold_.end() || !f->second.fileBytes) continue;  // (nothing on disk there)
            if (!kept(rx, rz)) continue;                              // (past the kept)
            ColdRegion& cr = coldRegion(rk);
            for (auto& [k, loc] : cr.recs)
                if (k.x >= sx0 && k.x < sx1 && k.z >= sz0 && k.z < sz1 && !warm_.count(k)) want.push_back(k);
        }
    // (and the edit layers whole on their own: a build on air)
    for (auto& [k, l] : layers_)
        if ((l->selfContained() || (!l->baseKnown && l->fbBytes)) && k.x >= sx0 && k.x < sx1 && k.z >= sz0 && k.z < sz1 && !warm_.count(k) &&
            kept(fdivs(k.x, 16), fdivs(k.z, 16)))
            want.push_back(k);
    std::sort(want.begin(), want.end(), [](const Key3& a, const Key3& b) {
        return a.x != b.x ? a.x < b.x : a.z != b.z ? a.z < b.z : a.y < b.y;
    });
    want.erase(std::unique(want.begin(), want.end(), [](const Key3& a, const Key3& b) { return a.x == b.x && a.y == b.y && a.z == b.z; }),
               want.end());
    for (const Key3& k : want) {
        PackedIds w = get(k);
        if (w) out.emplace_back(k, w);
    }
}

void SectionStore::drop(std::unordered_map<Key3, Rec, Key3Hash>::iterator it) {
    const Key3 k = it->first;
    const Rec& r = it->second;
    LayerPtr l = layer(k);
    if ((r.flags & REGEN) && !(r.flags & EDITED)) {
        st_.dropped++;  // (made again when wanted)
    } else if (l && (r.flags & EDITED) && !l->replaced && !l->baseLost) {
        if (l->dirty || !r.onDisk) writeLayer(k, r.words);  // (a delta; or whole, a rewrite)
        st_.spilled++;
    } else if (l && l->dirty) {
        writeLayer(k, r.words);  // (whole, and its layer's mark: no regenerated base replaces it)
        st_.spilled++;
    } else if (r.onDisk || writeCold(k, r)) {
        st_.spilled++;
    } else {
        st_.lost++;  // (no cold tier: the server sends it again)
    }
    warmBytes_ -= uint64_t(r.words ? r.words->size() : 0) * 4;
    warm_.erase(it);
}

void SectionStore::enforce(int camSx, int camSz, int keepRegions) {
    // outside the kept regions: out of warm (to disk, or dropped). Only planned here: the disk work (a file append a
    // section, compactions) took seconds under the world's lock that the render thread takes too, so drainSpills does it
    // in slices with the lock let go between them
    int crx = fdivs(camSx * 16, 256), crz = fdivs(camSz * 16, 256);
    bool sameView = crx == keepRx_ && crz == keepRz_ && keepRegions == keep_;
    keepRx_ = crx, keepRz_ = crz, keep_ = keepRegions;
    if (spillPending() && sameView) return;  // (the plan stands: no rescan of warm each update)
    spillQueue_.clear();
    spillNext_ = 0;
    uint64_t freed = 0;
    for (auto& [k, r] : warm_) {
        int rx = fdivs(k.x * 16, 256), rz = fdivs(k.z * 16, 256);
        if (std::abs(rx - crx) > keepRegions || std::abs(rz - crz) > keepRegions) {
            spillQueue_.push_back(k);
            freed += uint64_t(r.words ? r.words->size() : 0) * 4;
        }
    }
    if (warmBytes_ - std::min(warmBytes_, freed) > warmBudget_) {
        // over budget: the farthest first (Chebyshev, in sections), until a tenth under it (no churn each update)
        std::vector<std::pair<int, Key3>> order;
        order.reserve(warm_.size());
        for (auto& [k, r] : warm_) {
            int rx = fdivs(k.x * 16, 256), rz = fdivs(k.z * 16, 256);
            if (std::abs(rx - crx) > keepRegions || std::abs(rz - crz) > keepRegions) continue;  // (planned already)
            order.push_back({std::max(std::abs(k.x - camSx), std::abs(k.z - camSz)), k});
        }
        std::sort(order.begin(), order.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
        uint64_t target = warmBudget_ - warmBudget_ / 10, left = warmBytes_ - std::min(warmBytes_, freed);
        for (auto& [d, k] : order) {
            if (left <= target) break;
            auto it = warm_.find(k);
            if (it == warm_.end()) continue;
            spillQueue_.push_back(k);
            left -= std::min<uint64_t>(left, uint64_t(it->second.words ? it->second.words->size() : 0) * 4);
        }
    }
    if (spillQueue_.empty()) coldBudget();
}

bool SectionStore::drainSpills(double budgetMs) {
    auto t0 = std::chrono::steady_clock::now();
    while (spillNext_ < spillQueue_.size()) {
        auto it = warm_.find(spillQueue_[spillNext_++]);
        if (it != warm_.end()) drop(it);  // (gone meanwhile: replaced or erased)
        if (std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() >= budgetMs) break;
    }
    if (spillNext_ < spillQueue_.size()) return true;
    spillQueue_.clear();
    spillNext_ = 0;
    coldBudget();
    return false;
}

SectionStore::Stats SectionStore::stats() const {
    Stats s = st_;
    s.warm = warm_.size();
    s.warmBytes = warmBytes_;
    s.warmBudget = warmBudget_;
    s.coldBytes = coldBytes_;
    s.coldBudget = coldBudget_;
    s.deltaBytes = deltaBytes_;
    s.coldOn = !dir_.empty();
    s.coldRegions = cold_.size();
    for (auto& [rk, cr] : cold_) s.coldRecords += cr.recs.size();
    s.layers = layers_.size();
    for (auto& [k, l] : layers_) s.layersWaiting += !l->baseKnown;
    return s;
}

}  // namespace atlas
