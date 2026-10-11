package com.skycraft.tamriel.settlement.build;

import com.skycraft.Skycraft;
import com.skycraft.tamriel.settlement.kit.Palette;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Furniture and Skycraft stations. Station blocks are looked up by id (other modules own them) with a vanilla
 * stand-in, so a missing module never breaks worldgen.
 */
public final class Props {
    private Props() {}

    // ------------------------------------------------------------------ stations (skycraft ids, vanilla fallbacks)

    public static BlockState forge() {
        return B.id("skycraft:blacksmith_forge", Blocks.BLAST_FURNACE);
    }

    public static BlockState smelter() {
        return B.id("skycraft:smelter", Blocks.FURNACE);
    }

    public static BlockState workbench() {
        return B.id("skycraft:armor_workbench", Blocks.SMITHING_TABLE);
    }

    public static BlockState grindstone() {
        return B.id("skycraft:grindstone_wheel", Blocks.GRINDSTONE);
    }

    public static BlockState tanningRack() {
        return B.id("skycraft:tanning_rack", Blocks.LOOM);
    }

    public static BlockState alchemyLab() {
        return B.id("skycraft:alchemy_lab", Blocks.BREWING_STAND);
    }

    public static BlockState enchanter() {
        return B.id("skycraft:arcane_enchanter", Blocks.ENCHANTING_TABLE);
    }

    public static BlockState cookingPot() {
        return B.id("skycraft:cooking_pot", Blocks.CAULDRON);
    }

    public static BlockState cheese() {
        return B.id("skycraft:cheese_wheel", Blocks.CAKE);
    }

    public static BlockState shrine(String divine) {
        return B.id("skycraft:shrine_of_" + divine, Blocks.LECTERN);
    }

    public static BlockState ore(String id, net.minecraft.world.level.block.Block fallback) {
        return B.id("skycraft:" + id + "_ore", fallback);
    }

    public static final String[] DIVINES = {"akatosh", "arkay", "dibella", "julianos", "kynareth", "mara", "stendarr", "talos", "zenithar"};

    // ------------------------------------------------------------------ loot tables (household loot: theft is a crime)

    public static ResourceLocation loot(String name) {
        return new ResourceLocation(Skycraft.MODID, "chests/village/" + name);
    }

    // ------------------------------------------------------------------ furniture

    /** A table top at (x, y, z). */
    public static void table(Canvas c, Palette p, int x, int y, int z) {
        c.set(x, y, z, B.slab(p.slab, true));
    }

    /** A chair at (x, y, z) whose seat faces away from {@code back}. */
    public static void chair(Canvas c, Palette p, int x, int y, int z, Direction back) {
        c.set(x, y, z, B.stair(p.stairs, back, false));
    }

    public static void candles(Canvas c, int x, int y, int z, int count) {
        BlockState s = B.s(Blocks.CANDLE).setValue(CandleBlock.CANDLES, Math.max(1, Math.min(4, count))).setValue(CandleBlock.LIT, true);
        c.set(x, y, z, s);
    }

    public static void lantern(Canvas c, Palette p, int x, int y, int z, boolean hanging) {
        BlockState l = p.light;
        if (hanging && l.hasProperty(BlockStateProperties.HANGING)) l = B.hanging(l);
        c.set(x, y, z, l);
    }

    public static void barrel(Canvas c, int x, int y, int z, String loot) {
        c.container(x, y, z, B.face(B.s(Blocks.BARREL), Direction.UP), loot == null ? null : loot(loot));
    }

    public static void chest(Canvas c, int x, int y, int z, Direction face, String loot) {
        c.container(x, y, z, B.face(B.s(Blocks.CHEST), face), loot == null ? null : loot(loot));
    }

