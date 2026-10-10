package com.skycraft.tamriel;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skycraft.tamriel.biome.BiomeNoise;
import com.skycraft.tamriel.biome.Palettes;
import com.skycraft.tamriel.biome.Palettes.Context;
import com.skycraft.tamriel.biome.Palettes.Palette;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.List;
import java.util.stream.Stream;

/**
 * The {@code skycraft:tamriel} biome source: picks each biome from the region at that position ({@link TamrielMap})
 * and the region's palette ({@link Palettes}).
 *
 * <ol>
 *     <li>The region comes from a domain-warped position (a smooth warp plus a little per-cell jitter), so region
 *     borders wander organically instead of following the map's 32-block cells.</li>
 *     <li>The terrain context comes from the noise router and the map: depth below the surface (caves), sea and coast
 *     (map coast distance, continentalness), rivers and lakes (map channels, vanilla valleys at water level), and
 *     the surface height estimated from depth (lowland, upland, peak, summit, with jittered thresholds).</li>
 *     <li>Inside a context the palette's weighted choices are walked with a smooth climate value: the sampler's
 *     humidity and temperature mixed with a fixed low-frequency noise (3D noise underground).</li>
 * </ol>
 * Thread-safe: compiled palettes are immutable; per-column work is cached in small thread-local tables.
 *
 * <p>Dev testing: {@code -Dskycraft.biomeTest=<palette key>} (or env {@code SKYCRAFT_BIOME_TEST}) forces one
 * palette everywhere; {@code -Dskycraft.biomeTest=grid} lays every catalogue biome out in a 48-block grid.
 * {@code /skybiome} prints what the source picks at a position. The type id {@code skycraft:tamriel} and the JSON
 * shape {@code {"type": "skycraft:tamriel"}} are fixed.</p>
 */
