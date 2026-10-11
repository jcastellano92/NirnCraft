package com.skycraft.realms.worldgen.decor;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code skycraft:realm_decor}: one feature type for every realm's procedural decoration (giant mushrooms, crystal
 * trees, rock spikes, bone pillars, tentacles, book stacks, chain pylons...). The configured feature names the
 * {@code kind}; {@code size} scales it. Every decorator writes only within 12 blocks (horizontally) of its origin, so
 * it stays inside the 3x3-chunk region a feature may touch.
 *
 * <pre>{@code {"type": "skycraft:realm_decor", "config": {"kind": "giant_mushroom", "size": 1}}}</pre>
 */
public class RealmDecor extends Feature<RealmDecor.Config> {
    public record Config(String kind, int size) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("kind").forGetter(Config::kind),
                Codec.INT.optionalFieldOf("size", 1).forGetter(Config::size)
        ).apply(i, Config::new));
    }

    /** One decorator. Return true when something was placed. */
    @FunctionalInterface
    public interface Decorator {
        boolean place(WorldGenLevel level, BlockPos origin, RandomSource random, int size);
    }

    private static final Map<String, Decorator> KINDS = new HashMap<>();

    static {
        DeadlandsDecor.register();
        SovngardeDecor.register();
        IslesDecor.register();
        ApocryphaDecor.register();
        SoulCairnDecor.register();
        ColdharbourDecor.register();
    }

    public static void add(String kind, Decorator decorator) {
        if (KINDS.put(kind, decorator) != null) throw new IllegalStateException("Duplicate realm decor " + kind);
    }

    @Nullable
    public static Decorator get(String kind) {
        return KINDS.get(kind);
    }

    public RealmDecor() {
        super(Config.CODEC);
    }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Decorator d = KINDS.get(ctx.config().kind());
        if (d == null) return false;
        return d.place(ctx.level(), ctx.origin(), ctx.random(), Math.max(1, ctx.config().size()));
    }
}
