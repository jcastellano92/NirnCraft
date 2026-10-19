package com.nirncraft.perf;

import com.mojang.serialization.MapCodec;
import com.nirncraft.NirnCraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.registries.DeferredRegister;
import java.util.function.Supplier;

/**
 * Worldgen performance: the {@code nirncraft:fast_surface} surface rule wrapper ({@link FastSurfaceRule}), the
 * benchmark and profiler ({@link PerfCommands}, {@link ChunkBench}, {@link Profiling}), the pre-generator
 * ({@link Pregen}) and headless automation ({@link PerfAutorun}). Config: {@code nirncraft-perf-server.toml}
 * ({@link PerfConfig}).
 */
public final class PerfModule {
    public static final DeferredRegister<MapCodec<? extends MaterialRule>> RULES =
            DeferredRegister.create(Registries.MATERIAL_RULE_TYPE, NirnCraft.MODID);
    public static final Supplier<MapCodec<FastSurfaceRule.Source>> FAST_SURFACE =
            RULES.register("fast_surface", () -> FastSurfaceRule.Source.CODEC);

    private PerfModule() {}

    public static void init(IEventBus modBus) {
        PerfTickets.init(modBus);
        RULES.register(modBus);
        PerfPackets.register();
        com.nirncraft.NirnCraft.config(ModConfig.Type.SYNCED, PerfConfig.SPEC, "nirncraft-perf-server.toml");
    }
}
