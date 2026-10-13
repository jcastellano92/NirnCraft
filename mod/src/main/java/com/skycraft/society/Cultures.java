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
 *
 * <p>The other folk live where the lore puts them: Reachmen in the Reach, Ohmes-raht in Elsweyr's south, Imga in
 * Valenwood, a few Lilmothiit and Kothringi in Black Marsh, Sea Elves only in Pyandonea, Atmorans in Atmora and the
 * Tsaesci, Ka Po' Tun and Tang Mo in Akavir. Snow Elves are never part of a mix: one is met only rarely, near the
 * deep places where their people went below (Blackreach's great lifts, Darkfall). The Dwemer and Ayleids are gone.</p>
 */
public final class Cultures {
    private Cultures() {}

    /** Weights per race, in {@link Race} order (the races after the first ten weigh 0 unless {@link #and} adds them). */
    private static int[] w(int nord, int imperial, int breton, int redguard, int altmer, int bosmer, int dunmer, int khajiit,
                           int argonian, int orc) {
        int[] w = new int[Race.VALUES.length];
        int[] first = {nord, imperial, breton, redguard, altmer, bosmer, dunmer, khajiit, argonian, orc};
        System.arraycopy(first, 0, w, 0, first.length);
        return w;
    }

    /** Adds weights for the other folk: {@code and(w(...), Race.REACHMAN, 20, Race.ORC, 5)}. */
    private static int[] and(int[] w, Object... raceWeights) {
        for (int i = 0; i < raceWeights.length; i += 2) w[((Race) raceWeights[i]).ordinal()] = (Integer) raceWeights[i + 1];
        return w;
    }

    private static final int[] SKYRIM = w(62, 9, 7, 5, 2, 3, 6, 2, 2, 2);
    private static final Map<String, int[]> REGION = Map.ofEntries(
            Map.entry("skyrim/eastmarch", w(56, 6, 4, 3, 1, 2, 20, 1, 6, 1)),
            Map.entry("skyrim/the_reach", and(w(44, 7, 8, 4, 2, 3, 4, 2, 2, 8), Race.REACHMAN, 18)),
            Map.entry("skyrim/haafingar", w(52, 20, 8, 6, 4, 3, 4, 1, 1, 1)),
            Map.entry("skyrim/the_rift", w(56, 8, 5, 4, 2, 7, 6, 2, 8, 2)),
            Map.entry("skyrim/winterhold", w(60, 6, 8, 3, 6, 2, 12, 1, 1, 1)),
            Map.entry("skyrim/the_pale", w(72, 8, 5, 4, 1, 3, 4, 1, 1, 1)),
            Map.entry("skyrim/falkreath", w(66, 10, 6, 5, 1, 6, 3, 1, 1, 1)),
            Map.entry("skyrim/hjaalmarch", w(70, 8, 6, 3, 1, 3, 5, 1, 2, 1)),
            Map.entry("morrowind/solstheim", w(26, 3, 1, 1, 1, 1, 65, 1, 1, 0)),
            Map.entry("black_marsh/lilmoth", and(w(0, 4, 0, 1, 0, 0, 3, 3, 80, 0), Race.LILMOTHIIT, 7, Race.KOTHRINGI, 2)),
            Map.entry("black_marsh/murkmire", and(w(0, 2, 0, 0, 0, 0, 2, 2, 90, 0), Race.LILMOTHIIT, 3, Race.KOTHRINGI, 1)),
            Map.entry("elsweyr/pellitine", and(w(0, 4, 1, 1, 1, 3, 0, 72, 1, 0), Race.OHMES_RAHT, 17)),
            Map.entry("elsweyr/senchal", and(w(0, 6, 1, 2, 1, 3, 1, 66, 2, 0), Race.OHMES_RAHT, 18)),
            Map.entry("valenwood/malabal_tor", and(w(0, 2, 0, 1, 4, 82, 0, 3, 1, 0), Race.IMGA, 7)),
            Map.entry("akavir/tsaesci", and(w(0, 3, 0, 0, 0, 0, 0, 0, 0, 0), Race.TSAESCI, 85, Race.KA_PO_TUN, 5, Race.TANG_MO, 7)),
            Map.entry("akavir/ka_po_tun", and(w(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), Race.KA_PO_TUN, 85, Race.TSAESCI, 5, Race.TANG_MO, 10)),
            Map.entry("akavir/tang_mo", and(w(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), Race.TANG_MO, 90, Race.KA_PO_TUN, 10)),
            Map.entry("akavir/kamal", and(w(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), Race.KA_PO_TUN, 50, Race.TSAESCI, 30, Race.TANG_MO, 20)));
    private static final Map<String, int[]> PROVINCE = Map.ofEntries(
            Map.entry("skyrim", SKYRIM),
            Map.entry("cyrodiil", w(5, 70, 6, 6, 1, 4, 2, 3, 3, 0)),
            Map.entry("morrowind", w(4, 6, 1, 1, 1, 1, 78, 2, 5, 1)),
            Map.entry("black_marsh", and(w(0, 4, 0, 1, 0, 0, 4, 3, 86, 0), Race.LILMOTHIIT, 2, Race.KOTHRINGI, 1)),
            Map.entry("elsweyr", and(w(0, 4, 1, 1, 1, 4, 0, 82, 1, 0), Race.OHMES_RAHT, 6)),
            Map.entry("valenwood", and(w(0, 3, 0, 1, 6, 83, 0, 4, 1, 0), Race.IMGA, 3)),
            Map.entry("summerset", w(0, 3, 0, 0, 90, 5, 1, 1, 0, 0)),
            Map.entry("hammerfell", w(2, 5, 5, 84, 1, 1, 0, 1, 0, 1)),
            Map.entry("high_rock", w(5, 5, 80, 5, 1, 1, 1, 0, 0, 2)),
            Map.entry("atmora", and(w(10, 0, 0, 0, 0, 0, 0, 0, 0, 0), Race.ATMORAN, 90)),
            Map.entry("yokuda", w(0, 0, 0, 100, 0, 0, 0, 0, 0, 0)),
            Map.entry("pyandonea", and(w(0, 0, 0, 0, 0, 0, 0, 0, 0, 0), Race.MAORMER, 100)),
            Map.entry("thras", w(0, 0, 0, 0, 0, 0, 0, 0, 100, 0)),
            Map.entry("akavir", and(w(0, 2, 0, 0, 0, 0, 0, 0, 0, 0), Race.TSAESCI, 40, Race.KA_PO_TUN, 33, Race.TANG_MO, 25)),
            Map.entry("realm", and(w(15, 15, 10, 10, 10, 10, 10, 8, 7, 5), Race.REACHMAN, 2, Race.OHMES_RAHT, 2, Race.IMGA, 1,
                    Race.LILMOTHIIT, 1, Race.TSAESCI, 2, Race.KA_PO_TUN, 2, Race.TANG_MO, 2, Race.MAORMER, 1)));
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
        return Race.VALUES[best];
    }

    /**
     * A race for someone with {@code role} living at {@code pos}: {@link #raceFor(Region, NpcRole, RandomSource)}, except
     * that near the old ways down into Blackreach a stranger may be one of the last Snow Elves.
     */
    public static Race raceFor(Level level, BlockPos pos, @Nullable Region region, @Nullable NpcRole role, RandomSource r) {
        if (region != null && "skyrim".equals(region.province()) && (role == null || !role.townsfolk() || role == NpcRole.PRIEST)
                && r.nextInt(4) == 0) {
            for (com.skycraft.tamriel.Place p : com.skycraft.tamriel.Places.overlapping(pos.getX() - 300, pos.getZ() - 300,
                    pos.getX() + 300, pos.getZ() + 300)) {
                if (p.id().startsWith("great_lift") || p.id().equals("darkfall_cave") || p.id().startsWith("blackreach")) {
                    if (p.distSqr(pos.getX(), pos.getZ()) < 300L * 300L) return Race.FALMER;
                }
            }
        }
        Race local = localRace(level, pos, role, r);
        if (local != null) return local;
        return raceFor(region, role, r);
    }

    /**
     * The race of a person who lives in a settlement, by the settlement's own culture (owner, playtest 4: no orcs and
     * Altmer in a Nord village). A hamlet or village is 94% its own people, a town 82%, a city or capital 62%; the
     * rest follow the region's mix, but a village or town takes no far-off peoples (orc, Altmer, Bosmer, Khajiit,
     * Argonian) unless they are the region's own. Travellers, soldiers and the like keep the role rules above; null here.
     */
    @Nullable
    private static Race localRace(Level level, BlockPos pos, @Nullable NpcRole role, RandomSource r) {
        if (role == null || !role.townsfolk() || !(level instanceof net.minecraft.server.level.ServerLevel sl)
                || sl.dimension() != Level.OVERWORLD) {
            return null;
        }
        var entry = com.skycraft.tamriel.settlement.SettlementAnchors.near(sl, pos, 16);
        if (entry.isEmpty() || entry.get().homestead()) return null;
        Race home = entry.get().place().map(p -> raceOfCulture(p.culture())).orElse(null);
        if (home == null) return null;
        if (role == NpcRole.JARL || role == NpcRole.HOUSECARL || role == NpcRole.STEWARD) return home; // rulers: the land's own
        int tier = com.skycraft.tamriel.settlement.SettlementPopulation.tier(entry.get());
        return pickSettled(home, tier, regionAt(level, pos), r);
    }

    /** The mix of a settlement's people: {@code tier} 0 capital/city, 1 town, 2 village (and smaller). */
    static Race pickSettled(Race home, int tier, @Nullable Region region, RandomSource r) {
        int own = tier >= 2 ? 94 : tier == 1 ? 82 : 62;
        if (r.nextInt(100) < own) return home;
        for (int i = 0; i < 6; i++) {
            Race x = roll(weights(region), r);
            if (tier == 0 || !outsider(x, home, region)) return x;
        }
        return home;
    }

    /** A far-off people that a village or town of another culture would not have living in it. */
    private static boolean outsider(Race x, Race home, @Nullable Region region) {
        if (x == home || x == majority(region)) return false;
        return switch (x) {
            case ORC, ALTMER, BOSMER, KHAJIIT, ARGONIAN, DUNMER -> true;
            default -> false;
        };
    }

    /** The people of a place's culture string, or null for the cultures that are not a living people's (ruins, Daedra). */
    @Nullable
    private static Race raceOfCulture(String culture) {
        if (culture == null) return null;
        return switch (culture.toLowerCase(java.util.Locale.ROOT)) {
            case "nord" -> Race.NORD;
            case "imperial" -> Race.IMPERIAL;
            case "breton" -> Race.BRETON;
            case "redguard", "yokudan" -> Race.REDGUARD;
            case "altmer" -> Race.ALTMER;
            case "bosmer" -> Race.BOSMER;
            case "dunmer", "ashlander" -> Race.DUNMER;
            case "khajiit" -> Race.KHAJIIT;
            case "argonian" -> Race.ARGONIAN;
            case "orc" -> Race.ORC;
            case "forsworn", "reachman" -> Race.REACHMAN;
            case "atmoran", "skaal" -> Race.ATMORAN;
            case "akaviri" -> Race.TSAESCI;
            case "maormer" -> Race.MAORMER;
            default -> null;
        };
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
                    return r.nextInt(3) == 0 ? Race.BRETON : Race.REACHMAN;
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
            if (n < 0) return Race.VALUES[i];
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
