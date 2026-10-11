package com.skycraft.realms.worldgen;

import com.skycraft.dungeons.world.Painter;
import com.skycraft.realms.worldgen.site.RealmSites;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * The one piece of every realm site: pure data (site id, origin, a parameter, facing and seed). Each chunk the
 * piece's box touches calls the site's painter ({@link RealmSites}) through a chunk-clipped {@link Painter}, so a
 * site describes itself in full and every chunk receives only its own part.
 */
public class RealmPiece extends StructurePiece {
    public final String site;
    public final int ox, oy, oz;
    /** Site-specific: keep cell, chapter number, variant... */
    public final int param;
    /** 2D data value of the facing (0 south, 1 west, 2 north, 3 east). */
    public final int facing;
    public final long seed;

    public RealmPiece(String site, BoundingBox box, int ox, int oy, int oz, int param, int facing, long seed) {
        super(RealmWorldgen.REALM_PIECE.get(), 0, box);
        this.site = site;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.param = param;
        this.facing = facing;
        this.seed = seed;
    }

    public RealmPiece(StructurePieceSerializationContext context, CompoundTag tag) {
        super(RealmWorldgen.REALM_PIECE.get(), tag);
        this.site = tag.getString("Site");
        this.ox = tag.getInt("OX");
        this.oy = tag.getInt("OY");
        this.oz = tag.getInt("OZ");
        this.param = tag.getInt("Param");
        this.facing = tag.getInt("Facing");
        this.seed = tag.getLong("Seed");
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putString("Site", site);
        tag.putInt("OX", ox);
        tag.putInt("OY", oy);
        tag.putInt("OZ", oz);
        tag.putInt("Param", param);
        tag.putInt("Facing", facing);
        tag.putLong("Seed", seed);
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random,
                            BoundingBox chunkBox, ChunkPos chunkPos, BlockPos pivot) {
        RealmSites.Site s = RealmSites.get(site);
        if (s == null) return;
        Painter p = new Painter(level, chunkBox, getBoundingBox(), seed);
        if (p.empty()) return;
        s.paint(p, this);
    }
}
