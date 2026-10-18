package com.skycraft.arsenal;

import com.skycraft.Skycraft;
import com.skycraft.arsenal.item.ArtifactBowItem;
import com.skycraft.arsenal.item.ArtifactItem;
import com.skycraft.arsenal.item.SpellbreakerItem;
import com.skycraft.combat.CombatHandler;
import com.skycraft.core.Notifier;
import com.skycraft.core.PlayerData;
import com.skycraft.core.Race;
import com.skycraft.core.SkyData;
import com.skycraft.core.Skill;
import com.skycraft.crafting.SoulGems;
import com.skycraft.registry.ModEffects;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.Map;
import java.util.WeakHashMap;

/** The powers of the named artifacts. Everything is decided on the server. */
@net.neoforged.fml.common.EventBusSubscriber(modid = Skycraft.MODID)
public final class ArtifactEvents {
    /** Victim persistent data: Mace of Molag Bal's soul trap window (game time) and the wielder. */
    public static final String SOUL_TRAP_UNTIL = "skycraft_molag_trap_until";
    public static final String SOUL_TRAP_BY = "skycraft_molag_trap_by";
    private static final Identifier MAGIC_SOUL_TRAP = Identifier.fromNamespaceAndPath(Skycraft.MODID, "soul_trap");
    private static final Map<Player, Long> LAST_BLADE = new WeakHashMap<>();

    private ArtifactEvents() {}

    private static String artifactOf(ItemStack stack) {
        return stack.getItem() instanceof ArtifactItem a ? a.artifactId() : null;
    }

    // ------------------------------------------------------------------ hits

