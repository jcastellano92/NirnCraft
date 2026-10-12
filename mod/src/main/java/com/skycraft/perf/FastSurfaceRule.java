package com.skycraft.perf;

import com.mojang.serialization.Codec;
import com.skycraft.Skycraft;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Surface rule {@code skycraft:fast_surface}: {@code {"type": "skycraft:fast_surface", "rule": <any surface rule>}}
 * builds exactly the blocks {@code rule} builds, faster.
 *
 * <p>The Tamriel surface rule is a long sequence of {@code if biome is X then ...} entries (one per biome, about a
 * hundred). Vanilla walks that sequence for every block in the surface window of every column: each entry re-tests its
 * biome list, and when the matching biome's rules return nothing (most blocks below the top layer) it keeps testing all
 * the others. The worldgen benchmark measured the surface stage at ~40% of all chunk generation, most of it in those
 * biome tests. This wrapper rewrites the rule tree once: every sequence holding biome tests becomes a switch that looks
 * the column's biome up once and runs only the entries that can match it (that biome's entries and the entries without
 * a biome test, in the original order). Skipped entries are exactly those whose biome test would fail, so the result
 * is identical for every block.</p>
 *
 * <p>The rule classes involved are package-private in Minecraft, so the tree is read through reflection by field
 * type (stable across mappings). If anything about that fails, the original rule is used unchanged.</p>
 */
public final class FastSurfaceRule extends SurfaceRules {
    // (extends SurfaceRules only to reach its protected nested types Context and SurfaceRule; never instantiated)
    private FastSurfaceRule() {}

    /** The optimised tree (computed once per decoded rule). */
    private static final Map<SurfaceRules.RuleSource, SurfaceRules.RuleSource> OPTIMISED = new IdentityHashMap<>();
    /** Sequences with fewer biome tests than this are left alone. */
    private static final int MIN_TESTS = 3;

    /** The {@code skycraft:fast_surface} rule source as decoded from JSON. */
    public record Source(SurfaceRules.RuleSource rule) implements SurfaceRules.RuleSource {
        public static final KeyDispatchDataCodec<Source> CODEC = KeyDispatchDataCodec.of(
                SurfaceRules.RuleSource.CODEC.fieldOf("rule").xmap(Source::new, Source::rule));

        @Override
        public KeyDispatchDataCodec<? extends SurfaceRules.RuleSource> codec() {
            return CODEC;
        }

        @Override
        public SurfaceRule apply(Context ctx) {
            if (ColumnCache.OFF) return rule.apply(ctx);
            return optimised().apply(ctx);
        }

