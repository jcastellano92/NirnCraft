package com.nirncraft.loot.container;

import com.mojang.authlib.GameProfile;
import com.nirncraft.NirnCraft;
import com.nirncraft.inventory.Inv;
import com.nirncraft.inventory.ItemWeights;
import com.nirncraft.inventory.Pack;
import com.nirncraft.inventory.PackSelfTest;
import com.nirncraft.perf.PerfAutorun;
import com.nirncraft.vitals.ActionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Self-test of NirnCraft's loot containers (docs/CONTAINERS.md section 12), on a live server with two real players:
 * per-player copies, restock, the stash balance and unlimited player storage, shelves, the shared window, the lid
 * models, a chunk-style save and load, the conversion of a vanilla loot chest and the migration of a Lootr chest's
 * data. Runs as {@code /nirncraft-containers selftest} and as the autorun task {@code containers}; the log line is
 * {@code Containers selftest: n/m checks passed, k problems}.
 */
public final class ContainerSelfTest {
    private static final GameProfile PROFILE_A = new GameProfile(UUID.nameUUIDFromBytes("nirncraft-containers-selftest-a".getBytes()), "[ContTestA]");
    private static final GameProfile PROFILE_B = new GameProfile(UUID.nameUUIDFromBytes("nirncraft-containers-selftest-b".getBytes()), "[ContTestB]");
    private static final String TAG = "cont_t";
    static final String TABLE = "nirncraft:containers/dungeon/standard";
    static final String BOSS = "nirncraft:containers/dungeon/boss";

    private ContainerSelfTest() {}

    public record Report(int passed, int total, List<String> lines, List<String> failed) {
        public String summary() {
            return "Containers selftest: " + passed + "/" + total + " checks passed, " + (total - passed) + " problems"
                    + (failed.isEmpty() ? "" : " (" + String.join("; ", failed) + ")");
        }
    }

    public static void registerAutorun() {
        PerfAutorun.register("containers", (t, done) -> {
            Report r;
            try {
                r = run(t.server());
            } catch (RuntimeException e) {
                NirnCraft.LOGGER.error("Containers selftest crashed", e);
                r = new Report(0, 1, List.of(), List.of("crashed: " + e));
            }
            for (String l : r.lines()) NirnCraft.LOGGER.info("[containers] {}", l);
            NirnCraft.LOGGER.info("{}", r.summary());
            done.run();
        });
    }

