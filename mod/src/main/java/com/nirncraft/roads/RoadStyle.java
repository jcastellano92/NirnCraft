package com.nirncraft.roads;

import com.nirncraft.tamriel.TamrielMap;
import com.nirncraft.tamriel.biome.BiomeBlocks;
import com.nirncraft.tamriel.settlement.Culture;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/**
 * How roads look in each culture's land: Nord cobble, gravel and dirt paths; Imperial stone-brick highways; Dunmer ash
 * paths and dark stone; Argonian mud tracks and boardwalks; Khajiit and Redguard sandstone; Bosmer dirt (no cut stone
 * in the Green Pact's forests); Altmer white stone; Breton and Orc cobble. Highways get the finest surface and edge
 * stones, roads a coarser mix, lanes and trails bare earth. Bridges are stone arches for the stone-building cultures
 * and wooden plank bridges for the rest.
 */
final class RoadStyle {
    /** One culture's road materials: per class a weighted surface list, plus edge stones and the bridge set. */
    private record Style(Supplier<BlockState>[][] surface, Supplier<BlockState>[] edge, boolean stoneBridge,
                         Supplier<BlockState> deck, Supplier<BlockState> rail, Supplier<BlockState> pillar,
                         Supplier<BlockState> slab) {
    }

    private static final Style[] STYLES = new Style[Culture.VALUES.length];
    /** Every block a road, bridge or edge may consist of (so paving is idempotent and posts recognise roads). */
    private static Set<Block> materials;

    private RoadStyle() {}

    @SafeVarargs
    private static Supplier<BlockState>[] w(Supplier<BlockState>... s) {
        return s;
    }

    private static Supplier<BlockState> b(Block block) {
        return block::defaultBlockState;
    }

    private static Supplier<BlockState> ash() {
        return () -> BiomeBlocks.ASH_BLOCK.get().defaultBlockState();
    }

    private static Supplier<BlockState> volcanic() {
        return () -> BiomeBlocks.VOLCANIC_ROCK.get().defaultBlockState();
    }

    @SuppressWarnings("unchecked")
    private static Supplier<BlockState>[][] classes(Supplier<BlockState>[] highway, Supplier<BlockState>[] road,
                                                    Supplier<BlockState>[] lane, Supplier<BlockState>[] trail) {
        return new Supplier[][]{highway, road, lane, trail};
    }

