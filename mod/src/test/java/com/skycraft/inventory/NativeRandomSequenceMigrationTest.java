package com.skycraft.inventory;

import com.mojang.serialization.Dynamic;
import com.skycraft.testing.BoundedServerProvider;
import java.util.LinkedHashMap;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.RandomSequence;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(BoundedServerProvider.class)
class NativeRandomSequenceMigrationTest {
    @Test void actualLegacySequenceStatesSurviveNativeFixingAndCodecWithoutChangingNextRandomValue(MinecraftServer server) throws Exception {
        CompoundTag input;
        try (var stream = getClass().getResourceAsStream("/migration/random-sequences-1.20.1-converted-3565.dat")) {
            assertNotNull(stream);
            input = NbtIo.readCompressed(stream, NbtAccounter.create(1_048_576));
        }
        int version = input.getIntOr("DataVersion", 0);
        assertEquals(3565, version, "Only the exact completed random-sequence schema transition advances");
        var fixed = (CompoundTag) DataFixers.getDataFixer().update(References.SAVED_DATA_RANDOM_SEQUENCES,
                new Dynamic<>(NbtOps.INSTANCE, input), version, SharedConstants.getCurrentVersion().dataVersion().version()).getValue();
        CompoundTag data = fixed.getCompoundOrEmpty("data");
        assertEquals(0, data.getInt("salt").orElseThrow());
        assertTrue(data.getBoolean("include_world_seed").orElseThrow());
        assertTrue(data.getBoolean("include_sequence_id").orElseThrow());
        RandomSequences decoded = RandomSequences.CODEC.parse(NbtOps.INSTANCE, data).getOrThrow();
        var actual = new LinkedHashMap<Identifier, RandomSequence>();
        decoded.forAllSequences(actual::put);
        CompoundTag expected = input.getCompoundOrEmpty("data").getCompoundOrEmpty("sequences");
        assertEquals(15, expected.keySet().size(), "The real archived fixture contains fifteen independent streams");
        assertEquals(expected.keySet().size(), actual.size());
        for (var entry : actual.entrySet()) {
            long[] state = expected.getCompoundOrEmpty(entry.getKey().toString()).getLongArray("source").orElseThrow();
            CompoundTag saved = (CompoundTag) RandomSequence.CODEC.encodeStart(NbtOps.INSTANCE, entry.getValue()).getOrThrow();
            assertArrayEquals(state, saved.getLongArray("source").orElseThrow(), entry.getKey().toString());
            assertEquals(new XoroshiroRandomSource(state[0], state[1]).nextLong(),
                    decoded.get(entry.getKey(), 1234L).nextLong(), "Native load must preserve the exact next random result");
        }
        Identifier fresh = Identifier.parse("skycraft:migration/new_sequence");
        assertEquals(new RandomSequence(1234L, fresh).random().nextLong(), decoded.get(fresh, 1234L).nextLong(),
                "New streams must retain 1.20.1 world-seed + sequence-ID defaults");
    }
}
