// How long the driver takes to turn a compute SPIR-V module into a pipeline (its JIT), with no pipeline cache.
//   spv_jit_time <a.spv> [b.spv ...]     prints "TRACE done pipeline:<file> <secs>s (<bytes> bytes)" per module
// The layout is the feature kernels' (8 storage buffers, 32 bytes of push constants); NVIDIA caches by the SPIR-V's
// hash on disk, so time a module once (or after a change).
#include "vk_loader.hpp"  // first: VK_NO_PROTOTYPES

#include <chrono>
#include <cstdio>
#include <string>
#include <vector>

using namespace atlas;

int main(int argc, char** argv) {
    if (argc < 2) {
        std::fprintf(stderr, "usage: spv_jit_time <a.spv> ...\n");
        return 2;
    }
    std::string err;
    if (!vkLoadGlobal(err)) return std::fprintf(stderr, "%s\n", err.c_str()), 1;
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.apiVersion = VK_API_VERSION_1_2;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ici.pApplicationInfo = &app;
    VkInstance inst;
    if (vkCreateInstance(&ici, nullptr, &inst) != VK_SUCCESS || !vkLoadInstance(inst, err)) return std::fprintf(stderr, "instance\n"), 1;
    uint32_t n = 0;
    vkEnumeratePhysicalDevices(inst, &n, nullptr);
    std::vector<VkPhysicalDevice> devs(n);
    vkEnumeratePhysicalDevices(inst, &n, devs.data());
    VkPhysicalDevice phys = VK_NULL_HANDLE;
    for (auto d : devs) {
        VkPhysicalDeviceProperties p;
        vkGetPhysicalDeviceProperties(d, &p);
        if (p.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU || phys == VK_NULL_HANDLE) phys = d;
    }
    if (!phys) return std::fprintf(stderr, "no device\n"), 1;
    uint32_t qn = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(phys, &qn, nullptr);
    std::vector<VkQueueFamilyProperties> qf(qn);
    vkGetPhysicalDeviceQueueFamilyProperties(phys, &qn, qf.data());
    uint32_t fam = 0;
    for (uint32_t i = 0; i < qn; i++)
        if (qf[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
            fam = i;
            break;
        }
    float prio = 1.0f;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = fam;
    qci.queueCount = 1;
    qci.pQueuePriorities = &prio;
    VkPhysicalDeviceFeatures feats{};
    feats.shaderFloat64 = VK_TRUE;
    feats.shaderInt64 = VK_TRUE;
    VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dci.queueCreateInfoCount = 1;
    dci.pQueueCreateInfos = &qci;
    dci.pEnabledFeatures = &feats;
    VkDevice dev;
    if (vkCreateDevice(phys, &dci, nullptr, &dev) != VK_SUCCESS) return std::fprintf(stderr, "device\n"), 1;
    VkDescriptorSetLayoutBinding b[8];
    for (uint32_t i = 0; i < 8; i++) b[i] = {i, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    VkDescriptorSetLayoutCreateInfo dl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dl.bindingCount = 8;
    dl.pBindings = b;
    VkDescriptorSetLayout setLayout;
    vkCreateDescriptorSetLayout(dev, &dl, nullptr, &setLayout);
    VkPushConstantRange pr{VK_SHADER_STAGE_COMPUTE_BIT, 0, 32};
    VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pl.setLayoutCount = 1;
    pl.pSetLayouts = &setLayout;
    pl.pushConstantRangeCount = 1;
    pl.pPushConstantRanges = &pr;
    VkPipelineLayout layout;
    vkCreatePipelineLayout(dev, &pl, nullptr, &layout);
    int rc = 0;
    for (int a = 1; a < argc; a++) {
        FILE* f = std::fopen(argv[a], "rb");
        if (!f) {
            std::fprintf(stderr, "cannot open %s\n", argv[a]);
            rc = 1;
            continue;
        }
        std::fseek(f, 0, SEEK_END);
        std::vector<uint32_t> code(size_t(std::ftell(f)) / 4);
        std::fseek(f, 0, SEEK_SET);
        size_t got = std::fread(code.data(), 4, code.size(), f);
        std::fclose(f);
        if (got != code.size()) continue;
        auto t0 = std::chrono::steady_clock::now();
        VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        smci.codeSize = code.size() * 4;
        smci.pCode = code.data();
        VkShaderModule mod;
        if (vkCreateShaderModule(dev, &smci, nullptr, &mod) != VK_SUCCESS) {
            std::printf("TRACE fail pipeline:%s module\n", argv[a]);
            rc = 1;
            continue;
        }
        VkComputePipelineCreateInfo cp{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        cp.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
        cp.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        cp.stage.module = mod;
        cp.stage.pName = "main";
        cp.layout = layout;
        VkPipeline pipe;
        VkResult r = vkCreateComputePipelines(dev, VK_NULL_HANDLE, 1, &cp, nullptr, &pipe);
        double s = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        std::printf("TRACE done pipeline:%s %.1fs (%zu bytes)%s\n", argv[a], s, code.size() * 4, r == VK_SUCCESS ? "" : " FAILED");
        std::fflush(stdout);
        if (r == VK_SUCCESS) vkDestroyPipeline(dev, pipe, nullptr);
        vkDestroyShaderModule(dev, mod, nullptr);
    }
    vkDestroyPipelineLayout(dev, layout, nullptr);
    vkDestroyDescriptorSetLayout(dev, setLayout, nullptr);
    vkDestroyDevice(dev, nullptr);
    vkDestroyInstance(inst, nullptr);
    return rc;
}