    static {
        Supplier<BlockState> cobble = b(Blocks.COBBLESTONE), mossy = b(Blocks.MOSSY_COBBLESTONE), gravel = b(Blocks.GRAVEL),
                path = b(Blocks.DIRT_PATH), coarse = b(Blocks.COARSE_DIRT), bricks = b(Blocks.STONE_BRICKS),
                cracked = b(Blocks.CRACKED_STONE_BRICKS), mossyBricks = b(Blocks.MOSSY_STONE_BRICKS), andesite = b(Blocks.ANDESITE),
                polishedAndesite = b(Blocks.POLISHED_ANDESITE), packedMud = b(Blocks.PACKED_MUD), mudBricks = b(Blocks.MUD_BRICKS),
                rooted = b(Blocks.ROOTED_DIRT), sandstone = b(Blocks.SANDSTONE), smoothSand = b(Blocks.SMOOTH_SANDSTONE),
                cutSand = b(Blocks.CUT_SANDSTONE), redSand = b(Blocks.SMOOTH_RED_SANDSTONE), cutRed = b(Blocks.CUT_RED_SANDSTONE),
                calcite = b(Blocks.CALCITE), diorite = b(Blocks.POLISHED_DIORITE), quartz = b(Blocks.QUARTZ_BRICKS),
                smoothQuartz = b(Blocks.SMOOTH_QUARTZ), tuff = b(Blocks.TUFF), deepTiles = b(Blocks.DEEPSLATE_TILES),
                deepBricks = b(Blocks.DEEPSLATE_BRICKS), basalt = b(Blocks.SMOOTH_BASALT), blackstone = b(Blocks.POLISHED_BLACKSTONE_BRICKS);
        Supplier<BlockState> spruce = b(Blocks.SPRUCE_PLANKS), spruceFence = b(Blocks.SPRUCE_FENCE), spruceLog = b(Blocks.SPRUCE_LOG),
                spruceSlab = b(Blocks.SPRUCE_SLAB), oak = b(Blocks.OAK_PLANKS), oakFence = b(Blocks.OAK_FENCE), oakLog = b(Blocks.OAK_LOG),
                oakSlab = b(Blocks.OAK_SLAB), mangrove = b(Blocks.MANGROVE_PLANKS), mangroveFence = b(Blocks.MANGROVE_FENCE),
                mangroveLog = b(Blocks.MANGROVE_LOG), mangroveSlab = b(Blocks.MANGROVE_SLAB);
        Supplier<BlockState> brickWall = b(Blocks.STONE_BRICK_WALL), brickSlab = b(Blocks.STONE_BRICK_SLAB),
                sandWall = b(Blocks.SANDSTONE_WALL), sandSlab = b(Blocks.SMOOTH_SANDSTONE_SLAB),
                redWall = b(Blocks.RED_SANDSTONE_WALL), redSlab = b(Blocks.SMOOTH_RED_SANDSTONE_SLAB),
                quartzSlab = b(Blocks.SMOOTH_QUARTZ_SLAB), dioriteWall = b(Blocks.DIORITE_WALL),
                deepWall = b(Blocks.DEEPSLATE_TILE_WALL), deepSlab = b(Blocks.DEEPSLATE_TILE_SLAB),
                cobbleWall = b(Blocks.COBBLESTONE_WALL), cobbleSlab = b(Blocks.COBBLESTONE_SLAB);

        Style nord = new Style(classes(
                w(bricks, cobble, mossyBricks, polishedAndesite, cobble, bricks, mossy, cracked),
                w(cobble, cobble, mossy, bricks, andesite, gravel, cobble, path),
                w(cobble, path, gravel, mossy, coarse),
                w(path, coarse, gravel, cobble)),
                w(bricks, mossyBricks, polishedAndesite, cobble, mossy), true, bricks, brickWall, bricks, brickSlab);
        Style imperial = new Style(classes(
                w(bricks, bricks, polishedAndesite, cracked, mossyBricks, bricks, polishedAndesite, bricks),
                w(bricks, cobble, polishedAndesite, cracked, mossyBricks, gravel, cobble),
                w(cobble, gravel, path, bricks, coarse),
                w(path, gravel, coarse, cobble)),
                w(polishedAndesite, bricks, mossyBricks, andesite), true, bricks, brickWall, bricks, brickSlab);
        Style dunmer = new Style(classes(
                w(deepTiles, deepBricks, basalt, deepTiles, tuff, blackstone),
                w(deepBricks, tuff, basalt, ash(), deepTiles, gravel),
                w(tuff, ash(), coarse, basalt, gravel),
                w(ash(), coarse, tuff)),
                w(deepTiles, basalt, blackstone, volcanic()), true, deepTiles, deepWall, deepBricks, deepSlab);
        Style argonian = new Style(classes(
                w(mudBricks, packedMud, mudBricks, mossyBricks, packedMud, rooted),
                w(packedMud, mudBricks, mossy, rooted, packedMud, path),
                w(packedMud, rooted, coarse, path),
                w(rooted, coarse, path)),
                w(mudBricks, mossyBricks, packedMud), false, mangrove, mangroveFence, mangroveLog, mangroveSlab);
        Style khajiit = new Style(classes(
                w(cutSand, smoothSand, cutSand, sandstone, smoothSand, redSand),
                w(smoothSand, cutSand, sandstone, packedMud, smoothSand, path),
                w(sandstone, smoothSand, coarse, path),
                w(coarse, path, sandstone)),
                w(cutSand, cutRed, smoothSand), true, cutSand, sandWall, sandstone, sandSlab);
        Style bosmer = new Style(classes(
                w(path, mossy, rooted, path, gravel, coarse),
                w(path, rooted, coarse, mossy, path),
                w(path, coarse, rooted),
                w(path, coarse, rooted)),
                w(mossy, rooted, coarse), false, oak, oakFence, oakLog, oakSlab);
        Style altmer = new Style(classes(
                w(quartz, smoothQuartz, calcite, diorite, quartz, smoothQuartz),
                w(calcite, diorite, quartz, smoothQuartz, gravel),
                w(diorite, calcite, path, gravel),
                w(path, calcite, coarse)),
                w(quartz, smoothQuartz, diorite), true, smoothQuartz, dioriteWall, quartz, quartzSlab);
        Style redguard = new Style(classes(
                w(cutRed, redSand, cutSand, cutRed, smoothSand),
                w(redSand, cutRed, smoothSand, sandstone, redSand),
                w(sandstone, redSand, coarse, path),
                w(coarse, path, redSand)),
                w(cutRed, cutSand, redSand), true, cutRed, redWall, cutRed, redSlab);
        Style breton = new Style(classes(
                w(bricks, cobble, mossyBricks, polishedAndesite, bricks, mossy, cracked),
                w(cobble, bricks, mossy, andesite, gravel, cobble, path),
                w(cobble, path, gravel, mossy),
                w(path, coarse, gravel)),
                w(bricks, mossyBricks, cobble, polishedAndesite), true, cobble, cobbleWall, bricks, cobbleSlab);
        Style orc = new Style(classes(
                w(blackstone, cobble, deepBricks, andesite, cobble, basalt),
                w(cobble, blackstone, andesite, gravel, coarse),
                w(cobble, gravel, path, coarse),
                w(path, coarse, gravel)),
                w(blackstone, cobble, andesite), false, spruce, spruceFence, spruceLog, spruceSlab);
        for (Culture c : Culture.VALUES) {
            STYLES[c.ordinal()] = switch (c) {
                case IMPERIAL, AKAVIRI -> imperial;
                case DUNMER -> dunmer;
                case ARGONIAN, MAORMER -> argonian;
                case KHAJIIT -> khajiit;
                case BOSMER -> bosmer;
                case ALTMER -> altmer;
                case REDGUARD, YOKUDAN -> redguard;
                case BRETON -> breton;
                case ORC -> orc;
                default -> nord;
            };
        }
    }

