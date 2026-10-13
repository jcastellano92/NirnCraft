package com.skycraft.explore.build;

import com.skycraft.explore.MinorSites;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jetbrains.annotations.Nullable;

/**
 * Block-by-block painter for one minor site, in local coordinates: {@code (dx, dz)} from the centre, rotated so local
 * +z points along the site's facing, and {@code dy} above the site's base level {@link #y} (the first air block over
 * the ground at the centre). Writes are refused outside the 3x3 chunks a feature owns, so a builder can never reach a
 * chunk that is not loaded. Block states are rotated with the site.
 */
public final class SiteBuilder {
    public final WorldGenLevel level;
    public final RandomSource r;
    public final MinorSites.Site site;
    public final Palette pal;
    public final Direction facing;
    public final Rotation rot;
    public final int cx, cz;
    /** Base level: the first air block above the ground at the centre (after {@link #settle}). */
    public int y;
    /** The first container placed: the unique item and its note go here. */
    @Nullable
    public BlockPos mainChest;
    private final int chunkX, chunkZ;
    /** Only a fishing dock may build over water; every other site writes to dry blocks only. */
    private final boolean wet;

    public SiteBuilder(WorldGenLevel level, RandomSource r, MinorSites.Site site, Palette pal) {
        this.level = level;
        this.r = r;
        this.site = site;
        this.pal = pal;
        this.facing = site.direction();
        this.rot = switch (facing) {
            case WEST -> Rotation.CLOCKWISE_90;
            case NORTH -> Rotation.CLOCKWISE_180;
            case EAST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
        this.cx = site.x();
        this.cz = site.z();
        this.wet = site.kind() == com.skycraft.explore.SiteKind.FISHING_DOCK;
        this.chunkX = cx >> 4;
        this.chunkZ = cz >> 4;
        this.y = groundAt(cx, cz) + 1;
    }

    // ------------------------------------------------------------------ coordinates

    public BlockPos at(int dx, int dy, int dz) {
        int wx, wz;
        switch (rot) {
            case CLOCKWISE_90 -> { wx = -dz; wz = dx; }
            case CLOCKWISE_180 -> { wx = -dx; wz = -dz; }
            case COUNTERCLOCKWISE_90 -> { wx = dz; wz = -dx; }
            default -> { wx = dx; wz = dz; }
        }
        return new BlockPos(cx + wx, y + dy, cz + wz);
    }

    /** A local direction (SOUTH = the site's facing) in world terms. */
    public Direction dir(Direction local) {
        return local.getAxis().isVertical() ? local : rot.rotate(local);
    }

    public boolean writable(BlockPos p) {
        return Math.abs((p.getX() >> 4) - chunkX) <= 1 && Math.abs((p.getZ() >> 4) - chunkZ) <= 1
                && p.getY() > level.getMinBuildHeight() && p.getY() < level.getMaxBuildHeight();
    }

    // ------------------------------------------------------------------ terrain

    /** Height of the solid natural ground at world column (x, z): skips trees, plants and snow. */
    public int groundAt(int x, int z) {
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, top, z);
        int floor = level.getMinBuildHeight() + 4;
        while (p.getY() > floor) {
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && !s.is(BlockTags.LOGS) && !s.is(BlockTags.LEAVES) && !s.canBeReplaced()
                    && !s.is(Blocks.SNOW) && s.getFluidState().isEmpty() && !s.is(BlockTags.FLOWERS)
                    && !s.is(Blocks.BAMBOO) && !s.is(Blocks.MUSHROOM_STEM) && !s.is(Blocks.BROWN_MUSHROOM_BLOCK)
                    && !s.is(Blocks.RED_MUSHROOM_BLOCK) && !s.is(Blocks.CACTUS) && !s.is(Blocks.VINE)) {
                return p.getY();
            }
            if (!s.getFluidState().isEmpty()) return p.getY(); // water: the caller checks with waterAt
            p.move(Direction.DOWN);
        }
        return floor;
    }

    /** Local ground height (absolute y of the top solid block) under (dx, dz). */
    public int ground(int dx, int dz) {
        BlockPos p = at(dx, 0, dz);
        return groundAt(p.getX(), p.getZ());
    }

    /** True when the column at local (dx, dz) has water (or lava) on top of its ground. */
    public boolean waterAt(int dx, int dz) {
        BlockPos p = at(dx, 0, dz);
        int g = groundAt(p.getX(), p.getZ());
        return !level.getFluidState(new BlockPos(p.getX(), g, p.getZ())).isEmpty()
                || !level.getFluidState(new BlockPos(p.getX(), g + 1, p.getZ())).isEmpty();
    }

    /**
     * Sets the base level to the median ground of the centre and four points at {@code r} and reports the spread
     * (max - min), so builders can refuse cliffs.
     */
    public int settle(int r) {
        int[] hs = {ground(0, 0), ground(r, 0), ground(-r, 0), ground(0, r), ground(0, -r)};
        int[] sorted = hs.clone();
        java.util.Arrays.sort(sorted);
        y = sorted[2] + 1;
        return sorted[4] - sorted[0];
    }

    /** Clears trees and plants over a disc of {@code radius} from the base level up {@code height} blocks. */
    public void clear(int radius, int height) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius + 1) continue;
                for (int dy = 0; dy <= height; dy++) {
                    BlockPos p = at(dx, dy, dz);
                    if (!writable(p)) continue;
                    BlockState s = level.getBlockState(p);
                    // plants, trees and the rock of a slope above the base level all go
                    if (s.isAir() || !s.getFluidState().isEmpty()) continue;
                    level.setBlock(p, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    /**
     * Levels a disc: everything below the base level is filled down to the ground with {@code fill}, the top layer
     * becomes {@code top} (or stays natural when null), and the space above is cleared {@code height} blocks.
     */
    public void level(int radius, @Nullable BlockState top, BlockState fill, int height) {
        clear(radius, height);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius + 1) continue;
                BlockPos floor = at(dx, -1, dz);
                if (!writable(floor)) continue;
                if (!wet && inFluid(floor)) continue;
                if (top != null) level.setBlock(floor, top, 2);
                else if (!solidGround(floor)) level.setBlock(floor, fill, 2);
                for (int d = 1; d <= 10; d++) {
                    BlockPos f = floor.below(d);
                    if (solidGround(f)) break;
                    if (!wet && inFluid(f)) break;
                    level.setBlock(f, fill, 2);
                }
            }
        }
    }

    /** Water or lava at {@code p}, or water directly over it (a lake bed under the footprint). */
    public boolean inFluid(BlockPos p) {
        return !level.getFluidState(p).isEmpty() || !level.getFluidState(p.above()).isEmpty();
    }

    /**
     * True when any of the centre and eight points of the footprint (at 1/2 and full {@code radius}) stand in water.
     * Sites other than docks refuse to build then, so none ever ends up in or over a lake, river or the sea.
     */
    public boolean footprintWet(int radius) {
        if (waterAt(0, 0)) return true;
        for (int d : new int[]{radius / 2, radius}) {
            for (int[] o : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}}) {
                if (waterAt(o[0] * d, o[1] * d)) return true;
            }
        }
        return false;
    }

    /** Solid natural footing: a full block, not water, not a tree. */
    public boolean solidGround(BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.isSolidRender(level, p) && s.getFluidState().isEmpty() && !s.is(BlockTags.LOGS) && !s.is(BlockTags.LEAVES);
    }

    /** A pillar of {@code state} from just below local (dx, dy, dz) down to the ground (foundations, stilts). */
    public void pillarDown(int dx, int dy, int dz, BlockState state) {
        BlockPos p = at(dx, dy - 1, dz);
        for (int d = 0; d < 24 && writable(p); d++) {
            BlockState s = level.getBlockState(p);
            if (s.isSolidRender(level, p) && s.getFluidState().isEmpty() && !s.is(BlockTags.LOGS) && !s.is(BlockTags.LEAVES)) break;
            if (!wet && !s.getFluidState().isEmpty()) break;
            level.setBlock(p, state, 2);
            p = p.below();
        }
    }

    // ------------------------------------------------------------------ blocks

    public void set(int dx, int dy, int dz, BlockState state) {
        put(at(dx, dy, dz), state.rotate(rot));
    }

    public void set(int dx, int dy, int dz, Block block) {
        set(dx, dy, dz, block.defaultBlockState());
    }

    /** Places a block resting on the local ground at (dx, dz), {@code up} blocks above it. */
    public BlockPos onGround(int dx, int dz, int up, BlockState state) {
        BlockPos col = at(dx, 0, dz);
        BlockPos p = new BlockPos(col.getX(), groundAt(col.getX(), col.getZ()) + 1 + up, col.getZ());
        put(p, state.rotate(rot));
        return p;
    }

    public void put(BlockPos p, BlockState state) {
        if (!writable(p)) return;
        BlockState now = level.getBlockState(p);
        if (!wet && !now.getFluidState().isEmpty()) return;
        if (now.getFluidState().is(FluidTags.WATER) && state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            state = state.setValue(BlockStateProperties.WATERLOGGED, true);
        }
        level.setBlock(p, state, 2);
    }

    public void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
        for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++)
            for (int yy = Math.min(y0, y1); yy <= Math.max(y0, y1); yy++)
                for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) set(x, yy, z, state);
    }

    public BlockState state(Block block) {
        return block.defaultBlockState();
    }

    /** A stair facing a local direction. */
    public BlockState stair(Block stairs, Direction local) {
        return stairs.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, local);
    }

    /** Horizontal-facing block (chests, furnaces) facing a local direction. */
    public BlockState facing(Block block, Direction local) {
        BlockState s = block.defaultBlockState();
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) s = s.setValue(BlockStateProperties.HORIZONTAL_FACING, local);
        else if (s.hasProperty(BlockStateProperties.FACING)) s = s.setValue(BlockStateProperties.FACING, local);
        return s;
    }

    /** Random pick of the palette's stone, brick and mossy brick, weathered. */
    public BlockState rubble() {
        return switch (r.nextInt(5)) {
            case 0, 1 -> pal.brick().defaultBlockState();
            case 2 -> pal.mossy().defaultBlockState();
            default -> pal.stone().defaultBlockState();
        };
    }

    // ------------------------------------------------------------------ props

    /**
     * Hard rule: a container never stands over air, liquid or a non-solid block. A short gap is filled with the
     * site's stone; a container with no ground under it (over water, a cliff edge) is not placed at all.
     */
    private boolean supportContainer(int dx, int dy, int dz) {
        BlockPos below = at(dx, dy - 1, dz);
        if (!writable(below) || !writable(at(dx, dy, dz))) return false;
        if (solidSupport(below)) return true;
        BlockState fill = pal.stone().defaultBlockState();
        BlockPos q = below;
        for (int i = 0; i < 6 && writable(q); i++) {
            BlockState s = level.getBlockState(q);
            if (solidSupport(q)) break;
            if (!s.getFluidState().isEmpty() || !(s.isAir() || s.canBeReplaced())) return false;
            level.setBlock(q, fill, 2);
            q = q.below();
        }
        return solidSupport(below);
    }

    private boolean solidSupport(BlockPos p) {
        BlockState s = level.getBlockState(p);
        return !s.isAir() && !s.canBeReplaced() && s.getFluidState().isEmpty() && s.isFaceSturdy(level, p, Direction.UP);
    }

    /** A loot chest; returns its position. */
    public BlockPos chest(int dx, int dy, int dz, Direction local, ResourceLocation loot) {
        BlockPos p = at(dx, dy, dz);
        if (!supportContainer(dx, dy, dz)) return p;
        put(p, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, dir(local)));
        if (writable(p)) {
            RandomizableContainerBlockEntity.setLootTable(level, r, p, loot);
            if (mainChest == null) mainChest = p;
        }
        return p;
    }

    public BlockPos barrel(int dx, int dy, int dz, @Nullable ResourceLocation loot) {
        BlockPos p = at(dx, dy, dz);
        if (!supportContainer(dx, dy, dz)) return p;
        put(p, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Direction.UP));
        if (loot != null && writable(p)) {
            RandomizableContainerBlockEntity.setLootTable(level, r, p, loot);
            if (mainChest == null) mainChest = p;
        }
        return p;
    }

    /** Puts a stack into the first free slot of the container at {@code p} (worldgen-time, before any loot roll). */
    public void addItem(BlockPos p, ItemStack stack) {
        if (level == null || stack.isEmpty() || !writable(p)) return;
        BlockEntity be = level.getBlockEntity(p);
        if (be instanceof Container c) {
            try {
                for (int i = 0; i < c.getContainerSize(); i++) {
                    int slot = (i * 7 + 4) % c.getContainerSize();
                    if (c.getItem(slot).isEmpty()) {
                        c.setItem(slot, stack);
                        return;
                    }
                }
            } catch (NullPointerException e) {
                // a worldgen container with a loot table has no level yet: reading it would unpack the table; leave it
            }
        }
    }

    public void campfire(int dx, int dy, int dz, boolean lit, boolean smoke) {
        if (smoke) set(dx, dy - 1, dz, Blocks.HAY_BLOCK);
        set(dx, dy, dz, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, lit)
                .setValue(CampfireBlock.SIGNAL_FIRE, smoke));
    }

    public void banner(int dx, int dy, int dz, DyeColor color) {
        put(at(dx, dy, dz), BannerBlock.byColor(color).defaultBlockState().setValue(BannerBlock.ROTATION, r.nextInt(16)));
    }

    /** Playtest 3: loose skulls read as "random heads for no reason"; surface sites use bones instead. */
    public void skull(int dx, int dy, int dz) {
        BlockPos p = at(dx, dy, dz);
        if (free(dx, dy, dz)) put(p, Blocks.BONE_BLOCK.defaultBlockState());
    }

    /**
     * An item lying on the ground: an invisible, fixed item frame facing up. Everything is set through NBT: the
     * frame's own setters update neighbours and play sounds on the live level, which a worldgen thread must not do
     * (it deadlocks waiting for the chunk being generated).
     */
    public void lying(int dx, int dy, int dz, ItemStack stack) {
        BlockPos p = at(dx, dy, dz);
        if (!writable(p) || stack.isEmpty()) return;
        ItemFrame frame = new ItemFrame(level.getLevel(), p, Direction.UP);
        CompoundTag t = new CompoundTag();
        frame.saveWithoutId(t);
        ItemStack one = stack.copy();
        one.setCount(1);
        t.put("Item", one.save(new CompoundTag()));
        t.putByte("ItemRotation", (byte) r.nextInt(8));
        t.putBoolean("Fixed", true);
        t.putBoolean("Invisible", true);
        t.putBoolean("Silent", true);
        frame.load(t);
        level.addFreshEntity(frame);
    }

    /** A posed armour stand wearing the given pieces (head, chest, legs, feet; empty to skip), set through NBT. */
    public void standWith(int dx, int dz, float yaw, ItemStack head, ItemStack chest, ItemStack legs, ItemStack feet, ItemStack hand) {
        BlockPos p = at(dx, 0, dz);
        int gy = groundAt(p.getX(), p.getZ()) + 1;
        BlockPos at = new BlockPos(p.getX(), gy, p.getZ());
        if (!writable(at)) return;
        ArmorStand stand = EntityType.ARMOR_STAND.create(level.getLevel());
        if (stand == null) return;
        stand.moveTo(at.getX() + 0.5, gy, at.getZ() + 0.5, yaw, 0);
        CompoundTag t = new CompoundTag();
        stand.saveWithoutId(t);
        net.minecraft.nbt.ListTag armor = new net.minecraft.nbt.ListTag();
        for (ItemStack s : new ItemStack[]{feet, legs, chest, head}) armor.add(s.save(new CompoundTag()));
        net.minecraft.nbt.ListTag hands = new net.minecraft.nbt.ListTag();
        hands.add(hand.save(new CompoundTag()));
        hands.add(ItemStack.EMPTY.save(new CompoundTag()));
        t.put("ArmorItems", armor);
        t.put("HandItems", hands);
        t.putBoolean("ShowArms", true);
        t.putBoolean("NoBasePlate", true);
        t.putBoolean("Silent", true);
        stand.load(t);
        level.addFreshEntity(stand);
    }

    /** Spawns a persistent mob at local (dx, dz) on the ground. */
    @Nullable
    public Mob spawn(EntityType<? extends Mob> type, int dx, int dz) {
        BlockPos col = at(dx, 0, dz);
        if (!writable(col)) return null;
        Mob mob = type.create(level.getLevel());
        if (mob == null) return null;
        int gy = groundAt(col.getX(), col.getZ()) + 1;
        mob.moveTo(col.getX() + 0.5, gy, col.getZ() + 0.5, r.nextFloat() * 360f, 0);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(col), MobSpawnType.STRUCTURE, null, null);
        mob.setPersistenceRequired();
        level.addFreshEntityWithPassengers(mob);
        return mob;
    }

    /** Scatters a few blocks of {@code state} on the ground within {@code radius} (bones, stones, flowers). */
    public void scatter(int radius, int count, BlockState state, boolean replacePlants) {
        for (int i = 0; i < count; i++) {
            int dx = r.nextInt(radius * 2 + 1) - radius, dz = r.nextInt(radius * 2 + 1) - radius;
            BlockPos col = at(dx, 0, dz);
            if (!writable(col)) continue;
            int gy = groundAt(col.getX(), col.getZ());
            BlockPos p = new BlockPos(col.getX(), gy + 1, col.getZ());
            BlockState now = level.getBlockState(p);
            if (!level.getFluidState(p).isEmpty() || !level.getFluidState(p.below()).isEmpty()) continue;
            if (now.isAir() || (replacePlants && now.canBeReplaced())) put(p, state);
        }
    }

    /** True when local (dx, dy, dz) is air or a replaceable plant. */
    public boolean free(int dx, int dy, int dz) {
        BlockState s = level.getBlockState(at(dx, dy, dz));
        return s.isAir() || s.canBeReplaced();
    }

    /** A level for gear made at worldgen: somewhere in the lower half of the region's level band. */
    public int gearLevel() {
        com.skycraft.tamriel.Region region = com.skycraft.tamriel.TamrielMap.region(cx, cz);
        int lo = region.minLevel(), hi = Math.max(lo, region.maxLevel());
        return lo + r.nextInt(Math.max(1, (hi - lo) / 2 + 1));
    }

    public DyeColor dye(DyeColor fallback) {
        return pal.banner() != null ? pal.banner() : fallback;
    }
}
