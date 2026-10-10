package com.skycraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Skyrim's one-of-a-kind sites: High Hrothgar on the Throat of the World, the summit with its Word Wall and the
 * dragon's perch, Sky Haven Temple cut into the Reach's mountains, and the ruined city of Labyrinthian with
 * Shalidor's maze. Big, but still painted column by column and clipped to each chunk.
 */
public final class Uniques {
    private Uniques() {}

    public static void build(Frame fr, DungeonPiece c) {
        switch (c.theme) {
            case HIGH_HROTHGAR -> highHrothgar(fr);
            case THROAT_SUMMIT -> summit(fr, c);
            case SKY_HAVEN -> skyHaven(fr);
            case LABYRINTHIAN -> labyrinthian(fr);
            default -> {
            }
        }
    }

    static Frame.Masonry nordStone() {
        return (p, x, y, z) -> {
            int r = p.roll(x, y, z, 970, 20);
            return r < 11 ? Blocks.STONE_BRICKS.defaultBlockState() : r < 15 ? Blocks.POLISHED_ANDESITE.defaultBlockState()
                    : r < 18 ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState() : Blocks.ANDESITE.defaultBlockState();
        };
    }

    // ------------------------------------------------------------------ High Hrothgar

    /**
     * The Greybeards' monastery: a terraced courtyard at the head of the 7,000 Steps, the great hall with its tower,
     * two wings of cells, a library, a kitchen, and the walled back courtyard whose gate opens on the summit path.
     * Facing east, towards the pilgrims' path.
     */
    private static void highHrothgar(Frame fr) {
        Painter p = fr.p;
        Frame.Masonry stone = nordStone();
        Frame.Masonry paving = (pp, x, y, z) -> pp.chance(x, y, z, 971, 4) ? Blocks.POLISHED_ANDESITE.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
        // the whole site is a terrace cut into the mountain
        fr.pad(-36, -36, 36, 36, paving, 40, 30);
        // ---- front courtyard: low walls, a central walk and braziers
        fr.walls(12, 0, -16, 34, 1, 16, stone);
        fr.clear(34, 0, -2, 34, 2, 2);
        for (int fw = 35; fw <= 37; fw++) {
            for (int lt = -3; lt <= 3; lt++) {
                fr.set(fw, 34 - fw, lt, fr.stair(Blocks.STONE_BRICK_STAIRS, Direction.SOUTH, false));
                fr.foundation(fw, 33 - fw, lt, Blocks.STONE.defaultBlockState(), 12);
            }
        }
        for (int fw = 12; fw <= 33; fw++) for (int lt = -1; lt <= 1; lt++) fr.set(fw, -1, lt, Blocks.POLISHED_ANDESITE);
        for (int fw : new int[]{16, 24, 32}) {
            for (int lt : new int[]{-5, 5}) {
                fr.set(fw, 0, lt, Blocks.CHISELED_STONE_BRICKS);
                fr.set(fw, 1, lt, Deco.campfire(false, true));
            }
        }
        // ---- the great hall
        int hf0 = -20, hf1 = 11, hl = 14, eave = 12;
        fr.block(hf0, -1, -hl, hf1, -1, hl, Frame.Masonry.of(Blocks.POLISHED_ANDESITE));
        fr.walls(hf0, 0, -hl, hf1, eave, hl, stone);
        fr.fill(hf0 + 1, 0, -hl + 1, hf1 - 1, eave, hl - 1, Blocks.AIR);
        fr.gableRoof(hf0 - 1, -hl - 1, hf1 + 1, hl + 1, eave + 1, true, Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DEEPSLATE_TILE_SLAB,
                Blocks.STONE_BRICKS.defaultBlockState());
        // front door with a dragon-horned gable over it
        fr.clear(hf1, 0, -1, hf1, 3, 1);
        for (int lt = -2; lt <= 2; lt++) fr.set(hf1, 4, lt, Blocks.CHISELED_STONE_BRICKS);
        for (int s : new int[]{-1, 1}) {
            fr.set(hf1 + 1, 0, 3 * s, Blocks.STONE_BRICK_WALL);
            fr.set(hf1 + 1, 1, 3 * s, Deco.lantern(false, false));
        }
        // windows along the sides
        for (int fw = hf0 + 3; fw <= hf1 - 3; fw += 4) for (int s : new int[]{-hl, hl}) for (int dy = 5; dy <= 7; dy++) fr.set(fw, dy, s, Blocks.GLASS_PANE);
        // the colonnade inside, the meditation circle, braziers, banners
        for (int fw = hf0 + 3; fw <= hf1 - 3; fw += 5) {
            for (int lt : new int[]{-7, 7}) {
                for (int dy = 0; dy <= eave - 1; dy++) fr.set(fw, dy, lt, dy == 0 || dy == eave - 1 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState()
                        : Blocks.POLISHED_ANDESITE.defaultBlockState());
                fr.set(fw + 1, 4, lt, Deco.wallTorch(false, fr.f));
            }
        }
        fr.columns(-8, -4, 0, 4, (fw, lt, x, z) -> {
            double d = Math.hypot(fw + 4, lt);
            if (d > 3.2 && d < 4.4) p.set(x, fr.base, z, Blocks.LIGHT_GRAY_CARPET.defaultBlockState());
        });
        fr.set(-4, 0, 0, Deco.campfire(false, true));
        for (int lt = -6; lt <= 6; lt += 3) fr.set(hf0 + 1, 6, lt, Blocks.GRAY_BANNER.defaultBlockState());
        // the library along the back wall
        for (int lt = -12; lt <= 12; lt++) {
            if (Math.abs(lt) <= 1) continue;
            for (int dy = 0; dy <= 2; dy++) fr.set(hf0 + 1, dy, lt, Blocks.BOOKSHELF);
        }
        fr.set(hf0 + 3, 0, 0, Blocks.LECTERN.defaultBlockState());
        // ---- the central tower
        fr.walls(-8, eave, -4, 0, eave + 20, 4, stone);
        fr.fill(-7, eave + 1, -3, -1, eave + 19, 3, Blocks.AIR);
        for (int dy = eave + 6; dy <= eave + 18; dy += 6) for (int lt : new int[]{-4, 4}) fr.set(-4, dy, lt, Blocks.GLASS_PANE);
        fr.gableRoof(-9, -5, 1, 5, eave + 21, false, Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DEEPSLATE_TILE_SLAB, Blocks.STONE_BRICKS.defaultBlockState());
        fr.set(-4, eave + 19, 0, Deco.lantern(false, true));
        // ---- the wings: cells to the north, kitchen and hall of the Greybeards' meals to the south
        for (int side : new int[]{-1, 1}) {
            int l0 = side * (hl + 1), l1 = side * (hl + 9);
            fr.walls(-16, 0, Math.min(l0, l1), 4, 8, Math.max(l0, l1), stone);
            fr.fill(-15, 0, Math.min(l0, l1) + 1, 3, 8, Math.max(l0, l1) - 1, Blocks.AIR);
            fr.block(-15, -1, Math.min(l0, l1) + 1, 3, -1, Math.max(l0, l1) - 1, Frame.Masonry.of(Blocks.SPRUCE_PLANKS));
            fr.gableRoof(-17, Math.min(l0, l1) - 1, 5, Math.max(l0, l1) + 1, 9, true, Blocks.DEEPSLATE_TILE_STAIRS, Blocks.DEEPSLATE_TILE_SLAB,
                    Blocks.STONE_BRICKS.defaultBlockState());
            fr.clear(-4, 0, side * hl, -2, 2, side * (hl + 1)); // doorway from the hall
            int mid = side * (hl + 5);
            if (side < 0) {
                // cells: bedrolls and footlockers
                for (int fw = -14; fw <= 2; fw += 4) {
                    fr.set(fw, 0, mid - 2 * side, Blocks.GRAY_CARPET);
                    fr.set(fw + 1, 0, mid - 2 * side, Blocks.GRAY_CARPET);
                    fr.container(fw, 0, mid - 3 * side, Deco.chest(fr.dir(Direction.EAST)), Deco.LOOT_MINOR);
                    fr.set(fw, 2, mid, Deco.lantern(false, true));
                }
            } else {
                // kitchen and dining: a long table, the hearth, the stores
                for (int fw = -12; fw <= 0; fw++) {
                    fr.set(fw, 0, mid, Blocks.SPRUCE_FENCE);
                    fr.set(fw, 1, mid, Deco.slab(Blocks.SPRUCE_SLAB, false));
                }
                fr.set(-15, 0, mid - 2, Blocks.SMOKER);
                fr.set(-15, 0, mid + 2, Deco.campfire(false, true));
                fr.container(-15, 0, mid, Deco.barrel(Direction.UP), new net.minecraft.resources.ResourceLocation(com.skycraft.Skycraft.MODID, "chests/skyrim_food"));
                fr.set(2, 0, mid + 3, Blocks.BARREL);
            }
        }
        // ---- the back courtyard and the gate to the summit path (south)
        int cl0 = hl + 10, cl1 = 34;
        fr.walls(-12, 0, cl0, 2, 2, cl1, stone);
        fr.clear(-6, 0, cl1, -4, 2, cl1);
        for (int lt = cl0 + 1; lt < cl1; lt++) fr.set(-5, -1, lt, Blocks.POLISHED_ANDESITE);
        fr.clear(-6, 0, cl0 - 1, -4, 2, cl0); // from the south wing
        fr.set(-5, 3, cl1, Blocks.CHISELED_STONE_BRICKS);
        // a weathered shrine to Kynareth in the courtyard
        fr.set(-1, 0, cl0 + 5, Blocks.CHISELED_STONE_BRICKS);
        fr.set(-1, 1, cl0 + 5, Deco.candle(Blocks.WHITE_CANDLE, 4, true));
    }

