package com.skycraft.tamriel.biome.flora;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skycraft.tamriel.biome.feature.GenBox;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import com.skycraft.worldgen.ConfiguredFeature;
import com.skycraft.worldgen.FeaturePlaceContext;
import com.skycraft.worldgen.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;

import java.util.Optional;

/**
 * {@code skycraft:flora_rock}: rocks and boulders that each look different. A rock is an ellipsoid with its own three
 * radii, turned to its own angle, tilted and warped by noise, half sunk into the ground; its upper faces carry moss
 * or lichen ({@code moss}, {@code moss_chance}). Shapes: {@code boulder}, {@code slab} (a tilted plate),
 * {@code standing} (a leaning standing stone), {@code rubble} (a spill of small stones) or {@code mixed}.
 */
public class RockFeature extends ConfiguredFeature<RockFeature.Config> {
    public enum Style implements StringRepresentable {
        BOULDER("boulder"), SLAB("slab"), STANDING("standing"), RUBBLE("rubble"), MIXED("mixed");
        public static final Codec<Style> CODEC = StringRepresentable.fromEnum(Style::values);
        private final String id;

        Style(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }

    public record Config(BlockStateProvider stone, Optional<BlockStateProvider> moss, float mossChance, IntProvider size,
                         Style style) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockStateProvider.DIRECT_CODEC.fieldOf("stone").forGetter(Config::stone),
                BlockStateProvider.DIRECT_CODEC.optionalFieldOf("moss").forGetter(Config::moss),
                Codec.floatRange(0f, 1f).optionalFieldOf("moss_chance", 0f).forGetter(Config::mossChance),
                net.minecraft.util.valueproviders.IntProviders.codec(1, 6).fieldOf("size").forGetter(Config::size),
                Style.CODEC.optionalFieldOf("style", Style.MIXED).forGetter(Config::style)
        ).apply(i, Config::new));
    }

    public RockFeature(Codec<Config> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Config cfg = ctx.config();
        WorldGenLevel level = ctx.level();
        RandomSource r = ctx.random();
        GenBox box = GenBox.of(level, ctx.origin());
        int x = ctx.origin().getX(), y = ctx.origin().getY(), z = ctx.origin().getZ();
        if (!box.get(x, y, z).getFluidState().isEmpty() || !box.get(x, y - 1, z).getFluidState().isEmpty()) return false;
        if (box.get(x, y - 1, z).isAir() || box.reach(x, z) < 9) return false;
        if (!com.skycraft.world.PlacementMask.clear(level, x, z, 4)) return false;
        Style style = cfg.style();
        if (style == Style.MIXED) {
            int roll = r.nextInt(10);
            style = roll < 6 ? Style.BOULDER : roll < 8 ? Style.SLAB : roll < 9 ? Style.RUBBLE : Style.STANDING;
        }
        int size = cfg.size().sample(r);
        long seed = level.getSeed() + x * 341873128712L + z * 132897987541L;
        switch (style) {
            case RUBBLE -> {
                int n = 3 + r.nextInt(4);
                for (int i = 0; i < n; i++) {
                    double a = r.nextDouble() * Math.PI * 2, d = 1 + r.nextDouble() * (2 + size);
                    int sx = x + (int) Math.round(Math.cos(a) * d), sz = z + (int) Math.round(Math.sin(a) * d);
                    int sy = TreeShapes.ground(box, sx, sz, y);
                    if (sy == Integer.MIN_VALUE || Math.abs(sy - y) > 2) continue;
                    double rr = 0.7 + r.nextDouble() * 0.8;
                    rock(box, cfg, r, seed + i, sx + 0.5, sy - 0.3, sz + 0.5, rr, rr * 0.8, rr, r.nextDouble() * 3, 0, 0);
                }
            }
            case SLAB -> {
                double rr = size + 0.8;
                rock(box, cfg, r, seed, x + 0.5, y - 0.2, z + 0.5, rr * (1.1 + r.nextDouble() * 0.5),
                        0.55 + r.nextDouble() * 0.5, rr * (0.7 + r.nextDouble() * 0.4), r.nextDouble() * Math.PI,
                        (r.nextDouble() - 0.5) * 0.7, (r.nextDouble() - 0.5) * 0.5);
            }
            case STANDING -> {
                double rr = Math.max(1.0, size * 0.6);
                rock(box, cfg, r, seed, x + 0.5, y + size * 0.9, z + 0.5, rr, size * 1.4 + 1.5, rr * 0.75,
                        r.nextDouble() * Math.PI, (r.nextDouble() - 0.5) * 0.35, (r.nextDouble() - 0.5) * 0.35);
            }
            default -> {
                double rr = size + 0.5;
                double rx = rr * (0.8 + r.nextDouble() * 0.5), ry = rr * (0.55 + r.nextDouble() * 0.4);
                double rz = rr * (0.7 + r.nextDouble() * 0.4);
                rock(box, cfg, r, seed, x + 0.5, y - ry * 0.35, z + 0.5, rx, ry, rz, r.nextDouble() * Math.PI,
                        (r.nextDouble() - 0.5) * 0.3, (r.nextDouble() - 0.5) * 0.3);
                // a smaller companion leaning on it
                if (r.nextInt(3) == 0) {
                    double a = r.nextDouble() * Math.PI * 2;
                    double cx = x + 0.5 + Math.cos(a) * rx, cz = z + 0.5 + Math.sin(a) * rz;
                    rock(box, cfg, r, seed + 99, cx, y - 0.2, cz, rr * 0.5, rr * 0.45, rr * 0.5, r.nextDouble() * 3, 0, 0);
                }
            }
        }
        return true;
    }

    /**
     * One rock: an ellipsoid (rx, ry, rz) turned by {@code yaw}, tilted by {@code tiltX}/{@code tiltZ} (height gained per
     * block across), with a noise-warped surface; columns are filled down to the ground so nothing floats.
     */
    private static void rock(GenBox box, Config cfg, RandomSource r, long seed, double cx, double cy, double cz,
                             double rx, double ry, double rz, double yaw, double tiltX, double tiltZ) {
        double cos = Math.cos(yaw), sin = Math.sin(yaw);
        int ext = (int) Math.ceil(Math.max(rx, rz) + 1);
        int ey = (int) Math.ceil(ry + 2 + ext * (Math.abs(tiltX) + Math.abs(tiltZ)));
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -ext; dx <= ext; dx++) {
            for (int dz = -ext; dz <= ext; dz++) {
                int bx = (int) Math.floor(cx) + dx, bz = (int) Math.floor(cz) + dz;
                double fx = bx + 0.5 - cx, fz = bz + 0.5 - cz;
                double u = fx * cos + fz * sin, v = -fx * sin + fz * cos;
                double lift = fx * tiltX + fz * tiltZ;
                int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
                for (int dy = -ey; dy <= ey; dy++) {
                    int by = (int) Math.floor(cy) + dy;
                    double fy = by + 0.5 - cy - lift;
                    double d = (u / rx) * (u / rx) + (fy / ry) * (fy / ry) + (v / rz) * (v / rz);
                    double warp = FloraNoise.value(bx * 0.45 + by * 0.31, bz * 0.45 - by * 0.27, seed) * 0.28;
                    if (d > 1.0 + warp) continue;
                    if (!GenBox.isSoft(box.get(bx, by, bz)) && !box.get(bx, by, bz).is(net.minecraft.tags.BlockTags.SUBSTRATE_OVERWORLD)
                            && !box.get(bx, by, bz).is(net.minecraft.tags.BlockTags.SAND)) continue;
                    box.set(bx, by, bz, cfg.stone().getState(box.level, r, p.set(bx, by, bz)));
                    lowest = Math.min(lowest, by);
                    highest = Math.max(highest, by);
                }
                if (lowest == Integer.MAX_VALUE) continue;
                // no overhang hanging in the air: fill down to the ground
                for (int fy = lowest - 1, n = 0; n < 4 && GenBox.isSoft(box.get(bx, fy, bz)); fy--, n++) {
                    box.set(bx, fy, bz, cfg.stone().getState(box.level, r, p.set(bx, fy, bz)));
                }
                // moss and lichen on the upper faces
                if (cfg.moss().isPresent() && r.nextFloat() < cfg.mossChance() && box.get(bx, highest + 1, bz).isAir()) {
                    if (r.nextInt(3) == 0) box.set(bx, highest + 1, bz, Blocks.MOSS_CARPET.defaultBlockState());
                    else box.set(bx, highest, bz, cfg.moss().get().getState(box.level, r, p.set(bx, highest, bz)));
                }
            }
        }
    }
}
