package com.nirncraft.atlas;

import com.nirncraft.perf.FastSurfaceRule;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Interval;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctions;
import net.minecraft.world.level.levelgen.densityfunction.TilingMode;
import net.minecraft.world.level.levelgen.densityfunction.generator.ConstantFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.GradientFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.NoiseFunction;
import net.minecraft.world.level.levelgen.densityfunction.generator.ShiftNoiseFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.BinaryFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.BlendDensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.CacheFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.ClampFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.InterpolatedFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.IntervalSelectFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.RangeChoiceFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.SliceFunction;
import net.minecraft.world.level.levelgen.densityfunction.op.UnaryFunction;
import net.minecraft.world.level.levelgen.material.MaterialSystem;
import net.minecraft.world.level.levelgen.material.condition.AbovePreliminarySurfaceCondition;
import net.minecraft.world.level.levelgen.material.condition.BiomeCondition;
import net.minecraft.world.level.levelgen.material.condition.HoleCondition;
import net.minecraft.world.level.levelgen.material.condition.MaterialCondition;
import net.minecraft.world.level.levelgen.material.condition.NoiseThresholdCondition;
import net.minecraft.world.level.levelgen.material.condition.NotCondition;
import net.minecraft.world.level.levelgen.material.condition.SteepCondition;
import net.minecraft.world.level.levelgen.material.condition.StoneDepthCondition;
import net.minecraft.world.level.levelgen.material.condition.VerticalGradientCondition;
import net.minecraft.world.level.levelgen.material.condition.WaterCondition;
import net.minecraft.world.level.levelgen.material.condition.YCondition;
import net.minecraft.world.level.levelgen.material.rule.BandlandsRule;
import net.minecraft.world.level.levelgen.material.rule.BlockRule;
import net.minecraft.world.level.levelgen.material.rule.ConditionRule;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.OreVeinRule;
import net.minecraft.world.level.levelgen.material.rule.SequenceRule;
import net.minecraft.world.level.levelgen.synth.GradientNoise;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NoiseStack;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import net.minecraft.world.level.levelgen.placement.CaveSurface;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * The client generator's program (native/shaders/atlas_gen.slang; docs/ATLAS-CLIENT-GENERATION.md): the world's own
 * generator objects compiled into words the GPU interprets. Nothing is re-authored: the density functions are walked
 * as Java's DensityFunctionCompiler sees them (references inlined, caches transparent, the min/max range shortcuts
 * taken from the same {@code range()}), the noises are the RandomState's seeded instances (their permutation tables
 * and offsets read out), the material rule tree is the settings' own, and every random factory's seeds are the
 * RandomState's. Two evaluation paths are kept apart, as Java has them: volume (the chunk fill, the ore veins'
 * prefill) and point (the aquifer's and the rules' samples).
 *
 * <p>The mod's 2D functions ({@code nirncraft:tamriel_height}, {@code tamriel_channel}) become column fields: their
 * values come per chunk in pages ({@link AtlasGenPages}) from the very samplers the generator compiles.</p>
 */
final class AtlasGenProgram {
    // ---- layout (atlas_gen.slang) ----
    static final int MAGIC = 0x4E454741, VERSION = 1;
    static final int H_VERSION = 1, H_REGS = 2, H_K = 3, H_LAYERS = 4, H_STACKS = 5, H_CODE = 6, H_RULES = 7,
            H_CONDS = 8, H_BIOMESETS = 9, H_BLOCKS = 10, H_NBLOCKS = 11, H_BANDS = 12, H_FIELDS = 13, H_SETWORDS = 14,
            H_ORES = 15, H_ENTRY = 16;
    static final int E_CORNER = 0, E_FINAL = 1, E_AQ_BARRIER = 2, E_AQ_FLOOD = 3, E_AQ_SPREAD = 4, E_AQ_LAVA = 5,
            E_AQ_EXCL = 6, E_AQ_SURF = 7, E_PRELIM = 8, E_ORES = 9, E_MAX = 24;
    static final int S_SEA = 64, S_AQ_LO = 65, S_ORE_LO = 69, S_SURF_LO = 73, S_ZOOM = 77, S_SURFNOISE = 79,
            S_SECNOISE = 80, S_CLAYNOISE = 81, S_STONE = 82, S_WATER = 83, S_LAVA = 84, S_WAYBELOW = 85,
            S_LAVALEVEL = 86, S_ORECOUNT = 87, S_RULESTART = 88, S_NBIOMES = 89;
    /** The page passes' inputs (atlas_page.slang): the climate entries (6 start / end pairs), the fields' kinds, the
     *  height noises' stacks (4), and whether the program carries them all (the device then makes the pages). */
    static final int S_CLIMATE = 96, S_FKINDS = 108, S_HNOISE = 109, S_PAGEGEN = 113;
    /** A field's kind: TamrielHeight, else 1 + its TamrielChannel ordinal; FK_NONE: not on the device. */
    static final int FK_HEIGHT = 0, FK_NONE = 0xFFFF;
    static final int HEADER = 128, LAYER_WORDS = 76, REGS = 96, MAX_INTERP = 16, MAX_ORES = 4;
    static final int OP_CONST = 1, OP_Y = 2, OP_COL = 3, OP_NOISE = 4, OP_GRAD = 5, OP_ADD = 6, OP_SUB = 7,
            OP_MUL_V = 8, OP_MUL_P = 9, OP_DIV_V = 10, OP_DIV_P = 11, OP_MIN_V = 12, OP_MIN_P = 13, OP_MAX_V = 14,
            OP_MAX_P = 15, OP_ADDC = 16, OP_MULC = 17, OP_CSUB = 18, OP_CDIV = 19, OP_MINC_V = 20, OP_MINC_P = 21,
            OP_MAXC_V = 22, OP_MAXC_P = 23, OP_ABS = 24, OP_SQUARE = 25, OP_CUBE = 26, OP_SQUEEZE = 27, OP_NEG = 28,
            OP_LEAKY = 29, OP_RECIP = 30, OP_SIGN = 31, OP_CLAMP = 32, OP_RANGE = 33, OP_RANGEC = 34, OP_ISEL = 35,
            OP_INTERP = 36, OP_OUT = 39, OP_SNOISE = 41, OP_NOISEB = 42;
    static final int R_END = 0, R_TEST = 1, R_BLOCK = 2, R_ORE = 3, R_BANDS = 4;
    static final int C_STONE_DEPTH = 1, C_WATER = 2, C_Y = 3, C_STEEP = 4, C_HOLE = 5, C_NOISE = 6, C_VGRAD = 7,
            C_ABOVE_PRELIM = 8, C_BIOME = 9, C_NOT = 10;
    static final int F_AIR = 1, F_FLUID = 2, F_WATER = 4, F_LAVA = 8, F_MOTION = 16, F_SOFT = 32;
    /** Quarts per chunk column (y -64..639) and the page layout's tail (atlas_gen.slang). */
    static final int QY = 176;

