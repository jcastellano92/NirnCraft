package com.nirncraft.tamriel.biome.flora;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.nirncraft.crafting.arcane.ArcaneRegistry;
import com.nirncraft.dungeons.world.Cultures;
import com.nirncraft.tamriel.MapSample;
import com.nirncraft.tamriel.Region;
import com.nirncraft.tamriel.TamrielMap;
import com.nirncraft.tamriel.biome.feature.GenBox;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;
import com.nirncraft.worldgen.ConfiguredFeature;
import com.nirncraft.worldgen.FeaturePlaceContext;
import com.nirncraft.worldgen.FeatureConfiguration;

import java.util.List;

/**
 * {@code nirncraft:ground_cover}: one tuft of ground cover (grass, ferns, flowers, berries, moss, mushrooms, reeds,
 * alchemy plants). Which plant grows is picked by a slow noise per entry, sharpened by {@code patchiness}: at 0 the
 * entries are mixed at random, near 1 each plant keeps to its own drifts (a sweep of lavender, a bank of ferns, a ring
 * of mushrooms). A tuft is {@code cluster} plants within {@code spread} blocks of the same kind. Placed by
 * {@code count + in_square + nirncraft:flora_density}, which decides where the drifts and the bare ground are. With
 * {@code near_water} the plants keep to the shore.
 */
