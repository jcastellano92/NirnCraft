package com.skycraft.dungeons.world;

import com.skycraft.dungeons.world.cavern.CavernLibrary;
import com.skycraft.dungeons.world.cavern.CavernProfile;
import com.skycraft.dungeons.world.cavern.CavernProfile.FluidKind;
import com.skycraft.dungeons.world.cavern.CavernProfile.Pal;
import com.skycraft.dungeons.world.cavern.CavernSelector;
import com.skycraft.dungeons.world.cavern.CavernShape;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * The terrain and structures of one grand cavern: a pure function of position and the piece's seed (so every chunk
 * agrees), shaped by a {@link CavernShape} and dressed from a {@link CavernProfile}. See {@link GrandCavern} for the
 * contract; {@link CavernDress} adds flora, signature features, set pieces and people on top.
 *
 * <p>Every shape keeps the same guarantees: the door lanes and the cross of walkways are forced to the doors' level,
 * dry ground is a 1-Lipschitz surface (no step above one block) rooted in them, and fluids sit in basins whose rim is
 * never lower than their surface.</p>
 */
final class CavernGen {
    static final int HUMP_H = 4;
    static final int PLATFORM_R = 3;
    /** The bowl (or chasm belt) ends at this fraction of the cavern's radius. */
    static final double BOWL_EDGE = 0.62;

    final Painter p;
    final Room room;
    final CavernProfile prof;
    final CavernShape shape;
    final CavernLibrary.ShapeDef def;
    final int cx, cz, floor, height, top;
    final double ex, ez;
    /** The piece's long axis is x. */
    final boolean longX;
    final double aspect, meander, meanderFreq, meanderPhase;
    final boolean chasm, lake, lava;
    final FluidKind fkind;
    final int lakeTop, deepDepth;
    final double deepR;
    final boolean armX, armZ;
    final int humpCount;
    final double[] humpTheta = new double[2];
    final double wfTheta;
    final boolean waterfall;
    final double campTheta;
    final double setTheta;
    final int[][] pillars;
    /** Pillar profile: 0 plain rock column, 1 flared trunk. */
    final int pillarStyle;
    /** Extra walkways at the doors' level: {x0, x1, z0, z1}, inclusive. */
    final int[][] causeways;
    /** Islands (floating-islands shape): {x, z, radius}. */
    final int[][] islands;
    /** River centre line: offset (blocks, minor axis) and half width. */
    final double riverOff, riverAmp, riverHalf, riverPhase;
    /** Sea cave: direction of the fall (radians). */
    final double seaDir;
    final double hexSize;

    CavernGen(Painter p, Room room, CavernSelector.Choice choice) {
        this.p = p;
        this.room = room;
        this.prof = choice.profile();
        this.shape = choice.shape();
        this.def = CavernLibrary.get().shape(shape);
        this.cx = room.cx;
        this.cz = room.cz;
        this.floor = room.floor;
        this.height = room.height;
        this.top = room.maxY;
        this.ex = room.halfX() - 1.5;
        this.ez = room.halfZ() - 1.5;
        long h = p.hash(cx, 1, cz, 1100);
        this.longX = ex > ez + 1 || (Math.abs(ex - ez) <= 1 && ((h >>> 3) & 1) == 0);
        double eMinor = longX ? ez : ex;
        double eLong = longX ? ex : ez;

        // ---- shape scalars
        double asp = 1.0, mea = 0.0;
        boolean armLong = true;
        switch (shape) {
            case CANYON -> {
                asp = prm("aspect", 1.5, 1.7, 1);
                mea = prm("meander", 0, 4, 2);
                armLong = false;
            }
            case CREVASSE -> {
                asp = prm("aspect", 1.6, 1.7, 1);
                mea = prm("meander", 3, 6, 2);
                armLong = false;
            }
            case LAVA_TUBE -> {
                asp = prm("aspect", 1.55, 1.7, 1);
                mea = prm("meander", 0, 3, 2);
                armLong = false;
            }
            default -> {
            }
        }
        this.aspect = asp;
        this.meander = mea;
        this.meanderFreq = prm("meanderFreq", 2.6, 4.2, 3);
        this.meanderPhase = prm("meanderPhase", 0, 6.28, 4);
        this.armX = (longX ? armLong : true) || room.hasDoor(Direction.EAST) || room.hasDoor(Direction.WEST);
        this.armZ = (longX ? true : armLong) || room.hasDoor(Direction.NORTH) || room.hasDoor(Direction.SOUTH);

        // ---- fluid
        FluidKind fk = prof.fluid.kind();
        double u = ((h >>> 9) & 0x3FF) / 1023.0;
        boolean wantLake = fk != FluidKind.NONE && u < prof.fluid.chance();
        if (shape == CavernShape.SEA_CAVE || shape == CavernShape.LAVA_RIVER) wantLake = fk != FluidKind.NONE;
        this.fkind = fk;
        this.lake = wantLake;
        this.lava = wantLake && fk == FluidKind.LAVA;
        boolean wantsChasm = switch (shape) {
            case CANYON -> ((h >>> 5) & 3) != 0;
            case SHAFT, CREVASSE, FLOATING_ISLANDS -> true;
            case BOWL_BRIDGES -> ((h >>> 5) & 3) < 2;
            default -> false;
        };
        // never over lava, where a fall would be the end
        this.chasm = wantsChasm && !lava;
        this.deepDepth = switch (shape) {
            case CANYON -> 12;
            case SHAFT, CREVASSE -> 15;
            default -> 14;
        };
        this.deepR = switch (shape) {
            case SHAFT -> 0.52;
            case CREVASSE -> 0.46;
            case FLOATING_ISLANDS -> 0.60;
            default -> 0.40;
        };
        this.lakeTop = switch (shape) {
            case BOWL_BRIDGES, FLOATING_ISLANDS -> chasm ? floor - 10 : floor - 3;
            case CANYON -> chasm ? floor - 9 : floor - 3;
            case SHAFT, CREVASSE -> chasm ? floor - 12 : floor - 3;
            case AMPHITHEATRE -> floor - 6;
            case PILLAR_MAZE, LAVA_TUBE, HONEYCOMB, CATHEDRAL -> floor - 1;
            case ROOT_CAVERN, BONE_CAVERN -> floor - 2;
            default -> floor - 3;
        };

        // ---- galleries, the fall, the camp, the set piece: diagonal sectors
        int rot = (int) ((h >>> 12) & 3);
        this.humpCount = 1 + (int) ((h >>> 14) & 1);
        humpTheta[0] = Math.toRadians(45 + 90 * rot);
        humpTheta[1] = Math.toRadians(45 + 90 * (rot + 2));
        this.wfTheta = Math.toRadians(45 + 90 * (rot + 1));
        this.setTheta = Math.toRadians(45 + 90 * (rot + 1));
        this.waterfall = lake && fk == FluidKind.WATER && switch (shape) {
            case BOWL_BRIDGES, CANYON, AMPHITHEATRE, KARST, ROOT_CAVERN, SHAFT -> true;
            default -> false;
        };
        this.campTheta = Math.toRadians(45 + 90 * (rot + 3));

        // ---- river and sea
        this.riverOff = (((h >>> 20) & 1) == 0 ? 1 : -1) * 0.32 * eMinor;
        this.riverAmp = prm("meander", 1.0, 3.0, 5);
        this.riverHalf = prm("width", 3.2, 4.4, 6);
        this.riverPhase = prm("meanderPhase", 0, 6.28, 7);
        this.seaDir = Math.toRadians(45 + 90 * ((h >>> 22) & 3) + p.roll(cx, 8, cz, 1103, 30) - 15);
        this.hexSize = prm("cell", 5.5, 7.0, 8);

        // ---- columns, islands and causeways
        List<int[]> cw = new ArrayList<>();
        List<int[]> isl = new ArrayList<>();
        buildCauseways(cw, isl, eLong, eMinor);
        this.causeways = cw.toArray(new int[0][]);
        this.islands = isl.toArray(new int[0][]);
        this.pillarStyle = shape == CavernShape.ROOT_CAVERN ? 1 : 0;
        this.pillars = buildPillars(eLong, eMinor);
    }

