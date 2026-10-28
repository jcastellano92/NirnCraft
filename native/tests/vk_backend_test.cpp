// Minecraft's shared Vulkan backend path (docs/ATLAS-VULKAN-BACKEND.md), without the game:
//  S3  atlas_create_on_vulkan on a device this test makes the way NirnCraft's device-sharing mixin makes Minecraft's
//      (shaderInt64, timeline semaphores, atlas's frame queue in a compute-only family where there is one, the
//      generator's beside it); atlas_vk_render_shared's splice runs on the test's "Minecraft" graphics queue (wait,
//      execute, signal) into the test's own images, read back and compared against the host reference render.
// usage: atlas_vk_test <data dir>   (ATLAS_VK_VALIDATION=1: the Khronos layer on the device, any error fails)
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

#include <chrono>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <fstream>
#include <string>
#include <thread>
#include <vector>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#else
#include <dlfcn.h>
#include <signal.h>
#include <sys/wait.h>
#include <unistd.h>
#endif

#include "atlas/atlas.h"
#include "camera.hpp"

static int failures = 0;
#define CHECK(c)                                                              \
    do {                                                                      \
        if (!(c)) {                                                           \
            std::printf("  FAIL %s:%d: %s\n", __FILE__, __LINE__, #c);        \
            failures++;                                                       \
        }                                                                     \
    } while (0)

// ---- the few Vulkan entry points the test's own device needs (the loader opened at run time, as atlas_core does) ----
#define VKT_FNS(X)                                                                                                    \
    X(vkCreateInstance) X(vkDestroyInstance) X(vkEnumeratePhysicalDevices) X(vkGetPhysicalDeviceProperties)            \
    X(vkGetPhysicalDeviceFeatures) X(vkGetPhysicalDeviceFeatures2) X(vkGetPhysicalDeviceQueueFamilyProperties) X(vkGetPhysicalDeviceMemoryProperties)  \
    X(vkEnumerateDeviceExtensionProperties) X(vkCreateDevice) X(vkDestroyDevice) X(vkGetDeviceQueue) X(vkQueueSubmit) \
    X(vkQueueWaitIdle) X(vkDeviceWaitIdle) X(vkCreateImage) X(vkDestroyImage) X(vkGetImageMemoryRequirements)         \
    X(vkAllocateMemory) X(vkFreeMemory) X(vkBindImageMemory) X(vkCreateBuffer) X(vkDestroyBuffer)                     \
    X(vkGetBufferMemoryRequirements) X(vkBindBufferMemory) X(vkMapMemory) X(vkCreateCommandPool)                      \
    X(vkDestroyCommandPool) X(vkAllocateCommandBuffers) X(vkBeginCommandBuffer) X(vkEndCommandBuffer)                 \
    X(vkCmdPipelineBarrier) X(vkCmdCopyImageToBuffer)
static PFN_vkGetInstanceProcAddr gipa = nullptr;
#define VKT_DECL(n) static PFN_##n n = nullptr;
VKT_FNS(VKT_DECL)
#undef VKT_DECL

static bool loadVulkan() {
#if defined(_WIN32)
    HMODULE lib = LoadLibraryA("vulkan-1.dll");
    if (lib) gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(reinterpret_cast<void*>(GetProcAddress(lib, "vkGetInstanceProcAddr")));
#else
    void* lib = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
    if (lib) gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib, "vkGetInstanceProcAddr"));
#endif
    if (!gipa) return false;
    vkCreateInstance = reinterpret_cast<PFN_vkCreateInstance>(gipa(nullptr, "vkCreateInstance"));
    return vkCreateInstance != nullptr;
}

static void loadInstance(VkInstance inst) {
#define VKT_LOAD(n) n = reinterpret_cast<PFN_##n>(gipa(inst, #n));
    VKT_FNS(VKT_LOAD)
#undef VKT_LOAD
}

static VkResult submitSplice(VkQueue queue, const AtlasVkSplice& sp) {
    VkSemaphore wait = reinterpret_cast<VkSemaphore>(uintptr_t(sp.waitSemaphore));
    VkSemaphore signal = reinterpret_cast<VkSemaphore>(uintptr_t(sp.signalSemaphore));
    VkCommandBuffer command = static_cast<VkCommandBuffer>(sp.commandBuffer);
    VkPipelineStageFlags stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    VkTimelineSemaphoreSubmitInfo timeline{VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO};
    timeline.waitSemaphoreValueCount = 1; timeline.pWaitSemaphoreValues = &sp.waitValue;
    timeline.signalSemaphoreValueCount = 1; timeline.pSignalSemaphoreValues = &sp.signalValue;
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.pNext = &timeline; submit.waitSemaphoreCount = 1; submit.pWaitSemaphores = &wait;
    submit.pWaitDstStageMask = &stage; submit.commandBufferCount = 1; submit.pCommandBuffers = &command;
    submit.signalSemaphoreCount = 1; submit.pSignalSemaphores = &signal;
    return vkQueueSubmit(queue, 1, &submit, VK_NULL_HANDLE);
}

static int validationErrors = 0;
static VKAPI_ATTR VkBool32 VKAPI_CALL onMessage(VkDebugUtilsMessageSeverityFlagBitsEXT sev, VkDebugUtilsMessageTypeFlagsEXT,
                                                const VkDebugUtilsMessengerCallbackDataEXT* d, void*) {
    if (sev & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) {
        validationErrors++;
        if (validationErrors <= 10) std::printf("  [validation] %s\n", d && d->pMessage ? d->pMessage : "?");
    }
    return VK_FALSE;
}

static float halfToFloat(uint16_t h) {
    uint32_t s = uint32_t(h >> 15) << 31, e = (h >> 10) & 31, m = h & 1023, f;
    if (e == 0) {
        if (m == 0) {
            f = s;
        } else {  // subnormal
            int sh = 0;
            while (!(m & 1024)) m <<= 1, sh++;
            f = s | uint32_t(113 - sh) << 23 | (m & 1023) << 13;
        }
    } else if (e == 31) {
        f = s | 0x7F800000u | m << 13;
    } else {
        f = s | (e + 112) << 23 | m << 13;
    }
    float r;
    std::memcpy(&r, &f, 4);
    return r;
}

struct Frame {
    std::vector<uint16_t> color;  // RGBA16F, rows bottom-up
    std::vector<float> depth;     // R32F, rows bottom-up
    std::vector<uint16_t> nearLight;  // RGBA16F, rows bottom-up
};

static bool gridFixture() {
    const char* e = std::getenv("ATLAS_VK_TEST_GRID");
    return e && *e == '1';
}

