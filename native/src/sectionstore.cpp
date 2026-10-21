#include "sectionstore.hpp"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>

#include "sectionpack.hpp"

namespace atlas {

namespace {
int fdivs(int a, int b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }
constexpr uint32_t COLD_MAGIC = 0x3153434Eu;  // "NCS1": a record: magic, x, y, z, hash, flags, words, the words
constexpr uint32_t COLD_HEAD = 7;             // (words before the record's own)
}  // namespace

uint64_t SectionStore::regionKey(int sx, int sz) {
    return uint64_t(uint32_t(fdivs(sx, 16))) << 32 | uint32_t(fdivs(sz, 16));
}

std::string SectionStore::regionPath(uint64_t rk) const {
    char b[64];
    std::snprintf(b, sizeof b, "r.%d.%d.sec", int32_t(uint32_t(rk >> 32)), int32_t(uint32_t(rk)));
    return dir_ + "/" + b;
}

void SectionStore::configure(const std::string& dir, uint64_t seed, uint32_t genVersion, uint64_t warmBudget, uint64_t coldBudget) {
    warmBudget_ = warmBudget;
    coldBudget_ = coldBudget;
    cold_.clear();
    coldBytes_ = 0;
    coldScannedAll_ = false;
    dir_.clear();
    if (dir.empty()) return;
    // (the world's identity: what was cached for another seed or generator is another directory)
    char sub[64];
    std::snprintf(sub, sizeof sub, "/%016llx-%u", (unsigned long long)seed, genVersion);
    std::error_code ec;
    std::filesystem::create_directories(dir + sub, ec);
    if (ec) return;
    dir_ = dir + sub;
    // what is on disk already counts against the budget
    for (auto& e : std::filesystem::directory_iterator(dir_, ec)) {
        int rx, rz;
        if (std::sscanf(e.path().filename().string().c_str(), "r.%d.%d.sec", &rx, &rz) != 2) continue;
        uint64_t rk = uint64_t(uint32_t(rx)) << 32 | uint32_t(rz);
        ColdRegion& cr = cold_[rk];
        cr.fileBytes = uint64_t(e.file_size(ec));
        coldBytes_ += cr.fileBytes;
    }
}

SectionStore::ColdRegion& SectionStore::coldRegion(uint64_t rk) {
    ColdRegion& cr = cold_[rk];
    cr.lastUse = ++clock_;
    if (cr.scanned || dir_.empty()) return cr;
    cr.scanned = true;
    FILE* f = std::fopen(regionPath(rk).c_str(), "rb");
    if (!f) return cr;
    uint64_t at = 0;
    uint32_t h[COLD_HEAD];
    while (std::fread(h, 4, COLD_HEAD, f) == COLD_HEAD && h[0] == COLD_MAGIC && h[6] <= PACK_MAX_WORDS) {
        Key3 k{0, int32_t(h[1]), int32_t(h[2]), int32_t(h[3])};
        auto old = cr.recs.find(k);
        if (old != cr.recs.end()) cr.liveBytes -= uint64_t(old->second.words + COLD_HEAD) * 4;
        cr.recs[k] = {at + COLD_HEAD * 4, h[6], h[4], uint8_t(h[5])};
        cr.liveBytes += uint64_t(h[6] + COLD_HEAD) * 4;
        at += uint64_t(h[6] + COLD_HEAD) * 4;
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
    FILE* f = std::fopen(regionPath(rk).c_str(), "ab");
    if (!f) {
        if (std::getenv("ATLAS_STORE_DEBUG")) std::fprintf(stderr, "store: cannot append %s\n", regionPath(rk).c_str());
        return false;
    }
    uint32_t n = uint32_t(r.words->size());
    uint32_t h[COLD_HEAD] = {COLD_MAGIC, uint32_t(k.x), uint32_t(k.y), uint32_t(k.z), r.hash, r.flags, n};
    bool ok = std::fwrite(h, 4, COLD_HEAD, f) == COLD_HEAD && std::fwrite(r.words->data(), 4, n, f) == n;
    std::fclose(f);
    if (!ok) return false;
    if (old != cr.recs.end()) cr.liveBytes -= uint64_t(old->second.words + COLD_HEAD) * 4;
    cr.recs[k] = {cr.fileBytes + COLD_HEAD * 4, n, r.hash, r.flags};
    uint64_t add = uint64_t(n + COLD_HEAD) * 4;
    cr.fileBytes += add;
    cr.liveBytes += add;
    coldBytes_ += add;
    st_.coldWrites++;
    if (cr.fileBytes > (1u << 20) && cr.fileBytes > 2 * cr.liveBytes) compact(rk, cr);
    return true;
}

PackedIds SectionStore::readCold(const Key3& k) {
    if (dir_.empty()) return nullptr;
    ColdRegion& cr = coldRegion(regionKey(k.x, k.z));
    auto it = cr.recs.find(k);
    if (it == cr.recs.end()) return nullptr;
    FILE* f = std::fopen(regionPath(regionKey(k.x, k.z)).c_str(), "rb");
    if (!f) return nullptr;
    auto w = std::make_shared<std::vector<uint32_t>>(it->second.words);
    bool ok = std::fseek(f, long(it->second.offset), SEEK_SET) == 0 && std::fread(w->data(), 4, w->size(), f) == w->size();
    std::fclose(f);
    if (!ok || w->empty() || packWords(w->data()) != w->size()) return nullptr;
    return w;
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
    std::vector<uint32_t> buf;
    bool ok = true;
    for (auto& [k, loc] : cr.recs) {
        buf.resize(loc.words);
        if (std::fseek(in, long(loc.offset), SEEK_SET) != 0 || std::fread(buf.data(), 4, buf.size(), in) != buf.size()) {
            ok = false;
            break;
        }
        uint32_t h[COLD_HEAD] = {COLD_MAGIC, uint32_t(k.x), uint32_t(k.y), uint32_t(k.z), loc.hash, loc.flags, loc.words};
        ok = std::fwrite(h, 4, COLD_HEAD, out) == COLD_HEAD && std::fwrite(buf.data(), 4, buf.size(), out) == buf.size();
        if (!ok) break;
        recs[k] = {at + COLD_HEAD * 4, loc.words, loc.hash, loc.flags};
        at += uint64_t(loc.words + COLD_HEAD) * 4;
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
    // over the disk's budget: the least recently used regions' files go
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
        ColdRegion& cr = coldRegion(regionKey(k.x, k.z));
        auto it = cr.recs.find(k);
        r.onDisk = it != cr.recs.end() && it->second.hash == hash && it->second.flags == flags;
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
    if (dir_.empty()) return nullptr;
    PackedIds w = readCold(k);
    if (!w) return nullptr;
    st_.coldHits++;
    ColdRegion& cr = coldRegion(regionKey(k.x, k.z));
    const Loc& loc = cr.recs[k];
    Rec r;
    r.words = w;
    r.hash = loc.hash;
    r.flags = loc.flags;
    r.onDisk = true;
    warmBytes_ += uint64_t(w->size()) * 4;
    warm_[k] = std::move(r);
    return w;
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
    for (int rz = fdivs(sz0, 16); rz <= fdivs(sz1 - 1, 16); rz++)
        for (int rx = fdivs(sx0, 16); rx <= fdivs(sx1 - 1, 16); rx++) {
            uint64_t rk = uint64_t(uint32_t(rx)) << 32 | uint32_t(rz);
            auto f = cold_.find(rk);
            if (f == cold_.end() || !f->second.fileBytes) continue;  // (nothing on disk there)
            if (keep_ >= 0 && (std::abs(rx - keepRx_) > keep_ || std::abs(rz - keepRz_) > keep_)) continue;  // (past the kept)
            ColdRegion& cr = coldRegion(rk);
            for (auto& [k, loc] : cr.recs)
                if (k.x >= sx0 && k.x < sx1 && k.z >= sz0 && k.z < sz1 && !warm_.count(k)) want.push_back(k);
        }
    for (const Key3& k : want) {
        PackedIds w = get(k);
        if (w) out.emplace_back(k, w);
    }
}

void SectionStore::drop(std::unordered_map<Key3, Rec, Key3Hash>::iterator it) {
    const Rec& r = it->second;
    if ((r.flags & REGEN) && !(r.flags & EDITED)) st_.dropped++;  // (made again when wanted)
    else if (r.onDisk || writeCold(it->first, r)) st_.spilled++;
    else st_.lost++;  // (no cold tier: the server sends it again)
    warmBytes_ -= uint64_t(r.words ? r.words->size() : 0) * 4;
    warm_.erase(it);
}

void SectionStore::enforce(int camSx, int camSz, int keepRegions) {
    // outside the kept regions: out of warm (to disk, or dropped)
    int crx = fdivs(camSx * 16, 256), crz = fdivs(camSz * 16, 256);
    keepRx_ = crx, keepRz_ = crz, keep_ = keepRegions;
    for (auto it = warm_.begin(); it != warm_.end();) {
        int rx = fdivs(it->first.x * 16, 256), rz = fdivs(it->first.z * 16, 256);
        if (std::abs(rx - crx) > keepRegions || std::abs(rz - crz) > keepRegions) {
            auto next = std::next(it);
            drop(it);
            it = next;
        } else {
            ++it;
        }
    }
    if (warmBytes_ > warmBudget_) {
        // over budget: the farthest first (Chebyshev, in sections), until a tenth under it (no churn each update)
        std::vector<std::pair<int, Key3>> order;
        order.reserve(warm_.size());
        for (auto& [k, r] : warm_) order.push_back({std::max(std::abs(k.x - camSx), std::abs(k.z - camSz)), k});
        std::sort(order.begin(), order.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
        uint64_t target = warmBudget_ - warmBudget_ / 10;
        for (auto& [d, k] : order) {
            if (warmBytes_ <= target) break;
            auto it = warm_.find(k);
            if (it != warm_.end()) drop(it);
        }
    }
    coldBudget();
}

SectionStore::Stats SectionStore::stats() const {
    Stats s = st_;
    s.warm = warm_.size();
    s.warmBytes = warmBytes_;
    s.warmBudget = warmBudget_;
    s.coldBytes = coldBytes_;
    s.coldBudget = coldBudget_;
    s.coldOn = !dir_.empty();
    s.coldRegions = cold_.size();
    for (auto& [rk, cr] : cold_) s.coldRecords += cr.recs.size();
    return s;
}

}  // namespace atlas
