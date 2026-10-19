package com.nirncraft.inventory;

import com.mojang.authlib.GameProfile;
import com.nirncraft.NirnCraft;
import com.nirncraft.core.SkyData;
import com.nirncraft.quest.QuestItems;
import com.nirncraft.survival.SurvivalRegistry;
import com.nirncraft.survival.corpse.PlayerCorpseEntity;
import com.nirncraft.survival.corpse.PlayerCorpses;
import com.nirncraft.vitals.ActionHandler;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Self-test of the bulk store and everything built on it (docs/INVENTORY.md): the store itself, a container with an
 * overflow driven through the real take/store handlers, block entities saved and loaded like a chunk does and spilled
 * when destroyed, a death (drops event, corpse, corpse NBT, loot after a respawn clone), a player saved to a real file
 * and loaded, and the facade that makes pack items count at once. Called from {@link PackSelfTest#run}.
 */
final class OverflowSelfTest {
    private static final GameProfile PROFILE_D = new GameProfile(UUID.nameUUIDFromBytes("nirncraft-pack-selftest-d".getBytes()), "[PackTestD]");
    private static final GameProfile PROFILE_E = new GameProfile(UUID.nameUUIDFromBytes("nirncraft-pack-selftest-e".getBytes()), "[PackTestE]");
    private static final String TAG = "ovf_t";
    private static final ThreadLocal<java.util.Set<UUID>> PRESERVED_DROPS=new ThreadLocal<>();
    private static final ThreadLocal<java.util.Set<UUID>> PRESERVED_CARGO=new ThreadLocal<>();

    private OverflowSelfTest() {}

    static void run(MinecraftServer server, ServerLevel level, FakePlayer a, PackSelfTest.Checks c) {
        var existing=new java.util.HashSet<UUID>();
        for(var e:level.getEntitiesOfClass(ItemEntity.class,new AABB(testAnchor(level)).inflate(48))) existing.add(e.getUUID());
        PRESERVED_DROPS.set(existing);
        PRESERVED_CARGO.set(DeferredSpills.get(level).cargoIds());
        try {
        guarded("bulk store", c, () -> bulkStore(c));
        guarded("container overflow", c, () -> containerOverflow(server, a, c));
        guarded("block entities", c, () -> blockEntities(level, c));
        guarded("death and corpse", c, () -> death(server, level, c));
        guarded("player file", c, () -> playerFile(server, level, c));
        guarded("facade", c, () -> facade(server, level, a, c));
        guarded("stragglers", c, () -> stragglers(level, a, c));
        guarded("bounded deferred spills", c, () -> deferredSpills(level, a, c));
        } finally {
            try { sweep(level,existing); } finally { PRESERVED_DROPS.remove(); PRESERVED_CARGO.remove(); }
        }
    }

    /** Entity queries only see active chunks. A live operator may be far from
     * world spawn; keep fixtures in that operator's already-ticking area. */
    static BlockPos testAnchor(ServerLevel level) {
        for(var player:level.players()) if(!(player instanceof FakePlayer)) return player.blockPosition();
        return level.getRespawnData().pos();
    }

    private static void guarded(String what, PackSelfTest.Checks c, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            NirnCraft.LOGGER.error("Overflow selftest '{}' threw", what, e);
            c.check("overflow selftest '" + what + "' ran to the end", false, e.toString());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static ItemStack distinct(int i) {
        Item[] kinds = {Items.IRON_SWORD, Items.DIAMOND_PICKAXE, Items.BOW, Items.SHIELD, Items.LEATHER_HELMET, Items.POTION};
        ItemStack s = new ItemStack(kinds[i % kinds.length]);
        com.nirncraft.inventory.ItemData.updateCustom(s, customData263 -> customData263.putInt(TAG, i));
        return s;
    }

    private static void sweep(ServerLevel level) {
        var existing=PRESERVED_DROPS.get();
        sweep(level,existing==null?java.util.Set.of():existing);
    }

    private static void sweep(ServerLevel level,java.util.Set<UUID> existing) {
        BlockPos at = testAnchor(level);
        // bodies first: discarding one spills what it holds as item entities, which are swept next
        for (PlayerCorpseEntity e : level.getEntitiesOfClass(PlayerCorpseEntity.class, new AABB(at).inflate(48))) {
            if (e.ownerName().startsWith("[PackTest")) e.discard();
        }
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(48))) {
            if(existing.contains(e.getUUID())) continue;
            ItemStack s = e.getItem();
            if ((com.nirncraft.inventory.ItemData.hasCustom(s) && com.nirncraft.inventory.ItemData.custom(s).contains(TAG)) || s.is(Items.ARROW) || s.is(Items.IRON_HELMET)) e.discard();
        }
        var preservedCargo = PRESERVED_CARGO.get();
        if (preservedCargo != null) DeferredSpills.get(level).discardAfter(preservedCargo);
    }

    private static Map<String, Long> count(Iterable<ItemStack> stacks) {
        Map<String, Long> m = new TreeMap<>();
        for (ItemStack s : stacks) {
            if (s.isEmpty()) continue;
            m.merge(BuiltInRegistries.ITEM.getKey(s.getItem()) + "|" + com.nirncraft.inventory.ItemData.custom(s), (long) s.getCount(), Long::sum);
        }
        return m;
    }

    private static long total(Map<String, Long> m) {
        long n = 0;
        for (long v : m.values()) n += v;
        return n;
    }

    private static List<ItemStack> playerStacks(net.minecraft.server.level.ServerPlayer p) {
        List<ItemStack> out = new ArrayList<>();
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) out.add(inv.getItem(i).copy());
        out.addAll(Pack.entries(p));
        return out;
    }

    private static String diff(Map<String, Long> a, Map<String, Long> b) {
        if (a.equals(b)) return "identical (" + a.size() + " kinds, " + total(a) + " items)";
        for (Map.Entry<String, Long> e : a.entrySet()) {
            if (!e.getValue().equals(b.get(e.getKey()))) return "first difference at " + e.getKey() + ": " + e.getValue() + " vs " + b.get(e.getKey());
        }
        return "different key sets: " + a.size() + " vs " + b.size();
    }

    // ------------------------------------------------------------------ 1. the store

    private static void bulkStore(PackSelfTest.Checks c) {
        BulkStore s = new BulkStore(5, 1000, null);
        ItemStack arrows = new ItemStack(Items.ARROW, 600);
        int t1 = s.add(arrows);
        ItemStack more = new ItemStack(Items.ARROW, 600);
        int t2 = s.add(more);
        c.check("bulk: counts are ints and merge into one entry, the per-entry cap is enforced",
                t1 == 600 && t2 == 400 && more.getCount() == 200 && s.size() == 1 && s.countAt(0) == 1000,
                t1 + " + " + t2 + ", " + more.getCount() + " refused, " + s.size() + " entries of " + s.countAt(0));
        for (int i = 0; i < 4; i++) s.add(distinct(i));
        ItemStack sixth = distinct(77);
        int refused = s.add(sixth);
        c.check("bulk: the entry cap refuses a new kind without touching the stack", refused == 0 && sixth.getCount() == 1 && s.size() == 5,
                s.size() + " entries");
        List<String> order = new ArrayList<>();
        for (ItemStack e : s.entries()) order.add(BuiltInRegistries.ITEM.getKey(e.getItem()) + String.valueOf(com.nirncraft.inventory.ItemData.custom(e)));
        ItemStack firstOut = s.take(0, 300);
        c.check("bulk: a partial take keeps the entry in place and in order",
                firstOut.getCount() == 300 && s.countAt(0) == 700 && s.size() == 5 && order.size() == 5, s.countAt(0) + " left");
        ItemStack all = s.takeAll(new ItemStack(Items.ARROW));
        c.check("bulk: takeAll removes the whole entry and later entries slide up",
                all.getCount() == 700 && s.size() == 4 && com.nirncraft.inventory.ItemData.hasCustom(s.get(0)), all.getCount() + " taken, " + s.size() + " entries");

        // persistence: versioned, tolerant of bad and duplicate rows
        BulkStore big = new BulkStore(100, 5_000_000, null);
        big.add(new ItemStack(Items.ARROW, 3_000_000));
        for (int i = 0; i < 10; i++) big.add(distinct(i));
        CompoundTag saved = big.save();
        BulkStore back;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            CompoundTag wrap = new CompoundTag();
            wrap.put("store", saved);
            NbtIo.writeCompressed(wrap, bos);
            back = BulkStore.loaded(NbtIo.readCompressed(new ByteArrayInputStream(bos.toByteArray()), net.minecraft.nbt.NbtAccounter.create(268435456L)).getCompoundOrEmpty("store"), 100, 5_000_000, null);
        } catch (IOException e) {
            c.check("bulk: NBT round trip", false, e.toString());
            return;
        }
        c.check("bulk: save/load keeps rows, order and int counts above 127 (3 million arrows)",
                saved.getIntOr("v", 0) == BulkStore.VERSION && back.sameAs(big) && back.count(Items.ARROW) == 3_000_000,
                "version " + saved.getIntOr("v", 0) + ", " + back.size() + " rows");
        CompoundTag dirty = big.save();
        ListTag rows = dirty.getListOrEmpty("e");
        rows.add(rows.getCompoundOrEmpty(0).copy()); // a duplicate of the arrows
        CompoundTag bad = new CompoundTag();
        bad.putInt("n", -4);
        rows.add(bad);
        BulkStore repaired = BulkStore.loaded(dirty, 100, 5_000_000, null);
        c.check("bulk: loading merges duplicate rows and drops bad ones", repaired.size() == big.size() && repaired.count(Items.ARROW) == 5_000_000,
                repaired.size() + " rows, " + repaired.count(Items.ARROW) + " arrows (per-entry cap 5M)");
        BulkStore other = new BulkStore(100, 5_000_000, null);
        other.add(new ItemStack(Items.ARROW, 10));
        other.add(distinct(500));
        boolean moved = big.absorb(other);
        c.check("bulk: absorb moves everything (halves of a double chest)", moved && other.isEmpty() && big.count(Items.ARROW) == 3_000_010,
                big.count(Items.ARROW) + " arrows, other empty " + other.isEmpty());
    }

    // ------------------------------------------------------------------ 2. a container with an overflow

    private static final class OvChest extends SimpleContainer implements OverflowHost {
        final BulkStore bulk = new BulkStore(ContainerOverflow.MAX_ENTRIES, ContainerOverflow.MAX_PER_ENTRY, null);

        /** A weight-limited host (a saddlebag) says how many more of an item its rule allows. */
        int room = Integer.MAX_VALUE;

        OvChest() {
            super(27);
        }

        @Override
        public BulkStore overflow() {
            return bulk;
        }

        @Override
        public int overflowRoom(ItemStack stack) {
            return room;
        }
    }

    private static void containerOverflow(MinecraftServer server, FakePlayer a, PackSelfTest.Checks c) {
        a.getInventory().clearContent();
        Pack.clear(a);
        Inventory inv = a.getInventory();
        OvChest chest = new OvChest();
        for (int i = 0; i < 27; i++) chest.setItem(i, distinct(1000 + i));
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) inv.setItem(i, distinct(i));
        for (int i = 100; i < 164; i++) Pack.give(a, distinct(i));
        Map<String, Long> before = new TreeMap<>(count(playerStacks(a)));
        for (int i = 0; i < 27; i++) before.merge(BuiltInRegistries.ITEM.getKey(chest.getItem(i).getItem()) + "|" + com.nirncraft.inventory.ItemData.custom(chest.getItem(i)), 1L, Long::sum);
        ChestMenu menu = new ChestMenu(MenuType.GENERIC_9x3, 91, inv, chest, 3);
        a.containerMenu = menu;

        ActionHandler.handleContainerStore(a, 91, -1, true);
        c.check("container: Store All into a full container never fails, the rest goes to its overflow",
                chest.bulk.size() >= 60 && Pack.size(a) == 0, chest.bulk.size() + " overflow rows, pack " + Pack.size(a));
        List<ItemStack> now = new ArrayList<>(playerStacks(a));
        for (int i = 0; i < 27; i++) now.add(chest.getItem(i));
        now.addAll(chest.bulk.entries());
        c.check("container: storing loses and duplicates nothing", count(now).equals(before), diff(before, count(now)));

        // the wire format of the open container's overflow
        int rowsBefore = chest.bulk.size();
        List<InventoryPackets.ContainerOverflowSync> packets = ContainerSync.packets(91, chest.bulk);
        List<ItemStack> mirror = new ArrayList<>();
        for (InventoryPackets.ContainerOverflowSync p : packets) {
            net.minecraft.network.RegistryFriendlyByteBuf buf = new net.minecraft.network.RegistryFriendlyByteBuf(Unpooled.buffer(), a.registryAccess());
            InventoryPackets.ContainerOverflowSync.encode(p, buf);
            InventoryPackets.ContainerOverflowSync q = InventoryPackets.ContainerOverflowSync.decode(buf);
            for (InventoryPackets.PackSync.Row r : q.rows()) {
                ItemStack s = com.nirncraft.inventory.ItemData.parse(r.key());
                s.setCount(r.count());
                mirror.add(s);
            }
        }
        c.check("container: the overflow syncs to the client in " + packets.size() + " bounded packet(s)",
                count(mirror).equals(count(chest.bulk.entries())) && mirror.size() == rowsBefore, mirror.size() + " rows");

        // a host's own room rule limits what the overflow accepts (the mount's saddlebags will use this)
        chest.room = 3;
        ItemStack coal = new ItemStack(Items.COAL, 10);
        int coalTaken = new ContainerOverflow.Bound(chest.bulk, chest).add(coal);
        c.check("container: the host's room rule limits the overflow (3 of 10 accepted, 7 handed back)",
                coalTaken == 3 && coal.getCount() == 7 && chest.bulk.count(Items.COAL) == 3, coalTaken + " taken, " + coal.getCount() + " left");
        chest.bulk.takeAll(new ItemStack(Items.COAL));
        chest.room = Integer.MAX_VALUE;

        // a row that exists only in the overflow is taken by its overflow slot id
        int idx = chest.bulk.size() - 1;
        ItemStack row = chest.bulk.get(idx);
        int slotsFreeBefore = 0;
        for (int i = 0; i < 27; i++) if (chest.getItem(i).isEmpty()) slotsFreeBefore++;
        ActionHandler.handleContainerTake(a, 91, ContainerOverflow.SLOT_BASE + idx, false);
        boolean got = Inv.count(a, s -> ItemStack.isSameItemSameComponents(s, row)) == row.getCount();
        c.check("container: a take of an overflow-only row moves it into the bags", got && chest.bulk.indexOf(row) < 0, "taken " + got);

        // grouped: apples in a slot and in the overflow are one item
        chest.bulk.add(chest.getItem(0).copy()); // the stack in slot 0 moves to the overflow, apples take its place
        chest.setItem(0, new ItemStack(Items.APPLE, 5));
        chest.bulk.add(new ItemStack(Items.APPLE, 20));
        before.merge(BuiltInRegistries.ITEM.getKey(Items.APPLE) + "|null", 25L, Long::sum);
        int packApples = Inv.count(a, Items.APPLE);
        ActionHandler.handleContainerTake(a, 91, 0, false);
        int tookApples = Inv.count(a, Items.APPLE) - packApples;
        c.check("container: one take of a slot row also takes the item's overflow rows (5 in a slot + 20 in the overflow)",
                tookApples == 25 && chest.bulk.indexOf(new ItemStack(Items.APPLE)) < 0, tookApples + " apples taken");
        boolean full = true;
        for (int i = 0; i < 27; i++) full &= !chest.getItem(i).isEmpty();
        c.check("container: a freed slot is refilled from the overflow (hoppers and comparators see it)",
                full || chest.bulk.isEmpty(), "slots full " + full + ", overflow rows " + chest.bulk.size());

        ActionHandler.handleContainerTake(a, 91, -1, true);
        boolean empty = chest.bulk.isEmpty();
        for (int i = 0; i < 27; i++) empty &= chest.getItem(i).isEmpty();
        c.check("container: Take All empties the slots and the overflow", empty, empty ? "empty" : chest.bulk.size() + " rows left");
        c.check("container: the whole content ended up carried, nothing lost or duplicated", count(playerStacks(a)).equals(before),
                diff(before, count(playerStacks(a))));
        a.containerMenu = a.inventoryMenu;
        a.getInventory().clearContent();
        Pack.clear(a);
    }

    // ------------------------------------------------------------------ 3. block entities

    private static void blockEntities(ServerLevel level, PackSelfTest.Checks c) {
        BlockPos pos = testAnchor(level).above(3);
        level.getChunkAt(pos); // make sure the chunk is there for the spill
        ChestBlockEntity chest = new ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
        chest.setLevel(level);
        BulkStore store = ContainerOverflow.store(chest);
        c.check("block entity: a chest carries the overflow capability", store != null, store == null ? "none" : "present");
        c.check("block entity: hoppers are excluded", ContainerOverflow.store(new HopperBlockEntity(pos, Blocks.HOPPER.defaultBlockState())) == null, "hopper");
        if (store == null) return;
        for (int i = 0; i < 40; i++) store.add(distinct(2000 + i));
        store.add(new ItemStack(Items.ARROW, 5000));

        // save like a chunk does and load like a chunk does
        CompoundTag tag = chest.saveWithFullMetadata(level.registryAccess());
        CompoundTag back;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, bos);
            back = NbtIo.readCompressed(new ByteArrayInputStream(bos.toByteArray()), net.minecraft.nbt.NbtAccounter.create(268435456L));
        } catch (IOException e) {
            c.check("block entity: NBT round trip", false, e.toString());
            return;
        }
        BlockEntity re = BlockEntity.loadStatic(pos, Blocks.CHEST.defaultBlockState(), back, level.registryAccess());
        BulkStore loaded = ContainerOverflow.store(re);
        c.check("block entity: the overflow survives a save and a load (server restart)",
                loaded != null && loaded.sameAs(store) && loaded.count(Items.ARROW) == 5000, loaded == null ? "no capability" : loaded.size() + " rows");

        // double chest: both halves share the pair's overflow through the smaller position
        ChestBlockEntity left = new ChestBlockEntity(pos.east(10), Blocks.CHEST.defaultBlockState());
        ChestBlockEntity right = new ChestBlockEntity(pos.east(11), Blocks.CHEST.defaultBlockState());
        left.setLevel(level);
        right.setLevel(level);
        ContainerOverflow.store(left).add(new ItemStack(Items.ARROW, 7));
        ContainerOverflow.store(right).add(new ItemStack(Items.ARROW, 5));
        ContainerOverflow.store(right).add(distinct(3000));
        ContainerOverflow.Bound b1 = ContainerOverflow.of(null, new CompoundContainer(right, left)); // either order
        ContainerOverflow.Bound b2 = ContainerOverflow.of(null, new CompoundContainer(left, right));
        c.check("double chest: the halves are found and share the overflow of the smaller position",
                b1 != null && b2 != null && b1.store() == ContainerOverflow.store(left) && b2.store() == b1.store(),
                b1 == null ? "halves not found" : "primary is the left half");
        c.check("double chest: the other half's overflow was absorbed, nothing stranded",
                b1 != null && b1.store().count(Items.ARROW) == 12 && b1.store().size() == 2 && ContainerOverflow.store(right).isEmpty(),
                b1 == null ? "-" : b1.store().count(Items.ARROW) + " arrows");

        // destruction spills everything; a chunk unload does not
        ChestBlockEntity unloaded = new ChestBlockEntity(pos.north(5), Blocks.CHEST.defaultBlockState());
        unloaded.setLevel(level);
        ContainerOverflow.store(unloaded).add(distinct(4000));
        ContainerOverflow.markUnloading(unloaded); // same attachment stage as the real ChunkEvent.Unload consumer
        unloaded.onChunkUnloaded();
        unloaded.setRemoved();
        ContainerOverflow.removalTick(new net.neoforged.neoforge.event.tick.LevelTickEvent.Post(() -> true, level));
        int unloadedItems = spilled(level, pos.north(5));
        c.check("spill: a chunk unload spills nothing (the overflow is saved with the chunk)", unloadedItems == 0, unloadedItems + " entities");

        ChestBlockEntity broken = new ChestBlockEntity(pos.south(5), Blocks.CHEST.defaultBlockState());
        broken.setLevel(level);
        BulkStore bs = ContainerOverflow.store(broken);
        for (int i = 0; i < 5; i++) bs.add(distinct(5000 + i));
        bs.add(new ItemStack(Items.ARROW, 200)); // 200 arrows = 4 max-size stacks (64, 64, 64, 8)
        int expected = 5 + 4;
        broken.setRemoved();
        // Actual 26.3 removal is confirmed by the public level-post event, never by unload/setRemoved alone.
        ContainerOverflow.removalTick(new net.neoforged.neoforge.event.tick.LevelTickEvent.Post(() -> true, level));
        int dropped = spilled(level, pos.south(5));
        c.check("spill: a destroyed container drops its whole overflow as item entities", dropped == expected && bs.isEmpty(),
                dropped + " entities (expected " + expected + "), overflow rows left " + bs.size());
        sweep(level);
    }

    private static int spilled(ServerLevel level, BlockPos at) {
        int n = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(3))) {
            ItemStack s = e.getItem();
            if ((com.nirncraft.inventory.ItemData.hasCustom(s) && com.nirncraft.inventory.ItemData.custom(s).contains(TAG)) || s.is(Items.ARROW)) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ 4. a death

    private static FakePlayer newPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        FakePlayer p = new FakePlayer(level, profile);
        PackSelfTest.connect(server, p);
        BlockPos at = testAnchor(level);
        p.snapTo(at.getX() + 0.5, at.getY() + 1, at.getZ() + 0.5);
        return p;
    }

    private static void death(MinecraftServer server, ServerLevel level, PackSelfTest.Checks c) {
        // the default drop mode keeps everything but valuables: the test needs a death that drops it all
        com.nirncraft.SkyConfig.DeathDrops oldMode = com.nirncraft.SkyConfig.DEATH_DROPS.get();
        com.nirncraft.SkyConfig.DEATH_DROPS.set(com.nirncraft.SkyConfig.DeathDrops.EVERYTHING);
        try {
            deathBody(server, level, c);
        } finally {
            com.nirncraft.SkyConfig.DEATH_DROPS.set(oldMode);
        }
    }

    private static void deathBody(MinecraftServer server, ServerLevel level, PackSelfTest.Checks c) {
        FakePlayer dead = newPlayer(server, level, PROFILE_D);
        Inventory inv = dead.getInventory();
        // a full inventory, armor, and a pack of 300 distinct items, 1000 arrows and a quest letter
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) inv.setItem(i, distinct(i));
        inv.setItem(36 + (3), new ItemStack(Items.IRON_HELMET));
        for (int i = 100; i < 400; i++) Pack.give(dead, distinct(i));
        Pack.give(dead, new ItemStack(Items.ARROW, 1000));
        ItemStack letter = QuestItems.letter("selftest");
        Pack.give(dead, letter.copy());
        List<ItemStack> carriedBefore = playerStacks(dead);
        Map<String, Long> before = count(carriedBefore);
        long letters = Pack.count(dead, s -> QuestItems.isQuestItem(s));

        // the drops the game would build (Inventory#dropAll), then the real handlers in their real order
        List<ItemEntity> drops = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            drops.add(new ItemEntity(level, dead.getX(), dead.getY(), dead.getZ(), s.copy()));
            inv.setItem(i, ItemStack.EMPTY);
        }
        LivingDropsEvent ev = new LivingDropsEvent(dead, level.damageSources().generic(), drops, false);
        com.nirncraft.combat.DeathHandler.onPlayerDrops(ev);
        PlayerCorpses.onDrops(ev);

        PlayerCorpseEntity corpse = null;
        for (PlayerCorpseEntity e : level.getEntitiesOfClass(PlayerCorpseEntity.class, new AABB(dead.blockPosition()).inflate(8))) {
            if (dead.getUUID().equals(e.owner())) corpse = e;
        }
        c.check("death: the whole inventory and pack went into one body", corpse != null && ev.getDrops().isEmpty(),
                corpse == null ? "no corpse" : ev.getDrops().size() + " item entities left on the ground");
        if (corpse == null) return;
        Map<String, Long> inBody = count(corpse.contents());
        Map<String, Long> expectedBody = new TreeMap<>(before);
        expectedBody.remove(BuiltInRegistries.ITEM.getKey(letter.getItem()) + "|" + com.nirncraft.inventory.ItemData.custom(letter));
        c.check("death: the body holds every item (" + total(inBody) + ") except the quest item, which stays with the player",
                inBody.equals(expectedBody) && Pack.count(dead, s -> QuestItems.isQuestItem(s)) == letters,
                diff(expectedBody, inBody));
        c.check("death: slots and overflow are both used (54 slots, the rest in the bulk store)",
                corpse.overflow().size() > 200 && corpse.hasItems(), corpse.overflow().size() + " overflow rows");

        // the body saved with the chunk and loaded again (server restart / chunk unload)
        CompoundTag tag = new CompoundTag();
        tag.merge(com.nirncraft.platform.EntityData.saveWithoutId(corpse));
        PlayerCorpseEntity reloaded = SurvivalRegistry.PLAYER_CORPSE.get().create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, bos);
            com.nirncraft.platform.EntityData.load(reloaded, NbtIo.readCompressed(new ByteArrayInputStream(bos.toByteArray()), net.minecraft.nbt.NbtAccounter.create(268435456L)));
        } catch (IOException e) {
            c.check("death: body NBT round trip", false, e.toString());
            return;
        }
        c.check("death: the body keeps everything through a save and a load, owner included",
                count(reloaded.contents()).equals(inBody) && dead.getUUID().equals(reloaded.owner()),
                diff(inBody, count(reloaded.contents())));

        // respawn: the clone copies the player's data (the letter), then the owner loots the reloaded body
        net.minecraft.server.level.ServerPlayer respawned = PackSelfTest.menuPlayer(server, level, PROFILE_D);
        respawned.snapTo(dead.getX(), dead.getY(), dead.getZ());
        boolean cloned = true;
        try {
            NeoForge.EVENT_BUS.post(new PlayerEvent.Clone(respawned, dead, true));
        } catch (RuntimeException e) {
            cloned = false;
            NirnCraft.LOGGER.error("Clone event threw in the selftest", e);
        }
        c.check("death: the respawn clone keeps the quest item in the pack", cloned && Pack.count(respawned, s -> QuestItems.isQuestItem(s)) == letters,
                "letters " + Pack.count(respawned, s -> QuestItems.isQuestItem(s)));
        reloaded.snapTo(respawned.getX(), respawned.getY(), respawned.getZ());
        reloaded.interact(respawned, InteractionHand.MAIN_HAND, net.minecraft.world.phys.Vec3.ZERO);
        boolean opened = respawned.containerMenu != respawned.inventoryMenu;
        c.check("death: the owner can open the reloaded body", opened, opened ? "menu open" : "refused");
        if (opened) {
            ActionHandler.handleContainerTake(respawned, respawned.containerMenu.containerId, -1, true);
            Map<String, Long> got = count(playerStacks(respawned));
            c.check("death: looting the reloaded body returns every item exactly (slots, pack, armor)", got.equals(before) && !reloaded.hasItems(),
                    diff(before, got) + ", body still holds items " + reloaded.hasItems());
            respawned.containerMenu = respawned.inventoryMenu;
        }
        // clean up: the first body still holds its items; discarding it spills them as tagged item entities
        sweep(level);
    }

    // ------------------------------------------------------------------ 5. a player saved to a file

    private static void playerFile(MinecraftServer server, ServerLevel level, PackSelfTest.Checks c) {
        FakePlayer p = newPlayer(server, level, PROFILE_E);
        Inventory inv = p.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) inv.setItem(i, distinct(i));
        for (int i = 100; i < 160; i++) Pack.give(p, distinct(i));
        Pack.give(p, new ItemStack(Items.ARROW, 4321));
        Pack.give(p, QuestItems.letter("file"));
        int fav = Pack.find(p, s -> com.nirncraft.inventory.ItemData.hasCustom(s) && com.nirncraft.inventory.ItemData.custom(s).getIntOr(TAG, 0) == 130);
        Pack.setFavorite(p, Pack.get(p, fav), true);
        BulkStore ender = ContainerOverflow.enderStore(p);
        ender.add(new ItemStack(Items.ARROW, 99999));
        ender.add(distinct(9000));
        List<ItemStack> before = playerStacks(p);
        Map<String, Long> countBefore = count(before);

        File file = null;
        try {
            file = Files.createTempFile("nirncraft-pack-selftest", ".dat").toFile();
            CompoundTag tag = new CompoundTag();
            tag.merge(com.nirncraft.platform.EntityData.saveWithoutId(p));
            NbtIo.writeCompressed(tag, file.toPath());
            CompoundTag back = NbtIo.readCompressed(file.toPath(), net.minecraft.nbt.NbtAccounter.create(268435456L));
            FakePlayer q = newPlayer(server, level, PROFILE_E);
            com.nirncraft.platform.EntityData.load(q, back);
            Map<String, Long> countAfter = count(playerStacks(q));
            c.check("file: a player saved to disk and loaded keeps every count (slots, pack, armor)", countAfter.equals(countBefore),
                    diff(countBefore, countAfter) + ", file " + file.length() + " bytes");
            c.check("file: the quest item survives", Pack.count(q, s -> QuestItems.isQuestItem(s)) == 1, "letters " + Pack.count(q, s -> QuestItems.isQuestItem(s)));
            int fav2 = Pack.find(q, s -> com.nirncraft.inventory.ItemData.hasCustom(s) && com.nirncraft.inventory.ItemData.custom(s).getIntOr(TAG, 0) == 130);
            c.check("file: favorites survive", fav2 >= 0 && InvCategory.isFavorite(Pack.get(q, fav2)), "favorite " + (fav2 >= 0 && InvCategory.isFavorite(Pack.get(q, fav2))));
            BulkStore ender2 = ContainerOverflow.enderStore(q);
            c.check("file: the ender chest's overflow survives", ender2.count(Items.ARROW) == 99999 && ender2.size() == 2, ender2.size() + " rows");
            q.getInventory().clearContent();
            Pack.clear(q);
        } catch (IOException e) {
            c.check("file: player NBT file I/O", false, e.toString());
        } finally {
            if (file != null) file.delete();
            p.getInventory().clearContent();
            Pack.clear(p);
            SkyData.get(p).module(ContainerOverflow.ENDER_MODULE).remove(BulkStore.LIST);
        }
    }

    // ------------------------------------------------------------------ 6. the facade

    private static void facade(MinecraftServer server, ServerLevel level, FakePlayer a, PackSelfTest.Checks c) {
        a.getInventory().clearContent();
        Pack.clear(a);
        Inventory inv = a.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) inv.setItem(i, distinct(i));
        Pack.give(a, new ItemStack(Items.ARROW, 1000));
        Pack.give(a, new ItemStack(Items.IRON_INGOT, 30));
        c.check("facade: count sees pack items immediately (nothing refills slots first)",
                Inv.count(a, Items.ARROW) == 1000 && Inv.has(a, Items.IRON_INGOT, 30) && !Inv.has(a, Items.IRON_INGOT, 31)
                        && countSlots(inv, Items.ARROW) == 0, Inv.count(a, Items.ARROW) + " arrows, " + Inv.count(a, Items.IRON_INGOT) + " ingots");
        c.check("facade: find returns pack entries", Inv.find(a, s -> s.is(Items.IRON_INGOT)).getCount() == 30, "ingots");
        int removed = Inv.take(a, Items.IRON_INGOT, 12);
        c.check("facade: take consumes from the pack", removed == 12 && Inv.count(a, Items.IRON_INGOT) == 18, Inv.count(a, Items.IRON_INGOT) + " left");
        int tooMany = Inv.take(a, Items.IRON_INGOT, 100);
        c.check("facade: take never takes more than carried", tooMany == 18 && Inv.count(a, Items.IRON_INGOT) == 0, tooMany + " taken");
        int[] seen = {0};
        Inv.forEach(a, s -> {
            if (s.is(Items.ARROW)) seen[0] += s.getCount();
        });
        c.check("facade: forEach visits pack entries", seen[0] == 1000, seen[0] + " arrows");

        // vanilla's own getProjectile cannot see the pack: the ammo hook brings an arrow into a slot
        inv.setItem(inv.getSelectedSlot(), new ItemStack(Items.BOW));
        boolean vanillaBlind = a.getProjectile(a.getMainHandItem()).isEmpty();
        boolean brought = Ammo.ensure(a, a.getMainHandItem());
        c.check("ammo: a bow finds arrows that only exist in the pack", vanillaBlind && brought && !a.getProjectile(a.getMainHandItem()).isEmpty(),
                "vanilla blind " + vanillaBlind + ", materialised " + brought);
        c.check("ammo: nothing was lost bringing the arrows over", Inv.count(a, Items.ARROW) == 1000, Inv.count(a, Items.ARROW) + " arrows");
        a.getInventory().clearContent();
        Pack.clear(a);
    }

    // ------------------------------------------------------------------ 7. the stragglers routed through the facade

    private static ItemStack light(int i, int count) {
        ItemStack s = new ItemStack(Items.STICK, count);
        com.nirncraft.inventory.ItemData.updateCustom(s, customData263 -> customData263.putInt(TAG, i));
        com.nirncraft.inventory.ItemData.updateCustom(s, customData263 -> customData263.putFloat(ItemWeights.WEIGHT_NBT, 0.01f));
        return s;
    }

    private static void stragglers(ServerLevel level, FakePlayer a, PackSelfTest.Checks c) {
        a.getInventory().clearContent();
        Pack.clear(a);
        Inventory inv = a.getInventory();

        // magic: Transmute reads the pack through Inv, like every other rule
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) inv.setItem(i, distinct(i));
        Pack.give(a, new ItemStack(Items.IRON_INGOT, 5));
        Pack.give(a, new ItemStack(Items.RAW_IRON, 2));
        boolean slotsBlind = countSlots(inv, Items.IRON_INGOT) == 0;
        boolean did = com.nirncraft.magic.spell.SpellEffects.transmuteOne(a);
        c.check("transmute: iron that exists only in the pack is transmuted (ingots first), one piece per cast",
                slotsBlind && did && Inv.count(a, Items.IRON_INGOT) == 4 && Inv.count(a, Items.GOLD_INGOT) == 1 && Inv.count(a, Items.RAW_IRON) == 2,
                Inv.count(a, Items.IRON_INGOT) + " iron, " + Inv.count(a, Items.GOLD_INGOT) + " gold");
        for (int i = 0; i < 4; i++) com.nirncraft.magic.spell.SpellEffects.transmuteOne(a);
        boolean raw = com.nirncraft.magic.spell.SpellEffects.transmuteOne(a);
        c.check("transmute: ingots are used up, then raw iron; nothing else is touched, none left means false",
                raw && Inv.count(a, Items.IRON_INGOT) == 0 && Inv.count(a, Items.RAW_IRON) == 1 && Inv.count(a, Items.RAW_GOLD) == 1
                        && Inv.count(a, Items.GOLD_INGOT) == 5, Inv.count(a, Items.RAW_IRON) + " raw iron left");
        Inv.take(a, Items.RAW_IRON, 10);
        c.check("transmute: nothing to transmute returns false", !com.nirncraft.magic.spell.SpellEffects.transmuteOne(a), "");
        a.getInventory().clearContent();
        Pack.clear(a);

        // arrows for a crossbow come from the pack too (ProjectileWeaponItem, not only bows)
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) inv.setItem(i, distinct(i));
        inv.setItem(inv.getSelectedSlot(), new ItemStack(Items.CROSSBOW));
        Pack.give(a, new ItemStack(Items.ARROW, 300));
        boolean blind = a.getProjectile(a.getMainHandItem()).isEmpty();
        boolean brought = Ammo.ensure(a, a.getMainHandItem());
        c.check("ammo: a crossbow finds arrows that only exist in the pack, none lost",
                blind && brought && !a.getProjectile(a.getMainHandItem()).isEmpty() && Inv.count(a, Items.ARROW) == 300,
                "blind " + blind + ", brought " + brought + ", " + Inv.count(a, Items.ARROW) + " arrows");
        a.getInventory().clearContent();
        Pack.clear(a);

        // followers: 27 slots and an overflow behind them, weight-limited, persisted with the NPC, never lost
        BlockPos at = testAnchor(level).above(2);
        net.minecraft.world.entity.LivingEntity npc = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
        if (npc == null) {
            c.check("follower bags: a test entity", false, "zombie not created");
            return;
        }
        npc.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        SimpleContainer bags = com.nirncraft.society.Followers.getFollowerInventory(npc);
        c.check("follower bags: the trade container has an overflow behind its 27 slots", bags instanceof OverflowHost, bags.getClass().getSimpleName());
        if (!(bags instanceof OverflowHost host)) return;
        for (int i = 0; i < 27; i++) bags.setItem(i, light(i, 1));
        ContainerOverflow.Bound bound = new ContainerOverflow.Bound(host.overflow(), bags);
        int added = 0;
        for (int i = 0; i < 40; i++) added += bound.add(light(100 + i, 3));
        c.check("follower bags: what the 27 slots cannot hold goes to the overflow (40 more kinds)",
                added == 120 && host.overflow().size() == 40, added + " items, " + host.overflow().size() + " rows");
        ItemStack heavy = light(999, 1);
        com.nirncraft.inventory.ItemData.updateCustom(heavy, customData263 -> customData263.putFloat(ItemWeights.WEIGHT_NBT, com.nirncraft.society.Followers.CARRY_KG * 2));
        c.check("follower bags: an item heavier than the follower can carry is refused, not stored",
                host.overflowRoom(heavy) == 0 && bound.add(heavy) == 0 && heavy.getCount() == 1, "room " + host.overflowRoom(heavy));
        com.nirncraft.society.Followers.saveFollowerInventory(npc, bags);
        CompoundTag pd = npc.getPersistentData().copy();
        SimpleContainer back = null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(pd, bos);
            net.minecraft.world.entity.LivingEntity npc2 = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            npc2.getPersistentData().merge(NbtIo.readCompressed(new ByteArrayInputStream(bos.toByteArray()), net.minecraft.nbt.NbtAccounter.create(268435456L)));
            back = com.nirncraft.society.Followers.getFollowerInventory(npc2);
        } catch (IOException e) {
            c.check("follower bags: NBT round trip", false, e.toString());
        }
        boolean same = back instanceof OverflowHost bh && bh.overflow().sameAs(host.overflow());
        for (int i = 0; back != null && i < 27; i++) same &= ItemStack.matches(back.getItem(i), bags.getItem(i));
        c.check("follower bags: slots and overflow survive saving the NPC and loading it (a restart)", same, back == null ? "no read" : "compared");

        // Keep earlier drops nearby deliberately: reused live test locations can contain tagged sticks
        // from a prior run. Count only this synchronous death's increase, just as queued cargo is scoped.
        npc.spawnAtLocation(level, light(100, 64));
        npc.spawnAtLocation(level, light(101, 56));
        long oldDropped = followerDrops(level, at);
        // death: everything falls where it stood (slots as drops, the overflow as persistent items)
        var oldCargo = DeferredSpills.get(level).cargoIds();
        com.nirncraft.society.Followers.onDeath(npc);
        long dropped = followerDrops(level, at) - oldDropped;
        long queued = DeferredSpills.get(level).pendingAfter(oldCargo);
        c.check("follower bags: death conserves slots plus bounded queued overflow (27 + 120 items), the record is cleared",
                oldDropped >= 120 && dropped + queued == 27 + 120 && !npc.getPersistentData().contains(com.nirncraft.society.Followers.INVENTORY_KEY)
                        && !npc.getPersistentData().contains(com.nirncraft.society.Followers.OVERFLOW_KEY), dropped + " new dropped, " + queued + " queued, " + oldDropped + " pre-existing ignored");

        // restock: the overflow counts as inside the container (a player's things are never wiped)
        BlockPos cp = testAnchor(level).above(5);
        ChestBlockEntity chest = new ChestBlockEntity(cp, Blocks.CHEST.defaultBlockState());
        chest.setLevel(level);
        CompoundTag entry = new CompoundTag();
        ListTag gen = new ListTag();
        CompoundTag row = new CompoundTag();
        row.putString("id", "minecraft:apple");
        row.putInt("n", 3);
        gen.add(row);
        entry.put("gen", gen);
        chest.setItem(0, new ItemStack(Items.APPLE, 3));
        BulkStore ov = ContainerOverflow.store(chest);
        c.check("restock: a chest holding only what the table rolled restocks", ov != null && !com.nirncraft.loot.Restock.holdsForeignItems(entry, chest), "");
        if (ov != null) {
            ov.add(new ItemStack(Items.DIAMOND, 2));
            c.check("restock: an item the player stored in the overflow keeps the container from restocking",
                    com.nirncraft.loot.Restock.holdsForeignItems(entry, chest), "");
            ov.clear();
            ov.add(new ItemStack(Items.APPLE, 1));
            c.check("restock: more of a rolled item than the table rolled (slot + overflow) is the player's",
                    com.nirncraft.loot.Restock.holdsForeignItems(entry, chest), "");
        }
        sweep(level);
    }

    private static long followerDrops(ServerLevel level, BlockPos at) {
        long count = 0;
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(at).inflate(8))) {
            ItemStack stack = e.getItem();
            if (stack.is(Items.STICK) && com.nirncraft.inventory.ItemData.hasCustom(stack) && com.nirncraft.inventory.ItemData.custom(stack).contains(TAG)) count += stack.getCount();
        }
        return count;
    }

    private static void deferredSpills(ServerLevel level, FakePlayer player, PackSelfTest.Checks c) {
        try {
        BlockPos pos = testAnchor(level).above(3);
        var worldQueue = DeferredSpills.get(level);
        var preserved = worldQueue.cargoIds();
        BulkStore source = new BulkStore(4, 5_000_000, null);
        ItemStack arrows = new ItemStack(Items.ARROW, 3_000_000);
        com.nirncraft.inventory.ItemData.updateCustom(arrows, customData263 -> customData263.putInt(TAG, 999_001));
        source.add(arrows);
        ContainerOverflow.spill(level, pos, source);
        long physical = 0; int entities = 0;
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(8))) {
            ItemStack item = entity.getItem();
            if (com.nirncraft.inventory.ItemData.hasCustom(item) && com.nirncraft.inventory.ItemData.custom(item).getIntOr(TAG, 0) == 999_001) { physical += item.getCount(); entities++; }
        }
        long queued = worldQueue.pendingAfter(preserved);
        c.check("spill backlog: 3 million actual items conserve counts with at most64 new entities",
                source.isEmpty() && physical + queued == 3_000_000 && entities <= DeferredSpills.SPAWNS_PER_TICK,
                physical + " physical + " + queued + " queued, " + entities + " entities");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            NbtIo.writeCompressed(worldQueue.save(new CompoundTag()), bytes);
            DeferredSpills restored = DeferredSpills.load(NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray()), net.minecraft.nbt.NbtAccounter.create(268435456L)));
            c.check("spill backlog: real serialized SavedData conserves the million-item remainder",
                    restored.pendingAfter(preserved) == queued, queued + " queued, " + bytes.size() + " compressed bytes");
        } catch (IOException ex) { c.check("spill backlog: SavedData serialization", false, ex.toString()); }

        // Real spawn rejection, scoped to one marker; use isolated queue state so fixture budget is independent.
        DeferredSpills isolated = new DeferredSpills();
        BulkStore denied = new BulkStore(4, 5_000_000, null);
        ItemStack deniedStack = new ItemStack(Items.ARROW, 2_000_000);
        com.nirncraft.inventory.ItemData.updateCustom(deniedStack, customData263 -> customData263.putInt(TAG, 999_002)); denied.add(deniedStack);
        var cargo = isolated.retain(pos, denied.save());
        denied.clear();
        SpillSpawnRejector rejector = new SpillSpawnRejector();
        NeoForge.EVENT_BUS.register(rejector);
        try {
            isolated.deliver(level, cargo);
            c.check("spill backlog: rejected real entity spawn retains all2 million items",
                    rejector.observed && isolated.pendingItems() == 2_000_000 && isolated.outstandingItems() == 0,
                    "rejected " + rejector.observed + ", pending " + isolated.pendingItems());
        } finally { NeoForge.EVENT_BUS.unregister(rejector); }

        Pack.clear(player);
        ItemStack packed = new ItemStack(Items.ARROW, Pack.MAX_PER_ENTRY);
        com.nirncraft.inventory.ItemData.updateCustom(packed, customData263 -> customData263.putInt(TAG, 999_002)); Pack.add(player, packed);
        long before = isolated.pendingItems();
        long accepted = cargo.deliver(64, (identity, count) -> {
            ItemStack attempt = com.nirncraft.inventory.ItemData.parse(identity); attempt.setCount(count);
            return Pack.add(player, attempt);
        });
        c.check("spill backlog: actual full bulk recipient leaves every item queued",
                accepted == 0 && isolated.pendingItems() == before,
                accepted + " accepted, " + isolated.pendingItems() + " pending");
        } finally { Pack.clear(player); sweep(level); }
    }

    public static final class SpillSpawnRejector {
        boolean observed;
        @net.neoforged.bus.api.SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGHEST)
        public void reject(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
            if (event.getEntity() instanceof ItemEntity item && com.nirncraft.inventory.ItemData.hasCustom(item.getItem())
                    && com.nirncraft.inventory.ItemData.custom(item.getItem()).getIntOr(TAG, 0) == 999_002) {
                observed = true;
                event.setCanceled(true);
            }
        }
    }

    private static int countSlots(Inventory inv, Item item) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(item)) n += inv.getItem(i).getCount();
        return n;
    }
}
