package com.nirncraft.mixin;

import com.mojang.renderpearl.backend.vulkan.VulkanInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Reuses Minecraft's own object names for opt-in allocation/lifetime diagnostics. */
@Mixin(VulkanInstance.class)
public abstract class VkDiagnosticLabels {
    @ModifyArg(method = "<init>(IZZ)V", index = 1, require = 1,
            at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;create(IZLjava/util/Set;Ljava/util/Set;)Lcom/mojang/renderpearl/backend/vulkan/VulkanDebug;"))
    private static boolean nirncraft$diagnosticLabels(boolean requested) {
        return requested || Boolean.getBoolean("nirncraft.atlas.memoryDiagnostics");
    }
}
