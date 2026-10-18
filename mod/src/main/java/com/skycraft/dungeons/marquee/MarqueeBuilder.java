package com.skycraft.dungeons.marquee;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skycraft.dungeons.world.BuiltBuilder;
import com.skycraft.dungeons.world.Deco;
import com.skycraft.dungeons.world.DungeonPiece;
import com.skycraft.dungeons.world.Kit;
import com.skycraft.dungeons.world.Painter;
import com.skycraft.dungeons.world.Room;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Paints one room of a marquee plan. The room is a box (floor layer at the bottom, ceiling layer at the top, walls
 * with 3x3 doorways at the heights the plan gives), its interior carved from a height field the plan's terrain ops
 * shape ({@code flood}, {@code bowl}, {@code spiral}, {@code landing}); then the dressing ops run in order
 * ({@code pillars}, {@code walllights}, {@code veins}, {@code scatter}, {@code blocks}, {@code fill}, {@code mobs},
 * {@code chest}, {@code lectern}, {@code boss}, {@code feature}). Coordinates in the plan are local to the room:
 * {@code u} to the right, {@code v} towards the entrance, {@code y} above the room's floor. Block strings may use
 * {@code @n @s @e @w} (a local direction, as a world direction) and {@code @u @v} (a local axis).
 *
 * <p>Everything is a pure function of the plan and absolute coordinates, so each chunk paints its own part of the
 * room and the pieces agree.</p>
 */
public final class MarqueeBuilder {
    private MarqueeBuilder() {}

    /** Paints a room piece (called by {@link DungeonPiece#postProcess} for {@code Special.MARQUEE} rooms). */
    public static void room(Painter p, DungeonPiece dp) {
        MarqueePlans.Plan plan = MarqueePlans.byHash(dp.param);
        if (plan == null || dp.extra < 0 || dp.extra >= plan.rooms.size()) {
            BuiltBuilder.room(p, new Room(dp), RandomSource.create(dp.seed));
            return;
        }
        new Painted(p, dp, plan, plan.rooms.get(dp.extra)).paint();
    }

    /**
     * Paints a corridor in the plan's masonry: 3 wide, climbing one block per step with stairs, lit by lamps set in
     * its walls. Trapped corridors keep the stock builder (its dart plates and tripwires).
     */
    public static void corridor(Painter p, DungeonPiece c) {
        MarqueePlans.Plan plan = MarqueePlans.byHash(c.param);
        if (plan == null || c.has(DungeonPiece.FLAG_TRAP)) {
            BuiltBuilder.corridor(p, c, RandomSource.create(c.seed));
            return;
        }
        Pal pal = new Pal(plan, p.kit);
        var b = c.getBoundingBox();
        boolean xAxis = c.dir == 0;
        int center = xAxis ? (b.minZ() + b.maxZ()) / 2 : (b.minX() + b.maxX()) / 2;
        int low = xAxis ? b.minX() : b.minZ();
        int len = c.corridorLength();
        Direction plus = xAxis ? Direction.EAST : Direction.SOUTH;
        for (int x = p.minX(); x <= p.maxX(); x++) {
            for (int z = p.minZ(); z <= p.maxZ(); z++) {
                int k = (xAxis ? x : z) - low;
                int lat = (xAxis ? z : x) - center;
                int f = c.corridorFloor(k);
                int fPrev = k > 0 ? c.corridorFloor(k - 1) : f;
                int fNext = k < len - 1 ? c.corridorFloor(k + 1) : f;
                for (int y = p.minY(); y <= p.maxY(); y++) {
                    if (Math.abs(lat) <= 1) {
                        if (y >= f && y <= f + 2) p.air(x, y, z);
                        else if (y == f - 1) {
                            if (fPrev == f - 1) p.set(x, y, z, Deco.stair(pal.stair, plus, false));
                            else if (fNext == f - 1) p.set(x, y, z, Deco.stair(pal.stair, plus.getOpposite(), false));
                            else p.set(x, y, z, pal.floor(p, x, y, z));
                        } else if (y == f + 3) p.set(x, y, z, pal.ceiling(p, x, y, z));
                        else p.set(x, y, z, pal.wall(p, x, y, z, 1));
                    } else {
                        p.set(x, y, z, pal.wall(p, x, y, z, y - f));
                    }
                }
                // a lamp set into the wall every fifth step, alternating sides
                if (k % 5 == 2 && Math.abs(lat) == 2 && ((k / 5) % 2 == 0) == (lat > 0)) {
                    p.set(x, f + 2, z, pal.light);
                }
            }
        }
    }

