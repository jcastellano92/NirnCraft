package com.skycraft.tamriel.settlement.plan;

/**
 * What a settlement piece is. Buildings ({@link #building}) have a footprint, a front door and an interior; the rest
 * are open-air works (streets, walls, fields...). Default footprints are for a Nord village; layouts scale them.
 */
public enum Archetype {
    // ---- buildings: default width x depth x height
    HOUSE(true, 7, 9, 10),
    MANOR(true, 11, 13, 14),
    LONGHOUSE(true, 11, 19, 13),
    INN(true, 13, 17, 14),
    SMITHY(true, 9, 9, 10),
    STORE(true, 7, 9, 11),
    ALCHEMIST(true, 7, 9, 11),
    TEMPLE(true, 11, 17, 16),
    SHRINE(true, 5, 5, 8),
    PALACE(true, 21, 31, 24),
    BARRACKS(true, 9, 15, 11),
    STABLES(true, 11, 13, 9),
    FARMHOUSE(true, 7, 9, 10),
    MILL(true, 9, 9, 13),
    HALL_OF_DEAD(true, 9, 11, 11),
    TOWER(true, 7, 7, 18),
    TENT(true, 5, 7, 6),
    // ---- works
    FIELD(false, 11, 13, 4),
    WALL(false, 1, 1, 10),
    GATE(false, 9, 7, 14),
    STALL(false, 3, 3, 5),
    DOCK(false, 5, 13, 4),
    MINE(false, 7, 7, 8),
    GRAVEYARD(false, 13, 11, 5),
    STREET(false, 1, 1, 4),
    PLAZA(false, 13, 13, 6),
    WELL(false, 3, 3, 5),
    LAMP(false, 1, 1, 4),
    GARDEN(false, 5, 5, 3),
    TERRACE(false, 9, 9, 3),
    BRIDGE(false, 5, 9, 6),
    CANAL(false, 7, 1, 6),
    LUMBER(false, 7, 5, 4),
    HOMESTEAD_PLOT(false, 23, 23, 4),
    LANDMARK(false, 9, 9, 20),
    // ---- appended (ordinals are saved in structure pieces)
    /** A faction's guild hall ({@link Spec#name} = faction id), see {@link Halls}. */
    GUILD_HALL(true, 13, 17, 14),
    /** A hold's jail: guard room, evidence chests and cells ({@link Spec#variant} 1 = Cidhna Mine), see {@link Halls}. */
    JAIL(true, 15, 21, 12),
    // ---- building grammar (appended: ordinals are saved)
    /** Any grammar building ({@link Spec#use} says which). */
    BUILDING(true, 9, 11, 12),
    /** Street furniture and yard clutter: carts, woodpiles, benches, notice boards, statues, fountains... */
    YARD(false, 5, 5, 5),
    /** A fenced animal pen with its animals. */
    PEN(false, 9, 9, 3),
    /** A building slot reserved by another module ({@link com.skycraft.tamriel.settlement.SettlementPlan}). */
    RESERVED(true, 9, 11, 12),
    /** A stepped open-cast quarry. */
    QUARRY(false, 17, 17, 10),
    /** The work ground of an outlying site: felled forest, drying racks, hives, vine rows, paddies... */
    CAMP(false, 13, 13, 6);

    public static final Archetype[] VALUES = values();

    public final boolean building;
    public final int w, d, h;

    Archetype(boolean building, int w, int d, int h) {
        this.building = building;
        this.w = w;
        this.d = d;
        this.h = h;
    }

    public static Archetype byOrdinal(int i) {
        return i >= 0 && i < VALUES.length ? VALUES[i] : HOUSE;
    }
}
