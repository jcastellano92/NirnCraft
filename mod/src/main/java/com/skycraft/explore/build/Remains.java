package com.skycraft.explore.build;

import com.skycraft.arsenal.LeveledGear;
import com.skycraft.explore.ExploreLoot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What the dead leave behind: treasure caches under cairns, fallen adventurers, old battlefields and the wreckage
 * of ships thrown up on the shore.
 */
public final class Remains {
    private Remains() {}

    // ------------------------------------------------------------------ treasure cache

    public static boolean treasureCache(SiteBuilder b) {
        // always built (treasure maps point here): on a slope or a shore it simply follows the ground
        b.y = b.ground(0, 0) + 1;
        b.clear(2, 4);
        // the buried chest two blocks down, under a flat marker stone
        b.set(0, -1, 0, Blocks.COARSE_DIRT);
        b.set(0, -2, 0, Blocks.DIRT);
        b.chest(0, -3, 0, Direction.SOUTH, ExploreLoot.table("cache"));
        // a little cairn of stacked stones beside it, the way the maps draw it
        BlockState[] stones = {Blocks.COBBLESTONE.defaultBlockState(), Blocks.MOSSY_COBBLESTONE.defaultBlockState(),
                Blocks.ANDESITE.defaultBlockState()};
        b.set(1, 0, 0, stones[b.r.nextInt(3)]);
        b.set(1, 1, 0, stones[b.r.nextInt(3)]);
        b.set(1, 2, 0, Blocks.COBBLESTONE_WALL);
        b.set(1, 0, 1, stones[b.r.nextInt(3)]);
        b.set(0, 0, 1, Blocks.COBBLESTONE_SLAB);
        b.set(2, 0, 0, Blocks.COBBLESTONE_SLAB);
        b.set(0, 0, 0, Blocks.STONE_PRESSURE_PLATE);
        b.lying(-1, 0, 1, new ItemStack(Items.STICK));
        return true;
    }

    // ------------------------------------------------------------------ dead adventurer

