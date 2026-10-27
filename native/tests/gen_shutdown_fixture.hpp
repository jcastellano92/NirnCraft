// Opt-in diagnostic only. The default Vulkan parity suite does not load generator inputs.
// Included after Scene/loadScene, so the existing real host-device/splice fixture is reused.
static std::vector<uint32_t> generatorShutdownProgram, generatorShutdownMap, generatorShutdownIngest;
static bool generatorShutdownNear = true, generatorShutdownFar = true;
static uint32_t generatorShutdownMaxMaterial = 0;

static bool generatorShutdownLoad(const std::string& path, size_t limit, std::vector<uint32_t>& out, const char* expectedName) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    auto n = f ? f.tellg() : std::streampos(-1);
    if (n <= 0 || uint64_t(n) > limit || uint64_t(n) % 4) {
        std::printf("  FAIL gen-shutdown input size: %s\n", path.c_str());
        return false;
    }
    std::vector<unsigned char> bytes(static_cast<size_t>(n));
    f.seekg(0);
    if (!f.read(reinterpret_cast<char*>(bytes.data()), std::streamsize(bytes.size()))) return false;
    uint64_t hash = 1469598103934665603ull;  // the engine/manifest legacy FNV offset (not the standard offset)
    for (unsigned char b : bytes) hash = (hash ^ b) * 1099511628211ull;
    const char* expected = std::getenv(expectedName);
    char* end = nullptr;
    uint64_t wanted = expected ? std::strtoull(expected, &end, 16) : 0;
    if (!expected || std::strlen(expected) != 16 || !end || *end || wanted != hash) {
        std::printf("  FAIL gen-shutdown: preflight input FNV differs: %s\n", path.c_str());
        return false;
    }
    out.resize(bytes.size() / 4);
    for (size_t i = 0; i < out.size(); i++)
        out[i] = uint32_t(bytes[i * 4]) | uint32_t(bytes[i * 4 + 1]) << 8 |
                 uint32_t(bytes[i * 4 + 2]) << 16 | uint32_t(bytes[i * 4 + 3]) << 24;
    std::printf("TRACE gen-shutdown input path=%s bytes=%zu fnv=%016llx\n", path.c_str(), bytes.size(),
                (unsigned long long)hash);
    return true;
}

static bool generatorShutdownInputs(const std::string& dump) {
    const char* ingest = std::getenv("ATLAS_VK_TEST_GEN_INGEST");
    if (!ingest || !*ingest) {
        std::printf("  FAIL gen-shutdown: explicit ATLAS_VK_TEST_GEN_INGEST required (no directory discovery)\n");
        return false;
    }
    if (!generatorShutdownLoad(dump + "/prog.bin", 16u << 20, generatorShutdownProgram, "ATLAS_VK_TEST_GEN_EXPECT_PROGRAM") ||
        !generatorShutdownLoad(dump + "/map.bin", 256u << 20, generatorShutdownMap, "ATLAS_VK_TEST_GEN_EXPECT_MAP") ||
        !generatorShutdownLoad(ingest, 256u << 20, generatorShutdownIngest, "ATLAS_VK_TEST_GEN_EXPECT_INGEST")) return false;
    const auto& p = generatorShutdownProgram;
    const auto& m = generatorShutdownMap;
    const auto& t = generatorShutdownIngest;
    if (p.size() < 128 || p[0] != 0x4e454741u || p[3] > 16 || !p[113] ||
        m.size() < 32 || m[0] != 0x50414d41u || t.size() < 2 || !t[0] || !t[1]) {
        std::printf("  FAIL gen-shutdown: invalid program/map/ingest header\n");
        return false;
    }
    uint64_t table = 2ull + 2ull * t[0] + 2ull * t[1];
    if (table + uint64_t(t[0]) * t[1] != t.size()) {
        std::printf("  FAIL gen-shutdown: ingest length differs from nonzero state/biome counts\n");
        return false;
    }
    generatorShutdownMaxMaterial = 0;
    for (size_t i = size_t(table); i < t.size(); i++) {
        if (t[i] > 65535u) { std::printf("  FAIL gen-shutdown: material exceeds uint16\n"); return false; }
        if (t[i] > generatorShutdownMaxMaterial) generatorShutdownMaxMaterial = t[i];
    }
    return generatorShutdownMaxMaterial > 0;
}

