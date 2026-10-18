package com.skycraft.sailing;

import com.skycraft.Skycraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds a port's pier in the world (on demand, inside loaded chunks): a plank pier on log posts from the shore out
 * to deep water with a T-shaped head, lanterns on the bollards, barrels and a sign, a harbormaster's hut on the bank
 * when the ground allows, and the steps up a low bank. Foreign landings get a rough log jetty and an expedition camp
 * (tents, campfire, supplies) instead. Blocks follow the port's culture ({@link Palette}). Coordinates along the pier:
 * {@code a} grows seaward from the shoreline, {@code l} runs across it.
 */
public final class DockBuilder {
    /** Deck height: one block above the sea's surface block (y 62). */
    static final int DECK_Y = 63;
    private static final Identifier CAMP_LOOT = Identifier.fromNamespaceAndPath(Skycraft.MODID, "chests/landing_camp");
    private static final Identifier HARBOR_LOOT = Identifier.fromNamespaceAndPath(Skycraft.MODID, "chests/harbor_supplies");

    /** What was built: the pier head arrival spot, where the keeper stands, and a berth for the moored ship. */
    public record Result(BlockPos arrival, BlockPos keeper, @Nullable Vec3 berth, float berthYaw, boolean adopted) {
    }

    /** The block palette of a style. */
    record Palette(BlockState deck, BlockState post, BlockState wall, BlockState corner, BlockState roof,
                   BlockState roofSlab, BlockState foundation, BlockState door, BlockState canvas, BlockState fence) {
    }

    private DockBuilder() {}

