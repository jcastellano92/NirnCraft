package com.skycraft.realms.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skycraft.realms.worldgen.site.RealmSites;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

import java.util.Optional;

/**
 * {@code skycraft:realm_site}: one structure type for every realm landmark and every way into a realm. The JSON names
 * the {@code site} ({@link RealmSites}); the site decides where it starts in its start chunk (fixed landmark
 * coordinates, the chunk centre, or a named place) from noise heights only, and paints itself chunk by chunk.
 * Realm sites list their realm's biomes; the Nirn entries list every overworld biome.
 */
public class RealmStructure extends Structure {
    public static final Codec<RealmStructure> CODEC = RecordCodecBuilder.create(i -> i.group(
            settingsCodec(i),
            Codec.STRING.fieldOf("site").forGetter(s -> s.site)
    ).apply(i, RealmStructure::new));

    public final String site;

    public RealmStructure(StructureSettings settings, String site) {
        super(settings);
        this.site = site;
    }

    @Override
    public StructureType<?> type() {
        return RealmWorldgen.REALM_STRUCTURE.get();
    }

    /** The site decides; the biome check is skipped (landmarks build where the layout puts them). */
    @Override
    public Optional<GenerationStub> findValidGenerationPoint(GenerationContext ctx) {
        return findGenerationPoint(ctx);
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext ctx) {
        RealmSites.Site s = RealmSites.get(site);
        if (s == null) return Optional.empty();
        Optional<RealmSites.Start> start = s.locate(ctx);
        if (start.isEmpty()) return Optional.empty();
        RealmSites.Start st = start.get();
        long seed = ctx.random().nextLong();
        BoundingBox box = s.box(st);
        return Optional.of(new GenerationStub(new BlockPos(st.x(), st.y(), st.z()),
                builder -> builder.addPiece(new RealmPiece(site, box, st.x(), st.y(), st.z(), st.param(), st.facing(), seed))));
    }
}
