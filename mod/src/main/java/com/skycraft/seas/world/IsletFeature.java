package com.skycraft.seas.world;

import com.mojang.serialization.Codec;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Small islands raised from the shallows with something on them ({@link IslandSites}): a lighthouse and its keeper,
 * a pirate cove with buried treasure, a smugglers' tidal cave, a seabird rookery, a sea stack with a hawk's nest, a
 * hermit's hut, a wreck cast up on a sandbar, or (in the Sea of Ghosts) an ice floe with horkers and seals. Placed in
 * the sea biomes by {@code skycraft:seas_islets}.
 */
public class IsletFeature extends Feature<NoneFeatureConfiguration> {
    public enum Kind { LIGHTHOUSE, PIRATE_COVE, TIDAL_CAVE, ROOKERY, SEA_STACK, HERMIT, WRECK, ICE_FLOE }

    /** Sites placed since start (logged by the developer smoke test). */
    public static final java.util.concurrent.atomic.AtomicInteger PLACED = new java.util.concurrent.atomic.AtomicInteger();

    public IsletFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        SiteKit k = new SiteKit(ctx.level(), ctx.random(), ctx.origin());
        int depth = k.depth(k.cx, k.cz);
        if (depth < 2 || depth > 16 || k.surface(k.cx, k.cz) > IslandSites.SEA + 1) return false;
        MapSample s = TamrielMap.sampleScratch(k.cx, k.cz);
        if (s.coastDistance > -12) return false;
        Kind kind = choose(k, s);
        PLACED.incrementAndGet();
        return build(k, kind);
    }

    private static Kind choose(SiteKit k, MapSample s) {
        Region r = TamrielMap.region(k.cx, k.cz);
        String id = r.id();
        float temp = k.level.getBiome(new BlockPos(k.cx, IslandSites.SEA, k.cz)).value().getBaseTemperature();
        boolean pirateWaters = id.equals("sea/abecean_sea") || id.equals("sea/iliac_bay") || id.equals("sea/topal_bay")
                || id.equals("sea/sea_of_ghosts") || id.equals("sea/eltheric_ocean") || id.equals("sea/padomaic_ocean");
        int[] w = new int[Kind.values().length];
        w[Kind.LIGHTHOUSE.ordinal()] = s.coastDistance > -450 ? 3 : 1;
        w[Kind.PIRATE_COVE.ordinal()] = pirateWaters ? 3 : 1;
        w[Kind.TIDAL_CAVE.ordinal()] = 2;
        w[Kind.ROOKERY.ordinal()] = 3;
        w[Kind.SEA_STACK.ordinal()] = 3;
        w[Kind.HERMIT.ordinal()] = 2;
        w[Kind.WRECK.ordinal()] = 2;
        w[Kind.ICE_FLOE.ordinal()] = temp < 0.15f ? 6 : 0;
        int total = 0;
        for (int x : w) total += x;
        int roll = k.r.nextInt(total);
        for (Kind kind : Kind.values()) {
            roll -= w[kind.ordinal()];
            if (roll < 0) return kind;
        }
        return Kind.SEA_STACK;
    }

    /** Builds an islet of the given kind at the kit's centre (also used by {@code /skycraft-seas site}). */
    public static boolean build(SiteKit k, Kind kind) {
        int sea = IslandSites.SEA;
        Direction face = Direction.Plane.HORIZONTAL.getRandomDirection(k.r);
        String culture = IslandSites.cultureNear(k.cx, k.cz);
        CoastPalette p = CoastPalette.of(culture);
        IslandSites.Ground g = IslandSites.Ground.of(k);
        switch (kind) {
            case ICE_FLOE -> IslandSites.iceFloe(k, k.cx, k.cz);
            case SEA_STACK -> {
                IslandSites.seaStack(k, k.cx, k.cz);
                for (int i = 0; i < 1 + k.r.nextInt(2); i++) {
                    int x = k.cx + k.nextInt(-9, 9), z = k.cz + k.nextInt(-9, 9);
                    int bed = k.floor(x, z);
                    for (int y = bed; y <= sea + 2 + k.r.nextInt(5); y++) {
                        k.set(x, y, z, Blocks.STONE.defaultBlockState());
                        k.set(x + 1, y, z, Blocks.ANDESITE.defaultBlockState());
                    }
                }
            }
            case LIGHTHOUSE -> {
                k.mound(k.cx, k.cz, 13f, sea + 6, g.core(), g.skin(), g.beach(), sea);
                int y = k.surface(k.cx, k.cz);
                IslandSites.lighthouse(k, k.cx, y, k.cz, p, face, culture);
                int hx = k.cx + face.getStepX() * 6, hz = k.cz + face.getStepZ() * 6;
                IslandSites.cottage(k, hx, k.surface(hx, hz), hz, p, face.getClockWise(), culture, SiteKit.loot("chests/seas/lighthouse"));
                Direction out = face.getOpposite();
                IslandSites.jetty(k, k.cx + out.getStepX() * 11, k.cz + out.getStepZ() * 11, out, p);
                LairMarkerBlockEntity m = k.marker(hx, k.surface(hx, hz) + 1, hz);
                if (m != null) m.npc("fisher", 1, "seas.skycraft.npc.lighthouse_keeper", false);
            }
            case PIRATE_COVE -> {
                // a crescent ridge sheltering a sandy lagoon
                k.mound(k.cx, k.cz, 11f, sea + 5, g.core(), g.skin(), Blocks.SAND.defaultBlockState(), sea);
                for (int dx = -5; dx <= 5; dx++) {
                    for (int dz = -5; dz <= 5; dz++) {
                        int lx = k.cx + face.getStepX() * 5 + dx, lz = k.cz + face.getStepZ() * 5 + dz;
                        if (dx * dx + dz * dz > 22) continue;
                        for (int y = sea; y <= sea + 6; y++) k.clear(lx, y, lz, sea);
                        k.set(lx, sea - 1, lz, Blocks.SAND.defaultBlockState());
                    }
                }
                int cx = k.cx - face.getStepX() * 2, cz = k.cz - face.getStepZ() * 2;
                for (int dx = -6; dx <= 6; dx++) for (int dz = -6; dz <= 6; dz++) {
                    if (dx * dx + dz * dz > 30) continue;
                    int top = k.surface(cx + dx, cz + dz);
                    for (int y = sea + 2; y < top; y++) k.air(cx + dx, y, cz + dz);
                    if (top > sea + 1) k.set(cx + dx, sea + 1, cz + dz, Blocks.SAND.defaultBlockState());
                }
                IslandSites.pirateCamp(k, cx, sea + 2, cz, face);
            }
            case TIDAL_CAVE -> {
                k.mound(k.cx, k.cz, 10f, sea + 10, Blocks.STONE.defaultBlockState(), Blocks.STONE.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(), sea);
                IslandSites.seaCave(k, k.cx, k.cz, face, k.r.nextBoolean());
            }
            case ROOKERY -> {
                k.mound(k.cx, k.cz, 9f, sea + 9, g.core(), Blocks.STONE.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(), sea);
                IslandSites.rookery(k, k.cx, sea + 9, k.cz, 7);
                LairMarkerBlockEntity m = k.marker(k.cx, sea + 12, k.cz);
                if (m != null && k.r.nextBoolean()) m.creature("skycraft:hawk", 2, 3);
            }
            case HERMIT -> {
                k.mound(k.cx, k.cz, 13f, sea + 4, g.core(), g.skin(), g.beach(), sea);
                IslandSites.hermit(k, k.cx, k.surface(k.cx, k.cz), k.cz, p, face, culture);
                Direction out = face.getOpposite();
                IslandSites.jetty(k, k.cx + out.getStepX() * 10, k.cz + out.getStepZ() * 10, out, p);
            }
            case WRECK -> {
                k.mound(k.cx, k.cz, 10f, sea + 2, Blocks.SAND.defaultBlockState(), Blocks.SAND.defaultBlockState(), Blocks.SAND.defaultBlockState(), sea);
                IslandSites.wreckAshore(k, k.cx, k.cz, face);
            }
        }
        return true;
    }
}
