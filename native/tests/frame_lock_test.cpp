// CPU-only lock and snapshot regression. Uses the actual World/VoxGrid/SectionStore implementations, no renderer.
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <future>
#include <stdexcept>
#include <thread>
#include "world.hpp"
#include "voxgrid.hpp"
#include "sectionstore.hpp"
#include "sectionpack.hpp"
#include "flight_recorder.hpp"

// This isolated executable has no Vulkan backend or recorder. Their only World dependencies are identity and zones.
namespace atlas { uint64_t buildHash() { return 0; } }
namespace atlas {
struct SectionStoreTestAccess {
    static void compact(SectionStore& store) {
        uint64_t region = 0;
        auto& cold = store.coldRegion(region);
        store.compact(region, cold);
    }
};
}
namespace atlas::fr {
std::atomic<bool> g_enabled{false};
uint64_t nowNs() { return uint64_t(std::chrono::steady_clock::now().time_since_epoch().count()); }
void record(const char*, uint64_t, uint64_t, int32_t, uint32_t) {}
}

using namespace atlas;
using namespace std::chrono_literals;
static void check(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
static std::array<uint16_t, 4096> ids(uint16_t value) {
    std::array<uint16_t, 4096> result;
    result.fill(value); result[0] = uint16_t(value + 1);  // mixed, so the source is actually staged
    return result;
}
static PackedIds packed(uint16_t value) { auto a = ids(value); return std::make_shared<const std::vector<uint32_t>>(packSection(a.data())); }
static uint32_t hash(uint16_t value) { auto a = ids(value); return sectionHash(a.data()); }

struct Fixture {
    std::filesystem::path path = std::filesystem::temp_directory_path() /
        ("atlas-frame-lock-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    Fixture() {
        SectionStore writer;
        writer.configure(path.string(), 42, 7, 1, 1ull << 28);
        writer.put({0, 0, 6, 0}, packed(3), hash(3), 0);
        writer.enforce(10000, 10000, 0);
        while (writer.drainSpills(1000)) {}
    }
    ~Fixture() { std::error_code ec; std::filesystem::remove_all(path, ec); }
    void world(World& world) { world.configureStore(path.string(), 42, 7, 1ull << 24, 1ull << 28); }
};

static void configure(VoxGrid& grid, World& world) {
    grid.D = 8; grid.slotCap = 64; grid.coarseLevels = 0; grid.gpuRank = false;
    grid.setWorld(&world);
    int32_t camera[3] = {0, 100, 0}; grid.frame(camera, world);
}

template<class Fn> static void renderWhileHeld(std::atomic<bool>& release, Fn&& work) {
    auto render = std::async(std::launch::async, std::forward<Fn>(work));
    bool returned = render.wait_for(250ms) == std::future_status::ready;
    release.store(true);  // always release even on failure, so a regression cannot wedge the runner
    render.get();
    check(returned, "render-facing call waited behind background world/cold I/O");
}

static void heldWorld(bool gpu) {
    World world;
    auto a = ids(3); world.putSection(0, 6, 0, a.data());
    world.packOnGpu.store(true);
    world.editBlock(0, 96, 16, 11);  // a second section's edited base will arrive while the lock is held
    VoxGrid grid;
    grid.D = 8; grid.slotCap = 64; grid.coarseLevels = 2; grid.coarseD = 8;
    grid.coarseCap[1] = grid.coarseCap[2] = 16; grid.gpuRank = gpu; grid.setWorld(&world);
    int32_t first[3] = {0, 100, 0}; grid.frame(first, world);
    std::atomic<bool> release{false}, held{false};
    std::jthread holder([&] { world.holdLockForTest(release, held); });
    while (!held.load()) std::this_thread::yield();
    auto refill = std::async(std::launch::async, [&] { grid.refill(world); });
    renderWhileHeld(release, [&] {
        float forward[3] = {0, 0, -1}; grid.setView(forward, 0.5f, 0.01f, 0);
        int32_t camera[3] = {16, 100, 0}; grid.frame(camera, world);
        auto batch = grid.take(8);
        check(batch.secs.empty(), "a snapshot source was consumed without world validation while its lock was held");
        grid.packedResults({{{0, 0, 6, 1}, World::BASE_FETCH, hash(7), packed(7)}});
    });
    holder.join(); refill.get();
    World::SectionSrc edited;
    check(world.trySectionSource(0, 6, 1, edited) && edited.raw && edited.raw->mats[0] == 11 && edited.raw->mats[1] == 7,
          "a deferred edited-base result was dropped instead of applied by the update thread");
    auto resumed = grid.take(8);
    check(!resumed.secs.empty(), "retained staging work did not resume after the world lock released");
}

static void coldEdit(bool move) {
    Fixture fixture;
    World world; fixture.world(world);
    VoxGrid grid; configure(grid, world);
    std::atomic<bool> release{false}, held{false};
    world.sectionReadBarrierForTest(&release, &held);
    auto refill = std::async(std::launch::async, [&] { grid.refill(world); });
    auto until = std::chrono::steady_clock::now() + 2s;
    while (!held.load() && std::chrono::steady_clock::now() < until) std::this_thread::yield();
    if (!held.load()) { release.store(true); refill.get(); check(false, "fixture did not reach a cold read"); }
    renderWhileHeld(release, [&] {
        grid.setView(0, -1);
        auto changed = ids(9);
        auto section = world.putSection(0, 6, 0, changed.data());  // world lock must be free during cold I/O
        grid.put(0, 6, 0, VgIds(section, &section->mats));
        if (move) grid.forceReplace();
        int32_t camera[3] = {move ? 4096 : 0, 100, 0}; grid.frame(camera, world);
        grid.take(8);
    });
    refill.get();
    world.sectionReadBarrierForTest(nullptr, nullptr);
    grid.refill(world);
    World::SectionSrc current;
    check(world.trySectionSource(0, 6, 0, current) && current.raw && current.raw->mats[1] == 9,
          "cold completion replaced an edit with stale disk IDs");
    if (move) check(grid.stats().ox > 200, "old refill moved the grid back to its prior window");
    else {
        grid.forceReplace(); int32_t camera[3] = {0, 100, 0}; grid.frame(camera, world); grid.refill(world);
        auto batch = grid.take(8);
        bool newest = false, old = false;
        for (const auto& section : batch.secs) if (section) { newest |= (*section)[1] == 9; old |= (*section)[1] == 3; }
        for (const auto& words : batch.packs) if (words) {
            uint16_t decoded[4096]; unpackSection(words->data(), decoded); newest |= decoded[1] == 9; old |= decoded[1] == 3;
        }
        check(newest && !old, "frame staged stale/absent IDs after concurrent edit");
    }
}

static void revisionAndReset() {
    World world;
    auto a = ids(3); world.putSection(0, 6, 0, a.data());
    World::SectionSnapshot snapshot;
    check(world.snapshotSections(-1, -1, 1, 1, false, snapshot), "memory snapshot failed");
    auto b = ids(7); world.putSection(0, 6, 0, b.data());
    bool applied = false;
    check(!world.applySections(snapshot, [&] { applied = true; }) && !applied, "stale source revision published");
    Fixture fixture;
    SectionStore store; store.configure(fixture.path.string(), 42, 7, 1ull << 24, 1ull << 28);
    auto read = store.readRange(-1, -1, 1, 1); SectionStore::resolveRange(read);
    check(!read.loaded.empty(), "cold ticket did not load a record");
    store.erase({0, 0, 6, 0});
    check(!store.completeRange(read) && !store.peek({0, 0, 6, 0}), "ticket resurrected an invalidated source");
    auto beforeReset = store.readRange(-1, -1, 1, 1); SectionStore::resolveRange(beforeReset);
    store.configure("", 43, 8, 1ull << 24, 1ull << 28);
    check(!store.completeRange(beforeReset), "ticket from a different world identity was admitted");
}

static void missingEditedBase() {
    Fixture fixture;
    {
        SectionStore store; store.configure(fixture.path.string(), 42, 7, 1ull << 24, 1ull << 28);
        auto layer = store.makeLayer({0, 1, 6, 0});
        layer->set(7, 11);  // cold edit layer with neither a resident base nor a whole fallback
        store.writeLayer({0, 1, 6, 0}, nullptr);
    }
    World world; fixture.world(world);
    VoxGrid grid; grid.D = 8; grid.slotCap = 1; grid.coarseLevels = 0; grid.gpuRank = true; grid.setWorld(&world);
    int32_t camera[3] = {0, 100, 0}; grid.frame(camera, world); grid.refill(world); grid.take(1);
    auto section = std::make_shared<const std::array<uint16_t, 4096>>(ids(5));
    grid.put(1, 6, 0, section); grid.take(1);  // pool full: this source now waits in host standby
    uint32_t c0 = uint32_t((6 - VG_MIN_SY) * 8 * 8), c1 = c0 + 1;
    grid.rankResults({c1}, {c0}, 0, 0);
    grid.take(1);
    auto wanted = grid.takeGpuWanted();
    check(std::any_of(wanted.begin(), wanted.end(), [](const auto& k) { return k[0] == 0 && k[1] == 1 && k[2] == 6 && k[3] == 0; }),
          "a cold edit layer's missing base was not requested from the GPU generator");
    auto waiting = grid.residency().gpuWaiting;
    std::atomic<bool> release{false}, held{false};
    std::jthread holder([&] { world.holdLockForTest(release, held); });
    while (!held.load()) std::this_thread::yield();
    uint64_t offset = 0;
    int32_t position[3] = {1, 6, 0}; uint8_t accepted = 255;
    size_t count = grid.ingestGpu(1, &offset, position, 1, &accepted);  // bookkeeping only: no GPU buffer accessed
    auto retained = grid.residency().gpuWaiting;
    release.store(true); holder.join();
    check(count == 0 && accepted == 0 && waiting > 0 && retained == waiting,
          "a rejected GPU delivery removed its standby request while the world lock was busy");
}

// The last variant leaves an older host pending entry at the same key: its flag update must not race the new
// GPU candidate in one dispatch. The dirty change survives and is registered in the following batch instead.
enum class StandbyChange { None, InitialClear, RetainedShift, Jump, HostReplace, UniformReplace, NoCoarse, FlagConflict };
static void gpuStandbyCandidate(StandbyChange change) {
    std::printf("TRACE begin GPU standby candidate case %d\n", int(change));
    World world;
    VoxGrid grid;
    grid.D = 8; grid.slotCap = change == StandbyChange::FlagConflict ? 1 : 64;
    grid.coarseLevels = change == StandbyChange::NoCoarse ? 0 : 1; grid.coarseD = 8;
    grid.coarseCap[1] = 16; grid.gpuRank = true; grid.setWorld(&world);
    const float forward[3] = {1, 0, 0};
    grid.setView(forward, 0.5f, 0.1f, 0);  // level 0 reaches 20 blocks, not section x=3
    int32_t camera[3] = {0, 100, 0}; grid.frame(camera, world);
    if (change != StandbyChange::InitialClear) grid.take(8);
    if (change == StandbyChange::FlagConflict) {
        grid.put(0, 6, 0, std::make_shared<const std::array<uint16_t, 4096>>(ids(3)));
        grid.take(8);  // fill the sole slot, so the older pending source will emit a waiting flag
        grid.put(3, 6, 0, std::make_shared<const std::array<uint16_t, 4096>>(ids(5)));
    }
    uint64_t offset = 0;
    int32_t position[3] = {3, 6, 0}; uint8_t accepted = 0;
    const size_t expected = change == StandbyChange::NoCoarse ? 0 : 1;
    check(grid.ingestGpu(1, &offset, position, 1, &accepted) == expected && accepted == expected,
          "standby fixture did not accept the GPU section into its coarse level");
    check(grid.residency().gpuWaiting == 1, "out-of-reach GPU section did not enter standby");
    const uint32_t cell = uint32_t((6 - VG_MIN_SY) * 8 * 8 + 3);
    auto hasCandidate = [&](const VgBatch& batch) {
        return std::find(batch.cand.begin(), batch.cand.end(), std::pair<uint32_t, uint32_t>{cell, 2}) != batch.cand.end();
    };
    if (change == StandbyChange::RetainedShift) {
        camera[0] = 80; grid.frame(camera, world);  // shift five columns; section x=3 remains in the window
        auto vacated = grid.take(8);
        check(!vacated.clear && !vacated.jobs.empty() && vacated.cand.empty(),
              "incremental shift fixture did not exercise the column-only early return");
    } else if (change == StandbyChange::Jump) {
        camera[0] = 4096; grid.frame(camera, world);
    } else if (change == StandbyChange::HostReplace) {
        grid.put(3, 6, 0, std::make_shared<const std::array<uint16_t, 4096>>(ids(7)));
    } else if (change == StandbyChange::UniformReplace) {
        uint32_t uniform = 0;
        check(grid.ingestGpu(1, &offset, position, 1, &accepted, &uniform) == 1,
              "uniform replacement fixture did not accept its delivery");
    } else if (change == StandbyChange::FlagConflict) {
        auto conflicting = grid.take(8);
        check(!hasCandidate(conflicting) &&
              std::count_if(conflicting.cand.begin(), conflicting.cand.end(), [&](const auto& c) { return c.first == cell; }) == 1,
              "dirty GPU candidate raced an existing update to the same physical cell");
    }
    auto batch = grid.take(8);  // bookkeeping only: no fake GPU buffer is dereferenced
    if (change == StandbyChange::Jump || change == StandbyChange::HostReplace || change == StandbyChange::UniformReplace) {
        check(!hasCandidate(batch) && grid.residency().gpuWaiting == 0,
              "stale standby candidate survived a window jump or replacement");
        check(!hasCandidate(grid.take(8)) && grid.takeGpuWanted().empty(),
              "removed standby candidate was revived by a later batch");
        return;
    }
    check(hasCandidate(batch),
          "ingested GPU standby section was never registered as a GPU ranking candidate");
    check(batch.rank.on, "new GPU standby candidate did not schedule ranking");
    check(!hasCandidate(grid.take(8)), "unchanged standby candidate was re-emitted every batch");
    grid.setView(forward, 0.5f, 0, 0);  // unbounded footprint grows the reach without a timed sleep
    grid.frame(camera, world);
    grid.rankResults({cell}, {}, 255, 0);  // host plumbing only; actual GPU ranking is a separate gate
    grid.take(8);
    auto wanted = grid.takeGpuWanted();
    check(wanted.size() == 1 && wanted[0] == std::array<int32_t, 4>{0, 3, 6, 0},
          "ranked GPU standby section was not requested after reach grew");
}

static void destructiveColdChange() {
    Fixture fixture;
    SectionStore store; store.configure(fixture.path.string(), 42, 7, 1ull << 24, 1ull << 28);
    auto read = store.readRange(-1, -1, 1, 1);
    check(!read.regions.empty(), "no region captured for truncation test");
    std::filesystem::resize_file(read.regions.front().path, 12);
    SectionStore::resolveRange(read);
    check(!read.complete && !store.completeRange(read) && !store.peek({0, 0, 6, 0}),
          "a truncated cold scan was treated as a completed/empty range");
}

static void coldMutationTickets() {
    Fixture fixture;
    SectionStore store; store.configure(fixture.path.string(), 42, 7, 1ull << 24, 1ull << 28);
    auto read = store.readRange(-1, -1, 2, 1); SectionStore::resolveRange(read);
    check(!read.loaded.empty(), "append test did not capture existing data");
    store.put({0, 1, 6, 0}, packed(7), hash(7), 0);
    store.enforce(10000, 10000, 0);
    while (store.drainSpills(1000)) {}
    check(!store.completeRange(read), "a new key appended after capture was treated as a complete old range");
    SectionStore again; again.configure(fixture.path.string(), 42, 7, 1ull << 24, 1ull << 28);
    auto compacted = again.readRange(-1, -1, 2, 1); SectionStore::resolveRange(compacted);
    SectionStoreTestAccess::compact(again);
    check(!again.completeRange(compacted), "a pre-compaction ticket was admitted after file replacement");
    SectionStore evict; evict.configure(fixture.path.string(), 42, 7, 1ull << 24, 1);
    auto evicted = evict.readRange(-1, -1, 2, 1); SectionStore::resolveRange(evicted);
    evict.enforce(0, 0, 2);  // no warm work: directly enforces the one-byte disk budget
    check(!evict.completeRange(evicted) && !evict.peek({0, 0, 6, 0}), "ticket resurrected a budget-evicted region");
    {
        Fixture other;
        SectionStore fresh; fresh.configure(other.path.string(), 42, 7, 1, 1ull << 28);
        auto beforeNewRegion = fresh.readRange(0, 0, 32, 1); SectionStore::resolveRange(beforeNewRegion);
        fresh.put({0, 16, 6, 0}, packed(9), hash(9), 0);
        fresh.enforce(0, 0, 2);
        while (fresh.drainSpills(1000)) {}
        check(!fresh.completeRange(beforeNewRegion), "a new cold region inside the snapshot range was missed");
    }
}

static void coldUpdateCompatibility() {
    Fixture fixture;
    {
        World world; fixture.world(world);
        double away[3] = {20000, 100, 0}; double ms = 0;
        world.update(away, 0, &ms);
        auto outside = world.sectionSource(0, 6, 0);
        check(outside.packed && !outside.raw, "explicit cold section lookup incorrectly obeyed range retention");
        uint16_t decoded[4096]; unpackSection(outside.packed->data(), decoded);
        check(decoded[1] == 3, "explicit outside-retention cold lookup returned wrong IDs");
    }
    for (bool first : {true, false}) {
        World world; fixture.world(world);
        VoxGrid grid; grid.D = 8; grid.slotCap = 64; grid.coarseLevels = 2; grid.coarseD = 8;
        grid.coarseCap[1] = grid.coarseCap[2] = 16; grid.setWorld(&world);
        double centre[3] = {0, 100, 0}; double away[3] = {20000, 100, 0}; double ms = 0;
        int32_t farCamera[3] = {20000, 100, 0}, camera[3] = {0, 100, 0};
        world.update(away, 0, &ms);
        if (!first) { grid.frame(farCamera, world); grid.take(64); }
        auto prior = grid.stats();
        std::atomic<bool> release{false}, held{false};
        world.sectionReadBarrierForTest(&release, &held);
        auto update = std::async(std::launch::async, [&] {
            // Same order as atlas_update: new retention centre, anticipate next frame, refill current window.
            world.update(centre, 0, &ms); grid.prepare(world, centre); grid.refill(world);
        });
        auto until = std::chrono::steady_clock::now() + 2s;
        while (!held.load() && std::chrono::steady_clock::now() < until) std::this_thread::yield();
        if (!held.load()) { release.store(true); update.get(); check(false, "update prefetch did not reach cold I/O"); }
        check(grid.stats().ox == prior.ox, "update preparation moved the render-owned grid window");
        renderWhileHeld(release, [&] { grid.setView(0, -1); grid.frame(farCamera, world); grid.take(64); });
        update.get(); world.sectionReadBarrierForTest(nullptr, nullptr);
        // There is deliberately no second update after the camera moves (also the first actual centre frame).
        grid.frame(camera, world);
        auto batch = grid.take(64);
        bool found = false;
        for (const auto& words : batch.packs) if (words) {
            uint16_t decoded[4096]; unpackSection(words->data(), decoded); found |= decoded[1] == 3;
        }
        check(found, "first render after update/jump did not stage the cold packed section");
    }
}

static void publicationEdit(bool reset) {
    World world;
    VoxGrid grid; grid.D = 16; grid.slotCap = 256; grid.coarseLevels = 0; grid.setWorld(&world);
    float forward[3] = {0, 0, -1}; grid.setView(forward, -1, 0, 0);
    int32_t camera[3] = {0, 100, 0}; grid.frame(camera, world); grid.take(256);
    int id = 20;
    for (int z = -5; z < 5; z++) for (int x = -7; x < 7; x++) {
        auto a = ids(uint16_t(id++)); world.putSection(x, 6, z, a.data());
    }
    World::SectionSnapshot before;
    check(world.snapshotSections(-8, -8, 8, 8, false, before) && before.entries.size() == 140,
          "publication test must cross the 128-section boundary");
    auto key = before.entries.front().first;
    uint16_t stale = before.entries.front().second.raw->mats[1];
    std::atomic<bool> release{false}, held{false};
    grid.publishBarrierForTest(&release, &held); grid.forceReplace();
    auto publisher = std::async(std::launch::async, [&] { grid.frame(camera, world); });
    auto until = std::chrono::steady_clock::now() + 2s;
    while (!held.load() && std::chrono::steady_clock::now() < until) std::this_thread::yield();
    if (!held.load()) { release.store(true); publisher.get(); check(false, "publication did not reach its slice barrier"); }
    auto replacement = ids(600);
    world.putSection(key.x, key.y, key.z, replacement.data());  // deliberately no grid.put: consume must revalidate
    if (reset) {
        grid.forceReplace();
        int32_t far[3] = {4096, 100, 0}; grid.frame(far, world);
    }
    auto batch = grid.take(256);
    bool old = false, newest = false;
    for (const auto& source : batch.secs) if (source) { old |= (*source)[1] == stale; newest |= (*source)[1] == 600; }
    release.store(true); publisher.get(); grid.publishBarrierForTest(nullptr, nullptr);
    check(!old && (reset || newest), "take consumed an old source published before a concurrent World edit");
    if (!reset) check(grid.stats().freshColumns > 0, "failed later-slice validation discarded remaining refill work");
}

// A stationary reload replaces the generator: its answer to an earlier ask never comes. Level 0 must keep asking while
// the GPU's ranking keeps admitting the waiting section, never stall with the section in standby and no request.
static void unansweredAskIsRepeated() {
    World world;
    VoxGrid grid;
    grid.D = 8; grid.slotCap = 64; grid.coarseLevels = 1; grid.coarseD = 8;
    grid.coarseCap[1] = 16; grid.gpuRank = true; grid.setWorld(&world);
    const float forward[3] = {1, 0, 0};
    grid.setView(forward, 0.5f, 0.1f, 0);
    int32_t camera[3] = {0, 100, 0}; grid.frame(camera, world); grid.take(8);
    uint64_t offset = 0; int32_t position[3] = {3, 6, 0}; uint8_t accepted = 0;
    check(grid.ingestGpu(1, &offset, position, 1, &accepted) == 1, "fixture delivery not taken");
    grid.take(8);
    grid.setView(forward, 0.5f, 0, 0);
    grid.frame(camera, world);
    const uint32_t cell = uint32_t((6 - VG_MIN_SY) * 8 * 8 + 3);
    size_t asks = 0;
    for (int i = 0; i < 3 * 240; i++) {
        grid.rankResults({cell}, {}, 255, 0);  // the GPU keeps admitting it
        grid.take(8);
        asks += grid.takeGpuWanted().size();
    }
    check(asks >= 2, "an unanswered GPU ask was never repeated: the section waits in standby with no request");
}

static void setEnvVar(const char* name, const char* value) {
#ifdef _WIN32
    _putenv_s(name, value ? value : "");
#else
    if (value && *value) setenv(name, value, 1);
    else unsetenv(name);
#endif
}

static uint64_t totalPoolBytes(const VoxGrid& grid) {
    uint64_t slots = grid.slotCap;
    for (int k = 1; k < VG_LEVELS; k++) slots += grid.coarseCap[k];
    return slots * uint64_t(VoxGrid::SLOT_BYTES);
}

static void integratedMemoryCap() {
    setEnvVar("ATLAS_VOXGRID_SLOTS", "");
    setEnvVar("ATLAS_VOXGRID_MEM", "");

    VoxGrid discrete;
    discrete.planMemory(24ull << 30, 0, false);
    check(discrete.slotCap == 32768 && discrete.slotCap <= 32768 && totalPoolBytes(discrete) == 100352ull * VoxGrid::SLOT_BYTES, "24 GiB discrete GPU should bound default level-0 slotCap to <= 32768 shell slots (100352 total slots)");

    VoxGrid deck;
    deck.planMemory(16ull << 30, 0, true);
    uint64_t deckBytes = totalPoolBytes(deck);
    check(deckBytes <= (3ull << 29), "16 GiB integrated/UMA GPU (Steam Deck) default pool exceeded 1.5 GiB cap");
    check(deck.slotCap >= 16384 && deck.slotCap <= 40960, "16 GiB integrated/UMA GPU level-0 slots out of expected capped range");

    setEnvVar("ATLAS_VOXGRID_MEM", "0.20");
    VoxGrid explicitOverride;
    explicitOverride.planMemory(16ull << 30, 0, true);
    setEnvVar("ATLAS_VOXGRID_MEM", "");
    check(explicitOverride.slotCap > deck.slotCap, "explicit ATLAS_VOXGRID_MEM override was ignored on integrated GPU");
}

static void putPackedBatchParityAndBench() {
    auto mixed = packed(3);
    std::array<uint16_t, 4096> uniArr; uniArr.fill(7);
    auto uniPacked = std::make_shared<const std::vector<uint32_t>>(packSection(uniArr.data()));
    std::array<uint16_t, 4096> airArr; airArr.fill(0);
    auto airPacked = std::make_shared<const std::vector<uint32_t>>(packSection(airArr.data()));

    World worldA, worldB;
    VoxGrid gridA, gridB;
    gridA.setWorld(&worldA); gridB.setWorld(&worldB);
    int32_t cam[3] = {0, 100, 0};
    gridA.frame(cam, worldA); gridB.frame(cam, worldB);

    std::vector<VoxGrid::PackedEntry> entries;
    entries.reserve(1024);
    for (int i = 0; i < 1024; i++) {
        int sx = (i & 15) - 8, sz = ((i >> 4) & 7) - 4, sy = (i >> 7) + 2;
        VgPacked w = (i % 3 == 0) ? mixed : (i % 3 == 1) ? uniPacked : airPacked;
        entries.push_back({sx, sy, sz, w, VG_UNI_UNSET});
    }
    for (const auto& e : entries) gridA.putPacked(e.sx, e.sy, e.sz, e.words);
    for (auto& e : entries) e.uni = packUniform(e.words->data());
    gridB.putPackedBatch(entries.data(), entries.size());

    auto batchA = gridA.take(1024), batchB = gridB.take(1024);
    check(batchA.jobs.size() == batchB.jobs.size() && batchA.packs.size() == batchB.packs.size(),
          "putPackedBatch job/pack count diverged from serial putPacked");
    for (size_t i = 0; i < batchA.jobs.size(); i++) {
        check(batchA.jobs[i].chunk == batchB.jobs[i].chunk && batchA.jobs[i].word == batchB.jobs[i].word &&
              batchA.jobs[i].slot == batchB.jobs[i].slot && batchA.jobs[i].data == batchB.jobs[i].data,
              "putPackedBatch job word/slot diverged from serial putPacked");
    }

    constexpr int ITERS = 20;
    auto t0 = std::chrono::steady_clock::now();
    for (int it = 0; it < ITERS; it++) {
        for (const auto& e : entries) gridA.putPacked(e.sx, e.sy, e.sz, e.words);
    }
    double serialMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() / ITERS;

    t0 = std::chrono::steady_clock::now();
    for (int it = 0; it < ITERS; it++) {
        for (auto& e : entries) e.uni = packUniform(e.words->data());
        gridB.putPackedBatch(entries.data(), entries.size());
    }
    double batchMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() / ITERS;
    std::printf("BENCH exp17 putPacked_1024_serial_ms=%.3f putPackedBatch_1024_ms=%.3f speedup=%.2fx\n",
                serialMs, batchMs, serialMs / std::max(0.001, batchMs));
}

static void wave4CoverageCoarseAndPruneBench() {
    auto mixed = packed(3);
    World world;
    VoxGrid grid;
    grid.setWorld(&world);
    grid.D = 64;
    grid.coarseD = 64;
    grid.coarseLevels = 6;
    int32_t cam[3] = {0, 100, 0};
    grid.frame(cam, world);

    // Populate 8,192 pending / cpending sections across the L0..L2 windows
    std::vector<VoxGrid::PackedEntry> entries;
    entries.reserve(8192);
    uint32_t mixedUni = packUniform(mixed->data());
    for (int i = 0; i < 8192; i++) {
        int sx = (i & 63) - 32, sz = ((i >> 6) & 63) - 32, sy = (i >> 12) + 4;
        entries.push_back({sx, sy, sz, mixed, mixedUni});
    }
    grid.putPackedBatch(entries.data(), entries.size());
    check(grid.residency().coarseWaiting == 8192, "expected 8192 coarseWaiting entries before takeCoarse");

    // EXP-21 benchmark: 4 consecutive take() calls while new entries arrive each frame (incrementing cpendingRev_)
    auto t0 = std::chrono::steady_clock::now();
    for (int f = 0; f < 4; f++) {
        VoxGrid::PackedEntry one{0, 6 + f, 0, mixed, mixedUni};
        grid.putPackedBatch(&one, 1);
        auto b = grid.take(64);
        check(!b.ingests.empty(), "expected takeCoarse to stage coarse ingests");
    }
    double takeCoarseMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() / 4.0;

    // EXP-20a benchmark: coverage() scan across all 7 levels
    t0 = std::chrono::steady_clock::now();
    VoxGrid::Coverage cov{};
    constexpr int COV_ITERS = 20;
    for (int it = 0; it < COV_ITERS; it++) cov = grid.coverage();
    double covMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() / COV_ITERS;
    check(cov.columns[0] == 64 * 64 && cov.holes[0] < cov.columns[0], "coverageLocked L0 columns/holes sanity failed");

    // EXP-23b test: shift L0 and coarse windows away and verify dropOutside() prunes out-of-window pending_ and cpending_
    int32_t farCam[3] = {100000, 100, 100000};
    grid.frame(farCam, world);
    check(grid.stats().pending == 0 && grid.residency().coarseWaiting == 0,
          "EXP-23b: dropOutside did not prune out-of-window pending_ and cpending_ entries");
    std::printf("BENCH wave4 coverage_ms=%.3f takeCoarse_8k_ms=%.3f\n", covMs, takeCoarseMs);
}

static void wave5GenColdAndSnapshotBench() {
    // EXP-34: verify SectionStore with genActive drops unedited sections without coldWrites while spilling edited ones
    {
        Fixture fixture;
        SectionStore store;
        store.configure(fixture.path.string(), 99, 7, 1ull << 24, 1ull << 28);
        store.setGenActive(true);
        store.put({0, 10, 6, 0}, packed(5), hash(5), 0);
        store.enforce(10000, 10000, 0);
        while (store.drainSpills(1000)) {}
        auto st = store.stats();
        check(st.coldWrites == 0 && st.dropped == 1,
              "EXP-34: unedited section wrote to cold tier despite genActive=true");
        auto l = store.makeLayer({0, 11, 6, 0});
        l->set(0, 9);
        store.put({0, 11, 6, 0}, packed(9), hash(9), SectionStore::EDITED);
        store.enforce(10000, 10000, 0);
        while (store.drainSpills(1000)) {}
        st = store.stats();
        check(st.spilled == 1,
              "EXP-34: edited section was not spilled when genActive=true");
    }
    // EXP-36: verify snapshotSections collectEntries=false skips copying section entries
    {
        World world;
        auto s = ids(3);
        for (int z = -16; z < 16; z++)
            for (int x = -16; x < 16; x++)
                world.putSection(x, 6, z, s.data());
        World::SectionSnapshot fullSnap, lightSnap;
        auto t0 = std::chrono::steady_clock::now();
        for (int it = 0; it < 50; it++) world.snapshotSections(-16, -16, 16, 16, true, fullSnap, false, true);
        double fullMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() / 50.0;
        t0 = std::chrono::steady_clock::now();
        for (int it = 0; it < 50; it++) world.snapshotSections(-16, -16, 16, 16, true, lightSnap, false, false);
        double lightMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() / 50.0;
        check(fullSnap.entries.size() == 1024 && lightSnap.entries.empty(),
              "EXP-36: snapshotSections collectEntries flag did not control entry collection");
        std::printf("BENCH wave5 snapshot_full_ms=%.4f snapshot_light_ms=%.4f\n", fullMs, lightMs);
    }
}

int main() {
    try {
        std::printf("TRACE begin frame-lock held World, cold I/O, edit, window reset and source revision\n");
        heldWorld(false); heldWorld(true); coldEdit(false); coldEdit(true); revisionAndReset(); missingEditedBase(); destructiveColdChange(); coldMutationTickets();
        publicationEdit(false); publicationEdit(true); coldUpdateCompatibility();
        for (auto change : {StandbyChange::None, StandbyChange::InitialClear, StandbyChange::RetainedShift,
                            StandbyChange::Jump, StandbyChange::HostReplace, StandbyChange::UniformReplace,
                            StandbyChange::NoCoarse, StandbyChange::FlagConflict}) gpuStandbyCandidate(change);
        unansweredAskIsRepeated();
        integratedMemoryCap();
        putPackedBatchParityAndBench();
        wave4CoverageCoarseAndPruneBench();
        wave5GenColdAndSnapshotBench();
        std::printf("TRACE done frame-lock PASS (CPU synchronization; no route/performance verdict)\n");
        return 0;
    } catch (const std::exception& e) { std::fprintf(stderr, "FAIL frame-lock: %s\n", e.what()); return 1; }
}
