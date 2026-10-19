package com.nirncraft.magic.shout;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Dragon Shouts (Thu'um): three Words of Power each, in dragon language with their translations, and the voice
 * cooldown (seconds) for shouting one, two or all three words. {@code wallWeight} is how often a Word Wall teaches it
 * (0 = never from a random wall: quest/story shouts are only taught by walls that name them).
 *
 * <p><b>Canonical shout ids</b> ({@link #id()}, documented in docs/FEATURES.md) are the lower-case enum names, e.g.
 * {@code unrelenting_force}. They are saved in player data and on Word Walls, so they never change. {@link #byId}
 * also accepts the dragon words ({@code fus_ro_dah}, {@code fus}) and returns null for anything unknown, so callers
 * (word walls, quests, commands) must treat unknown ids gracefully.</p>
 */
public enum Shout {
    UNRELENTING_FORCE(new String[]{"FUS", "RO", "DAH"}, new int[]{20, 25, 45}, 30),
    FIRE_BREATH(new String[]{"YOL", "TOOR", "SHUL"}, new int[]{30, 50, 100}, 14),
    FROST_BREATH(new String[]{"FO", "KRAH", "DIIN"}, new int[]{30, 50, 100}, 14),
    WHIRLWIND_SPRINT(new String[]{"WULD", "NAH", "KEST"}, new int[]{20, 25, 35}, 14),
    BECOME_ETHEREAL(new String[]{"FEIM", "ZII", "GRON"}, new int[]{20, 30, 40}, 10),
    CLEAR_SKIES(new String[]{"LOK", "VAH", "KOOR"}, new int[]{5, 10, 15}, 6),
    AURA_WHISPER(new String[]{"LAAS", "YAH", "NIR"}, new int[]{30, 40, 50}, 8),
    SLOW_TIME(new String[]{"TIID", "KLO", "UL"}, new int[]{30, 45, 60}, 8),
    MARKED_FOR_DEATH(new String[]{"KRII", "LUN", "AUS"}, new int[]{20, 30, 40}, 6),
    DISARM(new String[]{"ZUN", "HAAL", "VIIK"}, new int[]{30, 35, 40}, 8),
    ELEMENTAL_FURY(new String[]{"SU", "GRAH", "DUN"}, new int[]{30, 40, 50}, 8),
    KYNES_PEACE(new String[]{"KAAN", "DREM", "OV"}, new int[]{40, 50, 60}, 6),
    ANIMAL_ALLEGIANCE(new String[]{"RAAN", "MIR", "TAH"}, new int[]{50, 60, 70}, 6),
    STORM_CALL(new String[]{"STRUN", "BAH", "QO"}, new int[]{300, 480, 600}, 4),
    DRAGONREND(new String[]{"JOOR", "ZAH", "FRUL"}, new int[]{10, 12, 15}, 3),
    ICE_FORM(new String[]{"IIZ", "SLEN", "NUS"}, new int[]{60, 90, 120}, 8),
    THROW_VOICE(new String[]{"ZUL", "MEY", "GUT"}, new int[]{30, 15, 5}, 6),
    DISMAY(new String[]{"FAAS", "RU", "MAAR"}, new int[]{40, 45, 50}, 8),
    CALL_DRAGON(new String[]{"OD", "AH", "VIING"}, new int[]{5, 5, 300}, 2),
    CALL_OF_VALOR(new String[]{"HUN", "KAAL", "ZOOR"}, new int[]{180, 180, 180}, 2),
    CYCLONE(new String[]{"VEN", "GAAR", "NOS"}, new int[]{30, 45, 60}, 6),
    DRAIN_VITALITY(new String[]{"GAAN", "LAH", "HAAS"}, new int[]{30, 60, 90}, 6),
    SOUL_TEAR(new String[]{"RII", "VAAZ", "ZOL"}, new int[]{5, 5, 90}, 3),
    BEND_WILL(new String[]{"GOL", "HAH", "DOV"}, new int[]{10, 90, 120}, 3),
    DRAGON_ASPECT(new String[]{"MUL", "QAH", "DIIV"}, new int[]{5, 5, 5}, 3),
    BATTLE_FURY(new String[]{"MID", "VUR", "SHAAN"}, new int[]{20, 30, 40}, 6);

    public static final Shout[] VALUES = values();

    private final String[] words;
    private final int[] cooldowns;
    public final int wallWeight;

    Shout(String[] words, int[] cooldowns, int wallWeight) {
        this.words = words;
        this.cooldowns = cooldowns;
        this.wallWeight = wallWeight;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Dragon word {@code i} (0..2), e.g. "FUS". */
    public String word(int i) {
        return words[i];
    }

    /** Translation of word {@code i}, e.g. "Force". */
    public Component translation(int i) {
        return Component.translatable("word.nirncraft." + words[i].toLowerCase(Locale.ROOT));
    }

    /** "FUS - Force". */
    public MutableComponent wordWithTranslation(int i) {
        return Component.literal(words[i] + " - ").append(translation(i));
    }

    /** "FUS RO DAH" for the given number of words. */
    public String phrase(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(3, count); i++) {
            if (i > 0) sb.append(' ');
            sb.append(words[i]);
        }
        return sb.toString();
    }

    public int cooldownSeconds(int wordCount) {
        return cooldowns[Math.max(1, Math.min(3, wordCount)) - 1];
    }

    public Component displayName() {
        return Component.translatable("shout.nirncraft." + id());
    }

    public Component description() {
        return Component.translatable("shout.nirncraft." + id() + ".desc");
    }

    /**
     * The shout with this canonical id. Also accepts, case-insensitively, the three words ({@code fus_ro_dah},
     * {@code "FUS RO DAH"}) or the first word ({@code fus}). Null for null, blank or unknown ids.
     */
    @Nullable
    public static Shout byId(@Nullable String id) {
        if (id == null || id.isBlank()) return null;
        for (Shout s : VALUES) {
            if (s.id().equals(id)) return s;
        }
        String norm = id.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        if (norm.startsWith("nirncraft:")) norm = norm.substring("nirncraft:".length());
        for (Shout s : VALUES) {
            if (s.id().equals(norm)) return s;
            String words = (s.words[0] + "_" + s.words[1] + "_" + s.words[2]).toLowerCase(Locale.ROOT);
            if (words.equals(norm) || s.words[0].toLowerCase(Locale.ROOT).equals(norm)) return s;
        }
        return null;
    }

    /** Every canonical shout id, in order. */
    public static java.util.List<String> ids() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (Shout s : VALUES) out.add(s.id());
        return out;
    }

    public static Shout byOrdinal(int i) {
        return i >= 0 && i < VALUES.length ? VALUES[i] : UNRELENTING_FORCE;
    }
}
