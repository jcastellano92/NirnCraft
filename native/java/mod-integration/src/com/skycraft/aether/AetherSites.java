package com.skycraft.aether;

// aether:sandbox-only (compiled against the real mod by sandbox_check.ps1, not by compile_check.ps1's stubs)

import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.PlaceType;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.function.IntBinaryOperator;

/**
 * Far silhouettes of the named wild sites (Places outside the street plan: forts, watchtowers, lighthouses, mage
 * towers, shrines, groves, dragon lairs, the Greybeards' monastery, Labyrinthian, the world wonders and every dungeon
 * entrance) as the mod's builders make them (dungeons/world: SurfaceBuilder, Towers, Sacred, Statues, Uniques,
 * Landmarks, Portals; dimensions transcribed from the builder code). Positions are the Place's, nudged off water the
 * way DungeonStructure does; the base is the ground there + 1. The few seeded choices (most sites' facing) are taken
 * from the place's trail where it has one, else north: every built shape is symmetric or nearly so, which is what reads
 * from afar. Seed-free, so multiplayer clients draw the same.
 */
final class AetherSites {
    private AetherSites() {}

    private static BlockState b(net.minecraft.world.level.block.Block block) {
        return block.defaultBlockState();
    }

    private static final BlockState STONE_BRICKS = b(Blocks.STONE_BRICKS), MOSSY = b(Blocks.MOSSY_STONE_BRICKS),
            DEEPSLATE_TILES = b(Blocks.DEEPSLATE_TILES), CALCITE = b(Blocks.CALCITE), QUARTZ = b(Blocks.SMOOTH_QUARTZ),
            BLACKSTONE = b(Blocks.POLISHED_BLACKSTONE_BRICKS), GILDED = b(Blocks.GILDED_BLACKSTONE), PLANKS = b(Blocks.SPRUCE_PLANKS),
            SPRUCE_LOG = b(Blocks.SPRUCE_LOG), DARK_OAK_LOG = b(Blocks.DARK_OAK_LOG), AZALEA = b(Blocks.AZALEA_LEAVES),
            STONE = b(Blocks.STONE), ANDESITE = b(Blocks.ANDESITE), BASALT = b(Blocks.POLISHED_BASALT), BONE = b(Blocks.BONE_BLOCK),
            COPPER = b(Blocks.CUT_COPPER.waxed().exposed()), WEATHERED = b(Blocks.CUT_COPPER.waxed().weathered()), MUD_BRICKS = b(Blocks.MUD_BRICKS),
            TUFF = b(Blocks.TUFF), AMETHYST = b(Blocks.AMETHYST_BLOCK), GOLD = b(Blocks.GOLD_BLOCK), SNOW = b(Blocks.SNOW_BLOCK),
            WHITE_GLASS = b(Blocks.STAINED_GLASS.white()), DEEPSLATE_BRICKS = b(Blocks.DEEPSLATE_BRICKS),
            POLISHED_DEEPSLATE = b(Blocks.POLISHED_DEEPSLATE), RED_SANDSTONE = b(Blocks.CUT_RED_SANDSTONE),
            SANDSTONE = b(Blocks.SMOOTH_SANDSTONE), LAVA = b(Blocks.LAVA), WHITE_CONCRETE = b(Blocks.CONCRETE.white()),
            COBBLE = b(Blocks.COBBLESTONE), WOOL = b(Blocks.WOOL.white()), BROWN_WOOL = b(Blocks.WOOL.brown()),
            DARK_OAK = b(Blocks.DARK_OAK_PLANKS), JUNGLE_LOG = b(Blocks.JUNGLE_LOG), JUNGLE_LEAVES = b(Blocks.JUNGLE_LEAVES),
            CHERRY_LEAVES = b(Blocks.CHERRY_LEAVES), BIRCH_LOG = b(Blocks.BIRCH_LOG), MANGROVE_LOG = b(Blocks.MANGROVE_LOG),
            YELLOW_TC = b(Blocks.DYED_TERRACOTTA.yellow()), MOSSY_BRICKS = b(Blocks.MOSSY_STONE_BRICKS),
            OBSIDIAN = b(Blocks.OBSIDIAN), PACKED_ICE = b(Blocks.PACKED_ICE), BLUE_ICE = b(Blocks.BLUE_ICE);

    /** Wonders that are actually built (SiteParams.wonderStyle ids whose place a structure starts at). */
    private static final Set<String> BUILT_WONDERS = Set.of("red_mountain", "dagoth_ur", "crystal_tower_summerset",
            "falinesti_autumn_site", "falinesti_winter_site", "falinesti_spring_site", "falinesti_summer_site", "eton_nir",
            "direnni_tower", "isle_of_balfiera", "tall_papas_spire", "two_moons_at_tenmar_temple");

