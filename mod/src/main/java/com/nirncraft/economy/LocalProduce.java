package com.nirncraft.economy;

import com.nirncraft.tamriel.settlement.Culture;
import com.nirncraft.tamriel.settlement.grammar.Styles;

import java.util.List;
import java.util.Set;

/**
 * What a named place makes of its own, decided once from its id so the land and the market agree: the settlement
 * planner builds an apiary outside only where {@link #keepsBees} holds, and the economy lists honey among that place's
 * exports (and its inn pours honey and serves honey treats) for exactly the same places. Places without hives simply
 * do not have honey, which is what makes the ones that do worth finding.
 */
public final class LocalProduce {
    /** Share of a honey-keeping culture's places that actually keep hives. */
    private static final int BEE_PERCENT = 55;
    public static final List<String> HONEY_GOODS = List.of("minecraft:honey_bottle", "minecraft:honeycomb", "nirncraft:honey_nut_treat");

    private LocalProduce() {}

    /** True if the named place {@code placeKey} of culture {@code c} keeps hives. Unnamed (generated) places always may. */
    public static boolean keepsBees(String placeKey, Culture c) {
        if (placeKey == null || placeKey.startsWith("gen:")) return true;
        try {
            if (!Styles.of(c, 0).crops().contains("honey")) return false;
        } catch (RuntimeException e) {
            return false;
        }
        long h = 1125899906842597L;
        for (int i = 0; i < placeKey.length(); i++) h = 31 * h + placeKey.charAt(i);
        h ^= h >>> 29;
        h *= 0x9E3779B97F4A7C15L;
        h ^= h >>> 32;
        return Math.floorMod(h, 100L) < BEE_PERCENT;
    }

    /** Adds this place's own goods to its export set. */
    public static void addTo(Set<String> exports, String placeKey, String culture) {
        if (placeKey != null && !placeKey.startsWith("gen:") && keepsBees(placeKey, Culture.of(culture))) exports.addAll(HONEY_GOODS);
    }
}

