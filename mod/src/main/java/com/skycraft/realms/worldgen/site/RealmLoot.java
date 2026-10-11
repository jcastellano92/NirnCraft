package com.skycraft.realms.worldgen.site;

import com.skycraft.Skycraft;
import net.minecraft.resources.ResourceLocation;

/** Loot tables of the realms' structures ({@code data/skycraft/loot_tables/chests/realms/}, from tools/worldgen/realms.py). */
public final class RealmLoot {
    private RealmLoot() {}

    public static final ResourceLocation DEADLANDS_SIGIL = id("deadlands_sigil");
    public static final ResourceLocation DEADLANDS_CITADEL = id("deadlands_citadel");
    public static final ResourceLocation DEADLANDS_SPIRE = id("deadlands_spire");
    public static final ResourceLocation SOVNGARDE_HALL = id("sovngarde_hall");
    public static final ResourceLocation SOVNGARDE_CAIRN = id("sovngarde_cairn");
    public static final ResourceLocation ISLES_PALACE = id("isles_palace");
    public static final ResourceLocation ISLES_RUIN = id("isles_ruin");
    public static final ResourceLocation APOCRYPHA_CHAPTER = id("apocrypha_chapter");
    public static final ResourceLocation APOCRYPHA_SUMMIT = id("apocrypha_summit");
    public static final ResourceLocation SOUL_CAIRN = id("soul_cairn");
    public static final ResourceLocation SOUL_CAIRN_KEEP = id("soul_cairn_keep");
    public static final ResourceLocation COLDHARBOUR = id("coldharbour");
    public static final ResourceLocation NIRN_GATE = id("nirn_gate");

    private static ResourceLocation id(String path) {
        return new ResourceLocation(Skycraft.MODID, "chests/realms/" + path);
    }
}
