package com.skycraft.crime;

import com.skycraft.Skycraft;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.quest.QuestItems;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import com.skycraft.tamriel.settlement.Anchor;
import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.SettlementAnchors;
import com.skycraft.vitals.ActionHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Jails. An arrest sends the prisoner to the jail of the hold where the crime happened: the gaol beside the keep of
 * the region's capital (Castle Dour's dungeon in Solitude, the Dragonsreach dungeon, Cidhna Mine in Markarth, the
 * Imperial Prison...), built by the settlements ({@code tamriel/settlement/build/Jails}) and found through its
 * recorded anchors. Until that city has been generated (or when every cell is taken) the prisoner goes to the jail
 * dimension {@code skycraft:jail} instead, where each prisoner has a cell block of their own.
 *
 * <p>The sentence is 30 seconds per 100 gold of bounty (30 s .. 10 min); sleeping in the cell bed after the first
 * minute serves the rest at once. Release clears the hold's bounty, hands back what isn't stolen, lets the days of
 * the sentence pass (single player) and costs some skill progress. Possessions go to the cell's evidence chest
 * (overflow is held in {@link JailData}; nothing is ever deleted). A prisoner may escape instead: pick the cell door
 * and the cell block gate (or slip down the drain at the end of the corridor) and walk out - escaping adds an escape
 * bounty. In Cidhna Mine the prisoners work the silver veins: every load mined shortens the sentence.</p>
 *
 * <p>State while jailed: {@code crime.jail = {hold, bounty, remaining (seconds), served, site ("hold" | "dim"),
 * place, cell, rdim, rx, ry, rz, ryaw, rpitch}}.</p>
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class Jail {
    public static final ResourceKey<Level> JAIL = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(Skycraft.MODID, "jail"));
    public static final int SECONDS_PER_100 = 30;
    public static final int MIN_SECONDS = 30;
    public static final int MAX_SECONDS = 600;
    public static final int CELL_SPACING = 32;
    public static final int CELL_Y = 64;
    /** Entity tag on the prisoners and the guard spawned with a cell block. */
    public static final String NPC_TAG = "skycraft_jail_npc";
    /** Item tag of the pickaxe Cidhna Mine's prisoners work with (taken back on the way out). */
    public static final String TOOL_TAG = "skycraft_jail_tool";
    /** Seconds one load of silver takes off a Cidhna Mine sentence. */
    public static final int LABOUR_SECONDS = 45;
    /** How far from a hold jail a prisoner may be before leaving counts as being taken away (not an escape). */
    private static final int ESCAPE_RANGE = 48;

    /** Ore veins worked recently (dim|x|y|z -> game time free again). */
    private static final Map<String, Long> WORKED = new HashMap<>();

    private Jail() {}

    public static boolean isJailDimension(Level level) {
        return level.dimension() == JAIL;
    }

    public static boolean isJailed(Player player) {
        return Bounty.state(player).contains("jail", Tag.TAG_COMPOUND);
    }

    /** Seconds of sentence left (0 when not jailed). Works on both sides. */
    public static int remaining(Player player) {
        return isJailed(player) ? Bounty.state(player).getCompound("jail").getInt("remaining") : 0;
    }

    public static int sentenceSeconds(int bounty) {
        return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, bounty * SECONDS_PER_100 / 100));
    }

    // ------------------------------------------------------------------ sites

    /**
     * Where a prisoner serves: a cell of a hold jail in the overworld ({@code hold}) or a cell block of the jail
     * dimension. {@code evidence} are the two halves of the cell's evidence chest; {@code min}/{@code max} the jail's
     * bounds (the whole cell block in the dimension).
     */
    record Site(boolean hold, String place, int cell, ServerLevel level, BlockPos stand, @Nullable BlockPos door,
                @Nullable BlockPos gate, List<BlockPos> evidence, @Nullable BlockPos exit, BlockPos min, BlockPos max,
                boolean mine, @Nullable BlockPos drain) {

        boolean contains(Level l, double x, double y, double z) {
            return l == level && x >= min.getX() - 0.5 && x <= max.getX() + 1.5 && y >= min.getY() - 1 && y <= max.getY() + 2
                    && z >= min.getZ() - 0.5 && z <= max.getZ() + 1.5;
        }

        boolean contains(Level l, BlockPos p) {
            return contains(l, p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
        }

        double distanceOutside(double x, double z) {
            double dx = Math.max(0, Math.max(min.getX() - x, x - max.getX() - 1));
            double dz = Math.max(0, Math.max(min.getZ() - z, z - max.getZ() - 1));
            return Math.sqrt(dx * dx + dz * dz);
        }

        String key() {
            return place + "#" + cell;
        }

        List<Container> chests() {
            List<Container> out = new ArrayList<>(2);
            for (BlockPos p : evidence) {
                if (level.getBlockEntity(p) instanceof ChestBlockEntity chest) out.add(chest);
            }
            return out;
        }
    }

    /** The cell block of the jail dimension that belongs to the player. */
    static Site dimensionSite(MinecraftServer server, ServerLevel jail, UUID player) {
        int slot = JailData.get(server).slotFor(player);
        BlockPos o = cellOrigin(slot);
        return new Site(false, "", slot, jail, o.offset(7, 1, 4), null, null, List.of(o.offset(2, 1, 1), o.offset(3, 1, 1)), null,
                o, o.offset(11, 4, 25), false, o.offset(2, 1, 23));
    }

    /** Cell {@code cell} of the hold jail recorded for a place, or null if the jail isn't (fully) known. */
    @Nullable
    static Site holdSite(ServerLevel overworld, String place, int cell) {
        Optional<SettlementAnchors.Entry> entry = SettlementAnchors.find(overworld, place);
        if (entry.isEmpty()) return null;
        SettlementAnchors.Entry e = entry.get();
        List<Anchor> cells = e.anchors(AnchorType.JAIL_CELL);
        List<Anchor> doors = e.anchors(AnchorType.JAIL_DOOR);
        List<Anchor> chests = e.anchors(AnchorType.JAIL_EVIDENCE);
        Anchor min = e.first(AnchorType.JAIL_MIN), max = e.first(AnchorType.JAIL_MAX);
        if (cell < 0 || cell >= cells.size() || min == null || max == null || chests.size() < 2 * (cell + 1)) return null;
        Anchor gate = e.first(AnchorType.JAIL_GATE), exit = e.first(AnchorType.JAIL_EXIT);
        BlockPos lo = new BlockPos(Math.min(min.pos().getX(), max.pos().getX()), Math.min(min.pos().getY(), max.pos().getY()),
                Math.min(min.pos().getZ(), max.pos().getZ()));
        BlockPos hi = new BlockPos(Math.max(min.pos().getX(), max.pos().getX()), Math.max(min.pos().getY(), max.pos().getY()),
                Math.max(min.pos().getZ(), max.pos().getZ()));
        boolean mine = cells.get(cell).owner().startsWith("cidhna_mine");
        return new Site(true, place, cell, overworld, cells.get(cell).pos(), cell < doors.size() ? doors.get(cell).pos() : null,
                gate == null ? null : gate.pos(), List.of(chests.get(2 * cell).pos(), chests.get(2 * cell + 1).pos()),
                exit == null ? null : exit.pos(), lo, hi, mine, null);
    }

    /** The place whose jail serves a hold: its capital's, else the nearest city jail of the region, else the nearest. */
    @Nullable
    static String jailPlaceFor(ServerLevel overworld, String hold, BlockPos near) {
        SettlementAnchors data = SettlementAnchors.get(overworld);
        Optional<Region> region = Regions.byId(com.skycraft.core.Holds.migrate(hold));
        if (region.isPresent() && region.get().capital() != null) {
            SettlementAnchors.Entry e = data.entry(region.get().capital());
            if (e != null && e.first(AnchorType.JAIL_CELL) != null) return e.key;
        }
        String best = null, bestAny = null;
        long bestD = Long.MAX_VALUE, bestAnyD = 1500L * 1500L;
        for (SettlementAnchors.Entry e : data.entries()) {
            if (e.first(AnchorType.JAIL_CELL) == null) continue;
            long dx = e.x - near.getX(), dz = e.z - near.getZ();
            long d = dx * dx + dz * dz;
            String regionId = e.place().map(Place::regionId).orElse("");
            if (regionId.equals(hold) && d < bestD) {
                bestD = d;
                best = e.key;
            }
            if (d < bestAnyD) {
                bestAnyD = d;
                bestAny = e.key;
            }
        }
        return best != null ? best : bestAny;
    }

    /** A free cell of the place's jail for the player (not another prisoner's, its evidence chest not holding another's things). */
    @Nullable
    static Site claimCell(ServerLevel overworld, String place, ServerPlayer player) {
        Optional<SettlementAnchors.Entry> entry = SettlementAnchors.find(overworld, place);
        if (entry.isEmpty()) return null;
        Set<BlockPos> npcCells = new HashSet<>();
        for (Anchor a : entry.get().anchors(AnchorType.JAIL_PRISONER)) npcCells.add(a.pos());
        JailData jd = JailData.get(player.server);
        int n = entry.get().anchors(AnchorType.JAIL_CELL).size();
        for (int cell = 0; cell < n; cell++) {
            Site s = holdSite(overworld, place, cell);
            if (s == null || npcCells.contains(s.stand())) continue;
            UUID inside = jd.occupied.get(s.key());
            if (inside != null && !inside.equals(player.getUUID())) continue;
            UUID owner = jd.chestOwner.get(s.key());
            if (owner != null && !owner.equals(player.getUUID())) {
                overworld.getChunk(s.evidence().get(0));
                if (!empty(s.chests())) continue;
                jd.chestOwner.remove(s.key());
            }
            return s;
        }
        return null;
    }

    private static boolean empty(List<Container> chests) {
        for (Container c : chests) if (!c.isEmpty()) return false;
        return true;
    }

    /** The player's current site from their jail state (null if their hold jail is gone). */
    @Nullable
    static Site siteOf(ServerPlayer player) {
        CompoundTag j = Bounty.state(player).getCompound("jail");
        if ("hold".equals(j.getString("site"))) return holdSite(player.server.overworld(), j.getString("place"), j.getInt("cell"));
        ServerLevel jail = player.server.getLevel(JAIL);
        return jail == null ? null : dimensionSite(player.server, jail, player.getUUID());
    }

    /** Whether a block lies inside a hold jail or the jail dimension (no theft or ownership there; jail keys work). */
    public static boolean isJailArea(Level level, BlockPos pos) {
        if (isJailDimension(level)) return true;
        if (!(level instanceof ServerLevel sl) || level.dimension() != Level.OVERWORLD) return false;
        Optional<SettlementAnchors.Entry> e = SettlementAnchors.near(sl, pos, 0);
        if (e.isEmpty()) return false;
        Anchor min = e.get().first(AnchorType.JAIL_MIN), max = e.get().first(AnchorType.JAIL_MAX);
        if (min == null || max == null) return false;
        return new AABB(min.pos(), max.pos()).inflate(0.5).contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    // ------------------------------------------------------------------ going to jail

    /** "I'll come quietly": serve the sentence for {@code hold}'s bounty. */
    public static void send(ServerPlayer player, String hold) {
        send(player, hold, Bounty.get(player, hold), null);
    }

    /**
     * A story sends the player to a city's jail (the Forsworn Conspiracy ends in Cidhna Mine), whatever their
     * bounty: the longest sentence. A successful escape sets the story flag {@code escaped_<place>}.
     */
    public static void imprison(ServerPlayer player, String placeId) {
        if (isJailed(player)) return;
        String hold = Places.byId(placeId).map(Place::regionId).orElse(com.skycraft.core.Holds.holdAt(player.level(), player.blockPosition()));
        send(player, hold, MAX_SECONDS * 100 / SECONDS_PER_100, placeId);
    }

    private static void send(ServerPlayer player, String hold, int bounty, @Nullable String forcedPlace) {
        if (isJailed(player)) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        String place = forcedPlace != null && SettlementAnchors.find(overworld, forcedPlace).map(e -> e.first(AnchorType.JAIL_CELL) != null).orElse(false)
                ? forcedPlace : forcedPlace == null ? jailPlaceFor(overworld, hold, player.blockPosition()) : null;
        Site site = place == null ? null : claimCell(overworld, place, player);
        if (site == null) {
            ServerLevel jail = server.getLevel(JAIL);
            if (jail == null) {
                Skycraft.LOGGER.error("Jail dimension {} is missing and no hold jail is known; the arrest is dropped", JAIL.location());
                return;
            }
            site = dimensionSite(server, jail, player.getUUID());
        }
        PlayerData data = SkyData.get(player);
        CompoundTag j = new CompoundTag();
        j.putString("hold", hold);
        j.putInt("bounty", bounty);
        j.putInt("remaining", sentenceSeconds(bounty));
        j.putInt("served", 0);
        j.putString("site", site.hold() ? "hold" : "dim");
        j.putString("place", site.hold() ? site.place() : forcedPlace == null ? "" : forcedPlace);
        j.putInt("cell", site.cell());
        j.putString("rdim", player.level().dimension().location().toString());
        j.putDouble("rx", player.getX());
        j.putDouble("ry", player.getY());
        j.putDouble("rz", player.getZ());
        j.putFloat("ryaw", player.getYRot());
        j.putFloat("rpitch", player.getXRot());
        Bounty.state(player).put("jail", j);
        Bounty.state(player).remove("hostile_until");
        data.markDirty();
        if (site.hold()) {
            JailData jd = JailData.get(server);
            jd.occupied.put(site.key(), player.getUUID());
            jd.chestOwner.put(site.key(), player.getUUID());
            jd.setDirty();
        }

        Guards.pacify(player);
        player.closeContainer();
        player.stopRiding();
        Bounty.confiscate(player);
        Component where = site.hold() ? jailName(site) : Component.translatable("crime.skycraft.jail.dimension");
        Notifier.title(player, Component.translatable("crime.skycraft.jail.title"),
                Component.translatable("crime.skycraft.jail.sentence", formatTime(sentenceSeconds(bounty))));
        if (site.hold()) Notifier.message(player, Component.translatable("crime.skycraft.jail.taken_to", where));
        teleportToCell(player, site);

        // Move possessions to the Evidence Chest (overflow is held in JailData; nothing is ever deleted), keeping
        // quest items and up to 2 lockpicks if skilled
        List<Container> chests = site.chests();
        var inv = player.getInventory();
        int lockpickCount = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(CrimeItems.LOCKPICK.get())) lockpickCount += s.getCount();
        }
        lockpickCount += com.skycraft.inventory.Pack.count(player, CrimeItems.LOCKPICK.get());
        int keep = (data.getSkill(Skill.LOCKPICKING) >= 20 || data.getSkill(Skill.SNEAK) >= 20
                || data.hasPerk(Skill.LOCKPICKING.id() + ".novice_locks"))
                ? Math.min(2, lockpickCount) : (lockpickCount > 0 ? 1 : 0);
        int kept = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || QuestItems.isQuestItem(s)) continue;
            if (s.is(CrimeItems.LOCKPICK.get()) && kept < keep) {
                int take = Math.min(s.getCount(), keep - kept);
                kept += take;
                if (s.getCount() > take) stash(player, chests, s.split(s.getCount() - take));
                continue;
            }
            inv.setItem(i, ItemStack.EMPTY);
            stash(player, chests, s);
        }
        // the overflow pack (inventory.Pack) is confiscated like the slots: quest items and the kept picks stay
        for (ItemStack e : com.skycraft.inventory.Pack.entries(player)) {
            if (e.isEmpty() || QuestItems.isQuestItem(e)) continue;
            int take = e.getCount();
            if (e.is(CrimeItems.LOCKPICK.get()) && kept < keep) {
                int k = Math.min(take, keep - kept);
                kept += k;
                take -= k;
            }
            if (take <= 0) continue;
            ItemStack all = com.skycraft.inventory.Pack.take(player, com.skycraft.inventory.Pack.indexOf(player, e), take);
            while (!all.isEmpty()) stash(player, chests, all.split(all.getMaxStackSize()));
        }
        if (site.mine()) {
            ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
            pick.setHoverName(Component.translatable("item.skycraft.reward.cidhna_pickaxe"));
            pick.getOrCreateTag().putBoolean(TOOL_TAG, true);
            com.skycraft.inventory.Pack.giveOrDrop(player, pick);
            Notifier.message(player, Component.translatable("crime.skycraft.jail.mine_labour", LABOUR_SECONDS));
        }
        player.inventoryMenu.broadcastChanges();
        if (kept > 0) {
            Notifier.message(player, Component.translatable("crime.skycraft.jail.kept_picks", kept));
        }
    }

    static Component jailName(Site site) {
        Optional<SettlementAnchors.Entry> e = SettlementAnchors.find(site.level(), site.place());
        String owner = e.map(en -> en.first(AnchorType.JAIL_CELL)).map(Anchor::owner).orElse("jail");
        int hash = owner.indexOf('#');
        String name = hash < 0 ? owner : owner.substring(0, hash);
        Component placeName = Places.byId(site.place()).map(Place::name).orElse(Component.literal(site.place()));
        if (name.equals("jail")) return Component.translatableWithFallback("settlement.skycraft.jail_of", "%s Jail", placeName);
        return Component.translatableWithFallback("settlement.skycraft.name." + name, name);
    }

    // ------------------------------------------------------------------ evidence chest

    /** Both halves of the cell block's evidence double chest (an empty list if it is missing). */
    private static List<Container> evidenceChests(ServerLevel jail, BlockPos origin) {
        List<Container> out = new ArrayList<>(2);
        for (BlockPos p : new BlockPos[]{origin.offset(2, 1, 1), origin.offset(3, 1, 1)}) {
            if (jail.getBlockEntity(p) instanceof ChestBlockEntity chest) out.add(chest);
        }
        return out;
    }

    /** Places the evidence double chest (54 slots, more than a whole inventory); never touches its contents. */
    private static void ensureEvidenceChest(ServerLevel level, BlockPos o) {
        BlockPos right = o.offset(2, 1, 1);
        BlockPos left = o.offset(3, 1, 1);
        BlockState base = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH);
        // same block -> the block entity (and its items) is kept, only the half type changes
        level.setBlock(right, base.setValue(ChestBlock.TYPE, ChestType.RIGHT), 3);
        level.setBlock(left, base.setValue(ChestBlock.TYPE, ChestType.LEFT), 3);
        Locks.setLock(level, right, Locks.MASTER);
        Locks.setLock(level, left, Locks.MASTER);
    }

    /** A hold jail's cell made ready for a new prisoner: chests present and locked, the doors shut and locked. */
    private static void prepareHoldCell(ServerPlayer player, Site s) {
        ServerLevel level = s.level();
        level.getChunk(s.stand());
        for (BlockPos p : s.evidence()) {
            if (!(level.getBlockEntity(p) instanceof ChestBlockEntity)) {
                // a broken evidence chest is replaced (single chests: still 54 slots in all)
                level.setBlock(p, Blocks.CHEST.defaultBlockState(), 3);
            }
            Locks.setLock(level, p, Locks.ADEPT);
            relock(level, p);
        }
        lockDoor(level, s.door(), Locks.APPRENTICE);
        lockDoor(level, s.gate(), Locks.ADEPT);
    }

    private static void lockDoor(ServerLevel level, @Nullable BlockPos door, int tier) {
        if (door == null) return;
        BlockState st = level.getBlockState(door);
        if (st.getBlock() instanceof DoorBlock d && st.getValue(DoorBlock.OPEN)) d.setOpen(null, level, st, door, false);
        Locks.setLock(level, door, tier);
        relock(level, door);
    }

    /** Forgets who picked this lock, so the next prisoner has to pick it again. */
    private static void relock(ServerLevel level, BlockPos pos) {
        Locks.LockData data = Locks.LockData.get(level.getServer());
        if (data.unlocked.remove(Locks.key(level, Locks.normalizePos(level, pos))) != null) data.setDirty();
    }

    /** Merges {@code stack} into the containers; returns whatever did not fit (possibly empty). */
    private static ItemStack insert(List<Container> containers, ItemStack stack) {
        for (Container c : containers) {
            for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
                ItemStack in = c.getItem(i);
                if (in.isEmpty() || !ItemStack.isSameItemSameTags(in, stack)) continue;
                int move = Math.min(stack.getCount(), Math.min(in.getMaxStackSize(), c.getMaxStackSize()) - in.getCount());
                if (move > 0) {
                    in.grow(move);
                    stack.shrink(move);
                    c.setChanged();
                }
            }
        }
        for (Container c : containers) {
            for (int i = 0; i < c.getContainerSize() && !stack.isEmpty(); i++) {
                if (!c.getItem(i).isEmpty()) continue;
                c.setItem(i, stack.copy());
                stack.setCount(0);
                c.setChanged();
            }
        }
        return stack.isEmpty() ? ItemStack.EMPTY : stack;
    }

    /** Confiscates {@code stack} into the evidence chest; overflow is held safely in {@link JailData}. */
    private static void stash(ServerPlayer player, List<Container> chests, ItemStack stack) {
        if (stack.isEmpty()) return;
        ItemStack rest = insert(chests, stack);
        if (!rest.isEmpty()) {
            JailData data = JailData.get(player.server);
            data.held.computeIfAbsent(player.getUUID(), k -> new ArrayList<>()).add(rest.copy());
            data.setDirty();
        }
    }

    /** Moves held overflow into the evidence chest as room frees up, so escapees can still find everything there. */
    private static void refillChest(ServerPlayer player, Site site) {
        JailData data = JailData.get(player.server);
        List<ItemStack> held = data.held.get(player.getUUID());
        if (held == null || held.isEmpty()) return;
        List<Container> chests = site.chests();
        if (chests.isEmpty()) return;
        for (var it = held.iterator(); it.hasNext(); ) {
            ItemStack rest = insert(chests, it.next());
            if (!rest.isEmpty()) break;
            it.remove();
        }
        if (held.isEmpty()) data.held.remove(player.getUUID());
        data.setDirty();
    }

    /** Hands a confiscated item back: armor to its empty slot, then the bags, then at the player's feet. */
    private static void giveBack(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) return;
        EquipmentSlot slot = LivingEntity.getEquipmentSlotForItem(stack);
        if (slot.getType() == EquipmentSlot.Type.ARMOR && player.getItemBySlot(slot).isEmpty()) {
            player.setItemSlot(slot, stack);
            return;
        }
        if (!ActionHandler.addToBags(player, stack) && !stack.isEmpty()) player.drop(stack, false);
    }

    private static void teleportToCell(ServerPlayer player, Site site) {
        if (site.hold()) {
            prepareHoldCell(player, site);
            BlockPos s = site.stand();
            player.teleportTo(site.level(), s.getX() + 0.5, s.getY(), s.getZ() + 0.5, player.getYRot(), 0f);
            return;
        }
        ServerLevel jail = site.level();
        int slot = site.cell();
        BlockPos origin = cellOrigin(slot);
        JailData data = JailData.get(player.server);
        boolean fresh = !data.built.contains(slot);
        // the partition wall beside the evidence chest is always stone; air there means the cell block is gone
        if (fresh || jail.getBlockState(origin.offset(5, 1, 1)).isAir()) {
            buildCell(jail, origin, fresh);
            data.built.add(slot);
            data.setDirty();
        } else if (!(jail.getBlockEntity(origin.offset(3, 1, 1)) instanceof ChestBlockEntity)) {
            ensureEvidenceChest(jail, origin); // cells built before the evidence chest became a double chest
        }
        player.teleportTo(jail, origin.getX() + 7.5, CELL_Y + 1, origin.getZ() + 4.5, 90f, 0f);
    }

    static BlockPos cellOrigin(int slot) {
        return new BlockPos(slot * CELL_SPACING, CELL_Y, 0);
    }

    /**
     * A hold jail with central corridor, evidence chest, guard patrol, and multiple cells with NPCs. Rebuilding never
     * touches the evidence chest's contents; the prisoners and the guard are only spawned the first time
     * ({@code spawnNpcs}).
     */
    private static void buildCell(ServerLevel level, BlockPos o, boolean spawnNpcs) {
        BlockState wall = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = 0; x <= 11; x++) {
            for (int y = 0; y <= 4; y++) {
                for (int z = 0; z <= 25; z++) {
                    if (y == 1 && z == 1 && (x == 2 || x == 3)) continue; // evidence chest
                    boolean shell = x == 0 || x == 11 || y == 0 || y == 4 || z == 0 || z == 25;
                    BlockState s = shell ? (y == 0 && (x + z) % 3 == 0 ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState()
                            : y > 0 && y < 4 && (x * 7 + z * 3 + y) % 5 == 0 ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState() : wall) : air;
                    level.setBlock(o.offset(x, y, z), s, 2);
                }
            }
        }

        // Inner partition separating corridor (x: 1..4) from cells (x: 6..10)
        for (int z = 1; z <= 24; z++) {
            for (int y = 1; y <= 3; y++) {
                level.setBlock(o.offset(5, y, z), wall, 2);
            }
        }
        // Dividers between cells
        for (int x = 6; x <= 10; x++) {
            for (int y = 1; y <= 3; y++) {
                level.setBlock(o.offset(x, y, 8), wall, 2);
                level.setBlock(o.offset(x, y, 16), wall, 2);
            }
        }

        // Corridor lanterns
        level.setBlock(o.offset(2, 3, 4), Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true), 2);
        level.setBlock(o.offset(2, 3, 12), Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true), 2);
        level.setBlock(o.offset(2, 3, 20), Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true), 2);

        // North end: Evidence Chest
        ensureEvidenceChest(level, o);

        // South end: Old sewer grate / Escape hatch
        BlockPos escapePos = o.offset(2, 1, 23);
        level.setBlock(escapePos, Blocks.IRON_TRAPDOOR.defaultBlockState(), 3);

        // Setup Cell 1 (Player's cell: z: 2..7)
        setupCell(level, o, 4, 3, true);
        // Setup Cell 2 (NPC Outlaw: z: 9..15)
        setupCell(level, o, 12, 11, false);
        // Setup Cell 3 (NPC Thief: z: 17..23)
        setupCell(level, o, 20, 19, false);
        if (!spawnNpcs) return;

        // Spawn prisoners in Cells 2 and 3 (once per cell block; tagged so they can be told apart)
        try {
            var prisoner1 = net.minecraft.world.entity.EntityType.VILLAGER.create(level);
            if (prisoner1 != null) {
                prisoner1.setCustomName(Component.translatable("crime.skycraft.jail.npc.outlaw"));
                prisoner1.setCustomNameVisible(true);
                prisoner1.setPos(o.getX() + 7.5, CELL_Y + 1, o.getZ() + 12.5);
                prisoner1.addTag(NPC_TAG);
                prisoner1.setPersistenceRequired();
                level.addFreshEntity(prisoner1);
            }
            var prisoner2 = net.minecraft.world.entity.EntityType.VILLAGER.create(level);
            if (prisoner2 != null) {
                prisoner2.setCustomName(Component.translatable("crime.skycraft.jail.npc.thief"));
                prisoner2.setCustomNameVisible(true);
                prisoner2.setPos(o.getX() + 7.5, CELL_Y + 1, o.getZ() + 20.5);
                prisoner2.addTag(NPC_TAG);
                prisoner2.setPersistenceRequired();
                level.addFreshEntity(prisoner2);
            }
            // Spawn patrolling Guard in corridor
            var guard = com.skycraft.creatures.ModEntities.GUARD.get().create(level);
            if (guard != null) {
                guard.setCustomName(Component.translatable("crime.skycraft.jail.npc.guard"));
                guard.setCustomNameVisible(true);
                guard.setPos(o.getX() + 2.5, CELL_Y + 1, o.getZ() + 12.5);
                guard.restrictTo(o.offset(2, 1, 12), 10);
                guard.addTag(NPC_TAG);
                guard.setPersistenceRequired();
                level.addFreshEntity(guard);
            }
        } catch (Exception ignored) {}
    }

    private static void setupCell(ServerLevel level, BlockPos o, int doorZ, int bedZ, boolean playerCell) {
        // Iron Door facing corridor
        BlockPos doorBottom = o.offset(5, 1, doorZ);
        BlockPos doorTop = o.offset(5, 2, doorZ);
        level.setBlock(doorBottom, Blocks.IRON_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER).setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.WEST), 2);
        level.setBlock(doorTop, Blocks.IRON_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER).setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.WEST), 2);
        Locks.setLock(level, doorBottom, Locks.MASTER);

        // Iron bars beside door
        BlockState bars = Blocks.IRON_BARS.defaultBlockState().setValue(IronBarsBlock.NORTH, true).setValue(IronBarsBlock.SOUTH, true);
        for (int dz = -2; dz <= 2; dz++) {
            if (dz == 0) continue;
            level.setBlock(o.offset(5, 1, doorZ + dz), bars, 2);
            level.setBlock(o.offset(5, 2, doorZ + dz), bars, 2);
        }

        // Bed inside cell
        BlockPos head = o.offset(9, 1, bedZ);
        BlockPos foot = o.offset(9, 1, bedZ + 1);
        level.setBlock(head, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.HEAD), 2);
        level.setBlock(foot, Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.FOOT), 2);

        // Cell furnishings
        level.setBlock(o.offset(9, 1, doorZ + 2), Blocks.CAULDRON.defaultBlockState(), 2);
        level.setBlock(o.offset(7, 3, doorZ), Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true), 2);
    }

    // ------------------------------------------------------------------ serving the sentence

    /** Every second. */
    static void tick(ServerPlayer player) {
        if (!isJailed(player)) return;
        CompoundTag j = Bounty.state(player).getCompound("jail");
        Site site = siteOf(player);
        if (site == null) {
            // the hold jail is gone (or the dimension is missing): serve in the jail dimension instead
            ServerLevel jail = player.server.getLevel(JAIL);
            if (jail == null) return;
            j.putString("site", "dim");
            SkyData.get(player).markDirty();
            if (player.isAlive()) teleportToCell(player, dimensionSite(player.server, jail, player.getUUID()));
            return;
        }
        boolean inside = site.hold() ? site.contains(player.level(), player.getX(), player.getY(), player.getZ()) : isJailDimension(player.level());
        if (!inside) {
            if (!player.isAlive()) return;
            if (player.isCreative() || player.isSpectator()) {
                String hold = j.getString("hold");
                freeCell(player, site);
                Bounty.state(player).remove("jail");
                SkyData.get(player).markDirty();
                Bounty.add(player, hold, Bounty.ESCAPE);
                return;
            }
            // walked out of a hold jail: that's an escape; anything else (death, a portal) brings them back
            if (site.hold() && player.level() == site.level() && site.distanceOutside(player.getX(), player.getZ()) < ESCAPE_RANGE) {
                escape(player);
                return;
            }
            teleportToCell(player, site);
            return;
        }
        refillChest(player, site);
        j.putInt("served", j.getInt("served") + 1);
        int left = j.getInt("remaining") - 1;
        if (left <= 0) {
            release(player, false);
        } else {
            j.putInt("remaining", left);
            SkyData.get(player).markDirty();
        }
    }

    private static void freeCell(ServerPlayer player, Site site) {
        removeTools(player);
        if (!site.hold()) return;
        JailData jd = JailData.get(player.server);
        if (player.getUUID().equals(jd.occupied.get(site.key()))) {
            jd.occupied.remove(site.key());
            jd.setDirty();
        }
    }

    /** Takes back the jail's tools (Cidhna Mine's pickaxe). */
    private static void removeTools(ServerPlayer player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getTag() != null && s.getTag().getBoolean(TOOL_TAG)) inv.setItem(i, ItemStack.EMPTY);
        }
        com.skycraft.inventory.Pack.remove(player, s -> s.getTag() != null && s.getTag().getBoolean(TOOL_TAG), Integer.MAX_VALUE);
    }

    static void release(ServerPlayer player, boolean slept) {
        CompoundTag state = Bounty.state(player);
        if (!state.contains("jail", Tag.TAG_COMPOUND)) return;
        Site site = siteOf(player);
        CompoundTag j = state.getCompound("jail");
        state.remove("jail");
        String hold = j.getString("hold");
        int bounty = Math.max(j.getInt("bounty"), 1);
        Bounty.clear(player, hold);

        // Restore non-stolen possessions from the Evidence Chest and the overflow held in JailData
        JailData jd = JailData.get(player.server);
        if (site != null) {
            freeCell(player, site);
            boolean mine = !site.hold() || player.getUUID().equals(jd.chestOwner.get(site.key()));
            if (mine) {
                for (Container chest : site.chests()) {
                    for (int i = 0; i < chest.getContainerSize(); i++) {
                        ItemStack s = chest.getItem(i);
                        if (s.isEmpty()) continue;
                        chest.setItem(i, ItemStack.EMPTY);
                        if (!Bounty.isStolen(s)) giveBack(player, s);
                    }
                    chest.setChanged();
                }
                if (site.hold()) jd.chestOwner.remove(site.key());
            }
        }
        List<ItemStack> held = jd.held.remove(player.getUUID());
        if (held != null) for (ItemStack s : held) if (!Bounty.isStolen(s)) giveBack(player, s);
        jd.setDirty();
        player.inventoryMenu.broadcastChanges();

        List<Skill> lost = deteriorate(player, Math.max(1, bounty / 100));
        MutableComponent skills = Component.empty();
        for (int i = 0; i < lost.size(); i++) {
            if (i > 0) skills.append(", ");
            skills.append(lost.get(i).displayName());
        }
        Notifier.title(player, Component.translatable(slept ? "crime.skycraft.jail.slept" : "crime.skycraft.jail.released"),
                Component.translatable("crime.skycraft.jail.deteriorate"));
        if (!lost.isEmpty()) Notifier.message(player, Component.translatable("crime.skycraft.jail.lost", skills));
        passDays(player, Math.max(1, Math.min(7, bounty / 100)));

        if (site != null && site.hold()) {
            // released at the jail's door
            BlockPos out = site.exit() != null ? site.exit() : site.stand();
            player.teleportTo(site.level(), out.getX() + 0.5, out.getY(), out.getZ() + 0.5, player.getYRot(), 0f);
        } else {
            ServerLevel target = null;
            ResourceLocation dimId = ResourceLocation.tryParse(j.getString("rdim"));
            if (dimId != null) target = player.server.getLevel(ResourceKey.create(Registries.DIMENSION, dimId));
            if (target == null || target.dimension() == JAIL) {
                target = player.server.overworld();
                BlockPos spawn = target.getSharedSpawnPos();
                player.teleportTo(target, spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0f, 0f);
            } else {
                player.teleportTo(target, j.getDouble("rx"), j.getDouble("ry"), j.getDouble("rz"), j.getFloat("ryaw"), j.getFloat("rpitch"));
            }
        }
        SkyData.get(player).markDirty();
    }

    /**
     * The days of a sentence go by (Skyrim: a day per hundred gold of bounty). Only when nobody else is about to be
     * hurried along with them: a single-player world, or the only player online.
     */
    private static void passDays(ServerPlayer player, int days) {
        MinecraftServer server = player.server;
        boolean alone = server.getPlayerList().getPlayerCount() <= 1;
        if (!alone) return;
        ServerLevel overworld = server.overworld();
        overworld.setDayTime(overworld.getDayTime() + 24000L * days);
        Notifier.message(player, Component.translatable("crime.skycraft.jail.days", days));
    }

    /** The prisoner is out. Escaping always costs an escape bounty; guards who saw it come after the prisoner. */
    public static void escape(ServerPlayer player) {
        if (!isJailed(player)) return;
        boolean detected = false;
        for (Mob m : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16),
                e -> Crimes.isGuard(e) && e.isAlive())) {
            if (m.getTarget() == player || m.hasLineOfSight(player)) {
                detected = true;
                break;
            }
        }
        Site site = siteOf(player);
        CompoundTag j = Bounty.state(player).getCompound("jail");
        String hold = j.getString("hold");
        String place = j.getString("place");
        Bounty.state(player).remove("jail");
        SkyData.get(player).markDirty();
        if (site != null) freeCell(player, site);
        if (!place.isEmpty()) com.skycraft.quest.story.Story.setFlag(SkyData.get(player), "escaped_" + place, true);

        Bounty.add(player, hold, Bounty.ESCAPE);
        if (detected) {
            Guards.attack(player, 24);
            Notifier.title(player, Component.translatable("crime.skycraft.jail.escaped"), Component.translatable("crime.skycraft.jail.escaped_seen"));
        } else {
            Notifier.title(player, Component.translatable("crime.skycraft.jail.escaped"), Component.translatable("crime.skycraft.jail.escaped_unseen"));
        }
        // whatever was left behind stays in the evidence chest (and JailData) and is handed back on the next release
        if (site != null && site.hold()) return;

        ServerLevel target = null;
        ResourceLocation dimId = ResourceLocation.tryParse(j.getString("rdim"));
        if (dimId != null) target = player.server.getLevel(ResourceKey.create(Registries.DIMENSION, dimId));
        if (target == null || target.dimension() == JAIL) target = player.server.overworld();

        player.teleportTo(target, j.getDouble("rx"), j.getDouble("ry"), j.getDouble("rz"), j.getFloat("ryaw"), j.getFloat("rpitch"));
    }

    /** Skyrim: time in jail erodes skill progress. Picks {@code times} skills weighted by level and resets their progress. */
    private static List<Skill> deteriorate(ServerPlayer player, int times) {
        PlayerData data = SkyData.get(player);
        List<Skill> lost = new ArrayList<>();
        for (int t = 0; t < times; t++) {
            int total = 0;
            for (Skill s : Skill.VALUES) {
                if (!lost.contains(s) && data.getSkillXp(s) > 0) total += Math.max(1, data.getSkill(s));
            }
            if (total <= 0) break;
            int roll = player.getRandom().nextInt(total);
            for (Skill s : Skill.VALUES) {
                if (lost.contains(s) || data.getSkillXp(s) <= 0) continue;
                roll -= Math.max(1, data.getSkill(s));
                if (roll < 0) {
                    data.setSkillXp(s, 0);
                    lost.add(s);
                    break;
                }
            }
        }
        return lost;
    }

    public static String formatTime(int seconds) {
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }

    // ------------------------------------------------------------------ events

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (event.getEntity().isCreative()) return;
        boolean dimension = isJailDimension(level);
        if (!dimension && !(event.getEntity() instanceof ServerPlayer sp && isJailed(sp))) return;
        BlockState state = level.getBlockState(event.getPos());
        BlockPos pos = event.getPos();

        if (event.getEntity() instanceof ServerPlayer player && isJailed(player) && event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND) {
            Site site = siteOf(player);
            boolean here = site != null && (dimension ? site.level() == level : site.contains(level, pos));
            if (here && state.getBlock() instanceof BedBlock) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.FAIL);
                CompoundTag j = Bounty.state(player).getCompound("jail");
                int served = j.getInt("served");
                if (served < 60) {
                    Notifier.message(player, Component.translatable("crime.skycraft.jail.too_soon", 60 - served));
                } else {
                    release(player, true);
                }
                return;
            }

            if (dimension && site != null && pos.equals(site.drain())) {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                escape(player);
                return;
            }
        }
        if (!dimension) return;

        // Allow doors, trapdoors, and container block entities to be interacted with (for lockpicking and looting)
        if (state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock
                || state.getBlock() instanceof net.minecraft.world.level.block.TrapDoorBlock
                || level.getBlockEntity(pos) != null) {
            return;
        }

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.FAIL);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBreak(BlockEvent.BreakEvent event) {
        Player p = event.getPlayer();
        if (p.isCreative()) return;
        if (isJail(event.getLevel())) {
            event.setCanceled(true);
            return;
        }
        if (!(p instanceof ServerPlayer player) || !isJailed(player)) return;
        // a prisoner can't dig their way out; in Cidhna Mine the silver veins are worked for time off
        event.setCanceled(true);
        Site site = siteOf(player);
        if (site == null || !site.mine() || !site.contains(player.level(), event.getPos())) return;
        BlockState st = event.getState();
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(st.getBlock());
        if (!id.getPath().endsWith("_ore")) return;
        String key = event.getPos().asLong() + "";
        long now = player.level().getGameTime();
        Long free = WORKED.get(key);
        if (free != null && now < free) {
            Notifier.message(player, Component.translatable("crime.skycraft.jail.vein_worked"));
            return;
        }
        WORKED.put(key, now + 20L * 120);
        if (WORKED.size() > 512) WORKED.values().removeIf(t -> t < now);
        CompoundTag j = Bounty.state(player).getCompound("jail");
        int left = Math.max(1, j.getInt("remaining") - LABOUR_SECONDS);
        j.putInt("remaining", left);
        SkyData.get(player).markDirty();
        player.level().playSound(null, event.getPos(), SoundEvents.STONE_BREAK, SoundSource.BLOCKS, 1f, 0.8f);
        Notifier.message(player, Component.translatable("crime.skycraft.jail.labour", formatTime(left)));
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player p && p.isCreative()) return;
        if (isJail(event.getLevel()) || event.getEntity() instanceof ServerPlayer sp && isJailed(sp)) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (isJailDimension(event.getLevel()) && !event.getEntity().isCreative()) event.setCanceled(true);
    }

    private static boolean isJail(LevelAccessor level) {
        return level instanceof Level l && isJailDimension(l);
    }

    /** A prisoner who dies in jail doesn't lose what they carried: it goes into the evidence chest. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onDrops(LivingDropsEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player) || !isJailed(player)) return;
        Site site = siteOf(player);
        if (site == null || player.level() != site.level()) return;
        if (site.hold() && !site.contains(player.level(), player.getX(), player.getY(), player.getZ())) return;
        List<Container> chests = site.chests();
        for (ItemEntity drop : event.getDrops()) {
            ItemStack s = drop.getItem().copy();
            if (s.getTag() != null && s.getTag().getBoolean(TOOL_TAG)) continue;
            stash(player, chests, s);
        }
        event.getDrops().clear();
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !isJailed(player) || player.isCreative()) return;
        Site site = siteOf(player);
        if (site == null) return;
        boolean inside = site.hold() ? site.contains(player.level(), player.getX(), player.getY(), player.getZ()) : isJailDimension(player.level());
        if (!inside) teleportToCell(player, site);
    }

    // ------------------------------------------------------------------ saved data

    /**
     * Which jail-dimension cell belongs to which player, which cells are built, confiscated items that didn't fit
     * the evidence chest (handed back on release), and the hold jails' cells: who is locked in which, and whose
     * things are in its evidence chest. Stored with the overworld.
     */
    public static class JailData extends SavedData {
        private static final String NAME = "skycraft_jail";
        final Map<UUID, Integer> slots = new HashMap<>();
        final Set<Integer> built = new HashSet<>();
        final Map<UUID, List<ItemStack>> held = new HashMap<>();
        /** Hold jail cell ({@code place#cell}) -> the prisoner in it. */
        final Map<String, UUID> occupied = new HashMap<>();
        /** Hold jail cell -> whose confiscated things its evidence chest holds. */
        final Map<String, UUID> chestOwner = new HashMap<>();
        int next = 0;

        static JailData get(MinecraftServer server) {
            return server.overworld().getDataStorage().computeIfAbsent(JailData::load, JailData::new, NAME);
        }

        int slotFor(UUID player) {
            Integer slot = slots.get(player);
            if (slot == null) {
                slot = next++;
                slots.put(player, slot);
                setDirty();
            }
            return slot;
        }

        static JailData load(CompoundTag tag) {
            JailData data = new JailData();
            data.next = tag.getInt("next");
            CompoundTag slots = tag.getCompound("slots");
            for (String k : slots.getAllKeys()) {
                try {
                    data.slots.put(UUID.fromString(k), slots.getInt(k));
                } catch (IllegalArgumentException ignored) {
                    // corrupt entry
                }
            }
            for (int b : tag.getIntArray("built")) data.built.add(b);
            CompoundTag held = tag.getCompound("held");
            for (String k : held.getAllKeys()) {
                try {
                    List<ItemStack> items = new ArrayList<>();
                    for (Tag t : held.getList(k, Tag.TAG_COMPOUND)) {
                        ItemStack s = ItemStack.of((CompoundTag) t);
                        if (!s.isEmpty()) items.add(s);
                    }
                    if (!items.isEmpty()) data.held.put(UUID.fromString(k), items);
                } catch (IllegalArgumentException ignored) {
                    // corrupt entry
                }
            }
            readCells(tag.getCompound("occupied"), data.occupied);
            readCells(tag.getCompound("chest_owner"), data.chestOwner);
            return data;
        }

        private static void readCells(CompoundTag t, Map<String, UUID> into) {
            for (String k : t.getAllKeys()) {
                if (t.hasUUID(k)) into.put(k, t.getUUID(k));
            }
        }

        private static CompoundTag writeCells(Map<String, UUID> from) {
            CompoundTag t = new CompoundTag();
            from.forEach(t::putUUID);
            return t;
        }

        @Override
        public CompoundTag save(CompoundTag tag) {
            tag.putInt("next", next);
            CompoundTag s = new CompoundTag();
            slots.forEach((k, v) -> s.putInt(k.toString(), v));
            tag.put("slots", s);
            tag.put("built", new IntArrayTag(new ArrayList<>(built)));
            CompoundTag h = new CompoundTag();
            held.forEach((k, items) -> {
                ListTag list = new ListTag();
                for (ItemStack item : items) if (!item.isEmpty()) list.add(item.save(new CompoundTag()));
                if (!list.isEmpty()) h.put(k.toString(), list);
            });
            tag.put("held", h);
            tag.put("occupied", writeCells(occupied));
            tag.put("chest_owner", writeCells(chestOwner));
            return tag;
        }
    }
}
