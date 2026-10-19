package com.nirncraft.arsenal;

import com.nirncraft.arsenal.item.PolearmItem;
import com.nirncraft.arsenal.item.StaffItem;
import com.nirncraft.combat.WeaponClass;
import com.nirncraft.society.entity.NpcEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;

/**
 * Sheathed & holstered weapon queries for third-person player and humanoid rendering.
 * Delegates player sheathe state to {@link com.nirncraft.combat.Sheathe} and determines which one-handed sidearm,
 * two-handed/ranged back weapon, or shield should render on the hip or back.
 */
public final class Sheathe {
    private Sheathe() {}

    public static boolean isSheathed(Player player) {
        return com.nirncraft.combat.Sheathe.isSheathed(player);
    }

    public static void setSheathed(Player player, boolean sheathed) {
        com.nirncraft.combat.Sheathe.setSheathed(player, sheathed);
    }

    public static void toggle(Player player) {
        com.nirncraft.combat.Sheathe.toggle(player);
    }

    /** True when the entity has its held weapons stowed away (player sheathed, or calm non-aggressive NPC). */
    public static boolean isSheathedOrHolstered(LivingEntity entity) {
        if (entity instanceof Player player) {
            return com.nirncraft.combat.Sheathe.isSheathed(player);
        }
        if (entity instanceof NpcEntity npc) {
            return !npc.isAggressive() && npc.getTarget() == null && !npc.isCasting()
                    && !npc.isUsingItem() && !npc.isPrisoner() && !npc.isPlaying();
        }
        return false;
    }

    /** One-handed melee weapons (swords, daggers, war axes, maces) wear on the belt/hip. */
    public static boolean isHipWeapon(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() instanceof PolearmItem || stack.getItem() instanceof StaffItem) {
            return false;
        }
        WeaponClass wc = WeaponClass.of(stack);
        return wc == WeaponClass.SWORD || wc == WeaponClass.DAGGER || wc == WeaponClass.WAR_AXE || wc == WeaponClass.MACE;
    }

    /** Two-handed weapons, polearms, staves, bows, and crossbows sling across the back. */
    public static boolean isBackWeapon(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.getItem() instanceof PolearmItem
                || stack.getItem() instanceof StaffItem
                || stack.getItem() instanceof BowItem
                || stack.getItem() instanceof CrossbowItem) {
            return true;
        }
        WeaponClass wc = WeaponClass.of(stack);
        return wc == WeaponClass.GREATSWORD || wc == WeaponClass.BATTLEAXE || wc == WeaponClass.WARHAMMER;
    }

    /** Returns the one-handed weapon to display on the entity's hip, or {@link ItemStack#EMPTY}. */
    public static ItemStack findHipWeapon(LivingEntity entity) {
        if (entity instanceof Player player) {
            boolean sheathed = isSheathed(player);
            ItemStack main = player.getMainHandItem();
            if (sheathed && isHipWeapon(main)) return main;
            ItemStack off = player.getOffhandItem();
            if (sheathed && isHipWeapon(off)) return off;
            return com.nirncraft.inventory.Inv.find(player, s -> s != main && s != off && isHipWeapon(s));
        }
        if (entity instanceof NpcEntity npc && isSheathedOrHolstered(npc)) {
            ItemStack main = npc.getMainHandItem();
            if (isHipWeapon(main)) return main;
        }
        return ItemStack.EMPTY;
    }

    /** Returns the two-handed/ranged/staff weapon to display on the entity's back, or {@link ItemStack#EMPTY}. */
    public static ItemStack findBackWeapon(LivingEntity entity) {
        if (entity instanceof Player player) {
            boolean sheathed = isSheathed(player);
            ItemStack main = player.getMainHandItem();
            if (sheathed && isBackWeapon(main)) return main;
            ItemStack off = player.getOffhandItem();
            return com.nirncraft.inventory.Inv.find(player, s -> s != main && s != off && isBackWeapon(s));
        }
        if (entity instanceof NpcEntity npc && isSheathedOrHolstered(npc)) {
            ItemStack main = npc.getMainHandItem();
            if (isBackWeapon(main)) return main;
        }
        return ItemStack.EMPTY;
    }

    /** Returns the shield to display on the entity's back when sheathed, or {@link ItemStack#EMPTY}. */
    public static ItemStack findBackShield(LivingEntity entity) {
        if (isSheathedOrHolstered(entity)) {
            ItemStack off = entity.getOffhandItem();
            if (off.getItem() instanceof ShieldItem) return off;
        }
        return ItemStack.EMPTY;
    }
}
