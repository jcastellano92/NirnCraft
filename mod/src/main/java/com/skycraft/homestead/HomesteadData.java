package com.skycraft.homestead;

import com.skycraft.crime.Theft;
import com.skycraft.quest.Locate;
import com.skycraft.roads.RoadsData;
import com.skycraft.roads.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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
        return server.overworld().getDataStorage().computeIfAbsent(HomesteadData::load, HomesteadData::new, NAME);
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
    public String checkCanClaim(ServerPlayer player, BlockPos pos) {
        if (player.level().dimension() != Level.OVERWORLD) {
            return "message.skycraft.homestead.overworld_only";
        }

        // 1. One homestead per player
        HomesteadClaim existing = claimsByOwner.get(player.getUUID());
        if (existing != null) {
            return "message.skycraft.homestead.already_claimed";
        }

        ServerLevel level = player.serverLevel();

        // Hearthfire plots (Lakeview, Windstad, Heljarchen) lie by farms and roads: only one owner each
        Homesteads.Plot plot = Homesteads.plotAt(pos);
        if (plot != null) {
            for (HomesteadClaim claim : claimsByOwner.values()) {
                if (plot.id().equals(claim.getPlot())) return "message.skycraft.homestead.plot_taken";
            }
            return null;
        }

        // 2. Check settlements and villages
        if (Theft.inVillage(level, pos)) {
            return "message.skycraft.homestead.near_settlement";
        }

        // POI check (village bells / beds)
        var closestPoi = level.getPoiManager().findClosest(
                holder -> holder.is(PoiTypes.MEETING) || holder.is(PoiTypes.HOME),
                pos, MIN_DIST_FROM_SETTLEMENT, PoiManager.Occupancy.ANY);
        if (closestPoi.isPresent()) {
            return "message.skycraft.homestead.near_settlement";
        }

        // Roads / Settlements data check
        RoadsData roads = RoadsData.get(level);
        for (Settlement s : roads.settlements()) {
            int dx = Math.max(0, Math.max(s.minX - pos.getX(), pos.getX() - s.maxX));
            int dz = Math.max(0, Math.max(s.minZ - pos.getZ(), pos.getZ() - s.maxZ));
            if (Math.sqrt(dx * dx + dz * dz) < MIN_DIST_FROM_SETTLEMENT) {
                return "message.skycraft.homestead.near_settlement";
            }
        }

        // 3. Check dungeons and special spawned structures
        BlockPos dungeon = Locate.structure(level, Locate.DUNGEONS, pos, 4);
        if (dungeon != null) {
            double dist = Math.sqrt(pos.distSqr(dungeon));
            if (dist < MIN_DIST_FROM_DUNGEON) {
                return "message.skycraft.homestead.near_dungeon";
            }
        }

        // 4. Check distance to existing claims
        for (HomesteadClaim claim : claimsByOwner.values()) {
            if (claim.getDimension().equals(level.dimension().location().toString())) {
                double dist = Math.sqrt(claim.getCenter().distSqr(pos));
                if (dist < MIN_DIST_FROM_CLAIM) {
                    return "message.skycraft.homestead.near_claim";
                }
            }
        }

        return null;
    }

    public HomesteadClaim addClaim(ServerPlayer player, BlockPos pos) {
        HomesteadClaim claim = new HomesteadClaim(
                player.getUUID(),
                player.getName().getString(),
                pos,
                player.level().dimension().location().toString()
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
            if (claim.getCenter().equals(pos) && claim.getDimension().equals(level.dimension().location().toString())) {
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
        ListTag list = tag.getList("claims", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            HomesteadClaim claim = HomesteadClaim.load(list.getCompound(i));
            data.claimsByOwner.put(claim.getOwnerId(), claim);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (HomesteadClaim claim : claimsByOwner.values()) {
            list.add(claim.save());
        }
        tag.put("claims", list);
        return tag;
    }
}
