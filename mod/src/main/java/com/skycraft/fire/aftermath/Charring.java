package com.skycraft.fire.aftermath;

import com.skycraft.fire.FireBlocks;
import com.skycraft.tamriel.settlement.AnchorType;
import com.skycraft.tamriel.settlement.build.B;
import com.skycraft.tamriel.settlement.build.Builders;
import com.skycraft.tamriel.settlement.build.Canvas;
import com.skycraft.tamriel.settlement.plan.Spec;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.AbstractGlassBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * The burnt look of a ruin, applied to any settlement builder through {@link Canvas#burn}: {@link #apply} runs on every
 * block a burnt piece writes (wood charred or gone, cloth and glass gone, stone scorched), and {@link #finish} adds what
 * a fire leaves behind (collapsed corners, rubble, fires, soot, ash) and records the fire sites that
 * {@link com.skycraft.fire.FireAnchors} turns into far-visible fires.
 *
 * <p>Severity: 1 scorched (some wood charred, most windows broken, soot), 2 burnt, 3 gutted (also holes in the walls
 * and roof, a collapsed quarter). Everything is a pure function of the block position and the building's seed, so the
 * result is the same whichever chunk paints it and in what order.</p>
 */
public final class Charring {
    private Charring() {}

    private static int hash(int x, int y, int z, long seed, int salt) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L) ^ (salt * 0xD6E8FEB86659FD93L);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (int) ((h >>> 20) & 0x7fffffffL) % 100;
    }

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /**
     * The burnt version of a block about to be placed at (x, y, z) ({@code ly} = height above the building's floor).
     * Returns the same state when nothing burns there, and air where it burnt away.
     */
    public static BlockState apply(BlockState s, int x, int y, int z, int ly, long seed, int severity) {
        Block b = s.getBlock();
        int h = hash(x, y, z, seed, 1);
        boolean above = ly >= 1;
        // wood
        if (s.is(BlockTags.LOGS)) {
            if (severity >= 3 && above && ly > 3 && h < 14) return AIR;
            if (severity == 1 && h >= 55) return s;
            BlockState out = FireBlocks.CHARRED_LOG.get().defaultBlockState();
            if (s.hasProperty(RotatedPillarBlock.AXIS)) out = out.setValue(RotatedPillarBlock.AXIS, s.getValue(RotatedPillarBlock.AXIS));
            return out;
        }
        if (s.is(BlockTags.PLANKS)) {
            if (severity >= 2 && above && h >= 90 - (severity - 2) * 8) return AIR;
            if (severity == 1 && h >= 45) return s;
            return FireBlocks.CHARRED_PLANKS.get().defaultBlockState();
        }
        if (s.is(BlockTags.WOODEN_STAIRS)) {
            if (severity >= 3 && above && h >= 88) return AIR;
            if (severity == 1 && h >= 45) return s;
            return copy(s, FireBlocks.CHARRED_PLANKS_STAIRS.get().defaultBlockState(), StairBlock.FACING, StairBlock.HALF, StairBlock.SHAPE,
                    BlockStateProperties.WATERLOGGED);
        }
        if (s.is(BlockTags.WOODEN_SLABS)) {
            if (severity >= 3 && above && h >= 85) return AIR;
            if (severity == 1 && h >= 45) return s;
            return copy(s, FireBlocks.CHARRED_PLANKS_SLAB.get().defaultBlockState(), SlabBlock.TYPE, BlockStateProperties.WATERLOGGED);
        }
        if (s.is(BlockTags.WOODEN_FENCES) || s.is(BlockTags.FENCE_GATES)) {
            int fh = hash(x, 0, z, seed, 3);
            return (severity >= 2 && above && fh < 55) ? AIR : s;
        }
        if (s.is(BlockTags.WOODEN_DOORS) || s.is(BlockTags.WOODEN_TRAPDOORS) || s.is(BlockTags.WOODEN_BUTTONS) || s.is(BlockTags.WOODEN_PRESSURE_PLATES)) {
            // both halves of a door roll the same
            int dh = hash(x, 0, z, seed, 2);
            return (above && dh < (severity >= 2 ? 70 : 35)) ? AIR : s;
        }
        // cloth and glass
        if (s.is(BlockTags.WOOL) || s.is(BlockTags.WOOL_CARPETS) || s.is(BlockTags.BEDS) || s.is(BlockTags.BANNERS) || s.is(BlockTags.CANDLES)) {
            return (above && h < (severity >= 2 ? 90 : 55)) ? AIR : s;
        }
        // glass blocks and panes (iron bars stay)
        if (b instanceof AbstractGlassBlock || (b instanceof IronBarsBlock && !s.is(Blocks.IRON_BARS))) {
            return (above && h < 86) ? AIR : s;
        }
        if (s.is(Blocks.HAY_BLOCK) || s.is(Blocks.BAMBOO_MOSAIC_STAIRS) || s.is(Blocks.BAMBOO_MOSAIC_SLAB) || s.is(Blocks.BAMBOO_MOSAIC)) {
            return (above && h < 70) ? AIR : (h < 85 ? B.id("skycraft:ash_block", Blocks.GRAY_CONCRETE_POWDER) : s);
        }
        if (s.is(Blocks.LANTERN) || s.is(Blocks.SOUL_LANTERN) || s.is(Blocks.TORCH) || s.is(Blocks.WALL_TORCH)) {
            return (above && severity >= 2 && h < 60) ? AIR : s;
        }
        // stone
        if (severity >= 2) {
            if (s.is(Blocks.STONE_BRICKS) || s.is(Blocks.CRACKED_STONE_BRICKS) || s.is(Blocks.MOSSY_STONE_BRICKS)) {
                return h < 60 ? FireBlocks.SCORCHED_STONE_BRICKS.get().defaultBlockState() : s;
            }
            if (s.is(Blocks.COBBLESTONE) || s.is(Blocks.STONE) || s.is(Blocks.ANDESITE) || s.is(Blocks.MOSSY_COBBLESTONE)
                    || s.is(Blocks.SMOOTH_STONE) || s.is(Blocks.DIORITE)) {
                return h < 55 ? FireBlocks.SCORCHED_STONE.get().defaultBlockState() : s;
            }
        } else if (s.is(Blocks.STONE_BRICKS) && h < 25) {
            return FireBlocks.SCORCHED_STONE_BRICKS.get().defaultBlockState();
        }
        return s;
    }

    @SafeVarargs
    private static BlockState copy(BlockState from, BlockState to, Property<?>... props) {
        for (Property<?> p : props) {
            if (from.hasProperty(p) && to.hasProperty(p)) to = with(to, p, from);
        }
        return to;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState to, Property<T> p, BlockState from) {
        return to.setValue(p, from.getValue(p));
    }

    // ------------------------------------------------------------------ what a fire leaves behind

    /**
     * Adds the aftermath to a burnt piece after it was built: rubble, ash, soot, fires, a collapsed quarter, and the
     * fire-site anchors. Called by {@code Builders.build} for pieces with {@link Spec#BURNT}.
     */
    public static void finish(Canvas c, Spec s) {
        boolean gutted = s.has(Spec.RUINED);
        boolean burning = (s.variant & 1) != 0 && gutted;
        boolean collapsed = (s.variant & 2) != 0 && gutted;
        int w = Math.max(3, s.w), d = Math.max(3, s.d);
        // fire sites are recorded in recording mode only (anchors); the blocks below are painted only when generating
        if (burning) {
            c.anchor(AnchorType.FIRE_SITE, w / 2, 5, d / 2, "1.15|" + (6 + Math.min(8, w / 2)) + "|3|1");
            if (w >= 9 && d >= 9) c.anchor(AnchorType.FIRE_SITE, w / 4, 3, d * 3 / 4, "0.8|5|2|1");
        } else if (gutted) {
            c.anchor(AnchorType.FIRE_SITE, w / 2, 3, d / 2, "0.35|3|1|1");
        }
        if (gutted) {
            // the people who did not get out: one fallen at the door (most of the time), a survivor weeping beside a burning home
            String[] roles = {"citizen", "farmer", "lumberjack", "imperial_soldier", "hunter", "blacksmith"};
            if (c.roll(w / 2, 0, d / 2, 97, 100) < 70) {
                int bx = w / 2 + c.roll(w, 1, d, 99, 3) - 1;
                String role = roles[c.roll(w, 2, d, 101, roles.length)];
                boolean loot = c.roll(w, 3, d, 103, 100) < 30;
                c.anchor(AnchorType.TABLEAU, bx, 1, -2, "body|" + role + "|" + (loot ? "l" : "") + (c.roll(w, 4, d, 105, 2) == 0 ? "f" : "s"));
            }
            if (burning && c.roll(w / 2, 5, d / 2, 107, 100) < 55) {
                c.anchor(AnchorType.TABLEAU, w + 1, 1, 2, "stand_weep|citizen|");
            }
        }
        if (c.recording() || !c.touches(-3, -3, w + 2, d + 2)) return;
        int hh = Math.min(26, Math.max(s.h, Builders.height(s)));
        if (collapsed) collapse(c, s, w, d, hh);
        if (gutted) {
            for (int x = 1; x < w - 1; x++) {
                for (int z = 1; z < d - 1; z++) interior(c, x, z, burning);
            }
        }
        // soot and ash on the ground round the walls, and the odd beam that came down
        for (int x = -3; x < w + 3; x++) {
            for (int z = -3; z < d + 3; z++) {
                boolean inside = x >= 0 && x < w && z >= 0 && z < d;
                if (inside) continue;
                int r = c.roll(x, 0, z, 91, 100);
                int gy = ground(c, x, z);
                if (!c.touches(x, z, x, z)) continue;
                if (r < 22) c.setIfOpen(x, gy + 1, z, FireBlocks.SOOT_STAIN.get().defaultBlockState());
                else if (r < 30) c.setIfOpen(x, gy + 1, z, FireBlocks.TIMBER_DEBRIS.get().defaultBlockState());
                else if (r < 36) c.setIfOpen(x, gy + 1, z, B.id("skycraft:ash_layer", Blocks.SNOW));
                else if (r < 38 && gutted) c.setIfOpen(x, gy + 1, z, FireBlocks.SMOULDERING_EMBERS.get().defaultBlockState());
            }
        }
        // a fire, a bedroll or a stain whose floor fell in or burnt through must not hang in the air
        Supports.sweep(c, -3, -3, w + 2, d + 2, -2, hh + 6);
    }

    private static int ground(Canvas c, int x, int z) {
        return c.wground(c.wx(x, z), c.wz(x, z)) - c.oy;
    }

    private static void interior(Canvas c, int x, int z, boolean burning) {
        if (!c.touches(x, z, x, z)) return;
        if (!c.get(x, 1, z).isAir() || c.get(x, 0, z).isAir()) return;
        int r = c.roll(x, 1, z, 71, 100);
        BlockState fire = FireBlocks.BIG_FIRE.get().defaultBlockState();
        if (burning && r < 13) {
            int hgt = 1 + c.roll(x, 2, z, 73, 3);
            c.set(x, 1, z, fire);
            for (int y = 2; y <= hgt; y++) {
                if (!c.get(x, y, z).isAir()) break;
                c.set(x, y, z, y == hgt ? FireBlocks.FLAME_TIP.get().defaultBlockState() : FireBlocks.FLAME_COLUMN.get().defaultBlockState());
            }
        } else if (burning && r < 24) {
            c.set(x, 1, z, FireBlocks.RUBBLE_FIRE.get().defaultBlockState());
        } else if (r < 38) {
            c.set(x, 1, z, c.chance(x, 1, z, 75, 3) ? FireBlocks.SPLINTERED_WRECKAGE.get().defaultBlockState()
                    : FireBlocks.TIMBER_DEBRIS.get().defaultBlockState());
        } else if (r < 48) {
            c.set(x, 1, z, B.id("skycraft:ash_layer", Blocks.SNOW));
        } else if (r < 54 && !burning) {
            c.set(x, 1, z, FireBlocks.SMOULDERING_EMBERS.get().defaultBlockState());
        } else if (r < 78) {
            c.set(x, 1, z, FireBlocks.SOOT_STAIN.get().defaultBlockState());
        }
    }

    /** One quarter of the building has fallen in: walls and roof gone above a ragged line, a few posts left standing. */
    private static void collapse(Canvas c, Spec s, int w, int d, int hh) {
        int k = Math.floorMod(s.x * 31 + s.z * 17, 4);
        int rw = Math.max(3, Math.round(w * 0.55f)), rd = Math.max(3, Math.round(d * 0.5f));
        int x0 = (k & 1) == 0 ? -1 : w - rw, x1 = (k & 1) == 0 ? rw : w;
        int z0 = (k & 2) == 0 ? -1 : d - rd, z1 = (k & 2) == 0 ? rd : d;
        BlockState beam = FireBlocks.CHARRED_BEAM.get().defaultBlockState();
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                if (!c.touches(x, z, x, z)) continue;
                int jag = c.roll(x, 0, z, 81, 4);                  // ragged edge of the break
                int keep = 2 + jag;
                boolean post = (x % 4 == 0) && (z % 4 == 0);
                for (int y = keep; y <= hh + 6; y++) c.air(x, y, z);
                if (post) {
                    for (int y = 1; y <= keep + 2; y++) c.set(x, y, z, B.axis(beam, Direction.Axis.Y));
                }
                int edgeDist = Math.min(Math.min(x - x0, x1 - x), Math.min(z - z0, z1 - z));
                if (edgeDist >= 0 && x >= 0 && x < w && z >= 0 && z < d && c.chance(x, 1, z, 83, 3)) {
                    c.set(x, 1, z, c.chance(x, 1, z, 85, 2) ? FireBlocks.SPLINTERED_WRECKAGE.get().defaultBlockState()
                            : B.s(Blocks.COBBLESTONE_SLAB));
                }
            }
        }
        // a rafter or two hanging over the break from the standing walls
        for (int i = 0; i < 2; i++) {
            int bx = (k & 1) == 0 ? rw + 1 : w - rw - 2;
            int bz = 1 + c.roll(i, 0, i, 87, Math.max(1, d - 2));
            int by = Math.min(hh - 3, 5 + c.roll(bz, i, 0, 89, 4));
            if (bx < 0 || bx >= w || bz < 0 || bz >= d) continue;
            if (c.get(bx, by, bz).isAir() && c.get(bx, by - 1, bz).isAir()) continue;
            for (int t = 0; t < 3; t++) {
                int xx = (k & 1) == 0 ? bx - t : bx + t;
                if (xx < 0 || xx >= w) break;
                c.set(xx, by, bz, B.axis(beam, Direction.Axis.X));
            }
        }
    }
}