    /** Underground dungeon variants whose surface is an entrance (the variants a structure set builds). */
    private static final Set<String> OUTCROP = Set.of("cave", "den", "smuggler", "bandit_cave", "spider", "sixth_house",
            "ice_cave", "troll", "falmer", "forsworn_cave", "root_hollow", "skeever", "hound_den", "spriggan", "necromancer",
            "cultist", "vampire", "hagraven", "scorpion_den", "mine", "egg_mine", "kolskeggr", "sea_vault", "coral_pit"),
            BARROW = Set.of("barrow", "bleak_falls", "ustengrav", "saarthal", "forelhost", "ysgramor", "breton_crypt",
                    "imperial_crypt", "orc_ruin", "kamal_ice_vault", "atmoran_tomb", "ayleid", "ayleid_ruin", "daedric_ruin",
                    "akaviri_ruin", "moon_temple", "sandstone_tomb", "khajiit_shrine", "yokudan_tomb", "orichalc_ruin");

    /** The silhouette of a place, or null when nothing visible is built there. Pure function of the place. */
    static AetherCapture.Shape shape(Place p, IntBinaryOperator g) {
        String v = p.variant() == null ? "" : p.variant();
        PlaceType t = p.type();
        if (BUILT_WONDERS.contains(p.id())) {
            int[] c = centre(p, g, false);
            return wonder(p.id(), c[0], c[1], c[2]);
        }
        switch (p.id()) {
            case "high_hrothgar" -> {
                int[] c = centre(p, g, false);
                return highHrothgar(c[0], c[1], c[2]);
            }
            case "throat_of_the_world" -> {
                int[] c = centre(p, g, false);
                return throatSummit(c[0], c[1], c[2]);
            }
            case "sky_haven_temple" -> {
                int[] c = centre(p, g, false);
                return skyHaven(c[0], c[1], c[2]);
            }
            case "labyrinthian" -> {
                int[] c = centre(p, g, false);
                return labyrinthian(c[0], c[1], c[2]);
            }
            default -> { }
        }
        if (t == PlaceType.FORT || t == PlaceType.DUNGEON_FIXED && Set.of("fort", "akaviri_fort", "dunmer_stronghold",
                "imperial_prison_fort", "tsaesci_fortress", "breton_keep_ruin").contains(v)) {
            int[] c = centre(p, g, false);
            boolean ruin = v.equals("breton_keep_ruin");
            BlockState wall = switch (v) {
                case "dunmer_stronghold" -> MUD_BRICKS;
                case "akaviri_fort", "tsaesci_fortress" -> BLACKSTONE;
                case "fort_forsworn" -> TUFF;
                default -> ruin ? MOSSY : STONE_BRICKS;
            };
            return fort(c[0], c[1], c[2], wall);
        }
        if (t == PlaceType.DUNGEON_FIXED) {
            int[] c = centre(p, g, v.equals("lighthouse") || v.equals("shipwreck"));
            Direction f = facing(p);
            return switch (v) {
                case "watchtower", "tower" -> tower(c, false, STONE_BRICKS);
                case "nordic_tower" -> tower(c, true, MOSSY_BRICKS);
                case "forsworn_tower" -> tower(c, true, TUFF);
                case "velothi_tower" -> tower(c, false, MUD_BRICKS);
                case "lighthouse" -> lighthouse(c);
                case "mage_tower" -> mageTower(c, false);
                case "altmer_sky_spire" -> mageTower(c, true);
                case "great_lift", "mzark" -> greatLift(c);
                case "dragon_lair" -> dragonLair(c, f);
                case "grove" -> grove(c, f);
                case "nordic_ruin" -> nordicRuin(c, f);
                case "giant_camp" -> giantCamp(c, f);
                case "bandit_camp", "forsworn_camp", "imperial_camp", "stormcloak_camp" -> camp(c, f);
                case "cabin" -> cabin(c, f);
                case "shipwreck" -> shipwreck(c, f);
                case "dwemer_ruin" -> portal(c, f, 7, 3, COPPER);
                case "velothi_tomb" -> portal(c, f, 7, 3, MUD_BRICKS);
                case "xanmeer" -> xanmeer(c, f);
                case "tree_tomb", "bosmer_ossuary" -> hollowTree(c, f);
                case "crystal_vault", "elven_ruin" -> portal(c, f, 13, 3, QUARTZ);
                default -> OUTCROP.contains(v) ? outcrop(c, f) : BARROW.contains(v) ? portal(c, f, 7, 3, STONE_BRICKS) : null;
            };
        }
        if (t == PlaceType.DAEDRIC_SHRINE && !v.equals("altar") && !v.equals("daedric_ruin")) {
            int[] c = centre(p, g, false);
            Direction f = facing(p);
            return v.endsWith("_great") ? greatStatue(c, f, v.startsWith("meridia")) : shrine(c, f);
        }
        if (t == PlaceType.STANDING_STONE) return standingStone(centre(p, g, false));
        if (t == PlaceType.WORD_WALL) return wordWall(centre(p, g, false), facing(p));
        if (t == PlaceType.LANDMARK && (v.equals("monument") || v.equals("rock") || v.equals("eyrie"))) {
            int[] c = centre(p, g, false);
            return v.equals("monument") ? monument(c) : rock(c);
        }
        return null;
    }

