package com.skycraft.tamriel.settlement.grammar;

import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.SettlementPiece;
import com.skycraft.tamriel.settlement.build.B;
import com.skycraft.tamriel.settlement.build.Canvas;
import com.skycraft.tamriel.settlement.build.Shells;
import com.skycraft.tamriel.settlement.build.Shells.Room;
import com.skycraft.tamriel.settlement.grammar.Blueprint.Annex;
import com.skycraft.tamriel.settlement.grammar.Blueprint.Body;
import com.skycraft.tamriel.settlement.grammar.Blueprint.Foundation;
import com.skycraft.tamriel.settlement.grammar.Blueprint.Part;
import com.skycraft.tamriel.settlement.grammar.Blueprint.Roof;
import com.skycraft.tamriel.settlement.kit.Palette;
import com.skycraft.tamriel.settlement.kit.Shell;
import com.skycraft.tamriel.settlement.plan.Spec;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;

import java.util.ArrayList;
import java.util.List;

/**
 * Paints a {@link Blueprint}: foundation (flat, stone plinth or stilts), floors, walls with timber framing, windows,
 * shutters and flower boxes, doors and partitions, upper floors and stairs, the roof of every footprint part,
 * chimney, porch, balcony, outside stairs and annex; then hands the rooms to the building's interior
 * {@link Programs program} and hangs out its sign and banner. Exotic culture bodies (round huts, chitin hulls,
 * mushroom towers, tree houses, pagodas, tents, watchtowers) are drawn by the kit {@link Shells} and furnished the same
 * way. Every write goes through the chunk-clipped canvas, so the whole building is described on every call.
 */
public final class Grammar {
    private static final int DEPTH = SettlementPiece.FOUNDATION;

    private Grammar() {}

    /** What the shell left for the interior program. */
    public static final class Result {
        /** Ground-floor rooms (main part first). */
        public final List<Room> ground = new ArrayList<>();
        /** Upper-floor rooms (storey by storey, main part first). */
        public final List<Room> upper = new ArrayList<>();
        public int doorX, doorZ;
        /** Hearth spot against the chimney wall (x, z) and the direction into the room, or none. */
        public int hearthX = Integer.MIN_VALUE, hearthZ;
        public Direction hearthInto = Direction.NORTH;
        /** Annex strip (inclusive x/z), when there is one. */
        public int ax0, ax1, az0, az1;
        public boolean[][] mask;

        public boolean hasHearth() {
            return hearthX != Integer.MIN_VALUE;
        }

        public Room main() {
            return ground.get(0);
        }
    }

    public static void build(Canvas c, Spec s) {
        Blueprint b = Blueprint.of(s);
        Result res = b.body == Body.RECT ? rect(c, b) : exotic(c, b);
        if (res.ground.isEmpty()) return;
        Programs.furnish(c, b, res);
        dress(c, b, res);
        if (s.has(Spec.FOR_SALE)) {
            c.anchor(AnchorType.HOUSE_DOOR, res.doorX, 1, res.doorZ);
            c.anchor(AnchorType.HOUSE_MIN, -1, 0, -1);
            c.anchor(AnchorType.HOUSE_MAX, s.w, b.height(), s.d);
        }
    }

    // ------------------------------------------------------------------ rectangular grammar body

