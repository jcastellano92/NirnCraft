package com.skycraft.realms.worldgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.skycraft.realms.Realm;
import com.skycraft.realms.RealmLayout;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * {@code skycraft:realm}: the biome source of every realm. The realm's noise settings route its terrain channels
 * ({@link RealmTerrain}) into the climate sampler: {@code continentalness} = the shape mask, {@code erosion} = the
 * feature mask, {@code depth} = blocks below the surface / 128; temperature and humidity are plain noise for variety.
 * The fixed landmarks (Sovngarde's Hall, the Fringe, the Boneyard...) are placed from {@link RealmLayout}.
 *
 * <pre>{@code {"type": "skycraft:realm", "realm": "deadlands",
 *   "biomes": {"lava_sea": "skycraft:deadlands_lava_sea", "ashen_wastes": "skycraft:deadlands_ashen_wastes", ...}}}</pre>
 * Roles missing from the map fall back to the first biome listed.
 */
public class RealmBiomeSource extends BiomeSource {
    private static final Codec<Realm> REALM = Codec.STRING.comapFlatMap(s -> {
        Realm r = Realm.byId(s);
        return r == null ? DataResult.error(() -> "Unknown realm " + s) : DataResult.success(r);
    }, r -> r.id);

    public static final Codec<RealmBiomeSource> CODEC = RecordCodecBuilder.create(i -> i.group(
            REALM.fieldOf("realm").forGetter(s -> s.realm),
            Codec.unboundedMap(Codec.STRING, Biome.CODEC).fieldOf("biomes").forGetter(s -> s.biomes)
    ).apply(i, i.stable(RealmBiomeSource::new)));

    private final Realm realm;
    private final Map<String, Holder<Biome>> biomes;
    private final Holder<Biome> fallback;

    public RealmBiomeSource(Realm realm, Map<String, Holder<Biome>> biomes) {
        this.realm = realm;
        this.biomes = new LinkedHashMap<>(biomes);
        if (this.biomes.isEmpty()) throw new IllegalArgumentException("Realm biome source without biomes");
        this.fallback = this.biomes.values().iterator().next();
    }

    @Override
    protected Codec<? extends BiomeSource> codec() {
        return CODEC;
    }

    @Override
    protected Stream<Holder<Biome>> collectPossibleBiomes() {
        return biomes.values().stream().distinct();
    }

    private Holder<Biome> role(String role) {
        Holder<Biome> b = biomes.get(role);
        return b != null ? b : fallback;
    }

    @Override
    public Holder<Biome> getNoiseBiome(int qx, int qy, int qz, Climate.Sampler sampler) {
        Climate.TargetPoint tp = sampler.sample(qx, qy, qz);
        return role(pick(realm, qx << 2, qy << 2, qz << 2, Climate.unquantizeCoord(tp.continentalness()),
                Climate.unquantizeCoord(tp.erosion()), Climate.unquantizeCoord(tp.depth()),
                Climate.unquantizeCoord(tp.temperature()), Climate.unquantizeCoord(tp.humidity())));
    }

    /**
     * The biome role at a block position. {@code shape}, {@code feature}: the realm's masks (0..1); {@code depth}:
     * blocks below the surface / 128; {@code temp}, {@code wet}: variety noise (about -1..1).
     */
    public static String pick(Realm realm, int x, int y, int z, double shape, double feature, double depth, double temp, double wet) {
        double surface = y + depth * 128.0;
        switch (realm) {
            case DEADLANDS -> {
                if (shape < 0.3 && surface < 45) return "lava_sea";
                if (feature > 0.55 || surface > 128) return "spires";
                if (wet < -0.28) return "harrada_thicket";
                return temp > 0.12 ? "blood_fields" : "ashen_wastes";
            }
            case SOVNGARDE -> {
                if (RealmLayout.dist(x, z, 0, RealmLayout.SG_HALL_Z) < RealmLayout.SG_HALL_R + 55 + temp * 12) return "hall_heights";
                if (feature > 0.25) return "chasm";
                if (shape > 0.45 && surface > 118) return "mountains";
                if (Math.abs(x) < 240 + temp * 50 && z > -70 && z < 470 + wet * 40) return "mistvale";
                return wet > 0.28 ? "golden_grove" : "fields";
            }
            case SHIVERING_ISLES -> {
                if (RealmLayout.dist(x, z, RealmLayout.SI_FRINGE_X, 0) < RealmLayout.SI_FRINGE_R + 60 + temp * 15) return "fringe";
                if (shape < 0.4 && surface < 63) return "sea";
                if (feature > 0.5) {
                    if (wet > 0.32) return "mania_mushroom_forest";
                    if (temp < -0.3) return "mania_crystal_grove";
                    return "mania_fields";
                }
                return wet > -0.05 ? "dementia_forest" : "dementia_swamp";
            }
            case APOCRYPHA -> {
                if (y > 92 && depth < -0.06) return "floating_library";
                if (shape > 0.35) return "stacks";
                if (feature > 0.55 && wet < -0.1) return "tentacle_depths";
                return "ink_sea";
            }
            case SOUL_CAIRN -> {
                if (RealmLayout.dist(x, z, RealmLayout.SC_BONEYARD_X, RealmLayout.SC_BONEYARD_Z) < 140 + temp * 20) return "boneyard";
                if (feature > 0.45) return "soul_mesas";
                if (temp > 0.25) return "bone_fields";
                if (wet > 0.3) return "soul_gem_fields";
                return "wastes";
            }
            case COLDHARBOUR -> {
                if (RealmLayout.dist(x, z, 0, RealmLayout.CH_CITY_Z) < RealmLayout.CH_CITY_R + 40 + temp * 15) return "hollow_city";
                if (shape < 0.4 && surface < 60) return "lava_rift";
                if (feature > 0.5) return "black_crags";
                return temp > 0.2 ? "burning_ruins" : "chained_wastes";
            }
        }
        return "";
    }
}
