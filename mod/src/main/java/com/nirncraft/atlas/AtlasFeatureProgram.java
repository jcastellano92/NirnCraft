package com.nirncraft.atlas;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.valueproviders.BiasedToBottomInt;
import net.minecraft.util.valueproviders.ClampedInt;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.TrapezoidInt;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.InsideWorldBoundsPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.NotPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.StateTestingPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.TrueBlockPredicate;
import net.minecraft.world.level.levelgen.blockpredicates.WouldSurvivePredicate;
import net.minecraft.world.level.levelgen.feature.BlockReplacement;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.heightproviders.BiasedToBottomHeight;
import net.minecraft.world.level.levelgen.heightproviders.ConstantHeight;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.heightproviders.TrapezoidHeight;
import net.minecraft.world.level.levelgen.heightproviders.UniformHeight;
import net.minecraft.world.level.levelgen.heightproviders.VeryBiasedToBottomHeight;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.BlockPredicateFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.EnvironmentScanPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.HeightmapPlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.OffsetPlacement;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.placement.SurfaceRelativeThresholdFilter;
import net.minecraft.world.level.levelgen.structure.templatesystem.AlwaysTrueTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RandomBlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RandomBlockStateMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.material.Fluids;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * The world's placed features compiled for the GPU decoration (native/shaders/atlas_features.slang,
 * docs/ATLAS-CLIENT-GENERATION.md stage 4): Java's decoration order (steps, features by their global index), every
 * placement modifier and feature configuration as words, the biome lists, the block table the kernels test (flags
 * from the states themselves, bit sets for rule tests and block predicates evaluated on every state), the seeds.
 * Nothing is re-authored: the objects the server decorates with are read (by reflection where their fields are
 * private). A placed feature the kernels cannot run yet is left out of the dispatch list and named in
 * {@link #unsupported}; the decoration then runs only the supported ones on both sides when compared.
 *
 * <p>Block ids: the terrain program's ids first (AtlasGenProgram.blocks), then every state a feature can place.</p>
 */
final class AtlasFeatureProgram {
    static final int MAGIC = 0x41454641;  // "AFEA"
    static final int VERSION = 1;
    // header (atlas_features.slang H_*)
    static final int H_VERSION = 1, H_NBLOCKS = 2, H_BLOCKS = 3, H_NBIOMES = 4, H_BIOMEWORDS = 5, H_SEED = 6, H_ZOOM = 8,
            H_SEA = 10, H_SIN = 11, H_FIELDS = 12, H_PREDS = 13, H_SURVIVE = 14, H_SURVIVE_N = 15, H_CONSTS = 16,
            H_GENMINY = 17, H_GENHEIGHT = 18, H_DISPATCH = 19, H_NDISPATCH = 20, H_ALCH = 21, H_TREES = 22, H_BIOMETAB = 23, HEADER = 32;
    static final int BLK_W = 16;
    static final int F2_LIQUID = 1, F2_SNOWY = 2, F2_LIQUID_PROP = 4;
    static final int T_AX = 0, T_WL = 3, T_DIST = 5, T_SNOWY = 12;
    static final int B_AIR = 1, B_WATER = 1 << 1, B_LAVA = 1 << 2, B_SOURCE = 1 << 3, B_MOTION = 1 << 4, B_LEAVES_TAG = 1 << 5,
            B_LEAVES_BLOCK = 1 << 6, B_LOGS = 1 << 7, B_SOFT = 1 << 8, B_SUBSTRATE = 1 << 9, B_SAND = 1 << 10,
            B_CANOPY = 1 << 11, B_STURDY_UP = 1 << 12, B_SOLID = 1 << 13, B_GROUND = 1 << 14, B_REPLACEABLE = 1 << 15,
            B_FLUID = 1 << 16, B_WATERLOGGABLE = 1 << 17, B_SNOW_LAYER = 1 << 18, B_SOIL_DIRT = 1 << 19,
            B_SOIL_SAND = 1 << 20, B_SOIL_STONE = 1 << 21, B_SOIL_ASH = 1 << 22, B_SOIL_SNOW = 1 << 23,
            B_BAMBOO_SOIL = 1 << 24, B_DOUBLE_PLANT = 1 << 25, B_SURVIVE_SPECIAL = 1 << 28, B_FLUID_WATER_TAG = 1 << 29, B_MOTION_NL = 1 << 30, B_WATER_BLOCK = 1 << 31;
    static final int R_W = 8;
    static final int FT_NONE = 0, FT_ORE = 1, FT_COVER = 2, FT_ROCK = 3, FT_TREE = 4, FT_FREEZE = 5, FT_DISK = 6, FT_SPRING = 7, FT_LAKE = 8;
    static final int MOD_W = 16;
    static final int M_COUNT = 1, M_IN_SQUARE = 2, M_HEIGHTMAP = 3, M_BIOME = 4, M_RARITY = 5, M_HEIGHT_RANGE = 6,
            M_FLORA_SCATTER = 7, M_FLORA_DENSITY = 8, M_MASK = 9, M_SURF_REL = 10, M_ENV_SCAN = 11, M_OFFSET = 12,
            M_BLOCK_PRED = 13;
    static final int IP_CONST = 0, IP_UNIFORM = 1, IP_BIASED = 2, IP_TRAPEZOID = 3, IP_CLAMPED_UNIFORM = 4;
    static final int HP_CONST = 0, HP_UNIFORM = 1, HP_TRAPEZOID = 2, HP_BIASED = 3, HP_VERY_BIASED = 4;
    static final int P_END = 0, P_SET = 1, P_SURVIVE = 2, P_BOUNDS = 3, P_TRUE = 4, P_ALL = 5, P_ANY = 6, P_NOT = 7;
    static final int SK_BELOW = 0, SK_CANE = 1, SK_CACTUS = 2, SK_UNSUPPORTED = 3;
    static final int RT_TRUE = 0, RT_SET = 1, RT_RANDOM_SET = 2;
    static final int FIELD_W = 8;

    /** The program. */
    final int[] words;
    /** Block ids: the terrain's, then the features'. */
    final List<BlockState> blocks;
    final Map<BlockState, Integer> blockIds;
    /** The placed features dispatched, in order (step, global index), with their record offsets. */
    final List<PlacedFeature> dispatched = new ArrayList<>();
    final IntArrayList records = new IntArrayList();
    /** Placed features (by key) left out, with why. */
    final Map<String, String> unsupported = new LinkedHashMap<>();
    final Set<PlacedFeature> supported = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    final Map<PlacedFeature, String> keys = new IdentityHashMap<>();
    private final Compiler compiler;

    /** A column's alchemy class for the pages (variant 0 dry and low, 1 dry above y 110, 2 by water), 255 unknown. */
    int alchemyClass(int x, int z, int variant) {
        if (!compiler.alchDone) return 255;
        Integer known;
        synchronized (compiler) {
            int before = compiler.alchLists.size();
            int c = compiler.alchClass(x, z, variant);
            if (compiler.alchLists.size() != before) {
                // a list the scan did not meet: not in the uploaded program
                compiler.alchLists.removeLast();
                compiler.alchIds.values().removeIf(v -> v >= before);
                return 255;
            }
            known = c;
        }
        return known;
    }

    private static Object temperatureNoise;

    /** Biome's temperature noise at a column, as getHeightAdjustedTemperature reads it (x / 8, z / 8, times 8). */
    static float temperatureNoise(int x, int z) {
        try {
            if (temperatureNoise == null) {
                Field f = Biome.class.getDeclaredField("TEMPERATURE_NOISE");
                f.setAccessible(true);
                temperatureNoise = f.get(null);
            }
            return ((net.minecraft.world.level.levelgen.synth.Noise) temperatureNoise).get(x / 8.0F, z / 8.0F) * 8.0F;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Whether the frozen temperature modifier turns a column's temperature to 0.2. */
    static boolean frozenColumn(int x, int z) {
        return Biome.TemperatureModifier.FROZEN.modifyTemperature(new BlockPos(x, 64, z), 1.0F) == 0.2F;
    }

    private AtlasFeatureProgram(Compiler c) {
        compiler = c;
        words = c.finish();
        blocks = c.blocks;
        blockIds = c.blockIds;
        dispatched.addAll(c.dispatched);
        records.addAll(c.recordOffsets);
        unsupported.putAll(c.unsupported);
        supported.addAll(c.dispatched);
        keys.putAll(c.keys);
    }

    /**
     * Compiles the decoration of {@code generator} (the overworld's) over the terrain program's blocks and biomes.
     * only: the placed features to include (by key; null: every supported one).
     */
    static AtlasFeatureProgram compile(ChunkGenerator generator, ServerLevel level, AtlasGenProgram terrain, long seed, long zoomSeed,
                                       Predicate<String> only) throws ReflectiveOperationException {
        Compiler c = new Compiler(generator, level, terrain, seed, zoomSeed, only);
        c.run();
        return new AtlasFeatureProgram(c);
    }

    // =================================================================================================================

    private static final class Unsupported extends Exception {
        Unsupported(String why) {
            super(why, null, false, false);
        }
    }

    private static final class Compiler {
        final ChunkGenerator generator;
        final ServerLevel level;
        final AtlasGenProgram terrain;
        final long seed, zoomSeed;
        final Predicate<String> only;
        final WorldGenerationContext context;
        final List<BlockState> blocks = new ArrayList<>();
        final Map<BlockState, Integer> blockIds = new IdentityHashMap<>();
        final IntArrayList out = new IntArrayList();
        /** Bit sets over the final block table, filled once it is complete: (word offset, the test). */
        final List<Map.Entry<Integer, Predicate<BlockState>>> sets = new ArrayList<>();
        final List<int[]> fields = new ArrayList<>();
        final Map<Object, Integer> fieldIds = new HashMap<>();
        final List<BlockState> survivors = new ArrayList<>();
        final Map<BlockState, Integer> survivorIds = new IdentityHashMap<>();
        final List<PlacedFeature> dispatched = new ArrayList<>();
        final IntArrayList recordOffsets = new IntArrayList();
        final Map<String, String> unsupported = new LinkedHashMap<>();
        final Map<PlacedFeature, String> keys = new IdentityHashMap<>();
        final int nBiomes, biomeWords;

        Compiler(ChunkGenerator generator, ServerLevel level, AtlasGenProgram terrain, long seed, long zoomSeed, Predicate<String> only) {
            this.generator = generator;
            this.level = level;
            this.terrain = terrain;
            this.seed = seed;
            this.zoomSeed = zoomSeed;
            this.only = only;
            this.context = new WorldGenerationContext(generator, level);
            for (BlockState s : terrain.blocks) id(s);
            nBiomes = terrain.biomes.size();
            biomeWords = (nBiomes + 31) / 32;
            for (int i = 0; i < HEADER; i++) out.add(0);
        }

        int id(BlockState s) {
            Integer i = blockIds.get(s);
            if (i != null) return i;
            blocks.add(s);
            blockIds.put(s, blocks.size() - 1);
            return blocks.size() - 1;
        }

        int here() {
            return out.size();
        }

        /** A bit set over the block table (filled at the end), its word offset. */
        int set(Predicate<BlockState> test) {
            int at = here();
            sets.add(Map.entry(at, test));
            for (int i = 0; i < 2048; i++) out.add(0);  // up to 65536 ids
            return at;
        }

        @SuppressWarnings("unchecked")
        void run() throws ReflectiveOperationException {
            Field f = ChunkGenerator.class.getDeclaredField("featuresPerStep");
            f.setAccessible(true);
            List<FeatureSorter.StepFeatureData> steps = ((Supplier<List<FeatureSorter.StepFeatureData>>) f.get(generator)).get();
            var registry = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.PLACED_FEATURE);
            List<int[]> dispatch = new ArrayList<>();
            for (int step = 0; step < steps.size(); step++) {
                FeatureSorter.StepFeatureData data = steps.get(step);
                for (int gi = 0; gi < data.features().size(); gi++) {
                    PlacedFeature pf = data.features().get(gi);
                    String key = registry.getResourceKey(pf).map(k -> k.identifier().toString()).orElse("?#" + step + ":" + gi);
                    keys.put(pf, key);
                    // the biomes listing it at this step, and anywhere (BiomeFilter: hasFeature)
                    int[] hereBits = new int[biomeWords], anyBits = new int[biomeWords];
                    boolean listed = false;
                    for (int b = 0; b < nBiomes; b++) {
                        Holder<Biome> h = terrain.biomes.get(b);
                        var settings = generator.getBiomeGenerationSettings(h);
                        List<HolderSet<PlacedFeature>> fs = h.value().getGenerationSettings().features();
                        if (step < fs.size())
                            for (Holder<PlacedFeature> p : fs.get(step))
                                if (p.value() == pf) {
                                    hereBits[b >> 5] |= 1 << (b & 31);
                                    listed = true;
                                }
                        if (settings.hasFeature(pf)) anyBits[b >> 5] |= 1 << (b & 31);
                    }
                    if (!listed) continue;
                    if (only != null && !only.test(key)) continue;
                    int rec;
                    int mark = out.size();
                    int setMark = sets.size();
                    try {
                        rec = record(pf, step, gi, hereBits, anyBits);
                    } catch (Unsupported e) {
                        out.size(mark);
                        while (sets.size() > setMark) sets.removeLast();
                        unsupported.put(key, e.getMessage());
                        continue;
                    }
                    dispatched.add(pf);
                    recordOffsets.add(rec);
                    dispatch.add(new int[]{rec, step, gi});
                }
            }
            int d = here();
            for (int[] e : dispatch) out.add(e[0]);
            out.set(H_DISPATCH, d);
            out.set(H_NDISPATCH, dispatch.size());
        }

        int record(PlacedFeature pf, int step, int gi, int[] hereBits, int[] anyBits) throws Unsupported, ReflectiveOperationException {
            // the modifiers first (their words), then the feature's configuration, then the record
            List<int[]> mods = new ArrayList<>();
            for (PlacementModifier m : pf.placement()) mods.add(modifier(m));
            int[] typeCfg = feature(pf.feature().value());
            int modsAt = here();
            for (int[] m : mods) {
                for (int i = 0; i < MOD_W; i++) out.add(i < m.length ? m[i] : 0);
            }
            int here = here();
            for (int w : hereBits) out.add(w);
            int any = here();
            for (int w : anyBits) out.add(w);
            int rec = here();
            out.add(typeCfg[0]);
            out.add(step);
            out.add(gi);
            out.add(mods.size());
            out.add(modsAt);
            out.add(typeCfg[1]);
            out.add(here);
            out.add(any);
            return rec;
        }

        // ---- placement modifiers ----------------------------------------------------------------------------------
        int[] modifier(PlacementModifier m) throws Unsupported, ReflectiveOperationException {
            if (m instanceof CountPlacement c) return concat(new int[]{M_COUNT}, intProvider(c.count()));
            if (m instanceof InSquarePlacement) return new int[]{M_IN_SQUARE};
            if (m instanceof HeightmapPlacement h) return new int[]{M_HEIGHTMAP, h.heightmap().ordinal()};
            if (m instanceof BiomeFilter) return new int[]{M_BIOME};
            if (m instanceof RarityFilter r) return new int[]{M_RARITY, Float.floatToRawIntBits(1.0F / r.chance())};
            if (m instanceof HeightRangePlacement h) return concat(new int[]{M_HEIGHT_RANGE}, heightProvider(h.height()));
            if (m instanceof SurfaceRelativeThresholdFilter s)
                return new int[]{M_SURF_REL, s.heightmap().ordinal(), s.minInclusive(), s.maxInclusive()};
            if (m instanceof OffsetPlacement o)
                return concat(new int[]{M_OFFSET}, intProvider(o.x()), intProvider(o.y()), intProvider(o.z()));
            if (m instanceof EnvironmentScanPlacement e) {
                Direction d = e.directionOfSearch();
                int target = predicate(e.targetCondition()), allowed = predicate(e.allowedSearchCondition());
                return new int[]{M_ENV_SCAN, d.getStepX(), d.getStepY(), d.getStepZ(), target, allowed, e.maxSteps()};
            }
            if (m instanceof BlockPredicateFilter b) return new int[]{M_BLOCK_PRED, predicate(b.predicate())};
            String cls = m.getClass().getName();
            if (cls.endsWith("flora.ScatterPlacement")) {
                return new int[]{M_FLORA_SCATTER, (int) get(m, "spacing"), Float.floatToRawIntBits((float) get(m, "minDistance")),
                        0, Float.floatToRawIntBits((float) get(m, "density")), Float.floatToRawIntBits((float) get(m, "floor")),
                        field(get(m, "field"))};
            }
            if (cls.endsWith("flora.DensityFilter")) {
                return new int[]{M_FLORA_DENSITY, Float.floatToRawIntBits((float) get(m, "density")),
                        Float.floatToRawIntBits((float) get(m, "floor")), field(get(m, "field"))};
            }
            if (cls.endsWith("flora.MaskFilter")) return new int[]{M_MASK, (int) get(m, "radius")};
            throw new Unsupported("placement " + m.getClass().getSimpleName());
        }

        int[] intProvider(IntProvider p) throws Unsupported, ReflectiveOperationException {
            if (p instanceof ConstantInt c) return new int[]{IP_CONST, c.value(), 0, 0};
            if (p instanceof UniformInt u) return new int[]{IP_UNIFORM, u.minInclusive(), u.maxInclusive(), 0};
            if (p instanceof BiasedToBottomInt b) return new int[]{IP_BIASED, b.minInclusive(), b.maxInclusive(), 0};
            if (p instanceof TrapezoidInt t) return new int[]{IP_TRAPEZOID, t.minInclusive(), t.maxInclusive(), (int) get(t, "plateau")};
            if (p instanceof ClampedInt c && get(c, "source") instanceof UniformInt u) {
                int lo = c.minInclusive(), hi = c.maxInclusive();
                return new int[]{IP_CLAMPED_UNIFORM, u.minInclusive(), u.maxInclusive(), (lo & 0xFFFF) | (hi << 16)};
            }
            throw new Unsupported("int provider " + p.getClass().getSimpleName());
        }

        int[] heightProvider(HeightProvider h) throws Unsupported, ReflectiveOperationException {
            if (h instanceof ConstantHeight c) return new int[]{HP_CONST, ((VerticalAnchor) get(c, "value")).resolveY(context), 0, 0};
            if (h instanceof UniformHeight u) return new int[]{HP_UNIFORM, anchor(u, "minInclusive"), anchor(u, "maxInclusive"), 0};
            if (h instanceof TrapezoidHeight t) return new int[]{HP_TRAPEZOID, anchor(t, "minInclusive"), anchor(t, "maxInclusive"), (int) get(t, "plateau")};
            if (h instanceof BiasedToBottomHeight b) return new int[]{HP_BIASED, anchor(b, "minInclusive"), anchor(b, "maxInclusive"), (int) get(b, "inner")};
            if (h instanceof VeryBiasedToBottomHeight b) return new int[]{HP_VERY_BIASED, anchor(b, "minInclusive"), anchor(b, "maxInclusive"), (int) get(b, "inner")};
            throw new Unsupported("height provider " + h.getClass().getSimpleName());
        }

        int anchor(Object o, String field) throws ReflectiveOperationException {
            return ((VerticalAnchor) get(o, field)).resolveY(context);
        }

        /** A FloraField (record): salt, scale, coverage, softness, kind, mode, then pad. */
        int field(Object f) throws ReflectiveOperationException {
            Integer known = fieldIds.get(f);
            if (known != null) return known;
            Class<?> c = f.getClass();
            int salt = (int) c.getMethod("salt").invoke(f);
            float scale = (float) c.getMethod("scale").invoke(f), coverage = (float) c.getMethod("coverage").invoke(f),
                    softness = (float) c.getMethod("softness").invoke(f);
            int kind = ((Enum<?>) c.getMethod("kind").invoke(f)).ordinal(), mode = ((Enum<?>) c.getMethod("mode").invoke(f)).ordinal();
            fields.add(new int[]{salt, Float.floatToRawIntBits(scale), Float.floatToRawIntBits(coverage), Float.floatToRawIntBits(softness),
                    kind, mode, 0, 0});
            fieldIds.put(f, fields.size() - 1);
            return fields.size() - 1;
        }

        // ---- block predicates (postfix code) ----------------------------------------------------------------------
        int predicate(BlockPredicate p) throws Unsupported, ReflectiveOperationException {
            IntArrayList code = new IntArrayList();
            emit(p, code);
            code.add(P_END);
            for (int i = 0; i < 4; i++) code.add(0);
            int at = here();
            out.addAll(code);
            return at;
        }

        void emit(BlockPredicate p, IntArrayList code) throws Unsupported, ReflectiveOperationException {
            if (p instanceof TrueBlockPredicate) {
                op(code, P_TRUE, Vec3i.ZERO, 0);
            } else if (p instanceof StateTestingPredicate s) {
                Vec3i off = (Vec3i) get(s, StateTestingPredicate.class, "offset");
                Method t = StateTestingPredicate.class.getDeclaredMethod("test", BlockState.class);
                t.setAccessible(true);
                int set = set(st -> {
                    try {
                        return (boolean) t.invoke(s, st);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                });
                op(code, P_SET, off, set);
            } else if (p instanceof WouldSurvivePredicate w) {
                op(code, P_SURVIVE, (Vec3i) get(w, "offset"), survivor((BlockState) get(w, "state")));
            } else if (p instanceof InsideWorldBoundsPredicate b) {
                op(code, P_BOUNDS, (Vec3i) get(b, "offset"), 0);
            } else if (p instanceof NotPredicate n) {
                emit((BlockPredicate) get(n, "predicate"), code);
                op(code, P_NOT, Vec3i.ZERO, 0);
            } else if (p.getClass().getSuperclass().getSimpleName().equals("CombiningPredicate")) {
                @SuppressWarnings("unchecked")
                List<BlockPredicate> ps = (List<BlockPredicate>) get(p, p.getClass().getSuperclass(), "predicates");
                for (BlockPredicate q : ps) emit(q, code);
                op(code, p.getClass().getSimpleName().startsWith("All") ? P_ALL : P_ANY, Vec3i.ZERO, ps.size());
            } else {
                throw new Unsupported("block predicate " + p.getClass().getSimpleName());
            }
        }

        static void op(IntArrayList code, int op, Vec3i off, int arg) {
            code.add(op);
            code.add(off.getX());
            code.add(off.getY());
            code.add(off.getZ());
            code.add(arg);
        }

        /** A plant state in the survival table, its index. */
        int survivor(BlockState s) {
            Integer i = survivorIds.get(s);
            if (i != null) return i;
            id(s);
            survivors.add(s);
            survivorIds.put(s, survivors.size() - 1);
            return survivors.size() - 1;
        }

        // ---- features ---------------------------------------------------------------------------------------------
        /** {type, configuration offset}. */
        int[] feature(Feature f) throws Unsupported, ReflectiveOperationException {
            if (f.getClass() == OreFeature.class) {
                OreFeature o = (OreFeature) f;
                List<BlockReplacement> ts = o.targetStates();
                int[] rules = new int[ts.size() * 4];
                for (int i = 0; i < ts.size(); i++) {
                    BlockReplacement t = ts.get(i);
                    int[] r = ruleTest(t.target());
                    rules[i * 4] = r[0];
                    rules[i * 4 + 1] = r[1];
                    rules[i * 4 + 2] = r[2];
                    rules[i * 4 + 3] = id(t.state());
                }
                int at = here();
                out.add(o.size());
                out.add(Float.floatToRawIntBits(o.discardChanceOnAirExposure()));
                out.add(ts.size());
                for (int w : rules) out.add(w);
                if (o.size() > 64) throw new Unsupported("ore size " + o.size());
                return new int[]{FT_ORE, at};
            }
            Object cover = configOf(f, "com.nirncraft.tamriel.biome.flora.GroundCoverFeature");
            if (cover != null) return groundCover(cover);
            Object rock = configOf(f, "com.nirncraft.tamriel.biome.flora.RockFeature");
            if (rock != null) return rock(rock);
            Object tree = configOf(f, "com.nirncraft.tamriel.biome.flora.FloraTreeFeature");
            if (tree != null) return tree(tree);
            if (f instanceof net.minecraft.world.level.levelgen.feature.SnowAndFreezeFeature) return freeze();
            if (f instanceof net.minecraft.world.level.levelgen.feature.DiskFeature d) return disk(d);
            if (f instanceof net.minecraft.world.level.levelgen.feature.LakeFeature lk) {
                int fluid = stateProvider(lk.fluid().value()), barrier = stateProvider(lk.barrier().value());
                int a = predicate(lk.canPlaceFeature()), b = predicate(lk.canReplaceWithAirOrFluid()), c = predicate(lk.canReplaceWithBarrier());
                BlockState fl = lk.fluid().value() instanceof net.minecraft.world.level.levelgen.feature.stateproviders.SimpleStateProvider sp
                        ? sp.getState(null, null, BlockPos.ZERO) : null;
                if (fl == null) throw new Unsupported("lake fluid provider " + lk.fluid().value().getClass().getSimpleName());
                int at = here();
                out.add(fluid);
                out.add(barrier);
                out.add(a);
                out.add(b);
                out.add(c);
                out.add(id(Blocks.CAVE_AIR.defaultBlockState()));
                out.add(id(Blocks.ICE.defaultBlockState()));
                out.add(level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, ANCHOR) < 10 ? 1 : 0);
                out.add(fl.getFluidState().is(FluidTags.WATER) ? 1 : 0);
                return new int[]{FT_LAKE, at};
            }
            if (f instanceof net.minecraft.world.level.levelgen.feature.SpringFeature sp) {
                var valid = sp.validBlocks();
                int set = set(st -> st.is(valid));
                int at = here();
                out.add(id(sp.state().createLegacyBlock()));
                out.add(sp.requiresBlockBelow() ? 1 : 0);
                out.add(sp.rockCount());
                out.add(sp.holeCount());
                out.add(set);
                return new int[]{FT_SPRING, at};
            }
            throw new Unsupported("feature " + featureName(f));
        }

        String featureName(Feature f) {
            try {
                Object alg = get(f, "algorithm");
                return alg.getClass().getName();
            } catch (ReflectiveOperationException e) {
                return f.getClass().getName();
            }
        }

        /** The configuration of a mod feature bound to the algorithm class named, else null. */
        Object configOf(Feature f, String algorithm) throws ReflectiveOperationException {
            if (!f.getClass().getName().endsWith("ConfiguredFeature$Bound")) return null;
            Object alg = get(f, "algorithm");
            if (!alg.getClass().getName().equals(algorithm)) return null;
            return get(f, "config");
        }

        Object call(Object o, String method) throws ReflectiveOperationException {
            Method m = o.getClass().getMethod(method);
            m.setAccessible(true);
            return m.invoke(o);
        }

        /** GroundCoverFeature.Config: entries (state, upper half, weight, survivor), scale, patchiness, cluster, ... */
        int[] groundCover(Object c) throws Unsupported, ReflectiveOperationException {
            @SuppressWarnings("unchecked")
            List<Object> entries = (List<Object>) call(c, "entries");
            if (entries.size() > 16) throw new Unsupported("ground cover with more than 16 entries");
            int[] cluster = intProvider((IntProvider) call(c, "cluster"));
            int[] ents = new int[entries.size() * 4];
            for (int i = 0; i < entries.size(); i++) {
                BlockState st = (BlockState) call(entries.get(i), "state");
                int weight = (int) call(entries.get(i), "weight");
                int lower = id(st), upper = 0;
                if (st.getBlock() instanceof DoublePlantBlock && st.hasProperty(DoublePlantBlock.HALF)) {
                    BlockState lo = st.setValue(DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER);
                    BlockState up = st.setValue(DoublePlantBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER);
                    if (lo.hasProperty(BlockStateProperties.WATERLOGGED)) {
                        lo = lo.setValue(BlockStateProperties.WATERLOGGED, false);
                        up = up.setValue(BlockStateProperties.WATERLOGGED, false);
                    }
                    lower = id(lo);
                    upper = id(up);
                }
                ents[i * 4] = lower;
                ents[i * 4 + 1] = upper;
                ents[i * 4 + 2] = weight;
                ents[i * 4 + 3] = survivor(st);
            }
            alchemyClasses();
            int at = here();
            out.add(entries.size());
            out.add(Float.floatToRawIntBits((float) call(c, "scale")));
            out.add(Float.floatToRawIntBits((float) call(c, "patchiness")));
            for (int w : cluster) out.add(w);
            out.add((int) call(c, "spread"));
            out.add((int) call(c, "salt"));
            out.add((boolean) call(c, "nearWater") ? 1 : 0);
            out.add(0);
            for (int w : ents) out.add(w);
            return new int[]{FT_COVER, at};
        }

        /** RockFeature.Config: stone and moss providers, moss chance, size, style; then the moss carpet. */
        int[] rock(Object c) throws Unsupported, ReflectiveOperationException {
            int stone = stateProvider(call(c, "stone"));
            java.util.Optional<?> moss = (java.util.Optional<?>) call(c, "moss");
            int mossAt = moss.isPresent() ? stateProvider(moss.get()) : 0;
            int[] size = intProvider((IntProvider) call(c, "size"));
            String style = ((Enum<?>) call(c, "style")).name();
            int st = switch (style) {
                case "BOULDER" -> 0;
                case "SLAB" -> 1;
                case "STANDING" -> 2;
                case "RUBBLE" -> 3;
                default -> 4;
            };
            int at = here();
            out.add(stone);
            out.add(mossAt);
            out.add(Float.floatToRawIntBits((float) call(c, "mossChance")));
            for (int w : size) out.add(w);
            out.add(st);
            out.add(id(Blocks.MOSS_CARPET.defaultBlockState()));
            return new int[]{FT_ROCK, at};
        }

        // ---- trees ----------------------------------------------------------------------------------------------
        final Map<Object, Integer> speciesAt = new IdentityHashMap<>();
        static final int SPEC_W = 32;
        static final String[] SHAPES = {"CONIFER", "BROADLEAF", "ASPEN", "WILLOW", "COLUMNAR", "BALD_CYPRESS", "MANGROVE", "PALM",
                "ACACIA", "BAOBAB", "JUNGLE", "TWISTED", "SHRUB", "DEAD", "FALLEN_LOG", "STUMP", "BAMBOO", "CRYSTAL"};

        /** FloraTreeFeature.Config: species (offset, weight), stands, stand scale. */
        int[] tree(Object c) throws Unsupported, ReflectiveOperationException {
            @SuppressWarnings("unchecked")
            List<Object> entries = (List<Object>) call(c, "species");
            if (entries.size() > 24) throw new Unsupported("more than 24 tree species");
            int[] e = new int[entries.size() * 2];
            for (int i = 0; i < entries.size(); i++) {
                e[i * 2] = species(call(entries.get(i), "species"));
                e[i * 2 + 1] = (int) call(entries.get(i), "weight");
            }
            treeConstants();
            int at = here();
            out.add(entries.size());
            out.add(Float.floatToRawIntBits((float) call(c, "stands")));
            out.add(Float.floatToRawIntBits((float) call(c, "standScale")));
            for (int w : e) out.add(w);
            return new int[]{FT_TREE, at};
        }

        int species(Object sp) throws Unsupported, ReflectiveOperationException {
            Integer known = speciesAt.get(sp);
            if (known != null) return known;
            String shape = ((Enum<?>) get(sp, "shape")).name();
            int shapeId = java.util.Arrays.asList(SHAPES).indexOf(shape);
            if (shapeId < 0) throw new Unsupported("tree shape " + shape);
            BlockState log = (BlockState) get(sp, "log"), leaves = (BlockState) get(sp, "leaves"), accent = (BlockState) get(sp, "accent"),
                    extra = (BlockState) get(sp, "extra");
            int[] w = new int[SPEC_W];
            w[0] = shapeId;
            w[1] = id(log);
            w[2] = id(leaves);
            w[3] = accent == null ? -1 : id(accent);
            w[4] = extra == null ? -1 : id(extra);
            w[5] = fbits(sp, "accentChance");
            w[6] = (boolean) get(sp, "accentTop") ? 1 : 0;
            w[7] = (int) get(sp, "minHeight");
            w[8] = (int) get(sp, "maxHeight");
            w[9] = fbits(sp, "radius");
            w[10] = fbits(sp, "base0");
            w[11] = fbits(sp, "base1");
            w[12] = fbits(sp, "clump");
            w[13] = fbits(sp, "spread");
            w[14] = (int) get(sp, "limbs");
            w[15] = (int) get(sp, "trunk");
            w[16] = fbits(sp, "lean");
            w[17] = fbits(sp, "droop");
            w[18] = fbits(sp, "gaps");
            w[19] = fbits(sp, "deadLimbs");
            w[20] = fbits(sp, "vines");
            w[21] = fbits(sp, "roots");
            w[22] = fbits(sp, "ragged");
            w[23] = fbits(sp, "wind");
            w[24] = (boolean) get(sp, "umbrella") ? 1 : 0;
            w[25] = (boolean) get(sp, "whorls") ? 1 : 0;
            w[26] = (int) get(sp, "soil");
            w[27] = (int) get(sp, "maxWater");
            w[28] = (int) sp.getClass().getMethod("crownReach").invoke(sp);
            w[29] = log.is(Blocks.JUNGLE_LOG) ? 1 : 0;
            boolean facing = extra != null && extra.hasProperty(BlockStateProperties.FACING);
            w[30] = facing ? 1 : 0;
            w[31] = facing ? id(extra.setValue(BlockStateProperties.FACING, Direction.UP)) : 0;
            int at = here();
            for (int v : w) out.add(v);
            speciesAt.put(sp, at);
            return at;
        }

        int fbits(Object o, String field) throws ReflectiveOperationException {
            return Float.floatToRawIntBits((float) get(o, field));
        }

        int treesAt = -1;

        /** The trees' constant states: vines by direction, cocoa, moss, mushrooms, dirts, bamboo, Tamriel's bounds. */
        void treeConstants() {
            if (treesAt >= 0) return;
            int[] t = new int[32];
            Direction[] hz = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
            for (int d = 0; d < 4; d++) {
                t[d] = id(Blocks.VINE.defaultBlockState().setValue(net.minecraft.world.level.block.VineBlock.getPropertyForFace(hz[d].getOpposite()), true));
                for (int a = 0; a < 3; a++)
                    t[4 + d * 3 + a] = id(Blocks.COCOA.defaultBlockState().setValue(net.minecraft.world.level.block.CocoaBlock.FACING, hz[d].getOpposite())
                            .setValue(net.minecraft.world.level.block.CocoaBlock.AGE, a));
            }
            t[16] = id(Blocks.MOSS_CARPET.defaultBlockState());
            t[17] = id(Blocks.BROWN_MUSHROOM.defaultBlockState());
            t[18] = id(Blocks.RED_MUSHROOM.defaultBlockState());
            t[19] = id(Blocks.COARSE_DIRT.defaultBlockState());
            t[20] = id(Blocks.ROOTED_DIRT.defaultBlockState());
            var bl = new net.minecraft.world.level.block.state.properties.BambooLeaves[]{
                    net.minecraft.world.level.block.state.properties.BambooLeaves.NONE, net.minecraft.world.level.block.state.properties.BambooLeaves.SMALL,
                    net.minecraft.world.level.block.state.properties.BambooLeaves.LARGE};
            for (int lv = 0; lv < 3; lv++)
                for (int st = 0; st < 2; st++)
                    t[21 + lv * 2 + st] = id(Blocks.BAMBOO.defaultBlockState().setValue(net.minecraft.world.level.block.BambooStalkBlock.AGE, 1)
                            .setValue(net.minecraft.world.level.block.BambooStalkBlock.LEAVES, bl[lv])
                            .setValue(net.minecraft.world.level.block.BambooStalkBlock.STAGE, st));
            t[27] = com.nirncraft.tamriel.TamrielMap.MIN_X;
            t[28] = com.nirncraft.tamriel.TamrielMap.MAX_X;
            t[29] = com.nirncraft.tamriel.TamrielMap.MIN_Z;
            t[30] = com.nirncraft.tamriel.TamrielMap.MAX_Z;
            treesAt = here();
            for (int v : t) out.add(v);
        }

        /** SnowAndFreezeFeature: ice, the snow layer and its survivor, block light below 10 where nothing is loaded. */
        int[] freeze() {
            BlockState snow = Blocks.SNOW.defaultBlockState();
            int at = here();
            out.add(id(Blocks.ICE.defaultBlockState()));
            out.add(id(snow));
            out.add(survivor(snow));
            out.add(level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, ANCHOR) < 10 ? 1 : 0);
            return new int[]{FT_FREEZE, at};
        }

        /** DiskFeature: rule based provider, target predicate, radius, half height. */
        int[] disk(net.minecraft.world.level.levelgen.feature.DiskFeature d) throws Unsupported, ReflectiveOperationException {
            int prov = ruleProvider(d.stateProvider().value());
            int target = predicate(d.target());
            int[] radius = intProvider(d.radius());
            int at = here();
            out.add(prov);
            out.add(target);
            for (int w : radius) out.add(w);
            out.add(d.halfHeight());
            return new int[]{FT_DISK, at};
        }

        /** A provider that may give nothing: 2 rule based (n, fallback or 0, then (predicate, provider)); else stateProvider. */
        int ruleProvider(Object p) throws Unsupported, ReflectiveOperationException {
            if (p instanceof net.minecraft.world.level.levelgen.feature.stateproviders.RuleBasedStateProvider rb) {
                int fb = rb.fallback() == null ? 0 : stateProvider(rb.fallback().value());
                int[] rules = new int[rb.rules().size() * 2];
                for (int i = 0; i < rb.rules().size(); i++) {
                    var rule = rb.rules().get(i);
                    rules[i * 2] = predicate(rule.ifTrue());
                    rules[i * 2 + 1] = stateProvider(rule.then().value());
                }
                int at = here();
                out.add(2);
                out.add(rb.rules().size());
                out.add(fb);
                for (int w : rules) out.add(w);
                return at;
            }
            return stateProvider(p);
        }

        /** A block state provider: 0 simple (state), 1 weighted (total, then the state of every selection). */
        int stateProvider(Object p) throws Unsupported, ReflectiveOperationException {
            if (p instanceof Holder<?> h) p = h.value();
            if (p instanceof net.minecraft.world.level.levelgen.feature.stateproviders.SimpleStateProvider sp) {
                BlockState st = sp.getState(null, null, BlockPos.ZERO);
                int at = here();
                out.add(0);
                out.add(1);
                out.add(id(st));
                return at;
            }
            if (p instanceof net.minecraft.world.level.levelgen.feature.stateproviders.WeightedStateProvider wp) {
                // the selection of every nextInt(total) as the list's own selector makes it
                var list = (net.minecraft.util.random.WeightedList<?>) get(wp, "weightedList");
                int total = (int) get(list, "totalWeight");
                int[] ids = new int[total];
                for (int k = 0; k < total; k++) {
                    Fixed r = new Fixed(k);
                    ids[k] = id((BlockState) list.getRandomOrThrow(r));
                }
                int at = here();
                out.add(1);
                out.add(total);
                for (int i : ids) out.add(i);
                return at;
            }
            throw new Unsupported("state provider " + p.getClass().getSimpleName());
        }

        // ---- the regional alchemy plants of ground cover (GroundCoverFeature.chooseRegionalFloraId) -------------
        final Map<String, Integer> alchIds = new LinkedHashMap<>();
        final List<int[]> alchLists = new ArrayList<>();
        boolean alchDone;
        Method choose;

        /** The lists found over the map (every 64 blocks), so the pages' classes index a table the program holds. */
        void alchemyClasses() throws ReflectiveOperationException {
            if (alchDone) return;
            alchDone = true;
            Class<?> gc = Class.forName("com.nirncraft.tamriel.biome.flora.GroundCoverFeature");
            choose = gc.getDeclaredMethod("chooseRegionalFloraId", int.class, int.class, int.class, boolean.class, net.minecraft.util.RandomSource.class);
            choose.setAccessible(true);
            int minX = com.nirncraft.tamriel.TamrielMap.MIN_X, maxX = com.nirncraft.tamriel.TamrielMap.MAX_X;
            int minZ = com.nirncraft.tamriel.TamrielMap.MIN_Z, maxZ = com.nirncraft.tamriel.TamrielMap.MAX_Z;
            for (int z = minZ; z < maxZ; z += 64)
                for (int x = minX; x < maxX; x += 64)
                    for (int v = 0; v < 3; v++) alchClass(x, z, v);
        }

        /** The class of a column's alchemy draw (variant 0 dry and low, 1 dry above y 110, 2 by water), 255 unknown. */
        int alchClass(int x, int z, int variant) {
            String sig;
            List<String> list = new ArrayList<>();
            int kind;
            try {
                int y = variant == 1 ? 200 : 64;
                boolean water = variant == 2;
                Script s0 = new Script(0);
                String first = (String) choose.invoke(null, x, y, z, water, s0);
                if (s0.calls.size() == 1 && s0.calls.get(0).startsWith("i")) {
                    int n = Integer.parseInt(s0.calls.get(0).substring(1));
                    kind = 0;
                    for (int k = 0; k < n; k++) list.add((String) choose.invoke(null, x, y, z, water, new Script(k)));
                } else if (s0.calls.size() == 1 && s0.calls.get(0).equals("i3")) {
                    kind = 1;
                    list.add(first);
                    list.add((String) choose.invoke(null, x, y, z, water, new Script(1, 1)));
                    list.add((String) choose.invoke(null, x, y, z, water, new Script(1, 0)));
                } else return 255;
                // kind 1 has one nextInt(3) then maybe a boolean: tell it from a 3-entry list by the second call
                Script s1 = new Script(1, 1);
                choose.invoke(null, x, y, z, water, s1);
                if (s1.calls.size() == 2 && s1.calls.get(1).equals("b") && kind == 0) {
                    kind = 1;
                    list.clear();
                    list.add(first);
                    list.add((String) choose.invoke(null, x, y, z, water, new Script(1, 1)));
                    list.add((String) choose.invoke(null, x, y, z, water, new Script(1, 0)));
                }
                sig = kind + ":" + String.join(",", list);
            } catch (ReflectiveOperationException | RuntimeException e) {
                return 255;
            }
            Integer known = alchIds.get(sig);
            if (known != null) return known;
            if (alchIds.size() >= 255) return 255;
            int[] l = new int[2 + list.size() * 2];
            l[0] = kind;
            l[1] = list.size();
            for (int i = 0; i < list.size(); i++) {
                BlockState st = com.nirncraft.crafting.arcane.ArcaneRegistry.floraState(list.get(i));
                l[2 + i * 2] = id(st);
                l[3 + i * 2] = survivor(st);
            }
            alchIds.put(sig, alchLists.size());
            alchLists.add(l);
            return alchLists.size() - 1;
        }

        /** {kind, set, probability bits}. */
        int[] ruleTest(RuleTest t) throws Unsupported, ReflectiveOperationException {
            if (t instanceof AlwaysTrueTest) return new int[]{RT_TRUE, 0, 0};
            if (t instanceof TagMatchTest || t instanceof BlockMatchTest || t instanceof BlockStateMatchTest) {
                RandomSourceNone none = new RandomSourceNone();
                return new int[]{RT_SET, set(s -> t.test(s, BlockPos.ZERO, none)), 0};
            }
            if (t instanceof net.minecraft.world.level.levelgen.structure.templatesystem.AnyOfRuleTest any) {
                // only of tests that read no random: a set (any random child would draw in its order)
                @SuppressWarnings("unchecked")
                List<RuleTest> rules = (List<RuleTest>) get(any, "rules");
                for (RuleTest c : rules)
                    if (!(c instanceof TagMatchTest || c instanceof BlockMatchTest || c instanceof BlockStateMatchTest || c instanceof AlwaysTrueTest))
                        throw new Unsupported("rule test any_of with " + c.getClass().getSimpleName());
                RandomSourceNone none = new RandomSourceNone();
                return new int[]{RT_SET, set(s -> t.test(s, BlockPos.ZERO, none)), 0};
            }
            if (t instanceof RandomBlockMatchTest r) {
                var block = (net.minecraft.world.level.block.Block) get(r, "block");
                return new int[]{RT_RANDOM_SET, set(s -> s.is(block)), Float.floatToRawIntBits((float) get(r, "probability"))};
            }
            if (t instanceof RandomBlockStateMatchTest r) {
                BlockState bs = (BlockState) get(r, "blockState");
                return new int[]{RT_RANDOM_SET, set(s -> s == bs), Float.floatToRawIntBits((float) get(r, "probability"))};
            }
            throw new Unsupported("rule test " + t.getClass().getSimpleName());
        }

        // ---- the tables -------------------------------------------------------------------------------------------
        /** The variants a state can turn into on the GPU (axis, waterlogged, leaf distance, snowy), all in the table. */
        void closeTransforms() {
            for (int i = 0; i < blocks.size(); i++) {
                BlockState s = blocks.get(i);
                for (BlockState v : variants(s)) if (v != null) id(v);
            }
        }

        /** {axis x, y, z, waterlogged true, false, distance 1..7, snowy}: null where the state lacks the property. */
        BlockState[] variants(BlockState s) {
            BlockState[] v = new BlockState[13];
            if (s.hasProperty(BlockStateProperties.AXIS)) {
                v[0] = s.setValue(BlockStateProperties.AXIS, Direction.Axis.X);
                v[1] = s.setValue(BlockStateProperties.AXIS, Direction.Axis.Y);
                v[2] = s.setValue(BlockStateProperties.AXIS, Direction.Axis.Z);
            }
            if (s.hasProperty(BlockStateProperties.WATERLOGGED)) {
                v[3] = s.setValue(BlockStateProperties.WATERLOGGED, true);
                v[4] = s.setValue(BlockStateProperties.WATERLOGGED, false);
            }
            if (s.hasProperty(LeavesBlock.DISTANCE)) {
                for (int d = 1; d <= 6; d++) v[4 + d] = s.setValue(LeavesBlock.DISTANCE, d).setValue(LeavesBlock.PERSISTENT, false);
                v[11] = s.setValue(LeavesBlock.DISTANCE, 7).setValue(LeavesBlock.PERSISTENT, true);
            }
            if (s.hasProperty(net.minecraft.world.level.block.SnowyBlock.SNOWY)) v[12] = s.setValue(net.minecraft.world.level.block.SnowyBlock.SNOWY, true);
            return v;
        }

        int[] finish() {
            closeTransforms();
            // the biomes' climate (freeze_top_layer)
            int biomeTab = here();
            for (Holder<Biome> h : terrain.biomes) {
                Biome b = h.value();
                out.add(Float.floatToRawIntBits(b.getBaseTemperature()));
                out.add(b.hasPrecipitation() ? 1 : 0);
                boolean frozen;
                try {
                    Object climate = get(b, Biome.class, "climateSettings");
                    frozen = ((Biome.ClimateSettings) climate).temperatureModifier() == Biome.TemperatureModifier.FROZEN;
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
                out.add(frozen ? 1 : 0);
                out.add(0);
            }
            // fields
            int fieldsAt = here();
            for (int[] f : fields) for (int w : f) out.add(w);
            // survival table (bit sets over the final table)
            int survAt = here();
            List<int[]> surv = new ArrayList<>();
            for (BlockState s : survivors) {
                int kind = s.getBlock() instanceof SugarCaneBlock ? SK_CANE : s.getBlock() instanceof CactusBlock ? SK_CACTUS : SK_BELOW;
                surv.add(new int[]{id(s), 0, kind, 0});
                out.addElements(out.size(), new int[4]);
            }
            int alchAt = here();
            int listsAt = alchAt + 256;
            int[] offs = new int[256];
            int cursor = listsAt;
            int[] empty = {0, 1, 0, 0};
            for (int i = 0; i < 256; i++) {
                int[] l = i < alchLists.size() ? alchLists.get(i) : empty;
                offs[i] = cursor;
                cursor += l.length;
            }
            for (int o2 : offs) out.add(o2);
            for (int i = 0; i < 256; i++) for (int w : (i < alchLists.size() ? alchLists.get(i) : empty)) out.add(w);
            int consts = here();
            long c = Double.doubleToRawLongBits(1.702 / 0.27);
            out.add((int) c);
            out.add((int) (c >>> 32));
            // the sin table (Mth's own)
            int sinAt = here();
            // Mth's own table (re-evaluating Mth.sin(i / k) may land on a neighbouring entry)
            float[] table;
            try {
                Field t = net.minecraft.util.Mth.class.getDeclaredField("SIN");
                t.setAccessible(true);
                table = (float[]) t.get(null);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            for (int i = 0; i < 65536; i++) out.add(Float.floatToRawIntBits(table[i]));
            // survival bit sets: below-only rules, tried with everything else air (cane: water beside the soil)
            for (int i = 0; i < survivors.size(); i++) {
                BlockState plant = survivors.get(i);
                int kind = surv.get(i)[2];
                int at = set(below -> survivesOn(plant, below, kind));
                surv.get(i)[1] = at;
                if (kind == SK_BELOW && !belowOnly(plant)) surv.get(i)[2] = SK_UNSUPPORTED;
            }
            for (int i = 0; i < surv.size(); i++) for (int k = 0; k < 4; k++) out.set(survAt + i * 4 + k, surv.get(i)[k]);
            // the block table, now complete
            int blocksAt = here();
            for (BlockState s : blocks) {
                out.add(flags(s));
                int f2 = 0;
                if (s.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) f2 |= F2_LIQUID;
                if (s.hasProperty(net.minecraft.world.level.block.SnowyBlock.SNOWY)) f2 |= F2_SNOWY;
                if (s.liquid()) f2 |= F2_LIQUID_PROP;
                out.add(f2);
                BlockState[] v = variants(s);
                int self = blockIds.get(s);
                for (int k = 0; k < BLK_W - 2; k++) {
                    BlockState t = k < v.length ? v[k] : null;
                    Integer tid = t == null ? null : blockIds.get(t);
                    out.add(tid == null ? self : tid);
                }
            }
            for (Map.Entry<Integer, Predicate<BlockState>> e : sets) {
                int at = e.getKey();
                for (int b = 0; b < blocks.size(); b++)
                    if (e.getValue().test(blocks.get(b))) out.set(at + (b >> 5), out.getInt(at + (b >> 5)) | 1 << (b & 31));
            }
            if (blocks.size() > 65536) throw new IllegalStateException("more than 65536 block states");
            out.set(0, MAGIC);
            out.set(H_VERSION, VERSION);
            out.set(H_NBLOCKS, blocks.size());
            out.set(H_BLOCKS, blocksAt);
            out.set(H_NBIOMES, nBiomes);
            out.set(H_BIOMEWORDS, biomeWords);
            out.set(H_SEED, (int) seed);
            out.set(H_SEED + 1, (int) (seed >>> 32));
            out.set(H_ZOOM, (int) zoomSeed);
            out.set(H_ZOOM + 1, (int) (zoomSeed >>> 32));
            out.set(H_SEA, generator.getSeaLevel());
            out.set(H_SIN, sinAt);
            out.set(H_FIELDS, fieldsAt);
            out.set(H_SURVIVE, survAt);
            out.set(H_SURVIVE_N, survivors.size());
            out.set(H_CONSTS, consts);
            out.set(H_GENMINY, context.getMinGenY());
            out.set(H_GENHEIGHT, context.getGenDepth());
            out.set(H_ALCH, alchAt);
            out.set(H_TREES, treesAt < 0 ? 0 : treesAt);
            out.set(H_BIOMETAB, biomeTab);
            return out.toIntArray();
        }

        int flags(BlockState s) {
            var fluid = s.getFluidState();
            int f = 0;
            if (s.isAir()) f |= B_AIR;
            if (fluid.getType() == Fluids.WATER) f |= B_WATER;
            if (fluid.is(FluidTags.LAVA)) f |= B_LAVA;
            if (fluid.isSource()) f |= B_SOURCE;
            if (s.is(BlockTags.BLOCKS_MOTION_IN_HEIGHTMAP)) f |= B_MOTION;
            if (s.is(BlockTags.BLOCKS_MOTION_IN_HEIGHTMAP_NO_LEAVES)) f |= B_MOTION_NL;
            if (s.is(BlockTags.LEAVES)) f |= B_LEAVES_TAG;
            if (s.getBlock() instanceof LeavesBlock) f |= B_LEAVES_BLOCK;
            if (s.is(BlockTags.LOGS)) f |= B_LOGS;
            if (com.nirncraft.tamriel.biome.feature.GenBox.isSoft(s)) f |= B_SOFT;
            if (s.is(BlockTags.SUBSTRATE_OVERWORLD)) f |= B_SUBSTRATE;
            if (s.is(BlockTags.SAND)) f |= B_SAND;
            if (staticFlag("com.nirncraft.tamriel.biome.flora.GroundCoverFeature", "isCanopyOrFoliageOrRoof", s)) f |= B_CANOPY;
            if (s.isFaceSturdy(stub(s, null), ANCHOR, Direction.UP)) f |= B_STURDY_UP;
            if (s.isSolid()) f |= B_SOLID;
            if (staticFlag("com.nirncraft.tamriel.biome.flora.TreeBuilder", "isGround", s)) f |= B_GROUND;
            if (s.canBeReplaced()) f |= B_REPLACEABLE;
            if (!fluid.isEmpty()) f |= B_FLUID;
            if (s.hasProperty(BlockStateProperties.WATERLOGGED)) f |= B_WATERLOGGABLE;
            if (s.is(Blocks.SNOW)) f |= B_SNOW_LAYER;
            if (s.is(BlockTags.SUPPORTS_BAMBOO)) f |= B_BAMBOO_SOIL;
            if (s.getBlock() instanceof DoublePlantBlock && s.hasProperty(DoublePlantBlock.HALF)) f |= B_DOUBLE_PLANT;
            if (fluid.is(FluidTags.WATER)) f |= B_FLUID_WATER_TAG;
            if (s.is(Blocks.WATER)) f |= B_WATER_BLOCK;
            try {
                Class<?> ft = Class.forName("com.nirncraft.tamriel.biome.flora.FloraTreeFeature");
                Method soil = ft.getMethod("soilFits", int.class, BlockState.class);
                int[] bits = {B_SOIL_DIRT, B_SOIL_SAND, B_SOIL_STONE, B_SOIL_ASH, B_SOIL_SNOW};
                for (int i = 0; i < 5; i++) if ((boolean) soil.invoke(null, 1 << i, s)) f |= bits[i];
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
            return f;
        }

        private final Map<String, Method> statics = new HashMap<>();

        boolean staticFlag(String cls, String name, BlockState s) {
            try {
                Method m = statics.get(cls + "#" + name);
                if (m == null) {
                    m = Class.forName(cls).getDeclaredMethod(name, BlockState.class);
                    m.setAccessible(true);
                    statics.put(cls + "#" + name, m);
                }
                return (boolean) m.invoke(null, s);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }

        /**
         * Whether a plant survives on {@code below} (one above it in a stub level, everything else air; cane: water beside).
         * Survival that reads light (mushrooms) sees the server's light far from anything loaded, as a decoration of a
         * chunk the server has not loaded does.
         */
        boolean survivesOn(BlockState plant, BlockState below, int kind) {
            Map<BlockPos, BlockState> at = new HashMap<>();
            at.put(BlockPos.ZERO, below);
            if (kind == SK_CANE)
                for (Direction d : Direction.Plane.HORIZONTAL) at.put(BlockPos.ZERO.relative(d), Blocks.WATER.defaultBlockState());
            return plant.canSurvive(stub(Blocks.AIR.defaultBlockState(), at), ANCHOR.above());
        }

        /** True when the plant's survival reads nothing but the block below (stone or water around changes nothing). */
        boolean belowOnly(BlockState plant) {
            for (BlockState below : new BlockState[]{Blocks.GRASS_BLOCK.defaultBlockState(), Blocks.DIRT.defaultBlockState(),
                    Blocks.SAND.defaultBlockState(), Blocks.STONE.defaultBlockState()}) {
                boolean base = survivesOn(plant, below, SK_BELOW);
                for (BlockState around : new BlockState[]{Blocks.STONE.defaultBlockState(), Blocks.WATER.defaultBlockState()}) {
                    Map<BlockPos, BlockState> at = new HashMap<>();
                    for (int dx = -1; dx <= 1; dx++)
                        for (int dy = 0; dy <= 2; dy++)
                            for (int dz = -1; dz <= 1; dz++) at.put(new BlockPos(dx, dy, dz), around);
                    at.put(BlockPos.ZERO.above(), Blocks.AIR.defaultBlockState());
                    at.put(BlockPos.ZERO, below);
                    if (plant.canSurvive(stub(Blocks.AIR.defaultBlockState(), at), ANCHOR.above()) != base) return false;
                }
            }
            return true;
        }

        /** A level reading {@code fill} everywhere but the given positions (relative to ANCHOR; survival and face tests). */
        LevelReader stub(BlockState fill, Map<BlockPos, BlockState> at) {
            return (LevelReader) Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class}, (proxy, m, args) -> {
                int n = args == null ? 0 : args.length;
                switch (m.getName()) {
                    case "getBlockState":
                        if (n == 1 && args[0] instanceof BlockPos p) {
                            BlockPos r = p.subtract(ANCHOR);
                            return at != null && at.containsKey(r) ? at.get(r) : fill;
                        }
                        break;
                    case "getFluidState":
                        if (n == 1 && args[0] instanceof BlockPos p) {
                            BlockPos r = p.subtract(ANCHOR);
                            return (at != null && at.containsKey(r) ? at.get(r) : fill).getFluidState();
                        }
                        break;
                    case "getLightEngine":
                        return level.getLightEngine();
                    case "getMinY":
                        return -64;
                    case "getHeight":
                        if (n == 0) return 704;
                        break;
                    case "isClientSide":
                        return false;
                    case "registryAccess":
                        return level.registryAccess();
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "equals":
                        return proxy == args[0];
                    case "toString":
                        return "atlas feature program stub";
                    default:
                        break;
                }
                if (m.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, m, args);
                return AtlasCapture.zero(m.getReturnType());
            });
        }
    }

    /** Where the stub level's tests stand: far from anything a server loads. */
    static final BlockPos ANCHOR = new BlockPos(24_000_000, 100, 24_000_000);

    // ---- reflection ----------------------------------------------------------------------------------------------
    static Object get(Object o, String name) throws ReflectiveOperationException {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                // the superclass then
            }
        }
        throw new NoSuchFieldException(o.getClass().getName() + "." + name);
    }

    static Object get(Object o, Class<?> owner, String name) throws ReflectiveOperationException {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    static int[] concat(int[]... parts) {
        int n = 0;
        for (int[] p : parts) n += p.length;
        int[] r = new int[n];
        int i = 0;
        for (int[] p : parts) {
            System.arraycopy(p, 0, r, i, p.length);
            i += p.length;
        }
        return r;
    }

    /** Every nextInt answers k (a weighted list's selector, read entry by entry). */
    private static final class Fixed extends RandomSourceNone {
        final int k;

        Fixed(int k) {
            this.k = k;
        }

        @Override
        public int nextInt(int bound) {
            return k;
        }
    }

    /** Scripted answers, the calls recorded ("i<bound>", "b"). */
    private static final class Script extends RandomSourceNone {
        final int[] answers;
        int at;
        final List<String> calls = new ArrayList<>();

        Script(int... answers) {
            this.answers = answers;
        }

        int next() {
            return at < answers.length ? answers[at++] : 0;
        }

        @Override
        public int nextInt(int bound) {
            calls.add("i" + bound);
            return Math.min(next(), bound - 1);
        }

        @Override
        public boolean nextBoolean() {
            calls.add("b");
            return next() != 0;
        }
    }

    /** Rule tests that read no random: a source that refuses to be used. */
    private static class RandomSourceNone implements net.minecraft.util.RandomSource {
        public net.minecraft.util.RandomSource fork() { throw new IllegalStateException(); }
        public net.minecraft.world.level.levelgen.PositionalRandomFactory forkPositional() { throw new IllegalStateException(); }
        public void setSeed(long seed) { throw new IllegalStateException(); }
        public int nextInt() { throw new IllegalStateException(); }
        public int nextInt(int bound) { throw new IllegalStateException(); }
        public long nextLong() { throw new IllegalStateException(); }
        public boolean nextBoolean() { throw new IllegalStateException(); }
        public float nextFloat() { throw new IllegalStateException(); }
        public double nextDouble() { throw new IllegalStateException(); }
        public double nextGaussian() { throw new IllegalStateException(); }
    }
}
