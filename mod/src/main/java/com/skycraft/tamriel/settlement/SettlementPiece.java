package com.skycraft.tamriel.settlement;

import com.skycraft.Skycraft;
import com.skycraft.tamriel.settlement.build.Builders;
import com.skycraft.tamriel.settlement.build.Canvas;
import com.skycraft.tamriel.settlement.plan.Archetype;
import com.skycraft.tamriel.settlement.plan.Spec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

import java.util.List;

/**
 * One building or work of a settlement. Pure data (a serialized {@link Spec} plus the settlement key); every chunk
 * it touches calls {@link #postProcess}, which repaints the whole piece through a chunk-clipped {@link Canvas}. The
 * same data replayed through a recording canvas yields the piece's {@link Anchor}s on the server thread.
 */
public class SettlementPiece extends StructurePiece {
    /** Foundations and posts may reach this far below the floor. */
    public static final int FOUNDATION = 48;

    public final Spec spec;
    public final long seed;
    /** Settlement key: the place id, or {@code gen:<chunkX>,<chunkZ>} for generic settlements. */
    public final String settlement;
    public final int index;

    public SettlementPiece(Spec spec, long seed, String settlement, int index) {
        super(SettlementStructures.PIECE.get(), 0, box(spec));
        this.spec = spec;
        this.seed = seed;
        this.settlement = settlement;
        this.index = index;
    }

    public SettlementPiece(StructurePieceSerializationContext context, CompoundTag tag) {
        super(SettlementStructures.PIECE.get(), tag);
        Spec s = new Spec(Archetype.byOrdinal(tag.getInt("K")));
        s.culture = tag.getInt("C");
        s.style = tag.getInt("St");
        int[] g = tag.getIntArray("G");
        if (g.length >= 12) {
            s.x = g[0];
            s.z = g[1];
            s.x2 = g[2];
            s.z2 = g[3];
            s.w = g[4];
            s.d = g[5];
            s.h = g[6];
            s.floor = g[7];
            s.floor2 = g[8];
            s.flags = g[9];
            s.variant = g[10];
            s.margin = g[11];
        }
        s.facing = Direction.from2DDataValue(tag.getInt("F"));
        s.name = tag.getString("N");
        int[] gx = tag.getIntArray("Gx");
        if (gx.length >= 4) {
            s.use = gx[0];
            s.wealth = gx[1];
            s.gseed = gx[2];
            s.slope = gx[3];
        }
        this.spec = s;
        this.seed = tag.getLong("Seed");
        this.settlement = tag.getString("S");
        this.index = tag.getInt("I");
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        Spec s = spec;
        tag.putInt("K", s.kind.ordinal());
        tag.putInt("C", s.culture);
        tag.putInt("St", s.style);
        tag.putIntArray("G", new int[]{s.x, s.z, s.x2, s.z2, s.w, s.d, s.h, s.floor, s.floor2, s.flags, s.variant, s.margin});
        tag.putInt("F", s.facing.get2DDataValue());
        tag.putString("N", s.name);
        if (s.use >= 0) tag.putIntArray("Gx", new int[]{s.use, s.wealth, s.gseed, s.slope});
        tag.putLong("Seed", seed);
        tag.putString("S", settlement);
        tag.putInt("I", index);
    }

    /** World bounding box of a planned piece: its footprint (plus margin) from the floor up to its height. */
    public static BoundingBox box(Spec s) {
        if (s.isLine()) {
            int hw = s.w / 2 + 1;
            int f1 = s.floor, f2 = s.floor2 == Spec.UNSET ? s.floor : s.floor2;
            // lines never shape the terrain (NO_BEARD), so their box may span the slope they follow
            int slack = s.kind == com.skycraft.tamriel.settlement.plan.Archetype.STREET
                    || s.kind == com.skycraft.tamriel.settlement.plan.Archetype.BRIDGE ? 20 : 0;
            return new BoundingBox(Math.min(s.x, s.x2) - hw, Math.min(f1, f2) + 1 - slack, Math.min(s.z, s.z2) - hw,
                    Math.max(s.x, s.x2) + hw, Math.max(f1, f2) + Math.max(2, s.h) + slack, Math.max(s.z, s.z2) + hw);
        }
        int[] o = origin(s);
        int m = s.kind == com.skycraft.tamriel.settlement.plan.Archetype.TERRACE ? Math.max(s.margin, 14) : s.margin;
        int ax = cornerX(s, o, -m, -m), az = cornerZ(s, o, -m, -m);
        int bx = cornerX(s, o, s.w - 1 + m, s.d - 1 + m), bz = cornerZ(s, o, s.w - 1 + m, s.d - 1 + m);
        int h = Math.max(2, Builders.height(s));
        int minY = s.kind == com.skycraft.tamriel.settlement.plan.Archetype.TERRACE ? s.floor - 16 : s.floor + 1;
        return new BoundingBox(Math.min(ax, bx), minY, Math.min(az, bz), Math.max(ax, bx), s.floor + h, Math.max(az, bz));
    }

    /** World position of the building's local origin (front-left corner) so that its footprint is centred on (x, z). */
    public static int[] origin(Spec s) {
        int lcx = (s.w - 1) / 2, lcz = (s.d - 1) / 2;
        int dx = mapX(s.facing, lcx, lcz), dz = mapZ(s.facing, lcx, lcz);
        return new int[]{s.x - dx, s.z - dz};
    }

    private static int cornerX(Spec s, int[] o, int lx, int lz) {
        return o[0] + mapX(s.facing, lx, lz);
    }

