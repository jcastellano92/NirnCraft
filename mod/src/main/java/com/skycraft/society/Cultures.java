package com.skycraft.society;

import com.skycraft.society.NpcNames.Race;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Who lives where. Each region has a realistic mix of races: Skyrim is mostly Nord with Imperials, Bretons and
 * Redguards in the cities, a Dunmer quarter in Eastmarch, Reachfolk Bretons in the Reach, Argonians at the Rift's
 * docks and Thalmor Altmer at the embassy; the other provinces have their own majority race. Roles override the mix
 * where lore demands it (Thalmor are Altmer, caravan traders Khajiit, Forsworn Reachmen).
 */
public final class Cultures {
    private Cultures() {}

    /** Weights per race, in {@link Race} order. */
    private static int[] w(int nord, int imperial, int breton, int redguard, int altmer, int bosmer, int dunmer, int khajiit,
                           int argonian, int orc) {
        return new int[]{nord, imperial, breton, redguard, altmer, bosmer, dunmer, khajiit, argonian, orc};
    }

    private static final int[] SKYRIM = w(62, 9, 7, 5, 2, 3, 6, 2, 2, 2);
    private static final Map<String, int[]> REGION = Map.ofEntries(
            Map.entry("skyrim/eastmarch", w(56, 6, 4, 3, 1, 2, 20, 1, 6, 1)),
            Map.entry("skyrim/the_reach", w(44, 7, 24, 4, 2, 3, 4, 2, 2, 8)),
            Map.entry("skyrim/haafingar", w(52, 20, 8, 6, 4, 3, 4, 1, 1, 1)),
            Map.entry("skyrim/the_rift", w(56, 8, 5, 4, 2, 7, 6, 2, 8, 2)),
            Map.entry("skyrim/winterhold", w(60, 6, 8, 3, 6, 2, 12, 1, 1, 1)),
            Map.entry("skyrim/the_pale", w(72, 8, 5, 4, 1, 3, 4, 1, 1, 1)),
            Map.entry("skyrim/falkreath", w(66, 10, 6, 5, 1, 6, 3, 1, 1, 1)),
            Map.entry("skyrim/hjaalmarch", w(70, 8, 6, 3, 1, 3, 5, 1, 2, 1)),
            Map.entry("morrowind/solstheim", w(26, 3, 1, 1, 1, 1, 65, 1, 1, 0)));
    private static final Map<String, int[]> PROVINCE = Map.ofEntries(
            Map.entry("skyrim", SKYRIM),
            Map.entry("cyrodiil", w(5, 70, 6, 6, 1, 4, 2, 3, 3, 0)),
            Map.entry("morrowind", w(4, 6, 1, 1, 1, 1, 78, 2, 5, 1)),
            Map.entry("black_marsh", w(0, 4, 0, 1, 0, 0, 4, 3, 88, 0)),
            Map.entry("elsweyr", w(0, 4, 1, 1, 1, 4, 0, 88, 1, 0)),
            Map.entry("valenwood", w(0, 3, 0, 1, 6, 85, 0, 4, 1, 0)),
            Map.entry("summerset", w(0, 3, 0, 0, 90, 5, 1, 1, 0, 0)),
            Map.entry("hammerfell", w(2, 5, 5, 84, 1, 1, 0, 1, 0, 1)),
            Map.entry("high_rock", w(5, 5, 80, 5, 1, 1, 1, 0, 0, 2)),
            Map.entry("atmora", w(100, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
            Map.entry("yokuda", w(0, 0, 0, 100, 0, 0, 0, 0, 0, 0)),
            Map.entry("pyandonea", w(0, 0, 0, 0, 90, 10, 0, 0, 0, 0)),
            Map.entry("thras", w(0, 0, 0, 0, 0, 0, 0, 0, 100, 0)),
            Map.entry("akavir", w(10, 40, 0, 40, 0, 0, 0, 10, 0, 0)),
            Map.entry("realm", w(15, 15, 10, 10, 10, 10, 10, 8, 7, 5)));
    /** The region at a position (overworld map; other dimensions get a neutral region). */
    public static Region regionAt(Level level, BlockPos pos) {
        try {
            return Regions.at(level, pos);
        } catch (RuntimeException e) {
            return Regions.fallback();
        }
    }

    private static int[] weights(@Nullable Region region) {
        if (region == null) return SKYRIM;
        int[] w = REGION.get(region.id());
        if (w != null) return w;
        if ("high_rock/wrothgar".equals(region.id())) return w(3, 3, 12, 2, 0, 0, 0, 0, 0, 80);
        if ("orc".equals(region.culture())) return w(3, 3, 12, 2, 0, 0, 0, 0, 0, 80);
        w = PROVINCE.get(region.province());
        return w != null ? w : SKYRIM;
    }

    /** The majority race of a region. */
    public static Race majority(@Nullable Region region) {
        int[] w = weights(region);
        int best = 0;
        for (int i = 1; i < w.length; i++) if (w[i] > w[best]) best = i;
        return Race.values()[best];
    }

    /** A race for someone with {@code role} living in {@code region}. */
    public static Race raceFor(@Nullable Region region, @Nullable NpcRole role, RandomSource r) {
        if (role != null) {
            switch (role) {
                case THALMOR:
                    return Race.ALTMER;
                case CARAVAN_TRADER:
                    return Race.KHAJIIT;
                case FORSWORN:
                    return Race.BRETON;
                case JARL, STEWARD, HOUSECARL:
                    // rulers come from the land's own people
                    return r.nextInt(6) == 0 ? NpcNames.raceFor(role, r) : majority(region);
                case STORMCLOAK_SOLDIER:
                    return r.nextInt(10) == 0 ? Race.IMPERIAL : Race.NORD;
                case IMPERIAL_SOLDIER, MAGE, ASSASSIN, THIEF, NECROMANCER, VAMPIRE, ADVENTURER, COURIER, THUG:
                    return r.nextBoolean() ? NpcNames.raceFor(role, r) : roll(weights(region), r);
                default:
                    break;
            }
        }
        return roll(weights(region), r);
    }

    private static Race roll(int[] w, RandomSource r) {
        int total = 0;
        for (int x : w) total += x;
        if (total <= 0) return Race.NORD;
        int n = r.nextInt(total);
        for (int i = 0; i < w.length; i++) {
            n -= w[i];
            if (n < 0) return Race.values()[i];
        }
        return Race.NORD;
    }

    /** What someone wears for their role, in their race's and region's fashion. */
    public static Outfit outfitFor(NpcRole role, Race race, RandomSource r) {
        Outfit home = Outfit.cultural(race);
        return switch (role) {
            case FARMER -> r.nextInt(5) < 3 ? Outfit.FARM : home;
            case BLACKSMITH -> Outfit.BLACKSMITH;
            case MERCHANT -> r.nextInt(5) == 0 ? Outfit.FINE : (r.nextInt(4) == 0 ? home : Outfit.MERCHANT);
            case APOTHECARY -> r.nextBoolean() ? Outfit.SCHOLAR : Outfit.MERCHANT;
            case FISHER -> Outfit.FISHER;
            case BUTCHER -> Outfit.INNKEEPER;
            case SCHOLAR -> r.nextBoolean() ? Outfit.SCHOLAR : Outfit.MAGE;
            case STEWARD -> Outfit.STEWARD;
            case CARAVAN_TRADER -> r.nextInt(3) == 0 ? Outfit.KHAJIIT : Outfit.CARAVAN;
            case CHILD -> Outfit.CHILD;
            case CITIZEN -> {
                int roll = r.nextInt(10);
                yield roll < 4 ? home : roll < 7 ? Outfit.FARM : roll < 9 ? Outfit.FINE : Outfit.MERCHANT;
            }
            case HUNTER -> Outfit.HUNTER;
            case MINER -> Outfit.MINER;
            case LUMBERJACK -> Outfit.LUMBERJACK;
            case BARD -> Outfit.BARD;
            case PRIEST -> Outfit.PRIEST;
            case BEGGAR -> Outfit.BEGGAR;
            case MAGE -> race == Race.ALTMER && r.nextBoolean() ? Outfit.ALTMER : Outfit.MAGE;
            case ADVENTURER -> Outfit.ADVENTURER;
            case THALMOR -> Outfit.THALMOR;
            case IMPERIAL_SOLDIER -> Outfit.IMPERIAL_SOLDIER;
            case STORMCLOAK_SOLDIER -> Outfit.STORMCLOAK_SOLDIER;
            case FORSWORN -> Outfit.FORSWORN;
            case VAMPIRE -> Outfit.VAMPIRE;
            case NECROMANCER -> Outfit.NECROMANCER;
            case ASSASSIN -> Outfit.ASSASSIN;
            case THUG -> race == Race.ORC ? Outfit.ORC : Outfit.THUG;
            case COURIER -> Outfit.COURIER;
            case INNKEEPER -> Outfit.INNKEEPER;
            case JARL -> Outfit.JARL;
            case HOUSECARL -> race == Race.ORC ? Outfit.ORC : Outfit.HOUSECARL;
            case THIEF -> Outfit.THIEF;
        };
    }
}
