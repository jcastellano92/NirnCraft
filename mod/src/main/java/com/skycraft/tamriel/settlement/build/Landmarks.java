package com.skycraft.tamriel.settlement.build;

import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.Culture;
import com.skycraft.tamriel.settlement.build.Shells.Room;
import com.skycraft.tamriel.settlement.kit.Palette;
import com.skycraft.tamriel.settlement.plan.Archetype;
import com.skycraft.tamriel.settlement.plan.Spec;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The set pieces that make a capital recognisable at a glance: the Gildergreen, the Skyforge's eagle, Solitude's
 * rock arch, the College of Winterhold on its sea stack, Markarth's carved cliff, the White-Gold Tower, the giant
 * tree of Elden Root, Vivec's cantons, domed temples and palaces, xanmeers, crystal spires and castles.
 * {@link Spec#variant} picks the landmark; some take parameters in {@link Spec#name}.
 */
public final class Landmarks {
    public static final int GILDERGREEN = 1, SKYFORGE = 2, TALOS = 3, ARCH = 4, COLLEGE = 5, SEA_STACK = 6,
            WHITE_GOLD_TOWER = 7, GIANT_TREE = 8, CANTON = 9, DOME_TEMPLE = 10, XANMEER = 11, CRYSTAL_TOWER = 12,
            CASTLE = 13, CLIFF = 14, RATWAY = 15, BLUE_PALACE = 16, DOME_PALACE = 17, ROCK = 18, ARENA = 19;

    private Landmarks() {}

    public static void build(Canvas c, Palette p, Spec s, String settlement) {
        switch (s.variant) {
            case GILDERGREEN -> gildergreen(c, p, s);
            case SKYFORGE -> skyforge(c, p, s);
            case TALOS -> talos(c, p, s);
            case ARCH -> arch(c, p, s);
            case COLLEGE -> college(c, p, s);
            case SEA_STACK, ROCK -> rock(c, p, s);
            case WHITE_GOLD_TOWER -> whiteGold(c, p, s);
            case GIANT_TREE -> giantTree(c, p, s);
            case CANTON -> canton(c, p, s);
            case DOME_TEMPLE, DOME_PALACE -> domeHall(c, p, s, s.variant == DOME_PALACE);
            case XANMEER -> xanmeer(c, p, s);
            case CRYSTAL_TOWER -> crystalTower(c, p, s);
            case CASTLE -> castle(c, p, s);
            case CLIFF -> cliff(c, p, s);
            case RATWAY -> ratway(c, p, s);
            case BLUE_PALACE -> bluePalace(c, p, s);
            case ARENA -> arena(c, p, s);
            default -> {
            }
        }
    }

    static BlockState leaves(BlockState s) {
        return s.hasProperty(LeavesBlock.PERSISTENT) ? s.setValue(LeavesBlock.PERSISTENT, true) : s;
    }

    static void disc(Canvas c, int cx, int y, int cz, double r, BlockState s) {
        int ri = (int) Math.ceil(r);
        for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
            if (dx * dx + dz * dz <= r * r) c.set(cx + dx, y, cz + dz, s);
        }
    }

    static void ring(Canvas c, int cx, int y, int cz, double r, double thick, BlockState s) {
        int ri = (int) Math.ceil(r);
        for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist <= r + 0.4 && dist > r - thick) c.set(cx + dx, y, cz + dz, s);
        }
    }

    static void hollowCylinder(Canvas c, int cx, int y0, int y1, int cz, double r, BlockState wall, BlockState floor) {
        int ri = (int) Math.ceil(r);
        for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > r + 0.4) continue;
            for (int y = y0; y <= y1; y++) {
                if (dist > r - 0.6) c.set(cx + dx, y, cz + dz, wall);
                else if (y == y0) c.set(cx + dx, y, cz + dz, floor);
                else c.air(cx + dx, y, cz + dz);
            }
        }
    }

    static void cone(Canvas c, int cx, int y, int cz, double r, int height, BlockState s) {
        for (int k = 0; k <= height; k++) {
            double rr = r * (1 - k / (double) (height + 1));
            ring(c, cx, y + k, cz, rr, 1.2, s);
            if (rr < 1.2) c.set(cx, y + k, cz, s);
        }
    }

    // ------------------------------------------------------------------ Whiterun

    /** The Gildergreen: a blossoming sacred tree in a round stone planter, benches around it. */
    static void gildergreen(Canvas c, Palette p, Spec s) {
        int cx = (s.w - 1) / 2, cz = (s.d - 1) / 2;
        if (!c.recording()) c.clear(0, 1, 0, s.w - 1, 20, s.d - 1);
        for (int x = 0; x < s.w; x++) for (int z = 0; z < s.d; z++) {
            c.set(x, 0, z, p.plaza);
            c.foundation(x, -1, z, p.foundation, 8);
        }
        ring(c, cx, 1, cz, 5, 1, B.s(Blocks.STONE_BRICKS));
        disc(c, cx, 1, cz, 4, B.s(Blocks.GRASS_BLOCK));
        disc(c, cx, 0, cz, 4, B.s(Blocks.DIRT));
        ring(c, cx, 2, cz, 5, 1, B.slab(B.s(Blocks.STONE_BRICK_SLAB), false));
        BlockState log = B.s(Blocks.CHERRY_LOG);
        BlockState bloom = leaves(B.s(Blocks.CHERRY_LEAVES));
        for (int y = 2; y <= 10; y++) {
            c.set(cx, y, cz, log);
            if (y <= 6) {
                c.set(cx + 1, y, cz, log);
                c.set(cx, y, cz + 1, log);
            }
        }
        int[][] branches = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, -1}};
        for (int i = 0; i < branches.length; i++) {
            int[] b = branches[i];
            for (int k = 1; k <= 4; k++) {
                c.set(cx + b[0] * k, 7 + k / 2 + i % 2, cz + b[1] * k, B.axis(log, b[0] != 0 ? Direction.Axis.X : Direction.Axis.Z));
            }
        }
        for (int dx = -7; dx <= 7; dx++) for (int dz = -7; dz <= 7; dz++) for (int dy = 8; dy <= 15; dy++) {
            double e = dx * dx / 42.0 + dz * dz / 42.0 + (dy - 11.5) * (dy - 11.5) / 12.0;
            if (e <= 1.0 && !c.chance(dx, dy, dz, 61, 7)) c.setIfOpen(cx + dx, dy, cz + dz, bloom);
        }
        for (int i = 0; i < 4; i++) {
            Direction d = Direction.from2DDataValue(i);
            int bx = cx + d.getStepX() * 6, bz = cz + d.getStepZ() * 6;
            c.set(bx, 1, bz, B.stair(p.stairs, d, false));
            c.set(bx + d.getClockWise().getStepX(), 1, bz + d.getClockWise().getStepZ(), B.stair(p.stairs, d, false));
        }
        c.set(cx, 1, cz - 6, B.s(Blocks.AIR));
        c.set(cx, 2, cz + 5, Props.shrine("kynareth"));
    }

    /** The Skyforge: a raised stone forge under a giant stone eagle with outspread wings. */
    static void skyforge(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d, cx = (w - 1) / 2;
        if (!c.recording()) c.clear(0, 1, 0, w - 1, 22, d - 1);
        BlockState stone = B.s(Blocks.STONE_BRICKS), dark = B.s(Blocks.POLISHED_ANDESITE);
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            for (int y = 0; y <= 2; y++) c.set(x, y, z, y == 2 ? (c.chance(x, y, z, 3, 3) ? B.s(Blocks.CRACKED_STONE_BRICKS) : stone) : B.s(Blocks.STONE));
            c.foundation(x, -1, z, B.s(Blocks.STONE), 12);
        }
        for (int x = cx - 2; x <= cx + 2; x++) {
            c.set(x, 1, -1, B.stair(B.s(Blocks.STONE_BRICK_STAIRS), Direction.SOUTH, false));
            c.set(x, 2, 0, B.stair(B.s(Blocks.STONE_BRICK_STAIRS), Direction.SOUTH, false));
            c.set(x, 0, -2, B.stair(B.s(Blocks.STONE_BRICK_STAIRS), Direction.SOUTH, false));
        }
        // the forge
        int fz = d / 2;
        c.set(cx, 3, fz, B.face(Props.forge(), Direction.NORTH));
        c.set(cx - 2, 3, fz, B.s(Blocks.ANVIL));
        c.set(cx + 2, 3, fz, B.face(Props.grindstone(), Direction.WEST));
        c.set(cx + 3, 3, fz - 2, B.face(Props.workbench(), Direction.WEST));
        c.set(cx - 3, 3, fz - 2, B.face(Props.smelter(), Direction.EAST));
        c.set(cx - 3, 3, fz + 1, B.s(Blocks.WATER_CAULDRON).setValue(net.minecraft.world.level.block.LayeredCauldronBlock.LEVEL, 3));
        Props.chest(c, cx + 3, 3, fz + 1, Direction.WEST, "smithy");
        c.anchor(AnchorType.SMITH, cx, 3, fz - 1);
        Props.brazier(c, p, 1, 2, 1);
        Props.brazier(c, p, w - 2, 2, 1);
        // the eagle: body and head over the back of the forge, wings spread wide
        int ez = d - 3;
        for (int y = 3; y <= 11; y++) {
            for (int dx = -1; dx <= 1; dx++) c.set(cx + dx, y, ez, y > 9 && dx != 0 ? B.s(Blocks.AIR) : dark);
            c.set(cx, y, ez + 1, dark);
        }
        c.set(cx, 12, ez, dark);
        c.set(cx, 13, ez, dark);
        c.set(cx, 13, ez - 1, B.stair(B.s(Blocks.POLISHED_ANDESITE_STAIRS), Direction.SOUTH, true));
        c.set(cx, 12, ez - 1, B.s(Blocks.ANDESITE_WALL));
        for (int side = -1; side <= 1; side += 2) {
            for (int k = 1; k <= 7; k++) {
                int x = cx + side * (1 + k);
                int y = 9 + k / 2;
                c.set(x, y, ez, dark);
                c.set(x, y - 1, ez, B.stair(B.s(Blocks.POLISHED_ANDESITE_STAIRS), side > 0 ? Direction.WEST : Direction.EAST, true));
                if (k > 2) c.set(x, y + 1, ez, B.slab(B.s(Blocks.POLISHED_ANDESITE_SLAB), false));
                if (k % 2 == 0) c.set(x, y - 2, ez, B.s(Blocks.ANDESITE_WALL));
            }
        }
        // talons on a rock
        c.set(cx - 1, 3, ez - 1, B.s(Blocks.ANDESITE_WALL));
        c.set(cx + 1, 3, ez - 1, B.s(Blocks.ANDESITE_WALL));
    }

    static void talos(Canvas c, Palette p, Spec s) {
        int cx = (s.w - 1) / 2, cz = (s.d - 1) / 2;
        BlockState st = B.s(Blocks.POLISHED_ANDESITE), stairs = B.s(Blocks.POLISHED_ANDESITE_STAIRS);
        for (int x = 0; x < s.w; x++) for (int z = 0; z < s.d; z++) {
            c.set(x, 0, z, p.plaza);
            c.foundation(x, -1, z, p.foundation, 6);
        }
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            c.set(cx + dx, 1, cz + dz, B.s(Blocks.CHISELED_STONE_BRICKS));
            if (Math.abs(dx) <= 1 && Math.abs(dz) <= 1) c.set(cx + dx, 2, cz + dz, B.s(Blocks.STONE_BRICKS));
        }
        // legs, torso, arms, head, raised sword
        for (int y = 3; y <= 5; y++) {
            c.set(cx - 1, y, cz, st);
            c.set(cx + 1, y, cz, st);
        }
        for (int y = 6; y <= 9; y++) for (int dx = -1; dx <= 1; dx++) c.set(cx + dx, y, cz, st);
        c.set(cx - 2, 9, cz, st);
        c.set(cx - 2, 8, cz, B.stair(stairs, Direction.EAST, true));
        c.set(cx + 2, 9, cz, st);
        c.set(cx + 2, 10, cz, st);
        for (int y = 11; y <= 15; y++) c.set(cx + 2, y, cz, B.s(Blocks.IRON_BARS));
        c.set(cx, 10, cz, st);
        c.set(cx, 11, cz, B.s(Blocks.ANDESITE_WALL));
        c.set(cx, 10, cz - 1, B.stair(stairs, Direction.SOUTH, false));
        c.set(cx - 1, 6, cz - 1, B.slab(B.s(Blocks.POLISHED_ANDESITE_SLAB), true));
        c.set(cx + 1, 6, cz - 1, B.slab(B.s(Blocks.POLISHED_ANDESITE_SLAB), true));
        Props.candles(c, cx - 2, 2, cz - 2, 3);
        Props.candles(c, cx + 2, 2, cz - 2, 3);
        c.set(cx, 2, cz - 2, Props.shrine("talos"));
    }

    // ------------------------------------------------------------------ Solitude

    /**
     * One segment of a great natural rock arch. {@link Spec#name} holds {@code sx,sz,ex,ez,top,span}: the arch runs from
     * (sx, sz) to (ex, ez), its deck sits {@code top} blocks over this piece's floor and it is {@code span} blocks wide.
     */
    static void arch(Canvas c, Palette p, Spec s) {
        if (c.recording()) return;
        String[] a = s.name.split(",");
        if (a.length < 6) return;
        int sx = Integer.parseInt(a[0]), sz = Integer.parseInt(a[1]), ex = Integer.parseInt(a[2]), ez = Integer.parseInt(a[3]);
        int top = Integer.parseInt(a[4]), width = Integer.parseInt(a[5]);
        Canvas w = c.frame(0, s.floor, 0, Direction.NORTH);
        double dx = ex - sx, dz = ez - sz, len2 = dx * dx + dz * dz, len = Math.sqrt(len2);
        BlockState[] rock = {B.s(Blocks.STONE), B.s(Blocks.ANDESITE), B.s(Blocks.STONE), B.s(Blocks.COBBLESTONE), B.s(Blocks.TUFF)};
        for (int x = w.clipMinX(); x <= w.clipMaxX(); x++) {
            for (int z = w.clipMinZ(); z <= w.clipMaxZ(); z++) {
                double px = x - sx, pz = z - sz;
                double t = (px * dx + pz * dz) / len2;
                if (t < -0.08 || t > 1.08) continue;
                double side = Math.abs((px * dz - pz * dx) / len);
                double halfW = width / 2.0 + 2.5 * Math.sin(x * 0.21 + z * 0.17);
                if (side > halfW) continue;
                double tc = Math.max(0, Math.min(1, t));
                // the deck rises over the span; under it a great opening, the legs reaching the ground at both ends
                double lift = Math.sin(Math.PI * tc);
                int deck = top + (int) Math.round(top * 0.6 * lift);
                int thick = 6 + (int) Math.round(8 * (1 - lift)) + (int) Math.round(2 * Math.sin(x * 0.3 + z * 0.23));
                int ground = w.wground(x, z) - s.floor;
                int from = lift > 0.35 ? deck - thick : ground - 2;
                double edge = side / halfW;
                int crown = deck - (edge > 0.85 ? (int) Math.round((edge - 0.85) * 18) : 0);
                for (int y = from; y <= crown; y++) {
                    BlockState b = rock[(int) Math.floorMod(w.hash(x, y, z, 5), rock.length)];
                    if (y == crown) b = edge < 0.85 ? B.s(Blocks.GRASS_BLOCK) : B.s(Blocks.MOSSY_COBBLESTONE);
                    w.set(x, y, z, b);
                }
            }
        }
    }

    /** A natural rock mass (sea stack, plateau) up to the piece's floor, round with a rough edge. */
    static void rock(Canvas c, Palette p, Spec s) {
        if (c.recording()) return;
        int cx = (s.w - 1) / 2, cz = (s.d - 1) / 2;
        double r = Math.min(s.w, s.d) / 2.0 - 2;
        BlockState[] stone = {B.s(Blocks.STONE), B.s(Blocks.ANDESITE), B.s(Blocks.STONE), B.s(Blocks.DIORITE), B.s(Blocks.COBBLESTONE)};
        for (int x = -2; x < s.w + 2; x++) {
            for (int z = -2; z < s.d + 2; z++) {
                double ang = Math.atan2(z - cz, x - cx);
                double rr = r * (0.9 + 0.1 * Math.sin(ang * 5 + s.x)) + 1.5 * Math.sin(ang * 11);
                double dist = Math.hypot(x - cx, z - cz);
                if (dist > rr) continue;
                // flanks taper towards the top: the edge sits a few blocks lower than the centre
                int topY = dist > rr - 3 ? -(int) Math.round((dist - (rr - 3)) * 1.5) : 0;
                int g = c.wground(c.wx(x, z), c.wz(x, z)) - s.floor;
                for (int y = Math.min(g, topY) - 3; y <= topY; y++) {
                    BlockState b = stone[c.roll(x, y, z, 3, stone.length)];
                    if (y == topY) b = s.variant == SEA_STACK ? B.s(Blocks.SNOW_BLOCK) : B.s(Blocks.GRASS_BLOCK);
                    c.set(x, y, z, b);
                }
            }
        }
    }

    // ------------------------------------------------------------------ the College of Winterhold

    /** The College: a walled courtyard round the Hall of the Elements, its spire, and two lesser towers. */
    static void college(Canvas c, Palette p, Spec s) {
        int cx = (s.w - 1) / 2, cz = (s.d - 1) / 2;
        int R = Math.min(s.w, s.d) / 2 - 1;
        BlockState wall = B.s(Blocks.STONE_BRICKS), wall2 = B.s(Blocks.CRACKED_STONE_BRICKS), dark = B.s(Blocks.DEEPSLATE_BRICKS);
        BlockState roof = B.s(Blocks.DEEPSLATE_TILES);
        if (!c.recording()) c.clear(0, 1, 0, s.w - 1, 60, s.d - 1);
        disc(c, cx, 0, cz, R, B.s(Blocks.STONE_BRICKS));
        // curtain wall with walkway
        for (int y = 1; y <= 7; y++) ring(c, cx, y, cz, R, 2, c.chance(y, y, y, 3, 3) ? wall2 : wall);
        ring(c, cx, 8, cz, R, 1, wall);
        for (int i = 0; i < 64; i += 2) {
            double ang = i * Math.PI / 32;
            c.set(cx + (int) Math.round(Math.cos(ang) * R), 9, cz + (int) Math.round(Math.sin(ang) * R), wall);
        }
        // the gate towards the bridge (front)
        for (int x = cx - 2; x <= cx + 2; x++) for (int y = 1; y <= 5; y++) for (int z = cz - R - 1; z <= cz - R + 2; z++) c.air(x, y, z);
        for (int x = cx - 2; x <= cx + 2; x++) c.set(x, 5, cz - R, B.s(Blocks.IRON_BARS));
        // courtyard: the Hall of the Elements
        int hr = Math.max(6, R / 2);
        hollowCylinder(c, cx, 1, 16, cz, hr, wall, B.s(Blocks.POLISHED_DEEPSLATE));
        for (int y = 3; y <= 13; y += 5) {
            for (int i = 0; i < 8; i++) {
                double ang = i * Math.PI / 4;
                c.set(cx + (int) Math.round(Math.cos(ang) * hr), y, cz + (int) Math.round(Math.sin(ang) * hr), B.s(Blocks.BLUE_STAINED_GLASS));
                c.set(cx + (int) Math.round(Math.cos(ang) * hr), y + 1, cz + (int) Math.round(Math.sin(ang) * hr), B.s(Blocks.BLUE_STAINED_GLASS));
            }
        }
        Shells.dome(c, p, cx, 17, cz, hr, roof);
        // the arch-mage's spire rises from the dome
        hollowCylinder(c, cx, 17, 40, cz, 3, dark, dark);
        cone(c, cx, 41, cz, 4, 9, roof);
        // lesser towers (Attainment and Countenance)
        int[][] towers = {{-hr - 4, hr + 1}, {hr + 4, hr + 1}};
        for (int[] t : towers) {
            int tx = cx + t[0], tz = cz + t[1];
            hollowCylinder(c, tx, 1, 24, tz, 3, wall, B.s(Blocks.SPRUCE_PLANKS));
            cone(c, tx, 25, tz, 4, 8, roof);
            for (int y = 6; y <= 20; y += 7) c.set(tx, y, tz - 3, B.s(Blocks.BLUE_STAINED_GLASS));
            c.door(tx, 1, tz - 3, B.s(Blocks.DARK_OAK_DOOR), Direction.SOUTH);
            for (int y = 1; y <= 24; y++) c.set(tx, y, tz + 2, B.face(B.s(Blocks.LADDER), Direction.NORTH));
            c.bed(tx - 1, 1, tz, B.s(Blocks.BLUE_BED), Direction.WEST);
            c.anchor(AnchorType.BED, tx - 1, 1, tz);
            Props.chest(c, tx + 1, 1, tz, Direction.WEST, "palace");
        }
        // the hall: the Arch-Mage's seat, enchanting and alchemy stations, the well of magicka
        c.door(cx, 1, cz - hr, B.s(Blocks.DARK_OAK_DOOR), Direction.SOUTH);
        ring(c, cx, 0, cz, 2.5, 1, B.s(Blocks.SEA_LANTERN));
        c.set(cx, 1, cz, B.s(Blocks.AMETHYST_CLUSTER));
        c.set(cx - hr + 2, 1, cz, B.face(Props.enchanter(), Direction.EAST));
        c.set(cx + hr - 2, 1, cz, B.face(Props.alchemyLab(), Direction.WEST));
        for (int i = -2; i <= 2; i++) c.set(cx + i, 1, cz + hr - 1, B.s(Blocks.BOOKSHELF));
        c.anchor(AnchorType.WIZARD, cx - hr + 3, 1, cz);
        c.anchor(AnchorType.JARL, cx, 1, cz + hr - 3);
        c.anchor(AnchorType.MERCHANT, cx + hr - 3, 1, cz + 1);
        for (int y = 1; y <= 16; y++) c.set(cx + hr - 1, y, cz - 2, B.face(B.s(Blocks.LADDER), Direction.WEST));
        c.anchor(AnchorType.MEETING, cx, 1, cz - hr - 3);
        c.set(cx - 3, 1, cz - hr - 3, B.s(Blocks.BELL).setValue(net.minecraft.world.level.block.BellBlock.ATTACHMENT,
                net.minecraft.world.level.block.state.properties.BellAttachType.FLOOR));
        // braziers in the courtyard
        Props.brazier(c, p, cx - R + 4, 0, cz);
        Props.brazier(c, p, cx + R - 4, 0, cz);
    }

    // ------------------------------------------------------------------ Imperial City

    /** The White-Gold Tower: a pale round tower banded in gold, buttressed at its foot, crowned by a needle spire. */
    static void whiteGold(Canvas c, Palette p, Spec s) {
        int cx = (s.w - 1) / 2, cz = (s.d - 1) / 2;
        BlockState wall = B.s(Blocks.SMOOTH_QUARTZ), band = B.s(Blocks.HONEYCOMB_BLOCK), glass = B.s(Blocks.LIGHT_BLUE_STAINED_GLASS);
        int shaft = Math.max(60, s.h - 50);
        double r0 = Math.min(s.w, s.d) / 2.0 - 3;
        if (!c.recording()) c.clear(0, 1, 0, s.w - 1, 12, s.d - 1);
        for (int y = 0; y <= shaft; y++) {
            double r = r0 - (r0 - 6) * (y / (double) shaft);
            boolean isBand = y % 24 == 0 && y > 0;
            int ri = (int) Math.ceil(r);
            for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > r + 0.4) continue;
                if (dist > r - 1.1) {
                    boolean window = y % 12 == 6 && (dx == 0 || dz == 0);
                    c.set(cx + dx, y, cz + dz, isBand ? band : window ? glass : wall);
                } else if (y == 0 || y % 24 == 0) {
                    c.set(cx + dx, y, cz + dz, B.s(Blocks.QUARTZ_BRICKS));
                } else if (y < 14) {
                    c.air(cx + dx, y, cz + dz);
                }
            }
        }
        // buttress fins at the foot
        for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI / 4 + Math.PI / 8;
            for (int k = 0; k < 6; k++) {
                int x = cx + (int) Math.round(Math.cos(ang) * (r0 + k)), z = cz + (int) Math.round(Math.sin(ang) * (r0 + k));
                for (int y = 0; y <= 22 - k * 3; y++) c.set(x, y, z, wall);
                c.foundation(x, -1, z, wall, 10);
            }
        }
        // needle spire
        for (int k = 0; k < s.h - shaft; k++) {
            double r = 6 * (1 - k / (double) (s.h - shaft));
            ring(c, cx, shaft + 1 + k, cz, Math.max(0.6, r), 1.3, k % 10 == 0 ? band : wall);
            if (r < 1.2) c.set(cx, shaft + 1 + k, cz, band);
        }
        c.set(cx, s.h + 1, cz, B.s(Blocks.LIGHTNING_ROD));
        // the Elder Council's hall at the foot
        c.door(cx, 1, (int) (cz - r0), B.s(Blocks.BIRCH_DOOR), Direction.SOUTH);
        disc(c, cx, 1, cz, 3, B.slab(B.s(Blocks.QUARTZ_SLAB), true));
        for (int i = 0; i < 8; i++) {
            double ang = i * Math.PI / 4;
            int x = cx + (int) Math.round(Math.cos(ang) * 4.5), z = cz + (int) Math.round(Math.sin(ang) * 4.5);
            c.set(x, 1, z, B.stair(B.s(Blocks.BIRCH_STAIRS), Direction.NORTH, false));
        }
        c.anchor(AnchorType.JARL, cx, 1, cz + 5);
        c.anchor(AnchorType.STEWARD, cx + 3, 1, cz + 4);
        c.anchor(AnchorType.GUARD, cx - 2, 1, (int) (cz - r0) - 2);
        c.anchor(AnchorType.GUARD, cx + 2, 1, (int) (cz - r0) - 2);
        ring(c, cx, 13, cz, 2, 1, B.s(Blocks.SEA_LANTERN));
    }

    // ------------------------------------------------------------------ Valenwood

    /** A city tree: an enormous trunk with ring platforms, huts and spiral stairs, under a vast canopy. */
    static void giantTree(Canvas c, Palette p, Spec s) {
        int cx = (s.w - 1) / 2, cz = (s.d - 1) / 2;
        BlockState log = B.id("skycraft:graht_oak_log", Blocks.JUNGLE_LOG);
        BlockState leaf = leaves(B.id("skycraft:graht_oak_leaves", Blocks.JUNGLE_LEAVES));
        double trunk = Math.max(5, Math.min(s.w, s.d) / 5.0);
        int top = s.h - 16;
        for (int y = -2; y <= top; y++) {
            double r = trunk * (1 + 0.6 * Math.max(0, (6 - y) / 6.0)) * (1 - 0.25 * y / top);
            disc(c, cx, y, cz, r, B.axis(log, Direction.Axis.Y));
        }
        for (int dx = -(int) trunk * 2; dx <= trunk * 2; dx++) for (int dz = -(int) trunk * 2; dz <= trunk * 2; dz++) {
            if (Math.hypot(dx, dz) <= trunk * 1.6) c.foundation(cx + dx, -3, cz + dz, B.axis(log, Direction.Axis.Y), 10);
        }
        // a hollow hall at the foot, the chieftain's seat inside
        int hr = (int) trunk - 2;
        for (int dx = -hr; dx <= hr; dx++) for (int dz = -hr; dz <= hr; dz++) {
            if (dx * dx + dz * dz > hr * hr) continue;
            for (int y = 1; y <= 6; y++) c.air(cx + dx, y, cz + dz);
            c.set(cx + dx, 0, cz + dz, p.floor);
        }
        int doorZ = cz - (int) Math.ceil(trunk * 1.6);
        for (int z = doorZ; z <= cz - hr; z++) for (int y = 1; y <= 3; y++) for (int x = cx - 1; x <= cx + 1; x++) c.air(x, y, z);
        c.anchor(AnchorType.JARL, cx, 1, cz + hr - 1);
        c.anchor(AnchorType.HOUSECARL, cx + 2, 1, cz + hr - 2);
        c.set(cx, 1, cz + hr, B.stair(p.stairs, Direction.SOUTH, false));
        Props.brazier(c, p, cx - hr + 1, 0, cz);
        // ring platforms with huts and a spiral stair
        int[] levels = {18, 36, 54};
        for (int L : levels) {
            if (L > top - 6) break;
            double tr = trunk * (1 - 0.25 * L / top);
            double pr = tr + 8;
            for (int dx = -(int) pr; dx <= pr; dx++) for (int dz = -(int) pr; dz <= pr; dz++) {
                double dist = Math.hypot(dx, dz);
                if (dist > pr || dist <= tr) continue;
                c.set(cx + dx, L, cz + dz, p.planks);
                if (dist > pr - 1) c.set(cx + dx, L + 1, cz + dz, p.fence);
            }
            for (int i = 0; i < 3; i++) {
                double ang = i * 2 * Math.PI / 3 + L;
                int hx = cx + (int) Math.round(Math.cos(ang) * (tr + 4)), hz = cz + (int) Math.round(Math.sin(ang) * (tr + 4));
                hollowCylinder(c, hx, L, L + 4, hz, 2.5, p.wall, p.floor);
                cone(c, hx, L + 5, hz, 3.5, 3, leaf);
                c.bed(hx, L + 1, hz, p.bed, Direction.EAST);
                c.anchor(AnchorType.BED, hx, L + 1, hz);
                int doorX = hx + (int) Math.round(Math.cos(ang + Math.PI) * 2), doorZ2 = hz + (int) Math.round(Math.sin(ang + Math.PI) * 2);
                c.air(doorX, L + 1, doorZ2);
                c.air(doorX, L + 2, doorZ2);
            }
        }
        // spiral stair round the trunk
        double sr0 = trunk + 1.5;
        for (int y = 1; y < Math.min(top - 6, 55); y++) {
            double ang = y * 0.35;
            double tr = trunk * (1 - 0.25 * y / top) * (1 + 0.6 * Math.max(0, (6 - y) / 6.0)) + 1.2;
            int x = cx + (int) Math.round(Math.cos(ang) * tr), z = cz + (int) Math.round(Math.sin(ang) * tr);
            c.set(x, y, z, p.planks);
            c.air(x, y + 1, z);
            c.air(x, y + 2, z);
        }
        // branches and canopy
        for (int i = 0; i < 6; i++) {
            double ang = i * Math.PI / 3;
            for (int k = 0; k < 16; k++) {
                int x = cx + (int) Math.round(Math.cos(ang) * (trunk + k)), z = cz + (int) Math.round(Math.sin(ang) * (trunk + k));
                c.set(x, top - 6 + k / 3, z, B.axis(log, Math.abs(Math.cos(ang)) > 0.7 ? Direction.Axis.X : Direction.Axis.Z));
            }
        }
        int cr = Math.min(s.w, s.d) / 2;
        for (int dx = -cr; dx <= cr; dx++) for (int dz = -cr; dz <= cr; dz++) for (int dy = -6; dy <= 10; dy++) {
            double e = (dx * dx + dz * dz) / (double) (cr * cr) + dy * dy / 64.0;
            if (e <= 1 && !c.chance(dx, dy, dz, 31, 6)) c.setIfOpen(cx + dx, top + dy, cz + dz, leaf);
        }
    }

    // ------------------------------------------------------------------ Morrowind

    /** A Vivec canton: a stepped stone ziggurat rising from the water, arched halls inside, a shrine on top. */
    static void canton(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d;
        BlockState wall = p.wall, trim = p.wallLow;
        int tiers = 3, th = Math.max(8, s.h / tiers);
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            c.set(x, 0, z, p.foundation);
            c.foundation(x, -1, z, p.foundation, SettlementPieceDepthL.DEPTH);
        }
        for (int t = 0; t < tiers; t++) {
            int in = t * 4;
            int y0 = t * th;
            for (int y = 1; y <= th; y++) {
                for (int x = in; x < w - in; x++) {
                    for (int z = in; z < d - in; z++) {
                        boolean edge = x == in || z == in || x == w - 1 - in || z == d - 1 - in;
                        if (edge) {
                            boolean arch = y <= 4 && t == 0 && ((x - in) % 6 == 3 || (z - in) % 6 == 3);
                            if (arch) c.air(x, y0 + y, z);
                            else c.set(x, y0 + y, z, y == th ? trim : wall);
                        } else if (y == th) {
                            c.set(x, y0 + y, z, p.roofFull);
                        } else {
                            c.air(x, y0 + y, z);
                        }
                    }
                }
            }
        }
        // shrine on the top tier and a lantern on each corner
        int top = tiers * th;
        int cx = (w - 1) / 2, cz = (d - 1) / 2;
        c.set(cx, top + 1, cz, Props.shrine("akatosh"));
        Props.brazier(c, p, tiers * 4 - 4, top - th, tiers * 4 - 4);
        // inner market hall
        Room hall = new Room(2, 2, w - 3, d - 3, 1, th);
        for (int i = 0; i < 4; i++) {
            c.anchor(AnchorType.MERCHANT, 3 + i * 3, 1, d / 2);
            c.set(3 + i * 3, 1, d / 2 + 1, p.planks);
        }
        c.anchor(AnchorType.INN, w - 4, 1, d - 4);
        for (int z = 3; z < d - 3; z += 4) {
            c.bed(w - 3, 1, z, p.bed, Direction.EAST);
            c.anchor(AnchorType.BED, w - 3, 1, z);
        }
        c.anchor(AnchorType.GUARD, cx, 1, 1);
        c.anchor(AnchorType.MEETING, cx, 1, cz);
        c.set(cx, 1, cz, B.s(Blocks.BELL));
        Props.lantern(c, p, cx, th - 1, cz, true);
        if (hall.w() > 0) Props.rug(c, p.carpet, cx - 1, 1, 2, cx + 1, d - 3);
    }

    /** A domed hall with four minarets: a temple, or (palace) a ruler's seat. */
    static void domeHall(Canvas c, Palette p, Spec s, boolean palace) {
        int w = s.w, d = s.d, wallH = 9;
        int cx = (w - 1) / 2, cz = (d - 1) / 2;
        if (!c.recording()) c.clear(0, 1, 0, w - 1, wallH + w / 2 + 4, d - 1);
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
            c.set(x, 0, z, edge ? p.wallLow : p.floor2.equals(p.floor) ? p.floor : p.floor2);
            c.foundation(x, -1, z, p.foundation, SettlementPieceDepthL.DEPTH);
            for (int y = 1; y <= wallH; y++) {
                if (edge) {
                    boolean window = y >= 4 && y <= 6 && ((x % 4 == 2 && (z == 0 || z == d - 1)) || (z % 4 == 2 && (x == 0 || x == w - 1)));
                    c.set(x, y, z, window ? p.window : (y == wallH ? p.wallLow : p.wall));
                } else {
                    c.air(x, y, z);
                }
            }
        }
        Shells.flatRoof(c, p, 0, 0, w - 1, d - 1, wallH + 1);
        Shells.dome(c, p, cx, wallH + 1, cz, Math.min(w, d) / 2 - 2, p.roofFull);
        int[][] corners = {{0, 0}, {w - 1, 0}, {0, d - 1}, {w - 1, d - 1}};
        for (int[] q : corners) {
            for (int y = 1; y <= wallH + 12; y++) {
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    if (Math.abs(dx) + Math.abs(dz) == 2 && y < wallH + 10) continue;
                    c.set(q[0] + dx, y, q[1] + dz, y % 7 == 0 ? p.wallLow : p.wall);
                }
            }
            c.set(q[0], wallH + 13, q[1], p.roofFull);
            c.set(q[0], wallH + 14, q[1], p.column);
        }
        for (int x = cx - 1; x <= cx + 1; x++) for (int y = 1; y <= 4; y++) c.air(x, y, 0);
        c.door(cx, 1, 0, p.door, Direction.SOUTH);
        Room r = new Room(2, 2, w - 3, d - 3, 1, wallH);
        if (palace) Interiors.palace(c, p, r, null, cx, true, false);
        else Interiors.temple(c, p, r, cx, s.name.isEmpty() ? "akatosh" : s.name, false);
    }

    /** An Argonian xanmeer: a stepped pyramid with a stair up the front and a shrine on top. */
    static void xanmeer(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d;
        int tiers = Math.min(w, d) / 4;
        BlockState stone = B.s(Blocks.MOSSY_STONE_BRICKS), stone2 = B.s(Blocks.CRACKED_STONE_BRICKS);
        for (int t = 0; t < tiers; t++) {
            int in = t * 2;
            for (int y = t * 3; y < t * 3 + 3; y++) {
                for (int x = in; x < w - in; x++) for (int z = in; z < d - in; z++) {
                    c.set(x, y, z, c.chance(x, y, z, 3, 4) ? stone2 : stone);
                    if (t == 0) c.foundation(x, -1, z, stone, 12);
                }
            }
        }
        int cx = (w - 1) / 2;
        for (int k = 0; k < tiers * 3; k++) {
            for (int x = cx - 1; x <= cx + 1; x++) c.set(x, k, -1 + (k * 2) / 3, B.stair(B.s(Blocks.MOSSY_STONE_BRICK_STAIRS), Direction.SOUTH, false));
        }
        int top = tiers * 3;
        c.set(cx, top, (d - 1) / 2, B.s(Blocks.MOSS_BLOCK));
        c.set(cx, top + 1, (d - 1) / 2, B.s(Blocks.MANGROVE_PROPAGULE));
        Props.brazier(c, p, cx - 2, top - 1, (d - 1) / 2);
        Props.brazier(c, p, cx + 2, top - 1, (d - 1) / 2);
        c.anchor(AnchorType.PRIEST, cx, top, (d - 1) / 2 - 2);
        for (int i = 0; i < 12; i++) {
            int x = c.roll(i, 0, 0, 5, w), z = c.roll(0, 0, i, 6, d);
            c.setIfOpen(x, 1 + c.roll(i, 1, 0, 7, top), z, B.s(Blocks.VINE));
        }
    }

    // ------------------------------------------------------------------ Summerset

    static void crystalTower(Canvas c, Palette p, Spec s) {
        int cx = (s.w - 1) / 2, cz = (s.d - 1) / 2;
        BlockState glass = B.s(Blocks.LIGHT_BLUE_STAINED_GLASS), crystal = B.s(Blocks.AMETHYST_BLOCK), quartz = B.s(Blocks.QUARTZ_BRICKS);
        int h = s.h;
        double r0 = Math.min(s.w, s.d) / 2.0 - 1;
        for (int y = 0; y <= h; y++) {
            double r = Math.max(1, r0 * (1 - y / (double) (h + 8)));
            int sides = 8;
            int ri = (int) Math.ceil(r);
            for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
                // octagonal facets
                double oct = Math.max(Math.abs(dx), Math.abs(dz)) * 0.92 + Math.min(Math.abs(dx), Math.abs(dz)) * 0.38;
                if (oct > r) continue;
                boolean shell = oct > r - 1.2;
                if (shell) c.set(cx + dx, y, cz + dz, y % 16 == 0 ? quartz : ((dx + dz + y) % 9 == 0 ? crystal : glass));
                else if (y == 0 || y % 16 == 0) c.set(cx + dx, y, cz + dz, quartz);
                else c.air(cx + dx, y, cz + dz);
            }
            if (sides == 0) break;
        }
        c.set(cx, h + 1, cz, B.s(Blocks.AMETHYST_CLUSTER));
        c.door(cx, 1, cz - (int) r0 + 1, B.s(Blocks.BIRCH_DOOR), Direction.SOUTH);
        for (int y = 1; y < Math.min(h, 64); y++) c.set(cx, y, cz + 1, B.face(B.s(Blocks.LADDER), Direction.NORTH));
        c.set(cx, 0, cz + 2, quartz);
        for (int y = 1; y < Math.min(h, 64); y++) c.set(cx, y, cz + 2, quartz);
        c.anchor(AnchorType.WIZARD, cx - 2, 1, cz);
    }

    // ------------------------------------------------------------------ castles and keeps

    /** A keep with four corner towers and a great hall inside (Wayrest, Orsinium, Helgen, Evermor...). */
    static void castle(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d, h = Math.max(14, s.h - 10);
        boolean pointed = p.name.equals("breton") || p.name.equals("altmer") || p.name.equals("imperial");
        if (!c.recording()) c.clear(0, 1, 0, w - 1, h + 12, d - 1);
        BlockState wall = p.cityWall, wall2 = p.cityWall2;
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
            c.set(x, 0, z, edge ? wall : p.floor);
            c.foundation(x, -1, z, p.foundation, SettlementPieceDepthL.DEPTH);
            for (int y = 1; y <= h; y++) {
                if (edge) c.set(x, y, z, y % 6 == 3 && (x % 4 == 2 || z % 4 == 2) ? p.window : c.chance(x, y, z, 5, 6) ? wall2 : wall);
                else if (y == 8) c.set(x, y, z, p.floor2.equals(p.floor) ? p.planks : p.floor2);
                else c.air(x, y, z);
            }
        }
        for (int x = -1; x <= w; x++) for (int z = -1; z <= d; z++) {
            boolean edge = x == -1 || z == -1 || x == w || z == d;
            if (edge && (x + z) % 2 == 0) c.set(x, h + 1, z, wall);
            if (!edge) c.set(x, h, z, wall);
        }
        int[][] corners = {{0, 0}, {w - 1, 0}, {0, d - 1}, {w - 1, d - 1}};
        for (int[] q : corners) {
            hollowCylinder(c, q[0], 1, h + 6, q[1], 3, wall, p.planks);
            if (pointed) cone(c, q[0], h + 7, q[1], 4, 8, p.roofFull);
            else {
                ring(c, q[0], h + 7, q[1], 3.5, 1, wall);
                for (int i = 0; i < 8; i += 2) {
                    double ang = i * Math.PI / 4;
                    c.set(q[0] + (int) Math.round(Math.cos(ang) * 3.5), h + 8, q[1] + (int) Math.round(Math.sin(ang) * 3.5), wall);
                }
            }
        }
        int cx = (w - 1) / 2;
        for (int x = cx - 1; x <= cx + 1; x++) for (int y = 1; y <= 4; y++) c.air(x, y, 0);
        c.door(cx, 1, 0, p.door, Direction.SOUTH);
        Interiors.palace(c, p, new Room(2, 2, w - 3, d - 3, 1, 8), new Room(2, 2, w - 3, d - 3, 9, h), cx, true, p.name.equals("orc") || p.name.startsWith("nord") || p.name.equals("stone"));
        for (int y = 1; y < 8; y++) c.set(w - 3, y, d - 3, B.face(B.s(Blocks.LADDER), Direction.WEST));
        c.air(w - 3, 8, d - 3);
        c.set(w - 3, 8, d - 3, B.face(B.s(Blocks.LADDER), Direction.WEST));
        Props.tapestry(c, p, cx - 3, 7, -1);
        Props.tapestry(c, p, cx + 3, 7, -1);
    }

    // ------------------------------------------------------------------ Markarth

    /**
     * A cliff face the city is carved into: a rock wall rising behind the city with waterfalls, and Understone Keep's
     * Dwemer facade and great hall cut into the rock. Local z = 0 is the cliff face (facing the city).
     */
    static void cliff(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d, h = s.h;
        BlockState[] rock = {B.s(Blocks.STONE), B.s(Blocks.ANDESITE), B.s(Blocks.STONE), B.s(Blocks.SMOOTH_SANDSTONE), B.s(Blocks.GRANITE)};
        boolean keep = s.name.equals("keep");
        int keepW = keep ? 17 : 0, keepD = Math.min(d - 4, 21), keepH = 10;
        int kx0 = (w - keepW) / 2;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                int top = h - (int) Math.round(8 * Math.abs(Math.sin(x * 0.17 + z * 0.09))) - (z < 3 ? (3 - z) * 2 : 0);
                c.foundation(x, -1, z, rock[0], SettlementPieceDepthL.DEPTH);
                for (int y = 0; y <= top; y++) {
                    boolean inKeep = x >= kx0 && x < kx0 + keepW && z >= 1 && z <= keepD && y >= 1 && y <= keepH;
                    if (inKeep) continue;
                    c.set(x, y, z, rock[c.roll(x, y, z, 9, rock.length)]);
                }
            }
        }
        if (!keep) {
            cliffFalls(c, p, w, h);
            return;
        }
        // the keep's hall carved into the rock
        for (int x = kx0; x < kx0 + keepW; x++) for (int z = 1; z <= keepD; z++) {
            c.set(x, 0, z, B.s(Blocks.SMOOTH_SANDSTONE));
            for (int y = 1; y <= keepH; y++) {
                boolean edge = x == kx0 || x == kx0 + keepW - 1 || z == keepD;
                c.set(x, y, z, edge ? B.s(Blocks.CUT_SANDSTONE) : B.s(Blocks.AIR));
            }
            c.set(x, keepH + 1, z, B.s(Blocks.CUT_SANDSTONE));
        }
        // Dwemer facade: bronze pillars, a great door and a stepped pediment
        int cx = kx0 + keepW / 2;
        for (int x = kx0 - 2; x < kx0 + keepW + 2; x++) {
            for (int y = 0; y <= keepH + 6; y++) {
                boolean pillar = (x - kx0) % 4 == 0;
                boolean door = Math.abs(x - cx) <= 2 && y >= 1 && y <= 6;
                if (door) {
                    c.air(x, y, 0);
                    continue;
                }
                c.set(x, y, 0, pillar ? B.s(Blocks.WAXED_OXIDIZED_CUT_COPPER) : y > keepH ? B.s(Blocks.CHISELED_SANDSTONE) : B.s(Blocks.CUT_SANDSTONE));
            }
        }
        for (int k = 0; k < 4; k++) for (int x = cx - 8 + k * 2; x <= cx + 8 - k * 2; x++) c.set(x, keepH + 7 + k, 0, B.s(Blocks.CUT_SANDSTONE));
        for (int x = cx - 2; x <= cx + 2; x++) c.set(x, 7, 0, B.s(Blocks.WAXED_CUT_COPPER));
        Interiors.palace(c, p, new Room(kx0 + 1, 1, kx0 + keepW - 2, keepD - 1, 1, keepH), null, cx, true, false);
        cliffFalls(c, p, w, h);
    }

    /** Waterfalls pouring from the clifftop into pools at its foot. */
    static void cliffFalls(Canvas c, Palette p, int w, int h) {
        int[] falls = {w / 5, w * 4 / 5};
        for (int fx : falls) {
            int top = h - (int) Math.round(8 * Math.abs(Math.sin(fx * 0.17)));
            c.set(fx, top, 1, B.s(Blocks.WATER));
            c.set(fx, top, 0, B.s(Blocks.AIR));
            for (int x = fx - 2; x <= fx + 2; x++) for (int z = -4; z <= -1; z++) {
                c.set(x, -1, z, p.foundation);
                c.set(x, 0, z, B.s(Blocks.WATER));
                boolean rim = Math.abs(x - fx) == 2 || z == -4;
                if (rim) c.set(x, 1, z, B.slab(p.trimSlab, false));
            }
        }
    }

    // ------------------------------------------------------------------ arenas

    /** An arena: an oval of tiered stone seating round a sanded floor, with entrance arches. */
    static void arena(Canvas c, Palette p, Spec s) {
        int cx = (s.w - 1) / 2, cz = (s.d - 1) / 2;
        double R = Math.min(s.w, s.d) / 2.0 - 1;
        double inner = R * 0.55;
        int tiers = 8;
        if (!c.recording()) c.clear(0, 1, 0, s.w - 1, tiers + 6, s.d - 1);
        int ri = (int) Math.ceil(R);
        for (int dx = -ri; dx <= ri; dx++) for (int dz = -ri; dz <= ri; dz++) {
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > R + 0.4) continue;
            int x = cx + dx, z = cz + dz;
            c.foundation(x, -1, z, p.foundation, 10);
            if (dist <= inner) {
                c.set(x, 0, z, B.s(Blocks.SAND));
                continue;
            }
            int tier = (int) ((dist - inner) / (R - inner) * tiers);
            boolean outer = dist > R - 1.0;
            int top = outer ? tiers + 4 : tier + 1;
            for (int y = 0; y <= top; y++) c.set(x, y, z, outer ? (y % 4 == 3 ? p.cityWall2 : p.cityWall) : y == top ? B.slab(p.trimSlab, true) : p.foundation);
            if (outer && (dx + dz) % 2 == 0) c.set(x, top + 1, z, p.cityWall);
        }
        // entrance tunnels on the four axes
        for (int i = 0; i < 4; i++) {
            Direction d = Direction.from2DDataValue(i);
            for (int k = (int) inner; k <= ri; k++) {
                for (int l = -1; l <= 1; l++) for (int y = 1; y <= 3; y++) {
                    int x = cx + d.getStepX() * k + (d.getAxis() == Direction.Axis.Z ? l : 0);
                    int z = cz + d.getStepZ() * k + (d.getAxis() == Direction.Axis.X ? l : 0);
                    c.air(x, y, z);
                }
            }
        }
        Props.tapestry(c, p, cx, tiers + 4, cz - ri);
        c.anchor(AnchorType.GUARD, cx, 1, cz - ri - 1);
        c.anchor(AnchorType.MEETING, cx, 1, cz);
    }

    // ------------------------------------------------------------------ Riften

    /** The Ratway: a barred culvert in the canal wall with steps down to a dank passage. */
    static void ratway(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d;
        BlockState stone = B.s(Blocks.MOSSY_STONE_BRICKS);
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            for (int y = -6; y <= 0; y++) {
                boolean edge = x == 0 || x == w - 1 || z == d - 1;
                boolean passage = x >= 1 && x <= w - 2 && y >= -5 && y <= -2;
                c.set(x, y, z, passage && !edge ? B.s(Blocks.AIR) : stone);
            }
        }
        int cx = (w - 1) / 2;
        for (int k = 0; k < 4; k++) c.set(cx, -k, k, B.stair(B.s(Blocks.MOSSY_STONE_BRICK_STAIRS), Direction.NORTH, false));
        for (int x = cx - 1; x <= cx + 1; x++) for (int y = -1; y <= 1; y++) c.set(x, y, 0, B.s(Blocks.IRON_BARS));
        c.air(cx, -1, 0);
        c.air(cx, 0, 0);
        c.air(cx, 1, 0);
        c.set(cx, 1, 0, B.s(Blocks.IRON_BARS));
        c.set(1, -5, d - 2, B.s(Blocks.COBWEB));
        Props.barrel(c, w - 2, -5, d - 2, null);
        c.set(cx, -2, d - 2, B.hanging(B.s(Blocks.LANTERN)));
    }

    // ------------------------------------------------------------------ the Blue Palace

    static void bluePalace(Canvas c, Palette p, Spec s) {
        Palette blue = p.copy();
        blue.roof = B.s(Blocks.DARK_PRISMARINE_STAIRS);
        blue.roofSlab = B.s(Blocks.DARK_PRISMARINE_SLAB);
        blue.roofFull = B.s(Blocks.DARK_PRISMARINE);
        blue.accent = B.s(Blocks.BLUE_WOOL);
        blue.carpet = B.s(Blocks.BLUE_CARPET);
        blue.wall = B.s(Blocks.CALCITE);
        blue.wallLow = B.s(Blocks.POLISHED_DIORITE);
        Spec palace = new Spec(Archetype.PALACE);
        palace.w = s.w;
        palace.d = s.d;
        palace.h = s.h;
        palace.flags = s.flags | Spec.UPPER;
        Builders.building(c, blue, palace, Culture.NORD);
        // flanking wings with towers
        for (int side = -1; side <= 1; side += 2) {
            int tx = side < 0 ? -4 : s.w + 3;
            for (int y = 0; y <= 22; y++) {
                for (int dx = -2; dx <= 2; dx++) for (int dz = 4; dz <= 8; dz++) {
                    boolean edge = Math.abs(dx) == 2 || dz == 4 || dz == 8;
                    if (y == 0 || edge) c.set(tx + dx, y, dz, y > 20 ? blue.roofFull : blue.wall);
                    else c.air(tx + dx, y, dz);
                }
                if (y == 0) for (int dx = -2; dx <= 2; dx++) for (int dz = 4; dz <= 8; dz++) c.foundation(tx + dx, -1, dz, blue.foundation, 16);
            }
            cone(c, tx, 23, 6, 3.5, 6, blue.roofFull);
        }
    }

    static final class SettlementPieceDepthL {
        static final int DEPTH = com.skycraft.tamriel.settlement.SettlementPiece.FOUNDATION;
    }
}
