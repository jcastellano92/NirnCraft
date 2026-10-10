package com.skycraft.arsenal;

import com.skycraft.Skycraft;
import com.skycraft.arsenal.entity.SkyArrow;
import com.skycraft.arsenal.item.ArrowKind;
import com.skycraft.arsenal.item.SkyCrossbowItem;
import com.skycraft.combat.CombatHandler;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.network.SkyNetwork;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.SpectralArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.event.entity.living.LivingExperienceDropEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge-bus rules of the arsenal: arrow bookkeeping (corpse arrows, contract 27), headshots, elemental arrows,
 * armor-piercing crossbows, leveled weapon drops from humanoid hostiles, kill-cam triggers and Wabbajack chickens.
 */
@Mod.EventBusSubscriber(modid = Skycraft.MODID)
public final class ArsenalEvents {
    /** Victim persistent data: ListTag of ItemStack NBT, the arrows stuck in its body (cap {@value #MAX_ARROWS}). */
    public static final String BODY_ARROWS = "skycraft_arrows";
    public static final int MAX_ARROWS = 16;
    /** Arrow persistent data: the ammo item it was shot as (bows; Skycraft arrows know it themselves). */
    public static final String AMMO = "skycraft_ammo";
    /** Arrow persistent data: fraction of armor ignored (Skycraft crossbows). */
    public static final String ARMOR_PIERCE = "skycraft_armor_pierce";
    private static final String RECORDED = "skycraft_recorded";
    private static final String PREPARED = "skycraft_arsenal_prepared";
    private static final String BOUND_ARROW = "skycraft_bound_arrow";

    private ArsenalEvents() {}

