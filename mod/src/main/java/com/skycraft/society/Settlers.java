package com.skycraft.society;

import com.skycraft.core.Holds;
import com.skycraft.roads.RoadsData;
import com.skycraft.roads.Settlement;
import com.skycraft.society.entity.NpcEntity;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import com.skycraft.crime.Ownership;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Gives each settlement detected by the roads module its people, once, the first time a player comes near: a roster
 * sized by the settlement (a farm's family, a village's farmers and smith, a town's merchants, priest, bard and
 * apothecary, a capital's Jarl, steward and housecarl), each of the region's races in realistic proportions (see
 * {@link Cultures}), each with a home bed (POI claims), a workplace ({@link Workplaces}) and a daily routine
 * ({@link Schedule}). The innkeeper comes from the survival module. The settlement on a capital {@code Place} (or,
 * without places, the first settlement found in a hold) is the hold's seat.
 *
 * <p>Public API for settlement builders: {@link #populate(ServerLevel, Settlement)} and
 * {@link #spawnResident(ServerLevel, BlockPos, NpcRole, BlockPos, int)}.</p>
 */
public final class Settlers {
    private Settlers() {}

    /** Called every 5 seconds per player. */
    static void tick(ServerPlayer player) {
        if (!SocietyConfig.POPULATE_SETTLEMENTS.get() || player.isSpectator()) return;
        ServerLevel level = player.level();
        if (level.dimension() != Level.OVERWORLD) return;
        Settlement s;
        try {
            s = RoadsData.get(level).zoneAt(player.getBlockX(), player.getBlockZ());
        } catch (RuntimeException e) {
            return;
        }
        if (s == null) return;
        Data data = Data.get(level);
        if (data.populated.contains(s.key)) return;
        BlockPos center = new BlockPos(s.x, player.getBlockY(), s.z);
        if (player.distanceToSqr(s.x + 0.5, player.getY(), s.z + 0.5) > 80 * 80 || !level.isPositionEntityTicking(center)) return;
        populate(level, s, data);
    }

    /** Settlement sizes, smallest first. */
    public enum Size { FARM, VILLAGE, TOWN, CITY }

    /** Populates a settlement now (idempotent per settlement). For builders that place their towns themselves. */
    public static void populate(ServerLevel level, Settlement s) {
        Data data = Data.get(level);
        if (!data.populated.contains(s.key)) populate(level, s, data);
    }

    /**
     * A settlement of Skycraft's own (a named place with a layout, or a generated one) is populated from its recorded
     * anchors by the housing model, never by this older roster: running both used to put a second crowd in the square.
     */
    private static boolean ownSettlement(Settlement s) {
        for (com.skycraft.tamriel.Place p : com.skycraft.tamriel.Places.overlapping(s.x - 8, s.z - 8, s.x + 8, s.z + 8)) {
            if (p.type().isSettlement() && p.distSqr(s.x, s.z) <= (long) (p.radius() + 24) * (p.radius() + 24)) return true;
        }
        return false;
    }

    private static void populate(ServerLevel level, Settlement s, Data data) {
        if (ownSettlement(s)) return;
        data.populated.add(s.key);
        data.setDirty();
        RandomSource r = level.getRandom();
        BlockPos center = NpcPlacement.outdoorGroundAt(level, s.x, s.z, s.y);
        if (center == null) center = new BlockPos(s.x, s.y, s.z);
        // Skycraft-built settlements populate their own buildings from SettlementAnchors (Jarl, smith, inn...)
        if (com.skycraft.tamriel.settlement.SettlementAnchors.near(level, center, 48).isPresent()) return;
        com.skycraft.tamriel.Place place = com.skycraft.tamriel.Places.nearest(null, s.x, s.z, 200)
                .filter(p -> p.type().isSettlement()).orElse(null);
        Size size = sizeOf(s, place);
        String hold = Holds.holdAt(level, center);
        boolean seat = !data.seats.contains(hold) && (place == null ? size.ordinal() >= Size.VILLAGE.ordinal()
                : place.type() == com.skycraft.tamriel.PlaceType.CAPITAL);

        List<NpcRole> roles = roster(size, seat, r);
        int cap = SocietyConfig.MAX_RESIDENTS.get();
        if (roles.size() > cap) roles = roles.subList(0, cap);
        // housing model: never more residents than free beds around the centre (the roster is most important first)
        int beds = freeBeds(level, center, data);
        if (roles.size() > beds) roles = roles.subList(0, beds);
        com.skycraft.crafting.SettlementStations.populate(level, s, center, r);

        NpcEntity jarl = null;
        Set<Long> taken = new HashSet<>();
        for (NpcRole role : roles) {
            NpcEntity npc = spawnResident(level, center, role, freeBed(level, center, taken, data), radiusFor(role));
            if (npc != null && npc.getBed() != null) taken.add(npc.getBed().asLong());
            if (role == NpcRole.JARL) jarl = npc;
            if (role == NpcRole.HOUSECARL && npc != null && jarl != null) npc.setLeader(jarl.getUUID());
        }
        if (seat) {
            data.seats.add(hold);
            data.setDirty();
        }
    }

    /** Spawns a roster of {@code size} around {@code center} (no settlement record; commands). Returns how many. */
    public static int populateHere(ServerLevel level, BlockPos center, Size size) {
        Data data = Data.get(level);
        int count = 0;
        Set<Long> taken = new HashSet<>();
        List<NpcRole> roles = roster(size, false, level.getRandom());
        int cap = SocietyConfig.MAX_RESIDENTS.get();
        if (roles.size() > cap) roles = roles.subList(0, cap);
        // housing model: never more residents than free beds around the centre (the roster is most important first)
        int beds = freeBeds(level, center, data);
        if (roles.size() > beds) roles = roles.subList(0, beds);
        for (NpcRole role : roles) {
            NpcEntity npc = spawnResident(level, center, role, freeBed(level, center, taken, data), radiusFor(role));
            if (npc == null) continue;
            count++;
            if (npc.getBed() != null) taken.add(npc.getBed().asLong());
        }
        return count;
    }

    private static Size sizeOf(Settlement s, @Nullable com.skycraft.tamriel.Place place) {
        if (place != null) {
            return switch (place.type()) {
                case CAPITAL, CITY -> Size.CITY;
                case TOWN -> Size.TOWN;
                case VILLAGE -> Size.VILLAGE;
                default -> Size.FARM;
            };
        }
        int half = Math.max(s.maxX - s.minX, s.maxZ - s.minZ) / 2;
        if (half > 80) return Size.CITY;
        if (half > 48) return Size.TOWN;
        if (half > 20) return Size.VILLAGE;
        return Size.FARM;
    }

    /** Who lives in a settlement of this size, most important first (the cap trims from the end). */
    static List<NpcRole> roster(Size size, boolean seat, RandomSource r) {
        List<NpcRole> roles = new ArrayList<>();
        if (seat) {
            roles.add(NpcRole.JARL);
            roles.add(NpcRole.HOUSECARL);
            roles.add(NpcRole.STEWARD);
        }
        NpcRole[] workers = {NpcRole.HUNTER, NpcRole.MINER, NpcRole.LUMBERJACK, NpcRole.FISHER};
        switch (size) {
            case FARM -> {
                roles.add(NpcRole.FARMER);
                roles.add(NpcRole.FARMER);
                if (r.nextBoolean()) roles.add(NpcRole.CHILD);
            }
            case VILLAGE -> {
                roles.addAll(List.of(NpcRole.FARMER, NpcRole.BLACKSMITH, NpcRole.MERCHANT, NpcRole.FARMER));
                roles.add(workers[r.nextInt(workers.length)]);
                if (r.nextBoolean()) roles.add(NpcRole.PRIEST);
                if (r.nextBoolean()) roles.add(NpcRole.BARD);
                roles.addAll(List.of(NpcRole.CITIZEN, NpcRole.CHILD, NpcRole.CITIZEN));
                if (r.nextBoolean()) roles.add(NpcRole.CHILD);
            }
            case TOWN -> {
                roles.addAll(List.of(NpcRole.BLACKSMITH, NpcRole.MERCHANT, NpcRole.PRIEST, NpcRole.BARD, NpcRole.APOTHECARY,
                        NpcRole.FARMER, NpcRole.FARMER, NpcRole.BUTCHER));
                roles.add(workers[r.nextInt(workers.length)]);
                roles.add(workers[r.nextInt(workers.length)]);
                if (r.nextBoolean()) roles.add(NpcRole.BEGGAR);
                if (r.nextInt(3) == 0) roles.add(NpcRole.SCHOLAR);
                roles.addAll(List.of(NpcRole.CITIZEN, NpcRole.CHILD, NpcRole.CITIZEN, NpcRole.CITIZEN, NpcRole.CHILD));
            }
            case CITY -> {
                roles.addAll(List.of(NpcRole.BLACKSMITH, NpcRole.MERCHANT, NpcRole.PRIEST, NpcRole.BARD, NpcRole.APOTHECARY,
                        NpcRole.SCHOLAR, NpcRole.MERCHANT, NpcRole.BUTCHER, NpcRole.FARMER, NpcRole.BLACKSMITH, NpcRole.BEGGAR,
                        NpcRole.MAGE, NpcRole.FARMER));
                roles.add(workers[r.nextInt(workers.length)]);
                roles.addAll(List.of(NpcRole.CITIZEN, NpcRole.CHILD, NpcRole.CITIZEN, NpcRole.CITIZEN, NpcRole.CHILD, NpcRole.CITIZEN,
                        NpcRole.PRIEST, NpcRole.CHILD, NpcRole.CITIZEN));
            }
        }
        return roles;
    }

    private static int radiusFor(NpcRole role) {
        return switch (role) {
            case HUNTER, LUMBERJACK, MINER, FISHER, FARMER -> 48;
            case CHILD -> 20;
            default -> 28;
        };
    }

    /** How many unclaimed, non-player beds the POI manager knows within 64 blocks of the centre (the housing capacity). */
    private static int freeBeds(ServerLevel level, BlockPos center, Data data) {
        return (int) level.getPoiManager().findAll(h -> h.is(PoiTypes.HOME),
                p -> !data.beds.containsKey(p.asLong()) && !Ownership.isPlayerOwned(level, p) && !Ownership.isInnBed(level, p),
                center, 64, PoiManager.Occupancy.ANY).count();
    }

    /** A free bed near the settlement center (POI lookup), or null. */
    @Nullable
    private static BlockPos freeBed(ServerLevel level, BlockPos center, Set<Long> taken, Data data) {
        return level.getPoiManager().findClosest(h -> h.is(PoiTypes.HOME),
                p -> !taken.contains(p.asLong()) && !data.beds.containsKey(p.asLong()) && !Ownership.isPlayerOwned(level, p) && !Ownership.isInnBed(level, p),
                center, 64, PoiManager.Occupancy.HAS_SPACE).map(BlockPos::immutable).orElse(null);
    }

    /**
     * Spawns one settled townsperson: standing near {@code bed} (or the settlement center), living there, with that
     * bed claimed. Returns the NPC or null. Public for settlement builders.
     */
    @Nullable
    public static NpcEntity spawnResident(ServerLevel level, BlockPos center, NpcRole role, @Nullable BlockPos bed, int radius) {
        RandomSource r = level.getRandom();
        BlockPos anchor = bed != null ? bed : center;
        boolean bedIndoors = bed != null && !level.canSeeSky(bed) && !level.canSeeSky(bed.above());
        BlockPos pos = null;
        for (int i = 0; i < 12 && pos == null; i++) {
            int spread = bed != null ? 3 : 16;
            int x = anchor.getX() + r.nextInt(spread * 2 + 1) - spread;
            int z = anchor.getZ() + r.nextInt(spread * 2 + 1) - spread;
            BlockPos p = bed != null ? new BlockPos(x, bed.getY(), z) : NpcPlacement.outdoorGroundAt(level, x, z, anchor.getY());
            if (p != null && Math.abs(p.getY() - anchor.getY()) <= 4
                    && !(bedIndoors && (level.canSeeSky(p) || level.canSeeSky(p.above())))
                    && !(level.canSeeSky(p) && NpcPlacement.hasRoomBelow(level, p))
                    && !NpcPlacement.isFireHazard(level, p)
                    && !NpcPlacement.isSlabHazard(level, p)
                    && !NpcPlacement.isFurnitureHazard(level, p)
                    && level.getBlockState(p).isAir()
                    && level.getBlockState(p.above()).isAir() && level.getBlockState(p.below()).isSolid()) pos = p;
        }
        if (pos == null) {
            pos = NpcPlacement.outdoorGroundAt(level, anchor.getX(), anchor.getZ(), anchor.getY());
            if (pos == null) pos = NpcPlacement.safeSurfaceAt(level, anchor.getX(), anchor.getZ());
        }
        NpcEntity npc = Npcs.create(level, pos, role);
        if (npc == null) return null;
        npc.setPersistenceRequired();
        npc.setHome(anchor, radius);
        if (bed != null) claimBed(npc, bed);
        if (!level.addFreshEntity(npc)) return null;
        NpcRegistry.get(level.getServer()).setHome(npc.getUUID(), anchor, level.dimension().identifier().toString());
        return npc;
    }

    // ------------------------------------------------------------------ beds

    /**
     * The bed {@code npc} sleeps in: its existing claim if that is still a free bed, else the closest unclaimed bed
     * near {@code home} from the POI manager (no block scanning). Beds a player placed or owns, beds a villager
     * claimed, and beds another NPC claimed are never taken. Returns null if there is none.
     */
    @Nullable
    public static BlockPos claimBed(NpcEntity npc, BlockPos home) {
        if (!(npc.level() instanceof ServerLevel level)) return null;
        Data data = Data.get(level);
        BlockPos current = npc.getBed();
        if (current != null) {
            if (Ownership.isInnBed(level, current)) {
                data.beds.remove(current.asLong());
                data.setDirty();
                npc.setBed(null);
                current = null;
            }
        }
        if (current != null) {
            UUID who = data.beds.get(current.asLong());
            boolean mine = who == null || who.equals(npc.getUUID());
            if (mine && (!level.isLoaded(current) || level.getBlockState(current).is(BlockTags.BEDS))) {
                if (who == null) {
                    data.beds.put(current.asLong(), npc.getUUID());
                    data.setDirty();
                }
                return current;
            }
            if (who != null && who.equals(npc.getUUID())) {
                data.beds.remove(current.asLong());
                data.setDirty();
            }
            npc.setBed(null);
        }
        // a spouse sleeps in the player's own beds at home; inn beds are reserved for guests
        boolean spouse = Marriage.isSpouse(npc);
        Optional<BlockPos> found = level.getPoiManager().findClosest(h -> h.is(PoiTypes.HOME),
                p -> !data.beds.containsKey(p.asLong()) && !Ownership.isInnBed(level, p) && (spouse || !Ownership.isPlayerOwned(level, p)),
                home, spouse ? 20 : 32, PoiManager.Occupancy.HAS_SPACE);
        // Skycraft-built settlements: the recorded household beds (no POI needed, no chunk loads)
        BlockPos bed = found.isPresent() ? found.get().immutable() : anchorBed(level, data, home, spouse);
        if (bed == null || Ownership.isInnBed(level, bed)) return null;
        data.beds.put(bed.asLong(), npc.getUUID());
        data.setDirty();
        npc.setBed(bed);
        return bed;
    }

    /**
     * The nearest unclaimed household bed ({@link com.skycraft.tamriel.settlement.AnchorType#BED}) recorded by the
     * settlement near {@code near}: read-only use of the settlement anchors, loaded chunks only. The bed also gets
     * its POI when worldgen did not register one, so villagers' own AI can use it too. Null when there is none.
     */
    @Nullable
    static BlockPos anchorBed(ServerLevel level, Data data, BlockPos near, boolean allowPlayerOwned) {
        Optional<com.skycraft.tamriel.settlement.SettlementAnchors.Entry> entry =
                com.skycraft.tamriel.settlement.SettlementAnchors.near(level, near, 64);
        if (entry.isEmpty()) return null;
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (com.skycraft.tamriel.settlement.Anchor a : entry.get().anchors(com.skycraft.tamriel.settlement.AnchorType.BED)) {
            BlockPos p = a.pos();
            if (data.beds.containsKey(p.asLong()) || !level.isLoaded(p) || !level.getBlockState(p).is(BlockTags.BEDS)) continue;
            if (!allowPlayerOwned && Ownership.isPlayerOwned(level, p)) continue;
            double d = p.distSqr(near);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        if (best == null) return null;
        final BlockPos bedPos = best;
        var state = level.getBlockState(best);
        if (state.hasProperty(net.minecraft.world.level.block.BedBlock.PART)
                && state.getValue(net.minecraft.world.level.block.BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.HEAD
                && !level.getPoiManager().exists(best, h -> h.is(PoiTypes.HOME))) {
            net.minecraft.core.registries.BuiltInRegistries.POINT_OF_INTEREST_TYPE.get(PoiTypes.HOME)
                    .ifPresent(h -> level.getPoiManager().add(bedPos, h));
        }
        return best.immutable();
    }

    /**
     * A bed for a settler villager (merchants, smiths, farmers...): claims one and sets the villager's HOME memory so
     * the vanilla schedule sends it to sleep there. Called every ~2 s per villager by {@link SettlerVillagers}.
     */
    static void homeForVillager(net.minecraft.world.entity.npc.villager.Villager v) {
        if (!(v.level() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) return;
        var brain = v.getBrain();
        var home = net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME;
        Data data = Data.get(level);
        BlockPos mine = null;
        for (Map.Entry<Long, UUID> e : data.beds.entrySet()) {
            if (e.getValue().equals(v.getUUID())) {
                mine = BlockPos.of(e.getKey());
                break;
            }
        }
        if (mine == null) {
            mine = anchorBed(level, data, v.blockPosition(), false);
            if (mine == null) return;
            data.beds.put(mine.asLong(), v.getUUID());
            data.setDirty();
        }
        if (!brain.hasMemoryValue(home)) {
            brain.setMemory(home, net.minecraft.core.GlobalPos.of(level.dimension(), mine));
        }
    }

    /** Whether nobody holds {@code bed} yet and it is still a bed (a bed in an unloaded chunk is assumed fine). */
    public static boolean bedFree(ServerLevel level, BlockPos bed) {
        if (Data.get(level).beds.containsKey(bed.asLong())) return false;
        return !level.isLoaded(bed) || level.getBlockState(bed).is(BlockTags.BEDS);
    }

    /**
     * Claims exactly {@code bed} for {@code who} (the housing model hands specific beds to specific residents). False
     * when somebody else holds it. The bed also gets its POI when worldgen did not register one.
     */
    public static boolean reserveBed(ServerLevel level, BlockPos bed, UUID who) {
        Data data = Data.get(level);
        UUID held = data.beds.get(bed.asLong());
        if (held != null && !held.equals(who)) return false;
        if (held == null) {
            data.beds.put(bed.asLong(), who);
            data.setDirty();
        }
        if (level.isLoaded(bed)) {
            var state = level.getBlockState(bed);
            if (state.hasProperty(net.minecraft.world.level.block.BedBlock.PART)
                    && state.getValue(net.minecraft.world.level.block.BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.HEAD
                    && !level.getPoiManager().exists(bed, h -> h.is(PoiTypes.HOME))) {
                net.minecraft.core.registries.BuiltInRegistries.POINT_OF_INTEREST_TYPE.get(PoiTypes.HOME)
                        .ifPresent(h -> level.getPoiManager().add(bed, h));
            }
        }
        return true;
    }

    /** Gives {@code npc} that bed to sleep in and makes it their home. */
    public static boolean assignBed(NpcEntity npc, BlockPos bed, int homeRadius) {
        if (!(npc.level() instanceof ServerLevel level) || !reserveBed(level, bed, npc.getUUID())) return false;
        npc.setBed(bed);
        npc.setHome(bed, homeRadius);
        return true;
    }

    /** Gives a settler villager that bed (its HOME memory, which the vanilla schedule sleeps in). */
    public static boolean assignBed(net.minecraft.world.entity.npc.villager.Villager v, BlockPos bed) {
        if (!(v.level() instanceof ServerLevel level) || !reserveBed(level, bed, v.getUUID())) return false;
        v.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HOME, net.minecraft.core.GlobalPos.of(level.dimension(), bed));
        return true;
    }

    /** Frees the NPC's bed claim (death or despawn). */
    public static void releaseBed(NpcEntity npc) {
        BlockPos bed = npc.getBed();
        if (bed == null || !(npc.level() instanceof ServerLevel level)) return;
        Data data = Data.get(level);
        if (npc.getUUID().equals(data.beds.get(bed.asLong()))) {
            data.beds.remove(bed.asLong());
            data.setDirty();
        }
        npc.setBed(null);
    }

    /** Which settlements got their people, which holds have a Jarl, and which NPC sleeps in which bed. */
    static final class Data extends SavedData {
        static final String NAME = "skycraft_society";
        final LongOpenHashSet populated = new LongOpenHashSet();
        final Set<String> seats = new HashSet<>();
        final Map<Long, UUID> beds = new HashMap<>();

        static Data get(ServerLevel level) {
            return com.skycraft.platform.SavedDataTypes.get(level, NAME, Data::load, Data::new, value263 -> value263.save(new net.minecraft.nbt.CompoundTag()));
        }

        static Data load(CompoundTag tag) {
            Data d = new Data();
            ListTag list = tag.getListOrEmpty("populated");
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof LongTag l) d.populated.add(l.value());
            }
            ListTag seats = tag.getListOrEmpty("seats");
            for (int i = 0; i < seats.size(); i++) d.seats.add(seats.getStringOr(i, ""));
            ListTag beds = tag.getListOrEmpty("beds");
            for (int i = 0; i < beds.size(); i++) {
                CompoundTag b = beds.getCompoundOrEmpty(i);
                if (b.read("npc", net.minecraft.core.UUIDUtil.CODEC).isPresent()) d.beds.put(b.getLongOr("pos",0L), b.read("npc", net.minecraft.core.UUIDUtil.CODEC).orElseThrow());
            }
            return d;
        }

        public CompoundTag save(CompoundTag tag) {
            ListTag list = new ListTag();
            for (long k : populated) list.add(LongTag.valueOf(k));
            tag.put("populated", list);
            ListTag seatList = new ListTag();
            for (String h : seats) seatList.add(StringTag.valueOf(h));
            tag.put("seats", seatList);
            ListTag bedList = new ListTag();
            beds.forEach((pos, npc) -> {
                CompoundTag b = new CompoundTag();
                b.putLong("pos", pos);
                b.store("npc", net.minecraft.core.UUIDUtil.CODEC,  npc);
                bedList.add(b);
            });
            tag.put("beds", bedList);
            return tag;
        }
    }
}