    // ---- placement --------------------------------------------------------------------------------------------

    /** {x, z, base y}: the place, off water unless coastal (DungeonStructure.nearestDry), on the ground + 1. */
    static int[] centre(Place p, IntBinaryOperator g, boolean coastal) {
        int x = p.x(), z = p.z();
        if (!coastal && wet(x, z, g)) {
            search:
            for (int d = 8; d <= 56; d += 8)
                for (int a = 0; a < 8; a++) {
                    int dx = (int) Math.round(Math.cos(a * Math.PI / 4)) * d, dz = (int) Math.round(Math.sin(a * Math.PI / 4)) * d;
                    if (!wet(x + dx, z + dz, g)) {
                        x += dx;
                        z += dz;
                        break search;
                    }
                }
        }
        int base = g.applyAsInt(x, z) + 1;
        if (wet(x, z, g)) base = Math.max(base, 63 + (coastal ? -3 : 1));
        return new int[] {x, z, base};
    }

    private static boolean wet(int x, int z, IntBinaryOperator g) {
        if (g.applyAsInt(x, z) < 62) return true;
        MapSample m = TamrielMap.sampleScratch(x, z);
        return m.inWater() && m.waterLevel > g.applyAsInt(x, z);
    }

    /** The trail's direction where the place has one (seed-free), else north. */
    static Direction facing(Place p) {
        com.skycraft.worldplan.TrailEnds.Arrival a = com.skycraft.worldplan.TrailEnds.of(p.id());
        if (a == null || Math.abs(a.x() - p.x()) + Math.abs(a.z() - p.z()) < 4) return Direction.NORTH;
        return Direction.getApproximateNearest(a.x() - p.x(), 0, a.z() - p.z());
    }

    private static int wx(int[] c, Direction f, int fwd, int lat) {
        return AetherCapture.wx(c[0], f, fwd, lat);
    }

    private static int wz(int[] c, Direction f, int fwd, int lat) {
        return AetherCapture.wz(c[1], f, fwd, lat);
    }

    /** A box in the site frame: fwd a..b, lat c..d, up to base + top. */
    private static void box(AetherCapture.Shape s, int[] c, Direction f, int fa, int fb, int la, int lb, float top, BlockState w, BlockState r) {
        s.box(wx(c, f, fa, la), wz(c, f, fa, la), wx(c, f, fb, lb), wz(c, f, fb, lb), c[2] + top, w, r);
    }

    private static void light(AetherCapture.Shape s, int x, int y, int z, int emission) {
        s.lights.add(new int[] {x, y, z, emission});
    }

    // ---- surface sites ---------------------------------------------------------------------------------------

