package com.nirncraft.homestead;

import com.nirncraft.dig.PlacedBlocks;
import com.nirncraft.survival.Divine;
import com.nirncraft.survival.SurvivalRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Builds Hearthfire plans in the world, Nordic style: stone footings, dark log posts, spruce walls under a steep
 * spruce gable roof, glass windows, hanging lanterns, then each room's furnishings. Everything is laid out in a local
 * frame that grows away from the drafting table ({@link Frame}: {@code u} to the right, {@code v} up, {@code w}
 * forward), so a house fits a claim of the default radius whichever way the table faces:
 *
 * <pre>
 *            w 16..23  north wing (storage / trophy room / alchemy laboratory)
 *   west wing w 7..15  MAIN HALL (u -5..5)  east wing        (cellar under the hall)
 *  (u -14..-6)                              (u 6..14)
 *  animal pen w 0..6   SMALL HOUSE (u -3..3) garden (u 7..13)
 *            w -3      drafting table   workshop (u 6..12, w -6..-2)
 * </pre>
 *
 * Builders replace whatever stands in a room's volume, lay foundations down to solid ground, open doorways into
 * rooms built before, and mark every block as placed by a player (so the owner can take it apart).
 */
public final class HomesteadBuilder {
    /** The house frame: {@code origin} is the floor at the middle of the small house's front wall. */
    public record Frame(BlockPos origin, Direction forward) {
        public Direction right() {
            return forward.getClockWise();
        }

        public BlockPos at(int u, int v, int w) {
            return origin.relative(right(), u).above(v).relative(forward, w);
        }

        /** World direction of a local step (du, dw): (0, 1) forward, (1, 0) right. */
        public Direction dir(int du, int dw) {
            if (dw > 0) return forward;
            if (dw < 0) return forward.getOpposite();
            return du > 0 ? right() : right().getOpposite();
        }
    }

    /** A room's ground area (with roof overhang): what must fit inside the claim. */
    public record Area(int u0, int u1, int w0, int w1) {}

    private static final BlockState STONE = Blocks.STONE_BRICKS.defaultBlockState();
    private static final BlockState COBBLE = Blocks.COBBLESTONE.defaultBlockState();
    private static final BlockState FLOOR = Blocks.SPRUCE_PLANKS.defaultBlockState();
    private static final BlockState WALL = Blocks.SPRUCE_PLANKS.defaultBlockState();
    private static final BlockState POST = Blocks.DARK_OAK_LOG.defaultBlockState();
    private static final BlockState BEAM = Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState();

    private final ServerLevel level;
    private final Frame f;
    private final HomesteadClaim claim;

    private HomesteadBuilder(ServerLevel level, HomesteadClaim claim) {
        this.level = level;
        this.claim = claim;
        this.f = frame(claim);
    }

    public static Frame frame(HomesteadClaim claim) {
        return new Frame(claim.getCenter().relative(claim.getFacing(), 3), claim.getFacing());
    }

    /** Ground area of a plan, roof overhang included. */
    public static Area area(String plan) {
        return switch (plan) {
            case "small_house" -> new Area(-4, 4, -1, 7);
            case "main_hall", "cellar" -> new Area(-6, 6, 6, 16);
            case "bedrooms", "greenhouse", "enchanters_tower" -> new Area(-15, -5, 7, 15);
            case "kitchen", "library", "armory" -> new Area(5, 15, 7, 15);
            case "storage_room", "trophy_room", "alchemy_laboratory" -> new Area(-5, 5, 15, 24);
            case "garden" -> new Area(7, 13, 0, 6);
            case "animal_pen" -> new Area(-13, -7, 0, 6);
            case "workshop" -> new Area(6, 12, -6, -2);
            default -> new Area(0, 0, 0, 0);
        };
    }

    /** Null if {@code plan} fits the claim and its area is loaded, else the reason's translation key. */
    public static String checkSite(ServerLevel level, HomesteadClaim claim, String plan) {
        Frame f = frame(claim);
        Area a = area(plan);
        for (int u : new int[]{a.u0(), a.u1()}) {
            for (int w : new int[]{a.w0(), a.w1()}) {
                BlockPos p = f.at(u, 0, w);
                if (!claim.isHorizontallyInside(level, p)) return "homestead.nirncraft.build.too_big";
                if (!level.isLoaded(p)) return "homestead.nirncraft.build.not_loaded";
            }
        }
        return null;
    }

