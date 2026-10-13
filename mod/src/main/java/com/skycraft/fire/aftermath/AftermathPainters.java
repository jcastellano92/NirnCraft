package com.skycraft.fire.aftermath;

import com.skycraft.fire.FireBlocks;
import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.SettlementPlan;
import com.skycraft.tamriel.settlement.build.B;
import com.skycraft.tamriel.settlement.build.Canvas;
import com.skycraft.tamriel.settlement.build.Props;
import com.skycraft.tamriel.settlement.plan.Spec;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The set pieces of a town that has just been attacked, as settlement painters, each one a scene with a reason: a
 * triage camp of real tents with the wounded lying inside and healers tending them, rows of covered dead with mourners
 * kneeling beside them, graves with headstones, a small shrine with candles where people pray, a funeral pyre, rubble
 * fields with people clearing them, a gate post with the soldiers who hold it, the surviving merchant's cart, craters,
 * scattered belongings. A city plan names them as {@code {"kind": "reserved", "name": "helgen_triage", ...}} (see
 * {@code cityplans/helgen.json}).
 *
 * <p>Painters only place blocks and record anchors: {@link AnchorType#TABLEAU} for each person or body (spawned once by
 * {@link AftermathPopulation}) and {@link AnchorType#FIRE_SITE} for fires seen from afar ({@link com.skycraft.fire.FireAnchors}).
 * They run on worldgen threads once per chunk the piece touches, so variety comes from position hashes
 * ({@code Canvas.roll}) and never from a shared random. The ground height of every column is read once, before
 * anything is painted ({@link #g}): a column that later holds a slab or a carpet is still "ground + 1" for whatever is
 * placed on it, and a final {@link Supports} pass removes anything left without a floor.</p>
 */
public final class AftermathPainters {
    private AftermathPainters() {}

    /** Registers every painter ({@code SettlementPlan.painter}); called once at mod construction. */
    public static void register() {
        SettlementPlan.painter("helgen_triage", AftermathPainters::triage);
        SettlementPlan.painter("helgen_funeral_row", AftermathPainters::funeralRow);
        SettlementPlan.painter("helgen_graves", AftermathPainters::graves);
        SettlementPlan.painter("helgen_shrine", AftermathPainters::shrine);
        SettlementPlan.painter("helgen_vigil", AftermathPainters::vigil);
        SettlementPlan.painter("helgen_pyre", AftermathPainters::pyre);
        SettlementPlan.painter("helgen_wreckage", AftermathPainters::wreckage);
        SettlementPlan.painter("helgen_gate_post", AftermathPainters::gatePost);
        SettlementPlan.painter("helgen_cart_stall", AftermathPainters::cartStall);
        SettlementPlan.painter("helgen_crater", AftermathPainters::crater);
        SettlementPlan.painter("helgen_belongings", AftermathPainters::belongings);
    }

    // ------------------------------------------------------------------ helpers

    /** The current piece's natural ground, per column (canvas, map, w, d); set by {@link #clear}. */
    private static final ThreadLocal<Object[]> GROUND = new ThreadLocal<>();

    private static void begin(Canvas c, int w, int d) {
        GROUND.set(new Object[]{c, Supports.ground(c, w, d), w, d});
    }

    private static int g(Canvas c, int x, int z) {
        if (c.recording()) return 0;
        Object[] cur = GROUND.get();
        if (cur != null && cur[0] == c) {
            int[][] m = (int[][]) cur[1];
            if (x >= 0 && x < m.length && z >= 0 && z < m[0].length) return m[x][z];
        }
        return c.wground(c.wx(x, z), c.wz(x, z)) - c.oy;
    }

    /** Final pass of a painter: whatever needs a floor and has none (a fire over a hole, a bedroll half in the air) goes. */
    private static void settle(Canvas c, int w, int d, int up) {
        Object[] cur = GROUND.get();
        if (cur != null && cur[0] == c) Supports.sweepGround(c, (int[][]) cur[1], w, d, up);
        GROUND.remove();
    }

    private static void put(Canvas c, int x, int dy, int z, BlockState s) {
        c.set(x, g(c, x, z) + dy, z, s);
    }

    private static void putOpen(Canvas c, int x, int dy, int z, BlockState s) {
        c.setIfOpen(x, g(c, x, z) + dy, z, s);
    }

    private static BlockState b(Block b) {
        return b.defaultBlockState();
    }

    private static BlockState charred() {
        return FireBlocks.CHARRED_PLANKS.get().defaultBlockState();
    }

    private static BlockState wreck() {
        return FireBlocks.SPLINTERED_WRECKAGE.get().defaultBlockState();
    }

    private static BlockState debris() {
        return FireBlocks.TIMBER_DEBRIS.get().defaultBlockState();
    }

    private static BlockState soot() {
        return FireBlocks.SOOT_STAIN.get().defaultBlockState();
    }

    private static BlockState blood() {
        return FireBlocks.BLOOD_STAIN.get().defaultBlockState();
    }

    private static BlockState arrows() {
        return FireBlocks.STUCK_ARROWS.get().defaultBlockState();
    }

    private static BlockState ash() {
        return B.id("skycraft:ash_layer", Blocks.SNOW);
    }

    private static BlockState bedroll() {
        return B.id("skycraft:bedroll", Blocks.RED_BED);
    }

    private static BlockState candle(boolean three) {
        BlockState s = b(Blocks.WHITE_CANDLE).setValue(CandleBlock.LIT, true);
        return three ? s.setValue(CandleBlock.CANDLES, 3) : s;
    }

    /** Option text that turns the actor (or body) to look at a canvas-local spot: the world x,z of that spot. */
    private static String face(Canvas c, int tx, int tz) {
        return "@" + c.wx(tx, tz) + "," + c.wz(tx, tz);
    }

    private static void person(Canvas c, int x, int z, String pose, String role) {
        c.anchor(AnchorType.TABLEAU, x, g(c, x, z) + 1, z, pose + "|" + role + "|");
    }

    private static void person(Canvas c, int x, int z, String pose, String role, String opt) {
        c.anchor(AnchorType.TABLEAU, x, g(c, x, z) + 1, z, pose + "|" + role + "|" + opt);
    }

    /** A person who looks at (tx, tz): a mourner at a body, a worshipper at an altar, a healer at a patient. */
    private static void personFacing(Canvas c, int x, int z, String pose, String role, int tx, int tz) {
        person(c, x, z, pose, role, face(c, tx, tz));
    }

    private static void body(Canvas c, int x, int z, String role, boolean lootable, String opt) {
        c.anchor(AnchorType.TABLEAU, x, g(c, x, z) + 1, z, "body|" + role + "|" + (lootable ? "l" : "") + opt);
    }

    private static void fire(Canvas c, int x, int dy, int z, float intensity, int height, int smoke) {
        c.anchor(AnchorType.FIRE_SITE, x, g(c, x, z) + dy, z, intensity + "|" + height + "|" + smoke + "|1");
    }

    /** A fire column of {@code height} blocks standing on the ground at (x, z). */
    private static void flames(Canvas c, int x, int z, int dy, int height) {
        int gy = g(c, x, z) + dy;
        for (int i = 0; i < height; i++) {
            BlockState st = i == 0 ? FireBlocks.BIG_FIRE.get().defaultBlockState()
                    : (i == height - 1 ? FireBlocks.FLAME_TIP.get().defaultBlockState() : FireBlocks.FLAME_COLUMN.get().defaultBlockState());
            c.set(x, gy + i, z, st);
        }
    }

    /** First step of every painter: reads the ground, then clears plants above it. */
    private static void clear(Canvas c, Spec s, int h) {
        begin(c, s.w, s.d);
        if (!c.recording()) c.clearPlants(0, 1, 0, s.w - 1, h, s.d - 1);
    }

    /** Scuffed, trampled and scorched ground. */
    private static void trample(Canvas c, int w, int d, int soot, int dirt) {
        if (c.recording()) return;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                if (!c.touches(x, z, x, z)) continue;
                int gy = g(c, x, z);
                int r = c.roll(x, 0, z, 211, 100);
                if (r < dirt) c.set(x, gy, z, b(Blocks.COARSE_DIRT));
                else if (r < dirt + soot) putOpen(c, x, 1, z, soot());
            }
        }
    }

    private static void rubblePile(Canvas c, int x, int z, int salt) {
        int r = c.roll(x, 0, z, salt, 5);
        switch (r) {
            case 0 -> {
                put(c, x, 1, z, wreck());
                putOpen(c, x, 2, z, debris());
            }
            case 1 -> put(c, x, 1, z, wreck());
            case 2 -> {
                put(c, x, 1, z, b(Blocks.COBBLESTONE));
                putOpen(c, x, 2, z, b(Blocks.COBBLESTONE_SLAB));
            }
            case 3 -> {
                put(c, x, 1, z, FireBlocks.SCORCHED_STONE_BRICKS.get().defaultBlockState());
                putOpen(c, x, 2, z, debris());
            }
            default -> put(c, x, 1, z, debris());
        }
    }

    // ------------------------------------------------------------------ the aid camp

    /**
     * The aid camp: field tents (A-frame cloth over a ridge, open at the front) with the wounded lying on bedrolls
     * inside and a healer standing between them tending, a cook's fire in front, somebody sitting up outside.
     */
    static void triage(Canvas c, Spec s) {
        int w = s.w, d = s.d;
        clear(c, s, 6);
        trample(c, w, d, 15, 35);
        String[] roles = {"citizen", "farmer", "imperial_soldier", "hunter", "child", "citizen", "imperial_soldier", "lumberjack"};
        int tents = w >= 14 ? 2 : 1;
        int n = 0;
        int len = Math.max(5, Math.min(6, d - 5));
        for (int t = 0; t < tents; t++) n = tent(c, 1 + t * 7, 1, len, roles, n, t);
        // the cook's fire in front of the tents, a barrel of water, bowls
        int px = Math.min(w - 2, w / 2), pz = Math.min(d - 2, 1 + len + 2);
        put(c, px, 1, pz, b(Blocks.CAMPFIRE).setValue(CampfireBlock.LIT, true).setValue(CampfireBlock.SIGNAL_FIRE, false));
        put(c, px, 2, pz, Props.cookingPot());
        personFacing(c, px + 1 < w ? px + 1 : px - 1, pz, "ladle", "citizen", px, pz);
        if (px + 2 < w) putOpen(c, px + 2, 1, pz + 1 < d ? pz + 1 : pz, B.face(b(Blocks.BARREL), Direction.UP));
        // somebody sitting up outside, watching
        person(c, Math.min(w - 1, 6), Math.min(d - 1, 1 + len + 1), "looker", "farmer");
        settle(c, w, d, 8);
    }

    /** One field tent whose back wall is at {@code z0}; returns the running count of patients placed. */
    private static int tent(Canvas c, int x0, int z0, int len, String[] roles, int n, int idx) {
        int gb = g(c, x0 + 2, z0 + len / 2);
        // floor, with earth under it where the ground falls away, and room to stand
        for (int x = x0; x < x0 + 5; x++) {
            for (int z = z0; z < z0 + len; z++) {
                c.set(x, gb, z, b(Blocks.COARSE_DIRT));
                c.foundation(x, gb - 1, z, b(Blocks.DIRT), 4);
                for (int y = 1; y <= 4; y++) c.air(x, gb + y, z);
            }
        }
        for (int z = z0; z < z0 + len; z++) {
            cloth(c, x0, gb + 1, z, idx, z);
            cloth(c, x0 + 4, gb + 1, z, idx, z);
            cloth(c, x0 + 1, gb + 2, z, idx, z);
            cloth(c, x0 + 3, gb + 2, z, idx, z);
            cloth(c, x0 + 2, gb + 3, z, idx, z);
        }
        // the back wall closes the tent
        for (int x = x0 + 1; x <= x0 + 3; x++) cloth(c, x, gb + 1, z0, idx, 0);
        cloth(c, x0 + 2, gb + 2, z0, idx, 0);
        // the wounded: two bedrolls a side, head to the back
        for (int side = 0; side < 2; side++) {
            int x = x0 + 1 + side * 2;
            for (int bi = 0; bi < 2; bi++) {
                int foot = z0 + 2 + bi * 2, head = z0 + 1 + bi * 2;
                if (head <= z0 || foot >= z0 + len) continue;
                c.bed(x, gb + 1, foot, bedroll(), Direction.NORTH);
                c.foundation(x, gb, head, b(Blocks.DIRT), 3);
                c.anchor(AnchorType.TABLEAU, x, gb + 1, head, "patient|" + roles[n % roles.length] + "|");
                n++;
                if (c.chance(x, bi, z0, 227, 2)) putAt(c, x, gb + 1, foot + 1 < z0 + len ? foot + 1 : foot, blood());
            }
        }
        // the healer between the rows, bent over the nearest patient
        String healer = idx % 2 == 0 ? "priest" : "apothecary";
        c.anchor(AnchorType.TABLEAU, x0 + 2, gb + 1, z0 + 3, "tend|" + healer + "|" + face(c, x0 + 1, z0 + 2));
        // a lantern under the ridge, a candle on the floor, a bundle of linen
        c.set(x0 + 2, gb + 2, z0 + 1, b(Blocks.LANTERN).setValue(LanternBlock.HANGING, true));
        c.setIfOpen(x0 + 2, gb + 1, z0 + 1, candle(false));
        c.setIfOpen(x0 + 2, gb + 1, z0 + len - 1, b(Blocks.WHITE_CARPET));
        return n;
    }

    private static void cloth(Canvas c, int x, int y, int z, int idx, int salt) {
        boolean grey = c.chance(x, y, z + salt, 221 + idx, 4);
        c.set(x, y, z, grey ? b(Blocks.LIGHT_GRAY_WOOL) : b(Blocks.WHITE_WOOL));
    }

    private static void putAt(Canvas c, int x, int y, int z, BlockState s) {
        c.setIfOpen(x, y, z, s);
    }

    // ------------------------------------------------------------------ the dead

    /**
     * Rows of covered dead: each body lies on the ground under a sheet (a carpet laid on the ground, never over air), a
     * candle at its head, and a mourner kneels beside every second one, a priest keeps the vigil.
     */
    static void funeralRow(Canvas c, Spec s) {
        int w = s.w, d = s.d;
        clear(c, s, 4);
        trample(c, w, d, 10, 20);
        String[] roles = {"citizen", "farmer", "hunter", "imperial_soldier", "child", "lumberjack", "citizen", "miner", "farmer"};
        int n = 0;
        int cols = Math.max(1, (w - 1) / 3);
        for (int r = 0; r < cols; r++) {
            int x = 1 + r * 3;
            if (x >= w - 1) break;
            for (int z = 2; z + 2 < d - 1 && n < roles.length; z += 4) {
                // the head end towards the candle, the feet towards the next body
                putOpen(c, x, 1, z, b(Blocks.WHITE_CARPET));
                putOpen(c, x, 1, z + 1, b(Blocks.WHITE_CARPET));
                putOpen(c, x, 1, z - 1, candle(false));
                if (c.chance(x, 0, z, 231, 2)) putOpen(c, x - 1 >= 0 ? x - 1 : x + 1, 1, z, b(Blocks.POPPY));
                if (c.chance(x, 0, z, 233, 3)) putOpen(c, x - 1 >= 0 ? x - 1 : x + 1, 1, z + 1, b(Blocks.LILY_OF_THE_VALLEY));
                body(c, x, z, roles[n], true, "s" + face(c, x, z + 4));
                // a mourner kneels beside every second body, facing it
                if (n % 2 == 0 && x + 1 < w) personFacing(c, x + 1, z, "mourn", n % 4 == 0 ? "citizen" : "farmer", x, z);
                n++;
            }
        }
        // the priest keeps the vigil at the foot of the rows, one who has nothing left to do kneels there
        personFacing(c, w / 2, d - 2, "priest", "priest", w / 2, 2);
        personFacing(c, Math.max(0, w / 2 - 2), d - 2, "kneel_weep", "citizen", w / 2, 2);
        settle(c, w, d, 4);
    }

    /** Graves: each a mound of fresh earth with a headstone at its head, flowers, and mourners kneeling beside it. */
    static void graves(Canvas c, Spec s) {
        int w = s.w, d = s.d;
        clear(c, s, 4);
        trample(c, w, d, 8, 25);
        int cx = w / 2;
        int k = 0;
        for (int z = 2; z + 1 < d - 1; z += 4) {
            // the mound, the headstone (a short wall with a cap) and what was left on it
            put(c, cx, 1, z, b(Blocks.COARSE_DIRT));
            put(c, cx, 1, z + 1, b(Blocks.COARSE_DIRT));
            put(c, cx, 1, z - 1, b(Blocks.STONE_BRICK_WALL));
            put(c, cx, 2, z - 1, b(Blocks.STONE_BRICK_SLAB));
            if (c.chance(cx, 0, z, 237, 2)) putOpen(c, cx, 2, z + 1, b(Blocks.POPPY));
            if (c.chance(cx, 1, z, 239, 3)) putOpen(c, cx, 2, z, candle(false));
            // a mourner at every second grave, kneeling at its side
            if (k % 2 == 0 && cx + 1 < w) personFacing(c, cx + 1, z, "kneel_weep", k % 4 == 0 ? "citizen" : "farmer", cx, z);
            else if (cx - 1 >= 0) personFacing(c, cx - 1, z + 1, "mourn", "citizen", cx, z + 1);
            k++;
        }
        settle(c, w, d, 4);
    }

    /**
     * A small shrine: a platform of stone, an altar with candles and a stele behind it, braziers at the front, people
     * kneeling before it and a priest beside it (Imperial and Nordic worship alike: the Divines are prayed to in ruins).
     */
    static void shrine(Canvas c, Spec s) {
        int w = s.w, d = s.d;
        clear(c, s, 5);
        trample(c, w, d, 6, 15);
        int cx = w / 2;
        // the platform: stone bricks over the ground
        if (!c.recording()) {
            for (int x = 1; x < w - 1; x++) {
                for (int z = 0; z < d - 1; z++) {
                    if (!c.touches(x, z, x, z)) continue;
                    c.set(x, g(c, x, z), z, c.chance(x, 0, z, 241, 4) ? b(Blocks.CRACKED_STONE_BRICKS) : b(Blocks.STONE_BRICKS));
                }
            }
        }
        // the altar: a block between two slabs, candles on it, a stele behind it
        put(c, cx, 1, 1, b(Blocks.STONE_BRICKS));
        put(c, cx - 1, 1, 1, b(Blocks.STONE_BRICK_SLAB));
        put(c, cx + 1, 1, 1, b(Blocks.STONE_BRICK_SLAB));
        put(c, cx, 2, 1, candle(true));
        putOpen(c, cx - 1, 2, 1, candle(false));
        putOpen(c, cx + 1, 2, 1, candle(false));
        for (int y = 1; y <= 3; y++) put(c, cx, y, 0, b(Blocks.CHISELED_STONE_BRICKS));
        put(c, cx - 1, 1, 0, b(Blocks.STONE_BRICK_WALL));
        put(c, cx + 1, 1, 0, b(Blocks.STONE_BRICK_WALL));
        // braziers at the front corners, offerings on the steps
        for (int x : new int[]{1, w - 2}) {
            put(c, x, 1, d - 2, b(Blocks.COBBLESTONE_WALL));
            put(c, x, 2, d - 2, b(Blocks.CAMPFIRE).setValue(CampfireBlock.LIT, true).setValue(CampfireBlock.SIGNAL_FIRE, false));
        }
        putOpen(c, cx - 2 >= 1 ? cx - 2 : cx, 1, 2, b(Blocks.POPPY));
        putOpen(c, cx + 2 <= w - 2 ? cx + 2 : cx, 1, 2, b(Blocks.LILY_OF_THE_VALLEY));
        // the faithful kneel in two rows facing the altar; the priest stands at its side
        personFacing(c, cx - 1, 3, "pray", "citizen", cx, 1);
        personFacing(c, cx + 1, 3, "pray", "farmer", cx, 1);
        personFacing(c, cx, 4, "pray", "citizen", cx, 1);
        personFacing(c, Math.max(1, cx - 2), 4, "kneel_weep", "citizen", cx, 1);
        personFacing(c, Math.min(w - 2, cx + 2), 2, "priest", "priest", cx, 1);
        settle(c, w, d, 5);
    }

    /** A small vigil: three covered dead side by side, candles at their heads, kneeling mourners and a priest. */
    static void vigil(Canvas c, Spec s) {
        int w = s.w, d = s.d;
        clear(c, s, 4);
        trample(c, w, d, 8, 20);
        String[] roles = {"citizen", "child", "imperial_soldier"};
        String[] mourners = {"farmer", "citizen", "hunter"};
        int n = 0;
        for (int x = 1; x < w - 1 && n < 3; x += 2) {
            putOpen(c, x, 1, 1, b(Blocks.WHITE_CARPET));
            putOpen(c, x, 1, 2, b(Blocks.WHITE_CARPET));
            putOpen(c, x, 1, 0, candle(false));
            body(c, x, 1, roles[n], true, "s" + face(c, x, 4));
            personFacing(c, x, 4, n == 1 ? "kneel_weep" : "mourn", mourners[n], x, 2);
            n++;
        }
        personFacing(c, w / 2, Math.min(d - 1, 6), "priest", "priest", w / 2, 1);
        settle(c, w, d, 4);
    }

    /** A funeral pyre: stacked charred logs, a shrouded pair on top, a column of flame, mourners kneeling round it. */
    static void pyre(Canvas c, Spec s) {
        int w = s.w, d = s.d;
        clear(c, s, 12);
        trample(c, w, d, 20, 25);
        int cx = w / 2, cz = d / 2;
        BlockState log = FireBlocks.CHARRED_LOG.get().defaultBlockState();
        for (int layer = 0; layer < 3; layer++) {
            Direction.Axis ax = layer % 2 == 0 ? Direction.Axis.X : Direction.Axis.Z;
            int len = 3 - layer / 2;
            for (int i = -len; i <= len; i++) {
                for (int off = -1; off <= 1; off += 1) {
                    int x = ax == Direction.Axis.X ? cx + i : cx + off;
                    int z = ax == Direction.Axis.X ? cz + off : cz + i;
                    put(c, x, 1 + layer, z, B.axis(log, ax));
                }
            }
        }
        // the shrouded dead on top, and the fire
        put(c, cx, 4, cz - 1, b(Blocks.SMOOTH_QUARTZ_SLAB));
        put(c, cx, 4, cz, b(Blocks.SMOOTH_QUARTZ_SLAB));
        put(c, cx, 4, cz + 1, b(Blocks.SMOOTH_QUARTZ_SLAB));
        putOpen(c, cx + 1, 4, cz, FireBlocks.RUBBLE_FIRE.get().defaultBlockState());
        flames(c, cx - 1, cz, 4, 5);
        flames(c, cx, cz + 1, 5, 3);
        fire(c, 6, cx, cz, 1.5f, 8, 3);
        // mourners kneeling round it, the priest standing back
        personFacing(c, cx - 4, cz, "mourn", "citizen", cx, cz);
        personFacing(c, cx + 4, cz + 1, "kneel_weep", "farmer", cx, cz);
        personFacing(c, cx, cz - 4, "mourn", "hunter", cx, cz);
        personFacing(c, cx + 3, cz + 4, "priest", "priest", cx, cz);
        for (int i = 0; i < 6; i++) {
            int x = c.roll(i, 0, 0, 243, w), z = c.roll(0, 0, i, 245, d);
            if (Math.abs(x - cx) + Math.abs(z - cz) > 3) putOpen(c, x, 1, z, ash());
        }
        settle(c, w, d, 12);
    }

    // ------------------------------------------------------------------ the clearing

    /**
     * A rubble field with people at work: two diggers, a picker, haulers carrying planks to a handcart, a looter at a
     * body, and (variant 2) a captain shouting orders. {@code variant} 0..2 shifts the mix.
     */
    static void wreckage(Canvas c, Spec s) {
        int w = s.w, d = s.d;
        clear(c, s, 5);
        trample(c, w, d, 20, 25);
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                int r = c.roll(x, 0, z, 251, 100);
                int edge = Math.min(Math.min(x, w - 1 - x), Math.min(z, d - 1 - z));
                if (r < 30 + (edge >= 2 ? 25 : 0)) rubblePile(c, x, z, 253);
                else if (r < 38) putOpen(c, x, 1, z, FireBlocks.SMOULDERING_EMBERS.get().defaultBlockState());
            }
        }
        // a broken wall segment and a standing post or two
        for (int x = 1; x < Math.min(w - 1, 5); x++) {
            int h = 1 + c.roll(x, 0, 0, 255, 3);
            for (int y = 1; y <= h; y++) put(c, x, y, 1, FireBlocks.SCORCHED_STONE_BRICKS.get().defaultBlockState());
        }
        put(c, w - 2, 1, d - 2, B.axis(FireBlocks.CHARRED_BEAM.get().defaultBlockState(), Direction.Axis.Y));
        put(c, w - 2, 2, d - 2, B.axis(FireBlocks.CHARRED_BEAM.get().defaultBlockState(), Direction.Axis.Y));
        // the smouldering heart of the pile
        putOpen(c, w / 2, 1, d / 2, FireBlocks.RUBBLE_FIRE.get().defaultBlockState());
        fire(c, 2, w / 2, d / 2, 0.45f, 3, 2);
        // a handcart at the near end
        int cx = w - 3, cz = 1;
        for (int x = cx; x <= cx + 1 && x < w; x++) {
            for (int z = cz; z <= cz + 2 && z < d; z++) put(c, x, 1, z, b(Blocks.SPRUCE_SLAB));
        }
        // the workers
        person(c, 2, d / 2, "dig", "citizen");
        person(c, w / 2 + 1, 2, "pick", "miner");
        person(c, 3, d - 3, "axe", "lumberjack");
        person(c, w / 2 - 1, d - 2, "haul", "farmer", (cx - (w / 2 - 1)) + "," + (cz + 1 - (d - 2)));
        person(c, 2, 3, "haul", "citizen", (cx - 2) + "," + (cz + 1 - 3));
        if (s.variant % 3 == 1) {
            person(c, 1, d - 2, "looter", "thief");
            body(c, 2, d - 2, "citizen", true, "f");
        }
        if (s.variant % 3 == 2) {
            person(c, w / 2, d - 2, "captain", "imperial_soldier");
            body(c, w - 2, 3, "imperial_soldier", true, "");
        }
        putOpen(c, 1, 1, 2, arrows());
        settle(c, w, d, 6);
    }

    // ------------------------------------------------------------------ the gate

    /** The soldiers who hold the gate: a barricade of crates and stakes, a brazier, a captain, two fallen comrades. */
    static void gatePost(Canvas c, Spec s) {
        int w = s.w, d = s.d;
        clear(c, s, 5);
        trample(c, w, d, 12, 25);
        // a low barricade with stakes along the outer edge, a gap in the middle for the road
        int mid = w / 2;
        for (int x = 0; x < w; x++) {
            if (Math.abs(x - mid) <= 1) continue;
            put(c, x, 1, 1, c.chance(x, 0, 1, 271, 3) ? B.face(b(Blocks.BARREL), Direction.UP) : charred());
            if (x % 2 == 0) put(c, x, 2, 1, b(Blocks.SPRUCE_FENCE));
        }
        // the brazier and a banner, scorched
        put(c, 1, 1, 3, b(Blocks.COBBLESTONE_WALL));
        put(c, 1, 2, 3, b(Blocks.CAMPFIRE).setValue(CampfireBlock.LIT, true).setValue(CampfireBlock.SIGNAL_FIRE, false));
        put(c, w - 2, 1, 3, b(Blocks.SPRUCE_FENCE));
        put(c, w - 2, 2, 3, b(Blocks.SPRUCE_FENCE));
        put(c, w - 2, 3, 3, b(Blocks.RED_WOOL));
        // the soldiers: three on watch, their captain, two who did not make it
        person(c, mid - 3, 3, "guard", "imperial_soldier");
        person(c, mid + 3, 3, "guard", "imperial_soldier");
        person(c, mid + 5 < w ? mid + 5 : w - 3, 4, "guard", "imperial_soldier");
        person(c, mid - 5 >= 0 ? mid - 5 : 2, 4, "captain", "imperial_soldier");
        body(c, mid - 2, d - 2, "imperial_soldier", true, "");
        body(c, mid + 2, d - 2, "imperial_soldier", true, "f");
        putOpen(c, mid - 1, 1, d - 3, arrows());
        putOpen(c, mid + 1, 1, d - 2, arrows());
        putOpen(c, mid - 2, 1, d - 3, blood());
        putOpen(c, mid + 2, 1, d - 3, blood());
        settle(c, w, d, 5);
    }

    // ------------------------------------------------------------------ the living town

    /** A salvaged handcart selling what was saved: awning, barrels, an anvil, the merchant behind it. */
    static void cartStall(Canvas c, Spec s) {
        int w = s.w, d = s.d;
        clear(c, s, 5);
        trample(c, w, d, 10, 20);
        int x0 = 1, x1 = w - 2, z0 = 1, z1 = Math.min(d - 3, 4);
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) put(c, x, 1, z, b(Blocks.SPRUCE_SLAB));
        }
        for (int x = x0; x <= x1; x += 2) {
            put(c, x, 2, z0, b(Blocks.SPRUCE_FENCE));
            put(c, x, 2, z1, b(Blocks.SPRUCE_FENCE));
        }
        // wheels (one broken), the shaft, the awning
        put(c, x0 - 1 >= 0 ? x0 - 1 : 0, 1, z0 + 1, b(Blocks.SPRUCE_TRAPDOOR));
        put(c, x1 + 1 < w ? x1 + 1 : x1, 1, z0 + 1, c.chance(0, 0, 0, 281, 2) ? b(Blocks.SPRUCE_TRAPDOOR) : debris());
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (c.chance(x, 5, z, 283, 6)) continue;
                put(c, x, 4, z, c.chance(x, 6, z, 285, 3) ? b(Blocks.ORANGE_WOOL) : b(Blocks.WHITE_WOOL));
            }
        }
        for (int x : new int[]{x0, x1}) {
            for (int y = 2; y <= 3; y++) put(c, x, y, z0, b(Blocks.SPRUCE_FENCE));
        }
        // wares: an anvil, barrels, a crate of salvaged tools
        put(c, x0 + 1, 2, z0 + 1, b(Blocks.ANVIL));
        put(c, x1 - 1, 2, z0 + 1, B.face(b(Blocks.BARREL), Direction.UP));
        put(c, x1, 1, z1 + 1 < d ? z1 + 1 : z1, B.face(b(Blocks.BARREL), Direction.UP));
        put(c, x0, 1, z1 + 1 < d ? z1 + 1 : z1, b(Blocks.BARREL));
        // the merchant stands behind the cart
        c.anchor(AnchorType.MERCHANT, w / 2, g(c, w / 2, z1 + 1) + 1, Math.min(d - 1, z1 + 1), "helgen_cart");
        settle(c, w, d, 5);
    }

    /** A dragon-fire crater: a bowl of scorched stone with a smouldering centre, rubble thrown out, soot. */
    static void crater(Canvas c, Spec s) {
        int w = s.w, d = s.d;
        clear(c, s, 3);
        double cx = (w - 1) / 2.0, cz = (d - 1) / 2.0, r = Math.min(w, d) / 2.0;
        int[][] floors = new int[w][d];
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                if (!c.touches(x, z, x, z)) continue;
                double dist = Math.hypot(x - cx, z - cz) / r;
                if (dist > 1.0) continue;
                int gy = g(c, x, z);
                int depth = (int) Math.round(2.6 * (1.0 - dist * dist));
                for (int y = gy; y > gy - depth; y--) c.air(x, y, z);
                int floor = gy - depth;
                floors[x][z] = depth > 0 ? floor : Integer.MIN_VALUE;
                if (depth > 0) {
                    c.set(x, floor, z, dist < 0.55 ? FireBlocks.SCORCHED_STONE.get().defaultBlockState() : b(Blocks.COARSE_DIRT));
                    if (dist < 0.4 && c.chance(x, 0, z, 291, 3)) c.set(x, floor + 1, z, FireBlocks.SMOULDERING_EMBERS.get().defaultBlockState());
                } else if (dist > 0.8) {
                    c.set(x, gy, z, b(Blocks.COARSE_DIRT));
                }
                if (dist > 0.85 && c.chance(x, 1, z, 293, 3)) putOpen(c, x, 1, z, c.chance(x, 2, z, 295, 2) ? ash() : debris());
                else if (dist <= 0.85 && depth == 0) putOpen(c, x, 1, z, soot());
            }
        }
        body(c, w - 1, d / 2, "citizen", true, "f");
        body(c, 0, d / 2 + 1, "farmer", true, "s");
        // the smouldering heart sits on the floor of the bowl (a block above it), never in the ground itself
        int mx = (int) Math.round(cx), mz = (int) Math.round(cz);
        if (mx >= 0 && mx < w && mz >= 0 && mz < d && floors[mx][mz] != Integer.MIN_VALUE) {
            c.set(mx, floors[mx][mz] + 1, mz, FireBlocks.RUBBLE_FIRE.get().defaultBlockState());
            fire(c, mx, floors[mx][mz] + 1 - g(c, mx, mz), mz, 0.7f, 4, 2);
        }
        settle(c, w, d, 3);
    }

    /** What people dropped: barrels, a broken chair, a cauldron, a dropped bundle, candles. */
    static void belongings(Canvas c, Spec s) {
        int w = s.w, d = s.d;
        clear(c, s, 3);
        body(c, w / 2, d / 2, s.x % 2 == 0 ? "citizen" : "farmer", true, "s");
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                int r = c.roll(x, 0, z, 301, 100);
                if (r < 8) putOpen(c, x, 1, z, B.face(b(Blocks.BARREL), c.chance(x, 0, z, 303, 2) ? Direction.NORTH : Direction.EAST));
                else if (r < 13) putOpen(c, x, 1, z, B.stair(b(Blocks.SPRUCE_STAIRS), Direction.values()[2 + c.roll(x, 1, z, 305, 4)], c.chance(x, 2, z, 307, 2)));
                else if (r < 17) putOpen(c, x, 1, z, b(Blocks.CAULDRON));
                else if (r < 21) putOpen(c, x, 1, z, b(Blocks.FLOWER_POT));
                else if (r < 25) putOpen(c, x, 1, z, b(Blocks.BROWN_CARPET));
                else if (r < 29) putOpen(c, x, 1, z, b(Blocks.WHITE_CARPET));
                else if (r < 33) putOpen(c, x, 1, z, b(Blocks.CANDLE));
                else if (r < 40) putOpen(c, x, 1, z, debris());
                else if (r < 44) putOpen(c, x, 1, z, arrows());
                else if (r < 50) putOpen(c, x, 1, z, soot());
            }
        }
        settle(c, w, d, 3);
    }
}