    // ------------------------------------------------------------------ one room

    private static final class Painted {
        final Painter p;
        final DungeonPiece dp;
        final MarqueePlans.Plan plan;
        final MarqueePlans.Room spec;
        final Pal pal;
        final Direction S, E;
        final int cx, cz, floor, minY, maxY, hu, hv, h;
        /** Doorway present per local side n, s, e, w (indices 0..3). */
        final boolean[] door = new boolean[4];

        // terrain
        @Nullable JsonObject flood, bowl, spiral, landing;
        int sink, margin = 1, top = -1, causeway = 3, tiers, step = 1, bowlWidth = 2;
        boolean floodLanes = true, rails;

        Painted(Painter p, DungeonPiece dp, MarqueePlans.Plan plan, MarqueePlans.Room spec) {
            this.p = p;
            this.dp = dp;
            this.plan = plan;
            this.spec = spec;
            this.pal = new Pal(plan, p.kit);
            this.S = Direction.from2DDataValue(dp.flavor);
            this.E = S.getCounterClockWise();
            var b = dp.getBoundingBox();
            this.cx = (b.minX() + b.maxX()) / 2;
            this.cz = (b.minZ() + b.maxZ()) / 2;
            this.floor = dp.floor;
            this.minY = b.minY();
            this.maxY = b.maxY();
            this.hu = spec.hu;
            this.hv = spec.hv;
            this.h = spec.h;
            String sides = "nsew";
            for (int k = 0; k < 4; k++) {
                Direction d = MarqueeLayout.world(sides.charAt(k), S);
                door[k] = (dp.doors & (1 << d.get2DDataValue())) != 0;
            }
            for (JsonElement e : spec.ops) {
                JsonObject op = e.getAsJsonObject();
                switch (op.get("op").getAsString()) {
                    case "flood" -> {
                        flood = op;
                        sink = i(op, "sink", 2);
                        margin = i(op, "margin", 1);
                        top = i(op, "top", -1);
                        causeway = i(op, "causeway", 3);
                        rails = op.has("rails") && op.get("rails").getAsBoolean();
                        floodLanes = !op.has("mode") || op.get("mode").getAsString().equals("lanes");
                    }
                    case "bowl" -> {
                        bowl = op;
                        tiers = i(op, "tiers", 3);
                        step = i(op, "step", 1);
                        bowlWidth = i(op, "width", 2);
                    }
                    case "spiral" -> spiral = op;
                    case "landing" -> landing = op;
                    default -> {
                    }
                }
            }
        }

        // ---------------------------------------------------------------- local frame

        int wx(int u, int v) {
            return cx + u * E.getStepX() + v * S.getStepX();
        }

        int wz(int u, int v) {
            return cz + u * E.getStepZ() + v * S.getStepZ();
        }

        boolean inside(int u, int v) {
            return Math.abs(u) < hu && Math.abs(v) < hv;
        }

        int inset(int u, int v) {
            return Math.min(hu - Math.abs(u), hv - Math.abs(v));
        }

        boolean hasDoor(char side) {
            return door["nsew".indexOf(side)];
        }

        /** On the causeway: the 3 (or {@code causeway}) wide strips from the middle to every doorway. */
        boolean lane(int u, int v) {
            int w = causeway / 2;
            if (Math.abs(u) <= w && Math.abs(v) <= w) return true;
            if (Math.abs(u) <= w && ((v > 0 && hasDoor('s')) || (v < 0 && hasDoor('n')))) return true;
            return Math.abs(v) <= w && ((u > 0 && hasDoor('e')) || (u < 0 && hasDoor('w')));
        }