    /** Builds {@code plan}; the caller has checked the site, taken the materials and records the plan as built. */
    public static void build(ServerLevel level, HomesteadClaim claim, String plan) {
        HomesteadBuilder b = new HomesteadBuilder(level, claim);
        switch (plan) {
            case "small_house" -> b.smallHouse();
            case "main_hall" -> b.mainHall();
            case "bedrooms" -> b.westWing("bedrooms");
            case "greenhouse" -> b.westWing("greenhouse");
            case "enchanters_tower" -> b.westWing("tower");
            case "kitchen" -> b.eastWing("kitchen");
            case "library" -> b.eastWing("library");
            case "armory" -> b.eastWing("armory");
            case "storage_room" -> b.northWing("storage");
            case "trophy_room" -> b.northWing("trophy");
            case "alchemy_laboratory" -> b.northWing("alchemy");
            case "cellar" -> b.cellar();
            case "garden" -> b.garden();
            case "animal_pen" -> b.animalPen();
            case "workshop" -> b.workshop();
            default -> { }
        }
    }

    /** The stone and clay deposits of a bought plot, beside the drafting table. */
    public static void placeDeposits(ServerLevel level, HomesteadClaim claim) {
        HomesteadBuilder b = new HomesteadBuilder(level, claim);
        b.ground(-5, -5, -5, -5, COBBLE);
        b.ground(-3, -3, -5, -5, COBBLE);
        b.set(-5, 0, -5, HomesteadRegistry.STONE_DEPOSIT.get().defaultBlockState());
        b.set(-3, 0, -5, HomesteadRegistry.CLAY_DEPOSIT.get().defaultBlockState());
        b.clear(-5, -3, 1, 2, -5, -5);
    }

    // ------------------------------------------------------------------ rooms

    private void smallHouse() {
        room(-3, 3, 0, 6, 4, ROOF_W, true, true);
        door(0, 0);
        windows(-3, 3, 0, 6, 4, 0);
        // inside: bed, chest, table, a cooking hearth
        bed(-2, 4, 1, false);
        chest(2, 5, -1, 0);
        set(2, 0, 4, station("nirncraft:armor_workbench", Blocks.CRAFTING_TABLE).setValue(BlockStateProperties.HORIZONTAL_FACING, f.dir(-1, 0)));
        table(0, 3);
        chair(-1, 3, 1, 0);
        chair(1, 3, -1, 0);
        hearth(2, 2);
        lantern(0, 3, 2);
        lantern(-1, 3, 5);
        rug(-1, 1, 1, 2, com.nirncraft.worldgen.VanillaBlocks.RED_CARPET);
    }

