package com.skycraft.tamriel.settlement.kit;

import com.skycraft.tamriel.settlement.Culture;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.EnumMap;
import java.util.Map;

/**
 * Culture kits: the block palettes (and building shapes) of every culture and style. Building archetypes are shared
 * ({@link com.skycraft.tamriel.settlement.build.Builders}); a kit decides what they are made of and how they are
 * shaped, so a Nord inn and a Redguard inn differ at a glance.
 *
 * <p>Styles: Nord 0 village, 1 Whiterun, 2 Windhelm, 3 Markarth, 4 Riften, 5 Solitude, 6 Winterhold, 7 marsh
 * (Morthal), 8 forest log cabins (Falkreath), 9 stone (Helgen, Dawnstar). Dunmer 0 Hlaalu, 1 Redoran, 2 Telvanni,
 * 3 Velothi. Imperial 1 is the Imperial City's marble. Other cultures have one style.</p>
 */
public final class Kits {
    public static final int NORD_VILLAGE = 0, WHITERUN = 1, WINDHELM = 2, MARKARTH = 3, RIFTEN = 4, SOLITUDE = 5,
            WINTERHOLD = 6, MARSH = 7, FOREST = 8, STONE = 9;
    public static final int HLAALU = 0, REDORAN = 1, TELVANNI = 2, VELOTHI = 3;
    public static final int IMPERIAL_CITY = 1;
    public static final int KIT_MASK = 0xFF, OVERLAY_SHIFT = 8;

    private static final Map<Culture, Palette[]> CACHE = new EnumMap<>(Culture.class);

    private Kits() {}

    /**
     * The palette of a culture and style. {@code style} packs the kit style (low 8 bits) and, above them, an optional
     * per-place overlay ({@link com.skycraft.tamriel.settlement.grammar.Overlays}) that re-tints the kit.
     */
    public static Palette palette(Culture c, int style) {
        Palette base = kit(c, style < 0 ? style : style & KIT_MASK);
        int overlay = style < 0 ? 0 : style >>> OVERLAY_SHIFT;
        return overlay == 0 ? base : com.skycraft.tamriel.settlement.grammar.Overlays.palette(base, c, overlay);
    }

    private static synchronized Palette kit(Culture c, int style) {
        Palette[] styles = CACHE.computeIfAbsent(c, Kits::build);
        return styles[Math.floorMod(style, styles.length)];
    }

    private static String[] kitNames(Culture c) {
        return switch (c) {
            case NORD -> new String[]{"nord", "whiterun", "windhelm", "markarth", "riften", "solitude", "winterhold", "marsh", "forest", "stone"};
            case IMPERIAL -> new String[]{"imperial", "imperial_city"};
            case DUNMER -> new String[]{"hlaalu", "redoran", "telvanni", "velothi"};
            default -> new String[]{c.id()};
        };
    }

    /** The name of the kit palette for a culture and style index (Bootstrap-safe for Tier-0 plan tests). */
    public static String kitName(Culture c, int style) {
        String[] names = kitNames(c);
        return names[Math.floorMod(style < 0 ? style : style & KIT_MASK, names.length)];
    }

