// Actual cache files and independent OS leases, including owned subprocesses. No renderer/Vulkan device.
#include "gencache.hpp"
#include "cache_set_lease.hpp"
#include "sectionpack.hpp"
#include <chrono>
#include <fstream>
#include <future>
#include <functional>
#include <stdexcept>
#ifndef _WIN32
#include <signal.h>
#include <sys/wait.h>
#endif
using namespace atlas;
namespace fs = std::filesystem;
static void check(bool ok, const char* message) { if (!ok) throw std::runtime_error(message); }
struct Finally { std::function<void()> fn; ~Finally() { fn(); } };
static bool flag(const std::atomic<bool>& value) {
    auto until = std::chrono::steady_clock::now() + std::chrono::seconds(3);
    while (!value && std::chrono::steady_clock::now() < until) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    return value.load();
}
static fs::path setPath(const fs::path& root, uint64_t fp) {
    char name[32]; std::snprintf(name, sizeof name, "gen-%016llx", (unsigned long long)fp); return root / name;
}
static void write(GenCache& c, uint32_t kind = 1) { c.putFarTile(6, 0, 0, std::vector<uint32_t>(256, kind)); c.flush(); }
static void near(GenCache& c, uint16_t value) {
    uint16_t ids[4096]; for (auto& id : ids) id = value;
    uint32_t row[GenCache::INFO_WORDS]{};
    row[4 + 4 * 4] = sectionHash(ids); row[4 + 4 * 4 + 2] = GenCache::KIND_BLOCKS;
    c.putChunk(0, 0, row); c.putSection({0, 0, 0, 0}, std::make_shared<const std::vector<uint32_t>>(packSection(ids)), sectionHash(ids)); c.flush();
}

