package com.skycraft.dungeons.world;

/**
 * Dungeon inhabitants by registry id. Creatures (contract 7) and optional mods are looked up at generation time and
 * silently skipped (or replaced by a vanilla stand-in) when missing.
 */
public final class Mobs {
    private Mobs() {}

    public static final String DRAUGR = "skycraft:draugr";
    public static final String DEATHLORD = "skycraft:draugr_deathlord";
    public static final String BANDIT = "skycraft:bandit";
    public static final String BANDIT_CHIEF = "skycraft:bandit_chief";
    public static final String SKEEVER = "skycraft:skeever";
    public static final String TROLL = "skycraft:troll";
    public static final String GIANT = "skycraft:giant";
    public static final String DWARVEN_SPIDER = "skycraft:dwarven_spider";
    public static final String DWARVEN_SPHERE = "skycraft:dwarven_sphere";
    public static final String DWARVEN_CENTURION = "skycraft:dwarven_centurion";
    public static final String GIANT_SCORPION = "skycraft:giant_scorpion";

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
        return orElse("skycraft:frostbite_spider", "minecraft:cave_spider");
    }

    public static String giantScorpion() {
        return orElse(GIANT_SCORPION, "skycraft:frostbite_spider", "minecraft:cave_spider");
    }

    public static String vampire() {
        return orElse("skycraft:vampire", "minecraft:spider");
    }

    public static String vampireMaster() {
        return orElse("skycraft:vampire", "minecraft:witch");
    }

    /** The master of a vampire lair (curse module). */
    public static String vampireLord() {
        return orElse("skycraft:vampire_lord", "skycraft:vampire", "minecraft:witch");
    }

    /** The vampires' undead hounds (curse module). */
    public static String deathHound() {
        return orElse("skycraft:death_hound", "minecraft:wolf");
    }

    /** Stone guardians of vampire lairs; they wait as statues (curse module). */
    public static String gargoyle() {
        return orElse("skycraft:gargoyle", "minecraft:iron_golem");
    }

    public static String necromancer() {
        return orElse("skycraft:necromancer", "minecraft:evoker");
    }

    public static String hagraven() {
        return orElse("skycraft:hagraven", "minecraft:witch");
    }

    public static String falmer() {
        return orElse("skycraft:falmer", "minecraft:zombie");
    }

    /** Reach natives: camps, redoubts and hagraven lairs. */
    public static String forsworn() {
        return orElse("skycraft:forsworn", "minecraft:vindicator");
    }

    /** Groves and forest dens. */
    public static String spriggan() {
        return orElse("skycraft:spriggan", "minecraft:vex");
    }

    /** Ice caves and snowbound ruins. */
    public static String iceWraith() {
        return orElse("skycraft:ice_wraith", "minecraft:stray");
    }

    /** Misty forest dens and grove hearts. */
    public static String wispmother() {
        return orElse("skycraft:wispmother", "minecraft:witch");
    }

    /** Military camps: the guard entity stands in for soldiers until NPC soldiers exist. */
    public static String soldier() {
        return orElse("skycraft:soldier", "skycraft:guard", "minecraft:villager");
    }

    public static String troll() {
        return orElse(TROLL, "minecraft:ravager");
    }

    public static String sabreCat() {
        return orElse("skycraft:sabre_cat", "skycraft:bear", "minecraft:wolf");
    }

    /** Shore wildlife; null (nothing spawns) without the fauna module. */
    public static String horker() {
        return Painter.firstExisting("skycraft:horker");
    }

    public static String mudcrab() {
        return Painter.firstExisting("skycraft:mudcrab");
    }

    /** Wildlife for animal dens: fauna's beasts if registered, frostbite spiders, wolves, and skeevers. */
    public static String denBeast(int roll) {
        return switch (Math.floorMod(roll, 4)) {
            case 0 -> orElse("skycraft:bear", "skycraft:wolf", SKEEVER, "minecraft:wolf");
            case 1 -> orElse("skycraft:wolf", "minecraft:wolf");
            case 2 -> spider();
            default -> skeever();
        };
    }

    public static String denBoss() {
        return orElse(TROLL, "skycraft:frostbite_spider", "skycraft:bear", "minecraft:ravager");
    }

    public static String mammoth() {
        return Painter.firstExisting("skycraft:mammoth");
    }

    /** First registered id of the list. */
    public static String orElse(String... ids) {
        String id = Painter.firstExisting(ids);
        return id != null ? id : ids[ids.length - 1];
    }
}
