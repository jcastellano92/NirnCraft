package com.nirncraft.loot.container;

import com.nirncraft.NirnCraft;
import com.nirncraft.inventory.BulkStore;
import com.nirncraft.inventory.ItemWeights;
import com.nirncraft.loot.Containers;
import com.nirncraft.loot.LootApi;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The contents of a NirnCraft loot container (docs/CONTAINERS.md section 4).
 *
 * <ul>
 *     <li><b>World loot</b> ({@link #isWorldLoot}: placed by world generation with a loot table) keeps one
 *     {@link LootInstance} per player, rolled from the table the first time that player opens it, at that player's
 *     loot level, and restocked per player ({@link Containers#restockDays}). Co-op players each find their own loot,
 *     and one player's stash is invisible to the others. With {@code perPlayer = false} everyone shares one copy.</li>
 *     <li><b>Player-placed</b> containers have one shared copy with no stash limit: they are the player's storage.</li>
 *     <li><b>Persistence.</b> Saved with the chunk: {@code LootTable} (the same key vanilla and Lootr use, so the QA
 *     tools and a Lootr migration read it), {@code WorldLoot}, {@code Instances}. Copies are never sent to clients:
 *     the client only ever sees the block state (kind, style, lid).</li>
 * </ul>
 */
public class LootContainerBlockEntity extends BlockEntity implements MenuProvider {
    /** Key of the one copy everyone shares (player-placed containers, or {@code perPlayer = false}). */
    public static final UUID SHARED = new UUID(0L, 0L);
    /** Saved-data version of this block entity; absent on data written by vanilla or Lootr. */
    static final int VERSION = 1;

    @Nullable
    private String table;
    private boolean worldLoot;
    /** Loaded from data this code did not write (a Lootr chest by registry alias): fix kind and style next tick. */
    private boolean pendingNormalize;
    /** What a migration found and did not need (Lootr's id), kept for diagnosis. */
    private CompoundTag legacy = new CompoundTag();
    private final Map<UUID, LootInstance> instances = new LinkedHashMap<>();
    private final Map<UUID, Player> viewers = new java.util.HashMap<>();

    public LootContainerBlockEntity(BlockPos pos, BlockState state) {
        super(ContainerRegistry.BLOCK_ENTITY.get(), pos, state);
    }

    // ------------------------------------------------------------------ identity

    public ContainerKind kind() {
        return getBlockState().getBlock() instanceof LootContainerBlock b ? b.kind() : ContainerKind.CHEST;
    }

    @Nullable
    public String table() {
        return table;
    }

    public boolean isWorldLoot() {
        return worldLoot;
    }

    /** Makes this a world loot container of {@code table} (null: a plain world container, e.g. decor storage). */
    public void setWorldLoot(@Nullable String table) {
        this.table = table;
        this.worldLoot = true;
        setChanged();
    }

    /** The stash allowance in kg on top of the last roll (negative: unlimited, the player's own storage). */
    public float stashKg() {
        if (!worldLoot || !ContainersConfig.limitWorldStash()) return -1f;
        return (float) (kind().stashKg * ContainersConfig.stashScale());
    }

    public int restockDays() {
        int days = Containers.restockDays(table);
        return days <= 0 ? 0 : Math.max(1, (int) Math.round(days * ContainersConfig.restockScale()));
    }

    /** Whose copy {@code player} opens. */
    public UUID keyFor(Player player) {
        return worldLoot && ContainersConfig.perPlayer() ? player.getUUID() : SHARED;
    }

    @Nullable
    public LootInstance existing(UUID key) {
        return instances.get(key);
    }

    public LootInstance instance(UUID key) {
        return instances.computeIfAbsent(key, k -> new LootInstance(this::setChanged));
    }

    public Map<UUID, LootInstance> instances() {
        return java.util.Collections.unmodifiableMap(instances);
    }

    public Set<UUID> viewers() {
        return java.util.Collections.unmodifiableSet(viewers.keySet());
    }

    /** The loot clock: game time, but sleeping and waiting (which move the day) count too, like Skyrim's respawn. */
    public static long clock(Level level) {
        return Math.max(level.getOverworldClockTime(), level.getGameTime());
    }

    // ------------------------------------------------------------------ opening

    @Override
    public Component getDisplayName() {
        BlockState s = getBlockState();
        ContainerKind k = kind();
        if (k.styled && s.hasProperty(LootContainerBlock.STYLE)) {
            return Component.translatable("container.nirncraft." + k.id + "." + s.getValue(LootContainerBlock.STYLE).id());
        }
        return Component.translatable("container.nirncraft." + k.id);
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        if (!(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel)) return null;
        LootWindow window = prepare(sp);
        return new LootMenu(id, inventory, window);
    }

    /**
     * The window over {@code player}'s copy: rolled (or restocked) first when it is due. A shared copy that is open
     * already is shown to the next player in the same window, like a vanilla chest two players look into.
     */
    public LootWindow prepare(ServerPlayer player) {
        LootInstance inst = instance(keyFor(player));
        if (inst.window != null && !inst.window.isClosed()) return inst.window;
        if (table != null && level instanceof ServerLevel server && inst.dueForRoll(clock(server), restockDays())) {
            roll(inst, server, player);
        }
        return new LootWindow(this, inst);
    }

    /** Rolls the table into a copy at the opener's loot level (THIS_ENTITY: {@code loot.LootLevels}). */
    void roll(LootInstance inst, ServerLevel server, @Nullable ServerPlayer player) {
        List<ItemStack> roll;
        try {
            roll = LootApi.roll(server, Identifier.parse(table), worldPosition, player);
        } catch (RuntimeException e) {
            NirnCraft.LOGGER.warn("Loot container at {} could not roll {}: {}", worldPosition, table, e.toString());
            roll = List.of();
        }
        inst.restock(roll, clock(server), ItemWeights::get);
        setChanged();
    }

    // ------------------------------------------------------------------ viewers and the lid

    void viewerOpened(Player player) {
        boolean first = viewers.isEmpty();
        viewers.put(player.getUUID(), player);
        if (first && level instanceof ServerLevel server) {
            server.playSound(null, worldPosition, kind().openSound(), SoundSource.BLOCKS, 0.5f, server.getRandom().nextFloat() * 0.1f + 0.9f);
            server.scheduleTick(worldPosition, getBlockState().getBlock(), 1);
        }
    }

    void viewerClosed(Player player) {
        if (viewers.remove(player.getUUID()) == null) return;
        if (viewers.isEmpty() && level instanceof ServerLevel server) {
            server.playSound(null, worldPosition, kind().closeSound(), SoundSource.BLOCKS, 0.5f, server.getRandom().nextFloat() * 0.1f + 0.9f);
            server.scheduleTick(worldPosition, getBlockState().getBlock(), 1);
        }
        updateBooks();
    }

    /** Scheduled tick: drop viewers whose menu is gone, then step the lid one model toward open or closed. */
    void tickLid(BlockState state) {
        if (!(level instanceof ServerLevel server)) return;
        viewers.values().removeIf(p -> p.isRemoved()
                || !(p.containerMenu instanceof ChestMenu m && m.getContainer() instanceof LootWindow w && w.blockEntity() == this));
        if (!state.hasProperty(LootContainerBlock.LID)) return;
        LidState lid = state.getValue(LootContainerBlock.LID);
        LidState target = viewers.isEmpty() ? LidState.CLOSED : LidState.OPEN;
        LidState next = lid.toward(target);
        if (next != lid) server.setBlock(worldPosition, state.setValue(LootContainerBlock.LID, next), 3);
        if (next != target) server.scheduleTick(worldPosition, state.getBlock(), LootContainerBlock.LID_STEP);
        else if (!viewers.isEmpty()) server.scheduleTick(worldPosition, state.getBlock(), LootContainerBlock.RECHECK);
    }

    /** A player's own shelf shows how full it is (world shelves keep the look their table gave them). */
    void updateBooks() {
        if (worldLoot || !(level instanceof ServerLevel server)) return;
        BlockState s = getBlockState();
        if (!s.hasProperty(LootContainerBlock.Bookshelf.BOOKS)) return;
        LootInstance shared = instances.get(SHARED);
        long n = 0;
        if (shared != null) for (ItemStack st : shared.contents()) n += st.getCount();
        int books = n == 0 ? 0 : n <= 3 ? 1 : n <= 8 ? 2 : 3;
        if (s.getValue(LootContainerBlock.Bookshelf.BOOKS) != books) server.setBlock(worldPosition, s.setValue(LootContainerBlock.Bookshelf.BOOKS, books), 3);
    }

    // ------------------------------------------------------------------ removal

    /**
     * The block is really gone (broken by a creative player, or a player's own container broken): close every
     * window and spill every copy where it stood, so no one's things are lost. Rolls nobody has opened were never
     * made and cost nothing.
     */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (!(level instanceof ServerLevel server)) return;
        for (LootInstance inst : instances.values()) {
            if (inst.window != null) inst.window.foldBack();
            BulkStore items = inst.items();
            if (!items.isEmpty()) com.nirncraft.inventory.ContainerOverflow.spill(server, pos, items);
        }
        instances.clear();
    }

    // ------------------------------------------------------------------ persistence

    @Override
    public void onLoad() {
        super.onLoad();
        if (pendingNormalize && level instanceof ServerLevel server) LootContainers.queueNormalize(server, worldPosition);
    }

    /** Called once by the normaliser: the kind and style are right now. */
    void normalized() {
        pendingNormalize = false;
        setChanged();
    }

    boolean pendingNormalize() {
        return pendingNormalize;
    }

    @Override
    protected void loadAdditional(net.minecraft.world.level.storage.ValueInput input) {
        super.loadAdditional(input);
        CompoundTag tag = com.nirncraft.platform.ValueTags.read(input, "LootTable", "WorldLoot", "Instances", "Legacy", "NcV", "LootrId", "LootTableSeed");
        String t = tag.getStringOr("LootTable", "");
        table = t.isEmpty() ? null : t;
        legacy = tag.getCompoundOrEmpty("Legacy");
        instances.clear();
        if (tag.getIntOr("NcV", 0) <= 0) {
            // written by someone else (a Lootr chest, read through the registry alias): its table is all there is
            worldLoot = table != null;
            pendingNormalize = true;
            if (tag.contains("LootrId")) legacy.put("LootrId", tag.get("LootrId").copy());
            return;
        }
        worldLoot = tag.getBooleanOr("WorldLoot", false);
        for (Tag e : tag.getListOrEmpty("Instances")) {
            if (!(e instanceof CompoundTag c)) continue;
            UUID who;
            try {
                who = UUID.fromString(c.getStringOr("who", ""));
            } catch (IllegalArgumentException bad) {
                continue;
            }
            LootInstance inst = new LootInstance(this::setChanged);
            inst.load(c);
            instances.put(who, inst);
        }
    }

    @Override
    protected void saveAdditional(net.minecraft.world.level.storage.ValueOutput output) {
        super.saveAdditional(output);
        CompoundTag tag = new CompoundTag();
        tag.putInt("NcV", VERSION);
        if (table != null) tag.putString("LootTable", table);
        tag.putBoolean("WorldLoot", worldLoot);
        ListTag list = new ListTag();
        for (Map.Entry<UUID, LootInstance> e : instances.entrySet()) {
            // an empty copy that was never rolled is nothing to keep
            if (!e.getValue().rolled() && e.getValue().isEmpty()) continue;
            CompoundTag c = e.getValue().save();
            c.putString("who", e.getKey().toString());
            list.add(c);
        }
        tag.put("Instances", list);
        if (!legacy.isEmpty()) tag.put("Legacy", legacy.copy());
        output.store(tag);
    }

    /** Copies the identity (table, world loot, copies) of another of ours: used when a container changes kind. */
    void copyFrom(LootContainerBlockEntity other) {
        this.table = other.table;
        this.worldLoot = other.worldLoot;
        this.legacy = other.legacy.copy();
        for (Map.Entry<UUID, LootInstance> e : other.instances.entrySet()) {
            LootInstance inst = new LootInstance(this::setChanged);
            inst.load(e.getValue().save());
            instances.put(e.getKey(), inst);
        }
        setChanged();
    }

    /** Every copy's items, for the selftest and the info command. */
    List<ItemStack> allItems() {
        List<ItemStack> out = new ArrayList<>();
        for (LootInstance i : instances.values()) out.addAll(i.contents());
        return out;
    }
}
