package com.skycraft.tamriel.settlement.grammar;

import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.build.B;
import com.skycraft.tamriel.settlement.build.Canvas;
import com.skycraft.tamriel.settlement.build.Props;
import com.skycraft.tamriel.settlement.build.Works;
import com.skycraft.tamriel.settlement.kit.Palette;
import com.skycraft.tamriel.settlement.plan.Spec;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * The life between buildings: street furniture and yard clutter ({@link com.skycraft.tamriel.settlement.plan.Archetype#YARD}),
 * animal pens with their animals, the work grounds of outlying sites (felled forest, drying racks, hives, vine rows,
 * rice paddies, moon sugar, kwama nests, Dwemer salvage), and quarries. Small clutter stands on the real ground of
 * its own column, so it never floats or sinks whatever the planner's height estimate was.
 */
public final class Outdoors {
    private Outdoors() {}

    // ---- yard kinds (Spec.variant of a YARD)
    public static final int CART = 0, WOODPILE = 1, CRATES = 2, HAY = 3, LAUNDRY = 4, BENCH = 5, LAMP_POST = 6, BRAZIER = 7,
            NOTICE_BOARD = 8, WELL = 9, FOUNTAIN = 10, STATUE = 11, FLOWER_BED = 12, VENDOR = 13, DUMMY = 14, COOP = 15,
            TREE = 16, SIGNPOST = 17, BARRELS = 18, KENNEL = 19, SHRINE = 20, TROUGH = 21;
    public static final int YARD_KINDS = 22;

    // ---- camp kinds (Spec.variant of a CAMP)
    public static final int FELLED = 0, DRYING = 1, HUNTING = 2, APIARY = 3, VINEYARD = 4, PADDY = 5, SUGAR = 6, KWAMA = 7,
            SALVAGE = 8, CAMPFIRE = 9, ORCHARD = 10, LOG_YARD = 11,
    /** Clears the trees and undergrowth over a tile of the built area (painted before everything else). */
            CLEARING = 12;

    // ------------------------------------------------------------------ helpers

    /** The y (relative to the canvas floor) of the solid ground under local (x, z), or 0 when recording / outside. */
    static int ground(Canvas c, int x, int z) {
        if (c.recording()) return 0;
        return c.wground(c.wx(x, z), c.wz(x, z)) - c.oy;
    }

    /** Sets a block {@code dy} above the real ground of its column. */
    static void gset(Canvas c, int x, int dy, int z, BlockState s) {
        c.set(x, ground(c, x, z) + dy, z, s);
    }

    /** Spawns a persistent animal standing on the ground of local (x, z) (only in the chunk being generated). */
    public static void animal(Canvas c, int x, int y, int z, EntityType<?> type) {
        if (c.recording() || type == null || c.level == null) return;
        int wx = c.wx(x, z), wz = c.wz(x, z), wy = c.oy + y;
        if (!c.okWorld(wx, wy, wz)) return;
        try {
            Entity e = type.create(c.level.getLevel());
            if (e == null) return;
            e.moveTo(wx + 0.5, wy, wz + 0.5, (float) c.roll(x, y, z, 81, 360), 0f);
            if (e instanceof Mob mob) {
                mob.setPersistenceRequired();
                mob.finalizeSpawn(c.level, c.level.getCurrentDifficultyAt(e.blockPosition()), MobSpawnType.STRUCTURE, null, null);
            }
            if (e instanceof Wolf wolf) {
                // a town dog: tame (to nobody in particular), so it leaves the sheep alone
                wolf.setTame(true);
                wolf.setOwnerUUID(new java.util.UUID(c.hash(wx, wy, wz, 83), c.seed));
            }
            c.level.addFreshEntityWithPassengers(e);
        } catch (RuntimeException ignored) {
            // an animal is never worth a failed chunk
        }
    }

    public static EntityType<?> animalType(String id) {
        return switch (id) {
            case "cow" -> EntityType.COW;
            case "sheep" -> EntityType.SHEEP;
            case "pig" -> EntityType.PIG;
            case "goat" -> EntityType.GOAT;
            case "horse" -> EntityType.HORSE;
            case "donkey" -> EntityType.DONKEY;
            case "mule" -> EntityType.MULE;
            case "camel" -> EntityType.CAMEL;
            case "llama" -> EntityType.LLAMA;
            case "cat" -> EntityType.CAT;
            case "dog" -> EntityType.WOLF;
            case "frog" -> EntityType.FROG;
            case "rabbit" -> EntityType.RABBIT;
            case "turtle" -> EntityType.TURTLE;
            default -> EntityType.CHICKEN;
        };
    }

    // ------------------------------------------------------------------ yards and street furniture

    public static void yard(Canvas c, Palette p, Spec s, Style st) {
        int w = s.w, d = s.d;
        switch (Math.floorMod(s.variant, YARD_KINDS)) {
            case CART -> cart(c, p, w, d);
            case WOODPILE -> {
                BlockState log = B.axis(p.post.hasProperty(BlockStateProperties.AXIS) ? p.post : B.s(Blocks.SPRUCE_LOG), c.dir(Direction.EAST).getAxis());
                for (int x = 0; x < w; x++) {
                    gset(c, x, 1, 0, log);
                    if (x > 0 && x < w - 1) gset(c, x, 2, 0, log);
                }
                gset(c, w - 1, 1, d - 1, B.axis(B.s(Blocks.STRIPPED_SPRUCE_LOG), Direction.Axis.Y));
            }
            case CRATES, BARRELS -> {
                for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
                    if (c.chance(x, 0, z, 7, 3)) continue;
                    BlockState box = s.variant == CRATES && c.chance(x, 1, z, 9, 2) ? p.planks : B.face(B.s(Blocks.BARREL), Direction.UP);
                    gset(c, x, 1, z, box);
                    if (c.chance(x, 2, z, 11, 3)) gset(c, x, 2, z, B.face(B.s(Blocks.BARREL), Direction.UP));
                }
            }
            case HAY -> {
                for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
                    gset(c, x, 1, z, B.s(Blocks.HAY_BLOCK));
                    if ((x + z) % 2 == 0 && c.chance(x, 0, z, 13, 2)) gset(c, x, 2, z, B.s(Blocks.HAY_BLOCK));
                }
            }
            case LAUNDRY -> {
                gset(c, 0, 1, 0, p.fence);
                gset(c, 0, 2, 0, p.fence);
                gset(c, w - 1, 1, 0, p.fence);
                gset(c, w - 1, 2, 0, p.fence);
                int gy = Math.max(ground(c, 0, 0), ground(c, w - 1, 0)) + 3;
                String[] cloth = {"white", "light_blue", "red", "yellow", "white", "brown"};
                for (int x = 0; x < w; x++) {
                    c.set(x, gy, 0, x == 0 || x == w - 1 ? p.fence : B.s(Blocks.CHAIN).setValue(BlockStateProperties.AXIS, c.dir(Direction.EAST).getAxis()));
                    if (x > 0 && x < w - 1 && c.chance(x, 0, 0, 15, 3) == false) c.set(x, gy - 1, 0, Materials.carpet(cloth[c.roll(x, 0, 0, 17, cloth.length)]));
                }
            }
            case BENCH -> {
                for (int x = 0; x < w; x++) gset(c, x, 1, 0, B.stair(p.stairs, Direction.SOUTH, false));
            }
            case LAMP_POST -> {
                int g = ground(c, 0, 0);
                c.set(0, g + 1, 0, p.fence);
                c.set(0, g + 2, 0, p.fence);
                c.set(0, g + 3, 0, p.light.hasProperty(BlockStateProperties.HANGING) ? p.light : B.s(Blocks.LANTERN));
            }
            case BRAZIER -> {
                int g = ground(c, 0, 0);
                c.set(0, g + 1, 0, p.wallLow);
                c.set(0, g + 2, 0, B.lit(B.s(Blocks.CAMPFIRE), true).setValue(CampfireBlock.SIGNAL_FIRE, false));
            }
            case NOTICE_BOARD -> {
                for (int x = 0; x < Math.max(3, w); x++) {
                    c.set(x, 0, 0, p.path2);
                    c.foundation(x, -1, 0, p.foundation, 6);
                }
                for (int y = 1; y <= 3; y++) {
                    c.set(0, y, 0, B.axis(p.post, Direction.Axis.Y));
                    c.set(2, y, 0, B.axis(p.post, Direction.Axis.Y));
                }
                c.set(1, 2, 0, p.planks);
                c.set(1, 3, 0, p.planks);
                c.set(0, 4, 0, B.slab(p.roofSlab, false));
                c.set(1, 4, 0, B.slab(p.roofSlab, false));
                c.set(2, 4, 0, B.slab(p.roofSlab, false));
                c.sign(1, 2, -1, p.sign, Direction.NORTH, net.minecraft.network.chat.Component.empty(),
                        net.minecraft.network.chat.Component.translatable("settlement.skycraft.notice.title"),
                        net.minecraft.network.chat.Component.translatable("settlement.skycraft.notice.line"));
                c.set(1, 3, -1, B.face(B.s(Blocks.WALL_TORCH), Direction.NORTH));
                c.anchor(AnchorType.MEETING, 1, 1, -2);
            }
            case WELL -> {
                for (int x = -1; x <= 3; x++) for (int z = -1; z <= 3; z++) {
                    c.set(x, 0, z, p.plaza);
                    c.foundation(x, -1, z, p.foundation, 6);
                }
                if (!c.recording()) c.clearPlants(-1, 1, -1, 3, 5, 3);
                Works.well(c, p, 0, 0);
                c.set(1, 2, 0, B.s(Blocks.CHAIN));
            }
            case FOUNTAIN -> fountain(c, p, w, d);
            case STATUE -> {
                for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
                    c.set(x, 0, z, p.plaza2);
                    c.foundation(x, -1, z, p.foundation, 6);
                }
                Works.statue(c, p, w / 2, d / 2);
            }
            case FLOWER_BED -> {
                BlockState[] flowers = {B.s(Blocks.POPPY), B.s(Blocks.DANDELION), B.s(Blocks.CORNFLOWER), B.s(Blocks.OXEYE_DAISY),
                        B.s(Blocks.ALLIUM), B.s(Blocks.AZURE_BLUET), B.s(Blocks.LILY_OF_THE_VALLEY), B.s(Blocks.RED_TULIP)};
                for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
                    int g = ground(c, x, z);
                    boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                    if (edge) {
                        c.set(x, g + 1, z, B.slab(p.trimSlab, false));
                    } else {
                        c.set(x, g, z, B.s(Blocks.GRASS_BLOCK));
                        c.set(x, g + 1, z, flowers[c.roll(x, 0, z, 19, flowers.length)]);
                    }
                }
            }
            case VENDOR -> {
                cart(c, p, w, d);
                c.anchor(AnchorType.MERCHANT, w / 2, 1, d);
            }
            case DUMMY -> {
                int g = ground(c, 0, 0);
                c.set(0, g + 1, 0, p.fence);
                c.set(0, g + 2, 0, B.s(Blocks.HAY_BLOCK));
                c.set(0, g + 3, 0, B.face(B.s(Blocks.CARVED_PUMPKIN), c.dir(Direction.NORTH)));
                gset(c, w > 1 ? 1 : 0, 1, d > 1 ? 1 : 0, B.s(Blocks.TARGET));
            }
            case COOP -> {
                for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
                    boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                    if (edge) gset(c, x, 1, z, x == w / 2 && z == 0 ? B.face(p.gate, Direction.NORTH) : p.fence);
                    else if (x == 1 && z == d - 2) gset(c, x, 1, z, B.s(Blocks.HAY_BLOCK));
                }
                for (int i = 0; i < 3; i++) animal(c, 1 + i % Math.max(1, w - 2), ground(c, 1, 1) + 1, 1 + i / 2 % Math.max(1, d - 2), EntityType.CHICKEN);
            }
            case TREE -> {
                int g = ground(c, w / 2, d / 2);
                BlockState log = st.culture.equals("bosmer") ? B.id("skycraft:graht_oak_log", Blocks.JUNGLE_LOG) : B.s(Blocks.OAK_LOG);
                BlockState leaves = B.s(st.culture.equals("altmer") ? Blocks.FLOWERING_AZALEA_LEAVES : Blocks.OAK_LEAVES);
                if (leaves.hasProperty(LeavesBlock.PERSISTENT)) leaves = leaves.setValue(LeavesBlock.PERSISTENT, true);
                for (int y = 1; y <= 4; y++) c.set(w / 2, g + y, d / 2, log);
                for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int dy = 3; dy <= 6; dy++) {
                    if (dx * dx + dz * dz + (dy - 4) * (dy - 4) <= 5 && c.roll(dx, dy, dz, 23, 6) != 0) c.setIfOpen(w / 2 + dx, g + dy, d / 2 + dz, leaves);
                }
            }
            case SIGNPOST -> {
                int g = ground(c, 0, 0);
                c.set(0, g + 1, 0, B.axis(p.post, Direction.Axis.Y));
                c.set(0, g + 2, 0, B.axis(p.post, Direction.Axis.Y));
                c.set(0, g + 3, 0, p.fence);
                if (s.has(Spec.MAJOR)) c.anchor(AnchorType.GATE, 0, 1, 2);
            }
            case KENNEL -> {
                gset(c, 0, 1, 0, p.planks);
                gset(c, 1, 1, 0, B.stair(p.roof, Direction.WEST, false));
                animal(c, 1, ground(c, 1, 1) + 1, 1, c.chance(0, 0, 0, 3, 2) ? EntityType.WOLF : EntityType.CAT);
            }
            case SHRINE -> {
                int g = ground(c, 0, 0);
                c.set(0, g + 1, 0, p.wallLow);
                c.set(0, g + 2, 0, Props.shrine(st.faiths().isEmpty() ? "mara" : st.faiths().get(Math.floorMod(c.roll(0, 0, 0, 3, 64), st.faiths().size()))));
                Props.candles(c, w > 1 ? 1 : 0, g + 1, 0, 3);
            }
            case TROUGH -> {
                for (int x = 0; x < w; x++) gset(c, x, 1, 0, B.s(Blocks.WATER_CAULDRON).setValue(LayeredCauldronBlock.LEVEL, 3));
            }
            default -> {
            }
        }
    }

    static void cart(Canvas c, Palette p, int w, int d) {
        int g = Math.max(ground(c, 0, 0), ground(c, Math.max(0, w - 1), 0));
        int len = Math.max(2, w - 1);
        for (int x = 0; x < len; x++) c.set(x, g + 1, 0, B.slab(p.slab, true));
        c.set(0, g + 1, 1, B.face(p.trapdoor, Direction.NORTH));
        c.set(len - 1, g + 1, 1, B.face(p.trapdoor, Direction.NORTH));
        c.set(0, g + 1, -1, B.face(p.trapdoor, Direction.SOUTH));
        c.set(len - 1, g + 1, -1, B.face(p.trapdoor, Direction.SOUTH));
        BlockState[] goods = {B.s(Blocks.HAY_BLOCK), B.face(B.s(Blocks.BARREL), Direction.UP), B.s(Blocks.PUMPKIN), B.s(Blocks.MELON), Props.cheese()};
        for (int x = 0; x < len; x++) if (c.chance(x, 2, 0, 25, 2)) c.set(x, g + 2, 0, goods[c.roll(x, 2, 0, 27, goods.length)]);
        c.set(len, g + 1, 0, p.fence);
    }

    static void fountain(Canvas c, Palette p, int w, int d) {
        if (!c.recording()) c.clearPlants(0, 1, 0, w - 1, 5, d - 1);
        int cx = w / 2, cz = d / 2;
        for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
            boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
            c.set(x, 0, z, edge ? p.plaza2 : p.foundation);
            c.foundation(x, -1, z, p.foundation, 6);
            if (edge) c.set(x, 1, z, B.slab(p.trimSlab, false));
            else c.set(x, 0, z, B.s(Blocks.WATER));
        }
        c.set(cx, 0, cz, p.plaza2);
        c.set(cx, 1, cz, p.column.hasProperty(BlockStateProperties.AXIS) ? B.axis(p.column, Direction.Axis.Y) : p.column);
        c.set(cx, 2, cz, p.column.hasProperty(BlockStateProperties.AXIS) ? B.axis(p.column, Direction.Axis.Y) : p.column);
        c.set(cx, 3, cz, B.s(Blocks.WATER));
        c.anchor(AnchorType.MEETING, cx, 1, -1);
    }

    // ------------------------------------------------------------------ pens

    public static void pen(Canvas c, Palette p, Spec s, Style st) {
        int w = s.w, d = s.d;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean edge = x == 0 || z == 0 || x == w - 1 || z == d - 1;
                int g = ground(c, x, z);
                if (!c.recording()) c.clearPlants(x, g + 1, z, x, g + 3, z);
                if (!edge) {
                    if (c.chance(x, 0, z, 29, 4)) c.set(x, g, z, B.s(Blocks.COARSE_DIRT));
                    continue;
                }
                c.set(x, g + 1, z, z == 0 && x == w / 2 ? B.face(p.gate, Direction.NORTH) : p.fence);
            }
        }
        gset(c, 1, 1, d - 2, B.s(Blocks.HAY_BLOCK));
        gset(c, w - 2, 1, d - 2, B.s(Blocks.WATER_CAULDRON).setValue(LayeredCauldronBlock.LEVEL, 3));
        String kind = st.animals().isEmpty() ? "sheep" : st.animals().get(Math.floorMod(s.variant, st.animals().size()));
        if (kind.equals("horse") || kind.equals("camel")) kind = st.animals().size() > 1 ? st.animals().get(0) : "sheep";
        int n = Math.max(2, (w - 2) * (d - 2) / 6);
        for (int i = 0; i < n; i++) {
            int x = 1 + c.roll(i, 0, 0, 31, Math.max(1, w - 2)), z = 1 + c.roll(i, 0, 1, 33, Math.max(1, d - 2));
            animal(c, x, ground(c, x, z) + 1, z, animalType(kind));
        }
        c.anchor(AnchorType.FARMER, w / 2, 1, -1);
    }

    // ------------------------------------------------------------------ outlying work grounds

    public static void camp(Canvas c, Palette p, Spec s, Style st) {
        int w = s.w, d = s.d;
        if (s.variant == CLEARING) {
            if (c.recording()) return;
            for (int x = 0; x < w; x++) {
                for (int z = 0; z < d; z++) {
                    if (!c.touches(x, z, x, z)) continue;
                    int g = ground(c, x, z);
                    c.clearPlants(x, g + 1, z, x, g + 26, z);
                }
            }
            return;
        }
        if (!c.recording()) c.clearPlants(0, 1, 0, w - 1, 6, d - 1);
        switch (s.variant) {
            case FELLED, LOG_YARD -> felled(c, p, w, d, s.variant == LOG_YARD);
            case DRYING -> {
                for (int x = 1; x < w - 1; x += 3) for (int z = 1; z < d - 1; z++) {
                    gset(c, x, 1, z, p.fence);
                    gset(c, x, 2, z, z % 2 == 0 ? B.s(Blocks.DRIED_KELP_BLOCK) : p.fence);
                }
                gset(c, 0, 1, 0, B.face(B.s(Blocks.BARREL), Direction.UP));
                gset(c, 0, 1, 1, B.face(B.s(Blocks.SMOKER), Direction.EAST));
                c.anchor(AnchorType.FISHER, w / 2, 1, 0);
            }
            case HUNTING -> {
                campfire(c, p, w / 2, d / 2);
                gset(c, 0, 1, 0, Props.tanningRack());
                gset(c, w - 1, 1, 0, Props.tanningRack());
                for (int x = 1; x < w - 1; x += 2) {
                    gset(c, x, 1, d - 1, p.fence);
                    gset(c, x, 2, d - 1, Materials.carpet(c.chance(x, 0, 0, 3, 2) ? "brown" : "white"));
                }
                gset(c, 0, 1, d - 1, B.s(Blocks.BONE_BLOCK));
                c.anchor(AnchorType.HUNTER, w / 2, 1, 1);
            }
            case APIARY -> {
                BlockState[] flowers = {B.s(Blocks.CORNFLOWER), B.s(Blocks.DANDELION), B.s(Blocks.ALLIUM), B.s(Blocks.LILAC)};
                for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
                    int g = ground(c, x, z);
                    if (x % 3 == 1 && z % 3 == 1) {
                        c.set(x, g + 1, z, p.fence);
                        c.set(x, g + 2, z, B.s(Blocks.BEEHIVE).setValue(BeehiveBlock.HONEY_LEVEL, c.roll(x, 0, z, 35, 6)));
                    } else if (c.chance(x, 0, z, 37, 2)) {
                        c.set(x, g, z, B.s(Blocks.GRASS_BLOCK));
                        BlockState f = flowers[c.roll(x, 0, z, 39, flowers.length)];
                        if (!f.is(Blocks.LILAC)) c.set(x, g + 1, z, f);
                    }
                }
                c.anchor(AnchorType.FARMER, w / 2, 1, -1);
            }
            case VINEYARD, ORCHARD -> {
                BlockState leaves = B.s(s.variant == ORCHARD ? Blocks.FLOWERING_AZALEA_LEAVES : Blocks.AZALEA_LEAVES).setValue(LeavesBlock.PERSISTENT, true);
                for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
                    int g = ground(c, x, z);
                    if (x % 3 == 1) {
                        c.set(x, g, z, B.s(Blocks.COARSE_DIRT));
                        if (s.variant == ORCHARD) {
                            if (z % 4 == 1) {
                                for (int y = 1; y <= 2; y++) c.set(x, g + y, z, B.s(Blocks.OAK_LOG));
                                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) c.setIfOpen(x + dx, g + 3, z + dz, leaves);
                                c.setIfOpen(x, g + 4, z, leaves);
                            }
                        } else {
                            c.set(x, g + 1, z, z % 4 == 0 ? p.fence : B.s(Blocks.SWEET_BERRY_BUSH).setValue(SweetBerryBushBlock.AGE, 3));
                            c.set(x, g + 2, z, leaves);
                        }
                    } else {
                        c.set(x, g, z, B.s(Blocks.GRASS_BLOCK));
                    }
                }
                c.anchor(AnchorType.FARMER, w / 2, 1, -1);
            }
            case PADDY -> {
                for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
                    int g = ground(c, x, z);
                    boolean bund = x == 0 || z == 0 || x == w - 1 || z == d - 1 || z % 4 == 0;
                    if (bund) {
                        c.set(x, g, z, B.s(Blocks.PACKED_MUD));
                    } else {
                        c.set(x, g - 1, z, B.s(Blocks.MUD));
                        c.set(x, g, z, B.s(Blocks.WATER));
                        c.set(x, g, z, c.roll(x, 0, z, 41, 4) != 0 ? B.s(Blocks.SEAGRASS) : B.s(Blocks.WATER));
                    }
                }
                c.anchor(AnchorType.FARMER, w / 2, 1, -1);
            }
            case SUGAR -> {
                for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
                    int g = ground(c, x, z);
                    if (x % 3 == 0) {
                        c.set(x, g, z, B.s(Blocks.WATER));
                        c.set(x, g - 1, z, B.s(Blocks.SAND));
                    } else {
                        c.set(x, g, z, B.s(Blocks.SAND));
                        int h = 1 + c.roll(x, 0, z, 43, 3);
                        for (int y = 1; y <= h; y++) c.set(x, g + y, z, B.s(Blocks.SUGAR_CANE));
                    }
                }
                c.anchor(AnchorType.WORKER, w / 2, 1, -1);
            }
            case KWAMA -> {
                for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
                    int g = ground(c, x, z);
                    c.set(x, g, z, c.chance(x, 0, z, 45, 3) ? B.s(Blocks.PACKED_MUD) : B.s(Blocks.COARSE_DIRT));
                    if (c.chance(x, 1, z, 47, 5)) c.set(x, g + 1, z, c.chance(x, 2, z, 49, 3) ? B.s(Blocks.SNIFFER_EGG) : B.s(Blocks.TURTLE_EGG));
                }
                gset(c, 0, 1, 0, B.face(B.s(Blocks.BARREL), Direction.UP));
                c.anchor(AnchorType.MINER, w / 2, 1, d / 2);
            }
            case SALVAGE -> {
                BlockState[] scrap = {B.s(Blocks.WAXED_OXIDIZED_CUT_COPPER), B.s(Blocks.WAXED_WEATHERED_COPPER), B.s(Blocks.WAXED_EXPOSED_CUT_COPPER),
                        B.s(Blocks.CHAIN), B.s(Blocks.LIGHTNING_ROD), B.id("skycraft:dwemer_pipes", Blocks.WAXED_CUT_COPPER)};
                for (int x = 0; x < w; x++) for (int z = 0; z < d; z++) {
                    if (!c.chance(x, 0, z, 51, 3)) continue;
                    gset(c, x, 1, z, scrap[c.roll(x, 1, z, 53, scrap.length)]);
                    if (c.chance(x, 2, z, 55, 3)) gset(c, x, 2, z, scrap[c.roll(x, 2, z, 57, scrap.length)]);
                }
                campfire(c, p, w / 2, d / 2);
                c.anchor(AnchorType.WORKER, w / 2, 1, d / 2 + 1);
            }
            case CAMPFIRE -> {
                campfire(c, p, w / 2, d / 2);
                gset(c, w / 2 - 2, 1, d / 2, B.axis(B.s(Blocks.SPRUCE_LOG), c.dir(Direction.SOUTH).getAxis()));
                gset(c, w / 2 + 2, 1, d / 2, B.axis(B.s(Blocks.SPRUCE_LOG), c.dir(Direction.SOUTH).getAxis()));
                gset(c, w / 2, 1, d / 2 + 2, B.id("skycraft:bedroll", Blocks.BROWN_CARPET));
            }
            default -> {
            }
        }
    }

    static void campfire(Canvas c, Palette p, int x, int z) {
        int g = ground(c, x, z);
        c.set(x, g + 1, z, B.lit(B.s(Blocks.CAMPFIRE), true).setValue(CampfireBlock.SIGNAL_FIRE, false));
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            c.set(x + dir.getStepX(), g, z + dir.getStepZ(), B.s(Blocks.COBBLESTONE));
        }
        c.set(x + 1, g + 1, z + 1, Props.cookingPot());
    }

    /** A felled stretch of forest: stumps, log piles, sawhorses, chips and a chopping block. */
    static void felled(Canvas c, Palette p, int w, int d, boolean yard) {
        BlockState log = B.s(Blocks.SPRUCE_LOG);
        BlockState sawn = B.id("skycraft:sawn_log", Blocks.STRIPPED_SPRUCE_LOG);
        Direction.Axis along = c.dir(Direction.EAST).getAxis();
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                int g = ground(c, x, z);
                if (c.chance(x, 0, z, 59, 4)) c.set(x, g, z, B.s(Blocks.COARSE_DIRT));
                if (!yard && c.chance(x, 1, z, 61, 9)) {
                    c.set(x, g + 1, z, B.axis(log, Direction.Axis.Y));
                    if (c.chance(x, 2, z, 63, 3)) c.set(x, g + 2, z, B.s(Blocks.SPRUCE_PRESSURE_PLATE));
                }
            }
        }
        // log piles
        for (int k = 0; k < (yard ? 3 : 2); k++) {
            int pz = 1 + k * 3;
            if (pz >= d - 1) break;
            for (int x = 1; x < w - 2; x++) {
                int g = ground(c, x, pz);
                c.set(x, g + 1, pz, B.axis(yard && k % 2 == 1 ? sawn : log, along));
                c.set(x, g + 1, pz + 1, B.axis(log, along));
                if (x > 1 && x < w - 3) c.set(x, g + 2, pz, B.axis(log, along));
            }
        }
        // sawhorses and the chopping block
        int sx = w - 2, sz = d - 2;
        gset(c, sx, 1, sz, p.fence);
        gset(c, sx, 2, sz, B.slab(p.slab, false));
        gset(c, sx - 1, 1, sz, p.fence);
        gset(c, sx - 1, 2, sz, B.slab(p.slab, false));
        gset(c, 0, 1, d - 1, B.axis(B.s(Blocks.STRIPPED_SPRUCE_LOG), Direction.Axis.Y));
        c.anchor(AnchorType.LUMBERJACK, w / 2, 1, d / 2);
    }

    // ------------------------------------------------------------------ quarries

    /** A stepped open-cast quarry cut into the ground, with a crane, stone blocks and a hut-side yard. */
    public static void quarry(Canvas c, Palette p, Spec s) {
        int w = s.w, d = s.d;
        int depth = Math.max(4, s.h - 2);
        BlockState stone = s.culture == com.skycraft.tamriel.settlement.Culture.DUNMER.ordinal() ? B.id("skycraft:volcanic_rock", Blocks.BASALT)
                : s.culture == com.skycraft.tamriel.settlement.Culture.KHAJIIT.ordinal() || s.culture == com.skycraft.tamriel.settlement.Culture.REDGUARD.ordinal()
                ? B.s(Blocks.SANDSTONE) : B.s(Blocks.STONE);
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                int edge = Math.min(Math.min(x, w - 1 - x), Math.min(z, d - 1 - z));
                int cut = Math.min(depth, edge);
                for (int y = 1; y <= 8; y++) c.air(x, y, z);
                for (int y = 0; y > -cut; y--) c.air(x, y, z);
                c.set(x, -cut, z, edge == 0 ? p.path2 : (c.chance(x, -cut, z, 65, 6) ? B.id("skycraft:quarried_stone", Blocks.COBBLESTONE) : stone));
                // the stepped walls
                c.foundation(x, -cut - 1, z, stone, 4);
            }
        }
        // stacked cut blocks and a crane
        for (int i = 0; i < 3; i++) c.set(1 + i, 1, 0, B.s(Blocks.STONE_BRICKS));
        for (int y = 1; y <= 6; y++) c.set(w - 1, y, 0, B.axis(p.post, Direction.Axis.Y));
        for (int x = w - 4; x < w; x++) c.set(x, 6, 0, B.axis(p.beam, Direction.Axis.X));
        for (int y = 2; y <= 5; y++) c.set(w - 4, y, 0, B.s(Blocks.CHAIN));
        c.set(w / 2, -depth + 1, d / 2, B.s(Blocks.STONECUTTER));
        c.anchor(AnchorType.WORKER, w / 2, -depth + 1, d / 2 - 1);
        c.anchor(AnchorType.MINER, 2, 1, 1);
    }
}
