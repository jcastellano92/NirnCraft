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
import net.minecraft.world.level.block.state.BlockState;

/**
 * The set pieces of a town that has just been attacked, as settlement painters: a triage camp, a row of covered
 * dead, a pyre, rubble fields with people clearing them, a bucket chain to a burning house, a gate post with the
 * soldiers who hold it, the surviving merchant's cart, craters, scattered belongings, huddled survivors. A city plan
 * names them as {@code {"kind": "reserved", "name": "helgen_triage", ...}} (see {@code cityplans/helgen.json}).
 *
 * <p>Painters only place blocks and record anchors: {@link AnchorType#TABLEAU} for each person or body (spawned once by
 * {@link AftermathPopulation}) and {@link AnchorType#FIRE_SITE} for fires seen from afar ({@link com.skycraft.fire.FireAnchors}).
 * They run on worldgen threads once per chunk the piece touches, so variety comes from position hashes
 * ({@code Canvas.roll}) and never from a shared random.</p>
 */
public final class AftermathPainters {
    private AftermathPainters() {}

    /** Registers every painter ({@code SettlementPlan.painter}); called once at mod construction. */
    public static void register() {
        SettlementPlan.painter("helgen_triage", AftermathPainters::triage);
        SettlementPlan.painter("helgen_funeral_row", AftermathPainters::funeralRow);
        SettlementPlan.painter("helgen_pyre", AftermathPainters::pyre);
        SettlementPlan.painter("helgen_wreckage", AftermathPainters::wreckage);
        SettlementPlan.painter("helgen_bucket_line", AftermathPainters::bucketLine);
        SettlementPlan.painter("helgen_gate_post", AftermathPainters::gatePost);
        SettlementPlan.painter("helgen_cart_stall", AftermathPainters::cartStall);
        SettlementPlan.painter("helgen_crater", AftermathPainters::crater);
        SettlementPlan.painter("helgen_belongings", AftermathPainters::belongings);
        SettlementPlan.painter("helgen_huddle", AftermathPainters::huddle);
    }

    // ------------------------------------------------------------------ helpers

