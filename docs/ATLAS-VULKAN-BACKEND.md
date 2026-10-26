# ATLAS on Minecraft's Vulkan backend (spike, 2026-10-26)

**Decision (owner, 2026-10-26):** ATLAS moves onto Minecraft 26.3's own Vulkan backend (renderpearl). GL interop stays
only as a fallback, and macOS is out of scope for now.

This plan comes from a read-only spike that read the client jar's bytecode with `javap -p -c`. **[V]** means seen in
bytecode; **[I]** means inferred.

## What Minecraft's Vulkan backend exposes

- **Handles are public.** `VulkanDevice.vkDevice()`, `instance().vkInstance()`, `graphicsQueue()` / `computeQueue()`
  (`vkQueue()`, `queueFamilyIndex()`), `VulkanGpuTexture.vkImage()` and `VulkanGpuBuffer.vkBuffer()` all return
  LWJGL handles. **[V]**
- **Getting to VulkanDevice.** `FrontendRenderPipeline.backendRenderPipeline()` → cast to `VulkanRenderPipeline` →
  `.device()`. All public. **[V]**
- **Command splicing.** `FrontendCommandEncoder.backend()` gives the `VulkanCommandEncoder`, which has `waitSemaphore`,
  `execute(VkCommandBuffer)` and `signalSemaphore`. All throw inside a render pass. **[V]**
- **Synchronisation.** Minecraft syncs with a timeline semaphore, and its images stay in `GENERAL` layout. It submits only
  on `graphicsQueue`; its compute and transfer queues are never submitted to. **[V]**
- **Requirements.** Vulkan 1.2, plus `dynamic_rendering`, `push_descriptor`, `synchronization2`,
  `vertex_attribute_divisor`, `swapchain`, `timelineSemaphore`, `hostQueryReset` and `shaderDrawParameters`. **[V]**
- **Missing for us.** The device has no `shaderInt64` (our tracer needs it), no external memory, no buffer device
  address and no descriptor indexing, and textures never get STORAGE usage. Features can't be added from outside,
  because `VulkanFeatureSets.*FeatureSets()` builds a new set on each call, so this needs a mixin. **[V]**
- **Usable now.** `PreferredGraphicsApi` is DEFAULT, OPENGL or VULKAN; DEFAULT tries GL first. Minecraft falls back
  between backends, and a crashed start forces GL next time. Launch arguments `--graphicsBackend` and
  `--vulkanValidation` exist. **[V]**

## Choice: run on Minecraft's own VkDevice (option b)

- **Why not the others.** Importing or exporting memory between the two devices can't work: Minecraft's device has no
  external memory, and its images are plain VMA images. **[I]**
- **What sharing the device gives.** The same device and queues, our own timeline semaphores, and zero-copy on every
  vendor, including AMD, Mesa and the Steam Deck.
- **Mixin (narrow exception, approved by the orchestrator 2026-10-26).** `com.nirncraft.mixin.VkDeviceCreate`, a
  `@WrapOperation` on the single `VK12.vkCreateDevice` call in
  `VulkanBackend.createDevice(FeatureSet, VulkanPhysicalDevice)`.
  - It appends our extensions and features only when the device supports them: `shaderInt64`, optional float64,
    `memory_budget` and `global_priority`.
  - It adds one queue on the compute family.
  - The switch is `atlas.vkShareDevice`.
  - It's re-checked on every Minecraft update.
- **Public fallback** (mixin off or unsupported): our own VkDevice plus a host copy through `encoder.writeToTexture`.
  No GL is needed.
- **GL stays** frozen to bug fixes for as long as Mojang ships it. We review removing it two Minecraft releases after
  DEFAULT picks Vulkan first.

## First probe (≤ 1 hour, Java and LWJGL, dev instance)

1. Set `preferredGraphicsBackend:"vulkan"` and launch with `--vulkanValidation`.
2. Add a throwaway `RenderLevelStageEvent.AfterLevel` listener that reaches VulkanDevice (reflection is fine for the
   probe) and logs:
   - the device, physical device and instance handles;
   - the queue families;
   - support for `shaderInt64`, buffer device address and descriptor indexing.
3. Record our own command pool and a magenta `vkCmdClearColorImage` on the main target's `vkImage()`. Splice it in with
   `enc.execute(cb)`, then `enc.signalSemaphore(ourTimeline, frame, ALL_COMMANDS)`.
4. **Pass:** the world shows magenta with the hand and GUI drawn on top, validation is clean, and a background
   `vkWaitSemaphores` sees the values advance.
5. If AfterLevel turns out to be inside a render pass, move the hook to another event.

## Build stages (each checked on NVIDIA, AMD and Steam Deck RADV)

- **S1, public fallback.** In Vulkan mode, keep our own device and host-copy into a Minecraft `GpuTexture` (a
  `VulkanGpuTexture` branch beside `GlTexture` in AtlasTargets.java:205). Check: Vulkan mode renders correctly and GL mode
  is unchanged.
- **S2, the mixin.** Check that our features are enabled and that turning the switch off lands back on S1.
- **S3, shared device.**
  - A JNI `attachVulkan(...)` makes vk_backend.cpp adopt Minecraft's device, skipping `vkCreateDevice` and the interop
    exports.
  - We trace into our own storage image, then `vkCmdCopyImage` into a Minecraft-owned target, spliced in with
    waitSemaphore + execute.
  - Check: pixel-identical to S1, lower frame time, validation clean.
- **S4, async compute.** Trace on our added compute queue into a ring of 2–3 images, with Minecraft waiting on frame
  N−1. Check that the recorder shows overlap and no stalls.

## Risks

- **Device loss.** A device lost on our side now crashes Minecraft, which then forces GL on the next start.
- **Internal API.** `backend.vulkan` is internal, so the mixin and casts are re-checked on every update.
- **Graphics-queue submits** go only through Minecraft's encoder, on the render thread.
- **Not checked:** NeoForge's early loading window under Vulkan.

## What it unlocks, ranked by win

1. Zero-copy on every vendor and OS, which retires the host-copy path on AMD, Mesa and the Steam Deck.
2. Async compute overlapping Minecraft's frame, with timeline-semaphore handoff.
3. Sampling Minecraft's own textures (block atlas, lightmap) by `vkImage`, dropping our texture-pack re-upload.
4. Buffer device address and bindless descriptors, through the mixin.
5. A low-priority generator queue, using the added queue and `global_priority`.
6. One GPU timeline shared with Minecraft's Tracy, using `calibrated_timestamps`.
