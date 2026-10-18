// Kernel backends: the same aether_far kernel on the CPU (Slang -> C++) or on Vulkan (Slang -> SPIR-V).
#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "gpu_layout.hpp"
#include "world.hpp"

namespace aether {

struct GlFrameIO {
    uint32_t nearDepthTex = 0, depthZeroToOne = 0, reversedZ = 0;
    float nearInvProjection[16] = {};
    uint32_t dstColor = 0, dstNormal = 0, dstDepth = 0;
    uint32_t dstSunMap = 0;  // caller texture (RG32F, size x size + 1) the sun map is copied into, or 0
};

struct FrameInputs {
    std::shared_ptr<const PackedScene> sceneHold;  // keeps the snapshot alive for the frame
    const PackedScene* scene;
    FrameGpu frame;
    const std::vector<MaterialGpu>* palette;
    const std::vector<uint32_t>* weather;
    const std::vector<uint32_t>* cloudNoise;  // 64^3 RGBA8
    const float* nearDepth = nullptr;         // host path: w*h near-field view distances (null: none)
    bool wantNearDepth = false;               // caller asked for the near-depth skip (GL path: shared image)
    std::vector<ActorGpu> actors;             // at least one element (count in frame.weatherParams[3])
};

struct FrameTargets {
    std::vector<float> color;  // linear RGBA, 4 floats per pixel
    std::vector<uint32_t> normal, counters;
    std::vector<float> depth;
};

// GL texture names of the images shared with the caller's OpenGL context (bottom-up rows, GL convention).
struct GlTargets {
    uint32_t color = 0, normal = 0, depth = 0, nearDepth = 0;
    uint32_t width = 0, height = 0;
};

class Backend {
public:
    virtual ~Backend() = default;
    virtual const char* name() const = 0;
    // One line of JSON for logs: device, driver, interop mode and the features it was chosen from.
    virtual std::string info() const { return std::string("{\"backend\":\"") + name() + "\"}"; }
    virtual bool render(const FrameInputs& in, FrameTargets& out, double* gpuMs, std::string& err) = 0;
    // Called from the update thread with a new scene revision: a backend may copy it to the GPU here, off the
    // render thread. Returns false when it could not (the frame then uploads it itself).
    virtual bool stageScene(const PackedScene&) { return false; }

    // Game path (render thread, the game's GL context current): zero-copy through shared memory and semaphores
    // when the driver pair supports it, else frames copied through host memory. Default: unsupported.
    virtual bool glAttach(uint32_t, uint32_t, GlTargets&, std::string& err) {
        err = "this backend cannot share images with OpenGL (use the Vulkan backend)";
        return false;
    }
    // Renders straight into the shared images. GL commands issued after it returns see the frame (a GL-side
    // semaphore wait is queued); the CPU does not block on the GPU. gpuMs reports the previous frame.
    virtual bool glRender(const FrameInputs&, const GlFrameIO*, double*, std::string& err) {
        err = "not attached";
        return false;
    }
    virtual void glDetach() {}
    // The sun map as last computed (size * (size + 1) float2, empty when none). Waits for the GPU.
    virtual bool readSunMap(std::vector<float>& out, std::string& err) {
        err = "no sun map";
        out.clear();
        return false;
    }
};

std::unique_ptr<Backend> makeCpuBackend();
std::unique_ptr<Backend> makeVulkanBackend(std::string& err);  // null + err when no usable device

}  // namespace aether
