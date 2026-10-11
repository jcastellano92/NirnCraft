package com.skycraft.crafting.arcane.alchemy;

import com.skycraft.crafting.arcane.effect.ArcaneEffects;
import com.skycraft.registry.ModEffects;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffects;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * An alchemical effect an ingredient can carry. Each maps to a Minecraft {@link MobEffect}; its strength in a
 * brewed potion is derived from the brewer's strength multiplier {@code m}:
 * <ul>
 *   <li>instant effects: amplifier {@code = floor((m - 1) / ampStep)} capped at {@code maxAmp};</li>
 *   <li>lasting effects: duration {@code = seconds * min(m, 4)}, amplifier as above.</li>
 * </ul>
 * Gold value is {@code baseValue * m}.
 */
public enum AlchemyEffect {
    // ---------------------------------------------------------------- beneficial
    RESTORE_HEALTH(() -> MobEffects.HEAL, true, 0, 2, 1.25f, 21, true),
    RESTORE_MAGICKA(ArcaneEffects.RESTORE_MAGICKA, true, 0, 7, 0.5f, 25, true),
    RESTORE_STAMINA(ArcaneEffects.RESTORE_STAMINA, true, 0, 7, 0.5f, 25, true),
    REGENERATE_HEALTH(() -> MobEffects.REGENERATION, true, 20, 1, 2f, 40, false),
    REGENERATE_MAGICKA(ArcaneEffects.REGENERATE_MAGICKA, true, 30, 3, 1f, 40, false),
    REGENERATE_STAMINA(ArcaneEffects.REGENERATE_STAMINA, true, 30, 3, 1f, 40, false),
    FORTIFY_HEALTH(() -> MobEffects.HEALTH_BOOST, true, 60, 2, 1.5f, 50, false),
    FORTIFY_MAGICKA(ArcaneEffects.FORTIFY_MAGICKA, true, 60, 3, 1f, 45, false),
    FORTIFY_STAMINA(ArcaneEffects.FORTIFY_STAMINA, true, 60, 3, 1f, 45, false),
    RESIST_FIRE(() -> MobEffects.FIRE_RESISTANCE, true, 60, 0, 1f, 40, false),
    RESIST_FROST(ArcaneEffects.RESIST_FROST, true, 60, 3, 1f, 40, false),
    RESIST_SHOCK(ArcaneEffects.RESIST_SHOCK, true, 60, 3, 1f, 40, false),
    RESIST_POISON(ArcaneEffects.RESIST_POISON, true, 60, 3, 1f, 45, false),
    RESIST_MAGIC(ArcaneEffects.RESIST_MAGIC, true, 60, 3, 1f, 60, false),
    FORTIFY_ONE_HANDED(ArcaneEffects.FORTIFY_ONE_HANDED, true, 60, 4, 1f, 45, false),
    FORTIFY_TWO_HANDED(ArcaneEffects.FORTIFY_TWO_HANDED, true, 60, 4, 1f, 45, false),
    FORTIFY_ARCHERY(ArcaneEffects.FORTIFY_ARCHERY, true, 60, 4, 1f, 45, false),
    FORTIFY_BLOCK(() -> MobEffects.ABSORPTION, true, 60, 2, 1.5f, 40, false),
    FORTIFY_LIGHT_ARMOR(ArcaneEffects.FORTIFY_LIGHT_ARMOR, true, 60, 3, 1f, 30, false),
    FORTIFY_HEAVY_ARMOR(ArcaneEffects.FORTIFY_HEAVY_ARMOR, true, 60, 3, 1f, 30, false),
    FORTIFY_CARRY_WEIGHT(ArcaneEffects.FORTIFY_CARRY_WEIGHT, true, 120, 2, 1.5f, 30, false),
    FORTIFY_SNEAK(ArcaneEffects.FORTIFY_SNEAK, true, 60, 3, 1f, 40, false),
    FORTIFY_SMITHING(ArcaneEffects.FORTIFY_SMITHING, true, 30, 3, 1f, 60, false),
    FORTIFY_ENCHANTING(ArcaneEffects.FORTIFY_ENCHANTING, true, 30, 3, 1f, 60, false),
    FORTIFY_ALCHEMY(ArcaneEffects.FORTIFY_ALCHEMY, true, 30, 3, 1f, 60, false),
    FORTIFY_DESTRUCTION(ArcaneEffects.FORTIFY_DESTRUCTION, true, 60, 3, 1f, 50, false),
    FORTIFY_RESTORATION(ArcaneEffects.FORTIFY_RESTORATION, true, 60, 3, 1f, 50, false),
    FORTIFY_CONJURATION(ArcaneEffects.FORTIFY_CONJURATION, true, 60, 3, 1f, 50, false),
    FORTIFY_ILLUSION(ArcaneEffects.FORTIFY_ILLUSION, true, 60, 3, 1f, 50, false),
    FORTIFY_ALTERATION(ArcaneEffects.FORTIFY_ALTERATION, true, 60, 3, 1f, 50, false),
    FORTIFY_LOCKPICKING(ArcaneEffects.FORTIFY_LOCKPICKING, true, 60, 3, 1f, 45, false),
    FORTIFY_PICKPOCKET(ArcaneEffects.FORTIFY_PICKPOCKET, true, 60, 3, 1f, 45, false),
    FORTIFY_BARTER(ArcaneEffects.FORTIFY_BARTER, true, 60, 3, 1f, 45, false),
    INVISIBILITY(() -> MobEffects.INVISIBILITY, true, 30, 0, 1f, 80, false),
    WATERBREATHING(() -> MobEffects.WATER_BREATHING, true, 60, 0, 1f, 50, false),
    NIGHT_EYE(() -> MobEffects.NIGHT_VISION, true, 60, 0, 1f, 30, false),
    SWIFTNESS(() -> MobEffects.MOVEMENT_SPEED, true, 45, 2, 1.5f, 40, false),
    LEAP(() -> MobEffects.JUMP, true, 45, 2, 1.5f, 30, false),
    FEATHERFALL(() -> MobEffects.SLOW_FALLING, true, 45, 0, 1f, 30, false),
    CURE_DISEASE(ArcaneEffects.CURE_DISEASE, true, 0, 0, 1f, 25, false),
    CURE_POISON(ArcaneEffects.CURE_POISON, true, 0, 0, 1f, 25, false),
    // ---------------------------------------------------------------- harmful
    DAMAGE_HEALTH(() -> MobEffects.HARM, false, 0, 2, 1.25f, 15, false),
    DAMAGE_MAGICKA(ArcaneEffects.DAMAGE_MAGICKA, false, 0, 7, 0.5f, 12, false),
    DAMAGE_STAMINA(ArcaneEffects.DAMAGE_STAMINA, false, 0, 7, 0.5f, 12, false),
    DAMAGE_MAGICKA_REGEN(ArcaneEffects.DAMAGE_MAGICKA_REGEN, false, 30, 3, 1f, 20, false),
    DAMAGE_STAMINA_REGEN(ArcaneEffects.DAMAGE_STAMINA_REGEN, false, 30, 3, 1f, 20, false),
    LINGERING_DAMAGE_HEALTH(() -> MobEffects.POISON, false, 10, 1, 2f, 30, false),
    RAVAGE_HEALTH(() -> MobEffects.WITHER, false, 10, 1, 2f, 25, false),
    RAVAGE_MAGICKA(ArcaneEffects.RAVAGE_MAGICKA, false, 10, 3, 1f, 20, false),
    RAVAGE_STAMINA(ArcaneEffects.RAVAGE_STAMINA, false, 10, 3, 1f, 20, false),
    LINGERING_DAMAGE_MAGICKA(ArcaneEffects.LINGERING_DAMAGE_MAGICKA, false, 20, 2, 1.5f, 20, false),
    LINGERING_DAMAGE_STAMINA(ArcaneEffects.LINGERING_DAMAGE_STAMINA, false, 20, 2, 1.5f, 20, false),
    WEAKNESS_TO_FIRE(ArcaneEffects.WEAKNESS_TO_FIRE, false, 30, 3, 1f, 30, false),
    WEAKNESS_TO_FROST(ArcaneEffects.WEAKNESS_TO_FROST, false, 30, 3, 1f, 30, false),
    WEAKNESS_TO_SHOCK(ArcaneEffects.WEAKNESS_TO_SHOCK, false, 30, 3, 1f, 30, false),
    WEAKNESS_TO_POISON(ArcaneEffects.WEAKNESS_TO_POISON, false, 30, 3, 1f, 30, false),
    WEAKNESS_TO_MAGIC(ArcaneEffects.WEAKNESS_TO_MAGIC, false, 30, 3, 1f, 30, false),
    ENFEEBLE(() -> MobEffects.WEAKNESS, false, 20, 1, 2f, 20, false),
    SLOW(() -> MobEffects.MOVEMENT_SLOWDOWN, false, 10, 2, 1.5f, 25, false),
    PARALYSIS(ModEffects.PARALYSIS, false, 4, 0, 1f, 80, false),
    FEAR(ArcaneEffects.FEAR, false, 15, 0, 1f, 40, false),
    FRENZY(ArcaneEffects.FRENZY, false, 15, 0, 1f, 40, false);

