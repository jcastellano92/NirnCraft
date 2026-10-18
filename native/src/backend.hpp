// Kernel backends: the same aether_far kernel on the CPU (Slang -> C++) or on Vulkan (Slang -> SPIR-V).
#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "gpu_layout.hpp"
#include "world.hpp"

namespace aether {

struct FrameInputs {
    const PackedScene* scene;
    FrameGpu frame;
    const std::vector<MaterialGpu>* palette;
    const std::vector<uint32_t>* weather;
    const std::vector<uint32_t>* cloudNoise;  // 64^3 RGBA8
    const float* nearDepth = nullptr;         // host path: w*h near-field view distances (null: none)
    bool wantNearDepth = false;               // caller asked for the near-depth skip (GL path: shared image)
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
    virtual bool render(const FrameInputs& in, FrameTargets& out, double* gpuMs, std::string& err) = 0;

    // Zero-copy game path (render thread, the game's GL context current). Default: unsupported.
    virtual bool glAttach(uint32_t, uint32_t, GlTargets&, std::string& err) {
        err = "this backend cannot share images with OpenGL (use the Vulkan backend)";
        return false;
    }
    // Renders straight into the shared images. GL commands issued after it returns see the frame (a GL-side
    // semaphore wait is queued); the CPU does not block on the GPU. gpuMs reports the previous frame.
    virtual bool glRender(const FrameInputs&, double*, std::string& err) {
        err = "not attached";
        return false;
    }
    virtual void glDetach() {}
};

std::unique_ptr<Backend> makeCpuBackend();
std::unique_ptr<Backend> makeVulkanBackend(std::string& err);  // null + err when no usable device

}  // namespace aether
