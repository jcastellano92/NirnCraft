package com.skycraft.homestead;

import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
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
            if (p.isEmpty()) p = Places.byId(canonPlotId(id));
            return p.map(place -> new BlockPos(place.x(), 0, place.z())).orElse(new BlockPos(x, 0, z));
        }
    }

    public static final List<Plot> PLOTS = buildPlots();
    /** How close to a plot's centre the drafting table must stand. */
    public static final int PLOT_RADIUS = 64;

    private static List<Plot> buildPlots() {
        java.util.LinkedHashMap<String, Plot> map = new java.util.LinkedHashMap<>();
        for (Place p : Places.all()) {
            if ("homestead".equals(p.variant())) {
                String region = p.regionId() != null ? p.regionId() : "";
                String hold = region.contains("/") ? region.substring(region.indexOf('/') + 1) : region;
                map.put(p.id(), new Plot(p.id(), hold, p.x(), p.z()));
            }
        }
        // Fallback if Places is empty in a minimal environment
        if (map.isEmpty()) {
            map.put("lakeview", new Plot("lakeview", "falkreath", -468, 982));
            map.put("windstad", new Plot("windstad", "hjaalmarch", -690, -1699));
            map.put("heljarchen", new Plot("heljarchen", "the_pale", 184, -730));
        }
        return List.copyOf(map.values());
    }

    /** Normalizes legacy Skyrim Hearthfire plot IDs ({@code lakeview_manor -> lakeview}, etc.). */
    public static String canonPlotId(String id) {
        if (id == null) return "";
        return switch (id.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "lakeview_manor" -> "lakeview";
            case "windstad_manor" -> "windstad";
            case "heljarchen_hall" -> "heljarchen";
            default -> id.trim().toLowerCase(java.util.Locale.ROOT);
        };
    }

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

    /** The economy module registers how it records bought plots. */
    public static void setPlotOwnership(PlotOwnership ownership) {
        plotOwnership = ownership == null ? (p, id) -> false : ownership;
    }

    /** True if {@code player} already bought plot {@code plotId} elsewhere (see {@link #setPlotOwnership}). */
    public static boolean ownsPlotElsewhere(ServerPlayer player, String plotId) {
        try {
            if (plotId == null) return false;
            if (plotOwnership.owns(player, plotId)) return true;
            String canon = canonPlotId(plotId);
            return !canon.equals(plotId) && plotOwnership.owns(player, canon);
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
        return residencesOf(player.level().getServer(), player.getUUID());
    }

    public static List<Residence> residencesOf(MinecraftServer server, UUID player) {
        List<Residence> out = new ArrayList<>();
        HomesteadClaim claim = HomesteadData.get(server).getClaim(player);
        if (claim == null) return out;
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, Identifier.parse(claim.getDimension()));
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
        if (id == null) return null;
        String canon = canonPlotId(id);
        for (Plot p : PLOTS) {
            if (p.id().equals(id) || p.id().equals(canon)) return p;
        }
        return null;
    }

    /** True when {@code id} (or its canonical alias) is a registered Hearthfire homestead plot. */
    public static boolean isPlot(String id) {
        return plot(id) != null;
    }

    /** All Hearthfire homestead plots in a given hold or region (e.g. {@code "falkreath"} or {@code "morrowind/west_gash"}). */
    public static List<Plot> plotsInHold(String holdOrRegion) {
        if (holdOrRegion == null || holdOrRegion.isBlank()) return List.of();
        String h = holdOrRegion.trim().toLowerCase(java.util.Locale.ROOT);
        String shortId = h.contains("/") ? h.substring(h.indexOf('/') + 1) : h;
        List<Plot> out = new ArrayList<>();
        for (Plot p : PLOTS) {
            if (p.hold().equalsIgnoreCase(h) || p.hold().equalsIgnoreCase(shortId)) out.add(p);
        }
        return out;
    }
}