        boolean nearDoorway(int u, int v, int r) {
            if (hasDoor('s') && v >= hv - 1 - r && Math.abs(u) <= 1 + r) return true;
            if (hasDoor('n') && v <= -hv + 1 + r && Math.abs(u) <= 1 + r) return true;
            if (hasDoor('e') && u >= hu - 1 - r && Math.abs(v) <= 1 + r) return true;
            return hasDoor('w') && u <= -hu + 1 + r && Math.abs(v) <= 1 + r;
        }

        // ---------------------------------------------------------------- terrain (heights relative to the floor)

        /** Index of a ring cell along the spiral (0 at the start side's middle, clockwise), or -1 off the ring. */
        int ringIndex(int u, int v) {
            int a = hu - 1, b = hv - 1;
            int ins = inset(u, v);
            int width = i(spiral, "width", 2);
            if (ins > width) return -1;
            int pu = u, pv = v;
            if (ins > 1) {
                double du = (double) Math.abs(u) / Math.max(1, a - (ins - 1)), dv = (double) Math.abs(v) / Math.max(1, b - (ins - 1));
                if (du >= dv) pu = (u < 0 ? -a : a);
                else pv = (v < 0 ? -b : b);
                pu = Math.max(-a, Math.min(a, pu));
                pv = Math.max(-b, Math.min(b, pv));
            }
            if (pv == b && pu <= 0) return -pu;
            if (pu == -a) return a + (b - pv);
            if (pv == -b) return a + 2 * b + (pu + a);
            if (pu == a) return 3 * a + 2 * b + (pv + b);
            return 3 * a + 4 * b + (a - pu);
        }

        int ground(int u, int v) {
            int g = 0;
            int ins = inset(u, v);
            if (bowl != null && ins >= 1) g = -Math.min((ins - 1) / bowlWidth, tiers) * step;
            if (landing != null) {
                int y = i(landing, "y", 6), depth = i(landing, "depth", 3), sw = i(landing, "stairs", 3) / 2;
                if (v >= hv - depth && inside(u, v)) g = y;
                else if (Math.abs(u) <= sw && v < hv - depth) {
                    int k = (hv - depth - 1) - v;
                    if (y - 1 - k >= 0) g = y - 1 - k;
                }
            }
            if (spiral != null && inside(u, v)) {
                int idx = ringIndex(u, v);
                if (idx >= 0) {
                    int n = 4 * (hu - 1) + 4 * (hv - 1);
                    int topY = i(spiral, "top", 8);
                    int y = idx >= n - 2 ? topY : Math.max(0, topY - idx);
                    g = y;
                }
            }
            if (wet(u, v)) g -= sink;
            return g;
        }

        boolean wet(int u, int v) {
            if (flood == null || !inside(u, v)) return false;
            int ins = inset(u, v);
            if (ins <= margin) return false;
            if (floodLanes && lane(u, v)) return false;
            if (spiral != null && ins <= i(spiral, "width", 2)) return false;
            if (landing != null && v >= hv - i(landing, "depth", 3)) return false;
            if (bowl != null && (ins - 1) / bowlWidth < tiers) return false;
            return true;
        }

        // ---------------------------------------------------------------- painting

        boolean doorway(int u, int v, int rel) {
            if (hasDoor('s') && v == hv && Math.abs(u) <= 1) return within(rel, spec.doors.getOrDefault('s', 0));
            if (hasDoor('n') && v == -hv && Math.abs(u) <= 1) return within(rel, spec.doors.getOrDefault('n', 0));
            if (hasDoor('e') && u == hu && Math.abs(v) <= 1) return within(rel, spec.doors.getOrDefault('e', 0));
            return hasDoor('w') && u == -hu && Math.abs(v) <= 1 && within(rel, spec.doors.getOrDefault('w', 0));
        }

        private static boolean within(int rel, int base) {
            return rel >= base && rel <= base + 2;
        }

