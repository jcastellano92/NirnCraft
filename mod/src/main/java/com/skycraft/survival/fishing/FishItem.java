package com.skycraft.survival.fishing;

import com.skycraft.survival.item.FoodSpec;
import com.skycraft.survival.item.SkyFoodItem;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

/**
 * A caught fish: a raw food, an alchemy ingredient and a cooking ingredient. Each catch remembers its length
 * ({@value #TAG}); trophy catches (Legendary Angler) are named and much bigger.
 */
public class FishItem extends SkyFoodItem {
    public static final String TAG = "skycraft_fish";
    private final FishSpecies species;

    public FishItem(FishSpecies species, Properties props) {
        super(FoodSpec.plain(species.nutrition(), 0.15f), true, props);
        this.species = species;
    }

    public FishSpecies species() {
        return species;
    }

    /** A freshly caught fish of {@code cm} centimetres. */
    public static ItemStack withLength(ItemStack stack, int cm, boolean trophy) {
        CompoundTag tag = stack.getOrCreateTagElement(TAG);
        tag.putInt("cm", cm);
        if (trophy) tag.putBoolean("trophy", true);
        return stack;
    }

    @Override
    public Component getName(ItemStack stack) {
        CompoundTag tag = stack.getTagElement(TAG);
        if (tag != null && tag.getBoolean("trophy")) {
            return Component.translatable("item.skycraft.fish.trophy", super.getName(stack)).withStyle(ChatFormatting.GOLD);
        }
        return super.getName(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        CompoundTag tag = stack.getTagElement(TAG);
        if (tag != null && tag.getInt("cm") > 0) {
            int cm = tag.getInt("cm");
            tooltip.add(Component.translatable("tooltip.skycraft.fish.length", cm,
                    String.format(Locale.ROOT, "%.1f", species.weightKg(cm))).withStyle(ChatFormatting.GRAY));
        }
        MutableComponent where = Component.empty();
        boolean first = true;
        for (FishSpecies.Water w : species.waters()) {
            if (!first) where.append(", ");
            where.append(Component.translatable("fishing.skycraft.water." + w.name().toLowerCase(Locale.ROOT)));
            first = false;
        }
        if (species.time() != FishSpecies.Time.ANY) {
            where.append(" · ").append(Component.translatable("fishing.skycraft.time." + species.time().name().toLowerCase(Locale.ROOT)));
        }
        tooltip.add(where.withStyle(ChatFormatting.DARK_AQUA));
        tooltip.add(Component.translatable("fishing.skycraft.rarity." + species.rarity()).withStyle(
                species.rarity() >= 2 ? ChatFormatting.LIGHT_PURPLE : species.rarity() == 1 ? ChatFormatting.AQUA : ChatFormatting.GRAY));
        super.appendHoverText(stack, level, tooltip, flag);
    }
}