class Child {
public:
    Child(const fs::path& exe, const fs::path& root, const fs::path& ready, const fs::path& release) {
#ifdef _WIN32
        std::wstring command = L"\"" + exe.wstring() + L"\" --hold \"" + root.wstring() + L"\" \"" + ready.wstring() + L"\" \"" + release.wstring() + L"\"";
        STARTUPINFOW si{}; si.cb = sizeof si;
        PROCESS_INFORMATION pi{};
        check(CreateProcessW(exe.c_str(), command.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW, nullptr, nullptr, &si, &pi) != 0,
              "owned holder process failed to start");
        process_ = pi.hProcess; CloseHandle(pi.hThread);
#else
        pid_ = fork(); check(pid_ >= 0, "owned holder fork failed");
        if (!pid_) { execl(exe.c_str(), exe.c_str(), "--hold", root.c_str(), ready.c_str(), release.c_str(), nullptr); _exit(2); }
#endif
    }
    ~Child() { crash(); }
    void crash() {
#ifdef _WIN32
        if (process_) { if (WaitForSingleObject(process_, 0) != WAIT_OBJECT_0) TerminateProcess(process_, 73); WaitForSingleObject(process_, 3000); CloseHandle(process_); process_ = nullptr; }
#else
        if (pid_ > 0) { int status; if (waitpid(pid_, &status, WNOHANG) == 0) { kill(pid_, SIGKILL); waitpid(pid_, &status, 0); } pid_ = -1; }
#endif
    }
    void wait() {
#ifdef _WIN32
        check(WaitForSingleObject(process_, 3000) == WAIT_OBJECT_0, "holder did not exit");
        DWORD rc = 2; check(GetExitCodeProcess(process_, &rc) && !rc, "holder failed"); CloseHandle(process_); process_ = nullptr;
#else
        auto until = std::chrono::steady_clock::now() + std::chrono::seconds(3); int status = 0;
        while (std::chrono::steady_clock::now() < until) {
            if (waitpid(pid_, &status, WNOHANG) == pid_) { pid_ = -1; check(WIFEXITED(status) && WEXITSTATUS(status) == 0, "holder failed"); return; }
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
        check(false, "holder did not exit");
#endif
    }
private:
#ifdef _WIN32
    HANDLE process_ = nullptr;
#else
    pid_t pid_ = -1;
#endif
};

static void independentHandles(const fs::path& root) {
    auto a = CacheSetLease::acquire(root, 1), b = CacheSetLease::acquire(root / ".", 1);
    check(a && b, "independent shared handles must coexist");
    check(a->root() == b->root(), "lexical aliases did not canonicalize to one lease root");
    check(!CacheSetLease::acquire(root, 1, true), "exclusive admitted while shared owners exist");
    a.reset(); check(!CacheSetLease::acquire(root, 1, true), "one owner release unlocked the other");
    b.reset(); auto exclusive = CacheSetLease::acquire(root, 1, true); check(bool(exclusive), "inactive exclusive failed");
    check(!CacheSetLease::acquire(root, 1), "shared admission ignored exclusive deletion");
    exclusive.reset(); check(bool(CacheSetLease::acquire(root, 1)), "lease failed after exclusive release");
    check(fs::exists(root / ".gen-0000000000000001.lease"), "lock filename must persist after release");
}

static void returnWhilePruneQueued(const fs::path& root) {
    std::atomic<bool> release{false}, held{false}, admitted{false};
    GenCache c;
    Finally unblock{[&] { release = true; }};
    c.configure(root.string(), 1); write(c);
    c.configure(root.string(), 2); c.pruneBarrierForTest(&release, &held); write(c, 2 | 7 << 8);
    check(flag(held), "prune did not reach its real deletion boundary");
    c.admissionSignalForTest(&admitted);
    auto returning = std::async(std::launch::async, [&] { c.configure(root.string(), 1); });
    Finally join{[&] { release = true; if (returning.valid()) returning.wait(); }};
    check(flag(admitted), "return did not acquire target before waiting for cleanup");
    check(returning.wait_for(std::chrono::milliseconds(20)) == std::future_status::timeout, "queued cleanup not joined");
    release = true; returning.get();
    std::vector<uint32_t> kinds;
    check(c.farTile(6, 0, 0, kinds) && kinds == std::vector<uint32_t>(256, 1), "returning set deleted or changed");
    c.pruneBarrierForTest(nullptr, nullptr); c.admissionSignalForTest(nullptr);
}

static void activeAndInactiveOwners(const fs::path& root) {
    GenCache a, b;
    a.configure(root.string(), 1); write(a);
    b.configure((root / ".").string(), 2); write(b); b.configure("", 0); // alias owner and actual attempted prune
    check(fs::exists(setPath(root, 1)) && a.farKnown(6, 0, 0), "another configured owner lost its set");
    a.configure("", 0);
    b.configure(root.string(), 3); write(b); b.configure("", 0);
    check(!fs::exists(setPath(root, 1)) && !fs::exists(setPath(root, 2)), "inactive sets were never pruned");
}

static void keepThree(const fs::path& root) {
    fs::create_directories(root); std::ofstream(root.parent_path() / "keep-gen-sets") << "3\n";
    GenCache c;
    for (uint64_t fp = 1; fp <= 3; ++fp) { c.configure(root.string(), fp); write(c); }
    c.configure("", 0);
    auto now = fs::file_time_type::clock::now();
    for (uint64_t fp = 1; fp <= 3; ++fp) fs::last_write_time(setPath(root, fp), now - std::chrono::hours(5 - fp));
    c.configure(root.string(), 4); write(c); c.configure("", 0);
    check(!fs::exists(setPath(root, 1)) && fs::exists(setPath(root, 2)) && fs::exists(setPath(root, 3)) && fs::exists(setPath(root, 4)),
          "keep3 policy changed");
}

static void failedAdmission(const fs::path& root) {
    GenCache c; c.configure(root.string(), 1); write(c);
    auto deleting = CacheSetLease::acquire(root, 2, true); check(bool(deleting), "exclusive fixture unavailable");
    c.configure(root.string(), 2);
    check(!c.on() && !c.farKnown(6, 0, 0), "busy target served prior fingerprint");
    check(fs::exists(setPath(root, 1)), "failed target admission evicted prior disk set");
    deleting.reset(); c.configure(root.string(), 1); check(c.farKnown(6, 0, 0), "prior set not reusable after refusal");
    c.configure(root.string(), 2); // queues A as a retention candidate, without writing anything to trigger deletion
    fs::path invalid = root.parent_path() / "file-not-root"; std::ofstream(invalid) << "x";
    c.configure(invalid.string(), 3); check(!c.on() && fs::exists(setPath(root, 1)), "acquire error removed prior data");
    fs::path elsewhere = root.parent_path() / "different-root";
    c.configure(elsewhere.string(), 4); write(c); c.configure("", 0);
    check(fs::exists(setPath(root, 1)), "stale prune paths survived disable/reconfigure");
}

static void independentReadTicket(const fs::path& root) {
    std::atomic<bool> release{false}, held{false};
    GenCache c; Finally unblock{[&] { release = true; }};
    c.configure(root.string(), 1); write(c); c.readBarrierForTest(&release, &held);
    auto reader = std::async(std::launch::async, [&] { std::vector<uint32_t> out; return c.farTile(6, 0, 0, out); });
    Finally join{[&] { release = true; if (reader.valid()) reader.wait(); }};
    check(flag(held), "reader did not capture a real IO ticket");
    c.configure(root.string(), 2); write(c); c.configure(root.string(), 3);
    check(fs::exists(setPath(root, 1)), "out-of-lock reader's old set deleted");
    check(!CacheSetLease::acquire(root, 1, true), "reader lost its independent OS lease");
    release = true; check(!reader.get(), "old read published into changed fingerprint"); c.readBarrierForTest(nullptr, nullptr);
    check(bool(CacheSetLease::acquire(root, 1, true)), "finished reader leaked its lease");
    write(c, 2 | 9 << 8); std::vector<uint32_t> out;
    check(c.farTile(6, 0, 0, out) && out == std::vector<uint32_t>(256, 2 | 9 << 8), "stale bytes entered new region index");
}

static void nearReadReconfigure(const fs::path& root) {
    std::atomic<bool> release{false}, held{false};
    GenCache c; Finally unblock{[&] { release = true; }};
    c.configure(root.string(), 1); near(c, 7); c.readBarrierForTest(&release, &held);
    auto reader = std::async(std::launch::async, [&] { return c.section({0, 0, 0, 0}); });
    Finally join{[&] { release = true; if (reader.valid()) reader.wait(); }};
    check(flag(held), "near read did not capture its old location");
    c.configure(root.string(), 2); near(c, 9);
    release = true; check(!reader.get(), "old section returned after reconfigure"); c.readBarrierForTest(nullptr, nullptr);
    std::shared_ptr<const std::vector<uint32_t>> words;
    check(c.complete(0, 0) && c.readSection({0, 0, 0, 0}, words) && words, "stale invalidation damaged new cache or became AIR");
    uint16_t ids[4096]; unpackSection(words->data(), ids); for (auto id : ids) check(id == 9, "old near data published");
}

static void subprocesses(const fs::path& root, const fs::path& exe) {
    fs::create_directories(root);
    for (bool crash : {false, true}) {
        fs::path ready = root.parent_path() / (crash ? "crash-ready" : "normal-ready"), release = root.parent_path() / "release";
        std::error_code ec; fs::remove(release, ec);
        Child child(exe, root, ready, release);
        auto until = std::chrono::steady_clock::now() + std::chrono::seconds(3);
        while (!fs::exists(ready) && std::chrono::steady_clock::now() < until) std::this_thread::sleep_for(std::chrono::milliseconds(1));
        check(fs::exists(ready), "holder did not confirm configured lease");
        check(!CacheSetLease::acquire(root, 9, true), "subprocess shared lease ignored");
        GenCache pruner; pruner.configure(root.string(), crash ? 12 : 10); write(pruner); pruner.configure("", 0);
        check(fs::exists(setPath(root, 9)), "live subprocess set deleted");
        if (crash) child.crash(); else { std::ofstream(release) << "exit\n"; child.wait(); }
        check(bool(CacheSetLease::acquire(root, 9, true)), "normal/crash exit did not release OS lease");
    }
    GenCache c; c.configure(root.string(), 11); write(c); c.configure("", 0);
    check(!fs::exists(setPath(root, 9)), "inactive subprocess set could not be pruned");
}

static void destructionJoins(const fs::path& root) {
    std::atomic<bool> release{false}, held{false};
    auto c = std::make_unique<GenCache>(); Finally unblock{[&] { release = true; }};
    c->configure(root.string(), 1); write(*c); c->configure(root.string(), 2);
    c->pruneBarrierForTest(&release, &held); write(*c); check(flag(held), "destruction fixture did not start cleanup");
    auto stopped = std::async(std::launch::async, [&] { c.reset(); });
    Finally join{[&] { release = true; if (stopped.valid()) stopped.wait(); }};
    check(stopped.wait_for(std::chrono::milliseconds(20)) == std::future_status::timeout, "destructor detached cleanup");
    release = true; stopped.get(); check(!fs::exists(setPath(root, 1)), "joined deletion not finished");
}

static void deletionFailure(const fs::path& root) {
    GenCache c; c.configure(root.string(), 1); write(c); c.configure("", 0);
    fs::path file; for (const auto& e : fs::directory_iterator(setPath(root, 1))) if (e.path().extension() == ".gch") file = e.path();
    check(!file.empty(), "delete failure fixture lacks actual cache file");
#ifdef _WIN32
    HANDLE blocked = CreateFileW(file.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    check(blocked != INVALID_HANDLE_VALUE, "delete-denying fixture handle failed");
    Finally close{[&] { if (blocked != INVALID_HANDLE_VALUE) CloseHandle(blocked); }};
#else
    check(geteuid() != 0, "delete failure IO test needs an unprivileged user on Linux (root bypasses permissions)");
    fs::permissions(setPath(root, 1), fs::perms::owner_read | fs::perms::owner_exec);
    Finally restore{[&] { std::error_code ec; fs::permissions(setPath(root, 1), fs::perms::owner_all, ec); }};
#endif
    c.configure(root.string(), 2); write(c); c.configure("", 0);
    check(fs::exists(file), "delete failure silently reported success");
#ifdef _WIN32
    CloseHandle(blocked); blocked = INVALID_HANDLE_VALUE;
#else
    fs::permissions(setPath(root, 1), fs::perms::owner_all);
#endif
    c.configure(root.string(), 3); write(c); c.configure("", 0);
    check(!fs::exists(setPath(root, 1)), "delete failure prevented later safe retry");
}

static void unknownRetention(const fs::path& root) {
    GenCache c; c.configure(root.string(), 1); write(c); c.configure("", 0);
    auto keep = root.parent_path() / "keep-gen-sets"; std::ofstream(keep) << "3\n";
#ifdef _WIN32
    HANDLE blocked = CreateFileW(keep.c_str(), GENERIC_READ, FILE_SHARE_WRITE, nullptr, OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    check(blocked != INVALID_HANDLE_VALUE, "retention read-denying handle unavailable");
    Finally close{[&] { CloseHandle(blocked); }};
#else
    check(geteuid() != 0, "retention read-error IO test requires an unprivileged Linux user");
    fs::permissions(keep, fs::perms::none);
    Finally restore{[&] { std::error_code ec; fs::permissions(keep, fs::perms::owner_all, ec); }};
#endif
    c.configure(root.string(), 2); write(c); c.configure("", 0);
    check(fs::exists(setPath(root, 1)), "unreadable keep policy defaulted to destructive cleanup");
}

int main(int argc, char** argv) {
    if (argc == 5 && std::string(argv[1]) == "--hold") {
        GenCache c; c.configure(argv[2], 9); if (!c.on()) return 2;
        std::ofstream(argv[3]) << "ready\n";
        auto until = std::chrono::steady_clock::now() + std::chrono::seconds(30);
        while (!fs::exists(argv[4]) && std::chrono::steady_clock::now() < until) std::this_thread::sleep_for(std::chrono::milliseconds(5));
        return fs::exists(argv[4]) ? 0 : 2;
    }
    fs::path base = fs::current_path() / ".work" / ("cache-life-" + std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    // Only this disposable root, after all handles/owned processes have closed. Production never removes lease files.
    Finally cleanup{[&] { std::error_code ec; fs::remove_all(base, ec); }};
    try {
        auto run = [&](const char* name, auto fn) { fs::path root = base / name / "sets"; fn(root); std::printf("TRACE PASS cache lifetime %s\n", name); std::fflush(stdout); };
        run("handles", independentHandles); run("return", returnWhilePruneQueued); run("owners", activeAndInactiveOwners);
        run("keep3", keepThree); run("admission", failedAdmission); run("read-ticket", independentReadTicket);
        run("near-read", nearReadReconfigure); run("process", [&](auto root) { subprocesses(root, fs::absolute(argv[0])); });
        run("destructor", destructionJoins); run("delete-failure", deletionFailure); run("unknown-retention", unknownRetention);
        std::printf("TRACE PASS cache lifetime 11 real IO/process scenarios; no GPU or live-client acceptance\n"); return 0;
    } catch (const std::exception& e) { std::fprintf(stderr, "FAIL cache lifetime: %s\n", e.what()); return 1; }
}