    private static int cornerZ(Spec s, int[] o, int lx, int lz) {
        return o[1] + mapZ(s.facing, lx, lz);
    }

    static int mapX(Direction f, int lx, int lz) {
        return switch (f) {
            case EAST -> -lz;
            case SOUTH -> -lx;
            case WEST -> lz;
            default -> lx;
        };
    }

    static int mapZ(Direction f, int lx, int lz) {
        return switch (f) {
            case EAST -> lx;
            case SOUTH -> -lz;
            case WEST -> -lx;
            default -> lz;
        };
    }

    /** Owner label of this piece (anchors and container ownership). */
    public String owner() {
        String kind = spec.use >= 0 ? com.skycraft.tamriel.settlement.grammar.Use.byOrdinal(spec.use).id()
                : spec.kind.name().toLowerCase(java.util.Locale.ROOT);
        String base = spec.name.isEmpty() ? kind : spec.name;
        return spec.has(Spec.FOR_SALE) ? base : base + "#" + index;
    }

    /** The beardifier only shapes terrain around pieces that sit on it (not streets, docks or bridges). */
    @Override
    public boolean isCloseToChunk(ChunkPos pos, int range) {
        if (spec.has(Spec.NO_BEARD)) return false;
        return super.isCloseToChunk(pos, range);
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random,
                            BoundingBox chunkBox, ChunkPos chunkPos, BlockPos pivot) {
        BoundingBox b = this.boundingBox;
        BoundingBox clip = new BoundingBox(
                Math.max(chunkBox.minX(), b.minX()), Math.max(chunkBox.minY(), b.minY() - FOUNDATION), Math.max(chunkBox.minZ(), b.minZ()),
                Math.min(chunkBox.maxX(), b.maxX()), Math.min(chunkBox.maxY(), b.maxY()), Math.min(chunkBox.maxZ(), b.maxZ()));
        if (clip.minX() > clip.maxX() || clip.minZ() > clip.maxZ() || clip.minY() > clip.maxY()) return;
        Canvas c = canvas(level, clip);
        c.owner = owner();
        try {
            Builders.build(c, this);
            com.skycraft.tamriel.settlement.build.Sanity.sweep(level, clip, chunkBox);
            // crowns and trunk stubs the clearing left hanging, here and in the neighbouring chunks of the region
            com.skycraft.world.OrphanSweep.sweep(level, clip.minX(), clip.minZ(), clip.maxX(), clip.maxZ(), 10,
                    chunkBox.minX() - 14, chunkBox.minZ() - 14, chunkBox.maxX() + 14, chunkBox.maxZ() + 14, null);
            // boulders and loose stone whose ground the grading removed (they would hang in the air over the town)
            com.skycraft.world.RockSweep.sweep(level, clip.minX(), clip.minZ(), clip.maxX(), clip.maxZ(), 6,
                    chunkBox.minX() - 14, chunkBox.minZ() - 14, chunkBox.maxX() + 14, chunkBox.maxZ() + 14, 24, null, null);
            {
                // a yard, garden, fence line or stall painted after a building can fence its door in: sweep the neighbourhood too
                BoundingBox wide = new BoundingBox(
                        Math.max(chunkBox.minX(), clip.minX() - 7), Math.max(chunkBox.minY(), clip.minY() - 2), Math.max(chunkBox.minZ(), clip.minZ() - 7),
                        Math.min(chunkBox.maxX(), clip.maxX() + 7), Math.min(chunkBox.maxY(), clip.maxY() + 4), Math.min(chunkBox.maxZ(), clip.maxZ() + 7));
                com.skycraft.tamriel.settlement.build.Accessibility.sweep(level, wide, chunkBox);
            }
        } catch (RuntimeException e) {
            Skycraft.LOGGER.error("Settlement piece {} of {} failed in chunk {}", spec.kind, settlement, chunkPos, e);
        }
    }

    private Canvas canvas(WorldGenLevel level, BoundingBox clip) {
        if (spec.isLine()) return new Canvas(level, clip, 0, spec.floor, 0, Direction.NORTH, seed);
        int[] o = origin(spec);
        return new Canvas(level, clip, o[0], spec.floor, o[1], spec.facing, seed);
    }

    /**
     * Replays the builder without painting: adds the anchors it declares to {@code anchors} and its declared entrances
     * ({world x, y, z of the lower door half, outward 2D value}) to {@code doors} (the street plan bake, P5).
     */
    public void recordDoors(List<Anchor> anchors, List<int[]> doors) {
        Canvas c;
        if (spec.isLine()) {
            c = new Canvas(anchors, 0, spec.floor, 0, Direction.NORTH, seed);
        } else {
            int[] o = origin(spec);
            c = new Canvas(anchors, o[0], spec.floor, o[1], spec.facing, seed);
        }
        c.doors(doors);
        c.owner = owner();
        try {
            Builders.build(c, this);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.warn("Could not record the doors of {} in {}", spec.kind, settlement, e);
        }
    }

    /** Replays the builder without painting and adds the anchors it declares to {@code out}. */
    public void record(List<Anchor> out) {
        Canvas c;
        if (spec.isLine()) {
            c = new Canvas(out, 0, spec.floor, 0, Direction.NORTH, seed);
        } else {
            int[] o = origin(spec);
            c = new Canvas(out, o[0], spec.floor, o[1], spec.facing, seed);
        }
        c.owner = owner();
        try {
            Builders.build(c, this);
        } catch (RuntimeException e) {
            Skycraft.LOGGER.warn("Could not record anchors of {} in {}", spec.kind, settlement, e);
        }
    }
}
