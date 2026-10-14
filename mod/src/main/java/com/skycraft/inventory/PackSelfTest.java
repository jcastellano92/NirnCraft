package com.skycraft.inventory;

import com.mojang.authlib.GameProfile;
import com.skycraft.Skycraft;
import com.skycraft.core.SkyData;
import com.skycraft.perf.PerfAutorun;
import com.skycraft.quest.QuestItems;
import com.skycraft.vitals.ActionHandler;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.common.util.FakePlayer;
import io.netty.buffer.Unpooled;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Headless self-test of the overflow pack and what hangs on it: the real {@code Player#saveWithoutId} /
 * {@code Entity#load} path (with the capability data), the packet encoding the client mirror uses, a full container
 * taken into a full inventory, actions on virtual slots, and (in {@code economy.BarterSelfTest}) selling: quest items
 * refused, equipped rows confirmed, buy-back. Runs as {@code /skycraft-inventory selftest} and as the autorun task
 * {@code inventory}; the log line is {@code Inventory selftest: n/m checks passed, k problems}.
 */
public final class PackSelfTest {
    private static final GameProfile PROFILE_A = new GameProfile(UUID.nameUUIDFromBytes("skycraft-pack-selftest-a".getBytes()), "[PackTestA]");
    private static final GameProfile PROFILE_B = new GameProfile(UUID.nameUUIDFromBytes("skycraft-pack-selftest-b".getBytes()), "[PackTestB]");
    private static final GameProfile PROFILE_C = new GameProfile(UUID.nameUUIDFromBytes("skycraft-pack-selftest-c".getBytes()), "[PackTestC]");

    private PackSelfTest() {}

    /** Collects checks. */
    public static final class Checks {
        public final List<String> lines = new ArrayList<>();
        public final List<String> failed = new ArrayList<>();
        public int passed, total;

        public void check(String name, boolean ok, String detail) {
            total++;
            if (ok) passed++;
            else failed.add(name);
            lines.add((ok ? "ok   " : "FAIL ") + name + ": " + detail);
        }
    }

    public record Report(int passed, int total, List<String> lines, List<String> failed) {
        public int problems() {
            return total - passed;
        }

        public String summary() {
            return "Inventory selftest: " + passed + "/" + total + " checks passed, " + problems() + " problems"
                    + (failed.isEmpty() ? "" : " (" + String.join("; ", failed) + ")");
        }
    }

    public static void registerAutorun() {
        PerfAutorun.register("inventory", (t, done) -> {
            Report r;
            try {
                r = run(t.server());
            } catch (RuntimeException e) {
                Skycraft.LOGGER.error("Inventory selftest crashed", e);
                r = new Report(0, 1, List.of(), List.of("crashed: " + e));
            }
            for (String l : r.lines()) Skycraft.LOGGER.info("[inventory] {}", l);
            Skycraft.LOGGER.info("{}", r.summary());
            done.run();
        });
    }

    public static Report run(MinecraftServer server) {
        Checks c = new Checks();
        ServerLevel level = server.overworld();
        FakePlayer a = new FakePlayer(level, PROFILE_A);
        connect(server, a);
        try {
            roundTrip(server, level, a, c);
            virtualSlots(level, a, c);
            containerTake(a, c);
            grouping(a, c);
            try {
                com.skycraft.economy.BarterSelfTest.run(server, level, a, c);
            } catch (RuntimeException e) {
                Skycraft.LOGGER.error("Barter selftest threw", e);
                c.check("barter selftest ran to the end", false, e.toString());
            }
        } catch (RuntimeException e) {
            Skycraft.LOGGER.error("Inventory selftest threw", e);
            c.check("selftest ran to the end", false, e.toString());
        } finally {
            a.getInventory().clearContent();
            Pack.clear(a);
            a.containerMenu = a.inventoryMenu;
            sweepDrops(level, a);
        }
        return new Report(c.passed, c.total, c.lines, c.failed);
    }

    /** A fake player has no connection: give it one that queues packets, so every handler may message it. */
    public static void connect(MinecraftServer server, ServerPlayer fake) {
        if (fake.connection == null) new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), fake);
    }

    private static void sweepDrops(ServerLevel level, ServerPlayer p) {
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(p.blockPosition()).inflate(16))) {
            if (e.getItem().hasTag() && (e.getItem().getTag().contains("pack_test") || e.getItem().getTag().contains("slot_t")
                    || e.getItem().getTag().contains("chest_t"))) e.discard();
            else if (e.getItem().is(Items.ARROW)) e.discard();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** item+NBT -> total count over slots, armor, offhand and the pack: the player's whole carried content. */
    public static Map<String, Integer> snapshot(ServerPlayer p) {
        Map<String, Integer> m = new TreeMap<>();
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) add(m, inv.getItem(i));
        for (ItemStack s : Pack.entries(p)) add(m, s);
        return m;
    }

    private static void add(Map<String, Integer> m, ItemStack s) {
        if (s.isEmpty()) return;
        m.merge(BuiltInRegistries.ITEM.getKey(s.getItem()) + "|" + s.getTag(), s.getCount(), Integer::sum);
    }

    private static long total(Map<String, Integer> m) {
        long n = 0;
        for (int v : m.values()) n += v;
        return n;
    }

    private static String diff(Map<String, Integer> a, Map<String, Integer> b) {
        if (a.equals(b)) return "identical (" + a.size() + " kinds, " + total(a) + " items)";
        for (Map.Entry<String, Integer> e : a.entrySet()) {
            if (!e.getValue().equals(b.get(e.getKey()))) return "first difference at " + e.getKey() + ": " + e.getValue() + " vs " + b.get(e.getKey());
        }
        return "different key sets: " + a.size() + " vs " + b.size();
    }

    private static int slotsUsed(Inventory inv) {
        int n = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) if (!inv.getItem(i).isEmpty()) n++;
        return n;
    }

    private static ItemStack distinct(int i, String tag) {
        Item[] kinds = {Items.IRON_SWORD, Items.DIAMOND_PICKAXE, Items.BOW, Items.SHIELD, Items.LEATHER_HELMET, Items.POTION};
        ItemStack s = new ItemStack(kinds[i % kinds.length]);
        s.getOrCreateTag().putInt(tag, i); // a different NBT each: like 300 differently enchanted weapons, never merged
        return s;
    }

    // ------------------------------------------------------------------ checks

    /** Fill 36 slots, add 300 more distinct items and 1000 arrows; weight, save, reload, wire format, refill. */
    private static void roundTrip(MinecraftServer server, ServerLevel level, FakePlayer a, Checks c) {
        a.getInventory().clearContent();
        Pack.clear(a);
        List<ItemStack> given = new ArrayList<>();
        int refused = 0;
        for (int i = 0; i < 336; i++) {
            ItemStack s = distinct(i, "pack_test");
            given.add(s.copy());
            if (!Pack.give(a, s).isEmpty()) refused++;
        }
        ItemStack arrows = new ItemStack(Items.ARROW, 1000);
        given.add(arrows.copy());
        if (!Pack.give(a, arrows).isEmpty()) refused++;
        Inventory inv = a.getInventory();
        c.check("pack: 336 distinct items and 1000 arrows are all accepted", refused == 0, refused + " refused");
        c.check("pack: the slots are full and the rest overflowed", slotsUsed(inv) >= 35 && Pack.size(a) >= 300,
                slotsUsed(inv) + " slots used, " + Pack.size(a) + " pack entries");
        float expected = 0f;
        for (ItemStack s : given) expected += ItemWeights.getStack(s);
        float have = CarryWeight.current(a);
        c.check("pack: carry weight is the sum of everything (slots + pack)", Math.abs(have - expected) < 0.05f * Math.max(1f, expected / 100f),
                "expected " + expected + ", CarryWeight.current " + have + ", capacity " + CarryWeight.capacity(a));
        Map<String, Integer> before = snapshot(a);
        c.check("pack: nothing lost or merged on the way in", total(before) == 336 + 1000, total(before) + " items");
        c.check("pack: the arrow count is an int, not a byte", Pack.count(a, Items.ARROW) + countSlots(inv, Items.ARROW) == 1000,
                (Pack.count(a, Items.ARROW) + countSlots(inv, Items.ARROW)) + " arrows");

        // the real player save/load path (capability data included), through compressed NBT bytes
        CompoundTag tag = new CompoundTag();
        a.saveWithoutId(tag);
        CompoundTag back;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, bos);
            back = NbtIo.readCompressed(new ByteArrayInputStream(bos.toByteArray()));
            c.check("pack: the player's NBT is " + bos.size() + " bytes compressed", bos.size() > 0 && tag.contains("ForgeCaps"),
                    "ForgeCaps present: " + tag.contains("ForgeCaps"));
        } catch (IOException e) {
            c.check("pack: player NBT serialises", false, e.toString());
            return;
        }
        FakePlayer b = new FakePlayer(level, PROFILE_B);
        connect(server, b);
        b.load(back);
        Map<String, Integer> after = snapshot(b);
        c.check("pack: save and reload through Player#saveWithoutId / Entity#load keeps every count", before.equals(after), diff(before, after));
        c.check("pack: the reloaded pack weighs the same", Math.abs(CarryWeight.current(b) - have) < 0.01f,
                CarryWeight.current(b) + " vs " + have);
        b.getInventory().clearContent();
        Pack.clear(b);

        // the wire format of the client mirror: chunked packets, encoded and decoded like the network does
        FakePlayer m = new FakePlayer(level, PROFILE_C);
        List<InventoryPackets.PackSync> packets = Pack.syncPackets(a);
        for (InventoryPackets.PackSync p : packets) {
            FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
            InventoryPackets.PackSync.encode(p, buf);
            InventoryPackets.PackSync q = InventoryPackets.PackSync.decode(buf);
            Pack.applyClientSync(m, q.start(), q.total(), q.rows());
        }
        Map<String, Integer> mirror = new TreeMap<>();
        for (ItemStack s : Pack.entries(m)) add(mirror, s);
        Map<String, Integer> packOnly = new TreeMap<>();
        for (ItemStack s : Pack.entries(a)) add(packOnly, s);
        c.check("pack: the client mirror built from " + packets.size() + " packets equals the server pack", mirror.equals(packOnly),
                diff(packOnly, mirror));
        c.check("pack: client and server compute the same pack weight", Math.abs(Pack.weight(m) - Pack.weight(a)) < 0.01f,
                Pack.weight(m) + " vs " + Pack.weight(a));

        // refill: a freed slot is filled from the pack on the tick
        int packBefore = Pack.size(a);
        inv.setItem(9, ItemStack.EMPTY);
        Pack.refill(a);
        c.check("pack: a freed slot is refilled from the pack", !inv.getItem(9).isEmpty() && Pack.size(a) == packBefore - 1,
                "slot 9 " + inv.getItem(9).isEmpty() + ", pack " + packBefore + " -> " + Pack.size(a));
        // that slot's stack left the player's content: restore the invariant for the next checks
        a.getInventory().clearContent();
        Pack.clear(a);
    }

    private static int countSlots(Inventory inv, Item item) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) if (inv.getItem(i).is(item)) n += inv.getItem(i).getCount();
        return n;
    }

    /** Actions on pack rows (virtual slots): nothing is lost, favorite and drop act on the pack, quest items stay. */
    private static void virtualSlots(ServerLevel level, FakePlayer a, Checks c) {
        a.getInventory().clearContent();
        Pack.clear(a);
        Inventory inv = a.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) inv.setItem(i, distinct(i, "slot_t"));
        ItemStack arrows = new ItemStack(Items.ARROW, 300);
        Pack.give(a, arrows);
        ItemStack letter = QuestItems.letter("selftest");
        Pack.give(a, letter);
        c.check("virtual: arrows and a quest letter overflow into the pack", Pack.count(a, Items.ARROW) == 300 && Pack.size(a) == 2,
                Pack.size(a) + " entries");
        int arrowIndex = Pack.find(a, s -> s.is(Items.ARROW));
        ResourceLocation arrowId = BuiltInRegistries.ITEM.getKey(Items.ARROW);
        Map<String, Integer> before = snapshot(a);

        InventoryActions.handle(a, InventoryPackets.InvAction.DROP_ONE, Pack.VIRTUAL_BASE + arrowIndex, arrowId);
        c.check("virtual: dropping one arrow from a pack row takes one", Pack.count(a, Items.ARROW) == 299, Pack.count(a, Items.ARROW) + " arrows");
        sweepDrops(level, a);

        int letterIndex = Pack.find(a, s -> QuestItems.isQuestItem(s));
        ResourceLocation letterId = BuiltInRegistries.ITEM.getKey(QuestItems.COURIER_LETTER.get());
        InventoryActions.handle(a, InventoryPackets.InvAction.DROP_ALL, Pack.VIRTUAL_BASE + letterIndex, letterId);
        c.check("virtual: a quest item in the pack is not dropped", Pack.find(a, s -> QuestItems.isQuestItem(s)) >= 0, "still in the pack");

        arrowIndex = Pack.find(a, s -> s.is(Items.ARROW));
        InventoryActions.handle(a, InventoryPackets.InvAction.FAVORITE, Pack.VIRTUAL_BASE + arrowIndex, arrowId);
        arrowIndex = Pack.find(a, s -> s.is(Items.ARROW));
        c.check("virtual: favorite marks the pack row", arrowIndex >= 0 && InvCategory.isFavorite(Pack.get(a, arrowIndex))
                && Pack.count(a, Items.ARROW) == 299, "arrows " + Pack.count(a, Items.ARROW));

        Map<String, Integer> mid = snapshot(a);
        int idx0 = Pack.find(a, s -> !QuestItems.isQuestItem(s));
        int real = Pack.materialize(a, idx0, null);
        c.check("virtual: a pack entry is pulled into a slot when all 36 are full (the displaced stack goes to the pack)",
                real >= 0 && real < Inventory.INVENTORY_SIZE && !inv.getItem(real).isEmpty(), "slot " + real);
        c.check("virtual: pulling a pack entry loses and duplicates nothing", snapshot(a).equals(mid), diff(mid, snapshot(a)));
        c.check("virtual: the overall content only changed by the dropped arrow", total(before) - 1 == total(mid), total(before) + " -> " + total(mid));
        a.getInventory().clearContent();
        Pack.clear(a);
    }

    /** Grouped takes (five apples in three slots are one click) and graded encumbrance. */
    private static void grouping(FakePlayer a, Checks c) {
        a.getInventory().clearContent();
        Pack.clear(a);
        Inventory inv = a.getInventory();
        SimpleContainer chest = new SimpleContainer(27);
        chest.setItem(2, new ItemStack(Items.APPLE, 2));
        chest.setItem(5, new ItemStack(Items.BREAD, 1));
        chest.setItem(9, new ItemStack(Items.APPLE, 2));
        chest.setItem(20, new ItemStack(Items.APPLE, 1));
        ChestMenu menu = new ChestMenu(MenuType.GENERIC_9x3, 78, inv, chest, 3);
        a.containerMenu = menu;
        ActionHandler.handleContainerTake(a, 78, 9, false); // one click on one of the three apple slots
        int apples = 0;
        for (int i = 0; i < 27; i++) if (chest.getItem(i).is(Items.APPLE)) apples += chest.getItem(i).getCount();
        c.check("grouping: one take request takes every apple stack in the container", apples == 0 && countSlots(inv, Items.APPLE) + Pack.count(a, Items.APPLE) == 5
                && chest.getItem(5).is(Items.BREAD), apples + " apples left, " + (countSlots(inv, Items.APPLE) + Pack.count(a, Items.APPLE)) + " taken");
        // and back: one store request stores all apples (slots and pack)
        ActionHandler.handleContainerStore(a, 78, findSlot(inv, Items.APPLE), false);
        int stored = 0;
        for (int i = 0; i < 27; i++) if (chest.getItem(i).is(Items.APPLE)) stored += chest.getItem(i).getCount();
        c.check("grouping: one store request stores every stack of that item", stored == 5 && countSlots(inv, Items.APPLE) == 0, stored + " stored");
        a.containerMenu = a.inventoryMenu;
        a.getInventory().clearContent();
        Pack.clear(a);

        c.check("encumbrance: no slowdown at or under capacity", CarryWeight.speedFactor(300f, 300f) == 1f && CarryWeight.speedFactor(10f, 300f) == 1f, "1.0");
        c.check("encumbrance: graded, +50% over is 65% speed", Math.abs(CarryWeight.speedFactor(450f, 300f) - 0.65f) < 0.001f,
                String.valueOf(CarryWeight.speedFactor(450f, 300f)));
        c.check("encumbrance: floor of 30% at +100% and beyond", CarryWeight.speedFactor(600f, 300f) == 0.30f
                && CarryWeight.speedFactor(5000f, 300f) == 0.30f, String.valueOf(CarryWeight.speedFactor(5000f, 300f)));
        c.check("encumbrance: a little over is only a little slower", CarryWeight.speedFactor(315f, 300f) > 0.9f, String.valueOf(CarryWeight.speedFactor(315f, 300f)));
    }

    /** ChestMenu slot index (27 container slots, then inventory 9-35, then the hotbar) of the first stack of {@code item}. */
    private static int findSlot(Inventory inv, Item item) {
        for (int i = 9; i < 36; i++) if (inv.getItem(i).is(item)) return 27 + (i - 9);
        for (int i = 0; i < 9; i++) if (inv.getItem(i).is(item)) return 54 + i;
        return 27;
    }

    /** A 27-stack container taken into a completely full inventory: every item lands, nothing is dropped or left. */
    private static void containerTake(FakePlayer a, Checks c) {
        a.getInventory().clearContent();
        Pack.clear(a);
        Inventory inv = a.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) inv.setItem(i, distinct(i, "slot_t"));
        SimpleContainer chest = new SimpleContainer(27);
        for (int i = 0; i < 27; i++) chest.setItem(i, distinct(i, "chest_t"));
        Map<String, Integer> before = snapshot(a);
        for (int i = 0; i < 27; i++) add(before, chest.getItem(i));
        ChestMenu menu = new ChestMenu(MenuType.GENERIC_9x3, 77, inv, chest, 3);
        a.containerMenu = menu;
        ActionHandler.handleContainerTake(a, 77, -1, true);
        boolean empty = true;
        for (int i = 0; i < 27; i++) empty &= chest.getItem(i).isEmpty();
        c.check("container: Take All from a full container into a full inventory empties the container", empty, empty ? "empty" : "items left behind");
        c.check("container: all 27 stacks are in the pack and none was lost", Pack.size(a) >= 27 && snapshot(a).equals(before),
                Pack.size(a) + " pack entries, " + diff(before, snapshot(a)));

        // and one entry back into the container from the pack (a virtual slot id)
        int idx = Pack.find(a, s -> s.hasTag() && s.getTag().contains("chest_t"));
        int packBefore = Pack.size(a);
        ActionHandler.handleContainerStore(a, 77, Pack.VIRTUAL_BASE + idx, false);
        int stored = 0;
        for (int i = 0; i < 27; i++) if (!chest.getItem(i).isEmpty()) stored++;
        c.check("container: storing a pack entry puts it in the container", stored == 1 && Pack.size(a) == packBefore - 1,
                stored + " stored, pack " + packBefore + " -> " + Pack.size(a));
        a.containerMenu = a.inventoryMenu;
        a.getInventory().clearContent();
        Pack.clear(a);
    }
}
