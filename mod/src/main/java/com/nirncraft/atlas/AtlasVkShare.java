package com.nirncraft.atlas;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;

import static org.lwjgl.vulkan.VK12.*;

/**
 * Minecraft's Vulkan device made fit for Infinite ATLAS (stage S2 of docs/ATLAS-VULKAN-BACKEND.md), called from the one
 * approved mixin ({@code com.nirncraft.mixin.VkDeviceCreate}) around {@code VulkanBackend.createDevice}'s
 * {@code vkCreateDevice}. Only what the device supports is added, and only on top of what Minecraft asks for:
 * <ul>
 * <li>features: shaderInt64 (the tracer's baseline), shaderFloat64 when present (the client generator's exact doubles);</li>
 * <li>queues: ATLAS's frames' queue in a compute-only family when there is one (the async engine; its images are
 * shared by both families), else one more in Minecraft's graphics family, and the generator's beside it;</li>
 * <li>extensions: VK_EXT_memory_budget (atlas_memory_info) and VK_EXT_calibrated_timestamps (the flight recorder), when
 * offered and not already on.</li>
 * </ul>
 * A device that refuses the additions is created again exactly as Minecraft asked (ATLAS then runs on its own device,
 * stage S1). Switch: {@code -Dnirncraft.atlas.vkShareDevice=false}. Runs once, on the render thread, before any mod
 * event; it must never throw into Minecraft's start.
 */
public final class AtlasVkShare {
    /** What the shared device carries for ATLAS (null: the mixin did not run, was switched off, or the device refused). */
    public record Shared(long instance, long physicalDevice, long device, int frameFamily, int frameIndex, int genFamily,
                         int genIndex, boolean float64, boolean memoryBudget, boolean calibrated) {}

    private static volatile Shared shared;
    private static volatile String why = "the device-sharing mixin did not run (OpenGL, or Minecraft's device creation moved)";
    private static Shared pending;
    private static Runnable undo;  // (Minecraft's own feature struct back as it was, after the call)

    private AtlasVkShare() {}

    public static Shared shared() {
        return shared;
    }