        private SurfaceRules.RuleSource optimised() {
            synchronized (OPTIMISED) {
                return OPTIMISED.computeIfAbsent(rule, r -> {
                    if (!Reflect.ok()) return r;
                    try {
                        int[] stats = new int[2];
                        SurfaceRules.RuleSource out = transform(r, stats);
                        Skycraft.LOGGER.info("[perf] fast surface rule: {} biome-test sequences ({} biome tests) turned into switches",
                                stats[0], stats[1]);
                        return out;
                    } catch (ReflectiveOperationException | RuntimeException e) {
                        Skycraft.LOGGER.warn("[perf] could not optimise the surface rule; using it as is", e);
                        return r;
                    }
                });
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ tree rewrite

    private static SurfaceRules.RuleSource transform(SurfaceRules.RuleSource src, int[] stats) throws ReflectiveOperationException {
        if (src instanceof Source f) return transform(f.rule(), stats);
        if (src.getClass() == Reflect.SEQUENCE) {
            List<SurfaceRules.RuleSource> children = Reflect.sequence(src);
            List<SurfaceRules.RuleSource> out = new ArrayList<>(children.size());
            List<List<ResourceKey<Biome>>> tests = new ArrayList<>(children.size());
            int biomeTests = 0;
            for (SurfaceRules.RuleSource c : children) {
                List<ResourceKey<Biome>> biomes = null;
                SurfaceRules.RuleSource body = c;
                if (c.getClass() == Reflect.TEST) {
                    SurfaceRules.ConditionSource cond = Reflect.condition(c);
                    if (cond.getClass() == Reflect.BIOME) {
                        biomes = Reflect.biomes(cond);
                        body = Reflect.thenRun(c);
                        biomeTests++;
                    }
                }
                tests.add(biomes);
                out.add(transform(body, stats));
            }
            if (biomeTests >= MIN_TESTS) {
                stats[0]++;
                stats[1] += biomeTests;
                return new BiomeSwitch(out, tests);
            }
            // not worth a switch: rebuild the sequence, putting back the biome tests that were unwrapped
            SurfaceRules.RuleSource[] rebuilt = new SurfaceRules.RuleSource[out.size()];
            for (int i = 0; i < rebuilt.length; i++) {
                List<ResourceKey<Biome>> b = tests.get(i);
                rebuilt[i] = b == null ? out.get(i) : SurfaceRules.ifTrue(Reflect.condition(children.get(i)), out.get(i));
            }
            return SurfaceRules.sequence(rebuilt);
        }
        if (src.getClass() == Reflect.TEST) {
            return SurfaceRules.ifTrue(Reflect.condition(src), transform(Reflect.thenRun(src), stats));
        }
        return src;
    }

    /**
     * A sequence whose biome-tested entries are dispatched by the column's biome. {@code tests[i]} is the biome list of
     * entry {@code i} (its body is {@code bodies[i]}), or null for an entry without a biome test.
     */
    private static final class BiomeSwitch implements SurfaceRules.RuleSource {
        private final SurfaceRules.RuleSource[] bodies;
        /** Entry indexes to try for each biome, in order (shared by every chunk's rule). */
        private final Plan plan;

        BiomeSwitch(List<SurfaceRules.RuleSource> bodies, List<List<ResourceKey<Biome>>> tests) {
            this.bodies = bodies.toArray(new SurfaceRules.RuleSource[0]);
            this.plan = new Plan(tests);
        }

        @Override
        public KeyDispatchDataCodec<? extends SurfaceRules.RuleSource> codec() {
            throw new UnsupportedOperationException("runtime only");
        }

        @Override
        public SurfaceRule apply(Context ctx) {
            SurfaceRule[] rules = new SurfaceRule[bodies.length];
            for (int i = 0; i < rules.length; i++) rules[i] = bodies[i].apply(ctx);
            return new Rule(ctx, rules, plan);
        }
    }

    private static final class Plan {
        final Map<ResourceKey<Biome>, int[]> byBiome = new IdentityHashMap<>();
        final int[] none;

        Plan(List<List<ResourceKey<Biome>>> tests) {
            List<Integer> plain = new ArrayList<>();
            java.util.Set<ResourceKey<Biome>> keys = new java.util.LinkedHashSet<>();
            for (int i = 0; i < tests.size(); i++) {
                if (tests.get(i) == null) plain.add(i);
                else keys.addAll(tests.get(i));
            }
            none = plain.stream().mapToInt(Integer::intValue).toArray();
            for (ResourceKey<Biome> k : keys) {
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < tests.size(); i++) {
                    List<ResourceKey<Biome>> t = tests.get(i);
                    if (t == null || t.contains(k)) order.add(i);
                }
                byBiome.put(k, order.stream().mapToInt(Integer::intValue).toArray());
            }
        }

        int[] order(@Nullable Holder<Biome> biome) {
            if (biome == null) return none;
            ResourceKey<Biome> key = biome.unwrapKey().orElse(null);
            if (key == null) return none;
            int[] o = byBiome.get(key);
            if (o == null) {
                // keys are interned, but look up by equality once in case one was not
                for (Map.Entry<ResourceKey<Biome>, int[]> e : byBiome.entrySet()) if (e.getKey().equals(key)) return e.getValue();
                return none;
            }
            return o;
        }
    }

    /** One chunk's switch: caches the order for the last biome supplier (the context makes a new one per block y). */
    private static final class Rule implements SurfaceRule {
        private final Context ctx;
        private final SurfaceRule[] rules;
        private final Plan plan;
        private Object lastSupplier;
        private int[] lastOrder;

        Rule(Context ctx, SurfaceRule[] rules, Plan plan) {
            this.ctx = ctx;
            this.rules = rules;
            this.plan = plan;
        }

        @Override
        public BlockState tryApply(int x, int y, int z) {
            Supplier<Holder<Biome>> s = Reflect.biome(ctx);
            int[] order;
            if (s == lastSupplier) {
                order = lastOrder;
            } else {
                order = plan.order(s == null ? null : s.get());
                lastSupplier = s;
                lastOrder = order;
            }
            for (int i : order) {
                BlockState state = rules[i].tryApply(x, y, z);
                if (state != null) return state;
            }
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------------ reflection

    /** Access to Minecraft's package-private surface rule classes, found by shape rather than by name. */
    private static final class Reflect {
        static Class<?> SEQUENCE, TEST, BIOME;
        static Field SEQ_LIST, TEST_COND, TEST_RULE, BIOME_LIST;
        static Field CTX_BIOME_FIELD;
        static final boolean OK = init();
        /** Static final so the JIT folds it: read once per block. */
        static final VarHandle CTX_BIOME = OK ? handle() : null;

        private static VarHandle handle() {
            try {
                return MethodHandles.privateLookupIn(Context.class, MethodHandles.lookup()).unreflectVarHandle(CTX_BIOME_FIELD);
            } catch (IllegalAccessException | RuntimeException e) {
                return null;
            }
        }

        static boolean ok() {
            return OK;
        }

        private static boolean init() {
            try {
                SurfaceRules.RuleSource air = SurfaceRules.state(Blocks.AIR.defaultBlockState());
                SEQUENCE = SurfaceRules.sequence(air, air).getClass();
                TEST = SurfaceRules.ifTrue(SurfaceRules.steep(), air).getClass();
                BIOME = SurfaceRules.isBiome(net.minecraft.world.level.biome.Biomes.PLAINS).getClass();
                SEQ_LIST = field(SEQUENCE, List.class);
                TEST_COND = field(TEST, SurfaceRules.ConditionSource.class);
                TEST_RULE = field(TEST, SurfaceRules.RuleSource.class);
                BIOME_LIST = field(BIOME, List.class);
                CTX_BIOME_FIELD = field(Context.class, Supplier.class);
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                Skycraft.LOGGER.warn("[perf] surface rule internals not accessible ({}); fast_surface is a no-op", e.toString());
                return false;
            }
        }

        /** The single declared field of {@code type} (fails if there are none or several). */
        private static Field field(Class<?> owner, Class<?> type) throws NoSuchFieldException {
            Field found = null;
            for (Field f : owner.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType() != type) continue;
                if (found != null) throw new NoSuchFieldException("several " + type.getSimpleName() + " fields in " + owner);
                found = f;
            }
            if (found == null) throw new NoSuchFieldException("no " + type.getSimpleName() + " field in " + owner);
            found.setAccessible(true);
            return found;
        }

        @SuppressWarnings("unchecked")
        static List<SurfaceRules.RuleSource> sequence(Object seq) throws IllegalAccessException {
            return (List<SurfaceRules.RuleSource>) SEQ_LIST.get(seq);
        }

        static SurfaceRules.ConditionSource condition(Object test) throws IllegalAccessException {
            return (SurfaceRules.ConditionSource) TEST_COND.get(test);
        }

        static SurfaceRules.RuleSource thenRun(Object test) throws IllegalAccessException {
            return (SurfaceRules.RuleSource) TEST_RULE.get(test);
        }

        @SuppressWarnings("unchecked")
        static List<ResourceKey<Biome>> biomes(Object cond) throws IllegalAccessException {
            return List.copyOf((List<ResourceKey<Biome>>) BIOME_LIST.get(cond));
        }

        @SuppressWarnings("unchecked")
        static Supplier<Holder<Biome>> biome(Context ctx) {
            if (CTX_BIOME != null) return (Supplier<Holder<Biome>>) CTX_BIOME.get(ctx);
            try {
                return (Supplier<Holder<Biome>>) CTX_BIOME_FIELD.get(ctx);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Codec for registration in {@code Registries.MATERIAL_RULE}. */
    public static Codec<Source> codecForRegistry() {
        return Source.CODEC.codec();
    }
}
