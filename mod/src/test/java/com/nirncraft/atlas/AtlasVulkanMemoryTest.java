package com.nirncraft.atlas;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Allocation attribution must keep missing/error readings distinct from real zero usage. No Vulkan device required. */
class AtlasVulkanMemoryTest {
    private static JsonObject parse(String line) {
        assertTrue(line.startsWith(" mcMemory="));
        return JsonParser.parseString(line.substring(" mcMemory=".length())).getAsJsonObject();
    }

    @Test
    void disabledAndUnavailableNeverQueryNativeAllocator() {
        assertEquals("", AtlasVulkanMemory.profile(false, null, () -> { fail("disabled query"); return null; }));
        for (String reason : List.of("render_thread_required", "client_not_running", "allocator_unavailable")) {
            JsonObject out = parse(AtlasVulkanMemory.profile(true, reason, () -> { fail("unavailable query"); return null; }));
            JsonObject vma = out.getAsJsonObject("minecraftVma");
            assertEquals("UNVERIFIED", vma.get("status").getAsString());
            assertEquals(reason, vma.get("reason").getAsString());
            assertFalse(vma.has("total"));
            assertFalse(vma.has("queryNs"));
            assertTrue(out.get("timestampMillis").getAsLong() > 0);
        }
    }

    @Test
    void allocatorFailureOrMissingSampleNeverReportsZeroUsage() {
        for (var query : List.<java.util.function.Supplier<AtlasVulkanMemory.Allocator>>of(
                () -> { throw new IllegalStateException("missing device"); },
                () -> { throw new UnsatisfiedLinkError("unsupported VMA binding"); },
                () -> null)) {
            JsonObject vma = parse(AtlasVulkanMemory.profile(true, null, query)).getAsJsonObject("minecraftVma");
            assertEquals("UNVERIFIED", vma.get("status").getAsString());
            assertFalse(vma.has("total"));
            assertTrue(vma.get("queryNs").getAsLong() >= 0);
        }
    }

    @Test
    void preservesAllocatorReservationAndLiveBytesAsSeparateLongCounters() {
        long allocation = 6L << 30, reservation = 9L << 30;
        var counts = new AtlasVulkanMemory.Counts(allocation, reservation, 7, 3);
        var snapshot = new AtlasVulkanMemory.Allocator(counts, List.of(
                new AtlasVulkanMemory.Heap(0, true, counts),
                new AtlasVulkanMemory.Heap(1, false, new AtlasVulkanMemory.Counts(0, 0, 0, 0))), List.of(
                new AtlasVulkanMemory.MemoryType(0, 0, 3, counts),
                new AtlasVulkanMemory.MemoryType(1, 1, 2, new AtlasVulkanMemory.Counts(0, 0, 0, 0))));
        JsonObject out = parse(AtlasVulkanMemory.profile(true, null, () -> snapshot));
        JsonObject vma = out.getAsJsonObject("minecraftVma");
        assertEquals("VERIFIED", vma.get("status").getAsString());
        assertEquals(allocation, vma.getAsJsonObject("total").get("allocationBytes").getAsLong());
        assertEquals(reservation, vma.getAsJsonObject("total").get("blockBytes").getAsLong());
        assertEquals(2, vma.getAsJsonArray("heaps").size());
        assertFalse(vma.getAsJsonArray("heaps").get(1).getAsJsonObject().get("deviceLocal").getAsBoolean());
        assertEquals(0, vma.getAsJsonArray("heaps").get(1).getAsJsonObject().get("allocationBytes").getAsLong());
        JsonObject bar = vma.getAsJsonArray("memoryTypes").get(0).getAsJsonObject();
        assertTrue(bar.get("deviceLocal").getAsBoolean());
        assertTrue(bar.get("hostVisible").getAsBoolean(), "BAR can be both host-visible and device-local");
        assertEquals(allocation, bar.get("allocationBytes").getAsLong());
        JsonObject host = vma.getAsJsonArray("memoryTypes").get(1).getAsJsonObject();
        assertTrue(host.get("hostVisible").getAsBoolean());
        assertFalse(host.get("deviceLocal").getAsBoolean());
        assertTrue(out.has("javaHeap"));
        assertTrue(out.getAsJsonObject("nioBufferPools").has("direct"));
        assertTrue(out.getAsJsonObject("nioBufferPools").has("mapped"));
        assertFalse(out.has("processPrivateBytes"));
    }

    @Test
    void commandPoolHandleCapacityIsSeparateFromLiveCommandBufferCounts() {
        JsonObject row = AtlasVulkanMemory.poolCounts(0, 3, 32, 512);
        assertEquals(3, row.get("usedCommandBuffers").getAsInt());
        assertEquals(32, row.get("allocatedCommandBuffers").getAsInt());
        assertEquals(512, row.get("pointerCapacity").getAsInt());
        assertEquals(512L * org.lwjgl.system.Pointer.POINTER_SIZE, row.get("pointerCapacityBytes").getAsLong());
        assertFalse(row.has("driverPoolBytes"));
        assertEquals(0, AtlasVulkanMemory.poolCounts(1, 0, 0, 512).get("allocatedCommandBuffers").getAsInt());
        assertThrows(IllegalArgumentException.class, () -> AtlasVulkanMemory.poolCounts(0, 33, 32, 512));
        assertThrows(IllegalArgumentException.class, () -> AtlasVulkanMemory.poolCounts(0, 1, 32, 16));
    }

    @Test
    void missingCommandPoolCountersNeverBecomeZeroAndDisabledNeverReadsThem() {
        assertEquals("", AtlasVulkanMemory.profile(false, null,
                () -> { fail("disabled allocator"); return null; }, () -> { fail("disabled pool query"); return null; }));
        JsonObject unavailable = parse(AtlasVulkanMemory.profile(true, "client_not_running",
                () -> { fail("closed allocator"); return null; }, () -> { fail("closed pools"); return null; }));
        assertEquals("UNVERIFIED", unavailable.getAsJsonObject("minecraftCommandPools").get("status").getAsString());
        var zero = new AtlasVulkanMemory.Counts(0, 0, 0, 0);
        JsonObject failure = parse(AtlasVulkanMemory.profile(true, null,
                () -> new AtlasVulkanMemory.Allocator(zero, List.of(), List.of()),
                () -> { throw new IllegalStateException("accessor unavailable"); }));
        JsonObject pools = failure.getAsJsonObject("minecraftCommandPools");
        assertEquals("UNVERIFIED", pools.get("status").getAsString());
        assertFalse(pools.has("pools"));
    }
}
