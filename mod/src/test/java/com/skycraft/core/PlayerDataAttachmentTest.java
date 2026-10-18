package com.skycraft.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerDataAttachmentTest {
    @Test
    void attachmentCodecPreservesAllExistingPlayerModuleData() {
        var original = new PlayerData();
        original.setLevel(34);
        original.setSkill(Skill.DESTRUCTION, 81);
        original.setSkillXp(Skill.DESTRUCTION, 12.5f);
        original.setGold(5_000_000_000L);
        original.module("quests").putString("active", "whiterun_watch");
        original.module("pack").putByteArray("payload", new byte[]{1, 4, 9});
        var encoded = PlayerData.CODEC.codec().encodeStart(NbtOps.INSTANCE, original).getOrThrow();
        assertInstanceOf(CompoundTag.class, encoded);
        assertEquals(original.save(), ((CompoundTag) encoded).getCompoundOrEmpty("data"));
        var restored = PlayerData.CODEC.codec().parse(NbtOps.INSTANCE, encoded).getOrThrow();
        assertEquals(original.save(), restored.save());
        restored.module("quests").putString("active", "changed");
        assertEquals("whiterun_watch", original.module("quests").getStringOr("active", ""));
    }

    @Test
    void cloneStorageAndUnknownModulesRemainIndependent() {
        var original = new PlayerData();
        original.module("future_module").putLong("value", 123L);
        var clone = new PlayerData();
        clone.copyFrom(original);
        assertEquals(original.save(), clone.save());
        clone.module("future_module").putLong("value", 456L);
        assertEquals(123L, original.module("future_module").getLongOr("value", 0L));
        assertEquals(456L, clone.module("future_module").getLongOr("value", 0L));
    }
}