        void paint() {
            // the shell and the carved interior, column by column
            for (int x = p.minX(); x <= p.maxX(); x++) {
                for (int z = p.minZ(); z <= p.maxZ(); z++) {
                    int dx = x - cx, dz = z - cz;
                    int u = dx * E.getStepX() + dz * E.getStepZ();
                    int v = dx * S.getStepX() + dz * S.getStepZ();
                    boolean wall = Math.abs(u) == hu || Math.abs(v) == hv;
                    int g = wall ? 0 : ground(u, v);
                    boolean wet = !wall && wet(u, v);
                    int waterTop = top;
                    for (int y = p.minY(); y <= p.maxY(); y++) {
                        int rel = y - floor;
                        if (y == minY) {
                            p.set(x, y, z, pal.floor(p, x, y, z));
                        } else if (y == maxY) {
                            p.set(x, y, z, pal.ceiling(p, x, y, z));
                        } else if (wall) {
                            if (doorway(u, v, rel)) p.air(x, y, z);
                            else p.set(x, y, z, pal.wall(p, x, y, z, rel));
                        } else if (rel < g) {
                            p.set(x, y, z, rel == g - 1 ? pal.floor(p, x, y, z) : pal.wall(p, x, y, z, 0));
                        } else if (wet && rel <= waterTop) {
                            p.set(x, y, z, Blocks.WATER.defaultBlockState());
                        } else {
                            p.air(x, y, z);
                        }
                    }
                }
            }
            railings();
            for (MarqueePlans.Link l : plan.links) {
                if (spec.id.equals(l.gate)) gate(l);
            }
            for (JsonElement e : spec.ops) dress(e.getAsJsonObject());
            if (spec.role == 0) p.feature("room:start");
        }

        // ---------------------------------------------------------------- railings and gates

        void railings() {
            BlockState rail = pal.rail;
            if (flood != null && rails) {
                int w = causeway / 2;
                for (int u = -hu + 1; u < hu; u++) {
                    for (int v = -hv + 1; v < hv; v++) {
                        if (!lane(u, v)) continue;
                        boolean edge = false;
                        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                            if (wet(u + d[0], v + d[1])) edge = true;
                        }
                        // the rail stands on the causeway's outer cells, never across a doorway lane's centre
                        if (edge && (Math.abs(u) >= w || Math.abs(v) >= w || (Math.abs(u) == w && Math.abs(v) == w))) {
                            if (!nearDoorway(u, v, 0)) put(u, v, 0, rail);
                        }
                    }
                }
            }
            if (landing != null) {
                int y = i(landing, "y", 6), depth = i(landing, "depth", 3), sw = i(landing, "stairs", 3) / 2;
                int v = hv - depth;
                for (int u = -hu + 1; u < hu; u++) {
                    if (Math.abs(u) <= sw) continue;
                    put(u, v, y, rail);
                }
                // the first steps are lit by the landing's lamps
                if (hu > 3) {
                    put(-hu + 2, hv - 1, y, pal.light);
                    put(hu - 2, hv - 1, y, pal.light);
                }
                p.feature("setpiece:vista");
            }
        }

        void gate(MarqueePlans.Link l) {
            MarqueePlans.Room other = plan.rooms.get(plan.byId.get(l.a.equals(spec.id) ? l.b : l.a));
            char side = MarqueeLayout.side(other.i - spec.i, other.j - spec.j);
            Direction d = MarqueeLayout.world(side, S);
            int dh = spec.doors.getOrDefault(side, 0);
            int du = side == 'e' ? hu : side == 'w' ? -hu : 0;
            int dv = side == 's' ? hv : side == 'n' ? -hv : 0;
            int wxx = wx(du, dv), wzz = wz(du, dv);
            Direction inward = d.getOpposite();
            Direction right = d.getClockWise();
            for (int lat = -1; lat <= 1; lat++) {
                for (int dy = 0; dy <= 2; dy++) {
                    if (lat == 0 && dy < 2) continue;
                    p.set(wxx + right.getStepX() * lat, floor + dh + dy, wzz + right.getStepZ() * lat, pal.trim);
                }
            }
            Deco.ironDoor(p, wxx, floor + dh, wzz, inward);
            // the lever on the jamb's room face, and the lamps flanking the door
            int jx = wxx + right.getStepX(), jz = wzz + right.getStepZ();
            p.set(jx + inward.getStepX(), floor + dh + 1, jz + inward.getStepZ(), Deco.wallLever(inward));
            for (int s : new int[]{-2, 2}) {
                for (int dy = 0; dy <= 2; dy += 2) {
                    p.set(wxx + right.getStepX() * s, floor + dh + 1 + dy - (dy > 0 ? 1 : 0), wzz + right.getStepZ() * s, pal.light);
                }
            }
            p.feature("gate:lamp");
        }

