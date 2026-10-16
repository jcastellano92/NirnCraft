package com.skycraft.tamriel.terrain;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skycraft.tamriel.MapSample;
import com.skycraft.tamriel.TamrielMap;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Custom world-scale subterranean cave density router ({@code skycraft:tamriel_caves}).
 *
 * <p>Wraps an outer terrain density function (such as {@code skycraft:tamriel/sloped}) and carves regional karst
 * fault galleries, vaulted underworld chambers ({@code y = -32..40}), and river-fed underground gorges
 * ({@code s.river > 0.1f && s.elevation > 90f}) while smoothly pinching caves closed ({@code density -> +1.0})
 * within the footprint of any {@link com.skycraft.tamriel.Places} dungeon/landmark or
 * {@link com.skycraft.worldplan.PlanGround} road bed / settlement pad.</p>
 */
public record TamrielCaves(DensityFunction sloped) implements DensityFunction {

    public static final MapCodec<TamrielCaves> DATA_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            DensityFunction.HOLDER_HELPER_CODEC.fieldOf("sloped").forGetter(TamrielCaves::sloped)
    ).apply(i, TamrielCaves::new));

    public static final KeyDispatchDataCodec<TamrielCaves> CODEC = KeyDispatchDataCodec.of(DATA_CODEC);

    @Override
    public double compute(FunctionContext ctx) {
        int x = ctx.blockX();
        int y = ctx.blockY();
        int z = ctx.blockZ();
        double base = sloped.compute(ctx);
        MapSample s = TamrielMap.sampleScratch(x, z);
        return TerrainDensity.computeCaveDensity(s, x, y, z, base);
    }

    @Override
    public void fillArray(double[] array, ContextProvider provider) {
        provider.fillAllDirectly(array, this);
    }

    @Override
    public DensityFunction mapAll(Visitor visitor) {
        return visitor.apply(new TamrielCaves(sloped.mapAll(visitor)));
    }

    @Override
    public double minValue() {
        return -1.0;
    }

    @Override
    public double maxValue() {
        return 1.0;
    }

    @Override
    public KeyDispatchDataCodec<? extends DensityFunction> codec() {
        return CODEC;
    }

    /** Evaluates the standalone subterranean cave density at block position {@code (x, y, z)}. */
    public static double sample(int x, int y, int z, double slopedDensity) {
        MapSample s = TamrielMap.sampleScratch(x, z);
        return TerrainDensity.computeCaveDensity(s, x, y, z, slopedDensity);
    }

    /** True when column {@code (x, z)} is protected by a road bed, settlement pad, or placed dungeon footprint. */
    public static boolean isProtectedColumn(int x, int z) {
        return TerrainDensity.isRoadOrPlot(x, z);
    }

    /** True when column {@code (x, z)} carries a high-elevation river gorge that feeds subterranean river caves. */
    public static boolean isRiverGorgeColumn(int x, int z) {
        return TerrainDensity.isRiverGorge(TamrielMap.sampleScratch(x, z));
    }

    /** True when {@code y} lies within the vaulted underworld dome band ({@code y = -32..40}). */
    public static boolean isVaultChamberBand(int y) {
        return y >= TerrainDensity.VAULT_MIN_Y && y <= TerrainDensity.VAULT_MAX_Y;
    }
}