    /** The kit style index of the palette called {@code name} for a culture, or {@code fallback}. */
    public static int styleOf(Culture c, String name, int fallback) {
        String[] names = kitNames(c);
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) return i;
        }
        return fallback;
    }

    /** A packed style: kit style plus overlay index. */
    public static int pack(int kit, int overlay) {
        return (kit & KIT_MASK) | (overlay << OVERLAY_SHIFT);
    }

    private static Palette[] build(Culture c) {
        return switch (c) {
            case NORD -> nord();
            case IMPERIAL -> imperial();
            case DUNMER -> dunmer();
            case ARGONIAN -> new Palette[]{argonian()};
            case KHAJIIT -> new Palette[]{khajiit()};
            case BOSMER -> new Palette[]{bosmer()};
            case ALTMER -> new Palette[]{altmer()};
            case REDGUARD -> new Palette[]{redguard()};
            case BRETON -> new Palette[]{breton()};
            case ORC -> new Palette[]{orc()};
            case AKAVIRI -> new Palette[]{akaviri()};
            case ATMORAN -> new Palette[]{atmoran()};
            case YOKUDAN -> new Palette[]{yokudan()};
            case MAORMER -> new Palette[]{maormer()};
        };
    }

    private static net.minecraft.world.level.block.state.BlockState s(Block b) {
        return b.defaultBlockState();
    }

    // ------------------------------------------------------------------ Nord

    private static Palette[] nord() {
        Palette base = new Palette();
        base.name = "nord";
        base.horns = true;
        base.thatch = true;
        base.accent = s(com.skycraft.worldgen.VanillaBlocks.BLUE_WOOL);
        base.bed = s(com.skycraft.worldgen.VanillaBlocks.BROWN_BED);
        base.carpet = s(com.skycraft.worldgen.VanillaBlocks.BROWN_CARPET);
        base.carpet2 = s(com.skycraft.worldgen.VanillaBlocks.WHITE_CARPET);

        Palette whiterun = base.copy();
        whiterun.name = "whiterun";
        whiterun.wallLow = s(Blocks.STONE);
        whiterun.foundation = s(Blocks.STONE);
        whiterun.wall = s(Blocks.SPRUCE_PLANKS);
        whiterun.wall2 = s(Blocks.STRIPPED_SPRUCE_WOOD);
        whiterun.roof = s(Blocks.DARK_OAK_STAIRS);
        whiterun.roofSlab = s(Blocks.DARK_OAK_SLAB);
        whiterun.roofFull = s(Blocks.DARK_OAK_PLANKS);
        whiterun.path = s(Blocks.DIRT_PATH);
        whiterun.path2 = s(Blocks.GRAVEL);
        whiterun.plaza = s(Blocks.STONE_BRICKS);
        whiterun.plaza2 = s(Blocks.ANDESITE);
        whiterun.cityWall = s(Blocks.STONE_BRICKS);
        whiterun.cityWall2 = s(Blocks.MOSSY_STONE_BRICKS);
        whiterun.accent = s(com.skycraft.worldgen.VanillaBlocks.YELLOW_WOOL);
        whiterun.thatch = false;

        Palette windhelm = base.copy();
        windhelm.name = "windhelm";
        windhelm.foundation = s(Blocks.COBBLED_DEEPSLATE);
        windhelm.wallLow = s(Blocks.COBBLED_DEEPSLATE);
        windhelm.wall = s(Blocks.DEEPSLATE_BRICKS);
        windhelm.wall2 = s(Blocks.CRACKED_DEEPSLATE_BRICKS);
        windhelm.post = s(Blocks.DARK_OAK_LOG);
        windhelm.beam = s(Blocks.STRIPPED_DARK_OAK_LOG);
        windhelm.roof = s(Blocks.DEEPSLATE_TILE_STAIRS);
        windhelm.roofSlab = s(Blocks.DEEPSLATE_TILE_SLAB);
        windhelm.roofFull = s(Blocks.DEEPSLATE_TILES);
        windhelm.trim = s(Blocks.DEEPSLATE_BRICK_STAIRS);
        windhelm.trimSlab = s(Blocks.DEEPSLATE_BRICK_SLAB);
        windhelm.floor = s(Blocks.DARK_OAK_PLANKS);
        windhelm.door = s(Blocks.DARK_OAK_DOOR);
        windhelm.window = s(Blocks.IRON_BARS);
        windhelm.sign = s(Blocks.DARK_OAK_WALL_SIGN);
        windhelm.path = s(Blocks.COBBLED_DEEPSLATE);
        windhelm.path2 = s(Blocks.GRAVEL);
        windhelm.plaza = s(Blocks.POLISHED_DEEPSLATE);
        windhelm.plaza2 = s(Blocks.DEEPSLATE_TILES);
        windhelm.cityWall = s(Blocks.DEEPSLATE_BRICKS);
        windhelm.cityWall2 = s(Blocks.CRACKED_DEEPSLATE_BRICKS);
        windhelm.cityWallStairs = s(Blocks.DEEPSLATE_BRICK_STAIRS);
        windhelm.cityWallSlab = s(Blocks.DEEPSLATE_BRICK_SLAB);
        windhelm.cityWallWall = s(Blocks.DEEPSLATE_BRICK_WALL);
        windhelm.column = s(Blocks.POLISHED_DEEPSLATE);
        windhelm.timber = false;
        windhelm.thatch = false;
        windhelm.pitch = 2;

        Palette markarth = base.copy();
        markarth.name = "markarth";
        markarth.foundation = s(Blocks.SANDSTONE);
        markarth.wallLow = s(Blocks.SMOOTH_SANDSTONE);
        markarth.wall = s(Blocks.CUT_SANDSTONE);
        markarth.wall2 = s(Blocks.CHISELED_SANDSTONE);
        markarth.post = s(com.skycraft.worldgen.VanillaBlocks.WAXED_OXIDIZED_CUT_COPPER);
        markarth.beam = s(com.skycraft.worldgen.VanillaBlocks.WAXED_CUT_COPPER);
        markarth.roof = s(Blocks.SMOOTH_SANDSTONE_STAIRS);
        markarth.roofSlab = s(Blocks.SMOOTH_SANDSTONE_SLAB);
        markarth.roofFull = s(Blocks.SMOOTH_SANDSTONE);
        markarth.trim = s(Blocks.SANDSTONE_STAIRS);
        markarth.trimSlab = s(Blocks.SANDSTONE_SLAB);
        markarth.floor = s(Blocks.SMOOTH_SANDSTONE);
        markarth.window = s(Blocks.IRON_BARS);
        markarth.path = s(Blocks.SMOOTH_SANDSTONE);
        markarth.path2 = s(Blocks.SANDSTONE);
        markarth.plaza = s(Blocks.CUT_SANDSTONE);
        markarth.plaza2 = s(Blocks.SMOOTH_SANDSTONE);
        markarth.cityWall = s(Blocks.CUT_SANDSTONE);
        markarth.cityWall2 = s(Blocks.SANDSTONE);
        markarth.cityWallStairs = s(Blocks.SANDSTONE_STAIRS);
        markarth.cityWallSlab = s(Blocks.SANDSTONE_SLAB);
        markarth.cityWallWall = s(Blocks.SANDSTONE_WALL);
        markarth.column = s(com.skycraft.worldgen.VanillaBlocks.WAXED_OXIDIZED_CUT_COPPER);
        markarth.accent = s(com.skycraft.worldgen.VanillaBlocks.RED_WOOL);
        markarth.shell = Shell.FLAT;
        markarth.hallShell = Shell.FLAT;
        markarth.timber = false;
        markarth.thatch = false;
        markarth.horns = false;

        Palette riften = base.copy();
        riften.name = "riften";
        riften.wallLow = s(Blocks.MOSSY_COBBLESTONE);
        riften.foundation = s(Blocks.MOSSY_COBBLESTONE);
        riften.wall = s(Blocks.SPRUCE_PLANKS);
        riften.wall2 = s(Blocks.STRIPPED_DARK_OAK_WOOD);
        riften.post = s(Blocks.DARK_OAK_LOG);
        riften.beam = s(Blocks.STRIPPED_DARK_OAK_LOG);
        riften.roof = s(Blocks.DARK_OAK_STAIRS);
        riften.roofSlab = s(Blocks.DARK_OAK_SLAB);
        riften.roofFull = s(Blocks.DARK_OAK_PLANKS);
        riften.floor = s(Blocks.DARK_OAK_PLANKS);
        riften.path = s(Blocks.SPRUCE_PLANKS);
        riften.path2 = s(Blocks.MOSSY_COBBLESTONE);
        riften.plaza = s(Blocks.MOSSY_COBBLESTONE);
        riften.plaza2 = s(Blocks.COBBLESTONE);
        riften.cityWall = s(Blocks.MOSSY_STONE_BRICKS);
        riften.cityWall2 = s(Blocks.STONE_BRICKS);
        riften.cityWallStairs = s(Blocks.MOSSY_STONE_BRICK_STAIRS);
        riften.cityWallSlab = s(Blocks.MOSSY_STONE_BRICK_SLAB);
        riften.cityWallWall = s(Blocks.MOSSY_STONE_BRICK_WALL);
        riften.accent = s(com.skycraft.worldgen.VanillaBlocks.PURPLE_WOOL);
        riften.stilts = false;
        riften.thatch = false;

        Palette solitude = base.copy();
        solitude.name = "solitude";
        solitude.wallLow = s(Blocks.STONE_BRICKS);
        solitude.foundation = s(Blocks.STONE_BRICKS);
        solitude.wall = s(Blocks.CALCITE);
        solitude.wall2 = s(Blocks.CALCITE);
        solitude.post = s(Blocks.DARK_OAK_LOG);
        solitude.beam = s(Blocks.STRIPPED_DARK_OAK_LOG);
        solitude.roof = s(Blocks.DEEPSLATE_TILE_STAIRS);
        solitude.roofSlab = s(Blocks.DEEPSLATE_TILE_SLAB);
        solitude.roofFull = s(Blocks.DEEPSLATE_TILES);
        solitude.trim = s(Blocks.STONE_BRICK_STAIRS);
        solitude.trimSlab = s(Blocks.STONE_BRICK_SLAB);
        solitude.window = s(Blocks.GLASS_PANE);
        solitude.door = s(Blocks.DARK_OAK_DOOR);
        solitude.sign = s(Blocks.DARK_OAK_WALL_SIGN);
        solitude.floor = s(Blocks.DARK_OAK_PLANKS);
        solitude.path = s(Blocks.POLISHED_ANDESITE);
        solitude.path2 = s(Blocks.ANDESITE);
        solitude.plaza = s(Blocks.STONE_BRICKS);
        solitude.plaza2 = s(Blocks.POLISHED_ANDESITE);
        solitude.accent = s(com.skycraft.worldgen.VanillaBlocks.RED_WOOL);
        solitude.carpet = s(com.skycraft.worldgen.VanillaBlocks.RED_CARPET);
        solitude.bed = s(com.skycraft.worldgen.VanillaBlocks.RED_BED);
        solitude.pitch = 2;
        solitude.thatch = false;
        solitude.horns = false;

        Palette winterhold = base.copy();
        winterhold.name = "winterhold";
        winterhold.wall = s(Blocks.COBBLESTONE);
        winterhold.wall2 = s(Blocks.STONE);
        winterhold.wallLow = s(Blocks.MOSSY_COBBLESTONE);
        winterhold.path = s(Blocks.GRAVEL);
        winterhold.path2 = s(Blocks.COBBLESTONE);
        winterhold.pitch = 2;
        winterhold.thatch = false;

        Palette marsh = base.copy();
        marsh.name = "marsh";
        marsh.wall = s(Blocks.DARK_OAK_PLANKS);
        marsh.wall2 = s(Blocks.SPRUCE_PLANKS);
        marsh.post = s(Blocks.DARK_OAK_LOG);
        marsh.beam = s(Blocks.STRIPPED_DARK_OAK_LOG);
        marsh.wallLow = s(Blocks.DARK_OAK_PLANKS);
        marsh.floor = s(Blocks.SPRUCE_PLANKS);
        marsh.path = s(Blocks.SPRUCE_PLANKS);
        marsh.path2 = s(Blocks.MUD);
        marsh.plaza = s(Blocks.PACKED_MUD);
        marsh.plaza2 = s(Blocks.MUD_BRICKS);
        marsh.stilts = true;
        marsh.accent = s(com.skycraft.worldgen.VanillaBlocks.GREEN_WOOL);

        Palette forest = base.copy();
        forest.name = "forest";
        forest.wall = s(Blocks.SPRUCE_LOG);
        forest.wall2 = s(Blocks.SPRUCE_LOG);
        forest.wallLow = s(Blocks.COBBLESTONE);
        forest.timber = false;
        forest.path = s(Blocks.DIRT_PATH);
        forest.path2 = s(Blocks.GRAVEL);
        forest.accent = s(com.skycraft.worldgen.VanillaBlocks.GREEN_WOOL);

        Palette stone = base.copy();
        stone.name = "stone";
        stone.wall = s(Blocks.COBBLESTONE);
        stone.wall2 = s(Blocks.STONE_BRICKS);
        stone.wallLow = s(Blocks.STONE_BRICKS);
        stone.path = s(Blocks.GRAVEL);
        stone.path2 = s(Blocks.COBBLESTONE);
        stone.cityWall = s(Blocks.COBBLESTONE);
        stone.cityWall2 = s(Blocks.MOSSY_COBBLESTONE);
        stone.cityWallStairs = s(Blocks.COBBLESTONE_STAIRS);
        stone.cityWallSlab = s(Blocks.COBBLESTONE_SLAB);
        stone.cityWallWall = s(Blocks.COBBLESTONE_WALL);
        stone.thatch = false;
        stone.accent = s(com.skycraft.worldgen.VanillaBlocks.RED_WOOL);

        return new Palette[]{base, whiterun, windhelm, markarth, riften, solitude, winterhold, marsh, forest, stone};
    }

    // ------------------------------------------------------------------ Imperial

    private static Palette[] imperial() {
        Palette p = new Palette();
        p.name = "imperial";
        p.foundation = s(Blocks.STONE_BRICKS);
        p.floor = s(Blocks.OAK_PLANKS);
        p.floor2 = s(Blocks.POLISHED_ANDESITE);
        p.wallLow = s(Blocks.STONE_BRICKS);
        p.wall = s(Blocks.POLISHED_DIORITE);
        p.wall2 = s(Blocks.DIORITE);
        p.post = s(Blocks.QUARTZ_PILLAR);
        p.beam = s(Blocks.STRIPPED_OAK_LOG);
        p.roof = s(Blocks.BRICK_STAIRS);
        p.roofSlab = s(Blocks.BRICK_SLAB);
        p.roofFull = s(Blocks.BRICKS);
        p.trim = s(Blocks.STONE_BRICK_STAIRS);
        p.trimSlab = s(Blocks.STONE_BRICK_SLAB);
        p.window = s(Blocks.GLASS_PANE);
        p.door = s(Blocks.OAK_DOOR);
        p.fence = s(Blocks.OAK_FENCE);
        p.gate = s(Blocks.OAK_FENCE_GATE);
        p.trapdoor = s(Blocks.OAK_TRAPDOOR);
        p.sign = s(Blocks.OAK_WALL_SIGN);
        p.planks = s(Blocks.OAK_PLANKS);
        p.slab = s(Blocks.OAK_SLAB);
        p.stairs = s(Blocks.OAK_STAIRS);
        p.carpet = s(com.skycraft.worldgen.VanillaBlocks.RED_CARPET);
        p.carpet2 = s(com.skycraft.worldgen.VanillaBlocks.YELLOW_CARPET);
        p.accent = s(com.skycraft.worldgen.VanillaBlocks.RED_WOOL);
        p.bed = s(com.skycraft.worldgen.VanillaBlocks.RED_BED);
        p.path = s(Blocks.COBBLESTONE);
        p.path2 = s(Blocks.STONE_BRICKS);
        p.plaza = s(Blocks.STONE_BRICKS);
        p.plaza2 = s(Blocks.POLISHED_ANDESITE);
        p.column = s(Blocks.QUARTZ_PILLAR);
        p.shell = Shell.HIP;
        p.hallShell = Shell.HIP;
        p.timber = false;
        p.thatch = false;

        Palette ic = p.copy();
        ic.name = "imperial_city";
        ic.wall = s(Blocks.SMOOTH_QUARTZ);
        ic.wall2 = s(Blocks.QUARTZ_BRICKS);
        ic.wallLow = s(Blocks.CHISELED_STONE_BRICKS);
        ic.cityWall = s(Blocks.QUARTZ_BRICKS);
        ic.cityWall2 = s(Blocks.CALCITE);
        ic.cityWallStairs = s(Blocks.QUARTZ_STAIRS);
        ic.cityWallSlab = s(Blocks.QUARTZ_SLAB);
        ic.cityWallWall = s(Blocks.DIORITE_WALL);
        ic.path = s(Blocks.POLISHED_DIORITE);
        ic.path2 = s(Blocks.STONE_BRICKS);
        ic.plaza = s(Blocks.QUARTZ_BRICKS);
        ic.plaza2 = s(Blocks.POLISHED_DIORITE);
        ic.glass = s(com.skycraft.worldgen.VanillaBlocks.WHITE_STAINED_GLASS);
        return new Palette[]{p, ic};
    }

    // ------------------------------------------------------------------ Dunmer

    private static Palette[] dunmer() {
        Palette hlaalu = new Palette();
        hlaalu.name = "hlaalu";
        hlaalu.foundation = s(Blocks.SANDSTONE);
        hlaalu.floor = s(Blocks.JUNGLE_PLANKS);
        hlaalu.wallLow = s(Blocks.CUT_SANDSTONE);
        hlaalu.wall = s(Blocks.SMOOTH_SANDSTONE);
        hlaalu.wall2 = s(Blocks.SANDSTONE);
        hlaalu.post = s(Blocks.STRIPPED_JUNGLE_LOG);
        hlaalu.beam = s(Blocks.STRIPPED_JUNGLE_LOG);
        hlaalu.roof = s(Blocks.SMOOTH_SANDSTONE_STAIRS);
        hlaalu.roofSlab = s(Blocks.SMOOTH_SANDSTONE_SLAB);
        hlaalu.roofFull = s(Blocks.SMOOTH_SANDSTONE);
        hlaalu.trim = s(Blocks.SANDSTONE_STAIRS);
        hlaalu.trimSlab = s(Blocks.SANDSTONE_SLAB);
        hlaalu.window = s(Blocks.JUNGLE_FENCE);
        hlaalu.door = s(Blocks.JUNGLE_DOOR);
        hlaalu.fence = s(Blocks.JUNGLE_FENCE);
        hlaalu.gate = s(Blocks.JUNGLE_FENCE_GATE);
        hlaalu.trapdoor = s(Blocks.JUNGLE_TRAPDOOR);
        hlaalu.sign = s(Blocks.JUNGLE_WALL_SIGN);
        hlaalu.planks = s(Blocks.JUNGLE_PLANKS);
        hlaalu.slab = s(Blocks.JUNGLE_SLAB);
        hlaalu.stairs = s(Blocks.JUNGLE_STAIRS);
        hlaalu.carpet = s(com.skycraft.worldgen.VanillaBlocks.ORANGE_CARPET);
        hlaalu.carpet2 = s(com.skycraft.worldgen.VanillaBlocks.BROWN_CARPET);
        hlaalu.accent = s(com.skycraft.worldgen.VanillaBlocks.ORANGE_WOOL);
        hlaalu.bed = s(com.skycraft.worldgen.VanillaBlocks.ORANGE_BED);
        hlaalu.path = s(Blocks.PACKED_MUD);
        hlaalu.path2 = s(Blocks.COARSE_DIRT);
        hlaalu.plaza = s(Blocks.CUT_SANDSTONE);
        hlaalu.plaza2 = s(Blocks.SMOOTH_SANDSTONE);
        hlaalu.cityWall = s(Blocks.SANDSTONE);
        hlaalu.cityWall2 = s(Blocks.CUT_SANDSTONE);
        hlaalu.cityWallStairs = s(Blocks.SANDSTONE_STAIRS);
        hlaalu.cityWallSlab = s(Blocks.SANDSTONE_SLAB);
        hlaalu.cityWallWall = s(Blocks.SANDSTONE_WALL);
        hlaalu.column = s(Blocks.STRIPPED_JUNGLE_LOG);
        hlaalu.shell = Shell.FLAT;
        hlaalu.hallShell = Shell.DOME;
        hlaalu.timber = true;

        Palette redoran = hlaalu.copy();
        redoran.name = "redoran";
        redoran.foundation = s(Blocks.MUD_BRICKS);
        redoran.wallLow = s(Blocks.MUD_BRICKS);
        redoran.wall = s(Blocks.PACKED_MUD);
        redoran.wall2 = s(Blocks.MUD_BRICKS);
        redoran.roof = s(Blocks.MUD_BRICK_STAIRS);
        redoran.roofSlab = s(Blocks.MUD_BRICK_SLAB);
        redoran.roofFull = s(Blocks.TERRACOTTA);
        redoran.post = s(Blocks.STRIPPED_DARK_OAK_LOG);
        redoran.beam = s(Blocks.STRIPPED_DARK_OAK_LOG);
        redoran.trim = s(Blocks.MUD_BRICK_STAIRS);
        redoran.trimSlab = s(Blocks.MUD_BRICK_SLAB);
        redoran.floor = s(Blocks.DARK_OAK_PLANKS);
        redoran.door = s(Blocks.DARK_OAK_DOOR);
        redoran.sign = s(Blocks.DARK_OAK_WALL_SIGN);
        redoran.window = s(Blocks.DARK_OAK_FENCE);
        redoran.accent = s(com.skycraft.worldgen.VanillaBlocks.RED_WOOL);
        redoran.carpet = s(com.skycraft.worldgen.VanillaBlocks.RED_CARPET);
        redoran.bed = s(com.skycraft.worldgen.VanillaBlocks.RED_BED);
        redoran.path = s(Blocks.COARSE_DIRT);
        redoran.path2 = s(Blocks.GRAVEL);
        redoran.plaza = s(Blocks.MUD_BRICKS);
        redoran.plaza2 = s(Blocks.PACKED_MUD);
        redoran.cityWall = s(Blocks.POLISHED_ANDESITE);
        redoran.cityWall2 = s(Blocks.ANDESITE);
        redoran.cityWallStairs = s(Blocks.POLISHED_ANDESITE_STAIRS);
        redoran.cityWallSlab = s(Blocks.POLISHED_ANDESITE_SLAB);
        redoran.cityWallWall = s(Blocks.ANDESITE_WALL);
        redoran.column = s(Blocks.STRIPPED_DARK_OAK_LOG);
        redoran.shell = Shell.CHITIN;
        redoran.hallShell = Shell.CHITIN;
        redoran.timber = false;

        Palette telvanni = hlaalu.copy();
        telvanni.name = "telvanni";
        telvanni.foundation = s(Blocks.PACKED_MUD);
        telvanni.wallLow = s(Blocks.MUSHROOM_STEM);
        telvanni.wall = s(Blocks.MUSHROOM_STEM);
        telvanni.wall2 = s(Blocks.MUSHROOM_STEM);
        telvanni.roofFull = s(Blocks.RED_MUSHROOM_BLOCK);
        telvanni.roof = s(Blocks.MANGROVE_STAIRS);
        telvanni.roofSlab = s(Blocks.MANGROVE_SLAB);
        telvanni.post = s(Blocks.MUSHROOM_STEM);
        telvanni.beam = s(Blocks.STRIPPED_MANGROVE_LOG);
        telvanni.floor = s(Blocks.MANGROVE_PLANKS);
        telvanni.door = s(Blocks.MANGROVE_DOOR);
        telvanni.sign = s(Blocks.MANGROVE_WALL_SIGN);
        telvanni.window = s(com.skycraft.worldgen.VanillaBlocks.PURPLE_STAINED_GLASS_PANE);
        telvanni.glass = s(com.skycraft.worldgen.VanillaBlocks.PURPLE_STAINED_GLASS);
        telvanni.light = s(Blocks.SHROOMLIGHT);
        telvanni.accent = s(com.skycraft.worldgen.VanillaBlocks.PURPLE_WOOL);
        telvanni.carpet = s(com.skycraft.worldgen.VanillaBlocks.PURPLE_CARPET);
        telvanni.bed = s(com.skycraft.worldgen.VanillaBlocks.PURPLE_BED);
        telvanni.path = s(Blocks.PACKED_MUD);
        telvanni.path2 = s(Blocks.MUD);
        telvanni.plaza = s(Blocks.MUD_BRICKS);
        telvanni.column = s(Blocks.MUSHROOM_STEM);
        telvanni.shell = Shell.MUSHROOM;
        telvanni.hallShell = Shell.MUSHROOM;
        telvanni.towerShell = Shell.MUSHROOM;
        telvanni.timber = false;

        Palette velothi = hlaalu.copy();
        velothi.name = "velothi";
        velothi.foundation = s(Blocks.TUFF);
        velothi.wallLow = s(Blocks.TUFF);
        velothi.wall = s(Blocks.POLISHED_ANDESITE);
        velothi.wall2 = s(Blocks.ANDESITE);
        velothi.roof = s(Blocks.POLISHED_ANDESITE_STAIRS);
        velothi.roofSlab = s(Blocks.POLISHED_ANDESITE_SLAB);
        velothi.roofFull = s(Blocks.SMOOTH_STONE);
        velothi.trim = s(Blocks.STONE_BRICK_STAIRS);
        velothi.trimSlab = s(Blocks.STONE_BRICK_SLAB);
        velothi.post = s(Blocks.POLISHED_BASALT);
        velothi.beam = s(Blocks.STRIPPED_DARK_OAK_LOG);
        velothi.floor = s(Blocks.DARK_OAK_PLANKS);
        velothi.path = s(Blocks.TUFF);
        velothi.path2 = s(Blocks.GRAVEL);
        velothi.plaza = s(Blocks.POLISHED_ANDESITE);
        velothi.plaza2 = s(Blocks.STONE_BRICKS);
        velothi.cityWall = s(Blocks.STONE_BRICKS);
        velothi.column = s(Blocks.POLISHED_BASALT);
        velothi.accent = s(com.skycraft.worldgen.VanillaBlocks.RED_WOOL);
        velothi.carpet = s(com.skycraft.worldgen.VanillaBlocks.RED_CARPET);
        velothi.timber = false;
        velothi.shell = Shell.FLAT;
        velothi.hallShell = Shell.DOME;
        return new Palette[]{hlaalu, redoran, telvanni, velothi};
    }

    // ------------------------------------------------------------------ the other cultures

    private static Palette argonian() {
        Palette p = new Palette();
        p.name = "argonian";
        p.foundation = s(Blocks.MOSSY_STONE_BRICKS);
        p.floor = s(Blocks.MANGROVE_PLANKS);
        p.wallLow = s(Blocks.MANGROVE_PLANKS);
        p.wall = s(Blocks.BAMBOO_PLANKS);
        p.wall2 = s(Blocks.BAMBOO_MOSAIC);
        p.post = s(Blocks.MANGROVE_LOG);
        p.beam = s(Blocks.STRIPPED_MANGROVE_LOG);
        p.roof = s(Blocks.BAMBOO_MOSAIC_STAIRS);
        p.roofSlab = s(Blocks.BAMBOO_MOSAIC_SLAB);
        p.roofFull = s(Blocks.HAY_BLOCK);
        p.trim = s(Blocks.MOSSY_STONE_BRICK_STAIRS);
        p.trimSlab = s(Blocks.MOSSY_STONE_BRICK_SLAB);
        p.window = s(Blocks.MANGROVE_FENCE);
        p.door = s(Blocks.MANGROVE_DOOR);
        p.fence = s(Blocks.MANGROVE_FENCE);
        p.gate = s(Blocks.MANGROVE_FENCE_GATE);
        p.trapdoor = s(Blocks.MANGROVE_TRAPDOOR);
        p.sign = s(Blocks.MANGROVE_WALL_SIGN);
        p.planks = s(Blocks.MANGROVE_PLANKS);
        p.slab = s(Blocks.MANGROVE_SLAB);
        p.stairs = s(Blocks.MANGROVE_STAIRS);
        p.light = s(Blocks.VERDANT_FROGLIGHT);
        p.carpet = s(com.skycraft.worldgen.VanillaBlocks.GREEN_CARPET);
        p.carpet2 = s(Blocks.MOSS_CARPET);
        p.accent = s(com.skycraft.worldgen.VanillaBlocks.GREEN_WOOL);
        p.bed = s(com.skycraft.worldgen.VanillaBlocks.GREEN_BED);
        p.path = s(Blocks.PACKED_MUD);
        p.path2 = s(Blocks.MUD);
        p.plaza = s(Blocks.MOSSY_STONE_BRICKS);
        p.plaza2 = s(Blocks.MOSSY_COBBLESTONE);
        p.cityWall = s(Blocks.MOSSY_STONE_BRICKS);
        p.cityWall2 = s(Blocks.CRACKED_STONE_BRICKS);
        p.cityWallStairs = s(Blocks.MOSSY_STONE_BRICK_STAIRS);
        p.cityWallSlab = s(Blocks.MOSSY_STONE_BRICK_SLAB);
        p.cityWallWall = s(Blocks.MOSSY_STONE_BRICK_WALL);
        p.column = s(Blocks.MANGROVE_LOG);
        p.shell = Shell.ROUND;
        p.hallShell = Shell.ROUND;
        p.stilts = true;
        p.timber = false;
        p.thatch = true;
        return p;
    }

    private static Palette khajiit() {
        Palette p = new Palette();
        p.name = "khajiit";
        p.foundation = s(Blocks.SANDSTONE);
        p.floor = s(Blocks.ACACIA_PLANKS);
        p.wallLow = s(Blocks.CUT_SANDSTONE);
        p.wall = s(Blocks.SANDSTONE);
        p.wall2 = s(Blocks.SMOOTH_SANDSTONE);
        p.post = s(Blocks.STRIPPED_ACACIA_LOG);
        p.beam = s(Blocks.STRIPPED_ACACIA_LOG);
        p.roof = s(Blocks.SMOOTH_SANDSTONE_STAIRS);
        p.roofSlab = s(Blocks.SMOOTH_SANDSTONE_SLAB);
        p.roofFull = s(com.skycraft.worldgen.VanillaBlocks.ORANGE_TERRACOTTA);
        p.trim = s(Blocks.SANDSTONE_STAIRS);
        p.trimSlab = s(Blocks.SANDSTONE_SLAB);
        p.window = s(Blocks.ACACIA_FENCE);
        p.door = s(Blocks.ACACIA_DOOR);
        p.fence = s(Blocks.ACACIA_FENCE);
        p.gate = s(Blocks.ACACIA_FENCE_GATE);
        p.trapdoor = s(Blocks.ACACIA_TRAPDOOR);
        p.sign = s(Blocks.ACACIA_WALL_SIGN);
        p.planks = s(Blocks.ACACIA_PLANKS);
        p.slab = s(Blocks.ACACIA_SLAB);
        p.stairs = s(Blocks.ACACIA_STAIRS);
        p.carpet = s(com.skycraft.worldgen.VanillaBlocks.ORANGE_CARPET);
        p.carpet2 = s(com.skycraft.worldgen.VanillaBlocks.RED_CARPET);
        p.accent = s(com.skycraft.worldgen.VanillaBlocks.ORANGE_WOOL);
        p.bed = s(com.skycraft.worldgen.VanillaBlocks.ORANGE_BED);
        p.path = s(Blocks.SMOOTH_SANDSTONE);
        p.path2 = s(Blocks.SANDSTONE);
        p.plaza = s(Blocks.CUT_SANDSTONE);
        p.plaza2 = s(Blocks.CHISELED_SANDSTONE);
        p.cityWall = s(Blocks.SANDSTONE);
        p.cityWall2 = s(Blocks.CUT_SANDSTONE);
        p.cityWallStairs = s(Blocks.SANDSTONE_STAIRS);
        p.cityWallSlab = s(Blocks.SANDSTONE_SLAB);
        p.cityWallWall = s(Blocks.SANDSTONE_WALL);
        p.column = s(Blocks.CHISELED_SANDSTONE);
        p.shell = Shell.DOME;
        p.hallShell = Shell.DOME;
        p.timber = false;
        return p;
    }

    private static Palette bosmer() {
        Palette p = new Palette();
        p.name = "bosmer";
        p.foundation = s(Blocks.MOSSY_COBBLESTONE);
        p.floor = s(Blocks.JUNGLE_PLANKS);
        p.wallLow = s(com.skycraft.worldgen.VanillaBlocks.BROWN_TERRACOTTA);
        p.wall = s(com.skycraft.worldgen.VanillaBlocks.BROWN_WOOL);
        p.wall2 = s(com.skycraft.worldgen.VanillaBlocks.BROWN_TERRACOTTA);
        p.post = s(Blocks.BONE_BLOCK);
        p.beam = s(Blocks.JUNGLE_LOG);
        p.roof = s(Blocks.MANGROVE_STAIRS);
        p.roofSlab = s(Blocks.MANGROVE_SLAB);
        p.roofFull = s(Blocks.JUNGLE_LEAVES).setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
        p.trim = s(Blocks.JUNGLE_STAIRS);
        p.trimSlab = s(Blocks.JUNGLE_SLAB);
        p.window = s(Blocks.JUNGLE_FENCE);
        p.door = s(Blocks.JUNGLE_DOOR);
        p.fence = s(Blocks.JUNGLE_FENCE);
        p.gate = s(Blocks.JUNGLE_FENCE_GATE);
        p.trapdoor = s(Blocks.JUNGLE_TRAPDOOR);
        p.sign = s(Blocks.JUNGLE_WALL_SIGN);
        p.planks = s(Blocks.JUNGLE_PLANKS);
        p.slab = s(Blocks.JUNGLE_SLAB);
        p.stairs = s(Blocks.JUNGLE_STAIRS);
        p.carpet = s(com.skycraft.worldgen.VanillaBlocks.GREEN_CARPET);
        p.carpet2 = s(com.skycraft.worldgen.VanillaBlocks.BROWN_CARPET);
        p.accent = s(com.skycraft.worldgen.VanillaBlocks.GREEN_WOOL);
        p.bed = s(com.skycraft.worldgen.VanillaBlocks.GREEN_BED);
        p.path = s(Blocks.DIRT_PATH);
        p.path2 = s(Blocks.ROOTED_DIRT);
        p.plaza = s(Blocks.MOSSY_COBBLESTONE);
        p.plaza2 = s(Blocks.MOSS_BLOCK);
        p.cityWall = s(Blocks.JUNGLE_LOG);
        p.cityWall2 = s(Blocks.JUNGLE_WOOD);
        p.cityWallStairs = s(Blocks.JUNGLE_STAIRS);
        p.cityWallSlab = s(Blocks.JUNGLE_SLAB);
        p.cityWallWall = s(Blocks.JUNGLE_FENCE);
        p.column = s(Blocks.JUNGLE_LOG);
        p.shell = Shell.TREE;
        p.hallShell = Shell.TREE;
        p.towerShell = Shell.TREE;
        p.timber = true;
        return p;
    }

    private static Palette altmer() {
        Palette p = new Palette();
        p.name = "altmer";
        p.foundation = s(Blocks.QUARTZ_BRICKS);
        p.floor = s(Blocks.BIRCH_PLANKS);
        p.floor2 = s(Blocks.SMOOTH_QUARTZ);
        p.wallLow = s(Blocks.QUARTZ_BRICKS);
        p.wall = s(Blocks.SMOOTH_QUARTZ);
        p.wall2 = s(Blocks.CALCITE);
        p.post = s(Blocks.QUARTZ_PILLAR);
        p.beam = s(Blocks.QUARTZ_PILLAR);
        p.roof = s(Blocks.SMOOTH_QUARTZ_STAIRS);
        p.roofSlab = s(Blocks.SMOOTH_QUARTZ_SLAB);
        p.roofFull = s(Blocks.HONEYCOMB_BLOCK);
        p.trim = s(Blocks.QUARTZ_STAIRS);
        p.trimSlab = s(Blocks.QUARTZ_SLAB);
        p.window = s(com.skycraft.worldgen.VanillaBlocks.LIGHT_BLUE_STAINED_GLASS_PANE);
        p.glass = s(com.skycraft.worldgen.VanillaBlocks.LIGHT_BLUE_STAINED_GLASS);
        p.door = s(Blocks.BIRCH_DOOR);
        p.fence = s(Blocks.BIRCH_FENCE);
        p.gate = s(Blocks.BIRCH_FENCE_GATE);
        p.trapdoor = s(Blocks.BIRCH_TRAPDOOR);
        p.sign = s(Blocks.BIRCH_WALL_SIGN);
        p.planks = s(Blocks.BIRCH_PLANKS);
        p.slab = s(Blocks.BIRCH_SLAB);
        p.stairs = s(Blocks.BIRCH_STAIRS);
        p.light = s(Blocks.SEA_LANTERN);
        p.carpet = s(com.skycraft.worldgen.VanillaBlocks.LIGHT_BLUE_CARPET);
        p.carpet2 = s(com.skycraft.worldgen.VanillaBlocks.YELLOW_CARPET);
        p.accent = s(com.skycraft.worldgen.VanillaBlocks.YELLOW_WOOL);
        p.bed = s(com.skycraft.worldgen.VanillaBlocks.LIGHT_BLUE_BED);
        p.hearth = s(Blocks.SOUL_CAMPFIRE);
        p.path = s(Blocks.POLISHED_DIORITE);
        p.path2 = s(Blocks.CALCITE);
        p.plaza = s(Blocks.QUARTZ_BRICKS);
        p.plaza2 = s(Blocks.SMOOTH_QUARTZ);
        p.cityWall = s(Blocks.CALCITE);
        p.cityWall2 = s(Blocks.QUARTZ_BRICKS);
        p.cityWallStairs = s(Blocks.QUARTZ_STAIRS);
        p.cityWallSlab = s(Blocks.QUARTZ_SLAB);
        p.cityWallWall = s(Blocks.DIORITE_WALL);
        p.column = s(Blocks.QUARTZ_PILLAR);
        p.shell = Shell.HIP;
        p.hallShell = Shell.DOME;
        p.towerShell = Shell.TOWER;
        p.timber = false;
        p.pitch = 2;
        return p;
    }

    private static Palette redguard() {
        Palette p = khajiit();
        p.name = "redguard";
        p.wall = s(Blocks.SMOOTH_SANDSTONE);
        p.wall2 = s(Blocks.SMOOTH_SANDSTONE);
        p.wallLow = s(Blocks.SANDSTONE);
        p.roofFull = s(com.skycraft.worldgen.VanillaBlocks.CYAN_TERRACOTTA);
        p.roof = s(Blocks.SMOOTH_SANDSTONE_STAIRS);
        p.accent = s(com.skycraft.worldgen.VanillaBlocks.RED_WOOL);
        p.carpet = s(com.skycraft.worldgen.VanillaBlocks.RED_CARPET);
        p.carpet2 = s(com.skycraft.worldgen.VanillaBlocks.CYAN_CARPET);
        p.bed = s(com.skycraft.worldgen.VanillaBlocks.RED_BED);
        p.column = s(Blocks.CHISELED_SANDSTONE);
        p.shell = Shell.FLAT;
        p.hallShell = Shell.DOME;
        p.towerShell = Shell.TOWER;
        return p;
    }

    private static Palette breton() {
        Palette p = new Palette();
        p.name = "breton";
        p.foundation = s(Blocks.COBBLESTONE);
        p.floor = s(Blocks.DARK_OAK_PLANKS);
        p.wallLow = s(Blocks.COBBLESTONE);
        p.wall = s(Blocks.MUSHROOM_STEM);
        p.wall2 = s(Blocks.MUSHROOM_STEM);
        p.post = s(Blocks.DARK_OAK_LOG);
        p.beam = s(Blocks.STRIPPED_DARK_OAK_LOG);
        p.roof = s(Blocks.DEEPSLATE_TILE_STAIRS);
        p.roofSlab = s(Blocks.DEEPSLATE_TILE_SLAB);
        p.roofFull = s(Blocks.DEEPSLATE_TILES);
        p.trim = s(Blocks.STONE_BRICK_STAIRS);
        p.trimSlab = s(Blocks.STONE_BRICK_SLAB);
        p.window = s(Blocks.GLASS_PANE);
        p.door = s(Blocks.DARK_OAK_DOOR);
        p.fence = s(Blocks.DARK_OAK_FENCE);
        p.gate = s(Blocks.DARK_OAK_FENCE_GATE);
        p.trapdoor = s(Blocks.DARK_OAK_TRAPDOOR);
        p.sign = s(Blocks.DARK_OAK_WALL_SIGN);
        p.planks = s(Blocks.DARK_OAK_PLANKS);
        p.slab = s(Blocks.DARK_OAK_SLAB);
        p.stairs = s(Blocks.DARK_OAK_STAIRS);
        p.carpet = s(com.skycraft.worldgen.VanillaBlocks.BLUE_CARPET);
        p.carpet2 = s(com.skycraft.worldgen.VanillaBlocks.RED_CARPET);
        p.accent = s(com.skycraft.worldgen.VanillaBlocks.BLUE_WOOL);
        p.bed = s(com.skycraft.worldgen.VanillaBlocks.BLUE_BED);
        p.path = s(Blocks.COBBLESTONE);
        p.path2 = s(Blocks.GRAVEL);
        p.plaza = s(Blocks.COBBLESTONE);
        p.plaza2 = s(Blocks.STONE_BRICKS);
        p.column = s(Blocks.STRIPPED_DARK_OAK_LOG);
        p.shell = Shell.GABLE;
        p.hallShell = Shell.GABLE;
        p.towerShell = Shell.TOWER;
        p.timber = true;
        p.pitch = 2;
        return p;
    }

    private static Palette orc() {
        Palette p = new Palette();
        p.name = "orc";
        p.foundation = s(Blocks.COBBLED_DEEPSLATE);
        p.floor = s(Blocks.SPRUCE_PLANKS);
        p.wallLow = s(Blocks.COBBLED_DEEPSLATE);
        p.wall = s(Blocks.SPRUCE_LOG);
        p.wall2 = s(Blocks.DARK_OAK_LOG);
        p.post = s(Blocks.DARK_OAK_LOG);
        p.beam = s(Blocks.STRIPPED_DARK_OAK_LOG);
        p.roof = s(Blocks.SPRUCE_STAIRS);
        p.roofSlab = s(Blocks.SPRUCE_SLAB);
        p.roofFull = s(com.skycraft.worldgen.VanillaBlocks.BROWN_WOOL);
        p.trim = s(Blocks.COBBLED_DEEPSLATE_STAIRS);
        p.trimSlab = s(Blocks.COBBLED_DEEPSLATE_SLAB);
        p.carpet = s(com.skycraft.worldgen.VanillaBlocks.BROWN_CARPET);
        p.carpet2 = s(com.skycraft.worldgen.VanillaBlocks.GRAY_CARPET);
        p.accent = s(com.skycraft.worldgen.VanillaBlocks.RED_WOOL);
        p.bed = s(com.skycraft.worldgen.VanillaBlocks.BROWN_BED);
        p.path = s(Blocks.COARSE_DIRT);
        p.path2 = s(Blocks.GRAVEL);
        p.plaza = s(Blocks.COBBLED_DEEPSLATE);
        p.plaza2 = s(Blocks.GRAVEL);
        p.cityWall = s(Blocks.SPRUCE_LOG);
        p.cityWall2 = s(Blocks.DARK_OAK_LOG);
        p.cityWallStairs = s(Blocks.SPRUCE_STAIRS);
        p.cityWallSlab = s(Blocks.SPRUCE_SLAB);
        p.cityWallWall = s(Blocks.SPRUCE_FENCE);
        p.column = s(Blocks.DARK_OAK_LOG);
        p.shell = Shell.GABLE;
        p.hallShell = Shell.GABLE;
        p.timber = false;
        p.horns = true;
        return p;
    }

    private static Palette akaviri() {
        Palette p = new Palette();
        p.name = "akaviri";
        p.foundation = s(Blocks.STONE_BRICKS);
        p.floor = s(Blocks.BAMBOO_PLANKS);
        p.wallLow = s(Blocks.STONE_BRICKS);
        p.wall = s(Blocks.BIRCH_PLANKS);
        p.wall2 = s(com.skycraft.worldgen.VanillaBlocks.WHITE_TERRACOTTA);
        p.post = s(Blocks.MANGROVE_LOG);
        p.beam = s(Blocks.STRIPPED_MANGROVE_LOG);
        p.roof = s(Blocks.DEEPSLATE_TILE_STAIRS);
        p.roofSlab = s(Blocks.DEEPSLATE_TILE_SLAB);
        p.roofFull = s(Blocks.DEEPSLATE_TILES);
        p.trim = s(Blocks.MANGROVE_STAIRS);
        p.trimSlab = s(Blocks.MANGROVE_SLAB);
        p.window = s(Blocks.BAMBOO_FENCE);
        p.door = s(Blocks.MANGROVE_DOOR);
        p.fence = s(Blocks.BAMBOO_FENCE);
        p.gate = s(Blocks.BAMBOO_FENCE_GATE);
        p.trapdoor = s(Blocks.BAMBOO_TRAPDOOR);
        p.sign = s(Blocks.MANGROVE_WALL_SIGN);
        p.planks = s(Blocks.BAMBOO_PLANKS);
        p.slab = s(Blocks.BAMBOO_SLAB);
        p.stairs = s(Blocks.BAMBOO_STAIRS);
        p.carpet = s(com.skycraft.worldgen.VanillaBlocks.RED_CARPET);
        p.carpet2 = s(com.skycraft.worldgen.VanillaBlocks.BLACK_CARPET);
        p.accent = s(com.skycraft.worldgen.VanillaBlocks.RED_WOOL);
        p.bed = s(com.skycraft.worldgen.VanillaBlocks.RED_BED);
        p.path = s(Blocks.GRAVEL);
        p.path2 = s(Blocks.STONE);
        p.plaza = s(Blocks.STONE_BRICKS);
        p.plaza2 = s(Blocks.POLISHED_ANDESITE);
        p.column = s(Blocks.MANGROVE_LOG);
        p.shell = Shell.PAGODA;
        p.hallShell = Shell.PAGODA;
        p.towerShell = Shell.PAGODA;
        p.timber = true;
        return p;
    }

    private static Palette atmoran() {
        Palette p = new Palette();
        p.name = "atmoran";
        p.foundation = s(Blocks.COBBLESTONE);
        p.floor = s(Blocks.SPRUCE_PLANKS);
        p.wallLow = s(Blocks.MOSSY_COBBLESTONE);
        p.wall = s(Blocks.COBBLESTONE);
        p.wall2 = s(Blocks.MOSSY_COBBLESTONE);
        p.post = s(Blocks.STRIPPED_SPRUCE_LOG);
        p.beam = s(Blocks.BONE_BLOCK);
        p.roof = s(Blocks.MOSSY_STONE_BRICK_STAIRS);
        p.roofSlab = s(Blocks.MOSSY_STONE_BRICK_SLAB);
        p.roofFull = s(Blocks.MOSS_BLOCK);
        p.trim = s(Blocks.COBBLESTONE_STAIRS);
        p.trimSlab = s(Blocks.COBBLESTONE_SLAB);
        p.carpet = s(com.skycraft.worldgen.VanillaBlocks.WHITE_CARPET);
        p.carpet2 = s(com.skycraft.worldgen.VanillaBlocks.LIGHT_GRAY_CARPET);
        p.accent = s(com.skycraft.worldgen.VanillaBlocks.LIGHT_GRAY_WOOL);
        p.bed = s(com.skycraft.worldgen.VanillaBlocks.WHITE_BED);
        p.path = s(Blocks.GRAVEL);
        p.path2 = s(Blocks.COBBLESTONE);
        p.plaza = s(Blocks.COBBLESTONE);
        p.plaza2 = s(Blocks.STONE);
        p.cityWall = s(Blocks.STONE);
        p.cityWall2 = s(Blocks.COBBLESTONE);
        p.cityWallStairs = s(Blocks.COBBLESTONE_STAIRS);
        p.cityWallSlab = s(Blocks.COBBLESTONE_SLAB);
        p.cityWallWall = s(Blocks.COBBLESTONE_WALL);
        p.column = s(Blocks.BONE_BLOCK);
        p.shell = Shell.GABLE;
        p.hallShell = Shell.GABLE;
        p.towerShell = Shell.ROUND;
        p.timber = false;
        p.horns = true;
        p.pitch = 1;
        return p;
    }

    private static Palette yokudan() {
        Palette p = khajiit();
        p.name = "yokudan";
        p.foundation = s(Blocks.RED_SANDSTONE);
        p.wallLow = s(Blocks.CUT_RED_SANDSTONE);
        p.wall = s(Blocks.SMOOTH_RED_SANDSTONE);
        p.wall2 = s(Blocks.RED_SANDSTONE);
        p.roof = s(Blocks.SMOOTH_RED_SANDSTONE_STAIRS);
        p.roofSlab = s(Blocks.SMOOTH_RED_SANDSTONE_SLAB);
        p.roofFull = s(com.skycraft.worldgen.VanillaBlocks.ORANGE_TERRACOTTA);
        p.trim = s(Blocks.RED_SANDSTONE_STAIRS);
        p.trimSlab = s(Blocks.RED_SANDSTONE_SLAB);
        p.path = s(Blocks.SMOOTH_RED_SANDSTONE);
        p.path2 = s(Blocks.RED_SANDSTONE);
        p.plaza = s(Blocks.CUT_RED_SANDSTONE);
        p.plaza2 = s(Blocks.CHISELED_RED_SANDSTONE);
        p.cityWall = s(Blocks.RED_SANDSTONE);
        p.cityWall2 = s(Blocks.CUT_RED_SANDSTONE);
        p.cityWallStairs = s(Blocks.RED_SANDSTONE_STAIRS);
        p.cityWallSlab = s(Blocks.RED_SANDSTONE_SLAB);
        p.cityWallWall = s(Blocks.RED_SANDSTONE_WALL);
        p.column = s(Blocks.CHISELED_RED_SANDSTONE);
        p.accent = s(com.skycraft.worldgen.VanillaBlocks.YELLOW_WOOL);
        p.carpet = s(com.skycraft.worldgen.VanillaBlocks.YELLOW_CARPET);
        p.bed = s(com.skycraft.worldgen.VanillaBlocks.YELLOW_BED);
        p.shell = Shell.FLAT;
        p.hallShell = Shell.DOME;
        return p;
    }

    private static Palette maormer() {
        Palette p = new Palette();
        p.name = "maormer";
        p.foundation = s(Blocks.DARK_PRISMARINE);
        p.floor = s(Blocks.WARPED_PLANKS);
        p.floor2 = s(Blocks.PRISMARINE_BRICKS);
        p.wallLow = s(Blocks.DARK_PRISMARINE);
        p.wall = s(Blocks.PRISMARINE_BRICKS);
        p.wall2 = s(Blocks.PRISMARINE);
        p.post = s(Blocks.WARPED_STEM);
        p.beam = s(Blocks.STRIPPED_WARPED_STEM);
        p.roof = s(Blocks.PRISMARINE_BRICK_STAIRS);
        p.roofSlab = s(Blocks.PRISMARINE_BRICK_SLAB);
        p.roofFull = s(Blocks.DARK_PRISMARINE);
        p.trim = s(Blocks.DARK_PRISMARINE_STAIRS);
        p.trimSlab = s(Blocks.DARK_PRISMARINE_SLAB);
        p.window = s(com.skycraft.worldgen.VanillaBlocks.CYAN_STAINED_GLASS_PANE);
        p.glass = s(com.skycraft.worldgen.VanillaBlocks.CYAN_STAINED_GLASS);
        p.door = s(Blocks.WARPED_DOOR);
        p.fence = s(Blocks.WARPED_FENCE);
        p.gate = s(Blocks.WARPED_FENCE_GATE);
        p.trapdoor = s(Blocks.WARPED_TRAPDOOR);
        p.sign = s(Blocks.WARPED_WALL_SIGN);
        p.planks = s(Blocks.WARPED_PLANKS);
        p.slab = s(Blocks.WARPED_SLAB);
        p.stairs = s(Blocks.WARPED_STAIRS);
        p.light = s(Blocks.SEA_LANTERN);
        p.carpet = s(com.skycraft.worldgen.VanillaBlocks.CYAN_CARPET);
        p.carpet2 = s(com.skycraft.worldgen.VanillaBlocks.LIGHT_BLUE_CARPET);
        p.accent = s(com.skycraft.worldgen.VanillaBlocks.CYAN_WOOL);
        p.bed = s(com.skycraft.worldgen.VanillaBlocks.CYAN_BED);
        p.hearth = s(Blocks.SOUL_CAMPFIRE);
        p.path = s(Blocks.DARK_PRISMARINE);
        p.path2 = s(Blocks.PRISMARINE);
        p.plaza = s(Blocks.PRISMARINE_BRICKS);
        p.plaza2 = s(Blocks.DARK_PRISMARINE);
        p.cityWall = s(Blocks.DARK_PRISMARINE);
        p.cityWall2 = s(Blocks.PRISMARINE_BRICKS);
        p.cityWallStairs = s(Blocks.DARK_PRISMARINE_STAIRS);
        p.cityWallSlab = s(Blocks.DARK_PRISMARINE_SLAB);
        p.cityWallWall = s(Blocks.PRISMARINE_WALL);
        p.column = s(Blocks.WARPED_STEM);
        p.shell = Shell.CHITIN;
        p.hallShell = Shell.DOME;
        p.towerShell = Shell.TOWER;
        p.timber = false;
        return p;
    }
}
