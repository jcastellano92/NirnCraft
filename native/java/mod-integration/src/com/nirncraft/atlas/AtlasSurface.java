package com.nirncraft.atlas;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import net.minecraft.world.level.levelgen.material.rule.SequenceRule;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The surface blocks of far columns as chunk generation paints them: the overworld's own material rule (NirnCraft's
 * per-biome presets: grass, podzol, coarse dirt, sand, gravel, snow caps, rock bands, ash, scree on steep north and
 * east faces ...) evaluated per column at its exact top block, with the world's seeded surface noises, its biomes
 * (the generator's biome source through the seeded BiomeManager zoom, as chunks store them) and the real surface
 * depth; then freeze_top_layer's snow and ice where the biome is cold enough there. The top block and the one under it
 * go into the tile (AtlasTileCodec v3), so single and multiplayer far fields wear the same blocks.
 *
 * <p>Vanilla only builds its rule context for whole chunks; it is driven here through the same context class,
 * constructed and stepped reflectively (package-private in 26.3). If that ever fails, {@link #forServer} returns null
 * and the far field keeps atlas's raster dressing.</p>
 *
 * <p>Exact at LOD 0, where a far column is one block (steepness from the neighbouring tops, chunk-clamped like
 * vanilla's gradient); a coarser column shows the block at its centre, its steepness from its neighbours' slope. What
 * the rules cannot know is not here: the rivers and lakes MapWaterFeature fills later (their beds keep the dry top, as
 * in the game), roads and towns repainting their own ground, trees.</p>
 */
final class AtlasSurface {
    /** Column flags in the tile (ATLAS_COLUMN_*): snow lies on it; its water is frozen. */
    static final byte SNOW = 1, FROZEN = 2;
    private static final BlockState STONE = Blocks.STONE.defaultBlockState(), SNOW_LAYER = Blocks.SNOW.defaultBlockState();

    private final MaterialRule rule;
    private final RandomState random;
    private final MaterialSystem system;
    private final BiomeSource biomeSource;
    private final long zoomSeed;
    private final WorldGenerationContext genContext;
    private final int seaLevel;
    private final Constructor<?> ctor;
    private final MethodHandle updateXZ, updateY;
    private final ThreadLocal<Eval> eval;

    /** One thread's rule context and its compiled rule. */
    private final class Eval {
        final MaterialRuleContext ctx;
        final RuleEvaluator rule;
        final BiomeManager biomes;

        Eval() throws ReflectiveOperationException {
            BiomeResolver r = biomeSource.createResolver(random.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
            // the noise biome per quart, cached: neighbouring columns, the top and the block under it, and the snow
            // check share quarts (the climate sampling behind a quart is most of a column's cost)
            it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Holder<Biome>> quarts = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
            BiomeResolver cached = (qx, qy, qz) -> {
                long k = BlockPos.asLong(qx, qy, qz);
                Holder<Biome> h = quarts.get(k);
                if (h == null) {
                    if (quarts.size() > 1 << 18) quarts.clear();
                    h = r.getNoiseBiome(qx, qy, qz);
                    quarts.put(k, h);
                }
                return h;
            };
            biomes = new BiomeManager(cached, zoomSeed);
            Function<BlockPos, Holder<Biome>> getter = biomes::getBiome;
            ctx = (MaterialRuleContext) ctor.newInstance(system, random, new DensityVolume(1, 1, 1, 0, 0, 0),
                    random.samplersWithContext(SamplerContext.EMPTY_UNCACHED), getter, genContext, null);
            this.rule = AtlasSurface.this.rule.compile(ctx);
        }
    }

    private AtlasSurface(MaterialRule rule, RandomState random, BiomeSource biomes, long seed, WorldGenerationContext gen,
                          int seaLevel) throws ReflectiveOperationException {
        this.rule = rule;
        this.random = random;
        this.system = random.surfaceSystem();
        this.biomeSource = biomes;
        this.zoomSeed = BiomeManager.obfuscateSeed(seed);
        this.genContext = gen;
        this.seaLevel = seaLevel;
        Class<?> c = MaterialRuleContext.class;
        ctor = c.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Method xz = c.getDeclaredMethod("updateXZ", int.class, int.class, int.class, int.class);
        Method y = c.getDeclaredMethod("updateY", int.class, int.class, int.class, int.class);
        xz.setAccessible(true);
        y.setAccessible(true);
        updateXZ = MethodHandles.lookup().unreflect(xz);
        updateY = MethodHandles.lookup().unreflect(y);
        eval = ThreadLocal.withInitial(() -> {
            try {
                return new Eval();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        });
        eval.get();  // fail here, not in a tile
    }

    /** The overworld's surface painter, or null when it cannot be driven here (logged). */
    static AtlasSurface forServer(MinecraftServer server) {
        try {
            ServerLevel overworld = server.overworld();
            if (overworld == null) return null;
            ChunkGenerator gen = overworld.getChunkSource().getGenerator();
            if (!(gen instanceof NoiseBasedChunkGenerator noise)) return null;
            return forGenerator(noise, overworld.getChunkSource().randomState(), overworld.getSeed(), overworld);
        } catch (Throwable t) {
            com.nirncraft.NirnCraft.LOGGER.info("atlas: far surface blocks from the raster dressing ({})", t.toString());
            return null;
        }
    }

    /** The surface painter of a noise generator with its seeded RandomState (the harness builds one offline). */
    static AtlasSurface forGenerator(NoiseBasedChunkGenerator gen, RandomState rs, long seed,
                                      net.minecraft.world.level.LevelHeightAccessor level) throws ReflectiveOperationException {
        MaterialRule r = withoutOreVeins(gen.generatorSettings().value().materialRule().value());
        return new AtlasSurface(r, rs, gen.getBiomeSource(), seed, new WorldGenerationContext(gen, level), gen.getSeaLevel());
    }

    /** The rule without its ore-vein layers (they paint deep stone, never a surface, and sample 3D densities). */
    private static MaterialRule withoutOreVeins(MaterialRule r) {
        if (r instanceof SequenceRule seq) {
            List<MaterialRule> keep = new ArrayList<>();
            for (MaterialRule m : seq.sequence())
                if (!m.getClass().getSimpleName().contains("OreVein")) keep.add(m);
            return new SequenceRule(List.copyOf(keep));
        }
        return r;
    }

    /**
     * Tile (lod, tx, tz)'s surface blocks into out (its block table and per column top, the block under it and flags),
     * from the tile's surface (exact heights) and water (NaN = dry). Thread-safe; rows may run in parallel by calling
     * fillRows on disjoint row ranges with the same out (each row writes only its own columns; the table is locked).
     */
    void fillRows(int lod, int tx, int tz, float[] surface, float[] water, AtlasTileCodec.Surface out, int j0, int j1) {
        Eval e = eval.get();
        int n = AtlasNative.TILE, s = 1 << lod;
        int bx = tx * (n << lod), bz = tz * (n << lod);
        BlockState[] r = new BlockState[2];
        for (int j = j0; j < j1; j++)
            for (int i = 0; i < n; i++) {
                int c = j * n + i;
                if (lod > 0 && ((i | j) & 1) != 0) {
                    // a coarse column (2+ blocks) shares its 2 x 2 group's rule evaluation (the group's first column),
                    // unless it is under water where that one is dry or the other way round (shores stay right)
                    int c0 = (j & ~1) * n + (i & ~1);
                    if (wet(surface[c], water[c]) == wet(surface[c0], water[c0])) {
                        out.top[c] = out.top[c0];
                        out.under[c] = out.under[c0];
                        out.flags[c] = out.flags[c0];
                        continue;
                    }
                }
                int x = bx + i * s + (s >> 1), z = bz + j * s + (s >> 1);
                int gx, gz;
                if (lod == 0) {
                    // vanilla's gradient over the WORLD_SURFACE_WG heights, clamped to the chunk
                    int lx = x & 15, lz = z & 15;
                    gx = surfaceH(surface[j * n + Math.min(i + 1, i + 15 - lx)]) - surfaceH(surface[j * n + Math.max(i - 1, i - lx)]);
                    gz = surfaceH(surface[Math.min(j + 1, j + 15 - lz) * n + i]) - surfaceH(surface[Math.max(j - 1, j - lz) * n + i]);
                } else {
                    int i0 = Math.max(0, i - 1), i1 = Math.min(n - 1, i + 1), k0 = Math.max(0, j - 1), k1 = Math.min(n - 1, j + 1);
                    gx = Math.round((surfaceH(surface[j * n + i1]) - surfaceH(surface[j * n + i0])) * 2f / ((i1 - i0) * s));
                    gz = Math.round((surfaceH(surface[k1 * n + i]) - surfaceH(surface[k0 * n + i])) * 2f / ((k1 - k0) * s));
                }
                byte flags = column(e, x, z, surface[c], gx, gz, water[c], r);
                out.top[c] = out.index(r[0]);
                out.under[c] = out.index(r[1]);
                out.flags[c] = flags;
            }
    }

    /**
     * One column: r[0] the block seen on top (a snow layer where it snows there), r[1] the block under it; returns the
     * column flags. gx, gz: vanilla's surface gradient (WORLD_SURFACE_WG height differences across the column).
     */
    private byte column(Eval e, int x, int z, float surface, int gx, int gz, float water, BlockState[] r) {
        int top = (int) Math.ceil(surface) - 1;
        boolean sea = top < seaLevel - 1;  // the sea and the aquifers' water are there when the surface is painted
        int waterHeight = sea ? seaLevel : Integer.MIN_VALUE;
        BlockState topB, under;
        try {
            updateXZ.invoke(e.ctx, x, z, gx, gz);
            updateY.invoke(e.ctx, 1, 64, waterHeight, top);
            topB = e.rule.tryApply(x, top, z);
            updateY.invoke(e.ctx, 2, 63, waterHeight, top - 1);
            under = e.rule.tryApply(x, top - 1, z);
        } catch (Throwable t) {
            throw new IllegalStateException("atlas far surface: " + t, t);
        }
        if (topB == null) topB = STONE;
        if (under == null) under = STONE;
        byte flags = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        if (Float.isNaN(water) || water <= surface) {
            // freeze_top_layer: snow on the ground where the biome is cold enough at its height
            pos.set(x, top + 1, z);
            if (!sea && topB.isSolidRender() && !topB.is(Blocks.ICE) && !topB.is(Blocks.PACKED_ICE)
                    && e.biomes.getBiome(pos).value().coldEnoughToSnow(pos, seaLevel)) {
                under = topB;
                topB = SNOW_LAYER;
                flags |= SNOW;
            }
        } else {
            pos.set(x, (int) Math.ceil(water) - 1, z);
            if (e.biomes.getBiome(pos).value().coldEnoughToSnow(pos, seaLevel)) flags |= FROZEN;
        }
        r[0] = topB;
        r[1] = under;
        return flags;
    }

    /** Under the sea or a lake or river (the surface rules' wet branch, or ice instead of snow). */
    private boolean wet(float surface, float water) {
        return Math.ceil(surface) - 1 < seaLevel - 1 || !Float.isNaN(water) && water > surface;
    }

    /** The WORLD_SURFACE_WG height of tile column (i, j) as the surface pass sees it: the ground, or the sea over it. */
    private int surfaceH(float surface) {
        int top = (int) Math.ceil(surface) - 1;
        return Math.max(top + 1, top < seaLevel - 1 ? seaLevel : Integer.MIN_VALUE);
    }

    /** The registry name of a block, for the tile's block table. */
    static String name(BlockState s) {
        return BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
    }

    // ---- in-game check: the predicted surface against generated chunks -----------------------------------------

    /**
     * Compares predicted top blocks with the real ones of `count` columns in chunks the server has loaded around its
     * players (where the real ground is where the far field puts it: no roads, towns or structures on top), and logs the
     * agreement once. Server thread (it reads chunks). A cheap check for the in-game session.
     */
    static void selfCheck(MinecraftServer server, AtlasExactSource source, int count) {
        ServerLevel level = server.overworld();
        if (level == null || source == null || source.surface() == null || level.players().isEmpty()) return;
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(level.getGameTime());
        int checked = 0, same = 0, snowAgree = 0, snowReal = 0;
        Map<String, int[]> byBlock = new HashMap<>();
        var player = level.players().get(0);
        int px = player.getBlockX(), pz = player.getBlockZ();
        Eval e = source.surface().eval.get();
        BlockState[] r = new BlockState[2];
        for (int tries = 0; tries < count * 8 && checked < count; tries++) {
            int x = px + rnd.nextInt(-160, 161), z = pz + rnd.nextInt(-160, 161);
            if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) continue;
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, y, z);
            boolean snow = false;
            while (y > level.getMinY()) {
                BlockState b = level.getBlockState(p.setY(y));
                if (b.is(Blocks.SNOW)) snow = true;
                if (b.isAir() || b.canBeReplaced() || b.is(net.minecraft.tags.BlockTags.LEAVES) || b.is(net.minecraft.tags.BlockTags.LOGS)
                        || b.is(Blocks.SNOW) || !b.getFluidState().isEmpty()) {
                    y--;
                    continue;
                }
                break;
            }
            int ground = source.groundAt(x, z);
            if (y != ground) continue;  // a road, a town pad, a structure, a cave mouth: not what the rules painted
            BlockState real = level.getBlockState(p.setY(y));
            // vanilla's chunk-clamped gradient from the exact heights around the column
            AtlasSurface sf = source.surface();
            int lx = x & 15, lz = z & 15;
            int gx = sf.surfaceH((float) source.heightAt(lx == 15 ? x : x + 1, z)) - sf.surfaceH((float) source.heightAt(lx == 0 ? x : x - 1, z));
            int gz = sf.surfaceH((float) source.heightAt(x, lz == 15 ? z : z + 1)) - sf.surfaceH((float) source.heightAt(x, lz == 0 ? z : z - 1));
            byte f = sf.column(e, x, z, (float) source.heightAt(x, z), gx, gz, Float.NaN, r);
            boolean predSnow = (f & SNOW) != 0;
            String realName = name(real);
            boolean ok = name(predSnow ? r[1] : r[0]).equals(realName);
            checked++;
            if (ok) same++;
            if (snow) snowReal++;
            if (snow == predSnow) snowAgree++;
            int[] v = byBlock.computeIfAbsent(realName, k -> new int[2]);
            v[0]++;
            if (ok) v[1]++;
        }
        if (checked == 0) return;
        StringBuilder sb = new StringBuilder();
        byBlock.entrySet().stream().sorted((a, b) -> b.getValue()[0] - a.getValue()[0]).limit(6).forEach(en ->
                sb.append(' ').append(en.getKey().replace("minecraft:", "")).append(' ')
                        .append(Math.round(100.0 * en.getValue()[1] / en.getValue()[0])).append("% of ").append(en.getValue()[0]).append(';'));
        com.nirncraft.NirnCraft.LOGGER.info("atlas surface check: {} columns, top block matches {}% (snow cover agrees {}%, {} snowy);{}",
                checked, Math.round(100.0 * same / checked), Math.round(100.0 * snowAgree / checked), snowReal, sb);
    }
}