    /** The wood of a culture's roadside carts, fences and woodpiles. */
    record Wood(Block planks, Block fence, Block slab, Block log, Block trapdoor, Block gate) {
    }

    /** The stone of a culture's cairns. */
    record Stone(Block block, Block mossy, Block slab) {
    }

    private static final Wood SPRUCE = new Wood(Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_FENCE, Blocks.SPRUCE_SLAB,
            Blocks.SPRUCE_LOG, Blocks.SPRUCE_TRAPDOOR, Blocks.SPRUCE_FENCE_GATE);
    private static final Wood OAK = new Wood(Blocks.OAK_PLANKS, Blocks.OAK_FENCE, Blocks.OAK_SLAB, Blocks.OAK_LOG,
            Blocks.OAK_TRAPDOOR, Blocks.OAK_FENCE_GATE);
    private static final Wood BIRCH = new Wood(Blocks.BIRCH_PLANKS, Blocks.BIRCH_FENCE, Blocks.BIRCH_SLAB,
            Blocks.BIRCH_LOG, Blocks.BIRCH_TRAPDOOR, Blocks.BIRCH_FENCE_GATE);
    private static final Wood DARK_OAK = new Wood(Blocks.DARK_OAK_PLANKS, Blocks.DARK_OAK_FENCE, Blocks.DARK_OAK_SLAB,
            Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_TRAPDOOR, Blocks.DARK_OAK_FENCE_GATE);
    private static final Wood MANGROVE = new Wood(Blocks.MANGROVE_PLANKS, Blocks.MANGROVE_FENCE, Blocks.MANGROVE_SLAB,
            Blocks.MANGROVE_LOG, Blocks.MANGROVE_TRAPDOOR, Blocks.MANGROVE_FENCE_GATE);
    private static final Wood ACACIA = new Wood(Blocks.ACACIA_PLANKS, Blocks.ACACIA_FENCE, Blocks.ACACIA_SLAB,
            Blocks.ACACIA_LOG, Blocks.ACACIA_TRAPDOOR, Blocks.ACACIA_FENCE_GATE);

