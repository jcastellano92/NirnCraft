package com.nirncraft.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The one mixin of the narrow exception for Minecraft's Vulkan backend (docs/AGENT-RULES.md, approved 2026-10-26;
 * docs/ATLAS-VULKAN-BACKEND.md): wraps the single {@code vkCreateDevice} call in {@code VulkanBackend.createDevice} so
 * Minecraft's device also carries what Infinite ATLAS needs to run on it (shaderInt64, optional float64, its queues,
 * the memory budget). All of it lives in {@link com.nirncraft.atlas.AtlasVkShare#createDevice}; switch:
 * {@code -Dnirncraft.atlas.vkShareDevice=false} (then the call is Minecraft's own and ATLAS keeps its own device).
 * Re-checked on every Minecraft update: {@code require = 0}, so a moved call leaves Minecraft as it is and ATLAS on its
 * own device (AtlasVkShare.shared() stays null).
 */
@Mixin(VulkanBackend.class)
public abstract class VkDeviceCreate {
    // (the full descriptor: a bare "createDevice" also names the public createDevice(GpuDebugOptions), which holds no
    // vkCreateDevice call, and with require = 0 the wrap then silently applied nowhere)
    @WrapOperation(method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
            require = 0, at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/vulkan/VK12;vkCreateDevice(Lorg/lwjgl/vulkan/VkPhysicalDevice;Lorg/lwjgl/vulkan/VkDeviceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
    private static int nirncraft$shareDevice(VkPhysicalDevice phys, VkDeviceCreateInfo info, VkAllocationCallbacks alloc,
                                             PointerBuffer out, Operation<Integer> original) {
        return com.nirncraft.atlas.AtlasVkShare.createDevice(phys, info, ci -> original.call(phys, ci, alloc, out));
    }
}
