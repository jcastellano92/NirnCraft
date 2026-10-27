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