        // ---------------------------------------------------------------- dressing

        void put(int u, int v, int rel, BlockState s) {
            p.set(wx(u, v), floor + rel, wz(u, v), s);
        }

        BlockState state(String text) {
            String t = text;
            if (t.equals("light")) return pal.light;
            if (t.equals("dim")) return pal.dim;
            if (t.equals("trim")) return pal.trim;
            if (t.equals("pillar")) return pal.pillar;
            if (t.equals("cap")) return pal.cap;
            if (t.equals("accent")) return pal.accent;
            if (t.equals("rail")) return pal.rail;
            if (t.equals("rock")) return pal.rock;
            if (t.contains("@")) {
                t = t.replace("@n", MarqueeLayout.world('n', S).getName()).replace("@s", MarqueeLayout.world('s', S).getName())
                        .replace("@e", MarqueeLayout.world('e', S).getName()).replace("@w", MarqueeLayout.world('w', S).getName())
                        .replace("@u", E.getAxis().getName()).replace("@v", S.getAxis().getName());
            }
            return Pal.parse(t);
        }

        void dress(JsonObject op) {
            switch (op.get("op").getAsString()) {
                case "pillars" -> pillars(op);
                case "walllights" -> wallLights(op);
                case "veins" -> veins(op);
                case "scatter" -> scatter(op);
                case "blocks" -> {
                    for (JsonElement e : op.getAsJsonArray("list")) {
                        JsonArray c = e.getAsJsonObject().getAsJsonArray("at");
                        int rel = c.size() > 2 ? c.get(2).getAsInt() : 0;
                        put(c.get(0).getAsInt(), c.get(1).getAsInt(), rel, state(e.getAsJsonObject().get("block").getAsString()));
                    }
                }
                case "fill" -> fill(op);
                case "mobs" -> mobs(op);
                case "chest" -> chest(op);
                case "lectern" -> lectern(op);
                case "boss" -> boss(op);
                case "feature" -> p.feature(op.get("id").getAsString());
                default -> {
                }
            }
        }

        void fill(JsonObject op) {
            JsonArray a = op.getAsJsonArray("from"), b = op.getAsJsonArray("to");
            BlockState s = op.has("block") ? state(op.get("block").getAsString()) : Blocks.AIR.defaultBlockState();
            boolean hollow = op.has("hollow") && op.get("hollow").getAsBoolean();
            int u0 = Math.min(a.get(0).getAsInt(), b.get(0).getAsInt()), u1 = Math.max(a.get(0).getAsInt(), b.get(0).getAsInt());
            int v0 = Math.min(a.get(1).getAsInt(), b.get(1).getAsInt()), v1 = Math.max(a.get(1).getAsInt(), b.get(1).getAsInt());
            int y0 = Math.min(a.get(2).getAsInt(), b.get(2).getAsInt()), y1 = Math.max(a.get(2).getAsInt(), b.get(2).getAsInt());
            for (int u = u0; u <= u1; u++) {
                for (int v = v0; v <= v1; v++) {
                    for (int y = y0; y <= y1; y++) {
                        if (hollow && u > u0 && u < u1 && v > v0 && v < v1 && y > y0 && y < y1) continue;
                        if (s.isAir()) p.air(wx(u, v), floor + y, wz(u, v));
                        else put(u, v, y, s);
                    }
                }
            }
        }

