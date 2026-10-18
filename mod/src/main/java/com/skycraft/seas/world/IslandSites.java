package com.skycraft.seas.world;

import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

/**
 * Builders of the island and coast sites ({@link IsletFeature}, {@link CoastSiteFeature}): lighthouses with their
 * keeper's cottage, pirate coves with buried treasure, smugglers' and tidal caves, seabird rookeries, sea stacks with
 * nests, hermits' huts, shipwreck beaches, whale bones, and the ice floes of the Sea of Ghosts with their horkers and
 * seals. Buildings use the materials of the nearest land's culture ({@link CoastPalette}).
 */
final class IslandSites {
    private IslandSites() {}

    static final int SEA = 63;

    private static BlockState s(Block b) {
        return b.defaultBlockState();
    }

    private static Identifier loot(String name) {
        return SiteKit.loot("chests/seas/" + name);
    }

    /** The culture of the land a sea column belongs to: its own region, else the nearest coast's. */
    static String cultureNear(int x, int z) {
        Region r = TamrielMap.region(x, z);
        if (!"sea".equals(r.province()) && !"none".equals(r.culture())) return r.culture();
        for (int dist : new int[]{150, 350, 700, 1400}) {
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4;
                Region o = TamrielMap.region(x + (int) (Math.cos(a) * dist), z + (int) (Math.sin(a) * dist));
                if (!"sea".equals(o.province()) && !"none".equals(o.culture())) return o.culture();
            }
        }
        return "imperial";
    }

    /** The ground an islet is made of, by the climate of its waters. */
    record Ground(BlockState core, BlockState skin, BlockState beach) {
        static Ground of(SiteKit k) {
            float temp = k.level.getBiome(new net.minecraft.core.BlockPos(k.cx, SEA, k.cz)).value().getBaseTemperature();
            Region r = TamrielMap.region(k.cx, k.cz);
            if ("morrowind".equals(r.province()) || "sea/inner_sea".equals(r.id())) {
                return new Ground(s(Blocks.BASALT), s(Blocks.COARSE_DIRT), s(Blocks.GRAVEL));
            }
            if (temp < 0.2f) return new Ground(s(Blocks.STONE), s(Blocks.SNOW_BLOCK), s(Blocks.GRAVEL));
            if (temp > 0.9f) return new Ground(s(Blocks.SANDSTONE), s(Blocks.GRASS_BLOCK), s(Blocks.SAND));
            return new Ground(s(Blocks.STONE), s(Blocks.GRASS_BLOCK), s(Blocks.SAND));
        }
    }

    // ================================================================================================ lighthouse

    /**
     * A lighthouse: a round tower of the local stone with a ladder up the inside to the lamp room (a fire behind
     * glass, culture-dressed), a conical roof, and the keeper's cottage at its foot.
     */
    static void lighthouse(SiteKit k, int x, int y, int z, CoastPalette p, Direction face, String culture) {
        int h = 12 + k.r.nextInt(5);
        // foundation
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++) if (dx * dx + dz * dz <= 10) k.pillarDown(x + dx, y - 1, z + dz, p.base(), 12);
        for (int dy = 0; dy < h; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int d2 = dx * dx + dz * dz;
                    if (d2 > 5) continue;
                    boolean wall = d2 >= 4;
                    if (wall) {
                        BlockState st = (dy % 4 == 3) ? p.trim() : (dy % 2 == 0 ? p.wall() : p.wallAlt());
                        if ("nord".equals(culture) || "atmoran".equals(culture)) st = dy % 4 == 3 ? p.trim() : s(Blocks.STONE_BRICKS);
                        if (dy % 5 == 2 && (dx == 0 || dz == 0) && dy > 2) st = s(Blocks.GLASS);
                        k.set(x + dx, y + dy, z + dz, st);
                    } else {
                        k.set(x + dx, y + dy, z + dz, dy == 0 ? p.floor() : s(Blocks.AIR));
                    }
                }
            }
        }
        // door facing the cottage and a ladder up the inner wall opposite
        Direction back = face.getOpposite();
        k.set(x + face.getStepX() * 2, y + 1, z + face.getStepZ() * 2, door(culture, face, DoubleBlockHalf.LOWER));
        k.set(x + face.getStepX() * 2, y + 2, z + face.getStepZ() * 2, door(culture, face, DoubleBlockHalf.UPPER));
        for (int dy = 1; dy < h; dy++) {
            k.set(x + back.getStepX(), y + dy, z + back.getStepZ(), s(Blocks.LADDER).setValue(LadderBlock.FACING, face));
        }
        // lamp room
        int top = y + h;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (dx * dx + dz * dz <= 5) k.set(x + dx, top, z + dz, p.floor());
        k.set(x + back.getStepX(), top, z + back.getStepZ(), s(Blocks.LADDER).setValue(LadderBlock.FACING, face));
        for (int dy = 1; dy <= 3; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int d2 = dx * dx + dz * dz;
                    if (d2 > 5) continue;
                    if (d2 >= 4) k.set(x + dx, top + dy, z + dz, (Math.abs(dx) == 2 && Math.abs(dz) == 1 || Math.abs(dz) == 2 && Math.abs(dx) == 1) && dy < 3
                            ? p.log() : s(Blocks.GLASS));
                    else k.set(x + dx, top + dy, z + dz, s(Blocks.AIR));
                }
            }
        }
        BlockState flame = switch (culture) {
            case "altmer", "maormer" -> s(Blocks.SEA_LANTERN);
            case "dunmer" -> s(Blocks.SHROOMLIGHT);
            case "imperial", "breton" -> s(Blocks.GLOWSTONE);
            default -> s(Blocks.CAMPFIRE).setValue(CampfireBlock.LIT, true);
        };
        k.set(x, top + 1, z, flame.is(Blocks.CAMPFIRE) ? s(Blocks.IRON_BARS) : p.trim());
        k.set(x, top + 2, z, flame);
        // conical roof with a finial
        for (int dy = 4; dy <= 6; dy++) {
            int rr = 6 - dy;
            for (int dx = -rr - 1; dx <= rr + 1; dx++)
                for (int dz = -rr - 1; dz <= rr + 1; dz++)
                    if (dx * dx + dz * dz <= (rr + 1) * (rr + 1) + 1) k.set(x + dx, top + dy, z + dz, p.roofBlock());
        }
        k.set(x, top + 7, z, s(com.skycraft.worldgen.VanillaBlocks.LIGHTNING_ROD));
    }

    private static BlockState door(String culture, Direction face, DoubleBlockHalf half) {
        Block d = switch (culture) {
            case "nord", "atmoran" -> Blocks.SPRUCE_DOOR;
            case "argonian", "bosmer" -> Blocks.MANGROVE_DOOR;
            case "altmer", "maormer" -> Blocks.BIRCH_DOOR;
            case "redguard", "khajiit", "dunmer" -> Blocks.ACACIA_DOOR;
            default -> Blocks.DARK_OAK_DOOR;
        };
        return d.defaultBlockState().setValue(DoorBlock.FACING, face).setValue(DoorBlock.HALF, half);
    }

    /** A one-room cottage: walls, a pitched roof, door, window, bed, table, chest, barrel and a light. */
    static void cottage(SiteKit k, int x, int y, int z, CoastPalette p, Direction face, String culture, Identifier chest) {
        Direction side = face.getClockWise();
        int len = 4, wid = 3; // half sizes along face / side
        for (int a = -len; a <= len; a++) {
            for (int l = -wid; l <= wid; l++) {
                int wx = x + face.getStepX() * a + side.getStepX() * l, wz = z + face.getStepZ() * a + side.getStepZ() * l;
                k.pillarDown(wx, y - 1, wz, p.base(), 8);
                boolean wall = Math.abs(a) == len || Math.abs(l) == wid;
                boolean corner = Math.abs(a) == len && Math.abs(l) == wid;
                for (int dy = 0; dy <= 3; dy++) {
                    if (dy == 0) {
                        k.set(wx, y, wz, p.floor());
                    } else if (wall) {
                        BlockState st = corner ? p.log() : p.wall();
                        if (dy == 2 && Math.abs(l) == wid && a == 1) st = s(Blocks.GLASS_PANE);
                        k.set(wx, y + dy, wz, st);
                    } else {
                        k.set(wx, y + dy, wz, s(Blocks.AIR));
                    }
                }
                // pitched roof along the face axis
                int ridge = wid + 1 - Math.abs(l);
                int ry = y + 3 + ridge;
                Direction slope = l > 0 ? side.getOpposite() : side;
                if (l == 0) k.set(wx, ry - 1, wz, p.roofBlock());
                else k.set(wx, ry - 1, wz, p.roofStairs().defaultBlockState().setValue(StairBlock.FACING, slope));
                for (int fy = y + 4; fy < ry - 1; fy++) if (Math.abs(a) == len) k.set(wx, fy, wz, p.wall());
            }
        }
        int dx = x + face.getStepX() * len, dz = z + face.getStepZ() * len;
        k.set(dx, y + 1, dz, door(culture, face, DoubleBlockHalf.LOWER));
        k.set(dx, y + 2, dz, door(culture, face, DoubleBlockHalf.UPPER));
        // furnishing
        int bx = x - face.getStepX() * (len - 2) + side.getStepX() * (wid - 1), bz = z - face.getStepZ() * (len - 2) + side.getStepZ() * (wid - 1);
        k.set(bx, y + 1, bz, s(com.skycraft.worldgen.VanillaBlocks.RED_BED).setValue(BedBlock.FACING, face.getOpposite()).setValue(BedBlock.PART, BedPart.HEAD));
        k.set(bx + face.getStepX(), y + 1, bz + face.getStepZ(), s(com.skycraft.worldgen.VanillaBlocks.RED_BED).setValue(BedBlock.FACING, face.getOpposite()).setValue(BedBlock.PART, BedPart.FOOT));
        int cx = x - face.getStepX() * (len - 1) - side.getStepX() * (wid - 1), cz = z - face.getStepZ() * (len - 1) - side.getStepZ() * (wid - 1);
        k.chest(cx, y + 1, cz, face, chest);
        k.barrel(cx + face.getStepX(), y + 1, cz + face.getStepZ(), SiteKit.loot("chests/harbor_supplies"));
        k.set(x - side.getStepX() * (wid - 1), y + 1, z - side.getStepZ() * (wid - 1), p.fence());
        k.set(x - side.getStepX() * (wid - 1), y + 2, z - side.getStepZ() * (wid - 1), s(Blocks.SPRUCE_PRESSURE_PLATE));
        k.set(x, y + 3, z, p.light().hasProperty(BlockStateProperties.HANGING) ? p.light().setValue(BlockStateProperties.HANGING, true) : p.light());
    }

    // ================================================================================================ pirates and smugglers

    /** A pirates' camp on an islet's lagoon: lean-tos, a fire, plunder in barrels, and treasure buried under a cairn. */
    static void pirateCamp(SiteKit k, int x, int y, int z, Direction face) {
        Direction side = face.getClockWise();
        // two lean-to tents of patched sailcloth
        for (int t = -1; t <= 1; t += 2) {
            int tx = x + side.getStepX() * 4 * t, tz = z + side.getStepZ() * 4 * t;
            BlockState cloth = t < 0 ? s(com.skycraft.worldgen.VanillaBlocks.BLACK_WOOL) : s(com.skycraft.worldgen.VanillaBlocks.GRAY_WOOL);
            for (int a = -1; a <= 1; a++) {
                for (int l = -2; l <= 2; l++) {
                    int h = 2 - Math.abs(l);
                    int wx = tx + face.getStepX() * a + side.getStepX() * l, wz = tz + face.getStepZ() * a + side.getStepZ() * l;
                    if (h > 0) k.set(wx, y + h, wz, cloth);
                    k.set(wx, y, wz, s(Blocks.SAND));
                }
            }
        }
        k.set(x, y, z, s(Blocks.CAMPFIRE).setValue(CampfireBlock.LIT, true));
        for (Direction d : Direction.Plane.HORIZONTAL) {
            k.set(x + d.getStepX() * 2, y, z + d.getStepZ() * 2, s(Blocks.SPRUCE_LOG).setValue(RotatedPillarBlock.AXIS, d.getClockWise().getAxis()));
        }
        k.barrel(x + face.getStepX() * 3, y, z + face.getStepZ() * 3, loot("pirate_plunder"));
        k.barrel(x + face.getStepX() * 3 + side.getStepX(), y, z + face.getStepZ() * 3 + side.getStepZ(), SiteKit.loot("chests/flotsam"));
        k.set(x + face.getStepX() * 3 - side.getStepX(), y, z + face.getStepZ() * 3 - side.getStepZ(), s(Blocks.BARREL));
        // the treasure: a cairn of stones with a chest buried under it
        int tx = x - face.getStepX() * 5, tz = z - face.getStepZ() * 5;
        int ty = k.floor(tx, tz);
        k.chest(tx, ty - 2, tz, face, loot("pirate_treasure"));
        k.set(tx, ty - 1, tz, s(Blocks.SAND));
        k.set(tx, ty, tz, s(Blocks.COBBLESTONE));
        k.set(tx, ty + 1, tz, s(Blocks.COBBLESTONE_WALL));
        k.set(tx, ty + 2, tz, s(Blocks.SKELETON_SKULL));
        LairMarkerBlockEntity m = k.marker(x + side.getStepX() * 2, y + 1, z + side.getStepZ() * 2);
        if (m != null) m.npc("thug", 2 + k.r.nextInt(2), "seas.skycraft.crew.pirate", true);
    }

    /**
     * A cave in rock reached from the water: a tunnel at sea level into a domed chamber with a shelf of sand, a lamp,
     * crates and a chest. {@code smugglers}: and the smugglers who use it.
     */
    static void seaCave(SiteKit k, int x, int z, Direction in, boolean smugglers) {
        cave(k, x, z, in, smugglers, SEA, true);
    }

    /** {@link #seaCave}, or dug into a coastal hillside from the beach at {@code floorY} when not {@code wet}. */
    static void cave(SiteKit k, int x, int z, Direction in, boolean smugglers, int floorY, boolean wet) {
        BlockState ground = wet ? s(Blocks.WATER) : s(Blocks.GRAVEL);
        // tunnel from the outside in: water below, air above
        for (int a = -9; a <= 0; a++) {
            for (int l = -1; l <= 1; l++) {
                int wx = x + in.getStepX() * a + in.getClockWise().getStepX() * l, wz = z + in.getStepZ() * a + in.getClockWise().getStepZ() * l;
                k.set(wx, floorY - 1, wz, wet ? s(Blocks.WATER) : s(Blocks.STONE));
                k.set(wx, floorY, wz, ground);
                for (int dy = 1; dy <= 3; dy++) k.air(wx, floorY + dy, wz);
            }
        }
        // the chamber
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                double d = Math.hypot(dx, dz);
                if (d > 4.3) continue;
                int wx = x + dx, wz = z + dz;
                for (int dy = 1; dy <= 4 - (int) (d / 2); dy++) k.air(wx, floorY + dy, wz);
                boolean shelf = d > 2.2 || !wet;
                k.set(wx, floorY, wz, shelf ? (wet ? s(Blocks.SAND) : s(Blocks.COARSE_DIRT)) : s(Blocks.WATER));
                if (!shelf) k.set(wx, floorY - 1, wz, s(Blocks.WATER));
            }
        }
        Direction side = in.getClockWise();
        int ex = x + in.getStepX() * 3, ez = z + in.getStepZ() * 3;
        k.chest(ex, floorY + 1, ez, in.getOpposite(), loot(smugglers ? "smuggler_cache" : "tidal_cave"));
        k.barrel(ex + side.getStepX(), floorY + 1, ez + side.getStepZ(), SiteKit.loot(smugglers ? "chests/seas/smuggler_cache" : "chests/flotsam"));
        k.set(ex - side.getStepX(), floorY + 1, ez - side.getStepZ(), s(Blocks.LANTERN));
        k.set(x - side.getStepX() * 3, floorY + 1, z - side.getStepZ() * 3, s(Blocks.BARREL));
        if (smugglers) {
            LairMarkerBlockEntity m = k.marker(x + side.getStepX() * 3, floorY + 1, z + side.getStepZ() * 3);
            if (m != null) m.npc("thug", 2, "seas.skycraft.crew.smuggler", true);
        } else {
            LairMarkerBlockEntity m = k.marker(x, floorY + 1, z);
            if (m != null) m.creature("skycraft:sea_crab", 1 + k.r.nextInt(2), 2);
        }
    }

    // ================================================================================================ birds and beasts

    /** Nests on ledges, white with guano, and a clutch worth the climb. */
    static void rookery(SiteKit k, int x, int y, int z, int radius) {
        for (int i = 0; i < radius * 5; i++) {
            int wx = x + k.nextInt(-radius, radius), wz = z + k.nextInt(-radius, radius);
            int top = k.surface(wx, wz);
            if (top <= SEA + 1 || k.water(wx, top - 1, wz)) continue;
            int roll = k.r.nextInt(5);
            if (roll < 2) k.set(wx, top - 1, wz, s(Blocks.CALCITE));
            else if (roll == 2) k.set(wx, top, wz, s(Blocks.HAY_BLOCK));
            else if (roll == 3) k.set(wx, top, wz, s(Blocks.DEAD_BUSH));
            else k.set(wx, top - 1, wz, s(com.skycraft.worldgen.VanillaBlocks.WHITE_CONCRETE_POWDER));
        }
        int nx = x + k.nextInt(-2, 2), nz = z + k.nextInt(-2, 2);
        int ny = k.surface(nx, nz);
        k.chest(nx, ny, nz, Direction.Plane.HORIZONTAL.getRandomDirection(k.r), loot("rookery_eggs"));
        for (Direction d : Direction.Plane.HORIZONTAL) k.setIfOpen(nx + d.getStepX(), ny, nz + d.getStepZ(), s(Blocks.HAY_BLOCK));
        k.set(nx, ny + 1, nz, s(Blocks.SPRUCE_SLAB).setValue(SlabBlock.TYPE, SlabType.BOTTOM));
    }

    /** A tall sea stack of weathered rock with a nest on top. */
    static void seaStack(SiteKit k, int x, int z) {
        int bed = k.floor(x, z);
        int top = SEA + 8 + k.r.nextInt(12);
        double phase = k.r.nextDouble() * 6;
        for (int y = bed; y <= top; y++) {
            double t = (double) (y - bed) / (top - bed);
            double rad = 3.2 - t * 1.4 + 0.5 * Math.sin(y * 0.7 + phase);
            int ir = (int) Math.ceil(rad);
            for (int dx = -ir; dx <= ir; dx++) {
                for (int dz = -ir; dz <= ir; dz++) {
                    if (dx * dx + dz * dz > rad * rad) continue;
                    int roll = (x + dx) * 7 + y * 13 + (z + dz) * 3;
                    k.set(x + dx, y, z + dz, (roll & 7) == 0 ? s(Blocks.ANDESITE) : (roll & 7) == 1 ? s(Blocks.TUFF) : s(Blocks.STONE));
                }
            }
        }
        k.set(x, top + 1, z, s(Blocks.HAY_BLOCK));
        k.set(x + 1, top + 1, z, s(Blocks.DEAD_BUSH));
        k.chest(x - 1, top + 1, z, Direction.NORTH, loot("rookery_eggs"));
        LairMarkerBlockEntity m = k.marker(x, top + 3, z + 1);
        if (m != null) m.creature("skycraft:hawk", 1 + k.r.nextInt(2), 2);
    }

    /** A hermit's hut with a garden and a drying rack of fish. */
    static void hermit(SiteKit k, int x, int y, int z, CoastPalette p, Direction face, String culture) {
        cottage(k, x, y, z, p, face, culture, loot("hermit"));
        Direction side = face.getClockWise();
        int gx = x + face.getStepX() * 7, gz = z + face.getStepZ() * 7;
        for (int a = -1; a <= 1; a++) {
            for (int l = -2; l <= 2; l++) {
                int wx = gx + face.getStepX() * a + side.getStepX() * l, wz = gz + face.getStepZ() * a + side.getStepZ() * l;
                int wy = k.floor(wx, wz);
                if (Math.abs(wy - y) > 2) continue;
                if (a == 0 && l == 0) {
                    k.set(wx, wy - 1, wz, s(Blocks.WATER));
                    continue;
                }
                k.set(wx, wy - 1, wz, s(Blocks.FARMLAND).setValue(BlockStateProperties.MOISTURE, 7));
                k.set(wx, wy, wz, (l % 2 == 0 ? s(Blocks.POTATOES) : s(Blocks.CARROTS)).setValue(CropBlock.AGE, 4 + k.r.nextInt(4)));
            }
        }
        int rx = x - side.getStepX() * 6, rz = z - side.getStepZ() * 6;
        int ry = k.floor(rx, rz);
        k.set(rx, ry, rz, p.fence());
        k.set(rx + face.getStepX() * 2, ry, rz + face.getStepZ() * 2, p.fence());
        k.set(rx, ry + 1, rz, p.fence());
        k.set(rx + face.getStepX() * 2, ry + 1, rz + face.getStepZ() * 2, p.fence());
        k.set(rx + face.getStepX(), ry + 2, rz + face.getStepZ(), s(Blocks.SPRUCE_SLAB));
        LairMarkerBlockEntity m = k.marker(x + face.getStepX() * 5, y + 1, z + face.getStepZ() * 5);
        if (m != null) m.npc("beggar", 1, "seas.skycraft.npc.hermit", false);
    }

    /** A ship cast up on a beach: the broken hull on its side half in the sand, cargo strewn about, crabs. */
    static void wreckAshore(SiteKit k, int x, int z, Direction f) {
        Direction side = f.getClockWise();
        BlockState plank = s(Blocks.SPRUCE_PLANKS), log = s(Blocks.SPRUCE_LOG).setValue(RotatedPillarBlock.AXIS, f.getAxis());
        for (int a = -6; a <= 6; a++) {
            double t = a / 7.0;
            int w = Math.max(0, (int) Math.round(2.4 * Math.sqrt(Math.max(0, 1 - t * t))));
            for (int l = -w; l <= w; l++) {
                int wx = x + f.getStepX() * a + side.getStepX() * l, wz = z + f.getStepZ() * a + side.getStepZ() * l;
                int y = k.floor(wx, wz);
                if (k.r.nextInt(5) == 0) continue;
                // lying on its side: the low side buried, the high side rising
                int h = l > 0 ? 3 : 1;
                k.set(wx, y, wz, l == 0 ? log : plank);
                if (Math.abs(l) == w) for (int dy = 1; dy <= h; dy++) if (k.r.nextInt(4) > 0) k.set(wx, y + dy, wz, plank);
            }
        }
        // the mast across the sand
        for (int i = 1; i <= 7; i++) {
            int wx = x + side.getStepX() * (2 + i), wz = z + side.getStepZ() * (2 + i);
            k.set(wx, k.floor(wx, wz), wz, s(Blocks.SPRUCE_LOG).setValue(RotatedPillarBlock.AXIS, side.getAxis()));
        }
        k.chest(x, k.floor(x, z), z, f, SiteKit.loot("chests/sea_wreck"));
        for (int i = 0; i < 4; i++) {
            int wx = x + k.nextInt(-8, 8), wz = z + k.nextInt(-8, 8);
            int y = k.floor(wx, wz);
            if (k.water(wx, y, wz) && k.depth(wx, wz) > 1) continue;
            if (i < 2) k.barrel(wx, y, wz, SiteKit.loot("chests/flotsam"));
            else k.set(wx, y, wz, s(com.skycraft.worldgen.VanillaBlocks.WHITE_WOOL));
        }
        LairMarkerBlockEntity m = k.marker(x + side.getStepX() * 3, k.floor(x, z) + 1, z + side.getStepZ() * 3);
        if (m != null) m.creature("skycraft:mudcrab", 2 + k.r.nextInt(2), 4);
    }

    /** The bones of a whale on a beach: a spine with great curved ribs and the skull. */
    static void whaleBones(SiteKit k, int x, int z, Direction f) {
        Direction side = f.getClockWise();
        for (int a = -8; a <= 8; a++) {
            int wx = x + f.getStepX() * a, wz = z + f.getStepZ() * a;
            int y = k.floor(wx, wz);
            k.set(wx, y, wz, s(Blocks.BONE_BLOCK).setValue(RotatedPillarBlock.AXIS, f.getAxis()));
            if (a > -5 && a < 4 && Math.floorMod(a, 2) == 0) {
                for (int sgn = -1; sgn <= 1; sgn += 2) {
                    for (int l = 1; l <= 4; l++) {
                        int rx = wx + side.getStepX() * l * sgn, rz = wz + side.getStepZ() * l * sgn;
                        int ry = y + (l <= 2 ? l : 5 - l);
                        k.set(rx, ry, rz, s(Blocks.BONE_BLOCK));
                    }
                }
            }
        }
        for (int a = 9; a <= 11; a++) {
            for (int l = -1; l <= 1; l++) {
                int wx = x + f.getStepX() * a + side.getStepX() * l, wz = z + f.getStepZ() * a + side.getStepZ() * l;
                k.set(wx, k.floor(wx, wz), wz, s(Blocks.BONE_BLOCK));
            }
        }
    }

    /** An ice floe on the Sea of Ghosts, snow-dusted, with horkers and seals hauled out on it. */
    static void iceFloe(SiteKit k, int x, int z) {
        float rad = 5 + k.r.nextFloat() * 5;
        int ir = (int) Math.ceil(rad) + 1;
        double ph = k.r.nextDouble() * 6;
        for (int dx = -ir; dx <= ir; dx++) {
            for (int dz = -ir; dz <= ir; dz++) {
                double a = Math.atan2(dz, dx);
                double edge = rad * (0.8 + 0.2 * Math.sin(a * 4 + ph));
                if (Math.hypot(dx, dz) > edge) continue;
                k.set(x + dx, SEA, z + dz, k.r.nextInt(6) == 0 ? s(Blocks.ICE) : s(Blocks.PACKED_ICE));
                if (k.r.nextInt(3) > 0) k.set(x + dx, SEA + 1, z + dz, s(Blocks.SNOW));
            }
        }
        LairMarkerBlockEntity m = k.marker(x, SEA + 1, z);
        if (m != null) {
            m.creature("skycraft:horker", 1 + k.r.nextInt(3), 3);
            m.creature("skycraft:seal", 1 + k.r.nextInt(3), 4);
        }
    }

    /** Low rocks on a cold beach where horkers bask. */
    static void horkerRocks(SiteKit k, int x, int z) {
        for (int i = 0; i < 6; i++) {
            int wx = x + k.nextInt(-5, 5), wz = z + k.nextInt(-5, 5);
            int y = k.floor(wx, wz);
            k.set(wx, y, wz, k.r.nextBoolean() ? s(Blocks.STONE) : s(Blocks.MOSSY_COBBLESTONE));
        }
        LairMarkerBlockEntity m = k.marker(x, k.floor(x, z) + 1, z);
        if (m != null) m.creature("skycraft:horker", 2 + k.r.nextInt(3), 4);
    }

    /** A wooden landing stage for a rowboat at a cove or cottage. */
    static void jetty(SiteKit k, int x, int z, Direction out, CoastPalette p) {
        for (int a = 0; a <= 6; a++) {
            int wx = x + out.getStepX() * a, wz = z + out.getStepZ() * a;
            for (int l = -1; l <= 1; l++) {
                int px = wx + out.getClockWise().getStepX() * l, pz = wz + out.getClockWise().getStepZ() * l;
                k.set(px, SEA + 1, pz, s(Blocks.SPRUCE_SLAB).setValue(SlabBlock.TYPE, SlabType.TOP));
                if (Math.abs(l) == 1 && a % 3 == 0) k.pillarDown(px, SEA, pz, p.log(), 8);
            }
        }
        int ex = x + out.getStepX() * 6, ez = z + out.getStepZ() * 6;
        k.set(ex, SEA + 2, ez, p.fence());
        k.set(ex, SEA + 3, ez, s(Blocks.LANTERN));
    }

    /** Stairs from a beach up a slope (lighthouse paths). */
    static void stepTo(SiteKit k, int x, int z, int fromY, int toY, Direction dir, CoastPalette p) {
        int y = fromY;
        int i = 0;
        while (y < toY && i < 12) {
            int wx = x + dir.getStepX() * i, wz = z + dir.getStepZ() * i;
            k.set(wx, y, wz, p.roofStairs().defaultBlockState().setValue(StairBlock.FACING, dir).setValue(StairBlock.HALF, Half.BOTTOM));
            k.air(wx, y + 1, wz);
            k.air(wx, y + 2, wz);
            y++;
            i++;
        }
    }
}
