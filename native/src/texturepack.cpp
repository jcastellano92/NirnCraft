#include "texturepack.hpp"

#include <algorithm>
#include <cstring>

#include "flight_recorder.hpp"
#include "voxgrid.hpp"  // packTextures

namespace atlas {

TexturePack::TexturePack() = default;

TexturePack::~TexturePack() {
    {
        std::lock_guard<std::mutex> lk(mu_);
        stop_ = true;
    }
    cv_.notify_all();
    if (worker_.joinable()) worker_.join();
}

void TexturePack::submit(uint32_t first, const std::vector<std::shared_ptr<const MaterialTexture>>& v) {
    {
        std::lock_guard<std::mutex> lk(mu_);
        for (size_t i = 0; i < v.size(); i++)
            if (v[i] && first + i < TABLE) todo_.emplace_back(uint32_t(first + i), v[i]);
        if (!worker_.joinable() && !todo_.empty()) worker_ = std::thread([this] { work(); });
    }
    cv_.notify_one();
}

size_t TexturePack::pending() const {
    std::lock_guard<std::mutex> lk(mu_);
    return todo_.size() + done_.size();
}

void TexturePack::work() {
    std::vector<uint32_t> one;
    TextureSet single;
    single.byMaterial.resize(1);
    for (;;) {
        std::pair<uint32_t, std::shared_ptr<const MaterialTexture>> job;
        {
            std::unique_lock<std::mutex> lk(mu_);
            cv_.wait(lk, [&] { return stop_ || !todo_.empty(); });
            if (stop_) return;
            job = std::move(todo_.front());
            todo_.pop_front();
        }
        ATLAS_ZONE_MIN("tex:pack material", 200);
        // packTextures of a set of one: [1][slot of material 0][its slot]
        single.byMaterial[0] = job.second;
        packTextures(single, one);
        if (one.size() != 2 + size_t(SLOT_WORDS)) continue;
        Done d{job.first, std::vector<uint32_t>(one.begin() + 2, one.end())};
        std::lock_guard<std::mutex> lk(mu_);
        done_.push_back(std::move(d));
    }
}

bool TexturePack::sync(std::vector<uint32_t>& out, size_t maxSlots) {
    const size_t base = 1 + size_t(TABLE);
    if (out.size() < base) {
        out.assign(base, 0u);
        out[0] = TABLE;
        out.reserve(base + size_t(12288) * SLOT_WORDS);  // (the exact ids' cap: no reallocation of tens of MB on the render thread)
    }
    std::vector<Done> take;
    {
        std::lock_guard<std::mutex> lk(mu_);
        size_t n = std::min(done_.size(), maxSlots);
        take.reserve(n);
        for (size_t i = 0; i < n; i++) {
            take.push_back(std::move(done_.front()));
            done_.pop_front();
        }
    }
    if (take.empty()) return false;
    ATLAS_ZONE_MIN("tex:merge", 200);
    for (Done& d : take) {
        uint32_t& slot = out[1 + size_t(d.id)];
        if (slot) {
            std::memcpy(out.data() + base + size_t(slot - 1) * SLOT_WORDS, d.words.data(), size_t(SLOT_WORDS) * 4);
            generation_++;  // (a slot rewritten: what the backend holds is stale)
        } else {
            slot = uint32_t((out.size() - base) / SLOT_WORDS) + 1;
            out.insert(out.end(), d.words.begin(), d.words.end());
        }
    }
    return true;
}

}  // namespace atlas