    /** A shape parameter rolled once per cavern from its range. */
    double prm(String key, double lo, double hi, int salt) {
        double[] r = def.param(key, lo, hi, prof);
        double t = (p.hash(cx, salt, cz, 1500 + salt) & 0xFFFF) / 65535.0;
        return r[0] + (r[1] - r[0]) * t;
    }

    // ------------------------------------------------------------------ layout of columns, islands and causeways

    private void buildCauseways(List<int[]> cw, List<int[]> isl, double eLong, double eMinor) {
        switch (shape) {
            case LAVA_RIVER -> {
                for (int s = -1; s <= 1; s += 2) {
                    double a = s * 0.5 * eLong;
                    int ax = (int) Math.round(a);
                    double zc = riverOff;
                    int lo = (int) Math.floor(zc - riverHalf - 7), hi = (int) Math.ceil(zc + riverHalf + 7);
                    cw.add(longX ? new int[]{cx + ax - 1, cx + ax + 1, cz + lo, cz + hi} : new int[]{cx + lo, cx + hi, cz + ax - 1, cz + ax + 1});
                }
            }
            case CANYON, CREVASSE -> {
                if (!chasm && shape == CavernShape.CANYON) break;
                int ax = (int) Math.round((((p.hash(cx, 3, cz, 1104) >>> 4) & 1) == 0 ? 0.48 : -0.48) * eLong);
                int span = (int) Math.round(0.62 * eMinor / aspect);
                cw.add(longX ? new int[]{cx + ax - 1, cx + ax + 1, cz - span, cz + span} : new int[]{cx - span, cx + span, cz + ax - 1, cz + ax + 1});
            }
            case FLOATING_ISLANDS -> {
                int n = 4 + (int) ((p.hash(cx, 4, cz, 1105) >>> 6) & 1);
                for (int i = 0; i < n; i++) {
                    double ang = Math.toRadians(360.0 / n * i + 22 + p.roll(cx, i, cz, 1106, 20));
                    double rad = 0.34 + 0.12 * (p.roll(cx, i, cz, 1107, 100) / 100.0);
                    int ix = cx + (int) Math.round(Math.cos(ang) * rad * ex), iz = cz + (int) Math.round(Math.sin(ang) * rad * ez);
                    if (Math.min(Math.abs(ix - cx), Math.abs(iz - cz)) < 6) continue;
                    int ir = 3 + p.roll(ix, i, iz, 1108, 2);
                    isl.add(new int[]{ix, iz, ir});
                    // a spur to the nearer arm: along x to the z-arm or along z to the x-arm
                    if (Math.abs(ix - cx) <= Math.abs(iz - cz) * ez / ex + 1) {
                        cw.add(new int[]{Math.min(ix, cx), Math.max(ix, cx), iz - 1, iz + 1});
                    } else {
                        cw.add(new int[]{ix - 1, ix + 1, Math.min(iz, cz), Math.max(iz, cz)});
                    }
                }
            }
            default -> {
            }
        }
    }

    private int[][] buildPillars(double eLong, double eMinor) {
        List<int[]> out = new ArrayList<>();
        switch (shape) {
            case BOWL_BRIDGES, HONEYCOMB, FLOATING_ISLANDS -> {
                int n = shape == CavernShape.HONEYCOMB ? 3 : 4;
                for (int i = 0; i < 4 && i < n + 1; i++) {
                    double ang = Math.toRadians(45 + 90 * i + p.roll(cx, i, cz, 1101, 37) - 18);
                    double rad = (i % 2 == 0 ? 0.50 : 0.76) * Math.min(ex, ez);
                    int px = cx + (int) Math.round(Math.cos(ang) * rad), pz = cz + (int) Math.round(Math.sin(ang) * rad);
                    tryPillar(out, px, pz, 2 + p.roll(px, i, pz, 1102, 2));
                }
            }
            case CANYON, CREVASSE -> {
                double[] as = {-0.62, -0.32, 0.32, 0.62};
                for (int i = 0; i < as.length; i++) {
                    for (int s = -1; s <= 1; s += 2) {
                        if ((i + (s > 0 ? 1 : 0)) % 2 != 0 && shape == CavernShape.CREVASSE) continue;
                        double b = s * 0.74 / aspect;
                        placeAB(out, as[i] + 0.04 * (p.roll(cx, i, cz, 1109 + s, 3) - 1), b, 2 + p.roll(cx, i, cz, 1110 + s, 2), eLong, eMinor);
                    }
                }
            }
            case SHAFT -> {
                for (int i = 0; i < 6; i++) {
                    double ang = Math.toRadians(30 + 60 * i + p.roll(cx, i, cz, 1111, 16) - 8);
                    int px = cx + (int) Math.round(Math.cos(ang) * 0.78 * ex), pz = cz + (int) Math.round(Math.sin(ang) * 0.78 * ez);
                    tryPillar(out, px, pz, 2 + p.roll(px, i, pz, 1112, 2));
                }
            }
            case AMPHITHEATRE -> {
                for (int i = 0; i < 8; i++) {
                    double ang = Math.toRadians(22.5 + 45 * i);
                    int px = cx + (int) Math.round(Math.cos(ang) * 0.74 * ex), pz = cz + (int) Math.round(Math.sin(ang) * 0.74 * ez);
                    tryPillar(out, px, pz, 1 + p.roll(px, i, pz, 1113, 2));
                }
            }
            case PILLAR_MAZE -> {
                int pitch = 6;
                for (int gx = -4; gx <= 4; gx++) {
                    for (int gz = -4; gz <= 4; gz++) {
                        int px = cx + gx * pitch + 3 + p.roll(gx, 1, gz, 1114, 3) - 1, pz = cz + gz * pitch + 3 + p.roll(gx, 2, gz, 1115, 3) - 1;
                        if (p.roll(gx, 3, gz, 1116, 5) == 0) continue;
                        tryPillar(out, px, pz, 1 + p.roll(gx, 4, gz, 1117, 2));
                    }
                }
            }
            case LAVA_RIVER -> {
                double[] as = {-0.58, -0.26, 0.26, 0.58};
                for (int i = 0; i < as.length; i++) {
                    double b = -Math.signum(riverOff) * 0.62;
                    placeAB(out, as[i], b, 2 + p.roll(cx, i, cz, 1118, 2), eLong, eMinor);
                }
            }
            case ROOT_CAVERN -> {
                for (int i = 0; i < 8; i++) {
                    double ang = Math.toRadians(40 * i + p.roll(cx, i, cz, 1119, 30));
                    double rad = (0.34 + 0.40 * p.roll(cx, i, cz, 1120, 100) / 100.0);
                    int px = cx + (int) Math.round(Math.cos(ang) * rad * ex), pz = cz + (int) Math.round(Math.sin(ang) * rad * ez);
                    tryPillar(out, px, pz, 2 + p.roll(px, i, pz, 1121, 3));
                }
            }
            case LAVA_TUBE -> {
                double[] as = {-0.55, -0.28, 0.28, 0.55};
                for (int i = 0; i < as.length; i++) {
                    placeAB(out, as[i], (i % 2 == 0 ? 0.40 : -0.40) / aspect * 1.4, 1 + p.roll(cx, i, cz, 1122, 2), eLong, eMinor);
                }
            }
            case CATHEDRAL -> {
                int pitch = (int) prm("pitch", 6, 7, 9);
                for (int k = -6; k <= 6; k++) {
                    int a = k * pitch;
                    if (Math.abs(a) < 8 || Math.abs(a) > 0.72 * eLong) continue;
                    for (int s = -1; s <= 1; s += 2) {
                        int b = s * (int) Math.round(0.40 * eMinor);
                        int px = cx + (longX ? a : b), pz = cz + (longX ? b : a);
                        tryPillar(out, px, pz, 2);
                    }
                }
            }
            case KARST -> {
                int n = 12;
                for (int i = 0; i < n; i++) {
                    double ang = Math.toRadians(360.0 / n * i + p.roll(cx, i, cz, 1123, 25));
                    double rad = 0.22 + 0.58 * p.roll(cx, i, cz, 1124, 100) / 100.0;
                    int px = cx + (int) Math.round(Math.cos(ang) * rad * ex), pz = cz + (int) Math.round(Math.sin(ang) * rad * ez);
                    int pr = i == 3 ? 4 : 1 + p.roll(px, i, pz, 1125, 3);
                    tryPillar(out, px, pz, pr);
                }
            }
            case SEA_CAVE -> {
                double cs = Math.cos(seaDir), sn = Math.sin(seaDir);
                for (int i = 0; i < 7; i++) {
                    double along = 0.32 + 0.50 * p.roll(cx, i, cz, 1126, 100) / 100.0;
                    double side = (p.roll(cx, i, cz, 1127, 100) - 50) / 100.0 * 1.3;
                    int px = cx + (int) Math.round((cs * along - sn * side) * ex), pz = cz + (int) Math.round((sn * along + cs * side) * ez);
                    tryPillar(out, px, pz, 1 + p.roll(px, i, pz, 1128, 3));
                }
            }
            default -> {
            }
        }
        return out.toArray(new int[0][]);
    }

