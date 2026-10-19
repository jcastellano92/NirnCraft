// Runs one worker function on every hardware thread (the caller's included) without ever terminating the process:
// an exception in a worker is caught there and rethrown on the calling thread once all have finished (the C API
// turns it into a status), and when the system refuses more threads the work runs on the ones it got. Workers pull
// their items from a shared atomic counter, so any number of them finishes the job.
#pragma once
#include <algorithm>
#include <exception>
#include <mutex>
#include <thread>
#include <vector>

namespace aether {

template <typename F>
void parallelRun(F&& worker, unsigned maxThreads = 0) {
    std::exception_ptr first;
    std::mutex mu;
    auto safe = [&] {
        try {
            worker();
        } catch (...) {
            std::lock_guard<std::mutex> lock(mu);
            if (!first) first = std::current_exception();
        }
    };
    unsigned n = std::max(1u, std::thread::hardware_concurrency());
    if (maxThreads) n = std::max(1u, std::min(n, maxThreads));
    std::vector<std::thread> pool;
    try {
        pool.reserve(n);
        for (unsigned i = 0; i + 1 < n; i++) pool.emplace_back(safe);
    } catch (...) {
        // out of threads (or memory for them): carry on with those that started, and this one
    }
    safe();
    for (auto& t : pool) t.join();
    if (first) std::rethrow_exception(first);
}

}  // namespace aether
