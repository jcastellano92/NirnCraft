#include "backend.hpp"
#include <cstdio>
#include <thread>
#include <vector>

using atlas::Backend;
namespace {
int failures = 0;
#define CHECK(x) do { if (!(x)) { std::fprintf(stderr, "FAIL admission: %s:%d %s\n", __FILE__, __LINE__, #x); ++failures; } } while (0)
class TestBackend final : public Backend {
public:
    const char* name() const override { return "admission-test"; }
    bool render(const atlas::FrameInputs&, atlas::FrameTargets&, double*, std::string&) override { return true; }
    bool stageScene(const atlas::PackedScene&, const WorkTicket& ticket) override { return ticket.belongsTo(this); }
};
}
int main() {
    {
        TestBackend backend, other;
        auto update = backend.admitWork();
        CHECK(update && backend.activeWork() == 1);
        CHECK(backend.producerStarted(update));
        CHECK(!other.producerStarted(update));
        CHECK(backend.activeProducers() == 1);
        backend.sealWork();
        CHECK(!backend.admitWork() && !backend.admitRetirement());
        // An update admitted before seal can finish its nested scene upload without another admission decision.
        atlas::PackedScene scene;
        CHECK(backend.stageScene(scene, update));
        CHECK(!other.stageScene(scene, update));
        auto moved = std::move(update);
        CHECK(!update && moved && backend.activeWork() == 1);
        moved = std::move(moved);
        CHECK(moved && backend.activeWork() == 1);
        auto cleanup = backend.admitProducerCleanup();
        CHECK(cleanup && backend.activeWork() == 2);
        backend.producerStopped();
        CHECK(backend.activeProducers() == 0);
        cleanup = {};
        moved = {};
        CHECK(backend.activeWork() == 0);
        backend.markDrained();
        CHECK(!backend.admitWork() && !backend.admitProducerCleanup());
        auto retirement = backend.admitRetirement();
        CHECK(retirement && backend.activeWork() == 1);
    }
    // Real entrants overlap sealing: all accepted tickets remain counted, and no post-seal entrant is accepted.
    for (int pass = 0; pass < 100; ++pass) {
        TestBackend backend;
        std::atomic<bool> start{false}, release{false};
        std::atomic<unsigned> accepted{0}, arrived{0};
        std::vector<std::thread> entrants;
        for (int i = 0; i < 4; ++i) entrants.emplace_back([&] {
            while (!start.load()) std::this_thread::yield();
            auto ticket = backend.admitWork();
            if (ticket) accepted.fetch_add(1);
            arrived.fetch_add(1);
            if (ticket) while (!release.load()) std::this_thread::yield();
        });
        start.store(true);
        backend.sealWork();
        CHECK(!backend.admitWork());
        while (arrived.load() != 4) std::this_thread::yield();
        CHECK(backend.activeWork() == accepted.load());
        release.store(true);
        for (auto& thread : entrants) thread.join();
        CHECK(backend.activeWork() == 0 && accepted.load() <= 4);
    }
    {
        TestBackend backend;
        backend.injectCloseFault(4);
        CHECK(!backend.consumeCloseFault(5));
        CHECK(backend.consumeCloseFault(4) && !backend.consumeCloseFault(4));
    }
    if (failures) return 1;
    std::puts("TRACE close admission regression: PASS (nested ticket, producer lifetime, seal race, retirement, one-shot refusal)");
    return 0;
}
