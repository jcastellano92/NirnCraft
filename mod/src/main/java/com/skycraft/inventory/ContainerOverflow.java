package com.skycraft.inventory;

import com.skycraft.Skycraft;
import com.skycraft.core.SkyData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraftforge.common.capabilities.AutoRegisterCapability;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * The overflow of block containers (docs/INVENTORY.md "Containers"): Skyrim has no slots in a chest, only a (here
 * unlimited, engineering-capped) list of things. Vanilla chests keep their 27/54 slots (hoppers, comparators and every
 * non-Skyrim reader see only those) and get a {@link BulkStore} behind them as a Forge block-entity capability:
 * what the slots cannot hold when the Skyrim container screen stores into the chest goes there, and the screen lists
 * and takes it like any other row.
 *
 * <ul>
 *     <li><b>Persistence.</b> The capability is serialised by Forge into the block entity's NBT ({@code ForgeCaps}), so it
 *     is saved with the chunk, survives restarts, travels with {@code /clone} and structure saves, and is marked
 *     changed ({@code setChanged}) whenever it changes.</li>
 *     <li><b>Which containers.</b> {@link #eligible}: every {@code RandomizableContainerBlockEntity} (chests, trapped
 *     chests, barrels, modded chests) except hoppers, dispensers/droppers and shulker boxes (a shulker keeps its items
 *     with the item, and a carried box has no overflow). The ender chest is the player's: its overflow is part of the
 *     player's data ({@code ender_pack}).</li>
 *     <li><b>Double chests.</b> Both halves are one {@code CompoundContainer}; its overflow is the one of the half with
 *     the smaller block position (x, then y, then z). Whatever the other half held is absorbed into it the moment
 *     the pair is opened, so nothing is stranded by doubling a chest that already had an overflow. Splitting the pair
 *     later leaves the overflow with the primary half.</li>
 *     <li><b>Destruction.</b> When the block entity is really removed (broken, exploded, replaced, burnt: not a chunk
 *     unload) everything in the overflow is spilled as ordinary, never-despawning item entities where it stood.
 *     Whatever stops the block from breaking (the protect module in towns) stops the spill too.</li>
 *     <li><b>Drain.</b> After every Skyrim take/store the overflow refills free container slots, so a hopper or a
 *     comparator sees as much as it can and the overflow is only non-empty while the slots are really full.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class ContainerOverflow {
    public static final Capability<Holder> CAP = CapabilityManager.get(new CapabilityToken<>() {});
    private static final ResourceLocation KEY = new ResourceLocation(Skycraft.MODID, "overflow");

    /** Engineering caps of a container's overflow (the same as the player's pack, so a whole pack fits in one chest). */
    public static final int MAX_ENTRIES = Pack.MAX_ENTRIES;
    public static final int MAX_PER_ENTRY = Pack.MAX_PER_ENTRY;
    /** Corpses must take a whole player (41 slots + a full pack): a larger entry cap, a far larger per-entry cap. */
    public static final int CORPSE_ENTRIES = 4096;
    public static final int CORPSE_PER_ENTRY = 50_000_000;
    /** Client requests name a container's overflow entry n as the menu slot id {@code SLOT_BASE + n}. */
    public static final int SLOT_BASE = 10_000;
    /** Item entities one spill makes before the rest goes to the nearest player's pack. */
    private static final int SPILL_ENTITIES = 2048;
    /** SkyData module of the ender chest's overflow (not synced to the client with the rest of the player's data). */
    public static final String ENDER_MODULE = "ender_pack";

    private ContainerOverflow() {}

    // ------------------------------------------------------------------ the capability

    /** True for the block entities that get an overflow. */
    public static boolean eligible(BlockEntity be) {
        return be instanceof RandomizableContainerBlockEntity
                && !(be instanceof HopperBlockEntity) && !(be instanceof DispenserBlockEntity) && !(be instanceof ShulkerBoxBlockEntity);
    }

    @SubscribeEvent
    public static void attach(AttachCapabilitiesEvent<BlockEntity> event) {
        BlockEntity be = event.getObject();
        if (!eligible(be)) return;
        Holder holder = new Holder(be);
        event.addCapability(KEY, holder);
        event.addListener(holder::onInvalidate);
    }

    /** The overflow store of a block entity, or null when it has none (not eligible, or its caps were invalidated). */
    @Nullable
    public static BulkStore store(@Nullable BlockEntity be) {
        if (be == null) return null;
        Holder h = be.getCapability(CAP).orElse(null);
        return h == null ? null : h.store;
    }

    @AutoRegisterCapability
    public static final class Holder implements ICapabilitySerializable<CompoundTag> {
        final BulkStore store;
        private final BlockEntity be;
        private final LazyOptional<Holder> optional = LazyOptional.of(() -> this);
        /** The chunk is unloading (the block entity is saved with it): not a destruction. */
        private boolean unloading;
        private boolean spilled;

        Holder(BlockEntity be) {
            this.be = be;
            this.store = new BulkStore(MAX_ENTRIES, MAX_PER_ENTRY, this::changed);
        }

        public BulkStore store() {
            return store;
        }

        private void changed() {
            Level level = be.getLevel();
            if (level != null && !level.isClientSide && !be.isRemoved()) be.setChanged();
        }

        @Override
        public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
            return CAP.orEmpty(cap, optional);
        }

        @Override
        public CompoundTag serializeNBT() {
            // an empty overflow (nearly every chest) writes an empty tag: no weight in the chunk
            return store.isEmpty() ? new CompoundTag() : store.save();
        }

        @Override
        public void deserializeNBT(CompoundTag nbt) {
            store.load(nbt);
        }

        /**
         * Called whenever the block entity's capabilities are invalidated: {@code onChunkUnloaded} first (the entity is
         * not removed yet: remember the chunk is going away), then {@code setRemoved} either way. Destruction is
         * {@code setRemoved} alone.
         */
        void onInvalidate() {
            optional.invalidate();
            Level level = be.getLevel();
            if (level == null || level.isClientSide) return;
            if (!be.isRemoved()) {
                unloading = true;
                return;
            }
            if (unloading || spilled || store.isEmpty()) return;
            spilled = true;
            spill(level, be.getBlockPos(), store);
        }
    }

    // ------------------------------------------------------------------ spilling

    /**
     * Spills a store at {@code pos} as persistent item entities (no despawn; not part of the player-drop cap of
     * {@link ItemPersistence}, which would release them). The store is emptied. Past {@value #SPILL_ENTITIES} entities
     * the rest goes to the nearest player's pack when there is one (a chest of a million nails is not a million-stack
     * item storm) and is spawned anyway when nobody is near: nothing is deleted.
     */
    public static int spill(Level level, BlockPos pos, BulkStore store) {
        if (level.isClientSide || store.isEmpty()) return 0;
        List<ItemStack> stacks = new ArrayList<>();
        for (ItemStack all : store.entries()) {
            if (!all.isEmpty()) stacks.addAll(BulkStore.splitToStacks(all));
        }
        store.clear();
        double x = pos.getX() + 0.5;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5;
        int n = 0;
        for (ItemStack s : stacks) {
            if (n >= SPILL_ENTITIES) {
                Player near = level.getNearestPlayer(x, y, z, 16.0, false);
                if (near != null) {
                    Pack.giveOrDrop(near, s);
                    continue;
                }
            }
            ItemEntity e = new ItemEntity(level, x, y, z, s);
            e.setDeltaMovement((level.random.nextDouble() - 0.5) * 0.2, 0.15, (level.random.nextDouble() - 0.5) * 0.2);
            e.setUnlimitedLifetime();
            level.addFreshEntity(e);
            n++;
        }
        return stacks.size();
    }

    // ------------------------------------------------------------------ finding the overflow of an open menu

    /** The overflow behind a menu's slots: its store and the container whose slots it backs. */
    public record Bound(BulkStore store, Container container) {
        /**
         * Adds a stack to the overflow, within the host's own room rule ({@link OverflowHost#overflowRoom}, e.g. a
         * saddlebag's weight limit). Shrinks {@code stack} by what was taken and returns that number.
         */
        public int add(ItemStack stack) {
            int room = container instanceof OverflowHost h ? h.overflowRoom(stack) : Integer.MAX_VALUE;
            if (room <= 0 || stack.isEmpty()) return 0;
            if (room >= stack.getCount()) return store.add(stack);
            ItemStack part = stack.split(room);
            int taken = store.add(part);
            stack.grow(part.getCount()); // what the room rule or the caps refused goes back to the caller
            return taken;
        }
    }

    /** The overflow of the container an open menu shows, or null (not a chest menu, or a container with none). */
    @Nullable
    public static Bound of(ServerPlayer player, AbstractContainerMenu menu) {
        if (!(menu instanceof ChestMenu cm)) return null;
        return of(player, cm.getContainer());
    }

    /** The overflow of a container the way a menu would use it, or null. */
    @Nullable
    public static Bound of(ServerPlayer player, Container c) {
        if (c instanceof OverflowHost host) {
            BulkStore s = host.overflow();
            return s == null ? null : new Bound(s, c);
        }
        if (c instanceof BlockEntity be) {
            BulkStore s = store(be);
            return s == null ? null : new Bound(s, c);
        }
        if (c instanceof CompoundContainer cc) {
            BlockEntity[] halves = halves(player, cc);
            if (halves == null) return null;
            BulkStore a = store(halves[0]);
            BulkStore b = store(halves[1]);
            if (a == null || b == null) return null;
            // the primary half is the one with the smaller position; the other is absorbed into it
            boolean firstIsPrimary = comparePos(halves[0].getBlockPos(), halves[1].getBlockPos()) <= 0;
            BulkStore primary = firstIsPrimary ? a : b;
            BulkStore other = firstIsPrimary ? b : a;
            // (each store marks its own block entity changed; what the caps refuse stays in the other half)
            primary.absorb(other);
            return new Bound(primary, c);
        }
        if (c instanceof PlayerEnderChestContainer && player != null && c == player.getEnderChestInventory()) {
            return new Bound(enderStore(player), c);
        }
        return null;
    }

    /** Deterministic order of two block positions: x, then y, then z. */
    static int comparePos(BlockPos a, BlockPos b) {
        int c = Integer.compare(a.getX(), b.getX());
        if (c == 0) c = Integer.compare(a.getY(), b.getY());
        if (c == 0) c = Integer.compare(a.getZ(), b.getZ());
        return c;
    }

    /** The ender chest's overflow: part of the player's data, a view over its list (valid for the call). */
    public static BulkStore enderStore(ServerPlayer player) {
        com.skycraft.core.PlayerData data = SkyData.get(player);
        CompoundTag m = data.module(ENDER_MODULE);
        if (!m.contains(BulkStore.LIST, Tag.TAG_LIST)) m.put(BulkStore.LIST, new ListTag());
        return new BulkStore(m.getList(BulkStore.LIST, Tag.TAG_COMPOUND), MAX_ENTRIES, MAX_PER_ENTRY, data::markDirty);
    }

    // ------------------------------------------------------------------ double chests

    private static Field first;
    private static Field second;
    private static boolean reflectionFailed;

    /**
     * The two block entities of a double chest's {@code CompoundContainer}. The fields are private (no access
     * transformers): read through {@link ObfuscationReflectionHelper} (SRG {@code f_18910_}/{@code f_18911_}); when
     * that is not possible, the block entities around the player are searched with the public {@code contains}.
     */
    @Nullable
    static BlockEntity[] halves(@Nullable Player player, CompoundContainer cc) {
        if (!reflectionFailed) {
            try {
                if (first == null) {
                    first = ObfuscationReflectionHelper.findField(CompoundContainer.class, "f_18910_");
                    second = ObfuscationReflectionHelper.findField(CompoundContainer.class, "f_18911_");
                }
                if (first.get(cc) instanceof BlockEntity a && second.get(cc) instanceof BlockEntity b) return new BlockEntity[]{a, b};
                return null;
            } catch (Throwable t) {
                reflectionFailed = true;
                Skycraft.LOGGER.warn("Double-chest halves by reflection unavailable ({}); searching around the player", t.toString());
            }
        }
        if (player == null) return null;
        BlockEntity a = null;
        BlockEntity b = null;
        BlockPos o = player.blockPosition();
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -6; dy <= 6; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    p.set(o.getX() + dx, o.getY() + dy, o.getZ() + dz);
                    BlockEntity be = player.level().getBlockEntity(p);
                    if (be instanceof Container c && cc.contains(c)) {
                        if (a == null) a = be;
                        else if (be != a) b = be;
                    }
                }
            }
        }
        return a != null && b != null ? new BlockEntity[]{a, b} : null;
    }

    // ------------------------------------------------------------------ slots <-> overflow

    /**
     * Moves overflow into free capacity of {@code container}: first onto partial stacks of the same item, then into
     * empty slots (in entry order). Returns true if anything moved. Cheap when there is nothing to do.
     */
    public static boolean drain(Container container, BulkStore store) {
        if (store.isEmpty()) return false;
        boolean moved = false;
        int size = container.getContainerSize();
        // 1. top up partial stacks
        for (int i = 0; i < size && !store.isEmpty(); i++) {
            ItemStack s = container.getItem(i);
            if (s.isEmpty() || s.getCount() >= s.getMaxStackSize() || s.getCount() >= container.getMaxStackSize()) continue;
            int idx = store.indexOf(s);
            if (idx < 0) continue;
            int space = Math.min(s.getMaxStackSize(), container.getMaxStackSize()) - s.getCount();
            ItemStack pulled = store.take(idx, space);
            if (pulled.isEmpty()) continue;
            s.grow(pulled.getCount());
            container.setItem(i, s);
            moved = true;
        }
        // 2. empty slots
        for (int i = 0; i < size && !store.isEmpty(); i++) {
            if (!container.getItem(i).isEmpty()) continue;
            ItemStack head = store.get(0);
            if (head.isEmpty()) {
                store.take(0, Integer.MAX_VALUE); // unreadable row: drop it so the loop cannot stick
                continue;
            }
            int n = Math.min(head.getMaxStackSize(), container.getMaxStackSize());
            ItemStack probe = head.copy();
            probe.setCount(n);
            if (!container.canPlaceItem(i, probe)) continue;
            ItemStack pulled = store.take(0, n);
            if (pulled.isEmpty()) continue;
            container.setItem(i, pulled);
            moved = true;
        }
        if (moved) container.setChanged();
        return moved;
    }

    /**
     * Puts {@code stack} into a container the way a player's store does: onto matching stacks, into empty slots, then
     * into the overflow. Shrinks {@code stack}; what is left is only what the caps refused.
     */
    public static void insert(Container container, @Nullable BulkStore overflow, ItemStack stack) {
        int size = container.getContainerSize();
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            ItemStack s = container.getItem(i);
            if (s.isEmpty() || !ItemStack.isSameItemSameTags(s, stack)) continue;
            int limit = Math.min(s.getMaxStackSize(), container.getMaxStackSize());
            int move = Math.min(limit - s.getCount(), stack.getCount());
            if (move <= 0) continue;
            s.grow(move);
            stack.shrink(move);
            container.setItem(i, s);
        }
        for (int i = 0; i < size && !stack.isEmpty(); i++) {
            if (!container.getItem(i).isEmpty() || !container.canPlaceItem(i, stack)) continue;
            int n = Math.min(stack.getCount(), Math.min(stack.getMaxStackSize(), container.getMaxStackSize()));
            container.setItem(i, stack.split(n));
        }
        if (!stack.isEmpty() && overflow != null) overflow.add(stack);
        container.setChanged();
    }

    /** The items of the overflow behind an open menu as one list (for theft detection and counting), or empty. */
    public static List<ItemStack> entriesOf(ServerPlayer player, AbstractContainerMenu menu) {
        Bound b = of(player, menu);
        return b == null ? List.of() : b.store().entries();
    }

    /** True for a menu slot id that names a container overflow entry. */
    public static boolean isOverflowSlot(int slot) {
        return slot >= SLOT_BASE;
    }
}
