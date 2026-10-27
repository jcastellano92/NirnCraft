package com.nirncraft.atlas;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.nirncraft.mixin.VkCommandPoolMemory;
import com.nirncraft.mixin.VkEncoderMemory;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaStatistics;
import org.lwjgl.util.vma.VmaTotalStatistics;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Opt-in allocation ownership for the existing low-frequency profile sample. Minecraft VMA counters describe GPU
 * allocator blocks and live suballocations, not process-private bytes. They exclude ATLAS's raw Vulkan allocations.
 * JVM heap and NIO pools have different ownership; FFM, third-party malloc and driver host allocations remain untracked.
 */
final class AtlasVulkanMemory {
    private static final boolean ENABLED = Boolean.getBoolean("nirncraft.atlas.memoryDiagnostics");

    private AtlasVulkanMemory() {}

    /**
     * Capture beside profileTick's frame snapshot, before handing the immutable string to its writer thread.
     * No asynchronous Vulkan access: RenderPearl has no public closed-device flag. Running-client/render-thread
     * ownership keeps the query before normal device teardown, which runs on that same thread.
     */
    static String profile(Minecraft mc) {
        if (!ENABLED) return "";
        String unavailable = !RenderSystem.isOnRenderThread() ? "render_thread_required"
                : mc == null || !mc.isRunning() ? "client_not_running" : null;
        return profile(true, unavailable, AtlasVulkanMemory::readAllocator, AtlasVulkanMemory::readCommandPools);
    }

    record Counts(long allocationBytes, long blockBytes, int allocationCount, int blockCount) {
        Counts {
            if (allocationBytes < 0 || blockBytes < 0 || allocationCount < 0 || blockCount < 0)
                throw new IllegalArgumentException("negative allocator statistics");
        }

        JsonObject json() {
            JsonObject out = new JsonObject();
            out.addProperty("allocationBytes", allocationBytes);
            out.addProperty("blockBytes", blockBytes);
            out.addProperty("allocationCount", allocationCount);
            out.addProperty("blockCount", blockCount);
            return out;
        }
    }

    record Heap(int index, boolean deviceLocal, Counts counts) {}
    record MemoryType(int index, int heap, int propertyFlags, Counts counts) {}
    record Allocator(Counts total, List<Heap> heaps, List<MemoryType> memoryTypes) {
        Allocator {
            Objects.requireNonNull(total);
            heaps = List.copyOf(heaps);
            memoryTypes = List.copyOf(memoryTypes);
        }
    }

    // Also exercises disabled/missing/error semantics without creating a Vulkan device in a unit test.
    static String profile(boolean enabled, String unavailable, Supplier<Allocator> query) {
        return profile(enabled, unavailable, query, () -> unverified("not_sampled"));
    }

