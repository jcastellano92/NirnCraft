package com.skycraft.realms.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skycraft.realms.Realm;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

import java.util.Locale;

/**
 * Density function {@code skycraft:realm_terrain}: one channel of a realm's 2D terrain ({@link RealmTerrain}).
 * <pre>{@code {"type": "skycraft:realm_terrain", "realm": "deadlands", "channel": "height",
 *   "large": "skycraft:realm/large", "mid": "skycraft:realm/mid", "detail": "skycraft:realm/detail"}}</pre>
 * 2D: wrap it in {@code minecraft:cache_2d}. Thread-safe; the noises are immutable once wired by the noise router.
 */
public record RealmHeight(Realm realm, RealmTerrain.Channel channel, DensityFunction.NoiseHolder large,
                          DensityFunction.NoiseHolder mid, DensityFunction.NoiseHolder detail) implements DensityFunction {

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
            NoiseHolder.CODEC.fieldOf("large").forGetter(RealmHeight::large),
            NoiseHolder.CODEC.fieldOf("mid").forGetter(RealmHeight::mid),
            NoiseHolder.CODEC.fieldOf("detail").forGetter(RealmHeight::detail)
    ).apply(i, RealmHeight::new));
    public static final KeyDispatchDataCodec<RealmHeight> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    @Override
    public double compute(FunctionContext ctx) {
        return RealmTerrain.sample(realm, channel, ctx.blockX(), ctx.blockZ(), large, mid, detail);
    }

    @Override
    public void fillArray(double[] values, ContextProvider provider) {
        provider.fillAllDirectly(values, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new RealmHeight(realm, channel, visitor.visitNoise(large), visitor.visitNoise(mid),
                visitor.visitNoise(detail)));
    }

    @Override
    public double minValue() {
        return channel == RealmTerrain.Channel.HEIGHT ? 0 : 0;
    }

    @Override
    public double maxValue() {
        return channel == RealmTerrain.Channel.HEIGHT ? 256 : 1;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }
}
