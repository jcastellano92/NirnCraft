#include "gencache.hpp"

#include <algorithm>
#include <chrono>
#include <charconv>
#include <cstdio>
#include <cstring>
#include <filesystem>
#include <fstream>

#include "sectioncodec.hpp"
#include "sectionpack.hpp"
#include "cache_set_lease.hpp"

namespace atlas {

namespace {
// a record: magic, type (0 a chunk's header, its blocks mask's bits 32-39 in bits 8-15; 1 a section), x, y (a header:
// its blocks mask's bits 0-31), z, payload bytes; the payload padded to 4 bytes.
// A header's payload: its 3 words (lowest ground, highest top, biome), the 40 sections' kinds (a byte each), how many
// distinct hashes, then per section the index of its hash (a byte) and the hashes (lo, hi words). A section's: its ids
// compressed (sectioncodec.hpp).
constexpr uint32_t MAGIC = 0x3247434Eu;  // "NCG2"
constexpr uint32_t HEAD = 6;
constexpr uint32_t MAX_PAYLOAD = 64u << 10;
uint32_t pad4(uint32_t n) { return (n + 3u) & ~3u; }
int fdiv(int a, int b) { return a >= 0 ? a / b : -((-a + b - 1) / b); }
double msSince(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
}

void encodeHeader(const uint32_t* info, std::vector<uint8_t>& out) {
    auto put32 = [&](uint32_t v) {
        for (int i = 0; i < 4; i++) out.push_back(uint8_t(v >> (8 * i)));
    };
    put32(info[0]);
    put32(info[1]);
    put32(info[2]);
    std::vector<uint64_t> hashes;
    uint8_t idx[40];
    for (int s = 0; s < 40; s++) {
        out.push_back(uint8_t(info[4 + s * 4 + 2]));
        uint64_t h = uint64_t(info[4 + s * 4]) | uint64_t(info[4 + s * 4 + 1]) << 32;
        auto it = std::find(hashes.begin(), hashes.end(), h);
        idx[s] = uint8_t(it - hashes.begin());
        if (it == hashes.end()) hashes.push_back(h);
    }
    out.push_back(uint8_t(hashes.size()));
    out.insert(out.end(), idx, idx + 40);
    for (uint64_t h : hashes) put32(uint32_t(h)), put32(uint32_t(h >> 32));
}

// a section's (coarse chunk's) record: its hash, its ids; false when they do not match
bool decodeChecked(const std::vector<uint8_t>& in, uint16_t* ids) {
    if (in.size() < 5) return false;
    uint32_t h = uint32_t(in[0]) | uint32_t(in[1]) << 8 | uint32_t(in[2]) << 16 | uint32_t(in[3]) << 24;
    uint32_t v[4096];
    if (!decodeGrid(in.data() + 4, in.size() - 4, v)) return false;
    for (int i = 0; i < 4096; i++) {
        if (v[i] > 0xFFFFu) return false;
        ids[i] = uint16_t(v[i]);
    }
    return sectionHash(ids) == h;
}

bool decodeHeader(const std::vector<uint8_t>& in, uint32_t* info) {
    if (in.size() < 12 + 40 + 1 + 40) return false;
    auto get32 = [&](size_t at) {
        return uint32_t(in[at]) | uint32_t(in[at + 1]) << 8 | uint32_t(in[at + 2]) << 16 | uint32_t(in[at + 3]) << 24;
    };
    std::memset(info, 0, GenCache::INFO_WORDS * 4);
    info[0] = get32(0);
    info[1] = get32(4);
    info[2] = get32(8);
    size_t n = in[52];
    if (in.size() < 93 + n * 8) return false;
    for (int s = 0; s < 40; s++) {
        size_t k = in[53 + size_t(s)];
        if (k >= n) return false;
        info[4 + s * 4] = get32(93 + k * 8);
        info[4 + s * 4 + 1] = get32(93 + k * 8 + 4);
        info[4 + s * 4 + 2] = in[12 + size_t(s)];
    }
    return true;
}
}  // namespace

GenCache::GenCache() { writer_ = std::thread([this] { writerLoop(); }); }

GenCache::~GenCache() {
    std::lock_guard<std::mutex> transition(configureMu_);
    flush();
    {
        std::unique_lock<std::mutex> l(mu_);
        stop_ = true;
    }
    cv_.notify_all();
    if (writer_.joinable()) writer_.join();
    if (pruner_.joinable()) pruner_.join();  // no cache mutex held; lease_ still protects the configured set
}

uint64_t GenCache::regionOf(int cx, int cz) { return uint64_t(uint32_t(fdiv(cx, 32))) << 32 | uint32_t(fdiv(cz, 32)); }

std::string GenCache::regionPath(uint64_t rk) const {
    char b[64];
    if (rk >> 63) {
        // (a far region: lod, rx, rz sign-extended from 24 / 28 bits)
        int lod = int((rk >> 56) & 0x7Fu);
        int32_t rx = int32_t(uint32_t(rk >> 28 & 0xFFFFFFu) << 8) >> 8, rz = int32_t(uint32_t(rk & 0xFFFFFFFu) << 4) >> 4;
        std::snprintf(b, sizeof b, "/f%d.%d.%d.gch", lod, rx, rz);
    } else {
        std::snprintf(b, sizeof b, "/r.%d.%d.gch", int32_t(uint32_t(rk >> 32)), int32_t(uint32_t(rk)));
    }
    return dir_ + b;
}

namespace {
uint64_t tileKey(int tx, int tz) { return uint64_t(uint32_t(tx)) << 32 | uint32_t(tz); }
int tdiv(int a) { return a >= 0 ? a / 16 : -((-a + 15) / 16); }
// a far tile's kinds: runs of one value (varint value, varint run)
void encodeKinds(const std::vector<uint32_t>& k, std::vector<uint8_t>& out) {
    codec::putVarint(out, uint32_t(k.size()));
    for (size_t i = 0; i < k.size();) {
        size_t j = i;
        while (j < k.size() && k[j] == k[i]) j++;
        codec::putVarint(out, k[i]);
        codec::putVarint(out, uint32_t(j - i));
        i = j;
    }
}
bool decodeKinds(const std::vector<uint8_t>& in, std::vector<uint32_t>& k) {
    const uint8_t* p = in.data();
    const uint8_t* e = p + in.size();
    uint32_t n;
    if (!codec::getVarint(p, e, n) || n > (1u << 20)) return false;
    k.clear();
    while (k.size() < n) {
        uint32_t v, run;
        if (!codec::getVarint(p, e, v) || !codec::getVarint(p, e, run) || run == 0 || k.size() + run > n) return false;
        k.insert(k.end(), run, v);
    }
    return true;
}
}  // namespace

void GenCache::configure(const std::string& root, uint64_t fingerprint) {
    std::lock_guard<std::mutex> transition(configureMu_);
    // Admission precedes joining old cleanup: a queued A prune must see a returning A owner, not delete it first.
    // An exclusive prune already in progress can refuse admission. That request becomes cache-off, never old-FP data.
    auto target = root.empty() || !fingerprint ? nullptr : CacheSetLease::acquire(root, fingerprint);
    std::unique_lock<std::mutex> l(mu_);
    for (;;) {
        idle_.wait(l, [&] { return queue_.empty() && !busy_; });
        scanCv_.wait(l, [&] { return scanning_ == 0 && regionWaiters_ == 0; });
        // A producer may have queued work while the scan wait let mu_ go. Observe both drains atomically.
        if (queue_.empty() && !busy_) break;
    }
    if (target && on_ && fp_ == fingerprint && dir_ == target->set().string()) return;
    if (target && admitted_) admitted_->store(true);
    auto priorLease = std::move(lease_);  // independent read tickets may retain this after configure returns
    auto priorPruner = std::move(pruner_);
    oldSets_.clear();
    regions_.clear();
    fresh_.clear();
    dataBytes_ = 0;
    on_ = false;
    fp_ = 0;
    dir_.clear();
    st_ = Stats{};
    l.unlock();
    if (priorPruner.joinable()) priorPruner.join();
    if (!target) return;  // prior set remains on disk; pending destructive candidates were discarded
    std::error_code ec;
    std::filesystem::create_directories(target->set(), ec);
    if (ec) return;
    uint64_t fileBytes = 0;
    for (std::filesystem::directory_iterator it(target->set(), ec), end; !ec && it != end; it.increment(ec)) {
        const auto& e = *it;
        if (e.path().extension() == ".gch") fileBytes += uint64_t(e.file_size(ec));
        if (ec) return;
    }
    if (ec) return;
    // (another generator's or build's sets: never read again; deleted in the background, the world's folder does not
    // grow with every build)
    std::vector<std::pair<std::filesystem::file_time_type, std::filesystem::path>> others;
    bool pruneSafe = true;
    for (std::filesystem::directory_iterator it(target->root(), ec), end; !ec && it != end; it.increment(ec)) {
        const auto& e = *it;
        if (e.is_directory(ec) && e.path().filename().string().rfind("gen-", 0) == 0 && e.path().filename() != target->set().filename()) {
            auto stamp = e.last_write_time(ec);
            if (!ec) others.push_back({stamp, e.path()});
        }
        if (ec) { pruneSafe = false; break; }
    }
    if (ec) pruneSafe = false;
    // In development (a file `keep-gen-sets` in the cache's root, its text the number of sets to keep, 3 if empty: the test
    // harness writes it) the newest sets by last use stay beside this one, so builds and worktrees that alternate on one
    // instance do not delete each other's bakes. A player's cache keeps this set alone.
    size_t keep = 0;
    {
        auto keepPath = std::filesystem::path(root).parent_path() / "keep-gen-sets";
        bool exists = std::filesystem::exists(keepPath, ec);
        if (ec) pruneSafe = false;
        if (exists) {
            if (!std::filesystem::is_regular_file(keepPath, ec) || ec) pruneSafe = false;
            std::ifstream kf(keepPath);
            int n = 0;
            if (!kf) pruneSafe = false;
            else { kf >> n; if (kf.bad() || (kf.fail() && !kf.eof())) pruneSafe = false; }
            keep = size_t(n > 0 ? n : 3) - 1;  // (this set is one of them)
        }
    }
    std::sort(others.begin(), others.end(), [](const auto& a, const auto& b) { return a.first > b.first; });
    std::vector<std::filesystem::path> old;
    if (pruneSafe) for (size_t i = keep; i < others.size(); i++) old.push_back(others[i].second);
    l.lock();
    lease_ = std::move(target);
    dir_ = lease_->set().string();
    fp_ = fingerprint;
    st_.fileBytes = fileBytes;
    oldSets_ = std::move(old);  // (deleted once this set has written something: a set opened for a moment, while the
                                // generator was being put together or taken apart, must not delete the real one)
    on_ = true;
}

void GenCache::dropOldSets() {
    if (oldSets_.empty() || pruner_.joinable()) return;
    std::vector<std::filesystem::path> old;
    old.swap(oldSets_);
    auto release = pruneRelease_; auto held = pruneHeld_;
    pruner_ = std::thread([old = std::move(old), release, held] {
        if (release && held) {
            held->store(true);
            while (!release->load()) std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        for (const auto& p : old) {
            std::string name = p.filename().string();
            uint64_t fp = 0;
            if (name.size() != 20 || name.rfind("gen-", 0) != 0) continue;
            auto parsed = std::from_chars(name.data() + 4, name.data() + name.size(), fp, 16);
            if (parsed.ec != std::errc{} || parsed.ptr != name.data() + name.size() || !fp) continue;
            auto exclusive = CacheSetLease::acquire(p.parent_path(), fp, true, false);
            if (!exclusive || exclusive->set().filename() != p.filename()) continue;
            std::error_code ec;
            if (std::filesystem::is_symlink(exclusive->set(), ec) || ec) continue;
            std::filesystem::remove_all(exclusive->set(), ec);  // exclusive held across the whole delete; errors retain remainder
        }
    });
}

GenCache::Region& GenCache::region(std::unique_lock<std::mutex>& l, uint64_t rk) {
    for (;;) {
        Region& r = regions_[rk];
        if (r.scanning) {  // (another thread reads the file: wait for it, then look again)
            ++regionWaiters_;
            scanCv_.wait(l);
            --regionWaiters_;
            scanCv_.notify_all();
            continue;
        }
        if (r.scanned) return r;
        r.scanning = true;
        scanning_++;
        std::string path = regionPath(rk);
        l.unlock();
        Region tmp;
        scanFile(path, tmp);  // (the file read and parsed with the lock let go: a big region took seconds, and mu_ with it)
        l.lock();
        tmp.scanned = true;
        r = std::move(tmp);
        st_.chunks += r.chunks.size();
        st_.sections += r.secs.size();
        scanning_--;
        scanCv_.notify_all();
        return r;
    }
}

void GenCache::scanFile(const std::string& path, Region& r) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return;
    std::error_code ec;
    uint64_t bytes = std::filesystem::file_size(path, ec);
    if (ec) {
        std::fclose(f);
        return;
    }
    uint64_t at = 0;
    uint32_t h[HEAD];
    while (std::fread(h, 4, HEAD, f) == HEAD && h[0] == MAGIC && h[5] <= MAX_PAYLOAD) {
        if (at + HEAD * 4 + pad4(h[5]) > bytes) break;  // (a torn payload is not an indexed record)
        Loc loc{at + HEAD * 4, h[5]};
        uint32_t type = h[1] & 0xFFu;
        if (type == 0) {
            r.chunks[chunkKey(int32_t(h[2]), int32_t(h[4]))] = {loc, uint64_t(h[3]) | uint64_t(h[1] >> 8 & 0xFFu) << 32};
        } else if (type == 1) {
            r.secs[Key3{0, int32_t(h[2]), int32_t(h[3]), int32_t(h[4])}] = loc;
        } else if (type == 2) {
            bool tru = (h[1] >> 31) != 0;
            auto& t = r.tiles[tileKey(int32_t(h[2]), int32_t(h[4]))];
            if (t.loc.bytes == 0 || tru || !t.trueData) t = {loc, (h[1] >> 8) & 0x7FFFFFu, tru};  // (a stand-in never replaces true data)
        } else if (type == 4) {
            int32_t cx = int32_t(h[2]), cz = int32_t(h[4]);
            r.chunks.erase(chunkKey(cx, cz));
            for (int s = 0; s < 40; s++) r.secs.erase(Key3{0, cx, s - 4, cz});
        } else if (type == 5) {
            eraseFarTile(r, int32_t(h[3]), int32_t(h[2]), int32_t(h[4]));
        } else if (type == 3) {
            Key3 k{int32_t(h[3] >> 16 & 0x7FFFu), int32_t(h[2]), int32_t(h[3] & 0xFFFFu), int32_t(h[4])};
            bool tru = (h[3] >> 31) != 0;
            if (r.coarse.emplace(k, loc).second) r.baked[tileKey(tdiv(k.x), tdiv(k.z))]++;
            else if (tru) r.coarse[k] = loc;  // (true data replaces the stand-in's record)
            if (tru) r.coarseTrue.insert(k);
        }
        at += uint64_t(HEAD) * 4 + pad4(h[5]);
        if (std::fseek(f, long(at), SEEK_SET) != 0) break;
    }
    r.fileBytes = at;
    std::fclose(f);
    // The next append must start at the last whole record. Keeping a torn tail would hide every later record from
    // subsequent scans (and make its recorded offset differ from where append actually wrote it).
    if (at < bytes) std::filesystem::resize_file(path, at, ec);
}

void GenCache::eraseFarTile(Region& r, int lod, int tx, int tz) {
    r.tiles.erase(tileKey(tx, tz));
    r.baked.erase(tileKey(tx, tz));
    for (auto it = r.coarse.begin(); it != r.coarse.end();) {
        if (it->first.lod == lod && tdiv(it->first.x) == tx && tdiv(it->first.z) == tz) {
            r.coarseTrue.erase(it->first);
            it = r.coarse.erase(it);
        } else {
            ++it;
        }
    }
}

void GenCache::forgetFarTile(int lod, int tx, int tz) {
    if (!on()) return;
    Lease lease;
    { std::unique_lock<std::mutex> l(mu_); if (!on_) return; lease = lease_; }
    Job j;
    j.type = 5;
    j.k = Key3{lod, tx, 0, tz};
    {
        std::unique_lock<std::mutex> l(mu_);
        if (!on_ || lease != lease_) return;
        eraseFarTile(region(l, farRegionOf(lod, tx, tz)), lod, tx, tz);  // (known no more at once; the tombstone follows in the queue)
        queue_.push_back(std::move(j));
    }
    cv_.notify_one();
}

void GenCache::putChunk(int cx, int cz, const uint32_t* info) {
    if (!on()) return;
    Lease lease;
    { std::unique_lock<std::mutex> l(mu_); if (!on_) return; lease = lease_; }
    Job j;
    j.isChunk = true;
    j.k = Key3{0, cx, 0, cz};
    j.info.assign(info, info + INFO_WORDS);
    {
        std::unique_lock<std::mutex> l(mu_);
        if (!on_ || lease != lease_) return;
        fresh_.insert(chunkKey(cx, cz));
        queue_.push_back(std::move(j));
        st_.queued = queue_.size();
    }
    cv_.notify_one();
}

void GenCache::putSection(const Key3& k, std::shared_ptr<const std::vector<uint32_t>> words, uint32_t hash) {
    if (!on() || !words) return;
    Lease lease;
    { std::unique_lock<std::mutex> l(mu_); if (!on_) return; lease = lease_; }
    Job j;
    j.k = k;
    j.words = std::move(words);
    j.hash = hash;
    {
        std::unique_lock<std::mutex> l(mu_);
        if (!on_ || lease != lease_) return;
        queue_.push_back(std::move(j));
        st_.queued = queue_.size();
    }
    cv_.notify_one();
}

void GenCache::writerLoop() {
    std::unique_lock<std::mutex> l(mu_);
    for (;;) {
        cv_.wait(l, [&] { return stop_ || !queue_.empty(); });
        if (queue_.empty()) {
            if (stop_) return;
            continue;
        }
        Job j = std::move(queue_.front());
        queue_.pop_front();
        st_.queued = queue_.size();
        busy_ = true;
        std::string dir = dir_;
        bool on = on_;
        l.unlock();
        // (the compression outside the lock)
        std::vector<uint8_t> payload;
        auto t0 = std::chrono::steady_clock::now();
        if (j.type == 4 || j.type == 5) {
            // (a tombstone: no payload)
        } else if (j.type == 2) {
            encodeKinds(j.info, payload);
        } else if (j.isChunk) {
            encodeHeader(j.info.data(), payload);
        } else {
            uint16_t ids[4096];
            unpackSection(j.words->data(), ids);
            uint32_t v[4096];
            for (int i = 0; i < 4096; i++) v[i] = ids[i];
            for (int i = 0; i < 4; i++) payload.push_back(uint8_t(j.hash >> (8 * i)));
            encodeGrid(v, payload);
        }
        double ms = msSince(t0);
        l.lock();
        st_.encodeMs += ms;
        // One record appended to a region's file with mu_ let go (this thread alone appends; the index follows once it is
        // down, under the lock again): the open, the writes and the close are the disk's and the virus scanner's time.
        auto append = [&](const std::string& path, const uint32_t* h, const std::vector<uint8_t>& pl, uint64_t expected) {
            l.unlock();
            std::error_code ec;
            bool exists = std::filesystem::exists(path, ec);
            uint64_t bytes = exists ? std::filesystem::file_size(path, ec) : 0;
            // A failed tail repair or previous short write must not shift every later record's indexed offset.
            FILE* f = !ec && bytes == expected ? std::fopen(path.c_str(), "ab") : nullptr;
            static const uint8_t zero[4] = {0, 0, 0, 0};
            uint32_t padN = pad4(uint32_t(pl.size())) - uint32_t(pl.size());
            bool ok = f && std::fwrite(h, 4, HEAD, f) == HEAD && (pl.empty() || std::fwrite(pl.data(), 1, pl.size(), f) == pl.size()) &&
                      std::fwrite(zero, 1, padN, f) == padN;
            if (f && std::fclose(f) != 0) ok = false;
            l.lock();
            return ok;
        };
        if (on && dir == dir_ && on_ && j.type == 4) {
            uint64_t rk = regionOf(j.k.x, j.k.z);
            Region& r = region(l, rk);
            r.chunks.erase(chunkKey(j.k.x, j.k.z));
            for (int s = 0; s < 40; s++) r.secs.erase(Key3{0, j.k.x, s - 4, j.k.z});
            uint32_t h[HEAD] = {MAGIC, 4u, uint32_t(j.k.x), 0u, uint32_t(j.k.z), 0u};
            bool ok = append(regionPath(rk), h, payload, r.fileBytes);
            if (ok) {
                r.fileBytes += uint64_t(HEAD) * 4;
                st_.fileBytes += uint64_t(HEAD) * 4;
            }
        } else if (on && dir == dir_ && on_ && j.type == 5) {
            uint64_t rk = farRegionOf(j.k.lod, j.k.x, j.k.z);
            Region& r = region(l, rk);
            eraseFarTile(r, j.k.lod, j.k.x, j.k.z);
            uint32_t h[HEAD] = {MAGIC, 5u, uint32_t(j.k.x), uint32_t(j.k.lod), uint32_t(j.k.z), 0u};
            bool ok = append(regionPath(rk), h, payload, r.fileBytes);
            if (ok) {
                r.fileBytes += uint64_t(HEAD) * 4;
                st_.fileBytes += uint64_t(HEAD) * 4;
            }
        } else if (on && dir == dir_ && on_ && j.type >= 2) {
            // (the far terrain's: a tile's kinds, a coarse chunk)
            int tx = j.type == 2 ? j.k.x : tdiv(j.k.x), tz = j.type == 2 ? j.k.z : tdiv(j.k.z);
            uint64_t rk = farRegionOf(j.k.lod, tx, tz);
            Region& r = region(l, rk);
            // (a record is written unless one is there; true data also over a stand-in's)
            bool have;
            if (j.type == 2) {
                auto it = r.tiles.find(tileKey(tx, tz));
                have = it != r.tiles.end() && (j.standIn || it->second.trueData);
            } else {
                have = r.coarse.count(j.k) != 0 && (j.standIn || r.coarseTrue.count(j.k) != 0);
            }
            if (!have) {
                uint32_t mixed = 0;
                if (j.type == 2)
                    for (uint32_t v : j.info) mixed += v == 0u;
                uint32_t tru = j.standIn ? 0u : 0x80000000u;
                uint32_t h[HEAD] = {MAGIC, uint32_t(j.type) | (j.type == 2 ? mixed << 8 | tru : 0u), uint32_t(j.k.x),
                                    j.type == 2 ? uint32_t(j.k.lod) : uint32_t(j.k.y) | uint32_t(j.k.lod) << 16 | tru, uint32_t(j.k.z),
                                    uint32_t(payload.size())};
                bool ok = append(regionPath(rk), h, payload, r.fileBytes);
                if (ok) {
                    dropOldSets();
                    Loc loc{r.fileBytes + HEAD * 4, uint32_t(payload.size())};
                    if (j.type == 2) r.tiles[tileKey(tx, tz)] = {loc, mixed, !j.standIn};
                    else {
                        if (r.coarse.emplace(j.k, loc).second) r.baked[tileKey(tx, tz)]++;
                        else r.coarse[j.k] = loc;
                        if (!j.standIn) r.coarseTrue.insert(j.k);
                    }
                    uint64_t add = uint64_t(HEAD) * 4 + pad4(uint32_t(payload.size()));
                    r.fileBytes += add;
                    st_.fileBytes += add;
                    st_.farWrites++;
                }
            }
        } else if (on && dir == dir_ && on_) {
            int cx = j.isChunk ? j.k.x : fdiv(j.k.x, 1), cz = j.k.z;
            uint64_t rk = regionOf(cx, cz);
            Region& r = region(l, rk);
            bool have = j.isChunk ? r.chunks.count(chunkKey(cx, cz)) != 0 : r.secs.count(j.k) != 0;
            if (!have) {
                uint64_t mask = 0;
                if (j.isChunk)
                    for (int s = 0; s < 40; s++)
                        if (j.info[4 + s * 4 + 2] == KIND_BLOCKS) mask |= uint64_t(1) << s;
                uint32_t h[HEAD] = {MAGIC, j.isChunk ? uint32_t(mask >> 32) << 8 : 1u, uint32_t(j.k.x),
                                    j.isChunk ? uint32_t(mask) : uint32_t(j.k.y), uint32_t(j.k.z), uint32_t(payload.size())};
                bool ok = append(regionPath(rk), h, payload, r.fileBytes);
                if (ok) {
                    dropOldSets();
                    Loc loc{r.fileBytes + HEAD * 4, uint32_t(payload.size())};
                    if (j.isChunk) r.chunks[chunkKey(cx, cz)] = {loc, mask}, st_.chunks++, st_.chunkWrites++;
                    else r.secs[j.k] = loc, st_.sections++, st_.sectionWrites++;
                    uint64_t add = uint64_t(HEAD) * 4 + pad4(uint32_t(payload.size()));
                    r.fileBytes += add;
                    st_.fileBytes += add;
                }
            }
        }
        busy_ = false;
        if (queue_.empty()) idle_.notify_all();
    }
}

void GenCache::flush() {
    std::unique_lock<std::mutex> l(mu_);
    idle_.wait(l, [&] { return queue_.empty() && !busy_; });
}

bool GenCache::readBytes(uint64_t rk, const Loc& loc, std::vector<uint8_t>& out, const Lease& lease) {
    // (a region's file read whole on its first read: one open a region, not one a record; a record written since lies
    // past that copy: read alone. The copies within a budget, the least recently read dropped. The file is read with mu_
    // let go; two threads that read it at once keep one of the copies)
    std::shared_ptr<std::vector<uint8_t>> data;
    std::string path;
    bool load = false;
    const std::atomic<bool>* release = nullptr; std::atomic<bool>* held = nullptr;
    {
        std::unique_lock<std::mutex> l(mu_);
        if (!on_ || !lease || lease != lease_) return false;
        Region& r = region(l, rk);
        release = readRelease_; held = readHeld_;
        path = regionPath(rk);
        if (r.data) {
            r.dataUse = ++dataClock_;
            data = r.data;
        } else {
            load = true;
        }
    }
    if (release && held) {
        held->store(true);
        while (!release->load()) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    }
    if (load) {
        auto d = std::make_shared<std::vector<uint8_t>>();
        FILE* f = std::fopen(path.c_str(), "rb");
        if (!f) return false;
        std::fseek(f, 0, SEEK_END);
        long n = std::ftell(f);
        std::fseek(f, 0, SEEK_SET);
        d->resize(size_t(std::max(n, 0L)));
        size_t got = d->empty() ? 0 : std::fread(d->data(), 1, d->size(), f);
        std::fclose(f);
        d->resize(got);
        std::unique_lock<std::mutex> l(mu_);
        if (!on_ || lease != lease_) return false;
        Region& r = region(l, rk);
        if (!r.data) {
            r.data = d;
            dataBytes_ += got;
            while (dataBytes_ > DATA_BUDGET) {
                Region* lru = nullptr;
                for (auto& [k, o] : regions_)
                    if (o.data && &o != &r && (!lru || o.dataUse < lru->dataUse)) lru = &o;
                if (!lru) break;
                dataBytes_ -= std::min<uint64_t>(dataBytes_, lru->data->size());
                lru->data.reset();
            }
        }
        r.dataUse = ++dataClock_;
        data = r.data;
    }
    if (data->size() >= loc.offset + loc.bytes) {
        out.assign(data->begin() + ptrdiff_t(loc.offset), data->begin() + ptrdiff_t(loc.offset + loc.bytes));
        std::unique_lock<std::mutex> l(mu_);
        return on_ && lease == lease_;
    }
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    out.resize(loc.bytes);
    bool ok = std::fseek(f, long(loc.offset), SEEK_SET) == 0 && std::fread(out.data(), 1, out.size(), f) == out.size();
    std::fclose(f);
    std::unique_lock<std::mutex> l(mu_);
    return ok && on_ && lease == lease_;
}

bool GenCache::hasChunk(int cx, int cz) {
    if (!on()) return false;
    std::unique_lock<std::mutex> l(mu_);
    if (!on_) return false;
    return region(l, regionOf(cx, cz)).chunks.count(chunkKey(cx, cz)) != 0;
}

bool GenCache::forget(int cx, int cz) {
    if (!on()) return false;
    {
        std::unique_lock<std::mutex> l(mu_);
        if (!on_) return false;
        if (!fresh_.erase(chunkKey(cx, cz))) return false;  // (from disk, or never: kept)
        Region& r = region(l, regionOf(cx, cz));
        r.chunks.erase(chunkKey(cx, cz));
        for (int s = 0; s < 40; s++) r.secs.erase(Key3{0, cx, s - 4, cz});
        Job j;
        j.type = 4;
        j.k = Key3{0, cx, 0, cz};
        queue_.push_back(std::move(j));
        st_.queued = queue_.size();
    }
    cv_.notify_one();
    return true;
}

bool GenCache::complete(int cx, int cz) {
    if (!on()) return false;
    std::unique_lock<std::mutex> l(mu_);
    if (!on_) return false;
    Region& r = region(l, regionOf(cx, cz));
    auto it = r.chunks.find(chunkKey(cx, cz));
    if (it == r.chunks.end()) return false;
    for (int s = 0; s < 40; s++)
        if ((it->second.blocks >> s & 1u) && !r.secs.count(Key3{0, cx, s - 4, cz})) return false;
    return true;
}

void GenCache::invalidateChunk(const Key3& k, const Loc& failed, const Lease& lease, bool header) {
    {
        std::unique_lock<std::mutex> l(mu_);
        if (!on_ || !lease || lease != lease_) return;
        Region& r = region(l, regionOf(k.x, k.z));
        Loc current;
        if (header) {
            auto it = r.chunks.find(chunkKey(k.x, k.z));
            if (it == r.chunks.end()) return;
            current = it->second.loc;
        } else {
            auto it = r.secs.find(k);
            if (it == r.secs.end()) return;
            current = it->second;
        }
        if (current.offset != failed.offset || current.bytes != failed.bytes) return;  // (another reader already repaired it)
        r.chunks.erase(chunkKey(k.x, k.z));
        for (int s = 0; s < 40; s++) r.secs.erase(Key3{0, k.x, s - 4, k.z});
        fresh_.erase(chunkKey(k.x, k.z));
        Job j;
        j.type = 4;
        j.k = Key3{0, k.x, 0, k.z};
        queue_.push_back(std::move(j));
        st_.queued = queue_.size();
    }
    cv_.notify_one();
}

bool GenCache::readSection(const Key3& k, std::shared_ptr<const std::vector<uint32_t>>& words) {
    words.reset();
    if (!on() || k.lod != 0 || k.y < -4 || k.y >= 36) return false;
    Lease lease;
    { std::unique_lock<std::mutex> l(mu_); if (!on_) return false; lease = lease_; }
    uint32_t info[INFO_WORDS];
    if (!chunk(k.x, k.z, info)) return false;
    uint32_t kind = info[4 + (k.y + 4) * 4 + 2];
    if (kind == KIND_AIR) { std::unique_lock<std::mutex> l(mu_); return on_ && lease == lease_; }
    if (kind != KIND_BLOCKS) return false;
    words = section(k);
    { std::unique_lock<std::mutex> l(mu_); if (!on_ || lease != lease_) { words.reset(); return false; } }
    return words != nullptr;
}

bool GenCache::chunk(int cx, int cz, uint32_t* info) {
    if (!on()) return false;
    uint64_t rk = regionOf(cx, cz);
    Loc loc;
    Lease lease;
    {
        std::unique_lock<std::mutex> l(mu_);
        if (!on_) return false;
        Region& r = region(l, rk);
        auto it = r.chunks.find(chunkKey(cx, cz));
        if (it == r.chunks.end()) {
            st_.chunkMisses++;
            return false;
        }
        loc = it->second.loc;
        lease = lease_;
    }
    std::vector<uint8_t> b;
    if (!readBytes(rk, loc, b, lease) || !decodeHeader(b, info)) {
        invalidateChunk(Key3{0, cx, 0, cz}, loc, lease, true);
        return false;
    }
    std::unique_lock<std::mutex> l(mu_);
    if (!on_ || lease != lease_) return false;
    Region& r = region(l, rk);
    for (int s = 0; s < 40; s++)
        if (info[4 + s * 4 + 2] == KIND_BLOCKS && !r.secs.count(Key3{0, cx, s - 4, cz})) {
            st_.chunkMisses++;
            st_.chunkIncomplete++;
            return false;  // (its sections not all baked yet)
        }
    st_.chunkHits++;
    return true;
}

std::shared_ptr<const std::vector<uint32_t>> GenCache::section(const Key3& k) {
    if (!on()) return nullptr;
    uint64_t rk = regionOf(k.x, k.z);
    Loc loc;
    Lease lease;
    {
        std::unique_lock<std::mutex> l(mu_);
        if (!on_) return nullptr;
        Region& r = region(l, rk);
        auto it = r.secs.find(k);
        if (it == r.secs.end()) return nullptr;
        loc = it->second;
        lease = lease_;
    }
    std::vector<uint8_t> b;
    if (!readBytes(rk, loc, b, lease)) {
        invalidateChunk(k, loc, lease);
        return nullptr;
    }
    auto t0 = std::chrono::steady_clock::now();
    uint16_t ids[4096];
    if (!decodeChecked(b, ids)) {
        {
            std::unique_lock<std::mutex> l(mu_);
            if (!on_ || lease != lease_) return nullptr;
            st_.hashErrors++;
        }
        invalidateChunk(k, loc, lease);
        return nullptr;
    }
    auto w = std::make_shared<const std::vector<uint32_t>>(packSection(ids));
    double ms = msSince(t0);
    std::unique_lock<std::mutex> l(mu_);
    if (!on_ || lease != lease_) return nullptr;
    st_.decodeMs += ms;
    st_.sectionReads++;
    return w;
}

void GenCache::putFarTile(int lod, int tx, int tz, const std::vector<uint32_t>& kinds, bool standIn) {
    if (!on()) return;
    Lease lease;
    { std::unique_lock<std::mutex> l(mu_); if (!on_) return; lease = lease_; }
    Job j;
    j.type = 2;
    j.standIn = standIn;
    j.k = Key3{lod, tx, 0, tz};
    j.info = kinds;
    {
        std::unique_lock<std::mutex> l(mu_);
        if (!on_ || lease != lease_) return;
        queue_.push_back(std::move(j));
    }
    cv_.notify_one();
}

void GenCache::putCoarse(const Key3& k, std::shared_ptr<const std::vector<uint32_t>> words, uint32_t hash, bool standIn) {
    if (!on() || !words) return;
    Lease lease;
    { std::unique_lock<std::mutex> l(mu_); if (!on_) return; lease = lease_; }
    Job j;
    j.type = 3;
    j.standIn = standIn;
    j.k = k;
    j.words = std::move(words);
    j.hash = hash;
    {
        std::unique_lock<std::mutex> l(mu_);
        if (!on_ || lease != lease_) return;
        queue_.push_back(std::move(j));
    }
    cv_.notify_one();
}

bool GenCache::farKnown(int lod, int tx, int tz) {
    if (!on()) return false;
    std::unique_lock<std::mutex> l(mu_);
    if (!on_) return false;
    Region& r = region(l, farRegionOf(lod, tx, tz));
    auto it = r.tiles.find(tileKey(tx, tz));
    if (it == r.tiles.end()) return false;
    auto b = r.baked.find(tileKey(tx, tz));
    return it->second.mixed == 0 || (b != r.baked.end() && b->second >= it->second.mixed);
}

bool GenCache::farStandIn(int lod, int tx, int tz) {
    if (!farKnown(lod, tx, tz)) return false;
    std::unique_lock<std::mutex> l(mu_);
    if (!on_) return false;
    auto& r = region(l, farRegionOf(lod, tx, tz));
    auto tile = r.tiles.find(tileKey(tx, tz));
    return tile != r.tiles.end() && !tile->second.trueData;
}

bool GenCache::farTile(int lod, int tx, int tz, std::vector<uint32_t>& kinds) {
    if (!farKnown(lod, tx, tz)) {
        std::unique_lock<std::mutex> l(mu_);
        st_.farMisses++;
        return false;
    }
    uint64_t rk = farRegionOf(lod, tx, tz);
    Loc loc;
    Lease lease;
    {
        std::unique_lock<std::mutex> l(mu_);
        if (!on_) return false;
        auto& r = region(l, rk);
        auto tile = r.tiles.find(tileKey(tx, tz));
        if (tile == r.tiles.end()) return false;
        auto baked = r.baked.find(tileKey(tx, tz));
        if (tile->second.mixed && (baked == r.baked.end() || baked->second < tile->second.mixed)) return false;
        loc = tile->second.loc;
        lease = lease_;
    }
    std::vector<uint8_t> b;
    if (!readBytes(rk, loc, b, lease) || !decodeKinds(b, kinds)) return false;
    std::unique_lock<std::mutex> l(mu_);
    if (!on_ || lease != lease_) return false;
    st_.farHits++;
    return true;
}

std::shared_ptr<const std::vector<uint32_t>> GenCache::coarse(const Key3& k) {
    if (!on()) return nullptr;
    uint64_t rk = farRegionOf(k.lod, tdiv(k.x), tdiv(k.z));
    Loc loc;
    Lease lease;
    {
        std::unique_lock<std::mutex> l(mu_);
        if (!on_) return nullptr;
        Region& r = region(l, rk);
        auto it = r.coarse.find(k);
        if (it == r.coarse.end()) return nullptr;
        loc = it->second;
        lease = lease_;
    }
    std::vector<uint8_t> b;
    if (!readBytes(rk, loc, b, lease)) return nullptr;
    uint16_t ids[4096];
    if (!decodeChecked(b, ids)) {
        std::unique_lock<std::mutex> l(mu_);
        if (!on_ || lease != lease_) return nullptr;
        st_.hashErrors++;
        return nullptr;
    }
    auto words = std::make_shared<const std::vector<uint32_t>>(packSection(ids));
    std::unique_lock<std::mutex> l(mu_);
    return on_ && lease == lease_ ? words : nullptr;
}

GenCache::Stats GenCache::stats() {
    std::unique_lock<std::mutex> l(mu_, std::try_to_lock);
    if (l.owns_lock()) {
        Stats s = st_;
        s.on = on_;
        s.fingerprint = fp_;
        s.queued = queue_.size();
        l.unlock();
        std::lock_guard<std::mutex> g(snapMu_);
        snap_ = s;
        return s;
    }
    std::lock_guard<std::mutex> g(snapMu_);  // (the lock is busy: the last numbers, never a wait)
    return snap_;
}

void GenCache::holdLockForTest(const std::atomic<bool>& release, std::atomic<bool>& held) {
    std::lock_guard<std::mutex> l(mu_);
    held.store(true);
    while (!release.load()) std::this_thread::sleep_for(std::chrono::milliseconds(1));
}

void GenCache::pruneBarrierForTest(const std::atomic<bool>* release, std::atomic<bool>* held) {
    std::lock_guard<std::mutex> l(mu_); pruneRelease_ = release; pruneHeld_ = held;
}
void GenCache::readBarrierForTest(const std::atomic<bool>* release, std::atomic<bool>* held) {
    std::lock_guard<std::mutex> l(mu_); readRelease_ = release; readHeld_ = held;
}
void GenCache::admissionSignalForTest(std::atomic<bool>* admitted) {
    std::lock_guard<std::mutex> l(mu_); admitted_ = admitted;
}

}  // namespace atlas
