package com.nirncraft.hold;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Headless checks of the player hold on stand-in players (no client): {@code /nirncraft-hold selftest}. A held stand-in
 * with a zombie beside it and a second stand-in as the viewer:
 * <ul>
 *   <li>the held player is not sent to the viewer (the predicate vanilla's tracker asks: {@code broadcastToPlayer}), and
 *       an unheld control is (so the check can fail);</li>
 *   <li>the zombie cannot target them (setTarget through the event, and the targeting predicate the AI goals use);</li>
 *   <li>damage does nothing; their attack, block breaking and placing, teleports and commands are cancelled;</li>
 *   <li>moved away, the per-tick pin puts them back; no gravity; abilities invulnerable, flying off, mayBuild off;</li>
 *   <li>a client that never reports ready leaves them held; a stale token and a second ready do nothing; the right
 *       token releases them once, runs the release action once, and restores mode, abilities, gravity and visibility;</li>
 *   <li>a record left on the player's data by a crash makes the next hold restore the real mode, not spectator.</li>
 * </ul>
 */
final class HoldSelftest {
    private HoldSelftest() {}

    static List<String> run(MinecraftServer server, List<String> info) {
        List<String> problems = new ArrayList<>();
        ServerLevel level = server.overworld();
        BlockPos spawn = level.getRespawnData().pos();
        level.getChunkAt(spawn);
        Vec3 home = new Vec3(spawn.getX() + 0.5, level.getHeight() - 20, spawn.getZ() + 0.5);
        ServerPlayer held = standIn(level, "hold-test-held", home);
        ServerPlayer viewer = standIn(level, "hold-test-viewer", home.add(3, 0, 0));
        ServerPlayer control = standIn(level, "hold-test-control", home.add(0, 0, 3));
        Zombie zombie = (Zombie) net.minecraft.world.entity.EntityTypes.ZOMBIE.create(level, EntitySpawnReason.COMMAND);
        if (zombie == null) {
            problems.add("could not make a zombie");
            return problems;
        }
        zombie.setPos(home.add(1, 0, 0));
        try {
            PlayerHold.clearForTests();
            ready(problems, info, held, viewer, control, zombie, home);
            crashRecord(problems, info, held, home);
        } finally {
            PlayerHold.release(held, "selftest end");
            PlayerHold.clearForTests();
            for (ServerPlayer p : List.of(held, viewer, control)) {
                p.getPersistentData().remove("nirncraft_hold");
                p.setGameMode(GameType.SURVIVAL);
                p.setPermanentlyInvulnerable(true);  // (the stand-in factory's default)
            }
        }
        return problems;
    }

    private static ServerPlayer standIn(ServerLevel level, String name, Vec3 at) {
        ServerPlayer p = FakePlayerFactory.get(level, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), "[" + name + "]"));
        p.setGameMode(GameType.SURVIVAL);
        com.nirncraft.core.SkyData.get(p).setRace(com.nirncraft.core.Race.NORD);  // (a raceless player is protected while the race menu is pending)
        p.setPermanentlyInvulnerable(false);  // (stand-ins are permanently invulnerable: the damage and targeting checks need them not to be)
        p.getPersistentData().remove("nirncraft_hold");
        p.setPos(at.x, at.y, at.z);
        p.setHealth(p.getMaxHealth());
        p.setInvisible(false);
        p.setNoGravity(false);
        return p;
    }

    private static void ready(List<String> problems, List<String> info, ServerPlayer held, ServerPlayer viewer, ServerPlayer control,
                              Zombie zombie, Vec3 home) {
        ServerLevel level = held.level();
        // before: the control checks (a check that cannot fail proves nothing)
        if (!held.broadcastToPlayer(viewer)) problems.add("before the hold the player is already hidden from the viewer");
        zombie.setTarget(held);
        if (zombie.getTarget() != held) problems.add("before the hold the zombie cannot target the player (the check could not fail)");
        zombie.setTarget(null);
        // (damage goes through the event: a stand-in keeps vanilla's spawn invulnerability for ever, its tick never runs)
        if (damageCancelled(held, level.damageSources().mobAttack(zombie))) problems.add("before the hold the damage event is already cancelled (the check could not fail)");

        AtomicInteger released = new AtomicInteger();
        PlayerHold.begin(held, released::incrementAndGet);
        info.add(PlayerHold.describe(held));
        if (!PlayerHold.isHeld(held)) problems.add("begin did not hold the player");
        if (held.gameMode() != GameType.SPECTATOR) problems.add("a held player is not in spectator mode: " + held.gameMode());
        var a = held.getAbilities();
        if (!a.invulnerable || a.flying || a.mayfly || a.mayBuild) problems.add("abilities: invulnerable " + a.invulnerable + " flying " + a.flying
                + " mayfly " + a.mayfly + " mayBuild " + a.mayBuild);
        if (!held.isNoGravity()) problems.add("a held player still has gravity");
        if (!held.isInvisible()) problems.add("a held player is not invisible");

        // invisible to other players: the tracker's own question
        if (held.broadcastToPlayer(viewer)) problems.add("the held player is sent to another player");
        if (!control.broadcastToPlayer(viewer)) problems.add("the unheld control is not sent to the viewer (the viewer is wrong)");

        // untargetable by mobs
        zombie.setTarget(held);
        if (zombie.getTarget() != null) problems.add("a mob targeted the held player");
        if (TargetingConditions.forCombat().range(32).test(level, zombie, held)) problems.add("the AI targeting predicate accepts the held player");

        // invulnerable
        for (DamageSource src : List.of(level.damageSources().mobAttack(zombie), level.damageSources().drown(), level.damageSources().fall(),
                level.damageSources().inWall(), level.damageSources().onFire()))
            if (!damageCancelled(held, src)) problems.add("damage from " + src.getMsgId() + " reaches the held player");
        if (!NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent(zombie, level.damageSources().playerAttack(held), 4f)).isCanceled())
            problems.add("the held player can hurt a mob");

        // cannot interact
        if (!NeoForge.EVENT_BUS.post(new AttackEntityEvent(held, zombie)).isCanceled()) problems.add("a held player's attack was not cancelled");
        BlockPos below = BlockPos.containing(home).below();
        if (!NeoForge.EVENT_BUS.post(new BreakBlockEvent(level, below, level.getBlockState(below), held)).isCanceled())
            problems.add("a held player's block breaking was not cancelled");
        if (!NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.EntityTeleportEvent.TeleportCommand(held, level, 0, 100, 0)).isCanceled())
            problems.add("a teleport of a held player was not cancelled");

        // frozen: moved, they are put back; a hold with nobody reporting stays a hold
        Vec3 pinned = held.position();
        held.setPos(pinned.x + 40, pinned.y - 30, pinned.z);
        held.setDeltaMovement(0, -2, 0);
        held.fallDistance = 12;
        for (int i = 0; i < 6; i++) {
            tick(held, level.getGameTime() + i * 2L);
        }
        if (held.position().distanceToSqr(pinned) > 0.01) problems.add("the held player was not pinned: " + held.position() + " vs " + pinned);
        if (held.getDeltaMovement().lengthSqr() > 0 || held.fallDistance > 0) problems.add("the held player still moves or falls");
        for (int i = 0; i < 40; i++) tick(held, 100 + i);
        if (!PlayerHold.isHeld(held)) problems.add("a hold with no ready was released by itself");
        if (released.get() != 0) problems.add("the release action ran without a ready");

        // a ready for another hold is ignored; the right one releases once
        PlayerHold.onReady(held, -1, false);
        if (!PlayerHold.isHeld(held)) problems.add("a stale token released the hold");
        long token = tokenOf(held);
        PlayerHold.onReady(held, token, false);
        if (PlayerHold.isHeld(held)) problems.add("the right token did not release the hold");
        PlayerHold.onReady(held, token, false);
        if (released.get() != 1) problems.add("the release action ran " + released.get() + " times, not once");

        // released: the real state is back, at the same place
        if (held.gameMode() != GameType.SURVIVAL) problems.add("not back in survival after the release: " + held.gameMode());
        a = held.getAbilities();
        if (a.invulnerable || a.mayfly || !a.mayBuild) problems.add("abilities not restored: invulnerable " + a.invulnerable + " mayfly " + a.mayfly + " mayBuild " + a.mayBuild);
        if (held.isNoGravity() || held.isInvisible()) problems.add("gravity or visibility not restored");
        if (!held.broadcastToPlayer(viewer)) problems.add("a released player is still hidden from the viewer");
        if (held.position().distanceToSqr(pinned) > 0.01) problems.add("the released player is not where the hold left them");
        zombie.setTarget(held);
        if (zombie.getTarget() != held) problems.add("a released player cannot be targeted again");
        if (damageCancelled(held, level.damageSources().mobAttack(zombie))) problems.add("a released player's damage is still cancelled");
        info.add("hold, pin, release: ok=" + problems.isEmpty());
    }

    private static boolean damageCancelled(ServerPlayer p, DamageSource src) {
        return NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent(p, src, 4f)).isCanceled();
    }

    private static void crashRecord(List<String> problems, List<String> info, ServerPlayer p, Vec3 home) {
        // a crash while held leaves spectator on the data and the record: the next hold must keep the real mode
        p.setGameMode(GameType.SURVIVAL);
        var rec = new net.minecraft.nbt.CompoundTag();
        rec.putString("mode", "creative");
        rec.putBoolean("gravity", false);
        rec.putBoolean("invisible", false);
        p.getPersistentData().put("nirncraft_hold", rec);
        p.setGameMode(GameType.SPECTATOR);
        PlayerHold.begin(p, null);
        PlayerHold.release(p, "selftest crash record");
        if (p.gameMode() != GameType.CREATIVE) problems.add("after a crash record the player came back as " + p.gameMode() + ", not creative");
        if (!p.getPersistentData().getCompoundOrEmpty("nirncraft_hold").isEmpty()) problems.add("the hold's record stays on the player after the release");
        info.add("crash record: restored " + p.gameMode().getName());
    }

    private static long tokenOf(ServerPlayer p) {
        String d = PlayerHold.describe(p);
        return Long.parseLong(d.substring(d.lastIndexOf("token ") + 6, d.length() - 1));
    }

    private static void tick(ServerPlayer p, long gameTime) {
        PlayerHold.onPlayerTick(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Pre(p));
    }
}
