// Small optional driver-cache files. Stable slots replace old versions; this never changes generator data.
#pragma once
#include <array>
#include <atomic>
#include <cstdint>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <string>
#include <vector>
#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#include <process.h>
// Windows' legacy pointer modifiers otherwise erase StageEngine::far.
#ifdef far
#undef far
#endif
#ifdef near
#undef near
#endif
#else
#include <unistd.h>
#endif

namespace atlas::pipeline_cache_io {
constexpr size_t MAX_BYTES = 32u * 1024 * 1024;
constexpr size_t ENVELOPE_BYTES = 40;
struct Identity {
    uint32_t vendor = 0, device = 0, driver = 0;
    std::array<uint8_t, 16> uuid{};
    uint64_t shader = 0;
};
inline uint64_t hash(const void* data, size_t bytes) {
    auto p = static_cast<const uint8_t*>(data);
    uint64_t h = 1469598103934665603ull;
    for (size_t i = 0; i < bytes; ++i) h = (h ^ p[i]) * 1099511628211ull;
    return h;
}
inline uint64_t get(const uint8_t* p, size_t n) {
    uint64_t v = 0;
    for (size_t i = 0; i < n; ++i) v |= uint64_t(p[i]) << (8 * i);
    return v;
}
inline void put(uint8_t* p, uint64_t v, size_t n) {
    for (size_t i = 0; i < n; ++i) p[i] = uint8_t(v >> (8 * i));
}
inline bool compatible(const std::vector<uint8_t>& data, const Identity& id) {
    // VkPipelineCacheHeaderVersionOne has a specified little-endian layout, independent of C++ packing.
    return data.size() >= 32 && data.size() <= MAX_BYTES && get(data.data(), 4) >= 32 &&
           get(data.data(), 4) <= data.size() && get(data.data() + 4, 4) == 1 &&
           get(data.data() + 8, 4) == id.vendor && get(data.data() + 12, 4) == id.device &&
           std::memcmp(data.data() + 16, id.uuid.data(), id.uuid.size()) == 0;
}
inline std::vector<uint8_t> load(const std::string& path, const Identity& id, std::string& status) {
    status = "miss";
    if (path.empty()) { status = "disabled"; return {}; }
    std::error_code ec;
    uint64_t size = std::filesystem::file_size(path, ec);
    if (ec) return {};
    if (size < ENVELOPE_BYTES + 32 || size > ENVELOPE_BYTES + MAX_BYTES) { status = "size"; return {}; }
    std::ifstream f(path, std::ios::binary);
    std::array<uint8_t, ENVELOPE_BYTES> h{};
    if (!f.read(reinterpret_cast<char*>(h.data()), h.size()) || std::memcmp(h.data(), "ATFCACH1", 8) ||
        get(h.data() + 8, 4) != 1 || get(h.data() + 12, 4) != id.driver ||
        get(h.data() + 16, 8) != id.shader || get(h.data() + 24, 8) != size - ENVELOPE_BYTES) {
        status = "identity"; return {};
    }
    std::vector<uint8_t> data(size_t(size - ENVELOPE_BYTES));
    if (!f.read(reinterpret_cast<char*>(data.data()), data.size()) || f.peek() != std::char_traits<char>::eof() ||
        hash(data.data(), data.size()) != get(h.data() + 32, 8) || !compatible(data, id)) {
        status = "corrupt"; return {};
    }
    status = "hit";
    return data;
}
inline bool save(const std::string& path, const Identity& id, const std::vector<uint8_t>& data) {
    if (path.empty() || !compatible(data, id)) return false;
    static std::atomic<uint64_t> serial{0};
#ifdef _WIN32
    auto pid = _getpid();
#else
    auto pid = getpid();
#endif
    std::filesystem::path dst(path), tmp(path + ".tmp." + std::to_string(pid) + "." + std::to_string(serial.fetch_add(1)));
    struct Remove {
        std::filesystem::path p;
        ~Remove() { std::error_code ec; std::filesystem::remove(p, ec); }
    } remove{tmp};
    std::error_code ec;
    if (!dst.parent_path().empty()) std::filesystem::create_directories(dst.parent_path(), ec);
    if (ec) return false;
    std::array<uint8_t, ENVELOPE_BYTES> h{};
    std::memcpy(h.data(), "ATFCACH1", 8);
    put(h.data() + 8, 1, 4); put(h.data() + 12, id.driver, 4); put(h.data() + 16, id.shader, 8);
    put(h.data() + 24, data.size(), 8); put(h.data() + 32, hash(data.data(), data.size()), 8);
    {
        std::ofstream f(tmp, std::ios::binary | std::ios::trunc);
        if (!f.write(reinterpret_cast<const char*>(h.data()), h.size()) ||
            !f.write(reinterpret_cast<const char*>(data.data()), data.size())) return false;
        f.close();
        if (!f) return false;
    }
#ifdef _WIN32
    return MoveFileExW(tmp.c_str(), dst.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH) != 0;
#else
    std::filesystem::rename(tmp, dst, ec);  // atomic replacement in the same directory/filesystem
    return !ec;
#endif
}
}  // namespace atlas::pipeline_cache_io
