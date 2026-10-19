package com.nirncraft.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PlayerDataSyncTest {
    private static volatile CompoundTag serializationSink;

    @Test
    void bulkExclusionAvoidsTheLargeAllocationInsteadOfDiscardingACopy() {
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        var data = new PlayerData();
        data.module("pack").putByteArray("payload", new byte[1_000_000]);
        for (int i = 0; i < 20; i++) serializationSink = data.saveExcludingModules(List.of("pack"));
        long thread = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 20; i++) {
            var legacy = data.save();
            legacy.getCompoundOrEmpty("modules").remove("pack");
            serializationSink = legacy;
        }
        long legacyBytes = bean.getThreadAllocatedBytes(thread) - before;
        before = bean.getThreadAllocatedBytes(thread);
        for (int i = 0; i < 20; i++) serializationSink = data.saveExcludingModules(List.of("pack"));
        long filteredBytes = bean.getThreadAllocatedBytes(thread) - before;
        System.out.println("CPU01 allocation for20 serializations: legacy=" + legacyBytes + " filtered=" + filteredBytes);
        assertTrue(filteredBytes < legacyBytes / 10, "Bulk must be excluded before its payload is copied");
    }

    @Test
    void excludingBulkMatchesLegacySyncAndRetainsFullSaveAndClientOmissions() {
        var data = new PlayerData();
        data.module("pack").putByteArray("large", new byte[1_000_000]);
        data.module("serverOnly").putInt("count", 10000);
        data.module("quest").putString("active", "test");
        var before = data.save();
        var legacy = before.copy();
        var excluded = List.of("pack", "serverOnly", "absent");
        var omissions = new ListTag();
        for (String key : excluded) {
            if (legacy.getCompoundOrEmpty("modules").contains(key)) {
                legacy.getCompoundOrEmpty("modules").remove(key);
                omissions.add(StringTag.valueOf(key));
            }
        }
        legacy.put("omitted", omissions);
        var sync = data.saveExcludingModules(excluded);
        assertEquals(legacy, sync);
        assertEquals(before, data.save(), "Sync cannot mutate persistent state");
        sync.getCompoundOrEmpty("modules").getCompoundOrEmpty("quest").putString("active", "changed");
        assertEquals("test", data.module("quest").getStringOr("active", ""));
        var client = new PlayerData();
        client.module("pack").putInt("retained", 123);
        client.load(legacy);
        assertEquals(123, client.module("pack").getIntOr("retained", 0));
        assertFalse(data.save().contains("omitted"));
    }
}
