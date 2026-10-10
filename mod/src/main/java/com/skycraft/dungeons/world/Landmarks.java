package com.skycraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Smaller named sites: shipwrecks on the Sea of Ghosts, sacred groves (a great tree over a spring, spriggans in
 * the undergrowth), Nordic dwelling ruins, and the minor landmarks of the map, by style ({@link SiteParams.Style}):
 * monuments, stone circles, ruins, peak cairns, passes, clearings, Dwemer ruins, bridges, altars, rocks,
 * waterfall lookouts, eyries, islands and the stalhrim source.
 */
public final class Landmarks {
    private Landmarks() {}

    public static void build(Frame fr, DungeonPiece c) {
        switch (c.theme) {
            case SHIPWRECK -> shipwreck(fr, c);
            case GROVE -> grove(fr, c);
            case NORDIC_RUIN -> nordicRuin(fr, c);
            default -> landmark(fr, c);
        }
    }

    // ------------------------------------------------------------------ shipwreck

    /**
     * A wrecked longship or merchantman listing on the shore: hull of dark planks, a snapped mast, a torn sail, crates
     * spilled into the shallows, and whatever moved in.
     */
    private static void shipwreck(Frame fr, DungeonPiece c) {
        Painter p = fr.p;
        int len = 10, beam = 3;
        fr.columns(-len - 1, -beam - 1, len + 1, beam + 1, (fw, lt, x, z) -> {
            // hull cross-section narrows towards bow (+fw) and stern
            double t = Math.abs(fw) / (double) len;
            double half = beam * Math.sqrt(Math.max(0, 1 - t * t * t));
            int tilt = lt > 0 ? 1 : 0; // listing to starboard
            if (Math.abs(lt) > half + 0.5) return;
            boolean broken = p.noise(x, 0, z, 3.0, 860) > 0.45;
            int keel = -1 - tilt;
            for (int dy = keel; dy <= 3; dy++) {
                int y = fr.base + dy;
                boolean side = Math.abs(lt) >= half - 0.5;
                boolean bottom = dy == keel;
                boolean deck = dy == 2 && Math.abs(fw) < len - 1;
                if (broken && dy >= 1) {
                    p.air(x, y, z);
                    continue;
                }
                if (bottom || side) p.set(x, y, z, p.chance(x, y, z, 861, 4) ? Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState() : Blocks.DARK_OAK_PLANKS.defaultBlockState());
                else if (deck && !p.chance(x, y, z, 862, 3)) p.set(x, y, z, Blocks.SPRUCE_PLANKS.defaultBlockState());
                else p.air(x, y, z);
            }
        });
        // bow post and the snapped mast with its torn sail
        for (int dy = 0; dy <= 5; dy++) fr.set(len + 1, dy, 0, Deco.pillar(Blocks.DARK_OAK_LOG, Direction.Axis.Y));
        fr.set(len + 2, 5, 0, Blocks.DARK_OAK_FENCE);
        for (int dy = 2; dy <= 8; dy++) fr.set(1, dy, 0, Deco.pillar(Blocks.SPRUCE_LOG, Direction.Axis.Y));
        for (int lt = -3; lt <= 3; lt++) fr.set(1, 7, lt, Deco.pillar(Blocks.STRIPPED_SPRUCE_LOG, fr.r.getAxis()));
        for (int lt = -3; lt <= 2; lt++) for (int dy = 4; dy <= 6; dy++) if (fr.chance(lt, dy, 863, 3) || dy == 6) fr.set(1, dy, lt + 1, Blocks.WHITE_WOOL);
        // cargo strewn about
        fr.container(-3, 0, 0, Deco.chest(fr.f), Deco.LOOT_COMMON);
        fr.container(4, 0, -1, Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
        fr.set(-6, 0, 1, Blocks.BARREL);
        fr.set(-6, 0, -1, Blocks.BARREL);
        fr.set(6, 0, 5, Blocks.BARREL);
        fr.set(-8, 0, 5, Deco.pillar(Blocks.STRIPPED_DARK_OAK_LOG, fr.f.getAxis()));
        // who lives here now: smugglers, the drowned, or horkers and mudcrabs
        switch (Math.floorMod(c.flavor, 3)) {
            case 0 -> {
                fr.spawn(Mobs.bandit(), 0, 3, 0, fr.f, "Smuggler");
                fr.spawn(Mobs.bandit(), -5, 0, 4, fr.r, "Smuggler");
            }
            case 1 -> {
                fr.spawn(Mobs.draugr(), -2, 0, 1, fr.f, null);
                fr.spawn("minecraft:drowned", 5, 0, -5, fr.f, null);
            }
            default -> {
                fr.spawn(Mobs.horker(), 6, 0, 6, fr.f, null);
                fr.spawn(Mobs.mudcrab(), -6, 0, -5, fr.f, null);
            }
        }
    }

    // ------------------------------------------------------------------ grove

    /** A sacred grove: a great old tree, a ring of flowers and mossy stones, a spring, and its guardians. */
    private static void grove(Frame fr, DungeonPiece c) {
        Painter p = fr.p;
        fr.all((fw, lt, x, z) -> {
            double d = Math.hypot(fw, lt);
            if (d > 13.5) return;
            p.clear(x, fr.base, z, fr.base + 22);
            double n = p.noise(x, 0, z, 3.0, 870);
            p.set(x, fr.base - 1, z, d < 3 ? Blocks.ROOTED_DIRT.defaultBlockState() : n > 0.2 ? Blocks.MOSS_BLOCK.defaultBlockState()
                    : Blocks.GRASS_BLOCK.defaultBlockState());
            p.foundation(x, fr.base - 2, z, Blocks.DIRT.defaultBlockState(), 10);
            if (d > 4 && d < 12 && n < -0.3 && p.chance(x, 1, z, 871, 2)) {
                int f = p.roll(x, 2, z, 872, 5);
                p.set(x, fr.base, z, f == 0 ? Blocks.BLUE_ORCHID.defaultBlockState() : f == 1 ? Blocks.LILY_OF_THE_VALLEY.defaultBlockState()
                        : f == 2 ? Blocks.ALLIUM.defaultBlockState() : f == 3 ? Blocks.FERN.defaultBlockState() : Blocks.MOSS_CARPET.defaultBlockState());
            }
            if (d >= 12 && d < 13.2 && p.chance(x, 3, z, 873, 4)) p.set(x, fr.base, z, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
        });
        // the spring
        for (int fw = 5; fw <= 7; fw++) for (int lt = -1; lt <= 1; lt++) {
            fr.set(fw, -2, lt, Blocks.MOSSY_COBBLESTONE);
            fr.set(fw, -1, lt, Blocks.WATER);
        }
        fr.set(6, -1, 0, Blocks.SEA_PICKLE.defaultBlockState().setValue(net.minecraft.world.level.block.SeaPickleBlock.PICKLES, 3));
        // the great tree: a thick twisted trunk and a wide crown
        BlockState log = Deco.pillar(Blocks.DARK_OAK_LOG, Direction.Axis.Y);
        BlockState leaves = Blocks.AZALEA_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        BlockState bloom = Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        fr.all((fw, lt, x, z) -> {
            double d = Math.hypot(fw + 1, lt);
            for (int dy = 0; dy <= 20; dy++) {
                double trunk = dy < 3 ? 2.2 : dy < 12 ? 1.5 : 0.9;
                if (d <= trunk && dy <= 14) p.set(x, fr.base + dy, z, log);
                double crown = Math.hypot(d / 1.0, (dy - 15) * 1.6);
                if (dy >= 11 && crown < 8.0 + p.noise(x, dy, z, 2.5, 874) * 1.5 && d > trunk) {
                    p.setIfOpen(x, fr.base + dy, z, p.chance(x, dy, z, 875, 4) ? bloom : leaves);
                }
            }
            // roots
            if (d > 2.2 && d < 4.5 && p.noise(x, 0, z, 1.5, 876) > 0.2) p.set(x, fr.base, z, Blocks.MANGROVE_ROOTS.defaultBlockState());
        });
        // offerings at the roots, hanging glow
        fr.container(-4, 0, 3, Deco.chest(fr.f), Deco.LOOT_ALCHEMY);
        fr.set(3, 0, -3, Deco.candle(Blocks.GREEN_CANDLE, 3, true));
        fr.set(-1, 10, 3, Blocks.SHROOMLIGHT);
        fr.set(-1, 10, -3, Blocks.SHROOMLIGHT);
        fr.spawn(Mobs.spriggan(), 4, 0, 4, fr.f, null);
        fr.spawn(Mobs.spriggan(), -6, 0, -4, fr.f, null);
        if (fr.chance(0, 0, 877, 3)) fr.spawn(Mobs.wispmother(), 8, 0, -6, fr.f, null);
    }

    // ------------------------------------------------------------------ Nordic dwelling ruins

    /** Broken walls of an old Nordic hall, a fallen arch, a crypt hatch, and the dead who still keep it. */
    private static void nordicRuin(Frame fr, DungeonPiece c) {
        Painter p = fr.p;
        Frame.Masonry stone = (pp, x, y, z) -> BuiltBuilder.wall(pp, Theme.BARROW, x, y, z, 1);
        fr.all((fw, lt, x, z) -> {
            if (Math.abs(fw) > 9 || Math.abs(lt) > 9) return;
            p.clear(x, fr.base, z, fr.base + 12);
            p.set(x, fr.base - 1, z, p.chance(x, 0, z, 880, 3) ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                    : BuiltBuilder.floor(p, Theme.BARROW, x, fr.base - 1, z));
            p.foundation(x, fr.base - 2, z, Blocks.COBBLESTONE.defaultBlockState(), 10);
            boolean hall = Math.abs(fw) <= 7 && Math.abs(lt) <= 5;
            boolean wall = hall && (Math.abs(fw) == 7 || Math.abs(lt) == 5);
            if (wall) {
                int top = 2 + (int) Math.round((p.noise(x, 0, z, 3.0, 881) + 1) * 2.5);
                boolean door = fw == 7 && Math.abs(lt) <= 1;
                for (int dy = 0; dy <= top; dy++) {
                    if (door && dy <= 2) continue;
                    p.set(x, fr.base + dy, z, stone.at(p, x, fr.base + dy, z));
                }
            } else if (!hall && p.noise(x, 0, z, 2.0, 882) > 0.55) {
                p.set(x, fr.base, z, Deco.slab(Blocks.COBBLESTONE_SLAB, false));
            }
        });
        // the fallen arch inside, a hearth and the crypt hatch
        for (int lt = -5; lt <= 5; lt++) {
            int a = Math.abs(lt);
            if (a == 5 || a <= 1) for (int dy = 0; dy <= 6 - Math.max(0, a - 3); dy++) fr.set(0, dy, lt, stone.at(p, fr.x(0, lt), fr.base + dy, fr.z(0, lt)));
        }
        fr.set(-5, 0, 0, Deco.campfire(false, false));
        fr.set(-6, 0, -3, Blocks.SPRUCE_TRAPDOOR);
        fr.container(-6, 0, 3, Deco.chest(fr.f), Deco.LOOT_COMMON);
        fr.set(3, 0, 3, Blocks.DECORATED_POT);
        fr.set(3, 0, -3, Deco.skull(Blocks.SKELETON_SKULL, Deco.skullRotation(fr.f)));
        // the occupants: draugr asleep in the ruins, or Forsworn/vampires/bandits using them
        switch (Math.floorMod(c.flavor, 4)) {
            case 0 -> {
                fr.p.spawnDormant(Mobs.draugr(), fr.x(-3, 3), fr.base, fr.z(-3, 3), 0f);
                fr.p.spawnDormant(Mobs.draugr(), fr.x(2, -3), fr.base, fr.z(2, -3), 90f);
            }
            case 1 -> {
                fr.spawn(Mobs.forsworn(), 2, 0, 2, fr.f, null);
                fr.spawn(Mobs.hagraven(), -3, 0, 0, fr.f, null);
            }
            case 2 -> {
                fr.spawn(Mobs.vampire(), 2, 0, 2, fr.f, null);
                fr.spawn(Mobs.vampire(), -3, 0, -1, fr.f, null);
            }
            default -> {
                fr.spawn(Mobs.bandit(), 2, 0, 2, fr.f, null);
                fr.spawn(Mobs.bandit(), -3, 0, -1, fr.f, null);
            }
        }
    }

    // ------------------------------------------------------------------ minor landmarks

    private static void landmark(Frame fr, DungeonPiece c) {
        SiteParams.Style style = SiteParams.Style.byIndex(c.param);
        Painter p = fr.p;
        switch (style) {
            case STONES -> {
                // a circle of standing slabs around a flat altar stone
                for (int i = 0; i < 9; i++) {
                    double a = i * Math.PI * 2 / 9;
                    int fw = (int) Math.round(Math.cos(a) * 7), lt = (int) Math.round(Math.sin(a) * 7);
                    int h = 2 + fr.roll(fw, lt, 890, 3);
                    for (int dy = 0; dy < h; dy++) fr.set(fw, dy, lt, dy == h - 1 ? Blocks.MOSSY_STONE_BRICKS : Blocks.STONE_BRICKS);
                    fr.foundation(fw, -1, lt, Blocks.COBBLESTONE.defaultBlockState(), 6);
                }
                fr.set(0, 0, 0, Blocks.CHISELED_STONE_BRICKS);
                fr.set(0, 1, 0, Deco.candle(Blocks.CANDLE, 3, true));
            }
            case MONUMENT -> {
                // a stepped plinth with a carved stele and braziers
                for (int s = 3; s >= 1; s--) fr.fill(-s, 3 - s, -s, s, 3 - s, s, Blocks.STONE_BRICKS);
                for (int dy = 3; dy <= 9; dy++) fr.set(0, dy, 0, dy == 9 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState() : Deco.pillar(Blocks.POLISHED_BASALT, Direction.Axis.Y));
                for (int s : new int[]{-4, 4}) {
                    fr.set(s, 0, s, Blocks.CHISELED_STONE_BRICKS);
                    fr.set(s, 1, s, Deco.campfire(false, true));
                }
            }
            case PEAK, PASS -> {
                // a cairn of stones with a pole and a tattered banner, marking the way
                for (int dy = 0; dy <= 2; dy++) for (int fw = -1; fw <= 1; fw++) for (int lt = -1; lt <= 1; lt++) {
                    if (Math.abs(fw) + Math.abs(lt) <= 2 - dy) fr.set(fw, dy, lt, fr.chance(fw, lt + dy * 7, 891, 2) ? Blocks.COBBLESTONE : Blocks.MOSSY_COBBLESTONE);
                }
                for (int dy = 3; dy <= 6; dy++) fr.set(0, dy, 0, Blocks.SPRUCE_FENCE);
                fr.set(0, 6, 1, Blocks.WHITE_WOOL);
                fr.set(0, 5, 1, Blocks.WHITE_WOOL);
                if (style == SiteParams.Style.PASS) {
                    for (int fw = -9; fw <= 9; fw += 3) fr.set(fw, 0, 3, Blocks.COBBLESTONE_WALL);
                }
            }
            case CLEARING, CAMPSITE -> {
                // a pond in a clearing with a hunter's campsite
                fr.all((fw, lt, x, z) -> {
                    double d = Math.hypot(fw + 3, lt);
                    if (d < 3.5) {
                        p.set(x, fr.base - 2, z, Blocks.MUD.defaultBlockState());
                        p.set(x, fr.base - 1, z, Blocks.WATER.defaultBlockState());
                        p.clear(x, fr.base, z, fr.base + 4);
                    } else if (d < 4.5) {
                        p.set(x, fr.base - 1, z, Blocks.MUD.defaultBlockState());
                        if (p.chance(x, 0, z, 892, 3)) p.set(x, fr.base, z, Blocks.TALL_GRASS.defaultBlockState());
                    }
                });
                fr.set(-3, 0, 0, Blocks.LILY_PAD);
                Camps.fire(fr, 4, 2);
                Towers.tent(fr, 6, -2, Blocks.BROWN_WOOL.defaultBlockState());
                fr.container(7, 0, 2, Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
            }
            case DWEMER, BRIDGE -> {
                // Dwemer stonework: a broken bronze-trimmed platform and pillars, pipes in the ground
                fr.fill(-6, -1, -4, 6, -1, 4, Blocks.POLISHED_BLACKSTONE_BRICKS);
                for (int fw = -6; fw <= 6; fw += 4) {
                    for (int lt : new int[]{-4, 4}) {
                        int h = 3 + fr.roll(fw, lt, 893, 5);
                        for (int dy = 0; dy < h; dy++) fr.set(fw, dy, lt, dy == h - 1 ? Blocks.WAXED_COPPER_BLOCK.defaultBlockState() : Blocks.WAXED_CUT_COPPER.defaultBlockState());
                    }
                }
                for (int fw = -5; fw <= 5; fw++) fr.set(fw, -1, 0, Blocks.WAXED_EXPOSED_CUT_COPPER);
                fr.set(0, 0, 0, Blocks.OCHRE_FROGLIGHT);
                fr.spawn(Mobs.DWARVEN_SPIDER, 2, 0, 2, fr.f, null);
                if (style == SiteParams.Style.BRIDGE) fr.spawn(Mobs.falmer(), -2, 0, -2, fr.f, null);
            }
            case ALTAR -> {
                fr.fill(-2, 0, -2, 2, 0, 2, Blocks.STONE_BRICKS);
                fr.set(0, 1, 0, Blocks.CHISELED_STONE_BRICKS);
                fr.set(0, 2, 0, Deco.campfire(false, true));
                for (int s : new int[]{-3, 3}) for (int dy = 0; dy <= 3; dy++) fr.set(0, dy, s, Blocks.MOSSY_STONE_BRICKS);
                fr.container(1, 1, 1, Deco.chest(fr.f), Deco.LOOT_SHRINE);
            }
            case ROCK, EYRIE -> {
                // a lone crag with a hagraven's nest or a ruined eyrie on top
                fr.all((fw, lt, x, z) -> {
                    double d = Math.hypot(fw, lt);
                    int h = (int) Math.round(Math.max(0, 9 - d * 1.4) + p.noise(x, 0, z, 2.5, 894) * 2);
                    for (int dy = 0; dy < h; dy++) p.set(x, fr.base + dy, z, p.chance(x, dy, z, 895, 4) ? Blocks.ANDESITE.defaultBlockState() : Blocks.STONE.defaultBlockState());
                });
                fr.set(0, 9, 0, Blocks.HAY_BLOCK);
                fr.set(1, 9, 0, Deco.skull(Blocks.SKELETON_SKULL, 4));
                fr.spawn(style == SiteParams.Style.ROCK ? Mobs.hagraven() : Mobs.forsworn(), 0, 10, 0, fr.f, null);
            }
            case WATERFALL -> {
                // a stone lookout on the brink, with an old offering cairn
                fr.fill(-2, -1, -2, 2, -1, 2, Blocks.STONE_BRICKS);
                for (int lt = -2; lt <= 2; lt++) fr.set(3, 0, lt, Blocks.STONE_BRICK_WALL);
                fr.set(-1, 0, 0, Blocks.MOSSY_COBBLESTONE);
                fr.set(-1, 1, 0, Deco.candle(Blocks.CANDLE, 2, true));
            }
            case ISLAND -> {
                for (int i = 0; i < 6; i++) {
                    int fw = fr.roll(i, 0, 896, 9) - 4, lt = fr.roll(i, 1, 897, 9) - 4;
                    fr.set(fw, 0, lt, Blocks.MOSSY_COBBLESTONE);
                }
                fr.spawn(Mobs.horker(), 2, 0, 1, fr.f, null);
                fr.spawn(Mobs.horker(), -2, 0, -1, fr.f, null);
            }
            case SOURCE -> {
                // stalhrim: blue ice veins in a frozen bowl
                fr.all((fw, lt, x, z) -> {
                    double d = Math.hypot(fw, lt);
                    if (d > 6) return;
                    p.set(x, fr.base - 1, z, d < 2.5 ? Blocks.BLUE_ICE.defaultBlockState() : Blocks.PACKED_ICE.defaultBlockState());
                    if (d > 4 && p.chance(x, 0, z, 898, 2)) p.set(x, fr.base, z, Blocks.PACKED_ICE.defaultBlockState());
                });
                fr.set(0, 0, 0, Blocks.BLUE_ICE);
                fr.set(0, 1, 0, Blocks.BLUE_ICE);
                fr.spawn(Mobs.iceWraith(), 3, 0, 3, fr.f, null);
            }
            default -> {
                // a ruin: corner of walls and a fallen column
                for (int fw = -5; fw <= 5; fw++) {
                    int h = 1 + fr.roll(fw, 0, 899, 4);
                    for (int dy = 0; dy < h; dy++) fr.set(fw, dy, -4, Blocks.STONE_BRICKS);
                }
                for (int lt = -4; lt <= 2; lt++) {
                    int h = 1 + fr.roll(lt, 1, 899, 3);
                    for (int dy = 0; dy < h; dy++) fr.set(-5, dy, lt, Blocks.MOSSY_STONE_BRICKS);
                }
                for (int fw = 0; fw <= 4; fw++) fr.set(fw, 0, 2, Deco.pillar(Blocks.QUARTZ_PILLAR, fr.f.getAxis()));
                fr.container(-4, 0, -3, Deco.chest(fr.f), Deco.LOOT_MINOR);
            }
        }
    }
}