    // ------------------------------------------------------------------ the summit

    /** The roof of the world: a snowy cap, broken walls of an ancient shrine, the Word Wall, the dragon's perch. */
    private static void summit(Frame fr, DungeonPiece c) {
        Painter p = fr.p;
        fr.all((fw, lt, x, z) -> {
            double d = Math.hypot(fw, lt);
            if (d > 13.5) return;
            p.clear(x, fr.base, z, fr.base + 16);
            p.set(x, fr.base - 1, z, p.chance(x, 0, z, 975, 3) ? Blocks.PACKED_ICE.defaultBlockState() : Blocks.SNOW_BLOCK.defaultBlockState());
            p.foundation(x, fr.base - 2, z, Blocks.STONE.defaultBlockState(), 20);
            if (d > 3 && p.chance(x, 1, z, 976, 3)) p.set(x, fr.base, z, Blocks.SNOW.defaultBlockState());
        });
        // the old shrine: a ring of tall broken walls
        for (int i = 0; i < 16; i++) {
            if (i % 4 == 3) continue;
            double a = i * Math.PI * 2 / 16;
            int fw = (int) Math.round(Math.cos(a) * 9), lt = (int) Math.round(Math.sin(a) * 9);
            int h = 3 + fr.roll(fw, lt, 977, 5);
            for (int dy = 0; dy < h; dy++) fr.set(fw, dy, lt, nordStone().at(p, fr.x(fw, lt), fr.base + dy, fr.z(fw, lt)));
        }
        // the Word Wall
        int[][] wall = {{-4, 0, 5}, {-4, -1, 5}, {-4, 1, 5}, {-4, -2, 4}, {-4, 2, 4}, {-3, -3, 3}, {-3, 3, 3}};
        for (int[] w : wall) for (int dy = 0; dy < w[2]; dy++) fr.set(w[0], dy, w[1], Blocks.STONE_BRICKS);
        Deco.wordWall(p, fr.x(-4, 0), fr.base + 1, fr.z(-4, 0), fr.f, c.param);
        // the dragon's perch: a crag of rock thrusting up behind the wall
        fr.all((fw, lt, x, z) -> {
            double d = Math.hypot(fw + 10, lt - 4);
            if (d > 3.5) return;
            int h = (int) Math.round(12 - d * 2.5);
            for (int dy = 0; dy < h; dy++) p.set(x, fr.base + dy, z, p.chance(x, dy, z, 978, 3) ? Blocks.ANDESITE.defaultBlockState() : Blocks.STONE.defaultBlockState());
            p.set(x, fr.base + h, z, Blocks.SNOW.defaultBlockState());
        });
        // scattered bones of old meals
        fr.set(4, 0, -5, Deco.pillar(Blocks.BONE_BLOCK, Direction.Axis.X));
        fr.set(5, 0, -4, Deco.skull(Blocks.SKELETON_SKULL, 3));
    }

