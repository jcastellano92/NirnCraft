package com.nirncraft.society;

import com.nirncraft.NirnCraft;
import com.nirncraft.core.Currency;
import com.nirncraft.creatures.spawn.SpawnPacing;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.gamerules.GameRules;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The scripted highway robbery that replaced bandits charging out of the woods: a few bandits step out ahead of the
 * player, stand still and make a demand with a ten-second clock. The player chooses (clickable chat options that run
 * {@code /nirncraft-ambush <choice>}): pay, persuade, flee or fight; when the clock runs out, or the player strikes
 * first, the bandits attack. Rare, never near a settlement or the world spawn, never inside the hostile pacing's grace
 * period or cooldown (it is a hostile event like any other, see {@link SpawnPacing}), and the bandits are ordinary
 * encounter mobs ({@link Encounters#TAG}) so the usual despawn rules clean them up.
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = NirnCraft.MODID)
public final class Ambush {
    /** The decision window. */
    public static final int TIMER_TICKS = 200;
    /** Flee: how long the bandits take to react (a head start). */
    private static final int HEAD_START_TICKS = 60;
    /** Persuade difficulty (a Speech skill value; see {@link SpeechChecks}). */
    private static final int PERSUADE_DIFFICULTY = 45;
    /** Blocks (from the settlement footprint) inside which no ambush happens. */
    private static final int TOWN_CLEARANCE = 260;
    public static final String TAG_AMBUSH = "nirncraft_ambush";

    private static final class State {
        UUID player;
        final List<UUID> mobs = new ArrayList<>();
        int demand;
        long start;
        long attackAt;
    }

    private static final Map<UUID, State> ACTIVE = new HashMap<>();
    /** Bandits sent away after being paid or talked round: id -> game time they vanish. */
    private static final Map<UUID, Long> LEAVING = new HashMap<>();

    private Ambush() {}

    public static boolean active(ServerPlayer player) {
        return ACTIVE.containsKey(player.getUUID());
    }

    // ------------------------------------------------------------------ starting

    /** Whether an ambush may begin around this player now (pacing, distance from towns, wilderness, bandits exist). */
    public static boolean eligible(ServerPlayer player) {
        ServerLevel level = player.level();
        if (!SocietyConfig.ENCOUNTERS.get() || !SocietyConfig.HOSTILE_ENCOUNTERS.get()) return false;
        if (level.getDifficulty() == Difficulty.PEACEFUL || !level.getGameRules().get(GameRules.SPAWN_MOBS)) return false;
        if (player.isCreative() || player.isSpectator() || ACTIVE.containsKey(player.getUUID())) return false;
        if (!Encounters.exists(Encounters.BANDIT) || !Encounters.wilderness(player)) return false;
        if (!SpawnPacing.hostileReady(player)) return false; // grace period after login/respawn and the shared cooldown
        BlockPos at = player.blockPosition();
        return !SpawnPacing.calm(level, at) && !SpawnPacing.settlementWithin(level, at, TOWN_CLEARANCE);
    }

    /** Starts an ambush if one may happen; used by the region spawner in place of a bandit group. */
    public static boolean tryStart(ServerPlayer player) {
        if (!eligible(player)) return false;
        boolean ok = start(player);
        if (ok) SpawnPacing.noteHostile(player);
        return ok;
    }

    /** Places the bandits ahead of the player and makes the demand (also the forced path of the encounter command). */
    public static boolean start(ServerPlayer player) {
        ServerLevel level = player.level();
        if (!Encounters.exists(Encounters.BANDIT) || ACTIVE.containsKey(player.getUUID())) return false;
        RandomSource r = player.getRandom();
        int n = 2 + r.nextInt(2);
        // ahead of where the player is heading, so it reads as someone stepping into the road
        double look = Math.toRadians(player.getYRot() + 90.0);
        Encounters.Group g = new Encounters.Group(level, player, player.blockPosition());
        State st = new State();
        st.player = player.getUUID();
        Mob leader = null;
        for (int i = 0; i < n; i++) {
            double a = look + (i - (n - 1) / 2.0) * 0.55 + (r.nextDouble() - 0.5) * 0.2;
            double dist = 13 + r.nextInt(6);
            BlockPos ground = Encounters.groundAt(level, Mth.floor(player.getX() + Math.cos(a) * dist), Mth.floor(player.getZ() + Math.sin(a) * dist));
            if (ground == null || Math.abs(ground.getY() - player.getBlockY()) > 6) continue;
            Mob bandit = g.mob(Encounters.BANDIT, ground);
            if (bandit == null) continue;
            bandit.setNoAi(true);
            net.minecraft.nbt.CompoundTag t = Encounters.tag(bandit);
            if (t != null) t.putBoolean(TAG_AMBUSH, true);
            bandit.getPersistentData().putBoolean(TAG_AMBUSH, true);
            face(bandit, player);
            st.mobs.add(bandit.getUUID());
            if (leader == null) leader = bandit;
        }
        if (leader == null) return false;
        long bal = Currency.balance(player);
        st.demand = (int) Math.max(15, Math.min(250, bal / 4));
        st.start = com.nirncraft.world.RestManager.liveTime(level);
        st.attackAt = st.start + TIMER_TICKS;
        ACTIVE.put(player.getUUID(), st);
        Barks.say(leader, Component.translatable("society.nirncraft.ambush.demand", st.demand));
        com.nirncraft.dialogue.Dialogue.open(player, leader, Component.translatable("society.nirncraft.ambush.demand", st.demand));
        return true;
    }

    public static int demandFor(ServerPlayer player) {
        State st = ACTIVE.get(player.getUUID());
        return st != null ? st.demand : 25;
    }

    private static MutableComponent option(String id, Component label, ChatFormatting color) {
        Style s = Style.EMPTY.withColor(color)
                .withClickEvent(new ClickEvent.RunCommand("/nirncraft-ambush " + id))
                .withHoverEvent(new HoverEvent.ShowText( Component.translatable("society.nirncraft.ambush.hover." + id)));
        return Component.empty().append(label).withStyle(s);
    }

    private static Component prompt(int demand) {
        return Component.translatable("society.nirncraft.ambush.prompt").withStyle(ChatFormatting.RED)
                .append(Component.literal("  "))
                .append(option("pay", Component.translatable("society.nirncraft.ambush.opt.pay", demand), ChatFormatting.GOLD))
                .append(Component.literal(" "))
                .append(option("persuade", Component.translatable("society.nirncraft.ambush.opt.persuade"), ChatFormatting.AQUA))
                .append(Component.literal(" "))
                .append(option("flee", Component.translatable("society.nirncraft.ambush.opt.flee"), ChatFormatting.YELLOW))
                .append(Component.literal(" "))
                .append(option("fight", Component.translatable("society.nirncraft.ambush.opt.fight"), ChatFormatting.RED));
    }

    // ------------------------------------------------------------------ the player's answer

    public static void choose(ServerPlayer player, String choice) {
        State st = ACTIVE.get(player.getUUID());
        if (st == null) {
            com.nirncraft.core.Notifier.message(player, Component.translatable("society.nirncraft.ambush.none"));
            return;
        }
        ServerLevel level = player.level();
        switch (choice) {
            case "pay" -> {
                if (!Currency.take(player, st.demand)) {
                    com.nirncraft.core.Notifier.message(player, Component.translatable("society.nirncraft.ambush.poor", st.demand));
                    return;
                }
                say(level, st, Component.translatable("society.nirncraft.ambush.paid"));
                release(level, st);
            }
            case "persuade" -> {
                if (SpeechChecks.checkPersuade(player, PERSUADE_DIFFICULTY)) {
                    say(level, st, Component.translatable("society.nirncraft.ambush.talked"));
                    release(level, st);
                } else {
                    say(level, st, Component.translatable("society.nirncraft.ambush.attack"));
                    attack(level, player, st);
                }
            }
            case "flee" -> {
                st.attackAt = Math.min(st.attackAt, com.nirncraft.world.RestManager.liveTime(level) + HEAD_START_TICKS);
                com.nirncraft.core.Notifier.message(player, Component.translatable("society.nirncraft.ambush.fled"));
            }
            case "fight" -> attack(level, player, st);
            default -> com.nirncraft.core.Notifier.message(player, Component.translatable("society.nirncraft.ambush.none"));
        }
    }

    // ------------------------------------------------------------------ resolution

    private static List<Mob> mobs(ServerLevel level, State st) {
        List<Mob> out = new ArrayList<>();
        for (UUID id : st.mobs) {
            Entity e = level.getEntity(id);
            if (e instanceof Mob m && m.isAlive()) out.add(m);
        }
        return out;
    }

    private static void say(ServerLevel level, State st, Component text) {
        List<Mob> m = mobs(level, st);
        if (!m.isEmpty()) Barks.say(m.get(0), text);
    }

    private static void face(Mob mob, Entity target) {
        double dx = target.getX() - mob.getX(), dz = target.getZ() - mob.getZ();
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        mob.setYRot(yaw);
        mob.setYHeadRot(yaw);
        mob.yBodyRot = yaw;
    }

    /** The bandits attack: AI back on, the player is their prey and they follow it. */
    private static void attack(ServerLevel level, ServerPlayer player, State st) {
        ACTIVE.remove(st.player);
        for (Mob m : mobs(level, st)) {
            m.setNoAi(false);
            m.getPersistentData().remove(TAG_AMBUSH);
            net.minecraft.nbt.CompoundTag t = Encounters.tag(m);
            if (t != null) t.remove(TAG_AMBUSH);
            Encounters.tagHunt(m, player.getUUID());
            m.setTarget(player);
        }
    }

    /** The bandits take what they were given (or lose their nerve) and walk away. */
    private static void release(ServerLevel level, State st) {
        ACTIVE.remove(st.player);
        long gone = com.nirncraft.world.RestManager.liveTime(level) + 100;
        ServerPlayer player = level.getServer().getPlayerList().getPlayer(st.player);
        for (Mob m : mobs(level, st)) {
            m.setNoAi(false);
            m.getPersistentData().remove(TAG_AMBUSH);
            net.minecraft.nbt.CompoundTag t = Encounters.tag(m);
            if (t != null) t.remove(TAG_AMBUSH);
            if (player != null) {
                double dx = m.getX() - player.getX(), dz = m.getZ() - player.getZ();
                double len = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
                m.getNavigation().moveTo(m.getX() + dx / len * 30, m.getY(), m.getZ() + dz / len * 30, 1.0D);
            }
            LEAVING.put(m.getUUID(), gone);
        }
    }

    /** Drops an ambush whose player is gone: the bandits simply vanish. */
    private static void abandon(ServerLevel level, State st) {
        ACTIVE.remove(st.player);
        for (Mob m : mobs(level, st)) m.discard();
    }

    // ------------------------------------------------------------------ clock

    @SubscribeEvent
    public static void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        if (!(event instanceof net.neoforged.neoforge.event.tick.LevelTickEvent.Post) || !(event.getLevel() instanceof ServerLevel level)) return;
        long now = com.nirncraft.world.RestManager.liveTime(level);
        if (now % 10 != 0) return;
        if (!LEAVING.isEmpty()) {
            Iterator<Map.Entry<UUID, Long>> it = LEAVING.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Long> e = it.next();
                if (now < e.getValue()) continue;
                Entity m = level.getEntity(e.getKey());
                if (m != null) {
                    level.sendParticles(ParticleTypes.POOF, m.getX(), m.getY() + 0.8, m.getZ(), 6, 0.3, 0.5, 0.3, 0.02);
                    m.discard();
                }
                it.remove();
            }
        }
        if (ACTIVE.isEmpty()) return;
        for (State st : new ArrayList<>(ACTIVE.values())) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(st.player);
            if (player == null || !player.isAlive() || player.isSpectator()) {
                if (player == null || player.level() == level) abandon(level, st);
                continue;
            }
            if (player.level() != level) continue;
            List<Mob> bandits = mobs(level, st);
            if (bandits.isEmpty()) {
                ACTIVE.remove(st.player);
                continue;
            }
            Mob first = bandits.get(0);
            // walked away from them, or they were struck: they stop waiting
            if (first.distanceToSqr(player) > 70.0 * 70.0) {
                abandon(level, st);
                continue;
            }
            boolean struck = false;
            for (Mob m : bandits) {
                if (m.getHealth() < m.getMaxHealth() || m.getLastHurtByMob() != null) struck = true;
                face(m, player);
            }
            if (struck || now >= st.attackAt) {
                if (!struck) say(level, st, Component.translatable("society.nirncraft.ambush.attack"));
                attack(level, player, st);
                continue;
            }
        }
    }

    /** Stale ambush bandits (left over from before a restart, no clock running) are cleared when they load. */
    @SubscribeEvent
    public static void onJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Mob mob)) return;
        if (!mob.getPersistentData().getBooleanOr(TAG_AMBUSH, false)) return;
        for (State st : ACTIVE.values()) if (st.mobs.contains(mob.getUUID())) return;
        mob.discard();
    }
}
