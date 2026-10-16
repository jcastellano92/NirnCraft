package com.skycraft.dungeons.world;

import com.skycraft.Skycraft;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.ChainBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

/** Block-state shorthands and loot table ids shared by every dungeon builder. */
public final class Deco {
    private Deco() {}

    // arsenal's leveled loot tables (contract 20); missing tables just generate empty containers
    public static final ResourceLocation LOOT_COMMON = new ResourceLocation(Skycraft.MODID, "chests/dungeon_common");
    public static final ResourceLocation LOOT_BOSS = new ResourceLocation(Skycraft.MODID, "chests/dungeon_boss");
    public static final ResourceLocation LOOT_MINOR = new ResourceLocation(Skycraft.MODID, "chests/dungeon_minor");
    // dungeons' own supplementary tables
    public static final ResourceLocation LOOT_DWEMER = new ResourceLocation(Skycraft.MODID, "chests/dwemer_scrap");
    public static final ResourceLocation LOOT_GIANT = new ResourceLocation(Skycraft.MODID, "chests/giant_camp");
    public static final ResourceLocation LOOT_MINE = new ResourceLocation(Skycraft.MODID, "chests/mine_ore");
    public static final ResourceLocation LOOT_DRAGON = new ResourceLocation(Skycraft.MODID, "chests/dragon_lair");
    public static final ResourceLocation LOOT_SHRINE = new ResourceLocation(Skycraft.MODID, "chests/daedric_offerings");
    public static final ResourceLocation LOOT_ALCHEMY = new ResourceLocation(Skycraft.MODID, "chests/lair_reagents");

    public static final ItemStack ARROWS = new ItemStack(Items.ARROW, 9);

    public static BlockState stair(Block b, Direction facing, boolean top) {
        return b.defaultBlockState().setValue(StairBlock.FACING, facing).setValue(StairBlock.HALF, top ? Half.TOP : Half.BOTTOM);
    }

    public static BlockState slab(Block b, boolean top) {
        return b.defaultBlockState().setValue(SlabBlock.TYPE, top ? SlabType.TOP : SlabType.BOTTOM);
    }

    public static BlockState lantern(boolean soul, boolean hanging) {
        return (soul ? Blocks.SOUL_LANTERN : Blocks.LANTERN).defaultBlockState().setValue(LanternBlock.HANGING, hanging);
    }

    public static BlockState candle(Block b, int count, boolean lit) {
        return b.defaultBlockState().setValue(CandleBlock.CANDLES, Math.max(1, Math.min(4, count))).setValue(CandleBlock.LIT, lit);
    }

    public static BlockState campfire(boolean soul, boolean lit) {
        return (soul ? Blocks.SOUL_CAMPFIRE : Blocks.CAMPFIRE).defaultBlockState().setValue(CampfireBlock.LIT, lit);
    }

    public static BlockState pillar(Block b, Direction.Axis axis) {
        return b.defaultBlockState().setValue(RotatedPillarBlock.AXIS, axis);
    }

    public static BlockState chain(Direction.Axis axis) {
        return Blocks.CHAIN.defaultBlockState().setValue(ChainBlock.AXIS, axis);
    }