    final int[] words;
    /** The column fields (the mod's 2D functions), in page order. */
    final List<DensityFunction> fields;
    /** Generator block ids: index = id. 0 air, 1 the default block, 2 the default fluid, 3 lava. */
    final List<BlockState> blocks;
    final Map<BlockState, Integer> blockIds;
    final List<Holder<Biome>> biomes;
    final Map<Holder<Biome>, Integer> biomeIds;
    final int interpolated, registers, oreVeins;
    /** The program carries what the page passes need: the device makes the pages (atlas_gen_set_map). */
    final boolean pageGen;

    int pageWords() {
        return fields.size() * 256 + 256 + 4 * 4 * QY / 2;
    }

    private AtlasGenProgram(Compiler c) {
        words = c.out;
        fields = c.fields;
        blocks = c.blocks;
        blockIds = c.blockIds;
        biomes = c.biomes;
        biomeIds = c.biomeIds;
        interpolated = c.interps.size();
        registers = c.maxRegs;
        oreVeins = c.ores.size();
        pageGen = c.climate != null;
    }

    /** A column field's kind for the page passes (FK_NONE: a function they do not compute). */
    static int fieldKind(DensityFunction f) {
        if (f instanceof com.nirncraft.tamriel.terrain.TamrielHeight) return FK_HEIGHT;
        if (f instanceof com.nirncraft.tamriel.terrain.TamrielChannel(var ch)) return 1 + ch.ordinal();
        return FK_NONE;
    }

    /**
     * Compiles the overworld generator. heights: the level's height accessor (vertical anchors resolve against it as
     * in chunk generation); material: the atlas material of a block state (null: the generator ids themselves).
     */
    static AtlasGenProgram compile(ChunkGenerator generator, NoiseGeneratorSettings settings, RandomState random,
                                   LevelHeightAccessor heights, long seed, ToIntFunction<BlockState> material) {
        Compiler c = new Compiler(generator, settings, random, heights, seed);
        c.run(material);
        return new AtlasGenProgram(c);
    }

    // =================================================================================================================

    private static final class Ins {
        final int op;
        int dst = -1;  // virtual register
        final int[] src;  // virtual registers read
        final int[] imm;  // immediate words; positions in immRegs hold virtual registers
        final int[] immRegs;

        Ins(int op, int[] src, int[] imm, int[] immRegs) {
            this.op = op;
            this.src = src;
            this.imm = imm;
            this.immRegs = immRegs;
        }
    }

    private static final class Entry {
        final boolean point;
        final List<Ins> code = new ArrayList<>();
        final Map<DensityFunction, Integer> cse = new HashMap<>();
        int nextReg;

        Entry(boolean point) {
            this.point = point;
        }
    }

    private static final class Compiler {
        final ChunkGenerator generator;
        final NoiseGeneratorSettings settings;
        final RandomState random;
        final LevelHeightAccessor heights;
        final long seed;
        final WorldGenerationContext genContext;
        final List<DensityFunction> fields = new ArrayList<>();
        final Map<DensityFunction, Integer> fieldIds = new HashMap<>();
        final List<InterpolatedFunction> interps = new ArrayList<>();
        final Map<InterpolatedFunction, Integer> interpIds = new HashMap<>();
        final Map<Noise, Integer> stackIds = new IdentityHashMap<>();
        final List<int[]> stacks = new ArrayList<>();  // (first layer, count)
        final List<int[]> layers = new ArrayList<>();  // LAYER_WORDS each
        final List<BlockState> blocks = new ArrayList<>();
        final Map<BlockState, Integer> blockIds = new IdentityHashMap<>();
        final List<Holder<Biome>> biomes = new ArrayList<>();
        final Map<Holder<Biome>, Integer> biomeIds = new HashMap<>();
        final List<int[]> conds = new ArrayList<>();
        final Map<MaterialCondition, Integer> condIds = new HashMap<>();
        final List<HolderSet<Biome>> biomeSets = new ArrayList<>();
        final List<Integer> rules = new ArrayList<>();
        final List<OreVeinRule> ores = new ArrayList<>();
        final List<long[]> extraSeeds = new ArrayList<>();  // vertical gradients' (lo, hi)
        final Entry[] entries = new Entry[E_MAX];
        /** The climate router's entries (point), or null when the page passes cannot run on the device. */
        Entry[] climate;
        final int[] heightStacks = {-1, -1, -1, -1};
        int maxRegs, surfNoise, secNoise, clayNoise;
        int[] out;

