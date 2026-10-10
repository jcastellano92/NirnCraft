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
        ServerLevel level = player.serverLevel();
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

    private static void populate(ServerLevel level, Settlement s, Data data) {
        data.populated.add(s.key);
        data.setDirty();
        RandomSource r = level.getRandom();
        BlockPos center = Encounters.groundAt(level, s.x, s.z);
        if (center == null) center = new BlockPos(s.x, s.y, s.z);
        com.skycraft.tamriel.Place place = com.skycraft.tamriel.Places.nearest(null, s.x, s.z, 200)
                .filter(p -> p.type().isSettlement()).orElse(null);
        Size size = sizeOf(s, place);
        String hold = Holds.holdAt(level, center);
        boolean seat = !data.seats.contains(hold) && (place == null ? size.ordinal() >= Size.VILLAGE.ordinal()
                : place.type() == com.skycraft.tamriel.PlaceType.CAPITAL);

        List<NpcRole> roles = roster(size, seat, r);
        int cap = SocietyConfig.MAX_RESIDENTS.get();
        if (roles.size() > cap) roles = roles.subList(0, cap);
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

    /** A free bed near the settlement center (POI lookup), or null. */
    @Nullable
    private static BlockPos freeBed(ServerLevel level, BlockPos center, Set<Long> taken, Data data) {
        return level.getPoiManager().findClosest(h -> h.is(PoiTypes.HOME),
                p -> !taken.contains(p.asLong()) && !data.beds.containsKey(p.asLong()) && !Ownership.isPlayerOwned(level, p),
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
        BlockPos pos = null;
        for (int i = 0; i < 12 && pos == null; i++) {
            int spread = bed != null ? 3 : 16;
            int x = anchor.getX() + r.nextInt(spread * 2 + 1) - spread;
            int z = anchor.getZ() + r.nextInt(spread * 2 + 1) - spread;
            BlockPos p = bed != null ? new BlockPos(x, bed.getY(), z) : Encounters.groundAt(level, x, z);
            if (p != null && Math.abs(p.getY() - anchor.getY()) <= 6 && level.getBlockState(p).isAir()
                    && level.getBlockState(p.above()).isAir() && level.getBlockState(p.below()).isSolid()) pos = p;
        }
        if (pos == null) pos = bed != null ? bed.above() : center;
        NpcEntity npc = Npcs.create(level, pos, role);
        if (npc == null) return null;
        npc.setPersistenceRequired();
        npc.setHome(anchor, radius);
        if (bed != null) claimBed(npc, bed);
        if (!level.addFreshEntity(npc)) return null;
        NpcRegistry.get(level.getServer()).setHome(npc.getUUID(), anchor, level.dimension().location().toString());
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
        // a spouse sleeps in the player's own beds at home
        boolean spouse = Marriage.isSpouse(npc);
        Optional<BlockPos> found = level.getPoiManager().findClosest(h -> h.is(PoiTypes.HOME),
                p -> !data.beds.containsKey(p.asLong()) && (spouse || !Ownership.isPlayerOwned(level, p)),
                home, spouse ? 20 : 32, PoiManager.Occupancy.HAS_SPACE);
        if (found.isEmpty()) return null;
        BlockPos bed = found.get().immutable();
        data.beds.put(bed.asLong(), npc.getUUID());
        data.setDirty();
        npc.setBed(bed);
        return bed;
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
            return level.getDataStorage().computeIfAbsent(Data::load, Data::new, NAME);
        }

        static Data load(CompoundTag tag) {
            Data d = new Data();
            ListTag list = tag.getList("populated", Tag.TAG_LONG);
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i) instanceof LongTag l) d.populated.add(l.getAsLong());
            }
            ListTag seats = tag.getList("seats", Tag.TAG_STRING);
            for (int i = 0; i < seats.size(); i++) d.seats.add(seats.getString(i));
            ListTag beds = tag.getList("beds", Tag.TAG_COMPOUND);
            for (int i = 0; i < beds.size(); i++) {
                CompoundTag b = beds.getCompound(i);
                if (b.hasUUID("npc")) d.beds.put(b.getLong("pos"), b.getUUID("npc"));
            }
            return d;
        }

        @Override
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
                b.putUUID("npc", npc);
                bedList.add(b);
            });
            tag.put("beds", bedList);
            return tag;
        }
    }
}
