package com.nirncraft.dungeons.world.cavern;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Picks the look and the shape of a cavern, deterministically, from its {@link CavernContext}.
 *
 * <ol>
 *     <li><b>Eligible profiles</b> are those whose match lists contain the context; each is scored by its weight and by
 *     how many lists it satisfied (a region beats a province, a province a culture, a kit or a roster...).</li>
 *     <li><b>Ranking</b>: weighted random order (exponential keys) scrambled per 2 km square, theme and province, so
 *     the order is the same for every dungeon of the neighbourhood.</li>
 *     <li><b>Placement</b>: the dungeon's lattice colour (0..7, see {@link CavernTag}) indexes the first
 *     {@value #POOL} of the ranking, so dungeons in lattice cells that touch always get different looks, and the
 *     second cavern of a dungeon is offset by half the pool, so one dungeon never shows a look twice.</li>
 *     <li><b>Shape</b>: weighted among the profile's shapes by shape weight, piece size, depth and the dungeon's key;
 *     the shapes already taken by the dungeon's earlier caverns are avoided.</li>
 * </ol>
 */
public final class CavernSelector {
    public static final int POOL = 8;

    /** The outcome: the profile and shape, and where in the ranked list it came from. */
    public record Choice(CavernProfile profile, CavernShape shape, int rank, int pool) {}

    private CavernSelector() {}

    public static Choice select(CavernContext c) {
        CavernLibrary lib = CavernLibrary.get();
        List<CavernProfile> ranked = rank(lib, c);
        int k = Math.min(POOL, ranked.size());
        int shift = Math.max(1, k / 2);
        // the first cavern's pick first, so the later ones can avoid its shape
        EnumSet<CavernShape> taken = EnumSet.noneOf(CavernShape.class);
        Choice last = null;
        for (int o = 0; o <= Math.min(c.ordinal, 3); o++) {
            int j = Math.floorMod(c.color + o * shift, k);
            CavernProfile p = ranked.get(j);
            CavernShape s = shape(lib, p, c, o, taken);
            taken.add(s);
            last = new Choice(p, s, j, k);
        }
        return last;
    }

    /** Eligible profiles in this neighbourhood's order (best first). */
    public static List<CavernProfile> rank(CavernLibrary lib, CavernContext c) {
        long cell = CavernContext.mix(Math.floorDiv(c.x, 2048) * 73856093L ^ Math.floorDiv(c.z, 2048) * 19349663L
                ^ c.theme.ordinal() * 83492791L ^ c.province.hashCode() * 2654435761L);
        List<CavernProfile> ps = new ArrayList<>();
        List<Double> keys = new ArrayList<>();
        for (CavernProfile p : lib.profiles()) {
            double s = p.score(c);
            if (s <= 0) continue;
            double u = unit(CavernContext.mix(cell ^ p.id.hashCode() * 0x9E3779B97F4A7C15L));
            double key = -Math.log(Math.max(1e-9, u)) / s;
            int at = 0;
            while (at < keys.size() && keys.get(at) <= key) at++;
            ps.add(at, p);
            keys.add(at, key);
        }
        if (ps.isEmpty()) ps.add(lib.profiles().get(0));
        return ps;
    }

    /** The shape of a profile in a context (which of the dungeon's caverns, avoiding the shapes already taken). */
    static CavernShape shape(CavernLibrary lib, CavernProfile p, CavernContext c, int ordinal, EnumSet<CavernShape> avoid) {
        boolean elongated = Math.max(c.hx, c.hz) >= 30;
        CavernShape best = null;
        double bestKey = Double.MAX_VALUE;
        for (int pass = 0; pass < 2 && best == null; pass++) {
            for (var e : p.shapes.entrySet()) {
                CavernShape s = e.getKey();
                var def = lib.shape(s);
                if (c.height < def.minHeight()) continue;
                if (pass == 0 && avoid.contains(s)) continue;
                double w = e.getValue() * def.weight();
                if (def.elongated()) w *= elongated ? 1.6 : 0.7;
                w *= Math.max(0.2, 1.0 + def.tierBias() * (c.tier - 3));
                if (w <= 0) continue;
                double u = unit(CavernContext.mix(c.key ^ p.id.hashCode() * 31L ^ s.ordinal() * 0x7F4A7C15L ^ ordinal * 0x51ED27L));
                double key = -Math.log(Math.max(1e-9, u)) / w;
                if (key < bestKey) {
                    bestKey = key;
                    best = s;
                }
            }
        }
        return best != null ? best : CavernShape.BOWL_BRIDGES;
    }

    private static double unit(long h) {
        return ((h >>> 11) + 0.5) / (double) (1L << 53);
    }
}
