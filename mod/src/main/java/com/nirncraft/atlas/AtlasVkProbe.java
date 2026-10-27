package com.nirncraft.atlas;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK12.*;

/**
 * Throwaway probe (docs/ATLAS-VULKAN-BACKEND.md, "First probe"): on Minecraft's Vulkan backend, reach its VkDevice, log
 * the handles, queue families and features, then splice our own command buffer into its frame at AfterLevel (a magenta
 * clear of the main target) and signal our own timeline semaphore after it; a background thread waits on the timeline
 * and logs that the values advance. On with {@code -Dnirncraft.atlas.vkProbe=true}; does nothing on OpenGL.
 */
final class AtlasVkProbe {
    private static final org.slf4j.Logger LOG = com.nirncraft.NirnCraft.LOGGER;
    private static final int RING = 4;
    private static boolean logged, failed;
    private static VkDevice dev;
    private static long pool, timeline;
    private static final VkCommandBuffer[] cbs = new VkCommandBuffer[RING];
    private static final long[] cbValue = new long[RING];
    private static long frame;
    private static volatile long submitted;

    private AtlasVkProbe() {}

    static void init() {
        if (!Boolean.getBoolean("nirncraft.atlas.vkProbe")) return;
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent.AfterLevel e) -> {
            if (failed) return;
            try {
                afterLevel();
            } catch (Throwable t) {
                failed = true;
                LOG.error("[atlas-vk-probe] failed", t);
            }
        });
    }

    private static VulkanDevice vulkanDevice() throws ReflectiveOperationException {
        Object front = RenderSystem.getDevice();
        var f = front.getClass().getDeclaredField("backend");  // FrontendGpuDevice.backend (no public accessor)
        f.setAccessible(true);
        Object b = f.get(front);
        return b instanceof VulkanDevice v ? v : null;
    }

    private static void afterLevel() throws ReflectiveOperationException {
        var enc = RenderSystem.getDevice().createCommandEncoder();
        if (!logged) {
            logged = true;
            VulkanDevice vd = vulkanDevice();
            if (vd == null) {
                LOG.info("[atlas-vk-probe] not on Vulkan (device backend {}): off", RenderSystem.getDevice().getClass().getName());
                failed = true;
                return;
            }
            dev = vd.vkDevice();
            VkPhysicalDevice phys = dev.getPhysicalDevice();
            LOG.info("[atlas-vk-probe] VkInstance 0x{} VkPhysicalDevice 0x{} VkDevice 0x{} api {} encoder {} inRenderPass {}",
                    Long.toHexString(vd.instance().vkInstance().address()), Long.toHexString(phys.address()),
                    Long.toHexString(dev.address()), dev.getCapabilities().apiVersion,
                    enc.getClass().getSimpleName(), enc instanceof FrontendCommandEncoder fe && fe.isInRenderPass());
            LOG.info("[atlas-vk-probe] queues: graphics family {} (0x{}), compute family {} (0x{}), transfer family {} (0x{})",
                    vd.graphicsQueue().queueFamilyIndex(), Long.toHexString(vd.graphicsQueue().vkQueue().address()),
                    vd.computeQueue().queueFamilyIndex(), Long.toHexString(vd.computeQueue().vkQueue().address()),
                    vd.transferQueue().queueFamilyIndex(), Long.toHexString(vd.transferQueue().vkQueue().address()));
            try (MemoryStack st = MemoryStack.stackPush()) {
                var v12 = VkPhysicalDeviceVulkan12Features.calloc(st).sType$Default();
                var f2 = VkPhysicalDeviceFeatures2.calloc(st).sType$Default().pNext(v12.address());
                vkGetPhysicalDeviceFeatures2(phys, f2);
                var ip = st.callocInt(1);
                vkGetPhysicalDeviceQueueFamilyProperties(phys, ip, null);
                var qf = VkQueueFamilyProperties.calloc(ip.get(0), st);
                vkGetPhysicalDeviceQueueFamilyProperties(phys, ip, qf);
                StringBuilder fams = new StringBuilder();
                for (int i = 0; i < qf.capacity(); i++)
                    fams.append(i).append(":flags=0x").append(Integer.toHexString(qf.get(i).queueFlags()))
                            .append(",count=").append(qf.get(i).queueCount()).append(' ');
                LOG.info("[atlas-vk-probe] supported: shaderInt64 {} shaderFloat64 {} bufferDeviceAddress {} descriptorIndexing {} "
                                + "runtimeDescriptorArray {} timelineSemaphore {}; families {}",
                        f2.features().shaderInt64(), f2.features().shaderFloat64(), v12.bufferDeviceAddress(),
                        v12.descriptorIndexing(), v12.runtimeDescriptorArray(), v12.timelineSemaphore(), fams);
            }
            try {
                var ef = VulkanDevice.class.getDeclaredField("enabledFeatures");
                ef.setAccessible(true);
                LOG.info("[atlas-vk-probe] Minecraft enabled: {}", ef.get(vd));
            } catch (ReflectiveOperationException ignored) {
            }
            create(vd.graphicsQueue().queueFamilyIndex());
        }
        if (dev == null) return;
        if (enc instanceof FrontendCommandEncoder fe && fe.isInRenderPass()) {
            LOG.warn("[atlas-vk-probe] AfterLevel is inside a render pass: move the hook");
            failed = true;
            return;
        }
        var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        if (!(main.getColorTexture() instanceof VulkanGpuTexture tex)) return;
        int slot = (int) (frame % RING);
        try (MemoryStack st = MemoryStack.stackPush()) {
            if (cbValue[slot] != 0) {  // the slot's last use finished before we reset it
                var wi = VkSemaphoreWaitInfo.calloc(st).sType$Default().semaphoreCount(1)
                        .pSemaphores(st.longs(timeline)).pValues(st.longs(cbValue[slot]));
                int r = vkWaitSemaphores(dev, wi, 2_000_000_000L);
                if (r != VK_SUCCESS) {
                    LOG.error("[atlas-vk-probe] waiting for slot {} (value {}): {}", slot, cbValue[slot], r);
                    failed = true;
                    return;
                }
            }
            VkCommandBuffer cb = cbs[slot];
            vkResetCommandBuffer(cb, 0);
            vkBeginCommandBuffer(cb, VkCommandBufferBeginInfo.calloc(st).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
            var range = VkImageSubresourceRange.calloc(st).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            var before = VkMemoryBarrier.calloc(1, st).sType$Default()
                    .srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT);
            vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, before, null, null);
            var magenta = VkClearColorValue.calloc(st);
            magenta.float32(0, 1f).float32(1, 0f).float32(2, 1f).float32(3, 1f);
            vkCmdClearColorImage(cb, tex.vkImage(), VK_IMAGE_LAYOUT_GENERAL, magenta, range);
            var after = VkMemoryBarrier.calloc(1, st).sType$Default()
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
            vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, after, null, null);
            vkEndCommandBuffer(cb);
            VulkanCommandEncoder ve = (VulkanCommandEncoder) ((FrontendCommandEncoder) enc).backend();
            ve.execute(cb);
            long value = ++frame;
            ve.signalSemaphore(timeline, value, VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
            cbValue[slot] = value;
            submitted = value;
        }
    }

    private static void create(int family) {
        try (MemoryStack st = MemoryStack.stackPush()) {
            LongBuffer lp = st.mallocLong(1);
            check(vkCreateCommandPool(dev, VkCommandPoolCreateInfo.calloc(st).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(family), null, lp), "pool");
            pool = lp.get(0);
            PointerBuffer pb = st.mallocPointer(RING);
            check(vkAllocateCommandBuffers(dev, VkCommandBufferAllocateInfo.calloc(st).sType$Default().commandPool(pool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(RING), pb), "command buffers");
            for (int i = 0; i < RING; i++) cbs[i] = new VkCommandBuffer(pb.get(i), dev);
            var type = VkSemaphoreTypeCreateInfo.calloc(st).sType$Default().semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE).initialValue(0);
            check(vkCreateSemaphore(dev, VkSemaphoreCreateInfo.calloc(st).sType$Default().pNext(type.address()), null, lp), "timeline");
            timeline = lp.get(0);
        }
        Thread t = new Thread(AtlasVkProbe::watch, "atlas-vk-probe timeline");
        t.setDaemon(true);
        t.start();
        LOG.info("[atlas-vk-probe] our pool 0x{} timeline 0x{} on family {}", Long.toHexString(pool), Long.toHexString(timeline), family);
    }

    /** Waits on the timeline in the background: each value Minecraft's submission signals after our command buffer. */
    private static void watch() {
        long seen = 0, lastLog = System.nanoTime(), waits = 0, timeouts = 0;
        while (true) {
            try (MemoryStack st = MemoryStack.stackPush()) {
                var wi = VkSemaphoreWaitInfo.calloc(st).sType$Default().semaphoreCount(1)
                        .pSemaphores(st.longs(timeline)).pValues(st.longs(seen + 1));
                int r = vkWaitSemaphores(dev, wi, 1_000_000_000L);
                if (r == VK_SUCCESS) {
                    var vp = st.mallocLong(1);
                    vkGetSemaphoreCounterValue(dev, timeline, vp);
                    seen = vp.get(0);
                    waits++;
                } else if (r == VK_TIMEOUT) timeouts++;
                else {
                    LOG.error("[atlas-vk-probe] vkWaitSemaphores: {}", r);
                    return;
                }
            }
            if (System.nanoTime() - lastLog > 5_000_000_000L) {
                lastLog = System.nanoTime();
                LOG.info("[atlas-vk-probe] timeline value {} (recorded {}), {} waits done, {} timeouts", seen, submitted, waits, timeouts);
            }
        }
    }

    private static void check(int r, String what) {
        if (r != VK_SUCCESS) throw new IllegalStateException("[atlas-vk-probe] " + what + ": VkResult " + r);
    }
}
