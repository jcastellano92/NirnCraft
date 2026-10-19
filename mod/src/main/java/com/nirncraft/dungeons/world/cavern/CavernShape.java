package com.nirncraft.dungeons.world.cavern;

import java.util.Locale;

/**
 * The macro-shapes of a grand cavern. Each is a parametric recipe (footprint, floor profile, roof, columns and
 * structures) implemented by {@code GrandCavern}; the numbers come from {@code shapes.json} and may be overridden per
 * profile. Ids are the data names.
 */
public enum CavernShape {
    /** Terraced bowl or chasm with a lake and bridges to a central platform. */
    BOWL_BRIDGES,
    /** Long gorge with a river, ledges on both sides and a bridge over it. */
    CANYON,
    /** Round well with a spiral ramp down its wall to a landing above the water. */
    SHAFT,
    /** Deep bowl in many steep tiers with a ring of columns on the upper rim. */
    AMPHITHEATRE,
    /** Shallow flooded floor crowded with pillars and dry causeways. */
    PILLAR_MAZE,
    /** A river of lava (or water) across the long axis, with causeways. */
    LAVA_RIVER,
    /** Hanging roots and giant flared trunks. */
    ROOT_CAVERN,
    /** Long round pipe with a stream down its middle. */
    LAVA_TUBE,
    /** Lattice of cell pillars and low cell walls. */
    HONEYCOMB,
    /** Street grid of sunken lanes between ruined building shells. */
    COLLAPSED_CITY,
    /** Narrow zig-zag fissure, deep, with overhanging roof. */
    CREVASSE,
    /** Flat nave with rows of columns and vaulted ribs. */
    CATHEDRAL,
    /** Columns of every size and a sinkhole of light in the roof. */
    KARST,
    /** One side falls away to a tide pool, sea stacks stand in it. */
    SEA_CAVE,
    /** Chasm with islands joined to the cross by spurs. */
    FLOATING_ISLANDS,
    /** Rib arches, a spine and a skull of a vast creature. */
    BONE_CAVERN;

    public static final CavernShape[] VALUES = values();

    public final String id = name().toLowerCase(Locale.ROOT);

    public static CavernShape byId(String id) {
        for (CavernShape s : VALUES) if (s.id.equals(id)) return s;
        return null;
    }
}