        Compiler(ChunkGenerator generator, NoiseGeneratorSettings settings, RandomState random, LevelHeightAccessor heights, long seed) {
            this.generator = generator;
            this.settings = settings;
            this.random = random;
            this.heights = heights;
            this.seed = seed;
            this.genContext = new WorldGenerationContext(generator, heights);
        }

        void run(ToIntFunction<BlockState> material) {
            // blocks 0..3 fixed
            block(Blocks.AIR.defaultBlockState());
            block(settings.defaultBlock());
            block(settings.defaultFluid());
            block(Blocks.LAVA.defaultBlockState());
            // biomes, in a stable order
            BiomeSource source = generator.getBiomeSource();
            List<Holder<Biome>> all = new ArrayList<>(source.possibleBiomes());
            all.sort(Comparator.comparing(h -> h.unwrapKey().map(k -> k.identifier().toString()).orElse("")));
            for (Holder<Biome> b : all) {
                biomeIds.put(b, biomes.size());
                biomes.add(b);
            }
            if (biomes.size() > 65535) throw new IllegalStateException("too many biomes for 16-bit ids");

            var router = settings.noiseRouter();
            Aquifer.Config aq = settings.aquifers().orElseThrow(() -> new IllegalStateException("the generator has no aquifers"));
            entries[E_FINAL] = entry(router.finalDensity(), false);
            entries[E_AQ_BARRIER] = entry(aq.barrierNoise(), true);
            entries[E_AQ_FLOOD] = entry(aq.fluidLevelFloodednessNoise(), true);
            entries[E_AQ_SPREAD] = entry(aq.fluidLevelSpreadNoise(), true);
            entries[E_AQ_LAVA] = entry(aq.lavaNoise(), true);
            entries[E_AQ_EXCL] = entry(aq.exclusion(), true);
            entries[E_AQ_SURF] = entry(aq.surfaceLevel(), true);
            entries[E_PRELIM] = entry(router.chunkSurfaceLevel(), true);
            pageInputs(router);
            // the material rule (its ore veins compile their own entries)
            MaterialRule rule = settings.materialRule().value();
            rule(rule);
            rules.add(R_END);
            if (ores.size() > MAX_ORES) throw new IllegalStateException("more than " + MAX_ORES + " ore veins");
            for (int i = 0; i < ores.size(); i++) {
                OreVeinRule o = ores.get(i);
                entries[E_ORES + i * 3] = entry(o.density(), false);
                entries[E_ORES + i * 3 + 1] = entry(o.richness(), false);
                entries[E_ORES + i * 3 + 2] = entry(o.fillerGap(), true);
                block(o.oreBlock());
                block(o.rawOreBlock());
                block(o.fillerBlock());
            }
            // the corners: every interpolated function's input, volume path
            Entry corner = new Entry(false);
            for (int k = 0; k < interps.size(); k++) {
                int r = compile(interps.get(k).input(), corner);
                corner.code.add(new Ins(OP_OUT, new int[]{r}, new int[0], new int[0]));
                corner.code.getLast().dst = -(k + 1);  // OUT k (see encode)
            }
            if (interps.size() > MAX_INTERP) throw new IllegalStateException("more than " + MAX_INTERP + " interpolated functions");
            entries[E_CORNER] = corner;
            // clay bands
            MaterialSystem system = random.surfaceSystem();
            BlockState[] bands = (BlockState[]) get(MaterialSystem.class, system, "clayBands");
            int[] bandIds = new int[bands.length];
            for (int i = 0; i < bands.length; i++) bandIds[i] = block(bands[i]);
            if (bands.length != 192) throw new IllegalStateException("clay bands: " + bands.length);
            // the surface system's noises, before the layout fixes the noise region
            surfNoise = stack((Noise) get(MaterialSystem.class, system, "surfaceNoise"));
            secNoise = stack((Noise) get(MaterialSystem.class, system, "surfaceSecondaryNoise"));
            clayNoise = stack((Noise) get(MaterialSystem.class, system, "clayBandsOffsetNoise"));
            assemble(material, system, bandIds);
        }

        /**
         * The page passes' inputs (atlas_page.slang): Climate.Sampler's six functions as point entries (the biome
         * source's climate), and the four noises TamrielHeight binds (its WorldNoise holders, the RandomState's
         * instances as compileSampler's bind takes them). Left null when anything is outside what the kernels do.
         */
        void pageInputs(net.minecraft.world.level.levelgen.NoiseRouter router) {
            try {
                DensityFunction[] fs = {router.temperature(), router.vegetation(), router.continents(), router.erosion(),
                        router.depth(), router.ridges()};
                Entry[] es = new Entry[fs.length];
                for (int i = 0; i < fs.length; i++) es[i] = entry(fs[i], true);
                com.nirncraft.tamriel.terrain.TamrielHeight th = null;
                for (DensityFunction f : fields) if (f instanceof com.nirncraft.tamriel.terrain.TamrielHeight t) th = t;
                if (th == null) throw new IllegalStateException("no nirncraft:tamriel_height in the router");
                com.nirncraft.worldgen.WorldNoise[] wn = {th.hills(), th.detail(), th.ridges(), th.dunes()};
                for (int i = 0; i < 4; i++) {
                    ResourceKey<net.minecraft.world.level.levelgen.synth.NormalNoise> key = wn[i].noiseData().unwrapKey()
                            .orElseThrow(() -> new IllegalStateException("an unnamed TamrielHeight noise"));
                    heightStacks[i] = stack(random.getOrCreateNoise(key));
                }
                for (DensityFunction f : fields)
                    if (fieldKind(f) == FK_NONE) throw new IllegalStateException("a column field the page passes lack: " + f);
                climate = es;
            } catch (IllegalStateException e) {
                climate = null;
                com.nirncraft.NirnCraft.LOGGER.info("atlas generator: pages stay in Java ({})", e.getMessage());
            }
        }

