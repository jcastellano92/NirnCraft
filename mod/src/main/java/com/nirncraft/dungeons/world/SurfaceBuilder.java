package com.nirncraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * Surface sites built column by column: the ruined fort (curtain walls, corner towers, keep with the cellar stairs,
 * camp in the courtyard), the giant camp, the dragon lair mound with its Word Wall, and the Daedric shrine.
 */
public final class SurfaceBuilder {
    private SurfaceBuilder() {}

    /** Local frame: fwd towards the site's facing, lat to its right. */
    private record Spot(Painter p, DungeonPiece c, int cx, int cz, int base, Direction f, Direction r) {
        int x(int fwd, int lat) {
            return cx + f.getStepX() * fwd + r.getStepX() * lat;
        }

        int z(int fwd, int lat) {
            return cz + f.getStepZ() * fwd + r.getStepZ() * lat;
        }

        int fwd(int x, int z) {
            return (x - cx) * f.getStepX() + (z - cz) * f.getStepZ();
        }

        int lat(int x, int z) {
            return (x - cx) * r.getStepX() + (z - cz) * r.getStepZ();
        }

        void set(int fwd, int y, int lat, BlockState s) {
            p.set(x(fwd, lat), y, z(fwd, lat), s);
        }

        void spawn(String id, int fwd, int y, int lat, Direction look, String name) {
            p.spawn(id, x(fwd, lat), y, z(fwd, lat), Painter.yaw(look), name);
        }
    }

    public static void build(Painter p, DungeonPiece c, RandomSource rnd) {
        BoundingBox b = c.getBoundingBox();
        Direction f = c.facing();
        if (c.special != Special.NONE && c.theme.underground()) {
            Exteriors.build(p, c);
            return;
        }
        Frame frame = new Frame(p, (b.minX() + b.maxX()) / 2, (b.minZ() + b.maxZ()) / 2, c.floor, f);
        switch (c.theme) {
            case FORT, BRETON_KEEP_RUIN, GIANT_CAMP, DRAGON_LAIR, DAEDRIC_SHRINE -> {
            }
            case WATCHTOWER, LIGHTHOUSE, MAGE_TOWER, GREAT_LIFT -> {
                Towers.build(frame, c);
                return;
            }
            case BANDIT_CAMP, FORSWORN_CAMP, MILITARY_CAMP, CABIN -> {
                Camps.build(frame, c);
                return;
            }
            case SHIPWRECK, GROVE, LANDMARK, NORDIC_RUIN -> {
                Landmarks.build(frame, c);
                return;
            }
            case STANDING_STONE, WORD_WALL, DRAGON_MOUND -> {
                Sacred.build(frame, c);
                return;
            }
            case PRINCE_SHRINE -> {
                Statues.build(frame, c);
                return;
            }
            case HIGH_HROTHGAR, THROAT_SUMMIT, SKY_HAVEN, LABYRINTHIAN -> {
                Uniques.build(frame, c);
                return;
            }
            default -> {
            }
        }
        Spot s = new Spot(p, c, (b.minX() + b.maxX()) / 2, (b.minZ() + b.maxZ()) / 2, c.floor, f, f.getClockWise());
        switch (c.theme) {
            case FORT, BRETON_KEEP_RUIN -> fort(s);
            case GIANT_CAMP -> giantCamp(s);
            case DRAGON_LAIR -> dragonLair(s);
            default -> shrine(s);
        }
    }

    // ------------------------------------------------------------------ ground

    /** Levels a column: ground block at base-1 on a foundation, air above up to {@code clearTo}. */
    private static void level(Spot s, int x, int z, BlockState ground, int clearTo) {
        s.p().clear(x, s.base(), z, clearTo);
        s.p().set(x, s.base() - 1, z, ground);
        s.p().foundation(x, s.base() - 2, z, Blocks.DIRT.defaultBlockState(), 12);
    }

    // ------------------------------------------------------------------ fort