        void pillars(JsonObject op) {
            int inset = i(op, "inset", 2), every = Math.max(2, i(op, "every", 4));
            boolean ring = !op.has("ring") || op.get("ring").getAsBoolean();
            boolean capLight = op.has("light") && op.get("light").getAsBoolean();
            String blockKey = op.has("block") ? op.get("block").getAsString() : "pillar";
            int topRel = h - 1;
            // symmetric positions: the middle, every n-th block out from it, and the far edge
            java.util.TreeSet<Integer> us = new java.util.TreeSet<>(), vs = new java.util.TreeSet<>();
            for (int k = 0; k <= hu - inset; k += every) {
                us.add(k);
                us.add(-k);
            }
            us.add(hu - inset);
            us.add(-(hu - inset));
            for (int k = 0; k <= hv - inset; k += every) {
                vs.add(k);
                vs.add(-k);
            }
            vs.add(hv - inset);
            vs.add(-(hv - inset));
            for (int u : us) {
                for (int v : vs) {
                    boolean onRing = Math.abs(u) == hu - inset || Math.abs(v) == hv - inset;
                    if (ring && !onRing) continue;
                    if (!inside(u, v) || lane(u, v) && !(op.has("lanes") && op.get("lanes").getAsBoolean())) continue;
                    if (nearDoorway(u, v, 1)) continue;
                    int g = ground(u, v);
                    for (int y = g; y <= topRel; y++) {
                        BlockState s = blockKey.equals("pillar") ? (y == g || y == topRel ? pal.cap : pal.pillar) : state(blockKey);
                        if (capLight && y == topRel - 1) s = pal.light;
                        put(u, v, y, s);
                    }
                }
            }
        }

        void wallLights(JsonObject op) {
            int every = Math.max(3, i(op, "every", 5)), y = i(op, "y", 3);
            BlockState s = state(op.has("block") ? op.get("block").getAsString() : "light");
            for (int u = -hu + 1; u < hu; u++) {
                for (int v : new int[]{-hv, hv}) {
                    if (Math.abs(u) <= 1 && hasDoor(v > 0 ? 's' : 'n')) continue;
                    if (Math.floorMod(u + hu, every) == 0) put(u, v, y, s);
                }
            }
            for (int v = -hv + 1; v < hv; v++) {
                for (int u : new int[]{-hu, hu}) {
                    if (Math.abs(v) <= 1 && hasDoor(u > 0 ? 'e' : 'w')) continue;
                    if (Math.floorMod(v + hv, every) == 0) put(u, v, y, s);
                }
            }
        }

        void veins(JsonObject op) {
            BlockState s = state(op.has("block") ? op.get("block").getAsString() : "light");
            int chance = Math.max(2, i(op, "chance", 6)), minLen = i(op, "min", 3), maxLen = Math.max(minLen, i(op, "max", 8));
            int lowest = i(op, "from", 1);
            for (int a = -Math.max(hu, hv) + 1; a < Math.max(hu, hv); a++) {
                for (int side = 0; side < 4; side++) {
                    int u, v;
                    switch (side) {
                        case 0 -> { u = a; v = -hv; }
                        case 1 -> { u = a; v = hv; }
                        case 2 -> { u = -hu; v = a; }
                        default -> { u = hu; v = a; }
                    }
                    if (Math.abs(u) > hu || Math.abs(v) > hv) continue;
                    if (side < 2 && Math.abs(u) >= hu || side >= 2 && Math.abs(v) >= hv) continue;
                    int wxx = wx(u, v), wzz = wz(u, v);
                    if (p.roll(wxx, 0, wzz, 441, chance) != 0) continue;
                    if (Math.abs(a) <= 1 && (side == 0 && hasDoor('n') || side == 1 && hasDoor('s') || side == 2 && hasDoor('w') || side == 3 && hasDoor('e'))) continue;
                    int len = minLen + p.roll(wxx, 1, wzz, 442, maxLen - minLen + 1);
                    int start = lowest + p.roll(wxx, 2, wzz, 443, Math.max(1, h - len - lowest - 1));
                    for (int y = start; y < start + len && y < h - 1; y++) {
                        // veins taper: every other block of the ends is stone
                        if ((y == start || y == start + len - 1) && p.chance(wxx, y, wzz, 444, 2)) continue;
                        put(u, v, y, s);
                    }
                }
            }
        }

