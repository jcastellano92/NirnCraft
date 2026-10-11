package com.skycraft.survival;

import com.skycraft.core.Notifier;
import com.skycraft.core.Race;
import com.skycraft.core.SkyData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Skyrim diseases (cross-module contract 24). Every disease is a mob effect {@code skycraft:<id>} of infinite
 * duration that milk does not cure. Cures: the arcane {@code skycraft:cure_disease} effect (potions), praying at a
 * shrine of the Divines, or paying a priest.
 *
 * <ul>
 *     <li>{@code ataxia}: lockpicking and pickpocketing 25% harder (marker for the crime module)</li>
 *     <li>{@code bone_break_fever}: -25 stamina</li>
 *     <li>{@code brain_rot}: -25 magicka</li>
 *     <li>{@code rattles}: stamina regenerates 50% slower</li>
 *     <li>{@code rockjoint}: melee damage -25%</li>
 *     <li>{@code witbane}: magicka regenerates 50% slower</li>
 *     <li>{@code swamp_rot}: healing received -25%</li>
 *     <li>{@code collywobbles}: attack speed -10%</li>
 *     <li>{@code greenspore}: -25 health</li>
 *     <li>{@code droops}: movement speed -10%</li>
 * </ul>
 */
public final class Diseases {
    /** Every disease, in display order. */
    public static final List<RegistryObject<MobEffect>> ALL = List.of(
            SurvivalEffects.ATAXIA, SurvivalEffects.BONE_BREAK_FEVER, SurvivalEffects.BRAIN_ROT, SurvivalEffects.RATTLES,
            SurvivalEffects.ROCKJOINT, SurvivalEffects.WITBANE, SurvivalEffects.SWAMP_ROT, SurvivalEffects.COLLYWOBBLES,
            SurvivalEffects.GREENSPORE, SurvivalEffects.DROOPS);

    /**
     * Which diseases a creature can pass on, by entity type id (namespace-wide entries end with ":*"). Skyrim's wolves
     * are vanilla wolves; cave/snow bears, snowy sabre cats and frost trolls are variants of one entity type each.
     */
    private static final Map<String, List<RegistryObject<MobEffect>>> CARRIERS = Map.ofEntries(
            Map.entry("skycraft:skeever", List.of(SurvivalEffects.ATAXIA, SurvivalEffects.RATTLES, SurvivalEffects.WITBANE)),
            Map.entry("minecraft:wolf", List.of(SurvivalEffects.ROCKJOINT)),
            Map.entry("skycraft:bear", List.of(SurvivalEffects.ROCKJOINT, SurvivalEffects.BONE_BREAK_FEVER)),
            Map.entry("minecraft:polar_bear", List.of(SurvivalEffects.ROCKJOINT, SurvivalEffects.BONE_BREAK_FEVER)),
            Map.entry("skycraft:sabre_cat", List.of(SurvivalEffects.BONE_BREAK_FEVER)),
            Map.entry("skycraft:troll", List.of(SurvivalEffects.BONE_BREAK_FEVER, SurvivalEffects.GREENSPORE)),
            Map.entry("skycraft:draugr", List.of(SurvivalEffects.BRAIN_ROT)),
            Map.entry("skycraft:draugr_deathlord", List.of(SurvivalEffects.BRAIN_ROT)),
            Map.entry("skycraft:mudcrab", List.of(SurvivalEffects.SWAMP_ROT)),
            Map.entry("minecraft:drowned", List.of(SurvivalEffects.SWAMP_ROT)),
            Map.entry("minecraft:zombie", List.of(SurvivalEffects.COLLYWOBBLES)),
            Map.entry("minecraft:husk", List.of(SurvivalEffects.COLLYWOBBLES)),
            Map.entry("skycraft:spriggan", List.of(SurvivalEffects.GREENSPORE)),
            Map.entry("vampirism:*", List.of(SurvivalEffects.DROOPS, SurvivalEffects.BRAIN_ROT)));

    private Diseases() {}

    public static boolean isDisease(MobEffect effect) {
        for (RegistryObject<MobEffect> d : ALL) {
            if (d.isPresent() && d.get() == effect) return true;
        }
        return false;
    }

    /** The diseases the entity currently suffers from. */
    public static List<MobEffect> active(LivingEntity entity) {
        List<MobEffect> out = new ArrayList<>();
        for (RegistryObject<MobEffect> d : ALL) {
            if (entity.hasEffect(d.get())) out.add(d.get());
        }
        return out;
    }

    public static boolean hasAny(LivingEntity entity) {
        for (RegistryObject<MobEffect> d : ALL) {
            if (entity.hasEffect(d.get())) return true;
        }
        return false;
    }

    /**
     * Removes every disease and returns how many were cured. Must not be called while the entity is ticking its
     * effects (use {@link SurvivalEvents#queueCure} from effect callbacks).
     */
    public static int cureAll(LivingEntity entity) {
        int cured = 0;
        for (RegistryObject<MobEffect> d : ALL) {
            if (entity.removeEffect(d.get())) cured++;
        }
        if (cured > 0 && entity instanceof Player player) {
            SurvivalEvents.reconcileBonuses(player);
            if (player instanceof ServerPlayer sp) Notifier.message(sp, Component.translatable("message.skycraft.survival.cured"));
        }
        return cured;
    }

    /** An infinite, particle-less disease instance. */
    public static MobEffectInstance instance(MobEffect disease) {
        return new MobEffectInstance(disease, MobEffectInstance.INFINITE_DURATION, 0, false, false, true);
    }

    /** Gives the entity a disease. Returns false if it already had it. */
    public static boolean infect(LivingEntity entity, MobEffect disease) {
        if (entity.hasEffect(disease)) return false;
        entity.addEffect(instance(disease));
        if (entity instanceof Player player) {
            SurvivalEvents.reconcileBonuses(player);
            if (player instanceof ServerPlayer sp) {
                Notifier.message(sp, Component.translatable("message.skycraft.survival.contracted", disease.getDisplayName()));
            }
        }
        return true;
    }

    /** The diseases an attacker can carry (empty if none). */
    public static List<RegistryObject<MobEffect>> carriedBy(Entity attacker) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(attacker.getType());
        if (id == null) return List.of();
        List<RegistryObject<MobEffect>> list = CARRIERS.get(id.toString());
        if (list == null) list = CARRIERS.get(id.getNamespace() + ":*");
        return list == null ? List.of() : list;
    }

    /** Fraction of disease chance a race resists (Argonians and Bosmer: 50%, like Skyrim). */
    public static float resistance(Player player) {
        Race race = SkyData.get(player).getRace();
        return race == null ? 0f : race.diseaseResistance();
    }
}
