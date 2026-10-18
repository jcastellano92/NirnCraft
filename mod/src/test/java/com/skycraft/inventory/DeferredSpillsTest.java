package com.skycraft.inventory;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.Entity;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeferredSpillsTest {
    private static CompoundTag bulk(int count) {
        CompoundTag identity = new CompoundTag();
        identity.putString("id", "minecraft:arrow");
        identity.putByte("Count", (byte) 1);
        CompoundTag custom = new CompoundTag();
        custom.putString("custom", "keep this enchantment/name/capability payload");
        identity.put("tag", custom);
        CompoundTag row = new CompoundTag();
        row.put("s", identity);
        row.putInt("n", count);
        ListTag rows = new ListTag(); rows.add(row);
        CompoundTag bulk = new CompoundTag(); bulk.put("e", rows);
        return bulk;
    }

    @Test
    void millionsOfItemsAreBoundedAndConservedThroughSaveReloadAndFullRecipient() {
        var queue = new DeferredSpills();
        var cargo = queue.retain(new BlockPos(50, -20, 60), bulk(50_000_000));
        int[] operations = {0};
        long delivered = cargo.deliver(64, (identity, count) -> {
            operations[0]++;
            assertEquals("keep this enchantment/name/capability payload", identity.getCompoundOrEmpty("tag").getStringOr("custom", ""));
            return Math.min(64, count);
        });
        assertEquals(64, operations[0]);
        assertEquals(4096, delivered);
        assertEquals(50_000_000, delivered + queue.pendingItems());
        assertEquals(0, cargo.deliver(64, (identity, count) -> 0));
        assertEquals(50_000_000, delivered + queue.pendingItems());
        CompoundTag saved = queue.save(new CompoundTag());
        var loaded = DeferredSpills.load(saved);
        assertEquals(queue.pendingItems(), loaded.pendingItems());
        assertEquals(saved, loaded.save(new CompoundTag()));
        assertTrue(saved.toString().length() < 1000, "No per-stack expansion on disk");
    }

    @Test
    void rejectedSpawnsDoNotConsumeItemsAndBadSinkCountsCannotCorruptState() {
        var queue = new DeferredSpills();
        var cargo = queue.retain(BlockPos.ZERO, bulk(Integer.MAX_VALUE));
        assertEquals(0, cargo.deliver(64, (identity, count) -> 0));
        assertEquals(Integer.MAX_VALUE, queue.pendingItems());
        assertThrows(IllegalStateException.class, () -> cargo.deliver(1, (identity, count) -> -1));
        assertEquals(Integer.MAX_VALUE, queue.pendingItems());
        assertEquals(Integer.MAX_VALUE, cargo.deliver(1, (identity, count) -> count));
        assertEquals(0, queue.pendingItems());
    }
    @Test void dimensionEntityCapSurvivesUnloadRestartAndOnlyFreesActualRemovals() {
        var data = new DeferredSpills();
        UUID first = UUID.randomUUID(); data.track(first);
        for (int i = 1; i < DeferredSpills.OUTSTANDING_ITEMS_PER_DIMENSION; i++) data.track(UUID.randomUUID());
        assertFalse(data.canSpawn());
        data.left(first, Entity.RemovalReason.UNLOADED_TO_CHUNK);
        assertFalse(data.canSpawn());
        var loaded = DeferredSpills.load(data.save(new CompoundTag()));
        assertEquals(DeferredSpills.OUTSTANDING_ITEMS_PER_DIMENSION, loaded.outstandingItems());
        assertFalse(loaded.track(first), "Loading a tagged item must reconcile instead of double-counting");
        loaded.left(first, Entity.RemovalReason.DISCARDED);
        assertTrue(loaded.canSpawn());
        assertFalse(loaded.forget(first), "Duplicate destruction is idempotent");
        loaded.track(first); loaded.left(first, null); assertFalse(loaded.canSpawn());
        loaded.left(first, Entity.RemovalReason.CHANGED_DIMENSION); assertTrue(loaded.canSpawn());
    }

    @Test void unsupportedVersionsRetainTheEntireOriginalPayloadForRecovery() {
        CompoundTag future = new CompoundTag(); future.putInt("v", 99); future.putString("new-format", "do not lose me");
        future.put("cargo", bulk(700));
        var loaded = DeferredSpills.load(future);
        assertEquals(0, loaded.pendingItems()); assertEquals(1, loaded.quarantinedRecords());
        CompoundTag saved = loaded.save(new CompoundTag());
        assertEquals(future, saved.getListOrEmpty("quarantine").getCompoundOrEmpty(0).getCompoundOrEmpty("raw"));
        assertEquals(saved, DeferredSpills.load(saved).save(new CompoundTag()));
    }

    @Test void malformedCargoIsQuarantinedWithoutLosingItsRawCountsOrIdentity() {
        var data = new DeferredSpills(); data.retain(BlockPos.ZERO, bulk(123456789));
        CompoundTag saved = data.save(new CompoundTag());
        CompoundTag row = saved.getListOrEmpty("cargo").getCompoundOrEmpty(0);
        row.remove("id"); CompoundTag original = row.copy();
        var loaded = DeferredSpills.load(saved);
        assertEquals(0, loaded.pendingItems()); assertEquals(1, loaded.quarantinedRecords());
        CompoundTag recovered = loaded.save(new CompoundTag()).getListOrEmpty("quarantine").getCompoundOrEmpty(0).getCompoundOrEmpty("raw");
        assertEquals(original, recovered);
    }

    @Test void versionOneQueuesMigrateWithoutExpandingOrChangingCounts() {
        var data = new DeferredSpills(); data.retain(BlockPos.ZERO, bulk(10000000));
        CompoundTag old = data.save(new CompoundTag()); old.putInt("v", 1); old.remove("outstanding"); old.remove("quarantine");
        var loaded = DeferredSpills.load(old);
        assertEquals(10000000, loaded.pendingItems()); assertEquals(0, loaded.quarantinedRecords());
        assertEquals(2, loaded.save(new CompoundTag()).getIntOr("v", 0));
    }

    @Test void blockedRecipientsBackOffButReleasedCapacityWakesRetriesImmediately() {
        var data = new DeferredSpills(); var cargo = data.retain(BlockPos.ZERO, bulk(100));
        cargo.blocked(100, 2);
        assertFalse(cargo.readyToRetry(101, 2)); assertTrue(cargo.readyToRetry(120, 2));
        assertTrue(cargo.readyToRetry(101, 3));
        for (int i = 0; i < 10; i++) cargo.blocked(200, 2);
        assertFalse(cargo.readyToRetry(299, 2)); assertTrue(cargo.readyToRetry(300, 2));
        cargo.progressed(); assertTrue(cargo.readyToRetry(201, 2));
        assertEquals(100, data.pendingItems());
    }

    @Test void unavailableFirstIdentityDoesNotStarveLaterItemsOrLoseRecoveryData() {
        var data = new DeferredSpills();
        CompoundTag mixed = bulk(500);
        var rows = mixed.getListOrEmpty("e");
        rows.getCompoundOrEmpty(0).getCompoundOrEmpty("s").putString("id", "missingmod:recoverable_item");
        rows.add(bulk(100).getListOrEmpty("e").getCompoundOrEmpty(0).copy());
        var cargo = data.retain(BlockPos.ZERO, mixed);
        int[] calls = {0};
        long sent = cargo.deliver(64, (identity, count) -> {
            calls[0]++;
            return identity.getStringOr("id", "").startsWith("missingmod:") ? 0 : Math.min(64, count);
        });
        assertEquals(100, sent); assertTrue(calls[0] <= 4);
        assertEquals(500, data.pendingItems());
        var restored = DeferredSpills.load(data.save(new CompoundTag()));
        assertEquals(500, restored.pendingItems()); assertEquals(0, restored.quarantinedRecords());
        var savedRows = restored.save(new CompoundTag()).getListOrEmpty("cargo").getCompoundOrEmpty(0).getListOrEmpty("rows");
        assertEquals(1, savedRows.size()); assertEquals("missingmod:recoverable_item", savedRows.getCompoundOrEmpty(0).getCompoundOrEmpty("s").getStringOr("id", ""));
    }

    @Test void fixtureCleanupRemovesOnlyCargoCreatedAfterItsSnapshot() {
        var data = new DeferredSpills(); data.retain(BlockPos.ZERO, bulk(7654321));
        var preserved = data.cargoIds();
        data.retain(BlockPos.ZERO, bulk(3000000));
        assertEquals(3000000, data.pendingAfter(preserved));
        data.discardAfter(preserved);
        assertEquals(7654321, data.pendingItems()); assertEquals(preserved, data.cargoIds());
        assertEquals(0, data.pendingAfter(preserved));
    }

}
