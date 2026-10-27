// Deletion ownership only: cooperating caches hold shared leases; pruning needs an independent exclusive lease.
// Lock files are siblings of gen-* directories and are never unlinked (an unlinked lock inode can split ownership).
#pragma once
#include <cstdint>
#include <cstdio>
#include <filesystem>
#include <memory>
#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#include <windows.h>
#ifdef far
#undef far
#endif
#ifdef near
#undef near
#endif
#else
#include <fcntl.h>
#include <sys/file.h>
#include <unistd.h>
#endif

namespace atlas {
class CacheSetLease {
public:
    static std::shared_ptr<CacheSetLease> acquire(const std::filesystem::path& root, uint64_t fingerprint,
                                                 bool exclusive = false, bool createRoot = true) {
        if (root.empty() || !fingerprint) return {};
        std::error_code ec;
        auto canonical = std::filesystem::weakly_canonical(root, ec);
        if (ec || canonical.empty()) return {};
        if (createRoot) std::filesystem::create_directories(canonical, ec);
        if (ec || !std::filesystem::is_directory(canonical, ec) || ec) return {};
        char name[40];
        std::snprintf(name, sizeof name, "gen-%016llx", (unsigned long long)fingerprint);
        auto lease = std::shared_ptr<CacheSetLease>(new CacheSetLease);
        lease->root_ = canonical;
        lease->set_ = canonical / name;
        auto path = canonical / (std::string(".") + name + ".lease");
#ifdef _WIN32
        lease->handle_ = CreateFileW(path.c_str(), GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE,
                                    nullptr, OPEN_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
        if (lease->handle_ == INVALID_HANDLE_VALUE) return {};
        OVERLAPPED offset{};
        if (!LockFileEx(lease->handle_, LOCKFILE_FAIL_IMMEDIATELY | (exclusive ? LOCKFILE_EXCLUSIVE_LOCK : 0), 0, 1, 0, &offset)) return {};
#else
        lease->fd_ = open(path.c_str(), O_CREAT | O_RDWR | O_CLOEXEC, 0666);
        if (lease->fd_ < 0 || flock(lease->fd_, (exclusive ? LOCK_EX : LOCK_SH) | LOCK_NB) != 0) return {};
#endif
        lease->locked_ = true;
        return lease;
    }
    ~CacheSetLease() {
#ifdef _WIN32
        if (handle_ != INVALID_HANDLE_VALUE) {
            if (locked_) { OVERLAPPED offset{}; UnlockFileEx(handle_, 0, 1, 0, &offset); }
            CloseHandle(handle_);
        }
#else
        if (fd_ >= 0) { if (locked_) flock(fd_, LOCK_UN); close(fd_); }
#endif
    }
    CacheSetLease(const CacheSetLease&) = delete;
    CacheSetLease& operator=(const CacheSetLease&) = delete;
    const std::filesystem::path& root() const { return root_; }
    const std::filesystem::path& set() const { return set_; }
private:
    CacheSetLease() = default;
    std::filesystem::path root_, set_;
    bool locked_ = false;
#ifdef _WIN32
    HANDLE handle_ = INVALID_HANDLE_VALUE;
#else
    int fd_ = -1;
#endif
};
}  // namespace atlas
