package com.skycraft.tamriel.biome.flora;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.TamrielMap;
import com.skycraft.tamriel.biome.BiomeBlocks;
import com.skycraft.tamriel.biome.feature.GenBox;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.skycraft.worldgen.ConfiguredFeature;
import com.skycraft.worldgen.FeaturePlaceContext;
import com.skycraft.worldgen.FeatureConfiguration;

import java.util.List;

/**
 * {@code skycraft:flora_tree}: grows one tree of a species picked from a weighted list. With {@code stands} above 0
 * the weights follow a slow noise per species, so species gather in stands (a knot of aspens in a pine wood, a
 * grove of oaks among elms) instead of a uniform salt-and-pepper mix. Placed by {@code skycraft:flora_scatter}.
 */
public class FloraTreeFeature extends ConfiguredFeature<FloraTreeFeature.Config> {
    public record Entry(TreeSpecies species, int weight) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                TreeSpecies.CODEC.fieldOf("species").forGetter(Entry::species),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("weight", 1).forGetter(Entry::weight)
        ).apply(i, Entry::new));
    }

    public record Config(List<Entry> species, float stands, float standScale) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                Entry.CODEC.listOf().fieldOf("species").forGetter(Config::species),
                Codec.floatRange(0f, 1f).optionalFieldOf("stands", 0.6f).forGetter(Config::stands),
                Codec.floatRange(4f, 2048f).optionalFieldOf("stand_scale", 48f).forGetter(Config::standScale)
        ).apply(i, Config::new));
    }

    public FloraTreeFeature(Codec<Config> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        WorldGenLevel level = ctx.level();
        BlockPos o = ctx.origin();
        int x = o.getX(), y = o.getY(), z = o.getZ();
        GenBox box = GenBox.of(level, o);
        long seed = level.getSeed();
        TreeSpecies sp = pick(ctx.config(), seed, x, z, ctx.random().nextDouble());
        if (sp == null) return false;
        // no tree (trunk or crown) where a road, street, plot, pad or site will be: skipped whole, never cut later
        if (!com.skycraft.world.PlacementMask.clear(level, x, z, sp.crownReach())) return false;
        int water = Math.max(0, box.surfaceWithWater(x, z) - y);
        if (water > sp.maxWater) return false;
        BlockState here = box.get(x, y, z);
        if (!GenBox.isSoft(here) || here.is(BlockTags.LOGS)) return false;
        if (!soilOk(sp.soil, box.get(x, y - 1, z))) return false;
        if (y > (level.getMaxY() + 1) - sp.maxHeight - 12) return false;

        TreeBuilder b = new TreeBuilder(box, ctx.random());
        double wind = FloraNoise.value(x / 900.0, z / 900.0, seed + 31) * Math.PI * 0.8 + Math.PI * 0.15;
        TreeShapes.Grow g = new TreeShapes.Grow(b, sp, x, y, z, wind, water);
        realmTint(g, x, z);
        TreeShapes.grow(g);
        return b.commit(x, y, z);
    }

    private static TreeSpecies pick(Config cfg, long seed, int x, int z, double roll) {
        List<Entry> list = cfg.species();
        if (list.isEmpty()) return null;
        if (list.size() == 1) return list.get(0).species();
        double[] w = new double[list.size()];
        double total = 0;
        for (int i = 0; i < w.length; i++) {
            double n = cfg.stands() > 0
                    ? FloraNoise.fbm(x / (double) cfg.standScale(), z / (double) cfg.standScale(), seed + 1013L * (i + 1), 2) : 0;
            w[i] = list.get(i).weight() * Math.exp(cfg.stands() * 3.2 * n);
            total += w[i];
        }
        double t = roll * total;
        for (int i = 0; i < w.length; i++) {
            t -= w[i];
            if (t <= 0) return list.get(i).species();
        }
        return list.get(w.length - 1).species();
    }

    static boolean soilOk(int soil, BlockState ground) {
        if ((soil & TreeSpecies.DIRT) != 0 && (ground.is(BlockTags.DIRT) || ground.is(Blocks.MUD))) return true;
        if ((soil & TreeSpecies.SAND) != 0 && (ground.is(BlockTags.SAND) || ground.is(BiomeBlocks.DUNE_SAND.get())
                || ground.is(Blocks.GRAVEL) || ground.is(Blocks.CLAY))) return true;
        if ((soil & TreeSpecies.STONE) != 0 && (ground.is(BlockTags.BASE_STONE_OVERWORLD) || ground.is(Blocks.GRAVEL)
                || ground.is(Blocks.CALCITE) || ground.is(BiomeBlocks.FROSTED_STONE.get())
                || ground.is(BiomeBlocks.CORAL_STONE.get()) || ground.is(Blocks.COBBLESTONE)
                || ground.is(Blocks.MOSSY_COBBLESTONE))) return true;
        if ((soil & TreeSpecies.ASH) != 0 && (ground.is(BiomeBlocks.ASH_BLOCK.get()) || ground.is(BiomeBlocks.VOLCANIC_ROCK.get())
                || ground.is(BiomeBlocks.CRACKED_VOLCANIC_ROCK.get()))) return true;
        return (soil & TreeSpecies.SNOW) != 0 && (ground.is(Blocks.SNOW_BLOCK) || ground.is(Blocks.POWDER_SNOW)
                || ground.is(Blocks.PACKED_ICE));
    }

    /**
     * The endless lands beyond Tamriel reuse Tamriel's biomes; each generated realm gets its own foliage cast (gold,
     * flame, blossom, pale or none), the same over the whole realm, so a far continent's oak woods look foreign.
     */
    private static void realmTint(TreeShapes.Grow g, int x, int z) {
        if (TamrielMap.inTamriel(x, z)) return;
        Region region = TamrielMap.region(x, z);
        if (region == null || !"realm".equals(region.province())) return;
        int cast = Math.floorMod(region.id().hashCode() * 0x9E3779B1, 5);
        BlockState to = switch (cast) {
            case 1 -> BiomeBlocks.GOLDEN_ASPEN_LEAVES.get().defaultBlockState();
            case 2 -> BiomeBlocks.ORANGE_ASPEN_LEAVES.get().defaultBlockState();
            case 3 -> Blocks.CHERRY_LEAVES.defaultBlockState();
            case 4 -> Blocks.AZALEA_LEAVES.defaultBlockState();
            default -> null;
        };
        if (to == null) return;
        BlockState l = g.leaves;
        if (l.is(Blocks.OAK_LEAVES) || l.is(Blocks.DARK_OAK_LEAVES) || l.is(Blocks.BIRCH_LEAVES) || l.is(Blocks.JUNGLE_LEAVES)
                || l.is(Blocks.ACACIA_LEAVES) || l.is(Blocks.MANGROVE_LEAVES)) {
            g.accent = g.leaves;
            g.leaves = to;
        }
    }
}