public class TamrielBiomeSource extends BiomeSource {
    public static final Codec<TamrielBiomeSource> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            RegistryOps.retrieveGetter(Registries.BIOME)
    ).apply(instance, instance.stable(TamrielBiomeSource::new)));

    private static final String TEST = System.getProperty("skycraft.biomeTest", System.getenv("SKYCRAFT_BIOME_TEST"));
    private static final int SEA = TamrielMap.SEA_LEVEL;
    private static final int GRID = 48;

    private final Palettes.Compiled palettes;
    private final Palette forced;
    private final boolean grid;

    private static final ThreadLocal<Column[]> COLUMNS = ThreadLocal.withInitial(() -> {
        Column[] c = new Column[16];
        for (int i = 0; i < c.length; i++) c[i] = new Column();
        return c;
    });

    /** Everything about one quart column that doesn't depend on y. */
    private static final class Column {
        int qx = Integer.MIN_VALUE, qz = Integer.MIN_VALUE;
        final MapSample sample = new MapSample();
        Palette palette;
        String regionId;
        double noise;
        int jitter;
    }

    public TamrielBiomeSource(HolderGetter<Biome> biomes) {
        this.palettes = Palettes.compile(biomes);
        this.grid = "grid".equals(TEST);
        this.forced = TEST != null && !grid ? palettes.byKey(TEST) : null;
        if (TEST != null) com.skycraft.Skycraft.LOGGER.warn("Tamriel biome source TEST MODE: {}", TEST);
    }

    @Override
    protected Codec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return palettes.all.stream();
    }

    @Override
    public Holder<Biome> getNoiseBiome(int qx, int qy, int qz, Climate.Sampler sampler) {
        if (grid) return gridBiome(qx, qz);
        Column col = column(qx, qz);
        Climate.TargetPoint tp = sampler.sample(qx, qy, qz);
        Context ctx = context(col, col.palette, qy << 2, tp);
        return pick(col, col.palette, ctx, qx, qy, qz, tp);
    }

    // ------------------------------------------------------------------------------------------- internals
    private Column column(int qx, int qz) {
        Column col = COLUMNS.get()[(qx & 3) | (qz & 3) << 2];
        if (col.qx == qx && col.qz == qz) return col;
        int x = qx << 2, z = qz << 2;
        TamrielMap.sample(x, z, col.sample);
        // domain-warp the region lookup so borders are organic, not the map's cell grid
        int h = BiomeNoise.hash(qx, qz, 7);
        int wx = (int) Math.round(BiomeNoise.fbm2(x / 160.0, z / 160.0, 101) * 36 + ((h & 15) - 7.5));
        int wz = (int) Math.round(BiomeNoise.fbm2(x / 160.0, z / 160.0, 202) * 36 + (((h >> 4) & 15) - 7.5));
        if (forced != null) {
            col.palette = forced;
            col.regionId = forced.key;
        } else {
            Region region = TamrielMap.region(x + wx, z + wz);
            if (region == null) region = Regions.fallback();
            col.palette = palettes.of(region);
            col.regionId = region.id();
        }
        col.noise = BiomeNoise.fbm2(x / 320.0, z / 320.0, 303);
        col.jitter = (int) Math.round(BiomeNoise.value2(x / 48.0, z / 48.0, 404) * 10 + ((h >> 8) & 7) - 3.5);
        col.qx = qx;
        col.qz = qz;
        return col;
    }

    private Context context(Column col, Palette p, int y, Climate.TargetPoint tp) {
        MapSample s = col.sample;
        float depth = Climate.unquantizeCoord(tp.depth());
        float c = Climate.unquantizeCoord(tp.continentalness());
        float erosion = Climate.unquantizeCoord(tp.erosion());
        float weird = Climate.unquantizeCoord(tp.weirdness());
        if (depth > 0.2f) return y < 0 || depth > 1.0f ? Context.DEEP_CAVE : Context.CAVE;
        // the surface height of this column, from the router's depth (depth ~ 0 at the surface, 1/128 per block)
        float h = y + depth * 128f;
        boolean sea = s.coastDistance < 0 || c < -0.19f;
        if (sea && h < SEA - 1) return c < -0.455f || h < 36 ? Context.DEEP_OCEAN : Context.OCEAN;
        if (h < SEA + 4) {
            if (s.lake > 0.45f) return Context.LAKE;
            if (s.river > 0.45f || (Math.abs(weird) < 0.05f && c > -0.11f && h < SEA + 1)) return Context.RIVER;
            if ((s.coastDistance >= 0 && s.coastDistance < 12 + (col.jitter & 7)) || (c >= -0.19f && c < -0.11f)) {
                return Context.BEACH;
            }
            if (h < SEA + 3 && erosion > 0.45f) return Context.MARSH;
        }
        int hj = (int) h + col.jitter;
        if (hj >= p.summitY) return Context.SUMMIT;
        if (hj >= p.peakY) return Context.PEAK;
        if (hj >= p.uplandY || (s.ridge > 0.6f && hj >= p.uplandY - 25)) return Context.UPLAND;
        return Context.LOWLAND;
    }

    private Holder<Biome> pick(Column col, Palette palette, Context ctx, int qx, int qy, int qz, Climate.TargetPoint tp) {
        double v;
        if (ctx == Context.CAVE || ctx == Context.DEEP_CAVE) {
            v = BiomeNoise.cdf(BiomeNoise.value3(qx / 24.0, qy / 12.0, qz / 24.0, 505) / 0.33);
        } else {
            v = climate(col, tp);
        }
        Holder<Biome> b = palette.get(ctx).pick(v);
        if (b != null) return b;
        // "@surface": the biome of the land above this cave
        return surfaceBiome(col, palette, tp);
    }

    private double climate(Column col, Climate.TargetPoint tp) {
        double t = Climate.unquantizeCoord(tp.temperature());
        double hum = Climate.unquantizeCoord(tp.humidity());
        // the mix is roughly normal with a standard deviation of ~0.22 (measured with /skybiome census): spread it
        // over [0, 1) so each choice gets its weight's share of the land
        return BiomeNoise.cdf((0.55 * hum + 0.3 * t + 0.45 * col.noise) / 0.22);
    }

    private Holder<Biome> surfaceBiome(Column col, Palette p, Climate.TargetPoint tp) {
        float e = col.sample.elevation;
        Context ctx = e >= p.peakY ? Context.PEAK : e >= p.uplandY ? Context.UPLAND : Context.LOWLAND;
        Holder<Biome> b = p.get(ctx).pick(climate(col, tp));
        return b != null ? b : p.get(Context.LOWLAND).pick(0.5);
    }

    private Holder<Biome> gridBiome(int qx, int qz) {
        List<Holder<Biome>> all = palettes.catalogue;
        int cx = Math.floorDiv(qx << 2, GRID), cz = Math.floorDiv(qz << 2, GRID);
        return all.get(Math.floorMod(cx + cz * 11, all.size()));
    }

    // ------------------------------------------------------------------------------------------ debugging
    /** What the source picks at a block position, for /skybiome and the F3 screen. */
    public String describe(int x, int y, int z, Climate.Sampler sampler) {
        int qx = x >> 2, qy = y >> 2, qz = z >> 2;
        if (grid) return "grid test: " + key(gridBiome(qx, qz));
        Column col = column(qx, qz);
        Climate.TargetPoint tp = sampler.sample(qx, qy, qz);
        Context ctx = context(col, col.palette, qy << 2, tp);
        MapSample s = col.sample;
        return String.format(java.util.Locale.ROOT,
                "region %s, palette %s, context %s -> %s | map elev %.0f river %.2f lake %.2f ridge %.2f coast %.0f | depth %.2f cont %.2f",
                col.regionId, col.palette.key, ctx.key, key(pick(col, col.palette, ctx, qx, qy, qz, tp)), s.elevation, s.river,
                s.lake, s.ridge, s.coastDistance, Climate.unquantizeCoord(tp.depth()),
                Climate.unquantizeCoord(tp.continentalness()));
    }

    private static String key(Holder<Biome> h) {
        return h.unwrapKey().map(k -> k.location().toString()).orElse("?");
    }

    @Override
    public void addDebugInfo(List<String> info, BlockPos pos, Climate.Sampler sampler) {
        Column col = column(pos.getX() >> 2, pos.getZ() >> 2);
        Climate.TargetPoint tp = sampler.sample(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2);
        info.add("Tamriel: " + col.regionId + " [" + col.palette.key + "] " + context(col, col.palette, pos.getY() & ~3, tp).key);
    }

    /**
     * Dev census: the surface biomes a palette would produce over a square (surface found from the router's depth,
     * no chunks generated). Returns biome id -> count, most common first.
     */
    public java.util.Map<String, Integer> census(String paletteKey, int cx, int cz, int radius, int step, Climate.Sampler sampler) {
        Palette p = palettes.byKey(paletteKey);
        if (p == null) return java.util.Map.of();
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (int x = cx - radius; x <= cx + radius; x += step) {
            for (int z = cz - radius; z <= cz + radius; z += step) {
                int qx = x >> 2, qz = z >> 2;
                Column col = column(qx, qz);
                int qy = 320 >> 2;
                Climate.TargetPoint tp = sampler.sample(qx, qy, qz);
                // walk down to the surface (depth crosses zero)
                while (qy > -16 && Climate.unquantizeCoord(tp.depth()) < 0f) {
                    qy -= 2;
                    tp = sampler.sample(qx, qy, qz);
                }
                Context ctx = context(col, p, qy << 2, tp);
                counts.merge(ctx.key + " " + key(pick(col, p, ctx, qx, qy, qz, tp)), 1, Integer::sum);
            }
        }
        java.util.Map<String, Integer> sorted = new java.util.LinkedHashMap<>();
        counts.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    public Palettes.Compiled palettes() {
        return palettes;
    }
}
