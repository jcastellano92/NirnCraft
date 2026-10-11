package com.skycraft.tamriel.settlement.grammar;

import com.skycraft.tamriel.settlement.plan.Archetype;
import net.minecraft.util.RandomSource;

import java.util.Locale;

/**
 * What a grammar building is for. Each use has a footprint range (width along the street x depth), its storeys and
 * wall height, whether it is a big hall (hall roofs from the style sheet), whether it hangs out a sign, and the
 * interior program {@link Programs} furnishes it with.
 */
public enum Use {
    //            wMin wMax dMin dMax stMin stMax wallH hall  sign  trade
    HOVEL(5, 7, 5, 7, 1, 1, 3, false, false, false),
    COTTAGE(7, 9, 7, 9, 1, 1, 4, false, false, false),
    HOUSE(7, 11, 9, 11, 1, 2, 4, false, false, false),
    TOWNHOUSE(9, 11, 9, 13, 2, 3, 4, false, false, false),
    MANOR(13, 17, 13, 17, 2, 3, 5, false, false, false),
    FARMHOUSE(9, 11, 9, 11, 1, 2, 4, false, false, false),
    INN(13, 17, 15, 19, 2, 2, 5, true, true, true),
    GENERAL_STORE(9, 11, 11, 13, 1, 2, 4, false, true, true),
    SMITHY(11, 13, 9, 11, 1, 1, 4, false, true, true),
    ALCHEMIST(9, 11, 9, 11, 1, 2, 4, false, true, true),
    JEWELLER(7, 9, 9, 11, 1, 2, 4, false, true, true),
    BOOKSELLER(7, 9, 9, 11, 2, 2, 4, false, true, true),
    TAILOR(7, 9, 9, 11, 1, 2, 4, false, true, true),
    BUTCHER(7, 9, 9, 11, 1, 1, 4, false, true, true),
    FLETCHER(7, 9, 9, 11, 1, 1, 4, false, true, true),
    TANNERY(9, 11, 9, 11, 1, 1, 4, false, true, true),
    MAGE_TOWER(7, 9, 7, 9, 3, 4, 4, false, true, true),
    FENCE_DEN(5, 7, 7, 7, 1, 1, 3, false, false, true),
    STABLES(11, 13, 11, 13, 1, 1, 4, false, true, true),
    BARN(9, 11, 13, 15, 1, 1, 5, false, false, false),
    MILL(9, 11, 9, 11, 2, 2, 4, false, true, true),
    SAWMILL(11, 13, 9, 11, 1, 1, 5, false, true, true),
    SMELTER(9, 11, 9, 11, 1, 1, 5, false, false, true),
    WAREHOUSE(11, 13, 13, 15, 1, 1, 6, false, true, false),
    FISHERY(7, 9, 9, 11, 1, 1, 4, false, true, true),
    TEMPLE(11, 15, 17, 21, 1, 1, 7, true, true, false),
    HALL_OF_DEAD(9, 11, 11, 13, 1, 1, 6, true, true, false),
    GUARDHOUSE(7, 9, 9, 11, 1, 2, 4, false, false, false),
    BARRACKS(9, 11, 15, 17, 1, 2, 4, true, false, false),
    KEEP(17, 21, 19, 23, 2, 3, 6, true, true, false),
    LONGHOUSE(11, 13, 17, 23, 1, 1, 5, true, false, false),
    GUILD_HALL(11, 13, 13, 15, 2, 2, 5, true, true, false),
    /** A camp worker's shack (outlying sites). */
    SHACK(5, 7, 5, 7, 1, 1, 3, false, false, false),
    /** A manned watchtower on a road. */
    WATCHTOWER(7, 7, 7, 7, 3, 4, 4, false, false, false);

    public static final Use[] VALUES = values();

