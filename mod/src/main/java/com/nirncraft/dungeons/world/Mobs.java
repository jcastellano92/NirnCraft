package com.nirncraft.dungeons.world;

/**
 * Dungeon inhabitants by registry id. Creatures (contract 7) and optional mods are looked up at generation time and
 * silently skipped (or replaced by a vanilla stand-in) when missing.
 */
public final class Mobs {
    private Mobs() {}

    public static final String DRAUGR = "nirncraft:draugr";
    public static final String DEATHLORD = "nirncraft:draugr_deathlord";
    public static final String BANDIT = "nirncraft:bandit";
    public static final String BANDIT_CHIEF = "nirncraft:bandit_chief";
    public static final String SKEEVER = "nirncraft:skeever";
    public static final String TROLL = "nirncraft:troll";
    public static final String GIANT = "nirncraft:giant";
    public static final String DWARVEN_SPIDER = "nirncraft:dwarven_spider";
    public static final String DWARVEN_SPHERE = "nirncraft:dwarven_sphere";
    public static final String DWARVEN_CENTURION = "nirncraft:dwarven_centurion";
    public static final String GIANT_SCORPION = "nirncraft:giant_scorpion";

    public static String draugr() {
        return orElse(DRAUGR, "minecraft:zombie");
    }

    public static String deathlord() {
        return orElse(DEATHLORD, "minecraft:wither_skeleton");
    }

    public static String bandit() {
        return orElse(BANDIT, "minecraft:vindicator");
    }

    public static String banditChief() {
        return orElse(BANDIT_CHIEF, BANDIT, "minecraft:vindicator");
    }

    public static String skeever() {
        return orElse(SKEEVER, "minecraft:cave_spider");
    }

    public static String spider() {
        return orElse("nirncraft:frostbite_spider", "minecraft:cave_spider");
    }

    public static String giantScorpion() {
        return orElse(GIANT_SCORPION, "nirncraft:frostbite_spider", "minecraft:cave_spider");
    }

    public static String vampire() {
        return orElse("nirncraft:vampire", "minecraft:spider");
    }

    public static String vampireMaster() {
        return orElse("nirncraft:vampire", "minecraft:witch");
    }

    /** The master of a vampire lair (curse module). */
    public static String vampireLord() {
        return orElse("nirncraft:vampire_lord", "nirncraft:vampire", "minecraft:witch");
    }

    /** The vampires' undead hounds (curse module). */
    public static String deathHound() {
        return orElse("nirncraft:death_hound", "minecraft:wolf");
    }

    /** Stone guardians of vampire lairs; they wait as statues (curse module). */
    public static String gargoyle() {
        return orElse("nirncraft:gargoyle", "minecraft:iron_golem");
    }

    public static String necromancer() {
        return orElse("nirncraft:necromancer", "minecraft:evoker");
    }

    public static String hagraven() {
        return orElse("nirncraft:hagraven", "minecraft:witch");
    }

    public static String falmer() {
        return orElse("nirncraft:falmer", "minecraft:zombie");
    }

    /** Reach natives: camps, redoubts and hagraven lairs. */
    public static String forsworn() {
        return orElse("nirncraft:forsworn", "minecraft:vindicator");
    }

    /** Groves and forest dens. */
    public static String spriggan() {
        return orElse("nirncraft:spriggan", "minecraft:vex");
    }

    /** Ice caves and snowbound ruins. */
    public static String iceWraith() {
        return orElse("nirncraft:ice_wraith", "minecraft:stray");
    }

    /** Misty forest dens and grove hearts. */
    public static String wispmother() {
        return orElse("nirncraft:wispmother", "minecraft:witch");
    }

    /** Military camps: the guard entity stands in for soldiers until NPC soldiers exist. */
    public static String soldier() {
        return orElse("nirncraft:soldier", "nirncraft:guard", "minecraft:villager");
    }

    public static String troll() {
        return orElse(TROLL, "minecraft:ravager");
    }

    public static String sabreCat() {
        return orElse("nirncraft:sabre_cat", "nirncraft:bear", "minecraft:wolf");
    }

    /** Shore wildlife; null (nothing spawns) without the fauna module. */
    public static String horker() {
        return Painter.firstExisting("nirncraft:horker");
    }

    public static String mudcrab() {
        return Painter.firstExisting("nirncraft:mudcrab");
    }

    /** Wildlife for animal dens: fauna's beasts if registered, frostbite spiders, wolves, and skeevers. */
    public static String denBeast(int roll) {
        return switch (Math.floorMod(roll, 4)) {
            case 0 -> orElse("nirncraft:bear", "nirncraft:wolf", SKEEVER, "minecraft:wolf");
            case 1 -> orElse("nirncraft:wolf", "minecraft:wolf");
            case 2 -> spider();
            default -> skeever();
        };
    }

    public static String denBoss() {
        return orElse(TROLL, "nirncraft:frostbite_spider", "nirncraft:bear", "minecraft:ravager");
    }

    public static String mammoth() {
        return Painter.firstExisting("nirncraft:mammoth");
    }

    /** First registered id of the list. */
    public static String orElse(String... ids) {
        String id = Painter.firstExisting(ids);
        return id != null ? id : ids[ids.length - 1];
    }
}