// PRIMARY_NEAR depth is NDC in [0,1] inside the host frustum, otherwise negative view distance;
// -1e7 is the sky sentinel. Count both kinds of geometry, not just positive values.
static void contentReceipt(const char* path, const std::vector<float>& depth, size_t litPixels, bool lit,
                           const AtlasFrame& frame, bool bottomUp) {
    size_t positive = 0, farGeometry = 0, sky = 0, invalid = 0;
    for (float d : depth) {
        if (!std::isfinite(d)) invalid++;
        else if (!lit) {
            // The non-primary path stores view distance, with 1e30 for the sky.
            if (d > 0.0f && d < 1e29f) positive++;
            else if (d >= 1e29f) sky++;
            else invalid++;
        } else if (d >= 0.0f && d < 1.0f) positive++;
        else if (d < 0.0f && d > -1e7f) farGeometry++;
        else sky++;
    }
    std::printf("  receipt %s content: %sGeometry=%zu farGeometry=%zu skyOrFarPlane=%zu invalid=%zu nonzeroNearLightRGB=%zu\n",
                path, lit ? "ndc" : "distance", positive, farGeometry, sky, invalid, litPixels);
    CHECK(invalid == 0 && positive + farGeometry > 0);
    if (lit) CHECK(litPixels > 0);
    if (gridFixture()) {
        size_t nearHits = 0, centralNear = 0, pillarSamples = 0, pillarMatched = 0;
        double closest = 1e30, farthest = 0;
        double expectedMin = 1e30, expectedMax = 0, actualMin = 1e30, actualMax = 0, maxError = 0;
        const float* m = frame.invViewProj;
        for (uint32_t y = 0; y < frame.height; y++) for (uint32_t x = 0; x < frame.width; x++) {
            size_t i = size_t(bottomUp ? frame.height - 1 - y : y) * frame.width + x;
            float nx = (float(x) + 0.5f) / frame.width * 2.0f - 1.0f;
            float ny = 1.0f - (float(y) + 0.5f) / frame.height * 2.0f;
            // Choose rays strictly inside the front face of the grid-only pillar, well away
            // from voxel edges. The flat fallback floor cannot produce these depths.
            float rayPoint[4];
            for (int k = 0; k < 4; k++) rayPoint[k] = m[k] * nx + m[4 + k] * ny + m[8 + k] * 0.5f + m[12 + k];
            double rx = rayPoint[0], ry = rayPoint[1], rz = rayPoint[2];
            double length = std::sqrt(rx * rx + ry * ry + rz * rz);
            if (rayPoint[3] < 0) length = -length;
            if (length != 0) { rx /= length; ry /= length; rz /= length; }
            double entry = rz < 0 ? (-9.0 - 4.5) / rz : -1;
            double floor = ry < 0 ? (100.0 - 108.0) / ry : -1;
            double hitX = 8.5 + entry * rx, hitY = 108.0 + entry * ry;
            bool pillar = entry > 0 && floor > entry + 2.0 && hitX > 2.25 && hitX < 4.75 && hitY > 100.25 && hitY < 105.75;
            float z = depth[i];
            if (pillar) { pillarSamples++; expectedMin = std::fmin(expectedMin, entry); expectedMax = std::fmax(expectedMax, entry); }
            if (!(z >= 0.0f && z < 1.0f)) continue;
            float p[4];
            for (int k = 0; k < 4; k++) p[k] = m[k] * nx + m[4 + k] * ny + m[8 + k] * z + m[12 + k];
            if (std::fabs(p[3]) < 1e-20f) continue;
            double distance = std::sqrt(double(p[0]) * p[0] + double(p[1]) * p[1] + double(p[2]) * p[2]) / std::fabs(p[3]);
            if (pillar) {
                actualMin = std::fmin(actualMin, distance); actualMax = std::fmax(actualMax, distance);
                double error = std::fabs(distance - entry);
                maxError = std::fmax(maxError, error);
                if (std::isfinite(distance) && error < 0.05) pillarMatched++;
            }
            if (distance > 0 && distance < 32.0) {
                nearHits++;
                if (x >= frame.width / 2 - 2 && x <= frame.width / 2 + 2 &&
                    y >= frame.height / 2 - 2 && y <= frame.height / 2 + 2) centralNear++;
                closest = std::fmin(closest, distance); farthest = std::fmax(farthest, distance);
            }
        }
        std::printf("  receipt %s grid near: %zu hits under 32 blocks, %zu in central 5x5, distance %.3f..%.3f\n",
                    path, nearHits, centralNear, closest, farthest);
        CHECK(nearHits > 0 && centralNear > 0);
        std::printf("  receipt %s grid pillar: %zu/%zu interior rays match; expected %.3f..%.3f actual %.3f..%.3f max error %.3f (tolerance 0.05)\n",
                    path, pillarMatched, pillarSamples, expectedMin, expectedMax, actualMin, actualMax, maxError);
        CHECK(pillarSamples >= 3 && pillarMatched == pillarSamples);
    }
}

static size_t nonzeroHalfLight(const uint16_t* light, size_t pixels) {
    if (!light) return 0;
    size_t nonzero = 0;
    for (size_t i = 0; i < pixels; i++) {
        float r = halfToFloat(light[i * 4]), g = halfToFloat(light[i * 4 + 1]), b = halfToFloat(light[i * 4 + 2]);
        if (std::isfinite(r) && std::isfinite(g) && std::isfinite(b) && (r != 0 || g != 0 || b != 0)) nonzero++;
    }
    return nonzero;
}

// host frame (rows top-down, float) vs a bottom-up half / float frame: pixels whose colour is off by more than half
// precision, and whose depth differs
static void compareHost(const std::vector<float>& refC, const std::vector<float>& refD, const Frame& fr, uint32_t W,
                        uint32_t H, size_t& badColor, size_t& badDepth) {
    badColor = badDepth = 0;
    for (uint32_t y = 0; y < H; y++)
        for (uint32_t x = 0; x < W; x++) {
            size_t a = size_t(y) * W + x, b = size_t(H - 1 - y) * W + x;
            bool bad = false;
            for (int k = 0; k < 4; k++) {
                float r = refC[a * 4 + k], g = halfToFloat(fr.color[b * 4 + k]);
                if (!std::isfinite(r) || !std::isfinite(g) ||
                    std::fabs(double(r) - g) > std::fmax(1e-3, std::fabs(r) * 1e-3)) bad = true;
            }
            badColor += bad;
            float ra = refD[a], gb = fr.depth[b];
            if (!(ra == gb || (ra > 1e29f && gb > 1e29f))) badDepth++;
        }
}

// Publish flips rows and converts RGB to half at preExposure=1; alpha has a separate visibility/water encoding.
static void compareNearLight(const char* path, const std::vector<float>& reference, const Frame& output,
                             uint32_t W, uint32_t H) {
    size_t pixels = size_t(W) * H;
    CHECK(reference.size() == pixels * 4 && output.nearLight.size() == pixels * 4);
    if (reference.size() != pixels * 4 || output.nearLight.size() != pixels * 4) return;
    size_t badPixels = 0;
    for (uint32_t y = 0; y < H; y++)
        for (uint32_t x = 0; x < W; x++) {
            size_t a = size_t(y) * W + x, b = size_t(H - 1 - y) * W + x;
            bool bad = false;
            for (int k = 0; k < 3; k++) {
                float r = reference[a * 4 + k], g = halfToFloat(output.nearLight[b * 4 + k]);
                if (!std::isfinite(r) || !std::isfinite(g) ||
                    std::fabs(double(r) - g) > std::fmax(1e-3, std::fabs(r) * 1e-3)) bad = true;
            }
            badPixels += bad;
        }
    std::printf("  receipt %s near-light RGB: %zu / %zu pixels differ (half tolerance, preExposure=1)\n", path, badPixels, pixels);
    CHECK(badPixels == 0);
}

struct Scene {
    AtlasFrame f{};
    double centre[3]{};
};

static bool loadScene(AtlasContext* ctx, const std::string& data, uint32_t W, uint32_t H, Scene& s) {
    if (!gridFixture() && atlas_preview_load_raster(ctx, (data + "/tamriel_raster.bin").c_str()) != ATLAS_OK) {
        std::printf("  SKIP: %s\n", atlas_last_error(ctx));
        return false;
    }
    double x = gridFixture() ? 8.5 : 0.0, z = gridFixture() ? 4.5 : 0.0;
    double y = gridFixture() ? 108.0 : std::fmax(atlas_preview_height(ctx, x, z), 63.0) + 60;
    for (int lod = 0; !gridFixture() && lod < 7; lod++) {
        int span = 256 << lod;
        int cx = int(std::floor(x / span)), cz = int(std::floor(z / span));
        if (atlas_preview_generate(ctx, lod, (cx - 2) * span, (cz - 2) * span, (cx + 3) * span, (cz + 3) * span) != ATLAS_OK) {
            std::printf("  FAIL: scene generation at level %d: %s\n", lod, atlas_last_error(ctx));
            return false;
        }
    }
    if (gridFixture()) {
        // Use a flat deterministic fallback too: the raster's real terrain can otherwise surround this test camera.
        std::vector<float> surface(256 * 256, 100.0f), water(256 * 256, NAN);
        std::vector<uint16_t> top(256 * 256, 2), fill(256 * 256, 3);
        for (int tz = -1; tz <= 1; tz++) for (int tx = -1; tx <= 1; tx++) {
            AtlasColumnTile tile{0, tx, tz, surface.data(), water.data(), top.data(), fill.data(), nullptr, nullptr};
            if (atlas_upload_column_tile(ctx, &tile) != ATLAS_OK) {
                std::printf("  FAIL: fixture column (%d,%d): %s\n", tx, tz, atlas_last_error(ctx));
                return false;
            }
        }
        // Deterministic mixed level-0 sections: floor y=96..99 and a visible small pillar above it.
        std::vector<uint16_t> section(4096);
        for (int cz = -2; cz <= 2; cz++) for (int cx = -2; cx <= 2; cx++) {
            for (int ly = 0; ly < 16; ly++) for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
                int wx = cx * 16 + lx, wz = cz * 16 + lz, wy = 96 + ly;
                bool pillar = wx >= 2 && wx < 5 && wz >= -12 && wz < -9 && wy >= 100 && wy < 106;
                section[size_t(lx + 16 * (lz + 16 * ly))] = wy < 100 ? (wy == 99 ? 2 : 3) : (pillar ? 1 : 0);
            }
            if (atlas_upload_section(ctx, cx, 6, cz, section.data()) != ATLAS_OK) {
                std::printf("  FAIL: fixture section (%d,6,%d): %s\n", cx, cz, atlas_last_error(ctx));
                return false;
            }
        }
        std::printf("  receipt fixture: flat y100 columns, 25 mixed sections at y96..111, camera (8.5,108,4.5), floor y99\n");
    }
    s.centre[0] = x;
    s.centre[1] = y;
    s.centre[2] = z;
    if (atlas_update(ctx, s.centre, 0) != ATLAS_OK) { std::printf("  FAIL: scene update: %s\n", atlas_last_error(ctx)); return false; }
    AtlasFrame& f = s.f;
    f = AtlasFrame{};
    for (int i = 0; i < 3; i++) f.cameraPos[i] = s.centre[i];
    atlas::cameraMatrix(gridFixture() ? 0 : 105, gridFixture() ? -45 : -3, 70, double(W) / H, f.invViewProj);
    f.width = W;
    f.height = H;
    f.nearFieldEnd = 96;
    f.maxDistance = 40000;
    f.planetRadius = 6371000;
    f.sunDir[0] = 0.55f;
    f.sunDir[1] = 0.5f;
    f.sunDir[2] = 0.66f;
    f.lodBias = 1;
    f.flags = ATLAS_FRAME_SHADOWS | ATLAS_FRAME_ATMOS | ATLAS_FRAME_SKY;
    if (const char* lit = std::getenv("ATLAS_VK_TEST_LIT"); gridFixture() || (lit && *lit == '1'))
        f.flags |= ATLAS_FRAME_NEAR_LIGHT | ATLAS_FRAME_PRIMARY_NEAR_LIT;
    return true;
}

