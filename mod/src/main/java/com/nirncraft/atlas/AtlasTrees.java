package com.nirncraft.atlas;

import com.nirncraft.tamriel.biome.flora.FloraTreeFeature;
import com.nirncraft.worldgen.ConfiguredFeature;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The far field's trees where the near world grows them: each chunk's tree features run as chunk decoration runs
 * them (the same global feature indices and decoration seeds, so the same species and shapes from the same random
 * draws; NirnCraft's flora_scatter places them by world seed and position) into a capturing level over the exact
 * ground (AtlasCapture: the true surface block for the soil check, the map's water, the generator's biomes, the
 * world's road and site masks). The captured crowns and trunks become far-field structures (leaves in their biome's
 * foliage colour) for the finest LODs; the dressing's noise canopy steps aside there (ATLAS_COLUMN_NO_CANOPY).
 *
 * <p>What it cannot know: blocks earlier features of the same chunk placed (rocks, structures' pieces), so a tree the
 * real world refused next to a boulder may stand here. Chunks are cached (about 4 k).</p>
 */
final class AtlasTrees {
    /** The coarsest LOD that gets real grown trees (columns of 2^LOD blocks). */
    static final int LOD = 0;
    /** The coarsest LOD that gets trees at their real places as silhouettes (approximate); coarser keeps the canopy. */
    static final int APPROX_LOD = 2;
    /** -1, or LOD while a tree overlay runs: tile uploads at or under it ask the dressing for no canopy. */
    static volatile int activeLod = -1;
    /** ATLAS_COLUMN_NO_CANOPY (abi 0.13): the dressing grows no forest canopy on the column. */
    static final byte NO_CANOPY = 4;
    private static final int VEGETAL = 9;  // GenerationStep.Decoration.VEGETAL_DECORATION
    private static final int CACHE = 1024;  // a tile and its border (cropped shapes: a few kB each)
    private static final int BIRCH = 0x80A755, SPRUCE = 0x619961;  // vanilla's fixed leaf colours

    private final ServerLevel level;
    private final ChunkGenerator generator;
    private final long seed;
    private final AtlasExactSource source;
    private final AtlasSurface surface;
    /** The vegetal step's tree features by global index (ascending), and which biomes have each. */
    private final int[] index;
    private final PlacedFeature[] features;
    private final FloraTreeFeature.Config[] configs;
    private final Map<Holder<Biome>, long[]> byBiome = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Long, AtlasCapture.Shape> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, AtlasCapture.Shape> e) {
            return size() > CACHE;
        }
    };
    private static final AtlasCapture.Shape NONE = new AtlasCapture.Shape(0, 0, 1, 1);
    long chunks, trees, nanos;

    private AtlasTrees(ServerLevel level, AtlasExactSource source, List<PlacedFeature> all, java.util.function.ToIntFunction<PlacedFeature> idx) {
        this.level = level;
        this.generator = level.getChunkSource().getGenerator();
        this.seed = level.getSeed();
        this.source = source;
        this.surface = source.surface();
        List<Integer> ix = new ArrayList<>();
        for (PlacedFeature f : all)
            if (ConfiguredFeature.configOf(f.feature().value(), FloraTreeFeature.class) != null) ix.add(idx.applyAsInt(f));
        ix.sort(Integer::compare);
        index = ix.stream().mapToInt(Integer::intValue).toArray();
        features = new PlacedFeature[index.length];
        configs = new FloraTreeFeature.Config[index.length];
        for (int k = 0; k < index.length; k++) {
            features[k] = all.get(index[k]);
            configs[k] = ConfiguredFeature.configOf(features[k].feature().value(), FloraTreeFeature.class);
        }
    }

    /** A tree at its real place, drawn from numbers (approximate): trunk base (x, ground y, z), its silhouette. */
    interface Visitor {
        void tree(int x, int y, int z, com.nirncraft.tamriel.biome.flora.TreeSpecies.Silhouette s, int tint);
    }

    /**
     * Every tree whose trunk stands in [x0, x1) x [z0, z1): the same placements chunk decoration runs (flora_scatter is
     * a pure function of seed and place; the biome check and heightmap from this world), the species by a stable
     * roll per place (the real one rolls the chunk's random, so a place's species may differ), soil and water
     * checked; not grown. For coarse LODs, where a tree is a few columns.
     */
    void approximate(int x0, int z0, int x1, int z1, Visitor v) {
        AtlasCapture.Env env = new AtlasCapture.Env(this::groundBlock, this::waterTop, p -> surface.biome(p.getX(), p.getY(), p.getZ()), level);
        net.minecraft.world.level.WorldGenLevel l = AtlasCapture.reader(source::groundAt, seed, env);
        WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        List<BlockPos> cur = new ArrayList<>(), next = new ArrayList<>();
        for (int cz = z0 >> 4; cz <= (z1 - 1) >> 4; cz++)
            for (int cx = x0 >> 4; cx <= (x1 - 1) >> 4; cx++) {
                long[] run = new long[(features.length + 63) >> 6];
                for (int q = 0; q < 4; q++) {  // a coarse look at the chunk's biomes (corners' quarts)
                    int x = (cx << 4) + 2 + (q & 1) * 12, z = (cz << 4) + 2 + (q >> 1) * 12;
                    long[] has = byBiome.computeIfAbsent(surface.biome(x, source.groundAt(x, z), z), this::treesOf);
                    for (int w = 0; w < run.length; w++) run[w] |= has[w];
                }
                for (int k = 0; k < features.length; k++) {
                    if ((run[k >> 6] & 1L << (k & 63)) == 0) continue;
                    net.minecraft.world.level.levelgen.placement.PlacementContext ctx =
                            new net.minecraft.world.level.levelgen.placement.PlacementContext(l, generator, java.util.Optional.of(features[k]));
                    random.setFeatureSeed(seed ^ ((long) cx << 32 | (cz & 0xFFFFFFFFL)), index[k], VEGETAL);
                    cur.clear();
                    cur.add(new BlockPos(cx << 4, level.getMinY(), cz << 4));
                    try {
                        for (net.minecraft.world.level.levelgen.placement.PlacementModifier m : features[k].placement()) {
                            next.clear();
                            for (BlockPos p : cur) m.modify(ctx, random, p, next::add);
                            List<BlockPos> t = cur;
                            cur = next;
                            next = t;
                        }
                    } catch (RuntimeException e) {
                        continue;
                    }
                    for (BlockPos p : cur) {
                        int x = p.getX(), z = p.getZ();
                        if (x < x0 || x >= x1 || z < z0 || z >= z1) continue;
                        double roll = (hash(x, z) >>> 11) * 0x1.0p-53;
                        com.nirncraft.tamriel.biome.flora.TreeSpecies sp = FloraTreeFeature.speciesAt(configs[k], seed, x, z, roll);
                        if (sp == null) continue;
                        com.nirncraft.tamriel.biome.flora.TreeSpecies.Silhouette s = sp.silhouette();
                        int gy = p.getY() - 1;
                        if (waterTop(x, z) - gy > s.maxWater()) continue;
                        if (!FloraTreeFeature.soilFits(s.soil(), groundBlock(x, z))) continue;
                        if (!com.nirncraft.world.PlacementMask.clear(l, x, z, sp.crownReach())) continue;
                        int tint = s.leaves() == null || !AtlasMaterials.biomeTinted(s.leaves()) ? 0
                                : leafTint(s.leaves(), x, gy, z);
                        v.tree(x, gy, z, s, tint);
                    }
                }
            }
    }

    private long hash(int x, int z) {
        long h = seed ^ 0x7EE5L ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
        h = (h ^ (h >>> 31)) * 0xBF58476D1CE4E5B9L;
        return h ^ (h >>> 29);
    }

    private int leafTint(BlockState leaves, int x, int y, int z) {
        String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(leaves.getBlock()).getPath();
        return name.contains("birch") ? BIRCH : name.contains("spruce") ? SPRUCE
                : surface.biome(x, y, z).value().getFoliageColor() & 0xFFFFFF;
    }

    /** The trees of a single-player world's overworld, or null when its generator cannot be read (logged). */
    @SuppressWarnings("unchecked")
    static AtlasTrees forServer(MinecraftServer server, AtlasExactSource source) {
        try {
            ServerLevel overworld = server == null ? null : server.overworld();
            if (overworld == null || source == null || source.surface() == null) return null;
            Field f = ChunkGenerator.class.getDeclaredField("featuresPerStep");
            f.setAccessible(true);
            List<FeatureSorter.StepFeatureData> steps =
                    ((Supplier<List<FeatureSorter.StepFeatureData>>) f.get(overworld.getChunkSource().getGenerator())).get();
            if (steps.size() <= VEGETAL) return null;
            FeatureSorter.StepFeatureData step = steps.get(VEGETAL);
            AtlasTrees t = new AtlasTrees(overworld, source, step.features(), step.indexMapping());
            com.nirncraft.NirnCraft.LOGGER.info("atlas: real far trees up to LOD {} ({} tree features)", LOD, t.features.length);
            return t.features.length == 0 ? null : t;
        } catch (ReflectiveOperationException | RuntimeException e) {
            com.nirncraft.NirnCraft.LOGGER.info("atlas: far trees from the dressing's canopy ({})", e.toString());
            return null;
        }
    }

    /** The trees decorated in chunk (cx, cz) as a far silhouette (crowns may reach 16 blocks out), null when none. */
    AtlasCapture.Shape chunk(int cx, int cz) {
        long key = (long) cx << 32 | (cz & 0xFFFFFFFFL);
        synchronized (cache) {
            AtlasCapture.Shape s = cache.get(key);
            if (s != null) return s == NONE ? null : s;
        }
        long t0 = System.nanoTime();
        AtlasCapture.Shape s = decorate(cx, cz);
        synchronized (cache) {
            cache.put(key, s == null ? NONE : s);
            chunks++;
            nanos += System.nanoTime() - t0;
            if (chunks == 256 || chunks == 4096)
                com.nirncraft.NirnCraft.LOGGER.info("atlas far trees: {} ({} with trees)", stats(), trees);
        }
        return s;
    }

    private AtlasCapture.Shape decorate(int cx, int cz) {
        int x0 = cx << 4, z0 = cz << 4;
        // the tree features any biome around the chunk has (the placement's biome check sorts out the rest)
        long[] run = new long[(features.length + 63) >> 6];
        for (int qz = -1; qz <= 4; qz++)
            for (int qx = -1; qx <= 4; qx++) {
                int x = x0 + qx * 4 + 2, z = z0 + qz * 4 + 2;
                Holder<Biome> b = surface.biome(x, source.groundAt(x, z), z);
                long[] has = byBiome.computeIfAbsent(b, this::treesOf);
                for (int w = 0; w < run.length; w++) run[w] |= has[w];
            }
        boolean any = false;
        for (long w : run) any |= w != 0;
        if (!any) return null;
        AtlasCapture.Env env = new AtlasCapture.Env(this::groundBlock, this::waterTop, p -> surface.biome(p.getX(), p.getY(), p.getZ()), level);
        BoundingBox box = new BoundingBox(x0 - 16, level.getMinY(), z0 - 16, x0 + 31, level.getMaxY(), z0 + 31);
        AtlasCapture.Shape s = AtlasCapture.capture(box, source::groundAt, seed, env, l -> {
            WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(0L));
            long deco = random.setDecorationSeed(seed, x0, z0);
            FeaturePlacer placer = new FeaturePlacer(l, generator);
            BlockPos origin = new BlockPos(x0, level.getMinY(), z0);
            for (int k = 0; k < features.length; k++) {
                if ((run[k >> 6] & 1L << (k & 63)) == 0) continue;
                random.setFeatureSeed(deco, index[k], VEGETAL);
                try {
                    placer.placeWithBiomeCheck(features[k], random, origin);
                } catch (RuntimeException e) {
                    // one tree that cannot grow here (it reads something the capture does not have): the rest still do
                }
            }
        });
        if (s == null) return null;
        s = crop(s);
        tint(s);
        return s;
    }

    /** The shape cut down to the columns it fills. */
    private static AtlasCapture.Shape crop(AtlasCapture.Shape s) {
        int i0 = s.w, i1 = -1, j0 = s.d, j1 = -1;
        for (int c = 0; c < s.top.length; c++) {
            if (Float.isNaN(s.top[c])) continue;
            int i = c % s.w, j = c / s.w;
            i0 = Math.min(i0, i);
            i1 = Math.max(i1, i);
            j0 = Math.min(j0, j);
            j1 = Math.max(j1, j);
        }
        if (i1 < 0) return s;
        AtlasCapture.Shape o = new AtlasCapture.Shape(s.x0 + i0, s.z0 + j0, i1 - i0 + 1, j1 - j0 + 1);
        for (int j = j0; j <= j1; j++)
            for (int i = i0; i <= i1; i++) {
                int a = j * s.w + i, b = (j - j0) * o.w + (i - i0);
                o.top[b] = s.top[a];
                o.base[b] = s.base[a];
                o.wall[b] = s.wall[a];
                o.roof[b] = s.roof[a];
            }
        o.rise = s.rise;
        return o;
    }

    private long[] treesOf(Holder<Biome> b) {
        long[] has = new long[(features.length + 63) >> 6];
        List<HolderSet<PlacedFeature>> steps = b.value().getGenerationSettings().features();
        if (steps.size() <= VEGETAL) return has;
        HolderSet<PlacedFeature> veg = steps.get(VEGETAL);
        for (int k = 0; k < features.length; k++) {
            for (Holder<PlacedFeature> h : veg)
                if (h.value() == features[k]) {
                    has[k >> 6] |= 1L << (k & 63);
                    break;
                }
        }
        return has;
    }

    /** Leaves that take the biome's foliage colour wear it (birch and spruce their fixed ones). */
    private void tint(AtlasCapture.Shape s) {
        for (int c = 0; c < s.top.length; c++) {
            BlockState r = s.roof[c];
            if (r == null || !AtlasMaterials.biomeTinted(r)) continue;
            if (s.tint == null) s.tint = new int[s.top.length];
            String name = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(r.getBlock()).getPath();
            int x = s.x0 + c % s.w, z = s.z0 + c / s.w;
            s.tint[c] = name.contains("birch") ? BIRCH : name.contains("spruce") ? SPRUCE
                    : surface.biome(x, (int) s.top[c], z).value().getFoliageColor() & 0xFFFFFF;
        }
        trees++;
    }

    /** The ground's top block at its column, as the material rules paint it (before snow: the trees come first). */
    private BlockState groundBlock(int x, int z) {
        return surface.groundBlock(x, z, (float) source.heightAt(x, z));
    }

    private int waterTop(int x, int z) {
        float w = source.waterLevelAt(x, z);
        return Float.isNaN(w) ? Integer.MIN_VALUE : (int) Math.ceil(w) - 1;
    }

    String stats() {
        synchronized (cache) {
            return String.format("%d chunks, %.2f ms each", chunks, chunks == 0 ? 0.0 : nanos / 1e6 / chunks);
        }
    }
}
