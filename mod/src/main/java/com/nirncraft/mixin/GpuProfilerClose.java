package com.nirncraft.mixin;

import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import com.mojang.renderpearl.frontend.TracyGpuProfiler;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Queues the optional profiler's query pool before the backend drains its destruction queue. */
@Mixin(FrontendGpuDevice.class)
public abstract class GpuProfilerClose {
    @Shadow @Final private TracyGpuProfiler profiler;
    @Unique private boolean nirncraft$profilerClosed;

    @Inject(method = "close()V", at = @At("HEAD"), require = 1)
    private void nirncraft$closeProfiler(CallbackInfo ci) {
        if (nirncraft$profilerClosed) return;
        if (profiler != null) profiler.close();
        nirncraft$profilerClosed = true;
    }
}
