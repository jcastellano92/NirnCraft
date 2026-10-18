package com.skycraft.seas.world;

import com.mojang.serialization.Codec;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.worldgen.ConfiguredFeature;
import com.skycraft.worldgen.FeaturePlaceContext;
import com.skycraft.worldgen.NoneFeatureConfiguration;

import java.util.ArrayList;
import java.util.List;

/**
 * Sites on the sea bed, chosen by where in Nirn's seas the chunk lies ({@link Undersea} builds them):
 * <ul>
 *     <li>sunken Yokudan ruins towards Yokuda and in the Abecean (the drowned continent), with a working sea-ward;</li>
 *     <li>Sload coral spires off Thras; sunken Maormer warships towards Pyandonea and Summerset;</li>
 *     <li>Dreugh lairs in the Inner Sea and Morrowind's waters; Dwemer ruins with dry air-pocket chambers behind
 *     doors;</li>
 *     <li>ancient Nordic longship graves in the Sea of Ghosts; slaughterfish nests in any sea; sea serpent lairs in
 *     the deep open ocean.</li>
 * </ul>
 * Placed in the sea biomes by {@code skycraft:seas_sea_sites}.
 */
public class SeaSiteFeature extends ConfiguredFeature<NoneFeatureConfiguration> {
    public enum Kind { YOKUDAN_RUIN, SLOAD_SPIRE, DREUGH_LAIR, MAORMER_WRECK, DWEMER_RUIN, NORDIC_GRAVE, SLAUGHTERFISH_NEST, SERPENT_LAIR }

    /** Sites placed since start (logged by the developer smoke test). */
    public static final java.util.concurrent.atomic.AtomicInteger PLACED = new java.util.concurrent.atomic.AtomicInteger();

    public SeaSiteFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> ctx) {
        SiteKit k = new SiteKit(ctx.level(), ctx.random(), ctx.origin());
        int depth = k.depth(k.cx, k.cz);
        if (depth < 6) return false;
        Kind kind = choose(k, depth);
        boolean ok = kind != null && build(k, kind);
        if (ok) PLACED.incrementAndGet();
        return ok;
    }

    /** Builds a site of the given kind at the kit's centre (also used by {@code /skycraft-seas site}). */
    public static boolean build(SiteKit k, Kind kind) {
        return switch (kind) {
            case YOKUDAN_RUIN -> Undersea.yokudanRuin(k);
            case SLOAD_SPIRE -> Undersea.sloadSpire(k);
            case DREUGH_LAIR -> Undersea.dreughLair(k);
            case MAORMER_WRECK -> Undersea.maormerWreck(k);
            case DWEMER_RUIN -> Undersea.dwemerRuin(k);
            case NORDIC_GRAVE -> Undersea.nordicGrave(k);
            case SLAUGHTERFISH_NEST -> Undersea.slaughterfishNest(k);
            case SERPENT_LAIR -> Undersea.serpentLair(k);
        };
    }

    private static double dist(int x, int z, int fx, int fz) {
        return Math.hypot(x - fx, z - fz);
    }

    private static Kind choose(SiteKit k, int depth) {
        int x = k.cx, z = k.cz;
        Region region = TamrielMap.region(x, z);
        MapSample s = TamrielMap.sampleScratch(x, z);
        String id = region.id(), prov = region.province();
        List<Kind> kinds = new ArrayList<>();
        List<Integer> weights = new ArrayList<>();
        // the drowned continent and its approaches
        double yok = dist(x, z, -19000, 7000);
        if (prov.equals("yokuda") || yok < 9000) add(kinds, weights, Kind.YOKUDAN_RUIN, prov.equals("yokuda") ? 12 : 8);
        else if (id.equals("sea/abecean_sea") || id.equals("sea/iliac_bay") || prov.equals("hammerfell")) add(kinds, weights, Kind.YOKUDAN_RUIN, 2);
        if (prov.equals("thras") || dist(x, z, -15000, 16500) < 5500) add(kinds, weights, Kind.SLOAD_SPIRE, 12);
        double pyan = dist(x, z, -17000, 19000);
        if (prov.equals("pyandonea") || pyan < 7000) add(kinds, weights, Kind.MAORMER_WRECK, 8);
        else if (prov.equals("summerset") || dist(x, z, -10500, 12000) < 3500) add(kinds, weights, Kind.MAORMER_WRECK, 2);
        boolean innerSea = id.equals("sea/inner_sea");
        if (innerSea) add(kinds, weights, Kind.DREUGH_LAIR, 8);
        else if (prov.equals("morrowind")) add(kinds, weights, Kind.DREUGH_LAIR, 5);
        else if (id.equals("sea/topal_bay") || prov.equals("black_marsh")) add(kinds, weights, Kind.DREUGH_LAIR, 2);
        if (innerSea || prov.equals("morrowind")) add(kinds, weights, Kind.DWEMER_RUIN, 3);
        else if (id.equals("sea/sea_of_ghosts") && x > -1000) add(kinds, weights, Kind.DWEMER_RUIN, 2);
        else if (prov.equals("skyrim")) add(kinds, weights, Kind.DWEMER_RUIN, 1);
        if (id.equals("sea/sea_of_ghosts") || prov.equals("skyrim")) add(kinds, weights, Kind.NORDIC_GRAVE, 6);
        else if (prov.equals("atmora") || dist(x, z, 0, -11000) < 6000) add(kinds, weights, Kind.NORDIC_GRAVE, 4);
        add(kinds, weights, Kind.SLAUGHTERFISH_NEST, id.equals("sea/sea_of_ghosts") ? 4 : 3);
        if (depth >= 18 && s.coastDistance < -250) {
            boolean open = id.equals("sea/open_ocean") || id.equals("sea/padomaic_ocean") || id.equals("sea/eltheric_ocean");
            add(kinds, weights, Kind.SERPENT_LAIR, open ? 3 : 1);
        }
        int total = 0;
        for (int w : weights) total += w;
        if (total <= 0) return null;
        int roll = k.r.nextInt(total);
        for (int i = 0; i < kinds.size(); i++) {
            roll -= weights.get(i);
            if (roll < 0) return kinds.get(i);
        }
        return null;
    }

    private static void add(List<Kind> kinds, List<Integer> weights, Kind kind, int weight) {
        kinds.add(kind);
        weights.add(weight);
    }
}
