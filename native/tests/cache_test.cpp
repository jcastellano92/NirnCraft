// Recovery from an actual corrupt cache record, without a renderer or GPU.
#include <array>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <stdexcept>
#include <thread>
#include <vector>

#include "gencache.hpp"
#include "sectionpack.hpp"

using namespace atlas;
namespace fs = std::filesystem;

static void check(bool ok, const char* what) {
    if (!ok) throw std::runtime_error(what);
}

static void corruptSection(const fs::path& root) {
    for (const auto& entry : fs::recursive_directory_iterator(root)) {
        if (entry.path().extension() != ".gch") continue;
        std::fstream file(entry.path(), std::ios::binary | std::ios::in | std::ios::out);
        uint32_t header[6];
        while (file.read(reinterpret_cast<char*>(header), sizeof header)) {
            if ((header[1] & 255u) == 1u) {
                auto payload = file.tellg();
                char value;
                check(bool(file.read(&value, 1)), "section has no checksum byte");
                value ^= 1;  // corrupt its stored checksum, leaving the block IDs and index intact
                file.seekp(payload);
                check(bool(file.write(&value, 1)), "could not corrupt section checksum");
                return;
            }
            file.seekg((header[5] + 3u) & ~3u, std::ios::cur);
        }
    }
    throw std::runtime_error("section record was not written");
}

