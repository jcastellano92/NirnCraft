package com.skycraft.survival.fishing;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A Skycraft fishing rod. Casting throws a {@link SkyFishingHook}: wait for a bite, then reel in when the marker
 * crosses the catch zone (Skyrim's fishing). Better rods get bites sooner, widen the catch zone and find rarer fish.
 */
public class SkyFishingRodItem extends FishingRodItem {
    public enum Tier {
        COMMON(1.0f, 1.0f, 0), FINE(0.85f, 1.15f, 1), DWARVEN(0.7f, 1.3f, 2);

        /** Multiplier of the wait for a bite. */
        public final float wait;
        /** Multiplier of the catch zone's width. */
        public final float zone;
        /** Extra luck toward uncommon and rare fish. */
        public final int luck;

        Tier(float wait, float zone, int luck) {
            this.wait = wait;
            this.zone = zone;
            this.luck = luck;
        }
    }

    private final Tier tier;

    public SkyFishingRodItem(Tier tier, Properties props) {
        super(props.enchantable(tier == Tier.DWARVEN ? 10 : 1));
        this.tier = tier;
    }

    public Tier tier() {
        return tier;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.fishing != null) {
            if (player.fishing instanceof SkyFishingHook skyHook && skyHook.isBiting()
                    && com.skycraft.survival.SurvivalConfig.FISHING_MINIGAME.get()) {
                return InteractionResult.PASS;
            }
            if (!level.isClientSide()) {
                int damage = player.fishing.retrieve(stack);
                stack.hurtAndBreak(damage, player, hand);
            }
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.NEUTRAL,
                    1.0f, 0.4f / (level.getRandom().nextFloat() * 0.4f + 0.8f));
            player.gameEvent(GameEvent.ITEM_INTERACT_FINISH);
        } else {
            level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.FISHING_BOBBER_THROW, SoundSource.NEUTRAL,
                    0.5f, 0.4f / (level.getRandom().nextFloat() * 0.4f + 0.8f));
            if (!level.isClientSide()) {
                int lure = EnchantmentHelper.getItemEnchantmentLevel(com.skycraft.platform.EnchantmentsAccess.holder(net.minecraft.world.item.enchantment.Enchantments.LURE, level.registryAccess()), stack);
                int luck = EnchantmentHelper.getFishingLuckBonus((net.minecraft.server.level.ServerLevel) level, stack, player);
                level.addFreshEntity(new SkyFishingHook(player, level, tier, lure, luck));
            }
            player.awardStat(Stats.ITEM_USED.get(this));
            player.gameEvent(GameEvent.ITEM_INTERACT_START);
        }
        return com.skycraft.platform.Interactions.sidedSuccess(level.isClientSide()).heldItemTransformedTo(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, net.minecraft.world.item.Item.TooltipContext context263, net.minecraft.world.item.component.TooltipDisplay display263, java.util.function.Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("tooltip.skycraft.fishing.rod").withStyle(ChatFormatting.GRAY));
        if (tier != Tier.COMMON) {
            tooltip.accept(Component.translatable("tooltip.skycraft.fishing.rod_tier", Math.round((1f - tier.wait) * 100),
                    Math.round((tier.zone - 1f) * 100)).withStyle(ChatFormatting.BLUE));
        }
    }

}
