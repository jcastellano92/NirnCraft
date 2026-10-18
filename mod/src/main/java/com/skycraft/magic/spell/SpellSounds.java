package com.skycraft.magic.spell;

import com.skycraft.magic.MagicFx;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The one place spell sounds are chosen. Every spell, rune, ward and shout effect asks for a {@link Slot}; the default
 * is a fitting vanilla sound. The audio module can replace any slot with its own {@code SoundEvent} through
 * {@link #override(Slot, Supplier)} (e.g. from its common setup) without touching spell code.
 */
public final class SpellSounds {
    /** A kind of spell sound. Element slots are picked from a spell's {@link Element}. */
    public enum Slot {
        CAST_FIRE, CAST_FROST, CAST_SHOCK, CAST_HOLY, CAST_MIND, CAST_SOUL, CAST_CONJURE, CAST_NATURE, CAST_ARCANE,
        LOOP_FIRE, LOOP_FROST, LOOP_SHOCK, LOOP_HOLY, LOOP_ARCANE,
        IMPACT_FIRE, IMPACT_FROST, IMPACT_SHOCK, IMPACT_HOLY, IMPACT_MIND, IMPACT_SOUL, IMPACT_ARCANE,
        CHARGE, DUAL_CAST, FIZZLE, WARD_HIT, WARD_BREAK, SUMMON, BANISH, TELEPORT, LEVITATE, DISPEL, REFLECT, ABSORB,
        SPELLMAKING, STAFF_ENCHANT
    }

    private static final Map<Slot, Supplier<SoundEvent>> SOUNDS = new EnumMap<>(Slot.class);

    static {
        set(Slot.CAST_FIRE, () -> SoundEvents.BLAZE_SHOOT);
        set(Slot.CAST_FROST, () -> SoundEvents.POWDER_SNOW_BREAK);
        set(Slot.CAST_SHOCK, () -> SoundEvents.FIREWORK_ROCKET_TWINKLE);
        set(Slot.CAST_HOLY, () -> SoundEvents.AMETHYST_BLOCK_CHIME);
        set(Slot.CAST_MIND, () -> SoundEvents.ILLUSIONER_CAST_SPELL);
        set(Slot.CAST_SOUL, () -> SoundEvents.SOUL_ESCAPE.value());
        set(Slot.CAST_CONJURE, () -> SoundEvents.EVOKER_PREPARE_SUMMON);
        set(Slot.CAST_NATURE, () -> SoundEvents.EVOKER_CAST_SPELL);
        set(Slot.CAST_ARCANE, () -> SoundEvents.ENCHANTMENT_TABLE_USE);
        set(Slot.LOOP_FIRE, () -> SoundEvents.FIRE_AMBIENT);
        set(Slot.LOOP_FROST, () -> SoundEvents.POWDER_SNOW_STEP);
        set(Slot.LOOP_SHOCK, () -> SoundEvents.FIREWORK_ROCKET_TWINKLE_FAR);
        set(Slot.LOOP_HOLY, () -> SoundEvents.AMETHYST_BLOCK_RESONATE);
        set(Slot.LOOP_ARCANE, () -> SoundEvents.BEACON_AMBIENT);
        set(Slot.IMPACT_FIRE, () -> SoundEvents.FIRECHARGE_USE);
        set(Slot.IMPACT_FROST, () -> SoundEvents.GLASS_BREAK);
        set(Slot.IMPACT_SHOCK, () -> SoundEvents.LIGHTNING_BOLT_IMPACT);
        set(Slot.IMPACT_HOLY, () -> SoundEvents.AMETHYST_BLOCK_BREAK);
        set(Slot.IMPACT_MIND, () -> SoundEvents.ILLUSIONER_MIRROR_MOVE);
        set(Slot.IMPACT_SOUL, () -> SoundEvents.SOUL_ESCAPE.value());
        set(Slot.IMPACT_ARCANE, () -> SoundEvents.AMETHYST_CLUSTER_BREAK);
        set(Slot.CHARGE, () -> SoundEvents.BEACON_AMBIENT);
        set(Slot.DUAL_CAST, () -> SoundEvents.EVOKER_CAST_SPELL);
        set(Slot.FIZZLE, () -> SoundEvents.FIRE_EXTINGUISH);
        set(Slot.WARD_HIT, () -> SoundEvents.AMETHYST_BLOCK_HIT);
        set(Slot.WARD_BREAK, () -> SoundEvents.GLASS_BREAK);
        set(Slot.SUMMON, () -> SoundEvents.EVOKER_PREPARE_SUMMON);
        set(Slot.BANISH, () -> SoundEvents.ILLUSIONER_MIRROR_MOVE);
        set(Slot.TELEPORT, () -> SoundEvents.ENDERMAN_TELEPORT);
        set(Slot.LEVITATE, () -> SoundEvents.ELYTRA_FLYING);
        set(Slot.DISPEL, () -> SoundEvents.BEACON_DEACTIVATE);
        set(Slot.REFLECT, () -> SoundEvents.SHIELD_BLOCK.value());
        set(Slot.ABSORB, () -> SoundEvents.BEACON_POWER_SELECT);
        set(Slot.SPELLMAKING, () -> SoundEvents.ENCHANTMENT_TABLE_USE);
        set(Slot.STAFF_ENCHANT, () -> SoundEvents.ENCHANTMENT_TABLE_USE);
    }

    private SpellSounds() {}

    private static void set(Slot slot, Supplier<SoundEvent> sound) {
        SOUNDS.put(slot, sound);
    }

    /** Replaces the sound of a slot (audio module hook). Call during setup, before players join. */
    public static synchronized void override(Slot slot, Supplier<SoundEvent> sound) {
        set(slot, sound);
    }

    public static SoundEvent get(Slot slot) {
        Supplier<SoundEvent> s = SOUNDS.get(slot);
        SoundEvent e = s == null ? null : s.get();
        return e != null ? e : SoundEvents.ENCHANTMENT_TABLE_USE;
    }

    public static Slot castSlot(Element element) {
        return switch (element) {
            case FIRE -> Slot.CAST_FIRE;
            case FROST -> Slot.CAST_FROST;
            case SHOCK -> Slot.CAST_SHOCK;
            case HOLY -> Slot.CAST_HOLY;
            case MIND -> Slot.CAST_MIND;
            case SOUL -> Slot.CAST_SOUL;
            case CONJURE -> Slot.CAST_CONJURE;
            case NATURE -> Slot.CAST_NATURE;
            default -> Slot.CAST_ARCANE;
        };
    }

    public static Slot loopSlot(Element element) {
        return switch (element) {
            case FIRE -> Slot.LOOP_FIRE;
            case FROST -> Slot.LOOP_FROST;
            case SHOCK -> Slot.LOOP_SHOCK;
            case HOLY -> Slot.LOOP_HOLY;
            default -> Slot.LOOP_ARCANE;
        };
    }

    public static Slot impactSlot(Element element) {
        return switch (element) {
            case FIRE -> Slot.IMPACT_FIRE;
            case FROST -> Slot.IMPACT_FROST;
            case SHOCK -> Slot.IMPACT_SHOCK;
            case HOLY -> Slot.IMPACT_HOLY;
            case MIND -> Slot.IMPACT_MIND;
            case SOUL -> Slot.IMPACT_SOUL;
            default -> Slot.IMPACT_ARCANE;
        };
    }

    /** The cast sound of a spell at the caster's hands (Quiet Casting: only the caster hears it). */
    public static void cast(ServerPlayer p, Spell spell) {
        Vec3 pos = p.getEyePosition();
        float pitch = spell.element == Element.SHOCK ? 1.6f : spell.element == Element.FROST ? 0.7f : 1.1f + p.getRandom().nextFloat() * 0.2f;
        MagicFx.sound(p, pos, get(castSlot(spell.element)), 0.9f, pitch);
        if (spell.element == Element.FROST) MagicFx.sound(p, pos, SoundEvents.AMETHYST_BLOCK_CHIME, 1f, 1.6f);
    }

    /** The looping sound of a held concentration spell (call every ~10 ticks). */
    public static void loop(ServerPlayer p, Spell spell) {
        MagicFx.sound(p, com.skycraft.magic.Targeting.handPos(p), get(loopSlot(spell.element)), 0.8f, 0.9f + p.getRandom().nextFloat() * 0.3f);
    }

    /** An impact anyone nearby hears. */
    public static void impact(ServerLevel level, Vec3 pos, Element element, float volume) {
        float pitch = element == Element.SHOCK ? 1.7f : 1.0f + level.getRandom().nextFloat() * 0.2f;
        MagicFx.worldSound(level, pos, get(impactSlot(element)), SoundSource.PLAYERS, element == Element.SHOCK ? volume * 0.5f : volume, pitch);
    }

    /** A slot sound at the caster (Quiet Casting respected). */
    public static void play(ServerPlayer p, Slot slot, float volume, float pitch) {
        MagicFx.sound(p, p.position(), get(slot), volume, pitch);
    }

    /** A slot sound everybody nearby hears. */
    public static void world(ServerLevel level, Vec3 pos, Slot slot, float volume, float pitch) {
        MagicFx.worldSound(level, pos, get(slot), SoundSource.PLAYERS, volume, pitch);
    }
}
