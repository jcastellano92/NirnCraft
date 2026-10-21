package com.nirncraft.tamriel.biome.flora;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.StringRepresentable;

/**
 * A density field over the world: where a biome's groves, clearings, drifts and rock fields lie. Fields with the same
 * {@code salt} and shape describe the same forest, so a biome's trees ({@code inside}), its edge shrubs
 * ({@code edge}) and its meadow flowers ({@code outside}) all agree on where the forest is.
 *
 * <ul>
 *   <li>{@code kind = fbm}: rolling fractal noise, {@code scale} blocks per feature: groves and clearings;</li>
 *   <li>{@code kind = worley}: cells {@code scale} blocks across with a spot near each cell point: tufts, rock
 *       fields, mushroom rings;</li>
 *   <li>{@code coverage}: about the fraction of the ground inside the field; {@code softness}: how gradual the edge
 *       is (0.02 sharp, 0.3 very soft).</li>
 * </ul>
 */
public record FloraField(int salt, float scale, float coverage, float softness, Kind kind, Mode mode) {
    public static final Codec<FloraField> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.fieldOf("salt").forGetter(FloraField::salt),
            Codec.floatRange(1f, 4096f).fieldOf("scale").forGetter(FloraField::scale),
            Codec.floatRange(0f, 1f).optionalFieldOf("coverage", 0.5f).forGetter(FloraField::coverage),
            Codec.floatRange(0f, 1f).optionalFieldOf("softness", 0.12f).forGetter(FloraField::softness),
            Kind.CODEC.optionalFieldOf("kind", Kind.FBM).forGetter(FloraField::kind),
            Mode.CODEC.optionalFieldOf("mode", Mode.INSIDE).forGetter(FloraField::mode)
    ).apply(i, FloraField::new));

    public enum Kind implements StringRepresentable {
        FBM("fbm"), WORLEY("worley");
        public static final Codec<Kind> CODEC = StringRepresentable.fromEnum(Kind::values);
        private final String id;

        Kind(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }

    public enum Mode implements StringRepresentable {
        /** Dense inside the field (forest). */
        INSIDE("inside"),
        /** Dense outside it (clearing meadows). */
        OUTSIDE("outside"),
        /** Dense along its edge (forest-edge shrubs and saplings). */
        EDGE("edge"),
        /** Everywhere the same (the field is ignored). */
        ANY("any");
        public static final Codec<Mode> CODEC = StringRepresentable.fromEnum(Mode::values);
        private final String id;

        Mode(String id) {
            this.id = id;
        }

        @Override
        public String getSerializedName() {
            return id;
        }
    }

    /** The field's seed for a world. */
    public long seed(long worldSeed) {
        return worldSeed * 0x5DEECE66DL + salt * 0x9E3779B97F4A7C15L;
    }

    /** How much of the field is at (x, z), 0 to 1, after the mode. */
    public double sample(long worldSeed, double x, double z) {
        if (mode == Mode.ANY) return 1.0;
        double f = raw(seed(worldSeed), x, z);
        return switch (mode) {
            case OUTSIDE -> 1.0 - f;
            case EDGE -> Math.min(1.0, 4.0 * f * (1.0 - f));
            default -> f;
        };
    }

    /** The field before the mode: 1 well inside, 0 well outside. */
    double raw(long seed, double x, double z) {
        double s = Math.max(0.02, softness);
        if (kind == Kind.WORLEY) {
            double d = FloraNoise.worley(x / scale, z / scale, seed);
            // radius of the spot around each point that covers `coverage` of the cell
            double r = Math.min(0.75, Math.sqrt(coverage / Math.PI));
            // a little fbm wobble so the spots are not circles
            d += FloraNoise.value(x / (scale * 0.4), z / (scale * 0.4), seed + 77) * 0.12;
            return 1.0 - FloraNoise.smoothstep(r - s, r + s, d);
        }
        double n = FloraNoise.fbm(x / scale, z / scale, seed, 3);
        // fractal value noise is roughly normal with sigma ~0.27: spread it over 0..1 before thresholding
        double u = 1.0 / (1.0 + StrictMath.exp(-n * (1.702 / 0.27)));
        double t = 1.0 - coverage;
        return FloraNoise.smoothstep(t - s, t + s, u);
    }
}
