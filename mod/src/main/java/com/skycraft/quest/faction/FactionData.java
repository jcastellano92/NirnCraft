package com.skycraft.quest.faction;

import com.skycraft.core.Currency;
import com.skycraft.quest.Faction;
import com.skycraft.quest.Factions;
import com.skycraft.quest.QuestSpawner;
import com.skycraft.quest.Quests;
import com.skycraft.society.NpcRole;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * Everything about a faction beyond its {@link Faction} constant: who staffs its halls (the head, two trainers and a
 * quartermaster with their trade), what it asks of new members (a story condition, e.g. {@code race:dunmer}), where
 * its word carries weight with the guards, the discount its quartermasters give members, and the gear each rank
 * brings (faction armour from the arms catalogue, robes in the faction's colours, guild signets and a unique
 * heirloom at the top).
 */
public final class FactionData {
    private FactionData() {}

    /**
     * A hall's people: the head's role, the trainers' role and trades (vanilla profession ids: their trade decides
     * the skill they teach), and the quartermaster's shop pools.
     */
    public record Staff(NpcRole head, NpcRole trainer, String[] trainerTrades, String[] vendorPools) {
    }

    /**
     * @param requirement story condition a new member must meet ({@code ""} = anyone), shown by {@code .join}
     * @param governs     region ids or {@code province/<id>} where the guards defer to senior members
     * @param discount    base percent off at the faction's quartermasters (plus 1% per rank)
     */
    public record Info(Staff staff, String requirement, String[] governs, int discount) {
    }

    private static final Map<Faction, Info> INFO = new EnumMap<>(Faction.class);

    private static String[] a(String... s) {
        return s;
    }

    private static void put(Faction f, NpcRole head, NpcRole trainer, String[] trades, String[] pools, String requirement,
                            String[] governs, int discount) {
        INFO.put(f, new Info(new Staff(head, trainer, trades, pools), requirement, governs, discount));
    }

