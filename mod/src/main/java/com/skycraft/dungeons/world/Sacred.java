package com.skycraft.dungeons.world;

import com.skycraft.lore.AllMakerStone;
import com.skycraft.lore.LoreModule;
import com.skycraft.lore.StandingStone;
import com.skycraft.lore.StandingStoneBlock;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Sacred sites of the map: the thirteen Standing Stones (each with a setting of its own; the Guardian Stones
 * together), Solstheim's six All-Maker stones, the open-air Word Walls and the dragon burial mounds.
 *
 * <p>The stones are the lore module's blocks, so using one grants its blessing through {@code StandingStones}
 * ({@code AllMakerStones} for Solstheim's).</p>
 */
public final class Sacred {
    private Sacred() {}

    public static void build(Frame fr, DungeonPiece c) {
        switch (c.theme) {
            case WORD_WALL -> wordWall(fr, c);
            case DRAGON_MOUND -> dragonMound(fr, c);
            default -> {
                if (c.param == SiteParams.GUARDIANS) guardians(fr);
                else if (c.param >= SiteParams.ALL_MAKER) allMaker(fr, AllMakerStone.byIndex(c.param - SiteParams.ALL_MAKER));
                else standingStone(fr, StandingStone.VALUES[Math.floorMod(c.param, StandingStone.VALUES.length)]);
            }
        }
    }

    // ------------------------------------------------------------------ shared

    static void stone(Frame fr, int fw, int dy, int lt, Block block, Direction facing) {
        BlockState lower = block.defaultBlockState().setValue(StandingStoneBlock.FACING, facing).setValue(StandingStoneBlock.HALF, DoubleBlockHalf.LOWER);
        fr.set(fw, dy, lt, lower);
        fr.set(fw, dy + 1, lt, lower.setValue(StandingStoneBlock.HALF, DoubleBlockHalf.UPPER));
    }

    /** Round weathered platform of radius r with a mossy edge, cleared above. */
    static void platform(Frame fr, double r, int clear) {
        Painter p = fr.p;
        fr.all((fw, lt, x, z) -> {
            double d = Math.hypot(fw, lt);
            if (d > r + 1.2) return;
            p.clear(x, fr.base, z, fr.base + clear);
            if (d <= r) {
                int roll = p.roll(x, 0, z, 900, 20);
                p.set(x, fr.base - 1, z, d <= 1.5 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState()
                        : roll < 9 ? Blocks.STONE_BRICKS.defaultBlockState() : roll < 15 ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState()
                        : roll < 18 ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState() : Blocks.MOSSY_COBBLESTONE.defaultBlockState());
                p.foundation(x, fr.base - 2, z, Blocks.STONE.defaultBlockState(), 10);
            } else if (p.chance(x, 1, z, 901, 2)) {
                p.set(x, fr.base - 1, z, p.chance(x, 2, z, 902, 2) ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.GRAVEL.defaultBlockState());
            }
        });
    }

    /** A ring of short weathered pillars. */
    static void ring(Frame fr, double r, int count, int salt) {
        for (int i = 0; i < count; i++) {
            double a = i * Math.PI * 2 / count;
            int fw = (int) Math.round(Math.cos(a) * r), lt = (int) Math.round(Math.sin(a) * r);
            int h = fr.roll(fw, lt, salt, 4) == 0 ? 1 : 2 + fr.roll(fw, lt, salt + 1, 2);
            for (int dy = 0; dy < h; dy++) fr.set(fw, dy, lt, dy == 0 || fr.chance(fw, lt + dy, salt + 2, 2) ? Blocks.MOSSY_COBBLESTONE : Blocks.MOSSY_STONE_BRICKS);
            if (h >= 2 && fr.chance(fw, lt, salt + 3, 3)) fr.set(fw, h, lt, Blocks.MOSSY_STONE_BRICK_SLAB);
        }
    }

    /** A candle in the sign's colour. */
    static Block candle(StandingStone s) {
        return switch (s) {
            case WARRIOR, RITUAL -> Blocks.RED_CANDLE;
            case MAGE, TOWER -> Blocks.LIGHT_BLUE_CANDLE;
            case THIEF, SERPENT -> Blocks.LIME_CANDLE;
            case LADY, LOVER -> Blocks.PINK_CANDLE;
            case LORD, STEED -> Blocks.ORANGE_CANDLE;
            case ATRONACH, SHADOW -> Blocks.PURPLE_CANDLE;
            case APPRENTICE -> Blocks.CYAN_CANDLE;
        };
    }

    // ------------------------------------------------------------------ the thirteen

    private static void standingStone(Frame fr, StandingStone sign) {
        Painter p = fr.p;
        Block block = LoreModule.STONES.get(sign).get();
        platform(fr, 3.3, 8);
        int stoneDy = 1;
        switch (sign) {
            case LORD -> {
                // a stepped dais, the stone high on it
                for (int s = 2; s >= 0; s--) fr.fill(-s, 2 - s, -s, s, 2 - s, s, s == 0 ? Blocks.CHISELED_STONE_BRICKS : Blocks.STONE_BRICKS);
                stoneDy = 3;
                for (int[] b : new int[][]{{-3, -3}, {3, 3}, {-3, 3}, {3, -3}}) {
                    fr.set(b[0], 0, b[1], Blocks.STONE_BRICK_WALL);
                    fr.set(b[0], 1, b[1], Deco.campfire(false, true));
                }
            }
            case TOWER -> {
                // a slender plinth tower with a stair round it
                for (int dy = 0; dy <= 3; dy++) fr.set(0, dy, 0, Blocks.STONE_BRICKS);
                stoneDy = 4;
                int[][] stairs = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}};
                for (int i = 0; i < stairs.length; i++) fr.set(stairs[i][0], i, stairs[i][1], Blocks.STONE_BRICKS);
            }
            case LADY -> {
                // the stone on an island in a little round pool
                fr.all((fw, lt, x, z) -> {
                    double d = Math.hypot(fw, lt);
                    if (d > 1.6 && d < 3.0) p.set(x, fr.base - 1, z, Blocks.WATER.defaultBlockState());
                });
                fr.set(2, 0, 2, Blocks.LILY_PAD);
            }
            case ATRONACH -> {
                // a ring of hot springs: steaming vents round the stone
                for (Direction d : Direction.Plane.HORIZONTAL) {
                    int fw = d.getStepX() * 2, lt = d.getStepZ() * 2;
                    fr.set(fw, -1, lt, Deco.campfire(false, true));
                    fr.set(fw, 0, lt, Blocks.AIR);
                }
                fr.set(0, 0, 0, Blocks.MAGMA_BLOCK);
            }
            case RITUAL -> {
                // a broken altar heaped with bones
                for (int i = 0; i < 6; i++) {
                    int fw = fr.roll(i, 0, 903, 5) - 2, lt = fr.roll(i, 1, 903, 5) - 2;
                    if (fw == 0 && lt == 0) continue;
                    fr.set(fw, 0, lt, i % 2 == 0 ? Deco.skull(Blocks.SKELETON_SKULL, i * 3) : Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.X));
                }
            }
            case SERPENT -> {
                // a coiled serpent of mossy stone on an ice floe
                fr.all((fw, lt, x, z) -> {
                    double d = Math.hypot(fw, lt);
                    if (d <= 3.3 && d > 1.5) p.set(x, fr.base - 1, z, Blocks.PACKED_ICE.defaultBlockState());
                });
                int[][] coil = {{2, 0}, {2, 1}, {1, 2}, {0, 2}, {-1, 2}, {-2, 1}, {-2, 0}, {-2, -1}, {-1, -2}};
                for (int i = 0; i < coil.length; i++) fr.set(coil[i][0], 0, coil[i][1], i == coil.length - 1 ? Blocks.CHISELED_STONE_BRICKS : Blocks.MOSSY_COBBLESTONE_WALL);
            }
            case SHADOW -> {
                // sunk into a dark hollow ringed with black candles
                fr.all((fw, lt, x, z) -> {
                    double d = Math.hypot(fw, lt);
                    if (d <= 2.2) p.set(x, fr.base - 1, z, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
                });
                stoneDy = 0;
                fr.set(0, -1, 0, Blocks.POLISHED_BLACKSTONE);
                for (int[] cc : new int[][]{{2, 2}, {-2, 2}, {2, -2}, {-2, -2}}) fr.set(cc[0], 0, cc[1], Deco.candle(Blocks.BLACK_CANDLE, 3, true));
            }
            case LOVER -> {
                // a ring of flowers
                fr.all((fw, lt, x, z) -> {
                    double d = Math.hypot(fw, lt);
                    if (d > 3.5 && d < 4.6 && p.chance(x, 0, z, 904, 2)) {
                        p.set(x, fr.base, z, p.chance(x, 1, z, 905, 2) ? Blocks.POPPY.defaultBlockState() : Blocks.PINK_TULIP.defaultBlockState());
                    }
                });
                fr.set(-4, 0, 0, Blocks.ROSE_BUSH.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, DoubleBlockHalf.LOWER));
                fr.set(-4, 1, 0, Blocks.ROSE_BUSH.defaultBlockState().setValue(net.minecraft.world.level.block.DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
            }
            case STEED -> {
                // the stone at the end of a short causeway
                for (int fw = 3; fw <= 6; fw++) for (int lt = -1; lt <= 1; lt++) fr.set(fw, -1, lt, Blocks.STONE_BRICKS);
                fr.set(6, 0, -2, Blocks.SPRUCE_FENCE);
                fr.set(6, 0, 2, Blocks.SPRUCE_FENCE);
            }
            case APPRENTICE -> {
                // marsh water lapping the platform
                fr.all((fw, lt, x, z) -> {
                    double d = Math.hypot(fw, lt);
                    if (d > 3.4 && d < 5.0 && p.chance(x, 0, z, 906, 2)) p.set(x, fr.base - 1, z, Blocks.WATER.defaultBlockState());
                });
            }
            default -> {
            }
        }
        if (stoneDy == 1) {
            fr.set(0, 0, 0, Blocks.POLISHED_ANDESITE);
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (sign == StandingStone.ATRONACH) break;
                fr.set(d.getStepX(), 0, d.getStepZ(), Deco.slab(Blocks.STONE_BRICK_SLAB, false));
            }
        }
        stone(fr, 0, stoneDy, 0, block, fr.f);
        ring(fr, 4.3, 7, 907);
        fr.set(2, 0, -2, Deco.candle(candle(sign), 3, true));
        fr.set(-2, 0, 2, Deco.candle(candle(sign), 2, true));
    }

    /** The Guardian Stones: the Warrior, the Mage and the Thief at the corners of a triangle, facing its heart. */
    private static void guardians(Frame fr) {
        Painter p = fr.p;
        platform(fr, 1.5, 6);
        StandingStone[] trio = {StandingStone.WARRIOR, StandingStone.MAGE, StandingStone.THIEF};
        // worn paths between them
        fr.all((fw, lt, x, z) -> {
            double d = Math.hypot(fw, lt);
            if (d > 8) return;
            p.clear(x, fr.base, z, fr.base + 6);
            if (d > 4 && d < 6.5 && p.chance(x, 0, z, 910, 2)) p.set(x, fr.base - 1, z, Blocks.COARSE_DIRT.defaultBlockState());
        });
        for (int i = 0; i < 3; i++) {
            double a = Math.PI * 2 * i / 3 + Math.PI;
            int fw = (int) Math.round(Math.cos(a) * 6), lt = (int) Math.round(Math.sin(a) * 6);
            for (int dfw = -1; dfw <= 1; dfw++) for (int dlt = -1; dlt <= 1; dlt++) {
                fr.set(fw + dfw, -1, lt + dlt, Blocks.STONE_BRICKS);
                fr.foundation(fw + dfw, -2, lt + dlt, Blocks.STONE.defaultBlockState(), 8);
                fr.set(fw + dfw, 0, lt + dlt, dfw == 0 && dlt == 0 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState() : Deco.slab(Blocks.STONE_BRICK_SLAB, false));
            }
            Direction facing = Math.abs(fw) >= Math.abs(lt) ? (fw > 0 ? fr.back() : fr.f) : (lt > 0 ? fr.left() : fr.r);
            stone(fr, fw, 1, lt, LoreModule.STONES.get(trio[i]).get(), facing);
            fr.set(fw + (fw > 0 ? -2 : 2), 0, lt, Deco.candle(candle(trio[i]), 3, true));
        }
        // the cairn at the heart
        fr.set(0, 0, 0, Blocks.MOSSY_COBBLESTONE);
        fr.set(0, 1, 0, Blocks.COBBLESTONE_WALL);
        fr.set(0, 2, 0, Deco.candle(Blocks.CANDLE, 1, true));
    }

    /**
     * An All-Maker stone of Solstheim: the stone ringed by six carved Skaal pillars, walled in by Miraak's black
     * stone (his servants still chant at some of them).
     */
    private static void allMaker(Frame fr, AllMakerStone stone) {
        Painter p = fr.p;
        platform(fr, 4.3, 10);
        // Miraak's shell: a broken dome of blackstone ribs around the stone
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            int fw = (int) Math.round(Math.cos(a) * 2.6), lt = (int) Math.round(Math.sin(a) * 2.6);
            int h = 3 + fr.roll(fw, lt, 920, 3);
            for (int dy = 0; dy < h; dy++) fr.set(fw, dy, lt, dy == h - 1 ? Blocks.CRYING_OBSIDIAN : Blocks.POLISHED_BLACKSTONE_BRICKS);
        }
        // the Skaal pillars, each topped with a rune
        for (int i = 0; i < 6; i++) {
            double a = i * Math.PI / 3;
            int fw = (int) Math.round(Math.cos(a) * 5.2), lt = (int) Math.round(Math.sin(a) * 5.2);
            for (int dy = 0; dy <= 2; dy++) fr.set(fw, dy, lt, dy == 2 ? Blocks.CHISELED_STONE_BRICKS : Blocks.STONE_BRICKS);
            fr.foundation(fw, -1, lt, Blocks.STONE.defaultBlockState(), 8);
        }
        fr.set(0, 0, 0, Blocks.POLISHED_DEEPSLATE);
        Block block = LoreModule.ALL_MAKER_STONES.get(stone).get();
        stone(fr, 0, 1, 0, block, fr.f);
        fr.set(3, 0, 3, Deco.campfire(true, true));
        if (fr.chance(0, 0, 921, 2)) {
            fr.spawn("minecraft:zombie_villager", 3, 0, -1, fr.back(), "Thrall of Miraak");
            fr.spawn("minecraft:zombie_villager", -3, 0, 1, fr.back(), "Thrall of Miraak");
        }
    }

    // ------------------------------------------------------------------ word wall

    /** An open-air Word Wall: a curved wall of weathered stone on a terrace, its guardians asleep at its foot. */
    private static void wordWall(Frame fr, DungeonPiece c) {
        Painter p = fr.p;
        fr.all((fw, lt, x, z) -> {
            if (Math.abs(fw) > 6 || Math.abs(lt) > 6) return;
            p.clear(x, fr.base, z, fr.base + 9);
            p.set(x, fr.base - 1, z, BuiltBuilder.floor(p, Theme.BARROW, x, fr.base - 1, z));
            p.foundation(x, fr.base - 2, z, Blocks.COBBLESTONE.defaultBlockState(), 10);
        });
        int[][] wall = {{-4, 0, 5}, {-4, -1, 5}, {-4, 1, 5}, {-4, -2, 4}, {-4, 2, 4}, {-3, -3, 4}, {-3, 3, 4}, {-2, -4, 3}, {-2, 4, 3}};
        for (int[] w : wall) {
            for (int dy = 0; dy < w[2]; dy++) {
                BlockState st = dy == w[2] - 1 && Math.abs(w[1]) <= 1 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState()
                        : BuiltBuilder.wall(p, Theme.BARROW, fr.x(w[0], w[1]), fr.base + dy, fr.z(w[0], w[1]), 1);
                fr.set(w[0], dy, w[1], st);
            }
        }
        Deco.wordWall(p, fr.x(-4, 0), fr.base + 1, fr.z(-4, 0), fr.f, c.param);
        // steps up to it, braziers, a chest of offerings
        for (int lt = -1; lt <= 1; lt++) fr.set(-2, 0, lt, Deco.slab(Blocks.STONE_BRICK_SLAB, false));
        for (int lt : new int[]{-3, 3}) {
            fr.set(0, 0, lt, Blocks.CHISELED_STONE_BRICKS);
            fr.set(0, 1, lt, Deco.campfire(false, true));
        }
        fr.container(-3, 0, 2, Deco.chest(fr.f), new net.minecraft.resources.ResourceLocation(com.skycraft.Skycraft.MODID, "chests/word_wall"));
        fr.p.spawnDormant(Mobs.draugr(), fr.x(1, -2), fr.base, fr.z(1, -2), Painter.yaw(fr.f));
        fr.p.spawnDormant(Mobs.draugr(), fr.x(1, 2), fr.base, fr.z(1, 2), Painter.yaw(fr.f));
    }

    // ------------------------------------------------------------------ dragon mound

    /**
     * A dragon burial mound: a low barrow of earth ringed with stones, a carved headstone, and (in some) the earth
     * already torn open and the bones within showing.
     */
    private static void dragonMound(Frame fr, DungeonPiece c) {
        Painter p = fr.p;
        boolean opened = Math.floorMod(c.flavor, 2) == 1;
        fr.all((fw, lt, x, z) -> {
            double d = Math.hypot(fw / 1.3, lt);
            if (d > 5.5) return;
            p.clear(x, fr.base, z, fr.base + 8);
            int h = (int) Math.round(Math.max(0, (5.0 - d) * 0.7));
            for (int dy = -1; dy < h; dy++) {
                p.set(x, fr.base + dy, z, dy == h - 1 ? (p.chance(x, dy, z, 930, 3) ? Blocks.COARSE_DIRT.defaultBlockState() : Blocks.PODZOL.defaultBlockState())
                        : Blocks.DIRT.defaultBlockState());
            }
            p.foundation(x, fr.base - 2, z, Blocks.DIRT.defaultBlockState(), 8);
            if (opened && d < 2.5) {
                // a torn-open pit with bones in it
                for (int dy = 0; dy <= 3; dy++) p.air(x, fr.base + dy, z);
                p.set(x, fr.base - 1, z, p.chance(x, 0, z, 931, 2) ? Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.X) : Blocks.GRAVEL.defaultBlockState());
            }
        });
        for (int i = 0; i < 10; i++) {
            double a = i * Math.PI * 2 / 10;
            int fw = (int) Math.round(Math.cos(a) * 6.8), lt = (int) Math.round(Math.sin(a) * 5.4);
            fr.set(fw, 0, lt, Blocks.MOSSY_COBBLESTONE);
            if (fr.chance(fw, lt, 932, 3)) fr.set(fw, 1, lt, Blocks.MOSSY_COBBLESTONE_WALL);
        }
        // the headstone with dragon script
        for (int dy = 0; dy <= 2; dy++) fr.set(-6, dy, 0, dy == 2 ? Blocks.CHISELED_STONE_BRICKS : Blocks.STONE_BRICKS);
        fr.set(-6, 1, -1, Deco.stair(Blocks.STONE_BRICK_STAIRS, fr.r, true));
        fr.set(-6, 1, 1, Deco.stair(Blocks.STONE_BRICK_STAIRS, fr.left(), true));
    }
}