#include "gen_shutdown_fixture.hpp"

static const int FRAMES = 40, WARM = 10;  // (timed: the frames after the first WARM)
static AtlasStats referenceStats{};
static bool haveReferenceStats = false;

static void sceneReceipt(AtlasContext* ctx, const char* path, bool reference) {
    AtlasStats st{};
    CHECK(atlas_get_stats(ctx, &st) == ATLAS_OK);
    std::printf("  receipt %s: regions=%u pending=%u nodes=%llu leaves=%llu materials=%llu\n", path,
                st.regions, st.regionsDirty, (unsigned long long)st.nodeBytes, (unsigned long long)st.leafBytes,
                (unsigned long long)st.materialBytes);
    CHECK(st.regions > 0 && st.nodeBytes > 0);
    CHECK(st.regionsDirty == 0);
    if (gridFixture()) {
        std::string info = atlas_info(ctx);
        size_t grid = info.find("\"voxgrid\":{");
        auto field = [&](const char* name) -> long long {
            if (grid == std::string::npos) return -1;
            std::string key = std::string("\"") + name + "\":";
            size_t at = info.find(key, grid);
            return at == std::string::npos ? -1 : std::strtoll(info.c_str() + at + key.size(), nullptr, 10);
        };
        long long slots = field("slots"), resident = field("resident"), built = field("built");
        long long pending = field("pending"), waiting = field("waiting"), gpuWaiting = field("gpuWaiting");
        long long coarseWaiting = field("coarseWaiting"), baking = field("baking");
        std::printf("  receipt %s grid: slots=%lld resident=%lld built=%lld pending=%lld waiting=%lld gpuWaiting=%lld coarseWaiting=%lld baking=%lld\n",
                    path, slots, resident, built, pending, waiting, gpuWaiting, coarseWaiting, baking);
        CHECK(slots > 0 && resident > 0 && built > 0);
        CHECK(pending == 0 && waiting == 0 && gpuWaiting == 0 && coarseWaiting == 0 && baking == 0);
        const int32_t xyz[] = {8, 99, 4, 8, 100, 4, 3, 103, -10, 8, 96, 4};
        uint32_t blocks[4] = {};
        AtlasStatus read = atlas_debug_read_blocks(ctx, xyz, 4, blocks);
        std::printf("  receipt %s grid GPU witness: status=%d ids=%u,%u,%u,%u expected=2,0,1,3\n",
                    path, int(read), blocks[0], blocks[1], blocks[2], blocks[3]);
        CHECK(read == ATLAS_OK && blocks[0] == 2 && blocks[1] == 0 && blocks[2] == 1 && blocks[3] == 3);
    }
    if (reference) { referenceStats = st; haveReferenceStats = true; }
    else if (haveReferenceStats) {
        CHECK(st.regions == referenceStats.regions && st.nodeBytes == referenceStats.nodeBytes &&
              st.leafBytes == referenceStats.leafBytes && st.materialBytes == referenceStats.materialBytes);
    }
}

// Real replacements exercise retirement accounting independently of transport parity.
static bool memoryGauges(const std::string& data, uint32_t W, uint32_t H) {
    AtlasContext* ctx = nullptr;
    if (atlas_create(ATLAS_BACKEND_VULKAN, &ctx) != ATLAS_OK) return false;
    Scene scene;
    if (!loadScene(ctx, data, W, H, scene)) { atlas_destroy(ctx); return false; }
    std::vector<float> depth(size_t(W) * H);
    AtlasFrameOutput output{nullptr, nullptr, depth.data(), nullptr, nullptr};
    uint64_t completedBefore = 0;
    bool sawRetired = false;
    for (int frame = 0; frame < 10; ++frame) {
        // Request growth twice (uploads may coalesce), observe a real retirement,
        // then let fences drain before asserting the retained buffers were released.
        if (frame == 2 || frame == 4) {
            const uint32_t count = frame == 2 ? 1 : 16;
            std::vector<uint32_t> textures(size_t(count) * 6 * 256 + count, 0xffffffffu);
            for (uint32_t i = 0; i < count; ++i) textures[size_t(count) * 6 * 256 + i] = 1;
            CHECK(atlas_set_material_textures(ctx, 1, textures.data(), count) == ATLAS_OK);
        }
        CHECK(atlas_render(ctx, &scene.f, &output) == ATLAS_OK);
        std::string info = atlas_info(ctx);
        const size_t base = info.find("\"backendMemory\":{");
        auto number = [&](const char* field) -> uint64_t {
            const std::string key = std::string("\"") + field + "\":";
            size_t at = base == std::string::npos ? base : info.find(key, base);
            CHECK(at != std::string::npos);
            return at == std::string::npos ? 0 : std::strtoull(info.c_str() + at + key.size(), nullptr, 10);
        };
        const uint64_t allocated = number("allocatedBytes"), retired = number("retiredBufferBytes");
        const uint64_t count = number("retiredBufferCount"), completed = number("completedFrames");
        const uint64_t submitted = number("submittedFrames");
        CHECK(allocated > 0 && retired <= allocated);
        CHECK(completed >= completedBefore && completed <= submitted);
        completedBefore = completed;
        CHECK((retired == 0) == (count == 0));
        sawRetired |= retired > 0 && count > 0;
        if (frame == 9) CHECK(retired == 0 && count == 0);
        std::printf("TRACE memory gauges frame %d: allocated=%llu retired=%llu count=%llu completed=%llu submitted=%llu\n",
                    frame, (unsigned long long)allocated, (unsigned long long)retired, (unsigned long long)count,
                    (unsigned long long)completed, (unsigned long long)submitted);
        std::printf("MEMORY_GAUGE_JSON %s\n", info.c_str());
    }
    CHECK(sawRetired);
    atlas_destroy(ctx);
    return failures == 0;
}

