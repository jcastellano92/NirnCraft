package com.nirncraft.survival.item;

import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import com.nirncraft.survival.SurvivalEffects;
import com.nirncraft.survival.SurvivalEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** A Skyrim food or drink (see {@link FoodSpec}). */
public class SkyFoodItem extends Item {
    private final FoodSpec spec;

    public SkyFoodItem(FoodSpec spec, boolean meat, Properties props) {
        super(props.food(spec.properties(meat)).component(net.minecraft.core.component.DataComponents.CONSUMABLE,
                net.minecraft.world.item.component.Consumable.builder().consumeSeconds(spec.fast() ? .8f : 1.6f)
                        .animation(spec.drink() ? ItemUseAnimation.DRINK : ItemUseAnimation.EAT)
                        .sound(spec.drink() ? net.minecraft.sounds.SoundEvents.GENERIC_DRINK : net.minecraft.sounds.SoundEvents.GENERIC_EAT)
                        .hasConsumeParticles(!spec.drink()).build()));
        this.spec = spec;
    }

    public FoodSpec spec() {
        return spec;
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return spec.drink() ? ItemUseAnimation.DRINK : ItemUseAnimation.EAT;
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        ItemStack result = super.finishUsingItem(stack, level, entity);
        if (!level.isClientSide()) applyExtras(entity, spec);
        return result;
    }

    /** Applies the over-time effects, drunkenness and skooma of a food. Server side. */
    public static void applyExtras(LivingEntity entity, FoodSpec spec) {
        if (spec.healTicks() > 0) entity.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.REGENERATION), spec.healTicks(), 0));
        if (spec.staminaTicks() > 0) {
            entity.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(SurvivalEffects.REGENERATE_STAMINA_FOOD.get()), spec.staminaTicks(), 0));
        }
        if (spec.fortifyTicks() > 0) {
            entity.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(SurvivalEffects.FORTIFY_STAMINA_REGEN.get()), spec.fortifyTicks(),
                    Math.max(0, spec.fortifyLevel() - 1)));
        }
        if (spec.instantStamina() > 0 && entity instanceof Player player) {
            PlayerData data = SkyData.get(player);
            data.setStamina(data.getStamina() + spec.instantStamina());
        }
        if (spec.alcohol() > 0) {
            // drinking more makes the room spin longer
            MobEffectInstance current = entity.getEffect(com.nirncraft.platform.Effects.holder(MobEffects.NAUSEA));
            int ticks = Math.min(20 * 60, spec.alcohol() + (current == null ? 0 : current.getDuration() / 2));
            entity.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.NAUSEA), ticks, 0, false, false, true));
        }
        if (spec.skooma() && entity instanceof Player player) {
            entity.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.SPEED), 60 * 20, 1));
            entity.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.HASTE), 60 * 20, 0));
            SurvivalEvents.scheduleSkoomaCrash(player, 60 * 20);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        if (spec.healTicks() > 0) {
            tooltip.accept(Component.translatable("tooltip.nirncraft.survival.heal_over_time", spec.healTicks() / 20).withStyle(ChatFormatting.GREEN));
        }
        if (spec.staminaTicks() > 0) {
            tooltip.accept(Component.translatable("tooltip.nirncraft.survival.stamina_over_time", spec.staminaTicks() / 20).withStyle(ChatFormatting.GREEN));
        }
        if (spec.instantStamina() > 0) {
            tooltip.accept(Component.translatable("tooltip.nirncraft.survival.restore_stamina", (int) spec.instantStamina()).withStyle(ChatFormatting.GREEN));
        }
        if (spec.fortifyTicks() > 0) {
            tooltip.accept(Component.translatable("tooltip.nirncraft.survival.fortify_stamina_regen", 25 * Math.max(1, spec.fortifyLevel()),
                    spec.fortifyTicks() / 1200).withStyle(ChatFormatting.BLUE));
        }
        if (spec.alcohol() > 0 && !spec.skooma()) {
            tooltip.accept(Component.translatable("tooltip.nirncraft.survival.alcohol").withStyle(ChatFormatting.GRAY));
        }
        if (spec.skooma()) {
            tooltip.accept(Component.translatable("tooltip.nirncraft.survival.skooma").withStyle(ChatFormatting.LIGHT_PURPLE));
            tooltip.accept(Component.translatable("tooltip.nirncraft.survival.contraband").withStyle(ChatFormatting.RED));
        }
    }
}