    /** Shared transport is the default; explicit false keeps the host-copy control available. */
    static boolean sharedSyncEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty("nirncraft.atlas.vkSharedSync"));
    }

    /** Why there is no shared device (for the log), or "" when there is one. */
    public static String why() {
        return shared != null ? "" : why;
    }

    /** -Dnirncraft.atlas.vkShareDevice (default on); read before the mod's harness flags are applied, so the file too. */
    static boolean enabled() {
        String v = System.getProperty("nirncraft.atlas.vkShareDevice");
        if (v == null) {
            try {
                var f = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("nirncraft-harness-flags.properties");
                if (java.nio.file.Files.isRegularFile(f)) {
                    var p = new java.util.Properties();
                    try (var in = java.nio.file.Files.newBufferedReader(f)) {
                        p.load(in);
                    }
                    v = p.getProperty("nirncraft.atlas.vkShareDevice");
                }
            } catch (Throwable ignored) {
            }
        }
        return v == null || !v.equalsIgnoreCase("false");
    }

    /** The mixin's body: Minecraft's own call when off or unfit, else the call with ATLAS's additions. */
    public static int createDevice(VkPhysicalDevice phys, VkDeviceCreateInfo info, Function<VkDeviceCreateInfo, Integer> create) {
        if (!enabled()) {
            why = "switched off (-Dnirncraft.atlas.vkShareDevice=false)";
            return create.apply(info);
        }
        VkDeviceCreateInfo ours;
        try (MemoryStack st = MemoryStack.stackPush()) {
            try {
                ours = extend(phys, info, st);
            } catch (Throwable t) {
                if (undo != null) undo.run();
                undo = null;
                why = "extending Minecraft's device failed: " + t;
                com.nirncraft.NirnCraft.LOGGER.warn("atlas: Vulkan device not shared: {}", why);
                return create.apply(info);
            }
            if (ours == null) {
                com.nirncraft.NirnCraft.LOGGER.info("atlas: Vulkan device not shared: {}", why);
                return create.apply(info);
            }
            int r = create.apply(ours);
            if (undo != null) undo.run();
            undo = null;
            if (r == VK_SUCCESS) {
                shared = pending;
                com.nirncraft.NirnCraft.LOGGER.info("atlas: Minecraft's Vulkan device carries ATLAS's features and queues: {}", shared);
                return r;
            }
            why = "the device refused ATLAS's additions (VkResult " + r + ")";
            com.nirncraft.NirnCraft.LOGGER.warn("atlas: {}; created as Minecraft asked", why);
            return create.apply(info);
        }
    }

    /** The patched copy of info (null + why when the device cannot take ATLAS). The device handle is filled later. */
    private static VkDeviceCreateInfo extend(VkPhysicalDevice phys, VkDeviceCreateInfo info, MemoryStack st) {
        VkPhysicalDeviceFeatures have = VkPhysicalDeviceFeatures.calloc(st);
        vkGetPhysicalDeviceFeatures(phys, have);
        if (!have.shaderInt64()) {
            why = "the device has no shaderInt64";
            return null;
        }
        if (!have.shaderInt16()) {
            why = "the device has no shaderInt16";
            return null;
        }
        if (info.pEnabledFeatures() == null) {
            why = "Minecraft's device names its core features in the pNext chain (unexpected: re-check the mixin)";
            return null;
        }
        IntBuffer ip = st.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(phys, ip, null);
        VkQueueFamilyProperties.Buffer qf = VkQueueFamilyProperties.calloc(ip.get(0), st);
        vkGetPhysicalDeviceQueueFamilyProperties(phys, ip, qf);
        int families = qf.capacity();
        int[] count = new int[families];
        VkDeviceQueueCreateInfo.Buffer mq = info.pQueueCreateInfos();
        int mcEntries = mq == null ? 0 : mq.remaining();
        for (int i = 0; i < mcEntries; i++) {
            var q = mq.get(mq.position() + i);
            count[q.queueFamilyIndex()] = q.queueCount();
        }
        // the frames' queue: in a compute-only family where there is one with room (the async engine: AMD has a single
        // graphics queue, NVIDIA runs it beside the graphics work), else a second queue of a graphics family Minecraft
        // takes its queues from; the generator's next to it
        int[] want = count.clone();
        int compute = -1, graphics = -1;
        for (int f = 0; f < families; f++) {
            int fl = qf.get(f).queueFlags();
            if (compute < 0 && (fl & VK_QUEUE_COMPUTE_BIT) != 0 && (fl & VK_QUEUE_GRAPHICS_BIT) == 0
                    && want[f] < qf.get(f).queueCount()) compute = f;
            if (graphics < 0 && (fl & VK_QUEUE_GRAPHICS_BIT) != 0 && (fl & VK_QUEUE_COMPUTE_BIT) != 0 && count[f] > 0) graphics = f;
        }
        int frame = compute >= 0 ? compute : graphics >= 0 && want[graphics] < qf.get(graphics).queueCount() ? graphics : -1;
        if (frame < 0) {
            why = "no room for another compute queue on the device";
            return null;
        }
        int frameIndex = want[frame]++;
        int gen = -1;
        for (int f : new int[] {compute, graphics})
            if (gen < 0 && f >= 0 && want[f] < qf.get(f).queueCount()) gen = f;
        int genIndex = gen >= 0 ? want[gen]++ : -1;
        // the queue create infos: Minecraft's families with their counts raised, then any family it did not ask for
        int entries = 0;
        for (int f = 0; f < families; f++) if (want[f] > 0) entries++;
        VkDeviceQueueCreateInfo.Buffer qs = VkDeviceQueueCreateInfo.calloc(entries, st);
        int e = 0;
        for (int f = 0; f < families; f++) {
            if (want[f] == 0) continue;
            FloatBuffer prio = st.callocFloat(want[f]);  // all 0, as Minecraft's own (equal priority within a family)
            long next = 0;
            for (int i = 0; i < mcEntries; i++) {
                var q = mq.get(mq.position() + i);
                if (q.queueFamilyIndex() == f) {
                    next = q.pNext();
                    for (int k = 0; k < q.queueCount(); k++) prio.put(k, q.pQueuePriorities().get(k));
                }
            }
            qs.get(e++).sType$Default().pNext(next).queueFamilyIndex(f).pQueuePriorities(prio);
        }
        // extensions: Minecraft's, then ours where offered
        Set<String> names = new LinkedHashSet<>();
        PointerBuffer mx = info.ppEnabledExtensionNames();
        if (mx != null) for (int i = mx.position(); i < mx.limit(); i++) names.add(MemoryUtil.memUTF8(mx.get(i)));
        Set<String> offered = new java.util.HashSet<>();
        vkEnumerateDeviceExtensionProperties(phys, (java.nio.ByteBuffer) null, ip, null);
        // (on the heap: ~260 bytes an extension, a few hundred of them overflow the 64 KB stack Minecraft's frame shares;
        // 2026-10-26 live: "Out of stack space", the device then created as Minecraft asked)
        VkExtensionProperties.Buffer ext = VkExtensionProperties.calloc(ip.get(0));
        try {
            vkEnumerateDeviceExtensionProperties(phys, (java.nio.ByteBuffer) null, ip, ext);
            for (int i = 0; i < ext.capacity(); i++) offered.add(ext.get(i).extensionNameString());
        } finally {
            ext.free();
        }
        boolean budget = offered.contains("VK_EXT_memory_budget");
        if (budget) names.add("VK_EXT_memory_budget");
        if (offered.contains("VK_EXT_calibrated_timestamps") && !names.contains("VK_KHR_calibrated_timestamps"))
            names.add("VK_EXT_calibrated_timestamps");
        boolean calibrated = names.contains("VK_EXT_calibrated_timestamps");  // (the name the core loads)
        PointerBuffer px = st.mallocPointer(names.size());
        for (String n : names) px.put(st.UTF8(n));
        px.flip();
        // core features: Minecraft's plus ours
        VkPhysicalDeviceFeatures feats = VkPhysicalDeviceFeatures.calloc(st);
        MemoryUtil.memCopy(info.pEnabledFeatures().address(), feats.address(), VkPhysicalDeviceFeatures.SIZEOF);
        feats.shaderInt64(true);
        if (have.shaderFloat64()) feats.shaderFloat64(true);
        if (have.shaderInt16()) feats.shaderInt16(true);  // (some kernels declare Int16 / Float16)
        VkDeviceCreateInfo ours = VkDeviceCreateInfo.calloc(st).set(info);
        ours.pQueueCreateInfos(qs).ppEnabledExtensionNames(px).pEnabledFeatures(feats);
        // shaderFloat16: in Minecraft's own Vulkan 1.2 feature struct when its chain has one (set for this call only,
        // undone after: a refused device is created again as Minecraft asked), else a struct of ours at the chain's head
        var hf = org.lwjgl.vulkan.VkPhysicalDeviceShaderFloat16Int8Features.calloc(st).sType$Default();
        vkGetPhysicalDeviceFeatures2(phys, org.lwjgl.vulkan.VkPhysicalDeviceFeatures2.calloc(st).sType$Default().pNext(hf.address()));
        if (!hf.shaderFloat16()) {
            why = "the device has no shaderFloat16";
            return null;
        }
        if (hf.shaderFloat16()) {
            long v12 = 0;
            for (long p = ours.pNext(); p != 0; p = MemoryUtil.memGetAddress(p + 8))
                if (MemoryUtil.memGetInt(p) == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES) v12 = p;
            if (v12 != 0) {
                var s12 = org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features.create(v12);
                boolean was = s12.shaderFloat16();
                s12.shaderFloat16(true);
                undo = () -> s12.shaderFloat16(was);
            } else {
                var f16 = org.lwjgl.vulkan.VkPhysicalDeviceShaderFloat16Int8Features.calloc(st).sType$Default()
                        .shaderFloat16(true).pNext(ours.pNext());
                ours.pNext(f16.address());
            }
        }
        pending = new Shared(0, phys.address(), 0, frame, frameIndex, gen, genIndex, have.shaderFloat64(), budget, calibrated);
        return ours;
    }
}
