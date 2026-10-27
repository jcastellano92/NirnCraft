package com.nirncraft.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandPool;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read only on the render thread by the existing opt-in memory profile. */
@Mixin(VulkanCommandEncoder.class)
public interface VkEncoderMemory {
    @Accessor("commandPools") VulkanCommandPool[] nirncraft$commandPools();
    @Accessor("currentSubmitIndex") long nirncraft$submitIndex();
}
