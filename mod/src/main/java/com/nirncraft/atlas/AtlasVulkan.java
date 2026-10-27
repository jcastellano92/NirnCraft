package com.nirncraft.atlas;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import org.lwjgl.vulkan.VkCommandBuffer;

/**
 * Infinite ATLAS on Minecraft's Vulkan backend (docs/ATLAS-VULKAN-BACKEND.md). Which way the frame reaches Minecraft:
 * <ul>
 * <li>{@link Path#GL}: Minecraft runs OpenGL; the GL interop bridge (atlas_gl_*), as before;</li>
 * <li>{@link Path#VK_HOST} (stage S1, the public fallback): Minecraft runs Vulkan, atlas keeps its own Vulkan device and
 * hands each frame over in host memory, uploaded with the encoder's writeToTexture (atlas_vk_render);</li>
 * <li>{@link Path#VK_SHARED} (stage S3): atlas runs on Minecraft's own VkDevice (the device-sharing mixin gave it its
 * features and queues: {@link AtlasVkShare}), traces on its own queue and splices one command buffer into Minecraft's
 * frame that copies the result into Minecraft's textures, after a timeline-semaphore wait (atlas_vk_render_shared).
 * The opt-in synchronous baseline submits that copy and waits with a deadline before resolving it.</li>
 * </ul>
 * The path is chosen once per context: {@link #choose} (a shared context that cannot be made falls back to VK_HOST).
 */
final class AtlasVulkan {
    enum Path { GL, VK_HOST, VK_SHARED }

    private static Boolean vulkan;
    private static Path path;
    private static VulkanDevice device;
    private enum CopyPhase { OFFERED, CANCELING, RECORDING, SUBMITTED, COMPLETE, FAILED }
    // Render-thread owned. A failed/ambiguous insertion is retained until device teardown is prevented.
    private static final java.util.Map<Object, CopyPhase> copies = new java.util.IdentityHashMap<>();
    private static final java.util.Map<AtlasNative.Context, java.util.concurrent.CompletableFuture<Void>> copyWaits = new java.util.IdentityHashMap<>();

    private AtlasVulkan() {}

    /** Minecraft runs its Vulkan backend (once: it never changes while the game runs). Render thread. */
    static boolean vulkan() {
        Boolean v = vulkan;
        if (v == null) vulkan = v = "Vulkan".equalsIgnoreCase(RenderSystem.getDevice().getDeviceInfo().backendName());
        return v;
    }

    /** The path of the current context (GL until a Vulkan context chose). */
    static Path path() {
        Path p = path;
        return p != null ? p : vulkan() ? Path.VK_HOST : Path.GL;
    }

    /** The context just made runs on this path. */
    static void choose(Path p) {
        path = p;
    }

    /** Minecraft's Vulkan device, by the public route (a compiled pipeline's backend); null on OpenGL. */
    static VulkanDevice device() {
        VulkanDevice d = device;
        if (d != null || !vulkan()) return d;
        var compiled = RenderSystem.getCompiledPipeline(net.minecraft.client.renderer.RenderPipelines.GUI);
        if (compiled instanceof FrontendRenderPipeline f && f.backendRenderPipeline() instanceof VulkanRenderPipeline v)
            device = d = v.device();
        return d;
    }

    /** Encoder work is allowed now (Minecraft's Vulkan encoder refuses copies and splices inside a render pass). */
    static boolean outsidePass(CommandEncoder enc) {
        return !(enc instanceof FrontendCommandEncoder f) || !f.isInRenderPass();
    }

    /** (VK_SHARED) a splice can be recorded now: Minecraft's Vulkan encoder, outside a render pass. */
    static boolean canSplice() {
        var enc = RenderSystem.getDevice().createCommandEncoder();
        return outsidePass(enc) && enc instanceof FrontendCommandEncoder f && f.backend() instanceof VulkanCommandEncoder
                && device() != null;
    }

    /** VK_PIPELINE_STAGE_2_ALL_TRANSFER_BIT, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT */
    private static final long STAGE_TRANSFER = 0x1000L, STAGE_ALL = 0x10000L;