// A separate context is the reference, with the same scene and number of frames as either transport.
static bool referenceFrame(const std::string& data, uint32_t W, uint32_t H, std::vector<float>& color, std::vector<float>& depth, std::vector<float>& light) {
    AtlasContext* ctx = nullptr;
    if (atlas_create(ATLAS_BACKEND_VULKAN, &ctx) != ATLAS_OK) {
        std::printf("  FAIL: reference context: %s\n", atlas_last_error(nullptr));
        failures++;
        return false;
    }
    Scene s;
    if (!loadScene(ctx, data, W, H, s)) { atlas_destroy(ctx); failures++; return false; }
    color.assign(size_t(W) * H * 4, 0.f);
    depth.assign(size_t(W) * H, 0.f);
    light.assign(size_t(W) * H * 4, 0.f);
    AtlasFrameOutput output{nullptr, nullptr, depth.data(), color.data(), light.data()};
    bool ok = true;
    for (int i = 0; i < FRAMES; i++) {
        if (atlas_render(ctx, &s.f, &output) != ATLAS_OK) {
            std::printf("  FAIL reference frame %d: %s\n", i, atlas_last_error(ctx));
            failures++; ok = false; break;
        }
    }
    std::printf("  receipt reference: independent context, %d frames, output age 0, info %s\n", FRAMES, atlas_info(ctx));
    sceneReceipt(ctx, "reference", true);
    size_t nonzero = 0;
    for (size_t i = 0; i < size_t(W) * H; i++) {
        float r = light[i * 4], g = light[i * 4 + 1], b = light[i * 4 + 2];
        if (std::isfinite(r) && std::isfinite(g) && std::isfinite(b) && (r != 0 || g != 0 || b != 0)) nonzero++;
    }
    contentReceipt("reference", depth, nonzero, (s.f.flags & ATLAS_FRAME_NEAR_LIGHT) != 0, s.f, false);
    atlas_destroy(ctx);
    return ok;
}

// ---- S3 ------------------------------------------------------------------------------------------------------------
struct Img {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
};

static uint32_t memType(VkPhysicalDevice phys, uint32_t bits, VkMemoryPropertyFlags need) {
    VkPhysicalDeviceMemoryProperties mp;
    vkGetPhysicalDeviceMemoryProperties(phys, &mp);
    for (uint32_t t = 0; t < mp.memoryTypeCount; t++)
        if ((bits & (1u << t)) && (mp.memoryTypes[t].propertyFlags & need) == need) return t;
    return UINT32_MAX;
}