int main() {
    const fs::path root = fs::current_path() / ".work" /
        ("cache-recovery-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    try {
        fs::create_directories(root);
        uint16_t ids[4096];
        for (auto& id : ids) id = 7;
        const uint32_t hash = sectionHash(ids);
        const auto packed = std::make_shared<const std::vector<uint32_t>>(packSection(ids));
        uint32_t info[GenCache::INFO_WORDS]{};
        info[4 + 4 * 4] = hash;  // section y=0 (the fifth of the -4..35 rows)
        info[4 + 4 * 4 + 2] = GenCache::KIND_BLOCKS;
        auto writeChunk = [&](GenCache& cache) {
            cache.putChunk(0, 0, info);
            cache.putSection({0, 0, 0, 0}, packed, hash);
            cache.flush();
        };
        {
            GenCache cache;
            cache.configure(root.string(), 1);
            writeChunk(cache);
            check(cache.complete(0, 0), "valid chunk not complete");
        }
        corruptSection(root);
        {
            GenCache cache;
            cache.configure(root.string(), 1);
            uint32_t row[GenCache::INFO_WORDS];
            check(cache.chunk(0, 0, row), "corruption fixture must still pass index lookup");
            std::shared_ptr<const std::vector<uint32_t>> words;
            check(cache.readSection({0, 0, 1, 0}, words) && !words, "known air must still succeed");
            std::array<bool, 8> refused{};
            std::array<std::thread, 8> readers;
            std::atomic<int> ready{0};
            for (size_t i = 0; i < readers.size(); i++)
                readers[i] = std::thread([&, i] {
                    std::shared_ptr<const std::vector<uint32_t>> read;
                    ready.fetch_add(1);
                    while (ready.load() != int(readers.size())) std::this_thread::yield();
                    refused[i] = !cache.readSection({0, 0, 0, 0}, read) && !read;
                });
            for (auto& reader : readers) reader.join();
            for (bool missed : refused) check(missed, "corrupt blocks must be a miss for every concurrent reader");
            check(cache.stats().hashErrors >= 1, "real checksum failure was not observed");
            check(!cache.complete(0, 0), "corrupt chunk must allow regeneration");
            check(!cache.chunk(0, 0, row), "corrupt chunk must leave the lookup index");
            cache.flush();
        }
        {
            GenCache cache;
            cache.configure(root.string(), 1);
            uint32_t row[GenCache::INFO_WORDS];
            check(!cache.chunk(0, 0, row), "invalidation must survive a restart");
            writeChunk(cache);  // the same generator output must be admitted again, not rejected as already cached
            std::shared_ptr<const std::vector<uint32_t>> words;
            check(cache.readSection({0, 0, 0, 0}, words) && words, "regenerated blocks not readable");
            uint16_t got[4096];
            unpackSection(words->data(), got);
            for (auto id : got) check(id == 7, "regenerated block ID changed");
        }
        {
            GenCache cache;
            cache.configure(root.string(), 1);
            std::shared_ptr<const std::vector<uint32_t>> words;
            check(cache.readSection({0, 0, 0, 0}, words) && words, "repaired cache failed on restart");
            check(cache.readSection({0, 0, 1, 0}, words) && !words, "air failed after repair");
        }
        fs::path region;
        for (const auto& entry : fs::recursive_directory_iterator(root))
            if (entry.path().extension() == ".gch") region = entry.path();
        check(!region.empty(), "repaired region missing");
        fs::resize_file(region, fs::file_size(region) - 1);  // simulate a process stopping in its last section write
        {
            GenCache cache;
            cache.configure(root.string(), 1);
            check(!cache.complete(0, 0), "torn payload must not be considered complete");
            writeChunk(cache);
            std::shared_ptr<const std::vector<uint32_t>> words;
            check(cache.readSection({0, 0, 0, 0}, words) && words, "torn section could not be regenerated");
        }
        {
            // Also a process stopped part way through its next header: future appends must remain discoverable.
            std::ofstream tail(region, std::ios::binary | std::ios::app);
            tail.write("bad", 3);
        }
        {
            GenCache cache;
            cache.configure(root.string(), 1);
            check(cache.complete(0, 0), "whole records before torn header must survive");
            cache.putChunk(1, 0, info);
            cache.putSection({0, 1, 0, 0}, packed, hash);
            cache.flush();
        }
        {
            GenCache cache;
            cache.configure(root.string(), 1);
            check(cache.complete(0, 0) && cache.complete(1, 0), "append after torn tail lost records on restart");
            std::shared_ptr<const std::vector<uint32_t>> words;
            check(cache.readSection({0, 1, 0, 0}, words) && words, "appended section after torn tail unreadable");
        }
        {
            // EXP-6 microbenchmark: 100,000 packSection calls (baseline heap where(65536) vs thread_local scratch)
            // plus warm region scan + 8-thread 1,024-section ingest read.
            uint16_t mixed[4096];
            for (int i = 0; i < 4096; i++) mixed[i] = uint16_t((i * 73 + (i >> 4) * 19) & 15);
            auto packBaseline = [](const uint16_t* s) {
                std::vector<uint16_t> pal;
                std::vector<int16_t> where(65536, -1);
                for (int i = 0; i < 4096 && pal.size() <= 256; i++)
                    if (where[s[i]] < 0) where[s[i]] = int16_t(pal.size()), pal.push_back(s[i]);
                uint32_t n = uint32_t(pal.size());
                uint32_t bits = n == 1 ? 0 : n <= 2 ? 1 : n <= 4 ? 2 : n <= 16 ? 4 : n <= 256 ? 8 : 16;
                std::vector<uint32_t> w(1 + (n + 1) / 2 + 4096 * bits / 32, 0u);
                w[0] = bits | n << 8;
                for (uint32_t k = 0; k < n; k++) w[1 + k / 2] |= uint32_t(pal[k]) << (16 * (k & 1));
                size_t at = 1 + (n + 1) / 2;
                for (int i = 0; i < 4096; i++) {
                    uint32_t bit = uint32_t(i) * bits;
                    w[at + bit / 32] |= uint32_t(where[s[i]]) << (bit & 31u);
                }
                return w;
            };
            constexpr int N_PACK = 100000;
            size_t sink = 0;
            auto t0 = std::chrono::steady_clock::now();
            for (int i = 0; i < N_PACK; i++) sink += packBaseline(mixed).size();
            double basePackMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            t0 = std::chrono::steady_clock::now();
            for (int i = 0; i < N_PACK; i++) sink += packSection(mixed).size();
            double optPackMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();

            const fs::path benchRoot = root / "bench";
            fs::create_directories(benchRoot);
            const uint32_t mHash = sectionHash(mixed);
            const auto mPacked = std::make_shared<const std::vector<uint32_t>>(packSection(mixed));
            uint32_t mInfo[GenCache::INFO_WORDS]{};
            for (int s = 0; s < 16; s++) {
                mInfo[4 + s * 4] = mHash;
                mInfo[4 + s * 4 + 2] = GenCache::KIND_BLOCKS;
            }
            {
                GenCache writer;
                writer.configure(benchRoot.string(), 42);
                for (int c = 0; c < 64; c++) {
                    int cx = c & 7, cz = c >> 3;
                    writer.putChunk(cx, cz, mInfo);
                    for (int s = 0; s < 16; s++)
                        writer.putSection({0, cx, s - 4, cz}, mPacked, mHash);
                }
                writer.flush();
            }
            t0 = std::chrono::steady_clock::now();
            {
                GenCache warm;
                warm.configure(benchRoot.string(), 42);
                std::atomic<int> next{0};
                std::array<std::thread, 8> pool;
                for (auto& th : pool)
                    th = std::thread([&] {
                        for (int idx; (idx = next.fetch_add(1)) < 1024;) {
                            int c = idx >> 4, s = idx & 15;
                            auto w = warm.section({0, c & 7, s - 4, c >> 3});
                            check(w != nullptr && !w->empty(), "warm section read failed");
                        }
                    });
                for (auto& th : pool) th.join();
            }
            double warmReadMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
            std::printf("BENCH exp6 packSection_100k_baseline_ms=%.1f packSection_100k_opt_ms=%.1f speedup=%.2fx warm_1024_sections_8t_ms=%.1f (sink=%zu)\n",
                        basePackMs, optPackMs, basePackMs / std::max(0.001, optPackMs), warmReadMs, sink);
        }
        fs::remove_all(root);
        std::printf("PASS cache checksum recovery, invalidation, regeneration, known air, and torn payload/header recovery\n");
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "FAIL %s (fixture retained at %s)\n", error.what(), root.string().c_str());
        return 1;
    }
}
