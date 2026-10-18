package com.skycraft.seas.world;

import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SkullBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;

/**
 * The builders of the sea-bed sites ({@link SeaSiteFeature}). Every site stays within 14 blocks of the chunk centre,
 * sits on the sea bed column by column, keeps its water, and leaves its inhabitants to a {@link LairMarkerBlock}.
 * Loot tables are under {@code skycraft:chests/seas/}; the lore notes are books of the lore module registered by
 * {@code SeasLore}.
 */
final class Undersea {
    private Undersea() {}

    private static final int SEA = 63;

    private static BlockState s(Block b) {
        return b.defaultBlockState();
    }

    private static Identifier loot(String name) {
        return SiteKit.loot("chests/seas/" + name);
    }

    /** A placed site's frame: centre, facing and side axis. */
    private record Frame(SiteKit k, int x, int z, Direction f, Direction side) {
        int wx(int a, int l) {
            return x + f.getStepX() * a + side.getStepX() * l;
        }

        int wz(int a, int l) {
            return z + f.getStepZ() * a + side.getStepZ() * l;
        }

        void set(int a, int y, int l, BlockState st) {
            k.set(wx(a, l), y, wz(a, l), st);
        }

        int floor(int a, int l) {
            return k.floor(wx(a, l), wz(a, l));
        }
    }

    private static Frame frame(SiteKit k) {
        Direction f = Direction.Plane.HORIZONTAL.getRandomDirection(k.r);
        return new Frame(k, k.cx, k.cz, f, f.getClockWise());
    }

    // ================================================================================================ Yokuda

    /**
     * Sunken Yokudan ruins, a piece of the drowned continent: a sandstone temple platform half buried in drifts, a
     * ring of broken columns, terracotta friezes, a collapsed sanctum with an offering chest, and in the courtyard a
     * sea-ward of prismarine around a conduit that still lets divers breathe (vanilla conduit power).
     */
    static boolean yokudanRuin(SiteKit k) {
        Frame fr = frame(k);
        int base = k.floor(k.cx, k.cz) + 1;
        if (base + 9 > SEA) base = Math.max(k.floor(k.cx, k.cz) + 1, SEA - 12);
        BlockState cut = s(Blocks.CUT_SANDSTONE), smooth = s(Blocks.SMOOTH_SANDSTONE), chis = s(Blocks.CHISELED_SANDSTONE);
        BlockState orange = s(com.skycraft.worldgen.VanillaBlocks.ORANGE_TERRACOTTA), yellow = s(com.skycraft.worldgen.VanillaBlocks.YELLOW_TERRACOTTA);
        // platform (filled down to the bed) with a border frieze
        for (int a = -8; a <= 8; a++) {
            for (int l = -8; l <= 8; l++) {
                int x = fr.wx(a, l), z = fr.wz(a, l);
                boolean edge = Math.abs(a) == 8 || Math.abs(l) == 8;
                if (edge && k.r.nextInt(5) == 0) continue; // crumbled corners
                k.pillarDown(x, base - 1, z, s(Blocks.SANDSTONE), 8);
                k.set(x, base, z, edge ? ((a + l) % 3 == 0 ? orange : yellow) : ((a + l) % 2 == 0 ? smooth : cut));
            }
        }
        // broken colonnade
        for (int i = 0; i < 12; i++) {
            double ang = i * Math.PI / 6;
            int a = (int) Math.round(Math.cos(ang) * 6.5), l = (int) Math.round(Math.sin(ang) * 6.5);
            int h = 2 + k.r.nextInt(6);
            for (int y = 1; y <= h; y++) fr.set(a, base + y, l, y == h && h >= 6 ? chis : (y % 3 == 0 ? s(Blocks.SMOOTH_SANDSTONE) : cut));
            if (h < 4 && k.r.nextBoolean()) {
                // the fallen drum lies beside its column
                int da = k.r.nextBoolean() ? 1 : -1;
                fr.set(a + da, base + 1, l, s(Blocks.SMOOTH_SANDSTONE_SLAB));
            }
        }
        // the sanctum: walls, half a dome, a collapsed side
        int sa = -4;
        for (int a = sa - 2; a <= sa + 2; a++) {
            for (int l = -2; l <= 2; l++) {
                boolean wall = Math.abs(a - sa) == 2 || Math.abs(l) == 2;
                for (int y = 1; y <= 4; y++) {
                    if (wall && !(a == sa + 2 && l == 0 && y <= 2) && !(l == -2 && y > 2 && k.r.nextBoolean())) {
                        fr.set(a, base + y, l, y == 4 ? orange : (y == 1 ? cut : smooth));
                    }
                }
            }
        }
        for (int a = sa - 1; a <= sa + 1; a++) for (int l = -1; l <= 1; l++) if (l >= 0 || a != sa) fr.set(a, base + 5, l, smooth);
        fr.set(sa, base + 6, 0, chis);
        fr.set(sa - 1, base + 1, 0, chis);
        k.chest(fr.wx(sa - 1, 1), base + 1, fr.wz(sa - 1, 1), fr.f, loot("yokudan_relics"));
        // the sea-ward: a conduit ringed by prismarine (horizontal and upright rings), on a pedestal
        int wa = 3, wy = base + 4;
        fr.set(wa, base + 1, 0, s(Blocks.DARK_PRISMARINE));
        fr.set(wa, base + 2, 0, s(Blocks.PRISMARINE_WALL));
        for (int da = -2; da <= 2; da++) {
            for (int dl = -2; dl <= 2; dl++) {
                if (Math.abs(da) == 2 || Math.abs(dl) == 2) fr.set(wa + da, wy, dl, (da + dl) % 2 == 0 ? s(Blocks.PRISMARINE_BRICKS) : s(Blocks.PRISMARINE));
            }
            for (int dy = -2; dy <= 2; dy++) {
                if (Math.abs(da) == 2 || Math.abs(dy) == 2) {
                    if (dy == -2 && Math.abs(da) < 2) continue; // the pedestal stands here
                    fr.set(wa + da, wy + dy, 0, s(Blocks.PRISMARINE));
                }
            }
        }
        fr.set(wa, wy, 0, s(Blocks.CONDUIT));
        // sand drifting over everything
        for (int i = 0; i < 40; i++) {
            int a = k.nextInt(-9, 9), l = k.nextInt(-9, 9);
            int x = fr.wx(a, l), z = fr.wz(a, l);
            int y = k.floor(x, z);
            if (y < base + 6) k.setIfOpen(x, y, z, k.r.nextInt(3) == 0 ? s(Blocks.SANDSTONE_SLAB) : s(Blocks.SAND));
        }
        LairMarkerBlockEntity m = k.marker(fr.wx(5, -5), base + 2, fr.wz(5, -5));
        if (m != null) m.creature("skycraft:slaughterfish", 2 + k.r.nextInt(2), 5);
        return true;
    }

