package com.nirncraft.magic.spell;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/** Spell levels. The matching perk ("destruction.adept_destruction") halves the magicka cost. */
public enum Tier {
    NOVICE(0x9FD08A, 20),
    APPRENTICE(0x8AC0E8, 10),
    ADEPT(0xC79AE8, 5),
    EXPERT(0xE8B060, 2),
    MASTER(0xF06060, 1);

    public static final Tier[] VALUES = values();

    public final int color;
    /** Relative weight of tomes of this level in loot. */
    public final int lootWeight;

    Tier(int color, int lootWeight) {
        this.color = color;
        this.lootWeight = lootWeight;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Component displayName() {
        return Component.translatable("magic.nirncraft.tier." + id());
    }

    /** The cost-halving perk of this level for a school, e.g. {@code "destruction.apprentice_destruction"}. */
    public String perkFor(School school) {
        return school.id() + "." + id() + "_" + school.id();
    }
}