        void scatter(JsonObject op) {
            BlockState s = state(op.get("block").getAsString());
            int chance = Math.max(2, i(op, "chance", 12));
            String on = op.has("on") ? op.get("on").getAsString() : "floor";
            boolean wetOk = op.has("wet") && op.get("wet").getAsBoolean();
            int salt = op.has("salt") ? op.get("salt").getAsInt() : 460;
            for (int u = -hu + 1; u < hu; u++) {
                for (int v = -hv + 1; v < hv; v++) {
                    int wxx = wx(u, v), wzz = wz(u, v);
                    if (p.roll(wxx, floor, wzz, salt, chance) != 0) continue;
                    boolean wet = wet(u, v);
                    if (wet && !wetOk) continue;
                    if (on.equals("ceiling")) {
                        put(u, v, h - 1, s);
                    } else if (on.equals("floor")) {
                        if (nearDoorway(u, v, 1) || (flood != null && lane(u, v) && !op.has("lanes"))) continue;
                        put(u, v, ground(u, v), s);
                    } else if (on.equals("water")) {
                        if (wet) put(u, v, top + 1, s);
                    }
                }
            }
        }

        // ---------------------------------------------------------------- inhabitants and furniture

        @Nullable
        String resolve(String ids) {
            if (ids.equals("kit")) return (plan.kit == Kit.NONE ? Kit.NORD : plan.kit).minion(1 + (dp.extra % 5)).id();
            return Painter.firstExisting(ids.split("\\|"));
        }

        int[] spot(int idx, int salt, String where) {
            int ru = Math.max(1, hu - 2), rv = Math.max(1, hv - 2);
            for (int k = 0; k < 80; k++) {
                int u = p.roll(cx + k * 7, salt + idx, cz, 471, 2 * ru + 1) - ru;
                int v = p.roll(cx - k * 5, salt + idx, cz, 472, 2 * rv + 1) - rv;
                if (!inside(u, v) || inset(u, v) < 2 || nearDoorway(u, v, 2)) continue;
                if (wet(u, v)) continue;
                boolean onLane = flood != null && floodLanes && lane(u, v);
                switch (where) {
                    case "lane" -> {
                        if (!onLane && !(flood == null && lane(u, v))) continue;
                    }
                    case "wall" -> {
                        if (inset(u, v) > 3 || onLane) continue;
                    }
                    case "ledge" -> {
                        if (ground(u, v) != 0 || inset(u, v) > 3) continue;
                    }
                    case "center" -> {
                        if (inset(u, v) < 3) continue;
                    }
                    default -> {
                    }
                }
                if (spiral != null && ringIndex(u, v) >= 0) continue;
                return new int[]{u, v};
            }
            return null;
        }

        void mobs(JsonObject op) {
            int salt = 500;
            int entry = 0;
            for (JsonElement e : op.getAsJsonArray("list")) {
                JsonObject m = e.getAsJsonObject();
                entry++;
                String id = resolve(m.has("id") ? m.get("id").getAsString() : "kit");
                if (id == null) continue;
                String mode = m.has("mode") ? m.get("mode").getAsString() : "awake";
                String name = m.has("name") ? m.get("name").getAsString() : null;
                String where = m.has("where") ? m.get("where").getAsString() : "any";
                int n = i(m, "n", 1);
                List<int[]> at = new ArrayList<>();
                if (m.has("at")) {
                    for (JsonElement c : m.getAsJsonArray("at")) {
                        JsonArray ca = c.getAsJsonArray();
                        at.add(new int[]{ca.get(0).getAsInt(), ca.get(1).getAsInt()});
                    }
                } else {
                    for (int k = 0; k < n; k++) {
                        int[] s = spot(entry * 11 + k, salt + entry * 31, where);
                        if (s != null) at.add(s);
                    }
                }
                for (int[] c : at) {
                    int y = floor + ground(c[0], c[1]);
                    int x = wx(c[0], c[1]), z = wz(c[0], c[1]);
                    float yaw = (float) (Math.toDegrees(Math.atan2(-(x - cx), (z - cz))) + 180.0);
                    String label = name != null ? name : null;
                    switch (mode) {
                        case "sleep", "ambush" -> p.spawnDormant(id, x, y, z, yaw);
                        case "elite" -> p.spawnElite(id, x, y, z, yaw, label);
                        default -> p.spawn(id, x, y, z, yaw, label);
                    }
                    if (mode.equals("ambush") || mode.equals("sleep")) p.feature("beat:ambush");
                    if (mode.equals("elite")) p.feature("beat:elite");
                }
            }
        }

