package com.skycraft.aether;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.skycraft.tamriel.terrain.TamrielHeight;
import com.skycraft.worldgen.WorldNoise;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** The offline harnesses' world: the overworld's tamriel_height for a seed, exactly as the game seeds it. */
final class HarnessSeed {
    private HarnessSeed() {}

    /** As RandomState builds it: Noises.instantiate = def.create(XOROSHIRO(seed).forkPositional().fromHashOf(id)). */
    static TamrielHeight height(long seed) throws Exception {
        PositionalRandomFactory r = WorldgenRandom.Algorithm.XOROSHIRO.newInstance(seed).forkPositional();
        WorldNoise[] n = new WorldNoise[AetherExactSource.NOISES.length];
        for (int i = 0; i < n.length; i++) {
            String name = AetherExactSource.NOISES[i];
            try (InputStream in = HarnessSeed.class.getResourceAsStream("/data/skycraft/worldgen/noise/" + name + ".json")) {
                if (in == null) throw new IllegalStateException("noise " + name + " not on the classpath");
                NormalNoise def = NormalNoise.DIRECT_CODEC.parse(JsonOps.INSTANCE,
                        JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8))).getOrThrow();
                n[i] = new WorldNoise(null, def.create(r.fromHashOf(Identifier.fromNamespaceAndPath("skycraft", name))));
            }
        }
        return new TamrielHeight(n[0], n[1], n[2], n[3]);
    }
}
