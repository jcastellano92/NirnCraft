# Minecraft 26.3 Vulkan pipeline lifetime correction

The official NeoForge 26.3.0.90 patched Minecraft source creates four Vulkan graphics pipelines per
`VulkanRenderPipeline`: depth, no depth, depth/stencil, and stencil. Its `destroy()` frees the first two,
the pipeline layout, descriptor layout and shader modules, but omits both stencil variants.

`VkPipelineDestroy` adds only those two missing frees at the return from the existing deferred
`destroy()` callback. It uses the original object's device and handles. It neither changes submission
nor moves destruction before Minecraft's retirement queue. Null handles are ignored, and a per-object
guard prevents repeated frees of the added variants. Vanilla's existing destruction remains intact.
The mixin is client-only and requires the exact `destroy()V` injection point.

The same source has a second omission: `FrontendGpuDevice` owns an optional `TracyGpuProfiler`, but
its `close()` only closes the shader compiler and backend. `TracyGpuProfiler.close()` closes its query
pool; no other official caller closes this profiler. `GpuProfilerClose` invokes that existing public
close operation at the start of frontend shutdown, once per frontend. On Vulkan this queues the query
pool for deferred destruction before the backend drains that queue. The optional-null case remains
valid; other backends use the same public query-pool close contract.

Recheck this workaround whenever Minecraft/NeoForge changes: remove it when upstream frees these
variants. The compatibility test checks the official class resource for the precise omission; it is
not a Vulkan execution test or proof that the mixin transformed the running class.

Evidence before this patch:

- Local source artifact: `migration-26.3/build/moddev/artifacts/minecraft-patched-26.3.0.90-sources.jar`
  under the existing scratch root. Entry `com/mojang/renderpearl/backend/vulkan/VulkanRenderPipeline.java`,
  creation lines 332–345 and destruction lines 351–360.
- Actual Minecraft validation log:
  `codex-vulkan-evidence/mc-validation-full-20261027-123520/validation.log` under the scratch root.
  The device-close report lists 592 leaked pipelines, consistent with two omitted variants per 296
  compiled pipelines. The single leaked query pool is consistent with the profiler omission, but
  handle ownership has not been measured. The other 85 objects and earlier native generator
  descriptor invalidation are separate unresolved findings.

Source review and a Java build are required before testing. Acceptance additionally requires actual
Minecraft object-lifetime validation through shutdown, proving that this mixin applies and eliminates
the omitted-pipeline leaks. This source patch alone establishes neither clean shutdown nor reduced
process memory.

The actual 13:36 run (`live-20261027-133613-800642`) reached world readiness and saved a terrain capture.
The descriptor invalidation and all 592 omitted pipelines were absent, but shutdown still failed with six
live children: two buffers, one image/view pair, one sampler and one query pool. The supervisor initiated
cleanup after sampled private commit reached its existing reserve; forced cleanup is not a shutdown pass.

A second ownership audit found two additional mod omissions. `QualityClient` owned a timestamp query pool
but its stopping listener only saved the profile. `AtlasTargets` created four targets but registered only
three with TextureManager; its private near-light target therefore had no terminal owner. Both now close
inside AtlasClient's existing latched group after native users retire. The registered textures retain
TextureManager ownership. Query close failure remains latched, retains the owner and seals later GPU work.
Attribution to the six reported handles still requires same-run validation.

Java build `1027-141209` passes 402 tests with zero failures/errors and 19 opt-in skips. The two new query
cleanup tests execute, as do the actual FFM shutdown tests. Diagnostic runs using the existing
`nirncraft.atlas.memoryDiagnostics` property additionally request Minecraft's own Vulkan object names and
sample the two existing command pools' buffer counts. Counts and pointer-table bytes exclude driver pool
storage; unavailable counters remain unverified. Normal settings and validation verbosity are unchanged.
The new Jar SHA-256 is `04714b19ffdfdda99fa270ff007512be8559bf3a3727c4af30a5a536bb7ef552`.
Native source and packaged binaries are unchanged from the descriptor-corrected build.

Diagnostic validation (`codex-grid-evidence/parity-6/validation.log`) identified the final two leaked
`VkBuffer` objects at `vkDestroyDevice` (`VUID-vkDestroyDevice-device-05137`) as NeoForge `earlydisplay`
buffers handed over via `RenderPearlRenderBackend`:

- `VkBuffer[shared_error]`: `SimpleBufferBuilder.gpuBuffer` (`AbstractEarlyScreen` constructs
  `new SimpleBufferBuilder("shared_error", 8192)`, and `SimpleBufferBuilder.close()` frees only the host
  `bufferAddr` while omitting `gpuBuffer.close()`).
- `VkBuffer[ScreenSize UBO]`: `ELSRenderBackend.screenSizeUbo` (`ELSRenderBackend` allocates
  `createBuffer("ScreenSize UBO", ...)`, and `RenderPearlRenderBackend.close()` closes only `pipelines`).

`AtlasClient.closeEarlyDisplayAtShutdown()` runs inside `ShutdownResources` during `ClientStoppingEvent`,
closing both `AutoCloseable` buffers on the completed `EarlyLoadingScreenController` renderer before
`VulkanDevice.close()` drains its deferred destroy queue. `VkPipelineDestroyCompatibilityTest` guards
both upstream `close()` omissions against bytecode changes.

Cold-cache shutdown captures (`parity-3`, `parity-4`, `parity-8`) also exposed a 3+ minute hang inside
`vkDestroyDevice` after `atlas_vk_prepare_close` had already drained all native queues: NVIDIA's Windows
Vulkan driver (`nvoglv64.dll`) queues background compute shader specialization onto a `BelowNormal` worker
thread (`0x180F15BB0`) during `vkCmdDispatch` when command buffers are recorded without
`VK_COMMAND_BUFFER_USAGE_SIMULTANEOUS_USE_BIT` (`0x180F1D7B0` / `0x180F43430`), and zeroes the initial
compile time for compute pipelines (`0x180F65EA0`), allowing multi-minute Slang kernel recompiles in
`nvgpucomp64.dll` to hold the device specialization `SRWLock` shared while `vkDestroyDevice` blocks on
`AcquireSRWLockExclusive` prior to signaling the worker thread to exit. Native command buffers now record
with `VK_COMMAND_BUFFER_USAGE_SIMULTANEOUS_USE_BIT`, and background pipeline compilation threads
(`farSplitThread_`, `reloadThread_`) join via `Backend::finishBackgroundWork()` in `atlas_vk_prepare_close`
without holding caller admission tickets. Warm (`codex-grid-evidence/parity-9-warm`) and cold
(`codex-grid-evidence/parity-10-cold`) validation captures both complete `vkDestroyDevice` in 8.0 s with
zero thread dumps and zero Khronos validation errors or warnings.