    public static void rug(Canvas c, BlockState carpet, int x0, int y, int z0, int x1, int z1) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) c.setIfOpen(x, y, z, carpet);
    }

    public static void pot(Canvas c, int x, int y, int z, int salt) {
        BlockState[] pots = {B.s(Blocks.POTTED_FERN), B.s(Blocks.POTTED_RED_MUSHROOM), B.s(Blocks.POTTED_BLUE_ORCHID),
                B.s(Blocks.POTTED_DEAD_BUSH), B.s(Blocks.POTTED_ALLIUM), B.s(Blocks.POTTED_LILY_OF_THE_VALLEY), B.s(Blocks.FLOWER_POT)};
        BlockState s = pots[c.roll(x, y, z, salt, pots.length)];
        if (s.getBlock() instanceof FlowerPotBlock) c.set(x, y, z, s);
    }

    /** Fireplace in a wall: stone surround, fire, and a cooking pot hung over it. */
    public static void hearth(Canvas c, Palette p, int x, int y, int z, Direction intoRoom) {
        Direction side = intoRoom.getClockWise();
        c.set(x, y, z, B.lit(p.hearth, true));
        c.set(x, y + 1, z, cookingPot());
        c.set(x + side.getStepX(), y, z + side.getStepZ(), p.foundation);
        c.set(x - side.getStepX(), y, z - side.getStepZ(), p.foundation);
        c.set(x + side.getStepX(), y + 1, z + side.getStepZ(), p.foundation);
        c.set(x - side.getStepX(), y + 1, z - side.getStepZ(), p.foundation);
        c.set(x + side.getStepX(), y + 2, z + side.getStepZ(), B.stair(p.trim, intoRoom.getOpposite(), true));
        c.set(x - side.getStepX(), y + 2, z - side.getStepZ(), B.stair(p.trim, intoRoom.getOpposite(), true));
        c.set(x, y + 2, z, p.foundation);
    }

    /** Sunken Nordic fire pit with a cooking pot (the floor block is replaced by the fire). */
    public static void firePit(Canvas c, Palette p, int x, int y, int z, int len, boolean alongZ) {
        for (int i = 0; i < len; i++) {
            int fx = alongZ ? x : x + i, fz = alongZ ? z + i : z;
            c.set(fx, y - 1, fz, B.lit(p.hearth, true));
            c.air(fx, y, fz);
        }
        for (int i = -1; i <= len; i++) {
            int ax = alongZ ? x - 1 : x + i, az = alongZ ? z + i : z - 1;
            int bx = alongZ ? x + 1 : x + i, bz = alongZ ? z + i : z + 1;
            c.set(ax, y - 1, az, p.foundation);
            c.set(bx, y - 1, bz, p.foundation);
            c.set(ax, y, az, B.slab(p.trimSlab, false));
            c.set(bx, y, bz, B.slab(p.trimSlab, false));
        }
        int ex0 = alongZ ? x : x - 1, ez0 = alongZ ? z - 1 : z;
        int ex1 = alongZ ? x : x + len, ez1 = alongZ ? z + len : z;
        c.set(ex0, y - 1, ez0, p.foundation);
        c.set(ex1, y - 1, ez1, p.foundation);
        c.set(ex0, y, ez0, B.slab(p.trimSlab, false));
        c.set(ex1, y, ez1, B.slab(p.trimSlab, false));
        int mx = alongZ ? x : x + len / 2, mz = alongZ ? z + len / 2 : z;
        c.set(mx, y, mz, cookingPot());
    }

    /** Free-standing brazier: a fire on a stone stand. */
    public static void brazier(Canvas c, Palette p, int x, int y, int z) {
        c.set(x, y, z, p.foundation);
        c.set(x, y + 1, z, B.lit(B.s(Blocks.CAMPFIRE), true));
    }

    /** A banner-like hanging cloth (two blocks of accent colour) on a wall. */
    public static void tapestry(Canvas c, Palette p, int x, int y, int z) {
        c.set(x, y, z, p.accent);
        c.set(x, y - 1, z, p.accent);
    }

    /** A rack of three pelts / carpets on the wall. */
    public static void pelts(Canvas c, Palette p, int x, int y, int z) {
        c.setIfOpen(x, y, z, p.carpet2);
    }
}