    // ------------------------------------------------------------------ arrows leaving the bow

    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof AbstractArrow arrow) || event.loadedFromDisk()) return;
        CompoundTag pd = arrow.getPersistentData();
        if (pd.getBoolean(PREPARED)) return;
        pd.putBoolean(PREPARED, true);
        if (!(arrow.getOwner() instanceof LivingEntity owner)) return;

        if (arrow.shotFromCrossbow()) {
            SkyCrossbowItem crossbow = owner.getMainHandItem().getItem() instanceof SkyCrossbowItem c ? c
                    : owner.getOffhandItem().getItem() instanceof SkyCrossbowItem c2 ? c2 : null;
            if (crossbow != null) {
                arrow.setBaseDamage(arrow.getBaseDamage() + crossbow.bonusDamage);
                pd.putFloat(ARMOR_PIERCE, crossbow.armorPierce);
            }
            return;
        }
        if (!(arrow instanceof SkyArrow) && owner instanceof Player player) {
            ItemStack weapon = player.getUseItem();
            if (weapon.getItem() instanceof ProjectileWeaponItem) {
                ItemStack ammo = player.getProjectile(weapon);
                if (!ammo.isEmpty()) {
                    ItemStack one = ammo.copy();
                    one.setCount(1);
                    pd.put(AMMO, one.save(new CompoundTag()));
                }
            }
        }
    }

    /** The item an arrow entity would be picked up as. */
    public static ItemStack ammoOf(AbstractArrow arrow) {
        if (arrow instanceof SkyArrow sky) return sky.pickupStack();
        CompoundTag pd = arrow.getPersistentData();
        if (pd.contains(AMMO, Tag.TAG_COMPOUND)) {
            ItemStack stack = ItemStack.of(pd.getCompound(AMMO));
            if (!stack.isEmpty()) return stack;
        }
        if (arrow instanceof SpectralArrow) return new ItemStack(Items.SPECTRAL_ARROW);
        return new ItemStack(Items.ARROW);
    }

    // ------------------------------------------------------------------ arrows hitting

    /** Headshots, elemental arrows and armor piercing (before armor is applied). */
    @SubscribeEvent
    public static void onHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide) return;
        DamageSource source = event.getSource();
        if (!(source.getDirectEntity() instanceof AbstractArrow arrow)) return;
        float amount = event.getAmount();

        if (arrow instanceof SkyArrow sky) {
            ArrowKind kind = sky.kind();
            if (kind != null) {
                switch (kind.element) {
                    case FIRE -> amount += victim.fireImmune() ? 0 : 1.5f;
                    case FROST -> amount += 1.5f;
                    case SHOCK -> {
                        amount += 2.5f;
                        if (victim instanceof Player p) {
                            PlayerData d = SkyData.get(p);
                            d.setMagicka(d.getMagicka() - 10f);
                        }
                        if (victim.level() instanceof ServerLevel sl) {
                            sl.sendParticles(ParticleTypes.ELECTRIC_SPARK, victim.getX(), victim.getY() + victim.getBbHeight() * 0.6,
                                    victim.getZ(), 12, 0.3, 0.4, 0.3, 0.2);
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        if (ArsenalConfig.HEADSHOTS.get() && arrow.getOwner() instanceof ServerPlayer shooter && humanoid(victim) && headshot(arrow, victim)) {
            amount *= ArsenalConfig.HEADSHOT_MULTIPLIER.get().floatValue();
            Notifier.message(shooter, Component.translatable("message.skycraft.arsenal.headshot"));
            if (victim.level() instanceof ServerLevel sl) {
                sl.sendParticles(ParticleTypes.CRIT, victim.getX(), victim.getEyeY(), victim.getZ(), 10, 0.2, 0.15, 0.2, 0.3);
            }
            shooter.playNotifySound(SoundEvents.ARROW_HIT_PLAYER, SoundSource.PLAYERS, 0.6f, 1.6f);
        }

        float pierce = arrow.getPersistentData().getFloat(ARMOR_PIERCE);
        if (pierce > 0 && !source.is(DamageTypeTags.BYPASSES_ARMOR) && victim.getArmorValue() > 0) {
            float armor = victim.getArmorValue();
            float toughness = (float) victim.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
            float full = CombatRules.getDamageAfterAbsorb(amount, armor, toughness);
            float pierced = CombatRules.getDamageAfterAbsorb(amount, armor * (1f - pierce), toughness);
            if (full > 0.01f) amount *= pierced / full;
        }
        event.setAmount(amount);
    }

    /** Arrows that wounded a mob stay in its body (contract 27: the corpse adds them to its loot). */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void recordArrow(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || victim instanceof Player || event.getAmount() <= 0) return;
        if (!(event.getSource().getDirectEntity() instanceof AbstractArrow arrow) || !ArsenalConfig.RECORD_ARROWS.get()) return;
        CompoundTag apd = arrow.getPersistentData();
        if (apd.getBoolean(RECORDED) || apd.getBoolean(BOUND_ARROW)) return;
        Entity owner = arrow.getOwner();
        boolean fromPlayer = owner instanceof Player && arrow.pickup == AbstractArrow.Pickup.ALLOWED;
        boolean fromMob = owner instanceof Mob && !(owner instanceof Player);
        if (!fromPlayer && !fromMob) return;
        ItemStack stack = ammoOf(arrow);
        if (stack.isEmpty() || (arrow instanceof SkyArrow sky && sky.element() == ArrowKind.Element.EXPLOSIVE)) return;
        apd.putBoolean(RECORDED, true);
        CompoundTag pd = victim.getPersistentData();
        ListTag list = pd.getList(BODY_ARROWS, Tag.TAG_COMPOUND);
        if (list.size() >= MAX_ARROWS) return;
        ItemStack one = stack.copy();
        one.setCount(1);
        list.add(one.save(new CompoundTag()));
        pd.put(BODY_ARROWS, list);
        if (fromPlayer) arrow.pickup = AbstractArrow.Pickup.DISALLOWED; // piercing arrows can't be collected twice
    }

    private static boolean humanoid(LivingEntity e) {
        float h = e.getBbHeight();
        return h >= 1.4f && h <= 2.4f && e.getBbWidth() <= 1.0f;
    }

    /** Did the arrow enter above the victim's eyes - 0.25? The arrow is at its previous position when it hits. */
    private static boolean headshot(AbstractArrow arrow, LivingEntity victim) {
        Vec3 from = arrow.position();
        Vec3 to = from.add(arrow.getDeltaMovement());
        double y = victim.getBoundingBox().inflate(0.3).clip(from, to).map(v -> v.y).orElse(arrow.getY());
        return y >= victim.getEyeY() - 0.25;
    }

    // ------------------------------------------------------------------ leveled drops

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onDrops(LivingDropsEvent event) {
        LivingEntity victim = event.getEntity();
        CompoundTag pd = victim.getPersistentData();
        if (pd.contains(StaffEffects.WABBAJACK_ORIGINAL)) {
            event.setCanceled(true); // Wabbajack chickens leave nothing behind (summons: see magic's Summons)
            return;
        }
        if (!(event.getSource().getEntity() instanceof ServerPlayer killer) || !(victim instanceof Mob) || !(victim instanceof Enemy)) return;
        if (victim.isBaby() || !humanoid(victim) || victim.getType().is(ArsenalTags.GUARDS) || victim.getType().is(CombatHandler.BOSSES)) return;
        var random = victim.getRandom();
        double chance = ArsenalConfig.MOB_WEAPON_DROP_CHANCE.get() * (1 + 0.3 * event.getLootingLevel());
        if (random.nextDouble() >= chance) return;
        int level = Math.max(1, SkyData.get(killer).getLevel());
        ItemStack loot;
        if (victim instanceof AbstractSkeleton) {
            loot = random.nextBoolean() ? LeveledGear.bow(level, random) : LeveledGear.arrows(level, random);
        } else {
            loot = LeveledGear.weapon(level, random);
        }
        if (!loot.isEmpty() && loot.getMaxStackSize() == 1) {
            loot.setDamageValue(random.nextInt(Math.max(1, loot.getMaxDamage() / 3)));
            LeveledGear.decorate(loot, level, random, 0.05f, 0.05f);
        }
        event.getDrops().add(new ItemEntity(victim.level(), victim.getX(), victim.getY() + 0.5, victim.getZ(), loot));
    }

    @SubscribeEvent
    public static void onXp(LivingExperienceDropEvent event) {
        CompoundTag pd = event.getEntity().getPersistentData();
        if (pd.contains(StaffEffects.WABBAJACK_ORIGINAL)) event.setCanceled(true);
    }

    // ------------------------------------------------------------------ kill cams

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide || victim instanceof Player || !ArsenalConfig.KILL_CAMS.get()) return;
        DamageSource source = event.getSource();
        if (!(source.getEntity() instanceof ServerPlayer killer) || !killer.isAlive()) return;
        Entity direct = source.getDirectEntity();
        boolean melee = direct == killer;
        boolean arrow = direct instanceof AbstractArrow;
        if (!melee && !arrow) return;
        boolean hostile = victim instanceof Enemy || victim instanceof Mob mob && mob.getTarget() == killer;
        if (!hostile || victim.distanceToSqr(killer) > (arrow ? 64 * 64 : 12 * 12)) return;
        boolean last = victim.level().getEntitiesOfClass(Mob.class, killer.getBoundingBox().inflate(24),
                m -> m != victim && m.isAlive() && (m instanceof Enemy || m.getTarget() == killer)).isEmpty();
        double chance = last ? ArsenalConfig.KILL_CAM_LAST_ENEMY_CHANCE.get() : ArsenalConfig.KILL_CAM_OTHER_CHANCE.get();
        if (killer.getRandom().nextDouble() >= chance) return;
        Vec3 dir = arrow ? direct.getDeltaMovement() : victim.position().subtract(killer.position());
        if (dir.lengthSqr() < 1.0E-4) dir = killer.getViewVector(1f);
        dir = dir.normalize();
        // Sent on the killing blow, before the loot body replaces the victim: the packet carries where the victim was.
        Vec3 mid = victim.position().add(0, victim.getBbHeight() * 0.55, 0);
        SkyNetwork.sendToPlayer(killer, new ArsenalPackets.KillCam(victim.getId(),
                arrow ? ArsenalPackets.KillCam.ARROW : ArsenalPackets.KillCam.MELEE, arrow ? direct.getId() : -1,
                (float) dir.x, (float) dir.y, (float) dir.z, (float) mid.x, (float) mid.y, (float) mid.z));
        killer.playNotifySound(SoundEvents.PLAYER_ATTACK_CRIT, SoundSource.PLAYERS, 1f, 0.55f);
        killer.playNotifySound(SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.PLAYERS, 0.8f, 0.7f);
    }

    // ------------------------------------------------------------------ staff effect lifetimes

    /** Wabbajack chickens turn back. (Summons, calm and fury are status effects / summons of the magic module.) */
    @SubscribeEvent
    public static void onLivingTick(LivingEvent.LivingTickEvent event) {
        LivingEntity e = event.getEntity();
        if (e.level().isClientSide || e.tickCount % 10 != 0 || e instanceof Player) return;
        CompoundTag pd = e.getPersistentData();
        if (pd.isEmpty()) return;
        long now = e.level().getGameTime();
        if (pd.contains(StaffEffects.WABBAJACK_UNTIL) && now > pd.getLong(StaffEffects.WABBAJACK_UNTIL) && pd.contains(StaffEffects.WABBAJACK_ORIGINAL)) {
            StaffEffects.unchicken(e);
        }
    }
}
