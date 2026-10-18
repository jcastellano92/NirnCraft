// Minimal PNG writer (stored deflate blocks, no zlib dependency) for harness output.
#pragma once
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

namespace aether {

inline uint32_t crc32(const uint8_t* p, size_t n, uint32_t c = 0xFFFFFFFFu) {
    static uint32_t table[256];
    static bool init = false;
    if (!init) {
        for (uint32_t i = 0; i < 256; i++) {
            uint32_t v = i;
            for (int k = 0; k < 8; k++) v = (v & 1) ? 0xEDB88320u ^ (v >> 1) : v >> 1;
            table[i] = v;
        }
        init = true;
    }
    for (size_t i = 0; i < n; i++) c = table[(c ^ p[i]) & 0xFF] ^ (c >> 8);
    return c;
}

// rgba: width * height pixels, r in the low byte.
inline bool writePng(const std::string& path, const uint32_t* rgba, uint32_t w, uint32_t h) {
    std::vector<uint8_t> raw;
    raw.reserve(size_t(h) * (w * 4 + 1));
    for (uint32_t y = 0; y < h; y++) {
        raw.push_back(0);
        for (uint32_t x = 0; x < w; x++) {
            uint32_t p = rgba[size_t(y) * w + x];
            raw.push_back(uint8_t(p));
            raw.push_back(uint8_t(p >> 8));
            raw.push_back(uint8_t(p >> 16));
            raw.push_back(uint8_t(p >> 24));
        }
    }
    std::vector<uint8_t> z{0x78, 0x01};
    size_t pos = 0;
    while (pos < raw.size() || raw.empty()) {
        size_t len = std::min<size_t>(65535, raw.size() - pos);
        bool last = pos + len >= raw.size();
        z.push_back(last ? 1 : 0);
        z.push_back(uint8_t(len));
        z.push_back(uint8_t(len >> 8));
        z.push_back(uint8_t(~len));
        z.push_back(uint8_t(~len >> 8));
        z.insert(z.end(), raw.begin() + pos, raw.begin() + pos + len);
        pos += len;
        if (last) break;
    }
    uint32_t a = 1, b = 0;
    for (uint8_t c : raw) {
        a = (a + c) % 65521;
        b = (b + a) % 65521;
    }
    uint32_t adler = (b << 16) | a;
    for (int i = 3; i >= 0; i--) z.push_back(uint8_t(adler >> (8 * i)));

    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return false;
    auto be32 = [&](uint32_t v) {
        uint8_t q[4] = {uint8_t(v >> 24), uint8_t(v >> 16), uint8_t(v >> 8), uint8_t(v)};
        std::fwrite(q, 1, 4, f);
    };
    auto chunk = [&](const char* type, const std::vector<uint8_t>& data) {
        be32(uint32_t(data.size()));
        std::vector<uint8_t> td(type, type + 4);
        td.insert(td.end(), data.begin(), data.end());
        std::fwrite(td.data(), 1, td.size(), f);
        be32(crc32(td.data(), td.size()) ^ 0xFFFFFFFFu);
    };
    const uint8_t sig[8] = {137, 80, 78, 71, 13, 10, 26, 10};
    std::fwrite(sig, 1, 8, f);
    std::vector<uint8_t> ihdr = {uint8_t(w >> 24), uint8_t(w >> 16), uint8_t(w >> 8), uint8_t(w),
                                 uint8_t(h >> 24), uint8_t(h >> 16), uint8_t(h >> 8), uint8_t(h), 8, 6, 0, 0, 0};
    chunk("IHDR", ihdr);
    chunk("IDAT", z);
    chunk("IEND", {});
    return std::fclose(f) == 0;
}

}  // namespace aether