    static Result rect(Canvas c, Blueprint b) {
        Palette p = b.pal;
        int w = b.w, d = b.d;
        Result res = new Result();
        boolean[][] m = new boolean[w][d];
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) m[x][z] = b.inBody(x, z);
        res.mask = m;
        int floorH = b.floorH(), wallTop = b.wallTop();
        int roofY = wallTop + 1;
        int top = b.height();
        if (!c.recording()) {
            c.clear(0, 1, 0, w - 1, top, d - 1);
            c.clearPlants(-2, 1, -2, w + 1, top + 2, d + 1);
        }
        Part main = b.parts.get(0);
        // ---- foundation and ground floor
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                if (m[x][z]) {
                    c.set(x, 0, z, outer(m, x, z) ? p.wallLow : p.floor);
                    if (b.foundation == Foundation.STILTS) {
                        if (outer(m, x, z) && (x % 3 == 0 || z % 3 == 0 || corner(m, x, z))) c.foundation(x, -1, z, B.axis(p.post, Direction.Axis.Y), DEPTH);
                    } else {
                        c.foundation(x, -1, z, b.foundation == Foundation.PLINTH && outer(m, x, z) ? p.wallLow : p.foundation, DEPTH);
                    }
                } else if (b.foundation != Foundation.STILTS) {
                    // yards, porch and annex strips: firm ground at floor level
                    boolean strip = x >= b.bx0 && x <= b.bx1 ? z < b.bz0 : true;
                    if (strip) {
                        c.set(x, 0, z, b.foundation == Foundation.PLINTH ? p.foundation : p.path2);
                        c.foundation(x, -1, z, p.foundation, DEPTH);
                    }
                }
            }
        }
        if (b.foundation == Foundation.PLINTH) plinthLedge(c, p, m);
        // ---- stairs between floors (decided first: rooms and doorways avoid them)
        int stairMode = 0; // 0 none, 1 along the left wall, 2 along the back wall, 3 ladder
        if (b.storeys > 1) {
            if (main.d() - 2 >= floorH + 1 && main.w() >= 6) stairMode = 1;
            else if (main.w() - 2 >= floorH + 2) stairMode = 2;
            else stairMode = 3;
        }
        // ---- walls, storey by storey
        for (int k = 0; k < b.storeys; k++) {
            int fy = k * floorH;
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    if (!m[x][z]) continue;
                    boolean out = outer(m, x, z);
                    boolean part = !out && partition(b, x, z);
                    if (k > 0 && !out) c.set(x, fy, z, p.floor2);
                    if (!out && !part) continue;
                    for (int y = fy + (k > 0 ? 0 : 1); y <= fy + b.wallH; y++) {
                        if (y == 0) continue;
                        if (part) {
                            c.set(x, y, z, y == fy && k > 0 ? p.floor2 : partitionBlock(b, p));
                            continue;
                        }
                        c.set(x, y, z, wallBlock(c, b, p, m, x, y, z, k, fy));
                    }
                }
            }
            // doorways through partitions, avoiding the stairs
            for (int i = 1; i < b.parts.size(); i++) {
                Part q = b.parts.get(i);
                int dz = q.z0(), dx = (q.x0() + q.x1()) / 2;
                if (stairMode == 2 && dx >= main.x0() && dx <= main.x0() + floorH + 1) dx = Math.min(q.x1() - 1, main.x0() + floorH + 2);
                if (stairMode == 1 && dx == main.x0() + 1) dx++;
                c.air(dx, fy + 1, dz);
                c.air(dx, fy + 2, dz);
                if (b.wealth.atLeast(Wealth.WEALTHY) && k == 0) c.door(dx, fy + 1, dz, p.door, Direction.SOUTH);
            }
            // rooms
            for (int i = 0; i < b.parts.size(); i++) {
                Part q = b.parts.get(i);
                int rx0 = q.x0() + 1, rz0 = q.z0() + 1, rx1 = q.x1() - 1, rz1 = q.z1() - 1;
                if (i == 0 && (stairMode == 1 || stairMode == 3)) rx0 = q.x0() + 2;
                if (i == 0 && stairMode == 2) rz1 = q.z1() - 2;
                Room r = new Room(rx0, rz0, rx1, rz1, fy + 1, fy + b.wallH + 1);
                (k == 0 ? res.ground : res.upper).add(r);
            }
            // windows on every outer straight wall
            windows(c, b, p, m, fy, k);
        }
        // stairs / ladders between storeys
        for (int k = 1; k < b.storeys; k++) {
            int fy = k * floorH, fyPrev = (k - 1) * floorH;
            if (stairMode == 1) {
                int zs = main.z1() - floorH;
                for (int i = 0; i < floorH; i++) {
                    c.set(main.x0() + 1, fyPrev + 1 + i, zs + i, B.stair(p.stairs, Direction.SOUTH, false));
                    if (i < floorH - 1) c.air(main.x0() + 1, fy, zs + i);
                    c.air(main.x0() + 1, fyPrev + 2 + i, zs + i);
                }
            } else if (stairMode == 2) {
                int xs = main.x0() + 1, zz = main.z1() - 1;
                for (int i = 0; i < floorH; i++) {
                    c.set(xs + i, fyPrev + 1 + i, zz, B.stair(p.stairs, Direction.EAST, false));
                    if (i < floorH - 1) c.air(xs + i, fy, zz);
                    c.air(xs + i, fyPrev + 2 + i, zz);
                }
            } else if (stairMode == 3) {
                for (int y = fyPrev + 1; y <= fy + 1; y++) c.set(main.x0() + 1, y, main.z1() - 1, B.face(B.s(Blocks.LADDER), Direction.NORTH));
            }
        }
        // ---- the foot and head of every stair stay clear: no bed, chest or table is ever put on a landing
        for (int k = 1; k < b.storeys; k++) reserveStairs(c, stairMode, main, floorH, k);
        // ---- front door, steps, back door
        res.doorX = b.doorX;
        res.doorZ = main.z0();
        c.entrance(b.doorX, 1, main.z0(), p.door, Direction.SOUTH);
        if (b.wallH >= 4) c.set(b.doorX, 3, main.z0(), b.timber ? B.axis(p.beam, Direction.Axis.X) : B.stair(p.trim, Direction.NORTH, true));
        if (b.porchD == 0) steps(c, b, p, b.doorX, main.z0() - 1);
        if (b.wealth.atLeast(Wealth.COMMON) && b.use.home() && main.z1() == b.bz1 && b.parts.size() == 1) {
            int bx = b.doorX + (b.doorX + 2 <= main.x1() - 1 ? 1 : 0);
            c.entrance(bx, 1, main.z1(), p.door, Direction.NORTH);
        }
        // ---- roofs (wings first: the main roof overwrites where they meet)
        int ridgeTop = roofY;
        boolean flatish = b.roof == Roof.FLAT || b.roof == Roof.CRENEL || b.roof == Roof.DOME;
        if (flatish) {
            Roofs.flat(c, p, m, roofY, b.roof == Roof.CRENEL, b.timber && b.roof == Roof.FLAT && p.timber);
            if (b.roof == Roof.DOME) ridgeTop = Roofs.dome(c, p, main.x0() + 1, main.z0() + 1, main.x1() - 1, main.z1() - 1, roofY);
            else ridgeTop = roofY + 2;
        } else if (b.roof == Roof.SPIRE) {
            Roofs.flat(c, p, m, roofY, false, false);
            ridgeTop = Roofs.spire(c, p, b.bx0 - 1, b.bz0 - 1, b.bx1 + 1, b.bz1 + 1, roofY + 1,
                    b.culture == com.skycraft.tamriel.settlement.Culture.ALTMER ? B.s(Blocks.AMETHYST_CLUSTER) : B.s(Blocks.LIGHTNING_ROD));
        } else if (b.roof == Roof.SHED) {
            ridgeTop = Roofs.shed(c, p, b.bx0 - 1, b.bz0 - 1, b.bx1 + 1, b.bz1 + 1, roofY, p.wall);
        } else {
            Palette rp = b.roof == Roof.THATCH ? Roofs.thatch(p) : p;
            BlockState gableWall = logWall(p) ? B.axis(p.wall, main.alongZ() ? Direction.Axis.X : Direction.Axis.Z) : p.wall;
            int midZ = (main.z0() + main.z1()) / 2;
            for (int i = b.parts.size() - 1; i >= 0; i--) {
                Part q = b.parts.get(i);
                int ex0 = q.x0() - 1, ex1 = q.x1() + 1, ez0 = q.main() ? q.z0() - 1 : midZ, ez1 = q.z1() + 1;
                int top2;
                if (b.roof == Roof.HIP) {
                    top2 = Roofs.hip(c, rp, ex0, ez0, ex1, ez1, roofY, Math.max(1, b.pitch));
                } else {
                    top2 = Roofs.gable(c, rp, ex0, ez0, ex1, ez1, roofY, q.alongZ(), b.pitch, b.roof == Roof.GAMBREL,
                            q.main() || !q.alongZ(), true, gableWall);
                    if (b.horns && q.main()) {
                        if (q.alongZ()) {
                            Roofs.horns(c, rp, true, (ex0 + ex1) / 2, top2, ez0);
                            Roofs.horns(c, rp, true, (ex0 + ex1) / 2, top2, ez1);
                        } else {
                            Roofs.horns(c, rp, false, (ez0 + ez1) / 2, top2, ex0);
                            Roofs.horns(c, rp, false, (ez0 + ez1) / 2, top2, ex1);
                        }
                    }
                }
                if (q.main()) ridgeTop = top2;
            }
            if (b.dormers && !main.alongZ() && b.roof != Roof.HIP) dormers(c, b, p, main, roofY);
        }
        // ---- chimney and hearth
        if (b.chimney) chimney(c, b, p, main, res, ridgeTop);
        // ---- strips
        if (b.annex != Annex.NONE) annex(c, b, p, res, roofY);
        if (b.porchD > 0) porch(c, b, p, main);
        if (b.balcony) balcony(c, b, p, main);
        if (b.stairSide != 0) outsideStairs(c, b, p);
        if (b.foundation == Foundation.STILTS) stiltWalk(c, b, p, main);
        return res;
    }

    /** Reserves the cells a person walks through at the bottom and top of the stairs (or ladder) up to storey {@code k}. */
    static void reserveStairs(Canvas c, int mode, Part main, int floorH, int k) {
        int fy = k * floorH, fyPrev = (k - 1) * floorH;
        int x0 = main.x0(), z1 = main.z1();
        if (mode == 1) {
            int zs = z1 - floorH;
            c.keepClear(x0 + 1, fyPrev + 1, fyPrev + 2, zs - 1);
            c.keepClear(x0 + 2, fyPrev + 1, fyPrev + 2, zs - 1);
            c.keepClear(x0 + 2, fyPrev + 1, fyPrev + 2, zs);
            c.keepClear(x0 + 2, fy + 1, fy + 2, z1 - 1);
            c.keepClear(x0 + 2, fy + 1, fy + 2, z1 - 2);
        } else if (mode == 2) {
            int xs = x0 + 1, zz = z1 - 1;
            c.keepClear(xs, fyPrev + 1, fyPrev + 2, zz - 1);
            c.keepClear(xs + 1, fyPrev + 1, fyPrev + 2, zz - 1);
            c.keepClear(xs + floorH, fy + 1, fy + 2, zz);
            c.keepClear(xs + floorH - 1, fy + 1, fy + 2, zz - 1);
            c.keepClear(xs + floorH - 2, fy + 1, fy + 2, zz - 1);
        } else if (mode == 3) {
            c.keepClear(x0 + 1, fyPrev + 1, fyPrev + 2, z1 - 2);
            c.keepClear(x0 + 1, fy + 1, fy + 2, z1 - 2);
        }
    }

    /** True if the column is in the footprint with a neighbour (8-way) outside it. */
    static boolean outer(boolean[][] m, int x, int z) {
        if (!Roofs.in(m, x, z)) return false;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) if (!Roofs.in(m, x + dx, z + dz)) return true;
        return false;
    }

    /** A wall corner (convex or the inner corner of an L): not on a straight run. */
    static boolean corner(boolean[][] m, int x, int z) {
        boolean l = Roofs.in(m, x - 1, z), r = Roofs.in(m, x + 1, z), f = Roofs.in(m, x, z - 1), k = Roofs.in(m, x, z + 1);
        boolean runX = l && r && (f != k), runZ = f && k && (l != r);
        return !runX && !runZ;
    }

    /** Inner column on the edge of a part (a wall between rooms). */
    static boolean partition(Blueprint b, int x, int z) {
        if (b.parts.size() < 2) return false;
        boolean edge = false;
        for (Part q : b.parts) {
            if (!q.contains(x, z)) continue;
            boolean onEdge = x == q.x0() || x == q.x1() || z == q.z0() || z == q.z1();
            if (!onEdge) return false;
            edge = true;
        }
        return edge;
    }

    static BlockState partitionBlock(Blueprint b, Palette p) {
        return b.wealth.atLeast(Wealth.WEALTHY) ? p.wall : p.planks;
    }

    static BlockState wallBlock(Canvas c, Blueprint b, Palette p, boolean[][] m, int x, int y, int z, int k, int fy) {
        boolean corner = corner(m, x, z);
        boolean runX = Roofs.in(m, x - 1, z) && Roofs.in(m, x + 1, z);
        int along = runX ? x - b.bx0 : z - b.bz0;
        Direction.Axis axis = runX ? Direction.Axis.X : Direction.Axis.Z;
        if (b.timber) {
            if (corner || along % 4 == 0) return B.axis(p.post, Direction.Axis.Y);
            if (y == fy && k > 0 || y == b.wallTop()) return B.axis(p.beam, axis);
            if (y == 1) return p.wallLow;
            return logWall(p) ? B.axis(p.wall, axis) : c.chance(x, y, z, 11, 7) ? p.wall2 : p.wall;
        }
        if (corner) return b.wealth.atLeast(Wealth.WEALTHY) && !p.column.is(p.wall.getBlock()) && !logWall(p) ? quoin(p) : p.wallLow;
        if (y == 1 || y == fy && k > 0 && b.wealth.atLeast(Wealth.COMMON)) return p.wallLow;
        if (logWall(p)) return B.axis(p.wall, axis);
        return c.chance(x, y, z, 11, 7) ? p.wall2 : p.wall;
    }

    static BlockState quoin(Palette p) {
        return p.column.hasProperty(BlockStateProperties.AXIS) ? B.axis(p.column, Direction.Axis.Y) : p.column;
    }

    static boolean logWall(Palette p) {
        return p.wall.hasProperty(BlockStateProperties.AXIS);
    }

    /** Windows (with shutters and flower boxes) along every straight outer wall of a storey. */
    static void windows(Canvas c, Blueprint b, Palette p, boolean[][] m, int fy, int k) {
        int w = m.length, d = m[0].length;
        int period = b.timber ? 4 : 3, phase = b.timber ? 2 : 1;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                if (!outer(m, x, z) || corner(m, x, z)) continue;
                boolean runX = Roofs.in(m, x - 1, z) && Roofs.in(m, x + 1, z);
                Direction out;
                if (runX) out = Roofs.in(m, x, z - 1) ? Direction.SOUTH : Direction.NORTH;
                else out = Roofs.in(m, x - 1, z) ? Direction.EAST : Direction.WEST;
                int along = runX ? x - b.bx0 : z - b.bz0;
                if (Math.floorMod(along, period) != phase) continue;
                // keep clear of corners and the door
                if (corner(m, x - (runX ? 1 : 0), z - (runX ? 0 : 1)) || corner(m, x + (runX ? 1 : 0), z + (runX ? 0 : 1))) continue;
                if (k == 0 && out == Direction.NORTH && Math.abs(x - b.doorX) <= 1) continue;
                if (b.stairSide != 0 && k == 0 && out == (b.stairSide < 0 ? Direction.WEST : Direction.EAST)) continue;
                int ox = out.getStepX(), oz = out.getStepZ();
                // an annex or a neighbouring part right outside: no window there
                if (b.annex != Annex.NONE && k == 0 && x + ox >= 0 && x + ox < w && (b.annexSide < 0 ? x + ox < b.bx0 : x + ox > b.bx1)) continue;
                c.set(x, fy + 2, z, b.window);
                if (b.wallH >= 5) c.set(x, fy + 3, z, b.window);
                int top = fy + (b.wallH >= 5 ? 3 : 2);
                if (b.shutters) {
                    Direction side = out.getClockWise();
                    BlockState sh = B.with(B.with(B.face(p.trapdoor, out), TrapDoorBlock.OPEN, true), TrapDoorBlock.HALF, Half.TOP);
                    for (int s = -1; s <= 1; s += 2) {
                        for (int y = fy + 2; y <= top; y++) c.setIfOpen(x + ox + side.getStepX() * s, y, z + oz + side.getStepZ() * s, sh);
                    }
                }
                if (b.flowerBoxes && (k == 0 || b.foundation != Foundation.STILTS)) {
                    c.setIfOpen(x + ox, fy + 1, z + oz, B.slab(p.slab, true));
                    c.setIfOpen(x + ox, fy + 2, z + oz, flower(c, x, fy, z));
                }
            }
        }
    }

    static BlockState flower(Canvas c, int x, int y, int z) {
        BlockState[] pots = {B.s(Blocks.POTTED_RED_TULIP), B.s(Blocks.POTTED_POPPY), B.s(Blocks.POTTED_CORNFLOWER), B.s(Blocks.POTTED_AZURE_BLUET),
                B.s(Blocks.POTTED_FERN), B.s(Blocks.POTTED_ALLIUM), B.s(Blocks.POTTED_OXEYE_DAISY), B.s(Blocks.POTTED_FLOWERING_AZALEA)};
        return pots[c.roll(x, y, z, 61, pots.length)];
    }

    /** A one-block stone ledge round a plinth foundation. */
    static void plinthLedge(Canvas c, Palette p, boolean[][] m) {
        int w = m.length, d = m[0].length;
        for (int x = -1; x <= w; x++) {
            for (int z = -1; z <= d; z++) {
                if (Roofs.in(m, x, z)) continue;
                boolean next = Roofs.in(m, x - 1, z) || Roofs.in(m, x + 1, z) || Roofs.in(m, x, z - 1) || Roofs.in(m, x, z + 1);
                if (!next) continue;
                c.setIfOpen(x, 0, z, B.slab(p.trimSlab, true));
                c.foundation(x, -1, z, p.foundation, DEPTH);
            }
        }
    }

    /** Steps from a door (whose outside landing is (x, 0, z)) down to the ground. */
    static void steps(Canvas c, Blueprint b, Palette p, int x, int z) {
        BlockState landing = b.foundation == Foundation.STILTS ? p.planks : p.foundation;
        c.set(x, 0, z, landing);
        if (b.foundation == Foundation.STILTS) c.foundation(x, -1, z, B.axis(p.post, Direction.Axis.Y), DEPTH);
        else c.foundation(x, -1, z, p.foundation, DEPTH);
        c.air(x, 1, z);
        c.air(x, 2, z);
        if (c.recording()) return;
        BlockState stair = b.foundation == Foundation.STILTS ? p.stairs : p.trim;
        for (int k = 1; k <= 8; k++) {
            int y = -k + 1, zz = z - k;
            if (Canvas.solidGround(c.get(x, y, zz))) {
                c.air(x, y + 1, zz);
                break;
            }
            c.set(x, y, zz, B.stair(stair, Direction.SOUTH, false));
            c.air(x, y + 1, zz);
            c.air(x, y + 2, zz);
            c.foundation(x, y - 1, zz, b.foundation == Foundation.STILTS ? B.axis(p.post, Direction.Axis.Y) : p.foundation, 12);
        }
    }

    static void dormers(Canvas c, Blueprint b, Palette p, Part main, int roofY) {
        int pitch = Math.max(1, b.pitch);
        int ring = 2;
        int y = roofY + ring * pitch;
        int zf = main.z0() - 1 + ring, zb = main.z1() + 1 - ring;
        for (int x = main.x0() + 2; x <= main.x1() - 2; x += 4) {
            if (Math.abs(x - b.doorX) <= 1 && b.chimney) continue;
            Roofs.dormer(c, p, x, y, zf, Direction.NORTH, b.window, p.wall);
            if (b.roof == Roof.SLATE) Roofs.dormer(c, p, x, y, zb, Direction.SOUTH, b.window, p.wall);
        }
    }

    static void chimney(Canvas c, Blueprint b, Palette p, Part main, Result res, int ridgeTop) {
        int cx, cz;
        Direction into;
        if (main.alongZ()) {
            cx = (main.x0() + main.x1()) / 2 + 1;
            cz = main.z1();
            into = Direction.NORTH;
        } else {
            boolean right = b.annexSide <= 0;
            cx = right ? main.x1() : main.x0();
            cz = (main.z0() + main.z1()) / 2 + 1;
            if (cz >= main.z1()) cz = main.z1() - 1;
            into = right ? Direction.WEST : Direction.EAST;
        }
        BlockState stone = p.foundation.is(p.wallLow.getBlock()) ? p.foundation : p.wallLow;
        for (int y = 1; y <= ridgeTop + 1; y++) c.set(cx, y, cz, stone);
        c.set(cx, ridgeTop + 2, cz, B.lit(B.s(Blocks.CAMPFIRE), true));
        c.set(cx, 0, cz, stone);
        res.hearthX = cx + into.getStepX();
        res.hearthZ = cz + into.getStepZ();
        res.hearthInto = into;
    }

    // ------------------------------------------------------------------ strips

    static void annex(Canvas c, Blueprint b, Palette p, Result res, int roofY) {
        int ax0 = b.annexSide < 0 ? 0 : b.w - b.annexW, ax1 = ax0 + b.annexW - 1;
        int az0 = b.bz0, az1 = b.bz1;
        if (b.annex == Annex.LEAN_TO || b.annex == Annex.WOODSHED) az0 = Math.max(b.bz0, (b.bz0 + b.bz1) / 2 - 1);
        if (b.annex == Annex.TOWER) az1 = Math.min(b.bz1, b.bz0 + 4);
        if (b.annex == Annex.PEN) az1 = Math.min(b.bz1, b.bz0 + Math.max(4, (b.bz1 - b.bz0) / 2));
        res.ax0 = ax0;
        res.ax1 = ax1;
        res.az0 = az0;
        res.az1 = az1;
        int outX = b.annexSide < 0 ? ax0 : ax1;
        switch (b.annex) {
            case WORKSHOP, LEAN_TO, WOODSHED -> {
                for (int x = ax0; x <= ax1; x++) for (int z = az0; z <= az1; z++) {
                    c.set(x, 0, z, b.annex == Annex.WORKSHOP ? p.foundation : p.path2);
                    c.foundation(x, -1, z, p.foundation, DEPTH);
                }
                int h = b.annex == Annex.WORKSHOP ? b.wallH : b.wallH - 1;
                for (int z : new int[]{az0, az1, (az0 + az1) / 2}) for (int y = 1; y < h; y++) c.set(outX, y, z, B.axis(p.post, Direction.Axis.Y));
                // lean-to roof falling away from the main wall
                for (int z = az0 - 1; z <= az1 + 1; z++) {
                    for (int i = 0; i <= b.annexW; i++) {
                        int x = b.annexSide < 0 ? ax1 - i : ax0 + i;
                        int yy = h + (b.annexW - i) / 2;
                        c.set(x, yy, z, ((b.annexW - i) & 1) == 1 ? B.slab(p.roofSlab, true) : B.slab(p.roofSlab, false));
                    }
                }
                if (b.annex == Annex.WOODSHED) {
                    BlockState log = B.axis(p.post.hasProperty(BlockStateProperties.AXIS) ? p.post : B.s(Blocks.SPRUCE_LOG), Direction.Axis.Z);
                    int inner = b.annexSide < 0 ? ax1 : ax0;
                    for (int z = az0; z <= az1; z++) for (int y = 1; y <= 2; y++) c.set(inner, y, z, log);
                }
            }
            case TOWER -> {
                int th = b.wallTop() + b.floorH();
                BlockState stone = p.cityWall;
                for (int x = ax0; x <= ax1; x++) {
                    for (int z = az0; z <= az1; z++) {
                        boolean edge = x == ax0 || x == ax1 || z == az0 || z == az1;
                        c.set(x, 0, z, edge ? stone : p.floor);
                        c.foundation(x, -1, z, p.foundation, DEPTH);
                        for (int y = 1; y <= th; y++) {
                            if (edge) c.set(x, y, z, (y % 5 == 3 && (x == (ax0 + ax1) / 2 || z == (az0 + az1) / 2)) ? b.window : stone);
                            else if (y % b.floorH() == 0) c.set(x, y, z, p.floor2);
                        }
                    }
                }
                int inner = b.annexSide < 0 ? ax1 : ax0;
                int body = b.annexSide < 0 ? b.bx0 : b.bx1;
                int mz = (az0 + az1) / 2;
                for (int y = 1; y <= 2; y++) {
                    c.air(inner, y, mz);
                    c.air(body, y, mz);
                }
                int lx = b.annexSide < 0 ? ax0 + 1 : ax1 - 1;
                for (int y = 1; y <= th; y++) c.set(lx, y, az1 - 1, B.face(B.s(Blocks.LADDER), Direction.NORTH));
                if (b.roof == Roof.FLAT || b.roof == Roof.CRENEL || b.roof == Roof.DOME) {
                    for (int x = ax0; x <= ax1; x++) for (int z = az0; z <= az1; z++) {
                        boolean edge = x == ax0 || x == ax1 || z == az0 || z == az1;
                        c.set(x, th + 1, z, edge ? stone : p.floor2);
                        if (edge && (x + z) % 2 == 0) c.set(x, th + 2, z, stone);
                    }
                    c.air(lx, th + 1, az1 - 1);
                } else {
                    Roofs.spire(c, p, ax0 - 1, az0 - 1, ax1 + 1, az1 + 1, th + 1, B.s(Blocks.LIGHTNING_ROD));
                }
            }
            case PEN -> {
                for (int x = ax0; x <= ax1; x++) for (int z = az0; z <= az1; z++) {
                    boolean edge = x == ax0 || x == ax1 || z == az0 || z == az1;
                    c.set(x, 0, z, c.chance(x, 0, z, 3, 3) ? B.s(Blocks.COARSE_DIRT) : B.s(Blocks.PODZOL));
                    c.foundation(x, -1, z, B.s(Blocks.DIRT), 6);
                    if (edge && x != (b.annexSide < 0 ? ax1 : ax0)) c.set(x, 1, z, z == az0 && x == (ax0 + ax1) / 2 ? B.face(p.gate, Direction.NORTH) : p.fence);
                }
            }
            default -> {
            }
        }
    }

    static void porch(Canvas c, Blueprint b, Palette p, Part main) {
        int px0 = Math.max(main.x0(), b.doorX - 3), px1 = Math.min(main.x1(), b.doorX + 3);
        if (px1 - px0 < 4) {
            px0 = main.x0();
            px1 = main.x1();
        }
        BlockState floor = b.foundation == Foundation.STILTS ? p.planks : p.floor.equals(p.wallLow) ? p.planks : p.floor;
        for (int x = px0; x <= px1; x++) {
            for (int z = 0; z < b.porchD; z++) {
                c.set(x, 0, z, floor);
                c.foundation(x, -1, z, b.foundation == Foundation.STILTS ? B.axis(p.post, Direction.Axis.Y) : p.foundation, DEPTH);
                if (z == 0 && x != b.doorX && x != px0 && x != px1) c.set(x, 1, z, p.fence);
            }
        }
        int h = b.wallH;
        for (int y = 1; y <= h; y++) {
            c.set(px0, y, 0, B.axis(p.post, Direction.Axis.Y));
            c.set(px1, y, 0, B.axis(p.post, Direction.Axis.Y));
        }
        for (int x = px0 - 1; x <= px1 + 1; x++) {
            c.set(x, h + 1, -1, B.stair(p.roof, Direction.SOUTH, false));
            c.set(x, h + 1, 0, B.slab(p.roofSlab, true));
            c.set(x, h + 1, 1, B.slab(p.roofSlab, true));
        }
        // a bench by the wall
        c.set(px0 + 1, 1, b.porchD - 1, B.stair(p.stairs, Direction.SOUTH, false));
        if (px1 - 1 != b.doorX) c.set(px1 - 1, 1, b.porchD - 1, B.stair(p.stairs, Direction.SOUTH, false));
        steps(c, b, p, b.doorX, -1);
    }

    static void balcony(Canvas c, Blueprint b, Palette p, Part main) {
        int y = b.floorH();
        int z = main.z0() - 1;
        int x0 = b.doorX - 1, x1 = b.doorX + 1;
        // a shallow railed deck on brackets in front of a door-window of the upper floor
        for (int x = x0; x <= x1; x++) {
            c.set(x, y, z, B.slab(p.slab, true));
            c.set(x, y + 1, z, p.fence);
            if (x == x0 || x == x1) c.setIfOpen(x, y - 1, z, B.stair(p.stairs, Direction.SOUTH, true));
        }
        c.door(b.doorX, y + 1, main.z0(), p.door, Direction.SOUTH);
    }

    static void outsideStairs(Canvas c, Blueprint b, Palette p) {
        int sx0 = b.stairSide < 0 ? b.bx0 - 2 : b.bx1 + 1;
        int wallX = b.stairSide < 0 ? b.bx0 : b.bx1;
        int fh = b.floorH();
        int zEnd = b.bz1 - 1, zs = zEnd - fh;
        if (zs < b.bz0) return;
        BlockState stair = p.trim, solid = p.wallLow;
        for (int i = 0; i < fh; i++) {
            for (int x = sx0; x <= sx0 + 1; x++) {
                c.set(x, 1 + i, zs + i, B.stair(stair, Direction.SOUTH, false));
                for (int y = 1; y <= i; y++) c.set(x, y, zs + i, solid);
                c.foundation(x, 0, zs + i, p.foundation, DEPTH);
            }
            int rail = b.stairSide < 0 ? sx0 : sx0 + 1;
            c.set(rail, 2 + i, zs + i, p.fence);
        }
        // landing and the upper door
        for (int x = sx0; x <= sx0 + 1; x++) {
            for (int y = 1; y < fh; y++) c.set(x, y, zEnd, solid);
            c.set(x, fh, zEnd, B.slab(p.trimSlab, true));
            c.foundation(x, 0, zEnd, p.foundation, DEPTH);
        }
        c.door(wallX, fh + 1, zEnd, p.door, b.stairSide < 0 ? Direction.EAST : Direction.WEST);
    }

    static void stiltWalk(Canvas c, Blueprint b, Palette p, Part main) {
        // a plank deck along the front on posts
        for (int x = main.x0(); x <= main.x1(); x++) {
            c.setIfOpen(x, 0, main.z0() - 1, p.planks);
            if (x == main.x0() || x == main.x1()) {
                c.foundation(x, -1, main.z0() - 1, B.axis(p.post, Direction.Axis.Y), DEPTH);
                c.setIfOpen(x, 1, main.z0() - 1, p.fence);
            }
        }
    }

    // ------------------------------------------------------------------ exotic bodies

    static Result exotic(Canvas c, Blueprint b) {
        Shells.Frame f = new Shells.Frame();
        f.w = b.w;
        f.d = b.d;
        f.shell = b.shell();
        f.storeys = b.body == Body.TOWER ? Math.max(3, b.storeys) : b.storeys;
        f.wallH = b.wallH;
        f.stilts = b.foundation == Foundation.STILTS && b.body != Body.MUSHROOM && b.body != Body.TREE;
        f.thatch = b.roof == Roof.THATCH || b.wealth == Wealth.POOR;
        f.horns = false;
        f.chimney = false;
        Palette p = b.pal;
        if (b.body == Body.TOWER && b.roof == Roof.SPIRE) {
            p = p.copy();
            p.name = "altmer";
        }
        List<Room> rooms = Shells.build(c, p, f);
        Result res = new Result();
        if (rooms.isEmpty()) return res;
        res.ground.add(rooms.get(0));
        for (int i = 1; i < rooms.size(); i++) res.upper.add(rooms.get(i));
        res.doorX = (b.w - 1) / 2;
        res.doorZ = 0;
        if (b.body == Body.ROUND || b.body == Body.MUSHROOM) {
            int r = b.body == Body.ROUND ? Math.min(b.w, b.d) / 2 : Math.max(2, Math.min(b.w, b.d) / 2 - 1);
            res.doorZ = (b.d - 1) / 2 - r;
        }
        res.mask = new boolean[b.w][b.d];
        return res;
    }

    // ------------------------------------------------------------------ dressing: signs, banners, lights

    /** The wall hanging sign of the same wood as a palette's wall sign. */
    public static BlockState hanging(BlockState wallSign) {
        net.minecraft.resources.ResourceLocation id = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(wallSign.getBlock());
        if (id == null) return Blocks.SPRUCE_WALL_HANGING_SIGN.defaultBlockState();
        return B.id(id.toString().replace("_wall_sign", "_wall_hanging_sign"), Blocks.SPRUCE_WALL_HANGING_SIGN);
    }

    static void dress(Canvas c, Blueprint b, Result res) {
        Palette p = b.pal;
        Spec s = b.spec;
        int dz = res.doorZ - 1;
        if (b.body == Body.TREE) return;
        boolean signed = s.has(Spec.SIGN) || b.use.sign;
        if (signed) {
            Component title = s.name.isEmpty() || s.has(Spec.FOR_SALE) || s.kind == com.skycraft.tamriel.settlement.plan.Archetype.RESERVED
                    ? Component.translatable("settlement.skycraft.kind." + (s.kind == com.skycraft.tamriel.settlement.plan.Archetype.RESERVED && !s.name.isEmpty() ? s.name : b.use.id()))
                    : Component.translatable("settlement.skycraft.name." + s.name);
            // one hanging sign on a bracket beside the door, sticking out from the facade (never flat over the wall)
            int sx = res.doorX + 1;
            if (sx > b.bx1 - 1) sx = res.doorX - 1;
            if (!c.recording() && Canvas.open(c.get(sx, 3, dz))) c.sign(sx, 3, dz, hanging(p.sign), Direction.EAST, Component.empty(), title);
        }
        if (b.banner) {
            BlockState banner = B.face(Materials.banner(b.color), Direction.NORTH);
            int bx = res.doorX - 1;
            if (!c.recording() && Canvas.open(c.get(bx, 3, dz)) && !Canvas.open(c.get(bx, 3, dz + 1))) c.set(bx, 3, dz, banner);
            if (b.storeys > 1 && b.body == Body.RECT) {
                int uy = b.floorH() + 3;
                for (int x : new int[]{b.bx0 + 1, b.bx1 - 1}) {
                    if (!c.recording() && Canvas.open(c.get(x, uy, dz)) && !Canvas.open(c.get(x, uy, dz + 1))) c.set(x, uy, dz, banner);
                }
            }
        }
        if (b.lanterns) {
            BlockState torch = B.face(B.s(b.pal.light.is(Blocks.SOUL_LANTERN) ? Blocks.SOUL_WALL_TORCH : Blocks.WALL_TORCH), Direction.NORTH);
            for (int x : new int[]{res.doorX - 1, res.doorX + 1}) {
                if (!c.recording() && Canvas.open(c.get(x, 2, dz)) && !Canvas.open(c.get(x, 2, dz + 1))) c.set(x, 2, dz, torch);
            }
        }
        if (b.awning && b.body == Body.RECT) {
            BlockState cloth = b.use.trade && b.culture != com.skycraft.tamriel.settlement.Culture.NORD
                    ? Materials.wool(b.color) : null;
            for (int x = res.doorX - 1; x <= res.doorX + 1; x++) {
                if (cloth != null) c.setIfOpen(x, 3, dz, cloth);
                else c.setIfOpen(x, 3, dz, B.stair(p.roof, Direction.SOUTH, false));
            }
        }
    }
}
