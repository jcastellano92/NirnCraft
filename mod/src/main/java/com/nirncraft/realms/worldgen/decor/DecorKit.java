package com.nirncraft.realms.worldgen.decor;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Drawing helpers for realm decorators. Writes are confined to a 25-block-wide square around the origin (the feature
 * region rule) and to the build height; {@link #put} only replaces air, fluids and plants, {@link #force} replaces
 * anything but bedrock-like blocks.
 */
public final class DecorKit {
    public final WorldGenLevel level;
    public final BlockPos origin;
    private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
    private static final int REACH = 12;

    public DecorKit(WorldGenLevel level, BlockPos origin) {
        this.level = level;
        this.origin = origin;
    }

    public boolean inReach(int x, int y, int z) {
        return Math.abs(x - origin.getX()) <= REACH && Math.abs(z - origin.getZ()) <= REACH
                && y > level.getMinY() && y < (level.getMaxY() + 1) - 1;
    }

    public BlockState get(int x, int y, int z) {
        return level.getBlockState(m.set(x, y, z));
    }

    /** Air, fluid, plants, snow, leaves. */
    public static boolean open(BlockState s) {
        return s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced() || s.is(BlockTags.LEAVES) || s.is(BlockTags.FLOWERS);
    }

    public boolean put(int x, int y, int z, BlockState s) {
        if (!inReach(x, y, z)) return false;
        BlockState old = get(x, y, z);
        if (!open(old) || old.is(BlockTags.FEATURES_CANNOT_REPLACE)) return false;
        level.setBlock(m.set(x, y, z), s, 2);
        return true;
    }

    public boolean force(int x, int y, int z, BlockState s) {
        if (!inReach(x, y, z)) return false;
        BlockState old = get(x, y, z);
        if (old.is(BlockTags.FEATURES_CANNOT_REPLACE)) return false;
        level.setBlock(m.set(x, y, z), s, 2);
        return true;
    }

    /** Surface y (first free block) at a column, from the worldgen heightmap. */
    public int surface(int x, int z) {
        return level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z);
    }

    /** True when the block below is solid ground and the spot itself is open. */
    public boolean standable(int x, int y, int z) {
        BlockState below = get(x, y - 1, z);
        return open(get(x, y, z)) && below.isFaceSturdy(level, m.set(x, y - 1, z), Direction.UP) && below.getFluidState().isEmpty();
    }

    public void line(double x0, double y0, double z0, double x1, double y1, double z1, double r, BlockState s, boolean forced) {
        double len = Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0) + (z1 - z0) * (z1 - z0));
        int steps = Math.max(1, (int) Math.ceil(len * 2));
        double r2 = r * r + 0.2;
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            double cx = Mth.lerp(t, x0, x1), cy = Mth.lerp(t, y0, y1), cz = Mth.lerp(t, z0, z1);
            for (int x = (int) Math.floor(cx - r); x <= (int) Math.ceil(cx + r); x++)
                for (int z = (int) Math.floor(cz - r); z <= (int) Math.ceil(cz + r); z++)
                    for (int y = (int) Math.floor(cy - r); y <= (int) Math.ceil(cy + r); y++) {
                        double dx = x + 0.5 - cx, dy = y + 0.5 - cy, dz = z + 0.5 - cz;
                        if (dx * dx + dy * dy + dz * dz > r2) continue;
                        if (forced) force(x, y, z, s);
                        else put(x, y, z, s);
                    }
        }
    }

    /** Quadratic curve a -> (control c) -> b, radius tapering r0 -> r1. */
    public void curve(double ax, double ay, double az, double cx, double cy, double cz, double bx, double by, double bz,
                      double r0, double r1, BlockState s, boolean forced) {
        int seg = 8;
        double px = ax, py = ay, pz = az;
        for (int i = 1; i <= seg; i++) {
            double t = i / (double) seg, u = 1 - t;
            double x = u * u * ax + 2 * u * t * cx + t * t * bx;
            double y = u * u * ay + 2 * u * t * cy + t * t * by;
            double z = u * u * az + 2 * u * t * cz + t * t * bz;
            line(px, py, pz, x, y, z, Mth.lerp((i - 0.5) / seg, r0, r1), s, forced);
            px = x;
            py = y;
            pz = z;
        }
    }

    public void blob(int cx, int cy, int cz, double rx, double ry, double rz, BlockState s, boolean forced) {
        for (int x = (int) Math.floor(cx - rx); x <= (int) Math.ceil(cx + rx); x++)
            for (int z = (int) Math.floor(cz - rz); z <= (int) Math.ceil(cz + rz); z++)
                for (int y = (int) Math.floor(cy - ry); y <= (int) Math.ceil(cy + ry); y++) {
                    double dx = (x - cx) / rx, dy = (y - cy) / ry, dz = (z - cz) / rz;
                    if (dx * dx + dy * dy + dz * dz > 1) continue;
                    if (forced) force(x, y, z, s);
                    else put(x, y, z, s);
                }
    }

    /** Fills down from y until solid ground (anchoring a decoration that overhangs a slope). */
    public void root(int x, int y, int z, BlockState s, int maxDepth) {
        for (int d = 0; d < maxDepth; d++) {
            BlockState b = get(x, y - d, z);
            if (!open(b)) return;
            force(x, y - d, z, s);
        }
    }
}
