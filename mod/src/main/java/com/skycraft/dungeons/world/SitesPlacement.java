package com.skycraft.dungeons.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skycraft.dungeons.DungeonsRegistry;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;


import java.util.Optional;

/**
 * {@code skycraft:sites}: a structure starts in exactly the chunks listed by a named site list ({@link Sites}): the
 * waypoints of a mountain path or the tiles of a great cavern. The structure's {@code points} field names the same
 * list, so it can find its own site again.
 *
 * <pre>{@code "placement": {"type": "skycraft:sites", "salt": 3, "sites": "seven_thousand_steps"}}</pre>
 */
public class SitesPlacement implements StructurePlacement {
    public static final MapCodec<SitesPlacement> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.INT.optionalFieldOf("salt", 0).forGetter(SitesPlacement::salt),
            Codec.STRING.fieldOf("sites").forGetter(p -> p.sites)
    ).apply(instance, SitesPlacement::new));

    private final String sites;

    private final int salt;
    public int salt() { return salt; }

    public SitesPlacement(int salt, String sites) {
        this.salt = salt;
        this.sites = sites;
    }

    @Override
    public boolean isStructureChunk(ChunkGeneratorStructureState state, int chunkX, int chunkZ) {
        return Sites.indexAt(sites, chunkX, chunkZ) >= 0;
    }

    @Override
    public MapCodec<? extends StructurePlacement> codec() { return CODEC; }
}