public class GroundCoverFeature extends ConfiguredFeature<GroundCoverFeature.Config> {
    public record Entry(BlockState state, int weight) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockState.CODEC.fieldOf("state").forGetter(Entry::state),
                ExtraCodecs.POSITIVE_INT.optionalFieldOf("weight", 1).forGetter(Entry::weight)
        ).apply(i, Entry::new));
    }

    public record Config(List<Entry> entries, float scale, float patchiness, IntProvider cluster, int spread, int salt,
                         boolean nearWater) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                Entry.CODEC.listOf().fieldOf("entries").forGetter(Config::entries),
                Codec.floatRange(2f, 1024f).optionalFieldOf("scale", 24f).forGetter(Config::scale),
                Codec.floatRange(0f, 1f).optionalFieldOf("patchiness", 0.6f).forGetter(Config::patchiness),
                net.minecraft.util.valueproviders.IntProviders.codec(1, 32).optionalFieldOf("cluster", net.minecraft.util.valueproviders.ConstantInt.of(1))
                        .forGetter(Config::cluster),
                Codec.intRange(0, 6).optionalFieldOf("spread", 2).forGetter(Config::spread),
                Codec.INT.optionalFieldOf("salt", 0).forGetter(Config::salt),
                Codec.BOOL.optionalFieldOf("near_water", false).forGetter(Config::nearWater)
        ).apply(i, Config::new));
    }

    public GroundCoverFeature(Codec<Config> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Config cfg = ctx.config();
        WorldGenLevel level = ctx.level();
        RandomSource random = ctx.random();
        BlockPos o = ctx.origin();
        GenBox box = GenBox.of(level, o);
        BlockState state = pick(cfg, level.getSeed(), o.getX(), o.getZ(), random.nextDouble());
        if (state == null) return false;
        int n = cfg.cluster().sample(random);
        int placed = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int k = 0; k < n; k++) {
            int x = o.getX(), z = o.getZ();
            if (k > 0 && cfg.spread() > 0) {
                x += Math.round((random.nextFloat() - random.nextFloat()) * cfg.spread() * 1.3f);
                z += Math.round((random.nextFloat() - random.nextFloat()) * cfg.spread() * 1.3f);
            }
            // stay in the decorated chunk: its heightmaps are the only ones ready
            if ((x >> 4) != box.center.x() || (z >> 4) != box.center.z()) continue;
            // not on a road, street, plot or pad (the paver would only have to clear it again)
            if (!com.nirncraft.world.PlacementMask.clear(level, x, z, 0)) continue;
            int y = findTrueGroundY(level, x, z);
            if (y <= level.getMinY() + 1) continue;
            p.set(x, y, z);
            if (!level.getBlockState(p).isAir()) continue;
            BlockPos belowPos = p.below();
            BlockState belowState = level.getBlockState(belowPos);
            if (!isValidGroundSubstrate(level, belowPos, belowState)) continue;
            if (cfg.nearWater() && !nearWater(box, x, y - 1, z)) continue;
            if (!state.canSurvive(level, p)) continue;
            if (state.getBlock() instanceof DoublePlantBlock && state.hasProperty(DoublePlantBlock.HALF)) {
                if (!level.getBlockState(p.above()).isAir()) continue;
                DoublePlantBlock.placeAt(level, state.setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER), p, 2);
            } else {
                level.setBlock(p, state, 2);
            }
            placed++;
        }

        // Regional 3D alchemy botanical & fungal drift alongside ground cover
        if (placed > 0 && random.nextInt(4) == 0) {
            placeRegionalAlchemyFlora(level, box, o.getX(), o.getZ(), cfg.nearWater(), random, p);
        }
        return placed > 0;
    }

    /**
     * Walks downward from MOTION_BLOCKING_NO_LEAVES past any logs, leaves, bushes, snow layers, vines, fences, slabs,
     * stairs, or air until it reaches actual ground, returning the air Y immediately above true ground.
     */
    private static int findTrueGroundY(WorldGenLevel level, int x, int z) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int min = level.getMinY() + 1;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int y = top; y > min; y--) {
            cursor.set(x, y - 1, z);
            BlockState below = level.getBlockState(cursor);
            if (below.isAir() || isCanopyOrFoliageOrRoof(below)) {
                continue;
            }
            cursor.set(x, y, z);
            if (level.getBlockState(cursor).isAir()) {
                return y;
            }
            return -1;
        }
        return -1;
    }

    private static boolean isCanopyOrFoliageOrRoof(BlockState s) {
        if (s.is(net.minecraft.tags.BlockTags.LOGS) || s.is(net.minecraft.tags.BlockTags.LEAVES)
                || s.is(net.minecraft.tags.BlockTags.FLOWERS) || s.is(net.minecraft.tags.BlockTags.SAPLINGS)
                || s.is(net.minecraft.tags.BlockTags.FENCES) || s.is(net.minecraft.tags.BlockTags.WALLS)
                || s.is(net.minecraft.world.level.block.Blocks.SNOW)
                || s.is(net.minecraft.world.level.block.Blocks.MOSS_CARPET)) {
            return true;
        }
        net.minecraft.world.level.block.Block b = s.getBlock();
        return b instanceof net.minecraft.world.level.block.LeavesBlock
                || b instanceof net.minecraft.world.level.block.BushBlock
                || b instanceof net.minecraft.world.level.block.SnowLayerBlock
                || b instanceof net.minecraft.world.level.block.VineBlock
                || b instanceof net.minecraft.world.level.block.SlabBlock
                || b instanceof net.minecraft.world.level.block.StairBlock
                || b instanceof net.minecraft.world.level.block.FenceBlock
                || b instanceof net.minecraft.world.level.block.WallBlock;
    }

    private static boolean isValidGroundSubstrate(WorldGenLevel level, BlockPos belowPos, BlockState below) {
        if (below.isAir() || !below.getFluidState().isEmpty() || isCanopyOrFoliageOrRoof(below)) {
            return false;
        }
        return below.isFaceSturdy(level, belowPos, net.minecraft.core.Direction.UP);
    }

    private static void placeRegionalAlchemyFlora(WorldGenLevel level, GenBox box, int ox, int oz,
                                                  boolean nearWaterCfg, RandomSource random, BlockPos.MutableBlockPos p) {
        int x = ox + random.nextInt(5) - 2;
        int z = oz + random.nextInt(5) - 2;
        if ((x >> 4) != box.center.x() || (z >> 4) != box.center.z()) return;
        if (!com.nirncraft.world.PlacementMask.clear(level, x, z, 0)) return;
        int y = findTrueGroundY(level, x, z);
        if (y <= level.getMinY() + 1) return;
        p.set(x, y, z);
        if (!level.getBlockState(p).isAir()) return;
        BlockPos belowPos = p.below();
        if (!isValidGroundSubstrate(level, belowPos, level.getBlockState(belowPos))) return;

        boolean byWater = nearWaterCfg || nearWater(box, x, y - 1, z);
        String floraId = chooseRegionalFloraId(x, y, z, byWater, random);
        BlockState floraState = ArcaneRegistry.floraState(floraId);
        if (floraState.canSurvive(level, p)) {
            level.setBlock(p, floraState, 2);
        }
    }

    private static String chooseRegionalFloraId(int x, int y, int z, boolean byWater, RandomSource r) {
        Region region = TamrielMap.region(x, z);
        String province = Cultures.province(region, x, z);
        String regId = region.id();
        MapSample s = new MapSample();
        TamrielMap.sample(x, z, s);

        if (byWater) {
            if ("morrowind".equals(province)) {
                String[] mwWater = {"marshmerrow", "saltrice", "kresh_fiber", "coda_flower", "luminous_russula", "bog_beacon", "nirnroot"};
                return mwWater[r.nextInt(mwWater.length)];
            }
            if ("black_marsh".equals(province)) {
                String[] bmWater = {"hist_carp", "swamp_fungal_pod", "bog_beacon", "luminous_russula", "rot_scale_fungus", "giant_lichen", "nirnroot"};
                return bmWater[r.nextInt(bmWater.length)];
            }
            return r.nextInt(3) == 0 ? "nirnroot" : (r.nextBoolean() ? "nordic_barnacle" : "swamp_fungal_pod");
        }

        return switch (province) {
            case "morrowind" -> {
                if (regId.contains("ashlands") || regId.contains("red_mountain") || regId.contains("molag_amur")) {
                    String[] ash = {"trama_root", "ashen_grass_pod", "ash_creep_cluster", "ash_yam", "scaly_pholiota", "spawn_ash"};
                    yield ash[r.nextInt(ash.length)];
                }
                if (regId.contains("bitter_coast") || regId.contains("ascadian")) {
                    String[] coast = {"coda_flower", "luminous_russula", "bog_beacon", "bittergreen_petals", "hypha_facia", "emperor_parasol_moss"};
                    yield coast[r.nextInt(coast.length)];
                }
                String[] mw = {"comberry", "hackle_lo", "marshmerrow", "saltrice", "gold_kanet", "kresh_fiber", "ash_yam", "trama_root"};
                yield mw[r.nextInt(mw.length)];
            }
            case "black_marsh" -> {
                String[] bm = {"hist_sap", "hist_carp", "swamp_fungal_pod", "bog_beacon", "luminous_russula", "rot_scale_fungus",
                        "giant_lichen", "poison_bloom", "deathbell", "canis_root", "hanging_moss"};
                yield bm[r.nextInt(bm.length)];
            }
            case "elsweyr", "hammerfell" -> {
                String[] desert = {"moon_sugar", "desert_rose", "cactus_pulp", "gold_kanet", "thistle_branch", "jazbay_grapes", "juniper_berries"};
                yield desert[r.nextInt(desert.length)];
            }
            case "valenwood", "summerset" -> {
                String[] forest = {"mora_tapinella", "scaly_pholiota", "hanging_moss", "nightshade", "wisp_wrappings",
                        "fly_amanita", "bleeding_crown", "comberry", "elves_ear"};
                yield forest[r.nextInt(forest.length)];
            }
            case "cyrodiil", "high_rock" -> {
                String[] heartland = {"nightshade", "deathbell", "mora_tapinella", "fly_amanita", "bleeding_crown",
                        "thistle_branch", "juniper_berries", "canis_root", "giant_lichen", "scaly_pholiota"};
                yield heartland[r.nextInt(heartland.length)];
            }
            default -> {
                if (regId.contains("eastmarch")) {
                    String[] volcanic = {"jazbay_grapes", "creep_cluster", "canis_root", "dragon_tongue".equals(regId) ? "thistle_branch" : "mora_tapinella"};
                    yield volcanic[r.nextInt(volcanic.length)];
                }
                if (regId.contains("hjaalmarch")) {
                    String[] marsh = {"deathbell", "swamp_fungal_pod", "canis_root", "giant_lichen", "mora_tapinella"};
                    yield marsh[r.nextInt(marsh.length)];
                }
                if (regId.contains("the_rift") || regId.contains("falkreath")) {
                    String[] woods = {"mora_tapinella", "scaly_pholiota", "imp_stool", "fly_amanita", "bleeding_crown", "canis_root", "nightshade"};
                    yield woods[r.nextInt(woods.length)];
                }
                if (regId.contains("the_reach")) {
                    String[] reach = {"juniper_berries", "hanging_moss", "thistle_branch", "briar_heart", "namiras_rot", "bleeding_crown"};
                    yield reach[r.nextInt(reach.length)];
                }
                if (y > 110 || s.elevation > 140 || regId.contains("winterhold") || regId.contains("the_pale") || "atmora".equals(province)) {
                    String[] cold = {"frost_mirriam", "snowberries", "elves_ear", "thistle_branch", "white_cap"};
                    yield cold[r.nextInt(cold.length)];
                }
                String[] skyrim = {"thistle_branch", "snowberries", "frost_mirriam", "elves_ear", "juniper_berries", "fly_amanita", "nightshade"};
                yield skyrim[r.nextInt(skyrim.length)];
            }
        };
    }

    /** Water within two blocks of (x, y, z) at the same level (reeds, cane and rushes keep to the shore). */
    private static boolean nearWater(GenBox box, int x, int y, int z) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) + Math.abs(dz) > 3 || (dx == 0 && dz == 0)) continue;
                if (box.get(x + dx, y, z + dz).getFluidState().is(net.minecraft.tags.FluidTags.WATER)) return true;
            }
        }
        return false;
    }

    private static BlockState pick(Config cfg, long seed, int x, int z, double roll) {
        List<Entry> list = cfg.entries();
        if (list.isEmpty()) return null;
        if (list.size() == 1) return list.get(0).state();
        double[] w = new double[list.size()];
        double total = 0;
        long base = seed + cfg.salt() * 0x2545F4914F6CDD1DL;
        for (int i = 0; i < w.length; i++) {
            double nz = cfg.patchiness() > 0 ? FloraNoise.fbm(x / (double) cfg.scale(), z / (double) cfg.scale(), base + 7919L * (i + 1), 2) : 0;
            w[i] = list.get(i).weight() * Math.exp(cfg.patchiness() * 7.0 * nz);
            total += w[i];
        }
        double t = roll * total;
        for (int i = 0; i < w.length; i++) {
            t -= w[i];
            if (t <= 0) return list.get(i).state();
        }
        return list.get(w.length - 1).state();
    }
}
