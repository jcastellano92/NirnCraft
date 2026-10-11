package com.skycraft.tamriel.settlement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.settlement.plan.Cities;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacementType;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code skycraft:settlement_districts}: extra structure starts for named settlements too big for one start (pieces
 * must lie within 8 chunks of their start). A city whose layout reaches further than {@link #SINGLE_REACH} blocks
 * gets a start every 12 chunks on a grid around its anchor chunk; each start builds only the pieces whose centre
 * falls in its cell (see {@link com.skycraft.tamriel.settlement.plan.Plan#district}).
 *
 * <pre>{@code "placement": {"type": "skycraft:settlement_districts", "salt": 1}}</pre>
 */
public class DistrictPlacement extends StructurePlacement {
    public static final Codec<DistrictPlacement> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.optionalFieldOf("salt", 0).forGetter(DistrictPlacement::salt)
    ).apply(instance, DistrictPlacement::new));

    /** Layouts reaching at most this far from their anchor fit in a single start. */
    public static final int SINGLE_REACH = 120;
    public static final int STEP = 12;

    /** A district start: the place and its grid cell. */
    public record District(Place place, int ix, int iz) {
    }

    private static volatile Map<Long, District> districts;

    public DistrictPlacement(int salt) {
        super(Vec3i.ZERO, FrequencyReductionMethod.DEFAULT, 1.0f, salt, Optional.empty());
    }

    @Override
    protected boolean isPlacementChunk(ChunkGeneratorStructureState state, int chunkX, int chunkZ) {
        return at(new ChunkPos(chunkX, chunkZ)) != null;
    }

    @Override
    public StructurePlacementType<?> type() {
        return SettlementStructures.DISTRICTS.get();
    }

    /** The district whose start is in this chunk, if any. */
    @Nullable
    public static District at(ChunkPos pos) {
        Map<Long, District> m = districts;
        if (m == null) {
            synchronized (DistrictPlacement.class) {
                if (districts == null) districts = build();
                m = districts;
            }
        }
        return m.get(pos.toLong());
    }

    /** Grid cells needed on each side of the anchor for a layout reaching {@code reach} blocks. */
    public static int cells(int reach) {
        if (reach <= SINGLE_REACH) return 0;
        return (int) Math.ceil((reach - 6 * 16) / (STEP * 16.0));
    }

    private static Map<Long, District> build() {
        Map<Long, District> m = new HashMap<>();
        for (Place p : Places.all()) {
            if (!p.type().isSettlement()) continue;
            int n = cells(Cities.reach(p));
            if (n == 0) continue;
            int acx = p.x() >> 4, acz = p.z() >> 4;
            for (int i = -n; i <= n; i++) {
                for (int j = -n; j <= n; j++) {
                    if (i == 0 && j == 0) continue;
                    m.put(ChunkPos.asLong(acx + i * STEP, acz + j * STEP), new District(p, i, j));
                }
            }
        }
        return m;
    }
}
