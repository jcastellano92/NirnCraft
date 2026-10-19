package com.nirncraft.creatures.entity;

import com.nirncraft.arsenal.LeveledGear;
import com.nirncraft.combat.CombatHandler;
import com.nirncraft.combat.LegendaryItem;
import com.nirncraft.core.Notifier;
import com.nirncraft.creatures.Leveling;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * {@code nirncraft:draugr_deathlord}: the master of a barrow. Uses Unrelenting Force (cone knockback + stagger)
 * every ~10 seconds and Frost Breath now and then. Shows a boss bar.
 */
public class DraugrDeathlordEntity extends DraugrEntity {
    private static final String[] DEATHLORD_NAMES = {
            "Curalmil the Ancient", "Red Eagle", "Jyrik Gauldurson",
            "Mikrul Gauldurson", "Sigdis Gauldurson", "Kvenel the Tongue", "Olaf One-Eye", "Draugr Overlord"
    };

    private final ServerBossEvent bossEvent = new ServerBossEvent(java.util.UUID.randomUUID(), Component.translatable("entity.nirncraft.draugr_deathlord"), BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.NOTCHED_10);
    private int shoutCooldown = 120;
    private int shoutWindup = -1;
    private int breathCooldown = 240;
    private int breathTicks = 0;

    public DraugrDeathlordEntity(EntityType<? extends DraugrDeathlordEntity> type, Level level) {
        super(type, level);
        this.xpReward = 60;
        this.bossEvent.setDarkenScreen(false);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 160.0)
                .add(Attributes.ATTACK_DAMAGE, 8.0)
                .add(Attributes.MOVEMENT_SPEED, 0.26)
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.ARMOR, 14.0)
                .add(Attributes.ARMOR_TOUGHNESS, 4.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.85);
    }

    @Override
    protected int skinCount() {
        return 1;
    }

    @Override
    protected void equip(RandomSource random, DifficultyInstance difficulty) {
        if (!this.hasCustomName()) {
            String name = DEATHLORD_NAMES[random.nextInt(DEATHLORD_NAMES.length)];
            this.setCustomName(Component.literal(name));
        }
        ItemStack weapon = random.nextBoolean()
                ? new ItemStack(Gear.modItem("iron_battleaxe", Items.IRON_AXE))
                : new ItemStack(Gear.modItem("iron_greatsword", Items.IRON_SWORD));
        Gear.equip(this, EquipmentSlot.MAINHAND, weapon, 0.0f);
        Gear.equip(this, EquipmentSlot.CHEST, new ItemStack(Items.CHAINMAIL_CHESTPLATE), 0.0f);
        Gear.equip(this, EquipmentSlot.LEGS, new ItemStack(Items.CHAINMAIL_LEGGINGS), 0.0f);
        Gear.equip(this, EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS), 0.0f);
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
    public net.minecraft.network.chat.Component rankName(int level) {
        return null;
    }

    // ------------------------------------------------------------------ boss bar

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
        net.minecraft.nbt.CompoundTag tag = com.nirncraft.platform.ValueTags.read(input263);
        
        if (this.hasCustomName()) this.bossEvent.setName(this.getDisplayName());
    }

    // ------------------------------------------------------------------ shouts

    @Override
    protected void customServerAiStep(net.minecraft.server.level.ServerLevel serverLevel263) {
        super.customServerAiStep(serverLevel263);
        this.bossEvent.setProgress(this.getHealth() / this.getMaxHealth());
        if (this.tickCount % 20 == 0 && this.level() instanceof ServerLevel sl) {
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
        LivingEntity target = this.getTarget();
        if (target == null || !target.isAlive()) {
            shoutWindup = -1;
            breathTicks = 0;
            return;
        }
        double distSqr = this.distanceToSqr(target);

        if (shoutWindup >= 0) {
            this.getLookControl().setLookAt(target, 30f, 30f);
            if (shoutWindup-- == 0) unrelentingForce();
        } else if (--shoutCooldown <= 0 && distSqr < 64 && this.hasLineOfSight(target)) {
            shoutWindup = 14;
            shoutCooldown = 180 + this.getRandom().nextInt(60);
            announce("creatures.nirncraft.shout.fus_ro");
            this.playSound(SoundEvents.WARDEN_SONIC_CHARGE, 2.0f, 0.8f);
        }

        if (breathTicks > 0) {
            breathTicks--;
            this.getLookControl().setLookAt(target, 30f, 30f);
            if (breathTicks % 2 == 0) frostBreath(target);
        } else if (--breathCooldown <= 0 && distSqr < 100 && distSqr > 4 && shoutWindup < 0 && this.hasLineOfSight(target)) {
            breathTicks = 40;
            breathCooldown = 300 + this.getRandom().nextInt(120);
            announce("creatures.nirncraft.shout.fo_krah_diin");
        }
    }

    private void announce(String key) {
        for (ServerPlayer p : this.level().getEntitiesOfClass(ServerPlayer.class, this.getBoundingBox().inflate(32))) {
            Notifier.message(p, Component.translatable(key));
        }
    }

    private Vec3 facing() {
        return Vec3.directionFromRotation(0, this.getYHeadRot());
    }

    /** "Fus Ro Dah": everything in front within 8 blocks is thrown back and staggered. */
    private void unrelentingForce() {
        Vec3 look = facing();
        Vec3 eye = this.getEyePosition();
        announce("creatures.nirncraft.shout.dah");
        this.playSound(com.nirncraft.audio.AudioSounds.shout(com.nirncraft.magic.shout.Shout.UNRELENTING_FORCE, 3), 3.0f, 0.85f);
        if (this.level() instanceof ServerLevel server) {
            for (int i = 1; i <= 8; i++) {
                Vec3 p = eye.add(look.scale(i));
                server.sendParticles(ParticleTypes.CLOUD, p.x, p.y, p.z, 4 + i, 0.15 * i, 0.15 * i, 0.15 * i, 0.02);
            }
            server.sendParticles(ParticleTypes.SONIC_BOOM, eye.x + look.x * 2, eye.y, eye.z + look.z * 2, 1, 0, 0, 0, 0);
        }
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, this.getBoundingBox().inflate(8.5),
                e -> e != this && e.isAlive() && !(e instanceof DraugrEntity))) {
            if (e instanceof Player p && (p.isCreative() || p.isSpectator())) continue;
            Vec3 to = e.position().subtract(this.position());
            double dist = to.length();
            if (dist > 8.5) continue;
            Vec3 flat = new Vec3(to.x, 0, to.z);
            flat = flat.lengthSqr() < 1.0E-4 ? look : flat.normalize();
            if (dist > 1.5 && flat.dot(look) < 0.5) continue;
            com.nirncraft.platform.Effects.hurt(e, this.damageSources().mobAttack(this), 4.0f);
            double strength = 2.4 * (1.0 - dist / 12.0);
            e.setDeltaMovement(e.getDeltaMovement().add(flat.x * strength, 0.6, flat.z * strength));
            e.syncVelocity = true;
            CombatHandler.stagger(this, e, 40);
        }
    }

    /** "Fo Krah Diin": a cone of frost that slows and freezes. */
    private void frostBreath(LivingEntity target) {
        Vec3 mouth = this.getEyePosition().subtract(0, 0.2, 0);
        Vec3 dir = target.getBoundingBox().getCenter().subtract(mouth).normalize();
        if (this.level() instanceof ServerLevel server) {
            RandomSource r = this.getRandom();
            for (int i = 0; i < 10; i++) {
                double vx = dir.x * 0.6 + (r.nextDouble() - 0.5) * 0.15;
                double vy = dir.y * 0.6 + (r.nextDouble() - 0.5) * 0.15;
                double vz = dir.z * 0.6 + (r.nextDouble() - 0.5) * 0.15;
                server.sendParticles(i % 3 == 0 ? ParticleTypes.CLOUD : ParticleTypes.SNOWFLAKE, mouth.x, mouth.y, mouth.z, 0, vx, vy, vz, 1.0);
            }
        }
        if (breathTicks % 8 == 0) this.playSound(SoundEvents.PLAYER_HURT_FREEZE, 1.5f, 0.6f);
        for (LivingEntity e : this.level().getEntitiesOfClass(LivingEntity.class, this.getBoundingBox().inflate(10),
                e -> e != this && e.isAlive() && !(e instanceof DraugrEntity))) {
            Vec3 to = e.getBoundingBox().getCenter().subtract(mouth);
            double along = to.dot(dir);
            if (along < 0 || along > 10) continue;
            double perp = to.subtract(dir.scale(along)).length();
            if (perp > 0.8 + along * 0.25) continue;
            com.nirncraft.platform.Effects.hurt(e, this.damageSources().indirectMagic(this, this), 1.5f);
            e.addEffect(new MobEffectInstance(com.nirncraft.platform.Effects.holder(MobEffects.SLOWNESS), 60, 1), this);
            if (e.canFreeze()) e.setTicksFrozen(Math.min(e.getTicksRequiredToFreeze() + 60, e.getTicksFrozen() + 12));
        }
    }
}
