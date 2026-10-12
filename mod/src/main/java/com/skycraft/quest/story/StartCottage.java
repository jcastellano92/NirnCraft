package com.skycraft.quest.story;

import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.terrain.TamrielSpawn;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * The opening's stage: a small spruce cottage on a hill beside the road, 130 to 200 blocks from Helgen with a clear
 * line of sight to the town. The site is searched from the terrain noise (deterministic per world, nothing stored), the
 * cottage is built once, when the first player needs it, and every new player wakes in its bed. Also home of the
 * facts about Helgen the intro uses (its centre, the "ruined town" test).
 */
public final class StartCottage {
    /** Where the cottage is: floor level {@code floorY}, the bed's foot, where a player wakes, and the town it looks at. */
    public record Site(BlockPos origin, int floorY, int fx, int fz, BlockPos bed, Vec3 wake, float yaw, int helgenX, int helgenZ,
                       int helgenY) {
        /** The centre of the cottage floor, for distance checks. */
        public Vec3 centre() {
            return Vec3.atCenterOf(origin).add(0, 1, 0);
        }
    }

    private static final Map<Long, Optional2> CACHE = new HashMap<>();

    /** Nullable-site holder so "searched, nothing found" is cached too. */
    private record Optional2(@Nullable Site site) {
    }

    private static final int HELGEN_X = -24, HELGEN_Z = 1143;

    private StartCottage() {}

    // ------------------------------------------------------------------ Helgen facts

    /** Helgen's centre (x, z), from the places data when present. */
    public static int[] helgen() {
        Place p = Places.byId("helgen").orElse(null);
        return p != null ? new int[]{p.x(), p.z()} : new int[]{HELGEN_X, HELGEN_Z};
    }

    /** True within {@code margin} blocks of Helgen's walls (used to keep it the ruined, shop-less town). */
    public static boolean nearHelgen(int x, int z, int margin) {
        Place p = Places.byId("helgen").orElse(null);
        int hx = p != null ? p.x() : HELGEN_X, hz = p != null ? p.z() : HELGEN_Z, r = p != null ? p.radius() : 60;
        long dx = x - hx, dz = z - hz;
        long lim = r + margin;
        return dx * dx + dz * dz <= lim * lim;
    }

    // ------------------------------------------------------------------ site

    /** The cottage site for this world, or null when it is not a Tamriel world or no good hill was found. */
    @Nullable
    public static synchronized Site site(ServerLevel level) {
        if (!TamrielSpawn.isTamriel(level)) return null;
        long seed = level.getSeed();
        Optional2 cached = CACHE.get(seed);
        if (cached == null) {
            Site s;
            try {
                s = search(level);
            } catch (RuntimeException e) {
                com.skycraft.Skycraft.LOGGER.warn("Start cottage site search failed", e);
                s = null;
            }
            cached = new Optional2(s);
            CACHE.put(seed, cached);
        }
        return cached.site();
    }

    public static synchronized void reset() {
        CACHE.clear();
    }

    private static int height(ServerLevel level, int x, int z) {
        ChunkGenerator gen = level.getChunkSource().getGenerator();
        return gen.getBaseHeight(x, z, Heightmap.Types.WORLD_SURFACE_WG, level, level.getChunkSource().randomState());
    }

