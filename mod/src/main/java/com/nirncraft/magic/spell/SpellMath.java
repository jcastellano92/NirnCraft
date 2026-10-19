package com.nirncraft.magic.spell;

import com.nirncraft.core.SkyData;
import com.nirncraft.perk.Perks;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/** Skyrim formulas: magicka cost, damage/healing/duration multipliers from perks. */
public final class SpellMath {
    private SpellMath() {}

    /**
     * Effective magicka cost: {@code base * (1 - skill/400)^2}, halved by the matching Novice..Master perk.
     * For concentration spells this is the cost per second.
     */
    public static float cost(Player player, Spell spell) {
        int skill = Math.min(100, SkyData.get(player).getSkill(spell.school.skill));
        float f = 1f - skill / 400f;
        float cost = spell.cost * f * f;
        if (Perks.has(player, spell.tier.perkFor(spell.school))) cost *= 0.5f;
        if (ANCHORED.contains(spell.id) && Perks.has(player, "mysticism.anchor")) cost *= 0.5f;
        return Math.max(0f, cost);
    }

    /** Teleport spells made cheaper by the Mysticism perk Anchor. */
    private static final java.util.Set<String> ANCHORED = java.util.Set.of("mark", "recall", "divine_intervention", "almsivi_intervention");

    /** Damage multiplier of a destruction (or other offensive) spell against a target. */
    public static float damageMult(Player player, Spell spell, @Nullable LivingEntity target) {
        return damageMult(player, spell.element, target);
    }

    public static float damageMult(Player player, Element element, @Nullable LivingEntity target) {
        float mult = 1f;
        switch (element) {
            case FIRE -> mult += 0.25f * Perks.rank(player, "destruction.augmented_flames");
            case FROST -> mult += 0.25f * Perks.rank(player, "destruction.augmented_frost");
            case SHOCK -> mult += 0.25f * Perks.rank(player, "destruction.augmented_shock");
            default -> {
            }
        }
        if (target != null && com.nirncraft.platform.Effects.isUndead(target) && Perks.has(player, "restoration.necromage")) mult *= 1.25f;
        return mult;
    }

    /** Healing multiplier (Regeneration perk: +50%). */
    public static float healMult(Player player) {
        return Perks.has(player, "restoration.regeneration") ? 1.5f : 1f;
    }

    /** Duration of an Alteration effect (Stability perk: +50%). */
    public static int alterationDuration(Player player, Spell spell) {
        return Perks.has(player, "alteration.stability") ? spell.duration * 3 / 2 : spell.duration;
    }

    /** Restoration spells are 25% stronger against undead with Necromage. */
    public static float undeadMult(Player player, LivingEntity target) {
        return com.nirncraft.platform.Effects.isUndead(target) && Perks.has(player, "restoration.necromage") ? 1.25f : 1f;
    }
}
