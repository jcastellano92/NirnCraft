package com.skycraft.loot;

import com.skycraft.economy.ItemValues;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.core.registries.BuiltInRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Legendary unique weapons of Tamriel: one-of-a-kind named arms, each with its lore, a fixed enchantment, Legendary-ish
 * tempering and a value far above its material. They are defined here, centrally, and placed in the world by id:
 * <ul>
 *   <li>the explorers' sites put {@code skycraft:uniques/<id>} (a loot table) or {@link #create(String)} in a chest,
 *   weapon rack or on a corpse;</li>
 *   <li>a unique with a {@code place} is also handed out by {@code skycraft:place_collectibles} in that named
 *   dungeon's chests, and one with only a province turns up (rarely) in that province's boss chests
 *   ({@code skycraft:random_unique}).</li>
 * </ul>
 * Each character finds each unique once (with Lootr every player finds their own). Names and lore: lang keys
 * {@code unique.skycraft.<id>} and {@code unique.skycraft.<id>.lore}.
 */
public final class LegendaryUniques {
    public static final String COLLECTION = "legendary_uniques";

    private LegendaryUniques() {}

    /**
     * @param province gear key of the province it belongs to
     * @param place    named place ({@code places/*.json}) it waits in, or null (explorers' sites / random finds)
     */
    public record Unique(String id, String base, String province, @Nullable String place, int minLevel, int value,
                         int quality, Map<String, Integer> enchants) {}

    private static final Map<String, Unique> ALL = new LinkedHashMap<>();

    private static void def(String id, String base, String province, @Nullable String place, int minLevel, int value, int quality, Object... enchants) {
        Map<String, Integer> e = new LinkedHashMap<>();
        for (int i = 0; i < enchants.length; i += 2) e.put((String) enchants[i], (Integer) enchants[i + 1]);
        ALL.put(id, new Unique(id, base, province, place, minLevel, value, quality, e));
    }

