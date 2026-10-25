// The near field's textures as the kernels read them (atlas_far.slang texData), kept up to date one material at a time.
//
// Layout: word 0 the table's length (fixed at TABLE), words 1..TABLE the table (a material id -> its slot, 1-based, 0:
// none), then the slots, 6 * 341 words each (a face's mip chain). The table never changes length, so a slot's place never
// moves: a new material is a slot appended and one table word set, and the backend copies only that.
//
// The packing (the mip chains, ~20 us a material) runs on a worker thread; the render thread only merges what is done
// (an 8 KB copy a material, at most maxSlots a frame). Before this, every new material repacked the whole set on the render
// thread (150 ms with 9,000 materials) and uploaded it again.
#pragma once
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <thread>
#include <vector>

#include "groups.hpp"

namespace atlas {

class TexturePack {
public:
    static constexpr uint32_t TABLE = 65536;
    static constexpr uint32_t SLOT_WORDS = 6 * 341;

    TexturePack();
    ~TexturePack();
    TexturePack(const TexturePack&) = delete;
    TexturePack& operator=(const TexturePack&) = delete;

    // Textures for the materials first .. first + v.size() - 1 (null: none). Any thread; returns at once.
    void submit(uint32_t first, const std::vector<std::shared_ptr<const MaterialTexture>>& v);
    // Render thread: merges up to maxSlots finished slots into `out` (the table is laid out on first use). True when
    // `out` changed. Never waits for the worker.
    bool sync(std::vector<uint32_t>& out, size_t maxSlots = 256);
    // Bumped when a slot already merged was rewritten in place (the backend then uploads everything again).
    uint64_t generation() const { return generation_; }
    // Materials submitted but not merged yet.
    size_t pending() const;

private:
    struct Done {
        uint32_t id;
        std::vector<uint32_t> words;
    };
    void work();

    mutable std::mutex mu_;
    std::condition_variable cv_;
    std::deque<std::pair<uint32_t, std::shared_ptr<const MaterialTexture>>> todo_;
    std::deque<Done> done_;
    bool stop_ = false;
    uint64_t generation_ = 0;
    std::thread worker_;
};

}  // namespace atlas
