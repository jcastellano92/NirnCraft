package com.skycraft.explore.build;

import com.skycraft.explore.ExploreLoot;
import com.skycraft.lore.LoreBookItem;
import com.skycraft.lore.LoreBooks;
import com.skycraft.tamriel.terrain.Hydrology;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Builders of the river sites ({@code hydrology.json}, see {@code explore.RiverSites}): fishing huts, mills, fords,
 * ferry landings, toll posts, waterfall overlooks, the cave behind a waterfall, sunken caches, bank caches (boulders
 * and hollow logs), hidden fishing spots and spring basins. Local coordinates as in {@link SiteBuilder}: +z points
 * towards the water (or along the stream for fords and downstream for waterfalls). Every builder returns false when
 * the ground does not suit it, and all of them write only inside the 3x3 chunks the feature owns.
 */
public final class RiverBuilds {
    private RiverBuilds() {}

    // ------------------------------------------------------------------ helpers

    private static BlockState door(SiteBuilder b, Direction facing, DoubleBlockHalf half) {
        return b.pal.door().defaultBlockState().setValue(DoorBlock.FACING, facing).setValue(DoorBlock.HALF, half);
    }

    private static void note(SiteBuilder b, BlockPos chest, String id) {
        LoreBooks.Book book = LoreBooks.get(id);
        if (book != null && chest != null) b.addItem(chest, LoreBookItem.create(book));
    }

    /** Puts a block at world column of local (dx, dz) at absolute height y. */
    private static void putAbs(SiteBuilder b, int dx, int y, int dz, BlockState s) {
        BlockPos p = b.at(dx, 0, dz);
        b.put(new BlockPos(p.getX(), y, p.getZ()), s.rotate(b.rot));
    }

    /** Water surface (top water block y) of the column at local (dx, dz), or Integer.MIN_VALUE when it is dry. */
    private static int waterTop(SiteBuilder b, int dx, int dz) {
        BlockPos p = b.at(dx, 0, dz);
        int g = b.groundAt(p.getX(), p.getZ());
        if (b.level.getFluidState(new BlockPos(p.getX(), g, p.getZ())).isEmpty()) return Integer.MIN_VALUE;
        return g;
    }

    /** A little plank pier from the bank (local z 3) out over the water, at most {@code len} blocks into it. */
    private static void pier(SiteBuilder b, int fromZ, int len) {
        int shore = -1;
        for (int z = fromZ; z <= fromZ + 10; z++) {
            if (waterTop(b, 0, z) != Integer.MIN_VALUE) {
                shore = z;
                break;
            }
        }
        if (shore < 0) return;
        int top = waterTop(b, 0, shore);
        BlockState planks = b.pal.planks().defaultBlockState();
        for (int z = Math.max(fromZ, shore - 2); z <= shore + len; z++) {
            putAbs(b, 0, top, z, planks);
            if ((z - shore) % 3 == 0) {
                for (int y = top - 1; y > top - 8; y--) {
                    BlockPos p = b.at(0, 0, z);
                    BlockPos q = new BlockPos(p.getX(), y, p.getZ());
                    if (!b.level.getBlockState(q).getFluidState().isEmpty() || b.level.getBlockState(q).isAir()) {
                        b.put(q, b.pal.log().defaultBlockState());
                    } else {
                        break;
                    }
                }
            }
        }
        putAbs(b, 0, top + 1, shore + len, b.pal.fence().defaultBlockState());
        putAbs(b, 0, top + 2, shore + len, Blocks.LANTERN.defaultBlockState());
    }

    // ------------------------------------------------------------------ fishing hut