static long long generatorShutdownNumber(AtlasContext* ctx, const char* name) {
    std::string info = atlas_info(ctx), key = std::string("\"") + name + "\":";
    size_t at = info.find(key);
    return at == std::string::npos ? -1 : std::strtoll(info.c_str() + at + key.size(), nullptr, 10);
}

static bool generatorShutdownCacheDevice(VkPhysicalDevice physical) {
    const char* expected = std::getenv("ATLAS_VK_TEST_GEN_CACHE_UUID");
    if (!expected) return true;  // the original uncached diagnostic remains available
    const char* vendor = std::getenv("ATLAS_VK_TEST_GEN_CACHE_VENDOR");
    const char* device = std::getenv("ATLAS_VK_TEST_GEN_CACHE_DEVICE");
    VkPhysicalDeviceProperties props{};
    vkGetPhysicalDeviceProperties(physical, &props);
    char uuid[33]{};
    for (unsigned i = 0; i < 16; i++) std::snprintf(uuid + i * 2, 3, "%02x", props.pipelineCacheUUID[i]);
    if (!vendor || !device || std::strcmp(expected, uuid) || std::strtoul(vendor, nullptr, 16) != props.vendorID ||
        std::strtoul(device, nullptr, 16) != props.deviceID) {
        std::printf("  FAIL gen-shutdown: cache differs from actual physical device before context creation\n");
        return false;
    }
    std::printf("TRACE gen-shutdown pipeline-cache-device-match vendor=%08x device=%08x uuid=%s before-context\n",
                props.vendorID, props.deviceID, uuid);
    return true;
}

static bool generatorShutdownScene(AtlasContext* ctx, uint32_t W, uint32_t H, Scene& s) {
    // No CPU sections, preview raster, cache, feature stages or overlay data: every witness below must come from Gen.
    s.centre[0] = s.centre[2] = 4096.5;
    s.centre[1] = 522;
    s.f = {};
    for (int i = 0; i < 3; i++) s.f.cameraPos[i] = s.centre[i];
    atlas::cameraMatrix(0, -35, 70, double(W) / H, s.f.invViewProj);
    s.f.width = W; s.f.height = H;
    s.f.maxDistance = 4000; s.f.nearFieldEnd = 96; s.f.lodBias = 1; s.f.sunDir[1] = 1;
    std::vector<AtlasMaterial> materials(generatorShutdownMaxMaterial,
                                       AtlasMaterial{{0.5f, 0.5f, 0.5f}, 0.9f, 0, ATLAS_CLASS_TERRAIN});
    return atlas_set_materials(ctx, 1, materials.data(), uint32_t(materials.size())) == ATLAS_OK &&
           atlas_update(ctx, s.centre, 0) == ATLAS_OK;
}

