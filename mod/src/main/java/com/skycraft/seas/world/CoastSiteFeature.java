package com.skycraft.seas.world;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Sites on the shore itself ({@link IslandSites}): lighthouses on headlands, wrecks cast up on beaches, smugglers'
 * caves dug into coastal hills, seabird rookeries on rocky shores, hermits' huts, whale bones bleaching on the sand,
 * horkers basking on cold beaches, the odd pirate camp. The buildings follow the region's culture. Placed in the coast
 * biomes by {@code skycraft:seas_coast_sites}.
 */
public class CoastSiteFeature extends Feature<NoneFeatureConfiguration> {
    public enum Kind { LIGHTHOUSE, WRECK_BEACH, SMUGGLER_CAVE, ROOKERY, HERMIT, WHALE_BONES, HORKERS, PIRATE_CAMP }

    /** Sites placed since start (logged by the developer smoke test). */
    public static final java.util.concurrent.atomic.AtomicInteger PLACED = new java.util.concurrent.atomic.AtomicInteger();

    public CoastSiteFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        SiteKit k = new SiteKit(ctx.level(), ctx.random(), ctx.origin());
        int sea = IslandSites.SEA;
        int y = k.surface(k.cx, k.cz);
        if (y <= sea + 1 || k.water(k.cx, y - 1, k.cz) || y > sea + 14) return false;
        Direction toSea = seaward(k);
        if (toSea == null) return false;
        PLACED.incrementAndGet();
        return build(k, choose(k, y, toSea), toSea);
    }

    /** The direction of open water within 14 blocks, or null when the site is not on a shore. */
    static Direction seaward(SiteKit k) {
        Direction best = null;
        int bestD = 99;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int dist = 4; dist <= 14; dist += 2) {
                int x = k.cx + d.getStepX() * dist, z = k.cz + d.getStepZ() * dist;
                if (k.depth(x, z) >= 2) {
                    if (dist < bestD) {
                        bestD = dist;
                        best = d;
                    }
                    break;
                }
            }
        }
        return best;
    }

    private static Kind choose(SiteKit k, int y, Direction toSea) {
        int sea = IslandSites.SEA;
        int height = y - sea;
        Direction inland = toSea.getOpposite();
        int hill = k.surface(k.cx + inland.getStepX() * 9, k.cz + inland.getStepZ() * 9) - y;
        float temp = k.level.getBiome(new BlockPos(k.cx, y, k.cz)).value().getBaseTemperature();
        int[] w = new int[Kind.values().length];
        w[Kind.LIGHTHOUSE.ordinal()] = height >= 3 ? 3 : 1;
        w[Kind.WRECK_BEACH.ordinal()] = height <= 3 ? 3 : 0;
        w[Kind.SMUGGLER_CAVE.ordinal()] = hill >= 5 ? 4 : 0;
        w[Kind.ROOKERY.ordinal()] = height >= 4 ? 2 : 0;
        w[Kind.HERMIT.ordinal()] = 2;
        w[Kind.WHALE_BONES.ordinal()] = height <= 3 ? 2 : 0;
        w[Kind.HORKERS.ordinal()] = temp < 0.3f ? 3 : 0;
        w[Kind.PIRATE_CAMP.ordinal()] = height <= 4 ? 1 : 0;
        int total = 0;
        for (int x : w) total += x;
        int roll = k.r.nextInt(Math.max(1, total));
        for (Kind kind : Kind.values()) {
            roll -= w[kind.ordinal()];
            if (roll < 0) return kind;
        }
        return Kind.HERMIT;
    }

    /** Builds a coast site of the given kind at the kit's centre (also used by {@code /skycraft-seas site}). */
    public static boolean build(SiteKit k, Kind kind, Direction toSea) {
        int x = k.cx, z = k.cz;
        int y = k.surface(x, z);
        String culture = IslandSites.cultureNear(x, z);
        CoastPalette p = CoastPalette.of(culture);
        Direction inland = toSea.getOpposite();
        switch (kind) {
            case LIGHTHOUSE -> {
                IslandSites.lighthouse(k, x, y, z, p, inland.getClockWise(), culture);
                int hx = x + inland.getStepX() * 7, hz = z + inland.getStepZ() * 7;
                int hy = k.surface(hx, hz);
                if (Math.abs(hy - y) <= 3) {
                    IslandSites.cottage(k, hx, hy, hz, p, toSea, culture, SiteKit.loot("chests/seas/lighthouse"));
                    LairMarkerBlockEntity m = k.marker(hx + toSea.getStepX() * 5, hy + 1, hz + toSea.getStepZ() * 5);
                    if (m != null) m.npc("fisher", 1, "seas.skycraft.npc.lighthouse_keeper", false);
                }
            }
            case WRECK_BEACH -> IslandSites.wreckAshore(k, x + toSea.getStepX() * 3, z + toSea.getStepZ() * 3, toSea.getClockWise());
            case SMUGGLER_CAVE -> {
                int cx = x + inland.getStepX() * 9, cz = z + inland.getStepZ() * 9;
                IslandSites.cave(k, cx, cz, inland, true, y, false);
            }
            case ROOKERY -> IslandSites.rookery(k, x, y, z, 6);
            case HERMIT -> {
                int hy = k.surface(x + inland.getStepX() * 3, z + inland.getStepZ() * 3);
                IslandSites.hermit(k, x + inland.getStepX() * 3, hy, z + inland.getStepZ() * 3, p, toSea, culture);
            }
            case WHALE_BONES -> IslandSites.whaleBones(k, x, z, toSea.getClockWise());
            case HORKERS -> IslandSites.horkerRocks(k, x + toSea.getStepX() * 2, z + toSea.getStepZ() * 2);
            case PIRATE_CAMP -> IslandSites.pirateCamp(k, x, y, z, toSea);
        }
        return true;
    }
}
