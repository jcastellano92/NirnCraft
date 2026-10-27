package com.nirncraft.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import org.lwjgl.vulkan.VK12;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Completes Minecraft 26.3's deferred pipeline destruction; see docs/ATLAS-VULKAN-MC-LIFETIMES.md. */
@Mixin(VulkanRenderPipeline.class)
public abstract class VkPipelineDestroy {
    @Shadow @Final private VulkanDevice device;
    @Shadow @Final private long withStencilPipeline;
    @Shadow @Final private long withDepthStencilPipeline;
    @Unique private boolean nirncraft$stencilVariantsDestroyed;

    // This runs inside the existing Destroyable callback, after Minecraft has retired its queue users.
    // Leave vanilla's depth/no-depth pipelines, layouts and shader modules with their original owner.
    @Inject(method = "destroy()V", at = @At("RETURN"), require = 1)
    private void nirncraft$destroyStencilVariants(CallbackInfo ci) {
        if (nirncraft$stencilVariantsDestroyed) return;
        if (withStencilPipeline != 0L) VK12.vkDestroyPipeline(device.vkDevice(), withStencilPipeline, null);
        if (withDepthStencilPipeline != 0L) VK12.vkDestroyPipeline(device.vkDevice(), withDepthStencilPipeline, null);
        nirncraft$stencilVariantsDestroyed = true;
    }
}
