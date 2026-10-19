package com.nirncraft.crime;

import com.nirncraft.core.Notifier;
import com.nirncraft.inventory.BulkStore;
import com.nirncraft.inventory.ContainerOverflow;
import com.nirncraft.inventory.Inv;
import com.nirncraft.inventory.OverflowHost;
import com.nirncraft.tamriel.settlement.Anchor;
import com.nirncraft.tamriel.settlement.AnchorType;
import com.nirncraft.tamriel.settlement.SettlementAnchors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * What a jail does with a prisoner's things, in one place per jail (docs/JAIL.md).
 *
 * <p>Two containers stand in the jail, in no cell, in the evidence room (the guard room of a hold jail, the evidence
 * room behind the locked iron door of a jail-dimension cell block):</p>
 * <ul>
 *     <li>the <b>evidence chest</b> (a chest with an "Evidence" sign), never locked, no screen: interact with it (the Interact key) and
 *     everything the jail took from <i>you</i> comes back to your inventory (armour to its slot when empty);</li>
 *     <li>the <b>confiscated goods</b> (a barrel with a "Confiscated" sign), shared by every prisoner of that jail:
 *     the stolen goods taken from arrested players. It is locked every time (lockpicking at a level that grows
 *     with the bounties that went in, or a guard's key) and opens with the Skyrim container screen. Taking from it
 *     is stealing like from any owned container ({@link Theft}); the items keep their stolen mark.</li>
 * </ul>
 *
 * <p>Both are unbounded {@link BulkStore}s held in the jail's {@link Jail.JailData} (saved with the world), keyed per
 * jail and, for the evidence, per player UUID. The blocks are only access points: breaking or unloading them loses
 * nothing.</p>
 */
final class JailEvidence {
    private JailEvidence() {}

    /** Engineering caps only: a jail's store never refuses a whole inventory. */
    static final int MAX_ENTRIES = 1_000_000;
    static final int MAX_PER_ENTRY = 1_000_000_000;
    /** Bucket for things held by older versions (the per-player overflow list); any jail's evidence chest returns it. */
    static final String LEGACY = "legacy";
    /** Jail key of the jail dimension (every cell block shares one set of stores). */
    static final String DIM = "dim";

    /** How long each confiscated stolen item batch remains in the jail's confiscated barrel before decaying (5 minutes). */
    static final long CONFISCATED_TTL_TICKS = 5L * 60L * 20L;

    enum Kind { NONE, PERSONAL, CONFISCATED, LEGACY_CHEST }

    record ConfiscatedBatch(CompoundTag key, int count, long addedAt) {}

    // ------------------------------------------------------------------ the stores

    /** One jail's stores. */
    static final class Stores {
        final Map<UUID, BulkStore> personal = new HashMap<>();
        final BulkStore confiscated;
        final List<ConfiscatedBatch> confiscatedBatches = new ArrayList<>();
        /** Lock tier of the confiscated goods: grows with the biggest bounty that was taken in. */
        int tier = Locks.APPRENTICE;
        private final Runnable changed;

        Stores(Runnable changed) {
            this.changed = changed;
            this.confiscated = new BulkStore(MAX_ENTRIES, MAX_PER_ENTRY, changed);
        }

        BulkStore personal(UUID player) {
            return personal.computeIfAbsent(player, k -> new BulkStore(MAX_ENTRIES, MAX_PER_ENTRY, changed));
        }

        void pruneConfiscated(long now) {
            if (now <= 0 || confiscatedBatches.isEmpty()) return;
            boolean dirty = false;
            for (var it = confiscatedBatches.iterator(); it.hasNext(); ) {
                ConfiscatedBatch b = it.next();
                if (now >= b.addedAt() && now - b.addedAt() >= CONFISCATED_TTL_TICKS) {
                    ItemStack template = com.nirncraft.inventory.ItemData.parse(b.key());
                    if (!template.isEmpty()) {
                        confiscated.take(template, b.count());
                    }
                    it.remove();
                    dirty = true;
                }
            }
            if (confiscated.isEmpty() && !confiscatedBatches.isEmpty()) {
                confiscatedBatches.clear();
                dirty = true;
            }
            if (dirty && changed != null) changed.run();
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("tier", tier);
            tag.put("confiscated", confiscated.save());
            if (!confiscatedBatches.isEmpty()) {
                net.minecraft.nbt.ListTag batches = new net.minecraft.nbt.ListTag();
                for (ConfiscatedBatch b : confiscatedBatches) {
                    CompoundTag bt = new CompoundTag();
                    bt.put("k", b.key().copy());
                    bt.putInt("n", b.count());
                    bt.putLong("t", b.addedAt());
                    batches.add(bt);
                }
                tag.put("confiscated_batches", batches);
            }
            CompoundTag p = new CompoundTag();
            personal.forEach((id, s) -> {
                if (!s.isEmpty()) p.put(id.toString(), s.save());
            });
            tag.put("personal", p);
            return tag;
        }

        static Stores load(CompoundTag tag, Runnable changed) {
            Stores st = new Stores(changed);
            st.tier = Math.max(Locks.NOVICE, Math.min(Locks.MASTER, tag.contains("tier") ? tag.getIntOr("tier",0) : Locks.APPRENTICE));
            st.confiscated.load(tag.getCompoundOrEmpty("confiscated"));
            if (com.nirncraft.platform.NbtData.hasType(tag, "confiscated_batches", net.minecraft.nbt.Tag.TAG_LIST)) {
                net.minecraft.nbt.ListTag batches = tag.getListOrEmpty("confiscated_batches");
                for (int i = 0; i < batches.size(); i++) {
                    CompoundTag bt = batches.getCompoundOrEmpty(i);
                    int n = bt.getIntOr("n",0);
                    if (n > 0 && com.nirncraft.platform.NbtData.hasType(bt, "k", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                        st.confiscatedBatches.add(new ConfiscatedBatch(bt.getCompoundOrEmpty("k"), n, bt.getLongOr("t",0L)));
                    }
                }
            }
            CompoundTag p = tag.getCompoundOrEmpty("personal");
            for (String k : p.keySet()) {
                try {
                    st.personal(UUID.fromString(k)).load(p.getCompoundOrEmpty(k));
                } catch (IllegalArgumentException ignored) {
                    // corrupt entry
                }
            }
            return st;
        }
    }

    static Stores stores(MinecraftServer server, String jailKey) {
        Jail.JailData jd = Jail.JailData.get(server);
        Stores st = jd.stores(jailKey);
        long now = server.overworld() != null ? server.overworld().getGameTime() : 0L;
        st.pruneConfiscated(now);
        if (!LEGACY.equals(jailKey)) {
            // stolen goods an older version parked under no jail go to the first jail that is looked at
            Stores old = jd.stores.get(LEGACY);
            if (old != null && !old.confiscated.isEmpty()) {
                st.confiscated.absorb(old.confiscated);
                jd.setDirty();
            }
        }
        return st;
    }

    /** The key of the jail a prisoner is held in. */
    static String jailKey(Jail.Site site) {
        return site.hold() ? site.place() : DIM;
    }

    // ------------------------------------------------------------------ arrest

    /**
     * Takes {@code stack} from an arrested player: stolen goods to the jail's shared store, everything else to the
     * player's own. Consumes {@code stack}; whatever a (practically unreachable) cap refused goes back to the player.
     */
    static void confiscate(ServerPlayer player, String jailKey, ItemStack stack) {
        if (stack.isEmpty()) return;
        route(stores(player.level().getServer(), jailKey), player.getUUID(), stack, player.level().getGameTime());
        if (!stack.isEmpty()) Inv.giveOrDrop(player, stack);
    }

    /** The one rule: stolen goods into the shared store, anything else into the player's own. Consumes what it took. */
    static void route(Stores st, UUID player, ItemStack stack) {
        route(st, player, stack, 0L);
    }

    static void route(Stores st, UUID player, ItemStack stack, long now) {
        if (Bounty.isStolen(stack)) {
            CompoundTag key = BulkStore.key(stack);
            int taken = st.confiscated.add(stack);
            if (taken > 0 && now > 0) {
                st.confiscatedBatches.add(new ConfiscatedBatch(key, taken, now));
            }
        } else {
            st.personal(player).add(stack);
        }
    }

    /** Empties one player's evidence in {@code st}: their own things, nobody else's (as stacks of at most max size). */
    static List<ItemStack> takePersonal(Stores st, UUID player) {
        List<ItemStack> out = new ArrayList<>();
        BulkStore mine = st.personal.get(player);
        while (mine != null && !mine.isEmpty()) {
            ItemStack all = mine.take(0, Integer.MAX_VALUE);
            if (!all.isEmpty()) out.addAll(BulkStore.splitToStacks(all));
        }
        return out;
    }

    /** Remembers how big a bounty was taken in: the confiscated goods' lock grows with it. */
    static void noteBounty(MinecraftServer server, String jailKey, int bounty) {
        Stores st = stores(server, jailKey);
        int tier = Jail.evidenceTier(bounty);
        if (tier > st.tier) {
            st.tier = tier;
            Jail.JailData.get(server).setDirty();
        }
    }

    // ------------------------------------------------------------------ giving back

    /**
     * Hands back everything the jail took from this player (the jail's own store, whatever an older version kept):
     * armour to its slot when empty, the rest into the bags, never deleted. Returns how many items.
     */
    static int returnPersonal(ServerPlayer player, String jailKey) {
        MinecraftServer server = player.level().getServer();
        Jail.JailData jd = Jail.JailData.get(server);
        UUID id = player.getUUID();
        int n = 0;
        // an older version's overflow list: this player's own things are theirs again, stolen goods stay in the jail
        List<ItemStack> held = jd.held.remove(id);
        if (held != null) {
            jd.setDirty();
            for (ItemStack s : held) {
                if (Bounty.isStolen(s)) {
                    stores(server, jailKey).confiscated.add(s);
                } else {
                    n += s.getCount();
                    giveBack(player, s);
                }
            }
        }
        for (String key : new String[]{jailKey, LEGACY}) {
            Stores st = jd.stores.get(key);
            if (st == null) continue;
            for (ItemStack part : takePersonal(st, id)) {
                n += part.getCount();
                giveBack(player, part);
            }
        }
        if (n > 0) player.inventoryMenu.broadcastChanges();
        return n;
    }

    /** Armour goes to its empty slot, everything else into the bags (and, only past the pack's cap, the floor). */
    static void giveBack(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;
        EquipmentSlot slot = player.getEquipmentSlotForItem(stack);
        if (slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR && player.getItemBySlot(slot).isEmpty()) {
            player.setItemSlot(slot, stack);
            return;
        }
        Inv.giveOrDrop(player, stack);
    }

    // ------------------------------------------------------------------ the blocks

    /** What the block at {@code pos} is to the jail (none for every chest outside a jail). */
    static Kind kindAt(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(Blocks.CHEST) && !state.is(Blocks.BARREL) && !state.is(Blocks.TRAPPED_CHEST)) return Kind.NONE;
        if (Jail.isJailDimension(level)) {
            if (pos.getY() != Jail.CELL_Y + 1 || pos.getZ() != 1) return Kind.NONE;
            return switch (Math.floorMod(pos.getX(), Jail.CELL_SPACING)) {
                case 1 -> Kind.PERSONAL;
                case 4 -> Kind.CONFISCATED;
                case 2, 3 -> Kind.LEGACY_CHEST; // the double chest of cell blocks built earlier
                default -> Kind.NONE;
            };
        }
        if (level.dimension() != Level.OVERWORLD) return Kind.NONE;
        Optional<SettlementAnchors.Entry> near = SettlementAnchors.near(level, pos, 0);
        if (near.isEmpty() || near.get().first(AnchorType.JAIL_CELL) == null) return Kind.NONE;
        SettlementAnchors.Entry e = near.get();
        for (Anchor a : e.anchors()) {
            if (!a.pos().equals(pos)) continue;
            if (a.type() == AnchorType.JAIL_PERSONAL) return Kind.PERSONAL;
            if (a.type() == AnchorType.JAIL_CONFISCATED) return Kind.CONFISCATED;
        }
        // jails generated before the two containers: the cells' double chests in the guard room
        List<Anchor> old = e.anchors(AnchorType.JAIL_EVIDENCE);
        if (e.first(AnchorType.JAIL_PERSONAL) == null) {
            for (int i = 0; i < old.size(); i++) {
                if (!old.get(i).pos().equals(pos)) continue;
                return i == 0 ? Kind.PERSONAL : i == 2 ? Kind.CONFISCATED : Kind.LEGACY_CHEST;
            }
        }
        return Kind.NONE;
    }

    /** The jail key of the jail the block at {@code pos} belongs to (after {@link #kindAt} said it is one). */
    static String keyAt(ServerLevel level, BlockPos pos) {
        if (Jail.isJailDimension(level)) return DIM;
        return SettlementAnchors.near(level, pos, 0).map(e -> e.key).orElse(LEGACY);
    }

    /** Interacting with the evidence chest: your things come back, no screen. */
    static void interactPersonal(ServerPlayer player, ServerLevel level, BlockPos pos) {
        migrateAt(level, pos);
        int n = returnPersonal(player, keyAt(level, pos));
        if (n > 0) {
            level.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.8f, 1.1f);
            level.playSound(null, player.blockPosition(), SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.6f, 1.0f);
            Notifier.message(player, Component.translatable("crime.nirncraft.jail.evidence_returned", n));
        } else {
            level.playSound(null, pos, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.6f, 1.0f);
            Notifier.message(player, Component.translatable("crime.nirncraft.jail.evidence_empty"));
        }
    }

    /** Opens the shared confiscated goods (the lock was passed): the Skyrim container screen over the jail's store. */
    static void openConfiscated(ServerPlayer player, ServerLevel level, BlockPos pos) {
        migrateAt(level, pos);
        String key = keyAt(level, pos);
        Stores st = stores(player.level().getServer(), key);
        // locked again the moment it has been opened: the next one has to pick it, too
        relock(level, pos);
        Locks.setLock(level, pos, st.tier);
        StoreContainer container = new StoreContainer(st.confiscated, pos);
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> new ChestMenu(MenuType.GENERIC_9x3, id, inv, container, 3),
                Component.translatable("crime.nirncraft.jail.confiscated")));
    }

    /** Forgets who picked this lock, so the next one has to pick it again. */
    static void relock(ServerLevel level, BlockPos pos) {
        Locks.LockData data = Locks.LockData.get(level.getServer());
        if (data.unlocked.remove(Locks.key(level, Locks.normalizePos(level, pos))) != null) data.setDirty();
    }

    /**
     * The confiscated goods as a container the Skyrim screen and the theft machinery understand: its 27 slots never
     * hold anything ({@code canPlaceItem} is false), everything lives in the jail's store as the overflow behind them.
     */
    static final class StoreContainer extends SimpleContainer implements OverflowHost {
        private final BulkStore store;
        private final BlockPos pos;

        StoreContainer(BulkStore store, BlockPos pos) {
            super(27);
            this.store = store;
            this.pos = pos;
        }

        BlockPos pos() {
            return pos;
        }

        @Override
        public BulkStore overflow() {
            return store;
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return false;
        }

        @Override
        public boolean stillValid(Player player) {
            return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64;
        }
    }

    // ------------------------------------------------------------------ building the access points

    /** Places the chest and the barrel (and their signs) of a hold jail; contents live in the stores, not here. */
    static void ensureHold(ServerLevel level, Jail.Site s, int tier) {
        BlockPos chest = s.personal(), barrel = s.confiscated();
        if (chest != null) {
            level.getChunk(chest);
            BlockState now = level.getBlockState(chest);
            Direction facing = now.is(Blocks.CHEST) ? now.getValue(ChestBlock.FACING) : Direction.SOUTH;
            if (!now.is(Blocks.CHEST) || now.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                level.setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing), 3);
            }
            Locks.setLock(level, chest, Locks.NOT_LOCKED);
            sign(level, chest.above(), wallSide(s, chest), "crime.nirncraft.jail.sign.evidence", "crime.nirncraft.jail.sign.evidence_2", "crime.nirncraft.jail.sign.use_evidence");
        }
        if (barrel != null) {
            level.getChunk(barrel);
            if (!level.getBlockState(barrel).is(Blocks.BARREL)) level.setBlock(barrel, Blocks.BARREL.defaultBlockState(), 3);
            Locks.setLock(level, barrel, tier);
            sign(level, barrel.above(), wallSide(s, barrel), "crime.nirncraft.jail.sign.confiscated", "crime.nirncraft.jail.sign.confiscated_2", "crime.nirncraft.jail.sign.use_confiscated");
        }
    }

    /** The side wall a container stands against decides which way its sign faces. */
    private static Direction wallSide(Jail.Site s, BlockPos p) {
        return p.getX() <= (s.min().getX() + s.max().getX()) / 2 ? Direction.EAST : Direction.WEST;
    }

    /** The jail dimension's evidence room: chest and barrel against the walls, signs above them. */
    static void ensureDim(ServerLevel level, BlockPos o, int tier) {
        BlockPos chest = o.offset(1, 1, 1), barrel = o.offset(4, 1, 1);
        if (!level.getBlockState(chest).is(Blocks.CHEST)) {
            level.setBlock(chest, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH), 3);
        }
        if (!level.getBlockState(barrel).is(Blocks.BARREL)) level.setBlock(barrel, Blocks.BARREL.defaultBlockState(), 3);
        Locks.setLock(level, chest, Locks.NOT_LOCKED);
        Locks.setLock(level, barrel, tier);
        sign(level, chest.above(), Direction.EAST, "crime.nirncraft.jail.sign.evidence", "crime.nirncraft.jail.sign.evidence_2", "crime.nirncraft.jail.sign.use_evidence");
        sign(level, barrel.above(), Direction.WEST, "crime.nirncraft.jail.sign.confiscated", "crime.nirncraft.jail.sign.confiscated_2", "crime.nirncraft.jail.sign.use_confiscated");
    }

    /**
     * A wall sign where the block is free (an existing one is left alone): its name, what it is, and a third line
     * that names the Interact key (a keybind component: the player's own key, F by default).
     */
    private static void sign(ServerLevel level, BlockPos pos, Direction face, String line1, String line2, String use) {
        if (!level.getBlockState(pos).isAir()) return;
        level.setBlock(pos, Blocks.OAK_WALL_SIGN.defaultBlockState().setValue(WallSignBlock.FACING, face), 3);
        if (level.getBlockEntity(pos) instanceof SignBlockEntity sbe) {
            SignText.Mutable text = SignText.EMPTY.asMutable().setLine(0, Component.translatable(line1)).setLine(1, Component.translatable(line2))
                    .setLine(2, Component.translatable(use, Component.keybind("key.nirncraft.interact")));
            sbe.setText(text.asImmutable(), net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
            sbe.setChanged();
        }
    }

    // ------------------------------------------------------------------ older jails

    /** Moves the contents of the older per-cell double chests around {@code pos} into the stores (on first access). */
    static void migrateAt(ServerLevel level, BlockPos pos) {
        if (Jail.isJailDimension(level)) {
            int slot = Math.floorDiv(pos.getX(), Jail.CELL_SPACING);
            migrateDim(level, slot);
            return;
        }
        SettlementAnchors.near(level, pos, 0).ifPresent(e -> migrateHold(level, e));
    }

    /** A hold jail's old guard-room chests: all contents into the jail's stores, the chests that are not an access point removed. */
    static void migrateHold(ServerLevel level, SettlementAnchors.Entry e) {
        List<Anchor> old = e.anchors(AnchorType.JAIL_EVIDENCE);
        if (old.isEmpty()) return;
        Jail.JailData jd = Jail.JailData.get(level.getServer());
        Stores st = stores(level.getServer(), e.key);
        boolean newLayout = e.first(AnchorType.JAIL_PERSONAL) != null;
        for (int i = 0; i < old.size(); i++) {
            BlockPos p = old.get(i).pos();
            level.getChunk(p);
            UUID owner = jd.chestOwner.get(e.key + "#" + (i / 2));
            drain(level, p, st, owner);
            boolean accessPoint = !newLayout && (i == 0 || i == 2);
            if (!accessPoint && level.getBlockState(p).is(Blocks.CHEST) && isEmpty(level, p)) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    /** A jail-dimension cell block's old double chest at (2..3, 1, 1). */
    static void migrateDim(ServerLevel level, int slot) {
        if (slot < 0) return;
        BlockPos o = Jail.cellOrigin(slot);
        Jail.JailData jd = Jail.JailData.get(level.getServer());
        UUID owner = null;
        for (Map.Entry<UUID, Integer> en : jd.slots.entrySet()) {
            if (en.getValue() == slot) owner = en.getKey();
        }
        Stores st = stores(level.getServer(), DIM);
        for (BlockPos p : new BlockPos[]{o.offset(2, 1, 1), o.offset(3, 1, 1)}) {
            if (!level.getBlockState(p).is(Blocks.CHEST)) continue;
            drain(level, p, st, owner);
            if (isEmpty(level, p)) level.setBlock(p, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static boolean isEmpty(ServerLevel level, BlockPos p) {
        BlockEntity be = level.getBlockEntity(p);
        if (!(be instanceof Container c)) return true;
        if (!c.isEmpty()) return false;
        BulkStore ov = ContainerOverflow.store(be);
        return ov == null || ov.isEmpty();
    }

    /** Everything in the container (slots and overflow) into the stores: stolen goods and unknown owners to the shared one. */
    private static void drain(ServerLevel level, BlockPos pos, Stores st, @Nullable UUID owner) {
        BlockEntity be = level.getBlockEntity(pos);
        if (!(be instanceof Container c)) return;
        List<ItemStack> moving = new ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty()) continue;
            moving.add(s.copy());
            c.setItem(i, ItemStack.EMPTY);
        }
        BulkStore ov = ContainerOverflow.store(be);
        while (ov != null && !ov.isEmpty()) {
            ItemStack all = ov.take(0, Integer.MAX_VALUE);
            if (!all.isEmpty()) moving.add(all);
        }
        for (ItemStack s : moving) {
            BulkStore target = owner == null || Bounty.isStolen(s) ? st.confiscated : st.personal(owner);
            target.add(s);
        }
        if (!moving.isEmpty()) c.setChanged();
    }
}
