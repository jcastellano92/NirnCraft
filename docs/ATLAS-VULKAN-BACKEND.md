# ATLAS on Minecraft's Vulkan backend (spike, 2026-10-26; Gate G11 completed 2026-10-27)

**Decision (owner, 2026-10-26; end-state cleanup completed 2026-10-27, Gate G11):** ATLAS runs exclusively on Minecraft
26.3's own Vulkan backend (renderpearl) via the single device-creation hook (`com.nirncraft.mixin.VkDeviceCreate`) and
shared-device `VK_SHARED` splice transport (`atlas_create_on_vulkan` + `atlas_vk_render_shared`). Following live Prism
verification across Gates G5–G10, the device-share off switch (`-Dnirncraft.atlas.vkShareDevice=false`,
`-Dnirncraft.atlas.vkSharedSync=false`), the own-device `VK_HOST` fallback (`atlas_vk_render`), and the OpenGL interop
bridge (`atlas_gl_*`, `src/gl_api.*`, `src/gl_near.hpp`) have been removed. macOS remains outside the current migration
gate; future Apple planning is recorded below.

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
- **Mixin (narrow exception, approved by the orchestrator 2026-10-26; single device-creation hook as of Gate G11).**
  `com.nirncraft.mixin.VkDeviceCreate`, a `@WrapOperation` on the single `VK12.vkCreateDevice` call in
  `VulkanBackend.createDevice(FeatureSet, VulkanPhysicalDevice)`.
  - It appends our extensions and features only when the device supports them: `shaderInt64`, `shaderInt16`,
    `shaderFloat16`, optional `shaderFloat64`, `VK_EXT_memory_budget` and `VK_EXT_calibrated_timestamps`.
  - It adds ATLAS's frame queue (compute-only family when available, else graphics family) and generator queue.
  - It's re-checked on every Minecraft update (`require = 0`: if Minecraft's device creation moves or the physical
    device lacks required features, Minecraft starts unmodified and ATLAS stays off for the session).
- **Removed legacy paths (Gate G11):** The temporary `-Dnirncraft.atlas.vkShareDevice=false` /
  `-Dnirncraft.atlas.vkSharedSync=false` switches, the own-device `VK_HOST` host-copy fallback (`atlas_vk_render`), and
  the OpenGL interop bridge (`atlas_gl_attach`, `atlas_gl_render`, `atlas_gl_render_io`, `atlas_gl_detach`) were
  dropped after the final Prism verification runs.

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

## Capability checkpoint and next experiments (owner discussion, 2026-10-27)

Audited candidate `21d3f61c` has rendered a world on Minecraft's shared Vulkan device, but is not yet accepted
into main: terminal cleanup, memory and controlled baseline parity remain live gates. An available capability
or a queue reservation is not a measured performance gain.

| Capability | Implemented state |
| --- | --- |
| Slang | Native terrain, lighting, clouds and world-generation compute kernels already compile Slang to SPIR-V. Minecraft-facing graphics passes and the legacy pack still contain GLSL; Vulkan does not require rewriting all GLSL. |
| Temporal reconstruction | Custom sparse near-light reconstruction/history and a separate TAA resolve exist. No DLSS/FSR/XeSS integration or accepted full-frame temporal upscaler. |
| GPU world data | Generator-to-grid GPU transfer and GPU-resident tracing/lighting exist. Planning, cache/disk, gameplay data and synchronization still involve the CPU; there is no claim that every pass uses one identical buffer without readback. |
| Explicit queues/synchronization | Shared-device frame and generator queues, command pools, barriers, timeline handoffs and serialized fallback exist. Actual 13:36 run reserved frame family2/index1 and generator family2/index2. |
| Async frame delivery | Not complete: the shared copy path still waits on the CPU. S4's image ring and measured graphics/compute overlap remain work. Separate queues do not prove concurrent hardware execution. |
| Parallel command recording | Render and generator workers exist, alongside background pipeline work; not a general multithreaded per-frame command recorder. CPU threads record command buffers; GPU execution parallelism is a separate question. |
| Resource layouts | Native buffers, storage images and descriptor sets are independent of shader-pack conventions. Bindless descriptors and buffer-device-address are not enabled merely by sharing the device. |
| Particles | Minecraft simulation/drawing with Atlas-aware shading. Compute simulation and GPU-driven particle dispatch remain planned. |
| Hardware ray tracing | No acceleration-structure/ray-query/ray-tracing-pipeline implementation. Present rays traverse voxel data in ordinary compute shaders. |
| Shadows | One orthographic near shadow map, a separate progressively refreshed far sun map and traced terrain shadow rays. Not a cascaded-shadow-map implementation. |
| Datapack compute | World-generation data/features are data driven; kernels are repository code compiled and embedded at build time. No general datapack-supplied compute-kernel API. |
| Apple | No supported package/test path; packaged native targets are Windows/Linux x86-64. Future Apple work needs an arm64 native build, capability/numeric-contract audit and real-device tests. |

Keep a Vulkan1.2-compatible baseline and negotiate optional features at the existing device-creation hook,
preserving Minecraft's requested features and queues. Query support, enable the required extension/feature chain
before device creation, load the matching entry points and retain a tested fallback. A Vulkan1.4 SDK or physical
device does not by itself enable new operations on Minecraft's already-created logical device. Promoted extension
paths can offer newer functionality without making Vulkan1.4 mandatory; Minecraft already uses dynamic-rendering
and synchronization2 extensions. See the [Khronos version guide](https://docs.vulkan.org/guide/latest/versions.html)
and [feature requirements](https://docs.vulkan.org/spec/latest/chapters/features.html).

Possible experiments after migration parity include measured S4 overlap without CPU frame waits, reducing the
dominant trace/kernel cost and improving GPU data reuse, motion/disocclusion-tested temporal reconstruction,
and a bounded GPU particle stage. These proposals do not change the stage order or authorize new feature work.
Queue overlap must improve the complete frame/loading result, not just one queue's timer: async compute
shares execution, register and bandwidth resources and can regress a saturated workload. Treat the suggested10%
as a hypothesis to measure, never a promised gain; see [NVIDIA's overlap guidance](https://developer.nvidia.com/blog/advanced-api-performance-async-compute-and-overlap/).
Optional ray queries/reflections or improved shadow-map allocation need independent quality/cost experiments;
they do not replace the common voxel world or become migration prerequisites. A future datapack compute API should
describe bounded, versioned stages with explicit inputs/outputs, deterministic fallback and cache fingerprints.

For future Apple support, evaluate both available Vulkan-on-Metal implementations rather than fixing the plan
to MoltenVK. Khronos's [current MoltenVK notice](https://github.com/KhronosGroup/MoltenVK) recommends KosmicKrisp
for Apple Silicon and retains MoltenVK for Intel Macs/iOS. [Mesa's KosmicKrisp documentation](https://docs.mesa3d.org/drivers/kosmickrisp.html)
requires Apple Silicon, Metal4 and macOS26+. Translation-layer availability is not evidence that our shaders,
numeric requirements, queue design or Minecraft integration work there. This is future planning, not Apple support.