    // ------------------------------------------------------------------ Sky Haven Temple

    /**
     * The Blades' temple: a great stair up to an Akaviri facade cut into the rock under a carved dragon's head; inside,
     * the long hall with Alduin's Wall, the armoury, the barracks and the war table. Built as a block of mountain so it
     * reads as carved rock even where the terrain is lower.
     */
    private static void skyHaven(Frame fr) {
        Painter p = fr.p;
        BlockState rock = Blocks.STONE.defaultBlockState();
        // the mountain shell around the halls
        fr.all((fw, lt, x, z) -> {
            if (fw > 12 || Math.abs(lt) > 24 || fw < -26) return;
            int top = 24 - Math.max(0, Math.abs(lt) - 16) * 2 - Math.max(0, fw - 6);
            top += (int) Math.round(p.noise(x, 0, z, 6.0, 980) * 3);
            for (int dy = -1; dy <= top; dy++) {
                if (p.get(x, fr.base + dy, z).isAir() || !p.get(x, fr.base + dy, z).getFluidState().isEmpty() || dy <= 0) {
                    p.set(x, fr.base + dy, z, p.chance(x, dy, z, 981, 5) ? Blocks.ANDESITE.defaultBlockState() : rock);
                }
            }
            p.foundation(x, fr.base - 2, z, rock, 20);
        });
        // the great hall
        int f0 = -22, f1 = 10, hl = 9, h = 14;
        fr.fill(f0, -1, -hl, f1, -1, hl, Blocks.DEEPSLATE_TILES);
        fr.fill(f0 + 1, 0, -hl + 1, f1 - 1, h, hl - 1, Blocks.AIR);
        for (int fw = f0 + 2; fw <= f1 - 2; fw++) for (int lt = -1; lt <= 1; lt++) fr.set(fw, -1, lt, Blocks.RED_TERRACOTTA);
        // Alduin's Wall along the back: a mural of glazed tiles
        for (int lt = -hl + 1; lt <= hl - 1; lt++) {
            for (int dy = 1; dy <= 9; dy++) {
                int k = Math.floorMod(lt * 7 + dy * 3, 4);
                Block tile = k == 0 ? Blocks.BLACK_GLAZED_TERRACOTTA : k == 1 ? Blocks.GRAY_GLAZED_TERRACOTTA
                        : k == 2 ? Blocks.RED_GLAZED_TERRACOTTA : Blocks.LIGHT_GRAY_GLAZED_TERRACOTTA;
                fr.set(f0, dy, lt, tile);
            }
        }
        // lacquered pillars and lanterns
        for (int fw = f0 + 3; fw <= f1 - 3; fw += 5) {
            for (int lt : new int[]{-hl + 2, hl - 2}) {
                for (int dy = 0; dy < h; dy++) fr.set(fw, dy, lt, Deco.pillar(Blocks.STRIPPED_MANGROVE_LOG, Direction.Axis.Y));
                fr.set(fw + 1, 4, lt, Blocks.RED_WOOL);
                fr.set(fw - 1, 4, lt, Blocks.RED_WOOL);
                fr.set(fw, h - 2, lt + (lt < 0 ? 1 : -1), Deco.lantern(false, true));
            }
        }
        // the war table and the Blades' armoury
        fr.fill(-8, 0, -2, -6, 0, 2, Blocks.DARK_OAK_FENCE);
        fr.fill(-8, 1, -2, -6, 1, 2, Blocks.DARK_OAK_SLAB.defaultBlockState());
        fr.set(-7, 1, 0, Blocks.CARTOGRAPHY_TABLE);
        for (int fw = f0 + 2; fw <= f0 + 8; fw += 2) {
            fr.set(fw, 0, -hl + 1, Blocks.ANVIL);
            fr.container(fw + 1, 0, -hl + 1, Deco.barrel(Direction.UP), Deco.LOOT_COMMON);
        }
        for (int fw = f0 + 2; fw <= f0 + 10; fw += 2) {
            fr.set(fw, 0, hl - 1, Blocks.RED_CARPET);
            fr.set(fw + 1, 0, hl - 1, Blocks.RED_CARPET);
        }
        fr.container(f0 + 2, 0, hl - 2, Deco.chest(fr.f), Deco.LOOT_BOSS);
        // the facade: posts, a curved roof, the dragon head over the door
        for (int lt = -hl; lt <= hl; lt++) {
            for (int dy = 0; dy <= h; dy++) {
                boolean door = Math.abs(lt) <= 2 && dy <= 4;
                if (door) {
                    fr.air(f1, dy, lt);
                    continue;
                }
                fr.set(f1, dy, lt, Math.abs(lt) == hl || Math.abs(lt) == 3 ? Deco.pillar(Blocks.STRIPPED_MANGROVE_LOG, Direction.Axis.Y)
                        : dy == 5 || dy == h ? Blocks.CHISELED_POLISHED_BLACKSTONE.defaultBlockState() : Blocks.POLISHED_BLACKSTONE_BRICKS.defaultBlockState());
            }
        }
        // the dragon's head: brow, snout and eyes of blackstone and fire
        for (int lt = -3; lt <= 3; lt++) {
            fr.set(f1 + 1, 9, lt, fr.stair(Blocks.POLISHED_BLACKSTONE_STAIRS, Direction.SOUTH, true));
            fr.set(f1 + 1, 8, lt, Blocks.POLISHED_BLACKSTONE);
        }
        for (int lt = -2; lt <= 2; lt++) {
            fr.set(f1 + 2, 7, lt, Blocks.POLISHED_BLACKSTONE);
            fr.set(f1 + 3, 6, lt, fr.stair(Blocks.POLISHED_BLACKSTONE_STAIRS, Direction.SOUTH, true));
        }
        fr.set(f1 + 1, 9, -2, Blocks.MAGMA_BLOCK);
        fr.set(f1 + 1, 9, 2, Blocks.MAGMA_BLOCK);
        for (int s : new int[]{-1, 1}) {
            fr.set(f1 + 1, 11, 4 * s, Blocks.POLISHED_BLACKSTONE_WALL);
            fr.set(f1 + 1, 12, 5 * s, Blocks.POLISHED_BLACKSTONE_WALL);
        }
        // the stair up from the valley
        for (int i = 0; i <= 12; i++) {
            for (int lt = -3; lt <= 3; lt++) {
                fr.set(f1 + 1 + i, -1 - i / 2, lt, i % 2 == 0 ? fr.stair(Blocks.DEEPSLATE_TILE_STAIRS, Direction.SOUTH, false)
                        : Blocks.DEEPSLATE_TILES.defaultBlockState());
                fr.clear(f1 + 1 + i, -i / 2, lt, f1 + 1 + i, 4, lt);
                fr.foundation(f1 + 1 + i, -2 - i / 2, lt, rock, 16);
            }
        }
        for (int s : new int[]{-4, 4}) {
            fr.set(f1 + 4, 0, s, Blocks.STONE_BRICK_WALL);
            fr.set(f1 + 4, 1, s, Blocks.LANTERN);
        }
    }

