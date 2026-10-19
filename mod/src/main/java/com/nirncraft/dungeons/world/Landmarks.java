package com.nirncraft.dungeons.world;

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
        for (int lt = -3; lt <= 2; lt++) for (int dy = 4; dy <= 6; dy++) if (fr.chance(lt, dy, 863, 3) || dy == 6) fr.set(1, dy, lt + 1, com.nirncraft.worldgen.VanillaBlocks.WHITE_WOOL);
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
        fr.set(3, 0, -3, Deco.candle(com.nirncraft.worldgen.VanillaBlocks.GREEN_CANDLE, 3, true));
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
                fr.set(0, 6, 1, com.nirncraft.worldgen.VanillaBlocks.WHITE_WOOL);
                fr.set(0, 5, 1, com.nirncraft.worldgen.VanillaBlocks.WHITE_WOOL);
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
                Towers.tent(fr, 6, -2, com.nirncraft.worldgen.VanillaBlocks.BROWN_WOOL.defaultBlockState());
                fr.container(7, 0, 2, Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
            }
            case DWEMER, BRIDGE -> {
                // Dwemer stonework: a broken bronze-trimmed platform and pillars, pipes in the ground
                fr.fill(-6, -1, -4, 6, -1, 4, Blocks.POLISHED_BLACKSTONE_BRICKS);
                for (int fw = -6; fw <= 6; fw += 4) {
                    for (int lt : new int[]{-4, 4}) {
                        int h = 3 + fr.roll(fw, lt, 893, 5);
                        for (int dy = 0; dy < h; dy++) fr.set(fw, dy, lt, dy == h - 1 ? com.nirncraft.worldgen.VanillaBlocks.WAXED_COPPER_BLOCK.defaultBlockState() : com.nirncraft.worldgen.VanillaBlocks.WAXED_CUT_COPPER.defaultBlockState());
                    }
                }
                for (int fw = -5; fw <= 5; fw++) fr.set(fw, -1, 0, com.nirncraft.worldgen.VanillaBlocks.WAXED_EXPOSED_CUT_COPPER);
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
            case RED_MOUNTAIN -> redMountainCitadel(fr, c);
            case CRYSTAL_LAW -> crystalLikeLaw(fr, c);
            case FALINESTI_AUTUMN, FALINESTI_WINTER, FALINESTI_SPRING, FALINESTI_SUMMER -> falinestiSite(fr, c, style);
            case ETON_NIR -> etonNirCloudrest(fr, c);
            case BAAR_DAU -> baarDauMeteor(fr, c);
            case ADAMANTINE_UR_TOWER -> adamantineUrTower(fr, c);
            case ORICHALC_TOWER -> orichalcTowerRuin(fr, c);
            case CORAL_PINNACLE -> coralPinnacle(fr, c);
            case SUNSPIRE_SUMMIT -> sunspireSummit(fr, c);
            case HELSTROM_HIST -> helstromHistMother(fr, c);
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

    // ------------------------------------------------------------------ monumental world wonders

    /**
     * Red Mountain Summit Caldera, Dagoth Ur Dwemer-Volcanic Citadel & Ghostfence Pylons:
     * volcanic crater basin with magma fissures, a bronze-spanned Dwemer citadel keep rising 58 blocks above the
     * caldera floor, and 8 perimeter Ghostfence bone-and-brass pylons linked by arcane barrier beams.
     */
    private static void redMountainCitadel(Frame fr, DungeonPiece c) {
        Painter p = fr.p;
        fr.all((fw, lt, x, z) -> {
            double r = Math.hypot(fw, lt);
            if (r > 30) return;
            p.clear(x, fr.base, z, fr.base + 64);
            // Volcanic caldera rim and lava moat ring (11 <= r <= 16)
            if (r >= 22) {
                int rimH = (int) Math.round((r - 22) * 1.6 + p.noise(x, 0, z, 3.0, 901) * 2.5);
                for (int dy = -2; dy <= rimH; dy++) {
                    p.set(x, fr.base + dy, z, dy == rimH && p.chance(x, dy, z, 902, 5)
                            ? Blocks.MAGMA_BLOCK.defaultBlockState()
                            : p.chance(x, dy, z, 903, 3) ? Blocks.BLACKSTONE.defaultBlockState() : Blocks.BASALT.defaultBlockState());
                }
            } else if (r >= 11 && r <= 16.5 && Math.abs(lt) > 3) {
                p.set(x, fr.base - 3, z, Blocks.BLACKSTONE.defaultBlockState());
                p.set(x, fr.base - 2, z, Blocks.MAGMA_BLOCK.defaultBlockState());
                p.set(x, fr.base - 1, z, Blocks.LAVA.defaultBlockState());
            } else {
                p.set(x, fr.base - 1, z, r < 11 ? Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState() : Blocks.BASALT.defaultBlockState());
                p.foundation(x, fr.base - 2, z, Blocks.BLACKSTONE.defaultBlockState(), 14);
            }
            // Central Dwemer-Volcanic Citadel tower (r <= 10.5)
            if (r <= 10.5) {
                int top = r <= 5.5 ? 54 : r <= 8.0 ? 36 : 20;
                boolean wall = Math.abs(r - 10.0) < 0.85 || (r <= 8.0 && Math.abs(r - 7.5) < 0.8) || (r <= 5.5 && Math.abs(r - 5.0) < 0.8);
                boolean gate = (Math.abs(lt) <= 2 && fw > 0) || (Math.abs(fw) <= 2 && lt > 0);
                for (int dy = 0; dy <= top; dy++) {
                    if (gate && dy <= 4 && r > 6.0) continue;
                    if (dy == 20 || dy == 36 || dy == top) {
                        p.set(x, fr.base + dy, z, (dy % 16 == 0) ? com.nirncraft.worldgen.VanillaBlocks.WAXED_CUT_COPPER.defaultBlockState() : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
                    } else if (wall) {
                        BlockState bs = (dy % 6 == 0) ? com.nirncraft.worldgen.VanillaBlocks.WAXED_COPPER_BLOCK.defaultBlockState()
                                : (dy % 9 == 4) ? Blocks.OCHRE_FROGLIGHT.defaultBlockState()
                                : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
                        p.set(x, fr.base + dy, z, bs);
                    }
                }
            }
        });
        // Dwemer causeway bridge over the lava ring (+fw and -fw)
        for (int fw = -21; fw <= 21; fw++) {
            if (Math.abs(fw) < 10) continue;
            for (int lt = -3; lt <= 3; lt++) {
                fr.set(fw, 0, lt, Math.abs(lt) == 3 ? com.nirncraft.worldgen.VanillaBlocks.WAXED_CUT_COPPER : Blocks.POLISHED_BLACKSTONE_BRICKS);
                if (Math.abs(lt) == 3 && Math.abs(fw) % 3 == 0) {
                    fr.set(fw, 1, lt, Blocks.POLISHED_BLACKSTONE_BRICK_WALL);
                    fr.set(fw, 2, lt, Blocks.SOUL_LANTERN);
                }
            }
        }
        // Heart of Lorkhan / Akulakhan brass resonator core at center
        for (int dy = 1; dy <= 58; dy++) {
            fr.set(0, dy, 0, dy >= 54 ? Blocks.SHROOMLIGHT.defaultBlockState()
                    : dy % 4 == 0 ? Blocks.REDSTONE_BLOCK.defaultBlockState() : Deco.pillar(Blocks.POLISHED_BASALT, Direction.Axis.Y));
        }
        // 8 Ghostfence Pylons around the caldera rim (r = 25)
        int[][] pylons = new int[8][2];
        for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI / 4.0;
            int pfw = (int) Math.round(Math.cos(ang) * 25);
            int plt = (int) Math.round(Math.sin(ang) * 25);
            pylons[i][0] = pfw;
            pylons[i][1] = plt;
            for (int df = -1; df <= 1; df++) for (int dl = -1; dl <= 1; dl++) {
                for (int dy = 0; dy <= 24; dy++) {
                    BlockState bs = (df == 0 && dl == 0)
                            ? (dy >= 21 ? Blocks.SEA_LANTERN.defaultBlockState() : Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.Y))
                            : (dy < 16 && (df == 0 || dl == 0)) ? com.nirncraft.worldgen.VanillaBlocks.WAXED_CUT_COPPER.defaultBlockState() : null;
                    if (bs != null) fr.set(pfw + df, dy, plt + dl, bs);
                }
            }
        }
        // Arcane Ghostfence energy barrier segments between pylons at dy = 19
        for (int i = 0; i < 8; i++) {
            int[] a = pylons[i], b = pylons[(i + 1) % 8];
            for (int s = 2; s <= 10; s++) {
                int bfw = a[0] + (b[0] - a[0]) * s / 12;
                int blt = a[1] + (b[1] - a[1]) * s / 12;
                fr.set(bfw, 19, blt, com.nirncraft.worldgen.VanillaBlocks.LIGHT_BLUE_STAINED_GLASS);
                fr.set(bfw, 20, blt, com.nirncraft.worldgen.VanillaBlocks.CYAN_STAINED_GLASS_PANE);
            }
        }
        fr.container(3, 1, 3, Deco.chest(fr.f), Deco.LOOT_BOSS);
    }

    /**
     * Crystal-Like-Law (The Crystal Tower of Summerset):
     * terraced white-quartz and prismarine gardens with reflecting pools surrounding a 136-block faceted translucent
     * spire of quartz, amethyst, sea lanterns, and stained glass crowned by Transparent Law.
     */
    private static void crystalLikeLaw(Frame fr, DungeonPiece c) {
        Painter p = fr.p;
        fr.all((fw, lt, x, z) -> {
            double r = Math.hypot(fw, lt);
            if (r > 30) return;
            p.clear(x, fr.base, z, fr.base + 142);
            // Stepped marble & prismarine arcane garden plaza
            int terrace = r < 14 ? 4 : r < 22 ? 2 : 0;
            for (int dy = -2; dy <= terrace; dy++) {
                boolean pool = terrace == 2 && r >= 15.5 && r <= 19.5 && Math.abs(fw) > 3 && Math.abs(lt) > 3 && dy == terrace;
                p.set(x, fr.base + dy, z, pool ? Blocks.WATER.defaultBlockState()
                        : (dy == terrace && (int) Math.round(r) % 4 == 0) ? Blocks.PRISMARINE_BRICKS.defaultBlockState()
                        : Blocks.SMOOTH_QUARTZ.defaultBlockState());
            }
            p.foundation(x, fr.base - 3, z, Blocks.QUARTZ_BRICKS.defaultBlockState(), 14);
            // Faceted multi-tier Crystal Spire (height 136)
            for (int dy = terrace + 1; dy <= 136; dy++) {
                double progress = (dy - 4) / 132.0;
                double ang = Math.atan2(lt, fw);
                // 8-faceted star profile tapering toward the apex
                double facet = 1.0 + 0.14 * Math.cos(8 * ang);
                double maxR = (11.5 * Math.pow(1.0 - progress, 0.72) + 1.2) * facet;
                if (r > maxR) continue;
                boolean shell = r >= maxR - 1.15;
                boolean rib = Math.abs(Math.sin(4 * ang)) < 0.16;
                boolean ring = (dy % 16 == 0);
                if (r <= 1.6) {
                    p.set(x, fr.base + dy, z, dy % 3 == 0 ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.AMETHYST_BLOCK.defaultBlockState());
                } else if (shell || ring) {
                    BlockState bs;
                    if (ring || rib) {
                        bs = (dy % 8 == 0) ? Blocks.SEA_LANTERN.defaultBlockState() : Deco.pillar(Blocks.QUARTZ_PILLAR, Direction.Axis.Y);
                    } else if (dy > 96) {
                        bs = com.nirncraft.worldgen.VanillaBlocks.WHITE_STAINED_GLASS.defaultBlockState();
                    } else if (dy > 52) {
                        bs = ((dy + fw + lt) & 1) == 0 ? com.nirncraft.worldgen.VanillaBlocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState() : com.nirncraft.worldgen.VanillaBlocks.WHITE_STAINED_GLASS.defaultBlockState();
                    } else {
                        bs = ((dy + fw) & 1) == 0 ? com.nirncraft.worldgen.VanillaBlocks.CYAN_STAINED_GLASS.defaultBlockState() : Blocks.CALCITE.defaultBlockState();
                    }
                    p.set(x, fr.base + dy, z, bs);
                } else if (dy % 16 == 1 && r < maxR - 1.5) {
                    p.set(x, fr.base + dy, z, Blocks.SMOOTH_QUARTZ.defaultBlockState());
                }
            }
        });
        // Transparent Law apex crystal at the summit (dy = 137..141)
        for (int dy = 137; dy <= 141; dy++) {
            fr.set(0, dy, 0, dy == 141 ? Blocks.BEACON : Blocks.SEA_LANTERN);
        }
        // 8 Welkynd crystal obelisks around the outer terrace (r = 25)
        for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI / 4.0;
            int ofw = (int) Math.round(Math.cos(ang) * 25);
            int olt = (int) Math.round(Math.sin(ang) * 25);
            for (int dy = 1; dy <= 12; dy++) {
                fr.set(ofw, dy, olt, dy == 12 ? Blocks.SEA_LANTERN.defaultBlockState()
                        : dy >= 9 ? com.nirncraft.worldgen.VanillaBlocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState()
                        : Deco.pillar(Blocks.QUARTZ_PILLAR, Direction.Axis.Y));
            }
        }
        fr.container(0, 5, 4, Deco.chest(fr.f), Deco.LOOT_BOSS);
    }

    /**
     * The Four Seasonal Sites of Falinesti (Autumn, Winter, Spring, Summer):
     * colossal Graht-oak root basin, sacred sap pool, buttress root arches, and multi-tiered seasonal canopy platforms.
     */
    private static void falinestiSite(Frame fr, DungeonPiece c, SiteParams.Style style) {
        Painter p = fr.p;
        BlockState wood = switch (style) {
            case FALINESTI_WINTER -> Deco.pillar(Blocks.BIRCH_LOG, Direction.Axis.Y);
            case FALINESTI_AUTUMN -> Deco.pillar(Blocks.DARK_OAK_LOG, Direction.Axis.Y);
            case FALINESTI_SPRING -> Deco.pillar(Blocks.MANGROVE_LOG, Direction.Axis.Y);
            default -> Deco.pillar(Blocks.JUNGLE_LOG, Direction.Axis.Y);
        };
        BlockState leafMain = switch (style) {
            case FALINESTI_AUTUMN -> com.nirncraft.worldgen.VanillaBlocks.YELLOW_TERRACOTTA.defaultBlockState();
            case FALINESTI_WINTER -> Blocks.SNOW_BLOCK.defaultBlockState();
            case FALINESTI_SPRING -> Blocks.CHERRY_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
            default -> Blocks.JUNGLE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        };
        BlockState leafAccent = switch (style) {
            case FALINESTI_AUTUMN -> com.nirncraft.worldgen.VanillaBlocks.ORANGE_TERRACOTTA.defaultBlockState();
            case FALINESTI_WINTER -> Blocks.PACKED_ICE.defaultBlockState();
            case FALINESTI_SPRING -> Blocks.FLOWERING_AZALEA_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
            default -> Blocks.AZALEA_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
        };
        BlockState sapBlock = switch (style) {
            case FALINESTI_AUTUMN -> Blocks.HONEY_BLOCK.defaultBlockState();
            case FALINESTI_WINTER -> Blocks.BLUE_ICE.defaultBlockState();
            case FALINESTI_SPRING -> Blocks.VERDANT_FROGLIGHT.defaultBlockState();
            default -> Blocks.OCHRE_FROGLIGHT.defaultBlockState();
        };
        BlockState glow = (style == SiteParams.Style.FALINESTI_WINTER)
                ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.SHROOMLIGHT.defaultBlockState();

        fr.all((fw, lt, x, z) -> {
            double r = Math.hypot(fw, lt);
            if (r > 30) return;
            p.clear(x, fr.base, z, fr.base + 82);
            double ang = Math.atan2(lt, fw);
            // Root basin floor & central sacred sap pool
            if (r < 6.5) {
                p.set(x, fr.base - 2, z, Blocks.ROOTED_DIRT.defaultBlockState());
                p.set(x, fr.base - 1, z, r < 4.5 ? sapBlock : Blocks.MOSS_BLOCK.defaultBlockState());
            } else {
                p.set(x, fr.base - 1, z, style == SiteParams.Style.FALINESTI_WINTER && r > 14
                        ? Blocks.SNOW_BLOCK.defaultBlockState()
                        : Blocks.MOSS_BLOCK.defaultBlockState());
                p.foundation(x, fr.base - 2, z, Blocks.ROOTED_DIRT.defaultBlockState(), 12);
            }
            // 6 colossal buttress roots curving inward and rising into the Graht-oak trunk
            double rootWave = Math.cos(6 * ang);
            for (int dy = 0; dy <= 56; dy++) {
                double twist = ang + dy * 0.035;
                double buttress = 1.0 + 0.38 * Math.max(0, Math.cos(6 * twist)) * Math.max(0, 1.0 - dy / 36.0);
                double outerR = (dy < 18 ? 11.0 - dy * 0.22 : 7.0 - (dy - 18) * 0.06) * buttress;
                double innerR = dy < 16 ? 5.5 : 3.6;
                if (r <= outerR && r >= innerR) {
                    boolean doorway = dy <= 5 && rootWave < -0.55;
                    if (!doorway) p.set(x, fr.base + dy, z, wood);
                } else if (r < innerR && (dy == 16 || dy == 32 || dy == 48)) {
                    // Interior Bosmer pod floors
                    p.set(x, fr.base + dy, z, Blocks.STRIPPED_DARK_OAK_WOOD.defaultBlockState());
                }
            }
            // Multi-tiered Graht-oak canopy crown (dy = 42..76)
            for (int dy = 42; dy <= 76; dy++) {
                double d1 = Math.hypot(r / 1.45, (dy - 54) * 1.35);
                double d2 = Math.hypot(r / 1.25, (dy - 66) * 1.45);
                if (d1 <= 19.5 || d2 <= 15.5) {
                    if (dy == 48 || dy == 62) {
                        if (r <= 18.0) p.set(x, fr.base + dy, z, r > 16.5 ? Blocks.OAK_FENCE.defaultBlockState() : Blocks.DARK_OAK_PLANKS.defaultBlockState());
                    } else if (d1 >= 16.0 || d2 >= 12.5 || dy >= 70) {
                        BlockState lb = ((fw + lt + dy) % 7 == 0) ? glow : ((fw * 3 + lt + dy) & 1) == 0 ? leafMain : leafAccent;
                        p.set(x, fr.base + dy, z, lb);
                    }
                }
            }
            // Outer root-arch ring (r ≈ 24)
            if (Math.abs(r - 24.0) < 1.3 && rootWave > 0.25) {
                int archH = (int) Math.round(10 * rootWave);
                for (int dy = 0; dy <= archH; dy++) {
                    p.set(x, fr.base + dy, z, dy == archH ? glow : Blocks.MANGROVE_ROOTS.defaultBlockState());
                }
            }
        });
        fr.container(0, 17, 0, Deco.chest(fr.f), Deco.LOOT_BOSS);
        fr.spawn(Mobs.spriggan(), 5, 0, 5, fr.f, null);
        fr.spawn(Mobs.spriggan(), -5, 0, -5, fr.f, null);
    }

    /**
     * Eton Nir & Cloudrest Alaxon Aerie:
     * high-altitude Altmer marble-and-crystal spire rising 98 blocks with four cantilevered gryphon roost platforms.
     */
    private static void etonNirCloudrest(Frame fr, DungeonPiece c) {
        Painter p = fr.p;
        fr.all((fw, lt, x, z) -> {
            double r = Math.hypot(fw, lt);
            if (r > 29) return;
            p.clear(x, fr.base, z, fr.base + 104);
            if (r <= 22) {
                p.set(x, fr.base - 1, z, ((fw + lt) & 1) == 0 ? Blocks.SMOOTH_QUARTZ.defaultBlockState() : Blocks.CALCITE.defaultBlockState());
                p.foundation(x, fr.base - 2, z, Blocks.QUARTZ_BRICKS.defaultBlockState(), 16);
            }
            // Soaring gothic Altmer spire
            for (int dy = 0; dy <= 96; dy++) {
                double maxR = 9.5 * Math.pow(1.0 - dy / 100.0, 0.75) + 1.0;
                if (r <= maxR) {
                    if (r >= maxR - 1.1) {
                        BlockState bs = (dy % 10 == 0) ? Blocks.GOLD_BLOCK.defaultBlockState()
                                : (dy % 5 == 0) ? Blocks.SEA_LANTERN.defaultBlockState()
                                : (Math.abs(fw) <= 1 || Math.abs(lt) <= 1) ? com.nirncraft.worldgen.VanillaBlocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState()
                                : Deco.pillar(Blocks.QUARTZ_PILLAR, Direction.Axis.Y);
                        if (!(dy <= 4 && (Math.abs(fw) <= 1 || Math.abs(lt) <= 1))) {
                            p.set(x, fr.base + dy, z, bs);
                        }
                    } else if (dy % 16 == 0) {
                        p.set(x, fr.base + dy, z, Blocks.SMOOTH_QUARTZ.defaultBlockState());
                    }
                }
            }
        });
        // 4 cantilevered Gryphon Roost sky-platforms at dy = 28 in the 4 cardinal offsets
        int[][] roosts = {{18, 0}, {-18, 0}, {0, 18}, {0, -18}};
        for (int[] rt : roosts) {
            for (int df = -5; df <= 5; df++) for (int dl = -5; dl <= 5; dl++) {
                double d = Math.hypot(df, dl);
                if (d <= 5.2) {
                    fr.set(rt[0] + df, 27, rt[1] + dl, Blocks.QUARTZ_BRICKS);
                    fr.set(rt[0] + df, 28, rt[1] + dl, d <= 2.5 ? Blocks.HAY_BLOCK : Blocks.SMOOTH_QUARTZ);
                    if (d >= 4.2) fr.set(rt[0] + df, 29, rt[1] + dl, Blocks.GOLD_BLOCK);
                }
            }
            // Bridge from roost to central spire at dy = 28
            int stepF = Integer.signum(rt[0]), stepL = Integer.signum(rt[1]);
            for (int s = 6; s <= 13; s++) {
                fr.set(stepF * s, 28, stepL * s, Blocks.SMOOTH_QUARTZ);
            }
        }
        fr.container(0, 17, 0, Deco.chest(fr.f), Deco.LOOT_BOSS);
    }

    /**
     * Baar Dau (The Ministry of Truth):
     * hollowed levitating meteor suspended above a Temple plaza deck with chains, Velothi scaffolding, and prison cells.
     */
    private static void baarDauMeteor(Frame fr, DungeonPiece c) {
        Painter p = fr.p;
        fr.all((fw, lt, x, z) -> {
            double r = Math.hypot(fw, lt);
            if (r > 28) return;
            p.clear(x, fr.base, z, fr.base + 72);
            // Temple canton plaza deck at base
            if (r <= 20) {
                p.set(x, fr.base - 1, z, r > 18 ? com.nirncraft.worldgen.VanillaBlocks.WAXED_CUT_COPPER.defaultBlockState() : Blocks.SMOOTH_SANDSTONE.defaultBlockState());
                p.foundation(x, fr.base - 2, z, Blocks.SANDSTONE.defaultBlockState(), 12);
            }
            // Levitating asteroid body (dy = 18..58)
            for (int dy = 16; dy <= 60; dy++) {
                double dz = (dy - 38) / 20.0;
                double n = p.noise(x, dy, z, 4.5, 920) * 2.2;
                double rad = 16.5 * Math.sqrt(Math.max(0, 1.0 - dz * dz)) + n;
                if (r <= rad) {
                    if (r >= rad - 2.2) {
                        BlockState rock = p.chance(x, dy, z, 921, 5) ? Blocks.MAGMA_BLOCK.defaultBlockState()
                                : p.chance(x, dy, z, 922, 3) ? Blocks.BLACKSTONE.defaultBlockState()
                                : Blocks.DEEPSLATE.defaultBlockState();
                        p.set(x, fr.base + dy, z, rock);
                    } else if (dy == 26 || dy == 36 || dy == 46) {
                        if (r > 3.5) p.set(x, fr.base + dy, z, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                    } else if (Math.abs(r - 8.0) < 0.75 && (dy > 26 && dy < 46)) {
                        p.set(x, fr.base + dy, z, Blocks.IRON_BARS.defaultBlockState());
                    }
                }
            }
        });
        // Mooring chains & suspended Ordinator scaffolding from plaza to asteroid belly
        int[][] anchors = {{10, 10}, {-10, 10}, {10, -10}, {-10, -10}};
        for (int[] a : anchors) {
            for (int dy = 0; dy <= 24; dy++) {
                fr.set(a[0], dy, a[1], dy < 4 ? com.nirncraft.worldgen.VanillaBlocks.WAXED_COPPER_BLOCK.defaultBlockState() : com.nirncraft.worldgen.VanillaBlocks.CHAIN.defaultBlockState());
            }
        }
        fr.set(0, 37, 0, Blocks.SOUL_LANTERN);
        fr.container(0, 27, 5, Deco.chest(fr.f), Deco.LOOT_BOSS);
    }

    /**
     * Adamantine Tower (Direnni Tower / Ur-Tower, Isle of Balfiera):
     * octagonal Direnni keep courtyard surrounding a 140-block fluted metallic cylinder-spire.
     */
    private static void adamantineUrTower(Frame fr, DungeonPiece c) {
        Painter p = fr.p;
        fr.all((fw, lt, x, z) -> {
            double r = Math.hypot(fw, lt);
            if (r > 30) return;
            p.clear(x, fr.base, z, fr.base + 144);
            // Direnni courtyard bastion (r <= 24)
            if (r <= 24) {
                p.set(x, fr.base - 1, z, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                p.foundation(x, fr.base - 2, z, Blocks.DEEPSLATE_BRICKS.defaultBlockState(), 16);
                if (Math.abs(r - 23.0) < 1.1 && !(Math.abs(lt) <= 2 && fw > 0)) {
                    for (int dy = 0; dy <= 10; dy++) {
                        p.set(x, fr.base + dy, z, dy == 10 ? Blocks.SMOOTH_QUARTZ.defaultBlockState() : Blocks.DEEPSLATE_BRICKS.defaultBlockState());
                    }
                }
            }
            // Fluted metallic Ur-Tower cylinder-spire (r <= 11.5, height 140)
            double ang = Math.atan2(lt, fw);
            double flute = 1.0 + 0.12 * Math.cos(12 * ang);
            for (int dy = 0; dy <= 140; dy++) {
                double maxR = (dy < 90 ? 10.5 - dy * 0.03 : 7.8 - (dy - 90) * 0.11) * flute;
                if (r > maxR) continue;
                boolean rib = Math.cos(12 * ang) > 0.72;
                if (r <= 1.8) {
                    // Zero-Stone Aedric conduit column
                    p.set(x, fr.base + dy, z, dy % 4 == 0 ? Blocks.SEA_LANTERN.defaultBlockState() : Blocks.IRON_BLOCK.defaultBlockState());
                } else if (r >= maxR - 1.2) {
                    if (!(dy <= 5 && Math.abs(lt) <= 1 && fw > 0)) {
                        BlockState bs = rib ? Blocks.IRON_BLOCK.defaultBlockState()
                                : (dy % 14 == 0) ? Blocks.SMOOTH_QUARTZ.defaultBlockState()
                                : Blocks.POLISHED_DEEPSLATE.defaultBlockState();
                        p.set(x, fr.base + dy, z, bs);
                    }
                } else if (dy % 14 == 0) {
                    p.set(x, fr.base + dy, z, Blocks.DEEPSLATE_TILES.defaultBlockState());
                }
            }
        });
        fr.container(0, 1, 4, Deco.chest(fr.f), Deco.LOOT_BOSS);
    }

    /**
     * Orichalc Tower of Yokuda:
     * sunken Sword-Singer red-sandstone ziggurat and fractured helical orichalc-bronze observatory spire.
     */
    private static void orichalcTowerRuin(Frame fr, DungeonPiece c) {
        Painter p = fr.p;
        fr.all((fw, lt, x, z) -> {
            double r = Math.hypot(fw, lt);
            if (r > 28) return;
            p.clear(x, fr.base, z, fr.base + 92);
            int step = r < 12 ? 6 : r < 19 ? 3 : 0;
            for (int dy = -2; dy <= step; dy++) {
                p.set(x, fr.base + dy, z, dy == step ? Blocks.CUT_RED_SANDSTONE.defaultBlockState() : Blocks.RED_SANDSTONE.defaultBlockState());
            }
            p.foundation(x, fr.base - 3, z, Blocks.RED_SANDSTONE.defaultBlockState(), 14);
            double ang = Math.atan2(lt, fw);
            for (int dy = step + 1; dy <= 86; dy++) {
                double maxR = 9.0 * (1.0 - dy / 105.0);
                // Helical fracture gap spiraling up the ruined tower
                double spiral = Math.sin(ang * 2 - dy * 0.11);
                if (dy > 38 && spiral > 0.55) continue;
                if (r <= maxR && r >= maxR - 1.3) {
                    BlockState bs = (dy % 5 == 0) ? Blocks.RAW_GOLD_BLOCK.defaultBlockState()
                            : (dy % 3 == 0) ? com.nirncraft.worldgen.VanillaBlocks.WAXED_COPPER_BLOCK.defaultBlockState()
                            : Blocks.CUT_RED_SANDSTONE.defaultBlockState();
                    p.set(x, fr.base + dy, z, bs);
                } else if (r <= 1.5 && dy <= 76) {
                    p.set(x, fr.base + dy, z, dy % 4 == 0 ? Blocks.OCHRE_FROGLIGHT.defaultBlockState() : Blocks.GOLD_BLOCK.defaultBlockState());
                }
            }
        });
        fr.container(0, 7, 3, Deco.chest(fr.f), Deco.LOOT_BOSS);
    }

    /**
     * Coral Kingdom Pinnacle of Thras:
     * living abyssal reef-spire of vibrant coral blocks, prismarine, and sea lanterns rising 92 blocks.
     */
    private static void coralPinnacle(Frame fr, DungeonPiece c) {
        Painter p = fr.p;
        BlockState[] corals = {
                Blocks.TUBE_CORAL_BLOCK.defaultBlockState(),
                Blocks.BRAIN_CORAL_BLOCK.defaultBlockState(),
                Blocks.BUBBLE_CORAL_BLOCK.defaultBlockState(),
                Blocks.FIRE_CORAL_BLOCK.defaultBlockState(),
                Blocks.HORN_CORAL_BLOCK.defaultBlockState()
        };
        fr.all((fw, lt, x, z) -> {
            double r = Math.hypot(fw, lt);
            if (r > 28) return;
            p.clear(x, fr.base, z, fr.base + 96);
            if (r >= 11 && r <= 20) {
                p.set(x, fr.base - 2, z, Blocks.PRISMARINE.defaultBlockState());
                p.set(x, fr.base - 1, z, Blocks.WATER.defaultBlockState());
            } else {
                p.set(x, fr.base - 1, z, Blocks.DARK_PRISMARINE.defaultBlockState());
                p.foundation(x, fr.base - 2, z, Blocks.PRISMARINE_BRICKS.defaultBlockState(), 14);
            }
            double ang = Math.atan2(lt, fw);
            for (int dy = 0; dy <= 92; dy++) {
                double lobe = 1.0 + 0.25 * Math.sin(5 * ang + dy * 0.08);
                double maxR = (10.5 * (1.0 - dy / 104.0) + 1.2) * lobe;
                if (r <= maxR && r >= maxR - 1.5) {
                    BlockState bs = (dy % 7 == 0) ? Blocks.SEA_LANTERN.defaultBlockState()
                            : corals[Math.floorMod(fw + lt + dy, corals.length)];
                    p.set(x, fr.base + dy, z, bs);
                } else if (r < 2.2 && dy % 6 == 0) {
                    p.set(x, fr.base + dy, z, Blocks.SEA_LANTERN.defaultBlockState());
                }
            }
        });
        fr.container(0, 1, 0, Deco.chest(fr.f), Deco.LOOT_BOSS);
    }

    /**
     * Sunspire Summit Dragon-Temple of Alkosh:
     * golden-domed Elsweyr temple with four solar-prism obelisks and the Time-Wound Dragon Altar.
     */
    private static void sunspireSummit(Frame fr, DungeonPiece c) {
        Painter p = fr.p;
        fr.all((fw, lt, x, z) -> {
            double r = Math.hypot(fw, lt);
            if (r > 28) return;
            p.clear(x, fr.base, z, fr.base + 88);
            int step = r < 12 ? 6 : r < 20 ? 3 : 0;
            for (int dy = -2; dy <= step; dy++) {
                p.set(x, fr.base + dy, z, dy == step && ((fw + lt) & 3) == 0
                        ? Blocks.GOLD_BLOCK.defaultBlockState() : Blocks.SMOOTH_SANDSTONE.defaultBlockState());
            }
            p.foundation(x, fr.base - 3, z, Blocks.SANDSTONE.defaultBlockState(), 14);
            // Domed colonnade & golden sun-spire (dy = 7..84)
            for (int dy = 7; dy <= 84; dy++) {
                double maxR = dy < 32 ? 11.0 : 11.0 * Math.pow(1.0 - (dy - 32) / 56.0, 0.7) + 0.8;
                if (r <= maxR && r >= maxR - 1.2) {
                    if (dy < 22 && (Math.abs(fw) <= 2 || Math.abs(lt) <= 2)) continue;
                    BlockState bs = dy >= 32
                            ? ((dy % 4 == 0) ? Blocks.SHROOMLIGHT.defaultBlockState() : Blocks.GOLD_BLOCK.defaultBlockState())
                            : Deco.pillar(Blocks.QUARTZ_PILLAR, Direction.Axis.Y);
                    p.set(x, fr.base + dy, z, bs);
                }
            }
        });
        // Time-Wound Dragon Altar at center
        fr.set(0, 7, 0, Blocks.GOLD_BLOCK);
        fr.set(0, 8, 0, Blocks.BEACON);
        fr.container(0, 7, 3, Deco.chest(fr.f), Deco.LOOT_BOSS);
    }

    /**
     * Great Hist Mother-Tree of Helstrom:
     * impenetrable outer root-dome sanctuary surrounding a glowing amber Hist-sap basin and ancient Xanmeer shrine.
     */
    private static void helstromHistMother(Frame fr, DungeonPiece c) {
        Painter p = fr.p;
        fr.all((fw, lt, x, z) -> {
            double r = Math.hypot(fw, lt);
            if (r > 30) return;
            p.clear(x, fr.base, z, fr.base + 78);
            // Hist-sap inner lagoon (r <= 11)
            if (r >= 4.5 && r <= 11.5) {
                p.set(x, fr.base - 2, z, Blocks.SHROOMLIGHT.defaultBlockState());
                p.set(x, fr.base - 1, z, com.nirncraft.worldgen.VanillaBlocks.ORANGE_STAINED_GLASS.defaultBlockState());
            } else {
                p.set(x, fr.base - 1, z, Blocks.MUD_BRICKS.defaultBlockState());
                p.foundation(x, fr.base - 2, z, Blocks.MUD.defaultBlockState(), 14);
            }
            // Impenetrable outer Hist root-dome cage (r ≈ 24..28)
            for (int dy = 0; dy <= 34; dy++) {
                double domeR = 26.0 * Math.sqrt(Math.max(0, 1.0 - (dy / 36.0) * (dy / 36.0)));
                if (Math.abs(r - domeR) < 1.25 && !(dy <= 4 && Math.abs(lt) <= 2 && fw > 0)) {
                    if (((fw + lt + dy) % 3) != 0) {
                        p.set(x, fr.base + dy, z, Blocks.MANGROVE_ROOTS.defaultBlockState());
                    }
                }
            }
            // Central Hist Mother-Tree trunk and weeping amber crown (dy = 0..74)
            for (int dy = 0; dy <= 74; dy++) {
                double trunkR = dy < 16 ? 4.8 - dy * 0.08 : 3.4;
                if (r <= trunkR && dy <= 52) {
                    p.set(x, fr.base + dy, z, dy % 6 == 0 ? Blocks.SHROOMLIGHT.defaultBlockState()
                            : Deco.pillar(Blocks.MANGROVE_LOG, Direction.Axis.Y));
                }
                if (dy >= 40) {
                    double crown = Math.hypot(r / 1.4, (dy - 58) * 1.4);
                    if (crown <= 18.0 && crown >= 13.5) {
                        p.set(x, fr.base + dy, z, ((fw + lt + dy) % 5 == 0)
                                ? Blocks.OCHRE_FROGLIGHT.defaultBlockState()
                                : Blocks.MANGROVE_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
                    }
                }
            }
        });
        fr.container(0, 1, 5, Deco.chest(fr.f), Deco.LOOT_BOSS);
    }
}