    public static final AlchemyEffect[] VALUES = values();

    private final Supplier<? extends MobEffect> effect;
    public final boolean positive;
    /** Base duration in seconds; 0 for instant effects. */
    public final int seconds;
    public final int maxAmp;
    public final float ampStep;
    public final float baseValue;
    /** Restore Health/Magicka/Stamina: boosted by the Physician perk. */
    public final boolean restore;

    AlchemyEffect(Supplier<? extends MobEffect> effect, boolean positive, int seconds, int maxAmp, float ampStep, float baseValue, boolean restore) {
        this.effect = effect;
        this.positive = positive;
        this.seconds = seconds;
        this.maxAmp = maxAmp;
        this.ampStep = ampStep;
        this.baseValue = baseValue;
        this.restore = restore;
    }

    public MobEffect mobEffect() {
        return effect.get();
    }

    public boolean instant() {
        return seconds <= 0;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public Component displayName() {
        return Component.translatable("alchemy.skycraft." + id());
    }

    public Component coloredName() {
        return displayName().copy().withStyle(positive ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    public int amplifier(float strength) {
        return Math.max(0, Math.min(maxAmp, (int) Math.floor((strength - 1f) / ampStep + 1e-4f)));
    }

    public int durationTicks(float strength) {
        if (instant()) return 1;
        return Math.max(20, Math.round(seconds * 20f * Math.min(strength, 4f)));
    }

    public float value(float strength) {
        return baseValue * strength;
    }

    public static AlchemyEffect byId(String id) {
        for (AlchemyEffect e : VALUES) if (e.id().equals(id)) return e;
        return null;
    }
}
