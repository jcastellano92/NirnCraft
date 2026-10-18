package com.skycraft.creatures.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import com.skycraft.arsenal.LeveledGear;
import com.skycraft.combat.LegendaryItem;
import com.skycraft.creatures.Leveling;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;

/** {@code skycraft:bandit_chief}: leader of a bandit camp. Heavy armor, two-handed weapon, hits hard. */
public class BanditChiefEntity extends BanditEntity {
    private static final String[] CHIEF_NAMES = {
            "Hajveth the Cruel", "Rigel Strong-Arm", "Kragh Blood-Eye",
            "Vald the Reaver", "Hakir Iron-Fist", "Malkor Ruthless", "Bandit Chief"
    };

    private final ServerBossEvent bossEvent = new ServerBossEvent(java.util.UUID.randomUUID(), Component.translatable("entity.skycraft.bandit_chief"), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_6);

    public BanditChiefEntity(EntityType<? extends BanditChiefEntity> type, Level level) {
        super(type, level);
        this.xpReward = 35;
        this.bossEvent.setDarkenScreen(false);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 65.0)
                .add(Attributes.ATTACK_DAMAGE, 6.0)
                .add(Attributes.MOVEMENT_SPEED, 0.27)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.ARMOR, 6.0)
                .add(Attributes.ARMOR_TOUGHNESS, 2.5)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.6);
    }

    @Override
    protected int skinCount() {
        return 1;
    }

    @Override
    protected void equip(RandomSource random, DifficultyInstance difficulty) {
        if (!this.hasCustomName()) {
            String name = CHIEF_NAMES[random.nextInt(CHIEF_NAMES.length)];
            this.setCustomName(Component.literal(name));
        }
        Item[] weapons = {
                Gear.modItem("steel_greatsword", Items.IRON_SWORD), Gear.modItem("steel_battleaxe", Items.IRON_AXE),
                Gear.modItem("iron_warhammer", Items.IRON_AXE), Gear.modItem("orcish_battleaxe", Items.DIAMOND_AXE)
        };
        Gear.equip(this, EquipmentSlot.MAINHAND, Gear.pick(random, weapons), 0.15f);
        for (EquipmentSlot slot : Gear.ARMOR_SLOTS) {
            ItemStack stack = slot == EquipmentSlot.CHEST && random.nextFloat() < 0.25f ? new ItemStack(Items.DIAMOND_CHESTPLATE)
                    : random.nextFloat() < 0.75f ? new ItemStack(Gear.iron(slot)) : new ItemStack(Gear.chainmail(slot));
            Gear.equip(this, slot, stack, 0.12f);
        }
    }

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        if (player.distanceToSqr(this) <= 48.0 * 48.0) {
            this.bossEvent.addPlayer(player);
        }
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        this.bossEvent.removePlayer(player);
    }

    @Override
    public void setCustomName(@Nullable Component name) {
        super.setCustomName(name);
        this.bossEvent.setName(this.getDisplayName());
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.world.level.storage.ValueInput input263) {
        super.readAdditionalSaveData(input263);
        net.minecraft.nbt.CompoundTag tag = com.skycraft.platform.ValueTags.read(input263);
        
        if (this.hasCustomName()) this.bossEvent.setName(this.getDisplayName());
    }

    @Override
    protected void customServerAiStep(net.minecraft.server.level.ServerLevel serverLevel263) {
        super.customServerAiStep(serverLevel263);
        this.bossEvent.setProgress(this.getHealth() / this.getMaxHealth());
        if (this.tickCount % 20 == 0 && this.level() instanceof net.minecraft.server.level.ServerLevel sl) {
            double rangeSq = 48.0 * 48.0;
            for (ServerPlayer player : sl.players()) {
                boolean inRange = player.isAlive() && !player.isSpectator() && player.distanceToSqr(this) <= rangeSq;
                boolean tracking = this.bossEvent.getPlayers().contains(player);
                if (inRange && !tracking) {
                    this.bossEvent.addPlayer(player);
                } else if (!inRange && tracking) {
                    this.bossEvent.removePlayer(player);
                }
            }
        }
    }

    /** Two-handed blows knock the target back hard. */
    @Override
    public boolean doHurtTarget(net.minecraft.server.level.ServerLevel serverLevel263, Entity target) {
        boolean hit = super.doHurtTarget(serverLevel263, target);
        if (hit && target instanceof LivingEntity living) {
            living.knockback(0.6, this.getX() - target.getX(), this.getZ() - target.getZ(), this.damageSources().mobAttack(this), (float)this.getAttributeValue(Attributes.ATTACK_DAMAGE));
            living.syncVelocity = true;
        }
        return hit;
    }

    @Override
    protected void dropCustomDeathLoot(net.minecraft.server.level.ServerLevel lootLevel263, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(lootLevel263, source, recentlyHit);
        if (this.level() instanceof ServerLevel sl) {
            int lvl = Leveling.levelOf(this);
            if (lvl <= 0) lvl = Leveling.regionLevel(sl, this.blockPosition());
            boolean isWeapon = this.random.nextFloat() < 0.6f;
            LeveledGear.Kind kind = isWeapon ? (this.random.nextFloat() < 0.25f ? LeveledGear.Kind.BOW : LeveledGear.Kind.WEAPON) : LeveledGear.Kind.ARMOR;
            ItemStack leg = LeveledGear.roll(kind, lvl, this.random);
            if (leg.isEmpty()) leg = new ItemStack(Items.IRON_SWORD);
            LegendaryItem.apply(leg, lvl, this.random, kind == LeveledGear.Kind.ARMOR);
            this.spawnAtLocation(lootLevel263, leg);
        }
    }

    @Override
    @Nullable
    public Component rankName(int level) {
        return null;
    }

    @Override
    public void die(DamageSource source) {
        super.die(source);
        this.bossEvent.removeAllPlayers();
    }

    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        this.bossEvent.removeAllPlayers();
    }
}
