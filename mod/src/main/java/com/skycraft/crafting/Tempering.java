package com.skycraft.crafting;

import com.skycraft.combat.WeaponClass;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.crafting.item.SkyAxeItem;
import com.skycraft.crafting.item.SkyBowItem;
import com.skycraft.crafting.item.SkyMeleeItem;
import com.skycraft.crafting.item.SkyShieldItem;
import com.skycraft.crafting.recipe.Cost;
import com.skycraft.perk.Perk;
import com.skycraft.perk.Perks;
import com.skycraft.skills.Progression;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

/**
 * Skyrim tempering at the grindstone (weapons) and armor workbench (armor). Quality is stored as ItemStack NBT int
 * {@value #TAG} (1 Fine, 2 Superior, 3 Exquisite, 4 Flawless, 5 Epic, 6 Legendary); each level adds +0.5 attack damage
 * (weapons, main hand) or +0.5 armor (armor in its slot, shields in the off hand) via {@link CraftingEvents}. Shields
 * are improved at the armor workbench like armor.
 */
public final class Tempering {
    public static final String TAG = "skycraft_quality";
    public static final int MAX = 6;
    public static final String ARCANE_BLACKSMITH = "smithing.arcane_blacksmith";

    public enum Kind { NONE, WEAPON, ARMOR }

    /** Outcome of a tempering check; {@code reason} is a translation key when {@code ok} is false. */
    public record Check(boolean ok, @Nullable Component reason, int gain, int maxQuality, @Nullable Cost cost) {}

    private Tempering() {}

    // ------------------------------------------------------------------ quality NBT

    public static int quality(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? 0 : Math.max(0, Math.min(MAX, tag.getInt(TAG)));
    }

    public static void setQuality(ItemStack stack, int quality) {
        if (quality <= 0) {
            CompoundTag tag = stack.getTag();
            if (tag != null) tag.remove(TAG);
        } else {
            stack.getOrCreateTag().putInt(TAG, Math.min(MAX, quality));
        }
    }

    public static Component qualityName(int quality) {
        return Component.translatable("quality.skycraft." + Math.max(0, Math.min(MAX, quality)));
    }

    // ------------------------------------------------------------------ classification

    public static Kind kind(ItemStack stack) {
        if (stack.isEmpty() || stack.getCount() != 1) return Kind.NONE;
        Item item = stack.getItem();
        if (item instanceof ArmorItem || item instanceof ShieldItem) return Kind.ARMOR;
        if (!stack.isDamageableItem() && !(item instanceof SkyBowItem)) return Kind.NONE;
        WeaponClass wc = WeaponClass.of(stack);
        if (wc != WeaponClass.UNARMED && wc != WeaponClass.OTHER) return Kind.WEAPON;
        return Kind.NONE;
    }

    public static boolean accepts(StationType station, ItemStack stack) {
        Kind kind = kind(stack);
        return station == StationType.GRINDSTONE && kind == Kind.WEAPON
                || station == StationType.ARMOR_WORKBENCH && kind == Kind.ARMOR;
    }