        // ---- density functions ----

        Entry entry(DensityFunction f, boolean point) {
            Entry e = new Entry(point);
            int r = compile(f, e);
            Ins out = new Ins(OP_OUT, new int[]{r}, new int[0], new int[0]);
            out.dst = -1;  // OUT 0
            e.code.add(out);
            return e;
        }

        static DensityFunction unwrap(DensityFunction f) {
            for (;;) {
                if (f instanceof DensityFunctions.HolderHolder(Holder<DensityFunction> h)) f = h.value();
                else if (f instanceof CacheFunction(DensityFunction in)) f = in;
                else if (f instanceof BlendDensityFunction(DensityFunction in)) f = in;  // no blender: new worlds
                else if (f instanceof SliceFunction(var axis, int coordinate, DensityFunction in)) f = in;  // values do not depend on a sliced axis
                else return f;
            }
        }

        int emit(Entry e, int op, int[] src, int[] imm, int[] immRegs) {
            Ins i = new Ins(op, src, imm, immRegs);
            i.dst = e.nextReg++;
            e.code.add(i);
            return i.dst;
        }

        int emit(Entry e, int op, int... src) {
            return emit(e, op, src, new int[0], new int[0]);
        }

        int emitImm(Entry e, int op, int src, int... imm) {
            return emit(e, op, new int[]{src}, imm, new int[0]);
        }

        int compile(DensityFunction raw, Entry e) {
            DensityFunction f = unwrap(raw);
            Integer known = e.cse.get(f);
            if (known != null) return known;
            int r = compileNew(f, e);
            e.cse.put(f, r);
            return r;
        }

        static int fb(float f) {
            return Float.floatToRawIntBits(f);
        }

