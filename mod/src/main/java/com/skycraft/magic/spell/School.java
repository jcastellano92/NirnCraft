package com.skycraft.magic.spell;

import com.skycraft.core.Skill;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** The six schools of magic (Skyrim's five plus Mysticism). Each trains its own skill and has its own Novice..Master cost perks. */
public enum School {
    DESTRUCTION(Skill.DESTRUCTION, 0xE0603A),
    RESTORATION(Skill.RESTORATION, 0xE8C860),
    ALTERATION(Skill.ALTERATION, 0x58C08A),
    CONJURATION(Skill.CONJURATION, 0xA060E0),
    ILLUSION(Skill.ILLUSION, 0x60A8F0),
    MYSTICISM(Skill.MYSTICISM, 0x5CD6C8);

    public static final School[] VALUES = values();

    public final Skill skill;
    /** Accent color used by the HUD, the magic menu and tooltips. */
    public final int color;

    School(Skill skill, int color) {
        this.skill = skill;
        this.color = color;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Component displayName() {
        return skill.displayName();
    }
}