    static Palette palette(String style) {
        return switch (style) {
            case "imperial" -> new Palette(Blocks.OAK_PLANKS.defaultBlockState(), Blocks.STRIPPED_OAK_LOG.defaultBlockState(),
                    Blocks.STONE_BRICKS.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(), Blocks.DARK_OAK_STAIRS.defaultBlockState(),
                    Blocks.DARK_OAK_SLAB.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(), Blocks.OAK_DOOR.defaultBlockState(),
                    com.skycraft.worldgen.VanillaBlocks.RED_WOOL.defaultBlockState(), Blocks.OAK_FENCE.defaultBlockState());
            case "dunmer" -> new Palette(Blocks.DARK_OAK_PLANKS.defaultBlockState(), Blocks.DARK_OAK_LOG.defaultBlockState(),
                    Blocks.MUD_BRICKS.defaultBlockState(), Blocks.PACKED_MUD.defaultBlockState(), Blocks.MUD_BRICK_STAIRS.defaultBlockState(),
                    Blocks.MUD_BRICK_SLAB.defaultBlockState(), Blocks.PACKED_MUD.defaultBlockState(), Blocks.DARK_OAK_DOOR.defaultBlockState(),
                    com.skycraft.worldgen.VanillaBlocks.BROWN_WOOL.defaultBlockState(), Blocks.DARK_OAK_FENCE.defaultBlockState());
            case "argonian" -> new Palette(Blocks.MANGROVE_PLANKS.defaultBlockState(), Blocks.MANGROVE_LOG.defaultBlockState(),
                    Blocks.MANGROVE_PLANKS.defaultBlockState(), Blocks.MANGROVE_LOG.defaultBlockState(), Blocks.MANGROVE_STAIRS.defaultBlockState(),
                    Blocks.MANGROVE_SLAB.defaultBlockState(), Blocks.MUD_BRICKS.defaultBlockState(), Blocks.MANGROVE_DOOR.defaultBlockState(),
                    com.skycraft.worldgen.VanillaBlocks.GREEN_WOOL.defaultBlockState(), Blocks.MANGROVE_FENCE.defaultBlockState());
            case "khajiit" -> new Palette(Blocks.ACACIA_PLANKS.defaultBlockState(), Blocks.ACACIA_LOG.defaultBlockState(),
                    Blocks.SMOOTH_SANDSTONE.defaultBlockState(), Blocks.CUT_SANDSTONE.defaultBlockState(), Blocks.ACACIA_STAIRS.defaultBlockState(),
                    Blocks.ACACIA_SLAB.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(), Blocks.ACACIA_DOOR.defaultBlockState(),
                    com.skycraft.worldgen.VanillaBlocks.ORANGE_WOOL.defaultBlockState(), Blocks.ACACIA_FENCE.defaultBlockState());
            case "redguard" -> new Palette(Blocks.ACACIA_PLANKS.defaultBlockState(), Blocks.STRIPPED_ACACIA_LOG.defaultBlockState(),
                    Blocks.SMOOTH_SANDSTONE.defaultBlockState(), Blocks.CHISELED_SANDSTONE.defaultBlockState(), Blocks.SANDSTONE_STAIRS.defaultBlockState(),
                    Blocks.SANDSTONE_SLAB.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(), Blocks.ACACIA_DOOR.defaultBlockState(),
                    com.skycraft.worldgen.VanillaBlocks.YELLOW_WOOL.defaultBlockState(), Blocks.ACACIA_FENCE.defaultBlockState());
            case "breton" -> new Palette(Blocks.OAK_PLANKS.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(),
                    Blocks.COBBLESTONE.defaultBlockState(), Blocks.OAK_LOG.defaultBlockState(), Blocks.SPRUCE_STAIRS.defaultBlockState(),
                    Blocks.SPRUCE_SLAB.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), Blocks.SPRUCE_DOOR.defaultBlockState(),
                    com.skycraft.worldgen.VanillaBlocks.BLUE_WOOL.defaultBlockState(), Blocks.OAK_FENCE.defaultBlockState());
            case "bosmer" -> new Palette(Blocks.JUNGLE_PLANKS.defaultBlockState(), Blocks.JUNGLE_LOG.defaultBlockState(),
                    Blocks.JUNGLE_PLANKS.defaultBlockState(), Blocks.JUNGLE_LOG.defaultBlockState(), Blocks.JUNGLE_STAIRS.defaultBlockState(),
                    Blocks.JUNGLE_SLAB.defaultBlockState(), Blocks.MOSSY_COBBLESTONE.defaultBlockState(), Blocks.JUNGLE_DOOR.defaultBlockState(),
                    com.skycraft.worldgen.VanillaBlocks.LIME_WOOL.defaultBlockState(), Blocks.JUNGLE_FENCE.defaultBlockState());
            case "altmer" -> new Palette(Blocks.BIRCH_PLANKS.defaultBlockState(), Blocks.STRIPPED_BIRCH_LOG.defaultBlockState(),
                    Blocks.CALCITE.defaultBlockState(), Blocks.QUARTZ_PILLAR.defaultBlockState(), Blocks.BIRCH_STAIRS.defaultBlockState(),
                    Blocks.BIRCH_SLAB.defaultBlockState(), Blocks.SMOOTH_QUARTZ.defaultBlockState(), Blocks.BIRCH_DOOR.defaultBlockState(),
                    com.skycraft.worldgen.VanillaBlocks.YELLOW_WOOL.defaultBlockState(), Blocks.BIRCH_FENCE.defaultBlockState());
            case "akaviri" -> new Palette(Blocks.BAMBOO_PLANKS.defaultBlockState(), Blocks.STRIPPED_CHERRY_LOG.defaultBlockState(),
                    Blocks.CHERRY_PLANKS.defaultBlockState(), Blocks.CHERRY_LOG.defaultBlockState(), Blocks.CHERRY_STAIRS.defaultBlockState(),
                    Blocks.CHERRY_SLAB.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(), Blocks.CHERRY_DOOR.defaultBlockState(),
                    com.skycraft.worldgen.VanillaBlocks.RED_WOOL.defaultBlockState(), Blocks.BAMBOO_FENCE.defaultBlockState());
            case "yokudan" -> new Palette(Blocks.ACACIA_PLANKS.defaultBlockState(), Blocks.ACACIA_LOG.defaultBlockState(),
                    Blocks.SANDSTONE.defaultBlockState(), Blocks.CUT_SANDSTONE.defaultBlockState(), Blocks.SANDSTONE_STAIRS.defaultBlockState(),
                    Blocks.SANDSTONE_SLAB.defaultBlockState(), Blocks.SANDSTONE.defaultBlockState(), Blocks.ACACIA_DOOR.defaultBlockState(),
                    com.skycraft.worldgen.VanillaBlocks.WHITE_WOOL.defaultBlockState(), Blocks.ACACIA_FENCE.defaultBlockState());
            case "maormer", "coral" -> new Palette(Blocks.BIRCH_PLANKS.defaultBlockState(), Blocks.STRIPPED_BIRCH_LOG.defaultBlockState(),
                    Blocks.PRISMARINE_BRICKS.defaultBlockState(), Blocks.PRISMARINE.defaultBlockState(), Blocks.PRISMARINE_BRICK_STAIRS.defaultBlockState(),
                    Blocks.PRISMARINE_BRICK_SLAB.defaultBlockState(), Blocks.PRISMARINE.defaultBlockState(), Blocks.BIRCH_DOOR.defaultBlockState(),
                    com.skycraft.worldgen.VanillaBlocks.CYAN_WOOL.defaultBlockState(), Blocks.BIRCH_FENCE.defaultBlockState());
            default -> new Palette(Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.SPRUCE_LOG.defaultBlockState(),
                    Blocks.SPRUCE_PLANKS.defaultBlockState(), Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState(), Blocks.SPRUCE_STAIRS.defaultBlockState(),
                    Blocks.SPRUCE_SLAB.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(), Blocks.SPRUCE_DOOR.defaultBlockState(),
                    com.skycraft.worldgen.VanillaBlocks.WHITE_WOOL.defaultBlockState(), Blocks.SPRUCE_FENCE.defaultBlockState());
        };
    }

    // ------------------------------------------------------------------ site geometry

    /** The block area (min/max x, z) the builder may touch for a site, so callers can make sure it is loaded. */
    static int[] footprint(PortSites.Site site) {
        Direction toLand = site.toLand();
        int mapShore = Math.abs(site.shoreX() - site.waterX()) + Math.abs(site.shoreZ() - site.waterZ());
        int back = -SCAN_SEAWARD - 36, fwd = mapShore + SCAN_LANDWARD + 30, side = 40;
        int x0 = site.waterX() + toLand.getStepX() * back, z0 = site.waterZ() + toLand.getStepZ() * back;
        int x1 = site.waterX() + toLand.getStepX() * fwd, z1 = site.waterZ() + toLand.getStepZ() * fwd;
        int sx = Math.abs(toLand.getClockWise().getStepX()) * side, sz = Math.abs(toLand.getClockWise().getStepZ()) * side;
        return new int[]{Math.min(x0, x1) - sx, Math.min(z0, z1) - sz, Math.max(x0, x1) + sx, Math.max(z0, z1) + sz};
    }

    private static final int SCAN_SEAWARD = 32, SCAN_LANDWARD = 40;

    /** A frame on the pier axis: origin on the shoreline, {@code sea} pointing seaward, {@code across} to the right. */
    private record Frame(int ox, int oz, Direction sea, Direction across) {
        BlockPos at(int a, int y, int l) {
            return new BlockPos(ox + sea.getStepX() * a + across.getStepX() * l, y, oz + sea.getStepZ() * a + across.getStepZ() * l);
        }
    }

    /** Open or frozen sea at sea level (the Sea of Ghosts freezes over near the shore). */
    private static boolean water(ServerLevel level, int x, int z) {
        BlockPos p = new BlockPos(x, DECK_Y - 1, z);
        return level.getFluidState(p).is(FluidTags.WATER) || level.getBlockState(p).is(BlockTags.ICE);
    }

    /** Top solid block of a column (ignoring water, ice and plants). */
    private static int ground(ServerLevel level, int x, int z) {
        int y = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y, z);
        while (p.getY() > level.getMinY() && (level.getBlockState(p).is(BlockTags.ICE) || !level.getFluidState(p).isEmpty())) {
            p.move(0, -1, 0);
        }
        return p.getY();
    }

    /** Dry ground at or just under sea level counts (marshy flats). */
    private static boolean land(ServerLevel level, int x, int z) {
        return !water(level, x, z) && ground(level, x, z) >= DECK_Y - 4;
    }

    /** Breaks the ice over an area of sea (pier head, berth and the way out) so ships can come and go. */
    private static void thaw(ServerLevel level, Frame f, int a0, int a1, int l0, int l1) {
        for (int a = a0; a <= a1; a++) {
            for (int l = l0; l <= l1; l++) {
                BlockPos p = f.at(a, DECK_Y - 1, l);
                if (level.getBlockState(p).is(BlockTags.ICE)) {
                    level.setBlock(p, Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
                    BlockPos up = p.above();
                    if (level.getBlockState(up).is(Blocks.SNOW)) level.setBlock(up, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
        }
    }

    /**
     * Finds the real shoreline along the site's line (or lines beside it): a land column with three water columns on
     * its seaward side, nearest the map's estimate.
     */
    @Nullable
    private static Frame findShore(ServerLevel level, PortSites.Site site) {
        Direction toLand = site.toLand();
        Direction sea = toLand.getOpposite();
        Direction across = sea.getClockWise();
        int mapShore = Math.abs(site.shoreX() - site.waterX()) + Math.abs(site.shoreZ() - site.waterZ());
        for (int lateral : new int[]{0, 6, -6, 12, -12}) {
            int bx = site.waterX() + across.getStepX() * lateral, bz = site.waterZ() + across.getStepZ() * lateral;
            int best = Integer.MIN_VALUE;
            for (int t = -SCAN_SEAWARD; t <= mapShore + SCAN_LANDWARD; t++) {
                int x = bx + toLand.getStepX() * t, z = bz + toLand.getStepZ() * t;
                if (!land(level, x, z) || !land(level, x + toLand.getStepX(), z + toLand.getStepZ())) continue;
                boolean sea3 = true;
                for (int k = 1; k <= 3; k++) {
                    if (!water(level, x - toLand.getStepX() * k, z - toLand.getStepZ() * k)) {
                        sea3 = false;
                        break;
                    }
                }
                if (sea3 && (best == Integer.MIN_VALUE || Math.abs(t - mapShore) < Math.abs(best - mapShore))) best = t;
            }
            if (best != Integer.MIN_VALUE) {
                return new Frame(bx + toLand.getStepX() * best, bz + toLand.getStepZ() * best, sea, across);
            }
        }
        // the map's water was dry land here (or shallows): look around it for real water and any shore of it
        for (int r = 0; r <= 24; r += 3) {
            for (int dx = -r; dx <= r; dx += 3) {
                for (int dz = -r; dz <= r; dz += 3) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int wx = site.waterX() + dx, wz = site.waterZ() + dz;
                    if (!water(level, wx, wz) || DECK_Y - 1 - ground(level, wx, wz) < 2) continue;
                    Frame f = shoreAround(level, wx, wz, toLand);
                    if (f != null) return f;
                }
            }
        }
        return null;
    }

    /** From a water column, the nearest shore in the four directions (the preferred one first on ties). */
    @Nullable
    private static Frame shoreAround(ServerLevel level, int wx, int wz, Direction preferred) {
        Frame best = null;
        int bestT = Integer.MAX_VALUE;
        for (Direction d : new Direction[]{preferred, preferred.getClockWise(), preferred.getCounterClockWise(), preferred.getOpposite()}) {
            for (int t = 3; t <= 28 && t < bestT; t++) {
                int x = wx + d.getStepX() * t, z = wz + d.getStepZ() * t;
                if (!land(level, x, z)) {
                    if (!water(level, x, z)) break; // neither: a wall or cliff face
                    continue;
                }
                if (land(level, x + d.getStepX(), z + d.getStepZ()) && water(level, x - d.getStepX(), z - d.getStepZ())
                        && water(level, x - d.getStepX() * 2, z - d.getStepZ() * 2) && water(level, x - d.getStepX() * 3, z - d.getStepZ() * 3)) {
                    bestT = t;
                    best = new Frame(x, z, d.getOpposite(), d.getOpposite().getClockWise());
                }
                break;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ building

    /** Builds (or adopts) the pier of {@code port}. All chunks of {@link #footprint} must be loaded. */
    @Nullable
    public static Result build(ServerLevel level, Port port, PortSites.Site site) {
        Frame f = findShore(level, site);
        boolean shore = f != null;
        if (f == null) {
            // no shore here after all: a free-standing landing stage on the open water (never on dry land)
            if (!water(level, site.waterX(), site.waterZ())) return null;
            Direction sea = site.toLand().getOpposite();
            f = new Frame(site.waterX(), site.waterZ(), sea, sea.getClockWise());
        }
        Result adopted = adoptExisting(level, f);
        if (adopted != null) return adopted;
        RandomSource random = RandomSource.create(port.id().hashCode());
        boolean camp = port.isLanding();
        Palette p = palette(port.style());
        int length = pierLength(level, f, camp);
        Skycraft.LOGGER.debug("Port {}: shoreline {} {} facing {} (found {}), pier length {}", port.id(), f.ox(), f.oz(), f.sea(), shore, length);
        int head = camp ? 0 : 3; // half width of the T-head beyond the walkway
        thaw(level, f, -1, length + 14, -(2 + head) - 12, 2 + head + 12);

        // landward steps up the bank
        int[] deck = new int[13];
        deck[0] = DECK_Y;
        int inland = 3;
        for (int a = -1; a >= -12; a--) {
            BlockPos g = f.at(a, 0, 0);
            int top = ground(level, g.getX(), g.getZ());
            int prev = deck[-(a + 1)];
            deck[-a] = Math.max(DECK_Y, Math.min(top, prev + 1));
            if (a <= -3 && top <= deck[-a]) {
                inland = -a;
                break;
            }
            inland = -a;
        }

        // the walkway
        for (int a = -inland; a <= length; a++) {
            boolean isHead = a >= length - 3;
            int half = isHead ? 2 + head : camp ? 1 : 2;
            int y = a < 0 ? deck[-a] : DECK_Y;
            for (int l = -half; l <= half; l++) {
                BlockPos pos = f.at(a, y, l);
                BlockState block = camp ? p.post().setValue(RotatedPillarBlock.AXIS, f.sea().getAxis()) : p.deck();
                if (a < 0 && y > (a + 1 < 0 ? deck[-(a + 1)] : DECK_Y) && l != -half && l != half) {
                    block = p.roof().setValue(StairBlock.FACING, f.sea().getOpposite()); // a step up the bank
                }
                set(level, pos, block);
                for (int up = 1; up <= 3; up++) clearIfSolid(level, pos.above(up));
                if (a < 0) fillDown(level, pos.below(), p.foundation(), 6);
            }
            // posts and bollards
            boolean postRow = a >= 0 && (a % 4 == 0 || a == length);
            if (postRow) {
                for (int l : isHead ? new int[]{-half, half} : new int[]{-half, half}) {
                    BlockPos top = f.at(a, DECK_Y, l);
                    set(level, top, p.post());
                    set(level, top.above(), p.post());
                    sinkPost(level, top.below(), p.post());
                    if ((a / 4) % 2 == 0 || a == length) set(level, top.above(2), Blocks.LANTERN.defaultBlockState());
                }
            }
            if (isHead && a == length) {
                for (int l = -half + 2; l <= half - 2; l += 2) sinkPost(level, f.at(a, DECK_Y - 1, l), p.post());
            }
        }

        // deck furniture on the head
        if (!camp) {
            set(level, f.at(length - 1, DECK_Y + 1, -(2 + head) + 1), Blocks.BARREL.defaultBlockState());
            set(level, f.at(length - 2, DECK_Y + 1, -(2 + head) + 1), Blocks.BARREL.defaultBlockState());
            set(level, f.at(length - 1, DECK_Y + 2, -(2 + head) + 1), Blocks.BARREL.defaultBlockState());
            set(level, f.at(length - 1, DECK_Y + 1, (2 + head) - 1), com.skycraft.worldgen.VanillaBlocks.CHAIN.defaultBlockState());
            set(level, f.at(length - 3, DECK_Y + 1, (2 + head) - 1), p.fence());
            set(level, f.at(length - 3, DECK_Y + 2, (2 + head) - 1), Blocks.LANTERN.defaultBlockState());
        }
        sign(level, f.at(2, DECK_Y + 1, camp ? 1 : 2), f.sea().getOpposite(), port.name());

        // the hut or the expedition camp on the bank
        if (camp) buildCamp(level, f, p, random);
        else buildHut(level, f, p, inland);

        BlockPos arrival = f.at(length - 2, DECK_Y + 1, 0);
        BlockPos keeper = f.at(1, DECK_Y + 1, 0);
        // berth: alongside the pier head, bow to sea
        Vec3 berth = null;
        float yaw = yawOf(f.sea());
        float shipHalfBeam = port.ferry().beam / 2f;
        for (int side : new int[]{1, -1}) {
            double l = side * (2 + head + 1.6 + shipHalfBeam);
            BlockPos c = f.at(length - 3, DECK_Y, 0);
            double cx = c.getX() + 0.5 + f.across().getStepX() * l, cz = c.getZ() + 0.5 + f.across().getStepZ() * l;
            if (berthClear(level, cx, cz, f.sea(), port.ferry())) {
                berth = new Vec3(cx, DECK_Y - 1 + 0.9 - port.ferry().draft, cz);
                break;
            }
        }
        return new Result(arrival, keeper, berth, yaw, false);
    }

    static float yawOf(Direction d) {
        return (float) Math.toDegrees(Math.atan2(-d.getStepX(), d.getStepZ()));
    }

    /** Seaward length: until the water has been at least three deep for three blocks, plus room for the head. */
    private static int pierLength(ServerLevel level, Frame f, boolean camp) {
        int deep = 0;
        int min = camp ? 7 : 12, max = camp ? 18 : 30;
        for (int a = 1; a <= max; a++) {
            BlockPos p = f.at(a, 0, 0);
            if (!water(level, p.getX(), p.getZ())) return Math.max(min / 2, a - 2); // an inlet: stop short of the far bank
            if (DECK_Y - 1 - ground(level, p.getX(), p.getZ()) >= 3) deep++;
            else deep = 0;
            if (deep >= 3 && a >= min - 4) return Math.min(max, a + 4);
        }
        return max;
    }

    private static boolean berthClear(ServerLevel level, double cx, double cz, Direction sea, ShipType type) {
        for (double t = -type.length / 2 + 0.5; t <= type.length / 2 - 0.5; t += 1) {
            for (double s = -type.beam / 2 + 0.3; s <= type.beam / 2 - 0.3; s += type.beam / 2 - 0.3) {
                int x = (int) Math.floor(cx + sea.getStepX() * t + sea.getClockWise().getStepX() * s);
                int z = (int) Math.floor(cz + sea.getStepZ() * t + sea.getClockWise().getStepZ() * s);
                if (!water(level, x, z)) return false;
                if (DECK_Y - 1 - ground(level, x, z) < Math.ceil(type.draft) + 1) return false;
                for (int y = DECK_Y; y <= DECK_Y + 2; y++) {
                    if (!level.getBlockState(new BlockPos(x, y, z)).isAir()) return false;
                }
            }
        }
        return true;
    }

    /**
     * A settlement already built a dock here: wooden planks or slabs over the water near the shoreline. Adopt its
     * farthest seaward plank as the arrival point instead of building a second pier.
     */
    @Nullable
    private static Result adoptExisting(ServerLevel level, Frame f) {
        int count = 0;
        BlockPos far = null;
        int farA = Integer.MIN_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int a = -6; a <= 34; a++) {
            for (int l = -12; l <= 12; l++) {
                BlockPos c = f.at(a, 0, l);
                if (!water(level, c.getX(), c.getZ())) continue;
                for (int y = DECK_Y - 1; y <= DECK_Y + 1; y++) {
                    BlockState s = level.getBlockState(p.set(c.getX(), y, c.getZ()));
                    if ((s.is(BlockTags.PLANKS) || s.is(BlockTags.WOODEN_SLABS)) && level.getFluidState(p.below()).is(FluidTags.WATER)) {
                        count++;
                        if (a > farA) {
                            farA = a;
                            far = p.immutable();
                        }
                        break;
                    }
                }
            }
        }
        if (count < 12 || far == null) return null;
        BlockPos keeper = far.above();
        return new Result(far.above(), keeper, null, yawOf(f.sea()), true);
    }

    private static void buildHut(ServerLevel level, Frame f, Palette p, int inland) {
        // try both sides of the pier's root, a few blocks back from the water
        for (int side : new int[]{1, -1}) {
            for (int back = inland + 2; back <= inland + 8; back += 3) {
                int a0 = -back - 4, l0 = side > 0 ? 4 : -8;
                int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
                boolean ok = true;
                for (int a = a0; a <= a0 + 4 && ok; a++) {
                    for (int l = l0; l <= l0 + 4; l++) {
                        BlockPos c = f.at(a, 0, l);
                        if (!land(level, c.getX(), c.getZ())) {
                            ok = false;
                            break;
                        }
                        int g = ground(level, c.getX(), c.getZ());
                        min = Math.min(min, g);
                        max = Math.max(max, g);
                    }
                }
                if (!ok || max - min > 3) continue;
                int y0 = max + 1;
                for (int a = a0; a <= a0 + 4; a++) {
                    for (int l = l0; l <= l0 + 4; l++) {
                        boolean edgeA = a == a0 || a == a0 + 4, edgeL = l == l0 || l == l0 + 4;
                        BlockPos floor = f.at(a, y0 - 1, l);
                        set(level, floor, p.foundation());
                        fillDown(level, floor.below(), p.foundation(), 6);
                        for (int h = 0; h <= 3; h++) {
                            BlockPos pos = f.at(a, y0 + h, l);
                            if (edgeA && edgeL) set(level, pos, p.corner());
                            else if (edgeA || edgeL) set(level, pos, h == 1 && (a == a0 + 2 || l == l0 + 2) ? Blocks.GLASS_PANE.defaultBlockState() : p.wall());
                            else set(level, pos, Blocks.AIR.defaultBlockState());
                        }
                        for (int h = 4; h <= 7; h++) clearIfSolid(level, f.at(a, y0 + h, l));
                    }
                }
                // gable roof along the pier axis
                for (int a = a0 - 1; a <= a0 + 5; a++) {
                    for (int k = 0; k <= 2; k++) {
                        BlockState lo = p.roof().setValue(StairBlock.FACING, f.across());
                        BlockState hi = p.roof().setValue(StairBlock.FACING, f.across().getOpposite());
                        set(level, f.at(a, y0 + 4 + k, l0 - 1 + k), lo);
                        set(level, f.at(a, y0 + 4 + k, l0 + 5 - k), hi);
                    }
                    set(level, f.at(a, y0 + 6, l0 + 2), p.roofSlab());
                    if (a == a0 - 1 || a == a0 + 5) continue;
                    if (a == a0 || a == a0 + 4) {
                        for (int k = 1; k <= 2; k++) {
                            for (int l = l0 + k; l <= l0 + 4 - k; l++) set(level, f.at(a, y0 + 3 + k, l), p.wall());
                        }
                    }
                }
                // door facing the pier, furniture
                int doorL = side > 0 ? l0 : l0 + 4;
                Direction out = side > 0 ? f.across().getOpposite() : f.across();
                BlockPos door = f.at(a0 + 2, y0, doorL);
                set(level, door, p.door().setValue(DoorBlock.FACING, out).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
                set(level, door.above(), p.door().setValue(DoorBlock.FACING, out).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
                BlockPos step = door.relative(out);
                if (level.getBlockState(step.below()).isAir()) set(level, step.below(), p.foundation());
                clearIfSolid(level, step);
                clearIfSolid(level, step.above());
                int innerL = side > 0 ? l0 + 3 : l0 + 1;
                set(level, f.at(a0 + 1, y0, innerL), Blocks.CARTOGRAPHY_TABLE.defaultBlockState());
                BlockPos chest = f.at(a0 + 3, y0, innerL);
                set(level, chest, Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.FACING, out));
                loot(level, chest, HARBOR_LOOT);
                set(level, f.at(a0 + 1, y0 + 2, l0 + 2), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
                set(level, f.at(a0 + 3, y0, l0 + 2), Blocks.BARREL.defaultBlockState());
                return;
            }
        }
    }

    private static void buildCamp(ServerLevel level, Frame f, Palette p, RandomSource random) {
        List<int[]> spots = new ArrayList<>();
        for (int back = 5; back <= 16; back += 3) {
            for (int l : new int[]{-6, 4, -2}) spots.add(new int[]{-back, l});
        }
        int tents = 0;
        boolean fire = false, chest = false, flag = false;
        for (int[] s : spots) {
            BlockPos c = f.at(s[0], 0, s[1]);
            if (!land(level, c.getX(), c.getZ())) continue;
            int y = ground(level, c.getX(), c.getZ()) + 1;
            if (tents < 2 && flatEnough(level, f, s[0], s[1], 3, 4)) {
                tent(level, f, s[0], s[1], y, p.canvas());
                tents++;
                continue;
            }
            if (!fire) {
                set(level, f.at(s[0], y, s[1]), Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
                set(level, f.at(s[0] + 1, y, s[1]), Blocks.SPRUCE_SLAB.defaultBlockState());
                fire = true;
            } else if (!chest) {
                BlockPos pos = f.at(s[0], y, s[1]);
                set(level, pos, Blocks.CHEST.defaultBlockState());
                loot(level, pos, CAMP_LOOT);
                set(level, f.at(s[0], y, s[1] + 1), Blocks.BARREL.defaultBlockState());
                chest = true;
            } else if (!flag) {
                for (int h = 0; h < 4; h++) set(level, f.at(s[0], y + h, s[1]), p.fence());
                set(level, f.at(s[0], y + 3, s[1] + 1), p.canvas());
                set(level, f.at(s[0], y + 2, s[1] + 1), p.canvas());
                flag = true;
            }
        }
    }

    private static boolean flatEnough(ServerLevel level, Frame f, int a0, int l0, int w, int d) {
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (int a = a0 - d + 1; a <= a0; a++) {
            for (int l = l0; l < l0 + w; l++) {
                BlockPos c = f.at(a, 0, l);
                if (!land(level, c.getX(), c.getZ())) return false;
                int g = ground(level, c.getX(), c.getZ());
                min = Math.min(min, g);
                max = Math.max(max, g);
            }
        }
        return max - min <= 1;
    }

    /** An A-frame canvas tent, 3 wide and 4 deep, with a bedroll inside. */
    private static void tent(ServerLevel level, Frame f, int a0, int l0, int y, BlockState canvas) {
        for (int a = a0 - 3; a <= a0; a++) {
            set(level, f.at(a, y, l0), canvas);
            set(level, f.at(a, y, l0 + 2), canvas);
            set(level, f.at(a, y + 1, l0 + 1), canvas);
            clearIfSolid(level, f.at(a, y, l0 + 1));
            fillDown(level, f.at(a, y - 1, l0 + 1), Blocks.DIRT.defaultBlockState(), 3);
        }
        set(level, f.at(a0 - 2, y, l0 + 1), com.skycraft.worldgen.VanillaBlocks.BROWN_CARPET.defaultBlockState());
        set(level, f.at(a0 - 3, y, l0 + 1), com.skycraft.worldgen.VanillaBlocks.BROWN_CARPET.defaultBlockState());
    }

    // ------------------------------------------------------------------ block helpers

    private static void set(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.hasProperty(SlabBlock.TYPE) && state.getValue(SlabBlock.TYPE) == SlabType.BOTTOM && state.hasProperty(SlabBlock.WATERLOGGED)) {
            state = state.setValue(SlabBlock.WATERLOGGED, level.getFluidState(pos).is(FluidTags.WATER));
        }
        if (state.hasProperty(StairBlock.HALF)) state = state.setValue(StairBlock.HALF, Half.BOTTOM);
        level.setBlock(pos, state, Block.UPDATE_CLIENTS);
    }

    private static void clearIfSolid(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (!s.isAir() && !s.is(Blocks.LANTERN)) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    /** Fills air and water below a block with {@code state} down to the ground. */
    private static void fillDown(ServerLevel level, BlockPos start, BlockState state, int max) {
        BlockPos.MutableBlockPos p = start.mutable();
        for (int i = 0; i < max; i++) {
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && !s.liquid() && !s.canBeReplaced()) return;
            level.setBlock(p, state, Block.UPDATE_CLIENTS);
            p.move(0, -1, 0);
        }
    }

    /** A post driven down through the water to the bed. */
    private static void sinkPost(ServerLevel level, BlockPos start, BlockState post) {
        BlockPos.MutableBlockPos p = start.mutable();
        for (int i = 0; i < 40 && p.getY() > level.getMinY(); i++) {
            BlockState s = level.getBlockState(p);
            if (!s.isAir() && !s.liquid() && !s.canBeReplaced()) return;
            level.setBlock(p, post, Block.UPDATE_CLIENTS);
            p.move(0, -1, 0);
        }
    }

    private static void sign(ServerLevel level, BlockPos pos, Direction facing, Component name) {
        int rot = switch (facing) {
            case SOUTH -> 0;
            case WEST -> 4;
            case NORTH -> 8;
            default -> 12;
        };
        set(level, pos, Blocks.SPRUCE_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, rot));
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof SignBlockEntity sign) {
            SignText text = SignText.EMPTY.asMutable().setLine(1, name).setLine(2, Component.translatable("sailing.skycraft.sign.docks")).asImmutable();
            sign.setText(text, net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
            sign.setText(text, net.minecraft.world.level.block.entity.SignTextSlot.BACK);
            sign.setWaxed(true);
            sign.setChanged();
        }
    }

    private static void loot(ServerLevel level, BlockPos pos, Identifier table) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof RandomizableContainerBlockEntity c) c.setLootTable(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, table), level.getRandom().nextLong());
    }
}