    public static boolean fishingHut(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(4) > 5) return false;
        b.level(4, b.pal.path().defaultBlockState(), Blocks.DIRT.defaultBlockState(), 5);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                b.set(dx, -1, dz, b.pal.planks());
                for (int dy = 0; dy <= 2; dy++) {
                    if (corner) b.set(dx, dy, dz, b.pal.log());
                    else if (edge) b.set(dx, dy, dz, dy == 1 && dz != 2 && dx == 0 ? Blocks.GLASS_PANE : b.pal.planks());
                    else b.set(dx, dy, dz, Blocks.AIR);
                }
                b.set(dx, 3, dz, b.pal.slab());
            }
        }
        b.set(0, 0, 2, door(b, Direction.SOUTH, DoubleBlockHalf.LOWER));
        b.set(0, 1, 2, door(b, Direction.SOUTH, DoubleBlockHalf.UPPER));
        b.set(0, 2, 0, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        b.set(0, 3, 0, b.pal.planks());
        b.barrel(-1, 0, -1, ExploreLoot.table("fisher"));
        b.set(1, 0, -1, Blocks.CRAFTING_TABLE);
        b.lying(0, 0, -1, new ItemStack(Items.FISHING_ROD));
        Camps.rack(b, 3, 0);
        pier(b, 3, 5);
        return true;
    }

    // ------------------------------------------------------------------ watermill

    public static boolean watermill(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(4) > 4) return false;
        b.level(5, b.pal.path().defaultBlockState(), Blocks.DIRT.defaultBlockState(), 7);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 1; dz++) {
                boolean edge = Math.abs(dx) == 2 || dz == -2 || dz == 1;
                b.set(dx, -1, dz, b.pal.stone());
                for (int dy = 0; dy <= 3; dy++) {
                    b.set(dx, dy, dz, edge ? (dy == 0 ? b.pal.stone() : b.pal.planks()) : Blocks.AIR);
                }
                b.set(dx, 4, dz, b.pal.stairs().defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING,
                        dz <= -1 ? Direction.NORTH : Direction.SOUTH));
            }
        }
        b.set(0, 0, 1, door(b, Direction.SOUTH, DoubleBlockHalf.LOWER));
        b.set(0, 1, 1, door(b, Direction.SOUTH, DoubleBlockHalf.UPPER));
        // the wheel against the wall that faces the stream: a ring of fence round an axle of log
        int wz = 2;
        int[][] ring = {{-2, 1}, {-2, 2}, {-1, 3}, {0, 4}, {1, 3}, {2, 2}, {2, 1}, {1, 0}, {0, -0}, {-1, 0}};
        for (int[] c : ring) b.set(c[0] + 3, c[1], wz, b.pal.fence());
        b.set(3, 2, wz, Blocks.STRIPPED_OAK_LOG.defaultBlockState());
        b.set(3, 1, wz, b.pal.fence());
        b.set(3, 3, wz, b.pal.fence());
        b.barrel(-1, 0, -1, ExploreLoot.table("hermit_food"));
        b.set(1, 0, -1, Blocks.HAY_BLOCK);
        b.set(1, 1, -1, Blocks.HAY_BLOCK);
        b.set(-1, 0, 0, Blocks.COMPOSTER);
        return true;
    }

    // ------------------------------------------------------------------ ford

    /** A shallow stepping crossing: local z runs along the stream, so the water is crossed along local x. */
    public static boolean ford(SiteBuilder b) {
        int wet = 0;
        for (int dx = -9; dx <= 9; dx++) if (waterTop(b, dx, 0) != Integer.MIN_VALUE) wet++;
        if (wet < 1 || wet > 8) return false;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -9; dx <= 9; dx++) {
                int top = waterTop(b, dx, dz);
                if (top == Integer.MIN_VALUE) continue;
                BlockPos p = b.at(dx, 0, dz);
                // fill the bed up to one block under the surface: knee-deep water over gravel
                for (int y = top - 1; y > top - 8; y--) {
                    BlockPos q = new BlockPos(p.getX(), y, p.getZ());
                    if (b.level.getBlockState(q).getFluidState().isEmpty() && !b.level.getBlockState(q).isAir()) break;
                    b.put(q, ((dx + dz) & 1) == 0 ? Blocks.GRAVEL.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState());
                }
            }
        }
        // marker posts on both banks
        for (int s = -1; s <= 1; s += 2) {
            int dx = s * (wet / 2 + 4);
            BlockPos p = b.at(dx, 0, 2);
            int gy = b.groundAt(p.getX(), p.getZ());
            if (b.level.getFluidState(new BlockPos(p.getX(), gy, p.getZ())).isEmpty()) {
                b.put(new BlockPos(p.getX(), gy + 1, p.getZ()), b.pal.fence().defaultBlockState());
                b.put(new BlockPos(p.getX(), gy + 2, p.getZ()), b.pal.fence().defaultBlockState());
                b.put(new BlockPos(p.getX(), gy + 3, p.getZ()), Blocks.LANTERN.defaultBlockState());
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ ferry landing

    public static boolean ferry(SiteBuilder b) {
        if (b.waterAt(0, 0)) return false;
        b.settle(2);
        b.level(2, b.pal.path().defaultBlockState(), Blocks.DIRT.defaultBlockState(), 4);
        b.set(-2, 0, -1, b.pal.fence());
        b.set(-2, 1, -1, b.pal.fence());
        b.set(-2, 2, -1, Blocks.LANTERN);
        b.barrel(2, 0, -1, ExploreLoot.table("fisher"));
        b.lying(1, 0, 0, new ItemStack(Items.LEAD));
        pier(b, 1, 6);
        // the ferryman's boat, moored beside the pier
        int top = Integer.MIN_VALUE, zAt = 0;
        for (int z = 1; z <= 11 && top == Integer.MIN_VALUE; z++) {
            top = waterTop(b, 1, z);
            zAt = z;
        }
        if (top != Integer.MIN_VALUE) {
            BlockPos p = b.at(1, 0, zAt + 2);
            if (b.writable(p) && b.level.getFluidState(new BlockPos(p.getX(), top, p.getZ())).isSource()) {
                Boat boat = net.minecraft.world.entity.EntityTypes.SPRUCE_BOAT.create(b.level.getLevel(), net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
                if (boat == null) return false;
                boat.setPos(p.getX() + 0.5, top + 0.5, p.getZ() + 0.5);
                boat.setYRot(b.r.nextFloat() * 360f);
                b.level.addFreshEntity(boat);
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ toll post

    public static boolean tollPost(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 4) return false;
        b.level(3, b.pal.path().defaultBlockState(), Blocks.DIRT.defaultBlockState(), 4);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                boolean edge = Math.abs(dx) == 1 || Math.abs(dz) == 1;
                b.set(dx, -1, dz, b.pal.planks());
                for (int dy = 0; dy <= 2; dy++) b.set(dx, dy, dz, edge ? (Math.abs(dx) + Math.abs(dz) == 2 ? b.pal.log() : b.pal.planks()) : Blocks.AIR);
                b.set(dx, 3, dz, b.pal.slab());
            }
        }
        b.set(0, 0, 1, door(b, Direction.SOUTH, DoubleBlockHalf.LOWER));
        b.set(0, 1, 1, door(b, Direction.SOUTH, DoubleBlockHalf.UPPER));
        b.chest(0, 0, -0, Direction.SOUTH, ExploreLoot.table("lookout"));
        b.set(-3, 0, 2, b.pal.fence());
        b.set(-3, 1, 2, b.pal.fence());
        b.banner(-3, 2, 2, b.dye(net.minecraft.world.item.DyeColor.RED));
        b.set(3, 0, 2, b.pal.fence());
        b.set(3, 1, 2, Blocks.LANTERN);
        return true;
    }

    // ------------------------------------------------------------------ waterfall overlook

    public static boolean overlook(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 5) return false;
        b.level(3, b.pal.stone().defaultBlockState(), b.pal.stone().defaultBlockState(), 4);
        for (int dx = -2; dx <= 2; dx++) b.set(dx, 0, 3, b.pal.wall());
        b.set(0, 0, 3, Blocks.AIR);
        b.set(-1, 0, 0, b.pal.stairs().defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.NORTH));
        b.set(1, 0, 0, b.pal.stairs().defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.NORTH));
        b.set(2, 0, -2, b.pal.fence());
        b.set(2, 1, -2, Blocks.LANTERN);
        return true;
    }

    // ------------------------------------------------------------------ the cave behind the fall

    /**
     * A small chamber under the upstream channel, entered through the falling water at the foot of the fall: a stone
     * floor level with the pool, a chest and a lantern. Built only when the surrounding rock is solid, so it never
     * opens into the stream above or the gorge beside it.
     */
    public static boolean fallSecret(SiteBuilder b) {
        Hydrology.Fall f = Hydrology.get().nearestFall(b.cx, b.cz, 10);
        if (f == null || f.drop() < 8f) return false;
        int poolTop = (int) Math.ceil(f.down()) - 1;
        int upTop = (int) Math.ceil(f.up()) - 1;
        int floorY = poolTop;
        // the chamber: x -1..1, z -6..-2 (under the upstream channel), 3 high; the entrance column z -1
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -7; dz <= -1; dz++) {
                for (int y = floorY - 1; y <= floorY + 4; y++) {
                    BlockPos p = b.at(dx, 0, dz);
                    BlockPos q = new BlockPos(p.getX(), y, p.getZ());
                    if (!b.writable(q)) return false;
                    BlockState s = b.level.getBlockState(q);
                    if (s.isAir() || !s.getFluidState().isEmpty() || !s.canOcclude() || s.is(net.minecraft.tags.BlockTags.LEAVES)
                            || s.is(net.minecraft.tags.BlockTags.LOGS)) {
                        return false;
                    }
                }
            }
        }
        // the plunge pool must really be there: water below the lip, air above it
        BlockPos pool = b.at(0, 0, 2);
        if (b.level.getFluidState(new BlockPos(pool.getX(), poolTop, pool.getZ())).isEmpty()) return false;
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState brick = b.pal.brick().defaultBlockState();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -6; dz <= -1; dz++) {
                putAbs(b, dx, floorY, dz, dz % 2 == 0 ? b.pal.mossy().defaultBlockState() : brick);
                for (int y = floorY + 1; y <= floorY + 3; y++) putAbs(b, dx, y, dz, air);
            }
        }
        // the curtain: falling water over the entrance, from the lip down to the pool
        BlockState falling = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 8);
        for (int dx = -1; dx <= 1; dx++) {
            for (int y = floorY + 1; y <= upTop; y++) putAbs(b, dx, y, 0, falling);
        }
        putAbs(b, 1, floorY + 1, -5, Blocks.LANTERN.defaultBlockState());
        putAbs(b, -1, floorY + 1, -5, Blocks.MOSS_CARPET.defaultBlockState());
        BlockPos cp = b.at(0, 0, -6);
        BlockPos chest = new BlockPos(cp.getX(), floorY + 1, cp.getZ());
        b.put(chest, Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING, b.dir(Direction.SOUTH)));
        com.skycraft.worldgen.WorldgenData.setLootTable(b.level, b.r, chest, ExploreLoot.table("cache"));
        b.mainChest = chest;
        note(b, chest, b.r.nextBoolean() ? "explore_note_cache_ledger" : "explore_note_fishers_almanac");
        return true;
    }

    // ------------------------------------------------------------------ sunken cache

    public static boolean sunkenCache(SiteBuilder b) {
        BlockPos c = b.at(0, 0, 0);
        int floor = b.level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, c.getX(), c.getZ());
        int surface = b.level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, c.getX(), c.getZ());
        if (surface - floor < 3 || b.level.getFluidState(new BlockPos(c.getX(), surface - 1, c.getZ())).isEmpty()) return false;
        BlockPos p = new BlockPos(c.getX(), floor, c.getZ());
        b.put(p, Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING, b.dir(Direction.SOUTH)));
        com.skycraft.worldgen.WorldgenData.setLootTable(b.level, b.r, p, ExploreLoot.table("shipwreck"));
        b.mainChest = p;
        for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            BlockPos q = b.at(o[0], 0, o[1]);
            int f2 = b.level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, q.getX(), q.getZ());
            if (b.r.nextInt(3) == 0) b.put(new BlockPos(q.getX(), f2, q.getZ()), Blocks.MOSSY_COBBLESTONE.defaultBlockState());
        }
        return true;
    }

    // ------------------------------------------------------------------ bank caches

    public static boolean bankCache(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 5) return false;
        b.clear(3, 4);
        if (b.r.nextBoolean()) {
            // a mossy boulder with a hollow in it on the side away from the water
            BlockState moss = Blocks.MOSSY_COBBLESTONE.defaultBlockState(), rock = Blocks.COBBLESTONE.defaultBlockState();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int h = 3 - (Math.abs(dx) + Math.abs(dz)) / 2 - (Math.abs(dx) == 2 && Math.abs(dz) == 2 ? 1 : 0);
                    for (int dy = -1; dy < h; dy++) b.set(dx, dy, dz, b.r.nextInt(3) == 0 ? moss : rock);
                }
            }
            b.set(0, 0, 0, Blocks.AIR);
            b.set(0, 1, 0, Blocks.AIR);
            b.barrel(0, 0, 0, ExploreLoot.table("camp"));
            b.set(0, 0, -1, Blocks.AIR);
            b.set(0, 0, -2, Blocks.STONE_BRICKS); // the way in: one cracked block
        } else {
            // a hollow fallen log with a pack stuffed inside
            for (int dz = -2; dz <= 2; dz++) {
                b.set(-1, 0, dz, b.pal.log());
                b.set(1, 0, dz, b.pal.log());
                b.set(0, 1, dz, b.pal.log());
                b.set(0, 0, dz, Blocks.AIR);
            }
            b.set(0, 0, -2, Blocks.MOSS_BLOCK);
            b.barrel(0, 0, 0, ExploreLoot.table("hunter"));
        }
        return true;
    }

    // ------------------------------------------------------------------ hidden fishing spot

    public static boolean hiddenFishing(SiteBuilder b) {
        if (b.waterAt(0, 0) || b.settle(3) > 4) return false;
        b.clear(3, 4);
        b.set(-1, 0, -1, b.pal.stairs().defaultBlockState().setValue(net.minecraft.world.level.block.StairBlock.FACING, Direction.SOUTH));
        b.set(1, 0, -1, b.pal.log());
        BlockPos barrel = b.barrel(2, 0, 0, ExploreLoot.table("fisher"));
        note(b, barrel, "explore_note_fishers_almanac");
        b.lying(0, 0, 1, new ItemStack(Items.FISHING_ROD));
        b.set(-2, 0, 1, Blocks.FERN);
        pier(b, 2, 3);
        return true;
    }

    // ------------------------------------------------------------------ spring basin

    public static boolean springShrine(SiteBuilder b) {
        b.y = b.ground(0, 0) + 1;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 < 3 || d2 > 5) continue;
                if ((dx * 31 + dz * 17 + b.cx) % 4 == 0) continue;
                BlockPos col = b.at(dx, 0, dz);
                int gy = b.groundAt(col.getX(), col.getZ());
                BlockPos p = new BlockPos(col.getX(), gy + 1, col.getZ());
                if (b.level.getBlockState(p).isAir() || b.level.getBlockState(p).canBeReplaced()) {
                    b.put(p, (dx + dz) % 2 == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState());
                }
            }
        }
        BlockPos c = b.at(0, 0, 0);
        int gy = b.groundAt(c.getX(), c.getZ());
        BlockPos top = new BlockPos(c.getX(), gy + 1, c.getZ());
        if (b.level.getBlockState(top).isAir() || b.level.getBlockState(top).canBeReplaced()) b.put(top, Blocks.AZURE_BLUET.defaultBlockState());
        return true;
    }
}