    /**
     * (VK_SHARED) atlas's splice into Minecraft's frame: wait for atlas's frame, run its copies into Minecraft's textures,
     * then signal that atlas may reuse its images. False when the encoder cannot take it here (inside a render pass).
     */
    static boolean splice(AtlasNative.Context ctx, AtlasNative.VkSplice s) {
        return splice(ctx, new CopyOps() {
            VulkanCommandEncoder encoder;
            VulkanDevice device;
            public boolean prepare() {
                var enc = RenderSystem.getDevice().createCommandEncoder();
                if (!outsidePass(enc) || !(enc instanceof FrontendCommandEncoder f)
                        || !(f.backend() instanceof VulkanCommandEncoder v)) return false;
                encoder = v;
                device = device();
                return device != null;
            }
            public void cancel() { ctx.vkCancelSplice(s.signalValue()); }
            public void record() {
                if (s.waitSemaphore() != 0) encoder.waitSemaphore(s.waitSemaphore(), s.waitValue(), STAGE_TRANSFER);
                encoder.execute(new VkCommandBuffer(s.commandBuffer(), device.vkDevice()));
                encoder.signalSemaphore(s.signalSemaphore(), s.signalValue(), STAGE_ALL);
            }
            public void submit() { encoder.submit(); }
            public boolean waitCopy() { return ctx.vkWaitCopy(s.signalValue(), COPY_TIMEOUT_NS); }
        });
    }

    interface CopyOps {
        boolean prepare();
        void cancel();
        void record();
        void submit();
        boolean waitCopy();
    }

    /** The actual offer/record/submit state machine, with injected operations for failure tests. */
    static boolean splice(Object owner, CopyOps ops) {
        CopyPhase prior = copies.get(owner);
        if (prior != null && prior != CopyPhase.COMPLETE)
            throw new IllegalStateException("atlas: refusing a splice while the previous copy is " + prior);
        copies.put(owner, CopyPhase.OFFERED);
        try {
        if (!ops.prepare()) {
            copies.put(owner, CopyPhase.CANCELING);
            ops.cancel();
            copies.remove(owner);
            return false;
        }
        copies.put(owner, CopyPhase.RECORDING);
        ops.record();
        // Synchronous S3 baseline: public backend submission keeps the frontend profiler's frame intact.
        // Once insertion starts, failure cleanup retires resources; it must never cancel or host-signal this splice.
        ops.submit();
        copies.put(owner, CopyPhase.SUBMITTED);
        } catch (Throwable failure) {
            if (copies.get(owner) == CopyPhase.OFFERED) {
                try { ops.cancel(); copies.remove(owner); }
                catch (Throwable cancel) { copies.put(owner, CopyPhase.FAILED); failure.addSuppressed(cancel); }
            } else {
                copies.put(owner, CopyPhase.FAILED);
            }
            throw failure;
        }
        boolean completed;
        try { completed = ops.waitCopy(); }
        catch (Throwable failure) { copies.put(owner, CopyPhase.FAILED); throw failure; }
        if (!completed)
            throw new AtlasNative.AtlasException(AtlasNative.ERR_TIMEOUT, "atlas: shared Vulkan copy did not complete in 5 s");
        copies.put(owner, CopyPhase.COMPLETE);
        return true;
    }

    /** Nonblocking stop preflight. Ambiguous insertion is never re-submitted or cancelled. */
    static boolean copyRetired(AtlasNative.Context ctx, Path ownerPath) {
        if (ownerPath != Path.VK_SHARED) return true;
        return copyRetired(ctx, () -> ctx.vkCopyComplete(0));
    }

    /** Render thread: validate the recorded state before an owning release worker waits on the native timeline. */
    static void requirePollable(AtlasNative.Context ctx, Path ownerPath) {
        if (ownerPath != Path.VK_SHARED) return;
        requirePollable((Object) ctx);
    }

