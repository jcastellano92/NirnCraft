// CPU-only optional cache IO: compatibility/corruption cannot reach a driver, and replacement never publishes a partial file.
#include "pipeline_cache_io.hpp"
#include <chrono>
#include <cstdio>
#include <stdexcept>
#include <thread>

namespace io = atlas::pipeline_cache_io;
namespace fs = std::filesystem;
static void check(bool ok, const char* what) { if (!ok) throw std::runtime_error(what); }
static std::vector<uint8_t> payload(const io::Identity& id, uint8_t value = 17) {
    std::vector<uint8_t> data(128, value);
    io::put(data.data(), 32, 4); io::put(data.data() + 4, 1, 4);
    io::put(data.data() + 8, id.vendor, 4); io::put(data.data() + 12, id.device, 4);
    std::memcpy(data.data() + 16, id.uuid.data(), id.uuid.size());
    return data;
}
int main() {
    fs::path root = fs::temp_directory_path() / ("atlas-pipeline-io-" + std::to_string(
        std::chrono::steady_clock::now().time_since_epoch().count()));
    struct Remove { fs::path p; ~Remove() { std::error_code ec; fs::remove_all(p, ec); } } remove{root};
    try {
        fs::create_directories(root);
        const std::string path = (root / "pipelines.bin.feat-store.tree").string();
        io::Identity id{0x10de, 123, 456}; id.uuid[0] = 77; id.shader = 991;
        auto a = payload(id), b = payload(id, 93);
        std::string status;
        check(io::load("", id, status).empty() && status == "disabled", "no base must disable cache IO");
        check(!io::save("", id, a), "empty path must not create a file");
        check(io::save(path, id, a), "initial cache save");
        check(io::load(path, id, status) == a && status == "hit", "exact round trip");
        for (int field = 0; field < 5; ++field) {
            auto other = id;
            if (field == 0) ++other.vendor;
            if (field == 1) ++other.device;
            if (field == 2) ++other.driver;
            if (field == 3) ++other.uuid[0];
            if (field == 4) ++other.shader;
            check(io::load(path, other, status).empty(), "incompatible vendor/device/driver/UUID/shader must miss");
        }
        for (int field = 0; field < 3; ++field) {
            auto bad = a;
            io::put(bad.data() + (field == 1 ? 4 : 0), field == 0 ? 31 : field == 1 ? 2 : bad.size() + 1, 4);
            check(!io::save(path, id, bad), "invalid native header must not be persisted");
        }
        {
            std::fstream f(path, std::ios::binary | std::ios::in | std::ios::out);
            f.seekp(io::ENVELOPE_BYTES + 64); f.put(0);
        }
        check(io::load(path, id, status).empty() && status == "corrupt", "bad checksum must miss");
        check(io::save(path, id, a), "recover corrupt slot");
        fs::resize_file(path, 20);
        check(io::load(path, id, status).empty(), "truncated header must miss");
        {
            std::ofstream f(path, std::ios::binary | std::ios::trunc);
            f.seekp(io::ENVELOPE_BYTES + io::MAX_BYTES); f.put(0);
        }
        check(io::load(path, id, status).empty() && status == "size", "oversized file must miss before allocation");
        check(io::save(path, id, a), "recover oversized slot");
        fs::create_directory(root / "not-a-file");
        check(!io::save((root / "not-a-file").string(), id, a), "failed atomic replacement must stay optional");
        std::atomic<int> active{4}, saved{0}, invalid{0};
        std::vector<std::thread> threads;
        for (int i = 0; i < 4; ++i) threads.emplace_back([&, i] {
            for (int j = 0; j < 20; ++j) if (io::save(path, id, i & 1 ? a : b)) ++saved;
            --active;
        });
        while (active) {
            auto data = io::load(path, id, status);
            if (!data.empty() && data != a && data != b) ++invalid;
        }
        for (auto& t : threads) t.join();
        check(saved > 0 && invalid == 0, "concurrent replacement must publish only complete payloads");
        auto final = io::load(path, id, status);
        check(final == a || final == b, "final file remains complete");
        for (const auto& entry : fs::directory_iterator(root))
            check(entry.path().filename().string().find(".tmp.") == std::string::npos, "all owned temporary files removed");
        std::printf("TRACE PASS pipeline cache IO: identity, corruption, size, recovery, atomic concurrency, cleanup\n");
        return 0;
    } catch (const std::exception& e) { std::fprintf(stderr, "FAIL pipeline cache IO: %s\n", e.what()); return 1; }
}