    private static int g(Canvas c, int x, int z) {
        return c.recording() ? 0 : c.wground(c.wx(x, z), c.wz(x, z)) - c.oy;
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

    private static void person(Canvas c, int x, int z, String pose, String role) {
        c.anchor(AnchorType.TABLEAU, x, g(c, x, z) + 1, z, pose + "|" + role + "|");
    }

    private static void person(Canvas c, int x, int z, String pose, String role, String opt) {
        c.anchor(AnchorType.TABLEAU, x, g(c, x, z) + 1, z, pose + "|" + role + "|" + opt);
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

    private static void clear(Canvas c, Spec s, int h) {
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

    /** Bedrolls with the wounded, two healers, a soup pot, a table of bandages, an awning over it all. */
    static void triage(Canvas c, Spec s) {
        int w = s.w, d = s.d;
        clear(c, s, 6);
        trample(c, w, d, 15, 35);
        // the awning: four posts and ragged cloth
        int ax0 = 1, ax1 = Math.min(w - 5, 9), az0 = 1, az1 = Math.min(d - 3, 8);
        for (int x : new int[]{ax0, ax1}) {
            for (int z : new int[]{az0, az1}) {
                for (int y = 1; y <= 3; y++) put(c, x, y, z, b(Blocks.SPRUCE_FENCE));
            }
        }
        for (int x = ax0; x <= ax1; x++) {
            for (int z = az0; z <= az1; z++) {
                if (c.chance(x, 4, z, 221, 5)) continue;
                int gy = g(c, x, z);
                c.set(x, gy + 4, z, c.chance(x, 4, z, 223, 3) ? b(Blocks.LIGHT_GRAY_WOOL) : b(Blocks.WHITE_WOOL));
            }
        }
        // bedrolls in two rows; the wounded lie at the head end
        String[] roles = {"citizen", "farmer", "imperial_soldier", "hunter", "child", "citizen", "imperial_soldier", "lumberjack"};
        int n = 0;
        for (int row = 0; row < 2; row++) {
            int z = az0 + 1 + row * (az1 - az0 - 2);
            for (int x = ax0 + 1; x < ax1 && n < roles.length; x += 2) {
                int gy = g(c, x, z);
                c.bed(x, gy + 1, z, bedroll(), Direction.SOUTH);
                if (c.chance(x, 0, z, 227, 2)) putOpen(c, x, 1, z - 1, blood());
                person(c, x, z + 1, "patient", roles[n++]);
            }
        }
        // healers between the rows, a cook at the pot
        int mid = (az0 + az1) / 2;
        person(c, ax0 + 2, mid, "tend", "priest");
        person(c, ax0 + 6 <= ax1 ? ax0 + 6 : ax1 - 1, mid, "tend", "apothecary");
        int px = Math.min(w - 2, ax1 + 3), pz = Math.min(d - 2, 3);
        put(c, px, 1, pz, b(Blocks.CAMPFIRE).setValue(CampfireBlock.LIT, true).setValue(CampfireBlock.SIGNAL_FIRE, false));
        put(c, px, 2, pz, Props.cookingPot());
        person(c, px + 1 < w ? px + 1 : px - 1, pz, "ladle", "citizen");
        // a table of bandages and bottles, barrels of water, crates
        put(c, px, 1, pz + 3 < d ? pz + 3 : pz - 2, b(Blocks.SPRUCE_FENCE));
        put(c, px, 2, pz + 3 < d ? pz + 3 : pz - 2, b(Blocks.SPRUCE_PRESSURE_PLATE));
        put(c, px + 1 < w ? px + 1 : px - 2, 1, pz + 3 < d ? pz + 3 : pz - 2, B.face(b(Blocks.BARREL), Direction.UP));
        putOpen(c, ax1 + 1, 1, az1, b(Blocks.WHITE_CANDLE).setValue(net.minecraft.world.level.block.CandleBlock.LIT, true));
        // somebody sitting up, watching
        person(c, ax1 + 2 < w ? ax1 + 2 : ax1, az0 + 2, "looker", "farmer");
    }

    // ------------------------------------------------------------------ the dead

    /** Rows of covered bodies with candles and flowers, mourners kneeling beside them, a priest. */
    static void funeralRow(Canvas c, Spec s) {
        int w = s.w, d = s.d;
        clear(c, s, 4);
        trample(c, w, d, 10, 20);
        BlockState slab = b(Blocks.SMOOTH_QUARTZ_SLAB);
        BlockState sheet = b(Blocks.WHITE_CARPET);
        int rows = Math.max(2, (w - 1) / 3);
        for (int r = 0; r < rows; r++) {
            int x = 1 + r * 3;
            if (x >= w) break;
            for (int z = 2; z + 3 < d && z < 2 + 3 * 4; z += 5) {
                // a body is three slabs long with a sheet over the head end
                for (int k = 0; k < 3; k++) put(c, x, 1, z + k, slab);
                putOpen(c, x, 2, z, sheet);
                putOpen(c, x - 1, 1, z, b(Blocks.WHITE_CANDLE).setValue(net.minecraft.world.level.block.CandleBlock.LIT, true));
                if (c.chance(x, 0, z, 231, 2)) putOpen(c, x + 1, 1, z + 1, b(Blocks.POPPY));
                if (c.chance(x, 0, z, 233, 3)) putOpen(c, x + 1, 1, z + 2, b(Blocks.LILY_OF_THE_VALLEY));
                if (c.chance(x, 0, z, 235, 2)) person(c, x + 1 < w ? x + 1 : x - 1, z + 1, "mourn", "citizen");
            }
        }
        person(c, w / 2, d - 2, "kneel_weep", "priest");
        person(c, 1, d - 2, "stand_weep", "citizen");
        person(c, w - 2, d - 3, "mourn", "farmer");
        // the dead still waiting on the ground at the far end, without a sheet
        body(c, w / 2 - 1, 1, "citizen", false, "");
        body(c, w / 2 + 1, 1, "hunter", false, "f");
    }

    /** A funeral pyre: stacked charred logs, a shrouded pair on top, a column of flame, mourners round it. */
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
                    if (c.chance(x, layer, z, 241, 9)) continue;
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
        // mourners kneeling round it, one standing back
        person(c, cx - 4, cz, "mourn", "citizen");
        person(c, cx + 4, cz + 1, "kneel_weep", "farmer");
        person(c, cx, cz - 4, "mourn", "hunter");
        person(c, cx + 3, cz + 4, "stand_weep", "priest");
        for (int i = 0; i < 6; i++) {
            int x = c.roll(i, 0, 0, 243, w), z = c.roll(0, 0, i, 245, d);
            if (Math.abs(x - cx) + Math.abs(z - cz) > 3) putOpen(c, x, 1, z, ash());
        }
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
    }

    /** A chain of people passing buckets from a water barrel to the fire, the last one throwing. */
    static void bucketLine(Canvas c, Spec s) {
        int w = s.w, d = s.d;
        clear(c, s, 4);
        int cx = w / 2;
        put(c, cx, 1, 0, b(Blocks.WATER_CAULDRON).setValue(net.minecraft.world.level.block.LayeredCauldronBlock.LEVEL, 3));
        put(c, cx + 1 < w ? cx + 1 : cx - 1, 1, 0, B.face(b(Blocks.BARREL), Direction.UP));
        String[] roles = {"citizen", "farmer", "imperial_soldier", "hunter", "citizen", "lumberjack", "imperial_soldier"};
        int n = 0;
        for (int z = 1; z < d && n < roles.length; z += 2) {
            person(c, cx, z, "bucket", roles[n++]);
            if (c.chance(0, 0, z, 261, 2)) putOpen(c, cx + 1, 1, z, soot());
        }
        // wet ground and a dropped bucket
        for (int z = 0; z < d; z++) {
            if (c.chance(1, 0, z, 263, 3) && !c.recording()) c.set(cx, g(c, cx, z), z, b(Blocks.MUD));
        }
        putOpen(c, cx - 1, 1, d - 1, b(Blocks.CAULDRON));
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
        body(c, mid + 2, d - 2, "imperial_soldier", false, "f");
        putOpen(c, mid - 1, 1, d - 3, arrows());
        putOpen(c, mid + 1, 1, d - 2, arrows());
        putOpen(c, mid - 2, 1, d - 3, blood());
        putOpen(c, mid + 2, 1, d - 3, blood());
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
    }

    /** A dragon-fire crater: a bowl of scorched stone with a smouldering centre, rubble thrown out, soot. */
    static void crater(Canvas c, Spec s) {
        int w = s.w, d = s.d;
        clear(c, s, 3);
        double cx = (w - 1) / 2.0, cz = (d - 1) / 2.0, r = Math.min(w, d) / 2.0;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                if (!c.touches(x, z, x, z)) continue;
                double dist = Math.hypot(x - cx, z - cz) / r;
                if (dist > 1.0) continue;
                int gy = g(c, x, z);
                int depth = (int) Math.round(2.6 * (1.0 - dist * dist));
                for (int y = gy; y > gy - depth; y--) c.air(x, y, z);
                int floor = gy - depth;
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
        body(c, w - 1, d / 2, "citizen", false, "f");
        body(c, 0, d / 2 + 1, "farmer", c.chance(0, 0, 0, 297, 2), "s");
        int mx = (int) Math.round(cx), mz = (int) Math.round(cz);
        c.set(mx, g(c, mx, mz) - 2, mz, FireBlocks.RUBBLE_FIRE.get().defaultBlockState());
        fire(c, mx, -1, mz, 0.7f, 4, 2);
    }

    /** What people dropped: barrels, a broken chair, a cauldron, a dropped bundle, candles. */
    static void belongings(Canvas c, Spec s) {
        int w = s.w, d = s.d;
        clear(c, s, 3);
        body(c, w / 2, d / 2, s.x % 2 == 0 ? "citizen" : "farmer", c.chance(1, 0, 1, 299, 3), "s");
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
    }

    /** Survivors who have nowhere to go: a family under a blanket, a child clinging to its mother. */
    static void huddle(Canvas c, Spec s) {
        int w = s.w, d = s.d;
        clear(c, s, 3);
        trample(c, w, d, 10, 30);
        for (int x = 1; x < w - 1; x++) {
            for (int z = 1; z < d - 1; z++) {
                if (c.chance(x, 0, z, 311, 4)) continue;
                putOpen(c, x, 1, z, c.chance(x, 1, z, 313, 3) ? b(Blocks.GRAY_CARPET) : b(Blocks.BROWN_CARPET));
            }
        }
        int cx = w / 2, cz = d / 2;
        person(c, cx, cz, "huddle_weep", "citizen");
        person(c, cx + 1, cz, "cling", "child");
        person(c, cx - 2, cz + 1, "huddle", "farmer");
        person(c, cx + 2, cz - 2, "kneel_weep", "citizen");
        if (s.variant % 2 == 1) person(c, cx - 1, cz - 2, "huddle_weep", "child");
        putOpen(c, cx + 3 < w ? cx + 3 : cx - 3, 1, cz, B.face(b(Blocks.BARREL), Direction.UP));
        putOpen(c, cx, 1, cz + 3 < d ? cz + 3 : cz - 3, b(Blocks.CAMPFIRE).setValue(CampfireBlock.LIT, false));
    }
}