        int compileNew(DensityFunction f, Entry e) {
            if (f instanceof ConstantFunction(float v)) return emit(e, OP_CONST, new int[0], new int[]{fb(v)}, new int[0]);
            if (f instanceof GradientFunction(var axis, TilingMode tiling, int from, int to, float fromValue, float toValue)) {
                if (tiling != TilingMode.CLAMP_TO_EDGE) throw unsupported(f);
                int range = to - from;
                float factor = (toValue - fromValue) / range;
                int ax = switch (axis) { case X -> 0; case Y -> 1; case Z -> 2; };
                return emit(e, OP_GRAD, new int[0],
                        new int[]{ax, from, Math.min(from, to), Math.max(from, to), fb(fromValue), fb(factor)}, new int[0]);
            }
            if (f instanceof ShiftNoiseFunction sn) {
                // the climate's shifts (point only): Shift / ShiftA / ShiftB's samplers, then their ConstMulSampler * 4
                if (!e.point) throw unsupported(f);
                ResourceKey<net.minecraft.world.level.levelgen.synth.NormalNoise> key = sn.offsetNoise().unwrapKey()
                        .orElseThrow(() -> unsupported(f));
                int stack = stack(random.getOrCreateNoise(key));
                long q = Double.doubleToRawLongBits(0.25), zero = Double.doubleToRawLongBits(0.0);
                int r;
                if (sn instanceof ShiftNoiseFunction.ShiftA)
                    r = emit(e, OP_NOISE, new int[0], new int[]{stack | 1 << 16, (int) q, (int) (q >>> 32), (int) zero, (int) (zero >>> 32)},
                            new int[0]);
                else if (sn instanceof ShiftNoiseFunction.ShiftB) r = emit(e, OP_NOISEB, new int[0], new int[]{stack}, new int[0]);
                else if (sn instanceof ShiftNoiseFunction.Shift)
                    r = emit(e, OP_NOISE, new int[0], new int[]{stack | 1 << 16, (int) q, (int) (q >>> 32), (int) q, (int) (q >>> 32)},
                            new int[0]);
                else throw unsupported(f);
                return emitImm(e, OP_MULC, r, fb(ShiftNoiseFunction.VALUE_FACTOR));
            }
            if (f instanceof NoiseFunction n) {
                boolean shifted = !isZero(n.shiftX()) || !isZero(n.shiftY()) || !isZero(n.shiftZ());
                if (shifted && (!e.point || !isZero(n.shiftY()))) throw unsupported(f);
                ResourceKey<net.minecraft.world.level.levelgen.synth.NormalNoise> key = n.noise().unwrapKey()
                        .orElseThrow(() -> unsupported(f));
                int stack = stack(random.getOrCreateNoise(key));
                long xz = Double.doubleToRawLongBits(n.xzScale()), y = Double.doubleToRawLongBits(n.yScale());
                if (shifted) {
                    // NoiseFunction.ShiftedXzSampler.sampleValue
                    int sx = compile(n.shiftX(), e), sz = compile(n.shiftZ(), e);
                    return emit(e, OP_SNOISE, new int[]{sx, sz}, new int[]{stack | 1 << 16, (int) xz, (int) (xz >>> 32), (int) y,
                            (int) (y >>> 32)}, new int[0]);
                }
                return emit(e, OP_NOISE, new int[0], new int[]{stack | (e.point ? 1 << 16 : 0), (int) xz, (int) (xz >>> 32),
                        (int) y, (int) (y >>> 32)}, new int[0]);
            }
            if (f instanceof BinaryFunction(BinaryFunction.Type type, DensityFunction l0, DensityFunction r0)) {
                DensityFunction l = unwrap(l0), r = unwrap(r0);
                ConstantFunction lc = l instanceof ConstantFunction c ? c : null, rc = r instanceof ConstantFunction c ? c : null;
                switch (type) {
                    case ADD -> {
                        if (lc != null) return emitImm(e, OP_ADDC, compile(r, e), fb(lc.value()));
                        if (rc != null) return emitImm(e, OP_ADDC, compile(l, e), fb(rc.value()));
                        return emit(e, OP_ADD, compile(l, e), compile(r, e));
                    }
                    case SUB -> {
                        if (lc != null) return emitImm(e, OP_CSUB, compile(r, e), fb(lc.value()));
                        if (rc != null) return emitImm(e, OP_ADDC, compile(l, e), fb(-rc.value()));
                        return emit(e, OP_SUB, compile(l, e), compile(r, e));
                    }
                    case MUL -> {
                        if (lc != null) return emitImm(e, OP_MULC, compile(r, e), fb(lc.value()));
                        if (rc != null) return emitImm(e, OP_MULC, compile(l, e), fb(rc.value()));
                        return emit(e, e.point ? OP_MUL_P : OP_MUL_V, compile(l, e), compile(r, e));
                    }
                    case DIV -> {
                        if (lc != null) return emitImm(e, OP_CDIV, compile(r, e), fb(lc.value()));
                        if (rc != null) return emitImm(e, OP_MULC, compile(l, e), fb(1.0F / rc.value()));
                        return emit(e, e.point ? OP_DIV_P : OP_DIV_V, compile(l, e), compile(r, e));
                    }
                    case MIN, MAX -> {
                        // BinaryFunction.compileSampler: disjoint ranges compile to one side; constants; else the samplers
                        Interval lr = l0.range(), rr = r0.range();
                        boolean min = type == BinaryFunction.Type.MIN;
                        if (min ? lr.max() < rr.min() : lr.min() > rr.max()) return compile(l, e);
                        if (min ? rr.max() < lr.min() : rr.min() > lr.max()) return compile(r, e);
                        if (lc != null) return emitImm(e, min ? (e.point ? OP_MINC_P : OP_MINC_V) : (e.point ? OP_MAXC_P : OP_MAXC_V),
                                compile(r, e), fb(lc.value()));
                        if (rc != null) return emitImm(e, min ? (e.point ? OP_MINC_P : OP_MINC_V) : (e.point ? OP_MAXC_P : OP_MAXC_V),
                                compile(l, e), fb(rc.value()));
                        int a = compile(l, e), b = compile(r, e);
                        if (!e.point) return emit(e, min ? OP_MIN_V : OP_MAX_V, a, b);
                        return emit(e, min ? OP_MIN_P : OP_MAX_P, new int[]{a, b}, new int[]{fb(min ? rr.min() : rr.max())}, new int[0]);
                    }
                }
            }
            if (f instanceof UnaryFunction(UnaryFunction.Type type, DensityFunction in)) {
                int a = compile(in, e);
                return switch (type) {
                    case ABS -> emit(e, OP_ABS, a);
                    case SQUARE -> emit(e, OP_SQUARE, a);
                    case CUBE -> emit(e, OP_CUBE, a);
                    case SQUEEZE -> emit(e, OP_SQUEEZE, a);
                    case NEGATE -> emit(e, OP_NEG, a);
                    case HALF_NEGATIVE -> emitImm(e, OP_LEAKY, a, fb(0.5F));
                    case QUARTER_NEGATIVE -> emitImm(e, OP_LEAKY, a, fb(0.25F));
                    case RECIPROCAL -> emit(e, OP_RECIP, a);
                    case SIGN -> emit(e, OP_SIGN, a);
                    default -> throw unsupported(f);  // sqrt, log: not exact on a GPU
                };
            }
            if (f instanceof ClampFunction(DensityFunction in, float lo, float hi)) {
                return emit(e, OP_CLAMP, new int[]{compile(in, e)}, new int[]{fb(lo), fb(hi)}, new int[0]);
            }
            if (f instanceof RangeChoiceFunction(DensityFunction in, float lo, float hi, DensityFunction a0, DensityFunction b0)) {
                DensityFunction a = unwrap(a0), b = unwrap(b0);
                int input = compile(in, e);
                if (a instanceof ConstantFunction(float av) && b instanceof ConstantFunction(float bv))
                    return emit(e, OP_RANGEC, new int[]{input}, new int[]{fb(lo), fb(hi), fb(av), fb(bv)}, new int[0]);
                int ra = compile(a, e), rb = compile(b, e);
                // word: in = a, when in range = b; immediates: the other register, lo, hi
                return emit(e, OP_RANGE, new int[]{input, ra}, new int[]{rb, fb(lo), fb(hi)}, new int[]{0});
            }
            if (f instanceof IntervalSelectFunction(DensityFunction in, var thresholds, List<DensityFunction> fns)) {
                int input = compile(in, e);
                int n = thresholds.size();
                int[] imm = new int[1 + n + n + 1];
                int[] regPos = new int[n + 1];
                imm[0] = n;
                for (int i = 0; i < n; i++) imm[1 + i] = fb(thresholds.getFloat(i));
                for (int i = 0; i <= n; i++) {
                    imm[1 + n + i] = compile(fns.get(i), e);
                    regPos[i] = 1 + n + i;
                }
                return emit(e, OP_ISEL, new int[]{input}, imm, regPos);
            }
            if (f instanceof InterpolatedFunction interp) {
                if (interp.cellSizeXz() != 4 || interp.cellSizeY() != 8) throw unsupported(f);
                if (e.point) throw new IllegalStateException("interpolated function sampled by point: not in the kernel: " + f);
                Integer k = interpIds.get(interp);
                if (k == null) {
                    k = interps.size();
                    interps.add(interp);
                    interpIds.put(interp, k);
                }
                return emit(e, OP_INTERP, new int[0], new int[]{k}, new int[0]);
            }
            if (f.getClass().getName().startsWith("com.nirncraft.")
                    && f.domainAxes() == (DensityFunction.AXIS_X | DensityFunction.AXIS_Z)) {
                Integer id = fieldIds.get(f);
                if (id == null) {
                    id = fields.size();
                    fields.add(f);
                    fieldIds.put(f, id);
                }
                return emit(e, OP_COL, new int[0], new int[]{id}, new int[0]);
            }
            throw unsupported(f);
        }