    static {
        String ws = "minecraft:weaponsmith", ar = "minecraft:armorer", lib = "minecraft:librarian", cl = "minecraft:cleric",
                sh = "minecraft:shepherd", fl = "minecraft:fletcher", ca = "minecraft:cartographer", bu = "minecraft:butcher",
                tl = "minecraft:toolsmith";
        String[] smith = a("blacksmith"), arcane = a("arcane", "library"), apo = a("apothecary"), fence = a("fence", "general");
        String[] none = a();
        // Skyrim
        put(Faction.COMPANIONS, NpcRole.ADVENTURER, NpcRole.ADVENTURER, a(ws, ar), smith, "", none, 10);
        put(Faction.COLLEGE, NpcRole.MAGE, NpcRole.MAGE, a(lib, lib), arcane, "", none, 10);
        put(Faction.THIEVES_GUILD, NpcRole.CITIZEN, NpcRole.CITIZEN, a(sh, fl), fence, "", none, 15);
        put(Faction.DARK_BROTHERHOOD, NpcRole.ASSASSIN, NpcRole.CITIZEN, a(sh, fl), apo, "", none, 15);
        put(Faction.IMPERIAL_LEGION, NpcRole.IMPERIAL_SOLDIER, NpcRole.IMPERIAL_SOLDIER, a(ws, ar), smith, "",
                a("province/cyrodiil", "imperial"), 10);
        put(Faction.STORMCLOAKS, NpcRole.STORMCLOAK_SOLDIER, NpcRole.STORMCLOAK_SOLDIER, a(ws, ar), smith, "", a("stormcloak"), 10);
        put(Faction.BARDS_COLLEGE, NpcRole.BARD, NpcRole.BARD, a(ca, lib), a("library"), "", none, 10);
        put(Faction.GREYBEARDS, NpcRole.PRIEST, NpcRole.PRIEST, a(lib), none, "quest:main_way_of_the_voice", none, 0);
        put(Faction.VIGILANTS_OF_STENDARR, NpcRole.PRIEST, NpcRole.ADVENTURER, a(cl, ar), apo, "!curse:vampire;!curse:werewolf", none, 10);
        put(Faction.SILVER_HAND, NpcRole.THUG, NpcRole.THUG, a(ws), smith, "false", none, 0);
        put(Faction.FORSWORN, NpcRole.FORSWORN, NpcRole.FORSWORN, a(fl, bu), a("hunter"), "flag:fs_sided_madanach", none, 10);
        // across Tamriel
        put(Faction.FIGHTERS_GUILD, NpcRole.ADVENTURER, NpcRole.ADVENTURER, a(ws, ar), smith, "", none, 10);
        put(Faction.MAGES_GUILD, NpcRole.MAGE, NpcRole.MAGE, a(lib, cl), arcane, "", none, 10);
        put(Faction.UNDAUNTED, NpcRole.ADVENTURER, NpcRole.ADVENTURER, a(ws, fl), a("general", "fletcher"), "", none, 10);
        put(Faction.IMPERIAL_CULT, NpcRole.PRIEST, NpcRole.PRIEST, a(cl, cl), apo, "", none, 10);
        put(Faction.THALMOR, NpcRole.THALMOR, NpcRole.MAGE, a(lib, ar), arcane, "race:altmer",
                a("province/summerset", "province/valenwood"), 10);
        // Cyrodiil
        put(Faction.BLADES, NpcRole.ADVENTURER, NpcRole.ADVENTURER, a(ws, ar), smith, "level:10", none, 10);
        put(Faction.KNIGHTS_OF_THE_NINE, NpcRole.ADVENTURER, NpcRole.PRIEST, a(ar, cl), smith, "!curse:vampire", none, 10);
        put(Faction.ARENA, NpcRole.ADVENTURER, NpcRole.ADVENTURER, a(ws, ar), smith, "", none, 10);
        // Morrowind
        put(Faction.HOUSE_HLAALU, NpcRole.CITIZEN, NpcRole.CITIZEN, a(ca, sh), a("general", "jeweler"), "",
                a("morrowind/west_gash", "morrowind/ascadian_isles"), 15);
        put(Faction.HOUSE_REDORAN, NpcRole.ADVENTURER, NpcRole.ADVENTURER, a(ar, ws), smith, "",
                a("morrowind/vvardenfell_ashlands", "morrowind/solstheim"), 10);
        put(Faction.HOUSE_TELVANNI, NpcRole.MAGE, NpcRole.MAGE, a(lib, lib), arcane,
                "skill:destruction:15|skill:alteration:15|skill:mysticism:15|skill:conjuration:15",
                a("morrowind/telvannis", "morrowind/azuras_coast", "morrowind/sheogorad"), 10);
        put(Faction.TRIBUNAL_TEMPLE, NpcRole.PRIEST, NpcRole.PRIEST, a(cl, lib), apo, "",
                a("morrowind/deshaan", "morrowind/molag_amur"), 10);
        put(Faction.MORAG_TONG, NpcRole.ADVENTURER, NpcRole.ADVENTURER, a(sh, fl), smith, "", none, 10);
        put(Faction.ASHLANDERS, NpcRole.HUNTER, NpcRole.HUNTER, a(fl, bu), a("hunter", "exotic"), "", none, 10);
        put(Faction.CAMONNA_TONG, NpcRole.ADVENTURER, NpcRole.CITIZEN, a(sh, ws), fence, "race:dunmer", none, 15);
        put(Faction.EAST_EMPIRE_COMPANY, NpcRole.CITIZEN, NpcRole.CITIZEN, a(ca, fl), a("general", "exotic"), "",
                a("morrowind/stonefalls"), 15);
        // the other provinces
        put(Faction.SHADOWSCALES, NpcRole.ADVENTURER, NpcRole.ADVENTURER, a(sh, cl), apo, "race:argonian", a("province/black_marsh"), 10);
        put(Faction.MOON_PRIESTS, NpcRole.PRIEST, NpcRole.PRIEST, a(cl, lib), a("apothecary", "arcane"), "",
                a("elsweyr/torval", "elsweyr/senchal", "elsweyr/pellitine"), 10);
        put(Faction.RENRIJRA_KRIN, NpcRole.ADVENTURER, NpcRole.ADVENTURER, a(sh, fl), fence, "", none, 15);
        put(Faction.GREEN_PACT, NpcRole.HUNTER, NpcRole.HUNTER, a(fl, bu), a("hunter"), "", a("province/valenwood"), 10);
        put(Faction.ALDMERI_DOMINION, NpcRole.ADVENTURER, NpcRole.MAGE, a(fl, lib), a("general", "arcane"),
                "race:altmer,bosmer,khajiit,ohmes_raht", a("province/summerset", "province/valenwood"), 10);
        put(Faction.PSIJIC_ORDER, NpcRole.MAGE, NpcRole.MAGE, a(lib, lib), arcane,
                "skill:mysticism:25|faction:college:2|faction:mages_guild:4", none, 10);
        put(Faction.CROWNS, NpcRole.ADVENTURER, NpcRole.ADVENTURER, a(ws, ar), smith, "", a("hammerfell/alikr_desert", "hammerfell/craglorn"), 10);
        put(Faction.FOREBEARS, NpcRole.CITIZEN, NpcRole.ADVENTURER, a(ca, ws), a("exotic", "general"), "",
                a("hammerfell/hews_bane", "hammerfell/stros_mkai", "hammerfell/dragontail"), 15);
        put(Faction.KNIGHTS_OF_THE_DRAGON, NpcRole.ADVENTURER, NpcRole.ADVENTURER, a(ar, ws), smith, "",
                a("high_rock/glenumbra"), 10);
        put(Faction.KNIGHTS_OF_THE_ROSE, NpcRole.ADVENTURER, NpcRole.ADVENTURER, a(ar, ws), smith, "",
                a("high_rock/wayrest", "high_rock/stormhaven"), 10);
        put(Faction.ORC_STRONGHOLDS, NpcRole.ADVENTURER, NpcRole.BLACKSMITH, a(ar, tl), smith, "", a("high_rock/wrothgar"), 10);
    }

