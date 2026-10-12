package com.skycraft.tamriel.settlement.build;

import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.kit.Palette;
import com.skycraft.tamriel.settlement.plan.Spec;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;

/**
 * Open-air works of a settlement: streets, plazas, wells, lamps, market stalls, fields, walls, gates, docks, mines,
 * graveyards, terraces, bridges, stables, lumber yards and homestead plots.
 */
public final class Works {
    private Works() {}

    // ------------------------------------------------------------------ line helpers

    interface LineVisitor {
        void visit(int x, int z, double t, double side);
    }

    /** Visits the world columns of this chunk within {@code half} blocks of the segment (x, z) to (x2, z2). */
    static void line(Canvas c, Spec s, double half, LineVisitor v) {
        if (c.recording()) return;
        double ax = s.x + 0.5, az = s.z + 0.5, bx = s.x2 + 0.5, bz = s.z2 + 0.5;
        double dx = bx - ax, dz = bz - az;
        double len2 = dx * dx + dz * dz;
        int minX = Math.max(c.clipMinX(), Math.min(s.x, s.x2) - (int) Math.ceil(half) - 1);
        int maxX = Math.min(c.clipMaxX(), Math.max(s.x, s.x2) + (int) Math.ceil(half) + 1);
        int minZ = Math.max(c.clipMinZ(), Math.min(s.z, s.z2) - (int) Math.ceil(half) - 1);
        int maxZ = Math.min(c.clipMaxZ(), Math.max(s.z, s.z2) + (int) Math.ceil(half) + 1);
        double len = Math.sqrt(len2);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                double px = x + 0.5 - ax, pz = z + 0.5 - az;
                double t = len2 < 1e-6 ? 0 : Math.max(0, Math.min(1, (px * dx + pz * dz) / len2));
                double qx = px - t * dx, qz = pz - t * dz;
                double dist = Math.max(Math.abs(qx), Math.abs(qz));
                if (dist > half + 0.01) continue;
                double side = len < 1e-6 ? 0 : (px * dz - pz * dx) / len;
                v.visit(x, z, t, side);
            }
        }
    }

    // ------------------------------------------------------------------ streets

    public static void street(Canvas c, Palette p, Spec s) {
        double half = s.w / 2.0;
        boolean major = s.has(Spec.MAJOR);
        int ddx = s.x2 - s.x, ddz = s.z2 - s.z;
        int sx = Math.abs(ddx) >= Math.abs(ddz) ? Integer.signum(ddx) : 0, sz = Math.abs(ddx) >= Math.abs(ddz) ? 0 : Integer.signum(ddz);
        line(c, s, half, (x, z, t, side) -> {
            int g = c.wground(x, z);
            if (c.wwater(x, z) > g) {
                // a plank walk over shallow water
                int wy = c.wwater(x, z);
                c.wset(x, wy + 1, z, p.planks);
                return;
            }
            BlockState top = c.wget(x, g, z);
            if (top.is(Blocks.WATER)) return;
            // main streets are paved, the rest are beaten paths with the odd patch of gravel or stone
            long hsh = Math.floorMod(c.hash(x, 0, z, 17), 100);
            BlockState m = major ? (hsh < 22 ? p.plaza2 : p.plaza) : (hsh < 18 ? p.path2 : p.path);
            c.wset(x, g, z, m);
            for (int y = g + 1; y <= g + 3; y++) {
                BlockState s2 = c.wget(x, y, z);
                if (!s2.isAir() && Canvas.open(s2)) c.wset(x, y, z, Blocks.AIR.defaultBlockState());
            }
            // no tree trunks or crowns over a street
            for (int y = g + 1; y <= g + 14; y++) {
                BlockState s2 = c.wget(x, y, z);
                if (s2.is(net.minecraft.tags.BlockTags.LEAVES) && !s2.getOptionalValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT).orElse(false)
                        || s2.is(net.minecraft.tags.BlockTags.LOGS) && !net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s2.getBlock()).getPath().startsWith("stripped")
                        && y > g + 3 && c.wget(x, y - 1, z).isAir()) {
                    c.wset(x, y, z, Blocks.AIR.defaultBlockState());
                }
            }
            // steps on slopes: a stair up to a neighbour one block higher along the street (same chunk only)
            if (s.w > 1) {
                for (int k = -1; k <= 1; k += 2) {
                    int nx = x + sx * k, nz = z + sz * k;
                    if (nx < c.clipMinX() || nx > c.clipMaxX() || nz < c.clipMinZ() || nz > c.clipMaxZ()) continue;
                    if (c.wground(nx, nz) != g + 1) continue;
                    Direction up = Direction.getNearest(sx * k, 0, sz * k);
                    if (c.wget(x, g + 1, z).isAir()) c.wset(x, g + 1, z, B.stair(major ? p.trim : p.stairs, up, false));
                    break;
                }
            }
        });
    }

    // ------------------------------------------------------------------ squares

    public static void plaza(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d;
        if (!c.recording()) c.clearPlants(-1, 1, -1, w, 6, d);
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean ring = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                c.set(x, 0, z, ring || (x + z) % 4 == 0 ? p.plaza2 : p.plaza);
                c.foundation(x, -1, z, p.foundation, 6);
            }
        }
        int cx = (w - 1) / 2, cz = (d - 1) / 2;
        switch (s.variant) {
            case 1 -> statue(c, p, cx, cz);
            case 2 -> {
                // a small sacred tree
                BlockState log = B.s(Blocks.OAK_LOG);
                for (int y = 1; y <= 5; y++) c.set(cx, y, cz, log);
                BlockState leaves = B.s(Blocks.FLOWERING_AZALEA_LEAVES).setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 4; dy <= 7; dy++) {
                    if (dx * dx + dz * dz + (dy - 5) * (dy - 5) <= 6) c.setIfOpen(cx + dx, dy, cz + dz, leaves);
                }
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (dx != 0 || dz != 0) c.set(cx + dx, 1, cz + dz, B.slab(p.trimSlab, false));
            }
            case 3 -> Props.brazier(c, p, cx, 0, cz);
            default -> well(c, p, cx - 1, cz - 1);
        }
        // the town bell: the meeting point townsfolk gather at
        c.set(0, 1, 0, p.foundation);
        c.set(0, 2, 0, B.s(Blocks.BELL).setValue(BellBlock.ATTACHMENT, BellAttachType.FLOOR).setValue(BellBlock.FACING, c.dir(Direction.SOUTH)));
        c.anchor(AnchorType.MEETING, cx, 1, cz - 2);
        // benches and lamps
        if (w >= 9) {
            for (int x = 2; x < w - 2; x += 4) {
                c.set(x, 1, d - 1, B.stair(p.stairs, Direction.SOUTH, false));
                c.set(x + 1, 1, d - 1, B.stair(p.stairs, Direction.SOUTH, false));
            }
            lamp(c, p, w - 1, 0, 0);
            lamp(c, p, w - 1, 0, d - 1);
            lamp(c, p, 0, 0, d - 1);
        }
        // a named ward or square (city plans): a signpost at the front corner
        if (!s.name.isEmpty() && w >= 7 && !c.recording()) {
            int px = w - 2;
            for (int y = 1; y <= 3; y++) c.set(px, y, 2, p.foundation);
            c.sign(px, 2, 1, p.sign, Direction.NORTH, Component.empty(),
                    Component.translatable("settlement.skycraft.name." + s.name));
        }
    }

    public static void well(Canvas c, Palette p, int x0, int z0) {
        for (int x = x0; x <= x0 + 2; x++) {
            for (int z = z0; z <= z0 + 2; z++) {
                boolean mid = x == x0 + 1 && z == z0 + 1;
                if (mid) {
                    for (int y = -3; y <= 0; y++) c.set(x, y, z, B.s(Blocks.WATER));
                    c.set(x, -4, z, p.foundation);
                } else {
                    c.set(x, 1, z, p.wallLow);
                    for (int y = -3; y <= 0; y++) c.set(x, y, z, p.foundation);
                }
            }
        }
        c.set(x0, 2, z0, p.fence);
        c.set(x0 + 2, 2, z0 + 2, p.fence);
        c.set(x0, 2, z0 + 2, p.fence);
        c.set(x0 + 2, 2, z0, p.fence);
        for (int x = x0; x <= x0 + 2; x++) for (int z = z0; z <= z0 + 2; z++) c.set(x, 3, z, B.slab(p.roofSlab, false));
    }

    public static void statue(Canvas c, Palette p, int cx, int cz) {
        BlockState stone = B.s(Blocks.STONE_BRICKS);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.set(cx + dx, 1, cz + dz, B.s(Blocks.CHISELED_STONE_BRICKS));
        c.set(cx, 2, cz, stone);
        c.set(cx, 3, cz, B.s(Blocks.POLISHED_ANDESITE));
        c.set(cx, 4, cz, B.s(Blocks.POLISHED_ANDESITE));
        c.set(cx - 1, 4, cz, B.stair(B.s(Blocks.POLISHED_ANDESITE_STAIRS), Direction.EAST, true));
        c.set(cx + 1, 4, cz, B.stair(B.s(Blocks.POLISHED_ANDESITE_STAIRS), Direction.WEST, true));
        c.set(cx, 5, cz, B.s(Blocks.ANDESITE_WALL));
        c.set(cx, 6, cz, B.s(Blocks.STONE));
        c.set(cx + 1, 5, cz, B.s(Blocks.IRON_BARS));
        c.set(cx + 1, 6, cz, B.s(Blocks.IRON_BARS));
        c.set(cx + 1, 7, cz, B.s(Blocks.LIGHTNING_ROD));
    }

    public static void lamp(Canvas c, Palette p, int x, int y, int z) {
        c.set(x, y + 1, z, p.fence);
        c.set(x, y + 2, z, p.fence);
        c.set(x, y + 3, z, p.light.hasProperty(BlockStateProperties.HANGING) ? p.light : B.s(Blocks.LANTERN));
    }

    // ------------------------------------------------------------------ market

    public static void stall(Canvas c, Palette p, Spec s) {
        if (!c.recording()) c.clearPlants(-1, 1, -1, 3, 5, 3);
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) {
            c.set(x, 0, z, p.plaza);
            c.foundation(x, -1, z, p.foundation, 4);
        }
        int[][] posts = {{0, 0}, {2, 0}, {0, 2}, {2, 2}};
        for (int[] q : posts) for (int y = 1; y <= 3; y++) c.set(q[0], y, q[1], p.fence);
        BlockState[] cloth = {p.accent, B.s(Blocks.RED_WOOL), B.s(Blocks.YELLOW_WOOL), B.s(Blocks.GREEN_WOOL), B.s(Blocks.WHITE_WOOL)};
        BlockState a = cloth[c.roll(0, 0, 0, 5, cloth.length)];
        for (int x = -1; x <= 3; x++) for (int z = 0; z < 3; z++) c.set(x, 4, z, (x + z) % 2 == 0 ? a : B.s(Blocks.WHITE_WOOL));
        // counter on the front with goods on it
        Props.barrel(c, 0, 1, 0, "store");
        c.set(1, 1, 0, p.planks);
        c.set(2, 1, 0, B.s(Blocks.HAY_BLOCK));
        BlockState[] goods = {Props.cheese(), B.s(Blocks.MELON), B.s(Blocks.PUMPKIN), B.s(Blocks.FLOWER_POT), B.s(Blocks.DECORATED_POT)};
        c.set(1, 2, 0, goods[c.roll(1, 2, 0, 9, goods.length)]);
        c.anchor(AnchorType.MERCHANT, 1, 1, 1);
    }

    // ------------------------------------------------------------------ farming

    public static void field(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d;
        if (!c.recording()) c.clearPlants(-1, 1, -1, w, 6, d);
        Block crop = crop(s.variant);
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean border = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                c.foundation(x, -1, z, B.s(Blocks.DIRT), 6);
                if (border) {
                    c.set(x, 0, z, B.s(Blocks.DIRT_PATH));
                    boolean gate = z == 0 && x == (w - 1) / 2;
                    c.set(x, 1, z, gate ? B.face(p.gate, Direction.NORTH) : p.fence);
                    continue;
                }
                boolean water = (x - 1) % 5 == 2;
                if (water) {
                    c.set(x, 0, z, B.s(Blocks.WATER));
                    c.set(x, -1, z, B.s(Blocks.DIRT));
                    continue;
                }
                c.set(x, 0, z, B.s(Blocks.FARMLAND).setValue(FarmBlock.MOISTURE, 7));
                BlockState plant = crop.defaultBlockState();
                if (crop instanceof CropBlock cb) {
                    int age = c.chance(x, 0, z, 23, 4) ? cb.getMaxAge() - 1 - c.roll(x, 0, z, 29, 3) : cb.getMaxAge();
                    plant = cb.getStateForAge(Math.max(0, age));
                }
                c.set(x, 1, z, plant);
            }
        }
        // scarecrow
        c.set(1, 1, d - 2, p.fence);
        c.set(1, 2, d - 2, B.s(Blocks.HAY_BLOCK));
        c.set(1, 3, d - 2, B.face(B.s(Blocks.CARVED_PUMPKIN), c.dir(Direction.NORTH)));
        c.anchor(AnchorType.FARMER, (w - 1) / 2, 1, 1);
    }

    static Block crop(int v) {
        return switch (Math.floorMod(v, 8)) {
            case 1 -> Blocks.CARROTS;
            case 2 -> Blocks.POTATOES;
            case 3 -> Blocks.BEETROOTS;
            case 4 -> B.id("skycraft:cabbage_crop", Blocks.WHEAT).getBlock();
            case 5 -> B.id("skycraft:leek_crop", Blocks.CARROTS).getBlock();
            case 6 -> B.id("skycraft:garlic_crop", Blocks.POTATOES).getBlock();
            case 7 -> B.id("skycraft:tomato_crop", Blocks.BEETROOTS).getBlock();
            default -> Blocks.WHEAT;
        };
    }

    public static void garden(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d;
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            boolean border = x == 0 || z == 0 || x == w - 1 || z == d - 1;
            c.foundation(x, -1, z, B.s(Blocks.DIRT), 4);
            if (border) {
                c.set(x, 0, z, B.s(Blocks.GRASS_BLOCK));
                c.set(x, 1, z, z == 0 && x == (w - 1) / 2 ? B.face(p.gate, Direction.NORTH) : p.fence);
            } else {
                c.set(x, 0, z, B.s(Blocks.FARMLAND).setValue(FarmBlock.MOISTURE, 7));
                Block crop = crop(s.variant + x);
                BlockState st = crop instanceof CropBlock cb ? cb.getStateForAge(cb.getMaxAge()) : crop.defaultBlockState();
                c.set(x, 1, z, st);
            }
        }
        c.set((w - 1) / 2, -1, (d - 1) / 2, B.s(Blocks.WATER));
    }

    public static void lumber(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d;
        if (!c.recording()) c.clearPlants(-1, 1, -1, w, 5, d);
        BlockState log = B.axis(B.s(Blocks.SPRUCE_LOG), Direction.Axis.X);
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            c.set(x, 0, z, p.path2);
            c.foundation(x, -1, z, p.foundation, 4);
        }
        for (int x = 0; x < w - 2; x++) {
            c.set(x, 1, 0, c.dir(Direction.EAST).getAxis() == Direction.Axis.X ? log : B.axis(log, Direction.Axis.Z));
            c.set(x, 1, 1, c.dir(Direction.EAST).getAxis() == Direction.Axis.X ? log : B.axis(log, Direction.Axis.Z));
            if (x > 0 && x < w - 3) c.set(x, 2, 0, c.dir(Direction.EAST).getAxis() == Direction.Axis.X ? log : B.axis(log, Direction.Axis.Z));
        }
        c.set(w - 1, 1, d - 1, B.axis(B.s(Blocks.STRIPPED_SPRUCE_LOG), Direction.Axis.Y));
        c.set(w - 2, 1, d - 2, B.s(Blocks.STONECUTTER));
        c.anchor(AnchorType.LUMBERJACK, w - 2, 1, d - 1);
    }

    // ------------------------------------------------------------------ defences

    /** A city wall segment; {@code facing} points outwards. */
    public static void wall(Canvas c, Palette p, Spec s) {
        double half = s.w / 2.0;
        int top = s.floor + s.h;
        boolean palisade = s.has(Spec.PALISADE);
        Direction out = s.facing;
        double segLen = Math.hypot(s.x2 - s.x, s.z2 - s.z);
        line(c, s, half, (x, z, t, side) -> {
            int g = c.wground(x, z);
            double outward = (x + 0.5 - (s.x + s.x2) / 2.0) * out.getStepX() + (z + 0.5 - (s.z + s.z2) / 2.0) * out.getStepZ();
            boolean outer = outward > half - 1.0;
            boolean inner = outward < -half + 1.0;
            if (palisade) {
                BlockState log = B.axis(p.cityWall, Direction.Axis.Y);
                if (!outer && s.w > 1) {
                    // walkway behind the palisade
                    if (inner) for (int y = g + 1; y < top - 2; y++) c.wset(x, y, z, B.axis(p.post, Direction.Axis.Y));
                    c.wset(x, top - 2, z, B.slab(p.slab, true));
                    return;
                }
                for (int y = g - 2; y <= top; y++) c.wset(x, y, z, log);
                c.wset(x, top + 1, z, (x + z) % 2 == 0 ? p.fence : B.s(Blocks.AIR));
                return;
            }
            // over a deep drop the wall stands on piers instead of a solid curtain down to the ground
            int bottom = Math.min(g, top) - 2;
            boolean span = top - g > s.h + 10;
            boolean pier = Math.floorMod(Math.round((x + z) / 2.0), 4) == 0;
            for (int y = bottom; y <= top; y++) {
                if (span && !pier && y < top - s.h - 3) continue;
                c.wset(x, y, z, c.hash(x, y, z, 13) % 6 == 0 ? p.cityWall2 : p.cityWall);
            }
            if (span && !pier) c.wset(x, top - s.h - 3, z, B.stair(p.cityWallStairs, Direction.NORTH, true));
            for (int y = top + 1; y <= g; y++) c.wset(x, y, z, Blocks.AIR.defaultBlockState());
            if (outer) {
                if ((x + z) % 2 == 0) {
                    c.wset(x, top + 1, z, p.cityWall);
                    c.wset(x, top + 2, z, B.slab(p.cityWallSlab, false));
                } else {
                    c.wset(x, top + 1, z, B.slab(p.cityWallSlab, false));
                }
            } else if (inner) {
                c.wset(x, top + 1, z, p.cityWallWall);
                if (c.hash(x, 0, z, 51) % 9 == 0) c.wset(x, top + 2, z, B.s(Blocks.LANTERN));
            } else {
                c.wset(x, top, z, s.has(Spec.RUINED) && c.hash(x, 0, z, 3) % 3 == 0 ? Blocks.AIR.defaultBlockState() : p.cityWall);
            }
        });
        // access: a ladder up the inner face, mid-segment (a wall part is about 22 blocks), so soldiers can climb to
        // the wall-walk; the parapet is open there (stone) or the walkway reaches it (palisade)
        if (segLen < 8 || s.w < 3 || s.has(Spec.RUINED)) return;
        Direction in = out.getOpposite();
        BlockState ladder = B.face(B.s(Blocks.LADDER), in);
        line(c, s, half, (x, z, t, side) -> {
            double outward = (x + 0.5 - (s.x + s.x2) / 2.0) * out.getStepX() + (z + 0.5 - (s.z + s.z2) / 2.0) * out.getStepZ();
            if (outward >= -half + 1.0 || Math.abs(t * segLen - segLen / 2) >= 0.55) return;
            int g = c.wground(x, z);
            if (top - g < 3) return;
            int ladderTop = palisade ? top - 2 : top + 1;
            for (int y = g + 1; y <= ladderTop; y++) c.wset(x, y, z, ladder);
            int bx = x + out.getStepX(), bz = z + out.getStepZ();
            for (int y = g + 1; y < ladderTop - (palisade ? 0 : 1); y++) {
                c.wset(bx, y, bz, palisade ? B.axis(p.post, Direction.Axis.Y) : p.cityWall);
            }
            if (!palisade) c.wset(x, top + 2, z, Blocks.AIR.defaultBlockState());
        });
    }

    /** A gatehouse: twin towers and an arch through the wall; {@code facing} points out of town. */
    public static void gate(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d, h = s.h;
        int open = Math.max(3, w - 6);
        int ox0 = (w - open) / 2, ox1 = ox0 + open - 1;
        int archH = Math.min(h - 4, 6);
        if (!c.recording()) c.clear(-1, 1, -1, w, h + 3, d);
        boolean palisade = s.has(Spec.PALISADE);
        BlockState wallB = palisade ? B.axis(p.cityWall, Direction.Axis.Y) : p.cityWall;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                c.set(x, 0, z, x >= ox0 && x <= ox1 ? p.plaza : p.cityWall);
                c.foundation(x, -1, z, p.foundation, SettlementPieceDepth.DEPTH);
                for (int y = 1; y <= h; y++) {
                    boolean passage = x >= ox0 && x <= ox1 && y <= archH;
                    if (passage) {
                        c.air(x, y, z);
                        continue;
                    }
                    boolean shell = x == 0 || x == w - 1 || z == 0 || z == d - 1 || x == ox0 - 1 || x == ox1 + 1 || y == archH + 1 || y == h;
                    if (shell) c.set(x, y, z, c.chance(x, y, z, 13, 6) ? p.cityWall2 : wallB);
                    else c.air(x, y, z);
                }
            }
        }
        // arch voussoirs and a raised portcullis
        if (!palisade) {
            c.set(ox0, archH, 0, B.stair(p.cityWallStairs, Direction.EAST, true));
            c.set(ox1, archH, 0, B.stair(p.cityWallStairs, Direction.WEST, true));
            c.set(ox0, archH, d - 1, B.stair(p.cityWallStairs, Direction.EAST, true));
            c.set(ox1, archH, d - 1, B.stair(p.cityWallStairs, Direction.WEST, true));
        }
        for (int x = ox0; x <= ox1; x++) c.set(x, archH, d / 2, B.s(Blocks.IRON_BARS));
        // merlons
        for (int x = -1; x <= w; x++) {
            for (int z = -1; z <= d; z++) {
                boolean edge = x == -1 || z == -1 || x == w || z == d;
                if (!edge) continue;
                if (palisade) {
                    if ((x + z) % 2 == 0) c.set(x, h + 1, z, p.fence);
                } else {
                    c.set(x, h, z, B.stair(p.cityWallStairs, x == -1 ? Direction.EAST : x == w ? Direction.WEST : z == -1 ? Direction.SOUTH : Direction.NORTH, true));
                    if ((x + z) % 2 == 0) c.set(x, h + 1, z, p.cityWall);
                }
            }
        }
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) c.set(x, h, z, p.cityWall);
        // banners and torches outside
        Props.tapestry(c, p, ox0 - 2, archH, -1);
        Props.tapestry(c, p, ox1 + 2, archH, -1);
        c.set(ox0 - 1, 3, -1, B.face(B.s(Blocks.WALL_TORCH), c.dir(Direction.NORTH)));
        c.set(ox1 + 1, 3, -1, B.face(B.s(Blocks.WALL_TORCH), c.dir(Direction.NORTH)));
        // ladder up inside the left tower
        for (int y = 1; y < h; y++) c.set(1, y, d - 2, B.face(B.s(Blocks.LADDER), Direction.NORTH));
        c.air(1, h, d - 2);
        c.set(1, h, d - 2, B.face(B.s(Blocks.LADDER), Direction.NORTH));
        for (int y = 1; y < h; y++) c.air(1, y, d - 1 - 2);
        c.anchor(AnchorType.GUARD, ox0 - 1, 1, -2);
        c.anchor(AnchorType.GUARD, ox1 + 1, 1, -2);
        c.anchor(AnchorType.GUARD, (ox0 + ox1) / 2, h + 1, d / 2);
        c.anchor(AnchorType.GATE, (ox0 + ox1) / 2, 1, d / 2);
    }

    // ------------------------------------------------------------------ water and earthworks

    /** A pier running out from the shore: local z points over the water. */
    public static void dock(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d;
        BlockState post = B.axis(p.post, Direction.Axis.Y);
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                c.set(x, 0, z, (x == 0 || x == w - 1) ? B.axis(p.beam, Direction.Axis.Z) : p.planks);
                for (int y = 1; y <= 3; y++) {
                    BlockState here = c.get(x, y, z);
                    if (!here.isAir() && Canvas.open(here)) c.air(x, y, z);
                }
                if ((x == 0 || x == w - 1) && z % 3 == 0) {
                    c.foundation(x, -1, z, post, SettlementPieceDepth.DEPTH);
                    c.set(x, 1, z, p.fence);
                }
            }
        }
        Works.lamp(c, p, 0, 0, d - 1);
        Props.barrel(c, w - 2, 1, d - 2, "food");
        c.set(w - 2, 1, 1, B.s(Blocks.HAY_BLOCK));
    }

    /** A raised terrace: earth and stone up to the floor, paved on top, with a parapet. Open on the front (stairs). */
    public static void terrace(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                BlockState top = (s.variant & 2) != 0
                        ? (c.chance(x, 0, z, 7, 9) ? B.s(Blocks.COARSE_DIRT) : B.s(Blocks.GRASS_BLOCK))
                        : (c.chance(x, 0, z, 7, 4) ? p.plaza2 : p.plaza);
                c.set(x, 0, z, edge && (s.variant & 2) == 0 ? p.cityWall : top);
                c.foundation(x, -1, z, edge ? p.cityWall : p.foundation, SettlementPieceDepth.DEPTH);
                if (!c.recording()) c.clearPlants(x, 1, z, x, 4, z);
                if (edge && (s.variant & 1) == 0 && !(z == 0 && Math.abs(x - (w - 1) / 2) <= 1)) c.set(x, 1, z, p.cityWallWall);
            }
        }
        // stairs down from the front edge
        if (!c.recording()) {
            int mx = (w - 1) / 2;
            for (int k = 1; k <= 14; k++) {
                int y = -k + 1, z = -k;
                boolean done = true;
                for (int x = mx - 1; x <= mx + 1; x++) {
                    if (Canvas.solidGround(c.get(x, y, z))) continue;
                    done = false;
                    c.set(x, y, z, B.stair(p.cityWallStairs, Direction.SOUTH, false));
                    c.foundation(x, y - 1, z, p.cityWall, 16);
                }
                if (done) break;
            }
        }
    }

    /** A bridge from (x, z, floor) to (x2, z2, floor2). Variant 1: stone arches; else timber. */
    public static void bridge(Canvas c, Palette p, Spec s) {
        double half = s.w / 2.0;
        boolean stone = s.variant == 1;
        int f2 = s.floor2 == Spec.UNSET ? s.floor : s.floor2;
        int len = (int) Math.round(Math.hypot(s.x2 - s.x, s.z2 - s.z));
        line(c, s, half, (x, z, t, side) -> {
            int y = (int) Math.round(s.floor + (f2 - s.floor) * t);
            boolean rail = Math.abs(side) > half - 0.9;
            int ground = c.wground(x, z);
            if (c.wwater(x, z) == Integer.MIN_VALUE && y - ground <= 2 && y - ground >= -1) {
                // over dry land (a gate's approach): the road's own surface mix on a graded bed, not a stone deck
                int yy = Math.max(ground, y - 1);
                for (int fy = ground + 1; fy < yy; fy++) c.wset(x, fy, z, p.foundation);
                c.wset(x, yy, z, c.hash(x, 0, z, 17) % 100 < 28 ? p.path2 : p.path);
                for (int cy = yy + 1; cy <= yy + 3; cy++) {
                    if (!c.wget(x, cy, z).isAir()) c.wset(x, cy, z, Blocks.AIR.defaultBlockState());
                }
                return;
            }
            c.wset(x, y, z, stone ? (rail ? p.cityWall : p.plaza) : (rail ? B.axis(p.beam, Direction.Axis.Y) : p.planks));
            for (int yy = y + 1; yy <= y + 3; yy++) c.wset(x, yy, z, Blocks.AIR.defaultBlockState());
            if (rail) c.wset(x, y + 1, z, stone ? p.cityWallWall : p.fence);
            int along = (int) Math.round(t * len);
            boolean pier = along % 8 == 0 && along > 0 && along < len;
            if (pier || stone && rail) {
                int depth = stone ? (pier ? 40 : 1) : 40;
                BlockState col = stone ? p.cityWall : B.axis(p.post, Direction.Axis.Y);
                if (pier || depth > 1) {
                    for (int yy = y - 1; yy > y - depth; yy--) {
                        BlockState here = c.wget(x, yy, z);
                        if (Canvas.solidGround(here)) break;
                        c.wset(x, yy, z, col);
                    }
                } else {
                    c.wset(x, y - 1, z, B.stair(p.cityWallStairs, Direction.NORTH, true));
                }
            }
            if (rail && along % 10 == 5) c.wset(x, y + 2, z, B.s(Blocks.LANTERN));
        });
    }

    /**
     * A canal: a stone-walled water channel with its surface three blocks under the floor and plank walkways along
     * the top of both walls.
     */
    public static void canal(Canvas c, Palette p, Spec s) {
        double half = s.w / 2.0;
        int f = s.floor;
        line(c, s, half, (x, z, t, side) -> {
            boolean wallCol = Math.abs(side) > half - 1.0;
            if (wallCol) {
                for (int y = f - 5; y < f; y++) c.wset(x, y, z, p.cityWall);
                c.wset(x, f, z, p.planks);
                for (int y = f + 1; y <= f + 4; y++) {
                    BlockState here = c.wget(x, y, z);
                    if (!here.isAir() && (Canvas.open(here) || Canvas.solidGround(here))) c.wset(x, y, z, Blocks.AIR.defaultBlockState());
                }
                if (c.hash(x, 0, z, 41) % 7 == 0) c.wset(x, f + 1, z, p.fence);
                return;
            }
            c.wset(x, f - 5, z, p.cityWall);
            c.wset(x, f - 4, z, B.s(Blocks.WATER));
            c.wset(x, f - 3, z, B.s(Blocks.WATER));
            for (int y = f - 2; y <= f + 4; y++) c.wset(x, y, z, Blocks.AIR.defaultBlockState());
        });
    }

    // ------------------------------------------------------------------ mines and graves

    /** A mine adit: timbered portal, rails down into a gallery with ore in its walls. Local z runs into the hill. */
    public static void mine(Canvas c, Palette p, Spec s) {
        int cx = (s.w - 1) / 2;
        BlockState post = B.axis(B.s(Blocks.SPRUCE_LOG), Direction.Axis.Y);
        BlockState beam = B.axis(B.s(Blocks.STRIPPED_SPRUCE_LOG), Direction.Axis.X);
        int len = Math.max(6, s.d - 6);
        BlockState[] ores = {
                Props.ore("corundum", Blocks.IRON_ORE), Props.ore("orichalcum", Blocks.COPPER_ORE), Props.ore("silver", Blocks.IRON_ORE),
                Props.ore("moonstone", Blocks.IRON_ORE), Props.ore("quicksilver", Blocks.REDSTONE_ORE), B.s(Blocks.IRON_ORE), B.s(Blocks.COAL_ORE)};
        BlockState ore = ores[Math.floorMod(s.variant, ores.length)];
        for (int k = 0; k < len; k++) {
            int y = -k / 2;
            int z = k;
            for (int x = cx - 2; x <= cx + 2; x++) {
                for (int yy = y; yy <= y + 4; yy++) {
                    boolean inside = Math.abs(x - cx) <= 1 && yy >= y + 1 && yy <= y + 3;
                    if (inside) c.air(x, yy, z);
                    else if (yy == y && Math.abs(x - cx) <= 1) c.set(x, yy, z, B.s(Blocks.GRAVEL));
                    else if (c.get(x, yy, z).isAir() || !Canvas.solidGround(c.get(x, yy, z))) c.set(x, yy, z, c.chance(x, yy, z, 5, 9) ? ore : B.s(Blocks.STONE));
                    else if (c.chance(x, yy, z, 7, 11)) c.set(x, yy, z, ore);
                }
            }
            c.set(cx, y + 1, z, B.s(Blocks.RAIL).setValue(BlockStateProperties.RAIL_SHAPE,
                    c.dir(Direction.NORTH).getAxis() == Direction.Axis.Z ? RailShape.NORTH_SOUTH : RailShape.EAST_WEST));
            if (k % 3 == 0) {
                for (int yy = y + 1; yy <= y + 3; yy++) {
                    c.set(cx - 2, yy, z, post);
                    c.set(cx + 2, yy, z, post);
                }
                for (int x = cx - 2; x <= cx + 2; x++) c.set(x, y + 4, z, c.dir(Direction.EAST).getAxis() == Direction.Axis.X ? beam : B.axis(beam, Direction.Axis.Z));
                if (k % 6 == 3) c.set(cx - 1, y + 3, z, B.hanging(B.s(Blocks.LANTERN)));
            }
        }
        // portal facade
        for (int y = 1; y <= 4; y++) {
            c.set(cx - 2, y, 0, post);
            c.set(cx + 2, y, 0, post);
        }
        for (int x = cx - 3; x <= cx + 3; x++) c.set(x, 5, 0, c.dir(Direction.EAST).getAxis() == Direction.Axis.X ? beam : B.axis(beam, Direction.Axis.Z));
        // the yard: smelter, ore pile, cart track
        for (int x = 0; x < s.w; x++) for (int z = -4; z < 0; z++) {
            c.set(x, 0, z, p.path2);
            c.foundation(x, -1, z, p.foundation, 4);
            for (int y = 1; y <= 4; y++) c.air(x, y, z);
        }
        c.set(0, 1, -2, B.face(Props.smelter(), c.dir(Direction.EAST)));
        c.set(s.w - 1, 1, -2, ore);
        c.set(s.w - 1, 1, -3, B.s(Blocks.GRAVEL));
        Props.barrel(c, 0, 1, -4, "mine");
        c.anchor(AnchorType.MINER, cx, 1, -2);
    }

    public static void graveyard(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d;
        if (!c.recording()) c.clearPlants(-1, 1, -1, w, 6, d);
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean border = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                c.foundation(x, -1, z, B.s(Blocks.DIRT), 6);
                c.set(x, 0, z, border ? p.path2 : (c.chance(x, 0, z, 3, 3) ? B.s(Blocks.COARSE_DIRT) : B.s(Blocks.GRASS_BLOCK)));
                if (border) {
                    boolean gate = z == 0 && Math.abs(x - (w - 1) / 2) <= 0;
                    c.set(x, 1, z, gate ? B.s(Blocks.AIR) : B.s(Blocks.IRON_BARS));
                    if ((x == 0 || x == w - 1) && (z == 0 || z == d - 1)) c.set(x, 1, z, p.cityWall);
                }
            }
        }
        for (int x = 2; x < w - 2; x += 2) {
            for (int z = 2; z < d - 2; z += 3) {
                if (x == (w - 1) / 2) continue;
                c.set(x, 1, z + 1, c.chance(x, 1, z, 9, 3) ? B.s(Blocks.MOSSY_COBBLESTONE_WALL) : B.s(Blocks.STONE_BRICK_WALL));
                c.set(x, 0, z, B.s(Blocks.ROOTED_DIRT));
                if (c.chance(x, 2, z, 11, 4)) Props.candles(c, x, 2, z + 1, 1 + c.roll(x, 2, z, 12, 3));
            }
        }
    }

    // ------------------------------------------------------------------ stables & plots

    public static void stables(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d, wallH = 4;
        if (!c.recording()) {
            c.clear(0, 1, 0, w - 1, wallH + w / 2 + 2, d - 1);
            c.clearPlants(-2, 1, -2, w + 1, wallH + w / 2 + 3, d + 1);
        }
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                c.set(x, 0, z, x < w - 4 ? B.s(Blocks.COARSE_DIRT) : p.floor);
                c.foundation(x, -1, z, p.foundation, SettlementPieceDepth.DEPTH);
            }
        }
        BlockState post = B.axis(p.post, Direction.Axis.Y);
        for (int z = 0; z < d; z += 3) {
            for (int y = 1; y <= wallH; y++) {
                c.set(0, y, z, post);
                c.set(w - 5, y, z, post);
            }
        }
        // stalls: fences between, hay and water troughs at the back
        for (int z = 0; z < d; z++) {
            if (z % 3 == 0) for (int x = 1; x < w - 5; x++) c.set(x, 1, z, p.fence);
            else c.set(1, 1, z, z % 3 == 1 ? B.s(Blocks.HAY_BLOCK) : B.s(Blocks.WATER_CAULDRON).setValue(LayeredCauldronBlock.LEVEL, 3));
        }
        // tack room on the right: walls, door, bed and chest
        for (int x = w - 4; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean edge = x == w - 4 || x == w - 1 || z == 0 || z == d - 1;
                for (int y = 1; y <= wallH; y++) {
                    if (edge) c.set(x, y, z, y == 1 ? p.wallLow : p.wall);
                    else c.air(x, y, z);
                }
            }
        }
        c.door(w - 4, 1, d / 2, p.door, Direction.WEST);
        c.bed(w - 2, 1, d - 2, p.bed, Direction.SOUTH);
        c.anchor(AnchorType.BED, w - 2, 1, d - 2);
        Props.chest(c, w - 3, 1, d - 2, Direction.NORTH, "farm");
        c.set(w - 2, 1, 1, Props.tanningRack());
        Shells.gableRoof(c, p, -1, -1, w, d, wallH + 1, false, p.thatch, false);
        c.anchor(AnchorType.STABLES, w - 5, 1, -1);
        c.anchor(AnchorType.CARRIAGE, (w - 1) / 2, 1, -3);
        Works.lamp(c, p, w, 0, -1);
    }

    /** A cleared homestead plot with corner stakes and a notice sign; the buyer founds the homestead with a drafting table. */
    public static void homesteadPlot(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d;
        if (!c.recording()) c.clearPlants(-1, 1, -1, w, 12, d);
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            c.foundation(x, -1, z, B.s(Blocks.DIRT), 8);
            if (c.recording()) continue;
            BlockState top = c.get(x, 0, z);
            if (!Canvas.solidGround(top)) c.set(x, 0, z, B.s(Blocks.GRASS_BLOCK));
        }
        int[][] corners = {{0, 0}, {w - 1, 0}, {0, d - 1}, {w - 1, d - 1}};
        for (int[] q : corners) {
            c.set(q[0], 1, q[1], p.fence);
            c.set(q[0], 2, q[1], B.s(Blocks.LANTERN));
        }
        int cx = (w - 1) / 2;
        c.set(cx, 1, 0, B.axis(p.post, Direction.Axis.Y));
        c.sign(cx, 1, -1, p.sign, Direction.NORTH,
                net.minecraft.network.chat.Component.translatable("place.skycraft." + s.name),
                net.minecraft.network.chat.Component.translatable("settlement.skycraft.homestead.sign1"),
                net.minecraft.network.chat.Component.translatable("settlement.skycraft.homestead.sign2"));
        // a pile of building stone and timber
        for (int x = 2; x < 5; x++) c.set(x, 1, 2, B.axis(B.s(Blocks.SPRUCE_LOG), c.dir(Direction.EAST).getAxis()));
        c.set(2, 2, 2, B.axis(B.s(Blocks.SPRUCE_LOG), c.dir(Direction.EAST).getAxis()));
        c.set(w - 3, 1, 2, B.s(Blocks.COBBLESTONE));
        c.set(w - 4, 1, 2, B.s(Blocks.COBBLESTONE));
        c.set(w - 3, 2, 2, B.slab(B.s(Blocks.COBBLESTONE_SLAB), false));
        c.anchor(AnchorType.HOMESTEAD, cx, 1, (d - 1) / 2);
    }

    static final class SettlementPieceDepth {
        static final int DEPTH = com.skycraft.tamriel.settlement.SettlementPiece.FOUNDATION;
    }
}
