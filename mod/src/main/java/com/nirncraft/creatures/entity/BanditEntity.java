package com.nirncraft.creatures.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
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

/** {@code nirncraft:bandit}: outlaws of the roads. Melee fighters (sometimes with a shield) or archers. */
public class BanditEntity extends SkyHumanoid implements Ranked {
    public static final int SKINS = 4;
    /** Skyrim bandit leather is brown, not Minecraft tan. */
    private static final int[] LEATHER_COLORS = {0x5A3A22, 0x4A3526, 0x6B4A2E, 0x3E2C1F, 0x55463A};

    public BanditEntity(EntityType<? extends BanditEntity> type, Level level) {
        super(type, level);
        this.xpReward = 8;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 22.0)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.MOVEMENT_SPEED, 0.28)
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
        // economy workstream: leveled, province-appropriate gear (Chitin in Morrowind, Ra Gada in Hammerfell...)
        if (!this.level().isClientSide()) {
            com.nirncraft.arsenal.LeveledGear.outfit(this, com.nirncraft.arsenal.LeveledGear.Outfit.BANDIT, random, 0.06f);
            return;
        }
        if (random.nextFloat() < 0.35f) {
            Gear.equip(this, EquipmentSlot.MAINHAND, new ItemStack(Gear.modItem("iron_bow", Items.BOW)), 0.06f);
        } else {
            Item[] weapons = {
                    Gear.modItem("iron_sword", Items.IRON_SWORD), Items.STONE_SWORD,
                    Gear.modItem("iron_war_axe", Items.IRON_AXE), Items.STONE_AXE,
                    Gear.modItem("iron_mace", Items.IRON_SWORD), Gear.modItem("iron_dagger", Items.STONE_SWORD)
            };
            Gear.equip(this, EquipmentSlot.MAINHAND, Gear.pick(random, weapons), 0.06f);
            if (random.nextFloat() < 0.3f) Gear.equip(this, EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD), 0.05f);
        }
        int color = LEATHER_COLORS[random.nextInt(LEATHER_COLORS.length)];
        for (EquipmentSlot slot : Gear.ARMOR_SLOTS) {
            if (random.nextFloat() > 0.45f) continue;
            float r = random.nextFloat();
            ItemStack stack = r < 0.6f ? Gear.dyedLeather(Gear.leather(slot), color)
                    : r < 0.87f ? new ItemStack(Gear.chainmail(slot)) : new ItemStack(Gear.iron(slot));
            Gear.equip(this, slot, stack, 0.06f);
        }
    }

    @Override
    @Nullable
    public Component rankName(int level) {
        String rank = level >= 30 ? "marauder" : level >= 24 ? "plunderer" : level >= 18 ? "highwayman"
                : level >= 12 ? "thug" : level >= 6 ? "outlaw" : null;
        return rank == null ? null : Component.translatable("entity.nirncraft.bandit." + rank);
    }

    @Override
    protected SoundEvent getAmbientSound() {
        return SoundEvents.PILLAGER_AMBIENT;
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PILLAGER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.PILLAGER_DEATH;
    }
}