    // ------------------------------------------------------------------ Labyrinthian

    /**
     * Bromjunaar's ruins: a broken ring wall with gate arches, the great tribune at the heart (over the barrow's
     * stair), ruined halls and towers along the old streets, and Shalidor's maze to the west.
     */
    private static void labyrinthian(Frame fr) {
        Painter p = fr.p;
        Frame.Masonry stone = nordStone();
        // ground of old paving and snow
        fr.all((fw, lt, x, z) -> {
            double d = Math.hypot(fw, lt);
            if (d > 55) return;
            if (d > 12) p.clear(x, fr.base, z, fr.base + 4); // the heart is the barrow's own (its stair and arch)
            if (d > 10 && p.noise(x, 0, z, 4.0, 985) > -0.2) p.set(x, fr.base - 1, z, p.chance(x, 0, z, 986, 3) ? Blocks.CRACKED_STONE_BRICKS.defaultBlockState()
                    : Blocks.STONE_BRICKS.defaultBlockState());
            // the ring wall, broken in many places
            if (d >= 50 && d < 52) {
                double n = p.noise(x, 0, z, 5.0, 987);
                int h = n > 0.35 ? 0 : 3 + (int) Math.round((1 - n) * 3);
                boolean gate = Math.abs(lt) <= 2 || Math.abs(fw) <= 2;
                for (int dy = 0; dy < h; dy++) if (!(gate && dy <= 3)) p.set(x, fr.base + dy, z, stone.at(p, x, fr.base + dy, z));
                if (gate && h > 4) p.set(x, fr.base + 4, z, Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
            }
        });
        // the tribune: a ring of great columns round the barrow stair, keeping its approach (the forward side) clear
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI * 2 / 12;
            int fw = (int) Math.round(Math.cos(a) * 11), lt = (int) Math.round(Math.sin(a) * 11);
            if (fw > 6 && Math.abs(lt) < 7) continue;
            int h = 6 + fr.roll(fw, lt, 988, 7);
            for (int dy = 0; dy < h; dy++) fr.set(fw, dy, lt, dy == 0 || dy == h - 1 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState()
                    : Blocks.POLISHED_ANDESITE.defaultBlockState());
        }
        // ruined halls and towers along the streets
        int[][] halls = {{24, -26, 10, 7}, {-22, -24, 9, 6}, {26, 22, 8, 8}, {-20, 26, 11, 7}, {36, 0, 6, 9}, {-34, -8, 7, 5}};
        for (int[] hall : halls) ruinHall(fr, hall[0], hall[1], hall[2], hall[3], stone);
        int[][] towers = {{38, -30}, {-38, 32}, {0, -42}, {12, 40}};
        for (int[] t : towers) ruinTower(fr, t[0], t[1], stone);
        // arches over the main street
        for (int fw = 16; fw <= 44; fw += 9) {
            for (int s : new int[]{-3, 3}) for (int dy = 0; dy <= 5; dy++) fr.set(fw, dy, s, stone.at(p, fr.x(fw, s), fr.base + dy, fr.z(fw, s)));
            if (fr.chance(fw, 0, 989, 3)) continue;
            for (int lt = -3; lt <= 3; lt++) fr.set(fw, 6, lt, Math.abs(lt) == 3 ? Blocks.CHISELED_STONE_BRICKS.defaultBlockState()
                    : Blocks.STONE_BRICKS.defaultBlockState());
        }
        // Shalidor's maze: a 9x9 cell maze of stone walls to the west
        maze(fr, -40, -30, 9, 3);
        // the dead of Bromjunaar, and the trolls that took the ruins
        fr.p.spawnDormant(Mobs.draugr(), fr.x(20, -24), fr.base, fr.z(20, -24), 0f);
        fr.p.spawnDormant(Mobs.draugr(), fr.x(-20, 22), fr.base, fr.z(-20, 22), 0f);
        fr.spawn(Mobs.troll(), 30, 0, 18, fr.f, null);
        fr.spawn("minecraft:skeleton", -26, 0, -20, fr.f, null);
        fr.spawn("minecraft:skeleton", 8, 0, -30, fr.f, null);
        fr.container(26, 0, -24, Deco.chest(fr.f), Deco.LOOT_COMMON);
        fr.container(-38, 0, 33, Deco.chest(fr.f), Deco.LOOT_COMMON);
    }