        void chest(JsonObject op) {
            JsonArray c = op.getAsJsonArray("at");
            int u = c.get(0).getAsInt(), v = c.get(1).getAsInt();
            int rel = c.size() > 2 ? c.get(2).getAsInt() : ground(u, v);
            Direction face = facing(u, v, op);
            Identifier table = Identifier.parse(op.get("table").getAsString());
            p.container(wx(u, v), floor + rel, wz(u, v), op.has("barrel") && op.get("barrel").getAsBoolean() ? Deco.barrel(face) : Deco.chest(face), table);
            if (op.has("capstone") && op.get("capstone").getAsBoolean()) p.feature("capstone:relic");
            else p.feature("reward:cache");
        }

        void lectern(JsonObject op) {
            JsonArray c = op.getAsJsonArray("at");
            int u = c.get(0).getAsInt(), v = c.get(1).getAsInt();
            int rel = c.size() > 2 ? c.get(2).getAsInt() : ground(u, v);
            Direction face = facing(u, v, op);
            MarqueePlans.Book book = plan.books.get(op.get("book").getAsString());
            int x = wx(u, v), z = wz(u, v), y = floor + rel;
            p.set(x, y, z, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, face).setValue(LecternBlock.HAS_BOOK, book != null));
            if (book != null && p.blockEntity(x, y, z) instanceof LecternBlockEntity lectern) lectern.setBook(book(book));
            // a candle by the book so it can be found in the dark
            Direction side = face.getClockWise();
            int cxx = x + side.getStepX(), czz = z + side.getStepZ();
            if (op.has("candle") && op.get("candle").getAsBoolean()) {
                p.set(cxx, y, czz, Deco.candle(Blocks.CANDLE, 3, true));
            }
            p.feature("story:journal");
        }

        void boss(JsonObject op) {
            JsonArray c = op.getAsJsonArray("at");
            int u = c.get(0).getAsInt(), v = c.get(1).getAsInt();
            int dais = i(op, "dais", 0);
            if (dais > 0) {
                for (int du = -dais; du <= dais; du++) {
                    for (int dv = -dais; dv <= dais; dv++) {
                        boolean edge = Math.abs(du) == dais || Math.abs(dv) == dais;
                        put(u + du, v + dv, ground(u, v) - 1 + 1, edge ? pal.trim : pal.cap);
                    }
                }
            }
            MarqueePlans.Boss b = plan.boss;
            if (b == null) return;
            String id = Painter.firstExisting(b.id().split("\\|"));
            if (id == null) id = "minecraft:wither_skeleton";
            int y = floor + ground(u, v) + (dais > 0 ? 1 : 0);
            int x = wx(u, v), z = wz(u, v);
            float yaw = Direction.from2DDataValue(dp.flavor).toYRot();
            p.spawnBoss(id, x, y, z, yaw, b.name());
            p.feature("setpiece:boss_dais");
        }

        Direction facing(int u, int v, JsonObject op) {
            if (op.has("face")) return MarqueeLayout.world(op.get("face").getAsString().charAt(0), S);
            // towards the room's middle
            if (Math.abs(u) * hv >= Math.abs(v) * hu) return MarqueeLayout.world(u > 0 ? 'w' : 'e', S);
            return MarqueeLayout.world(v > 0 ? 'n' : 's', S);
        }
    }

    // ------------------------------------------------------------------ helpers

    static int i(@Nullable JsonObject o, String key, int def) {
        return o != null && o.has(key) ? o.get(key).getAsInt() : def;
    }

    static ItemStack book(MarqueePlans.Book b) {
        return com.skycraft.worldgen.AuthoredBooks.create(b.title(), b.author(), b.pages().stream().map(p -> (Component) Component.literal(p)).toList());
    }

}