    @SubscribeEvent
    public static void onHurt(com.skycraft.combat.BeforeArmorDamageEvent event) {
        LivingEntity target = event.getEntity();
        if (target.level().isClientSide() || !(target.level() instanceof ServerLevel level)) return;
        DamageSource source = event.getSource();
        Entity direct = source.getDirectEntity();
        float amount = event.getAmount();

        if (direct instanceof AbstractArrow arrow) {
            String id = arrow.getPersistentData().getStringOr(ArtifactBowItem.ARROW_TAG, "");
            if (!id.isEmpty()) event.setAmount(arrowPower(id, arrow, target, level, amount));
            return;
        }
        if (!(source.getEntity() instanceof LivingEntity attacker) || direct != attacker) return;
        String id = artifactOf(attacker.getMainHandItem());
        if (id == null) return;
        var random = attacker.getRandom();
        boolean boss = target.getType().builtInRegistryHolder().is(CombatHandler.BOSSES);
        Vec3 mid = target.position().add(0, target.getBbHeight() * 0.6, 0);

        switch (id) {
            case "dawnbreaker" -> {
                if (!target.fireImmune()) {
                    amount += 2f;
                    target.igniteForSeconds(3);
                }
                if (target.getType().builtInRegistryHolder().is(ArsenalTags.UNDEAD)) amount += 2f;
                level.sendParticles(ParticleTypes.FLAME, mid.x, mid.y, mid.z, 10, 0.25, 0.4, 0.25, 0.03);
            }
            case "chillrend" -> {
                amount += 2f;
                target.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.SLOWNESS), 60, 1));
                target.setTicksFrozen(Math.max(target.getTicksFrozen(), target.getTicksRequiredToFreeze() + 40));
                if (!boss && random.nextFloat() < 0.15f) {
                    target.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(ModEffects.PARALYSIS.get()), 40, 0));
                    level.sendParticles(ParticleTypes.ENCHANTED_HIT, mid.x, mid.y, mid.z, 12, 0.3, 0.4, 0.3, 0.1);
                }
                level.sendParticles(ParticleTypes.SNOWFLAKE, mid.x, mid.y, mid.z, 14, 0.3, 0.4, 0.3, 0.05);
            }
            case "mehrunes_razor" -> {
                if (!boss && !(target instanceof Player) && random.nextFloat() < 0.02f) {
                    amount = Math.max(amount, (target.getHealth() + target.getAbsorptionAmount()) * 20f + 1000f);
                    level.sendParticles(ParticleTypes.SOUL, mid.x, mid.y, mid.z, 20, 0.3, 0.5, 0.3, 0.05);
                    level.playSound(null, target.blockPosition(), SoundEvents.WITHER_HURT, SoundSource.PLAYERS, 0.8f, 1.6f);
                    if (attacker instanceof ServerPlayer sp) {
                        Notifier.message(sp, Component.translatable("message.skycraft.arsenal.mehrunes_razor"));
                    }
                }
            }
            case "dragonbane" -> {
                amount += 1.5f;
                if (target.getType().builtInRegistryHolder().is(ArsenalTags.DRAGONS)) amount *= 2.5f;
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, mid.x, mid.y, mid.z, 10, 0.3, 0.4, 0.3, 0.15);
            }
            case "volendrung" -> {
                float drained = 15f;
                if (target instanceof Player victim) {
                    PlayerData vd = SkyData.get(victim);
                    drained = Math.min(vd.getStamina(), 25f);
                    vd.setStamina(vd.getStamina() - drained);
                } else {
                    target.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.WEAKNESS), 60, 0));
                }
                if (attacker instanceof Player p) {
                    PlayerData d = SkyData.get(p);
                    d.setStamina(d.getStamina() + drained);
                }
                level.sendParticles(ParticleTypes.HAPPY_VILLAGER, mid.x, mid.y, mid.z, 6, 0.3, 0.4, 0.3, 0);
            }
            case "ebony_blade" -> {
                attacker.heal(Math.max(1f, amount * 0.25f));
                level.sendParticles(ParticleTypes.DAMAGE_INDICATOR, mid.x, mid.y, mid.z, 4, 0.2, 0.3, 0.2, 0.05);
            }
            case "mace_of_molag_bal" -> {
                if (target instanceof Player victim) {
                    PlayerData vd = SkyData.get(victim);
                    vd.setMagicka(vd.getMagicka() - 15f);
                    vd.setStamina(vd.getStamina() - 15f);
                } else {
                    target.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.WEAKNESS), 80, 0));
                    target.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.SLOWNESS), 40, 0));
                }
                if (attacker instanceof ServerPlayer sp) {
                    CompoundTag pd = target.getPersistentData();
                    pd.putLong(SOUL_TRAP_UNTIL, level.getGameTime() + 60);
                    pd.store(SOUL_TRAP_BY, net.minecraft.core.UUIDUtil.CODEC,  sp.getUUID());
                }
                level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, mid.x, mid.y, mid.z, 8, 0.3, 0.4, 0.3, 0.02);
            }
            case "wuuthrad" -> {
                if (target.getType().builtInRegistryHolder().is(CombatHandler.MONSTERS)) amount *= 1.2f;
                if (target instanceof Player victim) {
                    Race race = SkyData.get(victim).getRace();
                    if (race == Race.ALTMER || race == Race.BOSMER || race == Race.DUNMER) amount *= 1.5f;
                }
            }
            case "windshear" -> {
                if (random.nextFloat() < 0.35f) CombatHandler.stagger(attacker, target, 20);
            }
            default -> {
            }
        }
        event.setAmount(amount);
    }

    private static float arrowPower(String id, AbstractArrow arrow, LivingEntity target, ServerLevel level, float amount) {
        Vec3 mid = target.position().add(0, target.getBbHeight() * 0.6, 0);
        switch (id) {
            case "auriels_bow" -> {
                if (target.getType().builtInRegistryHolder().is(ArsenalTags.UNDEAD)) {
                    amount *= 2f;
                    target.igniteForSeconds(5);
                } else {
                    amount += 1.5f;
                }
                ArsenalPackets.Fx.send(level, ArsenalPackets.Fx.SUN_BURST, mid, mid);
            }
            case "nightingale_bow" -> {
                amount += 2f;
                target.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.SLOWNESS), 60, 1));
                if (target instanceof Player p) {
                    PlayerData d = SkyData.get(p);
                    d.setMagicka(d.getMagicka() - 10f);
                }
                level.sendParticles(ParticleTypes.SNOWFLAKE, mid.x, mid.y, mid.z, 8, 0.3, 0.4, 0.3, 0.05);
                level.sendParticles(ParticleTypes.ELECTRIC_SPARK, mid.x, mid.y, mid.z, 8, 0.3, 0.4, 0.3, 0.15);
            }
            default -> {
            }
        }
        return amount;
    }

    // ------------------------------------------------------------------ deaths

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onDeath(LivingDeathEvent event) {
        LivingEntity victim = event.getEntity();
        if (!(victim.level() instanceof ServerLevel level)) return;
        DamageSource source = event.getSource();

        // Mace of Molag Bal: soul trap for 3 seconds after each hit
        CompoundTag pd = victim.getPersistentData();
        if (pd.contains(SOUL_TRAP_UNTIL) && level.getGameTime() <= pd.getLongOr(SOUL_TRAP_UNTIL, 0L) && pd.read(SOUL_TRAP_BY, net.minecraft.core.UUIDUtil.CODEC).isPresent()) {
            MobEffect magicTrap = BuiltInRegistries.MOB_EFFECT.getValue(MAGIC_SOUL_TRAP);
            boolean magicWillCapture = magicTrap != null && victim.hasEffect(com.skycraft.platform.Effects.holder(magicTrap));
            if (!magicWillCapture && level.getPlayerByUUID(pd.read(SOUL_TRAP_BY, net.minecraft.core.UUIDUtil.CODEC).orElseThrow()) instanceof ServerPlayer owner) {
                SoulGems.tryCaptureSoul(owner, victim);
            }
            pd.remove(SOUL_TRAP_UNTIL);
        }

        // Dawnbreaker: undead may explode in a burst of Meridia's light
        if (source.getEntity() instanceof LivingEntity killer && source.getDirectEntity() == killer
                && ArtifactItem.is(killer.getMainHandItem(), "dawnbreaker") && victim.getType().builtInRegistryHolder().is(ArsenalTags.UNDEAD)
                && killer.getRandom().nextFloat() < 0.3f) {
            Vec3 pos = victim.position().add(0, victim.getBbHeight() * 0.5, 0);
            ArsenalPackets.Fx.send(level, ArsenalPackets.Fx.SUN_BURST, pos, pos);
            ArsenalPackets.Fx.send(level, ArsenalPackets.Fx.FIRE_BURST, pos, pos);
            level.playSound(null, victim.blockPosition(), SoundEvents.GENERIC_EXPLODE.value(), SoundSource.PLAYERS, 0.8f, 1.5f);
            level.playSound(null, victim.blockPosition(), SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 1f, 1.4f);
            for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, new AABB(pos, pos).inflate(6),
                    e -> e != victim && e != killer && e.isAlive() && e.getType().builtInRegistryHolder().is(ArsenalTags.UNDEAD))) {
                com.skycraft.platform.Effects.hurt(e, StaffEffects.fire(level, null, killer), 8f);
                e.igniteForSeconds(5);
            }
            if (killer instanceof ServerPlayer sp) Notifier.message(sp, Component.translatable("message.skycraft.arsenal.meridia"));
        }
    }

    // ------------------------------------------------------------------ Bloodskal Blade

    /** Full-strength Bloodskal swings that hit a creature also release the energy blade. */
    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && ArtifactItem.is(player.getMainHandItem(), "bloodskal_blade")
                && player.getAttackStrengthScale(0.5f) >= 0.9f) {
            bloodskalSwing(player);
        }
    }

    /** Fires Bloodskal's energy blade (rate-limited; called for hits and from the client's swing packet). */
    public static void bloodskalSwing(ServerPlayer player) {
        if (!player.isAlive() || player.isSpectator() || !ArtifactItem.is(player.getMainHandItem(), "bloodskal_blade")) return;
        long now = player.level().getGameTime();
        Long last = LAST_BLADE.get(player);
        if (last != null && now - last < 16 && now >= last) return;
        LAST_BLADE.put(player, now);
        float power = 1f + SkyData.get(player).getSkill(Skill.TWO_HANDED) * 0.006f;
        StaffEffects.energyBlade(player, power);
    }

    // ------------------------------------------------------------------ bows

    /** Zephyr draws faster; the Bow of Shadows hides its wielder while drawing. */
    @SubscribeEvent
    public static void onUseTick(LivingEntityUseItemEvent.Tick event) {
        String id = artifactOf(event.getItem());
        if (id == null) return;
        LivingEntity user = event.getEntity();
        if ("zephyr".equals(id) && user.tickCount % 3 == 0) {
            event.setDuration(event.getDuration() - 1);
        } else if ("bow_of_shadows".equals(id) && !user.level().isClientSide() && user.tickCount % 10 == 0) {
            user.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.INVISIBILITY), 30, 0, false, false));
        }
    }

    // ------------------------------------------------------------------ Spellbreaker

    /** Blocking with Spellbreaker raises a ward that stops spells and magical damage from the front. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onAttacked(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Player player) || player.level().isClientSide()) return;
        if (!player.isBlocking() || !(player.getUseItem().getItem() instanceof SpellbreakerItem)) return;
        DamageSource source = event.getSource();
        if (!magical(source)) return;
        Vec3 from = source.getSourcePosition();
        if (from != null) {
            Vec3 look = player.getViewVector(1f).multiply(1, 0, 1).normalize();
            Vec3 dir = from.subtract(player.position()).multiply(1, 0, 1).normalize();
            if (look.dot(dir) < 0) return;
        }
        event.setCanceled(true);
        if (player.level() instanceof ServerLevel level) {
            Vec3 front = player.getEyePosition().add(player.getViewVector(1f).scale(0.8));
            ArsenalPackets.Fx.send(level, ArsenalPackets.Fx.WARD, front, player.getViewVector(1f));
            level.playSound(null, player.blockPosition(), SoundEvents.AMETHYST_BLOCK_HIT, SoundSource.PLAYERS, 1f, 0.6f);
        }
        if (player instanceof ServerPlayer sp) com.skycraft.skills.Progression.addSkillXp(sp, Skill.BLOCK, event.getAmount() * 0.5f);
    }

    private static boolean magical(DamageSource source) {
        if (source.is(DamageTypes.MAGIC) || source.is(DamageTypes.INDIRECT_MAGIC) || source.is(DamageTypes.DRAGON_BREATH)
                || source.is(DamageTypes.WITHER_SKULL) || source.is(DamageTypes.SONIC_BOOM) || source.is(DamageTypes.LIGHTNING_BOLT)) {
            return true;
        }
        if (source.is(DamageTypeTags.IS_FIRE) && source.getEntity() != null) return true;
        if (source.is(DamageTypeTags.IS_FREEZING) && source.getEntity() != null) return true;
        return source.typeHolder().unwrapKey()
                .map(k -> k.identifier().getNamespace().equals(Skycraft.MODID) && k.identifier().getPath().endsWith("spell"))
                .orElse(false);
    }

    /** Ward shimmer while Spellbreaker is raised. */
    @SubscribeEvent
    public static void onPlayerTick(net.neoforged.neoforge.event.tick.PlayerTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.PlayerTickEvent.Post) || !(event.getEntity() instanceof ServerPlayer player) || player.tickCount % 8 != 0) return;
        if (player.isBlocking() && player.getUseItem().getItem() instanceof SpellbreakerItem && player.level() instanceof ServerLevel level) {
            Vec3 front = player.getEyePosition().add(player.getViewVector(1f).scale(0.8)).add(0, -0.3, 0);
            level.sendParticles(ParticleTypes.ENCHANT, front.x, front.y, front.z, 6, 0.4, 0.4, 0.4, 0.2);
        }
    }
}