    private static void ruinHall(Frame fr, int fw0, int lt0, int hf, int hl, Frame.Masonry stone) {
        Painter p = fr.p;
        fr.columns(fw0 - hf, lt0 - hl, fw0 + hf, lt0 + hl, (fw, lt, x, z) -> {
            boolean edge = Math.abs(fw - fw0) == hf || Math.abs(lt - lt0) == hl;
            if (!edge) {
                if (p.chance(x, 1, z, 990, 9)) p.set(x, fr.base, z, Deco.slab(Blocks.COBBLESTONE_SLAB, false));
                return;
            }
            int h = Math.max(0, 2 + (int) Math.round((p.noise(x, 0, z, 3.0, 991) + 0.4) * 5));
            boolean door = fw == fw0 + hf && Math.abs(lt - lt0) <= 1;
            for (int dy = 0; dy < h; dy++) if (!(door && dy <= 2)) p.set(x, fr.base + dy, z, stone.at(p, x, fr.base + dy, z));
        });
    }

    private static void ruinTower(Frame fr, int fw0, int lt0, Frame.Masonry stone) {
        Painter p = fr.p;
        fr.columns(fw0 - 4, lt0 - 4, fw0 + 4, lt0 + 4, (fw, lt, x, z) -> {
            double d = Math.hypot(fw - fw0, lt - lt0);
            if (d > 4.2 || d < 3.0) return;
            int h = 6 + (int) Math.round((p.noise(x, 0, z, 3.0, 992) + 1) * 6);
            boolean door = fw > fw0 && Math.abs(lt - lt0) <= 0;
            for (int dy = 0; dy < h; dy++) if (!(door && dy <= 2)) p.set(x, fr.base + dy, z, stone.at(p, x, fr.base + dy, z));
        });
    }

