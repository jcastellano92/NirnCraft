package com.nirncraft.realms.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.nirncraft.realms.Realm;
import net.minecraft.util.Interval;
import net.minecraft.world.level.levelgen.densityfunction.*;
import com.nirncraft.worldgen.WorldNoise;
import com.nirncraft.worldgen.WorldDensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;

import java.util.Locale;

/**
 * Density function {@code nirncraft:realm_terrain}: one channel of a realm's 2D terrain ({@link RealmTerrain}).
 * <pre>{@code {"type": "nirncraft:realm_terrain", "realm": "deadlands", "channel": "height",
 *   "large": "nirncraft:realm/large", "mid": "nirncraft:realm/mid", "detail": "nirncraft:realm/detail"}}</pre>
 * 2D: wrap it in {@code minecraft:cache_2d}. Thread-safe; the noises are immutable once wired by the noise router.
 */
public record RealmHeight(Realm realm, RealmTerrain.Channel channel, WorldNoise large,
                          WorldNoise mid, WorldNoise detail) implements DensityFunction {

    private static final Codec<Realm> REALM = Codec.STRING.comapFlatMap(s -> {
        Realm r = Realm.byId(s);
        return r == null ? DataResult.error(() -> "Unknown realm " + s) : DataResult.success(r);
    }, r -> r.id);
    private static final Codec<RealmTerrain.Channel> CHANNEL = Codec.STRING.comapFlatMap(s -> {
        try {
            return DataResult.success(RealmTerrain.Channel.valueOf(s.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return DataResult.error(() -> "Unknown realm terrain channel " + s);
        }
    }, c -> c.name().toLowerCase(Locale.ROOT));

    public static final MapCodec<RealmHeight> DATA_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            REALM.fieldOf("realm").forGetter(RealmHeight::realm),
            CHANNEL.optionalFieldOf("channel", RealmTerrain.Channel.HEIGHT).forGetter(RealmHeight::channel),
            WorldNoise.CODEC.fieldOf("large").forGetter(RealmHeight::large),
            WorldNoise.CODEC.fieldOf("mid").forGetter(RealmHeight::mid),
            WorldNoise.CODEC.fieldOf("detail").forGetter(RealmHeight::detail)
    ).apply(i, RealmHeight::new));
    public static final MapCodec<RealmHeight> CODEC = DATA_CODEC;

    public double compute(int blockX, int blockY, int blockZ) {
        return RealmTerrain.sample(realm, channel, blockX, blockZ, large, mid, detail);
    }





    public double minValue() {
        return channel == RealmTerrain.Channel.HEIGHT ? 0 : 0;
    }

    public double maxValue() {
        return channel == RealmTerrain.Channel.HEIGHT ? 256 : 1;
    }

    @Override public DensitySampler compileSampler(CompileContext context) {
        var bound = new RealmHeight(realm, channel, large.bind(context), mid.bind(context), detail.bind(context));
        return (WorldDensitySampler) (sampling, x, y, z) -> (float) bound.compute(x, y, z);
    }

    @Override public DensityFunction rewriteChildren(DfRewriteRule rule) { return this; }
    @Override public Interval range() { return Interval.of((float) minValue(), (float) maxValue()); }
    @Override public int domainAxes() { return DensityFunction.AXIS_X | DensityFunction.AXIS_Z; }

    @Override
    public MapCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