    private void mainHall() {
        room(-5, 5, 7, 15, 5, ROOF_W, false, true);
        // the small house becomes the entryway
        opening(-1, 1, 6, 7, 3);
        windows(-5, 5, 7, 15, 5, 7);
        // central fire pit with a cooking pot over it
        ring(0, 11);
        set(0, 0, 11, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        set(0, 1, 11, SurvivalRegistry.COOKING_POT.get().defaultBlockState());
        // long tables with benches on both sides of the fire
        for (int w = 9; w <= 13; w++) {
            if (w == 11) continue;
            table(-3, w);
            table(3, w);
            chair(-4, w, 1, 0);
            chair(4, w, -1, 0);
        }
        // chests, shelves and lights
        chest(-4, 8, 1, 0);
        chest(4, 8, -1, 0);
        for (int u : new int[]{-4, -3, 3, 4}) set(u, 0, 14, Blocks.BOOKSHELF.defaultBlockState());
        set(-2, 0, 14, Blocks.BARREL.defaultBlockState()); // (2, 14) stays free for the cellar hatch
        for (int w : new int[]{9, 13}) {
            lantern(-3, 4, w);
            lantern(3, 4, w);
        }
        lantern(0, 4, 8);
        rug(-1, 1, 9, 10, com.nirncraft.worldgen.VanillaBlocks.RED_CARPET);
        rug(-1, 1, 12, 13, com.nirncraft.worldgen.VanillaBlocks.RED_CARPET);
    }

    private void westWing(String kind) {
        boolean tower = kind.equals("tower");
        room(-14, -6, 8, 14, tower ? 12 : 4, kind.equals("greenhouse") ? ROOF_FLAT : ROOF_U, true, false);
        opening(-6, -5, 10, 12, 3);
        windows(-14, -6, 8, 14, tower ? 12 : 4, -1);
        switch (kind) {
            case "bedrooms" -> {
                bed(-13, 9, 1, false);
                bed(-12, 9, 1, false);
                bed(-8, 13, -1, true);
                bed(-7, 13, -1, true);
                chest(-13, 13, 0, -1);
                set(-11, 0, 9, Blocks.BARREL.defaultBlockState());
                set(-10, 0, 9, Blocks.BARREL.defaultBlockState());
                set(-7, 0, 9, Blocks.BARREL.defaultBlockState());
                rug(-12, -9, 11, 12, com.nirncraft.worldgen.VanillaBlocks.BLUE_CARPET);
                lantern(-12, 3, 11);
                lantern(-8, 3, 11);
            }
            case "greenhouse" -> {
                // glass walls and roof, planting beds watered from the middle
                for (int u = -14; u <= -6; u++) {
                    for (int w = 8; w <= 14; w++) {
                        boolean edge = u == -14 || u == -6 || w == 8 || w == 14;
                        boolean corner = (u == -14 || u == -6) && (w == 8 || w == 14);
                        if (edge && !corner) {
                            for (int v = 1; v <= 3; v++) {
                                if (!(u == -6 && w >= 10 && w <= 12 && v <= 2)) set(u, v, w, Blocks.GLASS.defaultBlockState());
                            }
                        }
                        set(u, 4, w, edge ? BEAM.setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y) : Blocks.GLASS.defaultBlockState());
                    }
                }
                for (int u = -12; u <= -8; u++) {
                    for (int w = 9; w <= 13; w++) {
                        if (u == -10) {
                            set(u, -1, w, Blocks.WATER.defaultBlockState());
                            continue;
                        }
                        set(u, -1, w, Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, 7));
                        set(u, 0, w, crop((u + w) % 4));
                    }
                }
                set(-10, 0, 9, Blocks.SPRUCE_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.HALF, Half.BOTTOM));
                set(-13, 0, 9, Blocks.POTTED_FERN.defaultBlockState());
                set(-13, 0, 13, Blocks.POTTED_RED_TULIP.defaultBlockState());
                set(-7, 0, 13, Blocks.POTTED_BLUE_ORCHID.defaultBlockState());
                lantern(-10, 3, 11);
            }
            default -> {
                // the enchanter's tower: three floors joined by a ladder
                for (int fl : new int[]{4, 8}) {
                    fill(-13, fl, 9, -7, fl, 13, FLOOR);
                    set(-7, fl, 13, Blocks.AIR.defaultBlockState());
                }
                for (int v = 0; v <= 11; v++) set(-7, v, 13, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, f.dir(0, -1)));
                set(-10, 0, 11, station("nirncraft:arcane_enchanter", Blocks.ENCHANTING_TABLE));
                for (int u = -13; u <= -8; u++) set(u, 0, 13, Blocks.BOOKSHELF.defaultBlockState());
                for (int u = -13; u <= -8; u++) set(u, 1, 13, Blocks.BOOKSHELF.defaultBlockState());
                set(-13, 0, 9, Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.LIT, true));
                set(-10, 5, 11, Blocks.LECTERN.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, f.dir(0, -1)));
                chest(-13, 5, 0, 1);
                set(-10, 9, 11, Blocks.AMETHYST_CLUSTER.defaultBlockState());
                lantern(-10, 3, 9);
                lantern(-10, 7, 9);
                lantern(-10, 11, 9);
            }
        }
    }

    private void eastWing(String kind) {
        room(6, 14, 8, 14, 4, ROOF_U, false, true);
        opening(5, 6, 10, 12, 3);
        windows(6, 14, 8, 14, 4, -1);
        switch (kind) {
            case "kitchen" -> {
                ring(10, 11);
                set(10, 0, 11, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
                set(10, 1, 11, SurvivalRegistry.COOKING_POT.get().defaultBlockState());
                set(13, 0, 9, Blocks.SMOKER.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, f.dir(-1, 0)));
                set(13, 0, 10, Blocks.FURNACE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, f.dir(-1, 0)));
                set(13, 0, 12, Blocks.BARREL.defaultBlockState());
                set(13, 0, 13, Blocks.BARREL.defaultBlockState());
                chest(7, 13, 0, -1);
                table(8, 9);
                chair(7, 9, 1, 0);
                set(9, 0, 13, Blocks.CAULDRON.defaultBlockState());
                lantern(8, 3, 11);
                lantern(12, 3, 11);
            }
            case "library" -> {
                for (int w = 9; w <= 13; w++) {
                    for (int v = 0; v <= 2; v++) set(13, v, w, Blocks.BOOKSHELF.defaultBlockState());
                }
                for (int u = 8; u <= 12; u++) {
                    set(u, 0, 13, Blocks.BOOKSHELF.defaultBlockState());
                    set(u, 1, 13, Blocks.BOOKSHELF.defaultBlockState());
                    set(u, 0, 9, Blocks.BOOKSHELF.defaultBlockState());
                }
                set(10, 0, 11, Blocks.LECTERN.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, f.dir(-1, 0)));
                table(8, 11);
                chair(7, 11, 1, 0);
                set(8, 1, 11, Blocks.CANDLE.defaultBlockState().setValue(BlockStateProperties.LIT, true));
                rug(9, 11, 10, 12, com.nirncraft.worldgen.VanillaBlocks.GREEN_CARPET);
                lantern(10, 3, 11);
            }
            default -> {
                set(13, 0, 9, station("nirncraft:grindstone_wheel", Blocks.GRINDSTONE));
                set(13, 0, 11, station("nirncraft:armor_workbench", Blocks.SMITHING_TABLE));
                set(13, 0, 13, Blocks.ANVIL.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, f.dir(0, 1)));
                chest(7, 13, 0, -1);
                chest(8, 13, 0, -1);
                for (int[] p : new int[][]{{8, 9}, {10, 9}, {10, 13}}) armorStand(p[0], p[1], 0, 1);
                lantern(9, 3, 11);
                lantern(12, 3, 11);
            }
        }
    }

    private void northWing(String kind) {
        room(-4, 4, 16, 23, 4, ROOF_W, false, true);
        opening(-1, 1, 15, 16, 3);
        windows(-4, 4, 16, 23, 4, -1);
        switch (kind) {
            case "storage" -> {
                for (int w = 17; w <= 22; w += 2) {
                    chest(-3, w, 1, 0);
                    chest(3, w, -1, 0);
                }
                for (int u = -2; u <= 2; u++) {
                    set(u, 0, 22, Blocks.BARREL.defaultBlockState());
                    if (u != 0) set(u, 1, 22, Blocks.BARREL.defaultBlockState());
                }
                lantern(0, 3, 19);
            }
            case "trophy" -> {
                for (int[] p : new int[][]{{-3, 18}, {3, 18}, {-3, 21}, {3, 21}}) {
                    set(p[0], 0, p[1], Blocks.BONE_BLOCK.defaultBlockState());
                    set(p[0], 1, p[1], Blocks.SKELETON_SKULL.defaultBlockState());
                }
                set(0, 0, 22, Blocks.BONE_BLOCK.defaultBlockState());
                set(0, 1, 22, Blocks.WITHER_SKELETON_SKULL.defaultBlockState());
                armorStand(-1, 20, 0, -1);
                armorStand(1, 20, 0, -1);
                rug(-1, 1, 17, 21, com.nirncraft.worldgen.VanillaBlocks.BROWN_CARPET);
                lantern(-2, 3, 19);
                lantern(2, 3, 19);
            }
            default -> {
                set(0, 0, 21, station("nirncraft:alchemy_lab", Blocks.BREWING_STAND));
                set(-3, 0, 22, Blocks.BREWING_STAND.defaultBlockState());
                set(3, 0, 22, Blocks.WATER_CAULDRON.defaultBlockState().setValue(BlockStateProperties.LEVEL_CAULDRON, 3));
                for (int w = 17; w <= 20; w++) {
                    set(-3, 0, w, Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
                    set(-3, 1, w, w % 2 == 0 ? Blocks.POTTED_ALLIUM.defaultBlockState() : Blocks.POTTED_BROWN_MUSHROOM.defaultBlockState());
                }
                chest(3, 18, -1, 0);
                set(3, 0, 20, Blocks.BARREL.defaultBlockState());
                lantern(0, 3, 19);
            }
        }
    }

    private void cellar() {
        // stone room under the main hall, reached by a hatch and ladder in its corner
        for (int u = -5; u <= 5; u++) {
            for (int w = 7; w <= 15; w++) {
                for (int v = -6; v <= -2; v++) {
                    boolean edge = u == -5 || u == 5 || w == 7 || w == 15 || v == -6;
                    set(u, v, w, edge ? STONE : Blocks.AIR.defaultBlockState());
                }
            }
        }
        set(2, -1, 14, Blocks.SPRUCE_TRAPDOOR.defaultBlockState().setValue(TrapDoorBlock.FACING, f.dir(0, -1))
                .setValue(TrapDoorBlock.HALF, Half.TOP));
        for (int v = -5; v <= -2; v++) set(2, v, 14, Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, f.dir(0, -1)));
        set(0, -5, 14, SurvivalRegistry.SHRINES.get(Divine.MARA).get().defaultBlockState());
        set(-4, -5, 11, station("nirncraft:alchemy_lab", Blocks.BREWING_STAND));
        set(-4, -5, 9, station("nirncraft:arcane_enchanter", Blocks.ENCHANTING_TABLE));
        for (int w = 8; w <= 12; w += 2) {
            set(4, -5, w, Blocks.BARREL.defaultBlockState());
            set(4, -4, w, Blocks.BARREL.defaultBlockState());
        }
        chest(-4, 14, 1, 0, -5);
        for (int w : new int[]{9, 13}) set(0, -2, w, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
    }

    private void garden() {
        ground(7, 13, 0, 6, Blocks.DIRT.defaultBlockState());
        clear(7, 13, 0, 3, 0, 6);
        for (int u = 7; u <= 13; u++) {
            for (int w = 0; w <= 6; w++) {
                boolean edge = u == 7 || u == 13 || w == 0 || w == 6;
                if (edge) {
                    set(u, -1, w, Blocks.COARSE_DIRT.defaultBlockState());
                    set(u, 0, w, Blocks.SPRUCE_FENCE.defaultBlockState());
                } else if (u == 10) {
                    set(u, -1, w, Blocks.WATER.defaultBlockState());
                } else {
                    set(u, -1, w, Blocks.FARMLAND.defaultBlockState().setValue(BlockStateProperties.MOISTURE, 7));
                    set(u, 0, w, crop(u + w));
                }
            }
        }
        set(7, 0, 3, Blocks.SPRUCE_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, f.dir(1, 0)));
        fixFences(7, 13, 0, 6);
    }

    private void animalPen() {
        ground(-13, -7, 0, 6, Blocks.DIRT.defaultBlockState());
        clear(-13, -7, 0, 3, 0, 6);
        for (int u = -13; u <= -7; u++) {
            for (int w = 0; w <= 6; w++) {
                set(u, -1, w, Blocks.GRASS_BLOCK.defaultBlockState());
                if (u == -13 || u == -7 || w == 0 || w == 6) set(u, 0, w, Blocks.SPRUCE_FENCE.defaultBlockState());
            }
        }
        set(-7, 0, 3, Blocks.SPRUCE_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, f.dir(-1, 0)));
        fixFences(-13, -7, 0, 6);
        set(-12, 0, 1, Blocks.HAY_BLOCK.defaultBlockState());
        set(-12, 0, 2, Blocks.HAY_BLOCK.defaultBlockState());
        set(-12, 0, 5, Blocks.WATER_CAULDRON.defaultBlockState().setValue(BlockStateProperties.LEVEL_CAULDRON, 3));
        animal(net.minecraft.world.entity.EntityTypes.COW, -10, 3);
        animal(net.minecraft.world.entity.EntityTypes.CHICKEN, -9, 2);
        animal(net.minecraft.world.entity.EntityTypes.CHICKEN, -11, 4);
    }

    private void workshop() {
        ground(6, 12, -6, -2, COBBLE);
        clear(6, 12, 0, 4, -6, -2);
        fill(6, -1, -6, 12, -1, -2, Blocks.COBBLESTONE.defaultBlockState());
        for (int[] p : new int[][]{{6, -6}, {12, -6}, {6, -2}, {12, -2}}) {
            for (int v = 0; v <= 2; v++) set(p[0], v, p[1], POST);
        }
        for (int u = 6; u <= 12; u++) {
            for (int w = -6; w <= -2; w++) {
                set(u, 3, w, Blocks.SPRUCE_SLAB.defaultBlockState());
            }
        }
        set(7, 0, -5, station("nirncraft:smelter", Blocks.BLAST_FURNACE));
        set(9, 0, -5, station("nirncraft:blacksmith_forge", Blocks.SMITHING_TABLE));
        set(11, 0, -5, Blocks.ANVIL.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, f.dir(1, 0)));
        set(11, 0, -3, station("nirncraft:tanning_rack", Blocks.LOOM));
        set(7, 0, -3, station("nirncraft:grindstone_wheel", Blocks.GRINDSTONE));
        chest(9, -3, 0, -1);
    }

    // ------------------------------------------------------------------ building blocks

    private static final int ROOF_FLAT = 0, ROOF_W = 1, ROOF_U = 2;

    /**
     * Floor, footings, walls with posts and beams, an empty inside, and the roof: {@code ROOF_W} has its ridge running
     * forward, {@code ROOF_U} sideways, {@code ROOF_FLAT} none (the caller roofs it). {@code overhangA}/{@code B}: the
     * gable ends overhang at the near/left and far/right end (not where the room meets an older one).
     */
    private void room(int u0, int u1, int w0, int w1, int height, int roof, boolean overhangA, boolean overhangB) {
        ground(u0, u1, w0, w1, STONE);
        clear(u0 - 1, u1 + 1, 0, height + (Math.max(u1 - u0, w1 - w0) / 2) + 3, w0 - 1, w1 + 1);
        fill(u0, -1, w0, u1, -1, w1, FLOOR);
        for (int u = u0; u <= u1; u++) {
            for (int w = w0; w <= w1; w++) {
                boolean edgeU = u == u0 || u == u1;
                boolean edgeW = w == w0 || w == w1;
                if (!edgeU && !edgeW) continue;
                for (int v = 0; v < height; v++) {
                    BlockState s;
                    if (edgeU && edgeW) s = POST;
                    else if (v == 0) s = STONE;
                    else if (v == height - 1 || v % 4 == 3) s = BEAM.setValue(RotatedPillarBlock.AXIS, edgeU ? axis(0, 1) : axis(1, 0));
                    else s = WALL;
                    set(u, v, w, s);
                }
            }
        }
        if (roof == ROOF_W) roofAlongW(u0, u1, w0, w1, height, overhangA ? 1 : 0, overhangB ? 1 : 0);
        else if (roof == ROOF_U) roofAlongU(u0, u1, w0, w1, height, overhangA ? 1 : 0, overhangB ? 1 : 0);
    }

    private Direction.Axis axis(int du, int dw) {
        return f.dir(du, dw).getAxis();
    }

    /** Gable roof whose ridge runs forward (slopes face right and left), with planked gable ends. */
    private void roofAlongW(int u0, int u1, int w0, int w1, int base, int ohNear, int ohFar) {
        int left = u0 - 1, right = u1 + 1;
        int k = 0;
        for (; left + k < right - k; k++) {
            for (int w = w0 - ohNear; w <= w1 + ohFar; w++) {
                set(left + k, base + k, w, stair(Blocks.SPRUCE_STAIRS, 1, 0));
                set(right - k, base + k, w, stair(Blocks.SPRUCE_STAIRS, -1, 0));
            }
            for (int u = Math.max(u0, left + k + 1); u <= Math.min(u1, right - k - 1); u++) {
                set(u, base + k, w0, WALL);
                set(u, base + k, w1, WALL);
            }
        }
        if (left + k == right - k) {
            for (int w = w0 - ohNear; w <= w1 + ohFar; w++) set(left + k, base + k, w, Blocks.DARK_OAK_SLAB.defaultBlockState());
        }
    }

    /** Gable roof whose ridge runs sideways (slopes face forward and back). */
    private void roofAlongU(int u0, int u1, int w0, int w1, int base, int ohLeft, int ohRight) {
        int near = w0 - 1, far = w1 + 1;
        int k = 0;
        for (; near + k < far - k; k++) {
            for (int u = u0 - ohLeft; u <= u1 + ohRight; u++) {
                set(u, base + k, near + k, stair(Blocks.SPRUCE_STAIRS, 0, 1));
                set(u, base + k, far - k, stair(Blocks.SPRUCE_STAIRS, 0, -1));
            }
            for (int w = Math.max(w0, near + k + 1); w <= Math.min(w1, far - k - 1); w++) {
                set(u0, base + k, w, WALL);
                set(u1, base + k, w, WALL);
            }
        }
        if (near + k == far - k) {
            for (int u = u0 - ohLeft; u <= u1 + ohRight; u++) set(u, base + k, near + k, Blocks.DARK_OAK_SLAB.defaultBlockState());
        }
    }

    /** Stairs that rise toward the local step (du, dw). */
    private BlockState stair(Block stairs, int du, int dw) {
        return stairs.defaultBlockState().setValue(StairBlock.FACING, f.dir(du, dw));
    }

    /** Glass panes in the side walls every few blocks ({@code skipW}: a wall row left solid, -1 for none). */
    private void windows(int u0, int u1, int w0, int w1, int height, int skipW) {
        int top = Math.min(2, height - 2);
        for (int w = w0 + 2; w <= w1 - 2; w += 3) {
            for (int v = 1; v <= top; v++) {
                glass(u0, v, w);
                glass(u1, v, w);
            }
        }
        for (int u = u0 + 2; u <= u1 - 2; u += 3) {
            if (Math.abs(u) <= 1) continue; // doors and doorways sit in the middle
            for (int v = 1; v <= top; v++) {
                if (w0 != skipW) glass(u, v, w0);
                glass(u, v, w1);
            }
        }
    }

    private void glass(int u, int v, int w) {
        BlockState here = level.getBlockState(f.at(u, v, w));
        if (here.is(Blocks.SPRUCE_PLANKS)) set(u, v, w, Blocks.GLASS_PANE.defaultBlockState());
    }

    /** A doorway through two touching walls (u0..u1 x w0..w1, {@code height} high). */
    private void opening(int u0, int u1, int w0, int w1, int height) {
        for (int u = u0; u <= u1; u++) {
            for (int w = w0; w <= w1; w++) {
                for (int v = 0; v < height; v++) set(u, v, w, Blocks.AIR.defaultBlockState());
            }
        }
    }

    /** The house door in the front wall of the small house (at u, w), opening inward. */
    private void door(int u, int w) {
        Direction facing = f.dir(0, 1);
        BlockState door = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
        set(u, 0, w, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        set(u, 1, w, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        set(u, 2, w, BEAM.setValue(RotatedPillarBlock.AXIS, axis(1, 0)));
        // a step and two lanterns outside
        set(u, -1, w - 1, STONE);
        set(u - 1, 1, w - 1, Blocks.WALL_TORCH.defaultBlockState().setValue(net.minecraft.world.level.block.WallTorchBlock.FACING, f.dir(0, -1)));
        set(u + 1, 1, w - 1, Blocks.WALL_TORCH.defaultBlockState().setValue(net.minecraft.world.level.block.WallTorchBlock.FACING, f.dir(0, -1)));
    }

    /** A bed with its foot at (u, w), head toward (du, dw). Adult double beds are two of these side by side. */
    private void bed(int u, int w, int dw, boolean child) {
        Direction dir = f.dir(0, dw);
        Block block = child ? com.nirncraft.worldgen.VanillaBlocks.GREEN_BED : com.nirncraft.worldgen.VanillaBlocks.RED_BED;
        BlockState foot = block.defaultBlockState().setValue(BedBlock.FACING, dir).setValue(BedBlock.PART, BedPart.FOOT);
        set(u, 0, w, foot);
        set(u, 0, w + dw, foot.setValue(BedBlock.PART, BedPart.HEAD));
        claim.addBed(f.at(u, 0, w + dw).immutable(), child);
    }

    private void chest(int u, int w, int du, int dw) {
        chest(u, w, du, dw, 0);
    }

    private void chest(int u, int w, int du, int dw, int v) {
        set(u, v, w, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, f.dir(du, dw)));
    }

    /** A Nordic table: a post and a top. */
    private void table(int u, int w) {
        set(u, 0, w, Blocks.SPRUCE_FENCE.defaultBlockState());
        set(u, 1, w, Blocks.SPRUCE_PRESSURE_PLATE.defaultBlockState());
    }

    /** A chair facing (du, dw): stairs with their back away from the table. */
    private void chair(int u, int w, int du, int dw) {
        set(u, 0, w, Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, f.dir(-du, -dw)));
    }

    /** A stone ring around a fire pit at (u, w). */
    private void ring(int u, int w) {
        for (int du = -1; du <= 1; du++) {
            for (int dw = -1; dw <= 1; dw++) {
                if (du == 0 && dw == 0) continue;
                set(u + du, -1, w + dw, Blocks.COBBLESTONE.defaultBlockState());
            }
        }
        set(u, -1, w, Blocks.STONE_BRICKS.defaultBlockState());
    }

    /** A corner hearth: a campfire with a cooking pot over it. */
    private void hearth(int u, int w) {
        set(u, -1, w, STONE);
        set(u, 0, w, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
        set(u, 1, w, SurvivalRegistry.COOKING_POT.get().defaultBlockState());
    }

    /** A lantern hanging on a chain from whatever is above (a rafter, the roof, a floor). */
    private void lantern(int u, int v, int w) {
        int top = v + 1;
        while (top < v + 10 && level.getBlockState(f.at(u, top, w)).isAir()) top++;
        for (int c = top - 1; c > v; c--) set(u, c, w, com.nirncraft.worldgen.VanillaBlocks.CHAIN.defaultBlockState());
        set(u, v, w, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
    }

    private void rug(int u0, int u1, int w0, int w1, Block carpet) {
        for (int u = u0; u <= u1; u++) {
            for (int w = w0; w <= w1; w++) {
                if (level.getBlockState(f.at(u, 0, w)).isAir()) set(u, 0, w, carpet.defaultBlockState());
            }
        }
    }

    private BlockState crop(int i) {
        Block[] crops = {SurvivalRegistry.CABBAGE_CROP.get(), SurvivalRegistry.LEEK_CROP.get(), Blocks.POTATOES, Blocks.CARROTS,
                SurvivalRegistry.TOMATO_CROP.get(), Blocks.WHEAT};
        Block c = crops[Math.floorMod(i, crops.length)];
        BlockState s = c.defaultBlockState();
        if (c instanceof CropBlock crop) s = crop.getStateForAge(Math.min(crop.getMaxAge(), 2));
        return s;
    }

    private void fixFences(int u0, int u1, int w0, int w1) {
        for (int u = u0; u <= u1; u++) {
            for (int w = w0; w <= w1; w++) {
                BlockPos p = f.at(u, 0, w);
                BlockState s = level.getBlockState(p);
                if (s.is(BlockTags.FENCES)) {
                    BlockState updated = Block.updateFromNeighbourShapes(s, level, p);
                    level.setBlock(p, updated, 2);
                }
            }
        }
    }

    private void armorStand(int u, int w, int du, int dw) {
        ArmorStand stand = net.minecraft.world.entity.EntityTypes.ARMOR_STAND.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        if (stand == null) return;
        BlockPos p = f.at(u, 0, w);
        stand.snapTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, f.dir(du, dw).toYRot(), 0);
        level.addFreshEntity(stand);
    }

    private void animal(EntityType<? extends Mob> type, int u, int w) {
        Mob mob = type.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
        if (mob == null) return;
        BlockPos p = f.at(u, 0, w);
        mob.snapTo(p.getX() + 0.5, p.getY(), p.getZ() + 0.5, level.getRandom().nextFloat() * 360f, 0);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(p), EntitySpawnReason.EVENT, null);
        mob.setPersistenceRequired();
        level.addFreshEntity(mob);
    }

    /** A crafting station by id (another module's), facing into the room, or {@code fallback}. */
    private BlockState station(String id, Block fallback) {
        Block b = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
        BlockState s = (b == null || b == Blocks.AIR ? fallback : b).defaultBlockState();
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) s = s.setValue(BlockStateProperties.HORIZONTAL_FACING, f.dir(0, -1));
        return s;
    }

    // ------------------------------------------------------------------ terrain

    /** Footings: fills from the floor down to solid ground (at most the claim's depth) with {@code fill}. */
    private void ground(int u0, int u1, int w0, int w1, BlockState fill) {
        for (int u = u0; u <= u1; u++) {
            for (int w = w0; w <= w1; w++) {
                for (int v = -1; v >= -claim.getMinDepth(); v--) {
                    BlockPos p = f.at(u, v, w);
                    BlockState s = level.getBlockState(p);
                    boolean solid = !s.canBeReplaced() && s.getFluidState().isEmpty() && !s.is(BlockTags.LEAVES) && !s.is(BlockTags.LOGS);
                    if (solid && v < -1) break;
                    set(u, v, w, fill);
                }
            }
        }
    }

    /** Empties a volume of natural blocks (trees, terrain, snow); anything built stays. */
    private void clear(int u0, int u1, int v0, int v1, int w0, int w1) {
        for (int u = u0; u <= u1; u++) {
            for (int w = w0; w <= w1; w++) {
                for (int v = v0; v <= v1; v++) {
                    BlockPos p = f.at(u, v, w);
                    BlockState s = level.getBlockState(p);
                    if (s.isAir() || s.getBlock() instanceof DraftingTableBlock || s.getBlock() instanceof DepositBlock) continue;
                    if (PlacedBlocks.isPlayerPlaced(level, p)) continue; // other rooms and the owner's own building stay
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                    PlacedBlocks.mark(level, p, false);
                }
            }
        }
    }

    private void fill(int u0, int v0, int w0, int u1, int v1, int w1, BlockState state) {
        for (int u = Math.min(u0, u1); u <= Math.max(u0, u1); u++) {
            for (int v = Math.min(v0, v1); v <= Math.max(v0, v1); v++) {
                for (int w = Math.min(w0, w1); w <= Math.max(w0, w1); w++) set(u, v, w, state);
            }
        }
    }

    private void set(int u, int v, int w, BlockState state) {
        BlockPos p = f.at(u, v, w);
        if (!claim.isHorizontallyInside(level, p) || level.isOutsideBuildHeight(p)) return;
        BlockState here = level.getBlockState(p);
        if (here.getBlock() instanceof DraftingTableBlock) return;
        level.setBlock(p, state, Block.UPDATE_ALL);
        PlacedBlocks.mark(level, p, !state.isAir());
    }
}
