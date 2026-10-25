// The Vulkan loader, opened at run time (vulkan-1.dll / libvulkan.so.1) instead of linked: the library then has no
// link-time Vulkan dependency, so one libatlas_core.so built on an old sysroot loads on every distro, and a machine
// without a loader gets an atlas_create error (the CPU backend still works) instead of a library that will not load.
// Every entry point the backend calls is a pointer in namespace atlas with the usual vk* name.
#pragma once
#ifndef VK_NO_PROTOTYPES
#define VK_NO_PROTOTYPES
#endif
#include <vulkan/vulkan.h>

#include <string>

#define ATLAS_VK_GLOBAL_FNS(X) X(vkCreateInstance) X(vkEnumerateInstanceExtensionProperties)

#define ATLAS_VK_INSTANCE_FNS(X)                                                                                      \
    X(vkDestroyInstance) X(vkEnumeratePhysicalDevices) X(vkGetPhysicalDeviceFeatures) X(vkGetPhysicalDeviceFeatures2) X(vkGetPhysicalDeviceProperties)                                \
    X(vkGetPhysicalDeviceProperties2) X(vkGetPhysicalDeviceQueueFamilyProperties)                                    \
    X(vkGetPhysicalDeviceMemoryProperties) X(vkGetPhysicalDeviceMemoryProperties2) X(vkGetPhysicalDeviceImageFormatProperties2)                        \
    X(vkGetPhysicalDeviceExternalSemaphoreProperties) X(vkEnumerateDeviceExtensionProperties) X(vkCreateDevice)      \
    X(vkGetDeviceProcAddr) X(vkDestroyDevice) X(vkGetDeviceQueue) X(vkDeviceWaitIdle) X(vkQueueSubmit)               \
    X(vkAllocateMemory) X(vkFreeMemory) X(vkMapMemory) X(vkUnmapMemory) X(vkCreateBuffer) X(vkDestroyBuffer)          \
    X(vkGetBufferMemoryRequirements) X(vkBindBufferMemory) X(vkCreateImage) X(vkDestroyImage)                         \
    X(vkGetImageMemoryRequirements) X(vkGetImageMemoryRequirements2) X(vkBindImageMemory) X(vkCreateImageView)       \
    X(vkDestroyImageView) X(vkCreateSampler) X(vkDestroySampler) X(vkCreateShaderModule) X(vkDestroyShaderModule)    \
    X(vkCreateDescriptorSetLayout) X(vkDestroyDescriptorSetLayout) X(vkCreatePipelineLayout)                         \
    X(vkDestroyPipelineLayout) X(vkCreateComputePipelines) X(vkDestroyPipeline) X(vkCreateDescriptorPool)            \
    X(vkCreatePipelineCache) X(vkDestroyPipelineCache) X(vkGetPipelineCacheData)                                     \
    X(vkDestroyDescriptorPool) X(vkAllocateDescriptorSets) X(vkUpdateDescriptorSets) X(vkCreateCommandPool)          \
    X(vkDestroyCommandPool) X(vkAllocateCommandBuffers) X(vkResetCommandBuffer) X(vkBeginCommandBuffer)              \
    X(vkEndCommandBuffer) X(vkCreateFence) X(vkDestroyFence) X(vkResetFences) X(vkWaitForFences) X(vkGetFenceStatus) \
    X(vkCreateSemaphore) X(vkDestroySemaphore) X(vkCreateQueryPool) X(vkDestroyQueryPool) X(vkGetQueryPoolResults)   \
    X(vkCmdBindPipeline) X(vkCmdBindDescriptorSets) X(vkCmdPushConstants) X(vkCmdDispatch) X(vkCmdPipelineBarrier)   \
    X(vkCmdCopyBuffer) X(vkCmdCopyBufferToImage) X(vkCmdCopyImageToBuffer) X(vkCmdFillBuffer) X(vkCmdUpdateBuffer)                      \
    X(vkCmdResetQueryPool) X(vkCmdWriteTimestamp) X(vkCmdDispatchBase) X(vkWaitSemaphores) X(vkGetSemaphoreCounterValue)

namespace atlas {

#define ATLAS_VK_DECLARE(name) extern PFN_##name name;
extern PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr;
ATLAS_VK_GLOBAL_FNS(ATLAS_VK_DECLARE)
ATLAS_VK_INSTANCE_FNS(ATLAS_VK_DECLARE)
#undef ATLAS_VK_DECLARE

// Opens the loader and resolves the global entry points (once per process). False + err when there is none.
bool vkLoadGlobal(std::string& err);
// Resolves everything else through the instance (loader trampolines: valid for every instance, any thread).
bool vkLoadInstance(VkInstance instance, std::string& err);

}  // namespace atlas
