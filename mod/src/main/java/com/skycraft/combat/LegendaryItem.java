package com.skycraft.combat;

import com.skycraft.arsenal.LeveledGear;
import com.skycraft.creatures.Leveling;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Fallout 4 style legendary weapons and armor: named prefixes and unique combat effects.
 * Contract 4 (docs/PLAYTEST-1.md).
 */
public final class LegendaryItem {
    private LegendaryItem() {}

    public enum Prefix {
        // Weapon prefixes
        WOUNDING("Wounding", "Targets take bleeding damage on hit.", true, false),
        INSTIGATING("Instigating", "Deals double damage if the target is at full health.", true, false),
        VAMPIRIC("Vampiric", "Restores health on hit.", true, false),
        INCENDIARY("Incendiary", "Sets targets ablaze for extra fire damage.", true, false),
        FREEZING("Freezing", "Slows targets and deals bonus frost damage.", true, false),
        SHOCKING("Shocking", "Deals bonus shock damage and drains magicka.", true, false),
        STAGGERING("Staggering", "Chance to stagger targets on hit.", true, false),
        DEADEYE("Deadeye", "High critical hit chance and critical damage.", true, false),
        BERSERKER("Berserker", "Deals more damage the lower your armor rating.", true, false),
        JUGGERNAUT("Juggernaut", "Deals more damage the higher your current health.", true, false),

        // Armor prefixes
        BOLSTERING("Bolstering", "Increases damage resistance as health decreases.", false, true),
        FORTIFIED("Fortified", "Grants increased maximum health.", false, true),
        UNYIELDING("Unyielding", "Greatly reduces damage taken when below 30% health.", false, true),
        NIMBLE("Nimble", "Increases movement speed.", false, true);

        public final String displayName;
        public final String description;
        public final boolean weapon;
        public final boolean armor;

        Prefix(String displayName, String description, boolean weapon, boolean armor) {
            this.displayName = displayName;
            this.description = description;
            this.weapon = weapon;
            this.armor = armor;
        }
    }

    public static ItemStack generate(LootContext context) {
        RandomSource random = context.getRandom();
        ServerLevel level = context.getLevel();
        Vec3 origin = context.getOptional(LootContextParams.ORIGIN);
        BlockPos pos = origin != null ? BlockPos.containing(origin) : level.getRespawnData().pos();
        int regLevel = Leveling.regionLevel(level, pos);
        int playerLevel = LeveledGear.levelOf(context);
        int effLevel = Math.max(regLevel, playerLevel);

        boolean isWeapon = random.nextFloat() < 0.65f;
        LeveledGear.Kind kind = isWeapon ? (random.nextFloat() < 0.25f ? LeveledGear.Kind.BOW : LeveledGear.Kind.WEAPON) : LeveledGear.Kind.ARMOR;
        ItemStack stack = LeveledGear.roll(kind, effLevel, random);
        if (stack.isEmpty()) {
            stack = new ItemStack(Items.IRON_SWORD);
        }
        apply(stack, effLevel, random, kind == LeveledGear.Kind.ARMOR);
        return stack;
    }

    public static ItemStack apply(ItemStack stack, int level, RandomSource random, boolean isArmor) {
        List<Prefix> matching = new ArrayList<>();
        for (Prefix p : Prefix.values()) {
            if (isArmor ? p.armor : p.weapon) matching.add(p);
        }
        Prefix prefix = matching.get(random.nextInt(matching.size()));

        CompoundTag leg = com.skycraft.platform.ItemTags.childOrEmpty(stack, "skycraft_legendary");
        leg.putString("prefix", prefix.name());
        leg.putString("title", prefix.displayName);
        leg.putString("desc", prefix.description);
        com.skycraft.platform.ItemTags.setChild(stack, "skycraft_legendary", leg);
        com.skycraft.inventory.ItemData.updateCustom(stack, customData263 -> customData263.putBoolean("skycraft_is_legendary", true));

        Component customName = Component.literal("★ " + prefix.displayName + " " + stack.getHoverName().getString())
                .withStyle(ChatFormatting.GOLD);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, customName);

        return stack;
    }

    public static Prefix getPrefix(ItemStack stack) {
        if (stack == null || !com.skycraft.inventory.ItemData.hasCustom(stack)) return null;
        CompoundTag tag = com.skycraft.platform.ItemTags.child(stack, "skycraft_legendary");
        if (tag == null || !tag.contains("prefix")) return null;
        try {
            return Prefix.valueOf(tag.getStringOr("prefix",""));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}