// Separate failure fixtures intentionally keep the host device alive when native ownership is unresolved.
// OS reclamation on the bounded child's exit is not a successful Vulkan teardown.
static uint32_t terminalCloseFault = 0;
static bool terminalClosePreAttach = false;
static uint32_t terminalCloseLock = 0;
static bool terminalCloseUpdate = false;
extern "C" ATLAS_API AtlasStatus atlas_debug_hold_locks(AtlasContext*, uint32_t);
static bool terminalCloseActualUpdate(AtlasContext* ctx) {
    std::vector<AtlasMaterial> materials(generatorShutdownMaxMaterial,
                                       AtlasMaterial{{0.5f,0.5f,0.5f},0.9f,0,ATLAS_CLASS_TERRAIN});
    CHECK(atlas_set_materials(ctx, 1, materials.data(), uint32_t(materials.size())) == ATLAS_OK);
    CHECK(atlas_set_clipmap(ctx, 1) == ATLAS_OK); // One actual level0 column fixture; no generator/near-grid workload claim.
    double centre[3] = {4096.5,522,4096.5};
    std::vector<float> surface(256*256, 100.0f), water(256*256, NAN);
    std::vector<uint16_t> top(256*256, 1), fill(256*256, 1);
    AtlasColumnTile tile{0,16,16,surface.data(),water.data(),top.data(),fill.data(),nullptr,nullptr};
    CHECK(atlas_upload_column_tile(ctx, &tile) == ATLAS_OK);
    auto primeLimit = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    AtlasStatus primed;
    do {
        CHECK(atlas_update(ctx, centre, 0) == ATLAS_OK);
        primed = atlas_debug_hold_locks(ctx, 9u << 24);
        if (primed == ATLAS_ERR_TIMEOUT) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    } while (primed == ATLAS_ERR_TIMEOUT && std::chrono::steady_clock::now() < primeLimit);
    CHECK(primed == ATLAS_OK);
    if (primed != ATLAS_OK) return false;
    surface[0] = 104.0f; // A real payload mutation must produce a newer scene than the completed baseline upload.
    CHECK(atlas_upload_column_tile(ctx, &tile) == ATLAS_OK);
    CHECK(atlas_debug_hold_locks(ctx, 5u << 24) == ATLAS_OK);
    std::atomic<int> updateStatus{ATLAS_ERR_STATE};
    std::atomic<bool> finished{false};
    std::thread update([&] { updateStatus.store(atlas_update(ctx, centre, 0)); finished.store(true); });
    auto pauseLimit = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    AtlasStatus paused;
    do {
        paused = atlas_debug_hold_locks(ctx, 6u << 24);
        if (paused == ATLAS_ERR_TIMEOUT) std::this_thread::sleep_for(std::chrono::milliseconds(1));
    } while (paused == ATLAS_ERR_TIMEOUT && std::chrono::steady_clock::now() < pauseLimit);
    CHECK(paused == ATLAS_OK);
    if (paused == ATLAS_OK) {
        auto start = std::chrono::steady_clock::now();
        AtlasStatus close = atlas_vk_prepare_close(ctx);
        double elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
        CHECK(close == ATLAS_ERR_TIMEOUT && elapsed >= 4.5 && elapsed <= 5.75);
        std::printf("TRACE terminal-close admitted-update timeout status=%d elapsed=%.3f\n", int(close), elapsed);
        CHECK(atlas_update(ctx, centre, 0) == ATLAS_ERR_STATE); // A later update cannot take the paused caller's ticket.
        atlas_destroy(ctx); // SEALED context is retained before releasing the actual admitted operation.
    }
    CHECK(atlas_debug_hold_locks(ctx, 7u << 24) == ATLAS_OK);
    auto doneLimit = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (!finished.load() && std::chrono::steady_clock::now() < doneLimit)
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    if (!finished.load()) {
        std::puts("  FAIL terminal-close admitted-update did not join; retaining context/device for owned child exit");
        std::fflush(nullptr); std::_Exit(1);
    }
    update.join();
    CHECK(updateStatus.load() == ATLAS_OK);
    CHECK(atlas_debug_hold_locks(ctx, 8u << 24) == ATLAS_OK); // Checks actual staging result and reached revision, not update's status alone.
    std::printf("TRACE terminal-close admitted-update joined status=%d\n", updateStatus.load());
    return failures == 0;
}
static void terminalCloseBlocked(AtlasContext* ctx, AtlasStatus expected) {
    AtlasStatus status = atlas_vk_prepare_close(ctx);
    CHECK(status == expected);
    AtlasVkRetirement* token = nullptr;
    CHECK(atlas_vk_detach_retired(ctx, &token) == ATLAS_ERR_STATE && token == nullptr);
    double centre[3]{};
    CHECK(atlas_update(ctx, centre, 0) == ATLAS_ERR_STATE);
    AtlasGen* denied = nullptr;
    CHECK(atlas_gen_create_for(ctx, &denied) == ATLAS_ERR_STATE && denied == nullptr);
    atlas_destroy(ctx); // Legacy caller must retain the same SEALED context and all children.
    CHECK(std::string(atlas_info(ctx)).find("onMinecraftDevice") != std::string::npos);
    std::printf("TRACE terminal-close blocked status=%d preAttach=%d; token-null; admission-denied; legacy-owner-retained\n",
                int(status), terminalClosePreAttach ? 1 : 0);
}