        static boolean isZero(DensityFunction f) {
            return unwrap(f) instanceof ConstantFunction(float v) && v == 0.0F;
        }

        static IllegalStateException unsupported(Object f) {
            return new IllegalStateException("the GPU generator does not implement " + f.getClass().getSimpleName() + ": " + f);
        }

        int stack(Noise noise) {
            Integer id = stackIds.get(noise);
            if (id != null) return id;
            try {
                if (noise.getClass() != NoiseStack.class && !noise.getClass().getName().equals(NoiseStack.class.getName() + "$Perlin"))
                    throw unsupported(noise);
                Field layersF = field(NoiseStack.class, "layers");
                Field perms = field(GradientNoise.class, "perms"), ox = field(GradientNoise.class, "offsetX"),
                        oy = field(GradientNoise.class, "offsetY"), oz = field(GradientNoise.class, "offsetZ");
                Class<?> layerC = Class.forName(NoiseStack.class.getName() + "$Layer");
                Field lNoise = field(layerC, "noise"), lFreq = field(layerC, "frequency"), lAmp = field(layerC, "amplitude");
                Object[] ls = (Object[]) layersF.get(noise);
                int first = layers.size();
                for (Object l : ls) {
                    Object perlin = lNoise.get(l);
                    if (perlin.getClass() != PerlinNoise.class) throw unsupported(perlin);
                    int[] w = new int[LAYER_WORDS];
                    putDouble(w, 0, lFreq.getDouble(l));
                    putDouble(w, 2, ox.getDouble(perlin));
                    putDouble(w, 4, oy.getDouble(perlin));
                    putDouble(w, 6, oz.getDouble(perlin));
                    w[8] = Float.floatToRawIntBits(lAmp.getFloat(l));
                    byte[] p = (byte[]) perms.get(perlin);
                    for (int i = 0; i < 256; i++) w[12 + (i >> 2)] |= (p[i] & 255) << ((i & 3) * 8);
                    layers.add(w);
                }
                id = stacks.size();
                stacks.add(new int[]{first, ls.length});
                stackIds.put(noise, id);
                return id;
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("noise tables unavailable", ex);
            }
        }

        // ---- material rules ----

        int block(BlockState s) {
            Integer id = blockIds.get(s);
            if (id != null) return id;
            id = blocks.size();
            blocks.add(s);
            blockIds.put(s, id);
            return id;
        }

        void rule(MaterialRule r) {
            for (;;) {
                if (r instanceof MaterialRule.HolderHolder(Holder<MaterialRule> h)) r = h.value();
                else if (r instanceof FastSurfaceRule.Source s) r = s.rule();
                else break;
            }
            if (r instanceof SequenceRule(List<MaterialRule> seq)) {
                for (MaterialRule child : seq) rule(child);
            } else if (r instanceof ConditionRule(MaterialCondition ifTrue, MaterialRule then)) {
                int at = rules.size();
                rules.add(R_TEST | cond(ifTrue) << 8);
                rules.add(0);
                rule(then);
                rules.set(at + 1, rules.size());
            } else if (r instanceof BlockRule(BlockState state)) {
                rules.add(R_BLOCK | block(state) << 8);
            } else if (r == BandlandsRule.INSTANCE) {
                rules.add(R_BANDS);
            } else if (r instanceof OreVeinRule o) {
                int k = ores.indexOf(o);
                if (k < 0) {
                    k = ores.size();
                    ores.add(o);
                }
                rules.add(R_ORE | k << 8);
            } else {
                throw unsupported(r);
            }
        }

