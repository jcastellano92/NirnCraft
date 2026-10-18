package com.skycraft.magic;

import com.skycraft.Skycraft;
import com.skycraft.magic.spell.Element;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Spell damage types ({@code data/skycraft/damage_type/*.json}). Fire spells are tagged {@code is_fire}, frost
 * spells {@code is_freezing}, and all of them {@code bypasses_armor} (in Skyrim armor does not stop spells).
 */
public final class MagicDamage {
    public static final ResourceKey<DamageType> FIRE = key("fire_spell");
    public static final ResourceKey<DamageType> FROST = key("frost_spell");
    public static final ResourceKey<DamageType> SHOCK = key("shock_spell");
    public static final ResourceKey<DamageType> SPELL = key("spell");

    private MagicDamage() {}

    private static ResourceKey<DamageType> key(String name) {
        return ResourceKey.create(Registries.DAMAGE_TYPE, Identifier.fromNamespaceAndPath(Skycraft.MODID, name));
    }

    /**
     * Creates a spell damage source. Pass {@code direct = null} for beams and areas so the combat handler does not
     * mistake the hit for a melee attack; {@code causing} gets the kill credit.
     */
    public static DamageSource source(Level level, ResourceKey<DamageType> key, @Nullable Entity direct, @Nullable Entity causing) {
        return new DamageSource(level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key), direct, causing);
    }

    public static ResourceKey<DamageType> forElement(Element element) {
        return switch (element) {
            case FIRE -> FIRE;
            case FROST -> FROST;
            case SHOCK -> SHOCK;
            default -> SPELL;
        };
    }

    /** Damage from one of our spells. */
    public static boolean isOurSpell(DamageSource source) {
        return source.is(FIRE) || source.is(FROST) || source.is(SHOCK) || source.is(SPELL);
    }

    /** Anything a ward can absorb: spells, vanilla magic, dragon breath and magical fireballs (blazes, ghasts). */
    public static boolean isWardable(DamageSource source) {
        return isOurSpell(source) || source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC)
                || source.is(DamageTypes.DRAGON_BREATH) || source.is(DamageTypes.SONIC_BOOM)
                || source.is(DamageTypeTags.IS_FIRE) && source.getDirectEntity() instanceof AbstractHurtingProjectile;
    }
}