    static {
        // Skyrim
        def("red_eagles_fury", "skycraft:ancient_nord_sword", "skyrim", "red_eagle_redoubt", 5, 450, 1, "minecraft:fire_aspect", 1);
        def("bloodthorn", "skycraft:ancient_nord_dagger", "skyrim", "hag_rock_redoubt", 6, 400, 1, "minecraft:sharpness", 1, "minecraft:looting", 1);
        def("bolars_oathblade", "skycraft:steel_greatsword", "skyrim", "valtheim_towers", 8, 600, 2, "minecraft:sharpness", 2);
        def("nettlebane", "skycraft:orcish_dagger", "skyrim", "dead_crone_rock", 10, 650, 2, "minecraft:bane_of_arthropods", 3, "minecraft:sharpness", 1);
        def("gauldur_blackblade", "skycraft:ancient_nord_greatsword", "skyrim", "geirmunds_hall", 12, 900, 2, "minecraft:smite", 3, "minecraft:sharpness", 1);
        def("ghostblade", "skycraft:nordic_greatsword", "skyrim", "ironbind_barrow", 18, 1100, 2, "minecraft:smite", 2, "minecraft:knockback", 1);
        def("the_pale_blade", "skycraft:ancient_nord_sword", "skyrim", "lost_valkygg", 18, 1200, 3, "minecraft:sharpness", 3, "minecraft:knockback", 1);
        def("kahvozeins_fang", "skycraft:dwarven_dagger", "skyrim", "skyborn_altar", 22, 1500, 3, "minecraft:sharpness", 3, "minecraft:looting", 2);
        // Cyrodiil
        def("welkynd_sliver", "skycraft:elven_dagger", "cyrodiil", null, 14, 900, 2, "minecraft:sharpness", 2, "minecraft:smite", 2);
        def("goldbrand", "skycraft:glass_sword", "cyrodiil", null, 30, 3500, 4, "minecraft:fire_aspect", 2, "minecraft:sharpness", 3);
        def("umbra", "skycraft:ebony_sword", "cyrodiil", null, 34, 4000, 4, "minecraft:sharpness", 4, "minecraft:looting", 2);
        // Morrowind and Solstheim
        def("hopesfire", "skycraft:silver_sword", "morrowind", null, 20, 1600, 3, "minecraft:smite", 3, "minecraft:fire_aspect", 1);
        def("trueflame", "skycraft:chitin_sword", "morrowind", null, 26, 2600, 4, "minecraft:fire_aspect", 2, "minecraft:sharpness", 2);
        def("keening", "skycraft:dwarven_dagger", "morrowind", null, 40, 5000, 5, "minecraft:sharpness", 5, "minecraft:looting", 3);
        def("sunder", "skycraft:dwarven_warhammer", "morrowind", null, 40, 5000, 5, "minecraft:sharpness", 4, "minecraft:knockback", 2);
        def("riekling_spear_of_thirsk", "skycraft:stalhrim_sword", "solstheim", null, 40, 3000, 3, "minecraft:sharpness", 3, "minecraft:knockback", 1);
        // Black Marsh
        def("hist_fang", "skycraft:argonian_dagger", "black_marsh", null, 16, 1100, 2, "minecraft:bane_of_arthropods", 2, "minecraft:sharpness", 2);
        def("shadowscale_kris", "skycraft:argonian_sword", "black_marsh", null, 24, 1900, 3, "minecraft:sharpness", 3, "minecraft:looting", 1);
        def("marsh_lords_mace", "skycraft:argonian_mace", "black_marsh", null, 30, 2400, 3, "minecraft:smite", 3, "minecraft:knockback", 2);
        // Elsweyr
        def("claw_of_alkosh", "skycraft:khajiit_dagger", "elsweyr", null, 16, 1200, 2, "minecraft:sharpness", 2, "minecraft:fire_aspect", 1);
        def("moonblade_of_the_mane", "skycraft:khajiit_sword", "elsweyr", null, 26, 2200, 3, "minecraft:sharpness", 3, "minecraft:smite", 2);
        def("rajhins_shadow", "skycraft:khajiit_bow", "elsweyr", null, 30, 2500, 3, "minecraft:power", 3, "minecraft:punch", 1);
        // Valenwood
        def("green_pact_fang", "skycraft:bosmer_dagger", "valenwood", null, 14, 1000, 2, "minecraft:sharpness", 2, "minecraft:looting", 1);
        def("jaqspur_longbow", "skycraft:bosmer_bow", "valenwood", null, 22, 1800, 3, "minecraft:power", 3, "minecraft:infinity", 1);
        def("wild_hunt_axe", "skycraft:bosmer_war_axe", "valenwood", null, 30, 2300, 3, "minecraft:sharpness", 3, "minecraft:fire_aspect", 1);
        // Summerset
        def("alinor_dawnblade", "skycraft:elven_sword", "summerset", null, 22, 1900, 3, "minecraft:smite", 3, "minecraft:sharpness", 2);
        def("psijic_longbow", "skycraft:glass_bow", "summerset", null, 30, 3000, 4, "minecraft:power", 4, "minecraft:flame", 1);
        def("sunbird_greatsword", "skycraft:elven_greatsword", "summerset", null, 34, 3400, 4, "minecraft:sharpness", 4, "minecraft:fire_aspect", 1);
        // Hammerfell
        def("sword_of_the_ansei", "skycraft:redguard_sword", "hammerfell", null, 18, 1500, 3, "minecraft:sharpness", 3);
        def("hews_bane_scimitar", "skycraft:redguard_greatsword", "hammerfell", null, 26, 2200, 3, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        def("shehai_blade", "skycraft:yokudan_sword", "hammerfell", null, 34, 3600, 4, "minecraft:sharpness", 4, "minecraft:smite", 2);
        // High Rock and Orsinium
        def("shornhelm_blade", "skycraft:breton_sword", "high_rock", null, 14, 1100, 2, "minecraft:sharpness", 2, "minecraft:smite", 1);
        def("direnni_warhammer", "skycraft:breton_warhammer", "high_rock", null, 24, 2000, 3, "minecraft:smite", 3, "minecraft:knockback", 2);
        def("chrysamere", "skycraft:breton_greatsword", "high_rock", null, 36, 4200, 5, "minecraft:sharpness", 4, "minecraft:fire_aspect", 1);
        def("gortwogs_hammer", "skycraft:orcish_warhammer", "orsinium", null, 20, 1700, 3, "minecraft:sharpness", 2, "minecraft:knockback", 2);
        def("malacaths_cleaver", "skycraft:orcish_battleaxe", "orsinium", null, 28, 2600, 3, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        // Akavir and Atmora
        def("dragonguard_katana", "skycraft:akaviri_sword", "akavir", null, 28, 3000, 4, "minecraft:sharpness", 4);
        def("atmoran_frostaxe", "skycraft:atmoran_war_axe", "atmora", null, 24, 2100, 3, "minecraft:sharpness", 3, "minecraft:knockback", 1);
        // Cyrodiil additions (V7/V9 parity)
        def("honorblade_of_chorrol", "skycraft:imperial_sword", "cyrodiil", "chorrol", 16, 1400, 3, "minecraft:sharpness", 3, "minecraft:unbreaking", 2);
        def("redwave_cutlass", "skycraft:imperial_sword", "cyrodiil", "anvil", 14, 1150, 2, "minecraft:sharpness", 2, "minecraft:looting", 2);
        def("chillrend_of_chorrol", "skycraft:glass_sword", "cyrodiil", "bruma", 22, 2200, 3, "minecraft:sharpness", 3, "minecraft:knockback", 1);
        def("debilitator_warhammer", "skycraft:dwarven_warhammer", "cyrodiil", "bravil", 20, 1850, 3, "minecraft:smite", 3, "minecraft:knockback", 2);
        def("thornblade_of_cheydinhal", "skycraft:elven_greatsword", "cyrodiil", "cheydinhal", 24, 2400, 3, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        def("shadowhunt_bow", "skycraft:ebony_bow", "cyrodiil", "skingrad", 26, 2600, 4, "minecraft:power", 3, "minecraft:punch", 1);
        def("dragonsword_of_lainlyn", "skycraft:imperial_greatsword", "cyrodiil", "imperial_city", 28, 3100, 4, "minecraft:fire_aspect", 2, "minecraft:sharpness", 3);
        def("bow_of_infliction", "skycraft:elven_bow", "cyrodiil", "leyawiin", 18, 1600, 3, "minecraft:power", 3, "minecraft:flame", 1);
        def("ackbaris_mace_of_zenithar", "skycraft:silver_mace", "cyrodiil", "kvatch", 22, 2100, 3, "minecraft:smite", 4, "minecraft:unbreaking", 2);
        // All-province legendary unique floor (8+ per province)
        def("daedric_crescent_of_tel_fyr", "skycraft:daedric_sword", "morrowind", null, 34, 4200, 5, "minecraft:sharpness", 4, "minecraft:sweeping", 3);
        def("veloths_judgment", "skycraft:ebony_warhammer", "morrowind", null, 30, 3400, 4, "minecraft:smite", 4, "minecraft:fire_aspect", 1);
        def("biplar_blade_of_vivec", "skycraft:elven_greatsword", "morrowind", null, 24, 2300, 3, "minecraft:sharpness", 3, "minecraft:unbreaking", 2);
        def("fang_of_haynekhtnamet", "skycraft:dragonbone_dagger", "morrowind", null, 28, 2900, 4, "minecraft:sharpness", 4, "minecraft:looting", 2);
        def("spear_of_bitter_mercy", "skycraft:ebony_spear", "morrowind", null, 32, 3600, 4, "minecraft:sharpness", 4, "minecraft:knockback", 2);
        def("bloodskal_blade_of_raven_rock", "skycraft:silver_greatsword", "solstheim", null, 26, 2500, 4, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        def("champion_cudgel_of_frostmoth", "skycraft:stalhrim_warhammer", "solstheim", null, 28, 2700, 4, "minecraft:fire_aspect", 2, "minecraft:sharpness", 3);
        def("hoarfrost_pick_of_kolbjorn", "skycraft:stalhrim_war_axe", "solstheim", null, 24, 2100, 3, "minecraft:sharpness", 3, "minecraft:unbreaking", 3);
        def("gyldenhul_deathbrand_scimitar", "skycraft:stalhrim_sword", "solstheim", null, 32, 3400, 4, "minecraft:sharpness", 4, "minecraft:looting", 2);
        def("haknirs_bloodscythe", "skycraft:stalhrim_dagger", "solstheim", null, 30, 3200, 4, "minecraft:sharpness", 4, "minecraft:smite", 2);
        def("skaal_windcaller_axe", "skycraft:nordic_battleaxe", "solstheim", null, 22, 1900, 3, "minecraft:sharpness", 3, "minecraft:knockback", 1);
        def("miraaks_first_fang", "skycraft:daedric_sword", "solstheim", null, 36, 4400, 5, "minecraft:sharpness", 4, "minecraft:looting", 3);
        def("xanmeer_sun_cleaver", "skycraft:argonian_battleaxe", "black_marsh", null, 22, 1800, 3, "minecraft:sharpness", 3, "minecraft:bane_of_arthropods", 3);
        def("sithis_whisper_dagger", "skycraft:argonian_dagger", "black_marsh", null, 28, 2600, 4, "minecraft:sharpness", 4, "minecraft:looting", 2);
        def("root_warden_longbow", "skycraft:argonian_bow", "black_marsh", null, 20, 1650, 3, "minecraft:power", 3, "minecraft:punch", 1);
        def("blackrose_warden_spear", "skycraft:argonian_spear", "black_marsh", null, 26, 2300, 3, "minecraft:sharpness", 3, "minecraft:knockback", 2);
        def("helstrom_amber_staff", "skycraft:argonian_mace", "black_marsh", null, 32, 3100, 4, "minecraft:smite", 4, "minecraft:fire_aspect", 1);
        def("anequina_sun_scimitar", "skycraft:khajiit_sword", "elsweyr", null, 20, 1750, 3, "minecraft:sharpness", 3, "minecraft:fire_aspect", 1);
        def("rimmen_palace_glaive", "skycraft:khajiit_greatsword", "elsweyr", null, 28, 2650, 4, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        def("senchal_corsair_sabre", "skycraft:khajiit_sword", "elsweyr", null, 24, 2150, 3, "minecraft:sharpness", 3, "minecraft:looting", 2);
        def("two_moons_ritual_kris", "skycraft:khajiit_dagger", "elsweyr", null, 32, 3100, 4, "minecraft:smite", 4, "minecraft:sharpness", 3);
        def("tenmar_stalkers_bow", "skycraft:khajiit_bow", "elsweyr", null, 26, 2400, 3, "minecraft:power", 4, "minecraft:flame", 1);
        def("falinesti_bough_bow", "skycraft:bosmer_bow", "valenwood", null, 26, 2450, 4, "minecraft:power", 4, "minecraft:punch", 2);
        def("camoran_stag_blade", "skycraft:bosmer_sword", "valenwood", null, 24, 2100, 3, "minecraft:sharpness", 3, "minecraft:looting", 2);
        def("silvenar_thorn_spear", "skycraft:bosmer_spear", "valenwood", null, 28, 2700, 4, "minecraft:sharpness", 4, "minecraft:knockback", 1);
        def("green_lady_hart_dagger", "skycraft:bosmer_dagger", "valenwood", null, 32, 3200, 4, "minecraft:sharpness", 4, "minecraft:bane_of_arthropods", 3);
        def("haven_reef_harpoon", "skycraft:bosmer_war_axe", "valenwood", null, 20, 1700, 3, "minecraft:sharpness", 3, "minecraft:unbreaking", 2);
        def("crystal_tower_sceptre", "skycraft:glass_mace", "summerset", null, 28, 2850, 4, "minecraft:smite", 4, "minecraft:knockback", 2);
        def("cloudrest_welkynd_glaive", "skycraft:elven_greatsword", "summerset", null, 26, 2550, 4, "minecraft:sharpness", 3, "minecraft:fire_aspect", 2);
        def("firsthold_kinlord_rapier", "skycraft:elven_sword", "summerset", null, 20, 1800, 3, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        def("artaeum_dreaming_dagger", "skycraft:glass_dagger", "summerset", null, 32, 3300, 4, "minecraft:sharpness", 4, "minecraft:looting", 2);
        def("shimmerene_starlight_bow", "skycraft:elven_bow", "summerset", null, 24, 2250, 3, "minecraft:power", 3, "minecraft:infinity", 1);
        def("stros_mkai_admiral_sabre", "skycraft:redguard_sword", "hammerfell", null, 20, 1850, 3, "minecraft:sharpness", 3, "minecraft:looting", 2);
        def("alikr_dune_cleaver", "skycraft:redguard_battleaxe", "hammerfell", null, 24, 2200, 3, "minecraft:sharpness", 3, "minecraft:fire_aspect", 1);
        def("diagna_orichalc_mace", "skycraft:orcish_mace", "hammerfell", null, 28, 2750, 4, "minecraft:smite", 4, "minecraft:knockback", 2);
        def("dragonstar_arena_greatsword", "skycraft:redguard_greatsword", "hammerfell", null, 30, 3000, 4, "minecraft:sharpness", 4, "minecraft:sweeping", 2);
        def("tuwhacca_crypt_dagger", "skycraft:yokudan_dagger", "hammerfell", null, 22, 1950, 3, "minecraft:smite", 3, "minecraft:looting", 2);
        def("camlorn_wolf_greatsword", "skycraft:breton_greatsword", "high_rock", null, 22, 2050, 3, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        def("wayrest_corsair_rapier", "skycraft:breton_sword", "high_rock", null, 20, 1800, 3, "minecraft:sharpness", 3, "minecraft:looting", 2);
        def("adamantine_ward_mace", "skycraft:dwarven_mace", "high_rock", null, 28, 2900, 4, "minecraft:smite", 4, "minecraft:unbreaking", 3);
        def("rivenspire_blood_dagger", "skycraft:breton_dagger", "high_rock", null, 26, 2450, 3, "minecraft:sharpness", 3, "minecraft:fire_aspect", 1);
        def("evermor_border_longbow", "skycraft:breton_bow", "high_rock", null, 18, 1650, 3, "minecraft:power", 3, "minecraft:punch", 1);
        def("wrothgar_king_greataxe", "skycraft:orcish_battleaxe", "orsinium", null, 24, 2200, 3, "minecraft:sharpness", 3, "minecraft:knockback", 2);
        def("fharun_forge_maul", "skycraft:orcish_warhammer", "orsinium", null, 26, 2500, 4, "minecraft:sharpness", 4, "minecraft:unbreaking", 2);
        def("trinimac_vow_greatsword", "skycraft:orcish_greatsword", "orsinium", null, 30, 3100, 4, "minecraft:smite", 3, "minecraft:sharpness", 3);
        def("morkul_blood_cleaver", "skycraft:orcish_war_axe", "orsinium", null, 22, 1900, 3, "minecraft:sharpness", 3, "minecraft:looting", 1);
        def("pariah_peak_spear", "skycraft:orcish_spear", "orsinium", null, 28, 2700, 4, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        def("ora_stone_longbow", "skycraft:orcish_bow", "orsinium", null, 20, 1750, 3, "minecraft:power", 3, "minecraft:flame", 1);
        def("tsaesci_serpent_wakizashi", "skycraft:akaviri_dagger", "akavir", null, 22, 2100, 3, "minecraft:sharpness", 3, "minecraft:looting", 2);
        def("kamal_thaw_breaker", "skycraft:akaviri_warhammer", "akavir", null, 26, 2600, 4, "minecraft:sharpness", 3, "minecraft:knockback", 2);
        def("tang_mo_jade_staff", "skycraft:akaviri_spear", "akavir", null, 24, 2350, 3, "minecraft:smite", 3, "minecraft:sweeping", 2);
        def("ka_po_tun_tiger_fang", "skycraft:akaviri_greatsword", "akavir", null, 32, 3500, 4, "minecraft:fire_aspect", 2, "minecraft:sharpness", 4);
        def("cloud_pagoda_longbow", "skycraft:akaviri_bow", "akavir", null, 24, 2250, 3, "minecraft:power", 3, "minecraft:flame", 1);
        def("sun_scale_nodachi", "skycraft:akaviri_greatsword", "akavir", null, 30, 3200, 4, "minecraft:sharpness", 4, "minecraft:sweeping", 2);
        def("crimson_dirk_of_volatility", "skycraft:akaviri_dagger", "akavir", null, 20, 1850, 3, "minecraft:sharpness", 3, "minecraft:unbreaking", 2);
        def("jehanna_harbor_halberd", "skycraft:atmoran_battleaxe", "atmora", null, 22, 2000, 3, "minecraft:sharpness", 3, "minecraft:knockback", 1);
        def("ysgramors_first_oar_spear", "skycraft:atmoran_spear", "atmora", null, 26, 2500, 4, "minecraft:smite", 3, "minecraft:sharpness", 3);
        def("jylkurfyk_shipwright_axe", "skycraft:atmoran_war_axe", "atmora", null, 20, 1850, 3, "minecraft:sharpness", 3, "minecraft:unbreaking", 2);
        def("brodir_grove_greatsword", "skycraft:atmoran_greatsword", "atmora", null, 28, 2800, 4, "minecraft:sharpness", 4, "minecraft:sweeping", 2);
        def("dram_totem_skull_maul", "skycraft:atmoran_warhammer", "atmora", null, 30, 3100, 4, "minecraft:smite", 4, "minecraft:knockback", 2);
        def("pale_wastes_hunting_bow", "skycraft:atmoran_bow", "atmora", null, 22, 1950, 3, "minecraft:power", 3, "minecraft:punch", 1);
        def("whalebone_seax_of_saar", "skycraft:atmoran_dagger", "atmora", null, 18, 1650, 3, "minecraft:sharpness", 3, "minecraft:looting", 2);
        def("na_totambu_crown_blade", "skycraft:yokudan_sword", "yokuda", null, 26, 2600, 4, "minecraft:sharpness", 4, "minecraft:smite", 2);
        def("orichalc_tower_cleaver", "skycraft:yokudan_greatsword", "yokuda", null, 30, 3100, 4, "minecraft:sharpness", 4, "minecraft:sweeping", 2);
        def("hiradirge_stone_maul", "skycraft:yokudan_warhammer", "yokuda", null, 28, 2850, 4, "minecraft:sharpness", 3, "minecraft:knockback", 2);
        def("akaviri_reef_scimitar", "skycraft:yokudan_sword", "yokuda", null, 22, 2050, 3, "minecraft:sharpness", 3, "minecraft:looting", 2);
        def("sunken_ansei_kris", "skycraft:yokudan_dagger", "yokuda", null, 24, 2250, 3, "minecraft:smite", 3, "minecraft:sharpness", 2);
        def("tava_wind_longbow", "skycraft:yokudan_bow", "yokuda", null, 20, 1900, 3, "minecraft:power", 3, "minecraft:flame", 1);
        def("zeht_harvest_halberd", "skycraft:yokudan_battleaxe", "yokuda", null, 24, 2300, 3, "minecraft:sharpness", 3, "minecraft:unbreaking", 2);
        def("morwha_oasis_spear", "skycraft:yokudan_spear", "yokuda", null, 22, 2000, 3, "minecraft:sharpness", 3, "minecraft:knockback", 1);
        def("orgnums_coffer_trident", "skycraft:maormer_spear", "pyandonea", null, 28, 2900, 4, "minecraft:sharpness", 4, "minecraft:looting", 2);
        def("abbarbas_mist_cutlass", "skycraft:maormer_sword", "pyandonea", null, 24, 2300, 3, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        def("sea_viper_fang_dagger", "skycraft:maormer_dagger", "pyandonea", null, 20, 1850, 3, "minecraft:bane_of_arthropods", 3, "minecraft:sharpness", 2);
        def("leviathan_rib_longbow", "skycraft:maormer_bow", "pyandonea", null, 22, 2100, 3, "minecraft:power", 3, "minecraft:punch", 2);
        def("pyandonean_storm_glaive", "skycraft:maormer_greatsword", "pyandonea", null, 30, 3150, 4, "minecraft:sharpness", 4, "minecraft:fire_aspect", 1);
        def("serpent_scale_boarding_axe", "skycraft:maormer_war_axe", "pyandonea", null, 22, 1950, 3, "minecraft:sharpness", 3, "minecraft:unbreaking", 2);
        def("tigers_reef_warhammer", "skycraft:maormer_warhammer", "pyandonea", null, 26, 2500, 3, "minecraft:smite", 3, "minecraft:knockback", 2);
        def("pellucid_pearl_sabre", "skycraft:maormer_sword", "pyandonea", null, 28, 2750, 4, "minecraft:sharpness", 3, "minecraft:looting", 2);
        def("coral_tower_flensing_knife", "skycraft:daedric_dagger", "thras", null, 24, 2350, 3, "minecraft:smite", 3, "minecraft:looting", 2);
        def("agamir_sload_bone_mace", "skycraft:daedric_mace", "thras", null, 28, 2800, 4, "minecraft:smite", 4, "minecraft:knockback", 2);
        def("korsharg_lagoon_trident", "skycraft:daedric_spear", "thras", null, 26, 2600, 4, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        def("all_flags_crusader_relic", "skycraft:silver_greatsword", "thras", null, 30, 3200, 4, "minecraft:smite", 5, "minecraft:unbreaking", 3);
        def("abyssal_polyp_cleaver", "skycraft:daedric_battleaxe", "thras", null, 32, 3400, 4, "minecraft:sharpness", 4, "minecraft:fire_aspect", 1);
        def("soap_vat_necrotic_staff", "skycraft:daedric_warhammer", "thras", null, 26, 2550, 3, "minecraft:smite", 3, "minecraft:sharpness", 2);
        def("sunken_galleon_boarding_sword", "skycraft:silver_sword", "thras", null, 22, 2000, 3, "minecraft:sharpness", 3, "minecraft:looting", 2);
        def("reef_spire_barnacle_bow", "skycraft:daedric_bow", "thras", null, 24, 2250, 3, "minecraft:power", 3, "minecraft:flame", 1);
    }

    public static List<Unique> all() {
        return Collections.unmodifiableList(new ArrayList<>(ALL.values()));
    }

    @Nullable
    public static Unique get(String id) {
        return ALL.get(id);
    }

    /** The unique as an item (empty for an unknown id or a missing base item). */
    public static ItemStack create(String id) {
        Unique u = ALL.get(id);
        if (u == null) return ItemStack.EMPTY;
        Item base = BuiltInRegistries.ITEM.getValue(Identifier.parse(u.base));
        if (base == null || base == Items.AIR) return ItemStack.EMPTY;
        ItemStack s = new ItemStack(base);
        for (Map.Entry<String, Integer> e : u.enchants.entrySet()) {
            Enchantment ench = com.skycraft.platform.EnchantmentsAccess.lookup(Identifier.parse(e.getKey()));
            if (ench != null) s.enchant(com.skycraft.platform.EnchantmentsAccess.holder(ench), e.getValue());
        }
        s.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.translatable("unique.skycraft." + id).withStyle(ChatFormatting.GOLD).withStyle(s2 -> s2.withItalic(false)));
        s.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(
                java.util.List.of(Component.translatable("unique.skycraft." + id + ".lore")
                        .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC))));
        CompoundTag tag = com.skycraft.inventory.ItemData.customOrEmpty(s);
        tag.putString(Collectibles.NBT, COLLECTION + "/" + id);
        tag.putInt(ItemValues.VALUE_NBT, u.value);
        if (u.quality > 0) tag.putInt(ItemValues.QUALITY_NBT, u.quality);
        com.skycraft.inventory.ItemData.setCustom(s, tag);
        return s;
    }

    /**
     * Verifies that every registered {@link Unique} resolves to a non-empty {@link ItemStack} whose base item is
     * registered in {@link ForgeRegistries#ITEMS}. Throws {@link IllegalStateException} if any unique fails.
     */
    public static int verifyAllNonEmpty() {
        List<String> broken = new ArrayList<>();
        for (Unique u : ALL.values()) {
            ItemStack stack = create(u.id);
            if (stack.isEmpty()) {
                broken.add(u.id + " (" + u.base + ")");
            }
        }
        if (!broken.isEmpty()) {
            throw new IllegalStateException("LegendaryUniques has " + broken.size() + " empty unique(s): " + String.join(", ", broken));
        }
        return ALL.size();
    }
}
