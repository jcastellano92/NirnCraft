package com.nirncraft.tamriel.settlement;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.nirncraft.tamriel.Place;
import com.nirncraft.tamriel.Places;
import com.nirncraft.tamriel.settlement.plan.Cities;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * {@code nirncraft:settlement_districts}: extra structure starts for named settlements too big for one start (pieces
 * must lie within 8 chunks of their start). A city whose layout reaches further than {@link #SINGLE_REACH} blocks
 * gets a start every 12 chunks on a grid around its anchor chunk; each start builds only the pieces whose centre
 * falls in its cell (see {@link com.nirncraft.tamriel.settlement.plan.Plan#district}).
 *
 * <pre>{@code "placement": {"type": "nirncraft:settlement_districts", "salt": 1}}</pre>
 */
public class DistrictPlacement implements StructurePlacement {
    public static final MapCodec<DistrictPlacement> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.INT.optionalFieldOf("salt", 0).forGetter(DistrictPlacement::salt)
    ).apply(instance, DistrictPlacement::new));

    /** Layouts reaching at most this far from their anchor fit in a single start. */
    public static final int SINGLE_REACH = 120;
    public static final int STEP = 12;

    /** A district start: the place and its grid cell. */
    public record District(Place place, int ix, int iz) {
    }

    private static volatile Map<Long, District> districts;

    private final int salt;
    public int salt() { return salt; }

    public DistrictPlacement(int salt) {
        this.salt = salt;
    }

    @Override
    public boolean isStructureChunk(ChunkGeneratorStructureState state, int chunkX, int chunkZ) {
        return at(new ChunkPos(chunkX, chunkZ)) != null;
    }

    @Override
    public MapCodec<? extends StructurePlacement> codec() { return CODEC; }

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
        return m.get(pos.pack());
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
                    m.put(ChunkPos.pack(acx + i * STEP, acz + j * STEP), new District(p, i, j));
                }
            }
        }
        return m;
    }
}
