package com.skycraft.crafting.arcane.enchant;

import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.crafting.arcane.ArcaneModule;
import com.skycraft.crafting.arcane.ArcaneRegistry;
import com.skycraft.crafting.arcane.effect.ArcaneEffects;
import com.skycraft.crafting.arcane.item.SoulGemItem;
import com.skycraft.perk.Perks;
import com.skycraft.skills.Progression;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import com.skycraft.platform.EnchantmentsAccess;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Skyrim enchanting at the Arcane Enchanter: disenchanting destroys an item and teaches its enchantments; enchanting
 * binds a known enchantment into an unenchanted item, powered by a filled soul gem. Known enchantments are stored in
 * {@code data.module("arcane").getCompoundOrEmpty("known_enchants")} (enchantment id -> 1). Items enchanted here carry the
 * int NBT {@code skycraft_enchants} (number of enchantments added), which the Extra Effect perk uses.
 *
 * <p>Calculation methods are shared by the client screen (preview) and the server (authoritative).</p>
 */
public final class Enchanting {
    public static final String KNOWN = "known_enchants";
    public static final String MARK = "skycraft_enchants";

    private Enchanting() {}

    // ------------------------------------------------------------------ knowledge

    @Nullable
    public static String id(Enchantment ench) {
        Identifier key = EnchantmentsAccess.id(ench);
        return key == null ? null : key.toString();
    }

    public static boolean knows(Player player, Enchantment ench) {
        String id = id(ench);
        return id != null && SkyData.get(player).module(ArcaneModule.DATA).getCompoundOrEmpty(KNOWN).contains(id);
    }

    /** All known enchantments, in registry order. */
    public static List<Enchantment> known(Player player) {
        CompoundTag tag = SkyData.get(player).module(ArcaneModule.DATA).getCompoundOrEmpty(KNOWN);
        List<Enchantment> out = new ArrayList<>();
        for (Map.Entry<Identifier, Enchantment> e : sortedEnchantments(player)) {
            if (tag.contains(e.getKey().toString())) out.add(e.getValue());
        }
        return out;
    }

    /** Cache only the current registry snapshot; reload and level-unload events discard it. */
    private static synchronized List<Map.Entry<Identifier, Enchantment>> sortedEnchantments(Player player) {
        var registry = player.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        if (registry == sortedRegistry && sortedEnchantments != null) return sortedEnchantments;
        List<Map.Entry<Identifier, Enchantment>> list = new ArrayList<>();
        registry.listElements().forEach(entry -> list.add(Map.entry(entry.key().identifier(), entry.value())));
        list.sort(Map.Entry.comparingByKey(java.util.Comparator.comparing(Identifier::toString)));
        sortedRegistry = registry;
        return sortedEnchantments = List.copyOf(list);
    }

    private static HolderLookup.RegistryLookup<Enchantment> sortedRegistry;
    private static List<Map.Entry<Identifier, Enchantment>> sortedEnchantments;

    public static synchronized void clearRegistryCache() {
        sortedRegistry = null;
        sortedEnchantments = null;
    }

    private static void learn(ServerPlayer player, Enchantment ench) {
        String id = id(ench);
        if (id == null) return;
        PlayerData data = SkyData.get(player);
        CompoundTag module = data.module(ArcaneModule.DATA);
        CompoundTag known = module.getCompoundOrEmpty(KNOWN);
        known.putInt(id, 1);
        module.put(KNOWN, known);
        data.markDirty();
    }

    // ------------------------------------------------------------------ disenchanting

    /** Non-curse enchantments on the item that the player doesn't know yet. */
    public static List<Enchantment> unknownEnchantments(Player player, ItemStack stack) {
        List<Enchantment> out = new ArrayList<>();
        if (stack.isEmpty()) return out;
        for (Holder<Enchantment> holder : EnchantmentHelper.getEnchantmentsForCrafting(stack).keySet()) {
            Enchantment ench = holder.value();
            if (!holder.is(EnchantmentTags.CURSE) && !knows(player, ench)) out.add(ench);
        }
        return out;
    }

    /** Skyrim only lets you disenchant items that carry an enchantment you don't know yet. */
    public static boolean canDisenchant(Player player, ItemStack stack) {
        return !unknownEnchantments(player, stack).isEmpty();
    }

    public static void disenchant(ServerPlayer player, BlockPos pos, int slot) {
        if (!ArcaneModule.atStation(player, pos, ArcaneRegistry.ARCANE_ENCHANTER.get())) return;
        Inventory inv = player.getInventory();
        slot = com.skycraft.inventory.Pack.resolveSlot(player, slot, null);
        if (slot < 0 || slot >= inv.getContainerSize()) return;
        ItemStack stack = inv.getItem(slot);
        List<Enchantment> learnable = unknownEnchantments(player, stack);
        if (learnable.isEmpty()) {
            Notifier.message(player, Component.translatable("message.skycraft.arcane.nothing_to_learn"));
            return;
        }
        ItemEnchantments all = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        float use = 0.2f;
        for (Enchantment ench : learnable) {
            learn(player, ench);
            int weight = ench.getWeight();
            use += 0.1f * all.getLevel(EnchantmentsAccess.holder(ench, player.registryAccess())) * (1f + (10 - weight) / 10f);
            Notifier.message(player, Component.translatable("message.skycraft.arcane.learned_enchantment", ench.description()));
        }
        Component name = stack.getHoverName();
        stack.shrink(1);
        inv.setChanged();
        player.level().playSound(null, pos, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 1f, 0.6f);
        Progression.addSkillXp(player, Skill.ENCHANTING, Math.min(1.5f, use));
        SkyData.get(player).addStat("items_disenchanted", 1);
        Notifier.message(player, Component.translatable("message.skycraft.arcane.disenchanted", name));
    }

