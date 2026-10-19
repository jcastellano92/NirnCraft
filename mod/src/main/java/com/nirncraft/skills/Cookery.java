package com.nirncraft.skills;

import com.nirncraft.NirnCraft;
import com.nirncraft.core.PlayerData;
import com.nirncraft.core.SkyData;
import com.nirncraft.core.Skill;
import com.nirncraft.perk.Perks;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.List;

/**
 * Cooking: dishes made at a cooking pot carry their cook's skill ({@value #TAG} NBT) and give better effects when
 * eaten: a short Regeneration that grows with the skill, more healing (Hearty Meals), stamina (Camp Cook), cures
 * (Preserver), Health Boost (Master Chef) and Absorption (Gourmet). Seasoned sometimes makes an extra portion.
 *
 * <p>XP: each dish cooked, by how many ingredients went in. The survival module's cooking pot calls
 * {@link #season} and {@link #onCooked}.</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class Cookery {
    public static final String TAG = "nirncraft_meal";
    private static final int HEARTY = 1, CAMP = 2, PRESERVER = 4, MASTER = 8, GOURMET = 16;

    private Cookery() {}

    /** Marks a freshly cooked dish with the cook's skill and perks. Returns the stack to hand out. */
    public static ItemStack season(ServerPlayer cook, ItemStack dish) {
        if (dish.isEmpty() || !dish.has(net.minecraft.core.component.DataComponents.FOOD)) return dish;
        PlayerData data = SkyData.get(cook);
        int flags = 0;
        if (Perks.has(data, "cooking.hearty_meals")) flags |= HEARTY;
        if (Perks.has(data, "cooking.camp_cook")) flags |= CAMP;
        if (Perks.has(data, "cooking.preserver")) flags |= PRESERVER;
        if (Perks.has(data, "cooking.master_chef")) flags |= MASTER;
        if (Perks.has(data, "cooking.gourmet")) flags |= GOURMET;
        CompoundTag meal = new CompoundTag();
        meal.putInt("skill", data.getSkill(Skill.COOKING));
        meal.putInt("cook", Perks.rank(data, "cooking.cook"));
        meal.putInt("perks", flags);
        com.nirncraft.inventory.ItemData.updateCustom(dish, customData263 -> customData263.put(TAG, meal));
        return dish;
    }

    /** Extra portions from Seasoned (15% per dish). */
    public static int bonusPortions(ServerPlayer cook, int made) {
        if (!Perks.has(cook, "cooking.seasoned")) return 0;
        int extra = 0;
        for (int i = 0; i < made; i++) {
            if (cook.getRandom().nextFloat() < 0.15f) extra++;
        }
        return extra;
    }

    /** Trains Cooking: {@code ingredients} items went into each of {@code made} dishes. */
    public static void onCooked(ServerPlayer cook, int ingredients, int made) {
        if (made <= 0) return;
        Progression.addSkillXp(cook, Skill.COOKING, (2f + ingredients) * made);
    }

    @SubscribeEvent
    public static void onEat(LivingEntityUseItemEvent.Finish event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        ItemStack stack = event.getItem();
        CompoundTag tag = com.nirncraft.inventory.ItemData.custom(stack);
        if (tag == null || !tag.contains(TAG)) return;
        CompoundTag meal = tag.getCompoundOrEmpty(TAG);
        int skill = meal.getIntOr("skill",0);
        int cookRank = meal.getIntOr("cook",0);
        int flags = meal.getIntOr("perks",0);
        float quality = 1f + 0.1f * cookRank;
        int regenTicks = Math.round((40 + skill * 2) * quality);
        p.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.REGENERATION), regenTicks, 0, false, true, true));
        if ((flags & HEARTY) != 0) p.heal(3f * quality);
        if ((flags & CAMP) != 0) {
            PlayerData data = SkyData.get(p);
            data.setStamina(Math.min(data.maxStamina(), data.getStamina() + 25f * quality));
        }
        if ((flags & PRESERVER) != 0) {
            p.removeEffect(com.nirncraft.platform.Effects.holder(MobEffects.HUNGER));
            p.removeEffect(com.nirncraft.platform.Effects.holder(MobEffects.POISON));
            p.removeEffect(com.nirncraft.platform.Effects.holder(MobEffects.NAUSEA));
        }
        if ((flags & MASTER) != 0) p.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.HEALTH_BOOST), Math.round(20 * 180 * quality), 0, false, true, true));
        if ((flags & GOURMET) != 0) p.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.ABSORPTION), Math.round(20 * 120 * quality), 0, false, true, true));
    }

    /** Tooltip line for a cooked dish (client tooltip event). */
    public static void tooltip(ItemStack stack, List<Component> lines) {
        CompoundTag tag = com.nirncraft.inventory.ItemData.custom(stack);
        if (tag == null || !tag.contains(TAG)) return;
        CompoundTag meal = tag.getCompoundOrEmpty(TAG);
        lines.add(Component.translatable("tooltip.nirncraft.meal", meal.getIntOr("skill",0)).withStyle(ChatFormatting.GOLD));
        int flags = meal.getIntOr("perks",0);
        if ((flags & (HEARTY | CAMP | PRESERVER | MASTER | GOURMET)) != 0) {
            lines.add(Component.translatable("tooltip.nirncraft.meal_perks").withStyle(ChatFormatting.YELLOW));
        }
    }

    /** Whether a stack is a cook's dish. */
    public static boolean isMeal(ItemStack stack) {
        return com.nirncraft.inventory.ItemData.hasCustom(stack) && com.nirncraft.inventory.ItemData.custom(stack).contains(TAG);
    }
}