    static String profile(boolean enabled, String unavailable, Supplier<Allocator> query, Supplier<JsonObject> poolQuery) {
        if (!enabled) return "";  // does not inspect the device, management beans or native entry points
        JsonObject out = new JsonObject();
        out.addProperty("timestampMillis", System.currentTimeMillis());
        JsonObject vma;
        if (unavailable != null) {
            vma = unverified(unavailable);
        } else {
            long start = System.nanoTime();
            try {
                Allocator allocation = Objects.requireNonNull(query.get(), "missing allocator sample");
                vma = new JsonObject();
                vma.addProperty("status", "VERIFIED");
                vma.add("total", allocation.total().json());
                JsonArray heaps = new JsonArray();
                for (Heap heap : allocation.heaps()) {
                    JsonObject h = heap.counts().json();
                    h.addProperty("heap", heap.index());
                    h.addProperty("deviceLocal", heap.deviceLocal());
                    heaps.add(h);
                }
                vma.add("heaps", heaps);
                JsonArray types = new JsonArray();
                for (MemoryType type : allocation.memoryTypes()) {
                    JsonObject t = type.counts().json();
                    t.addProperty("memoryType", type.index());
                    t.addProperty("heap", type.heap());
                    t.addProperty("propertyFlags", type.propertyFlags());
                    t.addProperty("hostVisible", (type.propertyFlags() & VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0);
                    t.addProperty("deviceLocal", (type.propertyFlags() & VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0);
                    types.add(t);
                }
                vma.add("memoryTypes", types);
            } catch (RuntimeException | LinkageError failure) {
                vma = unverified(failure.getClass().getSimpleName());
            }
            vma.addProperty("queryNs", System.nanoTime() - start);
        }
        out.add("minecraftVma", vma);
        JsonObject pools;
        try {
            pools = unavailable != null ? unverified(unavailable) : Objects.requireNonNull(poolQuery.get());
        } catch (RuntimeException | LinkageError failure) {
            pools = unverified(failure.getClass().getSimpleName());
        }
        out.add("minecraftCommandPools", pools);
        out.add("javaHeap", javaHeap());
        out.add("nioBufferPools", bufferPools());
        return " mcMemory=" + out;
    }

    private static JsonObject readCommandPools() {
        VulkanDevice device = AtlasVulkan.device();
        if (device == null || !(device.createCommandEncoder() instanceof VkEncoderMemory encoder))
            return unverified("encoder_counters_unavailable");
        var pools = encoder.nirncraft$commandPools();
        long submit = encoder.nirncraft$submitIndex();
        if (pools == null || pools.length != 2 || submit < 0) return unverified("invalid_encoder_counters");
        JsonArray rows = new JsonArray();
        for (int i = 0; i < pools.length; i++) {
            if (!(pools[i] instanceof VkCommandPoolMemory access)) return unverified("pool_counters_unavailable");
            var handles = access.nirncraft$allocatedBuffers();
            if (handles == null) return unverified("pool_table_unavailable");
            rows.add(poolCounts(i, handles.position(), handles.limit(), handles.capacity()));
        }
        JsonObject out = new JsonObject();
        out.addProperty("status", "VERIFIED");
        out.addProperty("submitIndex", submit);
        out.add("pools", rows);
        out.addProperty("scope", "MC-owned command-buffer counts and pointer tables; excludes external ATLAS buffers and driver pool-storage bytes");
        return out;
    }

    static JsonObject poolCounts(int index, int used, int allocated, int capacity) {
        if (index < 0 || used < 0 || used > allocated || allocated > capacity || capacity < 0)
            throw new IllegalArgumentException("invalid command pool counts");
        JsonObject out = new JsonObject();
        out.addProperty("pool", index);
        out.addProperty("usedCommandBuffers", used);
        out.addProperty("allocatedCommandBuffers", allocated);
        out.addProperty("pointerCapacity", capacity);
        out.addProperty("pointerCapacityBytes", (long) capacity * org.lwjgl.system.Pointer.POINTER_SIZE);
        return out;
    }

    private static Allocator readAllocator() {
        if (!AtlasVulkan.vulkan()) throw new IllegalStateException("not_vulkan");
        VulkanDevice device = AtlasVulkan.device();
        if (device == null || device.vma() == 0) throw new IllegalStateException("allocator_unavailable");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties properties = VkPhysicalDeviceMemoryProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceMemoryProperties(device.vkDevice().getPhysicalDevice(), properties);
            VmaTotalStatistics statistics = VmaTotalStatistics.calloc(stack);
            Vma.vmaCalculateStatistics(device.vma(), statistics);
            int count = properties.memoryHeapCount();
            if (count <= 0 || count > statistics.memoryHeap().capacity())
                throw new IllegalStateException("memory_heap_metadata_unavailable");
            List<Heap> heaps = new ArrayList<>(count);
            for (int i = 0; i < count; ++i)
                heaps.add(new Heap(i, (properties.memoryHeaps(i).flags() & VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0,
                        counts(statistics.memoryHeap(i).statistics())));
            int typeCount = properties.memoryTypeCount();
            if (typeCount <= 0 || typeCount > statistics.memoryType().capacity())
                throw new IllegalStateException("memory_type_metadata_unavailable");
            List<MemoryType> types = new ArrayList<>(typeCount);
            for (int i = 0; i < typeCount; ++i)
                types.add(new MemoryType(i, properties.memoryTypes(i).heapIndex(), properties.memoryTypes(i).propertyFlags(),
                        counts(statistics.memoryType(i).statistics())));
            return new Allocator(counts(statistics.total().statistics()), heaps, types);
        }
    }

    private static Counts counts(VmaStatistics statistics) {
        return new Counts(statistics.allocationBytes(), statistics.blockBytes(), statistics.allocationCount(), statistics.blockCount());
    }

    private static JsonObject javaHeap() {
        try {
            var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            if (heap.getUsed() < 0 || heap.getCommitted() < 0 || heap.getMax() < 0) return unverified("heap_metrics_unavailable");
            JsonObject out = new JsonObject();
            out.addProperty("status", "VERIFIED");
            out.addProperty("usedBytes", heap.getUsed());
            out.addProperty("committedBytes", heap.getCommitted());
            out.addProperty("maxBytes", heap.getMax());
            return out;
        } catch (RuntimeException failure) {
            return unverified(failure.getClass().getSimpleName());
        }
    }

    private static JsonObject bufferPools() {
        JsonObject out = new JsonObject();
        for (String name : List.of("direct", "mapped")) out.add(name, unverified("pool_unavailable"));
        try {
            for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
                String name = pool.getName();
                if (!name.equals("direct") && !name.equals("mapped")) continue;
                long bytes = pool.getMemoryUsed(), capacity = pool.getTotalCapacity(), count = pool.getCount();
                if (bytes < 0 || capacity < 0 || count < 0) { out.add(name, unverified("pool_metrics_unavailable")); continue; }
                JsonObject p = new JsonObject();
                p.addProperty("status", "VERIFIED");
                p.addProperty("memoryUsedBytes", bytes);
                p.addProperty("capacityBytes", capacity);
                p.addProperty("count", count);
                out.add(name, p);
            }
        } catch (RuntimeException failure) {
            out.addProperty("error", failure.getClass().getSimpleName());
        }
        return out;
    }

    private static JsonObject unverified(String reason) {
        JsonObject out = new JsonObject();
        out.addProperty("status", "UNVERIFIED");
        out.addProperty("reason", reason);
        return out;
    }
}
