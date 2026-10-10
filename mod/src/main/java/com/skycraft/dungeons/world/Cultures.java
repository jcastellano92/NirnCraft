package com.skycraft.dungeons.world;

import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.TamrielMap;

import java.util.List;
import java.util.Locale;

/**
 * Which culture's dungeons belong where. Generic sites (caves, forts, camps) dress in the kit of the region they
 * stand in; culture ruins list the provinces they appear in ({@code "where"} in the structure JSON).
 *
 * <p>Skyrim's own sites are all named places now, so random dungeons skip the province entirely (its wilds still
 * get bandit camps and shrines from features). Generated realms beyond the known world take any culture: their
 * kit follows the region's culture when it names one, and a stable hash of the region otherwise.</p>
 */
public final class Cultures {
    /**
     * Test hook: {@code -Dskycraft.dungeons.province=cyrodiil} makes every position report that province, so a flat
     * test world can show one culture's dungeons. Never set in play.
     */
    private static final String FORCED = System.getProperty("skycraft.dungeons.province");

    private Cultures() {}

    /** The province of the region at (x, z), with the map stub's answer outside Tamriel read as the endless realms. */
    public static String province(Region region, int x, int z) {
        if (FORCED != null) return FORCED;
        String p = region.province();
        if ("skyrim".equals(p) && !TamrielMap.inTamriel(x, z)) return "realm";
        if ("sea".equals(region.kind())) return "sea";
        return p == null ? "realm" : p.toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a random site listed for {@code where} may start in this region. An empty list means anywhere outside
     * Skyrim; {@code "*"} adds Skyrim too; a list names provinces (and {@code realm} for the generated lands).
     */
    public static boolean allows(List<String> where, Region region, int x, int z) {
        String prov = province(region, x, z);
        if ("sea".equals(prov)) return false;
        if ("morrowind".equals(prov) && "morrowind/solstheim".equals(region.id())) return false;
        if (where.isEmpty()) return !"skyrim".equals(prov);
        for (String w : where) {
            if (w.equals("*")) return true;
            if (w.equals(prov)) return true;
            if (w.startsWith("!") && w.substring(1).equals(prov)) return false;
        }
        return false;
    }

    /** The building kit for generic sites in this region. */
    public static Kit kitFor(Region region, int x, int z) {
        Kit byCulture = FORCED != null ? null : byCulture(region.culture());
        if (byCulture != null) return byCulture;
        return switch (province(region, x, z)) {
            case "skyrim" -> Kit.NORD;
            case "cyrodiil" -> Kit.IMPERIAL;
            case "morrowind" -> Kit.VELOTHI;
            case "black_marsh" -> Kit.XANMEER;
            case "elsweyr" -> Kit.KHAJIIT;
            case "valenwood" -> Kit.BOSMER;
            case "summerset" -> Kit.ALTMER;
            case "hammerfell", "yokuda" -> Kit.REDGUARD;
            case "high_rock" -> Kit.BRETON;
            case "atmora" -> Kit.ATMORAN;
            case "akavir" -> Kit.AKAVIRI;
            case "pyandonea" -> Kit.MAORMER;
            case "thras" -> Kit.SLOAD;
            default -> realmKit(region);
        };
    }

    private static Kit byCulture(String culture) {
        if (culture == null) return null;
        return switch (culture.toLowerCase(Locale.ROOT)) {
            case "imperial", "colovian", "nibenese" -> Kit.IMPERIAL;
            case "dunmer", "velothi", "hlaalu", "redoran", "telvanni" -> Kit.VELOTHI;
            case "argonian" -> Kit.XANMEER;
            case "khajiit" -> Kit.KHAJIIT;
            case "bosmer" -> Kit.BOSMER;
            case "altmer" -> Kit.ALTMER;
            case "redguard", "yokudan" -> Kit.REDGUARD;
            case "breton" -> Kit.BRETON;
            case "orc", "orsimer" -> Kit.ORC;
            case "akaviri", "tsaesci", "kamal", "tang_mo", "ka_po_tun" -> Kit.AKAVIRI;
            case "atmoran" -> Kit.ATMORAN;
            case "maormer" -> Kit.MAORMER;
            case "sload" -> Kit.SLOAD;
            case "reachmen", "forsworn" -> Kit.FORSWORN;
            default -> null; // "nord" and unknown cultures fall through to the province
        };
    }

    private static final Kit[] REALM_KITS = {Kit.NORD, Kit.IMPERIAL, Kit.AYLEID, Kit.VELOTHI, Kit.DAEDRIC, Kit.XANMEER,
            Kit.KHAJIIT, Kit.BOSMER, Kit.ALTMER, Kit.REDGUARD, Kit.BRETON, Kit.ORC, Kit.AKAVIRI, Kit.ATMORAN, Kit.MAORMER};

    private static Kit realmKit(Region region) {
        return REALM_KITS[Math.floorMod(region.id().hashCode() * 31 + 7, REALM_KITS.length)];
    }
}
