package com.nirncraft.platform;

import java.util.Set;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ValueTagsTest {
    @Test
    void existingEntityFieldsKeepTheirNamesAndNativeTypes() {
        UUID owner = UUID.fromString("8c9b39e5-5b75-458a-95f9-ae66930f1593");
        CompoundTag old = new CompoundTag();
        old.putString("Role", "innkeeper");
        old.store("Owner", UUIDUtil.CODEC, owner);
        CompoundTag social = new CompoundTag();
        social.putInt("trust", 42);
        old.put("Social", social);
        old.putByteArray("UnrelatedVanillaPayload", new byte[131072]);

        CompoundTag fields = ValueTags.read(
                TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, old),
                "Role", "Owner", "Social", "MissingOptionalField");
        assertEquals(Set.of("Role", "Owner", "Social"), fields.keySet());
        assertEquals("innkeeper", fields.getStringOr("Role", ""));
        assertEquals(owner, fields.read("Owner", UUIDUtil.CODEC).orElseThrow());
        assertEquals(42, fields.getCompoundOrEmpty("Social").getIntOr("trust", 0));

        var output = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        output.putInt("VanillaField", 7);
        output.store(fields);
        CompoundTag saved = output.buildResult();
        assertEquals(7, saved.getIntOr("VanillaField", 0));
        assertEquals(owner, saved.read("Owner", UUIDUtil.CODEC).orElseThrow());
        assertEquals(social, saved.getCompoundOrEmpty("Social"));
        assertFalse(saved.contains("UnrelatedVanillaPayload"));
        assertFalse(saved.contains("nirncraft_legacy"));
    }

    @Test
    void absentCustomFieldsRemainAbsent() {
        var input = TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, new CompoundTag());
        assertTrue(ValueTags.read(input, "Role", "Owner", "Social").isEmpty());
    }
}
