package com.skycraft.landmarks;

import java.util.Locale;

/**
 * The four scales of natural landmark. Each tier has its own grid of cells (the cell size is the spacing: a tier's
 * sites are never closer than the cell margin allows) and its own share of occupied cells. Sites sit at the middle of
 * a chunk so a builder always has 23 blocks to every side inside the 3x3 chunks a feature may write to.
 *
 * <ul>
 *     <li>{@link #S}: tiny vignettes (a grave, a cairn, a cold camp, a lover's tree);</li>
 *     <li>{@link #M}: scenes with a story, often someone in them (a pilgrim at a shrine, a smuggler's drop);</li>
 *     <li>{@link #L}: natural formations (arches, hoodoos, sinkholes, a bone field);</li>
 *     <li>{@link #XL}: visible from afar (a colossal tree, a spire stack, a crater lake, a mesa with a path).</li>
 * </ul>
 */
public enum Tier {
    //  cell chunks, occupied share, footprint cap (blocks from the centre), margin to named places
    S(8, 0.28, 8, 36),
    M(12, 0.34, 12, 48),
    L(20, 0.46, 22, 70),
    XL(56, 0.06, 22, 110);

    public static final Tier[] VALUES = values();

    public final String id;
    public final int cellChunks;
    public final int cell;
    public final double occupancy;
    public final int radiusCap;
    public final int placeMargin;

    Tier(int cellChunks, double occupancy, int radiusCap, int placeMargin) {
        this.id = name().toLowerCase(Locale.ROOT);
        this.cellChunks = cellChunks;
        this.cell = cellChunks * 16;
        this.occupancy = occupancy;
        this.radiusCap = radiusCap;
        this.placeMargin = placeMargin;
    }

    public static Tier byId(String id) {
        for (Tier t : VALUES) if (t.id.equalsIgnoreCase(id)) return t;
        return null;
    }
}