static bool terminalClosePrepare(AtlasContext* ctx) {
    if (terminalCloseLock) {
        // Mode 4 retains the ordinary admitted debug operation; 1/2/3 own the actual native close locks.
        uint32_t encoded = terminalCloseLock == 4 ? 5500 : (terminalCloseLock << 24) | 5500;
        CHECK(atlas_debug_hold_locks(ctx, encoded) == ATLAS_OK);
        auto start = std::chrono::steady_clock::now();
        CHECK(atlas_vk_prepare_close(ctx) == ATLAS_ERR_TIMEOUT);
        double elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
        CHECK(elapsed >= 4.5 && elapsed <= 5.75);
        std::printf("TRACE terminal-close bounded real-lock=%u elapsed=%.3f\n", terminalCloseLock, elapsed);
        atlas_destroy(ctx);
        if (terminalCloseLock != 4) {
            auto limit = std::chrono::steady_clock::now() + std::chrono::seconds(3);
            AtlasStatus done;
            do {
                done = atlas_debug_hold_locks(ctx, 4u << 24);
                if (done == ATLAS_ERR_TIMEOUT) std::this_thread::sleep_for(std::chrono::milliseconds(1));
            } while (done == ATLAS_ERR_TIMEOUT && std::chrono::steady_clock::now() < limit);
            CHECK(done == ATLAS_OK); // Observe the holder's final raw-context access; elapsed time alone is insufficient.
        }
    }
    if (terminalCloseFault == ATLAS_FAULT_CLOSE_PRODUCER) {
        terminalCloseBlocked(ctx, ATLAS_ERR_GPU);
        CHECK(validationErrors == 0);
        std::printf("TRACE terminal-close blocked validation=%d; resources-not-released; host-device-close-blocked\n", validationErrors);
        std::puts("TRACE terminal-close EXPECTED_BLOCKED producer; host-device-destroy-not-reached");
        std::fflush(nullptr);
        std::_Exit(failures ? 1 : 0);
    }
    if (terminalCloseFault == ATLAS_FAULT_CLOSE_CONSUMER) {
        CHECK(atlas_debug_fault(ctx, terminalCloseFault, 0) == ATLAS_OK);
        terminalCloseBlocked(ctx, ATLAS_ERR_TIMEOUT);
        std::puts("TRACE terminal-close consumer retry retains the same real queue-tail fence");
    }
    CHECK(atlas_vk_prepare_close(ctx) == ATLAS_OK);
    CHECK(atlas_vk_prepare_close(ctx) == ATLAS_OK); // Idempotence must not enqueue another tail or reopen admission.
    double centre[3]{};
    CHECK(atlas_update(ctx, centre, 0) == ATLAS_ERR_STATE);
    CHECK(atlas_set_jitter(ctx, 0, 0) == ATLAS_ERR_STATE);
    AtlasGen* denied = nullptr;
    CHECK(atlas_gen_create_for(ctx, &denied) == ATLAS_ERR_STATE && denied == nullptr);
    std::printf("TRACE terminal-close DRAINED preAttach=%d; post-close-admission-denied\n", terminalClosePreAttach ? 1 : 0);
    return failures == 0;
}

