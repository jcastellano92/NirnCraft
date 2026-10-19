package com.nirncraft.magic.spell.spellmaking;

import com.nirncraft.magic.spell.Element;
import com.nirncraft.magic.spell.School;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Magic effects that can be combined at a Spellmaking Altar (Oblivion / Morrowind style). You know an effect when you
 * know any spell in its {@link #sources}. Costs follow Oblivion's formula, tuned so a made spell costs about what the
 * matching Skyrim spell does:
 * {@code base * max(1, magnitude)^1.28 * max(1, duration) * (1 + 0.15 * area)}, times 1.5 at Target range.
 */
public enum SpellEffectType {
    FIRE_DAMAGE(School.DESTRUCTION, Element.FIRE, 0.75f, 1, 100, 0, 0, true, Range.TOUCH,
            "flames", "firebolt", "fire_rune", "fireball", "flame_cloak", "incinerate", "wall_of_flames", "fire_storm"),
    FROST_DAMAGE(School.DESTRUCTION, Element.FROST, 0.75f, 1, 100, 0, 0, true, Range.TOUCH,
            "frostbite", "ice_spike", "frost_rune", "ice_storm", "frost_cloak", "icy_spear", "wall_of_frost", "blizzard"),
    SHOCK_DAMAGE(School.DESTRUCTION, Element.SHOCK, 0.85f, 1, 100, 0, 0, true, Range.TOUCH,
            "sparks", "lightning_bolt", "lightning_rune", "chain_lightning", "lightning_cloak", "thunderbolt", "wall_of_storms", "lightning_storm"),
    SUN_DAMAGE(School.RESTORATION, Element.HOLY, 0.8f, 1, 100, 0, 0, true, Range.TOUCH,
            "sun_fire", "stendarrs_aura", "vampires_bane"),
    RESTORE_HEALTH(School.RESTORATION, Element.HOLY, 0.4f, 1, 200, 0, 0, true, Range.SELF,
            "healing", "healing_hands", "fast_healing", "close_wounds", "heal_other", "grand_healing", "guardian_circle"),
    TURN_UNDEAD(School.RESTORATION, Element.HOLY, 0.04f, 10, 300, 5, 60, true, Range.TOUCH,
            "turn_lesser_undead", "turn_undead", "turn_greater_undead", "repel_lesser_undead", "repel_undead", "bane_of_the_undead"),
    SHIELD(School.ALTERATION, Element.NATURE, 0.28f, 1, 12, 5, 120, false, Range.SELF,
            "oakflesh", "stoneflesh", "ironflesh", "ebonyflesh", "dragonhide"),
    WATER_BREATHING(School.ALTERATION, Element.FROST, 2.2f, 0, 0, 10, 300, false, Range.SELF, "waterbreathing"),
    PARALYZE(School.ALTERATION, Element.ARCANE, 30f, 0, 0, 1, 30, true, Range.TOUCH, "paralyze", "mass_paralysis"),
    LIGHT(School.ALTERATION, Element.ARCANE, 0.35f, 0, 0, 10, 300, false, Range.SELF, "candlelight", "magelight"),
    DETECT_LIFE(School.ALTERATION, Element.ARCANE, 0.055f, 8, 64, 5, 60, false, Range.SELF, "detect_life", "detect_life_mysticism"),
    SOUL_TRAP(School.CONJURATION, Element.SOUL, 1.1f, 0, 0, 5, 120, true, Range.TOUCH, "soul_trap", "soul_trap_mysticism"),
    CALM(School.ILLUSION, Element.MIND, 0.022f, 10, 200, 5, 120, true, Range.TOUCH, "calm", "pacify", "harmony"),
    FEAR(School.ILLUSION, Element.MIND, 0.025f, 10, 200, 5, 120, true, Range.TOUCH, "fear", "rout", "hysteria"),
    FRENZY(School.ILLUSION, Element.MIND, 0.02f, 10, 200, 5, 120, true, Range.TOUCH, "fury", "frenzy", "mayhem"),
    INVISIBILITY(School.ILLUSION, Element.MIND, 11f, 0, 0, 5, 120, false, Range.SELF, "invisibility"),
    MUFFLE(School.ILLUSION, Element.MIND, 0.8f, 0, 0, 10, 300, false, Range.SELF, "muffle"),
    DISPEL(School.MYSTICISM, Element.ARCANE, 60f, 0, 0, 0, 0, true, Range.SELF, "dispel", "mass_dispel"),
    REFLECT_SPELL(School.MYSTICISM, Element.ARCANE, 0.077f, 5, 80, 5, 120, false, Range.SELF, "reflect_spell", "spell_turning"),
    SPELL_ABSORPTION(School.MYSTICISM, Element.ARCANE, 0.085f, 5, 80, 5, 120, false, Range.SELF, "spell_absorption", "spell_turning"),
    ABSORB_HEALTH(School.MYSTICISM, Element.SOUL, 0.9f, 1, 100, 0, 0, true, Range.TOUCH, "absorb_health"),
    WATER_WALKING(School.MYSTICISM, Element.FROST, 0.6f, 0, 0, 10, 300, false, Range.SELF, "water_walking"),
    SLOWFALL(School.MYSTICISM, Element.ARCANE, 1.2f, 0, 0, 5, 300, false, Range.SELF, "slowfall"),
    LEVITATE(School.MYSTICISM, Element.ARCANE, 8f, 0, 0, 5, 60, false, Range.SELF, "levitate");

    /** Where an effect lands: on you, on what you touch (4 blocks), or on what a bolt hits. */
    public enum Range {
        SELF('s'), TOUCH('t'), TARGET('r');

        public final char code;

        Range(char code) {
            this.code = code;
        }

        @Nullable
        public static Range byCode(char c) {
            for (Range r : values()) {
                if (r.code == c) return r;
            }
            return null;
        }

        public Component displayName() {
            return Component.translatable("spellmaking.nirncraft.range." + name().toLowerCase(Locale.ROOT));
        }
    }

    public static final SpellEffectType[] VALUES = values();
    public static final int MAX_AREA = 10;

    public final School school;
    public final Element element;
    /** Cost per magnitude point (Oblivion's base cost / 10). */
    public final float base;
    /** Magnitude bounds (0, 0 = no magnitude). */
    public final int minMag, maxMag;
    /** Duration bounds in seconds (0, 0 = instant). */
    public final int minDur, maxDur;
    /** Works on others (Touch / Target ranges, areas); otherwise self only. */
    public final boolean onOthers;
    /** Range a new effect starts at in the altar. */
    public final Range defaultRange;
    /** Spells that teach this effect. */
    public final Set<String> sources;

    SpellEffectType(School school, Element element, float base, int minMag, int maxMag, int minDur, int maxDur, boolean onOthers,
                    Range defaultRange, String... sources) {
        this.school = school;
        this.element = element;
        this.base = base;
        this.minMag = minMag;
        this.maxMag = maxMag;
        this.minDur = minDur;
        this.maxDur = maxDur;
        this.onOthers = onOthers;
        this.defaultRange = defaultRange;
        this.sources = Set.of(sources);
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean hasMagnitude() {
        return maxMag > 0;
    }

    public boolean hasDuration() {
        return maxDur > 0;
    }

    /** Area only for effects that reach others. */
    public boolean hasArea() {
        return onOthers;
    }

    /** Self-only effects can't be cast on others; the damage and mind effects can't be cast on yourself. */
    public boolean allows(Range range) {
        if (range == Range.SELF) return this == RESTORE_HEALTH || this == DISPEL || !onOthers;
        return onOthers;
    }

    /** Harms its target (marks the spell hostile; PvP rules apply). */
    public boolean harmful() {
        return switch (this) {
            case FIRE_DAMAGE, FROST_DAMAGE, SHOCK_DAMAGE, SUN_DAMAGE, PARALYZE, SOUL_TRAP, FRENZY, FEAR, ABSORB_HEALTH -> true;
            default -> false;
        };
    }

    public Component displayName() {
        return Component.translatable("spellmaking.nirncraft.effect." + id());
    }

    /** Whether a player who knows {@code knownSpells} may use this effect. */
    public boolean knownBy(List<String> knownSpells) {
        for (String s : knownSpells) {
            if (sources.contains(s)) return true;
        }
        return false;
    }

    @Nullable
    public static SpellEffectType byId(String id) {
        for (SpellEffectType t : VALUES) {
            if (t.id().equals(id)) return t;
        }
        return null;
    }
}
