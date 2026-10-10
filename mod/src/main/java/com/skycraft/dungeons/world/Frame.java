package com.skycraft.dungeons.world;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * A site's local frame for surface builders: {@code fwd} metres towards the site's facing, {@code lat} metres to
 * its right, {@code dy} metres above its ground level. Rectangle operations are clipped to the chunk being painted
 * before they iterate, so big landmarks stay cheap to repaint chunk by chunk.
 */
public final class Frame {
    public final Painter p;
    public final int cx, cz, base;
    public final Direction f, r;

    public Frame(Painter p, int cx, int cz, int base, Direction f) {
        this.p = p;
        this.cx = cx;
        this.cz = cz;
        this.base = base;
        this.f = f;
        this.r = f.getClockWise();
    }

    public int x(int fwd, int lat) {
        return cx + f.getStepX() * fwd + r.getStepX() * lat;
    }

    public int z(int fwd, int lat) {
        return cz + f.getStepZ() * fwd + r.getStepZ() * lat;
    }

    public int fwd(int x, int z) {
        return (x - cx) * f.getStepX() + (z - cz) * f.getStepZ();
    }

    public int lat(int x, int z) {
        return (x - cx) * r.getStepX() + (z - cz) * r.getStepZ();
    }

    /** World direction of a local one: NORTH = forward, EAST = right, SOUTH = back, WEST = left. */
    public Direction dir(Direction local) {
        return switch (local) {
            case NORTH -> f;
            case EAST -> r;
            case SOUTH -> f.getOpposite();
            case WEST -> r.getOpposite();
            default -> local;
        };
    }

    public Direction back() {
        return f.getOpposite();
    }

    public Direction left() {
        return r.getOpposite();
    }

    // ------------------------------------------------------------------ blocks

    public void set(int fwd, int dy, int lat, BlockState s) {
        p.set(x(fwd, lat), base + dy, z(fwd, lat), s);
    }

    public void set(int fwd, int dy, int lat, Block b) {
        set(fwd, dy, lat, b.defaultBlockState());
    }

    public void setIfOpen(int fwd, int dy, int lat, BlockState s) {
        p.setIfOpen(x(fwd, lat), base + dy, z(fwd, lat), s);
    }

    public void air(int fwd, int dy, int lat) {
        p.air(x(fwd, lat), base + dy, z(fwd, lat));
    }

    public BlockState get(int fwd, int dy, int lat) {
        return p.get(x(fwd, lat), base + dy, z(fwd, lat));
    }

    public boolean owns(int fwd, int dy, int lat) {
        return p.ok(x(fwd, lat), base + dy, z(fwd, lat));
    }

    /** Callback for clipped rectangle iteration. */
    public interface Cell {
        void at(int fwd, int lat, int x, int z);
    }

    /** Visits every local column in the rectangle that the current chunk owns. */
    public void columns(int f1, int l1, int f2, int l2, Cell cell) {
        int ax = x(f1, l1), az = z(f1, l1), bx = x(f2, l2), bz = z(f2, l2);
        int minX = Math.max(Math.min(ax, bx), p.minX()), maxX = Math.min(Math.max(ax, bx), p.maxX());
        int minZ = Math.max(Math.min(az, bz), p.minZ()), maxZ = Math.min(Math.max(az, bz), p.maxZ());
        for (int wx = minX; wx <= maxX; wx++) {
            for (int wz = minZ; wz <= maxZ; wz++) cell.at(fwd(wx, wz), lat(wx, wz), wx, wz);
        }
    }

    /** Every column of the piece this chunk owns. */
    public void all(Cell cell) {
        for (int wx = p.minX(); wx <= p.maxX(); wx++) {
            for (int wz = p.minZ(); wz <= p.maxZ(); wz++) cell.at(fwd(wx, wz), lat(wx, wz), wx, wz);
        }
    }

    public void fill(int f1, int dy1, int l1, int f2, int dy2, int l2, BlockState s) {
        int y1 = base + Math.min(dy1, dy2), y2 = base + Math.max(dy1, dy2);
        columns(f1, l1, f2, l2, (fw, la, wx, wz) -> {
            for (int y = Math.max(y1, p.minY()); y <= Math.min(y2, p.maxY()); y++) p.set(wx, y, wz, s);
        });
    }

    public void fill(int f1, int dy1, int l1, int f2, int dy2, int l2, Block b) {
        fill(f1, dy1, l1, f2, dy2, l2, b.defaultBlockState());
    }

    public void clear(int f1, int dy1, int l1, int f2, int dy2, int l2) {
        int y1 = base + Math.min(dy1, dy2), y2 = base + Math.max(dy1, dy2);
        columns(f1, l1, f2, l2, (fw, la, wx, wz) -> p.clear(wx, y1, wz, y2));
    }

