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
        fs::remove_all(root);
        std::printf("PASS cache checksum recovery, invalidation, regeneration, known air, and torn payload/header recovery\n");
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "FAIL %s (fixture retained at %s)\n", error.what(), root.string().c_str());
        return 1;
    }
}
