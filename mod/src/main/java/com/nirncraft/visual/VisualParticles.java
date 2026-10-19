package com.nirncraft.visual;

import com.nirncraft.NirnCraft;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.function.Supplier;

/**
 * Atmospheric particle types ({@code nirncraft:snowflake} ...). The client spawns them itself
 * ({@code visual.client.AmbientParticles}); they are registered as real particle types so their sprites live in the
 * particle atlas and servers or commands can spawn them too ({@code /particle nirncraft:ember ...}).
 */
public final class VisualParticles {
    public static final DeferredRegister<ParticleType<?>> TYPES = DeferredRegister.create(BuiltInRegistries.PARTICLE_TYPE, NirnCraft.MODID);

    public static final Supplier<SimpleParticleType> SNOWFLAKE = register("snowflake");
    public static final Supplier<SimpleParticleType> ASH_FLAKE = register("ash_flake");
    public static final Supplier<SimpleParticleType> EMBER = register("ember");
    public static final Supplier<SimpleParticleType> SAND = register("sand_dust");
    public static final Supplier<SimpleParticleType> LEAF = register("falling_leaf");
    public static final Supplier<SimpleParticleType> FIREFLY = register("firefly");
    public static final Supplier<SimpleParticleType> FROST_BREATH = register("frost_breath");
    public static final Supplier<SimpleParticleType> MARSH_WISP = register("marsh_wisp");
    public static final Supplier<SimpleParticleType> DUST_MOTE = register("dust_mote");

    public static final Supplier<SimpleParticleType> SPELL_FROST = register("spell_frost");
    public static final Supplier<SimpleParticleType> SPELL_SPARK = register("spell_spark");

    private VisualParticles() {}

    private static Supplier<SimpleParticleType> register(String name) {
        return TYPES.register(name, () -> new SimpleParticleType(false));
    }

    static void init(IEventBus modBus) {
        TYPES.register(modBus);
    }
}