    /** Walls (one block thick) around the rectangle from dy1 to dy2. */
    public void walls(int f1, int dy1, int l1, int f2, int dy2, int l2, Masonry m) {
        int fa = Math.min(f1, f2), fb = Math.max(f1, f2), la = Math.min(l1, l2), lb = Math.max(l1, l2);
        int y1 = base + Math.min(dy1, dy2), y2 = base + Math.max(dy1, dy2);
        columns(fa, la, fb, lb, (fw, lt, wx, wz) -> {
            if (fw != fa && fw != fb && lt != la && lt != lb) return;
            for (int y = Math.max(y1, p.minY()); y <= Math.min(y2, p.maxY()); y++) p.set(wx, y, wz, m.at(p, wx, y, wz));
        });
    }

    /** A solid box of masonry. */
    public void block(int f1, int dy1, int l1, int f2, int dy2, int l2, Masonry m) {
        int y1 = base + Math.min(dy1, dy2), y2 = base + Math.max(dy1, dy2);
        columns(f1, l1, f2, l2, (fw, lt, wx, wz) -> {
            for (int y = Math.max(y1, p.minY()); y <= Math.min(y2, p.maxY()); y++) p.set(wx, y, wz, m.at(p, wx, y, wz));
        });
    }

    /** Levels the rectangle: ground block at dy -1 on a foundation, air from dy 0 up to {@code clearTo}. */
    public void pad(int f1, int l1, int f2, int l2, Masonry ground, int clearTo, int depth) {
        columns(f1, l1, f2, l2, (fw, lt, wx, wz) -> {
            p.clear(wx, base, wz, base + clearTo);
            p.set(wx, base - 1, wz, ground.at(p, wx, base - 1, wz));
            p.foundation(wx, base - 2, wz, Blocks.STONE.defaultBlockState(), depth);
        });
    }

    /** Fills from dy down to solid ground (at most {@code depth} blocks) under one local column. */
    public void foundation(int fwd, int dy, int lat, BlockState s, int depth) {
        p.foundation(x(fwd, lat), base + dy, z(fwd, lat), s, depth);
    }

    /**
     * A pitched Nordic roof over the rectangle, ridge along the forward axis (or the lateral axis), eaves at dy;
     * stairs on the slopes and a slab ridge.
     */
    public void gableRoof(int f1, int l1, int f2, int l2, int dy, boolean ridgeAlongFwd, Block stairs, Block slab, @Nullable BlockState gable) {
        int fa = Math.min(f1, f2), fb = Math.max(f1, f2), la = Math.min(l1, l2), lb = Math.max(l1, l2);
        double mid = ridgeAlongFwd ? (la + lb) / 2.0 : (fa + fb) / 2.0;
        int half = (int) Math.ceil(((ridgeAlongFwd ? lb - la : fb - fa)) / 2.0);
        columns(fa, la, fb, lb, (fw, lt, wx, wz) -> {
            int across = ridgeAlongFwd ? lt : fw;
            double d = Math.abs(across - mid);
            int h = (int) Math.floor(half - d);
            int y = base + dy + h;
            boolean ridge = d < 0.75;
            Direction up = ridgeAlongFwd ? (across < mid ? r : r.getOpposite()) : (across < mid ? f : f.getOpposite());
            if (ridge) p.set(wx, y, wz, Deco.slab(slab, false));
            else p.set(wx, y, wz, Deco.stair(stairs, up, false));
            // fill the gable ends under the slope
            boolean end = ridgeAlongFwd ? (fw == fa || fw == fb) : (lt == la || lt == lb);
            if (end && gable != null) for (int yy = base + dy; yy < y; yy++) p.set(wx, yy, wz, gable);
        });
    }

    // ------------------------------------------------------------------ things

    public void spawn(@Nullable String id, int fwd, int dy, int lat, Direction look, @Nullable String name) {
        p.spawn(id, x(fwd, lat), base + dy, z(fwd, lat), Painter.yaw(look), name);
    }

    public void spawn(Kit.Spawn s, int fwd, int dy, int lat, Direction look) {
        p.spawn(s.id(), x(fwd, lat), base + dy, z(fwd, lat), Painter.yaw(look), s.label());
    }

    public void container(int fwd, int dy, int lat, BlockState state, ResourceLocation loot) {
        p.container(x(fwd, lat), base + dy, z(fwd, lat), state, loot);
    }

    public BlockState stair(Block b, Direction local, boolean top) {
        return Deco.stair(b, dir(local), top);
    }

    public int roll(int fwd, int lat, int salt, int n) {
        return p.roll(x(fwd, lat), 0, z(fwd, lat), salt, n);
    }

    public boolean chance(int fwd, int lat, int salt, int oneIn) {
        return p.chance(x(fwd, lat), 0, z(fwd, lat), salt, oneIn);
    }

    /** A block choice by position (weathered masonry and the like). */
    @FunctionalInterface
    public interface Masonry {
        BlockState at(Painter p, int x, int y, int z);

        static Masonry of(BlockState s) {
            return (p, x, y, z) -> s;
        }

        static Masonry of(Block b) {
            BlockState s = b.defaultBlockState();
            return (p, x, y, z) -> s;
        }
    }
}