    public static Report run(MinecraftServer server) {
        PackSelfTest.Checks c = new PackSelfTest.Checks();
        ServerLevel level = server.overworld();
        if (level == null) {
            c.check("fixture: an overworld exists", false, "data-only server");
            return new Report(c.passed, c.total, c.lines, c.failed);
        }
        BlockPos base = anchor(level).above(6);
        List<BlockPos> placed = new ArrayList<>();
        ServerPlayer a = PackSelfTest.menuPlayer(server, level, PROFILE_A);
        ServerPlayer b = PackSelfTest.menuPlayer(server, level, PROFILE_B);
        for (ServerPlayer p : List.of(a, b)) p.snapTo(base.getX() + 0.5, base.getY(), base.getZ() + 2.5);
        try {
            guarded("per-player copies and restock", c, () -> perPlayer(level, base, placed, a, b, c));
            guarded("stash balance", c, () -> stash(level, base.east(2), placed, a, c));
            guarded("player storage and the shared window", c, () -> playerStorage(level, base.east(4), placed, a, b, c));
            guarded("shelves", c, () -> shelves(level, base.east(6), placed, a, c));
            guarded("save and load", c, () -> persistence(level, base.east(8), placed, a, b, c));
            guarded("conversion and Lootr migration", c, () -> conversion(level, base.east(10), placed, c));
        } finally {
            for (ServerPlayer p : List.of(a, b)) {
                p.getInventory().clearContent();
                Pack.clear(p);
                p.containerMenu = p.inventoryMenu;
            }
            for (BlockPos p : placed) level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS);
            for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, new AABB(base).inflate(16))) {
                if (com.nirncraft.inventory.ItemData.hasCustom(e.getItem()) && com.nirncraft.inventory.ItemData.custom(e.getItem()).contains(TAG)) e.discard();
            }
        }
        return new Report(c.passed, c.total, c.lines, c.failed);
    }

    private static void guarded(String what, PackSelfTest.Checks c, Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            NirnCraft.LOGGER.error("Containers selftest '{}' threw", what, e);
            c.check("containers selftest '" + what + "' ran to the end", false, e.toString());
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Where the fixtures go: by a real operator (their chunks are loaded and ticking), else at the world spawn. */
    static BlockPos anchor(ServerLevel level) {
        for (var player : level.players()) if (!(player instanceof net.neoforged.neoforge.common.util.FakePlayer)) return player.blockPosition();
        return level.getRespawnData().pos();
    }

    /** Coins taken from a container go to the wallet, not the bags: compare everything else. */
    private static Map<String, Long> goods(Iterable<ItemStack> stacks) {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : stacks) if (!s.isEmpty() && com.nirncraft.core.Currency.valueOf(s) <= 0) out.add(s);
        return count(out);
    }

    private static LootContainerBlockEntity place(ServerLevel level, BlockPos pos, List<BlockPos> placed, ContainerKind kind, String table) {
        level.getChunkAt(pos);
        placed.add(pos);
        return LootContainers.place(level, pos, kind, Direction.NORTH, table == null ? null : Identifier.parse(table), ContainerStyle.NORDIC);
    }

    /** Opens {@code player}'s copy the way {@code player.openMenu} does (real menu, real window, real startOpen). */
    private static ChestMenu open(LootContainerBlockEntity be, ServerPlayer player, int id) {
        ChestMenu menu = (ChestMenu) be.createMenu(id, player.getInventory(), player);
        player.containerMenu = menu;
        com.nirncraft.inventory.ContainerSync.onOpen(new net.neoforged.neoforge.event.entity.player.PlayerContainerEvent.Open(player, menu));
        return menu;
    }

    private static void close(ServerPlayer player) {
        player.containerMenu.removed(player);
        player.containerMenu = player.inventoryMenu;
    }

    private static ItemStack distinct(int i) {
        ItemStack s = new ItemStack(i % 2 == 0 ? Items.IRON_SWORD : Items.BOW);
        com.nirncraft.inventory.ItemData.updateCustom(s, t -> t.putInt(TAG, i));
        return s;
    }

    private static Map<String, Long> count(Iterable<ItemStack> stacks) {
        Map<String, Long> m = new TreeMap<>();
        for (ItemStack s : stacks) {
            if (s.isEmpty()) continue;
            m.merge(BuiltInRegistries.ITEM.getKey(s.getItem()) + "|" + com.nirncraft.inventory.ItemData.custom(s), (long) s.getCount(), Long::sum);
        }
        return m;
    }

    private static List<ItemStack> carried(ServerPlayer p) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < p.getInventory().getContainerSize(); i++) out.add(p.getInventory().getItem(i).copy());
        out.addAll(Pack.entries(p));
        return out;
    }

    private static long total(Map<String, Long> m) {
        long n = 0;
        for (long v : m.values()) n += v;
        return n;
    }

    // ------------------------------------------------------------------ 1. per-player copies, restock, the lid

    private static void perPlayer(ServerLevel level, BlockPos pos, List<BlockPos> placed, ServerPlayer a, ServerPlayer b, PackSelfTest.Checks c) {
        LootContainerBlockEntity be = place(level, pos, placed, ContainerKind.CHEST, TABLE);
        c.check("place: a world loot chest of ours stands there", be != null && be.isWorldLoot() && TABLE.equals(be.table()),
                be == null ? "none" : be.kind() + " " + be.table());
        if (be == null) return;
        c.check("place: rendered from block models (RenderShape.MODEL, no block-entity renderer)",
                be.getBlockState().getRenderShape() == net.minecraft.world.level.block.RenderShape.MODEL, String.valueOf(be.getBlockState().getRenderShape()));
        c.check("questions: lootTableOf answers for our containers (theft, locks, QA)", TABLE.equals(LootContainers.lootTableOf(be)), String.valueOf(LootContainers.lootTableOf(be)));

        ChestMenu ma = open(be, a, 301);
        LootInstance ia = be.existing(a.getUUID());
        List<ItemStack> aFirst = ia == null ? List.of() : ia.contents();
        c.check("per player: the first open rolls the opener's own copy", ia != null && ia.rolled() && !aFirst.isEmpty(), aFirst.size() + " rows");
        c.check("lid: opening asks for the lid models (viewer counted)", be.viewers().contains(a.getUUID()), be.viewers().size() + " viewers");
        be.tickLid(level.getBlockState(pos));
        be.tickLid(level.getBlockState(pos));
        c.check("lid: two steps later the lid model is open", level.getBlockState(pos).getValue(LootContainerBlock.LID) == LidState.OPEN,
                String.valueOf(level.getBlockState(pos).getValue(LootContainerBlock.LID)));

        ChestMenu mb = open(be, b, 302);
        LootInstance ib = be.existing(b.getUUID());
        c.check("per player: a second player gets a copy of their own", ib != null && ib != ia && ib.rolled(), ib == null ? "none" : "separate");

        // A takes everything; B's copy is untouched
        Map<String, Long> bBefore = count(ib == null ? List.of() : ib.contents());
        ActionHandler.handleContainerTake(a, ma.containerId, -1, true);
        c.check("per player: A's Take All empties A's copy", ia != null && ia.isEmpty(), ia == null ? "-" : ia.contents().size() + " left");
        c.check("per player: ...and leaves B's copy as it was", ib != null && count(ib.contents()).equals(bBefore), bBefore.size() + " rows");
        Map<String, Long> carriedGoods = goods(carried(a));
        boolean allCarried = goods(aFirst).entrySet().stream().allMatch(e -> carriedGoods.getOrDefault(e.getKey(), 0L) >= e.getValue());
        c.check("per player: what A took is in A's bags (coins in the wallet)", allCarried, total(carriedGoods) + " carried");
        close(a);
        close(b);
        c.check("lid: the window folded back on close (nothing left in slots)", ia != null && ia.window == null && ib != null && ib.window == null, "windows detached");
        be.tickLid(level.getBlockState(pos));
        be.tickLid(level.getBlockState(pos));
        c.check("lid: after the last viewer the lid model closes again", level.getBlockState(pos).getValue(LootContainerBlock.LID) == LidState.CLOSED,
                String.valueOf(level.getBlockState(pos).getValue(LootContainerBlock.LID)));

        // restock: not before its time, then a fresh roll; never over the player's own things
        open(be, a, 303);
        boolean stillEmpty = ia.isEmpty();
        close(a);
        c.check("restock: an emptied copy stays empty before its restock time", stillEmpty, ia.contents().size() + " rows");
        int days = be.restockDays();
        ia.backdate(LootContainerBlockEntity.clock(level) - (days + 1L) * LootInstance.DAY);
        open(be, a, 304);
        boolean refilled = !ia.isEmpty();
        close(a);
        c.check("restock: after " + days + " days the copy is rolled afresh", days > 0 && refilled, ia.contents().size() + " rows");

        a.getInventory().clearContent();
        Pack.clear(a);
        ItemStack mine = distinct(1);
        Pack.add(a, mine.copy());
        ChestMenu ms = open(be, a, 305);
        ActionHandler.handleContainerStore(a, ms.containerId, -1, true);
        close(a);
        ia.backdate(LootContainerBlockEntity.clock(level) - (days + 1L) * LootInstance.DAY);
        open(be, a, 306);
        boolean kept = ia.contents().stream().anyMatch(s -> ItemStack.isSameItemSameComponents(s, mine));
        close(a);
        c.check("restock: a copy holding the player's own things is never restocked over them", kept && ia.holdsForeign(), "kept " + kept);

        LootContainerBlockEntity boss = place(level, pos.above(2), placed, ContainerKind.GRAND_CHEST, BOSS);
        c.check("restock: a boss hoard never restocks", boss != null && boss.restockDays() == 0, boss == null ? "-" : boss.restockDays() + " days");
        a.getInventory().clearContent();
        Pack.clear(a);
    }

    // ------------------------------------------------------------------ 2. the stash balance of world containers

    private static void stash(ServerLevel level, BlockPos pos, List<BlockPos> placed, ServerPlayer a, PackSelfTest.Checks c) {
        LootContainerBlockEntity be = place(level, pos, placed, ContainerKind.SACK, "nirncraft:containers/misc/satchel");
        if (be == null) {
            c.check("stash: a sack placed", false, "none");
            return;
        }
        a.getInventory().clearContent();
        Pack.clear(a);
        ItemStack heavy = new ItemStack(Items.IRON_BLOCK, 64);
        float unit = ItemWeights.get(heavy);
        for (int i = 0; i < 8; i++) Pack.add(a, heavy.copy());
        Map<String, Long> before = count(carried(a));
        ChestMenu m = open(be, a, 311);
        LootInstance inst = be.existing(a.getUUID());
        Map<String, Long> inside = count(inst.contents());
        before.forEach((k, v) -> inside.merge(k, v, Long::sum));
        ActionHandler.handleContainerStore(a, m.containerId, -1, true);
        double kg = inst.weight(ItemWeights::get);
        double cap = inst.rolledKg() + be.stashKg();
        Map<String, Long> after = count(inst.contents());
        count(carried(a)).forEach((k, v) -> after.merge(k, v, Long::sum));
        close(a);
        c.check("stash: a world sack takes only its allowance (" + be.stashKg() + " kg) on top of its loot",
                unit <= 0 || kg <= cap + unit + 0.01, String.format("%.1f kg inside, cap %.1f", kg, cap));
        c.check("stash: what it refused stayed with the player, nothing lost or duplicated", after.equals(inside),
                total(inside) + " before, " + total(after) + " after");
        c.check("stash: the allowance scales with the config", Math.abs(be.stashKg() - ContainerKind.SACK.stashKg * ContainersConfig.stashScale()) < 0.01
                || !ContainersConfig.limitWorldStash(), String.valueOf(be.stashKg()));
        a.getInventory().clearContent();
        Pack.clear(a);
    }

    // ------------------------------------------------------------------ 3. player storage: unlimited, shared

    private static void playerStorage(ServerLevel level, BlockPos pos, List<BlockPos> placed, ServerPlayer a, ServerPlayer b, PackSelfTest.Checks c) {
        level.getChunkAt(pos);
        placed.add(pos);
        level.setBlock(pos, ContainerRegistry.block(ContainerKind.CHEST).defaultBlockState(), Block.UPDATE_CLIENTS);
        if (!(level.getBlockEntity(pos) instanceof LootContainerBlockEntity be)) {
            c.check("storage: a player's chest placed", false, "none");
            return;
        }
        c.check("storage: a player's own container has no stash limit", !be.isWorldLoot() && be.stashKg() < 0, String.valueOf(be.stashKg()));
        a.getInventory().clearContent();
        Pack.clear(a);
        for (int i = 0; i < 120; i++) Pack.add(a, distinct(100 + i));
        Pack.add(a, new ItemStack(Items.ARROW, 5000));
        for (int i = 0; i < 8; i++) Pack.add(a, new ItemStack(Items.IRON_BLOCK, 64));
        Map<String, Long> before = count(carried(a));
        ChestMenu m = open(be, a, 321);
        ActionHandler.handleContainerStore(a, m.containerId, -1, true);
        LootInstance shared = be.existing(LootContainerBlockEntity.SHARED);
        Map<String, Long> inside = count(shared == null ? List.of() : shared.contents());
        c.check("storage: Store All of 129 kinds and 5000 arrows goes in whole (unlimited, like every NirnCraft container)",
                inside.equals(before) && Pack.size(a) == 0, total(inside) + " of " + total(before));

        // two players looking into one shared chest see one window
        ChestMenu mb = open(be, b, 322);
        c.check("storage: a second player shares the same window", mb.getContainer() == m.getContainer(), "same window");
        close(a);
        c.check("storage: the window stays open while someone still looks in", !((LootWindow) mb.getContainer()).isClosed(), "open");
        close(b);
        c.check("storage: the last close folds the window back", shared != null && shared.window == null && count(shared.contents()).equals(before),
                shared == null ? "-" : total(count(shared.contents())) + " items");
    }

    // ------------------------------------------------------------------ 4. shelves

    private static void shelves(ServerLevel level, BlockPos pos, List<BlockPos> placed, ServerPlayer a, PackSelfTest.Checks c) {
        LootContainerBlockEntity be = place(level, pos, placed, ContainerKind.BOOKSHELF, "nirncraft:containers/bookshelf/scholar");
        if (be == null) {
            c.check("shelf: placed", false, "none");
            return;
        }
        ChestMenu m = open(be, a, 331);
        LootInstance inst = be.existing(a.getUUID());
        List<ItemStack> books = inst.contents();
        boolean allBooks = !books.isEmpty() && books.stream().allMatch(s -> LootWindow.accepts(ContainerKind.BOOKSHELF, s) || s.is(Items.PAPER)
                || BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().contains("note"));
        c.check("shelf: a scholar's shelf holds books from the lore system", allBooks, books.size() + " rows");
        LootWindow w = (LootWindow) m.getContainer();
        c.check("shelf: a shelf refuses a sword", w.overflowRoom(new ItemStack(Items.IRON_SWORD)) == 0, "room " + w.overflowRoom(new ItemStack(Items.IRON_SWORD)));
        c.check("shelf: ...and takes a book", w.overflowRoom(new ItemStack(Items.BOOK)) > 0, "room " + w.overflowRoom(new ItemStack(Items.BOOK)));
        close(a);
        a.getInventory().clearContent();
        Pack.clear(a);
    }

    // ------------------------------------------------------------------ 5. save and load like a chunk

    private static void persistence(ServerLevel level, BlockPos pos, List<BlockPos> placed, ServerPlayer a, ServerPlayer b, PackSelfTest.Checks c) {
        LootContainerBlockEntity be = place(level, pos, placed, ContainerKind.CHEST, TABLE);
        if (be == null) return;
        open(be, b, 341);
        close(b);
        ChestMenu ma = open(be, a, 342); // saved while A's window is open: its slots must be in the save
        Map<String, Long> aInside = count(be.existing(a.getUUID()).contents());
        Map<String, Long> bInside = count(be.existing(b.getUUID()).contents());
        CompoundTag tag = be.saveWithFullMetadata(level.registryAccess());
        close(a);
        CompoundTag back;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            NbtIo.writeCompressed(tag, bos);
            back = NbtIo.readCompressed(new ByteArrayInputStream(bos.toByteArray()), NbtAccounter.create(268435456L));
        } catch (IOException e) {
            c.check("save: NBT round trip", false, e.toString());
            return;
        }
        BlockEntity re = BlockEntity.loadStatic(pos, be.getBlockState(), back, level.registryAccess());
        boolean ok = re instanceof LootContainerBlockEntity l && TABLE.equals(l.table()) && l.isWorldLoot()
                && l.existing(a.getUUID()) != null && count(l.existing(a.getUUID()).contents()).equals(aInside)
                && l.existing(b.getUUID()) != null && count(l.existing(b.getUUID()).contents()).equals(bInside)
                && l.existing(a.getUUID()).rolledAt() == be.existing(a.getUUID()).rolledAt();
        c.check("save: table, both players' copies and their roll times survive a save and a load (a window open meanwhile)", ok,
                re == null ? "no block entity" : re.getClass().getSimpleName());
        c.check("save: copies are never sent to clients (empty update tag)", be.getUpdateTag(level.registryAccess()).isEmpty(), "update tag");
        c.check("save: the table is stored under LootTable (QA tools, Lootr migration)", TABLE.equals(tag.getStringOr("LootTable", "")), tag.getStringOr("LootTable", "-"));
    }

    // ------------------------------------------------------------------ 6. conversion and Lootr migration

    private static void conversion(ServerLevel level, BlockPos pos, List<BlockPos> placed, PackSelfTest.Checks c) {
        level.getChunkAt(pos);
        placed.add(pos);
        BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST);
        level.setBlock(pos, chest, Block.UPDATE_CLIENTS);
        if (level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity v) {
            v.setLootTable(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, Identifier.parse(TABLE)), 7L);
        }
        int dropsBefore = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2)).size();
        boolean cand = LootContainers.candidate(level, level.getBlockEntity(pos), true);
        LootContainers.process(level, pos);
        BlockEntity be = level.getBlockEntity(pos);
        BlockState now = level.getBlockState(pos);
        c.check("convert: an unopened vanilla loot chest is a conversion candidate", cand, String.valueOf(cand));
        c.check("convert: it becomes ours with its table, facing kept", be instanceof LootContainerBlockEntity l && TABLE.equals(l.table())
                && now.hasProperty(LootContainerBlock.FACING) && now.getValue(LootContainerBlock.FACING) == Direction.EAST, now.toString());
        c.check("convert: nothing was generated or spilled by the swap",
                level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2)).size() == dropsBefore, "no new item entities");

        // a Lootr chest's data (the vanilla LootTable plus Lootr's id, no NirnCraft version) read through the alias
        BlockPos lp = pos.above(2);
        placed.add(lp);
        level.setBlock(lp, ContainerRegistry.block(ContainerKind.CHEST).defaultBlockState(), Block.UPDATE_CLIENTS);
        CompoundTag lootr = new CompoundTag();
        lootr.putString("id", "nirncraft:loot_container");
        lootr.putString("LootTable", BOSS);
        lootr.putLong("LootTableSeed", 42L);
        lootr.putIntArray("LootrId", new int[]{1, 2, 3, 4});
        BlockEntity loaded = BlockEntity.loadStatic(lp, level.getBlockState(lp), lootr, level.registryAccess());
        c.check("lootr: Lootr's chest data loads as one of ours, world loot with its table, waiting to be normalised",
                loaded instanceof LootContainerBlockEntity l && BOSS.equals(l.table()) && l.isWorldLoot() && l.pendingNormalize(),
                loaded == null ? "none" : String.valueOf(((LootContainerBlockEntity) loaded).table()));
        if (level.getBlockEntity(lp) instanceof LootContainerBlockEntity live) {
            com.nirncraft.worldgen.WorldgenData.load(live, lootr, level.registryAccess());
            LootContainers.process(level, lp);
        }
        BlockEntity after = level.getBlockEntity(lp);
        c.check("lootr: normalising turns a boss table into a hoard chest and keeps the table",
                level.getBlockState(lp).getBlock() == ContainerRegistry.block(ContainerKind.GRAND_CHEST) && after instanceof LootContainerBlockEntity l2
                        && BOSS.equals(l2.table()) && !l2.pendingNormalize(), level.getBlockState(lp).toString());
    }
}
