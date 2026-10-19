package com.nirncraft.tamriel.biome.feature;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.world.level.block.state.BlockState;
import com.nirncraft.worldgen.ConfiguredFeature;
import com.nirncraft.worldgen.FeaturePlaceContext;
import com.nirncraft.worldgen.FeatureConfiguration;

/**
 * Morrowind's giant mushrooms. Three styles:
 * <ul>
 *     <li>{@code parasol}: an emperor parasol, a slender leaning stem under a wide flat cap with a drooping rim;</li>
 *     <li>{@code tower}: a Telvanni tower mushroom, a thick stem ringed with bracket shelves under a bulbous dome;</li>
 *     <li>{@code cave}: a glowing cave mushroom (Blackreach), sized to the cave's headroom.</li>
 * </ul>
 */
public class GiantMushroomFeature extends ConfiguredFeature<GiantMushroomFeature.Config> {
    public record Config(BlockState stem, BlockState cap, String style, IntProvider height) implements FeatureConfiguration {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
                BlockState.CODEC.fieldOf("stem").forGetter(Config::stem),
                BlockState.CODEC.fieldOf("cap").forGetter(Config::cap),
                Codec.STRING.fieldOf("style").forGetter(Config::style),
                net.minecraft.util.valueproviders.IntProviders.codec(3, 64).fieldOf("height").forGetter(Config::height)
        ).apply(i, Config::new));
    }

    public GiantMushroomFeature(Codec<Config> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<Config> ctx) {
        Config cfg = ctx.config();
        RandomSource random = ctx.random();
        GenBox box = GenBox.of(ctx.level(), ctx.origin());
        int x = ctx.origin().getX(), z = ctx.origin().getZ();
        int y = ctx.origin().getY();
        BlockState below = box.get(x, y - 1, z);
        if (!below.isSolidRender() || !GenBox.isSoft(box.get(x, y, z))) return false;
        if (!com.nirncraft.world.PlacementMask.clear(ctx.level(), x, z, 8)) return false;
        int height = cfg.height().sample(random);
        return switch (cfg.style()) {
            case "tower" -> tower(box, cfg, random, x, y, z, height);
            case "cave" -> cave(box, cfg, random, x, y, z, height);
            default -> parasol(box, cfg, random, x, y, z, height);
        };
    }

    private boolean parasol(GenBox box, Config cfg, RandomSource random, int x, int y, int z, int height) {
        int capR = 4 + random.nextInt(3) + height / 8;
        if (box.reach(x, z) < capR + 3) return false;
        double a = random.nextDouble() * Math.PI * 2;
        double lean = 0.08 + random.nextDouble() * 0.12;
        double sx = x, sz = z;
        boolean thick = height > 14;
        double bendFreq = 0.1 + random.nextDouble() * 0.25, bendAmp = random.nextDouble() * 1.2;
        for (int dy = 0; dy < height; dy++) {
            int bx = Mth.floor(sx), bz = Mth.floor(sz);
            box.set(bx, y + dy, bz, cfg.stem());
            if (thick && dy < height / 2) {
                box.set(bx + 1, y + dy, bz, cfg.stem());
                box.set(bx, y + dy, bz + 1, cfg.stem());
                box.set(bx + 1, y + dy, bz + 1, cfg.stem());
            }
            double bendA = a + Math.sin(dy * bendFreq) * bendAmp;
            sx += Math.cos(bendA) * lean * dy / height * 2;
            sz += Math.sin(bendA) * lean * dy / height * 2;
        }
        int top = y + height;
        int cx = Mth.floor(sx), cz = Mth.floor(sz);
        // every parasol's cap differs: flat with a drooping rim, a low dome, a shallow funnel, or flat with a second,
        // smaller cap lower on the stem
        int style = random.nextInt(4);
        cap(box, cfg, cx, top, cz, capR, style == 1 ? 1 : style == 2 ? -1 : 0);
        if (style == 3 && height > 9) {
            int lowR = Math.max(2, capR / 2 + random.nextInt(2));
            int ly = y + height / 2 + random.nextInt(3);
            double t = (ly - y) / (double) height;
            int lx = Mth.floor(x + (sx - x) * t * t), lz = Mth.floor(z + (sz - z) * t * t);
            cap(box, cfg, lx, ly, lz, lowR, 0);
        }
        box.set(cx, top - 1, cz, cfg.stem());
        return true;
    }

    /** A parasol cap: a disc with a rim one lower and a raised middle; {@code bulge} 1 domes it, -1 dishes it. */
    private static void cap(GenBox box, Config cfg, int cx, int top, int cz, int capR, int bulge) {
        for (int dx = -capR; dx <= capR; dx++) {
            for (int dz = -capR; dz <= capR; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > capR * capR) continue;
                int lift = bulge > 0 && d2 <= (capR - 2) * (capR - 2) ? 1 : bulge < 0 && d2 > (capR - 2) * (capR - 2) ? 1 : 0;
                if (d2 <= (capR - 1) * (capR - 1)) box.setSoft(cx + dx, top + lift, cz + dz, cfg.cap());
                if (d2 <= (capR - 3) * (capR - 3)) box.setSoft(cx + dx, top + 1 + lift, cz + dz, cfg.cap());
                if (bulge > 0 && d2 <= (capR - 4) * (capR - 4)) box.setSoft(cx + dx, top + 2 + lift, cz + dz, cfg.cap());
                if (d2 > (capR - 2) * (capR - 2)) box.setSoft(cx + dx, top - 1 + lift, cz + dz, cfg.cap());
            }
        }
    }

    private boolean tower(GenBox box, Config cfg, RandomSource random, int x, int y, int z, int height) {
        int domeR = 4 + random.nextInt(2);
        if (box.reach(x, z) < domeR + 6) return false;
        double r = 1.6 + random.nextDouble() * 0.6;
        double sx = x + 0.5, sz = z + 0.5;
        double a = random.nextDouble() * Math.PI * 2;
        for (int dy = -2; dy < height; dy++) {
            double rr = r + (dy < 2 ? (2 - dy) * 0.5 : 0);
            GrahtOakFeature.disc(box, sx, y + dy, sz, rr, cfg.stem());
            sx += Math.cos(a + dy * 0.15) * 0.12;
            sz += Math.sin(a + dy * 0.15) * 0.12;
        }
        // bracket shelves spiralling up the stem
        int shelves = 2 + random.nextInt(3);
        for (int i = 0; i < shelves; i++) {
            int sy = y + (int) (height * (0.3 + 0.5 * i / (double) shelves));
            double sa = a + i * 2.1 + random.nextDouble();
            int sr = 2 + random.nextInt(2);
            double ox = sx + Math.cos(sa) * (r + sr - 0.5), oz = sz + Math.sin(sa) * (r + sr - 0.5);
            for (int dx = -sr; dx <= sr; dx++) {
                for (int dz = -sr; dz <= sr; dz++) {
                    if (dx * dx + dz * dz <= sr * sr) box.setSoft(Mth.floor(ox) + dx, sy, Mth.floor(oz) + dz, cfg.cap());
                }
            }
        }
        // the dome
        int top = y + height;
        int cx = Mth.floor(sx), cz = Mth.floor(sz);
        for (int dy = -1; dy <= domeR; dy++) {
            double layer = dy < 0 ? domeR : Math.sqrt(Math.max(0, domeR * domeR - dy * dy * 1.4));
            for (int dx = -domeR; dx <= domeR; dx++) {
                for (int dz = -domeR; dz <= domeR; dz++) {
                    double d = Math.sqrt(dx * dx + dz * dz);
                    if (d > layer) continue;
                    if (dy < 0 && d < domeR - 1.5) continue; // the rim hangs down at the edge only
                    box.setSoft(cx + dx, top + dy, cz + dz, cfg.cap());
                }
            }
        }
        return true;
    }

    private boolean cave(GenBox box, Config cfg, RandomSource random, int x, int y, int z, int height) {
        int room = 0;
        while (room < height + 4 && GenBox.isSoft(box.get(x, y + room, z)) && box.get(x, y + room, z).getFluidState().isEmpty()) {
            room++;
        }
        if (room < 6) return false;
        int h = Math.min(height, room - 3);
        int capR = Math.max(2, Math.min(2 + h / 3, 5));
        if (box.reach(x, z) < capR + 1) return false;
        for (int dy = 0; dy < h; dy++) box.set(x, y + dy, z, cfg.stem());
        int top = y + h;
        for (int dx = -capR; dx <= capR; dx++) {
            for (int dz = -capR; dz <= capR; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > capR * capR) continue;
                box.setSoft(x + dx, top, z + dz, cfg.cap());
                if (d2 > (capR - 1) * (capR - 1)) box.setSoft(x + dx, top - 1, z + dz, cfg.cap());
                if (d2 <= (capR - 2) * (capR - 2)) box.setSoft(x + dx, top + 1, z + dz, cfg.cap());
            }
        }
        return true;
    }
}
