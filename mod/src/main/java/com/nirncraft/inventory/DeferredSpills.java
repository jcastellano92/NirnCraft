package com.nirncraft.inventory;

import com.nirncraft.NirnCraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.bus.api.EventPriority;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToIntBiFunction;

/** Durable overflow spill backlog. Counts stay bulk encoded until delivery actually succeeds. */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class DeferredSpills extends SavedData {
    private static final String NAME = "nirncraft_deferred_spills";
    static final int SPAWNS_PER_TICK = 64;
    static final int OUTSTANDING_ITEMS_PER_DIMENSION = 4096;
    private static final int VERSION = 2;
    private static final String SPILL_ITEM = "nirncraft_deferred_spill";
    private static final int AREA_SCANS_PER_TICK = 2;
    private static final int RETRY_MIN = 20, RETRY_MAX = 100;
    private static final int ACTIVE_ITEMS_PER_AREA = 2048;
    private final ArrayDeque<Cargo> queue = new ArrayDeque<>();
    /** Unloaded items remain counted until destruction/pickup, including across server restarts. */
    private final Set<UUID> outstanding = new HashSet<>();
    private final ListTag quarantine = new ListTag();
    private long budgetTick = Long.MIN_VALUE;
    private int spawnBudget, scanBudget;
    private long capacityRevision;

    boolean track(UUID id) {
        boolean added = outstanding.add(id);
        if (added) setDirty();
        return added;
    }

    boolean forget(UUID id) {
        boolean removed = outstanding.remove(id);
        if (removed) { capacityRevision++; setDirty(); }
        return removed;
    }

    void left(UUID id, Entity.RemovalReason reason) {
        if (reason != null && (reason.shouldDestroy() || reason == Entity.RemovalReason.CHANGED_DIMENSION)) forget(id);
    }

    boolean canSpawn() { return outstanding.size() < OUTSTANDING_ITEMS_PER_DIMENSION; }
    int outstandingItems() { return outstanding.size(); }
    int quarantinedRecords() { return quarantine.size(); }

    static final class Cargo {
        final UUID id;
        final BlockPos pos;
        final ListTag rows;
        int cursor, nextRow;
        long retryAt, blockedRevision;
        int retryDelay;

        boolean readyToRetry(long tick, long revision) { return tick >= retryAt || revision != blockedRevision; }
        void blocked(long tick, long revision) {
            retryDelay = retryDelay == 0 ? RETRY_MIN : Math.min(RETRY_MAX, retryDelay * 2);
            retryAt = tick + retryDelay;
            blockedRevision = revision;
        }
        void progressed() { retryAt = 0; retryDelay = 0; }

        Cargo(UUID id, BlockPos pos, ListTag rows) {
            this.id = id;
            this.pos = pos.immutable();
            this.rows = rows;
        }

        /** The sink receives an immutable item identity and a bulk count, and reports only accepted items. */
        long deliver(int operations, ToIntBiFunction<CompoundTag, Integer> sink) {
            long delivered = 0;
            Set<Integer> rejected = null;
            for (int op = 0; op < operations && cursor < rows.size(); op++) {
                if (nextRow < cursor || nextRow >= rows.size()) nextRow = cursor;
                int index = nextRow++;
                CompoundTag row = rows.getCompoundOrEmpty(index);
                int count = row.getIntOr(BulkStore.COUNT, 0);
                if (count <= 0) { if (index == cursor) cursor++; continue; }
                if (rejected != null && rejected.contains(index)) continue;
                int accepted = sink.applyAsInt(row.getCompoundOrEmpty(BulkStore.STACK), count);
                if (accepted < 0 || accepted > count) throw new IllegalStateException("Invalid spill delivery count");
                if (accepted == 0) {
                    // A temporarily unavailable item/recipient must not starve every later identity.
                    if (rejected == null) rejected = new HashSet<>();
                    rejected.add(index);
                    continue;
                }
                row.putInt(BulkStore.COUNT, count - accepted);
                delivered += accepted;
                if (count == accepted && index == cursor) cursor++;
            }
            return delivered;
        }

        long remaining() {
            long count = 0;
            for (int i = cursor; i < rows.size(); i++) count += Math.max(0, rows.getCompoundOrEmpty(i).getIntOr(BulkStore.COUNT, 0));
            return count;
        }
    }

    public static DeferredSpills get(ServerLevel level) {
        return com.nirncraft.platform.SavedDataTypes.get(level, NAME, DeferredSpills::load, DeferredSpills::new, data -> data.save(new CompoundTag()));
    }

    /** Snapshot before clearing the source. This uses ordinary world autosave durability, like entity drops. */
    Cargo retain(BlockPos pos, CompoundTag bulk) {
        Cargo cargo = new Cargo(UUID.randomUUID(), pos, bulk.getListOrEmpty(BulkStore.LIST).copy());
        queue.addLast(cargo);
        setDirty();
        return cargo;
    }

    public static int spill(ServerLevel level, BlockPos pos, BulkStore store) {
        if (store.isEmpty()) return 0;
        long stacks = 0;
        for (ItemStack row : store.entries()) {
            stacks += ((long) row.getCount() + Math.max(1, row.getMaxStackSize()) - 1) / Math.max(1, row.getMaxStackSize());
        }
        DeferredSpills data = get(level);
        Cargo cargo = data.retain(pos, store.save());
        store.clear();
        data.deliver(level, cargo);
        if (cargo.cursor == cargo.rows.size()) data.queue.remove(cargo);
        return (int) Math.min(Integer.MAX_VALUE, stacks);
    }

    void deliver(ServerLevel level, Cargo cargo) {
        long tick = level.getGameTime();
        if (!cargo.readyToRetry(tick, capacityRevision)) return;
        if (!level.hasChunkAt(cargo.pos)) { cargo.blocked(tick, capacityRevision); return; }
        if (budgetTick != tick) {
            budgetTick = tick;
            spawnBudget = SPAWNS_PER_TICK;
            scanBudget = AREA_SCANS_PER_TICK;
        }
        if (spawnBudget == 0 || scanBudget == 0) return;
        double x = cargo.pos.getX() + .5, y = cargo.pos.getY() + .5, z = cargo.pos.getZ() + .5;
        scanBudget--;
        var localItems = new ArrayList<ItemEntity>();
        level.getEntities(EntityTypeTest.forClass(ItemEntity.class),
                new net.minecraft.world.phys.AABB(cargo.pos).inflate(16), item -> true, localItems, ACTIVE_ITEMS_PER_AREA);
        int[] active = {localItems.size()};
        Player near = active[0] >= ACTIVE_ITEMS_PER_AREA || !canSpawn()
                ? level.getNearestPlayer(x, y, z, 16, false) : null;
        long delivered = cargo.deliver(spawnBudget, (identity, count) -> {
            if (spawnBudget <= 0) return 0;
            spawnBudget--; // all delivery attempts share one dimension budget, including full packs
            ItemStack stack = com.nirncraft.inventory.ItemData.parse(identity);
            if (stack.isEmpty()) return 0; // missing mods retain their data for recovery
            if (active[0] >= ACTIVE_ITEMS_PER_AREA || !canSpawn()) {
                if (near == null) return 0;
                stack.setCount(count);
                return Pack.add(near, stack); // never giveOrDrop: a full pack must leave the remainder queued
            }
            int amount = Math.min(count, Math.max(1, stack.getMaxStackSize()));
            stack.setCount(amount);
            ItemEntity entity = new ItemEntity(level, x, y, z, stack);
            entity.setDeltaMovement((level.getRandom().nextDouble() - .5) * .2, .15, (level.getRandom().nextDouble() - .5) * .2);
            entity.setUnlimitedLifetime();
            entity.getPersistentData().putBoolean(SPILL_ITEM, true);
            // Reserve before the event fires; cancelled joins undo the reservation.
            if (!track(entity.getUUID())) return 0;
            if (!level.addFreshEntity(entity)) { forget(entity.getUUID()); return 0; }
            active[0]++;
            return amount;
        });
        if (delivered > 0) { cargo.progressed(); setDirty(); }
        else cargo.blocked(tick, capacityRevision);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onItemJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ItemEntity item)
                || !item.getPersistentData().getBooleanOr(SPILL_ITEM, false)) return;
        get(level).track(item.getUUID());
    }

    @SubscribeEvent
    public static void onItemLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ItemEntity item)
                || !item.getPersistentData().getBooleanOr(SPILL_ITEM, false)) return;
        get(level).left(item.getUUID(), item.getRemovalReason());
    }

    @SubscribeEvent
    public static void onTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        DeferredSpills data = get(level);
        int visits = Math.min(16, data.queue.size());
        for (int i = 0; i < visits; i++) {
            Cargo cargo = data.queue.removeFirst();
            data.deliver(level, cargo);
            if (cargo.cursor < cargo.rows.size()) data.queue.addLast(cargo);
            else data.setDirty();
        }
    }

    private void quarantine(String reason, Tag raw) {
        CompoundTag entry = new CompoundTag();
        entry.putString("reason", reason);
        entry.put("raw", raw.copy());
        quarantine.add(entry);
        setDirty();
    }

    private static boolean validRows(ListTag rows) {
        if (!com.nirncraft.platform.NbtData.isListOf(rows, Tag.TAG_COMPOUND) && !rows.isEmpty()) return false;
        for (Tag raw : rows) {
            CompoundTag row = (CompoundTag)raw;
            if (!com.nirncraft.platform.NbtData.hasType(row, BulkStore.COUNT, Tag.TAG_INT) || row.getIntOr(BulkStore.COUNT, 0) <= 0
                    || !com.nirncraft.platform.NbtData.hasType(row, BulkStore.STACK, Tag.TAG_COMPOUND)) return false;
            CompoundTag identity = row.getCompoundOrEmpty(BulkStore.STACK);
            if (net.minecraft.resources.Identifier.tryParse(identity.getStringOr("id", "")) == null
                    || identity.getIntOr("count", identity.getIntOr("Count", 0)) != 1) return false;
        }
        return true;
    }

    static DeferredSpills load(CompoundTag tag) {
        DeferredSpills data = new DeferredSpills();
        int version = tag.getIntOr("v", 0);
        if (!com.nirncraft.platform.NbtData.hasType(tag, "v", Tag.TAG_INT) || version < 1 || version > VERSION) {
            data.quarantine("Unsupported spill data version " + version, tag);
            NirnCraft.LOGGER.warn("[inventory] Unsupported deferred spill version {}; entire payload retained for recovery", version);
            return data;
        }
        Tag prior = tag.get("quarantine");
        if (prior instanceof ListTag list && (list.isEmpty() || com.nirncraft.platform.NbtData.isListOf(list, Tag.TAG_COMPOUND))) {
            for (Tag row : list) data.quarantine.add(row.copy());
        } else if (prior != null) data.quarantine("Malformed recovery records", prior);
        Tag savedCargo = tag.get("cargo");
        if (savedCargo instanceof ListTag list && (list.isEmpty() || com.nirncraft.platform.NbtData.isListOf(list, Tag.TAG_COMPOUND))) {
            Set<UUID> ids = new HashSet<>();
            for (Tag raw : list) {
                CompoundTag row = (CompoundTag)raw;
                Tag rawRows = row.get("rows");
                if (!row.read("id", net.minecraft.core.UUIDUtil.CODEC).isPresent() || !com.nirncraft.platform.NbtData.hasType(row, "pos", Tag.TAG_LONG) || !(rawRows instanceof ListTag rows)
                        || !validRows(rows) || !ids.add(row.read("id", net.minecraft.core.UUIDUtil.CODEC).orElseThrow())) {
                    data.quarantine("Malformed or duplicate cargo", row);
                    continue;
                }
                data.queue.addLast(new Cargo(row.read("id", net.minecraft.core.UUIDUtil.CODEC).orElseThrow(), BlockPos.of(row.getLongOr("pos", 0L)), rows.copy()));
            }
        } else if (savedCargo != null) data.quarantine("Malformed cargo list", savedCargo);
        Tag savedItems = tag.get("outstanding");
        if (savedItems instanceof ListTag list && (list.isEmpty() || com.nirncraft.platform.NbtData.isListOf(list, Tag.TAG_COMPOUND))) {
            for (Tag raw : list) {
                CompoundTag row = (CompoundTag)raw;
                if (row.read("id", net.minecraft.core.UUIDUtil.CODEC).isPresent()) data.outstanding.add(row.read("id", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
                else data.quarantine("Malformed outstanding item identity", row);
            }
        } else if (savedItems != null) data.quarantine("Malformed outstanding item list", savedItems);
        if (!data.quarantine.isEmpty()) NirnCraft.LOGGER.warn("[inventory] {} deferred spill recovery records retained", data.quarantine.size());
        return data;
    }

    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Cargo cargo : queue) {
            CompoundTag row = new CompoundTag();
            row.store("id", net.minecraft.core.UUIDUtil.CODEC, cargo.id);
            row.putLong("pos", cargo.pos.asLong());
            ListTag remaining = new ListTag();
            for (int i = cargo.cursor; i < cargo.rows.size(); i++) {
                CompoundTag item = cargo.rows.getCompoundOrEmpty(i);
                if (item.getIntOr(BulkStore.COUNT, 0) > 0) remaining.add(item.copy());
            }
            row.put("rows", remaining);
            list.add(row);
        }
        tag.putInt("v", VERSION);
        tag.put("cargo", list);
        ListTag items = new ListTag();
        outstanding.stream().sorted().forEach(id -> {
            CompoundTag row = new CompoundTag(); row.store("id", net.minecraft.core.UUIDUtil.CODEC, id); items.add(row);
        });
        tag.put("outstanding", items);
        tag.put("quarantine", quarantine.copy());
        return tag;
    }

    /** Scoped selftest bookkeeping: preserve every cargo that existed before the fixture. */
    Set<UUID> cargoIds() {
        Set<UUID> ids = new HashSet<>();
        for (Cargo cargo : queue) ids.add(cargo.id);
        return Set.copyOf(ids);
    }

    long pendingAfter(Set<UUID> preserved) {
        long count = 0;
        for (Cargo cargo : queue) if (!preserved.contains(cargo.id)) count += cargo.remaining();
        return count;
    }

    void discardAfter(Set<UUID> preserved) {
        if (queue.removeIf(cargo -> !preserved.contains(cargo.id))) setDirty();
    }

    long pendingItems() {
        long count = 0;
        for (Cargo cargo : queue) count += cargo.remaining();
        return count;
    }
}
