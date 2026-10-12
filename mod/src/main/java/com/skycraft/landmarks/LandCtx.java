package com.skycraft.landmarks;

import com.skycraft.dungeons.world.Cultures;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.TamrielMap;

import java.util.ArrayList;
import java.util.List;

/**
 * What the map says about a spot, boiled down to a base family (tundra, marsh, desert, ash...) and a few overlays
 * (coast, shore, mountains, high ground, cold). Landmark kinds are weighted by family, so each biome family gets its
 * own set. Map-only: it is the same on the worldgen thread, in the commands and in the offline audit.
 */
public final class LandCtx {
    public final MapSample s = new MapSample();
    public Region region;
    public String province;
    public String archetype = "";
    /** One of tundra, forest, plains, marsh, desert, savanna, ash, volcanic, jungle, crystal, mushroom, mountain. */
    public String base = "forest";
    public boolean coast, shore, high, mountains, cold;

    public static LandCtx of(int x, int z) {
        LandCtx c = new LandCtx();
        TamrielMap.sample(x, z, c.s);
        c.region = TamrielMap.region(x, z);
        c.province = Cultures.province(c.region, x, z);
        String pal = c.region.palette() == null ? "" : c.region.palette();
        c.archetype = pal.startsWith("realm_") ? pal.substring(6) : "";
        MapSample s = c.s;
        c.coast = s.coastDistance >= 3 && s.coastDistance <= 90 && s.elevation < TamrielMap.SEA_LEVEL + 20;
        boolean hasWater = !Float.isNaN(s.waterLevel);
        c.shore = hasWater && ((s.lakeDistance >= -50 && s.lakeDistance <= -3)
                || (s.riverHalfWidth >= 2 && s.riverDistance >= s.riverHalfWidth + 3 && s.riverDistance <= s.riverHalfWidth + 50));
        MapSample p = new MapSample();
        double sum = 0;
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3;
            TamrielMap.sample(x + (int) (Math.cos(a) * 80), z + (int) (Math.sin(a) * 80), p);
            sum += p.elevation;
        }
        float prominence = (float) (s.elevation - sum / 6);
        c.high = prominence > 10 || s.ridge > 0.45f;
        c.mountains = s.crag > 0.3f || s.ridge > 0.3f || s.elevation > 130;
        c.base = baseFamily(c);
        String id = c.region.id();
        c.cold = c.base.equals("tundra") || s.elevation > 170 || id.equals("skyrim/the_pale") || id.equals("skyrim/winterhold")
                || id.equals("skyrim/eastmarch") || id.equals("morrowind/solstheim") || id.equals("akavir/kamal")
                || id.equals("cyrodiil/jerall_mountains") || c.province.equals("atmora")
                || c.archetype.equals("frozen") || c.archetype.equals("alpine");
        return c;
    }

    static String baseFamily(LandCtx c) {
        String id = c.region.id();
        String prov = c.province;
        String f = switch (id) {
            case "skyrim/the_pale", "skyrim/winterhold", "skyrim/haafingar", "morrowind/solstheim", "akavir/kamal" -> "tundra";
            case "skyrim/hjaalmarch", "morrowind/bitter_coast" -> "marsh";
            case "skyrim/eastmarch", "morrowind/red_mountain", "high_rock/wrothgar" -> "volcanic";
            case "skyrim/the_reach", "high_rock/rivenspire", "hammerfell/dragontail", "cyrodiil/jerall_mountains" -> "mountain";
            case "skyrim/whiterun", "cyrodiil/heartlands", "morrowind/grazelands", "cyrodiil/gold_coast" -> "plains";
            case "hammerfell/alikr_desert", "hammerfell/hews_bane", "hammerfell/stros_mkai" -> "desert";
            case "morrowind/telvannis" -> "mushroom";
            case "morrowind/vvardenfell_ashlands", "morrowind/molag_amur", "morrowind/west_gash", "morrowind/stonefalls",
                 "morrowind/deshaan", "morrowind/sheogorad", "morrowind/azuras_coast" -> "ash";
            case "morrowind/ascadian_isles", "cyrodiil/blackwood" -> "jungle";
            default -> null;
        };
        if (f != null) return f;
        f = switch (prov) {
            case "atmora" -> "tundra";
            case "black_marsh" -> "marsh";
            case "elsweyr" -> "savanna";
            case "valenwood" -> "jungle";
            case "summerset" -> "crystal";
            case "yokuda" -> "desert";
            case "hammerfell" -> "plains";
            case "morrowind" -> "ash";
            case "pyandonea" -> "jungle";
            case "thras" -> "marsh";
            case "realm" -> switch (c.archetype) {
                case "frozen", "alpine" -> "tundra";
                case "swamp" -> "marsh";
                case "desert" -> "desert";
                case "savanna" -> "savanna";
                case "ash" -> "ash";
                case "jungle" -> "jungle";
                case "crystal" -> "crystal";
                case "mushroom" -> "mushroom";
                default -> "forest";
            };
            default -> null;
        };
        if (f != null) return f;
        // skyrim and the rest: by the land itself
        if (c.s.wetland > 0.55f) return "marsh";
        if (c.s.dunes > 0.5f) return "desert";
        if (c.s.elevation > 175 && c.mountains) return "mountain";
        return "forest";
    }

    /** The families that apply here, base first (used to weight kinds and to pick rock and wood). */
    public List<String> families() {
        List<String> out = new ArrayList<>(4);
        out.add(base);
        if (coast) out.add("coast");
        if (shore) out.add("shore");
        if (mountains && !base.equals("mountain")) out.add("mountain");
        if (cold && !base.equals("tundra")) out.add("tundra");
        return out;
    }
}
