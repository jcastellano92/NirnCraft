package com.nirncraft.combat;

import com.nirncraft.NirnCraft;
import com.nirncraft.core.Skill;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;

/** Skyrim weapon families, resolved from item tags with a vanilla-class fallback. */
public enum WeaponClass {
    UNARMED(null), DAGGER(Skill.ONE_HANDED), SWORD(Skill.ONE_HANDED), WAR_AXE(Skill.ONE_HANDED), MACE(Skill.ONE_HANDED),
    GREATSWORD(Skill.TWO_HANDED), BATTLEAXE(Skill.TWO_HANDED), WARHAMMER(Skill.TWO_HANDED), BOW(Skill.ARCHERY), OTHER(null),
    /** Spears, halberds and glaives (arsenal, tag {@code #nirncraft:polearms}): the Polearms skill. */
    POLEARM(Skill.POLEARM);

    public static final TagKey<Item> POLEARMS = tag("polearms");

    public static final TagKey<Item> TWO_HANDED = tag("two_handed");
    public static final TagKey<Item> DAGGERS = tag("daggers");
    public static final TagKey<Item> MACES = tag("maces");
    public static final TagKey<Item> WAR_AXES = tag("war_axes");
    public static final TagKey<Item> GREATSWORDS = tag("greatswords");
    public static final TagKey<Item> BATTLEAXES = tag("battleaxes");
    public static final TagKey<Item> WARHAMMERS = tag("warhammers");

    public final Skill skill;

    WeaponClass(Skill skill) {
        this.skill = skill;
    }

    private static TagKey<Item> tag(String name) {
        return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(NirnCraft.MODID, name));
    }

    public boolean twoHanded() {
        return skill == Skill.TWO_HANDED;
    }

    public static WeaponClass of(ItemStack stack) {
        if (stack.isEmpty()) return UNARMED;
        if (stack.is(POLEARMS)) return POLEARM;
        if (stack.is(GREATSWORDS)) return GREATSWORD;
        if (stack.is(BATTLEAXES)) return BATTLEAXE;
        if (stack.is(WARHAMMERS)) return WARHAMMER;
        if (stack.is(TWO_HANDED)) return GREATSWORD;
        if (stack.is(DAGGERS)) return DAGGER;
        if (stack.is(MACES)) return MACE;
        if (stack.is(WAR_AXES)) return WAR_AXE;
        if (stack.getItem() instanceof BowItem || stack.getItem() instanceof CrossbowItem) return BOW;
        if (stack.is(net.minecraft.tags.ItemTags.SWORDS)) return SWORD;
        if (stack.is(net.minecraft.tags.ItemTags.AXES)) return WAR_AXE;
        if (stack.getItem() instanceof TridentItem) return SWORD;
        return OTHER;
    }
}