    private void placeAB(List<int[]> out, double aN, double bN, int pr, double eLong, double eMinor) {
        int a = (int) Math.round(aN * eLong), b = (int) Math.round(bN * eMinor);
        tryPillar(out, cx + (longX ? a : b), cz + (longX ? b : a), pr);
    }

    private void tryPillar(List<int[]> out, int px, int pz, int pr) {
        if (px <= room.minX + 3 || px >= room.maxX - 3 || pz <= room.minZ + 3 || pz >= room.maxZ - 3) return;
        if (rrb(px, pz) > 0.80) return;
        if (Math.min(Math.abs(px - cx), Math.abs(pz - cz)) < 4 + 1.3 * pr) return;
        for (int[] c : causeways) {
            int dx = Math.max(Math.max(c[0] - px, 0), px - c[1]), dz = Math.max(Math.max(c[2] - pz, 0), pz - c[3]);
            if (Math.max(dx, dz) < pr + 3) return;
        }
        for (int[] q : islands) {
            if ((px - q[0]) * (px - q[0]) + (pz - q[1]) * (pz - q[1]) < (q[2] + pr + 3) * (q[2] + pr + 3)) return;
        }
        for (int[] q : out) {
            int dx = px - q[0], dz = pz - q[1];
            int m = q[2] + pr + 3;
            if (dx * dx + dz * dz < m * m) return;
        }
        out.add(new int[]{px, pz, pr});
    }

    // ------------------------------------------------------------------ shape (pure functions of x, z)

    /** The normalised position along the long axis and across it, in units of the half spans, before narrowing. */
    private double along(int x, int z) {
        return longX ? (x - cx) / ex : (z - cz) / ez;
    }

    private double across(int x, int z) {
        double b = longX ? (z - cz) / ez : (x - cx) / ex;
        if (meander != 0) b -= meander * Math.sin(along(x, z) * meanderFreq + meanderPhase) / (longX ? ez : ex);
        return b * aspect;
    }

    double rr(int x, int z) {
        double a = along(x, z), b = across(x, z);
        return Math.sqrt(a * a + b * b) + 0.20 * p.noise(x, 0, z, 10.0, 1201) + 0.09 * p.noise(x, 0, z, 4.5, 1202);
    }

    double rrb(int x, int z) {
        double a = along(x, z), b = across(x, z);
        return Math.sqrt(a * a + b * b) + 0.06 * p.noise(x, 0, z, 8.0, 1203);
    }