    // ------------------------------------------------------------------ enchanting

    /** Whether the item can receive a new enchantment from this player (unenchanted, or Extra Effect). */
    public static boolean canEnchantItem(Player player, ItemStack stack) {
        if (stack.isEmpty() || stack.is(Items.BOOK) || stack.is(Items.ENCHANTED_BOOK)) return false;
        if (com.skycraft.economy.ItemCategory.isJewelry(stack)) {
            if (!stack.isEnchanted()) return true;
            CompoundTag tag = com.skycraft.inventory.ItemData.custom(stack);
            return tag != null && tag.getIntOr(MARK, 0) == 1 && EnchantmentHelper.getEnchantmentsForCrafting(stack).size() == 1
                    && Perks.has(player, "enchanting.extra_effect");
        }
        if (stack.isEnchantable()) return true;
        if (!stack.isDamageableItem() && !stack.has(DataComponents.ENCHANTABLE)) return false;
        CompoundTag tag = com.skycraft.inventory.ItemData.custom(stack);
        return tag != null && tag.getIntOr(MARK, 0) == 1 && EnchantmentHelper.getEnchantmentsForCrafting(stack).size() == 1
                && Perks.has(player, "enchanting.extra_effect");
    }

    /** Whether the enchantment can be bound into the item (known, applicable and compatible). */
    public static boolean canApply(Player player, ItemStack stack, Enchantment ench) {
        Holder<Enchantment> selected = EnchantmentsAccess.holder(ench, player.registryAccess());
        if (selected.is(EnchantmentTags.CURSE) || !knows(player, ench)) return false;
        if (!fits(stack, ench)) return false;
        Set<Holder<Enchantment>> existing = EnchantmentHelper.getEnchantmentsForCrafting(stack).keySet();
        for (Holder<Enchantment> other : existing) {
            if (other.equals(selected) || !Enchantment.areCompatible(other, selected)) return false;
        }
        return true;
    }

    /**
     * Whether the enchantment belongs on this kind of item at the Arcane Enchanter (ignoring knowledge and what is
     * already on it). Weapons, bows, crossbows and armour use the enchantment's own category; like Skyrim, shields
     * also take armour enchantments (Protection and its kin) and rings, necklaces and circlets take armour and
     * wearable enchantments.
     */
    public static boolean fits(ItemStack stack, Enchantment ench) {
        if (ench.canEnchant(stack) || isJewelryEnchant(stack, ench)) return true;
        if (stack.getItem() instanceof net.minecraft.world.item.ShieldItem) {
            return targetsArmor(ench, EquipmentSlot.CHEST);
        }
        return false;
    }

    private static boolean isJewelryEnchant(ItemStack stack, Enchantment ench) {
        if (!com.skycraft.economy.ItemCategory.isJewelry(stack)) return false;
        return targetsArmor(ench, EquipmentSlot.HEAD) || targetsArmor(ench, EquipmentSlot.CHEST)
                || targetsArmor(ench, EquipmentSlot.LEGS) || targetsArmor(ench, EquipmentSlot.FEET);
    }

    private static boolean targetsArmor(Enchantment ench, EquipmentSlot slot) {
        return ench.definition().slots().stream().anyMatch(group -> group.test(slot));
    }

    public static List<Enchantment> applicable(Player player, ItemStack stack) {
        List<Enchantment> out = new ArrayList<>();
        if (!canEnchantItem(player, stack)) return out;
        for (Enchantment ench : known(player)) {
            if (canApply(player, stack, ench)) out.add(ench);
        }
        return out;
    }

    /**
     * Resulting enchantment level: {@code 1 + floor(skill/100 * maxLevel * soul/5 * (1 + 0.2 * Enchanter rank) * perk bonus)},
     * clamped to the enchantment's max level. Elemental/insightful/corpus perks add 25% to their enchantments and
     * Fortify Enchanting adds 10% per level.
     */
    public static int resultLevel(Player player, Enchantment ench, int soul) {
        int max = ench.getMaxLevel();
        if (max <= 1) return 1;
        double skill = SkyData.get(player).getSkill(Skill.ENCHANTING);
        double mult = (1.0 + 0.2 * Perks.rank(player, "enchanting.enchanter")) * perkBonus(player, ench);
        mult *= 1.0 + 0.1 * ArcaneEffects.level(player, ArcaneEffects.FORTIFY_ENCHANTING);
        double raw = skill / 100.0 * max * (SoulGemItem.strength(soul) / 5.0) * mult;
        return Mth.clamp(1 + (int) Math.floor(raw), 1, max);
    }

