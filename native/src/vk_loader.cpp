#include "vk_loader.hpp"

#include <mutex>

#if defined(_WIN32)
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#else
#include <dlfcn.h>
#endif

namespace aether {

PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr = nullptr;
#define AETHER_VK_DEFINE(name) PFN_##name name = nullptr;
AETHER_VK_GLOBAL_FNS(AETHER_VK_DEFINE)
AETHER_VK_INSTANCE_FNS(AETHER_VK_DEFINE)
#undef AETHER_VK_DEFINE

namespace {
std::mutex loadMu;
bool instanceLoaded = false;
}  // namespace

bool vkLoadGlobal(std::string& err) {
    std::lock_guard<std::mutex> lock(loadMu);
    if (vkGetInstanceProcAddr) return true;
#if defined(_WIN32)
    HMODULE lib = LoadLibraryA("vulkan-1.dll");
    if (!lib) {
        err = "no Vulkan loader (vulkan-1.dll)";
        return false;
    }
    auto gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(reinterpret_cast<void*>(GetProcAddress(lib, "vkGetInstanceProcAddr")));
#else
    void* lib = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
    if (!lib) lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        err = "no Vulkan loader (libvulkan.so.1)";
        return false;
    }
    auto gipa = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib, "vkGetInstanceProcAddr"));
#endif
    if (!gipa) {
        err = "the Vulkan loader lacks vkGetInstanceProcAddr";
        return false;
    }
#define AETHER_VK_GLOBAL(name) name = reinterpret_cast<PFN_##name>(gipa(VK_NULL_HANDLE, #name));
    AETHER_VK_GLOBAL_FNS(AETHER_VK_GLOBAL)
#undef AETHER_VK_GLOBAL
    if (!vkCreateInstance || !vkEnumerateInstanceExtensionProperties) {
        err = "the Vulkan loader lacks vkCreateInstance";
        return false;
    }
    vkGetInstanceProcAddr = gipa;  // last: it is the "loaded" flag
    return true;
}

bool vkLoadInstance(VkInstance instance, std::string& err) {
    std::lock_guard<std::mutex> lock(loadMu);
    if (instanceLoaded) return true;
    std::string missing;
#define AETHER_VK_INSTANCE(name)                                                    \
    name = reinterpret_cast<PFN_##name>(vkGetInstanceProcAddr(instance, #name));    \
    if (!name) missing += std::string(missing.empty() ? "" : ", ") + #name;
    AETHER_VK_INSTANCE_FNS(AETHER_VK_INSTANCE)
#undef AETHER_VK_INSTANCE
    if (!missing.empty()) {
        err = "the Vulkan loader lacks " + missing + " (needs Vulkan 1.2)";
        return false;
    }
    instanceLoaded = true;
    return true;
}

}  // namespace aether
