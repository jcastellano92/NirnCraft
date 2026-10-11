package com.skycraft.homestead;

import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Public API of Hearthfire homesteads, for the other modules (the NPC workstream's spouses and children).
 *
 * <ul>
 *     <li>{@link #residencesOf}: a player's homesteads, with their beds (adults' then children's), what has been
 *     built, the steward, and where the entrance is.</li>
 *     <li>{@link #setSpouseLocator}: lets the NPC module report a player's spouse near a bed, so sleeping there gives
 *     Lover's Comfort (survival/mode).</li>
 *     <li>{@link #PLOTS}: the three Hearthfire plots. Their position comes from {@code com.skycraft.tamriel.Places}
 *     when the world plan lists them; otherwise from the map positions below.</li>
 * </ul>
 */
public final class Homesteads {
    /** A Hearthfire plot: its place id, hold and fallback position (Skyrim's map, Whiterun at 0,0). */
    public record Plot(String id, String hold, int x, int z) {
        public Component name() {
            return Component.translatable("place.skycraft." + id);
        }

        /** The plot's centre: {@link Places} if it knows the place, else the fallback. */
        public BlockPos center() {
            Optional<Place> p = Places.byId(id);
            return p.map(place -> new BlockPos(place.x(), 0, place.z())).orElse(new BlockPos(x, 0, z));
        }
    }

    public static final List<Plot> PLOTS = List.of(
            new Plot("lakeview_manor", "falkreath", -468, 982),
            new Plot("windstad_manor", "hjaalmarch", -690, -1699),
            new Plot("heljarchen_hall", "the_pale", 184, -730));
    /** How close to a plot's centre the drafting table must stand. */
    public static final int PLOT_RADIUS = 64;

    /**
     * One homestead of a player.
     *
     * @param center   the drafting table
     * @param entrance in front of the house door (or the table if nothing is built)
     * @param beds     bed heads: the first {@code beds.size() - childBeds} are double beds for adults
     */
    public record Residence(UUID owner, Component name, @Nullable String plot, ResourceKey<Level> dimension, BlockPos center,
                            BlockPos entrance, List<BlockPos> beds, int childBeds, Set<String> built, @Nullable UUID steward) {}

    /** Reports a spouse (any entity) close to {@code bed}, or null. */
    @FunctionalInterface
    public interface SpouseLocator {
        @Nullable
        Entity spouseNear(ServerPlayer player, BlockPos bed, double radius);
    }

    private static SpouseLocator spouseLocator = (player, bed, radius) -> null;

    /**
     * Whether a player already owns a Hearthfire plot through another system (e.g. the economy module selling the
     * plots as houses). Such a plot's drafting table needs no second deed.
     */
    @FunctionalInterface
    public interface PlotOwnership {
        boolean owns(ServerPlayer player, String plotId);
    }

    private static PlotOwnership plotOwnership = (player, plot) -> false;

    /** The economy module registers how it records bought plots (place ids lakeview_manor, windstad_manor, heljarchen_hall). */
    public static void setPlotOwnership(PlotOwnership ownership) {
        plotOwnership = ownership == null ? (p, id) -> false : ownership;
    }

    /** True if {@code player} already bought plot {@code plotId} elsewhere (see {@link #setPlotOwnership}). */
    public static boolean ownsPlotElsewhere(ServerPlayer player, String plotId) {
        try {
            return plotId != null && plotOwnership.owns(player, plotId);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private Homesteads() {}

    /** The NPC module registers how to find a player's spouse (default: nobody is married). */
    public static void setSpouseLocator(SpouseLocator locator) {
        spouseLocator = locator == null ? (p, b, r) -> null : locator;
    }

    /** The player's spouse near {@code bed}, if the NPC module knows one. */
    @Nullable
    public static Entity spouseNear(ServerPlayer player, BlockPos bed, double radius) {
        try {
            return spouseLocator.spouseNear(player, bed, radius);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Every homestead {@code player} owns (one per player today; a list for the future). */
    public static List<Residence> residencesOf(ServerPlayer player) {
        return residencesOf(player.server, player.getUUID());
    }

    public static List<Residence> residencesOf(MinecraftServer server, UUID player) {
        List<Residence> out = new ArrayList<>();
        HomesteadClaim claim = HomesteadData.get(server).getClaim(player);
        if (claim == null) return out;
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(claim.getDimension()));
        BlockPos entrance = claim.getBuilt().contains("small_house")
                ? HomesteadBuilder.frame(claim).at(0, 0, -1) : claim.getCenter();
        out.add(new Residence(claim.getOwnerId(), claim.displayName(), claim.getPlot(), dim, claim.getCenter(), entrance,
                List.copyOf(claim.getBeds()), claim.getChildBeds(), Set.copyOf(claim.getBuilt()), claim.getSteward()));
        return out;
    }

    /** The Hearthfire plot whose land contains (x, z), or null. */
    @Nullable
    public static Plot plotAt(BlockPos pos) {
        for (Plot p : PLOTS) {
            BlockPos c = p.center();
            long dx = c.getX() - pos.getX(), dz = c.getZ() - pos.getZ();
            if (dx * dx + dz * dz <= (long) PLOT_RADIUS * PLOT_RADIUS) return p;
        }
        return null;
    }

    @Nullable
    public static Plot plot(String id) {
        for (Plot p : PLOTS) if (p.id().equals(id)) return p;
        return null;
    }
}