    /** The ground height at x/z: the real surface where the chunk is loaded, else the terrain noise. */
    static int groundY(ServerLevel level, int x, int z) {
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) != null) {
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        }
        return height(level, x, z);
    }

    private static Site search(ServerLevel level) {
        int[] h = helgen();
        int hx = h[0], hz = h[1];
        // the road to Riverwood leaves Helgen towards it: look in that direction first
        double base = -Math.PI / 2; // north (-z) by default
        Place riverwood = Places.byId("riverwood").orElse(null);
        if (riverwood != null) base = Math.atan2(riverwood.z() - hz, riverwood.x() - hx);
        int hy = height(level, hx, hz);
        MapSample s = new MapSample();
        double bestScore = -1e9;
        int bx = 0, bz = 0, by = 0;
        boolean bestLos = false;
        int[] dists = {140, 150, 130, 160, 170, 180, 200};
        for (int dist : dists) {
            for (int step = 0; step <= 8; step++) {
                for (int sign = -1; sign <= 1; sign += 2) {
                    if (step == 0 && sign == 1) continue;
                    double da = Math.toRadians(step * 9 * sign);
                    int x = hx + (int) Math.round(Math.cos(base + da) * dist);
                    int z = hz + (int) Math.round(Math.sin(base + da) * dist);
                    TamrielMap.sample(x, z, s);
                    if (!s.inTamriel || s.coastDistance < 24 || s.river > 0.15f || s.lake > 0.15f || s.riverDistance < 14) continue;
                    int y = height(level, x, z);
                    if (y <= TamrielMap.SEA_LEVEL + 2 || !flat(level, x, z, y)) continue;
                    boolean los = lineOfSight(level, x, y + 4, z, hx, hy + 14, hz);
                    double score = Math.min(30, y - hy) - Math.abs(dist - 145) * 0.15 - step * 0.7 + (los ? 100 : 0);
                    if (score > bestScore) {
                        bestScore = score;
                        bx = x;
                        bz = z;
                        by = y;
                        bestLos = los;
                    }
                }
            }
        }
        if (bestScore <= -1e8) return null;
        com.skycraft.Skycraft.LOGGER.info("Start cottage site near ({}, {}, {}), {} blocks from Helgen, line of sight: {}", bx, by, bz,
                (int) Math.sqrt((double) (bx - hx) * (bx - hx) + (double) (bz - hz) * (bz - hz)), bestLos);
        // the cottage looks at Helgen along the dominant axis
        double dx = hx - bx, dz = hz - bz;
        int fx = Math.abs(dx) >= Math.abs(dz) ? (int) Math.signum(dx) : 0;
        int fz = fx == 0 ? (int) Math.signum(dz) : 0;
        if (fx == 0 && fz == 0) fz = 1;
        return layout(new BlockPos(bx, by, bz), by, fx, fz, hx, hz, hy);
    }

    private static boolean flat(ServerLevel level, int x, int z, int y) {
        int[][] around = {{5, 0}, {-5, 0}, {0, 5}, {0, -5}, {4, 4}, {-4, -4}};
        for (int[] d : around) {
            if (Math.abs(height(level, x + d[0], z + d[1]) - y) > 3) return false;
        }
        return true;
    }

    /** True when no terrain along the sight line from (x0,y0,z0) to (x1,y1,z1) rises above it. */
    private static boolean lineOfSight(ServerLevel level, int x0, int y0, int z0, int x1, int y1, int z1) {
        for (int i = 1; i < 24; i++) {
            double t = i / 24.0;
            int x = (int) Math.round(Mth.lerp(t, x0, x1));
            int z = (int) Math.round(Mth.lerp(t, z0, z1));
            double sight = Mth.lerp(t, y0, y1);
            if (height(level, x, z) > sight) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ layout (local frame)

    /** World position of local (l = across, d = depth from the Helgen-facing front wall) at height y. */
    private static BlockPos at(Site s, int l, int d, int y) {
        int rx = -s.fz(), rz = s.fx();
        return new BlockPos(s.origin().getX() + rx * (l - 3) + s.fx() * (2 - d), y, s.origin().getZ() + rz * (l - 3) + s.fz() * (2 - d));
    }

    private static Site layout(BlockPos origin, int floorY, int fx, int fz, int hx, int hz, int hy) {
        Site tmp = new Site(origin, floorY, fx, fz, origin, Vec3.ZERO, 0f, hx, hz, hy);
        Direction back = Direction.fromDelta(-fx, 0, -fz);
        BlockPos bedFoot = at(tmp, 5, 3, floorY + 1);
        BlockPos stand = at(tmp, 4, 3, floorY + 1);
        // yaw so the sleeper looks out of the big window: 0 = +z, 90 = -x
        float yaw = (float) (Mth.atan2(-fx, fz) * 180.0 / Math.PI);
        Vec3 wake = new Vec3(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
        return new Site(origin, floorY, fx, fz, bedFoot, wake, yaw, hx, hz, hy);
    }

    // ------------------------------------------------------------------ building

    /**
     * Makes sure the cottage stands (builds it the first time) and returns the site; null when this world has none.
     * Loads the few chunks it covers.
     */
    @Nullable
    public static Site ensure(ServerLevel level) {
        Site tmp = site(level);
        if (tmp == null) return null;
        // load the chunks under the footprint (with margin) so the surface is real
        for (int l = -2; l <= 8; l += 5) {
            for (int d = -3; d <= 7; d += 5) {
                BlockPos p = at(tmp, l, d, 0);
                level.getChunk(p.getX() >> 4, p.getZ() >> 4);
            }
        }
        // already built (this session or an earlier one)? find the bed in its column and use that floor
        BlockPos col = at(tmp, 5, 3, 0);
        for (int y = tmp.floorY() + 24; y >= tmp.floorY() - 24; y--) {
            if (level.getBlockState(new BlockPos(col.getX(), y, col.getZ())).getBlock() instanceof BedBlock) {
                return layout(tmp.origin(), y - 1, tmp.fx(), tmp.fz(), tmp.helgenX(), tmp.helgenZ(), tmp.helgenY());
            }
        }
        // the real floor: above the highest natural ground under the footprint (leaves ignored)
        int ground = Integer.MIN_VALUE;
        for (int l = 0; l <= 6; l++) {
            for (int d = 0; d <= 5; d++) {
                BlockPos p = at(tmp, l, d, 0);
                ground = Math.max(ground, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ()));
            }
        }
        int floor = Math.max(ground, TamrielMap.SEA_LEVEL + 2);
        Site site = layout(tmp.origin(), floor, tmp.fx(), tmp.fz(), tmp.helgenX(), tmp.helgenZ(), tmp.helgenY());
        build(level, site);
        return site;
    }

    private static void put(ServerLevel level, BlockPos p, BlockState state) {
        level.setBlock(p, state, 3);
    }

    private static void build(ServerLevel level, Site s) {
        int y0 = s.floorY();
        BlockState planks = Blocks.SPRUCE_PLANKS.defaultBlockState();
        BlockState log = Blocks.SPRUCE_LOG.defaultBlockState();
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();
        Direction dirF = Direction.fromDelta(s.fx(), 0, s.fz());
        Direction dirR = Direction.fromDelta(-s.fz(), 0, s.fx());
        Direction back = dirF.getOpposite();

        // clear the volume (trees, bushes, snow), lay a foundation down to solid ground
        for (int l = -2; l <= 8; l++) {
            for (int d = -2; d <= 7; d++) {
                for (int y = y0; y <= y0 + 9; y++) put(level, at(s, l, d, y), air);
            }
        }
        for (int l = 0; l <= 6; l++) {
            for (int d = 0; d <= 5; d++) {
                BlockPos col = at(s, l, d, y0 - 1);
                boolean edge = l == 0 || l == 6 || d == 0 || d == 5;
                for (int k = 0; k < 10; k++) {
                    BlockPos p = col.below(k);
                    BlockState cur = level.getBlockState(p);
                    if (k > 0 && cur.isSolidRender(level, p) && cur.getFluidState().isEmpty() && !cur.is(net.minecraft.tags.BlockTags.LEAVES)) break;
                    put(level, p, edge ? cobble : Blocks.DIRT.defaultBlockState());
                }
                put(level, at(s, l, d, y0), planks);
            }
        }
        // walls: spruce logs at the corners, planks between, 3 high
        for (int l = 0; l <= 6; l++) {
            for (int d = 0; d <= 5; d++) {
                boolean wall = l == 0 || l == 6 || d == 0 || d == 5;
                if (!wall) continue;
                boolean corner = (l == 0 || l == 6) && (d == 0 || d == 5);
                for (int y = y0 + 1; y <= y0 + 3; y++) put(level, at(s, l, d, y), corner ? log : planks);
            }
        }
        // the big window towards Helgen, side windows, the door
        for (int l = 4; l <= 5; l++) {
            for (int y = y0 + 2; y <= y0 + 3; y++) put(level, at(s, l, 0, y), Blocks.GLASS_PANE.defaultBlockState());
        }
        put(level, at(s, 0, 3, y0 + 2), Blocks.GLASS_PANE.defaultBlockState());
        put(level, at(s, 6, 2, y0 + 2), Blocks.GLASS_PANE.defaultBlockState());
        put(level, at(s, 3, 5, y0 + 2), Blocks.GLASS_PANE.defaultBlockState());
        BlockState door = Blocks.SPRUCE_DOOR.defaultBlockState().setValue(DoorBlock.FACING, back).setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
        put(level, at(s, 2, 0, y0 + 1), door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        put(level, at(s, 2, 0, y0 + 2), door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        // a step up to the door
        put(level, at(s, 2, -1, y0), Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, back));
        put(level, at(s, 2, -2, y0 - 1), Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, back));
        // roof: a stepped gable across the cottage, solid so no sky shows through
        BlockState stairL = Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, dirR);
        BlockState stairR = Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, dirR.getOpposite());
        for (int d = -1; d <= 6; d++) {
            put(level, at(s, -1, d, y0 + 4), stairL);
            put(level, at(s, 7, d, y0 + 4), stairR);
            for (int l = 0; l <= 6; l++) put(level, at(s, l, d, y0 + 4), planks);
            put(level, at(s, 0, d, y0 + 5), stairL);
            put(level, at(s, 6, d, y0 + 5), stairR);
            for (int l = 1; l <= 5; l++) put(level, at(s, l, d, y0 + 5), planks);
            put(level, at(s, 1, d, y0 + 6), stairL);
            put(level, at(s, 5, d, y0 + 6), stairR);
            for (int l = 2; l <= 4; l++) put(level, at(s, l, d, y0 + 6), planks);
            put(level, at(s, 2, d, y0 + 7), stairL);
            put(level, at(s, 4, d, y0 + 7), stairR);
            put(level, at(s, 3, d, y0 + 7), Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, net.minecraft.world.level.block.state.properties.SlabType.TOP));
        }
        // furniture: the bed in the corner under the side window, a table with a lantern, books, a rug
        BlockState bedFoot = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, back).setValue(BedBlock.PART, BedPart.FOOT);
        BlockPos foot = s.bed();
        put(level, foot, bedFoot);
        put(level, foot.relative(back), bedFoot.setValue(BedBlock.PART, BedPart.HEAD));
        put(level, at(s, 1, 4, y0 + 1), Blocks.CRAFTING_TABLE.defaultBlockState());
        put(level, at(s, 1, 4, y0 + 2), Blocks.LANTERN.defaultBlockState());
        put(level, at(s, 2, 4, y0 + 1), Blocks.BARREL.defaultBlockState());
        put(level, at(s, 1, 1, y0 + 1), Blocks.BOOKSHELF.defaultBlockState());
        put(level, at(s, 1, 2, y0 + 1), Blocks.BOOKSHELF.defaultBlockState());
        for (int l = 3; l <= 4; l++) {
            for (int d = 2; d <= 3; d++) put(level, at(s, l, d, y0 + 1), Blocks.RED_CARPET.defaultBlockState());
        }
        // a torch by the door outside
        put(level, at(s, 3, -1, y0 + 1), Blocks.TORCH.defaultBlockState());
        com.skycraft.Skycraft.LOGGER.info("Built the start cottage at {} (floor y {}), looking at Helgen ({}, {})", s.origin(), y0, s.helgenX(), s.helgenZ());
    }

    // ------------------------------------------------------------------ the waking

    /** Puts the player in the cottage (beside the bed, facing the town) and makes the bed their respawn point. */
    public static boolean wake(ServerPlayer p) {
        Site site = ensure(p.serverLevel());
        if (site == null) return false;
        Vec3 w = site.wake();
        if (p.position().distanceToSqr(w) > 14 * 14) {
            p.teleportTo(p.serverLevel(), w.x, w.y + 0.05, w.z, site.yaw(), 8f);
            p.fallDistance = 0f;
            p.setDeltaMovement(Vec3.ZERO);
        } else {
            p.setYRot(site.yaw());
        }
        // the cottage bed is where a first death returns you, until a real bed is claimed
        com.skycraft.combat.RespawnPoints.set(p, p.serverLevel().dimension(), site.bed());
        return true;
    }
}
