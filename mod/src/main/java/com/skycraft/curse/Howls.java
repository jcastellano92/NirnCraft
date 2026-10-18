package com.skycraft.curse;

import com.skycraft.combat.CombatHandler;
import com.skycraft.core.Notifier;
import com.skycraft.magic.spell.Summons;
import com.skycraft.society.entity.NpcEntity;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.wolf.Wolf;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * The werewolf's howls (the Voice key in Beast Form): Howl of Terror (enemies flee), and with perks the Totems of the
 * Predator (sense the living), Ice Brothers (two ice wolves) and the Moon (two werewolves answer). Also the fear
 * shared with the Vampire Lord's and the creatures' roars.
 */
public final class Howls {
    /** Feared mobs: entity UUID -> {until game time, source x, source z}. */
    private static final Map<UUID, double[]> FEARED = new HashMap<>();

    private Howls() {}

    static void howl(ServerPlayer player, String id) {
        if (!CursePowers.hasAbility(player, id) || !CursePowers.ready(player, id)) return;
        ServerLevel level = player.level();
        CursePackets.anim(player, CurseAnims.Anim.HOWL);
        level.playSound(null, player.blockPosition(), com.skycraft.audio.AudioSounds.creature("wolf", "howl"), SoundSource.PLAYERS, 2.0f, 0.5f);
        level.sendParticles(ParticleTypes.SONIC_BOOM, player.getX(), player.getEyeY(), player.getZ(), 1, 0, 0, 0, 0);
        switch (id) {
            case CursePowers.HOWL_OF_TERROR -> {
                boolean totem = CursePerks.has(player, CursePerks.TOTEM_OF_TERROR);
                terrify(player, totem ? 20 : 14, 20 * (totem ? 30 : 15), totem);
            }
            case CursePowers.HOWL_PREDATOR -> {
                for (LivingEntity e : level.getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(64), e -> e != player && e.isAlive())) {
                    e.addEffect(new MobEffectInstance(com.skycraft.platform.Effects.holder(MobEffects.GLOWING), 20 * 30, 0, false, false));
                }
                Notifier.message(player, Component.translatable("curse.skycraft.howl.predator"));
            }
            case CursePowers.HOWL_ICE_BROTHERS -> {
                for (int i = 0; i < 2; i++) {
                    Mob wolf = Summons.conjureAlly(player, "familiar", "ice_wolf", 6, 20 * 60, m -> {
                        AttributeInstance hp = m.getAttribute(Attributes.MAX_HEALTH);
                        if (hp != null) hp.setBaseValue(Math.max(hp.getBaseValue(), 30));
                        AttributeInstance atk = m.getAttribute(Attributes.ATTACK_DAMAGE);
                        if (atk != null) atk.setBaseValue(Math.max(atk.getBaseValue(), 6));
                        m.setHealth(m.getMaxHealth());
                        if (m instanceof Wolf w) w.setComponent(net.minecraft.core.component.DataComponents.WOLF_COLLAR, net.minecraft.world.item.DyeColor.LIGHT_BLUE);
                    });
                    if (wolf != null) level.sendParticles(ParticleTypes.SNOWFLAKE, wolf.getX(), wolf.getY() + 0.5, wolf.getZ(), 20, 0.4, 0.4, 0.4, 0.02);
                }
            }
            case CursePowers.HOWL_MOON -> {
                for (int i = 0; i < 2; i++) {
                    Mob wolf = Summons.conjureAt(player, CurseRegistry.WEREWOLF.get(), "werewolf_brother",
                            player.position().add(player.getRandom().nextGaussian() * 3, 0, player.getRandom().nextGaussian() * 3), 20 * 60, m -> {});
                    if (wolf != null) level.sendParticles(ParticleTypes.LARGE_SMOKE, wolf.getX(), wolf.getY() + 1, wolf.getZ(), 12, 0.4, 0.8, 0.4, 0.02);
                }
            }
            default -> {
                return;
            }
        }
        CursePowers.startCooldown(player, id);
    }

    /**
     * Enemies within {@code radius} run from {@code source} for {@code ticks} (bosses and dragons stand their ground,
     * unless {@code strong}: then only bosses).
     */
    public static void terrify(LivingEntity source, double radius, int ticks, boolean strong) {
        if (!(source.level() instanceof ServerLevel level)) return;
        long until = level.getGameTime() + ticks;
        for (Mob mob : level.getEntitiesOfClass(Mob.class, source.getBoundingBox().inflate(radius), m -> m != source && m.isAlive()
                && !m.getType().builtInRegistryHolder().is(CombatHandler.BOSSES) && Summons.ownerOf(m) == null)) {
            if (!strong && mob.getMaxHealth() > 120) continue;
            if (mob.getTarget() == source) mob.setTarget(null);
            if (mob instanceof NpcEntity npc && source instanceof Player p) npc.setFearedPlayer(p.getUUID());
            FEARED.put(mob.getUUID(), new double[]{until, source.getX(), source.getZ()});
            flee(mob, source.getX(), source.getZ());
        }
    }

    private static void flee(Mob mob, double fromX, double fromZ) {
        if (!(mob instanceof PathfinderMob pm)) {
            Vec3 away = mob.position().subtract(fromX, mob.getY(), fromZ).normalize().scale(0.4);
            mob.setDeltaMovement(mob.getDeltaMovement().add(away.x, 0, away.z));
            return;
        }
        Vec3 pos = DefaultRandomPos.getPosAway(pm, 16, 7, new Vec3(fromX, mob.getY(), fromZ));
        if (pos != null) pm.getNavigation().moveTo(pos.x, pos.y, pos.z, 1.4);
    }

    /** Keeps the feared running, every half second (server tick). */
    static void tick(net.minecraft.server.MinecraftServer server) {
        if (FEARED.isEmpty() || server.getTickCount() % 10 != 0) return;
        long now = server.overworld().getGameTime();
        for (Iterator<Map.Entry<UUID, double[]>> it = FEARED.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, double[]> e = it.next();
            double[] f = e.getValue();
            if (now > f[0]) {
                it.remove();
                continue;
            }
            Mob mob = null;
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getEntity(e.getKey()) instanceof Mob m) {
                    mob = m;
                    break;
                }
            }
            if (mob == null || !mob.isAlive()) {
                it.remove();
                continue;
            }
            mob.setTarget(null);
            if (mob.getNavigation().isDone()) flee(mob, f[1], f[2]);
        }
    }

    static void clear() {
        FEARED.clear();
    }

    public static boolean feared(Mob mob) {
        double[] f = FEARED.get(mob.getUUID());
        return f != null && mob.level().getGameTime() <= f[0];
    }
}