    /** SurfaceBuilder.fort: curtain wall on the square ring 12 out (dy 0..6, merlons 7), 3x3 corner towers (to dy 10, merlons 11), a 9x9 keep (to dy 11, merlons 12); a courtyard fire. */
    static AetherCapture.Shape fort(int x, int z, int base, BlockState wall) {
        AetherCapture.Shape s = AetherCapture.Shape.around(x, z, 13);
        for (int k = -12; k <= 12; k++) {
            s.span(x + k, z - 12, Float.NaN, base + 7.5f, wall, wall);
            s.span(x + k, z + 12, Float.NaN, base + 7.5f, wall, wall);
            s.span(x - 12, z + k, Float.NaN, base + 7.5f, wall, wall);
            s.span(x + 12, z + k, Float.NaN, base + 7.5f, wall, wall);
        }
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2)
                s.box(x + sx * 10, z + sz * 10, x + sx * 12, z + sz * 12, base + 11.5f, wall, STONE_BRICKS);
        s.box(x - 4, z - 4, x + 4, z + 4, base + 12.5f, wall, STONE_BRICKS);
        light(s, x + 7, base, z - 4, 15);
        return s;
    }

    /** Towers.watchtower: 7x7 (octagonal: corners cut) to dy 19, merlons dy 20. */
    static AetherCapture.Shape tower(int[] c, boolean octagon, BlockState wall) {
        AetherCapture.Shape s = AetherCapture.Shape.around(c[0], c[1], 4);
        for (int dz = -3; dz <= 3; dz++)
            for (int dx = -3; dx <= 3; dx++) {
                if (octagon && Math.abs(dx) == 3 && Math.abs(dz) == 3) continue;
                s.span(c[0] + dx, c[1] + dz, Float.NaN, c[2] + 20.5f, wall, wall);
            }
        light(s, c[0], c[2] + 19, c[1], 14);
        return s;
    }

    /** Towers.lighthouse: cylinder r 3.6 to dy 24, cone to dy 29 (capH = (3.6 - d) * 1.2); its lamp. */
    static AetherCapture.Shape lighthouse(int[] c) {
        AetherCapture.Shape s = AetherCapture.Shape.around(c[0], c[1], 5);
        s.profile(c[0] + 0.5, c[1] + 0.5, c[2], 30, dy -> dy <= 24 ? 3.6 : 3.6 - (dy - 24 - 0.5) / 1.2, CALCITE, DEEPSLATE_TILES);
        light(s, c[0], c[2] + 23, c[1], 15);
        return s;
    }

    /** Towers.mage: cylinder r 4.2 to dy 26, cone to dy 32 (capH = (4.2 - d) * 2, crystal * 3.2). */
    static AetherCapture.Shape mageTower(int[] c, boolean crystal) {
        AetherCapture.Shape s = AetherCapture.Shape.around(c[0], c[1], 5);
        double k = crystal ? 3.2 : 2.0;
        s.profile(c[0] + 0.5, c[1] + 0.5, c[2], 33, dy -> dy <= 26 ? 4.2 : 4.2 - (dy - 26 - 0.5) / k,
                crystal ? QUARTZ : BLACKSTONE, crystal ? AMETHYST : GILDED);
        light(s, c[0], c[2] + 28, c[1], 12);
        return s;
    }

    /** Towers.greatLift: copper house +-5 to dy 7, stepped roof to dy 12. */
    static AetherCapture.Shape greatLift(int[] c) {
        AetherCapture.Shape s = AetherCapture.Shape.around(c[0], c[1], 6);
        for (int dz = -5; dz <= 5; dz++)
            for (int dx = -5; dx <= 5; dx++)
                s.span(c[0] + dx, c[1] + dz, Float.NaN, c[2] + 9 + Math.max(0, 4 - Math.max(Math.abs(dx), Math.abs(dz))), COPPER, WEATHERED);
        return s;
    }

    /** SurfaceBuilder.dragonLair: a mound (plateau dy 4 to r 4.95, down to the ground at r 11), the word wall arc. */
    static AetherCapture.Shape dragonLair(int[] c, Direction f) {
        AetherCapture.Shape s = AetherCapture.Shape.around(c[0], c[1], 12);
        s.profile(c[0] + 0.5, c[1] + 0.5, c[2], 5, dy -> 11 - dy * (11 - 4.95) / 4.0, STONE, STONE);
        box(s, c, f, -4, -2, -4, 4, 10, STONE_BRICKS, STONE_BRICKS);
        return s;
    }

    /** dungeons Landmarks.grove: trunk at fwd -1 (r 2.2 / 1.5 / 0.9 to dy 14), an azalea crown r 8.75 x 5 at dy 15, cut at dy 18. */
    static AetherCapture.Shape grove(int[] c, Direction f) {
        AetherCapture.Shape s = AetherCapture.Shape.around(c[0], c[1], 11);
        int tx = wx(c, f, -1, 0), tz = wz(c, f, -1, 0);
        s.ellipsoid(tx + 0.5, c[2] + 15, tz + 0.5, 8.75, 5, 8.75, true, AZALEA, AZALEA);
        for (int k = 0; k < s.top.length; k++) if (s.top[k] > c[2] + 19) s.top[k] = c[2] + 19;
        s.profile(tx + 0.5, tz + 0.5, c[2], 15, dy -> dy <= 2 ? 2.2 : dy <= 11 ? 1.5 : 0.9, DARK_OAK_LOG, DARK_OAK_LOG);
        return s;
    }

    /** dungeons Landmarks.nordicRuin: a broken ring wall fwd +-7 x lat +-5 (to about dy 5), an arch across it at fwd 0 (dy 6). */
    static AetherCapture.Shape nordicRuin(int[] c, Direction f) {
        AetherCapture.Shape s = AetherCapture.Shape.around(c[0], c[1], 9);
        box(s, c, f, -7, 7, -5, -5, 5, MOSSY, MOSSY);
        box(s, c, f, -7, 7, 5, 5, 5, MOSSY, MOSSY);
        box(s, c, f, -7, -7, -5, 5, 5, MOSSY, MOSSY);
        box(s, c, f, 7, 7, -5, 5, 5, MOSSY, MOSSY);
        box(s, c, f, 0, 0, -5, 5, 7, STONE_BRICKS, STONE_BRICKS);
        return s;
    }

    /** SurfaceBuilder.giantCamp: the lean-to (dy 6 at the back to 2 at the front), its totem; the camp fire. */
    static AetherCapture.Shape giantCamp(int[] c, Direction f) {
        AetherCapture.Shape s = AetherCapture.Shape.around(c[0], c[1], 12);
        for (int fwd = -8; fwd <= -3; fwd++) box(s, c, f, fwd, fwd, -5, 5, 7 - (fwd + 8) * 4 / 5, BROWN_WOOL, BROWN_WOOL);
        box(s, c, f, 4, 4, 0, 0, 6, SPRUCE_LOG, SPRUCE_LOG);
        light(s, c[0], c[2], c[1], 15);
        return s;
    }

    /** Camps: tents (dy 0..2) and a lookout (dy 0..5); a camp fire. */
    static AetherCapture.Shape camp(int[] c, Direction f) {
        AetherCapture.Shape s = AetherCapture.Shape.around(c[0], c[1], 12);
        box(s, c, f, -4, -2, 4, 6, 3, WOOL, WOOL);
        box(s, c, f, -4, -2, -6, -4, 3, WOOL, WOOL);
        box(s, c, f, -8, -6, -1, 1, 3, WOOL, WOOL);
        box(s, c, f, 4, 6, -8, -6, 6, SPRUCE_LOG, PLANKS);
        light(s, c[0], c[2], c[1], 15);
        return s;
    }

    /** Camps.cabin: walls fwd +-3, lat +-4 to dy 3; gable roof (eaves dy 4, ridge along fwd at dy 9). */
    static AetherCapture.Shape cabin(int[] c, Direction f) {
        AetherCapture.Shape s = AetherCapture.Shape.around(c[0], c[1], 7);
        for (int lat = -5; lat <= 5; lat++) box(s, c, f, -4, 4, lat, lat, 5 + 5 * (1 - Math.abs(lat) / 5f), PLANKS, SPRUCE_LOG);
        light(s, wx(c, f, 4, 0), c[2] + 2, wz(c, f, 4, 0), 12);
        return s;
    }

    /** dungeons Landmarks.shipwreck: hull fwd +-11 (half beam 3 sqrt(1 - |fwd / 10|^3)) to dy 3; mast at fwd 1 to dy 8. */
    static AetherCapture.Shape shipwreck(int[] c, Direction f) {
        AetherCapture.Shape s = AetherCapture.Shape.around(c[0], c[1], 12);
        for (int fwd = -11; fwd <= 11; fwd++) {
            double q = 1 - Math.pow(Math.min(1, Math.abs(fwd / 10.0)), 3);
            int hb = (int) Math.round(3 * Math.sqrt(Math.max(0, q)));
            box(s, c, f, fwd, fwd, -hb, hb, 4, DARK_OAK, DARK_OAK);
        }
        box(s, c, f, 1, 1, 0, 0, 9, DARK_OAK_LOG, WOOL);
        return s;
    }

    /** Statues: the prince on his pedestal (fwd -4..-2, lat -2..1, to about dy 12) and four braziers. */
    static AetherCapture.Shape shrine(int[] c, Direction f) {
        AetherCapture.Shape s = AetherCapture.Shape.around(c[0], c[1], 10);
        box(s, c, f, -5, -1, -2, 2, 1, BLACKSTONE, BLACKSTONE);
        box(s, c, f, -4, -2, -2, 1, 12, BLACKSTONE, GILDED);
        for (int[] q : new int[][] {{3, 5}, {3, -5}, {-5, 4}, {-5, -4}}) {
            box(s, c, f, q[0], q[0], q[1], q[1], 2, BLACKSTONE, BLACKSTONE);
            light(s, wx(c, f, q[0], q[1]), c[2] + 2, wz(c, f, q[0], q[1]), 15);
        }
        return s;
    }

    /** Statues giant (Azura, Meridia): pedestal, robe, torso, head, raised arms and the moon and star / the sword. */
    static AetherCapture.Shape greatStatue(int[] c, Direction f, boolean meridia) {
        AetherCapture.Shape s = AetherCapture.Shape.around(c[0], c[1], 22);
        BlockState body = meridia ? WHITE_CONCRETE : QUARTZ, trim = meridia ? GOLD : CALCITE;
        box(s, c, f, -11, -1, -5, 5, 4, STONE_BRICKS, STONE_BRICKS);
        box(s, c, f, -9, -4, -5, 4, 19, body, body);
        box(s, c, f, -8, -5, -4, 3, 28, body, body);
        box(s, c, f, -7, -6, -1, 1, 34, body, trim);
        box(s, c, f, -7, -6, -7, -5, 39, body, body);
        box(s, c, f, -7, -6, 4, 6, 39, body, body);
        if (meridia) {
            box(s, c, f, -5, -4, 5, 5, 49, GOLD, GOLD);
            light(s, wx(c, f, -6, 0), c[2] + 42, wz(c, f, -6, 0), 15);
        } else {
            box(s, c, f, -7, -6, -9, -5, 45, CALCITE, CALCITE);
            box(s, c, f, -7, -6, 4, 7, 43, CALCITE, CALCITE);
        }
        for (int[] q : new int[][] {{7, 12}, {7, -12}, {-12, 8}, {-12, -8}}) light(s, wx(c, f, q[0], q[1]), c[2] + 2, wz(c, f, q[0], q[1]), 15);
        return s;
    }

    /** Sacred.standingStone: the sign stone on its platform (to about dy 3-6). */
    static AetherCapture.Shape standingStone(int[] c) {
        AetherCapture.Shape s = AetherCapture.Shape.around(c[0], c[1], 7);
        s.box(c[0], c[1], c[0], c[1], c[2] + 4, STONE, STONE);
        s.ring(c[0] + 0.5, c[1] + 0.5, 3.6, 4.6, c[2] + 2, MOSSY, MOSSY);
        return s;
    }

    /** Sacred.wordWall: the curved wall fwd -4..-2, lat -4..4, dy 4 at the centre. */
    static AetherCapture.Shape wordWall(int[] c, Direction f) {
        AetherCapture.Shape s = AetherCapture.Shape.around(c[0], c[1], 7);
        for (int lat = -4; lat <= 4; lat++) box(s, c, f, -4, -2, lat, lat, 5 - Math.abs(lat) / 2f, STONE_BRICKS, STONE_BRICKS);
        return s;
    }

    /** dungeons Landmarks: a monument's stele (dy 3..9) on its plinth; braziers. */
    static AetherCapture.Shape monument(int[] c) {
        AetherCapture.Shape s = AetherCapture.Shape.around(c[0], c[1], 5);
        s.box(c[0] - 2, c[1] - 2, c[0] + 2, c[1] + 2, c[2] + 2, STONE_BRICKS, STONE_BRICKS);
        s.box(c[0], c[1], c[0], c[1], c[2] + 10, BASALT, BASALT);
        light(s, c[0] + 4, c[2] + 1, c[1] + 4, 15);
        light(s, c[0] - 4, c[2] + 1, c[1] - 4, 15);
        return s;
    }

    /** dungeons Landmarks rock / eyrie: a cone r 6.4, h = 9 - 1.4 d. */
    static AetherCapture.Shape rock(int[] c) {
        AetherCapture.Shape s = AetherCapture.Shape.around(c[0], c[1], 7);
        s.profile(c[0] + 0.5, c[1] + 0.5, c[2], 10, dy -> (9 - dy) / 1.4, STONE, ANDESITE);
        return s;
    }

    // ---- entrances (Portals) ---------------------------------------------------------------------------------

    /** The outcrop over a cave or mine mouth: ellipse 3.6 x 5.8 around fwd -0.5, peak dy 6-7. */
    static AetherCapture.Shape outcrop(int[] c, Direction f) {
        AetherCapture.Shape s = AetherCapture.Shape.around(c[0], c[1], 7);
        for (int fwd = -4; fwd <= 2; fwd++)
            for (int lat = -6; lat <= 6; lat++) {
                double u = (fwd + 0.5) / 3.6, v = lat / 5.8, d = Math.sqrt(u * u + v * v);
                if (d < 1) s.span(wx(c, f, fwd, lat), wz(c, f, fwd, lat), Float.NaN, c[2] + (float) Math.round((1 - d) * 6), STONE, STONE);
            }
        return s;
    }

    /** A built entrance (barrow arch, Dwemer gatehouse, tomb house, crystal pillars): its mound and facade. */
    static AetherCapture.Shape portal(int[] c, Direction f, int top, int half, BlockState facade) {
        AetherCapture.Shape s = outcrop(c, f);
        box(s, c, f, 1, 1, -half, half, top, facade, facade);
        light(s, wx(c, f, 2, 0), c[2] + 2, wz(c, f, 2, 0), 12);
        return s;
    }

    /** Portals xanmeer: a stepped pyramid centred fwd -4, square +-7, top dy 6. */
    static AetherCapture.Shape xanmeer(int[] c, Direction f) {
        AetherCapture.Shape s = AetherCapture.Shape.around(c[0], c[1], 12);
        for (int fwd = -11; fwd <= 3; fwd++)
            for (int lat = -7; lat <= 7; lat++) {
                int cheb = Math.max(Math.abs(fwd + 4), Math.abs(lat));
                int dy = cheb <= 1 ? 6 : cheb <= 3 ? 4 : cheb <= 5 ? 2 : 0;
                s.span(wx(c, f, fwd, lat), wz(c, f, fwd, lat), Float.NaN, c[2] + dy + 1, MOSSY, MOSSY);
            }
        return s;
    }

    /** Portals bosmer: a hollow tree (r 4.6 / 3.6 to dy 16) with a canopy r 6 at dy 14..18. */
    static AetherCapture.Shape hollowTree(int[] c, Direction f) {
        AetherCapture.Shape s = AetherCapture.Shape.around(c[0], c[1], 8);
        int tx = wx(c, f, -1, 0), tz = wz(c, f, -1, 0);
        s.ellipsoid(tx + 0.5, c[2] + 16, tz + 0.5, 6, 2.5, 6, true, JUNGLE_LEAVES, JUNGLE_LEAVES);
        s.profile(tx + 0.5, tz + 0.5, c[2], 17, dy -> dy <= 1 ? 4.6 : 3.6, JUNGLE_LOG, JUNGLE_LOG);
        return s;
    }

    // ---- uniques -----------------------------------------------------------------------------------------------

    /** Uniques.highHrothgar (faces east): the great hall with its gable roof (ridge dy 28), the tower to dy 38, two wings. */
    static AetherCapture.Shape highHrothgar(int x, int z, int base) {
        int[] c = {x, z, base};
        Direction f = Direction.EAST;
        AetherCapture.Shape s = AetherCapture.Shape.around(x, z, 37);
        for (int lat = -15; lat <= 15; lat++) box(s, c, f, -21, 12, lat, lat, 14 + 15 * (1 - Math.abs(lat) / 15f), STONE_BRICKS, DEEPSLATE_TILES);
        for (int sgn = -1; sgn <= 1; sgn += 2)
            for (int lat = 15; lat <= 23; lat++)
                box(s, c, f, -16, 4, sgn * lat, sgn * lat, 9 + 5 * (1 - Math.abs(lat - 19) / 4f), STONE_BRICKS, DEEPSLATE_TILES);
        for (int fwd = -8; fwd <= 0; fwd++) box(s, c, f, fwd, fwd, -4, 4, 33 + 6 * (1 - Math.abs(fwd + 4) / 4.5f), STONE_BRICKS, DEEPSLATE_TILES);
        light(s, wx(c, f, 13, 0), base + 2, wz(c, f, 13, 0), 15);
        return s;
    }

    /** Uniques.throatSummit: the broken ring wall r 9, the word wall, the crag cone at (fwd -10, lat 4). */
    static AetherCapture.Shape throatSummit(int x, int z, int base) {
        int[] c = {x, z, base};
        Direction f = Direction.NORTH;
        AetherCapture.Shape s = AetherCapture.Shape.around(x, z, 14);
        s.ring(x + 0.5, z + 0.5, 8.5, 9.5, base + 4, STONE, SNOW);
        box(s, c, f, -4, -3, -3, 3, 5, STONE, SNOW);
        s.profile(wx(c, f, -10, 4) + 0.5, wz(c, f, -10, 4) + 0.5, base, 12, dy -> (12 - dy) / 2.5, STONE, SNOW);
        return s;
    }

    /** Uniques.skyHaven (faces east): the rock mass fwd -26..12, lat +-24, dy 24 in the middle sloping to its edges. */
    static AetherCapture.Shape skyHaven(int x, int z, int base) {
        int[] c = {x, z, base};
        Direction f = Direction.EAST;
        AetherCapture.Shape s = AetherCapture.Shape.around(x, z, 27);
        for (int fwd = -26; fwd <= 12; fwd++)
            for (int lat = -24; lat <= 24; lat++)
                s.span(wx(c, f, fwd, lat), wz(c, f, fwd, lat), Float.NaN,
                        base + 25 - 2 * Math.max(0, Math.abs(lat) - 16) - Math.max(0, fwd - 6), STONE, STONE);
        return s;
    }

    /** Uniques.labyrinthian (faces north): the ring wall r 50..52 (about dy 6), round towers to about dy 12. */
    static AetherCapture.Shape labyrinthian(int x, int z, int base) {
        int[] c = {x, z, base};
        Direction f = Direction.NORTH;
        AetherCapture.Shape s = AetherCapture.Shape.around(x, z, 53);
        s.ring(x + 0.5, z + 0.5, 50, 52, base + 6, MOSSY, MOSSY);
        for (int[] q : new int[][] {{38, -30}, {-38, 32}, {0, -42}, {12, 40}})
            s.profile(wx(c, f, q[0], q[1]) + 0.5, wz(c, f, q[0], q[1]) + 0.5, base, 12, dy -> 3.6, MOSSY, MOSSY);
        return s;
    }

    // ---- world wonders (dungeons Landmarks; rotationally symmetric) -----------------------------------------------

    static AetherCapture.Shape wonder(String id, int x, int z, int base) {
        double cx = x + 0.5, cz = z + 0.5;
        AetherCapture.Shape s;
        switch (id) {
            case "red_mountain", "dagoth_ur" -> {
                // rim annulus r 22..30 rising to about dy 13, a lava moat r 11..16.5, the stepped citadel (r 10.5 to
                // dy 20, r 8 to 36, r 5.5 to 54, core to 58) and eight pylons on r 25 to dy 24
                s = AetherCapture.Shape.around(x, z, 31);
                for (int r = 22; r <= 30; r++) s.ring(cx, cz, r, r + 1, base + Math.round((r - 22) * 1.6f) + 1, BASALT, BLACKSTONE);
                s.ring(cx, cz, 11, 16.5, base, LAVA, LAVA);
                s.profile(cx, cz, base, 59, dy -> dy < 20 ? 10.5 : dy < 36 ? 8 : dy < 54 ? 5.5 : 0.7, BLACKSTONE, COPPER);
                for (int k = 0; k < 8; k++) {
                    int px = x + (int) Math.round(25 * Math.cos(k * Math.PI / 4)), pz = z + (int) Math.round(25 * Math.sin(k * Math.PI / 4));
                    s.box(px - 1, pz, px + 1, pz, base + 25, BONE, COPPER);
                    s.box(px, pz - 1, px, pz + 1, base + 25, BONE, COPPER);
                }
                for (int k = 0; k < 8; k++) light(s, x + (int) Math.round(13.5 * Math.cos(k * Math.PI / 4)), base - 1,
                        z + (int) Math.round(13.5 * Math.sin(k * Math.PI / 4)), 15);
                light(s, x, base + 58, z, 14);
            }
            case "crystal_tower_summerset" -> {
                s = AetherCapture.Shape.around(x, z, 31);
                s.profile(cx, cz, base, 5, dy -> dy < 1 ? 30 : dy < 3 ? 21.5 : 13.5, QUARTZ, QUARTZ);
                s.profile(cx, cz, base, 142, dy -> dy < 5 ? 0 : dy > 136 ? 1.0
                        : 11.5 * Math.pow(Math.max(0, 1 - (dy - 4) / 132.0), 0.72) + 1.2, CALCITE, WHITE_GLASS);
                for (int k = 0; k < 8; k++) {
                    int px = x + (int) Math.round(25 * Math.cos(k * Math.PI / 4)), pz = z + (int) Math.round(25 * Math.sin(k * Math.PI / 4));
                    s.box(px, pz, px, pz, base + 13, QUARTZ, QUARTZ);
                }
                light(s, x, base + 140, z, 15);
            }
            case "falinesti_autumn_site", "falinesti_winter_site", "falinesti_spring_site", "falinesti_summer_site" -> {
                BlockState wood = id.contains("winter") ? BIRCH_LOG : id.contains("spring") ? MANGROVE_LOG
                        : id.contains("summer") ? JUNGLE_LOG : DARK_OAK_LOG;
                BlockState crown = id.contains("winter") ? SNOW : id.contains("spring") ? CHERRY_LEAVES
                        : id.contains("summer") ? JUNGLE_LEAVES : YELLOW_TC;
                s = AetherCapture.Shape.around(x, z, 30);
                s.ellipsoid(cx, base + 54, cz, 28.3, 14.4, 28.3, true, crown, crown);
                s.ellipsoid(cx, base + 66, cz, 19.4, 10.7, 19.4, true, crown, crown);
                s.profile(cx, cz, base, 57, dy -> (dy < 18 ? 11 - 0.22 * dy : 7 - 0.06 * (dy - 18)) * 1.19, wood, wood);
            }
            case "eton_nir" -> {
                s = AetherCapture.Shape.around(x, z, 24);
                s.profile(cx, cz, base, 97, dy -> 9.5 * Math.pow(1 - dy / 100.0, 0.75) + 1, QUARTZ, GOLD);
                for (int[] q : new int[][] {{18, 0}, {-18, 0}, {0, 18}, {0, -18}})
                    for (int dz = -5; dz <= 5; dz++)
                        for (int dx = -5; dx <= 5; dx++)
                            if (dx * dx + dz * dz <= 27) s.span(x + q[0] + dx, z + q[1] + dz, base + 27, base + 30, QUARTZ, QUARTZ);
            }
            case "direnni_tower", "isle_of_balfiera" -> {
                s = AetherCapture.Shape.around(x, z, 25);
                s.ring(cx, cz, 21.9, 24.1, base + 11, DEEPSLATE_BRICKS, DEEPSLATE_BRICKS);
                s.profile(cx, cz, base, 141, dy -> dy < 90 ? 10.5 - 0.03 * dy : 7.8 - 0.11 * (dy - 90), POLISHED_DEEPSLATE, POLISHED_DEEPSLATE);
            }
            case "tall_papas_spire" -> {
                s = AetherCapture.Shape.around(x, z, 29);
                s.profile(cx, cz, base, 87, dy -> dy < 1 ? 28 : dy < 4 ? 18.5 : dy < 7 ? 11.5 : 9 * (1 - dy / 105.0), RED_SANDSTONE, RED_SANDSTONE);
            }
            case "two_moons_at_tenmar_temple" -> {
                s = AetherCapture.Shape.around(x, z, 29);
                s.profile(cx, cz, base, 7, dy -> dy < 1 ? 28 : dy < 4 ? 19.5 : 11.5, SANDSTONE, SANDSTONE);
                s.ring(cx, cz, 10.5, 11.5, base + 32, QUARTZ, QUARTZ);
                s.profile(cx, cz, base + 32, 53, dy -> 11 * Math.pow(Math.max(0, 1 - dy / 56.0), 0.7) + 0.8, GOLD, GOLD);
            }
            default -> {
                return null;
            }
        }
        return s;
    }
}