    public static BlockState chest(Direction facing) {
        return Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, facing);
    }

    public static BlockState barrel(Direction facing) {
        return Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, facing);
    }

    public static BlockState skull(Block b, int rotation) {
        return b.defaultBlockState().setValue(SkullBlock.ROTATION, rotation & 15);
    }

    /** Skull rotation (0..15) facing {@code dir}. */
    public static int skullRotation(Direction dir) {
        return switch (dir) {
            case SOUTH -> 0;
            case WEST -> 4;
            case NORTH -> 8;
            default -> 12;
        };
    }

    public static BlockState wallTorch(boolean soul, Direction facing) {
        return (soul ? Blocks.SOUL_WALL_TORCH : Blocks.WALL_TORCH).defaultBlockState().setValue(WallTorchBlock.FACING, facing);
    }

    public static BlockState trapdoor(Block b, Direction facing, boolean top, boolean open) {
        return b.defaultBlockState().setValue(TrapDoorBlock.FACING, facing).setValue(TrapDoorBlock.HALF, top ? Half.TOP : Half.BOTTOM)
                .setValue(TrapDoorBlock.OPEN, open);
    }

    public static BlockState wallLever(Direction facing) {
        return Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.WALL).setValue(LeverBlock.FACING, facing);
    }

    public static BlockState door(Block b, Direction facing, boolean upper) {
        return b.defaultBlockState().setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HINGE, DoorHingeSide.LEFT)
                .setValue(DoorBlock.HALF, upper ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER);
    }

    /** Iron bars / glass panes connected along one horizontal axis. */
    public static BlockState bars(Block b, Direction.Axis along) {
        boolean x = along == Direction.Axis.X;
        return b.defaultBlockState().setValue(CrossCollisionBlock.EAST, x).setValue(CrossCollisionBlock.WEST, x)
                .setValue(CrossCollisionBlock.NORTH, !x).setValue(CrossCollisionBlock.SOUTH, !x);
    }

    /** Fence connected along one horizontal axis. */
    public static BlockState fence(Block b, Direction.Axis along) {
        return bars(b, along);
    }

    // ------------------------------------------------------------------ painter helpers

    public static void column(Painter p, int x, int y0, int y1, int z, BlockState s) {
        for (int y = y0; y <= y1; y++) p.set(x, y, z, s);
    }

    /** An iron door (both halves) at (x, y, z). */
    public static void ironDoor(Painter p, int x, int y, int z, Direction facing) {
        p.set(x, y, z, door(Blocks.IRON_DOOR, facing, false));
        p.set(x, y + 1, z, door(Blocks.IRON_DOOR, facing, true));
    }

    /** A Word Wall (magic module) facing {@code facing}, or a chiseled tablet if magic isn't there. */
    public static void wordWall(Painter p, int x, int y, int z, Direction facing) {
        wordWall(p, x, y, z, facing, 0);
    }

    /**
     * A Word Wall that teaches a given shout: {@code shoutParam} is the shout's ordinal + 1 ({@link SiteParams#shout}),
     * 0 lets the wall pick a weighted random shout the first time it is read.
     */
    public static void wordWall(Painter p, int x, int y, int z, Direction facing, int shoutParam) {
        BlockState wall = Painter.block("skycraft:word_wall", Blocks.CHISELED_STONE_BRICKS.defaultBlockState());
        p.set(x, y, z, Painter.facing(wall, facing));
        if (shoutParam > 0 && p.blockEntity(x, y, z) instanceof com.skycraft.magic.wordwall.WordWallBlockEntity be) {
            be.setShout(com.skycraft.magic.shout.Shout.byOrdinal(shoutParam - 1));
        }
    }

    /** One of the Skyrim ores (crafting module) or a vanilla fallback. */
    public static BlockState ore(Painter p, int x, int y, int z, boolean deep) {
        int roll = p.roll(x, y, z, 404, 100);
        String id;
        BlockState fallback;
        if (roll < 30) return (deep ? Blocks.DEEPSLATE_IRON_ORE : Blocks.IRON_ORE).defaultBlockState();
        if (roll < 42) return (deep ? Blocks.DEEPSLATE_COAL_ORE : Blocks.COAL_ORE).defaultBlockState();
        if (roll < 50) return (deep ? Blocks.DEEPSLATE_GOLD_ORE : Blocks.GOLD_ORE).defaultBlockState();
        fallback = (deep ? Blocks.DEEPSLATE_IRON_ORE : Blocks.IRON_ORE).defaultBlockState();
        if (roll < 64) id = "corundum";
        else if (roll < 76) id = "silver";
        else if (roll < 84) id = "orichalcum";
        else if (roll < 90) id = "quicksilver";
        else if (roll < 95) id = "moonstone";
        else if (roll < 98) id = "malachite";
        else id = "ebony";
        return Painter.block("skycraft:" + (deep ? "deepslate_" : "") + id + "_ore", fallback);
    }

    /** Places a custom 3D Skyrim furniture or clutter block matching the dungeon kit's culture. */
    public static void furniture(Painter p, int x, int y, int z, Direction facing,
                                 com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureKind kind) {
        if (!p.ok(x, y, z)) return;
        com.skycraft.tamriel.settlement.Culture c = switch (p.kit) {
            case IMPERIAL, AYLEID -> com.skycraft.tamriel.settlement.Culture.IMPERIAL;
            case BRETON_KEEP_RUIN -> com.skycraft.tamriel.settlement.Culture.BRETON;
            case REDGUARD -> com.skycraft.tamriel.settlement.Culture.REDGUARD;
            case VELOTHI, DAEDRIC -> com.skycraft.tamriel.settlement.Culture.DUNMER;
            case ALTMER, MAORMER -> com.skycraft.tamriel.settlement.Culture.ALTMER;
            case BOSMER -> com.skycraft.tamriel.settlement.Culture.BOSMER;
            case KHAJIIT -> com.skycraft.tamriel.settlement.Culture.KHAJIIT;
            case XANMEER, SLOAD -> com.skycraft.tamriel.settlement.Culture.ARGONIAN;
            default -> com.skycraft.tamriel.settlement.Culture.NORD;
        };
        BlockState state = com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.stateFor(kind, facing);
        p.set(x, y, z, state);
        if (p.blockEntity(x, y, z) instanceof com.skycraft.tamriel.settlement.furniture.FurnitureBlocks.FurnitureBlockEntity be) {
            be.configure(kind, c.name(), p.roll(x, y, z, 901, 4));
        }
    }
}