    /** Within {@code w} of a doorway's lane (from the middle arms out to the wall). */
    boolean lane(int x, int z, int w) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!room.hasDoor(d)) continue;
            if (d.getAxis() == Direction.Axis.X) {
                int along = d == Direction.EAST ? x - cx : cx - x;
                if (Math.abs(z - cz) <= w && along >= 0.30 * ex) return true;
            } else {
                int along = d == Direction.SOUTH ? z - cz : cz - z;
                if (Math.abs(x - cx) <= w && along >= 0.30 * ez) return true;
            }
        }
        return false;
    }

    private boolean inCauseway(int x, int z) {
        for (int[] c : causeways) if (x >= c[0] && x <= c[1] && z >= c[2] && z <= c[3]) return true;
        return false;
    }

    /** The cross of walkways through the middle (bridges over the bowl), 5 wide, and the extra causeways. */
    boolean cross(int x, int z) {
        if (armZ && Math.abs(x - cx) <= 2 && Math.abs(z - cz) <= 0.72 * ez) return true;
        if (armX && Math.abs(z - cz) <= 2 && Math.abs(x - cx) <= 0.72 * ex) return true;
        return inCauseway(x, z);
    }

    boolean inside(int x, int z) {
        if (x < room.minX || x > room.maxX || z < room.minZ || z > room.maxZ) return false;
        // the doorway lanes run through the wall layer itself, to meet the corridors
        if (lane(x, z, 1)) return true;
        if (x == room.minX || x == room.maxX || z == room.minZ || z == room.maxZ) return false;
        if (lane(x, z, 2) || cross(x, z) || onPlatform(x, z)) return true;
        return rr(x, z) < 0.88;
    }

    int rimWobble(int x, int z, double r) {
        if (r < 0.62) return 0;
        return Mth.clamp((int) Math.round(1.6 * (p.noise(x, 0, z, 7.0, 1204) + 0.35)), 0, 2);
    }

    int hump(int x, int z, double r) {
        if (r < 0.66) return 0;
        double dx = x - cx, dz = z - cz;
        double rad = Math.sqrt(dx * dx + dz * dz);
        double phi = Math.atan2(dz, dx);
        int best = 0;
        for (int k = 0; k < humpCount; k++) {
            double da = Math.abs(phi - humpTheta[k]);
            if (da > Math.PI) da = 2 * Math.PI - da;
            double v = (3.0 + HUMP_H / 0.75 - da * rad) * 0.75;
            best = Math.max(best, (int) Math.round(Mth.clamp(v, 0, HUMP_H)));
        }
        return best;
    }

    private final HashMap<Long, Integer> baseMemo = new HashMap<>();
    private final HashMap<Long, Integer> floorMemo = new HashMap<>();
    private final HashMap<Long, Integer> coneMemo = new HashMap<>();
    private final HashMap<Long, Integer> moundMemo = new HashMap<>();

    private static long posKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    /**
     * Natural walking level of a column (before bridges and the platform). Dry ground never steps more than one block:
     * each column is lifted to the highest neighbour within three blocks minus the distance (the smallest 1-Lipschitz
     * surface above the terraces). Chasms keep their cliffs.
     */
    int floorAt(int x, int z) {
        long k = posKey(x, z);
        Integer c = coneMemo.get(k);
        if (c != null) return c;
        int f;
        if (forced(x, z) || chasm && baseFloor(x, z) < floor - 3) {
            f = baseFloor(x, z);
        } else {
            f = Integer.MIN_VALUE;
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -(3 - Math.abs(dx)); dz <= 3 - Math.abs(dx); dz++) {
                    f = Math.max(f, withMounds(x + dx, z + dz) - Math.abs(dx) - Math.abs(dz));
                }
            }
        }
        coneMemo.put(k, f);
        return f;
    }

    /** Terrain plus the stalagmite mounds, before the last smoothing. */
    private int withMounds(int x, int z) {
        long k = posKey(x, z);
        Integer c = moundMemo.get(k);
        if (c != null) return c;
        int f;
        if (forced(x, z)) {
            f = baseFloor(x, z);
        } else {
            f = terrainAt(x, z) + moundAt(x, z);
            // everything above the rim level eases down to it beside a walkway, so no walkway is ever a trench
            if (f > floor) f = floor + (int) Math.round((f - floor) * Mth.clamp(walkDist(x, z) / 8.0, 0, 1));
        }
        moundMemo.put(k, f);
        return f;
    }

    /** Blocks from (x, z) to the nearest walkway band (door lanes, the cross, causeways), 0 on it. */
    double walkDist(int x, int z) {
        int dx = Math.abs(x - cx), dz = Math.abs(z - cz);
        double best = 99;
        if (armX) best = Math.min(best, Math.max(dz - 2, Math.max(0, dx - 0.66 * ex)));
        if (armZ) best = Math.min(best, Math.max(dx - 2, Math.max(0, dz - 0.66 * ez)));
        for (int[] c : causeways) {
            int ddx = Math.max(Math.max(c[0] - x, 0), x - c[1]), ddz = Math.max(Math.max(c[2] - z, 0), z - c[3]);
            best = Math.min(best, Math.max(ddx, ddz));
        }
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (!room.hasDoor(d)) continue;
            boolean xAxis = d.getAxis() == Direction.Axis.X;
            int lat = xAxis ? dz : dx;
            int along = xAxis ? (d == Direction.EAST ? x - cx : cx - x) : (d == Direction.SOUTH ? z - cz : cz - z);
            double start = 0.5 * (xAxis ? ex : ez);
            best = Math.min(best, Math.max(lat - 2, Math.max(0, start - along)));
        }
        return best;
    }

    /** The walkways (door lanes, the cross) keep their level exactly: corridors and bridges meet them there. */
    boolean forced(int x, int z) {
        return lane(x, z, 2) || cross(x, z) && rrb(x, z) >= BOWL_EDGE;
    }

    /**
     * Stalagmites are mounds of the floor, steepest one block per step like everything else underfoot, so a crevice
     * between two of them is never a pit: pyramids (4 to 7 blocks across) rooted on a coarse grid.
     */
    int moundAt(int x, int z) {
        if (shape == CavernShape.COLLAPSED_CITY || shape == CavernShape.HONEYCOMB || shape == CavernShape.CATHEDRAL) return 0;
        int best = 0;
        int gx = Math.floorDiv(x, 6), gz = Math.floorDiv(z, 6);
        for (int a = gx - 1; a <= gx + 1; a++) {
            for (int b = gz - 1; b <= gz + 1; b++) {
                long h = p.hash(a, 41, b, 1335);
                if (Math.floorMod(h, 4L) != 0) continue;
                int rx = a * 6 + (int) Math.floorMod(h >>> 8, 6L), rz = b * 6 + (int) Math.floorMod(h >>> 16, 6L);
                int hgt = 2 + (int) Math.floorMod(h >>> 24, 3L);
                int d = Math.abs(x - rx) + Math.abs(z - rz);
                if (d >= hgt || !coneRoot(rx, rz)) continue;
                best = Math.max(best, hgt - d);
            }
        }
        return best;
    }

    private boolean coneRoot(int x, int z) {
        if (!inside(x, z) || lane(x, z, 3) || cross(x, z) || onPlatform(x, z) || pillarAt(x, z, 3) || blocked(x, z) || rr(x, z) > 0.8) return false;
        for (int[] q : islands) if ((x - q[0]) * (x - q[0]) + (z - q[1]) * (z - q[1]) <= (q[2] + 3) * (q[2] + 3)) return false;
        int t = terrainAt(x, z);
        return !(lake && t <= lakeTop) && (!chasm || t >= floor);
    }

    /** The ground before stalagmites: terraces, ring, galleries, 1-Lipschitz in a bowl. */
    int terrainAt(int x, int z) {
        int base = baseFloor(x, z);
        if (forced(x, z) || (chasm && base < floor - 3)) return base;
        long k = posKey(x, z);
        Integer c = floorMemo.get(k);
        if (c != null) return c;
        int best = base;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -(3 - Math.abs(dx)); dz <= 3 - Math.abs(dx); dz++) {
                if (dx == 0 && dz == 0) continue;
                int nb = baseFloor(x + dx, z + dz);
                if (chasm && nb < floor - 3) continue;
                int f = nb - Math.abs(dx) - Math.abs(dz);
                if (f > best) best = f;
            }
        }
        floorMemo.put(k, best);
        return best;
    }

    private int baseFloor(int x, int z) {
        long k = posKey(x, z);
        Integer c = baseMemo.get(k);
        if (c != null) return c;
        int f = baseFloorRaw(x, z);
        baseMemo.put(k, f);
        return f;
    }

    private int baseFloorRaw(int x, int z) {
        if (lane(x, z, 2)) return floor;
        double r = rrb(x, z);
        int core = coreFloor(x, z, r);
        if (core != Integer.MIN_VALUE) return core;
        if (cross(x, z) && r < 0.95) return floor;
        return floor + rimWobble(x, z, r) + hump(x, z, r) + rimLift(r);
    }

    /** Extra rise towards the walls of a round pipe. */
    private int rimLift(double r) {
        if (shape != CavernShape.LAVA_TUBE || r < 0.5) return 0;
        double t = Mth.clamp((r - 0.5) / 0.38, 0, 1);
        return (int) Math.round(4.0 * t * t);
    }

    /** A bowl profile: terraces from {@code edge} inwards over {@code span}, {@code steps} deep at most. */
    private int bowl(double r, double edge, double span, int steps) {
        double t = Mth.clamp((edge - r) / span, 0, 1);
        int step = (int) Math.floor(t * (steps - 0.01));
        return step > 0 ? floor - step : Integer.MIN_VALUE;
    }

    /** Where the shape sinks below the rim level (or MIN when the ring rules apply). */
    private int coreFloor(int x, int z, double r) {
        if (chasm && (shape == CavernShape.BOWL_BRIDGES || shape == CavernShape.CANYON || shape == CavernShape.SHAFT
                || shape == CavernShape.CREVASSE || shape == CavernShape.FLOATING_ISLANDS)) {
            if (r < deepR) return floor - deepDepth + (p.noise(x, 0, z, 3.0, 1210) > 0.2 ? 1 : 0);
            return Integer.MIN_VALUE;
        }
        switch (shape) {
            case BOWL_BRIDGES, CANYON, SHAFT, CREVASSE, FLOATING_ISLANDS -> {
                return bowl(r, BOWL_EDGE, 0.52, 7);
            }
            case AMPHITHEATRE -> {
                return bowl(r, 0.68, 0.62, 9);
            }
            case PILLAR_MAZE -> {
                if (r >= 0.82) return Integer.MIN_VALUE;
                return floor - 1 + (p.noise(x, 0, z, 6.0, 1211) > 0.32 ? 1 : 0);
            }
            case LAVA_RIVER -> {
                if (r >= 0.84) return Integer.MIN_VALUE;
                double d = Math.abs(minorBlocks(x, z) - riverCentre(x, z));
                return d < riverHalf + 0.6 * p.noise(x, 0, z, 5.0, 1212) ? floor - 5 : Integer.MIN_VALUE;
            }
            case LAVA_TUBE -> {
                double w = Math.abs(across(x, z));
                return w < 0.13 ? floor - 2 : Integer.MIN_VALUE;
            }
            case HONEYCOMB -> {
                if (r >= 0.80) return Integer.MIN_VALUE;
                int[] hx = hexAt(x, z);
                return Math.floorMod(hx[0] - hx[1], 3) == 0 ? floor - 1 : Integer.MIN_VALUE;
            }
            case COLLAPSED_CITY -> {
                if (r >= 0.80) return Integer.MIN_VALUE;
                return street(x, z) ? floor - 3 : floor - 1;
            }
            case KARST -> {
                if (r >= 0.80) return Integer.MIN_VALUE;
                double n = p.noise(x, 0, z, 9.0, 1213);
                int f = floor - (int) Math.round(1.6 * (n * 0.5 + 0.5));
                if (p.noise(x, 0, z, 7.0, 1214) > 0.60) f = floor - 4;
                return f == floor ? Integer.MIN_VALUE : f;
            }
            case ROOT_CAVERN -> {
                if (r >= 0.80) return Integer.MIN_VALUE;
                int f = floor - (int) Math.round(2.2 * (p.noise(x, 0, z, 8.0, 1215) * 0.5 + 0.5));
                return f == floor ? Integer.MIN_VALUE : f;
            }
            case SEA_CAVE -> {
                if (r >= 0.84) return Integer.MIN_VALUE;
                double s = seaProj(x, z);
                int depth = (int) Math.round(8 * Mth.clamp((s - 0.05) / 0.55, 0, 1));
                return depth > 0 ? floor - depth : Integer.MIN_VALUE;
            }
            default -> {
                return Integer.MIN_VALUE;
            }
        }
    }

    /** Position across the long axis in blocks (centre line = 0). */
    private double minorBlocks(int x, int z) {
        return longX ? z - cz : x - cx;
    }

    private double riverCentre(int x, int z) {
        double s = longX ? x - cx : z - cz;
        return riverOff + riverAmp * Math.sin(s * 0.13 + riverPhase);
    }

    /** Projection on the sea direction, in units of the half span along it (-1..1). */
    private double seaProj(int x, int z) {
        double cs = Math.cos(seaDir), sn = Math.sin(seaDir);
        double proj = (x - cx) * cs + (z - cz) * sn;
        double len = Math.abs(cs) * ex + Math.abs(sn) * ez;
        return proj / len;
    }

    // ---- city and hive lattices

    static final int CITY_PITCH = 13;

    /** True on the sunken streets: the middle street coincides with the cross. */
    boolean street(int x, int z) {
        return Math.floorMod(x - cx + 1, CITY_PITCH) <= 2 || Math.floorMod(z - cz + 1, CITY_PITCH) <= 2;
    }

    /** Height of the ruined wall on a column of the city (0 = none). */
    int cityWall(int x, int z) {
        if (shape != CavernShape.COLLAPSED_CITY) return 0;
        int lx = Math.floorMod(x - cx + 1, CITY_PITCH), lz = Math.floorMod(z - cz + 1, CITY_PITCH);
        if (lx < 3 || lz < 3) return 0;
        boolean wx = lx == 3 || lx == CITY_PITCH - 1, wz = lz == 3 || lz == CITY_PITCH - 1;
        if (!wx && !wz) return 0;
        // doorway in the middle of each side
        if (wz && !wx && lx >= 7 && lx <= 9) return 0;
        if (wx && !wz && lz >= 7 && lz <= 9) return 0;
        int bx = Math.floorDiv(x - cx + 1, CITY_PITCH), bz = Math.floorDiv(z - cz + 1, CITY_PITCH);
        int side = wx ? (lx == 3 ? 0 : 1) : (lz == 3 ? 2 : 3);
        long hh = p.hash(bx, side, bz, 1340);
        if (Math.floorMod(hh, 4L) == 0) return 0;
        int hgt = 1 + (int) Math.floorMod(hh >>> 8, 4L);
        // broken teeth along the top
        long tooth = p.hash(x, 7, z, 1341);
        if (Math.floorMod(tooth, 4L) == 0) hgt = Math.max(0, hgt - 1 - (int) Math.floorMod(tooth >>> 8, 2L));
        return hgt;
    }

    private static final double SQ3 = Math.sqrt(3.0);

    /** Axial coordinates of the hive cell holding (x, z). */
    int[] hexAt(int x, int z) {
        double px = x - cx, pz = z - cz;
        double q = (SQ3 / 3 * px - pz / 3) / hexSize, r = (2.0 / 3 * pz) / hexSize, s = -q - r;
        long rq = Math.round(q), rr = Math.round(r), rs = Math.round(s);
        double dq = Math.abs(rq - q), dr = Math.abs(rr - r), ds = Math.abs(rs - s);
        if (dq > dr && dq > ds) rq = -rr - rs;
        else if (dr > ds) rr = -rq - rs;
        return new int[]{(int) rq, (int) rr};
    }

    /** Height of the cell wall on a column of the hive (0 = none; every wall has a gap in its middle). */
    int hexWall(int x, int z) {
        if (shape != CavernShape.HONEYCOMB) return 0;
        double px = x - cx, pz = z - cz;
        int[] c = hexAt(x, z);
        double c1x = hexSize * SQ3 * (c[0] + c[1] / 2.0), c1z = hexSize * 1.5 * c[1];
        double d1 = (px - c1x) * (px - c1x) + (pz - c1z) * (pz - c1z);
        double best = Double.MAX_VALUE, c2x = 0, c2z = 0;
        int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, -1}, {-1, 1}};
        int bq = 0, br = 0;
        for (int[] n : nb) {
            int q = c[0] + n[0], r = c[1] + n[1];
            double nx = hexSize * SQ3 * (q + r / 2.0), nz = hexSize * 1.5 * r;
            double d = (px - nx) * (px - nx) + (pz - nz) * (pz - nz);
            if (d < best) {
                best = d;
                c2x = nx;
                c2z = nz;
                bq = q;
                br = r;
            }
        }
        double len = Math.sqrt((c2x - c1x) * (c2x - c1x) + (c2z - c1z) * (c2z - c1z));
        double bd = (best - d1) / (2 * len);
        if (bd > 0.8) return 0;
        // position along the edge from its middle: the doorway
        double mx = (c1x + c2x) / 2, mz = (c1z + c2z) / 2;
        double ux = -(c2z - c1z) / len, uz = (c2x - c1x) / len;
        double t = (px - mx) * ux + (pz - mz) * uz;
        if (Math.abs(t) < 1.6) return 0;
        int lo = Math.min(c[0] * 7 + c[1], bq * 7 + br), hi = Math.max(c[0] * 7 + c[1], bq * 7 + br);
        long hh = p.hash(lo, 9, hi, 1342);
        return 2 + (int) Math.floorMod(hh, 3L);
    }

    boolean blocked(int x, int z) {
        return switch (shape) {
            case COLLAPSED_CITY -> cityWall(x, z) > 0;
            case HONEYCOMB -> hexWall(x, z) > 0;
            case SHAFT -> CavernDress.rampLevel(this, x, z) != Integer.MIN_VALUE;
            default -> false;
        };
    }

    /** The first solid block above the cavern at a column. */
    int ceilAt(int x, int z, int f) {
        double r = Math.min(1.0, rrb(x, z));
        double n = p.noise(x, 0, z, 9.0, 1205);
        double c;
        switch (shape) {
            case CATHEDRAL -> c = height * (1 - 0.28 * r * r) - 1.5 + 0.9 * n;
            case SHAFT -> c = height * (1 - 0.30 * r * r) - 1.5 + 1.2 * n;
            case PILLAR_MAZE -> c = height * (0.66 - 0.22 * r * r) - 1.0 + 1.4 * n;
            case CANYON, CREVASSE -> c = height * (1 - 0.86 * r * r) - 1.5 + 2.2 * n;
            case LAVA_TUBE -> {
                double w = Math.min(1.0, Math.abs(across(x, z)) / 0.88);
                c = height * (0.34 + 0.62 * Math.sqrt(Math.max(0, 1 - w * w))) - 1.5 + 0.8 * n;
            }
            case HONEYCOMB -> c = height * (0.80 - 0.40 * r * r) - 1.0 + 1.8 * n;
            case COLLAPSED_CITY -> c = height * (0.95 - 0.55 * r * r) - 1.5 + 1.5 * n;
            case BONE_CAVERN -> c = height * (0.92 - 0.45 * r * r) - 1.5 + 1.2 * n;
            default -> c = height * (1 - 0.74 * r * r) - 1.5 + 2.2 * n;
        }
        int ci = floor + (int) Math.round(c);
        ci = Math.max(ci, f + 6);
        int cap = top - 1;
        if (p.ok(x, floor, z)) cap = Math.min(cap, Math.max(f + 5, p.surfaceAt(x, z) - 2));
        return Math.min(ci, cap);
    }

    boolean onPlatform(int x, int z) {
        int dx = x - cx, dz = z - cz;
        return dx * dx + dz * dz <= PLATFORM_R * PLATFORM_R + 1;
    }

    /** The span of a bridge (the cross's arms over the bowl or chasm, and the causeways). */
    boolean bridge(int x, int z) {
        if (inCauseway(x, z)) return true;
        return (cross(x, z) || lane(x, z, 2)) && (rrb(x, z) < 0.72 || terrainAt(x, z) < floor);
    }

    boolean pillarAt(int x, int z, int margin) {
        for (int[] pl : pillars) {
            int dx = x - pl[0], dz = z - pl[1];
            int r = pl[2] + 1 + margin;
            if (dx * dx + dz * dz <= r * r) return true;
        }
        return false;
    }

    boolean onIsland(int x, int z, int margin) {
        for (int[] q : islands) {
            int r = q[2] + margin;
            if ((x - q[0]) * (x - q[0]) + (z - q[1]) * (z - q[1]) <= r * r) return true;
        }
        return false;
    }

    /** Walking level of a column including bridges, the platform and islands, or MIN if it is water, lava or a column. */
    int walk(int x, int z) {
        int f = floorAt(x, z);
        if (onPlatform(x, z) || (bridge(x, z) && f < floor) || onIsland(x, z, 0)) return floor;
        if (lake && f <= lakeTop) return Integer.MIN_VALUE;
        return f;
    }

    /** Where a column's surface is free to dress: dry, off the walkways and the platform, with headroom. */
    boolean free(int x, int z) {
        if (!inside(x, z) || lane(x, z, 2) || cross(x, z) || onPlatform(x, z) || pillarAt(x, z, 0) || blocked(x, z)) return false;
        int f = floorAt(x, z);
        if (onIsland(x, z, 0)) return ceilAt(x, z, floor) - floor >= 5;
        if (lake && f <= lakeTop) return false;
        if (chasm && f < floor - 3) return false;
        return ceilAt(x, z, f) - f >= 5;
    }

    // ------------------------------------------------------------------ palettes

    BlockState rock(int y) {
        return NaturalBuilder.rock(y);
    }

    private BlockState pick(Pal pal, int x, int y, int z, int salt, BlockState fallback) {
        if (pal.isEmpty()) return fallback;
        BlockState s = pal.pick(p.roll(x, y, z, salt, pal.total()));
        return s != null ? s : rock(y);
    }

    BlockState floorBlock(int x, int y, int z) {
        if (!prof.patch.isEmpty() && p.noise(x, 0, z, 5.0, 1220) > prof.patchAt) {
            return pick(prof.patch, x, y, z, 1222, rock(y));
        }
        boolean terrace = shape == CavernShape.AMPHITHEATRE && floorAt(x, z) < floor && p.roll(x, y, z, 1223, 3) != 0;
        return pick(terrace ? prof.terrace : prof.floor, x, y, z, 1221, rock(y));
    }

    /** A patch of the cavern's own rock for walls and roof. */
    BlockState wallBlock(int x, int y, int z) {
        return pick(prof.wall, x, y, z, 1231, rock(y));
    }

    BlockState spike(int x, int y, int z) {
        return pick(prof.spike, x, y, z, 1241, rock(y));
    }

    BlockState pillarBlock(int x, int y, int z) {
        return pick(prof.pillar, x, y, z, 1243, spike(x, y, z));
    }

    BlockState platformBlock(int x, int z) {
        return pick(prof.platform, x, 0, z, 1290, Blocks.STONE_BRICKS.defaultBlockState());
    }

    BlockState lantern() {
        BlockState s = prof.lights.post().first();
        return s != null ? s : Deco.lantern(false, false);
    }

    // ------------------------------------------------------------------ painting

    void run() {
        int x0 = p.minX(), x1 = p.maxX(), z0 = p.minZ(), z1 = p.maxZ();
        int w = x1 - x0 + 3, d = z1 - z0 + 3;
        int[] fl = new int[w * d], ce = new int[w * d];
        for (int ix = 0; ix < w; ix++) {
            for (int iz = 0; iz < d; iz++) {
                int x = x0 - 1 + ix, z = z0 - 1 + iz;
                int i = ix * d + iz;
                if (inside(x, z)) {
                    fl[i] = floorAt(x, z);
                    ce[i] = ceilAt(x, z, fl[i]);
                } else {
                    fl[i] = Integer.MIN_VALUE;
                    ce[i] = Integer.MIN_VALUE;
                }
            }
        }
        carve(x0, x1, z0, z1, d, fl, ce);
        walls(x0, x1, z0, z1, d, fl, ce);
        water(x0, x1, z0, z1, d, fl);
        structures(x0, x1, z0, z1);
        CavernDress.dress(this, x0, x1, z0, z1);
        CavernDress.populate(this);
    }

    private int idx(int x, int z, int x0, int z0, int d) {
        return (x - x0 + 1) * d + (z - z0 + 1);
    }

    void carve(int x0, int x1, int z0, int z1, int d, int[] fl, int[] ce) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int i = idx(x, z, x0, z0, d);
                int f = fl[i];
                if (f == Integer.MIN_VALUE) continue;
                int c = ce[i];
                int nb = Integer.MIN_VALUE;
                boolean edge = false;
                for (int k = 0; k < 4; k++) {
                    int j = idx(x + (k == 0 ? 1 : k == 1 ? -1 : 0), z + (k == 2 ? 1 : k == 3 ? -1 : 0), x0, z0, d);
                    if (fl[j] == Integer.MIN_VALUE) edge = true;
                    else nb = Math.max(nb, fl[j]);
                }
                int yEnd = Math.min(c - 1, p.maxY());
                for (int y = Math.max(f, p.minY()); y <= yEnd; y++) {
                    p.carve(x, y, z, rock(y));
                }
                if (p.ok(x, f - 1, z)) {
                    // a stalagmite's mound shows the spike's own rock
                    boolean mound = f > terrainAt(x, z) && !forced(x, z);
                    p.set(x, f - 1, z, mound ? spike(x, f - 1, z) : floorBlock(x, f - 1, z));
                    for (int k = 2; mound && k <= f - terrainAt(x, z); k++) p.setIfOpen(x, f - k, z, spike(x, f - k, z));
                }
                if (p.ok(x, f - 2, z) && Painter.open(p.get(x, f - 2, z))) p.set(x, f - 2, z, rock(f - 2));
                if (p.ok(x, c, z)) {
                    // the roof: its own rock in patches, sealed where it met open ground or water
                    BlockState above = p.get(x, c, z);
                    if (Painter.open(above) || !above.getFluidState().isEmpty() || p.noise(x, c, z, 4.0, 1250) > 0.2) {
                        p.set(x, c, z, roofBlock(x, c, z));
                    }
                    if (p.ok(x, c + 1, z)) {
                        BlockState above2 = p.get(x, c + 1, z);
                        if (Painter.open(above2) || !above2.getFluidState().isEmpty()) {
                            p.set(x, c + 1, z, roofBlock(x, c + 1, z));
                        }
                    }
                }
            }
        }
    }

    /** Roof blocks: the wall palette, with veins. */
    BlockState roofBlock(int x, int y, int z) {
        BlockState v = CavernDress.vein(this, x, y, z);
        return v != null ? v : wallBlock(x, y, z);
    }

    /** Wall patches of the cavern's rock, and a seal where water meets open ground. */
    void walls(int x0, int x1, int z0, int z1, int d, int[] fl, int[] ce) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int i = idx(x, z, x0, z0, d);
                if (fl[i] != Integer.MIN_VALUE || x <= room.minX || x >= room.maxX || z <= room.minZ || z >= room.maxZ) continue;
                int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
                for (int k = 0; k < 4; k++) {
                    int j = idx(x + (k == 0 ? 1 : k == 1 ? -1 : 0), z + (k == 2 ? 1 : k == 3 ? -1 : 0), x0, z0, d);
                    if (fl[j] == Integer.MIN_VALUE) continue;
                    lo = Math.min(lo, fl[j]);
                    hi = Math.max(hi, ce[j]);
                }
                if (hi == Integer.MIN_VALUE) continue;
                for (int y = Math.max(lo - 1, p.minY()); y < hi && y <= p.maxY(); y++) {
                    BlockState s = p.get(x, y, z);
                    boolean wet = lake && y <= lakeTop + 1;
                    if (wet && Painter.open(s)) {
                        p.set(x, y, z, rock(y));
                    } else if (s.is(BlockTags.BASE_STONE_OVERWORLD)) {
                        BlockState v = CavernDress.vein(this, x, y, z);
                        if (v != null) p.set(x, y, z, v);
                        else if (p.noise(x, y, z, 3.5, 1260) > 0.12) p.set(x, y, z, wallBlock(x, y, z));
                    }
                }
            }
        }
    }

    void water(int x0, int x1, int z0, int z1, int d, int[] fl) {
        if (!lake) return;
        BlockState fluid = (lava ? Blocks.LAVA : Blocks.WATER).defaultBlockState();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                int f = fl[idx(x, z, x0, z0, d)];
                if (f == Integer.MIN_VALUE || f > lakeTop) continue;
                for (int y = Math.max(f, p.minY()); y <= lakeTop && y <= p.maxY(); y++) {
                    BlockState s = fluid;
                    if (fkind == FluidKind.POOL) {
                        s = pick(prof.fluid.block(), x, y, z, 1274, Blocks.MUD.defaultBlockState());
                    } else if (y == lakeTop && fkind == FluidKind.ICE) {
                        s = pick(prof.fluid.cap(), x, y, z, 1275, Blocks.PACKED_ICE.defaultBlockState());
                    }
                    p.set(x, y, z, s);
                }
            }
        }
        CavernDress.lakeBed(this, x0, x1, z0, z1, d, fl);
    }

    /** Bridges, the platform and islands, columns, ladders, the waterfall and the shape's own structures. */
    void structures(int x0, int x1, int z0, int z1) {
        // bridges: five wide, thick at the abutments, thin in the middle, with a low wall each side
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!bridge(x, z) || onPlatform(x, z)) continue;
                int f = floorAt(x, z);
                if (f >= floor) continue;
                boolean rect = inCauseway(x, z) && !(armZ && Math.abs(x - cx) <= 2 && Math.abs(z - cz) <= 0.72 * ez)
                        && !(armX && Math.abs(z - cz) <= 2 && Math.abs(x - cx) <= 0.72 * ex);
                int thick, lat;
                if (rect) {
                    int[] c = null;
                    for (int[] q : causeways) if (x >= q[0] && x <= q[1] && z >= q[2] && z <= q[3]) c = q;
                    boolean alongX = c[1] - c[0] >= c[3] - c[2];
                    int endDist = alongX ? Math.min(x - c[0], c[1] - x) : Math.min(z - c[2], c[3] - z);
                    thick = 1 + (endDist < 3 ? 1 : 0);
                    lat = alongX ? Math.min(z - c[2], c[3] - z) : Math.min(x - c[0], c[1] - x);
                    lat = lat == 0 ? 2 : 0;
                } else {
                    double along = Math.abs(x - cx) <= 2 ? Math.abs(z - cz) : Math.abs(x - cx);
                    double reach = Math.max(1.0, 0.72 * (Math.abs(x - cx) <= 2 ? ez : ex));
                    thick = 1 + (int) Math.round(2.0 * Math.pow(Mth.clamp(along / reach, 0, 1), 2));
                    lat = Math.abs(x - cx) <= 2 && Math.abs(z - cz) > 2 ? Math.abs(x - cx) : Math.abs(z - cz);
                }
                for (int y = Math.max(floor - thick, f); y < floor; y++) {
                    p.set(x, y, z, y == floor - 1 ? floorBlock(x, y, z) : rock(y));
                }
                for (int y = floor; y <= floor + 4; y++) p.air(x, y, z);
                if (lat == 2 && !lane(x, z, 1)) {
                    boolean dropSide = false;
                    for (Direction dir : Direction.Plane.HORIZONTAL) {
                        int nx = x + dir.getStepX(), nz = z + dir.getStepZ();
                        if (!bridge(nx, nz) && !onPlatform(nx, nz) && !lane(nx, nz, 2) && floorAt(nx, nz) < floor - 1) {
                            dropSide = true;
                            break;
                        }
                    }
                    if (dropSide) p.set(x, floor, z, Blocks.COBBLESTONE_WALL.defaultBlockState());
                }
            }
        }
        // the platform and the islands: piers from the bottom up to the rim level
        pier(cx, cz, PLATFORM_R, true);
        for (int[] q : islands) pier(q[0], q[1], q[2], false);
        // giant columns, wide where they meet floor and roof
        for (int[] pl : pillars) column(pl);
        // ladders up the chasm's cliff, so a fall (into the lake) is never the end
        if (chasm) ladders();
        if (waterfall) CavernDress.waterfall(this);
        switch (shape) {
            case SHAFT -> CavernDress.spiral(this);
            case COLLAPSED_CITY -> CavernDress.cityWalls(this, x0, x1, z0, z1);
            case HONEYCOMB -> CavernDress.hexWalls(this, x0, x1, z0, z1);
            case CATHEDRAL, BONE_CAVERN -> CavernDress.ribs(this);
            case KARST -> CavernDress.lightShaft(this);
            default -> {
            }
        }
        if (shape == CavernShape.BONE_CAVERN) CavernDress.skull(this);
    }

    private void pier(int ox, int oz, int radius, boolean platform) {
        for (int x = ox - radius - 5; x <= ox + radius + 5; x++) {
            for (int z = oz - radius - 5; z <= oz + radius + 5; z++) {
                if (!p.ok(x, floor, z)) continue;
                double dd = Math.sqrt((x - ox) * (double) (x - ox) + (z - oz) * (double) (z - oz));
                int f = floorAt(x, z);
                for (int y = Math.max(f, p.minY()); y < floor; y++) {
                    double taper = radius + 0.5 + (floor - 1 - y) * 0.16;
                    if (dd <= taper) p.set(x, y, z, y == floor - 1 ? platformBlock(x, z) : rock(y));
                }
                if (dd <= radius + 0.5) {
                    p.set(x, floor - 1, z, platformBlock(x, z));
                    for (int y = floor; y <= floor + 4; y++) p.air(x, y, z);
                }
            }
        }
    }

    private void column(int[] pl) {
        for (int x = pl[0] - 8; x <= pl[0] + 8; x++) {
            for (int z = pl[1] - 8; z <= pl[1] + 8; z++) {
                if (!inside(x, z)) continue;
                if (x < p.minX() || x > p.maxX() || z < p.minZ() || z > p.maxZ()) continue;
                double dd = Math.sqrt((x - pl[0]) * (double) (x - pl[0]) + (z - pl[1]) * (double) (z - pl[1]));
                int f = floorAt(x, z), c = ceilAt(x, z, f);
                int yMax = Math.min(c, p.maxY());
                for (int y = Math.max(f, p.minY()); y <= yMax; y++) {
                    double t = c > f ? (double) (y - f) / (c - f) : 0;
                    double rad;
                    if (pillarStyle == 1) {
                        // a trunk: buttress roots at the foot, a narrow bole, a crown spreading into the roof
                        double foot = t < 0.25 ? (0.25 - t) / 0.25 : 0;
                        double crown = t > 0.65 ? (t - 0.65) / 0.35 : 0;
                        double ang = Math.atan2(z - pl[1], x - pl[0]);
                        double root = 0.6 + 0.5 * Math.sin(ang * 3 + pl[0]) ;
                        rad = pl[2] * (0.78 + 0.5 * foot * (1 + root) + 1.1 * crown * crown) + 0.35 * p.noise(x, y, z, 3.0, 1280);
                    } else {
                        // a plain shaft at the foot (no skirt to make a pit), waisted in the middle, flaring into the roof
                        double flare = t > 0.6 ? (t - 0.6) / 0.4 : 0;
                        rad = pl[2] * (1.0 - 0.25 * Math.sin(Math.PI * Math.min(1.0, t / 0.6))) + 0.9 * pl[2] * flare * flare
                                + 0.4 * p.noise(x, y, z, 3.0, 1280);
                    }
                    if (dd <= rad) p.set(x, y, z, pillarBlock(x, y, z));
                }
            }
        }
    }

    private void ladders() {
        for (int k = 0; k < 3; k++) {
            double ang = Math.toRadians(45 + 90 * k);
            int dxs, dzs;
            if (Math.abs(Math.cos(ang)) < Math.abs(Math.sin(ang))) {
                dxs = 0;
                dzs = (int) Math.signum(Math.sin(ang));
            } else {
                dxs = (int) Math.signum(Math.cos(ang));
                dzs = 0;
            }
            Direction toWall = dxs > 0 ? Direction.EAST : dxs < 0 ? Direction.WEST : dzs > 0 ? Direction.SOUTH : Direction.NORTH;
            int x = cx + (int) Math.round(Math.cos(ang) * 0.25 * ex), z = cz + (int) Math.round(Math.sin(ang) * 0.25 * ez);
            int px = x, pz = z;
            for (int s = 0; s < 40 && floorAt(x, z) < floor - 2; s++) {
                px = x;
                pz = z;
                x += dxs;
                z += dzs;
            }
            if (floorAt(x, z) < floor - 2 || !inside(px, pz)) continue;
            BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, toWall.getOpposite());
            for (int y = floorAt(px, pz); y < floor; y++) {
                p.set(px, y, pz, ladder.setValue(LadderBlock.WATERLOGGED, lake && y <= lakeTop));
            }
        }
    }
}
