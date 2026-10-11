package com.skycraft.roads;

import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.biome.BiomeBlocks;
import com.skycraft.tamriel.settlement.Culture;
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
                w(cobble, cobble, mossy, gravel, path, cobble, bricks, mossy),
                w(gravel, path, path, coarse, cobble, gravel, path, mossy),
                w(path, path, coarse, path, gravel),
                w(path, coarse, path)),
                w(cobble, mossy, andesite, bricks, cobble), true, bricks, brickWall, bricks, brickSlab);
        Style imperial = new Style(classes(
                w(bricks, bricks, cracked, mossyBricks, bricks, polishedAndesite, bricks, cracked),
                w(cobble, bricks, gravel, cobble, cracked, path),
                w(path, gravel, coarse, path),
                w(path, coarse, path)),
                w(polishedAndesite, andesite, polishedAndesite, bricks), true, bricks, brickWall, bricks, brickSlab);
        Style dunmer = new Style(classes(
                w(deepTiles, tuff, deepTiles, basalt, deepBricks, tuff),
                w(ash(), coarse, tuff, ash(), gravel, coarse),
                w(ash(), coarse, ash(), gravel),
                w(ash(), coarse, ash())),
                w(basalt, tuff, deepBricks, volcanic()), true, deepTiles, deepWall, deepBricks, deepSlab);
        Style argonian = new Style(classes(
                w(packedMud, mudBricks, packedMud, rooted, mudBricks),
                w(packedMud, rooted, coarse, packedMud, path),
                w(rooted, coarse, packedMud, path),
                w(rooted, coarse, path)),
                w(mudBricks, packedMud, mudBricks), false, mangrove, mangroveFence, mangroveLog, mangroveSlab);
        Style khajiit = new Style(classes(
                w(smoothSand, cutSand, smoothSand, sandstone, cutSand),
                w(smoothSand, sandstone, coarse, smoothSand, packedMud),
                w(sandstone, coarse, smoothSand, path),
                w(coarse, path, sandstone)),
                w(cutSand, sandstone, cutRed), true, cutSand, sandWall, sandstone, sandSlab);
        Style bosmer = new Style(classes(
                w(path, path, rooted, coarse, path, gravel),
                w(path, coarse, rooted, path),
                w(path, coarse, path),
                w(path, coarse, rooted)),
                w(coarse, rooted, coarse), false, oak, oakFence, oakLog, oakSlab);
        Style altmer = new Style(classes(
                w(calcite, diorite, quartz, calcite, smoothQuartz, diorite),
                w(diorite, calcite, gravel, diorite, path),
                w(path, gravel, calcite, path),
                w(path, coarse, path)),
                w(quartz, diorite, smoothQuartz), true, smoothQuartz, dioriteWall, quartz, quartzSlab);
        Style redguard = new Style(classes(
                w(redSand, cutRed, smoothSand, redSand, cutSand),
                w(redSand, smoothSand, coarse, sandstone, redSand),
                w(sandstone, coarse, redSand, path),
                w(coarse, path, redSand)),
                w(cutRed, cutSand, cutRed), true, cutRed, redWall, cutRed, redSlab);
        Style breton = new Style(classes(
                w(cobble, mossy, bricks, cobble, mossyBricks, cobble, gravel),
                w(cobble, gravel, path, mossy, path),
                w(path, gravel, coarse, path),
                w(path, coarse, path)),
                w(cobble, mossy, andesite), true, cobble, cobbleWall, bricks, cobbleSlab);
        Style orc = new Style(classes(
                w(cobble, blackstone, gravel, cobble, andesite),
                w(gravel, cobble, coarse, path),
                w(path, gravel, coarse),
                w(path, coarse)),
                w(cobble, andesite), false, spruce, spruceFence, spruceLog, spruceSlab);
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