    private static void fort(Spot s) {
        Painter p = s.p();
        int base = s.base();
        int flavor = s.c().flavor;
        for (int x = p.minX(); x <= p.maxX(); x++) {
            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                int fwd = s.fwd(x, z), lat = s.lat(x, z);
                int af = Math.abs(fwd), al = Math.abs(lat);
                int cheb = Math.max(af, al);
                if (cheb > 13) continue;
                // ground
                int g = p.roll(x, base, z, 400, 10);
                BlockState ground = cheb >= 11 ? Blocks.STONE_BRICKS.defaultBlockState()
                        : al <= 1 && fwd > 4 ? Blocks.DIRT_PATH.defaultBlockState()
                        : g < 4 ? Blocks.COARSE_DIRT.defaultBlockState() : g < 6 ? Blocks.COBBLESTONE.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
                level(s, x, z, ground, base + 16);
                if (cheb == 13) continue;
                boolean tower = af >= 10 && al >= 10;
                if (tower) {
                    fortTower(s, x, z, Math.max(Math.abs(af - 12), Math.abs(al - 12)), af, al);
                } else if (cheb == 12) {
                    fortWall(s, x, z, fwd, lat);
                } else if (cheb == 11) {
                    boolean landing = fwd == -11 && al >= 7 && al <= 9; // where the stairs come up: always a walkway
                    if (landing || p.noise(x, 0, z, 4.0, 401) <= 0.5) {
                        p.set(x, base + 5, z, Deco.slab(Blocks.STONE_BRICK_SLAB, true));
                        // the wall-walk stands on timber posts
                        if (Math.floorMod(x + z, 4) == 0) {
                            for (int y = base; y < base + 5; y++) p.set(x, y, z, Blocks.SPRUCE_FENCE.defaultBlockState());
                        }
                    }
                } else if (cheb == 10 && fwd == -10 && al >= 3 && al <= 8) {
                    // a stair run up the back wall from the courtyard to the wall-walk (both sides)
                    int k = al - 3;
                    Direction up = lat > 0 ? s.r() : s.r().getOpposite();
                    for (int y = base; y < base + k; y++) p.set(x, y, z, Blocks.STONE_BRICKS.defaultBlockState());
                    p.set(x, base + k, z, Blocks.STONE_BRICK_STAIRS.defaultBlockState()
                            .setValue(net.minecraft.world.level.block.StairBlock.FACING, up));
                } else if (cheb <= 4) {
                    fortKeep(s, x, z, fwd, lat, cheb);
                }
            }
        }
        // courtyard camp: the garrison fights in the open before the cellars
        p.feature("setpiece:courtyard_battle");
        tent(s, 0, -7);
        tent(s, 0, 7);
        s.set(7, base, -4, Deco.campfire(false, true));
        s.set(7, base, -6, Deco.pillar(Blocks.STRIPPED_SPRUCE_LOG, s.f().getAxis()));
        s.set(8, base, -4, Deco.pillar(Blocks.STRIPPED_SPRUCE_LOG, s.r().getAxis()));
        s.set(7, base, 6, Blocks.SPRUCE_FENCE.defaultBlockState());
        s.set(7, base + 1, 6, Deco.pillar(Blocks.HAY_BLOCK, Direction.Axis.Y));
        s.set(-8, base, 6, Blocks.BARREL.defaultBlockState());
        s.set(-8, base, 7, Deco.pillar(Blocks.HAY_BLOCK, Direction.Axis.Y));
        p.container(s.x(-9, 5), base, s.z(-9, 5), Deco.barrel(Direction.UP), Deco.LOOT_MINOR);
        p.container(s.x(-9, -5), base, s.z(-9, -5), Deco.chest(s.f()), Deco.LOOT_COMMON);
        // garrison
        FortRooms.occupant(p, flavor, s.x(7, -2), base, s.z(7, -2), Painter.yaw(s.f()), false);
        FortRooms.occupant(p, flavor, s.x(6, 3), base, s.z(6, 3), Painter.yaw(s.r()), false);
        FortRooms.occupant(p, flavor, s.x(-7, 2), base, s.z(-7, 2), Painter.yaw(s.f().getOpposite()), false);
        FortRooms.occupant(p, flavor, s.x(2, 0), base, s.z(2, 0), Painter.yaw(s.f()), false);
        FortRooms.occupant(p, flavor, s.x(12, 12), base + 11, s.z(12, 12), Painter.yaw(s.f()), false);
        FortRooms.occupant(p, flavor, s.x(-12, -12), base + 11, s.z(-12, -12), Painter.yaw(s.f().getOpposite()), false);
        // keep upper floor treasure
        p.container(s.x(-2, -2), base + 6, s.z(-2, -2), Deco.chest(s.f()), Deco.LOOT_COMMON);
        s.set(-2, base + 6, 2, Deco.lantern(false, false));
    }

    private static BlockState masonry(Painter p, int x, int y, int z) {
        return BuiltBuilder.wall(p, Theme.FORT, x, y, z, 1);
    }

    private static void fortWall(Spot s, int x, int z, int fwd, int lat) {
        Painter p = s.p();
        int base = s.base();
        boolean front = fwd == 12;
        boolean gate = front && Math.abs(lat) <= 1;
        double n = p.noise(x, 0, z, 4.0, 401);
        int top = base + 6;
        boolean ruined = n > 0.5;
        if (ruined) top = base + 2 + p.roll(x, 0, z, 402, 3);
        for (int y = base; y <= top; y++) {
            if (gate && y <= base + 3) continue;
            p.set(x, y, z, gate && y == base + 4 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState() : masonry(p, x, y, z));
        }
        if (!ruined && Math.floorMod(x + z, 2) == 0) p.set(x, base + 7, z, masonry(p, x, base + 7, z));
        if (front && Math.abs(lat) == 2) p.set(x + s.f().getStepX(), base + 2, z + s.f().getStepZ(), Deco.wallTorch(false, s.f()));
    }

    private static void fortTower(Spot s, int x, int z, int ring, int af, int al) {
        Painter p = s.p();
        int base = s.base();
        if (ring == 2) {
            boolean innerMid = (af == 10 && al == 12) || (al == 10 && af == 12);
            for (int y = base; y <= base + 10; y++) {
                if (innerMid && (y <= base + 1 || y == base + 5 || y == base + 6)) continue;
                p.set(x, y, z, masonry(p, x, y, z));
            }
            if (Math.floorMod(x + z, 2) == 0) p.set(x, base + 11, z, masonry(p, x, base + 11, z));
        } else {
            p.set(x, base + 5, z, Blocks.SPRUCE_PLANKS.defaultBlockState());
            p.set(x, base + 10, z, Blocks.STONE_BRICKS.defaultBlockState());
            if (ring == 0) {
                for (int y = base; y <= base + 9; y++) p.set(x, y, z, Blocks.SCAFFOLDING.defaultBlockState().setValue(ScaffoldingBlock.DISTANCE, 0));
                p.set(x, base + 10, z, Blocks.SPRUCE_TRAPDOOR.defaultBlockState());
            }
        }
    }

    private static void fortKeep(Spot s, int x, int z, int fwd, int lat, int cheb) {
        Painter p = s.p();
        int base = s.base();
        if (cheb == 4) {
            boolean door = fwd == 4 && lat == 0;
            boolean slit = (Math.abs(fwd) == 4 && lat == 0 || Math.abs(lat) == 4 && fwd == 0) && !door;
            for (int y = base; y <= base + 11; y++) {
                if (door && y <= base + 1) continue;
                if (slit && (y == base + 2 || y == base + 7 || y == base + 8)) continue;
                boolean corner = Math.abs(fwd) == 4 && Math.abs(lat) == 4;
                p.set(x, y, z, corner ? Deco.pillar(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.Y) : masonry(p, x, y, z));
            }
            if (Math.floorMod(x + z, 2) == 0) p.set(x, base + 12, z, masonry(p, x, base + 12, z));
            if (door) {
                p.set(x, base + 2, z, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
                p.set(x + s.f().getStepX(), base + 2, z + s.f().getStepZ(), Deco.wallTorch(false, s.f()));
            }
        } else {
            // floors: ground (left to the stair tunnel), upper storey, roof with a hatch
            boolean ladder = lat == 3 && fwd == -1;
            boolean hole = lat == 3 && (fwd == -1);
            p.set(x, base - 1, z, Blocks.STONE_BRICKS.defaultBlockState());
            p.set(x, base + 5, z, hole ? Blocks.AIR.defaultBlockState() : Blocks.SPRUCE_PLANKS.defaultBlockState());
            p.set(x, base + 11, z, hole ? Blocks.SPRUCE_TRAPDOOR.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState());
            if (ladder) {
                Direction face = s.r().getOpposite();
                for (int y = base; y <= base + 10; y++) p.set(x, y, z, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, face));
            }
            if (cheb == 3 && Math.abs(fwd) == 3 && Math.abs(lat) == 3) {
                p.set(x, base + 6, z, Blocks.BARREL.defaultBlockState());
            }
        }
    }

    /** An A-frame hide tent with a bedroll, along fwd, centred at (fwd, lat). */
    private static void tent(Spot s, int fwd0, int lat0) {
        int base = s.base();
        BlockState hide = lat0 < 0 ? com.nirncraft.worldgen.VanillaBlocks.BROWN_WOOL.defaultBlockState() : com.nirncraft.worldgen.VanillaBlocks.WHITE_WOOL.defaultBlockState();
        for (int fwd = fwd0 - 2; fwd <= fwd0 + 1; fwd++) {
            s.set(fwd, base, lat0 - 1, hide);
            s.set(fwd, base + 1, lat0 - 1, hide);
            s.set(fwd, base, lat0 + 1, hide);
            s.set(fwd, base + 1, lat0 + 1, hide);
            s.set(fwd, base + 2, lat0, hide);
            s.set(fwd, base, lat0, fwd < fwd0 ? com.nirncraft.worldgen.VanillaBlocks.RED_CARPET.defaultBlockState() : Blocks.AIR.defaultBlockState());
        }
    }

    // ------------------------------------------------------------------ giant camp

    private static void giantCamp(Spot s) {
        Painter p = s.p();
        int base = s.base();
        for (int x = p.minX(); x <= p.maxX(); x++) {
            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                int fwd = s.fwd(x, z), lat = s.lat(x, z);
                double d = Math.sqrt(fwd * fwd + lat * lat);
                if (d > 12.5) continue;
                int g = p.roll(x, base, z, 410, 10);
                BlockState ground = d < 4 ? Blocks.COARSE_DIRT.defaultBlockState()
                        : g < 3 ? Blocks.COARSE_DIRT.defaultBlockState() : g < 4 ? Blocks.PODZOL.defaultBlockState()
                        : g < 5 ? Blocks.ROOTED_DIRT.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
                level(s, x, z, ground, base + 12);
                // ring of stones
                if (d >= 11.5 && d <= 12.5 && p.chance(x, base, z, 411, 3)) {
                    p.set(x, base, z, Blocks.MOSSY_COBBLESTONE.defaultBlockState());
                    if (p.chance(x, base, z, 412, 2)) p.set(x, base + 1, z, Blocks.COBBLESTONE_WALL.defaultBlockState());
                }
                // scattered bones
                if (d > 4 && d < 11 && p.noise(x, 0, z, 2.5, 413) > 0.55) {
                    p.set(x, base, z, Deco.pillar(Blocks.BONE_BLOCK, p.chance(x, 1, z, 414, 2) ? Direction.Axis.X : Direction.Axis.Z));
                }
            }
        }
        // great fire with a roasting spit
        for (int fwd = -1; fwd <= 1; fwd++) {
            for (int lat = -1; lat <= 1; lat++) {
                boolean rim = Math.abs(fwd) == 1 || Math.abs(lat) == 1;
                s.set(fwd, base - 1, lat, Blocks.COBBLESTONE.defaultBlockState());
                s.set(fwd, base, lat, rim && Math.abs(fwd) + Math.abs(lat) == 2 ? Blocks.COBBLESTONE_WALL.defaultBlockState() : Deco.campfire(false, true));
            }
        }
        for (int lat : new int[]{-2, 2}) {
            s.set(0, base, lat, Blocks.SPRUCE_FENCE.defaultBlockState());
            s.set(0, base + 1, lat, Blocks.SPRUCE_FENCE.defaultBlockState());
        }
        for (int lat = -2; lat <= 2; lat++) s.set(0, base + 2, lat, Deco.pillar(Blocks.STRIPPED_SPRUCE_LOG, s.r().getAxis()));
        // hide lean-to at the back
        for (int fwd = -8; fwd <= -3; fwd++) {
            int roofY = base + 6 - (fwd + 8) * 4 / 5;
            for (int lat = -5; lat <= 5; lat++) {
                boolean post = (fwd == -8 || fwd == -3) && Math.abs(lat) == 5;
                if (post) for (int y = base; y < roofY; y++) s.set(fwd, y, lat, Deco.pillar(Blocks.SPRUCE_LOG, Direction.Axis.Y));
                s.set(fwd, roofY, lat, Math.floorMod(lat, 3) == 0 ? com.nirncraft.worldgen.VanillaBlocks.WHITE_WOOL.defaultBlockState() : com.nirncraft.worldgen.VanillaBlocks.BROWN_WOOL.defaultBlockState());
            }
        }
        for (int lat = -4; lat <= 4; lat += 2) s.set(-7, base, lat, com.nirncraft.worldgen.VanillaBlocks.BROWN_CARPET.defaultBlockState());
        p.container(s.x(-7, 3), base, s.z(-7, 3), Deco.chest(s.f()), Deco.LOOT_GIANT);
        // mammoth tusks
        for (int lat : new int[]{-4, 4}) {
            int side = Integer.signum(lat);
            s.set(5, base, lat, Blocks.BONE_BLOCK.defaultBlockState());
            s.set(5, base + 1, lat, Blocks.BONE_BLOCK.defaultBlockState());
            s.set(5, base + 2, lat + side, Deco.pillar(Blocks.BONE_BLOCK, s.r().getAxis()));
            s.set(5, base + 3, lat + 2 * side, Blocks.BONE_BLOCK.defaultBlockState());
        }
        // totem
        for (int y = base; y <= base + 4; y++) s.set(4, y, 0, Deco.pillar(Blocks.STRIPPED_OAK_LOG, Direction.Axis.Y));
        s.set(4, base + 5, 0, Deco.skull(Blocks.SKELETON_SKULL, Deco.skullRotation(s.f())));
        // inhabitants
        s.spawn(Mobs.GIANT, -2, base, 4, s.f(), null);
        String mammoth = Mobs.mammoth();
        if (mammoth != null) {
            s.spawn(mammoth, 6, base, -7, s.r(), null);
            s.spawn(mammoth, 8, base, 6, s.r().getOpposite(), null);
        }
    }

    // ------------------------------------------------------------------ dragon lair

    private static void dragonLair(Spot s) {
        Painter p = s.p();
        int base = s.base();
        int plateau = base + 4;
        for (int x = p.minX(); x <= p.maxX(); x++) {
            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                int fwd = s.fwd(x, z), lat = s.lat(x, z);
                double d = Math.sqrt(fwd * fwd + lat * lat) / 11.0;
                if (d > 1.0) continue;
                int h = d <= 0.45 ? plateau
                        : base + (int) Math.round(4.0 * (1.0 - (d - 0.45) / 0.55) + p.noise(x, 0, z, 3.0, 420) * 1.2);
                h = Math.min(h, plateau);
                p.clear(x, h + 1, z, base + 14);
                for (int y = base - 1; y <= h; y++) {
                    int roll = p.roll(x, y, z, 421, 12);
                    BlockState st = y == h && d <= 0.45
                            ? (roll < 3 ? Blocks.BLACKSTONE.defaultBlockState() : roll < 5 ? Blocks.COARSE_DIRT.defaultBlockState()
                            : roll < 6 ? Blocks.COBBLED_DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState())
                            : roll < 3 ? Blocks.ANDESITE.defaultBlockState() : roll < 5 ? Blocks.COBBLESTONE.defaultBlockState()
                            : roll < 6 ? Blocks.TUFF.defaultBlockState() : Blocks.STONE.defaultBlockState();
                    p.set(x, y, z, st);
                }
                p.foundation(x, base - 2, z, Blocks.STONE.defaultBlockState(), 12);
                // the stair up the front of the mound
                if (Math.abs(lat) <= 1 && fwd > 4 && h > base - 1) {
                    p.set(x, h, z, Deco.stair(Blocks.STONE_BRICK_STAIRS, s.f().getOpposite(), false));
                }
            }
        }
        // curved wall with the Word Wall at its heart
        int[][] wall = {{-4, 0, 5}, {-4, -1, 5}, {-4, 1, 5}, {-4, -2, 4}, {-4, 2, 4}, {-3, -3, 4}, {-3, 3, 4}, {-2, -4, 3}, {-2, 4, 3}};
        for (int[] w : wall) {
            int height = w[2];
            boolean broken = Math.abs(w[1]) >= 2 && p.chance(s.x(w[0], w[1]), 0, s.z(w[0], w[1]), 422, 3);
            if (broken) height -= 1;
            for (int dy = 1; dy <= height; dy++) {
                BlockState st = dy == height && Math.abs(w[1]) <= 1 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState()
                        : BuiltBuilder.wall(p, Theme.BARROW, s.x(w[0], w[1]), plateau + dy, s.z(w[0], w[1]), 1);
                s.set(w[0], plateau + dy, w[1], st);
            }
        }
        Deco.wordWall(p, s.x(-4, 0), plateau + 2, s.z(-4, 0), s.f(), s.c().param);
        // a fallen dragon's bones across the summit
        for (int lat = -4; lat <= 3; lat++) s.set(1, plateau + 1, lat, Deco.pillar(Blocks.BONE_BLOCK, s.r().getAxis()));
        for (int lat = -3; lat <= 2; lat += 2) {
            for (int side : new int[]{-1, 1}) {
                s.set(1 + side, plateau + 1, lat, Deco.pillar(Blocks.BONE_BLOCK, s.f().getAxis()));
                s.set(1 + 2 * side, plateau + 2, lat, Blocks.BONE_BLOCK.defaultBlockState());
            }
        }
        s.set(1, plateau + 1, 4, Blocks.SKELETON_SKULL.defaultBlockState());
        p.container(s.x(-3, 2), plateau + 1, s.z(-3, 2), Deco.chest(s.f()), Deco.LOOT_DRAGON);
        s.set(-3, plateau + 1, -2, Deco.campfire(false, false));
        s.spawn(Mobs.draugr(), 3, plateau + 1, -3, s.f(), null);
        s.spawn(Mobs.draugr(), 3, plateau + 1, 3, s.f(), null);
    }

    // ------------------------------------------------------------------ Daedric shrine

    private static void shrine(Spot s) {
        Painter p = s.p();
        int base = s.base();
        for (int x = p.minX(); x <= p.maxX(); x++) {
            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                int fwd = s.fwd(x, z), lat = s.lat(x, z);
                double d = Math.sqrt(fwd * fwd + lat * lat);
                if (d > 8.5) continue;
                BlockState ground;
                if (d > 7.5) ground = Blocks.BLACKSTONE.defaultBlockState();
                else if (d > 6.5) ground = Blocks.POLISHED_BLACKSTONE.defaultBlockState();
                else if ((Math.abs(fwd) == Math.abs(lat)) && d > 2) ground = Blocks.CRYING_OBSIDIAN.defaultBlockState();
                else ground = p.chance(x, base, z, 430, 6) ? Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS.defaultBlockState()
                            : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState();
                level(s, x, z, ground, base + 18);
            }
        }
        // pedestal
        for (int fwd = -5; fwd <= -3; fwd++) {
            for (int lat = -1; lat <= 1; lat++) {
                for (int dy = 0; dy <= 1; dy++) s.set(fwd, base + dy, lat, Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState());
            }
        }
        // the statue (arms raised, a star in one hand and the moon in the other)
        BlockState body = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
        BlockState robe = Blocks.DEEPSLATE_TILES.defaultBlockState();
        int fw = -4;
        for (int dy = 2; dy <= 4; dy++) {
            s.set(fw, base + dy, -1, robe);
            s.set(fw, base + dy, 1, robe);
            s.set(fw, base + dy, 0, dy == 2 ? robe : Blocks.AIR.defaultBlockState());
        }
        for (int dy = 5; dy <= 8; dy++) for (int lat = -1; lat <= 1; lat++) s.set(fw, base + dy, lat, dy == 5 ? robe : body);
        s.set(fw, base + 9, 0, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
        s.set(fw, base + 10, 0, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
        s.set(fw, base + 11, -1, Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState());
        s.set(fw, base + 11, 1, Blocks.POLISHED_BLACKSTONE_WALL.defaultBlockState());
        for (int side : new int[]{-1, 1}) {
            s.set(fw, base + 8, 2 * side, body);
            s.set(fw, base + 9, 2 * side, body);
            s.set(fw, base + 10, 3 * side, body);
            s.set(fw, base + 11, 3 * side, body);
        }
        s.set(fw, base + 12, -3, Blocks.END_ROD.defaultBlockState());
        s.set(fw, base + 12, 3, Blocks.SEA_LANTERN.defaultBlockState());
        // altar
        for (int lat = -1; lat <= 1; lat++) s.set(-1, base, lat, lat == 0 ? Blocks.CRYING_OBSIDIAN.defaultBlockState() : Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState());
        s.set(-1, base + 1, -1, Deco.candle(com.nirncraft.worldgen.VanillaBlocks.BLACK_CANDLE, 3, true));
        s.set(-1, base + 1, 1, Deco.candle(com.nirncraft.worldgen.VanillaBlocks.PURPLE_CANDLE, 3, true));
        s.set(-1, base + 1, 0, Blocks.AMETHYST_CLUSTER.defaultBlockState());
        p.container(s.x(-2, 2), base, s.z(-2, 2), Deco.chest(s.f()), Deco.LOOT_SHRINE);
        // braziers of soul fire
        for (int[] b : new int[][]{{4, -4}, {4, 4}, {-6, -3}, {-6, 3}}) {
            s.set(b[0], base, b[1], Blocks.POLISHED_BLACKSTONE_BRICK_WALL.defaultBlockState());
            s.set(b[0], base + 1, b[1], Blocks.POLISHED_BLACKSTONE_BRICK_WALL.defaultBlockState());
            s.set(b[0], base + 2, b[1], Deco.campfire(true, true));
        }
        // steps at the front
        for (int lat = -2; lat <= 2; lat++) s.set(8, base - 1, lat, Deco.stair(Blocks.POLISHED_BLACKSTONE_STAIRS, s.f().getOpposite(), false));
        if (Mth.abs(s.c().flavor) % 2 == 1) {
            s.spawn("minecraft:vindicator", 2, base, -2, s.f().getOpposite(), "Daedra Worshipper");
            s.spawn("minecraft:evoker", 1, base, 2, s.f().getOpposite(), "Cult Priest");
        }
    }
}
