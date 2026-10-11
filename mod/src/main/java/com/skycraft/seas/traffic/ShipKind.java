package com.skycraft.seas.traffic;

import com.skycraft.sailing.ShipLivery;
import com.skycraft.sailing.ShipType;

import java.util.Locale;

/**
 * The ships of the sea traffic: who sails them, how they are painted, whether they hunt players, what is in their
 * holds ({@code skycraft:chests/seas/cargo_<id>}) and who crews them (society roles, or creatures for the dead).
 */
public enum ShipKind {
    /** A merchant cog: a trader who will deal from the deck, and a couple of hands. */
    MERCHANT(ShipType.COG, ShipLivery.MERCHANT, false, new Crew[]{
            Crew.npc("merchant", 1, "seas.skycraft.crew.ship_merchant"),
            Crew.npc("fisher", 2, "seas.skycraft.crew.deckhand")}),
    /** An Imperial navy cog patrolling the sea lanes. */
    NAVY(ShipType.COG, ShipLivery.NAVY, false, new Crew[]{
            Crew.npc("imperial_soldier", 3, "seas.skycraft.crew.marine")}),
    /** A Nord longship: traders and fishermen of the north (raiders only when provoked). */
    NORD(ShipType.LONGSHIP, ShipLivery.NORD, false, new Crew[]{
            Crew.npc("hunter", 2, "seas.skycraft.crew.nord_sailor"),
            Crew.npc("adventurer", 1, "seas.skycraft.crew.nord_sailor")}),
    /** A pirate longship (Sea of Ghosts, the Abecean, the Iliac Bay): fast, and it comes for you. */
    PIRATE_LONGSHIP(ShipType.LONGSHIP, ShipLivery.PIRATE, true, new Crew[]{
            Crew.npc("thug", 3, "seas.skycraft.crew.pirate")}),
    /** A pirate cog with a bigger crew and a richer hold. */
    PIRATE_COG(ShipType.COG, ShipLivery.PIRATE, true, new Crew[]{
            Crew.npc("thug", 4, "seas.skycraft.crew.pirate"),
            Crew.npc("assassin", 1, "seas.skycraft.crew.pirate_captain")}),
    /** A Maormer warship out of Pyandonea, sea-elves who raid Tamriel's coasts. */
    MAORMER(ShipType.LONGSHIP, ShipLivery.MAORMER, true, new Crew[]{
            Crew.npc("thug", 3, "seas.skycraft.crew.maormer_raider"),
            Crew.npc("mage", 1, "seas.skycraft.crew.maormer_tidecaller")}),
    /** A Sload hulk off Thras: a necromancer and the drowned dead that row for it. */
    SLOAD(ShipType.COG, ShipLivery.SLOAD, true, new Crew[]{
            Crew.npc("necromancer", 1, "seas.skycraft.crew.sload_thrallmaster"),
            Crew.creature("skycraft:draugr", 3, "seas.skycraft.crew.drowned_thrall")}),
    /** A ghost ship, seen at night: the drowned crew still sail it. */
    GHOST(ShipType.LONGSHIP, ShipLivery.GHOST, true, new Crew[]{
            Crew.creature("skycraft:draugr", 3, "seas.skycraft.crew.ghost_sailor")});

    /** One crew group: a society role or a creature type, how many, and a title. */
    public record Crew(String role, String entity, int count, String name) {
        static Crew npc(String role, int count, String name) {
            return new Crew(role, "", count, name);
        }

        static Crew creature(String entity, int count, String name) {
            return new Crew("", entity, count, name);
        }
    }

    public final String id;
    public final ShipType type;
    public final ShipLivery livery;
    /** Gives chase to players' ships and boards them. */
    public final boolean hostile;
    public final Crew[] crew;

    ShipKind(ShipType type, ShipLivery livery, boolean hostile, Crew[] crew) {
        this.id = name().toLowerCase(Locale.ROOT);
        this.type = type;
        this.livery = livery;
        this.hostile = hostile;
        this.crew = crew;
    }

    public String cargoTable() {
        return "chests/seas/cargo_" + switch (this) {
            case PIRATE_LONGSHIP, PIRATE_COG -> "pirate";
            default -> id;
        };
    }

    public static ShipKind byId(String id) {
        for (ShipKind k : values()) if (k.id.equals(id)) return k;
        return null;
    }
}
