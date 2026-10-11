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
        return c;
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
        int y = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) - 1;
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

    /** Water surface at (x, z) if the column is covered by water, else {@link Integer#MIN_VALUE}. */
    public int wwater(int x, int z) {
        if (level == null || x < x0 || x > x1 || z < z0 || z > z1) return Integer.MIN_VALUE;
        int top = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, x, z) - 1;
        return level.getBlockState(m.set(x, top, z)).getFluidState().isEmpty() ? Integer.MIN_VALUE : top;
    }

    // ------------------------------------------------------------------ local access

    public BlockState get(int lx, int ly, int lz) {
        return wget(wx(lx, lz), oy + ly, wz(lx, lz));
    }

    public void set(int lx, int ly, int lz, BlockState s) {
        int x = wx(lx, lz), y = oy + ly, z = wz(lx, lz);
        if (!okWorld(x, y, z)) return;
        wset(x, y, z, rot(s));
    }

    /** Sets only if the block there is open (air, fluid, plant, snow, leaves). */
    public void setIfOpen(int lx, int ly, int lz, BlockState s) {
        int x = wx(lx, lz), y = oy + ly, z = wz(lx, lz);
        if (!okWorld(x, y, z)) return;
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
        for (int x = minX; x <= maxX; x++)
            for (int z = minZ; z <= maxZ; z++)
                for (int y = minY; y <= maxY; y++) {
                    BlockState s = level.getBlockState(m.set(x, y, z));
                    if (s.isAir()) continue;
                    if (s.is(BlockTags.LEAVES) || (s.is(BlockTags.LOGS) && natural(s))
                            || (s.canBeReplaced() && s.getFluidState().isEmpty()) || s.is(Blocks.SNOW) || s.is(BlockTags.FLOWERS)
                            || s.is(Blocks.BAMBOO) || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.CACTUS)) {
                        wset(x, y, z, air);
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
        if (anchors != null) anchors.add(new Anchor(type, new BlockPos(wx(lx, lz), oy + ly, wz(lx, lz)), who));
    }

    /** A door (both halves) in the local wall, opening towards local {@code face}. */
    public void door(int lx, int ly, int lz, BlockState door, Direction face) {
        BlockState d = B.face(door, face).setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
        set(lx, ly, lz, d.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        set(lx, ly + 1, lz, d.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    /** A bed whose foot is at (lx, ly, lz) and whose head lies towards local {@code toHead}. */
    public void bed(int lx, int ly, int lz, BlockState bed, Direction toHead) {
        BlockState b = B.face(bed, toHead);
        set(lx, ly, lz, b.setValue(BedBlock.PART, BedPart.FOOT));
        set(lx + toHead.getStepX(), ly, lz + toHead.getStepZ(), b.setValue(BedBlock.PART, BedPart.HEAD));
    }

    /** A loot container; its owner is recorded as a {@link AnchorType#CONTAINER} anchor. */
    public void container(int lx, int ly, int lz, BlockState state, @Nullable ResourceLocation loot) {
        anchor(AnchorType.CONTAINER, lx, ly, lz);
        int x = wx(lx, lz), y = oy + ly, z = wz(lx, lz);
        if (!okWorld(x, y, z)) return;
        wset(x, y, z, rot(state));
        if (loot == null) return;
        m.set(x, y, z);
        if (level.getBlockEntity(m) instanceof RandomizableContainerBlockEntity c) {
            c.setLootTable(loot, hash(x, y, z, 31));
        }
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