    public static double perkBonus(Player player, Enchantment ench) {
        String path = path(ench);
        double bonus = 1.0;
        if (isFire(path) && Perks.has(player, "enchanting.fire_enchanter")) bonus *= 1.25;
        if (isFrost(path) && Perks.has(player, "enchanting.frost_enchanter")) bonus *= 1.25;
        if (isStorm(path) && Perks.has(player, "enchanting.storm_enchanter")) bonus *= 1.25;
        if (isInsightful(path) && Perks.has(player, "enchanting.insightful_enchanter")) bonus *= 1.25;
        if (isCorpus(path) && Perks.has(player, "enchanting.corpus_enchanter")) bonus *= 1.25;
        return bonus;
    }

    private static String path(Enchantment ench) {
        String id = id(ench);
        if (id == null) return "";
        return id.substring(id.indexOf(':') + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean isFire(String p) {
        return p.equals("fire_aspect") || p.equals("flame") || p.contains("fire") || p.contains("flame") || p.contains("burn");
    }

    private static boolean isFrost(String p) {
        return p.equals("frost_walker") || p.contains("frost") || p.contains("ice") || p.contains("freez") || p.contains("cold");
    }

    private static boolean isStorm(String p) {
        return p.equals("channeling") || p.equals("impaling") || p.equals("riptide") || p.contains("shock")
                || p.contains("lightning") || p.contains("thunder") || p.contains("storm") || p.contains("spark");
    }

    private static boolean isInsightful(String p) {
        return switch (p) {
            case "looting", "fortune", "efficiency", "silk_touch", "luck_of_the_sea", "lure", "aqua_affinity",
                    "respiration", "depth_strider", "swift_sneak", "soul_speed" -> true;
            default -> false;
        };
    }

    private static boolean isCorpus(String p) {
        return switch (p) {
            case "protection", "projectile_protection", "blast_protection", "fire_protection", "feather_falling" -> true;
            default -> false;
        };
    }

    /** Use value for the enchanting skill: scales with the soul's size. */
    private static float enchantXp(int soul, int level) {
        return 0.15f * SoulGemItem.strength(soul) * (1f + 0.1f * level);
    }

    public static void enchant(ServerPlayer player, BlockPos pos, int itemSlot, String enchantId, int gemSlot) {
        if (!ArcaneModule.atStation(player, pos, ArcaneRegistry.ARCANE_ENCHANTER.get())) return;
        Inventory inv = player.getInventory();
        int[] resolved = com.skycraft.inventory.Pack.resolveAll(player, new int[]{itemSlot, gemSlot});
        if (resolved == null) return;
        itemSlot = resolved[0];
        gemSlot = resolved[1];
        if (itemSlot < 0 || itemSlot >= inv.getContainerSize() || gemSlot < 0 || gemSlot >= inv.getContainerSize() || itemSlot == gemSlot) return;
        Identifier rl = Identifier.tryParse(enchantId);
        Enchantment ench = rl == null ? null : EnchantmentsAccess.lookup(rl, player.registryAccess());
        ItemStack stack = inv.getItem(itemSlot);
        ItemStack gem = inv.getItem(gemSlot);
        if (ench == null || !(gem.getItem() instanceof SoulGemItem)) return;
        int soul = SoulGemItem.getSoul(gem);
        if (soul <= 0) {
            Notifier.message(player, Component.translatable("message.skycraft.arcane.need_soul"));
            return;
        }
        if (!canEnchantItem(player, stack) || !canApply(player, stack, ench)) {
            Notifier.message(player, Component.translatable("message.skycraft.arcane.cannot_enchant"));
            return;
        }
        int level = resultLevel(player, ench, soul);

        ItemStack target = stack;
        boolean split = stack.getCount() > 1;
        if (split) {
            target = stack.split(1);
            com.skycraft.loot.affix.ItemAffixes.copyAffixes(stack, target);
        }
        Holder<Enchantment> holder = EnchantmentsAccess.holder(ench, player.registryAccess());
        EnchantmentHelper.updateEnchantments(target, mutable -> mutable.set(holder, level));
        CompoundTag tag = com.skycraft.inventory.ItemData.customOrEmpty(target);
        tag.putInt(MARK, tag.getIntOr(MARK, 0) + 1);
        com.skycraft.inventory.ItemData.setCustom(target, tag);
        com.skycraft.loot.affix.ItemAffixes.onEnchant(target, ench, level, soul * 10);
        if (split) com.skycraft.inventory.Pack.giveOrDrop(player, target);

        SoulGemItem.consume(gem);
        inv.setChanged();

        player.level().playSound(null, pos, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 1f, 1.1f);
        player.level().playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.8f, 1f);
        Progression.addSkillXp(player, Skill.ENCHANTING, enchantXp(soul, level));
        SkyData.get(player).addStat("items_enchanted", 1);
        Notifier.message(player, Component.translatable("message.skycraft.arcane.enchanted", target.getHoverName(), Enchantment.getFullname(holder, level)));
    }
}
