package com.skycraft.tamriel.settlement.build;

import com.skycraft.tamriel.settlement.Anchor;
import com.skycraft.tamriel.settlement.AnchorType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Chunk-clipped block writer in a building's local frame. Local x runs along the front (left to right seen from
 * outside), local z runs from the front wall ({@code z = 0}) to the back, and local y is relative to the floor block
 * ({@code y = 0}). The front faces {@link #facing}; local {@link Direction#NORTH} is "out of the front door", and block
 * states written through the local methods are rotated to match.
 *
 * <p>Every write is clipped to the chunk being generated, so a builder can describe its whole building on every call.
 * Variety comes from position hashes, never from a per-chunk random. A canvas without a level records the
 * {@link Anchor}s the builder declares instead of painting (used on the server thread to find beds, counters and
 * thrones from the structure data alone).</p>
 */
public final class Canvas {
    @Nullable
    public final WorldGenLevel level;
    public final int ox, oy, oz;
    public final Direction facing;
    public final long seed;
    private final Rotation rot;
    private final int x0, y0, z0, x1, y1, z1;
    @Nullable
    private final List<Anchor> anchors;
    /** Owner label of the building being painted (anchor owner). */
    public String owner = "";
    /** Containers declared so far by this building (shared by its frames); capped, see {@link #container}. */
    private int[] containers = new int[1];
    /** Cells (world positions) kept clear of furniture: stair landings and exits. Shared by the building's frames. */
    private java.util.Set<Long> reserved = new java.util.HashSet<>();
    /** Beds skipped because they would block a reserved cell (their anchors are dropped too). */
    private java.util.Set<Long> deadBeds = new java.util.HashSet<>();
    /** Beds placed so far by this building (shared by its frames; counted identically in every chunk's replay). */
    private int[] beds = new int[1];
    /** Recording only: declared entrances as {world x, y, z of the lower half, outward 2D value} (the street plan). */
    @Nullable
    private List<int[]> doorSink;
    private static final int MAX_CONTAINERS = 6, MAX_LOOT = 3;
    private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

    /** Painting canvas, clipped to {@code clip}. */
    public Canvas(WorldGenLevel level, BoundingBox clip, int ox, int oy, int oz, Direction facing, long seed) {
        this.level = level;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.facing = facing;
        this.seed = seed;
        this.rot = rotation(facing);
        this.x0 = clip.minX();
        this.y0 = Math.max(clip.minY(), level.getMinBuildHeight());
        this.z0 = clip.minZ();
        this.x1 = clip.maxX();
        this.y1 = Math.min(clip.maxY(), level.getMaxBuildHeight() - 1);
        this.z1 = clip.maxZ();
        this.anchors = null;
    }

    /** Recording canvas: nothing is painted, anchors are collected. */
    public Canvas(List<Anchor> out, int ox, int oy, int oz, Direction facing, long seed) {
        this.level = null;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.facing = facing;
        this.seed = seed;
        this.rot = rotation(facing);
        this.x0 = 1;
        this.x1 = 0;
        this.y0 = 1;
        this.y1 = 0;
        this.z0 = 1;
        this.z1 = 0;
        this.anchors = out;
    }

    /** The same clip, level and recording target with another local frame. */
    public Canvas frame(int nox, int noy, int noz, Direction nf) {
        Canvas c = level == null
                ? new Canvas(anchors, nox, noy, noz, nf, seed)
                : new Canvas(level, new BoundingBox(x0, y0, z0, x1, y1, z1), nox, noy, noz, nf, seed);
        c.owner = owner;
        c.containers = containers;
        c.reserved = reserved;
        c.deadBeds = deadBeds;
        c.beds = beds;
        c.doorSink = doorSink;
        return c;
    }

    /** Recording canvas: also collect the declared entrances into {@code sink} (see {@link #entrance}). */
    public Canvas doors(List<int[]> sink) {
        this.doorSink = sink;
        return this;
    }

    /** A sub-frame whose local origin is this frame's (lx, ly, lz), facing local {@code localFacing}. */
    public Canvas sub(int lx, int ly, int lz, Direction localFacing) {
        return frame(wx(lx, lz), oy + ly, wz(lx, lz), dir(localFacing));
    }

    public static Rotation rotation(Direction facing) {
        return switch (facing) {
            case EAST -> Rotation.CLOCKWISE_90;
            case SOUTH -> Rotation.CLOCKWISE_180;
            case WEST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    public boolean recording() {
        return level == null;
    }

    public boolean empty() {
        return x0 > x1 || y0 > y1 || z0 > z1;
    }

    /** World clip rectangle (empty when recording). */
    public int clipMinX() {
        return x0;
    }

    public int clipMaxX() {
        return x1;
    }

    public int clipMinZ() {
        return z0;
    }

    public int clipMaxZ() {
        return z1;
    }

    // ------------------------------------------------------------------ frame

    public int wx(int lx, int lz) {
        return ox + switch (facing) {
            case EAST -> -lz;
            case SOUTH -> -lx;
            case WEST -> lz;
            default -> lx;
        };
    }

    public int wz(int lx, int lz) {
        return oz + switch (facing) {
            case EAST -> lx;
            case SOUTH -> -lz;
            case WEST -> -lx;
            default -> lz;
        };
    }

    public int wy(int ly) {
        return oy + ly;
    }

    /** World direction of a local horizontal direction. */
    public Direction dir(Direction local) {
        return local.getAxis().isVertical() ? local : rot.rotate(local);
    }

    public BlockState rot(BlockState s) {
        return rot == Rotation.NONE ? s : s.rotate(rot);
    }

    public boolean okWorld(int x, int y, int z) {
        return x >= x0 && x <= x1 && y >= y0 && y <= y1 && z >= z0 && z <= z1;
    }

    /** True if any part of the local column range could be painted in this chunk. */
    public boolean touches(int lxa, int lza, int lxb, int lzb) {
        int ax = wx(lxa, lza), az = wz(lxa, lza), bx = wx(lxb, lzb), bz = wz(lxb, lzb);
        return Math.max(ax, bx) >= x0 && Math.min(ax, bx) <= x1 && Math.max(az, bz) >= z0 && Math.min(az, bz) <= z1;
    }

    // ------------------------------------------------------------------ world-space access

    /** The block at a world position; air outside this chunk's clip (never reads outside the generating region). */
    public BlockState wget(int x, int y, int z) {
        if (level == null || x < x0 || x > x1 || z < z0 || z > z1) return Blocks.AIR.defaultBlockState();
        return level.getBlockState(m.set(x, y, z));
    }

    public void wset(int x, int y, int z, BlockState s) {
        if (!okWorld(x, y, z)) return;
        m.set(x, y, z);
        BlockState old = level.getBlockState(m);
        if (old.is(BlockTags.FEATURES_CANNOT_REPLACE)) return;
        level.setBlock(m, s, 2);
    }

    /** Topmost solid ground at (x, z): skips leaves, logs, plants, snow and fluids. Clamped to the chunk's column. */
    public int wground(int x, int z) {
        if (level == null || x < x0 || x > x1 || z < z0 || z > z1) return oy;
        int y = level.getHeight(worldgen() ? Heightmap.Types.OCEAN_FLOOR_WG : Heightmap.Types.OCEAN_FLOOR, x, z) - 1;
        int min = level.getMinBuildHeight();
        while (y > min) {
            BlockState s = level.getBlockState(m.set(x, y, z));
            if (s.isAir() || s.is(BlockTags.LEAVES) || s.is(BlockTags.LOGS) || s.canBeReplaced() || !s.getFluidState().isEmpty()
                    || s.is(Blocks.SNOW) || s.is(BlockTags.FLOWERS)) {
                y--;
                continue;
            }
            break;
        }
        return y;
    }

    /** Painting during world generation (worldgen heightmaps), rather than into a live level (dev previews). */
    private boolean worldgen() {
        return !(level instanceof net.minecraft.server.level.ServerLevel);
    }

    /** Water surface at (x, z) if the column is covered by water, else {@link Integer#MIN_VALUE}. */
    public int wwater(int x, int z) {
        if (level == null || x < x0 || x > x1 || z < z0 || z > z1) return Integer.MIN_VALUE;
        int top = level.getHeight(worldgen() ? Heightmap.Types.WORLD_SURFACE_WG : Heightmap.Types.WORLD_SURFACE, x, z) - 1;
        return level.getBlockState(m.set(x, top, z)).getFluidState().isEmpty() ? Integer.MIN_VALUE : top;
    }

    // ------------------------------------------------------------------ local access

    public BlockState get(int lx, int ly, int lz) {
        return wget(wx(lx, lz), oy + ly, wz(lx, lz));
    }

    public void set(int lx, int ly, int lz, BlockState s) {
        int x = wx(lx, lz), y = oy + ly, z = wz(lx, lz);
        if (!okWorld(x, y, z)) return;
        if (!reserved.isEmpty() && !s.isAir() && reserved.contains(BlockPos.asLong(x, y, z))) return;
        wset(x, y, z, rot(s));
    }

    /** Keeps the local cells (lx, ly0..ly1, lz) free of furniture: nothing but air is set there from now on. */
    public void keepClear(int lx, int ly0, int ly1, int lz) {
        for (int y = ly0; y <= ly1; y++) reserved.add(BlockPos.asLong(wx(lx, lz), oy + y, wz(lx, lz)));
    }

    public boolean isReserved(int lx, int ly, int lz) {
        return !reserved.isEmpty() && reserved.contains(BlockPos.asLong(wx(lx, lz), oy + ly, wz(lx, lz)));
    }

    /** Whether any cell of the local rectangle (feet and head height at {@code ly}) is kept clear. */
    public boolean anyReserved(int lx0, int lz0, int lx1, int lz1, int ly) {
        if (reserved.isEmpty()) return false;
        for (int x = lx0; x <= lx1; x++) for (int z = lz0; z <= lz1; z++) if (isReserved(x, ly, z) || isReserved(x, ly + 1, z)) return true;
        return false;
    }

    /** Number of beds this building has so far. */
    public int bedCount() {
        return beds[0];
    }

    /** Sets only if the block there is open (air, fluid, plant, snow, leaves). */
    public void setIfOpen(int lx, int ly, int lz, BlockState s) {
        int x = wx(lx, lz), y = oy + ly, z = wz(lx, lz);
        if (!okWorld(x, y, z)) return;
        if (!reserved.isEmpty() && !s.isAir() && !(s.getBlock() instanceof net.minecraft.world.level.block.CarpetBlock)
                && reserved.contains(BlockPos.asLong(x, y, z))) return;
        if (open(wget(x, y, z))) wset(x, y, z, rot(s));
    }

    public void air(int lx, int ly, int lz) {
        int x = wx(lx, lz), y = oy + ly, z = wz(lx, lz);
        if (!okWorld(x, y, z)) return;
        if (!wget(x, y, z).isAir()) wset(x, y, z, Blocks.AIR.defaultBlockState());
    }

    public void fill(int ax, int ay, int az, int bx, int by, int bz, BlockState s) {
        BlockState r = rot(s);
        int wxa = wx(ax, az), wza = wz(ax, az), wxb = wx(bx, bz), wzb = wz(bx, bz);
        int minX = Math.max(Math.min(wxa, wxb), x0), maxX = Math.min(Math.max(wxa, wxb), x1);
        int minZ = Math.max(Math.min(wza, wzb), z0), maxZ = Math.min(Math.max(wza, wzb), z1);
        int minY = Math.max(oy + Math.min(ay, by), y0), maxY = Math.min(oy + Math.max(ay, by), y1);
        for (int x = minX; x <= maxX; x++)
            for (int z = minZ; z <= maxZ; z++)
                for (int y = minY; y <= maxY; y++) wset(x, y, z, r);
    }

    /** Clears a local box to air. */
    public void clear(int ax, int ay, int az, int bx, int by, int bz) {
        int wxa = wx(ax, az), wza = wz(ax, az), wxb = wx(bx, bz), wzb = wz(bx, bz);
        int minX = Math.max(Math.min(wxa, wxb), x0), maxX = Math.min(Math.max(wxa, wxb), x1);
        int minZ = Math.max(Math.min(wza, wzb), z0), maxZ = Math.min(Math.max(wza, wzb), z1);
        int minY = Math.max(oy + Math.min(ay, by), y0), maxY = Math.min(oy + Math.max(ay, by), y1);
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = minX; x <= maxX; x++)
            for (int z = minZ; z <= maxZ; z++)
                for (int y = minY; y <= maxY; y++) {
                    if (!level.getBlockState(m.set(x, y, z)).isAir()) wset(x, y, z, air);
                }
    }

    /** Clears trees, plants and snow (not built blocks or terrain) in a local box. */
    public void clearPlants(int ax, int ay, int az, int bx, int by, int bz) {
        int wxa = wx(ax, az), wza = wz(ax, az), wxb = wx(bx, bz), wzb = wz(bx, bz);
        int minX = Math.max(Math.min(wxa, wxb), x0), maxX = Math.min(Math.max(wxa, wxb), x1);
        int minZ = Math.max(Math.min(wza, wzb), z0), maxZ = Math.min(Math.max(wza, wzb), z1);
        int minY = Math.max(oy + Math.min(ay, by), y0), maxY = Math.min(oy + Math.max(ay, by), y1);
        BlockState air = Blocks.AIR.defaultBlockState();
        java.util.ArrayList<int[]> cut = new java.util.ArrayList<>();
        for (int x = minX; x <= maxX; x++)
            for (int z = minZ; z <= maxZ; z++)
                for (int y = minY; y <= maxY; y++) {
                    BlockState s = level.getBlockState(m.set(x, y, z));
                    if (s.isAir()) continue;
                    if (s.is(BlockTags.LEAVES) || (s.is(BlockTags.LOGS) && natural(s))
                            || (s.canBeReplaced() && s.getFluidState().isEmpty()) || s.is(Blocks.SNOW) || s.is(BlockTags.FLOWERS)
                            || s.is(Blocks.BAMBOO) || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.CACTUS)) {
                        if (s.is(BlockTags.LOGS) && cut.size() < 48) cut.add(new int[]{x, y, z});
                        wset(x, y, z, air);
                    }
                }
        sweepCrowns(cut);
    }

    /**
     * Leaves of trees whose trunk was just cleared hang in the air outside the cleared box (and persistent crown
     * leaves never decay): removes the leaves within 5 blocks of a cut log that no remaining log within 3 holds up.
     * Only inside this canvas's chunk clip.
     */
    private void sweepCrowns(java.util.List<int[]> cut) {
        if (cut.isEmpty()) return;
        BlockState air = Blocks.AIR.defaultBlockState();
        java.util.HashSet<Long> done = new java.util.HashSet<>();
        for (int[] l : cut) {
            for (int x = Math.max(x0, l[0] - 5); x <= Math.min(x1, l[0] + 5); x++)
                for (int z = Math.max(z0, l[2] - 5); z <= Math.min(z1, l[2] + 5); z++)
                    for (int y = Math.max(y0, l[1] - 2); y <= Math.min(y1, l[1] + 9); y++) {
                        if (!level.getBlockState(m.set(x, y, z)).is(BlockTags.LEAVES)) continue;
                        if (!done.add(net.minecraft.core.BlockPos.asLong(x, y, z))) continue;
                        boolean held = false;
                        for (int dx = -3; dx <= 3 && !held; dx++)
                            for (int dz = -3; dz <= 3 && !held; dz++) {
                                int px = x + dx, pz = z + dz;
                                if (px < x0 || px > x1 || pz < z0 || pz > z1) continue;
                                for (int dy = -3; dy <= 3; dy++) {
                                    int py = y + dy;
                                    if (py < y0 || py > y1) continue;
                                    if (level.getBlockState(m.set(px, py, pz)).is(BlockTags.LOGS)) {
                                        held = true;
                                        break;
                                    }
                                }
                            }
                        if (!held) wset(x, y, z, air);
                    }
        }
    }

    private static boolean natural(BlockState s) {
        // logs of grown trees keep their bark on every side; stripped logs are builders' beams
        return !net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().startsWith("stripped");
    }

    /**
     * Fills the column under local (lx, lz) with {@code s} from {@code ly} downwards until solid natural ground,
     * at most {@code maxDepth} blocks.
     */
    public void foundation(int lx, int ly, int lz, BlockState s, int maxDepth) {
        int x = wx(lx, lz), z = wz(lx, lz);
        if (x < x0 || x > x1 || z < z0 || z > z1) return;
        BlockState r = rot(s);
        for (int y = oy + ly; y > oy + ly - maxDepth; y--) {
            if (y < y0) return;
            BlockState here = wget(x, y, z);
            if (y < oy + ly && solidGround(here)) return;
            if (y <= y1) wset(x, y, z, r);
        }
    }

    public static boolean open(BlockState s) {
        return s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced() || s.is(BlockTags.LEAVES)
                || s.is(BlockTags.FLOWERS) || s.is(Blocks.SNOW);
    }

    public static boolean solidGround(BlockState s) {
        return !open(s) && s.isSolid() && !s.is(BlockTags.LEAVES) && !s.is(BlockTags.LOGS);
    }

    // ------------------------------------------------------------------ furniture & entities

    public void anchor(AnchorType type, int lx, int ly, int lz) {
        anchor(type, lx, ly, lz, owner);
    }

    public void anchor(AnchorType type, int lx, int ly, int lz, String who) {
        if ((type == AnchorType.BED || type == AnchorType.INN_BED || type == AnchorType.HOUSE_BED || type == AnchorType.GUEST_BED
                || type == AnchorType.GUILD_BED) && deadBeds.contains(BlockPos.asLong(wx(lx, lz), oy + ly, wz(lx, lz)))) return;
        if (anchors != null) anchors.add(new Anchor(type, new BlockPos(wx(lx, lz), oy + ly, wz(lx, lz)), who));
    }

    /** A door (both halves) in the local wall, opening towards local {@code face}. */
    public void door(int lx, int ly, int lz, BlockState door, Direction face) {
        BlockState d = B.face(door, face).setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
        set(lx, ly, lz, d.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        set(lx, ly + 1, lz, d.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    /** World positions (lower door halves) of declared entrances -> the direction leading away from the building. */
    private static final java.util.Map<Long, Direction> ENTRANCES = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * An entrance: a door in an outer wall, opening towards local {@code face} (the interior lies that way). Declared so
     * that {@link Accessibility} can keep its approach free.
     */
    public void entrance(int lx, int ly, int lz, BlockState door, Direction face) {
        door(lx, ly, lz, door, face);
        int x = wx(lx, lz), y = oy + ly, z = wz(lx, lz);
        if (doorSink != null) doorSink.add(new int[]{x, y, z, dir(face.getOpposite()).get2DDataValue()});
        if (level == null || !okWorld(x, y, z)) return;
        if (ENTRANCES.size() > 100_000) ENTRANCES.clear();
        ENTRANCES.put(BlockPos.asLong(x, y, z), dir(face.getOpposite()));
    }

    /** The outward direction of an entrance declared by {@link #entrance}, or null. */
    @Nullable
    public static Direction entranceOutward(BlockPos lowerHalf) {
        return ENTRANCES.get(lowerHalf.asLong());
    }

    /** A bed whose foot is at (lx, ly, lz) and whose head lies towards local {@code toHead}. */
    public void bed(int lx, int ly, int lz, BlockState bed, Direction toHead) {
        if (isReserved(lx, ly, lz) || isReserved(lx + toHead.getStepX(), ly, lz + toHead.getStepZ())) {
            deadBeds.add(BlockPos.asLong(wx(lx, lz), oy + ly, wz(lx, lz)));
            return;
        }
        beds[0]++;
        BlockState b = B.face(bed, toHead);
        set(lx, ly, lz, b.setValue(BedBlock.PART, BedPart.FOOT));
        set(lx + toHead.getStepX(), ly, lz + toHead.getStepZ(), b.setValue(BedBlock.PART, BedPart.HEAD));
    }

    /** A loot container; its owner is recorded as a {@link AnchorType#CONTAINER} anchor. */
    public void container(int lx, int ly, int lz, BlockState state, @Nullable ResourceLocation loot) {
        // per building (the count runs through every frame and every chunk's identical replay): few containers, and
        // only the first ones carry loot
        if (isReserved(lx, ly, lz)) return;
        int n = containers[0]++;
        if (n >= MAX_CONTAINERS) return;
        if (n >= MAX_LOOT) loot = null;
        anchor(AnchorType.CONTAINER, lx, ly, lz);
        int x = wx(lx, lz), y = oy + ly, z = wz(lx, lz);
        if (!okWorld(x, y, z)) return;
        if (!supportContainer(x, y, z)) return;
        wset(x, y, z, rot(state));
        if (loot == null) return;
        m.set(x, y, z);
        if (level.getBlockEntity(m) instanceof RandomizableContainerBlockEntity c) {
            c.setLootTable(loot, hash(x, y, z, 31));
        }
    }

    /**
     * Hard rule: no container stands on air, liquid or a non-solid block, or is buried in the ground. A missing
     * floor under it is filled with the neighbouring floor block; a container sealed inside terrain is skipped.
     */
    private boolean supportContainer(int x, int y, int z) {
        BlockState above = wget(x, y + 1, z);
        BlockState here = wget(x, y, z);
        if (!here.isAir() && !open(here) && !above.isAir() && !open(above) && above.isSolid()) return false;
        BlockState below = wget(x, y - 1, z);
        boolean ok = !open(below) && (below.isSolid() || below.getBlock() instanceof net.minecraft.world.level.block.CarpetBlock);
        if (ok) return true;
        if (!okWorld(x, y - 1, z)) return true;
        BlockState fill = null;
        int[][] nb = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : nb) {
            BlockState s = wget(x + d[0], y - 1, z + d[1]);
            if (solidGround(s) && !s.hasBlockEntity()) {
                fill = s;
                break;
            }
        }
        if (fill == null) fill = Blocks.SPRUCE_PLANKS.defaultBlockState();
        wset(x, y - 1, z, fill);
        return true;
    }

    /** A wall sign at (lx, ly, lz) facing local {@code face}, with up to four lines. */
    public void sign(int lx, int ly, int lz, BlockState wallSign, Direction face, Component... lines) {
        int x = wx(lx, lz), y = oy + ly, z = wz(lx, lz);
        if (!okWorld(x, y, z)) return;
        wset(x, y, z, rot(B.face(wallSign, face)));
        BlockEntity be = level.getBlockEntity(m.set(x, y, z));
        if (!(be instanceof SignBlockEntity sign)) return;
        SignText text = new SignText();
        for (int i = 0; i < Math.min(4, lines.length); i++) text = text.setMessage(i, lines[i]);
        CompoundTag tag = sign.saveWithoutMetadata();
        SignText.DIRECT_CODEC.encodeStart(NbtOps.INSTANCE, text).result().ifPresent(t -> tag.put("front_text", t));
        tag.putBoolean("is_waxed", true);
        sign.load(tag);
    }

    // ------------------------------------------------------------------ deterministic variety

    public long hash(int x, int y, int z, int salt) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L) ^ (salt * 0xD6E8FEB86659FD93L);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    /** Uniform int in [0, n) for a local position. */
    public int roll(int lx, int ly, int lz, int salt, int n) {
        return (int) Math.floorMod(hash(wx(lx, lz), oy + ly, wz(lx, lz), salt), (long) n);
    }

    public boolean chance(int lx, int ly, int lz, int salt, int oneIn) {
        return roll(lx, ly, lz, salt, oneIn) == 0;
    }

    /** A random source seeded from this canvas's seed and a salt (same on every chunk). */
    public RandomSource random(int salt) {
        return RandomSource.create(seed * 31 + salt);
    }

    public static int floorDiv(int a, int b) {
        return Mth.floorDiv(a, b);
    }
}