static bool generatorShutdownRun(AtlasContext* ctx, const std::function<bool()>& frame) {
    int before = failures;
    AtlasGen* gen = nullptr;
    if (!frame() || atlas_gen_create_for(ctx, &gen) != ATLAS_OK || !gen) {
        std::printf("  FAIL gen-shutdown: initial real frame / generator creation\n");
        return false;
    }
    auto finish = [&] {
        if (terminalCloseFault == ATLAS_FAULT_CLOSE_PRODUCER)
            CHECK(atlas_debug_fault(ctx, terminalCloseFault, 0) == ATLAS_OK);
        std::printf("TRACE gen-shutdown generator-destroy-begin\n");
        atlas_gen_destroy(gen); gen = nullptr;
        std::printf("TRACE gen-shutdown generator-destroy-end\n");
    };
    if (atlas_gen_set_program(gen, generatorShutdownProgram.data(), uint32_t(generatorShutdownProgram.size())) != ATLAS_OK ||
        atlas_gen_set_map(gen, generatorShutdownMap.data(), uint32_t(generatorShutdownMap.size())) != ATLAS_OK ||
        atlas_gen_set_ingest(gen, generatorShutdownIngest.data(), uint32_t(generatorShutdownIngest.size())) != ATLAS_OK ||
        atlas_gen_pace_boost(gen, 1) != ATLAS_OK) {
        std::printf("  FAIL gen-shutdown configuration: %s\n", atlas_gen_last_error(gen)); finish(); return false;
    }
    if (generatorShutdownFar) {
        int32_t window[6]{};
        if (atlas_gen_coarse_window(gen, 1, window) != ATLAS_OK || !window[3] || window[2] <= 0 || window[5] <= 0) {
            std::printf("  FAIL gen-shutdown: coarse window not placed\n"); finish(); return false;
        }
        // The tile containing the window centre. Positive coordinates make the division exact floor.
        int32_t tx = (window[0] + window[2] / 2) / 16, tz = (window[1] + window[2] / 2) / 16;
        int32_t tile[2] = {tx, tz};
        const uint32_t rows = 22, capacity = 256 * rows;
        std::vector<uint32_t> kinds(size_t(capacity) * 2);
        uint32_t mixed = 0, taken = 0, bad = ~0u;
        long long givenBefore = generatorShutdownNumber(ctx, "coarseGiven");
        AtlasStatus status = atlas_gen_far_ingest(gen, 1, tile, 1, capacity, kinds.data(), &mixed, &taken, &bad, nullptr);
        size_t eligible = 0, eligibleMixed = 0;
        for (uint32_t i = 0; i < capacity; i++) {
            int cx = tx * 16 + int((i / rows) & 15), cz = tz * 16 + int((i / rows) >> 4);
            if (cx < window[0] || cz < window[1] || cx >= window[0] + window[2] || cz >= window[1] + window[2] ||
                int(i % rows) >= window[5]) continue;
            uint32_t kind = kinds[size_t(i) * 2];
            if (kind == 0xfffffffeu) continue;
            eligible++; eligibleMixed += kind == 0;
        }
        long long givenAfter = generatorShutdownNumber(ctx, "coarseGiven");
        std::printf("TRACE gen-shutdown far-witness status=%d mixed=%u eligibleMixed=%zu eligible=%zu taken=%u "
                    "givenBefore=%lld givenAfter=%lld flags=%u\n", int(status), mixed, eligibleMixed, eligible, taken,
                    givenBefore, givenAfter, bad);
        // taken counts outside-window refusals too; exact initially-empty cgiven growth is the admission witness.
        if (status != ATLAS_OK || bad || !mixed || !eligibleMixed || givenBefore != 0 ||
            givenAfter != static_cast<long long>(eligible)) {
            std::printf("  FAIL gen-shutdown: far admission/non-air proof missing: %s\n", atlas_gen_last_error(gen));
            finish(); return false;
        }
    }
    if (generatorShutdownNear) {
        int32_t chunk[2] = {256, 256};
        uint32_t flags = ~0u, info[164]{};
        AtlasStatus status = atlas_gen_run(gen, chunk, 1, nullptr, &flags, info, nullptr);
        uint64_t mask = 0;
        for (uint32_t i = 0; i < 40; i++) if (info[4 + i * 4 + 2] == 1) mask |= 1ull << i;
        uint32_t sent = 0;
        const uint64_t requested = mask;
        if (status == ATLAS_OK && !flags && mask) status = atlas_gen_ingest(gen, &mask, &sent, nullptr);
        std::printf("TRACE gen-shutdown near-witness status=%d flags=%u requested=%llx acceptedMask=%llx sent=%u\n",
                    int(status), flags, (unsigned long long)requested, (unsigned long long)mask, sent);
        if (status != ATLAS_OK || flags || !requested || !mask || !sent) {
            std::printf("  FAIL gen-shutdown: near non-air/admission proof missing: %s\n", atlas_gen_last_error(gen));
            finish(); return false;
        }
    }
    // This is the final actual submission/copy. No frame, manual counter advance or host semaphore signal follows it.
    bool completed = frame();
    CHECK(completed);
    std::printf("TRACE gen-shutdown no-more-frames submitted=%lld completedBookkeeping=%lld coarseGiven=%lld\n",
                generatorShutdownNumber(ctx, "submittedFrames"), generatorShutdownNumber(ctx, "completedFrames"),
                generatorShutdownNumber(ctx, "coarseGiven"));
    finish();
    return completed && failures == before;
}

static bool generatorShutdownOwned(uint32_t W, uint32_t H) {
    AtlasContext* ctx = nullptr;
    if (atlas_create(ATLAS_BACKEND_VULKAN, &ctx) != ATLAS_OK || !ctx) return false;
    Scene s;
    bool ok = generatorShutdownScene(ctx, W, H, s);
    if (ok) {
        std::vector<uint32_t> color(size_t(W) * H);
        AtlasFrameOutput out{color.data(), nullptr, nullptr};
        ok = generatorShutdownRun(ctx, [&] {
            if (atlas_render(ctx, &s.f, &out) != ATLAS_OK) return false;
            long long submitted = generatorShutdownNumber(ctx, "submittedFrames");
            long long completed = generatorShutdownNumber(ctx, "completedFrames");
            std::printf("TRACE gen-shutdown actual-owned-readback-complete submitted=%lld completed=%lld\n", submitted, completed);
            return submitted > 0 && completed == submitted;
        });
    }
    std::printf("TRACE gen-shutdown context-destroy-begin\n");
    atlas_destroy(ctx);
    std::printf("TRACE gen-shutdown context-destroy-end\n");
    return ok;
}