    @Nullable
    public static Info info(Faction f) {
        return INFO.get(f);
    }

    public static String requirement(Faction f) {
        Info i = INFO.get(f);
        return i == null ? "" : i.requirement();
    }

    /** Percent off at the faction's own quartermasters for a member of {@code rank}. */
    public static int discount(Faction f, int rank) {
        Info i = INFO.get(f);
        return i == null || rank < 0 ? 0 : Math.min(30, i.discount() + rank);
    }

    // ------------------------------------------------------------------ rank gear

    private static Item item(String id, Item fallback) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        Item item = rl == null ? null : ForgeRegistries.ITEMS.getValue(rl);
        return item == null || item == Items.AIR ? fallback : item;
    }

    private static ItemStack named(String id, Item fallback, String key) {
        ItemStack s = new ItemStack(item(id, fallback));
        s.setHoverName(Component.translatable("item.skycraft.reward." + key));
        return s;
    }

    private static ItemStack ench(ItemStack s, Enchantment e, int lvl) {
        s.enchant(e, lvl);
        return s;
    }

    /** Robes: a dyed leather tunic in the faction's colours. */
    private static ItemStack robe(int color, String key) {
        ItemStack s = QuestSpawner.dyed(Items.LEATHER_CHESTPLATE, color);
        s.setHoverName(Component.translatable("item.skycraft.reward." + key));
        return s;
    }

    /** A full set of an arms-catalogue armour (helmet, chest, legs, boots), named after the faction. */
    private static void set(ServerPlayer p, String material, Item[] fallback, String key) {
        String[] pieces = {"helmet", "chestplate", "leggings", "boots"};
        for (int i = 0; i < 4; i++) Quests.give(p, named("skycraft:" + material + "_" + pieces[i], fallback[i], key));
    }

    private static final Item[] IRON = {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
    private static final Item[] LEATHER = {Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};
    private static final Item[] DIAMOND = {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};

    private static void give(ServerPlayer p, ItemStack s) {
        Quests.give(p, s);
    }

    /**
     * The gear of a rank of the factions added with the Tamriel halls (the Skyrim guilds' own rewards stay in
     * {@link Factions}): a joining gift, faction armour midway, the heirloom at the top, gold otherwise.
     */
    public static void rankReward(ServerPlayer p, Faction f, int rank) {
        int top = f.maxRank();
        int mid = Math.max(1, top / 2);
        boolean gift = rank == 0, middle = rank == mid, last = rank == top;
        switch (f) {
            case FIGHTERS_GUILD -> {
                if (gift) give(p, named("skycraft:steel_sword", Items.IRON_SWORD, "fighters_guild_blade"));
                if (gift) give(p, named("skycraft:silver_ring", Items.GOLD_NUGGET, "fighters_guild_signet"));
                if (middle) set(p, "steel_plate", IRON, "fighters_guild_plate");
                if (last) give(p, ench(named("skycraft:glass_greatsword", Items.DIAMOND_SWORD, "fighters_guild_masters_blade"), Enchantments.SHARPNESS, 4));
            }
            case MAGES_GUILD -> {
                if (gift) give(p, robe(0x2F4FA8, "mages_guild_robes"));
                if (gift) give(p, Factions.randomSpellTome(p.getRandom()));
                if (middle) give(p, ench(robe(0x22357A, "mages_guild_wizard_robes"), Enchantments.ALL_DAMAGE_PROTECTION, 2));
                if (last) give(p, ench(named("skycraft:staff_of_magnus", Items.BLAZE_ROD, "arch_mages_staff"), Enchantments.FIRE_ASPECT, 2));
            }
            case UNDAUNTED -> {
                if (gift) give(p, named("skycraft:hide_helmet", Items.LEATHER_HELMET, "undaunted_trophy_helm"));
                if (middle) give(p, ench(named("skycraft:dwarven_war_axe", Items.IRON_AXE, "undaunted_delvers_axe"), Enchantments.SHARPNESS, 2));
                if (last) set(p, "ebony", DIAMOND, "undaunted_legendary_set");
            }
            case IMPERIAL_CULT -> {
                if (gift) give(p, named("skycraft:amulet_of_mara", Items.GOLD_NUGGET, "imperial_cult_amulet"));
                if (middle) give(p, ench(robe(0xD8C070, "imperial_cult_robes"), Enchantments.ALL_DAMAGE_PROTECTION, 2));
                if (last) give(p, ench(named("skycraft:amulet_of_talos", Items.GOLD_INGOT, "primates_amulet"), Enchantments.ALL_DAMAGE_PROTECTION, 3));
            }
            case THALMOR -> {
                if (gift) give(p, robe(0x2D2D2D, "thalmor_robes"));
                if (middle) set(p, "thalmor", IRON, "thalmor_justiciar_set");
                if (last) give(p, ench(named("skycraft:elven_sword", Items.DIAMOND_SWORD, "first_emissarys_blade"), Enchantments.SHARPNESS, 4));
            }
            case BLADES -> {
                if (gift) give(p, named("skycraft:akaviri_dagger", Items.IRON_SWORD, "blades_tanto"));
                if (middle) set(p, "blades", IRON, "blades_set");
                if (last) give(p, ench(named("skycraft:akaviri_sword", Items.DIAMOND_SWORD, "grandmasters_katana"), Enchantments.SHARPNESS, 4));
            }
            case KNIGHTS_OF_THE_NINE -> {
                if (gift) give(p, named("skycraft:amulet_of_talos", Items.GOLD_NUGGET, "knights_of_the_nine_token"));
                if (middle) set(p, "steel_plate", IRON, "crusader_plate");
                if (last) give(p, ench(named("skycraft:daedric_mace", Items.DIAMOND_AXE, "mace_of_the_crusader"), Enchantments.SMITE, 5));
            }
            case ARENA -> {
                if (gift) give(p, robe(0x7A1F1F, "arena_raiment"));
                if (middle) set(p, "imperial", IRON, "arena_gladiator_set");
                if (last) give(p, ench(named("skycraft:ebony_battleaxe", Items.DIAMOND_AXE, "grand_champions_axe"), Enchantments.SHARPNESS, 4));
            }
            case HOUSE_HLAALU -> {
                if (gift) give(p, named("skycraft:netch_chestplate", Items.LEATHER_CHESTPLATE, "hlaalu_netch_jerkin"));
                if (middle) give(p, ench(named("skycraft:gold_ring", Items.GOLD_INGOT, "hlaalu_councilors_ring"), Enchantments.ALL_DAMAGE_PROTECTION, 1));
                if (last) give(p, ench(named("skycraft:ebony_sword", Items.DIAMOND_SWORD, "hlaalu_grandmasters_blade"), Enchantments.SHARPNESS, 4));
            }
            case HOUSE_REDORAN -> {
                if (gift) give(p, named("skycraft:chitin_sword", Items.IRON_SWORD, "redoran_chitin_blade"));
                if (middle) set(p, "bonemold", IRON, "redoran_bonemold_set");
                if (last) give(p, ench(named("skycraft:ebony_spear", Items.DIAMOND_SWORD, "archmasters_spear"), Enchantments.SHARPNESS, 4));
            }
            case HOUSE_TELVANNI -> {
                if (gift) give(p, robe(0x5B3A8C, "telvanni_robes"));
                if (middle) give(p, ench(robe(0x3F2466, "telvanni_wizards_robes"), Enchantments.ALL_DAMAGE_PROTECTION, 3));
                if (last) give(p, ench(named("skycraft:glass_dagger", Items.DIAMOND_SWORD, "archmagisters_dagger"), Enchantments.SHARPNESS, 4));
            }
            case TRIBUNAL_TEMPLE -> {
                if (gift) give(p, robe(0xB0602A, "temple_robes"));
                if (middle) set(p, "ordinator", IRON, "ordinator_set");
                if (last) give(p, ench(named("skycraft:daedric_spear", Items.DIAMOND_SWORD, "patriarchs_spear"), Enchantments.SMITE, 4));
            }
            case MORAG_TONG -> {
                if (gift) give(p, named("skycraft:netch_helmet", Items.LEATHER_HELMET, "morag_tong_hood"));
                if (middle) set(p, "netch", LEATHER, "morag_tong_set");
                if (last) give(p, ench(named("skycraft:ebony_dagger", Items.DIAMOND_SWORD, "grandmasters_writ_blade"), Enchantments.SHARPNESS, 5));
            }
            case ASHLANDERS -> {
                if (gift) give(p, named("skycraft:chitin_bow", Items.BOW, "ashlander_bow"));
                if (middle) set(p, "chitin", LEATHER, "ashlander_chitin_set");
                if (last) give(p, ench(named("skycraft:glass_spear", Items.DIAMOND_SWORD, "ashkhans_spear"), Enchantments.SHARPNESS, 4));
            }
            case CAMONNA_TONG -> {
                if (gift) give(p, named("skycraft:iron_dagger", Items.IRON_SWORD, "tong_shiv"));
                if (middle) set(p, "netch", LEATHER, "tong_netch_set");
                if (last) give(p, ench(named("skycraft:glass_war_axe", Items.DIAMOND_AXE, "tong_bosses_axe"), Enchantments.SHARPNESS, 4));
            }
            case EAST_EMPIRE_COMPANY -> {
                if (gift) give(p, robe(0x2F5F9F, "eec_factors_coat"));
                if (middle) give(p, ench(named("skycraft:imperial_light_chestplate", Items.CHAINMAIL_CHESTPLATE, "eec_agents_cuirass"), Enchantments.ALL_DAMAGE_PROTECTION, 2));
                if (last) give(p, ench(named("skycraft:imperial_sword", Items.DIAMOND_SWORD, "directors_sabre"), Enchantments.SHARPNESS, 4));
            }
            case SHADOWSCALES -> {
                if (gift) give(p, named("skycraft:argonian_helmet", Items.LEATHER_HELMET, "shadowscale_mask"));
                if (middle) set(p, "argonian", LEATHER, "shadowscale_set");
                if (last) give(p, ench(named("skycraft:glass_dagger", Items.DIAMOND_SWORD, "speakers_fang"), Enchantments.SHARPNESS, 5));
            }
            case MOON_PRIESTS -> {
                if (gift) give(p, robe(0xB8B0E8, "moon_priest_robes"));
                if (middle) set(p, "khajiit", LEATHER, "moon_singer_set");
                if (last) give(p, ench(named("skycraft:elven_glaive", Items.DIAMOND_SWORD, "lunar_glaive"), Enchantments.SMITE, 4));
            }
            case RENRIJRA_KRIN -> {
                if (gift) give(p, named("skycraft:iron_claw", Items.IRON_SWORD, "renrijra_claw"));
                if (middle) set(p, "khajiit", LEATHER, "renrijra_set");
                if (last) give(p, ench(named("skycraft:glass_claw", Items.DIAMOND_SWORD, "warlords_claw"), Enchantments.SHARPNESS, 4));
            }
            case GREEN_PACT -> {
                if (gift) give(p, named("skycraft:bosmer_helmet", Items.LEATHER_HELMET, "warden_hood"));
                if (middle) set(p, "bosmer", LEATHER, "warden_set");
                if (last) give(p, ench(named("skycraft:elven_bow", Items.BOW, "bow_of_the_green"), Enchantments.POWER_ARROWS, 4));
            }
            case ALDMERI_DOMINION -> {
                if (gift) give(p, named("skycraft:elven_dagger", Items.IRON_SWORD, "dominion_dagger"));
                if (middle) set(p, "elven", IRON, "dominion_sentinel_set");
                if (last) set(p, "elven_gilded", DIAMOND, "hand_of_the_queen_set");
            }
            case PSIJIC_ORDER -> {
                if (gift) give(p, robe(0x3E8E84, "psijic_robes"));
                if (middle) give(p, Factions.randomSpellTome(p.getRandom()));
                if (last) give(p, ench(named("minecraft:golden_helmet", Items.GOLDEN_HELMET, "ritemasters_circlet"), Enchantments.ALL_DAMAGE_PROTECTION, 4));
            }
            case CROWNS -> {
                if (gift) give(p, named("skycraft:steel_sword", Items.IRON_SWORD, "crown_scimitar"));
                if (middle) set(p, "ra_gada", IRON, "crown_ra_gada_set");
                if (last) give(p, ench(named("skycraft:ebony_sword", Items.DIAMOND_SWORD, "shehai_blade"), Enchantments.SHARPNESS, 5));
            }
            case FOREBEARS -> {
                if (gift) give(p, named("skycraft:gold_ring", Items.GOLD_NUGGET, "forebear_signet"));
                if (middle) set(p, "ra_gada", IRON, "forebear_ra_gada_set");
                if (last) give(p, ench(named("skycraft:dwarven_sword", Items.DIAMOND_SWORD, "forebear_lords_sabre"), Enchantments.SHARPNESS, 4));
            }
            case KNIGHTS_OF_THE_DRAGON -> {
                if (gift) give(p, named("skycraft:breton_helmet", Items.IRON_HELMET, "dragon_knights_helm"));
                if (middle) set(p, "steel_plate", IRON, "dragon_knights_plate");
                if (last) give(p, ench(named("skycraft:dwarven_greatsword", Items.DIAMOND_SWORD, "lord_commanders_blade"), Enchantments.SHARPNESS, 4));
            }
            case KNIGHTS_OF_THE_ROSE -> {
                if (gift) give(p, named("skycraft:breton_chestplate", Items.IRON_CHESTPLATE, "rose_knights_surcoat"));
                if (middle) set(p, "steel_plate", IRON, "rose_knights_plate");
                if (last) give(p, ench(named("skycraft:elven_sword", Items.DIAMOND_SWORD, "thorn_of_the_rose"), Enchantments.SHARPNESS, 4));
            }
            case ORC_STRONGHOLDS -> {
                if (gift) give(p, named("skycraft:orcish_war_axe", Items.IRON_AXE, "blood_kin_axe"));
                if (middle) set(p, "orsinium", IRON, "stronghold_set");
                if (last) give(p, ench(named("skycraft:orcish_greatsword", Items.DIAMOND_SWORD, "honored_chiefs_blade"), Enchantments.SHARPNESS, 4));
            }
            case GREYBEARDS -> {
                if (gift) give(p, robe(0x8C8C96, "greybeard_robes"));
                if (last) give(p, ench(robe(0x6E6E78, "master_of_the_voice_robes"), Enchantments.ALL_DAMAGE_PROTECTION, 3));
            }
            case VIGILANTS_OF_STENDARR -> {
                if (gift) give(p, robe(0xE8DCA0, "vigilant_robes"));
                if (middle) give(p, ench(named("skycraft:steel_mace", Items.IRON_AXE, "vigilants_mace"), Enchantments.SMITE, 3));
                if (last) give(p, ench(named("skycraft:daedric_warhammer", Items.DIAMOND_AXE, "hammer_of_stendarr"), Enchantments.SMITE, 5));
            }
            case FORSWORN -> {
                if (gift) set(p, "forsworn", LEATHER, "forsworn_set");
                if (last) give(p, ench(named("skycraft:forsworn_chestplate", Items.LEATHER_CHESTPLATE, "briarheart_vest"), Enchantments.THORNS, 3));
            }
            default -> {
            }
        }
        long gold = last ? 1500 : gift ? 100 : 120L * rank;
        Currency.give(p, gold);
    }
}
