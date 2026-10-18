package com.skycraft.realms.worldgen.site;

import com.skycraft.Skycraft;
import net.minecraft.resources.Identifier;

/** Loot tables of the realms' structures ({@code data/skycraft/loot_tables/chests/realms/}, from tools/worldgen/realms.py). */
public final class RealmLoot {
    private RealmLoot() {}

    public static final Identifier DEADLANDS_SIGIL = id("deadlands_sigil");
    public static final Identifier DEADLANDS_CITADEL = id("deadlands_citadel");
    public static final Identifier DEADLANDS_SPIRE = id("deadlands_spire");
    public static final Identifier SOVNGARDE_HALL = id("sovngarde_hall");
    public static final Identifier SOVNGARDE_CAIRN = id("sovngarde_cairn");
    public static final Identifier ISLES_PALACE = id("isles_palace");
    public static final Identifier ISLES_RUIN = id("isles_ruin");
    public static final Identifier APOCRYPHA_CHAPTER = id("apocrypha_chapter");
    public static final Identifier APOCRYPHA_SUMMIT = id("apocrypha_summit");
    public static final Identifier SOUL_CAIRN = id("soul_cairn");
    public static final Identifier SOUL_CAIRN_KEEP = id("soul_cairn_keep");
    public static final Identifier COLDHARBOUR = id("coldharbour");
    public static final Identifier NIRN_GATE = id("nirn_gate");

    private static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(Skycraft.MODID, "chests/realms/" + path);
    }
}