    /** A deterministic maze (depth-first, seeded by its position) of {@code n x n} cells, each cell {@code cell} wide. */
    private static void maze(Frame fr, int fw0, int lt0, int n, int cell) {
        Painter p = fr.p;
        boolean[][] eastOpen = new boolean[n][n], southOpen = new boolean[n][n];
        boolean[][] seen = new boolean[n][n];
        java.util.ArrayDeque<int[]> stack = new java.util.ArrayDeque<>();
        java.util.Random rnd = new java.util.Random(p.seed ^ 0x5A11D0L);
        stack.push(new int[]{0, 0});
        seen[0][0] = true;
        while (!stack.isEmpty()) {
            int[] c = stack.peek();
            java.util.List<int[]> next = new java.util.ArrayList<>();
            int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
            for (int[] d : dirs) {
                int a = c[0] + d[0], b = c[1] + d[1];
                if (a >= 0 && b >= 0 && a < n && b < n && !seen[a][b]) next.add(new int[]{a, b, d[0], d[1]});
            }
            if (next.isEmpty()) {
                stack.pop();
                continue;
            }
            int[] pick = next.get(rnd.nextInt(next.size()));
            if (pick[2] == 1) eastOpen[c[0]][c[1]] = true;
            else if (pick[2] == -1) eastOpen[pick[0]][pick[1]] = true;
            else if (pick[3] == 1) southOpen[c[0]][c[1]] = true;
            else southOpen[pick[0]][pick[1]] = true;
            seen[pick[0]][pick[1]] = true;
            stack.push(new int[]{pick[0], pick[1]});
        }
        int size = n * (cell + 1);
        fr.columns(fw0, lt0, fw0 + size, lt0 + size, (fw, lt, x, z) -> {
            int a = fw - fw0, b = lt - lt0;
            int ca = a / (cell + 1), cb = b / (cell + 1);
            boolean wallA = a % (cell + 1) == cell, wallB = b % (cell + 1) == cell;
            boolean wall = false;
            if (a == 0 || b == 0 || a == size || b == size) wall = !(a == size && cb == n - 1 && !wallB) && !(a == 0 && cb == 0 && !wallB);
            else if (wallA && wallB) wall = true;
            else if (wallA) wall = ca >= n - 1 || !eastOpen[Math.min(ca, n - 1)][Math.min(cb, n - 1)];
            else if (wallB) wall = cb >= n - 1 || !southOpen[Math.min(ca, n - 1)][Math.min(cb, n - 1)];
            p.set(x, fr.base - 1, z, Blocks.STONE_BRICKS.defaultBlockState());
            for (int dy = 0; dy <= 2; dy++) {
                if (wall) p.set(x, fr.base + dy, z, dy == 2 ? Blocks.MOSSY_STONE_BRICKS.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState());
                else p.air(x, fr.base + dy, z);
            }
        });
        // the heart of the maze: Shalidor's reward
        int mid = (n / 2) * (cell + 1) + cell / 2;
        fr.container(fw0 + mid, 0, lt0 + mid, Deco.chest(fr.f), Deco.LOOT_BOSS);
    }
}
