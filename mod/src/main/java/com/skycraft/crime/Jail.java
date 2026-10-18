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
import net.minecraft.resources.Identifier;
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
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
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
 * minute serves the rest at once, and a served sentence does not end by itself: the prisoner sleeps in the bed (or
 * breaks out) and wakes outside the jail's front door, never back at the crime. Release clears the hold's bounty,
 * hands back what isn't stolen, lets the days of the sentence pass (single player) and costs some skill progress.
 * A prisoner seen outside their cell by a guard is walked back to it ({@link #guardReaction}). Possessions go to the
 * jail's two stores ({@link JailEvidence}, docs/JAIL.md): personal belongings to the prisoner's own evidence (the
 * unlocked evidence chest gives them back, as does release), stolen goods to the shared confiscated goods (a locked
 * barrel, picked every time); both are unbounded, saved in {@link JailData} and never deleted. A prisoner may escape
 * instead: pick the cell door and the cell block gate (or slip down the drain at the end of the corridor) and walk
 * out - escaping adds an escape bounty. In Cidhna Mine the prisoners work the silver veins: every load mined shortens
 * the sentence.</p>
 *
 * <p>State while jailed: {@code crime.jail = {hold, bounty, remaining (seconds), served, site ("hold" | "dim"),
 * place, cell, rdim, rx, ry, rz, ryaw, rpitch}}.</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class Jail {
    public static final ResourceKey<Level> JAIL = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath(Skycraft.MODID, "jail"));
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
    /** Seconds a prisoner seen outside the cell has before a guard who saw them lays hands on them. */
    private static final int LOOSE_SECONDS = 5;
    /** Seconds added to the sentence when a guard drags a prisoner back to the cell. */
    private static final int DRAGGED_BACK_SECONDS = 30;
    /** Seconds a prisoner has been seen outside their cell by a guard. */
    private static final Map<UUID, Integer> LOOSE = new HashMap<>();

    /** Ore veins worked recently (dim|x|y|z -> game time free again). */
    private static final Map<String, Long> WORKED = new HashMap<>();

    private Jail() {}

    public static boolean isJailDimension(Level level) {
        return level.dimension() == JAIL;
    }

    public static boolean isJailed(Player player) {
        return com.skycraft.platform.NbtData.hasType(Bounty.state(player), "jail", Tag.TAG_COMPOUND);
    }

    /** Seconds of sentence left (0 when not jailed). Works on both sides. */
    public static int remaining(Player player) {
        return isJailed(player) ? Bounty.state(player).getCompoundOrEmpty("jail").getIntOr("remaining",0) : 0;
    }

    public static int sentenceSeconds(int bounty) {
        return Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, bounty * SECONDS_PER_100 / 100));
    }

    // ------------------------------------------------------------------ sites

    /**
     * Where a prisoner serves: a cell of a hold jail in the overworld ({@code hold}) or a cell block of the jail
     * dimension. {@code personal} is the evidence chest and {@code confiscated} the shared confiscated goods (see
     * {@link JailEvidence}); {@code evidence} are the older per-cell double chest halves, only read to be migrated;
     * {@code min}/{@code max} the jail's bounds (the whole cell block in the dimension).
     */
    record Site(boolean hold, String place, int cell, ServerLevel level, BlockPos stand, @Nullable BlockPos door,
                @Nullable BlockPos gate, List<BlockPos> evidence, @Nullable BlockPos exit, BlockPos min, BlockPos max,
                boolean mine, @Nullable BlockPos drain, @Nullable BlockPos personal, @Nullable BlockPos confiscated) {

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
    }

    /** The cell block of the jail dimension that belongs to the player. */
    static Site dimensionSite(MinecraftServer server, ServerLevel jail, UUID player) {
        int slot = JailData.get(server).slotFor(player);
        BlockPos o = cellOrigin(slot);
        return new Site(false, "", slot, jail, o.offset(7, 1, 4), o.offset(5, 1, 4), o.offset(2, 1, 3), List.of(o.offset(2, 1, 1), o.offset(3, 1, 1)), null,
                o, o.offset(11, 4, 25), false, o.offset(2, 1, 23), o.offset(1, 1, 1), o.offset(4, 1, 1));
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
        if (cell < 0 || cell >= cells.size() || min == null || max == null) return null;
        Anchor gate = e.first(AnchorType.JAIL_GATE), exit = e.first(AnchorType.JAIL_EXIT);
        BlockPos lo = new BlockPos(Math.min(min.pos().getX(), max.pos().getX()), Math.min(min.pos().getY(), max.pos().getY()),
                Math.min(min.pos().getZ(), max.pos().getZ()));
        BlockPos hi = new BlockPos(Math.max(min.pos().getX(), max.pos().getX()), Math.max(min.pos().getY(), max.pos().getY()),
                Math.max(min.pos().getZ(), max.pos().getZ()));
        boolean mine = cells.get(cell).owner().startsWith("cidhna_mine");
        // the evidence chest and the confiscated goods: the jail's own anchors, or (jails generated before them)
        // the first two cells' double chests of the guard room, converted on first use
        Anchor personal = e.first(AnchorType.JAIL_PERSONAL), confiscated = e.first(AnchorType.JAIL_CONFISCATED);
        BlockPos personalPos = personal != null ? personal.pos() : chests.size() > 0 ? chests.get(0).pos() : null;
        BlockPos confiscatedPos = confiscated != null ? confiscated.pos() : personal == null && chests.size() > 2 ? chests.get(2).pos() : null;
        List<BlockPos> oldChests = new ArrayList<>();
        for (Anchor c : chests) oldChests.add(c.pos());
        return new Site(true, place, cell, overworld, cells.get(cell).pos(), cell < doors.size() ? doors.get(cell).pos() : null,
                gate == null ? null : gate.pos(), oldChests, exit == null ? null : exit.pos(), lo, hi, mine, null,
                personalPos, confiscatedPos);
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

    /** A free cell of the place's jail for the player (not another prisoner's). */
    @Nullable
    static Site claimCell(ServerLevel overworld, String place, ServerPlayer player) {
        Optional<SettlementAnchors.Entry> entry = SettlementAnchors.find(overworld, place);
        if (entry.isEmpty()) return null;
        Set<BlockPos> npcCells = new HashSet<>();
        for (Anchor a : entry.get().anchors(AnchorType.JAIL_PRISONER)) npcCells.add(a.pos());
        JailData jd = JailData.get(player.level().getServer());
        int n = entry.get().anchors(AnchorType.JAIL_CELL).size();
        for (int cell = 0; cell < n; cell++) {
            Site s = holdSite(overworld, place, cell);
            if (s == null || npcCells.contains(s.stand())) continue;
            UUID inside = jd.occupied.get(s.key());
            if (inside != null && !inside.equals(player.getUUID())) continue;
            return s;
        }
        return null;
    }

    /** The player's current site from their jail state (null if their hold jail is gone). */
    @Nullable
    static Site siteOf(ServerPlayer player) {
        CompoundTag j = Bounty.state(player).getCompoundOrEmpty("jail");
        if ("hold".equals(j.getStringOr("site",""))) return holdSite(player.level().getServer().overworld(), j.getStringOr("place",""), j.getIntOr("cell",0));
        ServerLevel jail = player.level().getServer().getLevel(JAIL);
        return jail == null ? null : dimensionSite(player.level().getServer(), jail, player.getUUID());
    }

    /** Whether a block lies inside a hold jail or the jail dimension (no theft or ownership there; jail keys work). */
    public static boolean isJailArea(Level level, BlockPos pos) {
        if (isJailDimension(level)) return true;
        if (!(level instanceof ServerLevel sl) || level.dimension() != Level.OVERWORLD) return false;
        Optional<SettlementAnchors.Entry> e = SettlementAnchors.near(sl, pos, 0);
        if (e.isEmpty()) return false;
        Anchor min = e.get().first(AnchorType.JAIL_MIN), max = e.get().first(AnchorType.JAIL_MAX);
        if (min == null || max == null) return false;
        return AABB.encapsulatingFullBlocks(min.pos(), max.pos()).inflate(0.5).contains(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
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

    /** Whether the player is held (hidden, safe) while the jail that serves them is generated: see {@link JailLoading}. */
    public static boolean isLoadingJail(Player player) {
        return JailLoading.isLoading(player);
    }

    private static void send(ServerPlayer player, String hold, int bounty, @Nullable String forcedPlace) {
        if (isJailed(player) || JailLoading.isLoading(player)) return;
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        ServerLevel overworld = server.overworld();
        // a jail that exists serves the arrest; one that is not generated yet is generated first, with the prisoner
        // held hidden behind the loading cover (JailLoading); the dimension is only what is left when that fails
        String place = forcedPlace != null && SettlementAnchors.find(overworld, forcedPlace).map(e -> e.first(AnchorType.JAIL_CELL) != null).orElse(false)
                ? forcedPlace : forcedPlace == null ? jailPlaceFor(overworld, hold, player.blockPosition()) : null;
        Site site = place == null ? null : claimCell(overworld, place, player);
        if (site == null && JailLoading.begin(player, hold, bounty, forcedPlace)) return;
        if (site == null) {
            sendToDimension(player, hold, bounty, forcedPlace);
            return;
        }
        startSentence(player, hold, bounty, forcedPlace, site);
    }

    /** The fallback: the prisoner's own cell block in the jail dimension. */
    static void sendToDimension(ServerPlayer player, String hold, int bounty, @Nullable String forcedPlace) {
        MinecraftServer server = player.level().getServer();
        ServerLevel jail = server.getLevel(JAIL);
        if (jail == null) {
            Skycraft.LOGGER.error("Jail dimension {} is missing and no hold jail is known; the arrest is dropped", JAIL.identifier());
            return;
        }
        startSentence(player, hold, bounty, forcedPlace, dimensionSite(server, jail, player.getUUID()));
    }

    /** Puts the player in the cell of {@code site}: the sentence, the confiscation, the cell. */
    static void startSentence(ServerPlayer player, String hold, int bounty, @Nullable String forcedPlace, Site site) {
        MinecraftServer server = player.level().getServer();
        PlayerData data = SkyData.get(player);
        CompoundTag j = new CompoundTag();
        j.putString("hold", hold);
        j.putInt("bounty", bounty);
        j.putInt("remaining", sentenceSeconds(bounty));
        j.putInt("served", 0);
        j.putString("site", site.hold() ? "hold" : "dim");
        j.putString("place", site.hold() ? site.place() : forcedPlace == null ? "" : forcedPlace);
        j.putInt("cell", site.cell());
        j.putString("rdim", player.level().dimension().identifier().toString());
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
            jd.setDirty();
        }

        Guards.pacify(player);
        player.closeContainer();
        player.stopRiding();
        Component where = site.hold() ? jailName(site) : Component.translatable("crime.skycraft.jail.dimension");
        Notifier.title(player, Component.translatable("crime.skycraft.jail.title"),
                Component.translatable("crime.skycraft.jail.sentence", formatTime(sentenceSeconds(bounty))));
        if (site.hold()) Notifier.message(player, Component.translatable("crime.skycraft.jail.taken_to", where));
        JailEvidence.noteBounty(player.level().getServer(), JailEvidence.jailKey(site), bounty); // the goods' lock before the cell is made ready
        teleportToCell(player, site);

        // Move possessions to the jail's stores (nothing is ever deleted): stolen goods to the shared confiscated goods,
        // the rest to the prisoner's own evidence. Quest items and up to 2 lockpicks (if skilled) stay.
        String jail = JailEvidence.jailKey(site);
        recoverKeys(player); // a key from an earlier escape is the jail's property, not an item to confiscate
        JailEvidence.noteBounty(player.level().getServer(), jail, bounty);
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
                if (s.getCount() > take) JailEvidence.confiscate(player, jail, s.split(s.getCount() - take));
                continue;
            }
            inv.setItem(i, ItemStack.EMPTY);
            JailEvidence.confiscate(player, jail, s);
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
            JailEvidence.confiscate(player, jail, all);
        }
        if (site.mine()) {
            ItemStack pick = new ItemStack(Items.IRON_PICKAXE);
            pick.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.translatable("item.skycraft.reward.cidhna_pickaxe"));
            com.skycraft.inventory.ItemData.updateCustom(pick, customData263 -> customData263.putBoolean(TOOL_TAG, true));
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

    /**
     * A hold jail's cell made ready for a new prisoner: the older per-cell chests migrated into the stores, the
     * evidence chest and the confiscated goods in place (locked as hard as the biggest bounty taken in), the doors
     * shut and locked.
     */
    private static void prepareHoldCell(ServerPlayer player, Site s) {
        ServerLevel level = s.level();
        level.getChunk(s.stand());
        SettlementAnchors.find(level, s.place()).ifPresent(e -> JailEvidence.migrateHold(level, e));
        JailEvidence.ensureHold(level, s, JailEvidence.stores(player.level().getServer(), s.place()).tier);
        lockDoor(level, s.door(), Locks.APPRENTICE);
        lockDoor(level, s.gate(), Locks.ADEPT);
    }

    /**
     * How hard the lock on the confiscated goods (and, in the jail dimension, the evidence room's door) is: the bigger
     * the bounty, the harder the lock to pick. A guard's jail key opens any of them.
     */
    static int evidenceTier(int bounty) {
        if (bounty < 100) return Locks.NOVICE;
        if (bounty < 400) return Locks.APPRENTICE;
        if (bounty < 1000) return Locks.ADEPT;
        if (bounty < 3000) return Locks.EXPERT;
        return Locks.MASTER;
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
        JailEvidence.relock(level, pos);
    }

    private static void teleportToCell(ServerPlayer player, Site site) {
        if (site.hold()) {
            prepareHoldCell(player, site);
            BlockPos s = site.stand();
            player.teleportTo(site.level(),  s.getX() + 0.5,  s.getY(),  s.getZ() + 0.5, java.util.Set.of(),  player.getYRot(),  0f, true);
            return;
        }
        ServerLevel jail = site.level();
        int slot = site.cell();
        BlockPos origin = cellOrigin(slot);
        JailData data = JailData.get(player.level().getServer());
        boolean fresh = !data.built.contains(slot);
        int tier = JailEvidence.stores(player.level().getServer(), JailEvidence.DIM).tier;
        // an older cell block's per-cell chest goes into the stores before anything is rebuilt around it
        JailEvidence.migrateDim(jail, slot);
        // the partition wall beside the evidence room is always stone; air there means the cell block is gone
        if (fresh || jail.getBlockState(origin.offset(5, 1, 1)).isAir()) {
            buildCell(jail, origin, fresh, tier);
            data.built.add(slot);
            data.setDirty();
        }
        // the evidence chest and the confiscated goods stand in the evidence room (their contents are in the stores)
        JailEvidence.ensureDim(jail, origin, tier);
        // every sentence starts with the room's door and the cell door locked again
        if (jail.getBlockState(origin.offset(2, 1, 3)).getBlock() instanceof DoorBlock) lockDoor(jail, origin.offset(2, 1, 3), tier);
        if (jail.getBlockState(origin.offset(5, 1, 4)).getBlock() instanceof DoorBlock) lockDoor(jail, origin.offset(5, 1, 4), Locks.MASTER);
        player.teleportTo(jail,  origin.getX() + 7.5,  CELL_Y + 1,  origin.getZ() + 4.5, java.util.Set.of(),  90f,  0f, true);
    }

    static BlockPos cellOrigin(int slot) {
        return new BlockPos(slot * CELL_SPACING, CELL_Y, 0);
    }

    /**
     * A cell block with a central corridor, guard patrol, and multiple cells with NPCs. Rebuilding never touches
     * what the jail holds (it is in {@link JailData}, not in the blocks); the prisoners and the guard are only spawned
     * the first time ({@code spawnNpcs}). The cell block is two rooms: the corridor with the three cells and the
     * escape drain at its far end, and, behind a locked iron door at the near end, the evidence room with the
     * evidence chest and the confiscated goods (never in a cell).
     */
    private static void buildCell(ServerLevel level, BlockPos o, boolean spawnNpcs, int tier) {
        BlockState wall = Blocks.STONE_BRICKS.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = 0; x <= 11; x++) {
            for (int y = 0; y <= 4; y++) {
                for (int z = 0; z <= 25; z++) {
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

        // North end: the evidence room (z 1..2) with its own locked iron door off the corridor
        for (int x = 1; x <= 4; x++) {
            for (int y = 1; y <= 3; y++) level.setBlock(o.offset(x, y, 3), wall, 2);
        }
        BlockState evidenceDoor = Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        level.setBlock(o.offset(2, 1, 3), evidenceDoor.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER), 2);
        level.setBlock(o.offset(2, 2, 3), evidenceDoor.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER), 2);
        Locks.setLock(level, o.offset(2, 1, 3), tier);
        level.setBlock(o.offset(2, 3, 2), Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true), 2);
        JailEvidence.ensureDim(level, o, tier);

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
            var prisoner1 = net.minecraft.world.entity.EntityTypes.VILLAGER.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
            if (prisoner1 != null) {
                prisoner1.setCustomName(Component.translatable("crime.skycraft.jail.npc.outlaw"));
                prisoner1.setCustomNameVisible(true);
                prisoner1.setPos(o.getX() + 7.5, CELL_Y + 1, o.getZ() + 12.5);
                prisoner1.addTag(NPC_TAG);
                prisoner1.setPersistenceRequired();
                level.addFreshEntity(prisoner1);
            }
            var prisoner2 = net.minecraft.world.entity.EntityTypes.VILLAGER.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
            if (prisoner2 != null) {
                prisoner2.setCustomName(Component.translatable("crime.skycraft.jail.npc.thief"));
                prisoner2.setCustomNameVisible(true);
                prisoner2.setPos(o.getX() + 7.5, CELL_Y + 1, o.getZ() + 20.5);
                prisoner2.addTag(NPC_TAG);
                prisoner2.setPersistenceRequired();
                level.addFreshEntity(prisoner2);
            }
            // the corridor's guard walks his round (JailPatrol spawns him when he is missing, and walks him)
        } catch (Exception ignored) {}
    }

    private static void setupCell(ServerLevel level, BlockPos o, int doorZ, int bedZ, boolean playerCell) {
        // Iron Door facing corridor
        BlockPos doorBottom = o.offset(5, 1, doorZ);
        BlockPos doorTop = o.offset(5, 2, doorZ);
        level.setBlock(doorBottom, Blocks.IRON_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER).setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.WEST), 2);
        level.setBlock(doorTop, Blocks.IRON_DOOR.defaultBlockState().setValue(net.minecraft.world.level.block.DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER).setValue(net.minecraft.world.level.block.DoorBlock.FACING, Direction.WEST), 2);
        Locks.setLock(level, doorBottom, Locks.MASTER);

        // The door sits in a stone frame (the partition's own blocks at dz +-1: iron bars never meet a door's panel),
        // with bars on either side of it and a one-block grate window, pane-free, at head height beside the door:
        // a prisoner can reach through it (pickpocket the guard, talk) and cannot fit through.
        BlockState bars = Blocks.IRON_BARS.defaultBlockState().setValue(IronBarsBlock.NORTH, true).setValue(IronBarsBlock.SOUTH, true);
        for (int dz = -3; dz <= 3; dz++) {
            if (Math.abs(dz) <= 1) continue;
            level.setBlock(o.offset(5, 1, doorZ + dz), bars, 2);
            level.setBlock(o.offset(5, 2, doorZ + dz), dz == 2 ? Blocks.AIR.defaultBlockState() : bars, 2);
        }

        // Bed inside cell
        BlockPos head = o.offset(9, 1, bedZ);
        BlockPos foot = o.offset(9, 1, bedZ + 1);
        level.setBlock(head, com.skycraft.worldgen.VanillaBlocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.HEAD), 2);
        level.setBlock(foot, com.skycraft.worldgen.VanillaBlocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH).setValue(BedBlock.PART, BedPart.FOOT), 2);

        // Cell furnishings
        level.setBlock(o.offset(9, 1, doorZ + 2), Blocks.CAULDRON.defaultBlockState(), 2);
        level.setBlock(o.offset(7, 3, doorZ), Blocks.LANTERN.defaultBlockState().setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true), 2);
    }

    // ------------------------------------------------------------------ serving the sentence

    /** Every second. */
    static void tick(ServerPlayer player) {
        if (!isJailed(player)) return;
        CompoundTag j = Bounty.state(player).getCompoundOrEmpty("jail");
        Site site = siteOf(player);
        if (site == null) {
            // the hold jail is gone (or the dimension is missing): serve in the jail dimension instead
            ServerLevel jail = player.level().getServer().getLevel(JAIL);
            if (jail == null) return;
            j.putString("site", "dim");
            SkyData.get(player).markDirty();
            if (player.isAlive()) teleportToCell(player, dimensionSite(player.level().getServer(), jail, player.getUUID()));
            return;
        }
        boolean inside = site.hold() ? site.contains(player.level(), player.getX(), player.getY(), player.getZ()) : isJailDimension(player.level());
        if (!inside) {
            if (!player.isAlive()) return;
            if (player.isCreative() || player.isSpectator()) {
                String hold = j.getStringOr("hold","");
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
        guardReaction(player, site);
        if (!isJailed(player)) return;
        boolean over = serveSecond(j);
        SkyData.get(player).markDirty();
        if (over) {
            // the time is served: nobody carries the prisoner out. They sleep in the cell bed to wake outside the
            // jail's door with their things, or break out instead.
            Notifier.title(player, Component.translatable("crime.skycraft.jail.served"), Component.translatable("crime.skycraft.jail.served_sleep"));
        }
    }

    /**
     * One second of the sentence goes by. Returns true on the second it runs out. A served sentence stays served
     * ({@code remaining} 0): the prisoner is not released by the clock, only by sleeping in the bed (or escaping).
     */
    static boolean serveSecond(CompoundTag j) {
        j.putInt("served", j.getIntOr("served",0) + 1);
        int before = j.getIntOr("remaining",0);
        if (before <= 0) return false;
        j.putInt("remaining", before - 1);
        return before == 1;
    }

    /**
     * World time passed while the prisoner was not serving it second by second (a time skip of the rest system): the
     * sentence counts down by those seconds, and a sentence that runs out is announced like any served one.
     */
    public static void passTime(ServerPlayer player, int seconds) {
        if (seconds <= 0 || !isJailed(player)) return;
        CompoundTag j = Bounty.state(player).getCompoundOrEmpty("jail");
        int before = j.getIntOr("remaining",0);
        j.putInt("served", j.getIntOr("served",0) + seconds);
        if (before > 0) {
            j.putInt("remaining", Math.max(0, before - seconds));
            if (before - seconds <= 0) {
                Notifier.title(player, Component.translatable("crime.skycraft.jail.served"), Component.translatable("crime.skycraft.jail.served_sleep"));
            }
        }
        SkyData.get(player).markDirty();
    }

    /** Whether the prisoner may sleep to be released: the first minute is over, or the sentence is served. */
    static boolean maySleep(CompoundTag j) {
        return j.getIntOr("served",0) >= 60 || j.getIntOr("remaining",0) <= 0;
    }

    /** Forgets a player's jail bookkeeping (logout). */
    static void forget(UUID player) {
        LOOSE.remove(player);
    }

    /**
     * Whether a prisoner stands outside their cell: on the corridor side of the cell door's plane or in its doorway.
     * The cell lies on the side of the door where its stand point is; works for doors in x- or z-planes.
     */
    static boolean outOfCell(@Nullable BlockPos door, BlockPos stand, double x, double z) {
        if (door == null) return false;
        double dx = stand.getX() - door.getX(), dz = stand.getZ() - door.getZ();
        if (Math.abs(dx) >= Math.abs(dz)) return (x - (door.getX() + 0.5)) * Math.signum(dx) < 0.5;
        return (z - (door.getZ() + 0.5)) * Math.signum(dz) < 0.5;
    }

    /**
     * A guard who sees a prisoner outside their cell (door opened with a key or a pick) comes over and drags them
     * back: the sentence grows a little. The key is the jail's, and stays with them until they leave the jail for
     * good ({@link #recoverKeys}). Sneaking and darkness shorten how far the guard sees.
     */
    private static void guardReaction(ServerPlayer player, Site site) {
        if (site.door() == null || !player.isAlive() || player.isCreative() || player.isSpectator()) return;
        if (!outOfCell(site.door(), site.stand(), player.getX(), player.getZ())) {
            LOOSE.remove(player.getUUID());
            return;
        }
        // a prisoner running about outside the cell is heard by the guards nearby, seen or not (they come to look)
        if (player.isSprinting()) JailPatrol.noise(player, 10);
        LivingEntity guard = Crimes.findGuardWitness(player);
        if (guard == null) {
            LOOSE.computeIfPresent(player.getUUID(), (k, v) -> v > 1 ? v - 1 : null);
            return;
        }
        int seen = LOOSE.merge(player.getUUID(), 1, Integer::sum);
        if (guard instanceof Mob mob) {
            JailPatrol.busy(mob, 100);
            mob.getNavigation().moveTo(player, 1.25);
            mob.getLookControl().setLookAt(player, 30f, 30f);
        }
        if (seen == 1) Notifier.message(player, Component.translatable("crime.skycraft.jail.out_of_cell", guard.getDisplayName()));
        if (seen >= LOOSE_SECONDS || guard.distanceToSqr(player) <= 9) draggedBack(player, site, guard);
    }

    private static void draggedBack(ServerPlayer player, Site site, LivingEntity guard) {
        LOOSE.remove(player.getUUID());
        if (guard instanceof Mob mob) mob.getNavigation().stop();
        ServerLevel level = site.level();
        BlockPos s = site.stand();
        player.teleportTo(level,  s.getX() + 0.5,  s.getY(),  s.getZ() + 0.5, java.util.Set.of(),  player.getYRot(),  0f, true);
        BlockPos door = site.door();
        int tier = Locks.lockLevel(level, door);
        lockDoor(level, door, tier == Locks.NOT_LOCKED ? Locks.APPRENTICE : tier);
        CompoundTag j = Bounty.state(player).getCompoundOrEmpty("jail");
        j.putInt("remaining", Math.min(MAX_SECONDS, j.getIntOr("remaining",0) + DRAGGED_BACK_SECONDS));
        SkyData.get(player).markDirty();
        Notifier.message(player, Component.translatable("crime.skycraft.jail.dragged_back", guard.getDisplayName(), DRAGGED_BACK_SECONDS));
        level.playSound(null, s, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1f, 1f);
    }

    /**
     * The jail's keys go back to the guards: when the prisoner leaves the jail for good (released, escaped out of
     * the exit) or is arrested again. Until then a key stays in the inventory and keeps opening the jail's doors and
     * the confiscated goods (and nothing outside the jail). Returns how many were recovered.
     */
    private static int recoverKeys(ServerPlayer player) {
        int n = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(CrimeItems.JAIL_KEY.get())) {
                n += s.getCount();
                inv.setItem(i, ItemStack.EMPTY);
            }
        }
        return n + com.skycraft.inventory.Pack.remove(player, s -> s.is(CrimeItems.JAIL_KEY.get()), Integer.MAX_VALUE);
    }

    private static void freeCell(ServerPlayer player, Site site) {
        removeTools(player);
        if (!site.hold()) return;
        JailData jd = JailData.get(player.level().getServer());
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
            if (!s.isEmpty() && com.skycraft.inventory.ItemData.custom(s) != null && com.skycraft.inventory.ItemData.custom(s).getBooleanOr(TOOL_TAG, false)) inv.setItem(i, ItemStack.EMPTY);
        }
        com.skycraft.inventory.Pack.remove(player, s -> com.skycraft.inventory.ItemData.custom(s) != null && com.skycraft.inventory.ItemData.custom(s).getBooleanOr(TOOL_TAG, false), Integer.MAX_VALUE);
    }

    static void release(ServerPlayer player, boolean slept) {
        CompoundTag state = Bounty.state(player);
        if (!com.skycraft.platform.NbtData.hasType(state, "jail", Tag.TAG_COMPOUND)) return;
        Site site = siteOf(player);
        CompoundTag j = state.getCompoundOrEmpty("jail");
        state.remove("jail");
        String hold = j.getStringOr("hold","");
        int bounty = Math.max(j.getIntOr("bounty",0), 1);
        Bounty.clear(player, hold);

        // Their own things come back from the evidence (the stolen goods stay in the jail's confiscated goods)
        if (site != null) freeCell(player, site);
        JailEvidence.returnPersonal(player, site != null ? JailEvidence.jailKey(site) : JailEvidence.LEGACY);
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

        // wakes outside the jail's front door in the city, never back at the scene of the crime
        JailVisitors.forget(player.getUUID());
        recoverKeys(player);
        teleportOutside(player, j, site);
        SkyData.get(player).markDirty();
    }

    /** True for 1 in-game day (24,000 ticks) after a prisoner breaks out of jail; after a day they may freely visit again. */
    public static boolean isRecentEscapee(Player player) {
        long until = Bounty.state(player).getLongOr("escaped_until",0L);
        return until > 0 && player.level().getGameTime() < until;
    }

    /**
     * The days of a sentence go by (Skyrim: a day per hundred gold of bounty). Only when nobody else is about to be
     * hurried along with them: a single-player world, or the only player online.
     */
    private static void passDays(ServerPlayer player, int days) {
        MinecraftServer server = player.level().getServer();
        boolean alone = server.getPlayerList().getPlayerCount() <= 1;
        if (!alone) return;
        ServerLevel overworld = server.overworld();
        com.skycraft.platform.WorldTime.set(overworld, overworld.getDefaultClockTime() + 24000L * days);
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
        CompoundTag j = Bounty.state(player).getCompoundOrEmpty("jail");
        String hold = j.getStringOr("hold","");
        String place = j.getStringOr("place","");
        Bounty.state(player).remove("jail");
        Bounty.state(player).putLong("escaped_until", player.level().getGameTime() + 24000L);
        SkyData.get(player).markDirty();
        if (site != null) freeCell(player, site);
        if (!place.isEmpty()) com.skycraft.quest.story.Story.setFlag(SkyData.get(player), "escaped_" + place, true);

        Bounty.add(player, hold, Bounty.ESCAPE);
        recoverKeys(player); // out of the jail for good: the key goes back
        if (detected) {
            Guards.attack(player, 24);
            Notifier.title(player, Component.translatable("crime.skycraft.jail.escaped"), Component.translatable("crime.skycraft.jail.escaped_seen"));
        } else {
            Notifier.title(player, Component.translatable("crime.skycraft.jail.escaped"), Component.translatable("crime.skycraft.jail.escaped_unseen"));
        }
        // whatever was left behind stays in the jail's evidence: interacting with the evidence chest later (sneak back
        // in) gives it back; stolen goods are in the locked confiscated barrel and have to be picked out
        LOOSE.remove(player.getUUID());
        if (site != null && site.hold()) return;

        // out of the drain of the jail dimension's cell block: behind the nearest city jail, not where the crime was
        teleportOutside(player, j, site);
    }

    /** Where a freed or escaped prisoner stands: a place outside a city jail. */
    public record Exit(ServerLevel level, BlockPos pos) {}

    /** Public lookup for where a party member traveling to a jailed prisoner should arrive (outside the jail in town). */
    public static Exit outsideFor(ServerPlayer prisoner) {
        return outside(prisoner.level().getServer(), Bounty.state(prisoner).getCompoundOrEmpty("jail"), siteOf(prisoner));
    }

    /**
     * The spot outside the jail: a hold jail's own exit (in front of its door); for the jail dimension the exit of the
     * jail that serves the hold the crime was in (else the nearest city jail), else the middle of that settlement,
     * else the world spawn. Never the place of the crime or inside a cell.
     */
    static Exit outside(MinecraftServer server, CompoundTag j, @Nullable Site site) {
        ServerLevel overworld = server.overworld();
        if (site != null && site.hold()) {
            if (site.exit() != null) {
                return new Exit(site.level(), site.exit());
            }
            // Fallback for older worlds without a JAIL_EXIT anchor: step outside the jail bounding box (never site.stand() inside the cell!)
            int cx = (site.min().getX() + site.max().getX()) / 2;
            int outZ = site.min().getZ() - 2;
            int topY = site.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, outZ);
            return new Exit(site.level(), ensureOutdoor(site.level(), new BlockPos(cx, topY, outZ)));
        }
        BlockPos from = BlockPos.containing(j.getDoubleOr("rx",0D), j.getDoubleOr("ry",0D), j.getDoubleOr("rz",0D));
        String place = j.getStringOr("place","");
        if (place.isEmpty()) place = jailPlaceFor(overworld, j.getStringOr("hold",""), from);
        if (place != null) {
            Optional<SettlementAnchors.Entry> entry = SettlementAnchors.find(overworld, place);
            if (entry.isPresent()) {
                Anchor exit = entry.get().first(AnchorType.JAIL_EXIT);
                if (exit != null) return new Exit(overworld, exit.pos());
                for (AnchorType pub : new AnchorType[]{AnchorType.MEETING, AnchorType.CARRIAGE, AnchorType.GATE}) {
                    Anchor a = entry.get().first(pub);
                    if (a != null) return new Exit(overworld, ensureOutdoor(overworld, a.pos()));
                }
                SettlementAnchors.Entry e = entry.get();
                BlockPos center = new BlockPos(e.x, overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, e.x, e.z), e.z);
                return new Exit(overworld, ensureOutdoor(overworld, center));
            }
        }
        return new Exit(overworld, ensureOutdoor(overworld, overworld.getRespawnData().pos()));
    }

    private static BlockPos ensureOutdoor(ServerLevel level, BlockPos base) {
        if (level.canSeeSky(base) && !isJailArea(level, base)) return base;
        for (int r = 2; r <= 24; r += 2) {
            for (int dx = -r; dx <= r; dx += r) {
                for (int dz = -r; dz <= r; dz += r) {
                    int x = base.getX() + dx;
                    int z = base.getZ() + dz;
                    int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    BlockPos p = new BlockPos(x, y, z);
                    if (level.canSeeSky(p) && !isJailArea(level, p)) return p;
                }
            }
        }
        return base;
    }

    private static void teleportOutside(ServerPlayer player, CompoundTag j, @Nullable Site site) {
        Exit out = outside(player.level().getServer(), j, site);
        BlockPos p = out.pos();
        player.teleportTo(out.level(),  p.getX() + 0.5,  p.getY(),  p.getZ() + 0.5, java.util.Set.of(),  player.getYRot(),  0f, true);
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
        if (level instanceof ServerLevel sl && event.getEntity() instanceof ServerPlayer actor && onStoreClick(event, sl, actor)) return;
        // a free player opening a closed cell door (a guard's key, or it was already unlocked for them): a jailbreak
        if (level instanceof ServerLevel cl && event.getEntity() instanceof ServerPlayer free && !isJailed(free)
                && event.getHand() == net.minecraft.world.InteractionHand.MAIN_HAND
                && JailVisitors.cellOfDoor(cl, Locks.normalizePos(cl, event.getPos())) >= 0
                && !cl.getBlockState(Locks.normalizePos(cl, event.getPos())).getValue(DoorBlock.OPEN)
                && !Locks.blocks(free, cl, event.getPos())) {
            JailVisitors.freed(free, cl, event.getPos());
        }
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
                CompoundTag j = Bounty.state(player).getCompoundOrEmpty("jail");
                int served = j.getIntOr("served",0);
                if (!maySleep(j)) {
                    Notifier.message(player, Component.translatable("crime.skycraft.jail.too_soon", 60 - served));
                } else if (!com.skycraft.world.RestManager.beginJail(player, pos, () -> release(player, true))) {
                    // the sentence is slept off as a personal fast-forward (world/RestManager); no cinematic: release at once
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

    /**
     * The evidence chest and the confiscated goods (for everybody, jailed or not). The chest is never a screen: it
     * hands the player's own things back. The goods are locked: a click that passes the lock check (a guard's key)
     * opens them, one that does not is left to {@link Locks}' minigame, which opens them through
     * {@link JailEvidence#openConfiscated} when the pick turns. Returns true when the click was dealt with.
     */
    private static boolean onStoreClick(PlayerInteractEvent.RightClickBlock event, ServerLevel level, ServerPlayer player) {
        BlockPos pos = event.getPos();
        JailEvidence.Kind kind = JailEvidence.kindAt(level, pos);
        if (kind == JailEvidence.Kind.NONE) return false;
        if (event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
            return true;
        }
        switch (kind) {
            case PERSONAL -> {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                JailEvidence.interactPersonal(player, level, pos);
            }
            case LEGACY_CHEST -> {
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                JailEvidence.migrateAt(level, pos);
            }
            case CONFISCATED -> {
                if (Locks.blocks(player, level, pos)) return false; // the lockpick minigame
                event.setCanceled(true);
                event.setCancellationResult(InteractionResult.SUCCESS);
                JailEvidence.openConfiscated(player, level, pos);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBreak(net.neoforged.neoforge.event.level.block.BreakBlockEvent event) {
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
        Identifier id = BuiltInRegistries.BLOCK.getKey(st.getBlock());
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
        CompoundTag j = Bounty.state(player).getCompoundOrEmpty("jail");
        int left = Math.max(1, j.getIntOr("remaining",0) - LABOUR_SECONDS);
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

    /** A prisoner who dies in jail doesn't lose what they carried: it goes into the jail's stores like an arrest's. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onDrops(LivingDropsEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player) || !isJailed(player)) return;
        Site site = siteOf(player);
        if (site == null || player.level() != site.level()) return;
        if (site.hold() && !site.contains(player.level(), player.getX(), player.getY(), player.getZ())) return;
        String jail = JailEvidence.jailKey(site);
        for (ItemEntity drop : event.getDrops()) {
            ItemStack s = drop.getItem().copy();
            if (com.skycraft.inventory.ItemData.custom(s) != null && com.skycraft.inventory.ItemData.custom(s).getBooleanOr(TOOL_TAG, false)) continue;
            if (s.is(CrimeItems.JAIL_KEY.get())) continue; // the jail's own
            JailEvidence.confiscate(player, jail, s);
        }
        // the pack part of the death's drops (whole entries with int counts, see survival/corpse/PlayerCorpses)
        for (ItemStack all : com.skycraft.survival.corpse.PlayerCorpses.takePackCargo(player)) {
            JailEvidence.confiscate(player, jail, all);
        }
        event.getDrops().clear();
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer loading && JailLoading.isLoading(loading)) {
            JailLoading.resume(loading); // the arrest was waiting for its jail
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player) || !isJailed(player) || player.isCreative()) return;
        Site site = siteOf(player);
        if (site == null) return;
        boolean inside = site.hold() ? site.contains(player.level(), player.getX(), player.getY(), player.getZ()) : isJailDimension(player.level());
        if (!inside) teleportToCell(player, site);
    }

    // ------------------------------------------------------------------ saved data

    /**
     * Which jail-dimension cell belongs to which player, which cells are built, the hold jails' cells (who is locked
     * in which) and each jail's two stores (the prisoners' evidence and the shared confiscated goods,
     * {@link JailEvidence}). Stored with the overworld. {@code held} and {@code chestOwner} are only read from
     * older saves, to be migrated into the stores.
     */
    public static class JailData extends SavedData {
        private static final String NAME = "skycraft_jail";
        final Map<UUID, Integer> slots = new HashMap<>();
        final Set<Integer> built = new HashSet<>();
        final Map<UUID, List<ItemStack>> held = new HashMap<>();
        /** Hold jail cell ({@code place#cell}) -> the prisoner in it. */
        final Map<String, UUID> occupied = new HashMap<>();
        /** Hold jail cell -> whose confiscated things its (older, per-cell) evidence chest holds. */
        final Map<String, UUID> chestOwner = new HashMap<>();
        /** Jail key (hold place id, {@link JailEvidence#DIM} or {@link JailEvidence#LEGACY}) -> its stores. */
        final Map<String, JailEvidence.Stores> stores = new HashMap<>();
        int next = 0;

        JailEvidence.Stores stores(String jailKey) {
            return stores.computeIfAbsent(jailKey, k -> new JailEvidence.Stores(this::setDirty));
        }

        static JailData get(MinecraftServer server) {
            return com.skycraft.platform.SavedDataTypes.get(server.overworld(), NAME, JailData::load, JailData::new, value263 -> value263.save(new net.minecraft.nbt.CompoundTag()));
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
            data.next = tag.getIntOr("next",0);
            CompoundTag slots = tag.getCompoundOrEmpty("slots");
            for (String k : slots.keySet()) {
                try {
                    data.slots.put(UUID.fromString(k), slots.getIntOr(k, 0));
                } catch (IllegalArgumentException ignored) {
                    // corrupt entry
                }
            }
            for (int b : tag.getIntArray("built").orElseGet(() -> new int[0])) data.built.add(b);
            CompoundTag held = tag.getCompoundOrEmpty("held");
            for (String k : held.keySet()) {
                try {
                    List<ItemStack> items = new ArrayList<>();
                    for (Tag t : held.getListOrEmpty(k)) {
                        ItemStack s = com.skycraft.inventory.ItemData.parse((CompoundTag) t);
                        if (!s.isEmpty()) items.add(s);
                    }
                    if (!items.isEmpty()) data.held.put(UUID.fromString(k), items);
                } catch (IllegalArgumentException ignored) {
                    // corrupt entry
                }
            }
            readCells(tag.getCompoundOrEmpty("occupied"), data.occupied);
            readCells(tag.getCompoundOrEmpty("chest_owner"), data.chestOwner);
            CompoundTag st = tag.getCompoundOrEmpty("stores");
            for (String k : st.keySet()) data.stores.put(k, JailEvidence.Stores.load(st.getCompoundOrEmpty(k), data::setDirty));
            return data;
        }

        private static void readCells(CompoundTag t, Map<String, UUID> into) {
            for (String k : t.keySet()) {
                if (t.read(k, net.minecraft.core.UUIDUtil.CODEC).isPresent()) into.put(k, t.read(k, net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
            }
        }

        private static CompoundTag writeCells(Map<String, UUID> from) {
            CompoundTag t = new CompoundTag();
            from.forEach((key, uuid) -> t.store(key, net.minecraft.core.UUIDUtil.CODEC, uuid));
            return t;
        }

        public CompoundTag save(CompoundTag tag) {
            tag.putInt("next", next);
            CompoundTag s = new CompoundTag();
            slots.forEach((k, v) -> s.putInt(k.toString(), v));
            tag.put("slots", s);
            tag.put("built", new IntArrayTag(built.stream().mapToInt(Integer::intValue).toArray()));
            CompoundTag h = new CompoundTag();
            held.forEach((k, items) -> {
                ListTag list = new ListTag();
                for (ItemStack item : items) if (!item.isEmpty()) list.add(com.skycraft.inventory.ItemData.save(item));
                if (!list.isEmpty()) h.put(k.toString(), list);
            });
            tag.put("held", h);
            tag.put("occupied", writeCells(occupied));
            tag.put("chest_owner", writeCells(chestOwner));
            CompoundTag st = new CompoundTag();
            stores.forEach((k, v) -> st.put(k, v.save()));
            tag.put("stores", st);
            return tag;
        }
    }
}
