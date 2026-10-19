package com.nirncraft.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Compare the retained authored noise descriptions with the real native parity constructor and samplers. */
class NoiseDataParityTest {
    private static JsonObject read(String resource) throws Exception {
        try (var input = NoiseDataParityTest.class.getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test void everyAuthoredNoiseRetainsNativeParitySamplesAcrossSeedsAndCoordinates() throws Exception {
        var old = read("/nirncraft/migration/noise-before-263.json");
        assertEquals(10, old.size());
        for (var entry : old.entrySet()) {
            var description = entry.getValue().getAsJsonObject();
            var originalAmplitudes = description.getAsJsonArray("amplitudes");
            var amplitudes = new double[originalAmplitudes.size()];
            for (int i = 0; i < amplitudes.length; i++) amplitudes[i] = originalAmplitudes.get(i).getAsDouble();
            var parity = NormalNoise.createParity(description.get("firstOctave").getAsInt(), amplitudes);
            var actualJson = read("/data/nirncraft/worldgen/noise/" + entry.getKey());
            var actual = NormalNoise.DIRECT_CODEC.parse(JsonOps.INSTANCE, actualJson).getOrThrow();
            var expectedJson = NormalNoise.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, parity).getOrThrow().getAsJsonObject();
            assertEquals(expectedJson.get("base_octave"), actualJson.get("base_octave"), entry.getKey());
            assertEquals(expectedJson.get("octave_count"), actualJson.get("octave_count"), entry.getKey());
            assertEquals(expectedJson.get("base_amplitude").getAsDouble(), actualJson.get("base_amplitude").getAsDouble(), 1e-13, entry.getKey());
            assertEquals(expectedJson.get("amplitude_modifiers"), actualJson.get("amplitude_modifiers"), entry.getKey());
            for (long seed : new long[]{0, 17, 0x534B594352414654L}) {
                var expectedSamples = parity.create(RandomSource.create(seed));
                var actualSamples = actual.create(RandomSource.create(seed));
                for (int i = -16; i < 16; i++) {
                    double x = i * 117.375, y = i * 19.625 + 2.5, z = i * -371.875 + 0.125;
                    assertEquals(Float.floatToIntBits(expectedSamples.get(x, y, z)),
                            Float.floatToIntBits(actualSamples.get(x, y, z)), entry.getKey() + " seed=" + seed + " sample=" + i);
                }
            }
        }
    }
}