    public final int wMin, wMax, dMin, dMax, stMin, stMax, wallH;
    /** Big hall: the style sheet's hall roofs and taller walls. */
    public final boolean hall;
    /** Hangs out a sign with its name (shops, inns, temples). */
    public final boolean sign;
    /** A business whose keeper works here (registered as a workplace, gets a shop). */
    public final boolean trade;

    Use(int wMin, int wMax, int dMin, int dMax, int stMin, int stMax, int wallH, boolean hall, boolean sign, boolean trade) {
        this.wMin = wMin;
        this.wMax = wMax;
        this.dMin = dMin;
        this.dMax = dMax;
        this.stMin = stMin;
        this.stMax = stMax;
        this.wallH = wallH;
        this.hall = hall;
        this.sign = sign;
        this.trade = trade;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Use byOrdinal(int i) {
        return i >= 0 && i < VALUES.length ? VALUES[i] : HOUSE;
    }

    public static Use byId(String id) {
        for (Use u : VALUES) if (u.id().equals(id)) return u;
        return null;
    }

    /** A home (households sleep and keep their things here). */
    public boolean home() {
        return this == HOVEL || this == COTTAGE || this == HOUSE || this == TOWNHOUSE || this == MANOR || this == FARMHOUSE;
    }

    /** Footprint (odd width x odd depth) for a wealth tier: richer tiers lean to the top of the range. */
    public int[] size(Wealth w, RandomSource r) {
        double bias = w.ordinal() / 3.0;
        int wd = odd(wMin + (int) Math.round((wMax - wMin) * Math.min(1, bias * 0.6 + r.nextDouble() * 0.6)));
        int dp = odd(dMin + (int) Math.round((dMax - dMin) * Math.min(1, bias * 0.6 + r.nextDouble() * 0.6)));
        return new int[]{Math.max(wMin, Math.min(wMax, wd)), Math.max(dMin, Math.min(dMax, dp))};
    }

    static int odd(int v) {
        return (v & 1) == 0 ? v + 1 : v;
    }

    /** The legacy archetype a use stands in for (planner collision rules, owner names in old saves). */
    public Archetype archetype() {
        return switch (this) {
            case HOVEL, COTTAGE, HOUSE, TOWNHOUSE, SHACK -> Archetype.HOUSE;
            case MANOR -> Archetype.MANOR;
            case FARMHOUSE -> Archetype.FARMHOUSE;
            case INN -> Archetype.INN;
            case SMITHY -> Archetype.SMITHY;
            case GENERAL_STORE -> Archetype.STORE;
            case ALCHEMIST -> Archetype.ALCHEMIST;
            case TEMPLE -> Archetype.TEMPLE;
            case HALL_OF_DEAD -> Archetype.HALL_OF_DEAD;
            case BARRACKS, GUARDHOUSE -> Archetype.BARRACKS;
            case KEEP -> Archetype.PALACE;
            case LONGHOUSE -> Archetype.LONGHOUSE;
            case MILL -> Archetype.MILL;
            case WATCHTOWER, MAGE_TOWER -> Archetype.TOWER;
            default -> Archetype.BUILDING;
        };
    }

    /** The grammar use of a legacy archetype (unique layouts adopt the grammar for their ordinary buildings). */
    public static Use of(Archetype a, Wealth w) {
        return switch (a) {
            case HOUSE -> w == Wealth.POOR ? COTTAGE : w.atLeast(Wealth.WEALTHY) ? TOWNHOUSE : HOUSE;
            case MANOR -> MANOR;
            case FARMHOUSE -> FARMHOUSE;
            case INN -> INN;
            case SMITHY -> SMITHY;
            case STORE -> GENERAL_STORE;
            case ALCHEMIST -> ALCHEMIST;
            case TEMPLE -> TEMPLE;
            case HALL_OF_DEAD -> HALL_OF_DEAD;
            case BARRACKS -> BARRACKS;
            case LONGHOUSE -> LONGHOUSE;
            case MILL -> MILL;
            case STABLES -> STABLES;
            default -> null;
        };
    }
}
