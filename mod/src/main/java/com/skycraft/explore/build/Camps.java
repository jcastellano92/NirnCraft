package com.skycraft.explore.build;

import com.skycraft.creatures.ModEntities;
import com.skycraft.explore.ExploreLoot;
import com.skycraft.survival.SurvivalRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Camps and huts: abandoned and hunters' camps, hermit huts, bandit lookouts and fishing docks. */
public final class Camps {
    private Camps() {}

    // ------------------------------------------------------------------ shared pieces

    /**
     * An A-frame hide tent along local z, open towards +z, with a bedroll inside. {@code torn} leaves holes in the
     * cloth and knocks a pole down (abandoned camps).
     */
    static void tent(SiteBuilder b, int ox, int oz, BlockState cloth, boolean torn) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int[] c : new int[][]{{-2, 0}, {2, 0}, {-1, 1}, {1, 1}, {0, 2}}) {
                if (torn && b.r.nextInt(5) == 0) continue;
                b.set(ox + c[0], c[1], oz + dz, cloth);
            }
        }
        // closed back
        b.set(ox - 1, 0, oz - 2, cloth);
        b.set(ox, 0, oz - 2, cloth);
        b.set(ox + 1, 0, oz - 2, cloth);
        b.set(ox, 1, oz - 2, cloth);
        // poles at the entrance
        b.set(ox - 2, 0, oz + 2, b.pal.fence());
        if (!torn) b.set(ox + 2, 0, oz + 2, b.pal.fence());
        else b.set(ox + 3, 0, oz + 2, b.pal.log().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        bedroll(b, ox, 0, oz - 1, Direction.NORTH);
    }

    /** A Skycraft bedroll (two halves) with its head towards a local direction. */
    static void bedroll(SiteBuilder b, int dx, int dy, int dz, Direction headTo) {
        BlockState base = SurvivalRegistry.BEDROLL.get().defaultBlockState();
        Direction d = b.dir(headTo);
        BlockPos foot = b.at(dx, dy, dz);
        BlockPos head = foot.relative(d);
        b.put(foot, base.setValue(BedBlock.FACING, d).setValue(BedBlock.PART, BedPart.FOOT));
        b.put(head, base.setValue(BedBlock.FACING, d).setValue(BedBlock.PART, BedPart.HEAD));
    }

    /** Log seats around a campfire at (fx, fz). */
    static void seats(SiteBuilder b, int fx, int fz) {
        BlockState logX = b.pal.log().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        BlockState logZ = b.pal.log().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
        b.set(fx - 1, 0, fz + 2, logX);
        b.set(fx, 0, fz + 2, logX);
        b.set(fx + 2, 0, fz, logZ);
        b.set(fx + 2, 0, fz - 1, logZ);
    }

    /** A cooking spit: two fence posts with a fence rail over the fire... */
    static void spit(SiteBuilder b, int fx, int fz) {
        b.set(fx - 1, 0, fz, b.pal.fence());
        b.set(fx + 1, 0, fz, b.pal.fence());
        b.set(fx - 1, 1, fz, b.pal.fence());
        b.set(fx + 1, 1, fz, b.pal.fence());
    }

    /** A drying rack hung with hides: two posts and a trapdoor beam. */
    static void rack(SiteBuilder b, int ox, int oz) {
        b.set(ox, 0, oz, b.pal.fence());
        b.set(ox, 1, oz, b.pal.fence());
        b.set(ox + 2, 0, oz, b.pal.fence());
        b.set(ox + 2, 1, oz, b.pal.fence());
        BlockState beam = b.pal.trapdoor().defaultBlockState().setValue(TrapDoorBlock.HALF, Half.TOP)
                .setValue(TrapDoorBlock.OPEN, true).setValue(TrapDoorBlock.FACING, Direction.NORTH);
        b.set(ox + 1, 1, oz, beam);
        b.set(ox + 1, 0, oz, com.skycraft.worldgen.VanillaBlocks.BROWN_CARPET);
    }

    // ------------------------------------------------------------------ abandoned camp

    public static boolean abandonedCamp(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(4) > 9) return false;
        b.level(5, null, b.pal.path().defaultBlockState(), 5);
        b.scatter(4, 6, b.pal.path().defaultBlockState(), true);
        tent(b, -3, -1, b.pal.wool().defaultBlockState(), true);
        // the dead fire, a toppled barrel and an overturned pot
        b.set(1, 0, 1, Blocks.CAMPFIRE.defaultBlockState().setValue(net.minecraft.world.level.block.CampfireBlock.LIT, false));
        seats(b, 1, 1);
        b.set(3, 0, -2, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.EAST));
        b.lying(2, 0, 3, new ItemStack(Items.BOWL));
        b.set(-3, 1, -1, Blocks.COBWEB);
        b.chest(-3, 0, 1, Direction.SOUTH, ExploreLoot.table("camp"));
        if (b.r.nextBoolean()) b.lying(0, 0, -2, new ItemStack(b.r.nextBoolean() ? Items.BONE : Items.STICK));
        return true;
    }

    // ------------------------------------------------------------------ hunter's camp

    public static boolean huntersCamp(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(4) > 9) return false;
        b.level(5, null, b.pal.path().defaultBlockState(), 5);
        tent(b, -3, -1, (b.r.nextBoolean() ? com.skycraft.worldgen.VanillaBlocks.BROWN_WOOL : com.skycraft.worldgen.VanillaBlocks.WHITE_WOOL).defaultBlockState(), false);
        // a lit fire with a smoke column you can see over the trees
        b.campfire(1, 0, 1, true, true);
        spit(b, 1, 1);
        seats(b, 1, 1);
        rack(b, 2, -3);
        b.set(-1, 0, 3, com.skycraft.worldgen.VanillaBlocks.WHITE_CARPET);
        b.set(-2, 0, 3, com.skycraft.worldgen.VanillaBlocks.BROWN_CARPET);
        b.chest(4, 0, 2, Direction.WEST, ExploreLoot.table("hunter"));
        b.set(4, 0, 0, b.pal.log());
        b.lying(4, 1, 0, new ItemStack(Items.IRON_AXE));
        b.lying(-1, 0, 4, new ItemStack(Items.ARROW, 1 + b.r.nextInt(3)));
        return true;
    }

    // ------------------------------------------------------------------ hermit's hut

    public static boolean hermitHut(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(4) > 10) return false;
        b.level(6, null, b.pal.stone().defaultBlockState(), 7);
        BlockState log = b.pal.log().defaultBlockState();
        BlockState planks = b.pal.planks().defaultBlockState();
        // floor 5x5 inside a 7x7 frame
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                b.pillarDown(x, 0, z, b.pal.stone().defaultBlockState());
                b.set(x, -1, z, Math.abs(x) == 3 || Math.abs(z) == 3 ? b.pal.stone().defaultBlockState() : planks);
            }
        }
        for (int h = 0; h <= 3; h++) {
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) {
                    boolean edge = Math.abs(x) == 3 || Math.abs(z) == 3;
                    if (!edge) continue;
                    boolean corner = Math.abs(x) == 3 && Math.abs(z) == 3;
                    b.set(x, h, z, corner ? log : planks);
                }
            }
        }
        // windows and the door (front = local +z)
        b.set(-3, 1, 0, Blocks.GLASS_PANE);
        b.set(3, 1, 0, Blocks.GLASS_PANE);
        b.set(0, 1, -3, Blocks.GLASS_PANE);
        BlockState door = b.pal.door().defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH);
        b.set(0, 0, 3, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        b.set(0, 1, 3, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        b.set(0, -1, 4, b.pal.path().defaultBlockState());
        // gabled roof along x
        Block stairs = b.pal.stairs();
        for (int z = -4; z <= 4; z++) {
            for (int s = 0; s <= 3; s++) {
                b.set(-4 + s, 4 + s, z, b.stair(stairs, Direction.EAST));
                b.set(4 - s, 4 + s, z, b.stair(stairs, Direction.WEST));
            }
            b.set(0, 7, z, b.pal.slab().defaultBlockState());
        }
        for (int s = 0; s <= 2; s++) {
            for (int x = -3 + s; x <= 3 - s; x++) {
                b.set(x, 4 + s, -3, planks);
                b.set(x, 4 + s, 3, planks);
            }
        }
        // stone chimney with a smoking fire on top
        for (int h = 0; h <= 7; h++) b.set(-2, h, -2, Blocks.COBBLESTONE.defaultBlockState());
        b.campfire(-2, 9, -2, true, true);
        b.set(-2, 8, -2, Blocks.HAY_BLOCK);
        // furnishings
        Camps.bedroll(b, 2, 0, -2, Direction.NORTH);
        b.set(-2, 0, 1, Blocks.CRAFTING_TABLE);
        b.set(-1, 0, -2, Blocks.FURNACE.defaultBlockState().setValue(net.minecraft.world.level.block.FurnaceBlock.FACING, Direction.SOUTH));
        b.set(2, 0, 1, Blocks.BREWING_STAND);
        b.set(2, 1, 2, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        b.set(-2, 0, 2, Blocks.BOOKSHELF);
        b.chest(1, 0, -2, Direction.SOUTH, ExploreLoot.table("hermit"));
        b.barrel(-2, 1, 2, ExploreLoot.table("hermit_food"));
        // a little garden by the door
        for (int x = 2; x <= 4; x++) b.onGround(x, 5, 0, (b.r.nextBoolean() ? Blocks.SWEET_BERRY_BUSH : Blocks.FERN).defaultBlockState());
        return true;
    }

    // ------------------------------------------------------------------ bandit lookout

    public static boolean banditLookout(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 12) return false;
        b.level(4, null, b.pal.path().defaultBlockState(), 9);
        BlockState log = b.pal.log().defaultBlockState();
        int top = 5;
        for (int[] c : new int[][]{{-2, -2}, {2, -2}, {-2, 2}, {2, 2}}) {
            b.pillarDown(c[0], 0, c[1], log);
            for (int h = 0; h < top; h++) b.set(c[0], h, c[1], log);
        }
        // platform and railing
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                b.set(x, top, z, b.pal.planks().defaultBlockState());
                boolean edge = Math.abs(x) == 2 || Math.abs(z) == 2;
                if (edge && !(x == 0 && z == 2)) b.set(x, top + 1, z, b.pal.fence().defaultBlockState());
            }
        }
        // ladder up the front (local +z face of the platform)
        BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH);
        for (int h = 0; h < top; h++) {
            b.set(0, h, 2, b.pal.planks().defaultBlockState());
            b.set(0, h, 3, ladder);
        }
        b.set(0, top, 2, b.pal.planks().defaultBlockState());
        b.set(0, top + 1, 2, Blocks.AIR.defaultBlockState());
        // a banner pole, a lantern and the take
        b.set(-2, top + 2, -2, b.pal.fence().defaultBlockState());
        b.set(-2, top + 3, -2, b.pal.fence().defaultBlockState());
        b.banner(-2, top + 4, -2, DyeColor.BLACK);
        b.set(2, top + 2, -2, Blocks.LANTERN);
        b.chest(1, top + 1, -1, Direction.WEST, ExploreLoot.table("lookout"));
        b.set(-1, top + 1, 1, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        // their fire below
        b.campfire(3, 0, 3, true, true);
        if (ModEntities.BANDIT.get() != null) {
            b.spawn(ModEntities.BANDIT.get(), 3, 1);
            if (b.r.nextBoolean()) b.spawn(ModEntities.BANDIT.get(), -3, 3);
        }
        return true;
    }

    // ------------------------------------------------------------------ fishing dock

    public static boolean fishingDock(SiteBuilder b) {
        // walk towards the water (local +z) to find the shore
        int shore = -1;
        for (int z = 0; z <= 18; z++) {
            if (b.waterAt(0, z)) {
                shore = z;
                break;
            }
        }
        if (shore < 0 || shore > 14) return fishersCamp(b);
        BlockPos w = b.at(0, 0, shore);
        int waterTop = b.groundAt(w.getX(), w.getZ());
        // the deck sits one above the water surface
        b.y = waterTop + 1;
        int deck = 0;
        // approach: from the land side
        int start = Math.max(-3, shore - 4);
        int end = Math.min(shore + 7, 16);
        BlockState planks = b.pal.planks().defaultBlockState();
        for (int z = start; z <= end; z++) {
            for (int x = -1; x <= 1; x++) {
                b.set(x, deck - 1, z, planks);
                b.set(x, deck, z, Blocks.AIR.defaultBlockState());
                b.set(x, deck + 1, z, Blocks.AIR.defaultBlockState());
            }
            if ((z - start) % 3 == 0) {
                b.pillarDown(-1, deck - 1, z, b.pal.log().defaultBlockState());
                b.pillarDown(1, deck - 1, z, b.pal.log().defaultBlockState());
            }
        }
        // posts, rail and a lantern at the end
        b.set(-1, deck, end, b.pal.fence().defaultBlockState());
        b.set(-1, deck + 1, end, Blocks.LANTERN);
        b.set(1, deck, end, b.pal.fence().defaultBlockState());
        b.barrel(1, deck, end - 1, ExploreLoot.table("fisher"));
        b.lying(0, deck, end - 2, new ItemStack(Items.FISHING_ROD));
        b.set(-1, deck, start + 1, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        b.lying(-1, deck + 1, start + 1, new ItemStack(b.r.nextBoolean() ? Items.COD : Items.SALMON));
        // a drying rack on the shore
        rack(b, -4, Math.max(start - 1, -4));
        return true;
    }

    /** A fisher's camp on the bank when no water was found close enough for a dock. */
    private static boolean fishersCamp(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 8) return false;
        b.level(4, null, b.pal.path().defaultBlockState(), 4);
        b.campfire(0, 0, 0, true, true);
        rack(b, -3, -2);
        b.barrel(2, 0, -1, ExploreLoot.table("fisher"));
        b.lying(1, 0, 2, new ItemStack(Items.FISHING_ROD));
        bedroll(b, -2, 0, 2, Direction.WEST);
        return true;
    }

    static BlockState slab(Block slab, boolean top) {
        return slab.defaultBlockState().setValue(SlabBlock.TYPE, top ? SlabType.TOP : SlabType.BOTTOM);
    }

    static BlockState stairsUpsideDown(Block stairs, Direction d) {
        return stairs.defaultBlockState().setValue(StairBlock.FACING, d).setValue(StairBlock.HALF, Half.TOP);
    }
}
