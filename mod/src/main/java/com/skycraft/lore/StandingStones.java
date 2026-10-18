package com.skycraft.lore;

import com.skycraft.SkyConfig;
import com.skycraft.Skycraft;
import com.skycraft.combat.ArmorClass;
import com.skycraft.core.Buffs;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.magic.MagicDamage;
import com.skycraft.magic.Targeting;
import com.skycraft.magic.spell.Summons;
import com.skycraft.perk.Perks;
import com.skycraft.registry.ModEffects;
import com.skycraft.vitals.Vitals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * Standing Stone blessings: activation, the passive effects of every sign, and the once-a-day powers of the
 * Ritual, Serpent, Shadow and Tower stones.
 *
 * <p>State: {@code data.module("lore")} keys {@code stone} (sign id, the cross-module contract),
 * {@code power_<sign>} (game time the power is ready again), {@code applied_magicka}/{@code applied_carry}
 * (what this module currently adds to {@code module("bonus")}, so it can be removed exactly when switching).</p>
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class StandingStones {
    public static final String MODULE = "lore";
    public static final String KEY = "stone";
    /** Buffs flag granted by the Tower Stone's power; the crime module's lock handling may honour it. */
    public static final String TOWER_BUFF = "tower_unlock";

    private static final String APPLIED_MAGICKA = "applied_magicka";
    private static final String APPLIED_CARRY = "applied_carry";
    private static final net.minecraft.resources.Identifier LORD_ARMOR = net.minecraft.resources.Identifier.fromNamespaceAndPath("skycraft", "lore/lord_armor");
    private static final net.minecraft.resources.Identifier STEED_SPEED = net.minecraft.resources.Identifier.fromNamespaceAndPath("skycraft", "lore/steed_speed");
    private static final int DAY = 24000;

    private StandingStones() {}

    // ------------------------------------------------------------------ queries

    @Nullable
    public static StandingStone current(Player player) {
        return StandingStone.byId(SkyData.get(player).module(MODULE).getStringOr(KEY, ""));
    }

    /** Skill XP multiplier (registered with {@code Progression.registerXpModifier}). */
    public static float xpMultiplier(ServerPlayer player, Skill skill) {
        StandingStone s = current(player);
        if (s == null) return 1f;
        return switch (s) {
            case WARRIOR -> skill.group == Skill.Group.WARRIOR ? 1.2f : 1f;
            case MAGE -> skill.group == Skill.Group.MAGE ? 1.2f : 1f;
            case THIEF -> skill.group == Skill.Group.THIEF ? 1.2f : 1f;
            case LOVER -> 1.15f;
            default -> 1f;
        };
    }

    // ------------------------------------------------------------------ activation

    public static void activate(ServerPlayer player, StandingStone sign, BlockPos base) {
        PlayerData data = SkyData.get(player);
        CompoundTag tag = data.module(MODULE);
        ServerLevel level = player.level();
        StandingStone old = StandingStone.byId(tag.getStringOr(KEY, ""));
        if (old == sign) {
            Notifier.message(player, Component.translatable("message.skycraft.lore.stone_already", sign.stoneName()));
            level.playSound(null, base, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 1f, 0.7f);
            return;
        }
        tag.putString(KEY, sign.id());
        data.addStat("standing_stones_activated", 1);
        data.markDirty();
        refresh(player);
        effects(level, base, sign, player);
        Notifier.title(player, sign.stoneName(), sign.description());
        if (old != null) {
            Notifier.message(player, Component.translatable("message.skycraft.lore.stone_replaced", old.stoneName()));
        }
        if (sign.hasPower) {
            Notifier.message(player, Component.translatable("message.skycraft.lore.stone_power_hint", sign.powerName(),
                    Component.keybind("key.skycraft.stone_power")));
        }
    }

    private static void effects(ServerLevel level, BlockPos base, StandingStone sign, ServerPlayer player) {
        Vector3f color = new Vector3f((sign.color >> 16 & 255) / 255f, (sign.color >> 8 & 255) / 255f, (sign.color & 255) / 255f);
        DustParticleOptions dust = new DustParticleOptions(net.minecraft.util.ARGB.colorFromFloat(1f, (color).x(), (color).y(), (color).z()), 1.6f);
        double x = base.getX() + 0.5;
        double z = base.getZ() + 0.5;
        // a beam of light from the stone into the sky
        for (int i = 0; i < 56; i++) {
            level.sendParticles(dust, x, base.getY() + 1.8 + i * 0.5, z, 2, 0.08, 0.15, 0.08, 0);
        }
        level.sendParticles(ParticleTypes.END_ROD, x, base.getY() + 6, z, 50, 0.12, 5, 0.12, 0.01);
        // a spiral of light around the player
        for (int i = 0; i < 36; i++) {
            double a = i * Math.PI * 2 / 12.0;
            level.sendParticles(dust, player.getX() + Math.cos(a) * 1.1, player.getY() + 0.1 + i * 0.06, player.getZ() + Math.sin(a) * 1.1,
                    1, 0, 0, 0, 0);
        }
        level.sendParticles(ParticleTypes.ENCHANT, player.getX(), player.getY() + 1, player.getZ(), 60, 0.6, 0.8, 0.6, 0.8);
        level.playSound(null, base, com.skycraft.world.WorldSounds.MAGIC_STANDING_STONE.get(), SoundSource.BLOCKS, 1.6f, 1.0f);
        level.playSound(null, player.getX(), player.getY(), player.getZ(), com.skycraft.world.WorldSounds.MAGIC_STANDING_STONE.get(), SoundSource.PLAYERS, 1.0f, 1.0f);
    }

    // ------------------------------------------------------------------ passive effects

    /**
     * Brings attribute modifiers and module("bonus") contributions in line with the active stone. Idempotent; runs
     * once a second and right after switching, logging in or respawning (attribute modifiers are transient).
     */
    public static void refresh(ServerPlayer player) {
        StandingStone s = current(player);
        PlayerData data = SkyData.get(player);
        CompoundTag tag = data.module(MODULE);
        boolean changed = syncBonus(data, tag, "magicka", APPLIED_MAGICKA, s == StandingStone.ATRONACH ? 50f : 0f);
        changed |= syncBonus(data, tag, "carry", APPLIED_CARRY, s == StandingStone.STEED ? 100f : 0f);

        modifier(player, Attributes.ARMOR, LORD_ARMOR, "Skycraft Lord Stone",
                s == StandingStone.LORD ? 4.0 : 0.0, AttributeModifier.Operation.ADD_VALUE);
        double steed = 0;
        if (s == StandingStone.STEED) {
            steed = 0.05;
            // "Worn armor weighs nothing": cancel the heavy-armor slowdown the core applies.
            if (!Perks.has(player, "heavy_armor.conditioning")) steed += 0.025 * ArmorClass.countHeavy(player);
        }
        modifier(player, Attributes.MOVEMENT_SPEED, STEED_SPEED, "Skycraft Steed Stone", steed, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);

        if (changed) {
            data.markDirty();
            Vitals.refreshAttributes(player);
        }
    }

    /** Adds/removes exactly what this module contributed to a shared {@code module("bonus")} value. */
    private static boolean syncBonus(PlayerData data, CompoundTag tag, String key, String appliedKey, float desired) {
        float applied = tag.getFloatOr(appliedKey, 0f);
        if (applied == desired) return false;
        CompoundTag bonus = data.module("bonus");
        bonus.putFloat(key, bonus.getFloatOr(key, 0f) - applied + desired);
        tag.putFloat(appliedKey, desired);
        return true;
    }

    private static void modifier(ServerPlayer player, net.minecraft.core.Holder<Attribute> attribute, net.minecraft.resources.Identifier id, String name, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = player.getAttribute(attribute);
        if (inst == null) return;
        AttributeModifier existing = inst.getModifier(id);
        if (existing != null && existing.amount() == amount) return;
        if (existing != null) inst.removeModifier(id);
        if (amount != 0) inst.addTransientModifier(new AttributeModifier(id, amount, op));
    }

    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player)) return;
        long now = player.level().getGameTime();
        if (now % 20 == 7) refresh(player);
        if (now % 5 != 0 || !player.isAlive()) return;
        StandingStone s = current(player);
        if (s == null) return;
        switch (s) {
            case LADY -> ladyRegen(player, now);
            case ATRONACH -> magickaRegen(player, now, -0.5f);
            case APPRENTICE -> magickaRegen(player, now, 1f);
            default -> {
            }
        }
    }

    /** The Lady: health and stamina regenerate 25% faster (on top of the core's regen, same rules). */
    private static void ladyRegen(ServerPlayer player, long now) {
        PlayerData data = SkyData.get(player);
        boolean combat = Vitals.inCombat(player);
        float seconds = 0.25f;
        if (SkyConfig.SKYRIM_REGEN.get() && player.getHealth() < player.getMaxHealth()) {
            float hpPct = (float) (double) SkyConfig.HEALTH_REGEN_PERCENT.get() / 100f;
            if (combat) hpPct *= 0.1f;
            player.heal(player.getMaxHealth() * hpPct * seconds * 0.25f);
        }
        if (now - data.getLastStaminaUseTick() > 20 && !player.isSprinting() && data.getStamina() < data.maxStamina()) {
            float stPct = (float) (double) SkyConfig.STAMINA_REGEN_PERCENT.get() / 100f;
            if (combat) stPct *= 0.5f;
            data.setStamina(data.getStamina() + data.maxStamina() * stPct * seconds * 0.25f);
        }
    }

    /** The Apprentice (+100%) and the Atronach (-50%) adjust the core's magicka regeneration by this factor. */
    private static void magickaRegen(ServerPlayer player, long now, float factor) {
        PlayerData data = SkyData.get(player);
        if (now - data.getLastMagickaUseTick() <= 20) return;
        float max = data.maxMagicka();
        if (data.getMagicka() >= max) return;
        float pct = (float) (double) SkyConfig.MAGICKA_REGEN_PERCENT.get() / 100f;
        pct *= 1f + 0.25f * Perks.rank(player, "restoration.recovery");
        if (Vitals.inCombat(player)) pct *= 0.33f;
        data.setMagicka(data.getMagicka() + max * pct * 0.25f * factor);
    }

    /** The Lord resists magic, the Atronach absorbs some of it, the Apprentice is vulnerable to it. */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onHurt(com.skycraft.combat.BeforeArmorDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        StandingStone s = current(player);
        if (s == null || !MagicDamage.isWardable(event.getSource())) return;
        float amount = event.getAmount();
        switch (s) {
            case LORD -> event.setAmount(amount * 0.75f);
            case ATRONACH -> {
                float absorbed = amount * 0.3f;
                event.setAmount(amount - absorbed);
                PlayerData data = SkyData.get(player);
                data.setMagicka(data.getMagicka() + absorbed * 5f);
            }
            case APPRENTICE -> event.setAmount(amount * 1.5f);
            default -> {
            }
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) refresh(player);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) refresh(player);
    }

    // ------------------------------------------------------------------ powers

    /** The stone power key: the Ritual, Serpent, Shadow and Tower stones grant a power usable once a day. */
    public static void usePower(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator()) return;
        StandingStone s = current(player);
        if (s == null) {
            Notifier.message(player, Component.translatable("message.skycraft.lore.no_stone"));
            return;
        }
        if (!s.hasPower) {
            Notifier.message(player, Component.translatable("message.skycraft.lore.no_power", s.stoneName()));
            return;
        }
        PlayerData data = SkyData.get(player);
        CompoundTag tag = data.module(MODULE);
        String key = "power_" + s.id();
        long now = player.level().getGameTime();
        long ready = tag.getLongOr(key, 0L);
        if (now < ready) {
            String hours = com.skycraft.SkyConfig.formatTicks(ready - now);
            Notifier.message(player, Component.translatable("message.skycraft.lore.power_cooldown", s.powerName(), hours));
            return;
        }
        boolean used = switch (s) {
            case RITUAL -> ritual(player);
            case SERPENT -> serpent(player);
            case SHADOW -> shadow(player);
            case TOWER -> tower(player);
            default -> false;
        };
        if (!used) return;
        tag.putLong(key, now + com.skycraft.SkyConfig.powerCooldownTicks(data.getLevel()));
        data.markDirty();
        player.level().playSound(null, player.blockPosition(), SoundEvents.EVOKER_CAST_SPELL, SoundSource.PLAYERS, 1f, 0.7f);
        Notifier.message(player, Component.translatable("message.skycraft.lore.power_used", s.powerName()));
    }

    /** The Ritual: two corpses claw their way up and fight for you for a minute. */
    private static boolean ritual(ServerPlayer player) {
        ServerLevel level = player.level();
        long until = level.getGameTime() + 20 * 60;
        Vec3 look = player.getViewVector(1f).multiply(1, 0, 1);
        if (look.lengthSqr() < 1e-4) look = new Vec3(0, 0, 1);
        look = look.normalize();
        Vec3 side = new Vec3(-look.z, 0, look.x);
        int spawned = 0;
        for (int i = 0; i < 2; i++) {
            Zombie zombie = net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
            if (zombie == null) continue;
            Vec3 pos = player.position().add(look.scale(2)).add(side.scale(i == 0 ? -1.3 : 1.3));
            zombie.snapTo(pos.x, player.getY(), pos.z, player.getYRot(), 0f);
            if (!level.noCollision(zombie)) zombie.snapTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0f);
            zombie.setBaby(false);
            zombie.setCanPickUpLoot(false);
            zombie.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.FIRE_RESISTANCE), 20 * 90, 0, false, false));
            zombie.addTag(Summons.TAG);
            CompoundTag pd = zombie.getPersistentData();
            pd.store(Summons.OWNER, net.minecraft.core.UUIDUtil.CODEC,  player.getUUID());
            pd.putLong(Summons.UNTIL, until);
            pd.putString(Summons.KIND, "ritual");
            zombie.setPersistenceRequired();
            zombie.setCustomName(Component.translatable("entity.skycraft.lore.ritual_thrall"));
            level.addFreshEntity(zombie);
            level.sendParticles(ParticleTypes.SOUL, zombie.getX(), zombie.getY() + 0.6, zombie.getZ(), 16, 0.3, 0.5, 0.3, 0.03);
            level.sendParticles(ParticleTypes.LARGE_SMOKE, zombie.getX(), zombie.getY() + 0.2, zombie.getZ(), 10, 0.3, 0.1, 0.3, 0.01);
            spawned++;
        }
        if (spawned == 0) return false;
        level.playSound(null, player.blockPosition(), SoundEvents.ZOMBIE_VILLAGER_CURE, SoundSource.PLAYERS, 0.7f, 0.6f);
        return true;
    }

    /** The Serpent: paralyze and poison the creature you're looking at. */
    private static boolean serpent(ServerPlayer player) {
        Targeting.Ray ray = Targeting.ray(player, 24, e -> Targeting.canHarm(player, e));
        LivingEntity target = ray.entity();
        if (target == null) {
            Notifier.message(player, Component.translatable("message.skycraft.lore.serpent_no_target"));
            return false;
        }
        ServerLevel level = player.level();
        target.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(ModEffects.PARALYSIS.get()), 20 * 5, 0), player);
        target.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.POISON), 20 * 10, 1), player);
        com.skycraft.platform.Effects.hurt(target, player.damageSources().indirectMagic(player, player), 5f);
        if (target instanceof Mob mob) mob.setTarget(null);
        DustParticleOptions venom = new DustParticleOptions(net.minecraft.util.ARGB.colorFromFloat(1f, 0.35f, 0.95f, 0.55f), 1.2f);
        Vec3 from = player.getEyePosition().add(0, -0.3, 0);
        Vec3 to = target.position().add(0, target.getBbHeight() * 0.6, 0);
        Vec3 delta = to.subtract(from);
        int steps = (int) Math.max(1, delta.length() * 3);
        for (int i = 0; i <= steps; i++) {
            Vec3 p = from.add(delta.scale(i / (double) steps));
            level.sendParticles(venom, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0);
        }
        level.sendParticles(ParticleTypes.ITEM_SLIME, to.x, to.y, to.z, 20, 0.3, 0.4, 0.3, 0.05);
        level.playSound(null, target.blockPosition(), SoundEvents.SPIDER_HURT, SoundSource.PLAYERS, 1f, 0.6f);
        return true;
    }

    /** The Shadow: a minute of invisibility, and enemies lose track of you. */
    private static boolean shadow(ServerPlayer player) {
        player.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.INVISIBILITY), 20 * 60, 0, false, false, true));
        for (Mob mob : player.level().getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(16), m -> m.getTarget() == player)) {
            mob.setTarget(null);
        }
        player.level().sendParticles(ParticleTypes.LARGE_SMOKE, player.getX(), player.getY() + 1, player.getZ(), 30, 0.4, 0.7, 0.4, 0.02);
        return true;
    }

    /** The Tower: for two minutes, the next lock you try opens by itself (honoured by the crime module's locks). */
    private static boolean tower(ServerPlayer player) {
        Buffs.apply(player, TOWER_BUFF, 20 * 120);
        player.level().sendParticles(ParticleTypes.ENCHANT, player.getX(), player.getY() + 1.2, player.getZ(), 50, 0.5, 0.6, 0.5, 0.6);
        Notifier.message(player, Component.translatable("message.skycraft.lore.tower_ready"));
        return true;
    }
}