    static Wood wood(Culture c) {
        return switch (c) {
            case BRETON, BOSMER -> OAK;
            case ALTMER -> BIRCH;
            case DUNMER -> DARK_OAK;
            case ARGONIAN, MAORMER -> MANGROVE;
            case KHAJIIT, REDGUARD, YOKUDAN -> ACACIA;
            default -> SPRUCE;
        };
    }

    static Stone stone(Culture c) {
        return switch (c) {
            case DUNMER -> new Stone(Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.BASALT, Blocks.POLISHED_BLACKSTONE_BRICK_SLAB);
            case KHAJIIT, REDGUARD, YOKUDAN -> new Stone(Blocks.SANDSTONE, Blocks.CUT_SANDSTONE, Blocks.SANDSTONE_SLAB);
            case ALTMER -> new Stone(Blocks.CALCITE, Blocks.QUARTZ_BRICKS, Blocks.QUARTZ_SLAB);
            case IMPERIAL, AKAVIRI -> new Stone(Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.STONE_BRICK_SLAB);
            case ARGONIAN, MAORMER -> new Stone(Blocks.MUD_BRICKS, Blocks.PACKED_MUD, Blocks.MUD_BRICK_SLAB);
            default -> new Stone(Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.COBBLESTONE_SLAB);
        };
    }

    /** The building culture of the land at (x, z). */
    static Culture cultureAt(int x, int z) {
        try {
            return Culture.of(TamrielMap.region(x, z).culture());
        } catch (RuntimeException e) {
            return Culture.NORD;
        }
    }

    private static Style style(Culture c) {
        return STYLES[c.ordinal()];
    }

    /** A culture's surface list for a road class (0 highway .. 3 trail), in surface()'s roll order. */
    static BlockState[] surfaces(Culture c, int kind) {
        Supplier<BlockState>[] list = style(c).surface()[kind];
        BlockState[] out = new BlockState[list.length];
        for (int i = 0; i < list.length; i++) out[i] = list[i].get();
        return out;
    }

    /** Road surface for a column, or null to leave it alone. */
    static BlockState surface(Culture c, byte kind, int roll) {
        Supplier<BlockState>[] list = style(c).surface()[Math.max(0, Math.min(3, kind))];
        return list[roll % list.length].get();
    }

    /** Edge stones lining a highway. */
    static BlockState edge(Culture c, int roll) {
        Supplier<BlockState>[] list = style(c).edge();
        return list[roll % list.length].get();
    }

    static boolean stoneBridge(Culture c) {
        return style(c).stoneBridge();
    }

    static BlockState deck(Culture c) {
        return style(c).deck().get();
    }

    static BlockState deckSlab(Culture c) {
        return style(c).slab().get();
    }

    static BlockState rail(Culture c) {
        return style(c).rail().get();
    }

    static BlockState pillar(Culture c) {
        return style(c).pillar().get();
    }

    /** True for any block our roads, edges and bridges are made of. */
    static boolean isMaterial(BlockState s) {
        Set<Block> m = materials;
        if (m == null) {
            m = new HashSet<>();
            for (Style st : STYLES) {
                for (Supplier<BlockState>[] list : st.surface()) for (Supplier<BlockState> b : list) m.add(b.get().getBlock());
                for (Supplier<BlockState> b : st.edge()) m.add(b.get().getBlock());
                m.add(st.deck().get().getBlock());
                m.add(st.slab().get().getBlock());
            }
            m.remove(Blocks.ROOTED_DIRT); // natural soil too: it may be re-paved
            materials = m;
        }
        return m.contains(s.getBlock()) && !s.is(BlockTags.LOGS);
    }
}
