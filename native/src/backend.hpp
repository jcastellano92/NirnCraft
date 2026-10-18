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
};

struct FrameTargets {
    std::vector<uint32_t> color, normal, counters;
    std::vector<float> depth;
};

class Backend {
public:
    virtual ~Backend() = default;
    virtual const char* name() const = 0;
    virtual bool render(const FrameInputs& in, FrameTargets& out, double* gpuMs, std::string& err) = 0;
};

std::unique_ptr<Backend> makeCpuBackend();
std::unique_ptr<Backend> makeVulkanBackend(std::string& err);  // null + err when no usable device

}  // namespace aether