    public static boolean deadAdventurer(SiteBuilder b) {
        if (b.waterAt(0, 0)) return false;
        b.y = b.ground(0, 0) + 1;
        b.clear(2, 3);
        int level = b.gearLevel();
        // the bones: a skull and a scatter of bones on the ground, the weapon still beside them
        b.skull(0, 0, 0);
        b.lying(1, 0, 0, new ItemStack(Items.BONE));
        b.lying(-1, 0, 1, new ItemStack(Items.BONE));
        b.lying(0, 0, 2, new ItemStack(Items.BONE));
        ItemStack weapon = b.r.nextInt(4) == 0 ? LeveledGear.bow(level, b.r) : LeveledGear.weapon(level, b.r);
        if (!weapon.isEmpty()) {
            weapon.setDamageValue(weapon.getMaxDamage() / 2 + b.r.nextInt(Math.max(1, weapon.getMaxDamage() / 3)));
            b.lying(1, 0, 2, weapon);
        }
        // the pack they carried, a burnt-out torch and a bedroll they never unrolled
        b.chest(-1, 0, -1, Direction.EAST, ExploreLoot.table("adventurer"));
        b.set(2, 0, -1, Blocks.BROWN_CARPET);
        if (b.r.nextBoolean()) b.lying(-2, 0, 0, new ItemStack(Items.TORCH));
        if (b.r.nextInt(3) == 0) {
            // what killed them is still about
            switch (b.site.province()) {
                case "skyrim", "atmora" -> Shrines.spawnById(b, "minecraft:skeleton", 3, 3, true);
                default -> { }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ ancient battlefield

    public static boolean ancientBattlefield(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(6) > 12) return false;
        b.clear(9, 4);
        int level = Math.max(1, b.gearLevel() - 4);
        b.scatter(9, 30, Blocks.COARSE_DIRT.defaultBlockState(), false);
        for (int i = 0; i < 12; i++) {
            int dx = b.r.nextInt(17) - 8, dz = b.r.nextInt(17) - 8;
            BlockPos col = b.at(dx, 0, dz);
            int up = b.groundAt(col.getX(), col.getZ()) + 1 - b.y;
            switch (b.r.nextInt(5)) {
                case 0 -> b.skull(dx, up, dz);
                case 1, 2 -> {
                    ItemStack w = LeveledGear.weapon(level, b.r);
                    if (!w.isEmpty()) w.setDamageValue(w.getMaxDamage() * 2 / 3 + b.r.nextInt(Math.max(1, w.getMaxDamage() / 4)));
                    b.lying(dx, up, dz, w);
                }
                case 3 -> b.lying(dx, up, dz, new ItemStack(b.r.nextBoolean() ? Items.ARROW : Items.BONE, 1 + b.r.nextInt(3)));
                default -> b.lying(dx, up, dz, new ItemStack(Items.BONE));
            }
        }
        // tattered standards of the two sides
        for (int i = 0; i < 2; i++) {
            int dx = i == 0 ? -5 : 5, dz = b.r.nextInt(7) - 3;
            BlockPos col = b.at(dx, 0, dz);
            int up = b.groundAt(col.getX(), col.getZ()) + 1 - b.y;
            b.set(dx, up, dz, b.pal.fence());
            b.set(dx, up + 1, dz, b.pal.fence());
            b.banner(dx, up + 2, dz, i == 0 ? net.minecraft.world.item.DyeColor.GRAY : net.minecraft.world.item.DyeColor.BLACK);
        }
        // fallen warriors still in their armour
        for (int i = 0; i < 2 + b.r.nextInt(2); i++) {
            ItemStack chest = LeveledGear.armor(level, b.r);
            ItemStack hand = LeveledGear.weapon(level, b.r);
            b.standWith(b.r.nextInt(11) - 5, b.r.nextInt(11) - 5, b.r.nextFloat() * 360f,
                    b.r.nextBoolean() ? new ItemStack(Items.SKELETON_SKULL) : ItemStack.EMPTY,
                    chest.getItem() instanceof net.minecraft.world.item.ArmorItem a && a.getType() == net.minecraft.world.item.ArmorItem.Type.CHESTPLATE ? chest : ItemStack.EMPTY,
                    ItemStack.EMPTY, ItemStack.EMPTY, hand);
        }
        // the war chest, half buried
        b.chest(0, -1, 0, Direction.SOUTH, ExploreLoot.table("battlefield"));
        b.set(0, 0, 0, Blocks.COBBLESTONE_SLAB);
        b.set(1, 0, 0, b.pal.log().defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
        return true;
    }

    // ------------------------------------------------------------------ shipwreck debris

    public static boolean shipwreckDebris(SiteBuilder b) {
        // the hull lies on the beach, keel towards the land, bow pointing along the shore
        b.y = b.ground(0, 0) + 1;
        if (b.waterAt(0, 0)) {
            int dry = -1;
            for (int z = 0; z >= -10; z--) {
                if (!b.waterAt(0, z)) {
                    dry = z;
                    break;
                }
            }
            if (dry == -1) return false;
            b.y = b.ground(0, dry) + 1;
        }
        BlockState plank = Blocks.DARK_OAK_PLANKS.defaultBlockState();
        BlockState rib = Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState();
        // keel along local x
        for (int x = -6; x <= 5; x++) {
            int up = b.ground(x, 0) + 1 - b.y;
            b.set(x, up, 0, Blocks.DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X));
            // one side of the hull, broken open
            if (x % 2 == 0 || b.r.nextInt(3) > 0) {
                b.set(x, up, -1, plank);
                if (Math.abs(x) < 5) b.set(x, up + 1, -2, plank);
                if (Math.abs(x) < 4 && b.r.nextBoolean()) b.set(x, up + 2, -2, b.stair(Blocks.DARK_OAK_STAIRS, Direction.SOUTH));
            }
            if (x % 3 == 0) {
                b.set(x, up + 1, -1, rib);
                b.set(x, up + 2, -1, Blocks.DARK_OAK_FENCE);
            }
        }
        // the snapped mast and torn sail
        for (int z = 2; z <= 8; z++) b.set(1, b.ground(1, z) + 1 - b.y, z, Blocks.SPRUCE_LOG.defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z));
        for (int i = 0; i < 6; i++) b.scatter(7, 1, Blocks.WHITE_CARPET.defaultBlockState(), true);
        // cargo strewn along the sand
        b.chest(-2, b.ground(-2, 1) + 1 - b.y, 1, Direction.SOUTH, ExploreLoot.table("shipwreck"));
        for (int i = 0; i < 3; i++) {
            int dx = b.r.nextInt(13) - 6, dz = 2 + b.r.nextInt(5);
            BlockPos p = b.at(dx, 0, dz);
            int up = b.groundAt(p.getX(), p.getZ()) + 1 - b.y;
            if (!b.waterAt(dx, dz)) b.set(dx, up, dz, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.NORTH));
        }
        b.scatter(6, 4, Blocks.CHAIN.defaultBlockState().setValue(net.minecraft.world.level.block.ChainBlock.AXIS, Direction.Axis.X), true);
        return true;
    }
}
