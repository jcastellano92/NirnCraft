package com.skycraft.survival.mode;

import com.skycraft.atmosphere.weather.RegionalWeather;
import com.skycraft.atmosphere.weather.WeatherKind;
import com.skycraft.atmosphere.weather.WeatherProfile;
import com.skycraft.survival.camping.TentBlock;
import com.skycraft.tamriel.Place;
import com.skycraft.tamriel.Places;
import com.skycraft.tamriel.Region;
import com.skycraft.tamriel.Regions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * How cold it is where a player stands (Skyrim Survival Mode): the "target" the cold meter drifts toward, 0..300.
 *
 * <p>The base comes from the region (Skyrim's holds: the Pale and Winterhold are freezing, Whiterun and the Rift merely
 * cool), its weather profile and the biome's temperature or snow line, then altitude, night and the regional weather
 * ({@link RegionalWeather}: snow, blizzards, rain) are added. A roof shelters from the weather, a settlement's houses
 * and inns are warm, caves stay cool, and a fire, forge, furnace or lava within a few blocks warms you right up.
 * Water is the real danger: in a cold region it pulls the meter to Numb several times faster.</p>
 */
public final class Climate {
    /** Comfortable, Chilly, Very Cold, Freezing, Numb start at these cold values. */
    public static final float CHILLY = 60, VERY_COLD = 120, FREEZING = 180, NUMB = 240, MAX = 300;
    private static final int HEAT_RADIUS = 4;

    private Climate() {}

    /**
     * @param target     what the cold meter drifts toward
     * @param riseMul    multiplier for how fast it climbs (water)
     * @param heat       a fire or forge is warming the player
     * @param sheltered  under a roof, in a tent or underground
     * @param inWater    swimming or wading
     * @param base       the area's cold before weather, night and water (0 warm .. 220 arctic)
     */
    public record Env(float target, float riseMul, boolean heat, boolean sheltered, boolean inWater, float base) {}

    public static Env sample(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos pos = player.blockPosition();
        if (level.dimension() == Level.NETHER) return new Env(0, 1, true, true, false, 0);
        if (level.dimension() != Level.OVERWORLD) return new Env(10, 1, false, true, false, 10);

        float base = baseCold(level, pos);
        boolean night = isNight(level);
        BlockPos eye = BlockPos.containing(player.getEyePosition());
        boolean sky = level.canSeeSky(eye);
        boolean tent = !sky || inTent(level, pos);
        boolean underground = !sky && pos.getY() < level.getSeaLevel() - 8 && level.getBrightness(net.minecraft.world.level.LightLayer.SKY, eye) == 0;
        boolean inWater = player.isInWater();

        float target = base;
        int y = pos.getY();
        if (y > 140) target += Math.min(120, (y - 140) * 0.5f);
        if (night) target += base <= 30 ? 25 : 30;

        boolean sheltered = tent || underground;
        if (!sheltered) {
            WeatherKind kind = RegionalWeather.kindAt(level, pos);
            target += weatherCold(kind, base);
        }
        if (underground) {
            target = clamp(base * 0.5f, 30, 90);
        } else if (sheltered) {
            target = Math.max(0, target - 25);
            if (inSettlement(pos) && !tent) target = Math.min(target, 20); // houses and inns are heated
            else if (tent) target = Math.min(target, Math.max(40, base * 0.6f));
        }

        float riseMul = 1f;
        if (inWater) {
            if (base >= 120) {
                target = MAX;
                riseMul = 6f;
            } else if (base >= 50) {
                target = Math.max(target + 60, VERY_COLD);
                riseMul = 2.5f;
            } else {
                target += 20;
                riseMul = 1.5f;
            }
        }

        boolean heat = nearHeat(level, pos);
        if (heat && !inWater) target = 0;
        return new Env(clamp(target, 0, MAX), riseMul, heat, sheltered, inWater, base);
    }

