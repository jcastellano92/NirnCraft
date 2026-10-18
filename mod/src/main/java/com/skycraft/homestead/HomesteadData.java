package com.skycraft.homestead;

import com.skycraft.crime.Theft;
import com.skycraft.quest.Locate;
import com.skycraft.roads.RoadsData;
import com.skycraft.roads.Settlement;
import com.skycraft.survival.SurvivalConfig;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class HomesteadData extends SavedData {
    public static final String NAME = "skycraft_homesteads";
    public static final int MIN_DIST_FROM_SETTLEMENT = 96;
    public static final int MIN_DIST_FROM_DUNGEON = 64;
    public static final int MIN_DIST_FROM_CLAIM = 64;

    private final Map<UUID, HomesteadClaim> claimsByOwner = new HashMap<>();

    public static HomesteadData get(MinecraftServer server) {
        return com.skycraft.platform.SavedDataTypes.get(server.overworld(), NAME, HomesteadData::load, HomesteadData::new, value263 -> value263.save(new net.minecraft.nbt.CompoundTag()));
    }

    @Nullable
    public HomesteadClaim getClaim(UUID owner) {
        return claimsByOwner.get(owner);
    }

    @Nullable
    public HomesteadClaim getClaimAt(Level level, BlockPos pos) {
        for (HomesteadClaim claim : claimsByOwner.values()) {
            if (claim.isInside(level, pos)) {
                return claim;
            }
        }
        return null;
    }

    @Nullable
    public HomesteadClaim getClaimHorizontal(Level level, BlockPos pos) {
        for (HomesteadClaim claim : claimsByOwner.values()) {
            if (claim.isHorizontallyInside(level, pos)) {
                return claim;
            }
        }
        return null;
    }

    /**
     * Checks if a player may establish a homestead at this position.
     * Returns error message translation key or reason, or null if allowed.
     */
    @Nullable
    public Denial checkCanClaim(ServerPlayer player, BlockPos pos) {
        if (player.level().dimension() != Level.OVERWORLD) {
            return new Denial("message.skycraft.homestead.overworld_only");
        }

        // 1. One homestead per player
        HomesteadClaim existing = claimsByOwner.get(player.getUUID());
        if (existing != null) {
            return new Denial("message.skycraft.homestead.already_claimed");
        }

        ServerLevel level = player.level();

        // Hearthfire plots (Lakeview, Windstad, Heljarchen) lie by farms and roads: only one owner each
        Homesteads.Plot plot = Homesteads.plotAt(pos);
        if (plot != null) {
            for (HomesteadClaim claim : claimsByOwner.values()) {
                if (plot.id().equals(claim.getPlot())) return new Denial("message.skycraft.homestead.plot_taken");
            }
            return null;
        }

        // 2. Check settlements and villages
        int settlementDistance = SurvivalConfig.CLAIM_SETTLEMENT_DISTANCE.get();
        if (Theft.inVillage(level, pos)) {
            return new Denial("message.skycraft.homestead.near_settlement");
        }

        // POI check (village bells / beds)
        var closestPoi = level.getPoiManager().findClosest(
                holder -> holder.is(PoiTypes.MEETING) || holder.is(PoiTypes.HOME),
                pos, settlementDistance, PoiManager.Occupancy.ANY);
        if (closestPoi.isPresent()) {
            return new Denial("message.skycraft.homestead.near_settlement");
        }

        // Roads / Settlements data check
        RoadsData roads = RoadsData.get(level);
        for (Settlement s : roads.settlements()) {
            int dx = Math.max(0, Math.max(s.minX - pos.getX(), pos.getX() - s.maxX));
            int dz = Math.max(0, Math.max(s.minZ - pos.getZ(), pos.getZ() - s.maxZ));
            if (Math.sqrt(dx * dx + dz * dz) < settlementDistance) {
                return new Denial("message.skycraft.homestead.near_settlement");
            }
        }

        // 3. Check dungeons and special spawned structures
        BlockPos dungeon = Locate.structure(level, Locate.DUNGEONS, pos, 4);
        if (dungeon != null) {
            double dist = Math.sqrt(pos.distSqr(dungeon));
            if (dist < SurvivalConfig.CLAIM_DUNGEON_DISTANCE.get()) {
                return new Denial("message.skycraft.homestead.near_dungeon");
            }
        }

        // 4. Check distance to existing claims
        int spacing = Math.max(SurvivalConfig.CLAIM_SPACING.get(), 0);
        for (HomesteadClaim claim : claimsByOwner.values()) {
            if (claim.getDimension().equals(level.dimension().identifier().toString())) {
                double dist = Math.sqrt(claim.getCenter().distSqr(pos));
                // an expanded homestead keeps its whole grounds clear of a neighbour's
                if (dist < Math.max(spacing, claim.getRadius() + HomesteadClaim.DEFAULT_RADIUS + 8)) {
                    return new Denial("message.skycraft.homestead.near_claim");
                }
            }
        }

        // 5. Named places of the map and the man-made footprints (the open homestead plots are exempt)
        return checkLand(level, pos, HomesteadClaim.DEFAULT_RADIUS, 0);
    }

    /**
     * The land rules of a homestead of {@code radius} around {@code pos}: no named place of the map (standing stones,
     * forts, shrines, ruins, farms...) within its radius plus {@code claimPlaceClearance} (plus {@code extra}), no
     * settlement, structure or site footprint under it, and not on the open sea. A designated open homestead plot
     * (a place with variant {@code homestead}) is exempt from the footprint test. Null if fine.
     */
    @Nullable
    public static Denial checkLand(ServerLevel level, BlockPos pos, int radius, int extra) {
        int x = pos.getX(), z = pos.getZ();
        int clearance = SurvivalConfig.CLAIM_PLACE_CLEARANCE.get() + extra;
        boolean openPlot = false;
        for (Place p : Places.overlapping(x - clearance, z - clearance, x + clearance, z + clearance)) {
            long d2 = p.distSqr(x, z);
            if ("homestead".equals(p.variant())) {
                if (d2 <= (long) p.radius() * p.radius()) openPlot = true;
                continue;
            }
            long reach = (long) p.radius() + clearance;
            if (d2 <= reach * reach) return new Denial("message.skycraft.homestead.near_place", p.name());
        }
        try {
            com.skycraft.tamriel.Region region = com.skycraft.tamriel.Regions.at(level, pos);
            if (region != null && ("sea".equals(region.kind()) || "dimension".equals(region.kind()))) {
                return new Denial("message.skycraft.homestead.bad_ground");
            }
        } catch (RuntimeException ignored) {
            // no map data here: nothing to test
        }
        if (openPlot) return null;
        int ring = Math.max(1, radius * 3 / 4);
        int[][] samples = {{0, 0}, {ring, 0}, {-ring, 0}, {0, ring}, {0, -ring}, {ring * 7 / 10, ring * 7 / 10},
                {-ring * 7 / 10, ring * 7 / 10}, {ring * 7 / 10, -ring * 7 / 10}, {-ring * 7 / 10, -ring * 7 / 10}};
        for (int[] s : samples) {
            BlockPos sp = new BlockPos(x + s[0], pos.getY(), z + s[1]);
            if (!level.isLoaded(sp)) continue;
            com.skycraft.protect.Footprints.Kind kind = com.skycraft.protect.Footprints.at(level, sp);
            // a road may run by the plot (the lanes of the open plots); the table itself never stands on one
            boolean blocked = kind == com.skycraft.protect.Footprints.Kind.SETTLEMENT || kind == com.skycraft.protect.Footprints.Kind.STRUCTURE
                    || kind == com.skycraft.protect.Footprints.Kind.SITE || (s[0] == 0 && s[1] == 0 && kind.built());
            if (blocked) return new Denial("message.skycraft.homestead.near_place", Component.translatable("homestead.skycraft.something_built"));
        }
        return null;
    }

    /** Whether {@code claim}'s grounds may widen to {@code newRadius}: clear of every other homestead and (off the Hearthfire plots) of the world's places. */
    @Nullable
    public Denial checkExpansion(ServerLevel level, HomesteadClaim claim, int newRadius) {
        for (HomesteadClaim other : claimsByOwner.values()) {
            if (other == claim || !other.getDimension().equals(claim.getDimension())) continue;
            double dist = Math.sqrt(other.getCenter().distSqr(claim.getCenter()));
            if (dist < newRadius + other.getRadius() + 8) return new Denial("message.skycraft.homestead.near_claim");
        }
        if (claim.getPlot() != null) return null; // a Hearthfire plot is the land of its manor
        return checkLand(level, claim.getCenter(), newRadius, newRadius - HomesteadClaim.DEFAULT_RADIUS);
    }

    /** Why a homestead cannot be founded or expanded here: a translation key and its arguments. */
    public record Denial(String key, Object... args) {
        public Component message() {
            return Component.translatable(key, args);
        }
    }

    public HomesteadClaim addClaim(ServerPlayer player, BlockPos pos) {
        HomesteadClaim claim = new HomesteadClaim(
                player.getUUID(),
                player.getName().getString(),
                pos,
                player.level().dimension().identifier().toString()
        );
        // the house grows away from the drafting table's front (where whoever placed it stood)
        net.minecraft.world.level.block.state.BlockState table = player.level().getBlockState(pos);
        if (table.hasProperty(DraftingTableBlock.FACING)) claim.setFacing(table.getValue(DraftingTableBlock.FACING).getOpposite());
        else claim.setFacing(player.getDirection());
        Homesteads.Plot plot = Homesteads.plotAt(pos);
        if (plot != null) claim.setPlot(plot.id());
        claimsByOwner.put(player.getUUID(), claim);
        setDirty();
        return claim;
    }

    public boolean removeClaim(UUID owner) {
        if (claimsByOwner.remove(owner) != null) {
            setDirty();
            return true;
        }
        return false;
    }

    public boolean removeClaimAt(Level level, BlockPos pos) {
        Iterator<Map.Entry<UUID, HomesteadClaim>> it = claimsByOwner.entrySet().iterator();
        while (it.hasNext()) {
            HomesteadClaim claim = it.next().getValue();
            if (claim.getCenter().equals(pos) && claim.getDimension().equals(level.dimension().identifier().toString())) {
                it.remove();
                setDirty();
                return true;
            }
        }
        return false;
    }

    public Collection<HomesteadClaim> allClaims() {
        return Collections.unmodifiableCollection(claimsByOwner.values());
    }

    public static HomesteadData load(CompoundTag tag) {
        HomesteadData data = new HomesteadData();
        ListTag list = tag.getListOrEmpty("claims");
        for (int i = 0; i < list.size(); i++) {
            HomesteadClaim claim = HomesteadClaim.load(list.getCompoundOrEmpty(i));
            data.claimsByOwner.put(claim.getOwnerId(), claim);
        }
        return data;
    }

    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (HomesteadClaim claim : claimsByOwner.values()) {
            list.add(claim.save());
        }
        tag.put("claims", list);
        return tag;
    }
}
