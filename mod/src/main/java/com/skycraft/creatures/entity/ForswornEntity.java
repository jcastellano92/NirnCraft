package com.skycraft.creatures.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/** {@code skycraft:forsworn}: tribal native rebels of The Reach. Fast, brutal, wielding dual weapons or bows. */
public class ForswornEntity extends SkyHumanoid implements Ranked {
    public static final int SKINS = 2;

    public ForswornEntity(EntityType<? extends ForswornEntity> type, Level level) {
        super(type, level);
        this.xpReward = 12;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 26.0)
                .add(Attributes.ATTACK_DAMAGE, 3.5)
                .add(Attributes.MOVEMENT_SPEED, 0.29)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.ARMOR, 2.0);
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, AbstractVillager.class, false));
        this.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, GuardEntity.class, true));
        this.targetSelector.addGoal(4, new NearestAttackableTargetGoal<>(this, IronGolem.class, true));
    }

    @Override
    protected int skinCount() {
        return SKINS;
    }

    @Override
    protected void equip(RandomSource random, DifficultyInstance difficulty) {
        if (random.nextFloat() < 0.35f) {
            Gear.equip(this, EquipmentSlot.MAINHAND, new ItemStack(Gear.modItem("hunting_bow", Items.BOW)), 0.08f);
        } else {
            Item[] weapons = {
                    Gear.modItem("forsworn_axe", Items.STONE_AXE), Gear.modItem("forsworn_sword", Items.STONE_SWORD),
                    Items.IRON_AXE, Items.IRON_SWORD
            };
            Gear.equip(this, EquipmentSlot.MAINHAND, Gear.pick(random, weapons), 0.08f);
            if (random.nextFloat() < 0.45f) {
                Gear.equip(this, EquipmentSlot.OFFHAND, Gear.pick(random, weapons), 0.08f);
            }
        }
        for (EquipmentSlot slot : Gear.ARMOR_SLOTS) {
            if (random.nextFloat() < 0.6f) {
                Gear.equip(this, slot, Gear.dyedLeather(Gear.leather(slot), 0x5C4033), 0.06f);
            }
        }
    }

    @Override
    @Nullable
    public Component rankName(int level) {
        String rank = level >= 28 ? "ravager" : level >= 20 ? "pillager" : level >= 12 ? "looter" : null;
        return rank == null ? null : Component.translatable("entity.skycraft.forsworn." + rank);
    }
}