    /** The material consumed per improvement (the item's tier material; vanilla gear uses its repair item). */
    @Nullable
    public static Cost material(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof SkyBowItem bow) return Cost.of(bow.smithingTier::material, 1);
        if (item instanceof SkyMeleeItem melee) return Cost.of(melee.smithingTier::material, 1);
        if (item instanceof SkyAxeItem axe) return Cost.of(axe.smithingTier::material, 1);
        if (item instanceof SkyShieldItem shield) return Cost.of(shield.set::material, 1);
        if (item instanceof ShieldItem) return Cost.of(() -> Items.IRON_INGOT, 1);
        if (item instanceof ArmorItem armor) {
            ArmorMaterial mat = armor.getMaterial();
            if (mat instanceof SkyArmorMaterial sky) return Cost.of(sky::material, 1);
            if (mat == ArmorMaterials.NETHERITE) return Cost.of(() -> Items.NETHERITE_SCRAP, 1);
            if (mat == ArmorMaterials.CHAIN) return Cost.of(() -> Items.IRON_INGOT, 1);
            return ingredientCost(mat.getRepairIngredient());
        }
        if (item instanceof TieredItem tiered) {
            Tier tier = tiered.getTier();
            if (tier instanceof SmithingTier st) return Cost.of(st::material, 1);
            if (tier == Tiers.NETHERITE) return Cost.of(() -> Items.NETHERITE_SCRAP, 1);
            return ingredientCost(tier.getRepairIngredient());
        }
        if (item instanceof BowItem || item instanceof CrossbowItem) return Cost.of(CraftingItems.FIREWOOD, 1);
        if (item instanceof TridentItem) return Cost.of(() -> Items.PRISMARINE_SHARD, 1);
        return null;
    }

    @Nullable
    private static Cost ingredientCost(Ingredient ingredient) {
        return ingredient == null || ingredient.isEmpty() ? null : Cost.ingredient(ingredient, 1);
    }

    /** Perk that doubles improvement and allows Legendary quality (Skyrim's material smithing perks). */
    public static String temperPerk(ItemStack stack) {
        Item item = stack.getItem();
        if (item instanceof SkyBowItem bow) return bow.smithingTier.temperPerk;
        if (item instanceof SkyMeleeItem melee) return melee.smithingTier.temperPerk;
        if (item instanceof SkyAxeItem axe) return axe.smithingTier.temperPerk;
        if (item instanceof SkyShieldItem shield) return shield.set.temperPerk;
        if (item instanceof ArmorItem armor) {
            ArmorMaterial mat = armor.getMaterial();
            if (mat instanceof SkyArmorMaterial sky) return sky.temperPerk;
            if (mat == ArmorMaterials.DIAMOND) return "smithing.advanced_armors";
            if (mat == ArmorMaterials.NETHERITE) return "smithing.daedric_smithing";
            return "smithing.steel_smithing";
        }
        if (item instanceof TieredItem tiered) {
            Tier tier = tiered.getTier();
            if (tier == Tiers.DIAMOND) return "smithing.advanced_armors";
            if (tier == Tiers.NETHERITE) return "smithing.daedric_smithing";
        }
        return "smithing.steel_smithing";
    }

    /** Highest quality this player can reach on this item (Skyrim: smithing skill caps the improvement). */
    public static int maxQuality(Player player, ItemStack stack) {
        PlayerData data = SkyData.get(player);
        int skill = data.getSkill(Skill.SMITHING);
        int max = skill >= 90 ? 5 : skill >= 70 ? 4 : skill >= 50 ? 3 : skill >= 30 ? 2 : skill >= 15 ? 1 : 0;
        if (skill >= 100 && data.hasPerk(temperPerk(stack))) max = 6;
        return max;
    }

    /** Validates an improvement (used by the screen for display and by the server before applying). */
    public static Check check(Player player, StationType station, ItemStack stack, int slot) {
        if (!accepts(station, stack)) return new Check(false, Component.translatable("message.skycraft.smithing.cannot_improve"), 0, 0, null);
        PlayerData data = SkyData.get(player);
        int max = maxQuality(player, stack);
        int current = quality(stack);
        Cost cost = material(stack);
        if (cost == null) return new Check(false, Component.translatable("message.skycraft.smithing.cannot_improve"), 0, max, null);
        if (stack.isEnchanted() && !data.hasPerk(ARCANE_BLACKSMITH)) {
            return new Check(false, Component.translatable("message.skycraft.smithing.requires_perk", perkName(ARCANE_BLACKSMITH)), 0, max, cost);
        }
        if (current >= max) return new Check(false, Component.translatable("message.skycraft.smithing.max_quality"), 0, max, cost);
        if (cost.countIn(player.getInventory(), slot) < cost.count()) {
            return new Check(false, Component.translatable("message.skycraft.smithing.need_material", cost.display().get().getHoverName()), 0, max, cost);
        }
        int gain = data.hasPerk(temperPerk(stack)) ? 2 : 1;
        gain = Math.min(gain, max - current);
        return new Check(true, null, gain, max, cost);
    }

    public static Component perkName(String perkId) {
        Perk perk = Perks.get(perkId);
        return perk != null ? perk.displayName() : Component.literal(perkId);
    }

    /** Server: improves the item in inventory slot {@code slot} once. */
    public static void improve(ServerPlayer player, StationType station, int slot) {
        Inventory inv = player.getInventory();
        slot = com.skycraft.inventory.Pack.resolveSlot(player, slot, null); // an overflow-pack item comes into a slot first
        if (slot < 0 || slot >= inv.getContainerSize()) return;
        ItemStack stack = inv.getItem(slot);
        Check check = check(player, station, stack, slot);
        if (!check.ok()) {
            if (check.reason() != null) Notifier.message(player, check.reason());
            return;
        }
        check.cost().removeFrom(inv, check.cost().count(), slot);
        int quality = quality(stack) + check.gain();
        setQuality(stack, quality);
        inv.setChanged();
        player.level().playSound(null, player.blockPosition(),
                station == StationType.GRINDSTONE ? SoundEvents.GRINDSTONE_USE : SoundEvents.SMITHING_TABLE_USE, SoundSource.BLOCKS, 1f, 1f);
        float use = Math.max(8f, CraftingValues.of(stack) * 0.2f) * check.gain();
        Progression.addSkillXp(player, Skill.SMITHING, use);
        SkyData.get(player).addStat("items_improved", 1);
        Notifier.message(player, Component.translatable("message.skycraft.smithing.improved", stack.getHoverName(), qualityName(quality)));
    }
}
