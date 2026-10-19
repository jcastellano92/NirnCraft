package com.nirncraft.core;

import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * The 18 skills of Skyrim, Unarmed and Polearms, the Elder Scrolls classics Mysticism and Acrobatics, and the
 * "life" skills (Mining, Woodcutting, Fishing, Hunting, Athletics, Riding, Cooking).
 *
 * <p>{@code useMult} converts a "use value" (e.g. Skyrim-scale damage dealt, gold value of a crafted item)
 * into skill XP. {@code improveMult}/{@code improveOffset} define the XP needed per level:
 * {@code improveMult * level^1.95 + improveOffset} (the Skyrim curve).</p>
 */
public enum Skill {
    // Warrior (red constellation)
    ONE_HANDED(Group.WARRIOR, 6.3f, 1f, 0f, "The Lady"),
    TWO_HANDED(Group.WARRIOR, 5.95f, 1f, 0f, "The Lord"),
    ARCHERY(Group.WARRIOR, 9.3f, 1f, 0f, "The Warrior"),
    BLOCK(Group.WARRIOR, 8.1f, 1f, 0f, "The Lady"),
    HEAVY_ARMOR(Group.WARRIOR, 3.8f, 1f, 0f, "The Lord"),
    SMITHING(Group.WARRIOR, 0.25f, 1f, 300f, "The Steed"),
    UNARMED(Group.WARRIOR, 5.5f, 1f, 0f, "The Warrior"),
    /** Spears, halberds and glaives (arsenal). */
    POLEARM(Group.WARRIOR, 5.95f, 1f, 0f, "The Warrior"),
    // Mage (blue constellation)
    DESTRUCTION(Group.MAGE, 1.35f, 1f, 0f, "The Mage"),
    RESTORATION(Group.MAGE, 2.0f, 1f, 0f, "The Mage"),
    ALTERATION(Group.MAGE, 3.0f, 1f, 0f, "The Apprentice"),
    CONJURATION(Group.MAGE, 2.1f, 1f, 0f, "The Atronach"),
    ILLUSION(Group.MAGE, 4.6f, 1f, 0f, "The Ritual"),
    /** Oblivion/Morrowind's sixth school: telekinesis, dispel, reflect, absorb, Mark & Recall, levitation. */
    MYSTICISM(Group.MAGE, 2.2f, 1f, 0f, "The Apprentice"),
    ENCHANTING(Group.MAGE, 900f, 1f, 170f, "The Apprentice"),
    // Thief (green constellation)
    LIGHT_ARMOR(Group.THIEF, 4.0f, 1f, 0f, "The Lover"),
    SNEAK(Group.THIEF, 11.25f, 1f, 0f, "The Shadow"),
    LOCKPICKING(Group.THIEF, 45f, 1f, 300f, "The Thief"),
    PICKPOCKET(Group.THIEF, 8.1f, 1f, 250f, "The Thief"),
    SPEECH(Group.THIEF, 0.36f, 1f, 0f, "The Lover"),
    ALCHEMY(Group.THIEF, 0.75f, 1f, 65f, "The Tower"),
    /** Jumping and falling (Oblivion/Morrowind). */
    ACROBATICS(Group.THIEF, 1.0f, 1f, 0f, "The Shadow"),
    // Life (gold constellation) - NirnCraft additions
    MINING(Group.LIFE, 1.0f, 1f, 0f, "The Serpent"),
    WOODCUTTING(Group.LIFE, 1.0f, 1f, 0f, "The Tower"),
    FISHING(Group.LIFE, 1.0f, 1f, 0f, "The Ritual"),
    HUNTING(Group.LIFE, 1.0f, 1f, 0f, "The Steed"),
    ATHLETICS(Group.LIFE, 3.5f, 1f, 0f, "The Steed"),
    /** Horseback riding and mounted combat. */
    RIDING(Group.LIFE, 1.0f, 1f, 0f, "The Steed"),
    /** Cooking at the cooking pot. */
    COOKING(Group.LIFE, 1.0f, 1f, 20f, "The Lady");

    public enum Group {
        WARRIOR(0xC8463C), MAGE(0x4A7BD8), THIEF(0x4CB050), LIFE(0xD8B04A);
        public final int color;

        Group(int color) {
            this.color = color;
        }
    }

    public static final Skill[] VALUES = values();
    public static final int MAX_LEVEL = 100;
    public static final int BASE_LEVEL = 15;

    public final Group group;
    public final float useMult;
    public final float improveMult;
    public final float improveOffset;
    public final String sign;

    Skill(Group group, float useMult, float improveMult, float improveOffset, String sign) {
        this.group = group;
        this.useMult = useMult;
        this.improveMult = improveMult;
        this.improveOffset = improveOffset;
        this.sign = sign;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Component displayName() {
        return Component.translatable("skill.nirncraft." + id());
    }

    public Component description() {
        return Component.translatable("skill.nirncraft." + id() + ".desc");
    }

    /** Skill XP required to advance from {@code level} to {@code level + 1}. */
    public float xpToNext(int level) {
        return improveMult * (float) Math.pow(Math.max(1, level), 1.95) + improveOffset;
    }

    public static Skill byId(String id) {
        for (Skill s : VALUES) {
            if (s.id().equals(id)) return s;
        }
        return null;
    }
}