static bool stageThree(const std::string& data, uint32_t W, uint32_t H, const std::vector<float>& refC,
                       const std::vector<float>& refD, const std::vector<float>& refLight, int lifecycle = 0) {
    bool generatorShutdown = lifecycle == 4;
    bool abandon = lifecycle > 0 && lifecycle < 4;
    std::printf("S3: atlas on the test's \"Minecraft\" device, spliced into its queue\n");
    if (abandon) { std::printf("TRACE abandon: begin device setup\n"); std::fflush(stdout); }
    if (!loadVulkan()) {
        std::printf("  SKIP: no Vulkan loader\n");
        return false;
    }
    const char* val = std::getenv("ATLAS_VK_VALIDATION");
    bool validate = val && *val == '1';
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "atlas_vk_test (Minecraft's device)";
    app.apiVersion = VK_API_VERSION_1_2;
    const char* layers[] = {"VK_LAYER_KHRONOS_validation"};
    const char* exts[] = {VK_EXT_DEBUG_UTILS_EXTENSION_NAME};
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    if (validate) {
        ici.enabledLayerCount = 1;
        ici.ppEnabledLayerNames = layers;
        ici.enabledExtensionCount = 1;
        ici.ppEnabledExtensionNames = exts;
    }
    VkInstance inst = VK_NULL_HANDLE;
    if (vkCreateInstance(&ici, nullptr, &inst) != VK_SUCCESS) {
        std::printf("  SKIP: vkCreateInstance failed%s\n", validate ? " (is the validation layer on VK_ADD_LAYER_PATH?)" : "");
        return false;
    }
    loadInstance(inst);
    VkDebugUtilsMessengerEXT messenger = VK_NULL_HANDLE;
    if (validate) {
        auto create = reinterpret_cast<PFN_vkCreateDebugUtilsMessengerEXT>(gipa(inst, "vkCreateDebugUtilsMessengerEXT"));
        VkDebugUtilsMessengerCreateInfoEXT mi{VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT};
        mi.messageSeverity = VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT;
        mi.messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT;
        mi.pfnUserCallback = onMessage;
        if (create) create(inst, &mi, nullptr, &messenger);
    }
    // the device: a discrete one with shaderInt64 first, as atlas would pick
    uint32_t n = 0;
    vkEnumeratePhysicalDevices(inst, &n, nullptr);
    std::vector<VkPhysicalDevice> devs(n);
    vkEnumeratePhysicalDevices(inst, &n, devs.data());
    VkPhysicalDevice phys = VK_NULL_HANDLE;
    for (int pass = 0; pass < 2 && !phys; pass++)
        for (auto d : devs) {
            VkPhysicalDeviceProperties p;
            VkPhysicalDeviceFeatures fe;
            vkGetPhysicalDeviceProperties(d, &p);
            vkGetPhysicalDeviceFeatures(d, &fe);
            if (fe.shaderInt64 && p.apiVersion >= VK_API_VERSION_1_2 &&
                (pass == 1 || p.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU)) {
                phys = d;
                break;
            }
        }
    if (!phys) {
        std::printf("  SKIP: no Vulkan 1.2 device with shaderInt64\n");
        vkDestroyInstance(inst, nullptr);
        return false;
    }
    if (generatorShutdown && !generatorShutdownCacheDevice(phys)) {
        if (messenger) {
            auto destroy = reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(gipa(inst, "vkDestroyDebugUtilsMessengerEXT"));
            if (destroy) destroy(inst, messenger, nullptr);
        }
        vkDestroyInstance(inst, nullptr);
        return false;
    }
    uint32_t qn = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(phys, &qn, nullptr);
    std::vector<VkQueueFamilyProperties> qf(qn);
    vkGetPhysicalDeviceQueueFamilyProperties(phys, &qn, qf.data());
    // the queues as the mixin asks for them: "Minecraft's" graphics queue 0, atlas's frames in a compute-only family
    // where there is one (else graphics queue 1), the generator beside it
    uint32_t gfx = UINT32_MAX, comp = UINT32_MAX;
    for (uint32_t i = 0; i < qn; i++) {
        bool g = qf[i].queueFlags & VK_QUEUE_GRAPHICS_BIT, c = qf[i].queueFlags & VK_QUEUE_COMPUTE_BIT;
        if (gfx == UINT32_MAX && g && c) gfx = i;
        if (comp == UINT32_MAX && c && !g) comp = i;
    }
    if (gfx == UINT32_MAX) {
        std::printf("  SKIP: no graphics + compute family\n");
        vkDestroyInstance(inst, nullptr);
        return false;
    }
    std::vector<uint32_t> want(qn, 0);
    want[gfx] = 1;
    // (ATLAS_VK_TEST_GRAPHICS_QUEUE=1: atlas's frames on a second graphics queue instead, the fallback without a
    // compute-only family)
    const char* gq = std::getenv("ATLAS_VK_TEST_GRAPHICS_QUEUE");
    uint32_t frameFam = comp != UINT32_MAX && !(gq && *gq == '1') ? comp : gfx;
    if (want[frameFam] >= qf[frameFam].queueCount) {
        std::printf("  SKIP: no room for atlas's queue\n");
        vkDestroyInstance(inst, nullptr);
        return false;
    }
    uint32_t frameIdx = want[frameFam]++;
    uint32_t genFam = UINT32_MAX, genIdx = 0;
    for (uint32_t f : {comp, gfx})
        if (genFam == UINT32_MAX && f != UINT32_MAX && want[f] < qf[f].queueCount) genFam = f, genIdx = want[f]++;
    std::vector<VkDeviceQueueCreateInfo> qci;
    std::vector<std::vector<float>> prio(qn);
    for (uint32_t i = 0; i < qn; i++)
        if (want[i]) {
            prio[i].assign(want[i], 0.0f);
            VkDeviceQueueCreateInfo q{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
            q.queueFamilyIndex = i;
            q.queueCount = want[i];
            q.pQueuePriorities = prio[i].data();
            qci.push_back(q);
        }
    VkPhysicalDeviceFeatures have;
    vkGetPhysicalDeviceFeatures(phys, &have);
    VkPhysicalDeviceFeatures feats{};
    feats.shaderInt64 = VK_TRUE;
    feats.shaderFloat64 = have.shaderFloat64;
    feats.shaderInt16 = have.shaderInt16;
    VkPhysicalDeviceVulkan12Features have12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    VkPhysicalDeviceFeatures2 f2{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2};
    f2.pNext = &have12;
    vkGetPhysicalDeviceFeatures2(phys, &f2);
    VkPhysicalDeviceVulkan12Features f12{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES};
    f12.timelineSemaphore = VK_TRUE;
    f12.shaderFloat16 = have12.shaderFloat16;  // (as the mixin adds them: some kernels declare Int16 / Float16)
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.pNext = &f12;
    dci.queueCreateInfoCount = uint32_t(qci.size());
    dci.pQueueCreateInfos = qci.data();
    dci.pEnabledFeatures = &feats;
    VkDevice dev = VK_NULL_HANDLE;
    if (vkCreateDevice(phys, &dci, nullptr, &dev) != VK_SUCCESS) {
        std::printf("  FAIL: the test's device\n");
        failures++;
        vkDestroyInstance(inst, nullptr);
        return false;
    }
    VkQueue mcQueue = VK_NULL_HANDLE;
    vkGetDeviceQueue(dev, gfx, 0, &mcQueue);
    std::printf("  families: graphics %u, atlas's frames %u (index %u), generator %d\n", gfx, frameFam, frameIdx,
                genFam == UINT32_MAX ? -1 : int(genFam));

    AtlasVkDevice ad{};
    ad.instance = inst;
    ad.physicalDevice = phys;
    ad.device = dev;
    ad.frameFamily = frameFam;
    ad.frameIndex = frameIdx;
    ad.genFamily = genFam;
    ad.genIndex = genIdx;
    ad.flags = have.shaderFloat64 ? ATLAS_VK_DEVICE_FLOAT64 : 0u;
    ad.graphicsFamily = gfx;
    if (generatorShutdown) {
        CHECK(frameIdx < want[frameFam] && !(frameFam == gfx && frameIdx == 0));
        CHECK(genFam == UINT32_MAX || (genIdx < want[genFam] &&
              !(genFam == gfx && genIdx == 0) && !(genFam == frameFam && genIdx == frameIdx)));
        AtlasVkDevice alias = ad;
        alias.frameFamily = gfx; alias.frameIndex = 0;
        AtlasContext* rejected = nullptr;
        CHECK(atlas_create_on_vulkan(&alias, &rejected) != ATLAS_OK && rejected == nullptr);
        alias = ad; alias.genFamily = frameFam; alias.genIndex = frameIdx;
        CHECK(atlas_create_on_vulkan(&alias, &rejected) != ATLAS_OK && rejected == nullptr);
        alias = ad; alias.genFamily = gfx; alias.genIndex = 0;
        CHECK(atlas_create_on_vulkan(&alias, &rejected) != ATLAS_OK && rejected == nullptr);
        if (const char* fallback = std::getenv("ATLAS_VK_TEST_GEN_FALLBACK"); fallback && std::strcmp(fallback, "1") == 0)
            ad.genFamily = UINT32_MAX;
        std::printf("TRACE terminal-close actual-queue-allocation frame=%u:%u gen=%u:%u; aliases-rejected; fallback=%d\n",
                    frameFam, frameIdx, ad.genFamily, genIdx, ad.genFamily == UINT32_MAX ? 1 : 0);
    }
    AtlasContext* ctx = nullptr;
    if (abandon) { std::printf("TRACE abandon: before adopted context\n"); std::fflush(stdout); }
    bool ok = atlas_create_on_vulkan(&ad, &ctx) == ATLAS_OK;
    CHECK(ok);
    if (!ok) std::printf("  atlas_create_on_vulkan: %s\n", atlas_last_error(nullptr));
    if (ok) {
        if (generatorShutdown && terminalClosePreAttach) {
            if (terminalCloseUpdate) CHECK(terminalCloseActualUpdate(ctx));
            if (terminalCloseFault == ATLAS_FAULT_CLOSE_PRODUCER) {
                AtlasGen* producer = nullptr;
                CHECK(atlas_gen_create_for(ctx, &producer) == ATLAS_OK && producer);
                CHECK(atlas_debug_fault(ctx, terminalCloseFault, 0) == ATLAS_OK);
                std::puts("TRACE terminal-close pre-attach real producer destroy");
                atlas_gen_destroy(producer);
            }
            CHECK(terminalClosePrepare(ctx));
            AtlasVkRetirement* token = nullptr;
            CHECK(atlas_vk_detach_retired(ctx, &token) == ATLAS_OK && token == nullptr);
            atlas_destroy(ctx);
            std::puts("TRACE terminal-close pre-attach context-destroy-end");
            vkDestroyDevice(dev, nullptr);
            if (messenger) {
                auto destroy = reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(gipa(inst, "vkDestroyDebugUtilsMessengerEXT"));
                if (destroy) destroy(inst, messenger, nullptr);
            }
            vkDestroyInstance(inst, nullptr);
            CHECK(validationErrors == 0);
            std::printf("TRACE terminal-close pre-attach external-device-destroy-end validation=%d\n", validationErrors);
            return failures == 0;
        }
        uint32_t ready = 0;
        CHECK(atlas_vk_copy_complete(ctx, 0, &ready) == ATLAS_OK && ready == 1);
        CHECK(atlas_vk_wait_copy(ctx, 0, 0) == ATLAS_OK);
        AtlasVkRetirement* none = nullptr;
        CHECK(atlas_vk_detach_retired(ctx, &none) == ATLAS_OK && none == nullptr);
    }

    // "Minecraft's" textures: the targets the splice copies into, then read back
    Img color, depth, nearLight, sun;
    VkBuffer rb = VK_NULL_HANDLE;
    VkDeviceMemory rbMem = VK_NULL_HANDLE;
    VkCommandPool pool = VK_NULL_HANDLE;
    auto makeImage = [&](Img& im, VkFormat fmt, uint32_t width, uint32_t height) {
        VkImageCreateInfo ic{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
        ic.imageType = VK_IMAGE_TYPE_2D;
        ic.format = fmt;
        ic.extent = {width, height, 1};
        ic.mipLevels = ic.arrayLayers = 1;
        ic.samples = VK_SAMPLE_COUNT_1_BIT;
        ic.tiling = VK_IMAGE_TILING_OPTIMAL;
        ic.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        vkCreateImage(dev, &ic, nullptr, &im.image);
        VkMemoryRequirements r;
        vkGetImageMemoryRequirements(dev, im.image, &r);
        VkMemoryAllocateInfo ma{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        ma.allocationSize = r.size;
        ma.memoryTypeIndex = memType(phys, r.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        vkAllocateMemory(dev, &ma, nullptr, &im.mem);
        vkBindImageMemory(dev, im.image, im.mem, 0);
    };
    double s3Ms = 0, s3WallMs = 0;
    if (ok) {
        makeImage(color, VK_FORMAT_R16G16B16A16_SFLOAT, W, H);
        makeImage(depth, VK_FORMAT_R32_SFLOAT, W, H);
        bool lit = gridFixture() || (std::getenv("ATLAS_VK_TEST_LIT") && std::getenv("ATLAS_VK_TEST_LIT")[0] == '1');
        if (lit) makeImage(nearLight, VK_FORMAT_R16G16B16A16_SFLOAT, W, H);
        if (abandon) makeImage(sun, VK_FORMAT_R32G32_SFLOAT, 64, 65);
        VkCommandPoolCreateInfo pc{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        pc.queueFamilyIndex = gfx;
        vkCreateCommandPool(dev, &pc, nullptr, &pool);
        VkCommandBuffer cmd[2];
        VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        ai.commandPool = pool;
        ai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        ai.commandBufferCount = 2;
        vkAllocateCommandBuffers(dev, &ai, cmd);
        // the targets to GENERAL, as Minecraft keeps its images
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        vkBeginCommandBuffer(cmd[0], &bi);
        VkImageMemoryBarrier ib[4];
        VkImage targetImages[] = {color.image, depth.image, nearLight.image, sun.image};
        int imageCount = 2 + (lit ? 1 : 0) + (abandon ? 1 : 0);
        if (abandon && !lit) targetImages[2] = sun.image;
        for (int i = 0; i < imageCount; i++) {
            ib[i] = {VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
            ib[i].image = targetImages[i];
            ib[i].subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
            ib[i].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            ib[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
            ib[i].srcQueueFamilyIndex = ib[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            ib[i].dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        }
        vkCmdPipelineBarrier(cmd[0], VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0,
                             nullptr, imageCount, ib);
        vkEndCommandBuffer(cmd[0]);
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd[0];
        vkQueueSubmit(mcQueue, 1, &si, VK_NULL_HANDLE);
        vkQueueWaitIdle(mcQueue);

        Scene s;
        if (abandon) { std::printf("TRACE abandon: before scene load\n"); std::fflush(stdout); }
        if (generatorShutdown ? generatorShutdownScene(ctx, W, H, s) : loadScene(ctx, data, W, H, s)) {
            CHECK(atlas_vk_attach(ctx, W, H) == ATLAS_OK);
            std::string info = atlas_info(ctx);
            CHECK(info.find("\"interop\":\"vulkan shared device (zero-copy)\"") != std::string::npos);
            CHECK(info.find("\"onMinecraftDevice\":true") != std::string::npos);
            AtlasGlFrameIO io{};
            io.dstColor = io.dstDepth = 1;
            io.dstNearLight = (s.f.flags & ATLAS_FRAME_NEAR_LIGHT) ? 1u : 0u;
            AtlasVkTargets tg{};
            tg.color = uint64_t(reinterpret_cast<uintptr_t>(color.image));
            tg.depth = uint64_t(reinterpret_cast<uintptr_t>(depth.image));
            tg.nearLight = uint64_t(reinterpret_cast<uintptr_t>(nearLight.image));
            tg.sunMap = uint64_t(reinterpret_cast<uintptr_t>(sun.image));
            if (generatorShutdown) {
                bool populated = generatorShutdownRun(ctx, [&] {
                    AtlasVkSplice splice{};
                    if (atlas_vk_render_shared(ctx, &s.f, &io, &tg, &splice) != ATLAS_OK || !splice.commandBuffer ||
                        submitSplice(mcQueue, splice) != VK_SUCCESS ||
                        atlas_vk_wait_copy(ctx, splice.signalValue, 5'000'000'000ull) != ATLAS_OK) return false;
                    uint32_t complete = 0;
                    if (atlas_vk_copy_complete(ctx, splice.signalValue, &complete) != ATLAS_OK || !complete) return false;
                    std::printf("TRACE gen-shutdown actual-copy-complete value=%llu\n", (unsigned long long)splice.signalValue);
                    return true;
                });
                CHECK(populated);
                CHECK(terminalClosePrepare(ctx));
                AtlasVkRetirement* retired = nullptr;
                CHECK(atlas_vk_detach_retired(ctx, &retired) == ATLAS_OK && retired);
                std::printf("TRACE gen-shutdown context-destroy-begin\n");
                atlas_destroy(ctx); ctx = nullptr;
                std::printf("TRACE gen-shutdown context-destroy-end\n");
                CHECK(vkQueueWaitIdle(mcQueue) == VK_SUCCESS);
                CHECK(atlas_vk_release_retired(retired) == ATLAS_OK);
                std::printf("TRACE gen-shutdown token-released\n");
                goto cleanup;
            }
            if (abandon) {
                CHECK(atlas_set_sun_map(ctx, 64, 256.0f) == ATLAS_OK);
                if (lifecycle >= 2) {
                    AtlasVkSplice previous{};
                    CHECK(atlas_vk_render_shared(ctx, &s.f, &io, &tg, &previous) == ATLAS_OK && previous.commandBuffer);
                    CHECK(submitSplice(mcQueue, previous) == VK_SUCCESS);
                    CHECK(atlas_vk_wait_copy(ctx, previous.signalValue, 5'000'000'000ull) == ATLAS_OK);
                    std::printf("TRACE abandon: prior Minecraft copy completed before cancellation scenario\n");
                    std::fflush(stdout);
                }
                AtlasVkSplice first{}, second{};
                std::printf("TRACE abandon: before first frame\n"); std::fflush(stdout);
                AtlasStatus firstStatus = atlas_vk_render_shared(ctx, &s.f, &io, &tg, &first);
                if (firstStatus != ATLAS_OK || !first.commandBuffer || !first.waitSemaphore || !first.signalSemaphore || first.signalValue == 0) {
                    std::printf("  FAIL: could not create first splice\n");
                    std::fflush(stdout);
                    std::_Exit(2);
                }
                std::printf("TRACE abandon: first splice omitted; before busy frame\n"); std::fflush(stdout);
                AtlasStats before{}, after{};
                CHECK(atlas_get_stats(ctx, &before) == ATLAS_OK);
                uint32_t ready = 1;
                CHECK(atlas_vk_copy_complete(ctx, first.signalValue, &ready) == ATLAS_OK && ready == 0);
                CHECK(atlas_vk_wait_copy(ctx, first.signalValue, 0) == ATLAS_ERR_TIMEOUT);
                AtlasStatus secondStatus = atlas_vk_render_shared(ctx, &s.f, &io, &tg, &second);
                CHECK(secondStatus == ATLAS_OK && !second.commandBuffer && !second.waitSemaphore && !second.signalSemaphore &&
                      !second.waitValue && !second.signalValue);
                CHECK(atlas_get_stats(ctx, &after) == ATLAS_OK);
                CHECK(before.raySteps == after.raySteps && before.lastFrameMs == after.lastFrameMs);
                CHECK(atlas_vk_attach(ctx, W + 1, H) == ATLAS_ERR_STATE);
                CHECK(atlas_set_sun_map(ctx, 128, 256.0f) == ATLAS_ERR_STATE);
                AtlasFrameOutput legacyOutput{};
                CHECK(atlas_render(ctx, &s.f, &legacyOutput) == ATLAS_ERR_STATE);
                CHECK(atlas_get_stats(ctx, &after) == ATLAS_OK);
                CHECK(before.raySteps == after.raySteps && before.lastFrameMs == after.lastFrameMs);
                CHECK(atlas_vk_copy_complete(ctx, first.signalValue, &ready) == ATLAS_OK && ready == 0);
                atlas_destroy(ctx);
                CHECK(atlas_vk_copy_complete(ctx, first.signalValue, &ready) == ATLAS_OK && ready == 0);
                std::printf("TRACE abandon: busy returned no work; resize and sun deferred\n");
                std::fflush(stdout);
                if (lifecycle >= 2) {
                    CHECK(atlas_vk_cancel_splice(ctx, first.signalValue + 1) == ATLAS_ERR_STATE);
                    CHECK(atlas_vk_cancel_splice(ctx, first.signalValue) == ATLAS_OK);
                    CHECK(atlas_vk_copy_complete(ctx, 0, &ready) == ATLAS_OK && ready == 1);
                    CHECK(atlas_vk_render_shared(ctx, &s.f, &io, &tg, &second) == ATLAS_OK && second.commandBuffer &&
                          second.signalValue > first.signalValue);
                    std::printf("TRACE abandon: canceled unrecorded splice; next frame offered\n"); std::fflush(stdout);
                }
                if (lifecycle == 3) {
                    CHECK(submitSplice(mcQueue, second) == VK_SUCCESS);
                    CHECK(atlas_vk_wait_copy(ctx, second.signalValue, 5'000'000'000ull) == ATLAS_OK);
                }
                AtlasVkRetirement* retired = nullptr;
                CHECK(atlas_vk_detach_retired(ctx, &retired) == ATLAS_OK && retired != nullptr);
                if (lifecycle != 3) { atlas_destroy(ctx); ctx = nullptr; }
                if (lifecycle == 2) {
                    CHECK(submitSplice(mcQueue, second) == VK_SUCCESS);  // submitted only after detach + context destroy
                    std::printf("TRACE abandon: submitted retained splice after context destroy\n"); std::fflush(stdout);
                }
                CHECK(vkQueueWaitIdle(mcQueue) == VK_SUCCESS);
                CHECK(atlas_vk_release_retired(retired) == ATLAS_OK);
                if (lifecycle == 3) {
                    AtlasMemoryInfo baseline{};
                    CHECK(atlas_memory_info(ctx, &baseline) == ATLAS_OK);
                    for (int cycle = 0; cycle < 4; cycle++) {
                        CHECK(atlas_vk_attach(ctx, W, H) == ATLAS_OK);
                        AtlasVkSplice next{};
                        CHECK(atlas_vk_render_shared(ctx, &s.f, &io, &tg, &next) == ATLAS_OK && next.commandBuffer);
                        CHECK(submitSplice(mcQueue, next) == VK_SUCCESS);
                        CHECK(atlas_vk_wait_copy(ctx, next.signalValue, 5'000'000'000ull) == ATLAS_OK);
                        AtlasVkRetirement* token = nullptr;
                        CHECK(atlas_vk_detach_retired(ctx, &token) == ATLAS_OK && token != nullptr);
                        CHECK(vkQueueWaitIdle(mcQueue) == VK_SUCCESS);
                        CHECK(atlas_vk_release_retired(token) == ATLAS_OK);
                        AtlasMemoryInfo now{};
                        CHECK(atlas_memory_info(ctx, &now) == ATLAS_OK);
                        std::printf("TRACE repeat retire %d: atlasBytes=%llu baseline=%llu\n", cycle,
                                    (unsigned long long)now.atlasBytes, (unsigned long long)baseline.atlasBytes);
                        CHECK(now.atlasBytes == baseline.atlasBytes);
                    }
                    atlas_destroy(ctx); ctx = nullptr;
                }
                std::printf("TRACE abandon: queue retired and token released\n");
                std::fflush(stdout);
                goto cleanup;
            }
            auto t0 = std::chrono::steady_clock::now();
            double inCall = 0;
            int submitted = 0;
            for (int i = 0; i < FRAMES; i++) {
                if (i == WARM) {
                    vkQueueWaitIdle(mcQueue);
                    t0 = std::chrono::steady_clock::now();
                    inCall = 0;
                }
                AtlasVkSplice sp{};
                auto c0 = std::chrono::steady_clock::now();
                if (atlas_vk_render_shared(ctx, &s.f, &io, &tg, &sp) != ATLAS_OK) {
                    std::printf("  FAIL atlas_vk_render_shared %d: %s\n", i, atlas_last_error(ctx));
                    failures++;
                    break;
                }
                inCall += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - c0).count();
                CHECK(sp.commandBuffer && sp.signalSemaphore && sp.waitSemaphore);
                // "Minecraft's" frame: wait for atlas's frame, the copies, the signal atlas reuses its images after
                VkSemaphore waitSem = reinterpret_cast<VkSemaphore>(uintptr_t(sp.waitSemaphore));
                VkSemaphore sigSem = reinterpret_cast<VkSemaphore>(uintptr_t(sp.signalSemaphore));
                VkCommandBuffer cb = static_cast<VkCommandBuffer>(sp.commandBuffer);
                VkPipelineStageFlags stage = VK_PIPELINE_STAGE_TRANSFER_BIT;
                VkTimelineSemaphoreSubmitInfo ts{VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO};
                ts.waitSemaphoreValueCount = 1;
                ts.pWaitSemaphoreValues = &sp.waitValue;
                ts.signalSemaphoreValueCount = 1;
                ts.pSignalSemaphoreValues = &sp.signalValue;
                VkSubmitInfo fs{VK_STRUCTURE_TYPE_SUBMIT_INFO};
                fs.pNext = &ts;
                fs.waitSemaphoreCount = 1;
                fs.pWaitSemaphores = &waitSem;
                fs.pWaitDstStageMask = &stage;
                fs.commandBufferCount = 1;
                fs.pCommandBuffers = &cb;
                fs.signalSemaphoreCount = 1;
                fs.pSignalSemaphores = &sigSem;
                VkResult submittedResult = vkQueueSubmit(mcQueue, 1, &fs, VK_NULL_HANDLE);
                CHECK(submittedResult == VK_SUCCESS);
                if (submittedResult != VK_SUCCESS) break;
                if (atlas_vk_wait_copy(ctx, sp.signalValue, 5'000'000'000ull) != ATLAS_OK) {
                    std::printf("  FAIL: copy wait frame %d\n", i); failures++; break;
                }
                submitted++;
            }
            vkQueueWaitIdle(mcQueue);
            std::printf("  receipt S3: independent context, %d submitted splices, output age 0, info %s\n", submitted, atlas_info(ctx));
            CHECK(submitted == FRAMES);
            sceneReceipt(ctx, "S3", false);
            s3WallMs = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count() / (FRAMES - WARM);
            s3Ms = inCall / (FRAMES - WARM);
            // read the targets back
            size_t px = size_t(W) * H;
            VkBufferCreateInfo bc{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
            bc.size = px * (lit ? 20 : 12);
            bc.usage = VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            vkCreateBuffer(dev, &bc, nullptr, &rb);
            VkMemoryRequirements r;
            vkGetBufferMemoryRequirements(dev, rb, &r);
            VkMemoryAllocateInfo ma{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
            ma.allocationSize = r.size;
            ma.memoryTypeIndex = memType(phys, r.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            vkAllocateMemory(dev, &ma, nullptr, &rbMem);
            vkBindBufferMemory(dev, rb, rbMem, 0);
            vkBeginCommandBuffer(cmd[1], &bi);
            VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
            mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_MEMORY_WRITE_BIT;
            mb.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
            vkCmdPipelineBarrier(cmd[1], VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &mb, 0,
                                 nullptr, 0, nullptr);
            VkBufferImageCopy reg{};
            reg.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
            reg.imageExtent = {W, H, 1};
            vkCmdCopyImageToBuffer(cmd[1], color.image, VK_IMAGE_LAYOUT_GENERAL, rb, 1, &reg);
            reg.bufferOffset = px * 8;
            vkCmdCopyImageToBuffer(cmd[1], depth.image, VK_IMAGE_LAYOUT_GENERAL, rb, 1, &reg);
            if (lit) {
                reg.bufferOffset = px * 12;
                vkCmdCopyImageToBuffer(cmd[1], nearLight.image, VK_IMAGE_LAYOUT_GENERAL, rb, 1, &reg);
            }
            mb.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            mb.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
            vkCmdPipelineBarrier(cmd[1], VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &mb, 0, nullptr, 0,
                                 nullptr);
            vkEndCommandBuffer(cmd[1]);
            si.pCommandBuffers = &cmd[1];
            vkQueueSubmit(mcQueue, 1, &si, VK_NULL_HANDLE);
            vkQueueWaitIdle(mcQueue);
            void* map = nullptr;
            vkMapMemory(dev, rbMem, 0, VK_WHOLE_SIZE, 0, &map);
            Frame got;
            got.color.assign(static_cast<const uint16_t*>(map), static_cast<const uint16_t*>(map) + px * 4);
            got.depth.assign(reinterpret_cast<const float*>(static_cast<const char*>(map) + px * 8),
                             reinterpret_cast<const float*>(static_cast<const char*>(map) + px * 8) + px);
            if (lit) got.nearLight.assign(reinterpret_cast<const uint16_t*>(static_cast<const char*>(map) + px * 12),
                                          reinterpret_cast<const uint16_t*>(static_cast<const char*>(map) + px * 12) + px * 4);
            contentReceipt("S3", got.depth, nonzeroHalfLight(got.nearLight.empty() ? nullptr : got.nearLight.data(), px), lit, s.f, true);
            size_t badC, badD;
            compareHost(refC, refD, got, W, H, badC, badD);
            if (lit) compareNearLight("S3", refLight, got, W, H);
            std::printf("  %d frames: %.3f ms CPU per atlas_vk_render_shared (no wait), %.2f ms wall per frame with the "
                        "splices; vs the host render: %zu colour / %zu depth pixels differ of %zu\n",
                        FRAMES, s3Ms, s3WallMs, badC, badD, px);
            CHECK(badC == 0 && badD == 0);
        } else {
            std::printf("  FAIL: S3 scene could not be loaded\n");
            failures++;
        }
        AtlasVkRetirement* retired = nullptr;
        CHECK(atlas_vk_detach_retired(ctx, &retired) == ATLAS_OK);
        atlas_destroy(ctx);  // (never Minecraft's device: the test destroys it below)
        if (retired) CHECK(atlas_vk_release_retired(retired) == ATLAS_OK);
    }
cleanup:
    vkDeviceWaitIdle(dev);
    if (rb) vkDestroyBuffer(dev, rb, nullptr);
    if (rbMem) vkFreeMemory(dev, rbMem, nullptr);
    for (Img* im : {&color, &depth, &nearLight, &sun}) {
        if (im->image) vkDestroyImage(dev, im->image, nullptr);
        if (im->mem) vkFreeMemory(dev, im->mem, nullptr);
    }
    if (pool) vkDestroyCommandPool(dev, pool, nullptr);
    if (generatorShutdown) std::printf("TRACE gen-shutdown external-device-destroy-begin\n");
    vkDestroyDevice(dev, nullptr);
    if (generatorShutdown) std::printf("TRACE gen-shutdown external-device-destroy-end\n");
    if (messenger) {
        auto destroy = reinterpret_cast<PFN_vkDestroyDebugUtilsMessengerEXT>(gipa(inst, "vkDestroyDebugUtilsMessengerEXT"));
        if (destroy) destroy(inst, messenger, nullptr);
    }
    vkDestroyInstance(inst, nullptr);
    if (validate) {
        std::printf("  validation (the test's device, atlas on it): %d errors\n", validationErrors);
        CHECK(validationErrors == 0);
    }
    return true;
}

// A wedged queue must not wedge the test runner. The parent may terminate only this test-owned child.
static bool abandonedChild(const char* executable, const std::string& data, const char* childFlag) {
    constexpr int timeoutSeconds = 180;
    std::printf("TRACE begin abandoned splice child (timeout %ds)\n", timeoutSeconds);
    std::fflush(stdout);
#if defined(_WIN32)
    std::string command = std::string("\"") + executable + "\" \"" + data + "\" " + childFlag;
    STARTUPINFOA si{}; si.cb = sizeof(si);
    si.dwFlags = STARTF_USESTDHANDLES;
    si.hStdInput = GetStdHandle(STD_INPUT_HANDLE);
    si.hStdOutput = GetStdHandle(STD_OUTPUT_HANDLE);
    si.hStdError = GetStdHandle(STD_ERROR_HANDLE);
    PROCESS_INFORMATION pi{};
    if (!CreateProcessA(nullptr, command.data(), nullptr, nullptr, TRUE, CREATE_NO_WINDOW, nullptr, nullptr, &si, &pi)) {
        std::printf("  FAIL: cannot start abandoned-splice child (%lu)\n", GetLastError());
        return false;
    }
    DWORD result = WaitForSingleObject(pi.hProcess, timeoutSeconds * 1000);
    if (result != WAIT_OBJECT_0) {
        TerminateProcess(pi.hProcess, 124);
        WaitForSingleObject(pi.hProcess, 5000);
        std::printf("  FAIL: abandoned-splice child timed out or wait failed (%lu)\n", result);
        CloseHandle(pi.hThread); CloseHandle(pi.hProcess);
        return false;
    }
    DWORD code = 1;
    GetExitCodeProcess(pi.hProcess, &code);
    CloseHandle(pi.hThread); CloseHandle(pi.hProcess);
    std::printf("TRACE done abandoned splice child exit %lu\n", code);
    return code == 0;
#else
    pid_t pid = fork();
    if (pid == 0) { execl(executable, executable, data.c_str(), childFlag, static_cast<char*>(nullptr)); _exit(127); }
    if (pid < 0) { std::printf("  FAIL: cannot fork abandoned-splice child\n"); return false; }
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(timeoutSeconds);
    int status = 0;
    while (std::chrono::steady_clock::now() < deadline) {
        pid_t r = waitpid(pid, &status, WNOHANG);
        if (r == pid) { std::printf("TRACE done abandoned splice child status %d\n", status); return WIFEXITED(status) && WEXITSTATUS(status) == 0; }
        if (r < 0) return false;
        std::this_thread::sleep_for(std::chrono::seconds(1));
    }
    kill(pid, SIGKILL); waitpid(pid, &status, 0);
    std::printf("  FAIL: abandoned-splice child timed out\n");
    return false;
#endif
}

int main(int argc, char** argv) {
    std::string data = argc > 1 ? argv[1] : "native/data";
    const uint32_t W = 320, H = 180;
    std::vector<float> refC, refD, refLight;
    // Fix temporal and transport knobs for both independent contexts.
#if defined(_WIN32)
    _putenv_s("ATLAS_FAR_AMORT", "0");
    _putenv_s("ATLAS_NEAR_FULLRES", "1");
    _putenv_s("ATLAS_FAR_LEAN", "0");
    _putenv_s("ATLAS_FAR_SPLIT_KERNEL", "0");
    _putenv_s("ATLAS_AB", "");
    _putenv_s("ATLAS_SHADER_DIR", "");
    _putenv_s("ATLAS_ABLATE", "");
    _putenv_s("ATLAS_TIMING_DEVICE_OUTPUTS", "");
#else
    setenv("ATLAS_FAR_AMORT", "0", 1);
    setenv("ATLAS_NEAR_FULLRES", "1", 1);
    setenv("ATLAS_FAR_LEAN", "0", 1);
    setenv("ATLAS_FAR_SPLIT_KERNEL", "0", 1);
    unsetenv("ATLAS_AB");
    unsetenv("ATLAS_SHADER_DIR");
    unsetenv("ATLAS_ABLATE");
    unsetenv("ATLAS_TIMING_DEVICE_OUTPUTS");
#endif
    std::printf("  receipt settings: amort=0 nearFull=1 lean=0 splitKernel=0; lit=%s\n",
                std::getenv("ATLAS_VK_TEST_LIT") ? std::getenv("ATLAS_VK_TEST_LIT") : "0");
    if (argc > 2 && (std::strcmp(argv[2], "--gen-shutdown-child") == 0 ||
                     std::strcmp(argv[2], "--gen-shutdown-inputs") == 0)) {
        std::setvbuf(stdout, nullptr, _IONBF, 0);
        std::setvbuf(stderr, nullptr, _IONBF, 0);
        if (!generatorShutdownInputs(data)) return 2;
        if (std::strcmp(argv[2], "--gen-shutdown-inputs") == 0) {
            std::printf("TRACE gen-shutdown CPU input-preflight-complete before-Vulkan (no device created)\n");
            return 0;
        }
        const char* validation = std::getenv("ATLAS_VK_VALIDATION");
        const char* scenario = std::getenv("ATLAS_VK_TEST_GEN_CASE");
        if (!validation || std::strcmp(validation, "1") || !scenario) return 2;
        bool owned = std::strncmp(scenario, "owned-", 6) == 0;
        bool borrowed = std::strncmp(scenario, "borrowed-", 9) == 0;
        std::string component = scenario + (owned ? 6 : borrowed ? 9 : std::strlen(scenario));
        if ((!owned && !borrowed) || (component != "near" && component != "far" && component != "both")) return 2;
        generatorShutdownNear = component != "far";
        generatorShutdownFar = component != "near";
        if (const char* close = std::getenv("ATLAS_VK_TEST_TERMINAL_CLOSE")) {
            std::string mode(close);
            terminalClosePreAttach = mode.rfind("pre-", 0) == 0;
            if (mode == "producer" || mode == "pre-producer") terminalCloseFault = ATLAS_FAULT_CLOSE_PRODUCER;
            else if (mode == "consumer" || mode == "pre-consumer") terminalCloseFault = ATLAS_FAULT_CLOSE_CONSUMER;
            else if (mode == "pre-context-lock") terminalCloseLock = 1;
            else if (mode == "pre-scene-lock") terminalCloseLock = 2;
            else if (mode == "pre-queue-lock") terminalCloseLock = 3;
            else if (mode == "pre-admitted-work") terminalCloseLock = 4;
            else if (mode == "pre-update") terminalCloseUpdate = true;
            else return 2;
            if (!borrowed) return 2;
        }
        std::printf("TRACE gen-shutdown case=%s input-preflight-complete before-Vulkan\n", scenario);
        if (owned) return generatorShutdownOwned(16, 16) && !failures ? 0 : 1;
        std::vector<float> empty;
        bool ran = stageThree(data, 16, 16, empty, empty, empty, 4);
        return ran && !failures ? 0 : 1;
    }
    if (argc > 2 && std::strcmp(argv[2], "--memory-gauges") == 0)
        return memoryGauges(data, W, H) ? 0 : 1;
    if (argc > 2 && (std::strcmp(argv[2], "--abandon-child") == 0 || std::strcmp(argv[2], "--cancel-child") == 0 ||
                     std::strcmp(argv[2], "--repeat-child") == 0)) {
        std::printf("TRACE abandon: child started\n"); std::fflush(stdout);
        std::vector<float> empty;
        int scenario = std::strcmp(argv[2], "--repeat-child") == 0 ? 3 : std::strcmp(argv[2], "--cancel-child") == 0 ? 2 : 1;
        bool ran = stageThree(data, W, H, empty, empty, empty, scenario);
        return ran && failures == 0 ? 0 : 1;
    }
    if (argc > 2 && std::strcmp(argv[2], "--abandon-parent") == 0)
        return abandonedChild(argv[0], data, "--abandon-child") ? 0 : 1;
    if (argc > 2 && std::strcmp(argv[2], "--cancel-parent") == 0)
        return abandonedChild(argv[0], data, "--cancel-child") ? 0 : 1;
    if (argc > 2 && std::strcmp(argv[2], "--repeat-parent") == 0)
        return abandonedChild(argv[0], data, "--repeat-child") ? 0 : 1;
    if (!memoryGauges(data, W, H)) return 1;
    if (!referenceFrame(data, W, H, refC, refD, refLight)) return 1;
    auto t0 = std::chrono::steady_clock::now();
    std::printf("TRACE begin vkshared\n");
    if (!refC.empty() && !stageThree(data, W, H, refC, refD, refLight)) failures++;
    std::printf("TRACE done vkshared %.1fs\n", std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count());
    std::printf(failures ? "FAILED (%d)\n" : "PASS\n", failures);
    return failures ? 1 : 0;
}
