package com.nirncraft.inventory;

import com.nirncraft.NirnCraft;
import com.nirncraft.core.SkyData;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * The overflow of block containers (docs/INVENTORY.md "Containers"): Skyrim has no slots in a chest, only a (here
 * unlimited, engineering-capped) list of things. Vanilla chests keep their 27/54 slots (hoppers, comparators and every
 * non-Skyrim reader see only those) and get a {@link BulkStore} behind them as a persistent block-entity attachment:
 * what the slots cannot hold when the Skyrim container screen stores into the chest goes there, and the screen lists
 * and takes it like any other row.
 *
 * <ul>
 *     <li><b>Persistence.</b> The attachment is serialized by NeoForge with the block entity, so it
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
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class ContainerOverflow {
    private static final net.neoforged.neoforge.registries.DeferredRegister<net.neoforged.neoforge.attachment.AttachmentType<?>> TYPES =
            net.neoforged.neoforge.registries.DeferredRegister.create(net.neoforged.neoforge.registries.NeoForgeRegistries.ATTACHMENT_TYPES, NirnCraft.MODID);
    private static final java.util.function.Supplier<net.neoforged.neoforge.attachment.AttachmentType<Holder>> DATA = TYPES.register("overflow", () ->
            net.neoforged.neoforge.attachment.AttachmentType.builder(holder -> new Holder((BlockEntity) holder))
                    .serialize(new net.neoforged.neoforge.attachment.IAttachmentSerializer<Holder>() {
                        @Override
                        public Holder read(net.neoforged.neoforge.attachment.IAttachmentHolder owner, net.minecraft.world.level.storage.ValueInput input) {
                            Holder holder = new Holder((BlockEntity) owner);
                            holder.store.load(input.read("data", CompoundTag.CODEC).orElseGet(CompoundTag::new));
                            return holder;
                        }
                        @Override
                        public boolean write(Holder holder, net.minecraft.world.level.storage.ValueOutput output) {
                            if (holder.store.isEmpty()) return false;
                            output.store("data", CompoundTag.CODEC, holder.store.save());
                            return true;
                        }
                    }).build());
    private static final java.util.Map<net.minecraft.server.level.ServerLevel, java.util.ArrayDeque<RemovalCheck>> CHECKS = new java.util.IdentityHashMap<>();
    private record RemovalCheck(java.lang.ref.WeakReference<Holder> holder, long revision) {}
    private static final Identifier KEY = Identifier.fromNamespaceAndPath(NirnCraft.MODID, "overflow");

    /** Engineering caps of a container's overflow (the same as the player's pack, so a whole pack fits in one chest). */
    public static final int MAX_ENTRIES = Pack.MAX_ENTRIES;
    public static final int MAX_PER_ENTRY = Pack.MAX_PER_ENTRY;
    /** Corpses must take a whole player (41 slots + a full pack): a larger entry cap, a far larger per-entry cap. */
    public static final int CORPSE_ENTRIES = 4096;
    public static final int CORPSE_PER_ENTRY = 50_000_000;
    /** Client requests name a container's overflow entry n as the menu slot id {@code SLOT_BASE + n}. */
    public static final int SLOT_BASE = 10_000;
    /** SkyData module of the ender chest's overflow (not synced to the client with the rest of the player's data). */
    public static final String ENDER_MODULE = "ender_pack";

    private ContainerOverflow() {}

    public static void init(net.neoforged.bus.api.IEventBus modBus) {
        TYPES.register(modBus);
    }

    // ------------------------------------------------------------------ the attachment

    /** True for the block entities that get an overflow. */
    public static boolean eligible(BlockEntity be) {
        return be instanceof RandomizableContainerBlockEntity
                && !(be instanceof HopperBlockEntity) && !(be instanceof DispenserBlockEntity) && !(be instanceof ShulkerBoxBlockEntity);
    }

    /** The attachment is created only for eligible containers and remains saved with its block entity. */
    @Nullable
    public static BulkStore store(@Nullable BlockEntity be) {
        if (be == null || !eligible(be) || be.isRemoved()) return null;
        Holder holder = be.getData(DATA.get());
        schedule(holder, false);
        return holder.store;
    }

    public static final class Holder {
        final BulkStore store;
        private final BlockEntity be;
        private boolean unloading, spilled, queued;
        private long revision;

        Holder(BlockEntity be) {
            this.be = be;
            this.store = new BulkStore(MAX_ENTRIES, MAX_PER_ENTRY, this::changed);
        }

        public BulkStore store() { return store; }

        private void changed() {
            Level level = be.getLevel();
            if (level != null && !level.isClientSide() && !be.isRemoved()) be.setChanged();
            schedule(this, false);
        }

        private void removed() {
            if (unloading || spilled || store.isEmpty()) return;
            Level level = be.getLevel();
            if (!(level instanceof net.minecraft.server.level.ServerLevel server)) return;
            spilled = true;
            spill(server, be.getBlockPos(), store);
        }
    }

    private static void schedule(Holder holder, boolean priority) {
        if (!(holder.be.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
        if (holder.store.isEmpty() || holder.unloading || holder.spilled) {
            holder.queued = false;
            holder.revision++;
            return;
        }
        if (holder.queued && !priority) return;
        holder.queued = true;
        var checks = CHECKS.computeIfAbsent(level, ignored -> new java.util.ArrayDeque<>());
        var check = new RemovalCheck(new java.lang.ref.WeakReference<>(holder), ++holder.revision);
        if (priority) checks.addFirst(check); else checks.addLast(check);
    }

    /** Queue an immediate confirmation after block removal; the event can precede the actual removal. */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST, receiveCanceled = true)
    public static void drops(net.neoforged.neoforge.event.level.BlockDropsEvent event) {
        BlockEntity be = event.getBlockEntity();
        if (be != null) {
            Holder holder = be.getExistingDataOrNull(DATA.get());
            if (holder != null) schedule(holder, true);
        }
    }

    @SubscribeEvent
    public static void chunkLoad(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        if (event.getLevel().isClientSide() || !(event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk)) return;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            Holder holder = be.getExistingDataOrNull(DATA.get());
            if (holder != null) { holder.unloading = false; schedule(holder, false); }
        }
    }

    /** Invalidate checks before vanilla marks unloaded block entities removed; unloading never spills cargo. */
    @SubscribeEvent
    public static void chunkUnload(net.neoforged.neoforge.event.level.ChunkEvent.Unload event) {
        if (event.getLevel().isClientSide() || !(event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk)) return;
        for (BlockEntity be : chunk.getBlockEntities().values()) markUnloading(be);
    }

    /** The chunk lifecycle marks retained attachments before vanilla calls setRemoved on their block entities. */
    static void markUnloading(BlockEntity be) {
        Holder holder = be.getExistingDataOrNull(DATA.get());
        if (holder != null) { holder.unloading = true; holder.queued = false; holder.revision++; }
    }

    @SubscribeEvent
    public static void levelUnload(net.neoforged.neoforge.event.level.LevelEvent.Unload event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level) CHECKS.remove(level);
    }

    /** Public events also cover replacement/fire paths without access transformers or block-entity mixins. */
    @SubscribeEvent
    public static void removalTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
        var checks = CHECKS.get(level);
        if (checks == null) return;
        int visits = Math.min(256, checks.size());
        for (int i = 0; i < visits; i++) {
            var check = checks.removeFirst();
            Holder holder = check.holder().get();
            if (holder == null || !holder.queued || holder.revision != check.revision()) continue;
            holder.queued = false;
            if (holder.be.isRemoved()) holder.removed();
            else schedule(holder, false);
        }
        if (checks.isEmpty()) CHECKS.remove(level);
    }

    // ------------------------------------------------------------------ spilling

    /**
     * Spills a store at {@code pos} as persistent item entities (no despawn; not part of the player-drop cap of
     * {@link ItemPersistence}, which would release them). Large spills retain their undelivered bulk counts in
     * dimension SavedData; bounded delivery resumes in loaded chunks without expanding millions of stacks.
     */
    public static int spill(Level level, BlockPos pos, BulkStore store) {
        return level instanceof net.minecraft.server.level.ServerLevel server ? DeferredSpills.spill(server, pos, store) : 0;
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
        com.nirncraft.core.PlayerData data = SkyData.get(player);
        CompoundTag m = data.module(ENDER_MODULE);
        if (!com.nirncraft.platform.NbtData.hasType(m, BulkStore.LIST, Tag.TAG_LIST)) m.put(BulkStore.LIST, new ListTag());
        return new BulkStore(m.getListOrEmpty(BulkStore.LIST), MAX_ENTRIES, MAX_PER_ENTRY, data::markDirty);
    }

    // ------------------------------------------------------------------ double chests

    private static Field first;
    private static Field second;
    private static boolean reflectionFailed;

    /**
     * The two block entities of a double chest's {@code CompoundContainer}. The fields are private (no access
     * transformers): read through {@link ObfuscationReflectionHelper} (official {@code container1}/{@code container2}); when
     * that is not possible, the block entities around the player are searched with the public {@code contains}.
     */
    @Nullable
    static BlockEntity[] halves(@Nullable Player player, CompoundContainer cc) {
        if (!reflectionFailed) {
            try {
                if (first == null) {
                    first = ObfuscationReflectionHelper.findField(CompoundContainer.class, "container1");
                    second = ObfuscationReflectionHelper.findField(CompoundContainer.class, "container2");
                }
                if (first.get(cc) instanceof BlockEntity a && second.get(cc) instanceof BlockEntity b) return new BlockEntity[]{a, b};
                return null;
            } catch (Throwable t) {
                reflectionFailed = true;
                NirnCraft.LOGGER.warn("Double-chest halves by reflection unavailable ({}); searching around the player", t.toString());
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
            if (s.isEmpty() || !ItemStack.isSameItemSameComponents(s, stack)) continue;
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
