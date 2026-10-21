#include "gencache.hpp"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <filesystem>

#include "sectioncodec.hpp"
#include "sectionpack.hpp"

namespace atlas {

namespace {
// a record: magic, type (0 a chunk's header, its blocks mask's bits 32-39 in bits 8-15; 1 a section), x, y (a header:
// its blocks mask's bits 0-31), z, payload bytes; the payload padded to 4 bytes.
// A header's payload: its 3 words (lowest ground, highest top, biome), the 40 sections' kinds (a byte each), how many
// distinct hashes, then per section the index of its hash (a byte) and the hashes (lo, hi words). A section's: its ids
// compressed (sectioncodec.hpp).
constexpr uint32_t MAGIC = 0x3147434Eu;  // "NCG1"
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
    flush();
    {
        std::lock_guard<std::mutex> l(mu_);
        stop_ = true;
    }
    cv_.notify_all();
    if (writer_.joinable()) writer_.join();
}

uint64_t GenCache::regionOf(int cx, int cz) { return uint64_t(uint32_t(fdiv(cx, 32))) << 32 | uint32_t(fdiv(cz, 32)); }

std::string GenCache::regionPath(uint64_t rk) const {
    char b[64];
    std::snprintf(b, sizeof b, "/r.%d.%d.gch", int32_t(uint32_t(rk >> 32)), int32_t(uint32_t(rk)));
    return dir_ + b;
}

void GenCache::configure(const std::string& root, uint64_t fingerprint) {
    flush();
    std::lock_guard<std::mutex> l(mu_);
    if (!root.empty() && fingerprint && root + "#" + std::to_string(fingerprint) == dir_ + "#" + std::to_string(fp_) &&
        on_.load())
        return;
    regions_.clear();
    on_ = false;
    fp_ = fingerprint;
    dir_.clear();
    st_ = Stats{};
    if (root.empty() || !fingerprint) return;
    char sub[48];
    std::snprintf(sub, sizeof sub, "/gen-%016llx", (unsigned long long)fingerprint);
    std::error_code ec;
    std::filesystem::create_directories(root + sub, ec);
    if (ec) return;
    dir_ = root + sub;
    for (auto& e : std::filesystem::directory_iterator(dir_, ec))
        if (e.path().extension() == ".gch") st_.fileBytes += uint64_t(e.file_size(ec));
    on_ = true;
}

GenCache::Region& GenCache::region(uint64_t rk) {
    Region& r = regions_[rk];
    if (r.scanned) return r;
    r.scanned = true;
    FILE* f = std::fopen(regionPath(rk).c_str(), "rb");
    if (!f) return r;
    uint64_t at = 0;
    uint32_t h[HEAD];
    while (std::fread(h, 4, HEAD, f) == HEAD && h[0] == MAGIC && h[5] <= MAX_PAYLOAD) {
        Loc loc{at + HEAD * 4, h[5]};
        if ((h[1] & 0xFFu) == 0) r.chunks[chunkKey(int32_t(h[2]), int32_t(h[4]))] = {loc, uint64_t(h[3]) | uint64_t(h[1] >> 8 & 0xFFu) << 32};
        else r.secs[Key3{0, int32_t(h[2]), int32_t(h[3]), int32_t(h[4])}] = loc;
        at += uint64_t(HEAD) * 4 + pad4(h[5]);
        if (std::fseek(f, long(at), SEEK_SET) != 0) break;
    }
    r.fileBytes = at;
    std::fclose(f);
    st_.chunks += r.chunks.size();
    st_.sections += r.secs.size();
    return r;
}

void GenCache::putChunk(int cx, int cz, const uint32_t* info) {
    if (!on()) return;
    Job j;
    j.isChunk = true;
    j.k = Key3{0, cx, 0, cz};
    j.info.assign(info, info + INFO_WORDS);
    {
        std::lock_guard<std::mutex> l(mu_);
        queue_.push_back(std::move(j));
        st_.queued = queue_.size();
    }
    cv_.notify_one();
}

void GenCache::putSection(const Key3& k, std::shared_ptr<const std::vector<uint32_t>> words) {
    if (!on() || !words) return;
    Job j;
    j.k = k;
    j.words = std::move(words);
    {
        std::lock_guard<std::mutex> l(mu_);
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
        if (j.isChunk) {
            encodeHeader(j.info.data(), payload);
        } else {
            uint16_t ids[4096];
            unpackSection(j.words->data(), ids);
            uint32_t v[4096];
            for (int i = 0; i < 4096; i++) v[i] = ids[i];
            encodeGrid(v, payload);
        }
        double ms = msSince(t0);
        l.lock();
        st_.encodeMs += ms;
        if (on && dir == dir_ && on_) {
            int cx = j.isChunk ? j.k.x : fdiv(j.k.x, 1), cz = j.k.z;
            uint64_t rk = regionOf(cx, cz);
            Region& r = region(rk);
            bool have = j.isChunk ? r.chunks.count(chunkKey(cx, cz)) != 0 : r.secs.count(j.k) != 0;
            if (!have) {
                uint64_t mask = 0;
                if (j.isChunk)
                    for (int s = 0; s < 40; s++)
                        if (j.info[4 + s * 4 + 2] == KIND_BLOCKS) mask |= uint64_t(1) << s;
                uint32_t h[HEAD] = {MAGIC, j.isChunk ? uint32_t(mask >> 32) << 8 : 1u, uint32_t(j.k.x),
                                    j.isChunk ? uint32_t(mask) : uint32_t(j.k.y), uint32_t(j.k.z), uint32_t(payload.size())};
                FILE* f = std::fopen(regionPath(rk).c_str(), "ab");
                static const uint8_t zero[4] = {0, 0, 0, 0};
                uint32_t padN = pad4(uint32_t(payload.size())) - uint32_t(payload.size());
                bool ok = f && std::fwrite(h, 4, HEAD, f) == HEAD && std::fwrite(payload.data(), 1, payload.size(), f) == payload.size() &&
                          std::fwrite(zero, 1, padN, f) == padN;
                if (f) std::fclose(f);
                if (ok) {
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

bool GenCache::readBytes(uint64_t rk, const Loc& loc, std::vector<uint8_t>& out) {
    // (the region's file read whole once, again when a record lies past it: one open a region, not one a record)
    std::shared_ptr<std::vector<uint8_t>> data;
    {
        std::lock_guard<std::mutex> l(mu_);
        Region& r = region(rk);
        if (!r.data || r.data->size() < loc.offset + loc.bytes) {
            auto d = std::make_shared<std::vector<uint8_t>>();
            FILE* f = std::fopen(regionPath(rk).c_str(), "rb");
            if (!f) return false;
            std::fseek(f, 0, SEEK_END);
            long n = std::ftell(f);
            std::fseek(f, 0, SEEK_SET);
            d->resize(size_t(std::max(n, 0L)));
            size_t got = d->empty() ? 0 : std::fread(d->data(), 1, d->size(), f);
            std::fclose(f);
            d->resize(got);
            r.data = d;
        }
        data = r.data;
    }
    if (data->size() < loc.offset + loc.bytes) return false;
    out.assign(data->begin() + ptrdiff_t(loc.offset), data->begin() + ptrdiff_t(loc.offset + loc.bytes));
    return true;
}

bool GenCache::hasChunk(int cx, int cz) {
    if (!on()) return false;
    std::lock_guard<std::mutex> l(mu_);
    return region(regionOf(cx, cz)).chunks.count(chunkKey(cx, cz)) != 0;
}

bool GenCache::complete(int cx, int cz) {
    if (!on()) return false;
    std::lock_guard<std::mutex> l(mu_);
    Region& r = region(regionOf(cx, cz));
    auto it = r.chunks.find(chunkKey(cx, cz));
    if (it == r.chunks.end()) return false;
    for (int s = 0; s < 40; s++)
        if ((it->second.blocks >> s & 1u) && !r.secs.count(Key3{0, cx, s - 4, cz})) return false;
    return true;
}

bool GenCache::chunk(int cx, int cz, uint32_t* info) {
    if (!on()) return false;
    uint64_t rk = regionOf(cx, cz);
    Loc loc;
    {
        std::lock_guard<std::mutex> l(mu_);
        Region& r = region(rk);
        auto it = r.chunks.find(chunkKey(cx, cz));
        if (it == r.chunks.end()) {
            st_.chunkMisses++;
            return false;
        }
        loc = it->second.loc;
    }
    std::vector<uint8_t> b;
    if (!readBytes(rk, loc, b) || !decodeHeader(b, info)) return false;
    std::lock_guard<std::mutex> l(mu_);
    Region& r = region(rk);
    for (int s = 0; s < 40; s++)
        if (info[4 + s * 4 + 2] == KIND_BLOCKS && !r.secs.count(Key3{0, cx, s - 4, cz})) {
            st_.chunkMisses++;
            return false;  // (its sections not all baked yet)
        }
    st_.chunkHits++;
    return true;
}

std::shared_ptr<const std::vector<uint32_t>> GenCache::section(const Key3& k) {
    if (!on()) return nullptr;
    uint64_t rk = regionOf(k.x, k.z);
    Loc loc;
    {
        std::lock_guard<std::mutex> l(mu_);
        Region& r = region(rk);
        auto it = r.secs.find(k);
        if (it == r.secs.end()) return nullptr;
        loc = it->second;
    }
    std::vector<uint8_t> b;
    if (!readBytes(rk, loc, b)) return nullptr;
    auto t0 = std::chrono::steady_clock::now();
    uint32_t v[4096];
    if (!decodeGrid(b.data(), b.size(), v)) return nullptr;
    uint16_t ids[4096];
    for (int i = 0; i < 4096; i++) ids[i] = uint16_t(v[i]);
    auto w = std::make_shared<const std::vector<uint32_t>>(packSection(ids));
    double ms = msSince(t0);
    std::lock_guard<std::mutex> l(mu_);
    st_.decodeMs += ms;
    st_.sectionReads++;
    return w;
}

GenCache::Stats GenCache::stats() {
    std::lock_guard<std::mutex> l(mu_);
    Stats s = st_;
    s.on = on_;
    s.fingerprint = fp_;
    s.queued = queue_.size();
    return s;
}

}  // namespace atlas
