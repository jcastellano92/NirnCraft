package com.skycraft.perf;

import com.mojang.serialization.Codec;
import com.skycraft.Skycraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * Worldgen performance: the {@code skycraft:fast_surface} surface rule wrapper ({@link FastSurfaceRule}), the
 * benchmark and profiler ({@link PerfCommands}, {@link ChunkBench}, {@link Profiling}), the pre-generator
 * ({@link Pregen}) and headless automation ({@link PerfAutorun}). Config: {@code skycraft-perf-server.toml}
 * ({@link PerfConfig}).
 */
public final class PerfModule {
    public static final DeferredRegister<Codec<? extends SurfaceRules.RuleSource>> RULES =
            DeferredRegister.create(Registries.MATERIAL_RULE, Skycraft.MODID);
    public static final RegistryObject<Codec<? extends SurfaceRules.RuleSource>> FAST_SURFACE =
            RULES.register("fast_surface", FastSurfaceRule::codecForRegistry);

    private PerfModule() {}

    public static void init(IEventBus modBus) {
        RULES.register(modBus);
        PerfPackets.register();
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, PerfConfig.SPEC, "skycraft-perf-server.toml");
    }
}