        int cond(MaterialCondition c) {
            while (c instanceof MaterialCondition.HolderHolder(Holder<MaterialCondition> h)) c = h.value();
            Integer id = condIds.get(c);
            if (id != null) return id;
            int[] w = new int[8];
            if (c instanceof StoneDepthCondition(int offset, boolean addSurface, int secondary, CaveSurface surface)) {
                w[0] = C_STONE_DEPTH;
                w[1] = offset;
                w[2] = addSurface ? 1 : 0;
                w[3] = secondary;
                w[4] = surface == CaveSurface.CEILING ? 1 : 0;
            } else if (c instanceof WaterCondition(int offset, int mult, boolean addStone)) {
                w[0] = C_WATER;
                w[1] = offset;
                w[2] = mult;
                w[3] = addStone ? 1 : 0;
            } else if (c instanceof YCondition(var anchor, int mult, boolean addStone)) {
                w[0] = C_Y;
                w[1] = anchor.resolveY(genContext);
                w[2] = mult;
                w[3] = addStone ? 1 : 0;
            } else if (c == SteepCondition.INSTANCE) {
                w[0] = C_STEEP;
            } else if (c == HoleCondition.INSTANCE) {
                w[0] = C_HOLE;
            } else if (c == AbovePreliminarySurfaceCondition.INSTANCE) {
                w[0] = C_ABOVE_PRELIM;
            } else if (c instanceof NoiseThresholdCondition(var noise, double min, double max, boolean is3d)) {
                w[0] = C_NOISE;
                w[1] = stack(random.getOrCreateNoise(noise));
                w[2] = is3d ? 1 : 0;
                putDouble(w, 3, min);
                putDouble(w, 5, max);
            } else if (c instanceof VerticalGradientCondition(Identifier name, var trueAt, var falseAt)) {
                w[0] = C_VGRAD;
                w[1] = trueAt.resolveY(genContext);
                w[2] = falseAt.resolveY(genContext);
                w[3] = -1 - extraSeeds.size();  // resolved to a word index in assemble
                extraSeeds.add(seeds(random.getOrCreateRandomFactory(name)));
            } else if (c instanceof BiomeCondition(HolderSet<Biome> set)) {
                w[0] = C_BIOME;
                w[1] = biomeSets.size();
                biomeSets.add(set);
            } else if (c instanceof NotCondition(MaterialCondition target)) {
                w[0] = C_NOT;
                w[1] = cond(target);
            } else {
                throw unsupported(c);
            }
            id = conds.size();
            conds.add(w);
            condIds.put(c, id);
            return id;
        }

        static long[] seeds(PositionalRandomFactory f) {
            try {
                Class<?> c = f.getClass();
                return new long[]{field(c, "seedLo").getLong(f), field(c, "seedHi").getLong(f)};
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("not a Xoroshiro positional factory: " + f.getClass(), e);
            }
        }

        // ---- assembly ----

        /** Linear-scan register allocation of an entry, then its words. */
        void encode(Entry e, List<Integer> code) {
            int[] lastUse = new int[e.nextReg];
            java.util.Arrays.fill(lastUse, -1);
            for (int i = 0; i < e.code.size(); i++) {
                Ins ins = e.code.get(i);
                for (int s : ins.src) lastUse[s] = i;
                for (int p : ins.immRegs) lastUse[ins.imm[p]] = i;
            }
            int[] phys = new int[e.nextReg];
            java.util.Arrays.fill(phys, -1);
            java.util.ArrayDeque<Integer> free = new java.util.ArrayDeque<>();
            int used = 0;
            for (int i = 0; i < e.code.size(); i++) {
                Ins ins = e.code.get(i);
                int[] src = new int[ins.src.length];
                for (int k = 0; k < src.length; k++) src[k] = phys[ins.src[k]];
                int[] imm = ins.imm.clone();
                for (int p : ins.immRegs) imm[p] = phys[ins.imm[p]];
                // release what dies here, then give the result a register
                for (int s : ins.src) if (lastUse[s] == i && phys[s] >= 0 && !free.contains(phys[s])) free.push(phys[s]);
                for (int p : ins.immRegs) {
                    int s = ins.imm[p];
                    if (lastUse[s] == i && phys[s] >= 0 && !free.contains(phys[s])) free.push(phys[s]);
                }
                int d;
                if (ins.op == OP_OUT) d = -ins.dst - 1;  // the output slot
                else {
                    d = free.isEmpty() ? used++ : free.pop();
                    phys[ins.dst] = d;
                    if (lastUse[ins.dst] < 0) free.push(d);  // never read (cannot happen but be safe)
                }
                if (used > REGS) throw new IllegalStateException("the program needs more than " + REGS + " registers");
                int a = src.length > 0 ? src[0] : 0, b = src.length > 1 ? src[1] : 0;
                code.add(ins.op | d << 8 | a << 16 | b << 24);
                for (int w : imm) code.add(w);
            }
            maxRegs = Math.max(maxRegs, used);
        }