    static void requirePollable(Object owner) {
        CopyPhase phase = copies.get(owner);
        if (phase == CopyPhase.FAILED || phase == CopyPhase.RECORDING || phase == CopyPhase.OFFERED || phase == CopyPhase.CANCELING)
            throw new IllegalStateException("atlas: cannot retire an unconfirmed Vulkan copy (" + phase + ")");
    }

    static void copyConfirmed(Object owner) {
        copies.remove(owner);
        if (owner instanceof AtlasNative.Context ctx) copyWaits.remove(ctx);
    }

    /** Called on the owning render thread; the wait itself runs on the context release worker. */
    static java.util.concurrent.CompletableFuture<Void> waitCopyAsync(AtlasNative.Context ctx, Path ownerPath) {
        requirePollable(ctx, ownerPath);
        return copyWaits.computeIfAbsent(ctx,
                ignored -> AtlasReleaseGate.waitCopyAsync(() -> ctx.vkWaitCopy(0, 1_000_000_000L)));
    }

    static boolean copyRetired(Object owner, java.util.function.BooleanSupplier completed) {
        CopyPhase phase = copies.get(owner);
        if (phase == CopyPhase.FAILED || phase == CopyPhase.RECORDING || phase == CopyPhase.OFFERED || phase == CopyPhase.CANCELING)
            throw new IllegalStateException("atlas: cannot retire an unconfirmed Vulkan copy (" + phase + ")");
        boolean ready;
        try { ready = completed.getAsBoolean(); }
        catch (Throwable failure) { copies.put(owner, CopyPhase.FAILED); throw failure; }
        if (ready) { copies.remove(owner); return true; }
        if (phase != CopyPhase.SUBMITTED)
            throw new IllegalStateException("atlas: cannot retire an unconfirmed Vulkan copy (" + phase + ")");
        return false;
    }

    private static final long COPY_TIMEOUT_NS = 5_000_000_000L;

    /** Complete a previously recorded copy before native history, images or caller targets may change. */
    static boolean finishPending(AtlasNative.Context ctx) {
        if (path() != Path.VK_SHARED) return true;
        return finishPending(ctx, () -> waitCopyAsync(ctx, Path.VK_SHARED));
    }

    /** Never submit again: incomplete submitted work keeps this frame from mutating the previous targets. */
    static boolean finishPending(Object owner, java.util.function.Supplier<java.util.concurrent.CompletableFuture<Void>> wait) {
        requirePollable(owner);
        CopyPhase phase = copies.get(owner);
        if (phase == null || phase == CopyPhase.COMPLETE) { copyConfirmed(owner); return true; }
        if (phase != CopyPhase.SUBMITTED) throw new IllegalStateException("atlas: unknown copy phase " + phase);
        java.util.concurrent.CompletableFuture<Void> completion;
        try { completion = wait.get(); }
        catch (RuntimeException | Error failure) { copies.put(owner, CopyPhase.FAILED); throw failure; }
        if (!completion.isDone()) return false;
        try { completion.join(); }
        catch (RuntimeException failure) { copies.put(owner, CopyPhase.FAILED); throw failure; }
        copyConfirmed(owner);
        return true;
    }

    /** Native work has drained; Minecraft releases the detached copy resources with its own completed submissions. */
    static void retire(AtlasNative.Context ctx) {
        enqueueRetired(detachRetired(ctx));
    }

    /** Call only after all users of this context have exited and its last external copy has completed. */
    static AtlasNative.RetiredVulkan detachRetired(AtlasNative.Context ctx) {
        return ctx.vkDetachRetired();
    }

    /** The caller retains token ownership if finding the encoder or enqueueing it fails. */
    static void enqueueRetired(AtlasNative.RetiredVulkan token) {
        if (token == null) return;
        var enc = RenderSystem.getDevice().createCommandEncoder();
        if (!(enc instanceof FrontendCommandEncoder f) || !(f.backend() instanceof VulkanCommandEncoder v))
            throw new IllegalStateException("atlas: Vulkan retirement has no Minecraft encoder; native token retained");
        v.queueForDestroy(token::close);
    }
}
