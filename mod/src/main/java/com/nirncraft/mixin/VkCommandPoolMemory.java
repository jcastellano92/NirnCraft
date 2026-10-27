package com.nirncraft.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandPool;
import org.lwjgl.PointerBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Borrowed handle table; diagnostic readers must never change its position, limit or contents. */
@Mixin(VulkanCommandPool.class)
public interface VkCommandPoolMemory {
    @Accessor("allocatedBuffers") PointerBuffer nirncraft$allocatedBuffers();
}