        void assemble(ToIntFunction<BlockState> material, MaterialSystem system, int[] bandIds) {
            List<Integer> w = new ArrayList<>();
            for (int i = 0; i < HEADER; i++) w.add(0);
            w.set(0, MAGIC);
            w.set(H_VERSION, VERSION);
            w.set(H_K, interps.size());
            w.set(H_FIELDS, fields.size());
            // layers, stacks
            w.set(H_LAYERS, w.size());
            for (int[] l : layers) for (int v : l) w.add(v);
            w.set(H_STACKS, w.size());
            for (int[] s : stacks) {
                w.add(s[0]);
                w.add(s[1]);
            }
            // code
            w.set(H_CODE, w.size());
            List<Integer> code = new ArrayList<>();
            for (int e = 0; e < E_MAX; e++) {
                if (entries[e] == null) continue;
                int start = code.size();
                encode(entries[e], code);
                w.set(H_ENTRY + e * 2, start);
                w.set(H_ENTRY + e * 2 + 1, code.size());
            }
            if (climate != null)
                for (int k = 0; k < climate.length; k++) {
                    int start = code.size();
                    encode(climate[k], code);
                    w.set(S_CLIMATE + k * 2, start);
                    w.set(S_CLIMATE + k * 2 + 1, code.size());
                }
            w.addAll(code);
            w.set(H_REGS, maxRegs);
            // the fields' kinds and the height noises (atlas_page.slang)
            w.set(S_FKINDS, w.size());
            for (DensityFunction f : fields) w.add(fieldKind(f));
            for (int i = 0; i < 4; i++) w.set(S_HNOISE + i, heightStacks[i]);
            w.set(S_PAGEGEN, climate != null ? 1 : 0);
            // seeds of the vertical gradients
            int seedsAt = w.size();
            for (long[] s : extraSeeds) {
                w.add((int) s[0]);
                w.add((int) (s[0] >>> 32));
                w.add((int) s[1]);
                w.add((int) (s[1] >>> 32));
            }
            // rules, conditions, biome sets
            w.set(H_RULES, w.size());
            w.addAll(rules);
            w.set(S_RULESTART, 0);
            w.set(H_CONDS, w.size());
            for (int[] c : conds) {
                if (c[0] == C_VGRAD) c[3] = seedsAt + (-1 - c[3]) * 4;
                for (int v : c) w.add(v);
            }
            int setWords = (biomes.size() + 31) / 32;
            w.set(H_SETWORDS, setWords);
            w.set(H_BIOMESETS, w.size());
            for (HolderSet<Biome> set : biomeSets) {
                int[] bits = new int[setWords];
                for (int i = 0; i < biomes.size(); i++) if (set.contains(biomes.get(i))) bits[i >> 5] |= 1 << (i & 31);
                for (int v : bits) w.add(v);
            }
            // ores
            w.set(H_ORES, w.size());
            for (int i = 0; i < ores.size(); i++) {
                OreVeinRule o = ores.get(i);
                w.add(E_ORES + i * 3);
                w.add(E_ORES + i * 3 + 1);
                w.add(E_ORES + i * 3 + 2);
                w.add(blockIds.get(o.oreBlock()));
                w.add(blockIds.get(o.rawOreBlock()));
                w.add(blockIds.get(o.fillerBlock()));
                w.add(Float.floatToRawIntBits(o.rawOreChance()));
                w.add(0);
            }
            // bands
            w.set(H_BANDS, w.size());
            for (int b : bandIds) w.add(b);
            // blocks (last: the bands and ores added theirs)
            w.set(H_BLOCKS, w.size());
            w.set(H_NBLOCKS, blocks.size());
            for (BlockState s : blocks) {
                int flags = 0;
                boolean fluid = !s.getFluidState().isEmpty();
                if (s.isAir()) flags |= F_AIR;
                if (fluid) flags |= F_FLUID;
                if (s.is(Blocks.WATER)) flags |= F_WATER;
                if (s.is(Blocks.LAVA)) flags |= F_LAVA;
                if (net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG.isOpaque().test(s)) flags |= F_MOTION;  // the heightmap's own test
                if (!fluid && s.canBeReplaced() && !s.isSolid()) flags |= F_SOFT;
                w.add(flags);
                w.add(material == null ? blockIds.get(s) : material.applyAsInt(s));  // null: the generator id itself
            }
            // scalars
            w.set(S_SEA, settings.seaLevel());
            putSeeds(w, S_AQ_LO, seeds(random.getOrCreateRandomFactory(Identifier.withDefaultNamespace("aquifer"))));
            putSeeds(w, S_ORE_LO, seeds(random.getOrCreateRandomFactory(Identifier.withDefaultNamespace("ore"))));
            putSeeds(w, S_SURF_LO, seeds((PositionalRandomFactory) get(MaterialSystem.class, system, "noiseRandom")));
            long zoom = BiomeManager.obfuscateSeed(seed);
            w.set(S_ZOOM, (int) zoom);
            w.set(S_ZOOM + 1, (int) (zoom >>> 32));
            w.set(S_SURFNOISE, surfNoise);
            w.set(S_SECNOISE, secNoise);
            w.set(S_CLAYNOISE, clayNoise);
            w.set(S_STONE, 1);
            w.set(S_WATER, 2);
            w.set(S_LAVA, 3);
            w.set(S_WAYBELOW, DimensionType.WAY_BELOW_MIN_Y);
            w.set(S_LAVALEVEL, -54);  // NoiseBasedChunkGenerator.createFluidPicker
            w.set(S_ORECOUNT, ores.size());
            w.set(S_NBIOMES, biomes.size());
            out = new int[w.size()];
            for (int i = 0; i < out.length; i++) out[i] = w.get(i);
            if (stacks.size() * 2 != out[H_CODE] - out[H_STACKS])
                throw new IllegalStateException("noise stacks appeared after layout: compile them first");
        }

        static void putSeeds(List<Integer> w, int at, long[] s) {
            w.set(at, (int) s[0]);
            w.set(at + 1, (int) (s[0] >>> 32));
            w.set(at + 2, (int) s[1]);
            w.set(at + 3, (int) (s[1] >>> 32));
        }
    }

    static void putDouble(int[] t, int w, double v) {
        long b = Double.doubleToRawLongBits(v);
        t[w] = (int) b;
        t[w + 1] = (int) (b >>> 32);
    }

    static Field field(Class<?> c, String name) throws NoSuchFieldException {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    static Object get(Class<?> c, Object o, String name) {
        try {
            return field(c, name).get(o);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(c.getSimpleName() + "." + name + " unavailable", e);
        }
    }
}
