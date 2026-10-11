package com.skycraft.survival.fishing;

import com.skycraft.crafting.arcane.alchemy.AlchemyEffect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.skycraft.crafting.arcane.alchemy.AlchemyEffect.*;

/**
 * Every fish of Nirn you can catch, where and when it bites, and what it is good for (food value, alchemy). Skyrim's
 * own fish and ingredients first, then each province's. Each species is an item {@code skycraft:<id>}.
 *
 * @param rarity    0 common, 1 uncommon, 2 rare
 * @param waters    the water it lives in
 * @param provinces province ids ({@code skyrim}, {@code cyrodiil}, ...), or empty for everywhere
 * @param regions   region short ids that also count (e.g. {@code solstheim}), may be empty
 * @param time      when it bites
 * @param minDepth  water depth (blocks) it needs
 * @param minCm     smallest catch, cm
 * @param maxCm     largest catch, cm
 * @param value     gold value
 */
public record FishSpecies(String id, int rarity, Set<Water> waters, Set<String> provinces, Set<String> regions, Time time,
                          int minDepth, int minCm, int maxCm, int value, AlchemyEffect[] effects) {

    /** Where the hook sits. COLD and WARM are added on top of the water body type. */
    public enum Water { RIVER, LAKE, OCEAN, SWAMP, CAVE, COLD, WARM }

    public enum Time {
        ANY, DAY, NIGHT, TWILIGHT;

        public boolean matches(long dayTime) {
            long t = dayTime % 24000L;
            boolean night = t >= 13000 && t <= 23000;
            boolean twilight = t >= 22500 || t <= 1000 || (t >= 11500 && t <= 13500);
            return switch (this) {
                case ANY -> true;
                case DAY -> !night;
                case NIGHT -> night;
                case TWILIGHT -> twilight;
            };
        }
    }

    private static final Map<String, FishSpecies> BY_ID = new LinkedHashMap<>();

    // ---------------------------------------------------------------- Skyrim
    static {
        fish("salmon", 0, w(Water.RIVER, Water.LAKE), p("skyrim"), r("solstheim"), Time.ANY, 1, 40, 90, 5,
                RESTORE_STAMINA, WATERBREATHING, FORTIFY_HEALTH, RESTORE_MAGICKA);
        fish("arctic_char", 0, w(Water.COLD), p("skyrim", "atmora"), r("solstheim"), Time.ANY, 1, 30, 70, 4,
                RESIST_FROST, RESTORE_STAMINA, FORTIFY_BLOCK, WATERBREATHING);
        fish("arctic_grayling", 1, w(Water.COLD), p("skyrim", "atmora"), r("solstheim"), Time.DAY, 1, 25, 55, 8,
                RESIST_FROST, FORTIFY_SNEAK, RESTORE_HEALTH, NIGHT_EYE);
        fish("brook_trout", 0, w(Water.RIVER), p("skyrim", "high_rock"), r(), Time.ANY, 1, 20, 45, 4,
                RESTORE_STAMINA, FORTIFY_ARCHERY, RESTORE_HEALTH, SWIFTNESS);
        fish("carp", 0, w(Water.LAKE), p(), r(), Time.ANY, 1, 30, 80, 3,
                RESTORE_HEALTH, DAMAGE_STAMINA_REGEN, FORTIFY_CARRY_WEIGHT, WATERBREATHING);
        fish("catfish", 1, w(Water.LAKE, Water.SWAMP, Water.RIVER), p("skyrim", "elsweyr", "cyrodiil"), r(), Time.NIGHT, 2, 40, 110, 9,
                NIGHT_EYE, RESTORE_HEALTH, DAMAGE_MAGICKA, FORTIFY_HEAVY_ARMOR);
        fish("goldfish", 2, w(Water.LAKE), p("skyrim", "cyrodiil"), r(), Time.DAY, 1, 8, 20, 25,
                FORTIFY_BARTER, RESTORE_MAGICKA, FORTIFY_ILLUSION, INVISIBILITY);
        fish("pike", 1, w(Water.LAKE, Water.RIVER), p("skyrim", "high_rock", "cyrodiil"), r(), Time.TWILIGHT, 2, 50, 130, 10,
                FORTIFY_ONE_HANDED, RESTORE_STAMINA, FRENZY, DAMAGE_HEALTH);
        fish("pogfish", 0, w(Water.OCEAN), p("skyrim", "atmora"), r("solstheim", "sea_of_ghosts"), Time.ANY, 2, 15, 35, 3,
                RESTORE_MAGICKA, WATERBREATHING, DAMAGE_STAMINA, FEAR);
        fish("vampire_fish", 2, w(Water.RIVER, Water.LAKE, Water.SWAMP), p("skyrim"), r("solstheim"), Time.NIGHT, 2, 20, 45, 30,
                RAVAGE_HEALTH, NIGHT_EYE, INVISIBILITY, RESIST_MAGIC);
        fish("silverside_perch", 0, w(Water.RIVER, Water.LAKE), p(), r(), Time.ANY, 1, 15, 35, 3,
                RESTORE_STAMINA, DAMAGE_STAMINA_REGEN, RAVAGE_HEALTH, RESIST_FROST);
        fish("river_betty", 1, w(Water.RIVER), p("skyrim", "cyrodiil", "high_rock"), r(), Time.ANY, 1, 10, 25, 15,
                DAMAGE_HEALTH, FORTIFY_ALTERATION, SLOW, FORTIFY_CARRY_WEIGHT);
        fish("abecean_longfin", 1, w(Water.OCEAN), p("skyrim", "summerset", "hammerfell", "valenwood"), r("abecean_sea"), Time.ANY, 3, 30, 70, 15,
                WEAKNESS_TO_FROST, FORTIFY_SNEAK, WEAKNESS_TO_POISON, FORTIFY_RESTORATION);
        fish("cyrodilic_spadetail", 1, w(Water.RIVER, Water.LAKE), p("cyrodiil", "skyrim"), r(), Time.DAY, 1, 20, 50, 15,
                DAMAGE_STAMINA, FORTIFY_RESTORATION, FEAR, RAVAGE_HEALTH);
        fish("histcarp", 1, w(Water.SWAMP), p("black_marsh", "skyrim", "akavir"), r(), Time.ANY, 1, 30, 70, 6,
                RESTORE_STAMINA, RESTORE_MAGICKA, DAMAGE_MAGICKA_REGEN, WATERBREATHING);
        fish("glassfish", 2, w(Water.CAVE), p(), r(), Time.ANY, 1, 10, 30, 40,
                RESTORE_HEALTH, FORTIFY_ILLUSION, INVISIBILITY, RESIST_MAGIC);
        fish("juvenile_mudcrab", 0, w(Water.RIVER, Water.LAKE, Water.OCEAN, Water.SWAMP), p(), r(), Time.ANY, 1, 10, 25, 4,
                RESTORE_STAMINA, RESIST_POISON, FORTIFY_HEAVY_ARMOR, CURE_DISEASE);
        fish("slaughterfish_egg", 1, w(Water.RIVER, Water.LAKE, Water.SWAMP), p(), r(), Time.ANY, 1, 2, 4, 3,
                RESIST_POISON, FORTIFY_PICKPOCKET, LINGERING_DAMAGE_HEALTH, FORTIFY_HEALTH);

        // ------------------------------------------------------------ Cyrodiil
        fish("niben_eel", 0, w(Water.RIVER, Water.SWAMP), p("cyrodiil"), r(), Time.NIGHT, 1, 50, 120, 6,
                LEAP, RESTORE_STAMINA, DAMAGE_MAGICKA, WATERBREATHING);
        fish("rumare_bass", 0, w(Water.LAKE), p("cyrodiil"), r(), Time.ANY, 2, 25, 60, 5,
                RESTORE_HEALTH, FORTIFY_BLOCK, RESTORE_STAMINA, RESIST_SHOCK);
        fish("colovian_trout", 1, w(Water.RIVER, Water.COLD), p("cyrodiil", "hammerfell"), r(), Time.DAY, 1, 25, 55, 9,
                FORTIFY_TWO_HANDED, RESTORE_STAMINA, RESIST_FROST, REGENERATE_STAMINA);
        fish("gold_coast_snapper", 1, w(Water.OCEAN), p("cyrodiil"), r("topal_bay"), Time.ANY, 3, 30, 75, 12,
                FORTIFY_BARTER, RESTORE_HEALTH, WEAKNESS_TO_FIRE, FORTIFY_SMITHING);

        // ------------------------------------------------------------ Morrowind
        fish("ash_perch", 0, w(Water.LAKE, Water.RIVER, Water.WARM), p("morrowind"), r(), Time.ANY, 1, 15, 40, 5,
                RESIST_FIRE, RESTORE_STAMINA, DAMAGE_MAGICKA_REGEN, FORTIFY_DESTRUCTION);
        fish("kollop", 1, w(Water.OCEAN), p("morrowind"), r("inner_sea"), Time.ANY, 2, 8, 20, 20,
                FORTIFY_ENCHANTING, RESTORE_MAGICKA, WATERBREATHING, PARALYSIS);
        fish("bittergreen_eel", 1, w(Water.SWAMP, Water.RIVER), p("morrowind"), r("bitter_coast"), Time.NIGHT, 1, 50, 110, 12,
                LINGERING_DAMAGE_HEALTH, NIGHT_EYE, FORTIFY_ALCHEMY, WEAKNESS_TO_POISON);
        fish("dreugh_crab", 1, w(Water.OCEAN), p("morrowind"), r(), Time.TWILIGHT, 2, 20, 45, 14,
                FORTIFY_HEAVY_ARMOR, RESIST_SHOCK, RESTORE_STAMINA, SLOW);
        fish("telvanni_glowfish", 2, w(Water.LAKE, Water.SWAMP), p("morrowind"), r("telvannis", "sheogorad"), Time.NIGHT, 1, 12, 30, 35,
                NIGHT_EYE, REGENERATE_MAGICKA, FORTIFY_ILLUSION, RESIST_MAGIC);

        // ------------------------------------------------------------ Black Marsh
        fish("marsh_gar", 0, w(Water.SWAMP, Water.RIVER), p("black_marsh"), r(), Time.ANY, 2, 60, 150, 7,
                RESIST_POISON, FORTIFY_ONE_HANDED, DAMAGE_STAMINA, WATERBREATHING);
        fish("murkmire_mudskipper", 0, w(Water.SWAMP), p("black_marsh"), r(), Time.DAY, 1, 10, 25, 3,
                LEAP, CURE_DISEASE, RESIST_POISON, FORTIFY_SNEAK);
        fish("bloodfin", 2, w(Water.SWAMP, Water.RIVER), p("black_marsh"), r(), Time.NIGHT, 2, 15, 35, 30,
                FRENZY, DAMAGE_HEALTH, REGENERATE_HEALTH, FORTIFY_TWO_HANDED);

        // ------------------------------------------------------------ Elsweyr
        fish("riverhold_catfish", 0, w(Water.RIVER, Water.LAKE), p("elsweyr"), r(), Time.ANY, 1, 30, 90, 6,
                RESTORE_HEALTH, NIGHT_EYE, FORTIFY_CARRY_WEIGHT, DAMAGE_MAGICKA);
        fish("sugar_koi", 2, w(Water.LAKE), p("elsweyr"), r("pellitine"), Time.DAY, 1, 20, 50, 40,
                SWIFTNESS, RESTORE_STAMINA, FORTIFY_BARTER, FRENZY);
        fish("senchal_snapper", 0, w(Water.OCEAN), p("elsweyr"), r("topal_bay"), Time.ANY, 2, 25, 60, 6,
                RESTORE_HEALTH, FORTIFY_PICKPOCKET, RESTORE_STAMINA, RESIST_FIRE);
        fish("dune_minnow", 1, w(Water.LAKE, Water.WARM), p("elsweyr", "hammerfell", "yokuda"), r(), Time.TWILIGHT, 1, 5, 12, 6,
                RESIST_FIRE, SWIFTNESS, FORTIFY_SNEAK, DAMAGE_STAMINA);

        // ------------------------------------------------------------ Valenwood
        fish("valenwood_tigerfish", 0, w(Water.RIVER, Water.LAKE), p("valenwood"), r(), Time.ANY, 1, 25, 60, 6,
                FORTIFY_ARCHERY, RESTORE_STAMINA, FRENZY, NIGHT_EYE);
        fish("greenshade_glimmerfish", 2, w(Water.RIVER, Water.LAKE), p("valenwood"), r("greenshade"), Time.NIGHT, 1, 10, 25, 35,
                FORTIFY_ILLUSION, INVISIBILITY, REGENERATE_MAGICKA, NIGHT_EYE);
        fish("woodhearth_mackerel", 0, w(Water.OCEAN), p("valenwood"), r(), Time.ANY, 2, 25, 50, 5,
                RESTORE_STAMINA, WATERBREATHING, FORTIFY_ARCHERY, RESIST_SHOCK);

        // ------------------------------------------------------------ Summerset
        fish("alinor_sunfish", 1, w(Water.LAKE, Water.RIVER), p("summerset"), r(), Time.DAY, 1, 15, 35, 14,
                RESTORE_MAGICKA, FORTIFY_DESTRUCTION, RESIST_FIRE, FORTIFY_ENCHANTING);
        fish("auridon_silverfin", 0, w(Water.OCEAN), p("summerset"), r(), Time.ANY, 2, 25, 60, 8,
                RESTORE_MAGICKA, WATERBREATHING, FORTIFY_ALTERATION, RESIST_MAGIC);
        fish("artaeum_moonfish", 2, w(Water.OCEAN, Water.LAKE), p("summerset"), r("artaeum"), Time.NIGHT, 2, 20, 50, 60,
                FORTIFY_ILLUSION, REGENERATE_MAGICKA, INVISIBILITY, FORTIFY_CONJURATION);

        // ------------------------------------------------------------ Hammerfell
        fish("iliac_tuna", 0, w(Water.OCEAN), p("hammerfell", "high_rock"), r("iliac_bay"), Time.ANY, 3, 60, 160, 9,
                FORTIFY_HEALTH, RESTORE_STAMINA, FORTIFY_TWO_HANDED, WATERBREATHING);
        fish("redfin_trout", 1, w(Water.RIVER), p("hammerfell"), r("bangkorai"), Time.ANY, 1, 20, 45, 10,
                RESTORE_HEALTH, FORTIFY_ONE_HANDED, SWIFTNESS, RESIST_FIRE);
        fish("stros_mkai_marlin", 2, w(Water.OCEAN, Water.WARM), p("hammerfell", "yokuda"), r("stros_mkai"), Time.DAY, 4, 120, 300, 80,
                FORTIFY_TWO_HANDED, FORTIFY_HEALTH, REGENERATE_STAMINA, FORTIFY_BLOCK);

        // ------------------------------------------------------------ High Rock
        fish("glenumbra_herring", 0, w(Water.OCEAN), p("high_rock"), r(), Time.ANY, 2, 15, 35, 3,
                RESTORE_STAMINA, WATERBREATHING, FORTIFY_PICKPOCKET, RESIST_SHOCK);
        fish("bjoulsae_trout", 0, w(Water.RIVER, Water.LAKE), p("high_rock"), r(), Time.ANY, 1, 20, 50, 5,
                RESTORE_HEALTH, FORTIFY_RESTORATION, RESIST_MAGIC, RESTORE_STAMINA);
        fish("wrothgar_char", 1, w(Water.COLD), p("high_rock"), r("wrothgar"), Time.ANY, 1, 25, 60, 10,
                RESIST_FROST, FORTIFY_SMITHING, RESTORE_STAMINA, FORTIFY_HEAVY_ARMOR);

        // ------------------------------------------------------------ beyond Tamriel
        fish("atmoran_icefish", 1, w(Water.COLD, Water.OCEAN), p("atmora"), r(), Time.ANY, 2, 20, 50, 20,
                RESIST_FROST, WEAKNESS_TO_FIRE, FORTIFY_HEALTH, INVISIBILITY);
        fish("akaviri_koi", 1, w(Water.LAKE, Water.RIVER), p("akavir"), r(), Time.DAY, 1, 20, 60, 25,
                FORTIFY_ENCHANTING, RESTORE_MAGICKA, FORTIFY_BARTER, SWIFTNESS);
        fish("tsaesci_eel", 1, w(Water.SWAMP, Water.RIVER), p("akavir"), r("tsaesci"), Time.NIGHT, 1, 60, 140, 20,
                PARALYSIS, LINGERING_DAMAGE_HEALTH, FORTIFY_SNEAK, WATERBREATHING);
        fish("yokudan_sailfin", 1, w(Water.OCEAN), p("yokuda"), r(), Time.ANY, 3, 80, 200, 30,
                SWIFTNESS, FORTIFY_ONE_HANDED, RESTORE_STAMINA, RESIST_FIRE);
        fish("maormer_serpentfish", 2, w(Water.OCEAN), p("pyandonea"), r(), Time.NIGHT, 3, 70, 180, 45,
                PARALYSIS, RESIST_SHOCK, FORTIFY_CONJURATION, WATERBREATHING);
        fish("thras_coralfish", 1, w(Water.OCEAN, Water.WARM), p("thras"), r(), Time.ANY, 2, 10, 30, 25,
                FORTIFY_ALCHEMY, RESTORE_HEALTH, WATERBREATHING, RESIST_POISON);
    }

    private FishSpecies register() {
        BY_ID.put(id, this);
        return this;
    }

    private static void fish(String id, int rarity, Set<Water> waters, Set<String> provinces, Set<String> regions, Time time,
                             int minDepth, int minCm, int maxCm, int value, AlchemyEffect a, AlchemyEffect b, AlchemyEffect c, AlchemyEffect d) {
        new FishSpecies(id, rarity, waters, provinces, regions, time, minDepth, minCm, maxCm, value, new AlchemyEffect[]{a, b, c, d}).register();
    }

    private static Set<Water> w(Water... waters) {
        return Collections.unmodifiableSet(EnumSet.of(waters[0], waters));
    }

    private static Set<String> p(String... provinces) {
        return Set.of(provinces);
    }

    private static Set<String> r(String... regions) {
        return Set.of(regions);
    }

    public static List<FishSpecies> all() {
        return new ArrayList<>(BY_ID.values());
    }

    public static FishSpecies byId(String id) {
        return BY_ID.get(id);
    }

    /** True if this fish lives anywhere in this province or region (no list = everywhere). */
    public boolean livesIn(String province, String region) {
        if (provinces.isEmpty()) return true;
        return provinces.contains(province) || regions.contains(region);
    }

    /** Food value (vanilla nutrition) of a raw fish of this size class. */
    public int nutrition() {
        return maxCm >= 100 ? 4 : maxCm >= 40 ? 3 : 2;
    }

    public float weightKg(int cm) {
        // a fish's weight grows with the cube of its length
        float m = cm / 100f;
        return Math.max(0.05f, 9f * m * m * m);
    }
}
