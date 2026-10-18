package com.skycraft.visual;

import com.skycraft.Skycraft;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/**
 * Atmospheric particle types ({@code skycraft:snowflake} ...). The client spawns them itself
 * ({@code visual.client.AmbientParticles}); they are registered as real particle types so their sprites live in the
 * particle atlas and servers or commands can spawn them too ({@code /particle skycraft:ember ...}).
 */
public final class VisualParticles {
    public static final DeferredRegister<ParticleType<?>> TYPES = DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES, Skycraft.MODID);

    public static final RegistryObject<SimpleParticleType> SNOWFLAKE = register("snowflake");
    public static final RegistryObject<SimpleParticleType> ASH_FLAKE = register("ash_flake");
    public static final RegistryObject<SimpleParticleType> EMBER = register("ember");
    public static final RegistryObject<SimpleParticleType> SAND = register("sand_dust");
    public static final RegistryObject<SimpleParticleType> LEAF = register("falling_leaf");
    public static final RegistryObject<SimpleParticleType> FIREFLY = register("firefly");
    public static final RegistryObject<SimpleParticleType> FROST_BREATH = register("frost_breath");
    public static final RegistryObject<SimpleParticleType> MARSH_WISP = register("marsh_wisp");
    public static final RegistryObject<SimpleParticleType> DUST_MOTE = register("dust_mote");

    public static final RegistryObject<SimpleParticleType> SPELL_FROST = register("spell_frost");
    public static final RegistryObject<SimpleParticleType> SPELL_SPARK = register("spell_spark");

    private VisualParticles() {}

    private static RegistryObject<SimpleParticleType> register(String name) {
        return TYPES.register(name, () -> new SimpleParticleType(false));
    }

    static void init(IEventBus modBus) {
        TYPES.register(modBus);
    }
}
