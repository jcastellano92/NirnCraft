package com.skycraft.perf;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.material.MaterialRuleContext;
import net.minecraft.world.level.levelgen.material.condition.BiomeCondition;
import net.minecraft.world.level.levelgen.material.condition.MaterialCondition;
import net.minecraft.world.level.levelgen.material.rule.ConditionRule;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.material.rule.RuleEvaluator;
import net.minecraft.world.level.levelgen.material.rule.SequenceRule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Exact biome dispatch for 26.3 material rules, using public records instead of reflective engine internals. */
public final class FastSurfaceRule {
    private static final int MIN_TESTS = 3;
    private FastSurfaceRule() {}

    /** The decoded wrapper owns its optimized tree, so reloads cannot retain another world's biome registry. */
    public static final class Source implements MaterialRule {
        public static final MapCodec<Source> CODEC = MaterialRule.CODEC.fieldOf("rule").xmap(Source::new, Source::rule);
        private final MaterialRule rule;
        private volatile MaterialRule optimized;
        public Source(MaterialRule rule) { this.rule = rule; }
        public MaterialRule rule() { return rule; }
        @Override public MapCodec<Source> codec() { return CODEC; }
        @Override public RuleEvaluator compile(MaterialRuleContext context) {
            if (ColumnCache.OFF) return rule.compile(context);
            MaterialRule current = optimized;
            if (current == null) {
                synchronized (this) {
                    current = optimized;
                    if (current == null) optimized = current = transform(rule);
                }
            }
            return current.compile(context);
        }
    }

    private static MaterialRule transform(MaterialRule original) {
        if (original instanceof Source source) return transform(source.rule());
        if (original instanceof MaterialRule.HolderHolder reference) return transform(reference.holder().value());
        if (original instanceof SequenceRule sequence) {
            List<MaterialRule> bodies = new ArrayList<>();
            List<HolderSet<Biome>> tests = new ArrayList<>();
            int count = 0;
            for (MaterialRule child : sequence.sequence()) {
                MaterialRule body = child;
                HolderSet<Biome> biomes = null;
                if (child instanceof ConditionRule conditional && unwrap(conditional.ifTrue()) instanceof BiomeCondition test) {
                    biomes = test.biomes();
                    body = conditional.thenRun();
                    count++;
                }
                tests.add(biomes);
                bodies.add(transform(body));
            }
            if (count >= MIN_TESTS) return new BiomeSwitch(bodies, tests);
            List<MaterialRule> rebuilt = new ArrayList<>(bodies.size());
            for (int i = 0; i < bodies.size(); i++) {
                rebuilt.add(tests.get(i) == null ? bodies.get(i)
                        : new ConditionRule(((ConditionRule) sequence.sequence().get(i)).ifTrue(), bodies.get(i)));
            }
            return new SequenceRule(List.copyOf(rebuilt));
        }
        if (original instanceof ConditionRule conditional) {
            return new ConditionRule(conditional.ifTrue(), transform(conditional.thenRun()));
        }
        return original;
    }

    private static MaterialCondition unwrap(MaterialCondition condition) {
        while (condition instanceof MaterialCondition.HolderHolder reference) condition = reference.holder().value();
        return condition;
    }

    private static final class BiomeSwitch implements MaterialRule {
        private final MaterialRule[] bodies;
        private final Map<Holder<Biome>, int[]> byBiome = new HashMap<>();
        private final int[] unmatched;

        private BiomeSwitch(List<MaterialRule> bodies, List<HolderSet<Biome>> tests) {
            this.bodies = bodies.toArray(MaterialRule[]::new);
            List<Integer> plain = new ArrayList<>();
            java.util.Set<Holder<Biome>> all = new java.util.LinkedHashSet<>();
            for (int i = 0; i < tests.size(); i++) {
                HolderSet<Biome> test = tests.get(i);
                if (test == null) plain.add(i); else test.forEach(all::add);
            }
            unmatched = plain.stream().mapToInt(Integer::intValue).toArray();
            for (Holder<Biome> biome : all) {
                List<Integer> order = new ArrayList<>();
                for (int i = 0; i < tests.size(); i++) {
                    HolderSet<Biome> test = tests.get(i);
                    if (test == null || test.contains(biome)) order.add(i);
                }
                byBiome.put(biome, order.stream().mapToInt(Integer::intValue).toArray());
            }
        }

        @Override public MapCodec<? extends MaterialRule> codec() {
            throw new UnsupportedOperationException("Compiled biome dispatch is not a data-pack rule");
        }

        @Override public RuleEvaluator compile(MaterialRuleContext context) {
            RuleEvaluator[] rules = new RuleEvaluator[bodies.length];
            for (int i = 0; i < rules.length; i++) rules[i] = bodies[i].compile(context);
            return new RuleEvaluator() {
                private Holder<Biome> previous;
                private int[] order = unmatched;
                @Override public BlockState tryApply(int x, int y, int z) {
                    Holder<Biome> biome = context.getBiome();
                    if (biome != previous) {
                        previous = biome;
                        order = byBiome.getOrDefault(biome, unmatched);
                    }
                    for (int i : order) {
                        BlockState state = rules[i].tryApply(x, y, z);
                        if (state != null) return state;
                    }
                    return null;
                }
            };
        }
    }

    public static MapCodec<Source> codecForRegistry() { return Source.CODEC; }
}
