package com.skycraft.tamriel.settlement.grammar;

import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.build.B;
import com.skycraft.tamriel.settlement.build.Canvas;
import com.skycraft.tamriel.settlement.build.Props;
import com.skycraft.tamriel.settlement.build.Works;
import com.skycraft.tamriel.settlement.kit.Palette;
import com.skycraft.tamriel.settlement.plan.Spec;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Builders of the civic features (see {@link Features}): ponds, reflecting pools, harbour basins, aqueducts,
 * waterwheels, great trees, bell towers, memorials, banner poles and deity-themed statues. All are YARD pieces (an
 * {@link Outdoors} kind); {@link Spec#name} carries the theme ({@code species:willow}, {@code divine:akatosh}...), so
 * the saved piece format does not change.
 */
final class Civic {
    private Civic() {}

    static void build(Canvas c, Palette p, Spec s, Style st, int kind) {
        switch (kind) {
            case Outdoors.POND -> pond(c, p, s);
            case Outdoors.REFLECTING_POOL -> pool(c, p, s);
            case Outdoors.MEMORIAL -> memorial(c, p, s);
            case Outdoors.BELL_TOWER -> bellTower(c, p, s);
            case Outdoors.BANNER -> banner(c, p, s);
            case Outdoors.GREAT_TREE -> greatTree(c, p, s, st);
            case Outdoors.WATERWHEEL -> waterwheel(c, p, s);
            case Outdoors.AQUEDUCT -> aqueduct(c, p, s);
            case Outdoors.BASIN -> basin(c, p, s);
            case Outdoors.STATUE_THEMED -> statue(c, p, s);
            default -> {
            }
        }
    }

    private static String theme(Spec s, String key, String dflt) {
        int i = s.name.indexOf(key + ":");
        if (i < 0) return dflt;
        int e = s.name.indexOf(';', i);
        return s.name.substring(i + key.length() + 1, e < 0 ? s.name.length() : e);
    }

    private static BlockState leaves(Block b) {
        BlockState s = B.s(b);
        return s.hasProperty(LeavesBlock.PERSISTENT) ? s.setValue(LeavesBlock.PERSISTENT, true) : s;
    }

    private static BlockState pillar(BlockState s) {
        return s.hasProperty(BlockStateProperties.AXIS) ? B.axis(s, Direction.Axis.Y) : s;
    }

    private static void base(Canvas c, Palette p, int w, int d, BlockState top) {
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            c.set(x, 0, z, top);
            c.foundation(x, -1, z, p.foundation, 8);
        }
    }

    // ------------------------------------------------------------------ water

    static void pond(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d;
        if (!c.recording()) c.clearPlants(0, 1, 0, w - 1, 4, d - 1);
        double cx = (w - 1) / 2.0, cz = (d - 1) / 2.0;
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            double dx = (x - cx) / (w / 2.0), dz = (z - cz) / (d / 2.0), r = dx * dx + dz * dz;
            int g = Outdoors.ground(c, x, z);
            if (r <= 0.62) {
                c.set(x, g, z, B.s(Blocks.WATER));
                c.set(x, g - 1, z, c.chance(x, 0, z, 61, 3) ? B.s(Blocks.CLAY) : B.s(Blocks.MUD));
                c.set(x, g + 1, z, B.s(Blocks.AIR));
                if (r > 0.3 && c.chance(x, 1, z, 63, 3)) c.set(x, g + 1, z, B.s(Blocks.LILY_PAD));
            } else if (r <= 1.0) {
                c.set(x, g, z, c.chance(x, 0, z, 65, 2) ? B.s(Blocks.GRASS_BLOCK) : B.s(Blocks.MOSS_BLOCK));
                if (c.chance(x, 2, z, 67, 3)) c.set(x, g + 1, z, B.s(c.chance(x, 5, z, 79, 3) ? Blocks.FERN : Blocks.GRASS));
                else if (c.chance(x, 3, z, 69, 6)) c.set(x, g + 1, z, B.s(Blocks.SUGAR_CANE));
                else if (c.chance(x, 4, z, 71, 5)) c.set(x, g + 1, z, B.s(Blocks.BLUE_ORCHID));
            }
        }
    }

    static void pool(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d;
        if (!c.recording()) c.clearPlants(0, 1, 0, w - 1, 6, d - 1);
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
            boolean walk = x == 1 || z == 1 || x == w - 2 || z == d - 2;
            c.foundation(x, -1, z, p.foundation, 8);
            if (edge) {
                c.set(x, 0, z, p.plaza2);
                c.set(x, 1, z, B.slab(p.trimSlab, false));
            } else if (walk && w > 5) {
                c.set(x, 0, z, p.plaza);
            } else {
                c.set(x, 0, z, B.s(Blocks.WATER));
                c.set(x, -1, z, p.foundation);
                if (c.chance(x, 0, z, 73, 5)) c.set(x, 1, z, B.s(Blocks.LILY_PAD));
            }
        }
        // lamps at the four corners, a statue-less obelisk at one end
        Works.lamp(c, p, 0, 1, 0);
        Works.lamp(c, p, w - 1, 1, 0);
        Works.lamp(c, p, 0, 1, d - 1);
        Works.lamp(c, p, w - 1, 1, d - 1);
        c.anchor(AnchorType.MEETING, w / 2, 1, -1);
    }

    static void basin(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d;
        if (!c.recording()) c.clearPlants(0, 1, 0, w - 1, 6, d - 1);
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            boolean quay = x < 2 || z < 2 || x >= w - 2 || z >= d - 2;
            c.foundation(x, -1, z, p.foundation, 10);
            if (quay) {
                c.set(x, 0, z, (x + z) % 5 == 0 ? p.plaza2 : p.plaza);
            } else {
                c.set(x, 0, z, B.s(Blocks.WATER));
                c.set(x, -1, z, p.foundation);
                c.set(x, -2, z, p.foundation);
            }
        }
        // bollards on the corners of the quay and a landing stair in the middle of the front
        int[][] posts = {{1, 1}, {w - 2, 1}, {1, d - 2}, {w - 2, d - 2}, {w / 2, 1}, {w / 2, d - 2}};
        for (int[] q : posts) c.set(q[0], 1, q[1], p.fence);
        for (int x = w / 2 - 1; x <= w / 2 + 1; x++) c.set(x, 0, 2, B.stair(p.trim, Direction.SOUTH, true));
        // a plank jetty into the water
        for (int z = 2; z < d / 2; z++) c.set(w / 2, 1, z, B.slab(p.slab, false));
        c.anchor(AnchorType.MEETING, w / 2, 1, -1);
    }

    static void aqueduct(Canvas c, Palette p, Spec s) {
        int w = Math.max(3, s.w), d = s.d, top = 7;
        if (!c.recording()) c.clearPlants(0, 1, 0, w - 1, top + 2, d - 1);
        int mid = w / 2;
        BlockState wall = p.cityWall;
        for (int z = 0; z < d; z++) {
            boolean pier = z % 4 == 0;
            for (int x = 0; x < w; x++) {
                boolean side = x == 0 || x == w - 1;
                if (pier && (side || w <= 3)) {
                    c.foundation(x, 0, z, p.foundation, 20);
                    for (int y = 0; y < top; y++) c.set(x, y, z, y < 2 ? p.wallLow : wall);
                } else if (!pier && side) {
                    // the arch between piers: stairs spring from the piers
                    int k = z % 4;
                    if (k == 1 || k == 3) c.set(x, top - 2, z, B.stair(p.cityWallStairs, k == 1 ? Direction.SOUTH : Direction.NORTH, true));
                    else if (k == 2) c.set(x, top - 2, z, wall);
                    c.set(x, top - 1, z, wall);
                }
                if (!side || w <= 3) {
                    c.set(x, top - 1, z, wall);
                }
            }
            // the channel on top
            for (int x = 0; x < w; x++) {
                boolean side = x == 0 || x == w - 1;
                if (side) c.set(x, top, z, p.cityWallWall);
                else c.set(x, top, z, B.s(Blocks.WATER));
            }
            if (z % 8 == 4) {
                c.set(0, top + 1, z, B.s(Blocks.LANTERN));
                c.set(w - 1, top + 1, z, B.s(Blocks.LANTERN));
            }
        }
        // a spout at the end
        c.set(mid, top - 1, d - 1, B.s(Blocks.WATER));
    }

    static void waterwheel(Canvas c, Palette p, Spec s) {
        int w = Math.max(7, s.w), d = Math.max(3, s.d);
        if (!c.recording()) c.clearPlants(0, 1, 0, w - 1, 10, d - 1);
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            c.foundation(x, -1, z, p.foundation, 8);
            boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
            if (edge) c.set(x, 0, z, p.plaza2);
            else c.set(x, 0, z, B.s(Blocks.WATER));
        }
        int cx = w / 2, cy = 5, cz = d / 2;
        for (int x = 0; x < w; x++) for (int y = 1; y <= 9; y++) {
            double dd = Math.hypot(x - cx, y - cy);
            if (dd >= 3.2 && dd <= 4.2) c.set(x, y, cz, p.planks);
            else if (dd < 3.2 && (x == cx || y == cy || Math.abs(Math.abs(x - cx) - Math.abs(y - cy)) < 1)) c.set(x, y, cz, p.fence);
        }
        c.set(cx, cy, cz, B.axis(p.beam, Direction.Axis.Z));
        // the mill race feeding it from the top
        for (int x = 0; x < cx; x++) {
            c.set(x, 9, cz, B.slab(p.cityWallSlab, false));
            c.set(x, 8, cz, p.cityWall);
        }
        c.set(cx - 1, 9, cz, B.s(Blocks.WATER));
        // posts and a roof over the gear house side
        for (int y = 1; y <= 4; y++) {
            c.set(0, y, 0, p.post.hasProperty(BlockStateProperties.AXIS) ? B.axis(p.post, Direction.Axis.Y) : p.post);
            c.set(w - 1, y, 0, p.post.hasProperty(BlockStateProperties.AXIS) ? B.axis(p.post, Direction.Axis.Y) : p.post);
        }
    }

    // ------------------------------------------------------------------ monuments

    static void memorial(Canvas c, Palette p, Spec s) {
        int w = Math.max(3, s.w), d = Math.max(3, s.d);
        if (!c.recording()) c.clearPlants(0, 1, 0, w - 1, 10, d - 1);
        base(c, p, w, d, p.plaza2);
        int cx = w / 2, cz = d / 2;
        BlockState stone = B.s(Blocks.POLISHED_ANDESITE);
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) c.set(x, 1, z, B.slab(p.trimSlab, false));
        for (int x = cx - 1; x <= cx + 1; x++) for (int z = cz - 1; z <= cz + 1; z++) c.set(x, 1, z, B.s(Blocks.STONE_BRICKS));
        for (int y = 2; y <= 7; y++) c.set(cx, y, cz, y < 3 ? B.s(Blocks.CHISELED_STONE_BRICKS) : stone);
        c.set(cx, 8, cz, B.s(Blocks.LANTERN));
        c.set(cx, 2, cz - 1, B.stair(B.s(Blocks.STONE_BRICK_STAIRS), Direction.NORTH, false));
        Props.candles(c, cx - 1, 2, cz - 1, 3);
        Props.candles(c, cx + 1, 2, cz - 1, 2);
        c.anchor(AnchorType.MEETING, cx, 1, -1);
    }

    static void bellTower(Canvas c, Palette p, Spec s) {
        int w = Math.max(5, s.w), d = Math.max(5, s.d);
        if (!c.recording()) c.clearPlants(0, 1, 0, w - 1, 16, d - 1);
        base(c, p, w, d, p.plaza2);
        int[][] posts = {{0, 0}, {w - 1, 0}, {0, d - 1}, {w - 1, d - 1}};
        int top = 9;
        for (int[] q : posts) {
            for (int y = 1; y <= top; y++) c.set(q[0], y, q[1], y <= 3 ? p.wallLow : pillar(p.post));
        }
        // ring beam and floor of the belfry
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
            if (edge && !(x == w / 2 && z == 0)) {
                c.set(x, 4, z, pillar(p.beam));
                if (x != 0 && x != w - 1 || z != 0 && z != d - 1) c.set(x, 5, z, p.cityWallWall);
            }
            if (!edge) c.set(x, 4, z, p.planks);
        }
        c.set(w / 2, 1, 0, B.s(Blocks.AIR));
        // belfry: bell hanging from the cap
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) c.set(x, top + 1, z, p.cityWall);
        c.set(w / 2, top, d / 2, B.s(Blocks.BELL).setValue(BellBlock.ATTACHMENT, BellAttachType.CEILING).setValue(BellBlock.FACING, c.dir(Direction.SOUTH)));
        // the pyramid roof
        int inset = 0;
        for (int y = top + 2; inset * 2 < Math.min(w, d); y++, inset++) {
            for (int x = inset; x < w - inset; x++) for (int z = inset; z < d - inset; z++) {
                boolean rim = x == inset || z == inset || x == w - 1 - inset || z == d - 1 - inset;
                if (!rim) continue;
                Direction f = z == inset ? Direction.NORTH : z == d - 1 - inset ? Direction.SOUTH : x == inset ? Direction.WEST : Direction.EAST;
                c.set(x, y, z, B.stair(p.roof, f, false));
            }
            if (Math.min(w, d) - inset * 2 <= 2) {
                c.set(w / 2, y + 1, d / 2, p.fence);
                c.set(w / 2, y + 2, d / 2, B.s(Blocks.LIGHTNING_ROD));
                break;
            }
        }
        c.anchor(AnchorType.MEETING, w / 2, 1, -2);
    }

    static void banner(Canvas c, Palette p, Spec s) {
        int h = 7;
        int g = Outdoors.ground(c, 0, 0);
        for (int y = 1; y <= h; y++) c.set(0, g + y, 0, y == 1 ? p.wallLow : p.fence);
        BlockState cloth = p.accent;
        for (int y = h - 3; y <= h - 1; y++) for (int x = 1; x <= 2; x++) c.set(x, g + y, 0, cloth);
        c.set(1, g + h, 0, p.fence);
        c.set(2, g + h, 0, p.fence);
        c.set(0, g + h + 1, 0, B.s(Blocks.GOLD_BLOCK));
    }

    // ------------------------------------------------------------------ trees

    static void greatTree(Canvas c, Palette p, Spec s, Style st) {
        String sp = theme(s, "species", st.culture.equals("bosmer") ? "graht" : "oak");
        Block logB = Blocks.OAK_LOG, leafB = Blocks.OAK_LEAVES;
        boolean willow = false;
        switch (sp) {
            case "birch" -> {
                logB = Blocks.BIRCH_LOG;
                leafB = Blocks.BIRCH_LEAVES;
            }
            case "spruce", "pine" -> {
                logB = Blocks.SPRUCE_LOG;
                leafB = Blocks.SPRUCE_LEAVES;
            }
            case "dark_oak" -> {
                logB = Blocks.DARK_OAK_LOG;
                leafB = Blocks.DARK_OAK_LEAVES;
            }
            case "acacia" -> {
                logB = Blocks.ACACIA_LOG;
                leafB = Blocks.ACACIA_LEAVES;
            }
            case "jungle" -> {
                logB = Blocks.JUNGLE_LOG;
                leafB = Blocks.JUNGLE_LEAVES;
            }
            case "cherry" -> {
                logB = Blocks.CHERRY_LOG;
                leafB = Blocks.CHERRY_LEAVES;
            }
            case "azalea" -> leafB = Blocks.FLOWERING_AZALEA_LEAVES;
            case "mangrove" -> {
                logB = Blocks.MANGROVE_LOG;
                leafB = Blocks.MANGROVE_LEAVES;
            }
            case "willow" -> {
                logB = Blocks.MANGROVE_LOG;
                leafB = Blocks.MANGROVE_LEAVES;
                willow = true;
            }
            case "graht" -> {
                logB = Blocks.JUNGLE_LOG;
                leafB = Blocks.JUNGLE_LEAVES;
            }
            default -> {
            }
        }
        BlockState log = sp.equals("graht") ? B.id("skycraft:graht_oak_log", logB) : B.s(logB);
        BlockState leaf = sp.equals("graht") ? B.id("skycraft:graht_oak_leaves", leafB) : B.s(leafB);
        if (leaf.hasProperty(LeavesBlock.PERSISTENT)) leaf = leaf.setValue(LeavesBlock.PERSISTENT, true);
        BlockState trunk = log.hasProperty(BlockStateProperties.AXIS) ? B.axis(log, Direction.Axis.Y) : log;
        int cx = s.w / 2, cz = s.d / 2;
        int H = Math.max(12, s.h - 5);
        int rad = Math.max(4, Math.min(s.w / 2, 7));
        if (!c.recording()) c.clearPlants(0, 1, 0, s.w - 1, H + 6, s.d - 1);
        int g = Outdoors.ground(c, cx, cz);
        // roots and trunk
        for (int y = 0; y <= H; y++) {
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                boolean corner = dx != 0 && dz != 0;
                if (y < 4 || !corner && y < H * 0.5) c.set(cx + dx, g + y, cz + dz, trunk);
            }
            if (y >= H * 0.5) c.set(cx, g + y, cz, trunk);
        }
        int[][] roots = {{2, 0}, {-2, 0}, {0, 2}, {0, -2}, {2, 2}, {-2, -2}, {2, -2}, {-2, 2}};
        for (int[] r : roots) {
            c.set(cx + r[0], g, cz + r[1], trunk);
            if (Math.abs(r[0]) + Math.abs(r[1]) <= 2) c.set(cx + r[0], g + 1, cz + r[1], trunk);
        }
        // limbs
        int by = (int) (H * 0.62);
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dr : dirs) {
            for (int k = 1; k <= 3; k++) {
                BlockState limb = log.hasProperty(BlockStateProperties.AXIS) ? B.axis(log, dr[0] != 0 ? Direction.Axis.X : Direction.Axis.Z) : log;
                c.set(cx + dr[0] * k, g + by + k / 2, cz + dr[1] * k, limb);
            }
        }
        // crown: a big ellipsoid and four lobes over the limbs
        double[][] blobs = {{0, H - 2, 0, rad, 4.5}, {rad * 0.55, by + 3, 0, rad * 0.55, 3.2}, {-rad * 0.55, by + 3, 0, rad * 0.55, 3.2},
                {0, by + 3, rad * 0.55, rad * 0.55, 3.2}, {0, by + 3, -rad * 0.55, rad * 0.55, 3.2}};
        for (double[] b : blobs) {
            int r = (int) Math.ceil(b[3]), ry = (int) Math.ceil(b[4]);
            for (int dx = -r; dx <= r; dx++) for (int dz = -r; dz <= r; dz++) for (int dy = -ry; dy <= ry; dy++) {
                double v = (dx * dx + dz * dz) / (b[3] * b[3]) + (dy * dy) / (b[4] * b[4]);
                if (v > 1.0) continue;
                if (v > 0.72 && c.roll(cx + dx, g + dy, cz + dz, 75, 4) == 0) continue;
                int x = cx + (int) b[0] + dx, z = cz + (int) b[2] + dz, y = g + (int) b[1] + dy;
                c.setIfOpen(x, y, z, leaf);
            }
        }
        if (willow) {
            // long hanging fronds round the rim of the crown
            for (int i = 0; i < 28; i++) {
                double a = i * Math.PI * 2 / 28;
                int x = cx + (int) Math.round(Math.sin(a) * (rad - 0.5)), z = cz + (int) Math.round(Math.cos(a) * (rad - 0.5));
                int len = 4 + c.roll(x, 0, z, 77, 6);
                for (int k = 0; k < len; k++) c.setIfOpen(x, g + H - 2 - k, z, leaf);
            }
        }
        // a ring bench round the trunk for a meeting tree
        if (s.has(Spec.SIGN) || sp.equals("graht") || s.name.contains("bench")) {
            for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != 3 || (dx + dz) % 2 != 0) continue;
                int f = Outdoors.ground(c, cx + dx, cz + dz);
                c.set(cx + dx, f + 1, cz + dz, B.stair(p.stairs, Math.abs(dx) == 3 ? (dx > 0 ? Direction.WEST : Direction.EAST) : (dz > 0 ? Direction.NORTH : Direction.SOUTH), false));
            }
        }
    }

    // ------------------------------------------------------------------ themed statues

    static void statue(Canvas c, Palette p, Spec s) {
        String t = theme(s, "divine", "");
        String kind = !t.isEmpty() ? "divine" : theme(s, "daedric", "").isEmpty() ? theme(s, "tribunal", "").isEmpty() ? s.name.contains("hist") ? "hist"
                : s.name.contains("pact") ? "pact" : s.name.contains("emperor") ? "emperor" : "plain" : "tribunal" : "daedric";
        int w = Math.max(3, s.w), d = Math.max(3, s.d);
        if (!c.recording()) c.clearPlants(0, 1, 0, w - 1, 12, d - 1);
        int cx = w / 2, cz = d / 2;
        BlockState plinth = switch (kind) {
            case "daedric" -> B.s(Blocks.POLISHED_BLACKSTONE_BRICKS);
            case "tribunal" -> B.s(Blocks.SMOOTH_SANDSTONE);
            case "hist" -> B.s(Blocks.MOSSY_STONE_BRICKS);
            case "pact" -> B.s(Blocks.PODZOL);
            default -> B.s(Blocks.STONE_BRICKS);
        };
        base(c, p, w, d, kind.equals("pact") ? B.s(Blocks.COARSE_DIRT) : p.plaza2);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.set(cx + dx, 1, cz + dz, plinth);
        switch (kind) {
            case "divine", "emperor" -> {
                BlockState white = B.s(kind.equals("emperor") ? Blocks.POLISHED_DIORITE : Blocks.QUARTZ_BLOCK);
                c.set(cx, 2, cz, plinth);
                for (int y = 3; y <= 5; y++) c.set(cx, y, cz, white);
                c.set(cx - 1, 4, cz, B.stair(B.s(Blocks.QUARTZ_STAIRS), Direction.EAST, true));
                c.set(cx + 1, 4, cz, B.stair(B.s(Blocks.QUARTZ_STAIRS), Direction.WEST, true));
                c.set(cx, 6, cz, B.s(Blocks.CHISELED_QUARTZ_BLOCK));
                c.set(cx, 7, cz, B.s(kind.equals("emperor") ? Blocks.GOLD_BLOCK : Blocks.SEA_LANTERN));
                if (!t.isEmpty()) c.set(cx, 2, cz - 1, Props.shrine(t));
                Props.candles(c, cx + 1, 2, cz - 1, 4);
            }
            case "daedric" -> {
                c.set(cx, 2, cz, B.s(Blocks.CRYING_OBSIDIAN));
                for (int y = 3; y <= 6; y++) c.set(cx, y, cz, B.s(Blocks.POLISHED_BLACKSTONE));
                c.set(cx - 1, 5, cz, B.s(Blocks.POLISHED_BLACKSTONE_WALL));
                c.set(cx + 1, 5, cz, B.s(Blocks.POLISHED_BLACKSTONE_WALL));
                c.set(cx, 7, cz, B.s(Blocks.SOUL_LANTERN));
                c.set(cx - 1, 1, cz - 2, B.s(Blocks.SOUL_LANTERN));
                c.set(cx + 1, 1, cz - 2, B.s(Blocks.SOUL_LANTERN));
                c.set(cx, 2, cz - 1, Props.shrine(t.isEmpty() ? theme(s, "daedric", "azura") : t));
            }
            case "tribunal" -> {
                for (int y = 2; y <= 4; y++) c.set(cx, y, cz, B.s(Blocks.CUT_SANDSTONE));
                c.set(cx, 5, cz, B.s(Blocks.GOLD_BLOCK));
                c.set(cx, 6, cz, B.s(Blocks.CHISELED_SANDSTONE));
                c.set(cx - 1, 4, cz, B.s(Blocks.SANDSTONE_WALL));
                c.set(cx + 1, 4, cz, B.s(Blocks.SANDSTONE_WALL));
                c.set(cx, 7, cz, B.s(Blocks.AMETHYST_CLUSTER));
                c.set(cx, 2, cz - 1, Props.shrine(theme(s, "tribunal", "vivec")));
            }
            case "hist" -> {
                for (int y = 2; y <= 6; y++) c.set(cx, y, cz, B.s(Blocks.MANGROVE_LOG));
                BlockState lf = leaves(Blocks.AZALEA_LEAVES);
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.setIfOpen(cx + dx, 7, cz + dz, lf);
                c.setIfOpen(cx, 8, cz, lf);
                for (int dx = -1; dx <= 1; dx++) c.set(cx + dx, 2, cz + 1, B.s(Blocks.MOSS_CARPET));
                c.set(cx, 3, cz - 1, B.s(Blocks.GLOW_LICHEN));
            }
            case "pact" -> {
                for (int y = 2; y <= 5; y++) c.set(cx, y, cz, B.s(Blocks.STRIPPED_JUNGLE_LOG));
                c.set(cx, 6, cz, B.face(B.s(Blocks.CARVED_PUMPKIN), c.dir(Direction.NORTH)));
                c.set(cx - 1, 6, cz, p.fence);
                c.set(cx + 1, 6, cz, p.fence);
                c.set(cx - 1, 7, cz, p.fence);
                c.set(cx + 1, 7, cz, p.fence);
                c.set(cx, 7, cz, B.s(Blocks.BONE_BLOCK));
                BlockState lf = leaves(Blocks.OAK_LEAVES);
                c.setIfOpen(cx, 2, cz - 1, lf);
            }
            default -> Works.statue(c, p, cx, cz);
        }
        c.anchor(AnchorType.MEETING, cx, 1, -1);
    }
}
