package com.nirncraft.loot;

import com.nirncraft.economy.ItemValues;
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
 *   <li>the explorers' sites put {@code nirncraft:uniques/<id>} (a loot table) or {@link #create(String)} in a chest,
 *   weapon rack or on a corpse;</li>
 *   <li>a unique with a {@code place} is also handed out by {@code nirncraft:place_collectibles} in that named
 *   dungeon's chests, and one with only a province turns up (rarely) in that province's boss chests
 *   ({@code nirncraft:random_unique}).</li>
 * </ul>
 * Each character finds each unique once (with Lootr every player finds their own). Names and lore: lang keys
 * {@code unique.nirncraft.<id>} and {@code unique.nirncraft.<id>.lore}.
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
        def("red_eagles_fury", "nirncraft:ancient_nord_sword", "skyrim", "red_eagle_redoubt", 5, 450, 1, "minecraft:fire_aspect", 1);
        def("bloodthorn", "nirncraft:ancient_nord_dagger", "skyrim", "hag_rock_redoubt", 6, 400, 1, "minecraft:sharpness", 1, "minecraft:looting", 1);
        def("bolars_oathblade", "nirncraft:steel_greatsword", "skyrim", "valtheim_towers", 8, 600, 2, "minecraft:sharpness", 2);
        def("nettlebane", "nirncraft:orcish_dagger", "skyrim", "dead_crone_rock", 10, 650, 2, "minecraft:bane_of_arthropods", 3, "minecraft:sharpness", 1);
        def("gauldur_blackblade", "nirncraft:ancient_nord_greatsword", "skyrim", "geirmunds_hall", 12, 900, 2, "minecraft:smite", 3, "minecraft:sharpness", 1);
        def("ghostblade", "nirncraft:nordic_greatsword", "skyrim", "ironbind_barrow", 18, 1100, 2, "minecraft:smite", 2, "minecraft:knockback", 1);
        def("the_pale_blade", "nirncraft:ancient_nord_sword", "skyrim", "lost_valkygg", 18, 1200, 3, "minecraft:sharpness", 3, "minecraft:knockback", 1);
        def("kahvozeins_fang", "nirncraft:dwarven_dagger", "skyrim", "skyborn_altar", 22, 1500, 3, "minecraft:sharpness", 3, "minecraft:looting", 2);
        // Cyrodiil
        def("welkynd_sliver", "nirncraft:elven_dagger", "cyrodiil", null, 14, 900, 2, "minecraft:sharpness", 2, "minecraft:smite", 2);
        def("goldbrand", "nirncraft:glass_sword", "cyrodiil", null, 30, 3500, 4, "minecraft:fire_aspect", 2, "minecraft:sharpness", 3);
        def("umbra", "nirncraft:ebony_sword", "cyrodiil", null, 34, 4000, 4, "minecraft:sharpness", 4, "minecraft:looting", 2);
        // Morrowind and Solstheim
        def("hopesfire", "nirncraft:silver_sword", "morrowind", null, 20, 1600, 3, "minecraft:smite", 3, "minecraft:fire_aspect", 1);
        def("trueflame", "nirncraft:chitin_sword", "morrowind", null, 26, 2600, 4, "minecraft:fire_aspect", 2, "minecraft:sharpness", 2);
        def("keening", "nirncraft:dwarven_dagger", "morrowind", null, 40, 5000, 5, "minecraft:sharpness", 5, "minecraft:looting", 3);
        def("sunder", "nirncraft:dwarven_warhammer", "morrowind", null, 40, 5000, 5, "minecraft:sharpness", 4, "minecraft:knockback", 2);
        def("riekling_spear_of_thirsk", "nirncraft:stalhrim_sword", "solstheim", null, 40, 3000, 3, "minecraft:sharpness", 3, "minecraft:knockback", 1);
        // Black Marsh
        def("hist_fang", "nirncraft:argonian_dagger", "black_marsh", null, 16, 1100, 2, "minecraft:bane_of_arthropods", 2, "minecraft:sharpness", 2);
        def("shadowscale_kris", "nirncraft:argonian_sword", "black_marsh", null, 24, 1900, 3, "minecraft:sharpness", 3, "minecraft:looting", 1);
        def("marsh_lords_mace", "nirncraft:argonian_mace", "black_marsh", null, 30, 2400, 3, "minecraft:smite", 3, "minecraft:knockback", 2);
        // Elsweyr
        def("claw_of_alkosh", "nirncraft:khajiit_dagger", "elsweyr", null, 16, 1200, 2, "minecraft:sharpness", 2, "minecraft:fire_aspect", 1);
        def("moonblade_of_the_mane", "nirncraft:khajiit_sword", "elsweyr", null, 26, 2200, 3, "minecraft:sharpness", 3, "minecraft:smite", 2);
        def("rajhins_shadow", "nirncraft:khajiit_bow", "elsweyr", null, 30, 2500, 3, "minecraft:power", 3, "minecraft:punch", 1);
        // Valenwood
        def("green_pact_fang", "nirncraft:bosmer_dagger", "valenwood", null, 14, 1000, 2, "minecraft:sharpness", 2, "minecraft:looting", 1);
        def("jaqspur_longbow", "nirncraft:bosmer_bow", "valenwood", null, 22, 1800, 3, "minecraft:power", 3, "minecraft:infinity", 1);
        def("wild_hunt_axe", "nirncraft:bosmer_war_axe", "valenwood", null, 30, 2300, 3, "minecraft:sharpness", 3, "minecraft:fire_aspect", 1);
        // Summerset
        def("alinor_dawnblade", "nirncraft:elven_sword", "summerset", null, 22, 1900, 3, "minecraft:smite", 3, "minecraft:sharpness", 2);
        def("psijic_longbow", "nirncraft:glass_bow", "summerset", null, 30, 3000, 4, "minecraft:power", 4, "minecraft:flame", 1);
        def("sunbird_greatsword", "nirncraft:elven_greatsword", "summerset", null, 34, 3400, 4, "minecraft:sharpness", 4, "minecraft:fire_aspect", 1);
        // Hammerfell
        def("sword_of_the_ansei", "nirncraft:redguard_sword", "hammerfell", null, 18, 1500, 3, "minecraft:sharpness", 3);
        def("hews_bane_scimitar", "nirncraft:redguard_greatsword", "hammerfell", null, 26, 2200, 3, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        def("shehai_blade", "nirncraft:yokudan_sword", "hammerfell", null, 34, 3600, 4, "minecraft:sharpness", 4, "minecraft:smite", 2);
        // High Rock and Orsinium
        def("shornhelm_blade", "nirncraft:breton_sword", "high_rock", null, 14, 1100, 2, "minecraft:sharpness", 2, "minecraft:smite", 1);
        def("direnni_warhammer", "nirncraft:breton_warhammer", "high_rock", null, 24, 2000, 3, "minecraft:smite", 3, "minecraft:knockback", 2);
        def("chrysamere", "nirncraft:breton_greatsword", "high_rock", null, 36, 4200, 5, "minecraft:sharpness", 4, "minecraft:fire_aspect", 1);
        def("gortwogs_hammer", "nirncraft:orcish_warhammer", "orsinium", null, 20, 1700, 3, "minecraft:sharpness", 2, "minecraft:knockback", 2);
        def("malacaths_cleaver", "nirncraft:orcish_battleaxe", "orsinium", null, 28, 2600, 3, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        // Akavir and Atmora
        def("dragonguard_katana", "nirncraft:akaviri_sword", "akavir", null, 28, 3000, 4, "minecraft:sharpness", 4);
        def("atmoran_frostaxe", "nirncraft:atmoran_war_axe", "atmora", null, 24, 2100, 3, "minecraft:sharpness", 3, "minecraft:knockback", 1);
        // Cyrodiil additions (V7/V9 parity)
        def("honorblade_of_chorrol", "nirncraft:imperial_sword", "cyrodiil", "chorrol", 16, 1400, 3, "minecraft:sharpness", 3, "minecraft:unbreaking", 2);
        def("redwave_cutlass", "nirncraft:imperial_sword", "cyrodiil", "anvil", 14, 1150, 2, "minecraft:sharpness", 2, "minecraft:looting", 2);
        def("chillrend_of_chorrol", "nirncraft:glass_sword", "cyrodiil", "bruma", 22, 2200, 3, "minecraft:sharpness", 3, "minecraft:knockback", 1);
        def("debilitator_warhammer", "nirncraft:dwarven_warhammer", "cyrodiil", "bravil", 20, 1850, 3, "minecraft:smite", 3, "minecraft:knockback", 2);
        def("thornblade_of_cheydinhal", "nirncraft:elven_greatsword", "cyrodiil", "cheydinhal", 24, 2400, 3, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        def("shadowhunt_bow", "nirncraft:ebony_bow", "cyrodiil", "skingrad", 26, 2600, 4, "minecraft:power", 3, "minecraft:punch", 1);
        def("dragonsword_of_lainlyn", "nirncraft:imperial_greatsword", "cyrodiil", "imperial_city", 28, 3100, 4, "minecraft:fire_aspect", 2, "minecraft:sharpness", 3);
        def("bow_of_infliction", "nirncraft:elven_bow", "cyrodiil", "leyawiin", 18, 1600, 3, "minecraft:power", 3, "minecraft:flame", 1);
        def("ackbaris_mace_of_zenithar", "nirncraft:silver_mace", "cyrodiil", "kvatch", 22, 2100, 3, "minecraft:smite", 4, "minecraft:unbreaking", 2);
        // All-province legendary unique floor (8+ per province)
        def("daedric_crescent_of_tel_fyr", "nirncraft:daedric_sword", "morrowind", null, 34, 4200, 5, "minecraft:sharpness", 4, "minecraft:sweeping", 3);
        def("veloths_judgment", "nirncraft:ebony_warhammer", "morrowind", null, 30, 3400, 4, "minecraft:smite", 4, "minecraft:fire_aspect", 1);
        def("biplar_blade_of_vivec", "nirncraft:elven_greatsword", "morrowind", null, 24, 2300, 3, "minecraft:sharpness", 3, "minecraft:unbreaking", 2);
        def("fang_of_haynekhtnamet", "nirncraft:dragonbone_dagger", "morrowind", null, 28, 2900, 4, "minecraft:sharpness", 4, "minecraft:looting", 2);
        def("spear_of_bitter_mercy", "nirncraft:ebony_spear", "morrowind", null, 32, 3600, 4, "minecraft:sharpness", 4, "minecraft:knockback", 2);
        def("bloodskal_blade_of_raven_rock", "nirncraft:silver_greatsword", "solstheim", null, 26, 2500, 4, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        def("champion_cudgel_of_frostmoth", "nirncraft:stalhrim_warhammer", "solstheim", null, 28, 2700, 4, "minecraft:fire_aspect", 2, "minecraft:sharpness", 3);
        def("hoarfrost_pick_of_kolbjorn", "nirncraft:stalhrim_war_axe", "solstheim", null, 24, 2100, 3, "minecraft:sharpness", 3, "minecraft:unbreaking", 3);
        def("gyldenhul_deathbrand_scimitar", "nirncraft:stalhrim_sword", "solstheim", null, 32, 3400, 4, "minecraft:sharpness", 4, "minecraft:looting", 2);
        def("haknirs_bloodscythe", "nirncraft:stalhrim_dagger", "solstheim", null, 30, 3200, 4, "minecraft:sharpness", 4, "minecraft:smite", 2);
        def("skaal_windcaller_axe", "nirncraft:nordic_battleaxe", "solstheim", null, 22, 1900, 3, "minecraft:sharpness", 3, "minecraft:knockback", 1);
        def("miraaks_first_fang", "nirncraft:daedric_sword", "solstheim", null, 36, 4400, 5, "minecraft:sharpness", 4, "minecraft:looting", 3);
        def("xanmeer_sun_cleaver", "nirncraft:argonian_battleaxe", "black_marsh", null, 22, 1800, 3, "minecraft:sharpness", 3, "minecraft:bane_of_arthropods", 3);
        def("sithis_whisper_dagger", "nirncraft:argonian_dagger", "black_marsh", null, 28, 2600, 4, "minecraft:sharpness", 4, "minecraft:looting", 2);
        def("root_warden_longbow", "nirncraft:argonian_bow", "black_marsh", null, 20, 1650, 3, "minecraft:power", 3, "minecraft:punch", 1);
        def("blackrose_warden_spear", "nirncraft:argonian_spear", "black_marsh", null, 26, 2300, 3, "minecraft:sharpness", 3, "minecraft:knockback", 2);
        def("helstrom_amber_staff", "nirncraft:argonian_mace", "black_marsh", null, 32, 3100, 4, "minecraft:smite", 4, "minecraft:fire_aspect", 1);
        def("anequina_sun_scimitar", "nirncraft:khajiit_sword", "elsweyr", null, 20, 1750, 3, "minecraft:sharpness", 3, "minecraft:fire_aspect", 1);
        def("rimmen_palace_glaive", "nirncraft:khajiit_greatsword", "elsweyr", null, 28, 2650, 4, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        def("senchal_corsair_sabre", "nirncraft:khajiit_sword", "elsweyr", null, 24, 2150, 3, "minecraft:sharpness", 3, "minecraft:looting", 2);
        def("two_moons_ritual_kris", "nirncraft:khajiit_dagger", "elsweyr", null, 32, 3100, 4, "minecraft:smite", 4, "minecraft:sharpness", 3);
        def("tenmar_stalkers_bow", "nirncraft:khajiit_bow", "elsweyr", null, 26, 2400, 3, "minecraft:power", 4, "minecraft:flame", 1);
        def("falinesti_bough_bow", "nirncraft:bosmer_bow", "valenwood", null, 26, 2450, 4, "minecraft:power", 4, "minecraft:punch", 2);
        def("camoran_stag_blade", "nirncraft:bosmer_sword", "valenwood", null, 24, 2100, 3, "minecraft:sharpness", 3, "minecraft:looting", 2);
        def("silvenar_thorn_spear", "nirncraft:bosmer_spear", "valenwood", null, 28, 2700, 4, "minecraft:sharpness", 4, "minecraft:knockback", 1);
        def("green_lady_hart_dagger", "nirncraft:bosmer_dagger", "valenwood", null, 32, 3200, 4, "minecraft:sharpness", 4, "minecraft:bane_of_arthropods", 3);
        def("haven_reef_harpoon", "nirncraft:bosmer_war_axe", "valenwood", null, 20, 1700, 3, "minecraft:sharpness", 3, "minecraft:unbreaking", 2);
        def("crystal_tower_sceptre", "nirncraft:glass_mace", "summerset", null, 28, 2850, 4, "minecraft:smite", 4, "minecraft:knockback", 2);
        def("cloudrest_welkynd_glaive", "nirncraft:elven_greatsword", "summerset", null, 26, 2550, 4, "minecraft:sharpness", 3, "minecraft:fire_aspect", 2);
        def("firsthold_kinlord_rapier", "nirncraft:elven_sword", "summerset", null, 20, 1800, 3, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        def("artaeum_dreaming_dagger", "nirncraft:glass_dagger", "summerset", null, 32, 3300, 4, "minecraft:sharpness", 4, "minecraft:looting", 2);
        def("shimmerene_starlight_bow", "nirncraft:elven_bow", "summerset", null, 24, 2250, 3, "minecraft:power", 3, "minecraft:infinity", 1);
        def("stros_mkai_admiral_sabre", "nirncraft:redguard_sword", "hammerfell", null, 20, 1850, 3, "minecraft:sharpness", 3, "minecraft:looting", 2);
        def("alikr_dune_cleaver", "nirncraft:redguard_battleaxe", "hammerfell", null, 24, 2200, 3, "minecraft:sharpness", 3, "minecraft:fire_aspect", 1);
        def("diagna_orichalc_mace", "nirncraft:orcish_mace", "hammerfell", null, 28, 2750, 4, "minecraft:smite", 4, "minecraft:knockback", 2);
        def("dragonstar_arena_greatsword", "nirncraft:redguard_greatsword", "hammerfell", null, 30, 3000, 4, "minecraft:sharpness", 4, "minecraft:sweeping", 2);
        def("tuwhacca_crypt_dagger", "nirncraft:yokudan_dagger", "hammerfell", null, 22, 1950, 3, "minecraft:smite", 3, "minecraft:looting", 2);
        def("camlorn_wolf_greatsword", "nirncraft:breton_greatsword", "high_rock", null, 22, 2050, 3, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        def("wayrest_corsair_rapier", "nirncraft:breton_sword", "high_rock", null, 20, 1800, 3, "minecraft:sharpness", 3, "minecraft:looting", 2);
        def("adamantine_ward_mace", "nirncraft:dwarven_mace", "high_rock", null, 28, 2900, 4, "minecraft:smite", 4, "minecraft:unbreaking", 3);
        def("rivenspire_blood_dagger", "nirncraft:breton_dagger", "high_rock", null, 26, 2450, 3, "minecraft:sharpness", 3, "minecraft:fire_aspect", 1);
        def("evermor_border_longbow", "nirncraft:breton_bow", "high_rock", null, 18, 1650, 3, "minecraft:power", 3, "minecraft:punch", 1);
        def("wrothgar_king_greataxe", "nirncraft:orcish_battleaxe", "orsinium", null, 24, 2200, 3, "minecraft:sharpness", 3, "minecraft:knockback", 2);
        def("fharun_forge_maul", "nirncraft:orcish_warhammer", "orsinium", null, 26, 2500, 4, "minecraft:sharpness", 4, "minecraft:unbreaking", 2);
        def("trinimac_vow_greatsword", "nirncraft:orcish_greatsword", "orsinium", null, 30, 3100, 4, "minecraft:smite", 3, "minecraft:sharpness", 3);
        def("morkul_blood_cleaver", "nirncraft:orcish_war_axe", "orsinium", null, 22, 1900, 3, "minecraft:sharpness", 3, "minecraft:looting", 1);
        def("pariah_peak_spear", "nirncraft:orcish_spear", "orsinium", null, 28, 2700, 4, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        def("ora_stone_longbow", "nirncraft:orcish_bow", "orsinium", null, 20, 1750, 3, "minecraft:power", 3, "minecraft:flame", 1);
        def("tsaesci_serpent_wakizashi", "nirncraft:akaviri_dagger", "akavir", null, 22, 2100, 3, "minecraft:sharpness", 3, "minecraft:looting", 2);
        def("kamal_thaw_breaker", "nirncraft:akaviri_warhammer", "akavir", null, 26, 2600, 4, "minecraft:sharpness", 3, "minecraft:knockback", 2);
        def("tang_mo_jade_staff", "nirncraft:akaviri_spear", "akavir", null, 24, 2350, 3, "minecraft:smite", 3, "minecraft:sweeping", 2);
        def("ka_po_tun_tiger_fang", "nirncraft:akaviri_greatsword", "akavir", null, 32, 3500, 4, "minecraft:fire_aspect", 2, "minecraft:sharpness", 4);
        def("cloud_pagoda_longbow", "nirncraft:akaviri_bow", "akavir", null, 24, 2250, 3, "minecraft:power", 3, "minecraft:flame", 1);
        def("sun_scale_nodachi", "nirncraft:akaviri_greatsword", "akavir", null, 30, 3200, 4, "minecraft:sharpness", 4, "minecraft:sweeping", 2);
        def("crimson_dirk_of_volatility", "nirncraft:akaviri_dagger", "akavir", null, 20, 1850, 3, "minecraft:sharpness", 3, "minecraft:unbreaking", 2);
        def("jehanna_harbor_halberd", "nirncraft:atmoran_battleaxe", "atmora", null, 22, 2000, 3, "minecraft:sharpness", 3, "minecraft:knockback", 1);
        def("ysgramors_first_oar_spear", "nirncraft:atmoran_spear", "atmora", null, 26, 2500, 4, "minecraft:smite", 3, "minecraft:sharpness", 3);
        def("jylkurfyk_shipwright_axe", "nirncraft:atmoran_war_axe", "atmora", null, 20, 1850, 3, "minecraft:sharpness", 3, "minecraft:unbreaking", 2);
        def("brodir_grove_greatsword", "nirncraft:atmoran_greatsword", "atmora", null, 28, 2800, 4, "minecraft:sharpness", 4, "minecraft:sweeping", 2);
        def("dram_totem_skull_maul", "nirncraft:atmoran_warhammer", "atmora", null, 30, 3100, 4, "minecraft:smite", 4, "minecraft:knockback", 2);
        def("pale_wastes_hunting_bow", "nirncraft:atmoran_bow", "atmora", null, 22, 1950, 3, "minecraft:power", 3, "minecraft:punch", 1);
        def("whalebone_seax_of_saar", "nirncraft:atmoran_dagger", "atmora", null, 18, 1650, 3, "minecraft:sharpness", 3, "minecraft:looting", 2);
        def("na_totambu_crown_blade", "nirncraft:yokudan_sword", "yokuda", null, 26, 2600, 4, "minecraft:sharpness", 4, "minecraft:smite", 2);
        def("orichalc_tower_cleaver", "nirncraft:yokudan_greatsword", "yokuda", null, 30, 3100, 4, "minecraft:sharpness", 4, "minecraft:sweeping", 2);
        def("hiradirge_stone_maul", "nirncraft:yokudan_warhammer", "yokuda", null, 28, 2850, 4, "minecraft:sharpness", 3, "minecraft:knockback", 2);
        def("akaviri_reef_scimitar", "nirncraft:yokudan_sword", "yokuda", null, 22, 2050, 3, "minecraft:sharpness", 3, "minecraft:looting", 2);
        def("sunken_ansei_kris", "nirncraft:yokudan_dagger", "yokuda", null, 24, 2250, 3, "minecraft:smite", 3, "minecraft:sharpness", 2);
        def("tava_wind_longbow", "nirncraft:yokudan_bow", "yokuda", null, 20, 1900, 3, "minecraft:power", 3, "minecraft:flame", 1);
        def("zeht_harvest_halberd", "nirncraft:yokudan_battleaxe", "yokuda", null, 24, 2300, 3, "minecraft:sharpness", 3, "minecraft:unbreaking", 2);
        def("morwha_oasis_spear", "nirncraft:yokudan_spear", "yokuda", null, 22, 2000, 3, "minecraft:sharpness", 3, "minecraft:knockback", 1);
        def("orgnums_coffer_trident", "nirncraft:maormer_spear", "pyandonea", null, 28, 2900, 4, "minecraft:sharpness", 4, "minecraft:looting", 2);
        def("abbarbas_mist_cutlass", "nirncraft:maormer_sword", "pyandonea", null, 24, 2300, 3, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        def("sea_viper_fang_dagger", "nirncraft:maormer_dagger", "pyandonea", null, 20, 1850, 3, "minecraft:bane_of_arthropods", 3, "minecraft:sharpness", 2);
        def("leviathan_rib_longbow", "nirncraft:maormer_bow", "pyandonea", null, 22, 2100, 3, "minecraft:power", 3, "minecraft:punch", 2);
        def("pyandonean_storm_glaive", "nirncraft:maormer_greatsword", "pyandonea", null, 30, 3150, 4, "minecraft:sharpness", 4, "minecraft:fire_aspect", 1);
        def("serpent_scale_boarding_axe", "nirncraft:maormer_war_axe", "pyandonea", null, 22, 1950, 3, "minecraft:sharpness", 3, "minecraft:unbreaking", 2);
        def("tigers_reef_warhammer", "nirncraft:maormer_warhammer", "pyandonea", null, 26, 2500, 3, "minecraft:smite", 3, "minecraft:knockback", 2);
        def("pellucid_pearl_sabre", "nirncraft:maormer_sword", "pyandonea", null, 28, 2750, 4, "minecraft:sharpness", 3, "minecraft:looting", 2);
        def("coral_tower_flensing_knife", "nirncraft:daedric_dagger", "thras", null, 24, 2350, 3, "minecraft:smite", 3, "minecraft:looting", 2);
        def("agamir_sload_bone_mace", "nirncraft:daedric_mace", "thras", null, 28, 2800, 4, "minecraft:smite", 4, "minecraft:knockback", 2);
        def("korsharg_lagoon_trident", "nirncraft:daedric_spear", "thras", null, 26, 2600, 4, "minecraft:sharpness", 3, "minecraft:sweeping", 2);
        def("all_flags_crusader_relic", "nirncraft:silver_greatsword", "thras", null, 30, 3200, 4, "minecraft:smite", 5, "minecraft:unbreaking", 3);
        def("abyssal_polyp_cleaver", "nirncraft:daedric_battleaxe", "thras", null, 32, 3400, 4, "minecraft:sharpness", 4, "minecraft:fire_aspect", 1);
        def("soap_vat_necrotic_staff", "nirncraft:daedric_warhammer", "thras", null, 26, 2550, 3, "minecraft:smite", 3, "minecraft:sharpness", 2);
        def("sunken_galleon_boarding_sword", "nirncraft:silver_sword", "thras", null, 22, 2000, 3, "minecraft:sharpness", 3, "minecraft:looting", 2);
        def("reef_spire_barnacle_bow", "nirncraft:daedric_bow", "thras", null, 24, 2250, 3, "minecraft:power", 3, "minecraft:flame", 1);
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
            Enchantment ench = com.nirncraft.platform.EnchantmentsAccess.lookup(Identifier.parse(e.getKey()));
            if (ench != null) s.enchant(com.nirncraft.platform.EnchantmentsAccess.holder(ench), e.getValue());
        }
        s.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.translatable("unique.nirncraft." + id).withStyle(ChatFormatting.GOLD).withStyle(s2 -> s2.withItalic(false)));
        s.set(net.minecraft.core.component.DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(
                java.util.List.of(Component.translatable("unique.nirncraft." + id + ".lore")
                        .withStyle(ChatFormatting.DARK_PURPLE, ChatFormatting.ITALIC))));
        CompoundTag tag = com.nirncraft.inventory.ItemData.customOrEmpty(s);
        tag.putString(Collectibles.NBT, COLLECTION + "/" + id);
        tag.putInt(ItemValues.VALUE_NBT, u.value);
        if (u.quality > 0) tag.putInt(ItemValues.QUALITY_NBT, u.quality);
        com.nirncraft.inventory.ItemData.setCustom(s, tag);
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