    // ================================================================================================ Thras

    /** Sload coral spires: twisting towers of living coral off Thras, one hollow, a slimy hoard in its heart. */
    static boolean sloadSpire(SiteKit k) {
        int bed = k.floor(k.cx, k.cz);
        int top = Math.min(SEA + 6, bed + 26);
        BlockState[] coral = {s(Blocks.TUBE_CORAL_BLOCK), s(Blocks.BRAIN_CORAL_BLOCK), s(Blocks.BUBBLE_CORAL_BLOCK),
                s(Blocks.FIRE_CORAL_BLOCK), s(Blocks.HORN_CORAL_BLOCK), s(Blocks.DEAD_BRAIN_CORAL_BLOCK), s(Blocks.PRISMARINE_BRICKS)};
        int spires = 3 + k.r.nextInt(3);
        for (int i = 0; i < spires; i++) {
            boolean main = i == 0;
            int ox = main ? k.cx : k.cx + k.nextInt(-9, 9), oz = main ? k.cz : k.cz + k.nextInt(-9, 9);
            int b = k.floor(ox, oz);
            int h = main ? top - b : (int) ((top - b) * (0.4 + k.r.nextDouble() * 0.5));
            double phase = k.r.nextDouble() * 6.28;
            for (int y = 0; y <= h; y++) {
                double t = (double) y / Math.max(1, h);
                double rad = (main ? 3.4 : 2.2) * (1 - t * 0.85) + 0.6;
                double sx = Math.sin(phase + t * 5) * 1.6 * t, sz = Math.cos(phase + t * 4) * 1.6 * t;
                int ir = (int) Math.ceil(rad);
                for (int dx = -ir; dx <= ir; dx++) {
                    for (int dz = -ir; dz <= ir; dz++) {
                        double d = Math.hypot(dx - sx, dz - sz);
                        if (d > rad) continue;
                        boolean hollow = main && y >= 1 && y <= 4 && d < rad - 1.2;
                        int x = ox + dx, z = oz + dz;
                        if (hollow) continue;
                        k.set(x, b + y, z, coral[(int) ((y * 7 + dx * 3 + dz * 5 + i) & 0x7fffffff) % coral.length]);
                    }
                }
                // fans and growths on the flanks
                if (y > 1 && k.r.nextInt(3) == 0) {
                    Direction d = Direction.Plane.HORIZONTAL.getRandomDirection(k.r);
                    int x = ox + (int) Math.round(sx) + d.getStepX() * (ir + 1), z = oz + (int) Math.round(sz) + d.getStepZ() * (ir + 1);
                    if (k.water(x, b + y, z)) {
                        Block fan = switch (k.r.nextInt(4)) {
                            case 0 -> Blocks.TUBE_CORAL_WALL_FAN;
                            case 1 -> Blocks.FIRE_CORAL_WALL_FAN;
                            case 2 -> Blocks.HORN_CORAL_WALL_FAN;
                            default -> Blocks.BRAIN_CORAL_WALL_FAN;
                        };
                        k.set(x, b + y, z, fan.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, d));
                    }
                }
            }
        }
        // the heart of the main spire: a door of coral to the south, a hoard, a breathing vent
        int b = k.floor(k.cx, k.cz);
        for (int dz = 2; dz <= 5; dz++) for (int y = 1; y <= 2; y++) k.set(k.cx, b + y, k.cz + dz, s(Blocks.WATER));
        k.chest(k.cx - 1, b + 1, k.cz - 1, Direction.SOUTH, loot("sload_hoard"));
        k.set(k.cx + 1, b + 1, k.cz - 1, s(Blocks.SLIME_BLOCK));
        k.set(k.cx - 1, b + 1, k.cz + 1, s(Blocks.SEA_PICKLE).setValue(BlockStateProperties.PICKLES, 3));
        k.airVent(k.cx + 1, b, k.cz + 1, b + 4);
        LairMarkerBlockEntity m = k.marker(k.cx + 6, k.floor(k.cx + 6, k.cz) + 1, k.cz);
        if (m != null) m.creature("skycraft:sea_crab", 2, 4);
        return true;
    }

    // ================================================================================================ Morrowind

    /**
     * A Dreugh lair: a domed mound of black volcanic rock on the sea bed with a low mouth; inside, the brood's wax
     * combs, cast shells, the bones of their meals and the wreckage they hoard; kollops all about. The bigger lairs
     * hold a queen.
     */
    static boolean dreughLair(SiteKit k) {
        Frame fr = frame(k);
        int bed = k.floor(k.cx, k.cz);
        boolean queen = k.r.nextInt(4) == 0;
        float rad = queen ? 9f : 7f;
        int h = queen ? 8 : 6;
        if (bed + h + 2 > SEA) h = Math.max(4, SEA - bed - 2);
        BlockState rock = s(Blocks.BASALT), rock2 = s(Blocks.SMOOTH_BASALT), tuff = s(Blocks.TUFF);
        int ir = (int) rad + 1;
        for (int dx = -ir; dx <= ir; dx++) {
            for (int dz = -ir; dz <= ir; dz++) {
                double d = Math.hypot(dx, dz) / rad;
                if (d > 1) continue;
                int x = k.cx + dx, z = k.cz + dz;
                int b = k.floor(x, z);
                int roof = bed + (int) Math.round(h * Math.sqrt(1 - d * d));
                for (int y = b; y <= roof; y++) {
                    double inner = Math.hypot(dx, dz) / (rad - 1.6);
                    double innerCeil = bed + (h - 1) * Math.sqrt(Math.max(0, 1 - inner * inner));
                    boolean hollow = inner < 1 && y > bed && y < innerCeil;
                    if (hollow) {
                        // Upper dome above the low mouth (y >= bed + 4) traps a breathable air pocket
                        if (y >= bed + 4 && roof < SEA - 1) {
                            k.air(x, y, z);
                        } else {
                            k.set(x, y, z, s(Blocks.WATER));
                        }
                    } else {
                        int roll = (x * 31 + y * 17 + z * 13) & 7;
                        k.set(x, y, z, roll < 4 ? rock : roll < 6 ? rock2 : tuff);
                    }
                }
            }
        }
        // the mouth, facing out (stays at y = 1..2 so the upper air pocket remains sealed)
        for (int a = (int) rad - 2; a <= (int) rad + 1; a++) {
            for (int l = -1; l <= 1; l++) {
                for (int y = 1; y <= 2; y++) fr.set(a, bed + y, l, s(Blocks.WATER));
            }
        }
        // bioluminescent sea-lantern crystal in the dome crown
        if (bed + h - 1 < SEA - 1) {
            k.setDry(k.cx, bed + h - 2, k.cz, s(Blocks.SEA_LANTERN));
        }
        // the nest: wax combs, cast shells, bones, wreckage
        for (int i = 0; i < (queen ? 14 : 8); i++) {
            int dx = k.nextInt(-4, 4), dz = k.nextInt(-4, 4);
            if (dx * dx + dz * dz > 16) continue;
            int x = k.cx + dx, z = k.cz + dz;
            int roll = k.r.nextInt(6);
            BlockState st = roll < 2 ? s(Blocks.HONEYCOMB_BLOCK) : roll < 3 ? s(Blocks.BONE_BLOCK) : roll < 4 ? s(com.skycraft.worldgen.VanillaBlocks.BROWN_TERRACOTTA)
                    : roll < 5 ? com.skycraft.seas.SeasRegistry.KOLLOP.get().defaultBlockState() : s(Blocks.SPRUCE_SLAB);
            k.set(x, bed + 1, z, st);
        }
        k.chest(k.cx - 2, bed + 1, k.cz + 2, fr.f, loot(queen ? "dreugh_queen_hoard" : "dreugh_lair"));
        k.airVent(k.cx + 2, bed, k.cz - 2, Math.min(bed + 3, bed + h - 2));
        // kollops on the sand around it
        kollops(k, k.cx, k.cz, (int) rad + 6, 12);
        LairMarkerBlockEntity m = k.marker(k.cx, bed + 2, k.cz);
        if (m != null) {
            m.creature("skycraft:dreugh", queen ? 3 : 2 + k.r.nextInt(2), 3);
            if (queen) m.creature("skycraft:dreugh_queen", 1, 1);
        }
        return true;
    }

    /** Kollops scattered on the sea bed (pearl beds). */
    static void kollops(SiteKit k, int x0, int z0, int radius, int count) {
        BlockState kollop = com.skycraft.seas.SeasRegistry.KOLLOP.get().defaultBlockState();
        for (int i = 0; i < count; i++) {
            int x = x0 + k.nextInt(-radius, radius), z = z0 + k.nextInt(-radius, radius);
            int y = k.floor(x, z);
            if (!k.water(x, y, z)) continue;
            BlockState below = k.get(x, y - 1, z);
            if (!below.isFaceSturdy(k.level, new net.minecraft.core.BlockPos(x, y - 1, z), Direction.UP)) continue;
            k.set(x, y, z, kollop.setValue(KollopBlock.FACING, Direction.Plane.HORIZONTAL.getRandomDirection(k.r)));
        }
    }

    /**
     * A Dwemer ruin on the sea bed: a bronze dome on a plinth whose chamber is still dry after all the ages. A short
     * passage, an iron door with buttons inside and out (water never passes a door), lamps, a workbench, the
     * engineers' chest and their notes.
     */
    static boolean dwemerRuin(SiteKit k) {
        Frame fr = frame(k);
        int bed = k.floor(k.cx, k.cz);
        int base = bed + 2;
        if (base + 8 > SEA - 1) return false;
        BlockState cut = s(com.skycraft.worldgen.VanillaBlocks.WAXED_CUT_COPPER), exposed = s(com.skycraft.worldgen.VanillaBlocks.WAXED_EXPOSED_CUT_COPPER), weathered = s(com.skycraft.worldgen.VanillaBlocks.WAXED_WEATHERED_CUT_COPPER),
                block = s(com.skycraft.worldgen.VanillaBlocks.WAXED_COPPER_BLOCK), stone = s(Blocks.POLISHED_DEEPSLATE), glass = s(Blocks.GLASS);
        int r = 5;
        // plinth
        for (int a = -r - 1; a <= r + 1; a++) {
            for (int l = -r - 1; l <= r + 1; l++) {
                int x = fr.wx(a, l), z = fr.wz(a, l);
                k.pillarDown(x, base - 1, z, stone, 10);
            }
        }
        // the sealed dome: walls up to 4, a stepped roof, glass portholes
        for (int a = -r; a <= r; a++) {
            for (int l = -r; l <= r; l++) {
                double d = Math.max(Math.abs(a), Math.abs(l));
                for (int y = 0; y <= 7; y++) {
                    int roofAt = 5 + (int) Math.round(2.5 * (1 - Math.pow(Math.max(Math.abs(a), Math.abs(l)) / (double) r, 2)));
                    boolean shell = d == r && y <= 5 || y == 0 || (y >= 5 && y == Math.min(7, roofAt));
                    boolean inside = d < r && y >= 1 && y < Math.min(7, roofAt);
                    if (y > Math.min(7, roofAt)) continue;
                    if (shell) {
                        BlockState st = (a + l + y) % 5 == 0 ? weathered : (a + y) % 3 == 0 ? exposed : cut;
                        if (y == 3 && d == r && (a == 0 || l == 0) && !(a == r && l == 0)) st = glass;
                        if (y == 0) st = (a + l) % 2 == 0 ? block : cut;
                        fr.set(a, base + y, l, st);
                    } else if (inside) {
                        k.air(fr.wx(a, l), base + y, fr.wz(a, l));
                    }
                }
            }
        }
        // entrance passage on the +a side: an airlock-like porch with an iron door and buttons both sides
        for (int a = r; a <= r + 2; a++) {
            for (int l = -1; l <= 1; l++) {
                for (int y = 0; y <= 3; y++) {
                    boolean wall = Math.abs(l) == 1 || y == 0 || y == 3;
                    if (a == r + 2 && !wall) continue; // open to the sea
                    if (wall) fr.set(a, base + y, l, (y + l) % 2 == 0 ? cut : exposed);
                }
            }
        }
        Direction out = fr.f;
        BlockState door = s(Blocks.IRON_DOOR).setValue(DoorBlock.FACING, out.getOpposite());
        int dx = fr.wx(r, 0), dz = fr.wz(r, 0);
        k.setDry(dx, base + 1, dz, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        k.setDry(dx, base + 2, dz, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        // a button on the passage wall outside and on the chamber wall inside
        k.set(fr.wx(r + 1, 0), base + 2, fr.wz(r + 1, 0), s(Blocks.STONE_BUTTON)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, fr.side.getOpposite()));
        k.setDry(fr.wx(r - 1, 1), base + 2, fr.wz(r - 1, 1), s(Blocks.STONE_BUTTON)
                .setValue(BlockStateProperties.HORIZONTAL_FACING, out.getOpposite()));
        // in the chamber: lamps, a workbench, the chest, a lectern-side note and a dormant guardian
        k.setDry(fr.wx(0, 0), base + 6, fr.wz(0, 0), s(Blocks.LANTERN).setValue(BlockStateProperties.HANGING, true));
        k.setDry(fr.wx(-r + 1, -r + 1), base + 1, fr.wz(-r + 1, -r + 1), s(Blocks.LANTERN));
        k.setDry(fr.wx(-r + 1, r - 1), base + 1, fr.wz(-r + 1, r - 1), s(Blocks.LANTERN));
        k.setDry(fr.wx(-r + 1, 0), base + 1, fr.wz(-r + 1, 0), s(Blocks.SMITHING_TABLE));
        k.setDry(fr.wx(-r + 1, 1), base + 1, fr.wz(-r + 1, 1), SiteKit.facing(s(Blocks.BLAST_FURNACE), out));
        k.chest(fr.wx(-r + 1, -1), base + 1, fr.wz(-r + 1, -1), out, loot("dwemer_air_chamber"));
        k.setDry(fr.wx(-2, r - 1), base + 1, fr.wz(-2, r - 1), s(com.skycraft.worldgen.VanillaBlocks.WAXED_COPPER_BLOCK));
        k.setDry(fr.wx(-2, r - 1), base + 2, fr.wz(-2, r - 1), s(com.skycraft.worldgen.VanillaBlocks.LIGHTNING_ROD));
        k.setDry(fr.wx(1, -r + 1), base + 1, fr.wz(1, -r + 1), s(com.skycraft.worldgen.VanillaBlocks.WAXED_CUT_COPPER_STAIRS)
                .setValue(StairBlock.FACING, fr.side).setValue(StairBlock.HALF, Half.BOTTOM));
        LairMarkerBlockEntity m = k.marker(fr.wx(1, 2), base + 1, fr.wz(1, 2));
        if (m != null) m.creature("skycraft:dwarven_spider", 1 + k.r.nextInt(2), 2);
        // pipes running off into the silt
        for (int i = 1; i <= 6; i++) {
            int x = fr.wx(-r - 1 - i, 2), z = fr.wz(-r - 1 - i, 2);
            k.set(x, k.floor(x, z), z, s(com.skycraft.worldgen.VanillaBlocks.WAXED_CUT_COPPER_SLAB));
        }
        return true;
    }

    // ================================================================================================ the north

    /**
     * An ancient Nordic ship burial in the Sea of Ghosts: a longship laid on the sea bed with its shields along the
     * rails, a stone cairn amidships over the jarl's bones, urns, a grave chest, and dead that do not rest.
     */
    static boolean nordicGrave(SiteKit k) {
        Frame fr = frame(k);
        int half = 7;
        BlockState plank = s(Blocks.DARK_OAK_PLANKS), log = s(Blocks.DARK_OAK_LOG).setValue(RotatedPillarBlock.AXIS, fr.f.getAxis());
        BlockState[] shields = {s(com.skycraft.worldgen.VanillaBlocks.RED_WOOL), s(com.skycraft.worldgen.VanillaBlocks.WHITE_WOOL), s(com.skycraft.worldgen.VanillaBlocks.BLUE_WOOL), s(com.skycraft.worldgen.VanillaBlocks.YELLOW_WOOL)};
        for (int a = -half; a <= half; a++) {
            double t = a / (half + 1.0);
            int w = Math.max(0, (int) Math.round(2.4 * Math.sqrt(Math.max(0, 1 - t * t))));
            for (int l = -w; l <= w; l++) {
                int y0 = fr.floor(a, l);
                boolean wall = Math.abs(l) == w;
                fr.set(a, y0, l, l == 0 ? log : plank);
                if (wall) {
                    fr.set(a, y0 + 1, l, plank);
                    if (k.r.nextInt(4) > 0) fr.set(a, y0 + 2, l, a % 2 == 0 ? shields[(a + 8) % shields.length] : s(Blocks.DARK_OAK_FENCE));
                }
            }
        }
        // carved stem and stern posts
        for (int y = 0; y < 4; y++) {
            fr.set(half + 1, fr.floor(half + 1, 0) + y, 0, s(Blocks.DARK_OAK_LOG));
            fr.set(-half - 1, fr.floor(-half - 1, 0) + y, 0, s(Blocks.DARK_OAK_LOG));
        }
        fr.set(half + 2, fr.floor(half + 1, 0) + 3, 0, s(Blocks.DARK_OAK_FENCE));
        // the cairn amidships
        int cy = fr.floor(0, 0) + 1;
        for (int a = -2; a <= 2; a++) {
            for (int l = -1; l <= 1; l++) {
                int hh = 3 - Math.abs(a) / 2 - Math.abs(l);
                for (int y = 0; y < hh; y++) fr.set(a, cy + y, l, (a + y) % 3 == 0 ? s(Blocks.MOSSY_COBBLESTONE) : s(Blocks.COBBLESTONE));
            }
        }
        fr.set(0, cy + 3, 0, s(Blocks.SKELETON_SKULL).setValue(SkullBlock.ROTATION, k.r.nextInt(16)));
        fr.set(3, cy, 1, s(Blocks.DECORATED_POT));
        fr.set(-3, cy, -1, s(Blocks.DECORATED_POT));
        fr.set(3, cy, -1, s(Blocks.BONE_BLOCK));
        k.chest(fr.wx(-3, 1), cy, fr.wz(-3, 1), fr.side, loot("nordic_ship_grave"));
        LairMarkerBlockEntity m = k.marker(fr.wx(1, 0), cy + 4, fr.wz(1, 0));
        if (m != null) m.creature("skycraft:draugr", 2 + k.r.nextInt(2), 3);
        return true;
    }

    // ================================================================================================ any sea

    /** A slaughterfish nest: a ring of weeded rocks, eggs in the kelp, the remains of their last meal. */
    static boolean slaughterfishNest(SiteKit k) {
        int bed = k.floor(k.cx, k.cz);
        for (int i = 0; i < 22; i++) {
            double ang = i * Math.PI * 2 / 22;
            int x = k.cx + (int) Math.round(Math.cos(ang) * 5), z = k.cz + (int) Math.round(Math.sin(ang) * 5);
            int y = k.floor(x, z);
            int h = 1 + k.r.nextInt(3);
            for (int dy = 0; dy < h; dy++) k.set(x, y + dy, z, k.r.nextInt(3) == 0 ? s(Blocks.MOSSY_COBBLESTONE) : s(Blocks.COBBLESTONE));
            if (k.water(x, y + h, z)) {
                for (int dy = 0; dy < 2 + k.r.nextInt(4); dy++) {
                    if (!k.water(x, y + h + dy + 1, z)) break;
                    k.set(x, y + h + dy, z, s(Blocks.KELP_PLANT));
                }
            }
        }
        for (int i = 0; i < 10; i++) {
            int x = k.cx + k.nextInt(-3, 3), z = k.cz + k.nextInt(-3, 3);
            int y = k.floor(x, z);
            k.setIfOpen(x, y, z, k.r.nextInt(3) == 0 ? s(Blocks.BONE_BLOCK) : s(Blocks.SEAGRASS));
        }
        if (bed + 9 < SEA - 2 && k.r.nextInt(3) == 0) {
            seaCavernWithAirPocket(k, k.cx + 8, k.cz);
        }
        k.barrel(k.cx + 1, bed + 1, k.cz - 1, loot("slaughterfish_nest"));
        LairMarkerBlockEntity m = k.marker(k.cx, bed + 2, k.cz);
        if (m != null) {
            m.creature("skycraft:slaughterfish", 3 + k.r.nextInt(3), 4);
            if (k.r.nextInt(3) == 0) m.creature("skycraft:giant_slaughterfish", 1, 2);
        }
        return true;
    }

    /**
     * A submerged sea cavern dome on the seabed with a flooded lower pool, low diver entry arch, and a sealed stone &
     * prismarine roof trapping a breathable air pocket lit by sea-lanterns and sea pickles.
     */
    static boolean seaCavernWithAirPocket(SiteKit k) {
        return seaCavernWithAirPocket(k, k.cx, k.cz);
    }

    static boolean seaCavernWithAirPocket(SiteKit k, int cx, int cz) {
        int bed = k.floor(cx, cz);
        int h = 7;
        if (bed <= 0 || bed + h + 2 > SEA - 1) return false;
        float rad = 5.5f;
        BlockState stone = s(Blocks.STONE), mossy = s(Blocks.MOSSY_COBBLESTONE), prism = s(Blocks.PRISMARINE);
        int ir = (int) Math.ceil(rad) + 1;
        for (int dx = -ir; dx <= ir; dx++) {
            for (int dz = -ir; dz <= ir; dz++) {
                double d = Math.hypot(dx, dz) / rad;
                if (d > 1) continue;
                int x = cx + dx, z = cz + dz;
                int b = k.floor(x, z);
                int roof = bed + (int) Math.round(h * Math.sqrt(Math.max(0, 1 - d * d)));
                for (int y = Math.min(b, bed); y <= roof; y++) {
                    double inner = Math.hypot(dx, dz) / (rad - 1.4);
                    double innerCeil = bed + (h - 1) * Math.sqrt(Math.max(0, 1 - inner * inner));
                    boolean hollow = inner < 1 && y > bed && y < innerCeil;
                    if (hollow) {
                        if (y >= bed + 3) {
                            k.air(x, y, z);
                        } else {
                            k.set(x, y, z, s(Blocks.WATER));
                        }
                    } else {
                        int roll = (x * 19 + y * 13 + z * 7) & 7;
                        k.set(x, y, z, roll == 0 ? prism : roll < 4 ? mossy : stone);
                    }
                }
            }
        }
        // low submerged entry arch on the west side (y = bed + 1 .. bed + 2) so upper air pocket stays sealed
        for (int dx = -(int) rad; dx <= -2; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                k.set(cx + dx, bed + 1, cz + dz, s(Blocks.WATER));
                k.set(cx + dx, bed + 2, cz + dz, s(Blocks.WATER));
            }
        }
        k.setDry(cx, bed + h - 2, cz, s(Blocks.SEA_LANTERN));
        k.set(cx + 1, bed + 1, cz + 1, s(Blocks.SEA_PICKLE).setValue(BlockStateProperties.PICKLES, 3));
        k.chest(cx + 1, bed + 1, cz - 1, Direction.WEST, SiteKit.loot("chests/flotsam"));
        return true;
    }

    /** A sea serpent's lair in the deep: a rock arch over a field of picked bones and a sunken hoard. */
    static boolean serpentLair(SiteKit k) {
        Frame fr = frame(k);
        int bed = k.floor(k.cx, k.cz);
        // the arch
        for (int i = 0; i <= 20; i++) {
            double t = i / 20.0;
            int a = (int) Math.round(-8 + 16 * t);
            int y = bed + (int) Math.round(11 * Math.sin(t * Math.PI));
            for (int dl = -2; dl <= 2; dl++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (Math.abs(dl) == 2 && dy != 0) continue;
                    fr.set(a, y + dy, dl, (a + dy) % 3 == 0 ? s(Blocks.DEEPSLATE) : s(Blocks.COBBLED_DEEPSLATE));
                }
            }
            if (t < 0.12 || t > 0.88) for (int y2 = bed; y2 < y; y2++) for (int dl = -2; dl <= 2; dl++) fr.set(a, y2, dl, s(Blocks.COBBLED_DEEPSLATE));
        }
        // a ribcage: a spine with curved ribs
        for (int a = -6; a <= 6; a++) {
            fr.set(a, fr.floor(a, -5), -5, s(Blocks.BONE_BLOCK).setValue(RotatedPillarBlock.AXIS, fr.f.getAxis()));
            if (a % 2 == 0) {
                for (int l = 1; l <= 4; l++) {
                    int y = fr.floor(a, -5 + l) + (l < 3 ? l : 5 - l);
                    fr.set(a, y, -5 + l, s(Blocks.BONE_BLOCK));
                }
            }
        }
        k.chest(fr.wx(0, 1), bed + 1, fr.wz(0, 1), fr.f, loot("serpent_hoard"));
        for (int i = 0; i < 6; i++) {
            int a = k.nextInt(-4, 4), l = k.nextInt(-3, 3);
            k.setIfOpen(fr.wx(a, l), fr.floor(a, l), fr.wz(a, l), k.r.nextBoolean() ? s(Blocks.SEA_PICKLE) : s(Blocks.BONE_BLOCK));
        }
        LairMarkerBlockEntity m = k.marker(fr.wx(0, 0), bed + 5, fr.wz(0, 0));
        if (m != null) m.creature("skycraft:sea_serpent", 1, 2);
        return true;
    }

    // ================================================================================================ Pyandonea

    /**
     * A sunken Maormer warship: a sleek pale hull with a serpent figurehead, the blue-and-white sail rags drifting in
     * the current, and its cargo still aboard.
     */
    static boolean maormerWreck(SiteKit k) {
        Frame fr = frame(k);
        int half = 8;
        BlockState hull = s(Blocks.BIRCH_PLANKS), keel = s(Blocks.STRIPPED_BIRCH_LOG).setValue(RotatedPillarBlock.AXIS, fr.f.getAxis());
        BlockState trim = s(Blocks.PRISMARINE_BRICKS), stairs = s(Blocks.BIRCH_STAIRS);
        int list = k.r.nextBoolean() ? 1 : -1;
        for (int a = -half; a <= half; a++) {
            double t = a / (half + 1.0);
            int w = Math.max(0, (int) Math.round(2.0 * Math.pow(Math.max(0, 1 - t * t), 0.7)));
            for (int l = -w; l <= w; l++) {
                int y0 = fr.floor(a, l);
                fr.set(a, y0, l, l == 0 ? keel : hull);
                if (Math.abs(l) == w) {
                    int hh = 2 - (l * list > 0 ? 1 : 0);
                    for (int y = 1; y <= hh; y++) {
                        if (k.r.nextInt(8) == 0) continue;
                        fr.set(a, y0 + y, l, y == hh ? (a % 3 == 0 ? trim : stairs.setValue(StairBlock.FACING, l > 0 ? fr.side : fr.side.getOpposite())) : hull);
                    }
                }
            }
        }
        // the serpent figurehead
        int fy = fr.floor(half + 1, 0);
        fr.set(half + 1, fy + 1, 0, trim);
        fr.set(half + 1, fy + 2, 0, s(Blocks.PRISMARINE_WALL));
        fr.set(half + 2, fy + 3, 0, s(Blocks.DARK_PRISMARINE));
        fr.set(half + 2, fy + 4, 0, s(Blocks.SEA_LANTERN));
        // fallen mast and sail rags
        Direction fall = list > 0 ? fr.side : fr.side.getOpposite();
        int mx = fr.wx(1, 0), mz = fr.wz(1, 0);
        for (int i = 1; i <= 8; i++) {
            int x = mx + fall.getStepX() * i, z = mz + fall.getStepZ() * i;
            k.set(x, k.floor(x, z), z, s(Blocks.STRIPPED_BIRCH_LOG).setValue(RotatedPillarBlock.AXIS, fall.getAxis()));
            if (i >= 3 && k.r.nextBoolean()) {
                int x2 = x + fr.f.getStepX(), z2 = z + fr.f.getStepZ();
                k.set(x2, k.floor(x2, z2), z2, k.r.nextBoolean() ? s(com.skycraft.worldgen.VanillaBlocks.LIGHT_BLUE_WOOL) : s(com.skycraft.worldgen.VanillaBlocks.WHITE_WOOL));
            }
        }
        k.chest(fr.wx(-2, 0), fr.floor(-2, 0) + 1, fr.wz(-2, 0), fr.f, loot("maormer_cargo"));
        k.barrel(fr.wx(2, 1), fr.floor(2, 1) + 1, fr.wz(2, 1), SiteKit.loot("chests/flotsam"));
        LairMarkerBlockEntity m = k.marker(fr.wx(0, 0), fr.floor(0, 0) + 4, fr.wz(0, 0));
        if (m != null) m.creature("skycraft:sea_netch", 2, 6);
        return true;
    }
}