    /** Region, weather profile, biome and snow line: the cold of the area in clear daylight. */
    public static float baseCold(ServerLevel level, BlockPos pos) {
        Holder<Biome> holder = level.getBiome(pos);
        Biome biome = holder.value();
        float t = biome.getBaseTemperature();
        float base;
        if (t >= 1.0f) base = 0;
        else if (t >= 0.6f) base = 25;
        else if (t >= 0.3f) base = 50;
        else if (t >= 0.15f) base = 90;
        else if (t >= -0.2f) base = 150;
        else base = 190;

        Region region = Regions.at(level, pos);
        WeatherProfile profile = WeatherProfile.of(region);
        if (profile == WeatherProfile.FROZEN) base = Math.max(base, 210);
        else if (profile == WeatherProfile.SKYRIM_COLD) base = Math.max(base, 150);
        else if (profile == WeatherProfile.ALPINE) base += 20;
        else if (profile == WeatherProfile.ASH || profile == WeatherProfile.DESERT || profile == WeatherProfile.JUNGLE
                || profile == WeatherProfile.SAVANNA || profile == WeatherProfile.SWAMP) base = Math.min(base, 30);

        if ("skyrim".equals(region.province())) {
            switch (region.shortId()) {
                case "the_pale", "winterhold" -> base = Math.max(base, 150);
                case "eastmarch" -> base = Math.max(base, 110);
                case "haafingar", "hjaalmarch" -> base = Math.max(base, 90);
                default -> base = Math.max(base, 55); // Whiterun, the Reach, Falkreath, the Rift: cool
            }
        } else if ("solstheim".equals(region.shortId())) {
            base = Math.max(base, 110);
        } else if ("atmora".equals(region.province())) {
            base = Math.max(base, 200);
        }

        ResourceLocation key = holder.unwrapKey().map(k -> k.location()).orElse(null);
        String biomeId = key == null ? "" : key.getPath();
        if (biomeId.contains("hot_spring") || biomeId.contains("volcanic") || biomeId.contains("lava")) base = Math.min(base, 30);
        if (biome.coldEnoughToSnow(pos)) base = Math.max(base, 150);
        return base;
    }

    private static float weatherCold(WeatherKind kind, float base) {
        if (kind == null) return 0;
        return switch (kind) {
            case SNOW -> 30;
            case BLIZZARD -> 90;
            case RAIN -> base > 20 ? 15 : 5;
            case STORM -> base > 20 ? 25 : 8;
            case DRIZZLE -> base > 20 ? 8 : 0;
            case FOG -> 5;
            default -> 0;
        };
    }

    private static boolean isNight(Level level) {
        long time = level.getDayTime() % 24000L;
        return time >= 13000 && time <= 23000;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(hi, v);
    }

    /** A tent around the player: tent canvas on both sides. */
    private static boolean inTent(Level level, BlockPos pos) {
        int sides = 0;
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-1, 0, -1), pos.offset(1, 1, 1))) {
            if (level.getBlockState(p).getBlock() instanceof TentBlock) sides++;
            if (sides >= 2) return true;
        }
        return false;
    }

    private static boolean inSettlement(BlockPos pos) {
        for (Place p : Places.overlapping(pos.getX(), pos.getZ(), pos.getX(), pos.getZ())) {
            if (p.type().isSettlement() && p.distSqr(pos.getX(), pos.getZ()) <= (long) p.radius() * p.radius()) return true;
        }
        return false;
    }

    /** A lit campfire, fire, lava, lit furnace or smithing forge within {@value #HEAT_RADIUS} blocks. */
    public static boolean nearHeat(Level level, BlockPos pos) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -HEAT_RADIUS; dx <= HEAT_RADIUS; dx++) {
                for (int dz = -HEAT_RADIUS; dz <= HEAT_RADIUS; dz++) {
                    p.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (isHeatSource(level.getBlockState(p))) return true;
                }
            }
        }
        return false;
    }

    public static boolean isHeatSource(BlockState state) {
        if (state.isAir()) return false;
        if (CampfireBlock.isLitCampfire(state) || state.is(BlockTags.FIRE) || state.is(Blocks.MAGMA_BLOCK)) return true;
        if (state.getFluidState().is(FluidTags.LAVA)) return true;
        if (state.getBlock() instanceof AbstractFurnaceBlock) return state.getValue(AbstractFurnaceBlock.LIT);
        if (state.hasProperty(BlockStateProperties.LIT) && !state.getValue(BlockStateProperties.LIT)) return false;
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id == null || !id.getNamespace().equals("skycraft")) return false;
        String path = id.getPath();
        return path.contains("forge") || path.contains("smelter") || path.contains("hearth") || path.contains("brazier");
    }
}
