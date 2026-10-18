package com.skycraft.survival.fishing;

import com.skycraft.atmosphere.weather.RegionalWeather;
import com.skycraft.atmosphere.weather.WeatherKind;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.survival.mode.Climate;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.material.FluidState;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Which fish bite where: the water at the hook (river, lake, sea, swamp, underground; cold or warm), the region and
 * province, the time of day, the weather and the depth decide which {@link FishSpecies} can bite, weighted by rarity.
 * Luck (better rods, glow bait, fishing spots, Fishing skill) favours the rare ones.
 */
public final class Fishing {
    private Fishing() {}

    /** What the hook sits in. */
    public record Waters(Set<FishSpecies.Water> kinds, String province, String region, int depth, boolean raining, long dayTime) {}

    public static Waters waters(ServerLevel level, BlockPos hook) {
        Set<FishSpecies.Water> kinds = EnumSet.noneOf(FishSpecies.Water.class);
        Holder<Biome> biome = level.getBiome(hook);
        String biomeId = biome.unwrapKey().map(k -> k.identifier().getPath()).orElse("");
        Region region = Regions.at(level, hook);
        int depth = 0;
        BlockPos.MutableBlockPos p = hook.mutable();
        boolean flowing = false;
        for (int i = 0; i < 16; i++) {
            FluidState f = level.getFluidState(p);
            if (!f.is(FluidTags.WATER)) break;
            if (!f.isSource()) flowing = true;
            depth++;
            p.move(0, -1, 0);
        }
        boolean underground = !level.canSeeSky(hook.above()) && hook.getY() < level.getSeaLevel() - 6;
        if (underground) {
            kinds.add(FishSpecies.Water.CAVE);
        } else if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_BEACH) || "sea".equals(region.province())
                || "sea".equals(region.kind())) {
            kinds.add(FishSpecies.Water.OCEAN);
        } else if (biomeId.contains("swamp") || biomeId.contains("marsh") || biomeId.contains("mire") || biomeId.contains("bog")
                || biomeId.contains("mangrove") || biomeId.contains("fen") || "black_marsh".equals(region.province())) {
            kinds.add(FishSpecies.Water.SWAMP);
            if (flowing || biome.is(BiomeTags.IS_RIVER)) kinds.add(FishSpecies.Water.RIVER);
        } else if (biome.is(BiomeTags.IS_RIVER) || flowing) {
            kinds.add(FishSpecies.Water.RIVER);
        } else {
            kinds.add(FishSpecies.Water.LAKE);
        }
        float base = Climate.baseCold(level, hook);
        if (base >= 120) kinds.add(FishSpecies.Water.COLD);
        if (base <= 30) kinds.add(FishSpecies.Water.WARM);
        WeatherKind weather = RegionalWeather.kindAt(level, hook);
        boolean rain = weather != null && weather.wet();
        return new Waters(kinds, region.province(), region.shortId(), depth, rain, level.getDefaultClockTime());
    }

    /** True if {@code s} can bite in these waters. */
    public static boolean canBite(FishSpecies s, Waters w) {
        boolean water = false;
        for (FishSpecies.Water k : w.kinds()) {
            if (s.waters().contains(k)) {
                water = true;
                break;
            }
        }
        if (!water) return false;
        if (s.waters().size() == 1 && s.waters().contains(FishSpecies.Water.COLD) && w.kinds().contains(FishSpecies.Water.CAVE)) return false;
        if (!s.livesIn(w.province(), w.region())) return false;
        if (!s.time().matches(w.dayTime())) return false;
        return w.depth() >= s.minDepth();
    }

    /** Picks the fish that bites, or null if nothing lives here (then a generic fish of the water type is used). */
    @Nullable
    public static FishSpecies pick(Waters w, float luck, RandomSource random) {
        List<FishSpecies> pool = new ArrayList<>();
        List<Float> weights = new ArrayList<>();
        float total = 0;
        for (FishSpecies s : FishSpecies.all()) {
            if (!canBite(s, w)) continue;
            float weight = switch (s.rarity()) {
                case 0 -> 60f;
                case 1 -> 22f * (1f + 0.15f * luck);
                default -> 6f * (1f + 0.4f * luck);
            };
            // local fish are far more common than the widespread ones
            if (!s.provinces().isEmpty()) weight *= 1.6f;
            if (w.raining() && s.time() != FishSpecies.Time.DAY) weight *= 1.25f;
            pool.add(s);
            weights.add(weight);
            total += weight;
        }
        if (pool.isEmpty()) return fallback(w);
        float r = random.nextFloat() * total;
        for (int i = 0; i < pool.size(); i++) {
            r -= weights.get(i);
            if (r <= 0) return pool.get(i);
        }
        return pool.get(pool.size() - 1);
    }

    private static FishSpecies fallback(Waters w) {
        if (w.kinds().contains(FishSpecies.Water.OCEAN)) {
            return FishSpecies.byId(w.kinds().contains(FishSpecies.Water.COLD) ? "pogfish" : "abecean_longfin");
        }
        if (w.kinds().contains(FishSpecies.Water.CAVE)) return FishSpecies.byId("glassfish");
        return FishSpecies.byId("silverside_perch");
    }

    /** A caught fish of species {@code s}, with a length (trophies are near the top of the range or above). */
    public static ItemStack catchOf(FishSpecies s, RandomSource random, boolean trophy) {
        Supplier<net.minecraft.world.item.Item> item = FishingRegistry.FISH_ITEMS.get(s.id());
        if (item == null) return ItemStack.EMPTY;
        float t = random.nextFloat();
        t = t * t; // most catches are on the small side
        int cm = s.minCm() + Math.round((s.maxCm() - s.minCm()) * t);
        if (trophy) cm = Math.round(s.maxCm() * (1.1f + random.nextFloat() * 0.3f));
        return FishItem.withLength(new ItemStack(item.get()), Math.max(1, cm), trophy);
    }

    /** A trophy-sized fish of the same species as {@code caught} (Legendary Angler), or empty if it isn't a Skycraft fish. */
    public static ItemStack trophyOf(ItemStack caught, RandomSource random) {
        if (!(caught.getItem() instanceof FishItem fish)) return ItemStack.EMPTY;
        return catchOf(fish.species(), random, true);
    }

    /** Fishing skill, 0..100+. */
    public static int skill(ServerPlayer player) {
        return SkyData.get(player).getSkill(Skill.FISHING);
    }
}
